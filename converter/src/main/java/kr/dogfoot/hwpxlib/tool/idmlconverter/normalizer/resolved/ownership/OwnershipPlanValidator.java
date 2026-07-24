package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.textflow.TextFlowDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTextFrame;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
        v.validateDuplicateHwpxTextOwners();
        v.validateDroppedTextFramesAreExplicitlyOwned();
        v.validateInlineFloatingSourceSplit();
        v.validateInlineFloatingTextFramePlacementSplit();
        v.validateHwpxTextNotPlacedAsCompletePng();
        v.validateTextShellBehindOwnedText();
        v.validateParentTextShellDescendantsDropped();
        v.validateParentTextShellDescendantSourcesOwned();
        v.validateParentTextShellOwnedTextFrames();
        v.validateAtomicTextlessShellHasVisibleShellOwner();
        v.validatePlannerDeclaredTextShellsNotDroppedWithoutOwner();
        v.validateTableStyleSourcesNotVisibleVisuals();
        v.validateCompositeParentDoesNotDuplicateChildShellVisuals();
        v.validateTextHiddenCompositeCarrierDoesNotDuplicateChildShellFragments();
        v.validateRawClippedImagesAreNotVisibleOwners();
        v.validateCompositeClippedImageOwnersAreSpecific();
        v.validatePlacedContentVisualsStayContentLayer();
        v.validateContentVisualSlotDoesNotBecomeBackground();
        v.validatePageBackgroundHasOnlyExplicitPlaneContract();
        v.validateBackgroundShellDoesNotOwnText();
        v.validateBackgroundDepthBand();
        v.validateRenderedPlanExactArtifactMatchPreferred();
        v.validateVisibleVisualSourcesArePageLocal();
        v.validateTextlessVisualFragmentsArePageLocal();
        v.validateInlinePngHasExplicitInlineEvidence();
        v.validateDirectInlineAnchoredTextShellsStayInline();
        v.validateVisibleInlineSourceTextShellsStayInline();
        v.validateRenderedGraphicFrameIsNotAlternatePassOwner();
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

    private void validateDuplicateHwpxTextOwners() {
        Map<Integer, LinkedHashSet<String>> byTextFrame = new LinkedHashMap<>();
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null || plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            for (int textFrameId : textFrameSourceIds(plan)) {
                byTextFrame.computeIfAbsent(textFrameId, k -> new LinkedHashSet<>())
                        .add(planRef(plan));
            }
        }
        for (Map.Entry<Integer, LinkedHashSet<String>> e : byTextFrame.entrySet()) {
            if (e.getValue().size() <= 1) continue;
            warn("STAGE4_DUPLICATE_HWPX_TEXT_OWNER",
                    "textFrameId=" + e.getKey()
                            + " owners=" + e.getValue());
        }
    }

    private void validateDroppedTextFramesAreExplicitlyOwned() {
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null || !"text_frame".equals(plan.kind)) continue;
            if (plan.textAction != TextAction.DROP_TEXT) continue;
            String reason = safe(plan.reason);
            if (reason.equals("text_slot_owned_by_text_shell")
                    || reason.equals("text_slot_owned_by_inline_text_shell")) {
                warn("STAGE4_TEXT_FRAME_DROPPED_BY_SHELL_REFERENCE",
                        "plan=" + planRef(plan));
                continue;
            }
            if (reason.contains("text_shell")
                    && !hasSeparateHwpxTextOwner(plan.domId, plan.pageIndex, plan)) {
                warn("STAGE4_DROPPED_TEXT_FRAME_WITHOUT_EXPLICIT_TEXT_OWNER",
                        "plan=" + planRef(plan));
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
            if (ShellRole.isTextShell(plan)
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
        Map<Integer, Integer> hwpxTextZByTextFrame = new LinkedHashMap<>();
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null || plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            for (int textFrameId : textFrameSourceIds(plan)) {
                Integer existing = hwpxTextZByTextFrame.get(textFrameId);
                if (existing == null || plan.zOrder > existing) {
                    hwpxTextZByTextFrame.put(textFrameId, plan.zOrder);
                }
            }
        }
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (!ShellRole.isTextShell(plan)) continue;
            if (isBackPlaneTextShell(plan)) continue;
            for (int textFrameId : textFrameSourceIds(plan)) {
                ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(String.valueOf(textFrameId));
                if (tf == null) continue;
                Integer textZ = hwpxTextZByTextFrame.get(textFrameId);
                if (textZ == null) continue;
                if (plan.zOrder >= textZ) {
                    warn("STAGE4_TEXT_SHELL_NOT_BEHIND_TEXT",
                            "shell=" + planRef(plan)
                                    + " textFrameId=" + textFrameId
                                    + " shellZ=" + plan.zOrder
                                    + " textZ=" + textZ);
                }
            }
        }
    }

    private static boolean isBackPlaneTextShell(ObjectPlan plan) {
        return ShellRole.isBackPlaneShell(plan);
    }

    private void validateParentTextShellDescendantsDropped() {
        for (ObjectPlan parent : ctx.ownershipPlans) {
            if (!ShellRole.isTextShell(parent)) continue;
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

    private void validateParentTextShellDescendantSourcesOwned() {
        for (ObjectPlan parent : ctx.ownershipPlans) {
            if (!ShellRole.isTextShell(parent)) continue;
            if (parent.descendantVisualObjectIds == null || parent.descendantVisualObjectIds.length == 0) continue;
            for (int descendantId : parent.descendantVisualObjectIds) {
                ObjectPlan child = findReferencedDescendantPlan(parent, descendantId);
                int[] childSources = child != null ? visualSourceIds(child) : new int[]{descendantId};
                if (containsAll(visualSourceIds(parent), childSources)) continue;
                warn("STAGE4_PARENT_TEXT_SHELL_DESCENDANT_SOURCE_NOT_OWNED",
                        "parent=" + planRef(parent)
                                + " descendantId=" + descendantId
                                + " child=" + planRef(child)
                                + " parentVisualSources="
                                + ObjectPlan.intArrayJson(visualSourceIds(parent))
                                + " childVisualSources="
                                + ObjectPlan.intArrayJson(childSources));
            }
        }
    }

    private void validateParentTextShellOwnedTextFrames() {
        for (ObjectPlan parent : ctx.ownershipPlans) {
            if (!ShellRole.isTextShell(parent)) continue;
            if (parent.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (parent.ownedTextFrameIds == null || parent.ownedTextFrameIds.length == 0) continue;
            for (int textFrameId : parent.ownedTextFrameIds) {
                ObjectPlan textPlan = findHwpxTextOwnerPlan(textFrameId, parent.pageIndex);
                if (!isTextFrameAccountedForByShell(textPlan)) {
                    warn("STAGE4_PARENT_TEXT_SHELL_OWNED_TEXT_MISSING",
                            "parent=" + planRef(parent)
                                    + " textFrameId=" + textFrameId);
                }
            }
        }
    }

    private void validateCompositeParentDoesNotDuplicateChildShellVisuals() {
        for (ObjectPlan parent : ctx.ownershipPlans) {
            if (!isCompleteRenderedCompositeParent(parent)) continue;
            for (ObjectPlan child : ctx.ownershipPlans) {
                if (child == null || child == parent) continue;
                if (child.pageIndex != parent.pageIndex) continue;
                if (!ShellRole.isTextShell(child)) continue;
                if (!hasVisibleVisualSlot(child)) continue;
                if (child.textAction != TextAction.DROP_TEXT) continue;
                if (child.ownedTextFrameIds != null && child.ownedTextFrameIds.length > 0) continue;
                if (hasDistinctChildShellSlotSignal(child)) continue;
                if (!compositeParentVisuallyOwnsChildSlot(parent, child)) {
                    continue;
                }
                warn("STAGE4_COMPOSITE_PARENT_CHILD_SHELL_DUPLICATE",
                        "parent=" + planRef(parent)
                                + " child=" + planRef(child));
            }
        }
    }

    private void validateTextHiddenCompositeCarrierDoesNotDuplicateChildShellFragments() {
        for (ObjectPlan carrier : ctx.ownershipPlans) {
            if (!isVisibleTextHiddenCompositeShellCarrier(carrier)) continue;
            for (ObjectPlan child : ctx.ownershipPlans) {
                if (child == null || child == carrier) continue;
                if (child.pageIndex != carrier.pageIndex) continue;
                if (!isVisibleVisualOnlyShellFragment(child)) continue;
                if (!containsAll(carrier.sourceObjectIds, child.sourceObjectIds)
                        && !contains(carrier.sourceObjectIds, child.domId)) {
                    continue;
                }
                if (!compositeCarrierHasVisibleMaterialOutsideChildShellSlots(carrier, child)) {
                    continue;
                }
                warn("STAGE4_TEXT_HIDDEN_COMPOSITE_CHILD_SHELL_DUPLICATE",
                        "carrier=" + planRef(carrier)
                                + " child=" + planRef(child));
            }
        }
    }

    private boolean isVisibleTextHiddenCompositeShellCarrier(ObjectPlan plan) {
        if (!hasVisibleVisualSlot(plan)) return false;
        if (plan.materialization != Materialization.EXTRACTED_PNG_VECTOR) return false;
        if (plan.sourceObjectIds == null || plan.sourceObjectIds.length <= 1) return false;
        String reason = safe(plan.reason);
        return reason.contains("mixed_group_text_hidden")
                || reason.contains("complex_graphic_text_hidden")
                || reason.contains("image_group_text_hidden")
                || reason.contains("composite_shell_carrier");
    }

    private boolean isVisibleVisualOnlyShellFragment(ObjectPlan plan) {
        if (!hasVisibleVisualSlot(plan)) return false;
        if (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) return false;
        if (plan.materialization != Materialization.EXTRACTED_PNG_VECTOR) return false;
        if (plan.textAction != TextAction.DROP_TEXT) return false;
        if (hasDistinctChildShellSlotSignal(plan)) return true;
        if (!"complex_graphic_text_hidden".equals(safe(plan.reason))) return false;
        if (hasPlacedContentSourceTree(plan)) return false;
        return hasVisibleShellMaterialSource(plan);
    }

    private boolean compositeCarrierHasVisibleMaterialOutsideChildShellSlots(
            ObjectPlan carrier,
            ObjectPlan child) {
        if (carrier == null || child == null || ctx.resolvedData == null) return false;
        LinkedHashSet<Integer> covered = new LinkedHashSet<>();
        for (ObjectPlan candidate : ctx.ownershipPlans) {
            if (candidate == null || candidate == carrier) continue;
            if (candidate.pageIndex != carrier.pageIndex) continue;
            if (!isVisibleVisualOnlyShellFragment(candidate)) continue;
            if (!containsAll(carrier.sourceObjectIds, candidate.sourceObjectIds)
                    && !contains(carrier.sourceObjectIds, candidate.domId)) {
                continue;
            }
            addAll(candidate.sourceObjectIds, covered);
            addAll(visualSourceIds(candidate), covered);
        }
        if (covered.isEmpty()) return false;
        for (int sourceId : visualSourceIds(carrier)) {
            if (sourceId == carrier.domId) continue;
            if (covered.contains(sourceId)) continue;
            ResolvedPageItem item = ctx.resolvedData.getPageItem(String.valueOf(sourceId));
            if (sourceItemHasVisibleShellMaterial(item)) return true;
        }
        return false;
    }

    private boolean hasVisibleShellMaterialSource(ObjectPlan plan) {
        if (plan == null || ctx.resolvedData == null) return false;
        for (int sourceId : visualSourceIds(plan)) {
            ResolvedPageItem item = ctx.resolvedData.getPageItem(String.valueOf(sourceId));
            if (sourceItemHasVisibleShellMaterial(item)) return true;
        }
        return false;
    }

    private static boolean sourceItemHasVisibleShellMaterial(ResolvedPageItem item) {
        if (item == null || item.sourceHidden() || item.hiddenByParent() || !item.visible()) return false;
        String type = safe(item.type());
        if ("Group".equals(type) || "TextFrame".equals(type)) return false;
        if ("Rectangle".equals(type) || "Oval".equals(type) || "Polygon".equals(type)) {
            return !isNoneColor(item.fillColorName())
                    || (!isNoneColor(item.strokeColorName()) && item.strokeWeight() > 0.01);
        }
        if ("GraphicLine".equals(type)) {
            return !isNoneColor(item.strokeColorName()) && item.strokeWeight() > 0.01;
        }
        return true;
    }

    private void validateAtomicTextlessShellHasVisibleShellOwner() {
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (!isDroppedAtomicTextlessShellWithHwpxText(plan)) continue;
            if (hasVisibleTextShellOwnerForAtomicShell(plan)) continue;
            warn("STAGE4_ATOMIC_TEXTLESS_SHELL_DROPPED",
                    "plan=" + planRef(plan)
                            + " sourceObjectIds=" + ObjectPlan.intArrayJson(plan.sourceObjectIds)
                            + " textFrameIds=" + ObjectPlan.intArrayJson(textFrameSourceIds(plan)));
        }
    }

    private boolean isDroppedAtomicTextlessShellWithHwpxText(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.visualAction != VisualAction.DROP_VISUAL) return false;
        if (plan.file == null || plan.file.isEmpty()) return false;
        String reason = safe(plan.reason);
        if (!"atomic_ownership_root_text_hidden_shell".equals(reason)
                && !"leaf_group_text_hidden_shell".equals(reason)) {
            return false;
        }
        return textFrameSourceIds(plan).length > 0;
    }

    private boolean hasVisibleTextShellOwnerForAtomicShell(ObjectPlan droppedShell) {
        if (droppedShell == null) return false;
        for (ObjectPlan candidate : ctx.ownershipPlans) {
            if (candidate == null || candidate == droppedShell) continue;
            if (candidate.pageIndex != droppedShell.pageIndex) continue;
            if (!ShellRole.isTextShell(candidate)) continue;
            if (candidate.domId == droppedShell.domId || candidate.renderId == droppedShell.renderId) {
                return true;
            }
            if (containsAny(candidate.sourceObjectIds, droppedShell.sourceObjectIds)
                    && containsAny(candidate.ownedTextFrameIds, textFrameSourceIds(droppedShell))) {
                return true;
            }
        }
        return false;
    }

    private void validatePlannerDeclaredTextShellsNotDroppedWithoutOwner() {
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (!isDroppedPlannerDeclaredTextShellContract(plan)) continue;
            if (hasVisiblePlannerDeclaredTextShellAlternative(plan)) continue;
            warn("STAGE4_PLANNER_DECLARED_TEXT_SHELL_DROPPED_WITHOUT_OWNER",
                    "plan=" + planRef(plan)
                            + " sourceObjectIds=" + ObjectPlan.intArrayJson(plan.sourceObjectIds)
                            + " textFrameIds=" + ObjectPlan.intArrayJson(textFrameSourceIds(plan)));
        }
    }

    private boolean isDroppedPlannerDeclaredTextShellContract(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.visualAction != VisualAction.DROP_VISUAL) return false;
        if (!safe(plan.kind).startsWith("planner_declared_rendered:")) return false;
        if (!"planner_declared_object_plan".equals(safe(plan.reason))) return false;
        if (plan.materialization != Materialization.EXTRACTED_PNG_VECTOR) return false;
        if (!"direct_child_shell_slot".equals(safe(plan.slotRole))) return false;
        if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) return false;
        return hasVisibleShellMaterialSource(plan);
    }

    private boolean hasVisiblePlannerDeclaredTextShellAlternative(ObjectPlan droppedShell) {
        if (droppedShell == null) return false;
        for (ObjectPlan candidate : ctx.ownershipPlans) {
            if (candidate == null || candidate == droppedShell) continue;
            if (candidate.pageIndex != droppedShell.pageIndex) continue;
            if (!candidate.hasVisibleVisual()) continue;
            if (!ShellRole.isTextShell(candidate)) continue;
            if (!containsAny(candidate.ownedTextFrameIds, droppedShell.ownedTextFrameIds)
                    && !containsAny(candidate.sourceObjectIds, droppedShell.ownedTextFrameIds)) {
                continue;
            }
            if (candidate.renderId != null && candidate.renderId.equals(droppedShell.renderId)) return true;
            if (containsAny(candidate.sourceObjectIds, droppedShell.sourceObjectIds)) return true;
            if (containsAny(visualSourceIds(candidate), visualSourceIds(droppedShell))) return true;
        }
        return false;
    }

    private void validateTableStyleSourcesNotVisibleVisuals() {
        Map<Integer, LinkedHashSet<Integer>> tableSourcesByPage = new LinkedHashMap<>();
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null || plan.visualAction != VisualAction.PLACE_TABLE_STYLE) continue;
            LinkedHashSet<Integer> ids = tableSourcesByPage.computeIfAbsent(
                    plan.pageIndex, k -> new LinkedHashSet<>());
            addAll(plan.sourceObjectIds, ids);
            addAll(plan.visualSourceObjectIds, ids);
            addAll(plan.styleSourceObjectIds, ids);
        }
        if (tableSourcesByPage.isEmpty()) return;

        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null || !hasVisibleVisualSlot(plan)) continue;
            if (plan.visualAction == VisualAction.PLACE_TABLE_STYLE) continue;
            LinkedHashSet<Integer> tableSources = tableSourcesByPage.get(plan.pageIndex);
            if (tableSources == null || tableSources.isEmpty()) continue;
            for (int sourceId : visualSourceIds(plan)) {
                if (!tableSources.contains(sourceId)) continue;
                warn("STAGE4_TABLE_STYLE_SOURCE_VISIBLE_AS_VISUAL",
                        "sourceId=" + sourceId
                                + " plan=" + planRef(plan));
            }
        }
    }

    private void validateRawClippedImagesAreNotVisibleOwners() {
        if (ctx.resolvedData == null) return;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (!hasVisibleVisualSlot(plan)) continue;
            if (plan.visualAction != VisualAction.PLACE_FLOATING_PNG
                    && plan.visualAction != VisualAction.PLACE_INLINE_PNG) {
                continue;
            }
            for (int sourceId : visualSourceIds(plan)) {
                int clipParentId = rawClippedImageParentId(sourceId, plan);
                if (clipParentId < 0) continue;
                warn("STAGE4_RAW_CLIPPED_IMAGE_VISIBLE",
                        "plan=" + planRef(plan)
                                + " imageId=" + sourceId
                                + " clipParentId=" + clipParentId
                                + " pageIndex=" + plan.pageIndex);
            }
        }
    }

    private void validateCompositeClippedImageOwnersAreSpecific() {
        if (ctx.resolvedData == null) return;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (!hasVisibleVisualSlot(plan)) continue;
            if (plan.visualAction != VisualAction.PLACE_FLOATING_PNG
                    && plan.visualAction != VisualAction.PLACE_INLINE_PNG) {
                continue;
            }
            int[] visualSources = visualSourceIds(plan);
            if (visualSources.length <= 2) continue;
            for (int sourceId : visualSources) {
                int clipParentId = clippedImageParentIdAllowOwned(sourceId);
                if (clipParentId < 0) continue;
                if (!contains(plan.sourceObjectIds, clipParentId)
                        && !contains(visualSources, clipParentId)) {
                    continue;
                }
                ObjectPlan smallerOwner = findSmallerClipCarryingPlan(
                        plan, sourceId, clipParentId);
                if (smallerOwner == null) continue;
                warn("STAGE4_CLIPPED_IMAGE_OWNER_TOO_BROAD",
                        "plan=" + planRef(plan)
                                + " imageId=" + sourceId
                                + " clipParentId=" + clipParentId
                                + " smallerOwner=" + planRef(smallerOwner)
                                + " pageIndex=" + plan.pageIndex);
            }
        }
    }

    private void validatePlacedContentVisualsStayContentLayer() {
        if (ctx.resolvedData == null) return;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (!hasVisibleVisualSlot(plan)) continue;
            if (plan.visualAction != VisualAction.PLACE_FLOATING_PNG
                    && plan.visualAction != VisualAction.PLACE_INLINE_PNG) {
                continue;
            }
            if (!hasPlacedContentSourceTree(plan)) continue;
            if (hasVisibleShellRootWithPlacedContentTree(plan)) continue;
            if (isBackgroundSourceLayer(plan)) continue;
            if (plan.visualPolicyLayer() == PolicyLayer.CONTENT
                    && (plan.visualLayer == VisualLayer.CONTENT_VISUAL
                    || plan.visualLayer == VisualLayer.CONTENT_BACKDROP)) {
                continue;
            }
            if (plan.visualPolicyLayer() == PolicyLayer.DECORATION
                    && (plan.visualLayer == VisualLayer.CONTAINER_BACKDROP
                    || plan.visualLayer == VisualLayer.CONTAINER_FACE
                    || plan.visualLayer == VisualLayer.TEXT_CARD_BACKDROP
                    || plan.visualLayer == VisualLayer.LABEL_CONNECTOR_BACKDROP
                    || plan.visualLayer == VisualLayer.LABEL_BACKDROP
                    || plan.visualLayer == VisualLayer.LABEL_OVERLAY_BACKDROP
                    || plan.visualLayer == VisualLayer.CONTAINER_OUTLINE
                    || plan.visualLayer == VisualLayer.FOREGROUND_MASK)) {
                continue;
            }
            if (plan.visualPolicyLayer() == PolicyLayer.BACKGROUND
                    && plan.visualLayer == VisualLayer.PAGE_BACKGROUND) {
                continue;
            }
            warn("STAGE4_PLACED_CONTENT_UNEXPECTED_LAYER",
                    "plan=" + planRef(plan)
                            + " visualLayer=" + plan.visualLayer
                            + " policyLayer=" + plan.visualPolicyLayer());
        }
    }

    private void validateContentVisualSlotDoesNotBecomeBackground() {
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null || !hasVisibleVisualSlot(plan)) continue;
            if (!isContentVisualSlotPlan(plan)) continue;
            if (isExplicitPageBackdropContract(plan)) continue;
            if (isBackgroundSourceLayer(plan)) continue;
            if (ctx.resolvedData != null && hasVisibleShellRootWithPlacedContentTree(plan)) continue;
            if (ctx.resolvedData != null && isSourceAuthoredPageWashBackdropContract(plan)) continue;
            if (isMasterGraphicBackgroundFragmentContract(plan)) continue;
            if (plan.visualLayer != VisualLayer.PAGE_BACKGROUND
                    && plan.visualPolicyLayer() != PolicyLayer.BACKGROUND) {
                continue;
            }
            warn("STAGE4_CONTENT_VISUAL_SLOT_IN_BACKGROUND_PLANE",
                    "plan=" + planRef(plan)
                            + " visualLayer=" + plan.visualLayer
                            + " policyLayer=" + plan.visualPolicyLayer()
                            + " slotRole=" + safe(plan.slotRole));
        }
    }

    private void validateBackgroundShellDoesNotOwnText() {
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null) continue;
            if (!hasVisibleVisualSlot(plan)) continue;
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (!ShellRole.isBackgroundShell(plan)) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            warn("STAGE4_BACKGROUND_SHELL_OWNS_TEXT",
                    "plan=" + planRef(plan)
                            + " visualLayer=" + plan.visualLayer
                            + " policyLayer=" + plan.visualPolicyLayer());
        }
    }

    private void validatePageBackgroundHasOnlyExplicitPlaneContract() {
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null || !hasVisibleVisualSlot(plan)) continue;
            if (plan.visualLayer != VisualLayer.PAGE_BACKGROUND) continue;
            if (isExplicitPageBackgroundPlaneContract(plan)) continue;

            warn("STAGE4_PAGE_BACKGROUND_FORBIDDEN_NON_PLANE",
                    "plan=" + planRef(plan)
                            + " visualLayer=" + plan.visualLayer
                            + " slotRole=" + safe(plan.slotRole)
                            + " reason=" + safe(plan.reason)
                            + " placement=" + plan.placement
                            + " materialization=" + plan.materialization);
        }
    }

    private static boolean isExplicitPageBackgroundPlaneContract(ObjectPlan plan) {
        if (plan == null) return false;
        String slotRole = safe(plan.slotRole);
        if (!"page_background_plane".equals(slotRole)) {
            return false;
        }
        if (plan.materialization == Materialization.PAGE_PLANE_PNG) return true;
        String candidate = safe(plan.candidateId);
        String reason = safe(plan.reason);
        return candidate.contains("page_background_plane")
                || candidate.contains("source_backed_page_background_plane")
                || reason.contains("page_background_plane")
                || reason.contains("PAGE_BACKGROUND_PLANE")
                || reason.contains("source_backed_page_background_plane");
    }

    private void validateBackgroundDepthBand() {
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null || !hasVisibleVisualSlot(plan)) continue;
            if (isExplicitPageBackgroundPlaneContract(plan)) continue;
            if (plan.visualLayer == VisualLayer.PAGE_BACKGROUND
                    && !VisualPlanePolicy.isBackgroundZOrder(plan.zOrder)) {
                warn("STAGE4_PAGE_BACKGROUND_NOT_BOTTOM_Z",
                        "plan=" + planRef(plan)
                                + " visualLayer=" + plan.visualLayer
                                + " zOrder=" + plan.zOrder);
            }
        }
    }

    private void validateRenderedPlanExactArtifactMatchPreferred() {
        if (ctx.resolvedData == null || ctx.resolvedData.allRenderedFloatingItems() == null) return;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup rg
                : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg == null) continue;
            ObjectPlan exact = findExactRenderedArtifactPlan(rg);
            if (exact == null) continue;
            ObjectPlan matched = ctx.findOwnershipPlanForRendered(rg);
            if (matched == null || matched == exact) continue;
            if (sameRenderedArtifact(matched, rg)) continue;
            warn("STAGE4_RENDERED_PLAN_ARTIFACT_MISMATCH",
                    "renderedId=" + rg.id()
                            + " renderedFile=" + safe(rg.file())
                            + " exact=" + planRef(exact)
                            + " matched=" + planRef(matched));
        }
    }

    private ObjectPlan findExactRenderedArtifactPlan(
            kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup rg) {
        if (rg == null) return null;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null || plan.renderId == null) continue;
            if (plan.pageIndex != rg.pageIndex()) continue;
            if (plan.renderId.intValue() != rg.id()) continue;
            if (!safe(plan.file).equals(safe(rg.file()))) continue;
            return plan;
        }
        return null;
    }

    private static boolean sameRenderedArtifact(
            ObjectPlan plan,
            kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup rg) {
        return plan != null
                && rg != null
                && plan.renderId != null
                && plan.renderId.intValue() == rg.id()
                && safe(plan.file).equals(safe(rg.file()));
    }

    private void validateVisibleVisualSourcesArePageLocal() {
        if (ctx.resolvedData == null) return;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (!hasVisibleVisualSlot(plan)) continue;
            for (int sourceId : visualSourceIds(plan)) {
                ResolvedPageItem item = ctx.resolvedData.getPageItem(String.valueOf(sourceId));
                if (item == null || item.pageIndex() < 0) continue;
                if (item.pageIndex() == plan.pageIndex) continue;
                if (boundsOverlap(pageBounds(plan.pageIndex), boundsOf(item))) continue;
                warn("STAGE4_VISIBLE_VISUAL_SOURCE_NOT_PAGE_LOCAL",
                        "plan=" + planRef(plan)
                                + " sourceId=" + sourceId
                                + " sourcePageIndex=" + item.pageIndex()
                                + " planPageIndex=" + plan.pageIndex);
            }
        }
    }

    private void validateTextlessVisualFragmentsArePageLocal() {
        if (ctx.resolvedData == null || ctx.resolvedData.pages() == null) return;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null || !hasVisibleVisualSlot(plan)) continue;
            if (plan.bounds == null || plan.bounds.length < 4) continue;
            double pageWidth = pageWidth(plan.pageIndex);
            double pageHeight = pageHeight(plan.pageIndex);
            if (pageWidth <= 0.0 || pageHeight <= 0.0) continue;
            if (plan.materialization == Materialization.TEXTLESS_VISUAL_FRAGMENT) {
                continue;
            }
            if (isExplicitPageBackgroundPlaneContract(plan)) {
                continue;
            }
            if (plan.visualPolicyLayer() == PolicyLayer.BACKGROUND
                    && plan.placement == Placement.FLOATING
                    && plan.materialization != Materialization.TEXTLESS_VISUAL_FRAGMENT
                    && hasCrossPageBackgroundSource(plan)) {
                warn("STAGE4_CROSS_PAGE_BACKGROUND_WITHOUT_FRAGMENT",
                        "plan=" + planRef(plan)
                                + " materialization=" + plan.materialization
                                + " pageWidth=" + pageWidth
                                + " pageHeight=" + pageHeight);
            }
        }
    }

    private boolean hasCrossPageBackgroundSource(ObjectPlan plan) {
        if (plan == null || ctx.resolvedData == null) return false;
        double[] targetPage = pageBounds(plan.pageIndex);
        if (targetPage == null || targetPage.length < 4) return false;
        for (int sourceId : visualSourceIds(plan)) {
            ResolvedPageItem item = ctx.resolvedData.getPageItem(String.valueOf(sourceId));
            if (item == null || item.pageIndex() < 0) continue;
            if (item.pageIndex() == plan.pageIndex) continue;
            if (boundsOverlap(targetPage, boundsOf(item))) return true;
        }
        return false;
    }

    private void validateInlinePngHasExplicitInlineEvidence() {
        if (ctx.resolvedData == null) return;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null) continue;
            if (plan.placement != Placement.INLINE) continue;
            if (plan.visualAction != VisualAction.PLACE_INLINE_PNG) continue;
            InlineSourceEvidence evidence = inlineSourceEvidence(plan);
            if (!evidence.checked || evidence.inline) continue;
            warn("STAGE4_INLINE_PNG_WITHOUT_INLINE_SOURCE_EVIDENCE",
                    "plan=" + planRef(plan)
                            + " checkedSourceIds=" + evidence.checkedIds);
        }
    }

    private void validateDirectInlineAnchoredTextShellsStayInline() {
        if (ctx.resolvedData == null) return;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null) continue;
            if (!ShellRole.isTextShell(plan)) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) continue;
            if (!allOwnedTextFramesAreInline(plan)) continue;
            AnchorCarrier carrier = carrierForAnchor(plan.domId);
            if (!anchorCarrierParagraphHasVisibleText(carrier)
                    && !idmlInlineAnchorParagraphHasVisibleText(plan.domId)) {
                continue;
            }
            if (plan.placement == Placement.INLINE
                    && plan.coordinateSpace == CoordinateSpace.STORY_FLOW) {
                continue;
            }
            warn("STAGE4_DIRECT_INLINE_ANCHORED_TEXT_SHELL_NOT_INLINE",
                    "plan=" + planRef(plan)
                            + " anchorId=" + carrier.anchorId);
        }
    }

    private void validateVisibleInlineSourceTextShellsStayInline() {
        if (ctx.resolvedData == null) return;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null) continue;
            if (!ShellRole.isTextShell(plan)) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (!hasVisibleVisualSlot(plan)) continue;
            if (!allOwnedTextFramesAreInline(plan)) continue;
            if (!hasInlineSourceObject(plan)) continue;
            if (isTableCellAnchoredExternalLabelShell(plan)) continue;
            if (plan.placement == Placement.INLINE
                    && plan.coordinateSpace == CoordinateSpace.STORY_FLOW) {
                continue;
            }
            warn("STAGE4_INLINE_SOURCE_TEXT_SHELL_NOT_INLINE",
                    "plan=" + planRef(plan));
        }
    }

    private void validateRenderedGraphicFrameIsNotAlternatePassOwner() {
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null || !hasVisibleVisualSlot(plan)) continue;
            if (!safe(plan.kind).startsWith("rendered_graphic_frame:")) continue;
            ObjectPlan canonical = findCanonicalNonGraphicRenderedChannelForSameSlot(plan);
            if (canonical == null) continue;
            warn("STAGE4_RENDERED_GRAPHIC_FRAME_ALTERNATE_PASS_VISIBLE",
                    "graphic=" + planRef(plan)
                            + " canonical=" + planRef(canonical));
        }
    }

    private ObjectPlan findCanonicalNonGraphicRenderedChannelForSameSlot(ObjectPlan graphicPlan) {
        if (graphicPlan == null) return null;
        for (ObjectPlan candidate : ctx.ownershipPlans) {
            if (candidate == null || candidate == graphicPlan) continue;
            if (candidate.pageIndex != graphicPlan.pageIndex) continue;
            if (!isCanonicalNonGraphicRenderedChannel(candidate)) continue;
            if (sameRenderedSlot(candidate, graphicPlan)) return candidate;
        }
        return null;
    }

    private static boolean isCanonicalNonGraphicRenderedChannel(ObjectPlan plan) {
        if (plan == null) return false;
        String kind = safe(plan.kind);
        return kind.startsWith("rendered_floating_item:")
                || kind.startsWith("rendered_image_frame:")
                || kind.startsWith("rendered_pdf_frame:");
    }

    private static boolean sameRenderedSlot(ObjectPlan a, ObjectPlan b) {
        if (a == null || b == null) return false;
        String ak = safe(a.sourceBundleKey);
        String bk = safe(b.sourceBundleKey);
        if (!ak.isEmpty() && ak.equals(bk)) return true;
        int[] av = visualSourceIds(a);
        int[] bv = visualSourceIds(b);
        if (av.length > 0 && bv.length > 0 && containsAll(av, bv) && containsAll(bv, av)) return true;
        return a.sourceObjectIds != null && b.sourceObjectIds != null
                && a.sourceObjectIds.length > 0 && b.sourceObjectIds.length > 0
                && containsAll(a.sourceObjectIds, b.sourceObjectIds)
                && containsAll(b.sourceObjectIds, a.sourceObjectIds);
    }

    private double pageWidth(int pageIndex) {
        double[] bounds = pageBounds(pageIndex);
        if (bounds == null || bounds.length < 4) return -1.0;
        return (bounds[3] - bounds[1]) / safeScaleFactor();
    }

    private double pageHeight(int pageIndex) {
        double[] bounds = pageBounds(pageIndex);
        if (bounds == null || bounds.length < 4) return -1.0;
        return (bounds[2] - bounds[0]) / safeScaleFactor();
    }

    private double[] pageBounds(int pageIndex) {
        if (ctx.resolvedData == null || ctx.resolvedData.pages() == null) return null;
        if (pageIndex < 0 || pageIndex >= ctx.resolvedData.pages().size()) return null;
        return ctx.resolvedData.pages().get(pageIndex).bounds();
    }

    private double safeScaleFactor() {
        return ctx.scaleFactor != 0.0 ? ctx.scaleFactor : 1.0;
    }

    private boolean allOwnedTextFramesAreInline(ObjectPlan plan) {
        if (plan == null || plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) {
            return false;
        }
        for (int textFrameId : plan.ownedTextFrameIds) {
            ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(String.valueOf(textFrameId));
            if (tf == null || !tf.isInline()) return false;
        }
        return true;
    }

    private boolean hasInlineSourceObject(ObjectPlan plan) {
        if (plan == null || ctx.resolvedData == null) return false;
        if (isInlinePageItem(plan.domId)) return true;
        if (plan.sourceObjectIds != null) {
            for (int sourceId : plan.sourceObjectIds) {
                if (isInlinePageItem(sourceId)) return true;
            }
        }
        for (int sourceId : visualSourceIds(plan)) {
            if (isInlinePageItem(sourceId)) return true;
        }
        return false;
    }

    private boolean isInlinePageItem(int sourceId) {
        if (sourceId < 0 || ctx.resolvedData == null) return false;
        ResolvedPageItem item = ctx.resolvedData.getPageItem(String.valueOf(sourceId));
        return item != null && item.isInline();
    }

    private boolean isTableCellAnchoredExternalLabelShell(ObjectPlan plan) {
        if (plan == null || ctx.resolvedData == null || plan.domId < 0) return false;
        if (!ShellRole.isTextShell(plan)) return false;
        if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) return false;
        ResolvedTable table = resolvedTableContainingInlineAnchor(plan.domId);
        if (table == null) return false;
        ResolvedTable.Cell cell = resolvedCellContainingInlineAnchor(table, plan.domId);
        if (cell == null || !cell.hasTextRuns()) return false;
        return sourceBoundsOutsideTable(plan.bounds, table.bounds());
    }

    private ResolvedTable resolvedTableContainingInlineAnchor(int domId) {
        if (ctx.resolvedData == null || ctx.resolvedData.tables() == null) return null;
        for (ResolvedTable table : ctx.resolvedData.tables()) {
            if (resolvedCellContainingInlineAnchor(table, domId) != null) {
                return table;
            }
        }
        return null;
    }

    private static ResolvedTable.Cell resolvedCellContainingInlineAnchor(
            ResolvedTable table,
            int domId) {
        if (table == null || table.cells() == null) return null;
        for (ResolvedTable.Cell cell : table.cells()) {
            if (cell == null || cell.inlineAnchorIds() == null) continue;
            for (Integer id : cell.inlineAnchorIds()) {
                if (id != null && id == domId) return cell;
            }
        }
        return null;
    }

    private static boolean sourceBoundsOutsideTable(double[] sourceBounds, double[] tableBounds) {
        if (!validPlacementBounds(sourceBounds) || !validPlacementBounds(tableBounds)) return false;
        double tolerance = 0.5;
        double sourceCenterX = (sourceBounds[1] + sourceBounds[3]) * 0.5;
        double sourceCenterY = (sourceBounds[0] + sourceBounds[2]) * 0.5;
        return sourceCenterX < tableBounds[1] - tolerance
                || sourceCenterX > tableBounds[3] + tolerance
                || sourceCenterY < tableBounds[0] - tolerance
                || sourceCenterY > tableBounds[2] + tolerance;
    }

    private static boolean validPlacementBounds(double[] bounds) {
        return bounds != null
                && bounds.length >= 4
                && bounds[2] > bounds[0]
                && bounds[3] > bounds[1];
    }

    private AnchorCarrier carrierForAnchor(int anchoredObjectId) {
        if (ctx.resolvedData == null || anchoredObjectId < 0 || ctx.resolvedData.stories() == null) {
            return null;
        }
        if (ctx.textFlowDocument != null) {
            kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.textflow.TextFlowDocument.InlineSlotCarrier carrier =
                    ctx.textFlowDocument.inlineSlotCarrier(anchoredObjectId);
            if (carrier != null) {
                ResolvedParagraph paragraph = carrier.paragraph != null ? carrier.paragraph.sourceParagraph : null;
                return new AnchorCarrier(anchoredObjectId, paragraph, carrier.paragraph);
            }
        }
        for (ResolvedStory story : ctx.resolvedData.stories()) {
            if (story == null || story.paragraphs() == null) continue;
            for (ResolvedParagraph paragraph : story.paragraphs()) {
                if (paragraph == null || paragraph.runs() == null) continue;
                for (ResolvedRun run : paragraph.runs()) {
                    if (run == null || !run.isInlineAnchor()) continue;
                    Integer anchoredId = run.anchoredObjectId();
                    if (anchoredId != null && anchoredId == anchoredObjectId) {
                        return new AnchorCarrier(anchoredObjectId, paragraph);
                    }
                }
            }
        }
        return null;
    }

    private boolean idmlInlineAnchorParagraphHasVisibleText(int domId) {
        if (ctx.loadIDMLStory == null || ctx.resolvedData == null || ctx.resolvedData.textFrames() == null) {
            return false;
        }
        LinkedHashSet<String> visitedStoryIds = new LinkedHashSet<>();
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            if (tf == null || tf.storyId() == null || tf.storyId().isEmpty()) continue;
            if (!visitedStoryIds.add(tf.storyId())) continue;
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory story =
                    ctx.loadIDMLStory.apply(tf.storyId());
            if (idmlStoryInlineAnchorParagraphHasVisibleText(story, domId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean idmlStoryInlineAnchorParagraphHasVisibleText(
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory story,
            int domId) {
        if (story == null) return false;
        if (story.paragraphs() != null) {
            for (IDMLParagraph paragraph : story.paragraphs()) {
                if (idmlParagraphInlineAnchorHasVisibleText(paragraph, domId)) return true;
            }
        }
        if (story.tables() == null) return false;
        for (IDMLTable table : story.tables()) {
            if (table == null || table.rows() == null) continue;
            for (IDMLTableRow row : table.rows()) {
                if (row == null || row.cells() == null) continue;
                for (IDMLTableCell cell : row.cells()) {
                    if (cell == null || cell.paragraphs() == null) continue;
                    for (IDMLParagraph paragraph : cell.paragraphs()) {
                        if (idmlParagraphInlineAnchorHasVisibleText(paragraph, domId)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean idmlParagraphInlineAnchorHasVisibleText(IDMLParagraph paragraph, int domId) {
        if (paragraph == null || paragraph.characterRuns() == null) return false;
        boolean sawAnchor = false;
        for (IDMLCharacterRun run : paragraph.characterRuns()) {
            if (run == null) continue;
            if (run.inlineAnchors() != null && !run.inlineAnchors().isEmpty()) {
                for (IDMLCharacterRun.InlineAnchor anchor : run.inlineAnchors()) {
                    if (idmlInlineAnchorDomId(run, anchor) == domId) {
                        sawAnchor = true;
                        break;
                    }
                }
            }
            if (sawAnchor && !normalizeResolvedVisibleText(run.content()).isEmpty()) {
                return true;
            }
        }
        if (!sawAnchor) return false;
        return !normalizeResolvedVisibleText(paragraph.getPlainText()).isEmpty();
    }

    private static int idmlInlineAnchorDomId(IDMLCharacterRun run, IDMLCharacterRun.InlineAnchor anchor) {
        if (run == null || anchor == null) return -1;
        if (anchor.type() == IDMLCharacterRun.InlineAnchorType.FRAME) {
            if (run.inlineFrames() == null || anchor.index() < 0 || anchor.index() >= run.inlineFrames().size()) {
                return -1;
            }
            IDMLTextFrame frame = run.inlineFrames().get(anchor.index());
            return parseInt(frame != null ? frame.selfId() : null, -1);
        }
        if (anchor.type() == IDMLCharacterRun.InlineAnchorType.GRAPHIC) {
            if (run.inlineGraphics() == null || anchor.index() < 0 || anchor.index() >= run.inlineGraphics().size()) {
                return -1;
            }
            IDMLCharacterRun.InlineGraphic graphic = run.inlineGraphics().get(anchor.index());
            return parseInt(graphic != null ? graphic.selfId() : null, -1);
        }
        return -1;
    }

    private static boolean anchorCarrierParagraphHasVisibleText(AnchorCarrier carrier) {
        Boolean textFlowResult = anchorCarrierTextFlowParagraphHasVisibleText(carrier);
        if (textFlowResult != null) return textFlowResult;
        if (carrier == null || carrier.paragraph == null || carrier.paragraph.runs() == null) {
            return false;
        }
        boolean sawCarrierAnchor = false;
        for (ResolvedRun run : carrier.paragraph.runs()) {
            if (run == null) continue;
            if (run.isInlineAnchor()) {
                Integer anchoredId = run.anchoredObjectId();
                if (anchoredId != null && anchoredId == carrier.anchorId) {
                    sawCarrierAnchor = true;
                }
                continue;
            }
            if (!normalizeResolvedVisibleText(run.text()).isEmpty()) {
                return sawCarrierAnchor;
            }
        }
        return false;
    }

    private static Boolean anchorCarrierTextFlowParagraphHasVisibleText(AnchorCarrier carrier) {
        if (carrier == null || carrier.textFlowParagraph == null || carrier.textFlowParagraph.atoms == null) {
            return null;
        }
        boolean sawCarrierAnchor = false;
        for (TextFlowDocument.TextFlowAtom atom : carrier.textFlowParagraph.atoms) {
            if (atom == null) continue;
            if (atom instanceof TextFlowDocument.InlineSlotAtom) {
                TextFlowDocument.InlineSlotAtom slot = (TextFlowDocument.InlineSlotAtom) atom;
                if (slot.anchoredObjectId != null && slot.anchoredObjectId == carrier.anchorId) {
                    sawCarrierAnchor = true;
                }
                continue;
            }
            if (atom instanceof TextFlowDocument.TextAtom) {
                TextFlowDocument.TextAtom textAtom = (TextFlowDocument.TextAtom) atom;
                if (!normalizeResolvedVisibleText(textAtom.text).isEmpty()) {
                    return sawCarrierAnchor;
                }
            }
        }
        return false;
    }


    private int rawClippedImageParentId(int sourceId, ObjectPlan plan) {
        ResolvedPageItem image = ctx.resolvedData.getPageItem(String.valueOf(sourceId));
        if (image == null || !"Image".equals(safe(image.type()))) return -1;
        if (image.parentId() == null || image.parentId().isBlank()) return -1;
        ResolvedPageItem parent = ctx.resolvedData.getPageItem(image.parentId());
        if (parent == null || !isClippingImageParent(parent, image)) return -1;
        int parentId = parseInt(parent.id(), -1);
        if (parentId < 0) return -1;
        if (contains(plan.sourceObjectIds, parentId) || contains(visualSourceIds(plan), parentId)) {
            return -1;
        }
        return parentId;
    }

    private int clippedImageParentIdAllowOwned(int sourceId) {
        ResolvedPageItem image = ctx.resolvedData.getPageItem(String.valueOf(sourceId));
        if (image == null || !"Image".equals(safe(image.type()))) return -1;
        if (image.parentId() == null || image.parentId().isBlank()) return -1;
        ResolvedPageItem parent = ctx.resolvedData.getPageItem(image.parentId());
        if (parent == null || !isClippingImageParent(parent, image)) return -1;
        return parseInt(parent.id(), -1);
    }

    private ObjectPlan findSmallerClipCarryingPlan(
            ObjectPlan owner,
            int imageId,
            int clipParentId) {
        ObjectPlan best = null;
        int ownerSources = sourceCount(owner);
        for (ObjectPlan candidate : ctx.ownershipPlans) {
            if (candidate == null || candidate == owner) continue;
            if (candidate.pageIndex != owner.pageIndex) continue;
            if (sourceCount(candidate) >= ownerSources) continue;
            if (!contains(candidate.sourceObjectIds, imageId)
                    && !contains(visualSourceIds(candidate), imageId)) {
                continue;
            }
            if (!contains(candidate.sourceObjectIds, clipParentId)
                    && !contains(visualSourceIds(candidate), clipParentId)) {
                continue;
            }
            if (clipOwnerTreeDistance(candidate, clipParentId) < 0) continue;
            if (best == null || sourceCount(candidate) < sourceCount(best)) {
                best = candidate;
            }
        }
        return best;
    }

    private static int sourceCount(ObjectPlan plan) {
        return plan != null && plan.sourceObjectIds != null
                ? plan.sourceObjectIds.length
                : 0;
    }

    private static boolean isClippingImageParent(ResolvedPageItem parent, ResolvedPageItem image) {
        if (parent == null || image == null) return false;
        String type = safe(parent.type());
        if (!"Oval".equals(type) && !"Polygon".equals(type) && !"Rectangle".equals(type)) {
            return false;
        }
        if ("Oval".equals(type) || "Polygon".equals(type) || parent.clipContent()) {
            return true;
        }
        return !boundsContains(boundsOf(parent), boundsOf(image), 0.25);
    }

    private int clipOwnerTreeDistance(ObjectPlan candidate, int clipParentId) {
        if (candidate == null || ctx.resolvedData == null || clipParentId < 0) return -1;
        int candidateId = candidate.domId >= 0
                ? candidate.domId
                : (candidate.renderId != null ? candidate.renderId.intValue() : -1);
        if (candidateId < 0) return -1;
        if (candidateId == clipParentId) return 0;

        int distance = 1;
        String current = String.valueOf(clipParentId);
        HashSet<String> visited = new HashSet<>();
        while (current != null && !current.isBlank() && visited.add(current)) {
            ResolvedPageItem item = ctx.resolvedData.getPageItem(current);
            if (item == null || item.parentId() == null || item.parentId().isBlank()) {
                return -1;
            }
            int parentId = parseInt(item.parentId(), -1);
            if (parentId < 0) return -1;
            if (parentId == candidateId) return distance;
            current = item.parentId();
            distance++;
        }
        return -1;
    }

    private boolean hasPlacedContentSourceTree(ObjectPlan plan) {
        if (plan == null || ctx.resolvedData == null) return false;
        LinkedHashSet<Integer> sourceIds = new LinkedHashSet<>();
        if (plan.sourceObjectIds != null) {
            for (int id : plan.sourceObjectIds) sourceIds.add(id);
        }
        if (plan.visualSourceObjectIds != null) {
            for (int id : plan.visualSourceObjectIds) sourceIds.add(id);
        }
        for (int sourceId : sourceIds) {
            if (hasPlacedContentSourceTree(sourceId)) return true;
        }
        return false;
    }

    private static boolean isContentVisualSlotPlan(ObjectPlan plan) {
        if (plan == null) return false;
        if ("CONTENT_VISUAL_SLOT".equals(safe(plan.slotRole))) return true;
        String candidateId = safe(plan.candidateId);
        return candidateId.contains("CONTENT_VISUAL_SLOT")
                || candidateId.contains(".content_visual_slot")
                || candidateId.endsWith("content_visual_slot");
    }

    private static boolean isExplicitPageBackdropContract(ObjectPlan plan) {
        String reason = safe(plan != null ? plan.reason : null);
        return reason.equals("page_spanning_backdrop_visual")
                || reason.equals("page_spanning_backdrop_visual_fragment");
    }

    private boolean isSourceAuthoredPageWashBackdropContract(ObjectPlan plan) {
        if (plan == null || ctx.resolvedData == null) return false;
        if (plan.visualAction != VisualAction.PLACE_FLOATING_PNG
                && plan.visualAction != VisualAction.PLACE_INLINE_PNG) {
            return false;
        }
        if (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) return false;
        if (plan.visualPolicyLayer() != PolicyLayer.BACKGROUND
                && plan.visualLayer != VisualLayer.PAGE_BACKGROUND) {
            return false;
        }
        int[] roots = sourceRootIds(plan);
        if (roots.length == 0) roots = visualSourceIds(plan);
        for (int rootId : roots) {
            ResolvedPageItem root = ctx.resolvedData.getPageItem(String.valueOf(rootId));
            if (!isPageLevelShapeShellRoot(root)) continue;
            if (hasLowOpacityPlacedDescendant(rootId, visualSourceIds(plan))) return true;
        }
        return false;
    }

    private static boolean isMasterGraphicBackgroundFragmentContract(ObjectPlan plan) {
        if (plan == null) return false;
        String reason = safe(plan.reason);
        if (!reason.equals("master_graphic")
                && !reason.equals("master_page_graphic")
                && !reason.equals("master_side_composite")) {
            return false;
        }
        if (plan.materialization != Materialization.TEXTLESS_VISUAL_FRAGMENT) return false;
        if (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) return false;
        return plan.visualLayer == VisualLayer.PAGE_BACKGROUND
                || plan.visualPolicyLayer() == PolicyLayer.BACKGROUND;
    }

    private static boolean isPageLevelShapeShellRoot(ResolvedPageItem item) {
        if (item == null || item.sourceHidden() || item.hiddenByParent() || !item.visible()) {
            return false;
        }
        if (item.isInline()) return false;
        if (item.parentId() != null && !item.parentId().isBlank()) return false;
        if (!isVisibleShapeShellMaterial(item)) return false;
        return boundsOf(item) != null;
    }

    private boolean hasLowOpacityPlacedDescendant(int rootId, int[] sourceIds) {
        if (rootId <= 0 || ctx.resolvedData == null) return false;
        HashSet<Integer> sourceSet = new HashSet<>();
        if (sourceIds != null) {
            for (int sourceId : sourceIds) sourceSet.add(sourceId);
        }
        for (String childId : ctx.resolvedData.buildDescendantSet(String.valueOf(rootId), 8)) {
            int id = parseInt(childId, -1);
            if (!sourceSet.isEmpty() && !sourceSet.contains(id)) continue;
            ResolvedPageItem child = ctx.resolvedData.getPageItem(childId);
            if (!isPlacedContentItem(child)) continue;
            if (child.opacity() > 0.0 && child.opacity() <= 35.0) return true;
        }
        return false;
    }

    private boolean hasPlacedContentSourceTree(int sourceId) {
        if (ctx.resolvedData == null) return false;
        ResolvedPageItem item = ctx.resolvedData.getPageItem(String.valueOf(sourceId));
        if (isPlacedContentItem(item)) return true;
        for (String childId : ctx.resolvedData.buildDescendantSet(String.valueOf(sourceId), 8)) {
            if (isPlacedContentItem(ctx.resolvedData.getPageItem(childId))) return true;
        }
        return false;
    }

    private boolean hasVisibleShellRootWithPlacedContentTree(ObjectPlan plan) {
        if (plan == null || ctx.resolvedData == null) return false;
        if (!isTextlessCompositeShellContainerReason(plan.reason)) return false;
        ResolvedPageItem root = ctx.resolvedData.getPageItem(String.valueOf(plan.domId));
        if (!isVisibleShapeShellMaterial(root)) return false;
        return hasPlacedContentDescendantSourceTree(plan.domId);
    }

    private boolean hasPlacedContentDescendantSourceTree(int sourceId) {
        if (sourceId <= 0 || ctx.resolvedData == null) return false;
        for (String childId : ctx.resolvedData.buildDescendantSet(String.valueOf(sourceId), 8)) {
            if (isPlacedContentItem(ctx.resolvedData.getPageItem(childId))) return true;
        }
        return false;
    }

    private static boolean isTextlessCompositeShellContainerReason(String reason) {
        String value = safe(reason);
        return value.contains("complex_graphic_text_hidden")
                || value.contains("mixed_group_text_hidden")
                || value.contains("image_group_text_hidden")
                || value.contains("clip_carrying_textless_shell_owner")
                || value.contains("composite_shell_carrier");
    }

    private static boolean isVisibleShapeShellMaterial(ResolvedPageItem item) {
        if (item == null || item.sourceHidden() || item.hiddenByParent() || !item.visible()) {
            return false;
        }
        String type = safe(item.type());
        boolean shape = "Rectangle".equals(type)
                || "Oval".equals(type)
                || "Polygon".equals(type)
                || "GraphicLine".equals(type);
        if (!shape) return false;
        return sourceItemHasVisibleShellMaterial(item);
    }

    private static boolean isPlacedContentItem(ResolvedPageItem item) {
        if (item == null) return false;
        String type = safe(item.type());
        return "Image".equals(type) || "PDF".equals(type) || "EPS".equals(type);
    }

    private boolean isBackgroundSourceLayer(ObjectPlan plan) {
        if (plan == null) return false;
        if (isBackgroundLayerName(plan.sourceLayerName)) return true;
        if (ctx.resolvedData == null || plan.sourceObjectIds == null) return false;
        for (int sourceId : plan.sourceObjectIds) {
            ResolvedPageItem item = ctx.resolvedData.getPageItem(String.valueOf(sourceId));
            if (item != null && isBackgroundLayerName(item.layerName())) return true;
        }
        return false;
    }

    private static boolean isBackgroundLayerName(String layerName) {
        if (layerName == null) return false;
        String normalized = layerName.trim().toLowerCase();
        if (normalized.isEmpty()) return false;
        return normalized.equals("배경")
                || normalized.equals("바탕")
                || normalized.equals("background")
                || normalized.equals("bg")
                || normalized.equals("backdrop")
                || normalized.contains("바탕")
                || normalized.contains("background")
                || normalized.contains("backdrop");
    }

    private static double[] boundsOf(ResolvedPageItem item) {
        if (item == null) return null;
        return item.pageRelativeBounds() != null ? item.pageRelativeBounds() : item.geometricBounds();
    }

    private static boolean isTextFrameAccountedForByShell(ObjectPlan textPlan) {
        if (textPlan == null) return false;
        if (textPlan.textAction == TextAction.OWNED_BY_HWPX_TEXT) return true;
        if (textPlan.textAction != TextAction.DROP_TEXT) return false;
        if (textPlan.visualAction != VisualAction.DROP_VISUAL) return false;
        String reason = textPlan.reason != null ? textPlan.reason : "";
        return reason.equals("owned_by_inline_text_shell")
                || reason.equals("owned_by_inline_complete_png")
                || reason.equals("owned_by_anchored_table_plan");
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
            if (requiresMaterializationContract(plan) && plan.materialization == null) {
                warn("STAGE4_PLAN_MISSING_MATERIALIZATION", "plan=" + planRef(plan));
            }
            if (plan.coordinateSpace == null) {
                warn("STAGE4_PLAN_MISSING_COORDINATE_SPACE", "plan=" + planRef(plan));
            }
            if (ShellRole.isTextShell(plan)) {
                if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT
                        && plan.textAction != TextAction.DROP_TEXT) {
                    warn("STAGE4_TEXT_SHELL_WITHOUT_HWPX_TEXT",
                            "plan=" + planRef(plan));
                }
                if (plan.materialization != Materialization.EXTRACTED_PNG_VECTOR
                        && plan.materialization != Materialization.TEXTLESS_VISUAL_FRAGMENT
                        && plan.materialization != Materialization.NATIVE_SOURCE_SHAPE) {
                    warn("STAGE4_TEXT_SHELL_INVALID_MATERIALIZATION",
                            "plan=" + planRef(plan)
                                    + " materialization=" + plan.materialization);
                }
                if (plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                        && (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0)
                        && (plan.ownedTextRanges == null || plan.ownedTextRanges.length == 0)) {
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
                    && plan.materialization != Materialization.COMPLETE_PNG
                    && !isTextSlotCoveredByCompletePngOwner(plan)) {
                warn("STAGE4_PNG_TEXT_OWNER_NOT_COMPLETE_PNG",
                        "plan=" + planRef(plan)
                                + " materialization=" + plan.materialization);
            }
        }
    }

    private static boolean requiresMaterializationContract(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.hasVisibleVisual()) return true;
        if (plan.hasVisibleText()) return true;
        return plan.visualAction == VisualAction.PLACE_TABLE_STYLE
                || plan.visualAction == VisualAction.ABSORB_TEXT_STYLE;
    }

    private boolean isTextSlotCoveredByCompletePngOwner(ObjectPlan plan) {
        if (plan == null || ctx == null || ctx.ownershipPlans == null) return false;
        int[] textIds = textFrameSourceIds(plan);
        int[] sourceIds = plan.sourceObjectIds != null ? plan.sourceObjectIds : new int[0];
        if (isTextSlotCoveredBySimpleButtonCompletePngPlan(plan, textIds)) return true;
        for (ObjectPlan owner : ctx.ownershipPlans) {
            if (owner == null || owner == plan) continue;
            if (owner.pageIndex != plan.pageIndex) continue;
            if (owner.textAction != TextAction.OWNED_BY_PNG) continue;
            if (owner.materialization != Materialization.COMPLETE_PNG) continue;
            if (!owner.hasVisibleVisual()) continue;
            if (owner.sourceBundleKey != null && plan.sourceBundleKey != null
                    && !owner.sourceBundleKey.isBlank()
                    && owner.sourceBundleKey.equals(plan.sourceBundleKey)) {
                return true;
            }
            if (contains(owner.sourceObjectIds, plan.domId)) return true;
            if (containsAny(owner.sourceObjectIds, textIds)) return true;
            if (containsAny(owner.sourceObjectIds, sourceIds)) return true;
        }
        return false;
    }

    private boolean isTextSlotCoveredBySimpleButtonCompletePngPlan(
            ObjectPlan plan,
            int[] textIds) {
        if (ctx == null || ctx.simpleButtonLabelPlansByTextFrameId == null || textIds == null) {
            return false;
        }
        for (int textId : textIds) {
            SimpleButtonLabelPlan simple = ctx.simpleButtonLabelPlansByTextFrameId.get(textId);
            if (simple == null) continue;
            if (simple.mode != SimpleButtonLabelPlan.Mode.COMPLETE_PNG) continue;
            if (simple.pageIndex != plan.pageIndex) continue;
            if (contains(simple.sourceObjectIds, plan.domId)
                    || contains(simple.sourceObjectIds, textId)
                    || containsAny(simple.sourceObjectIds, plan.sourceObjectIds)) {
                return true;
            }
        }
        return false;
    }

    private boolean isVisualOnlyTextShellWithSeparateTextOwner(ObjectPlan plan) {
        if (plan == null) return false;
        if (!ShellRole.isTextShell(plan)) return false;
        if (plan.textAction != TextAction.DROP_TEXT) return false;
        int[] textFrameIds = textFrameSourceIds(plan);
        if (textFrameIds.length == 0) return true;
        for (int textFrameId : textFrameIds) {
            ObjectPlan textPlan = findHwpxTextOwnerPlan(textFrameId, plan.pageIndex);
            if (!isTextFrameAccountedForByShell(textPlan)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasVisibleVisualSlot(ObjectPlan plan) {
        return plan != null && plan.hasVisibleVisual();
    }

    private int[] textFrameSourceIds(ObjectPlan plan) {
        if (plan != null && plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) {
            return plan.ownedTextFrameIds;
        }
        if (plan != null && plan.ownedTextRanges != null && plan.ownedTextRanges.length > 0) {
            return new int[0];
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

    private InlineSourceEvidence inlineSourceEvidence(ObjectPlan plan) {
        InlineSourceEvidence evidence = new InlineSourceEvidence();
        if (plan == null || ctx.resolvedData == null) return evidence;
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        addAll(ids, plan.sourceObjectIds);
        addAll(ids, plan.sourceRootObjectIds);
        addAll(ids, plan.visualSourceObjectIds);
        addAll(ids, plan.exportSourceObjectIds);
        for (int sourceId : ids) {
            ResolvedPageItem item = ctx.resolvedData.getPageItem(String.valueOf(sourceId));
            if (item == null) continue;
            evidence.checked = true;
            if (evidence.checkedIds.length() > 0) evidence.checkedIds.append(',');
            evidence.checkedIds.append(sourceId);
            if (isExplicitInlineSource(item)) {
                evidence.inline = true;
            }
        }
        return evidence;
    }

    private static void addAll(LinkedHashSet<Integer> out, int[] ids) {
        if (out == null || ids == null) return;
        for (int id : ids) out.add(id);
    }

    private static boolean isExplicitInlineSource(ResolvedPageItem item) {
        if (item == null) return false;
        if (item.storyTextInlineSlot()) return true;
        String storyAnchorPlacement = safe(item.storyAnchorPlacement()).toUpperCase(java.util.Locale.ROOT);
        String anchoredPosition = safe(item.anchoredPosition()).toUpperCase(java.util.Locale.ROOT);
        if ("FLOATING_ANCHORED".equals(storyAnchorPlacement)
                || "ANCHORED".equals(anchoredPosition)) {
            return false;
        }
        return "INLINE".equals(storyAnchorPlacement)
                || "INLINE_POSITION".equals(anchoredPosition)
                || "INLINEPOSITION".equals(anchoredPosition);
    }

    private static final class InlineSourceEvidence {
        boolean checked;
        boolean inline;
        final StringBuilder checkedIds = new StringBuilder();
    }

    private static int[] sourceRootIds(ObjectPlan plan) {
        if (plan == null) return new int[0];
        if (plan.sourceRootObjectIds != null && plan.sourceRootObjectIds.length > 0) {
            return plan.sourceRootObjectIds;
        }
        return new int[0];
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

    private static boolean containsAny(int[] values, int[] candidates) {
        if (values == null || values.length == 0 || candidates == null || candidates.length == 0) {
            return false;
        }
        for (int candidate : candidates) {
            if (contains(values, candidate)) return true;
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

    private ObjectPlan findHwpxTextOwnerPlan(int textFrameId, int pageIndex) {
        ObjectPlan direct = findTextFramePlan(textFrameId, pageIndex);
        if (direct != null && direct.textAction == TextAction.OWNED_BY_HWPX_TEXT) return direct;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null || plan.pageIndex != pageIndex) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (contains(textOwnerSourceIds(plan), textFrameId)) return plan;
        }
        return direct;
    }

    private boolean hasSeparateHwpxTextOwner(int textFrameId, int pageIndex, ObjectPlan droppedPlan) {
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null || plan == droppedPlan) continue;
            if (plan.pageIndex != pageIndex) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (contains(textOwnerSourceIds(plan), textFrameId)) return true;
        }
        return false;
    }

    private static int[] textOwnerSourceIds(ObjectPlan plan) {
        if (plan == null) return new int[0];
        if (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) {
            return plan.ownedTextFrameIds;
        }
        return plan.sourceObjectIds != null ? plan.sourceObjectIds : new int[0];
    }

    private ObjectPlan findReferencedDescendantPlan(ObjectPlan parent, int descendantId) {
        if (parent == null) return null;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null || plan == parent) continue;
            if (plan.pageIndex != parent.pageIndex) continue;
            if (plan.domId == descendantId) return plan;
            if (plan.renderId != null && plan.renderId == descendantId) return plan;
            if (contains(visualSourceIds(plan), descendantId)) return plan;
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

    private boolean isCompleteRenderedCompositeParent(ObjectPlan plan) {
        if (!hasVisibleVisualSlot(plan)) return false;
        if (plan.materialization != Materialization.EXTRACTED_PNG_VECTOR) return false;
        if (plan.visualAction != VisualAction.PLACE_FLOATING_PNG
                && plan.visualAction != VisualAction.PLACE_INLINE_PNG) {
            return false;
        }
        if (plan.sourceObjectIds == null || plan.sourceObjectIds.length <= 1) return false;
        String reason = safe(plan.reason);
        return reason.contains("mixed_group_text_hidden")
                || reason.contains("image_group_text_hidden")
                || reason.contains("complex_graphic_text_hidden");
    }

    private static boolean hasDistinctChildShellSlotSignal(ObjectPlan plan) {
        if (plan == null) return false;
        String reason = safe(plan.reason);
        return "decoration_group".equals(reason)
                || "pure_decoration_group".equals(reason)
                || reason.contains("direct_label_shell_split_from_composite_carrier");
    }

    private static boolean compositeParentVisuallyOwnsChildSlot(ObjectPlan parent, ObjectPlan child) {
        if (parent == null || child == null) return false;
        int[] parentVisualSources = visualSourceIds(parent);
        if (parentVisualSources.length == 0) return false;
        return contains(parentVisualSources, child.domId)
                || containsAny(parentVisualSources, child.sourceObjectIds)
                || containsAny(parentVisualSources, visualSourceIds(child));
    }

    private static boolean containsAll(int[] values, int[] candidates) {
        if (candidates == null || candidates.length == 0) return true;
        if (values == null || values.length == 0) return false;
        for (int candidate : candidates) {
            if (!contains(values, candidate)) return false;
        }
        return true;
    }

    private static void addAll(int[] values, LinkedHashSet<Integer> out) {
        if (values == null || out == null) return;
        for (int value : values) out.add(value);
    }

    private static boolean contains(int[] values, int candidate) {
        if (values == null) return false;
        for (int value : values) {
            if (value == candidate) return true;
        }
        return false;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean boundsContains(double[] outer, double[] inner, double tolerance) {
        if (outer == null || inner == null || outer.length < 4 || inner.length < 4) return false;
        return inner[0] >= outer[0] - tolerance
                && inner[1] >= outer[1] - tolerance
                && inner[2] <= outer[2] + tolerance
                && inner[3] <= outer[3] + tolerance;
    }

    private static boolean boundsOverlap(double[] a, double[] b) {
        if (a == null || b == null || a.length < 4 || b.length < 4) return false;
        double top = Math.max(a[0], b[0]);
        double left = Math.max(a[1], b[1]);
        double bottom = Math.min(a[2], b[2]);
        double right = Math.min(a[3], b[3]);
        return right > left && bottom > top;
    }

    private static boolean isNoneColor(String colorName) {
        if (colorName == null || colorName.isBlank()) return true;
        String normalized = colorName.trim();
        return "None".equalsIgnoreCase(normalized)
                || "없음".equals(normalized)
                || "$ID/None".equalsIgnoreCase(normalized);
    }

    private static String normalizeResolvedVisibleText(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\uFFFC' || ch == '\u0016' || ch == '\u0018'
                    || ch == '\u0003' || ch == '\u0007' || ch == '\u0008') {
                continue;
            }
            if (Character.isWhitespace(ch) || Character.isISOControl(ch)) continue;
            sb.append(ch);
        }
        return sb.toString();
    }

    private static final class AnchorCarrier {
        final int anchorId;
        final ResolvedParagraph paragraph;
        final TextFlowDocument.TextFlowParagraph textFlowParagraph;

        AnchorCarrier(int anchorId, ResolvedParagraph paragraph) {
            this(anchorId, paragraph, null);
        }

        AnchorCarrier(
                int anchorId,
                ResolvedParagraph paragraph,
                TextFlowDocument.TextFlowParagraph textFlowParagraph) {
            this.anchorId = anchorId;
            this.paragraph = paragraph;
            this.textFlowParagraph = textFlowParagraph;
        }
    }

    private static String safe(String value) {
        return value != null ? value : "";
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
