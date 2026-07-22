package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionTiming;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared.InlineSemanticLabelPolicy;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.textflow.TextFlowDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTextFrame;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualLayeringRules;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Source ownership policy Stage 1 planner.
 *
 * <p>This class is the only place that may decide source ownership. Legacy
 * helpers still present in this file must be treated as migration code: they
 * may read source/extractor metadata to populate {@link ObjectPlan}, but must
 * not add page, literal-text, coordinate, pixel, or visual-symptom exceptions.</p>
 */
public final class OwnershipPlanner {
    private final ResolvedBuildContext ctx;
    private final ResolvedData data;
    private final List<ObjectPlan> plans = new ArrayList<>();
    private final Map<String, Double> imageInkScoreCache = new HashMap<>();
    private final Map<String, Double> imageWhiteOpaqueScoreCache = new HashMap<>();
    private final Map<Integer, Boolean> resolvedInlineAnchorCache = new HashMap<>();
    private final Map<Integer, Boolean> idmlAnchoredPagePositionCache = new HashMap<>();
    private List<RenderedGroup> allRenderedGroupsCache;
    private Map<String, List<RenderedGroup>> renderedGroupsByPageIdCache;
    private Map<Integer, List<RenderedGroup>> initialCompositeShellCarriersByPageCache;
    private Map<Integer, List<RenderedGroup>> initialVisibleChildShellFragmentsByPageCache;
    private Map<String, List<ResolvedTextFrame>> editableTextFramesByParentIdCache;
    private Map<Integer, List<ResolvedTextFrame>> visibleEditableTextFramesByPageCache;
    private final Map<String, Boolean> carrierVisibleMaterialOutsideInitialChildShellSlotsCache = new HashMap<>();
    private final Map<Integer, ObjectPlan> plannerDeclaredInlineTextShellContracts = new LinkedHashMap<>();
    private Map<String, Integer> renderedGroupCountByCandidateIdCache;
    private int importedPreplannedObjectPlanCount;


    private OwnershipPlanner(ResolvedBuildContext ctx) {
        this.ctx = ctx;
        this.data = ctx != null ? ctx.resolvedData : null;
    }

    public static void runObservation(ResolvedBuildContext ctx) {
        if (ctx == null || ctx.resolvedData == null) return;
        new OwnershipPlanner(ctx).run();
    }

    private void run() {
        timed("importPreplannedObjectPlans", this::importPreplannedObjectPlans);
        timed("ensureImportedInlineTextFramePlans", this::ensureImportedInlineTextFramePlans);
        timed("ensureEmptyInlineTextFrameVisualCarrierPlans",
                this::ensureEmptyInlineTextFrameVisualCarrierPlans);
        timed("ensurePngOwnedChildMarkerTextFramePlans", this::ensurePngOwnedChildMarkerTextFramePlans);
        timed("ensureSiblingTextShellBoundsTextFramePlans",
                this::ensureSiblingTextShellBoundsTextFramePlans);
        timed("planRenderedItems", this::planRenderedItems);
        timed("planNativePageBackdropShapes", this::planNativePageBackdropShapes);
        timed("planNativeParentTextShells", this::planNativeParentTextShells);
        timed("planNativeSiblingTextShells", this::planNativeSiblingTextShells);
        int legacyBridgePlanStart = plans.size();
        int legacyBridgeWarningStart = ctx.ownershipWarningLines.size();
        List<String> preBridgePlanJsons = snapshotPlanJsons();
        boolean legacyBridgeSkipped = shouldSkipLegacyOwnershipBridge();
        if (!legacyBridgeSkipped) {
            runLegacyOwnershipMutationBridge();
        } else {
            ctx.ownershipWarningLines.add("{\"code\":\"STAGE1_LEGACY_OWNERSHIP_BRIDGE_SKIPPED\""
                    + ",\"detail\":\"Java legacy ownership mutation bridge is disabled by default; Stage 1 ObjectPlan facts must be produced before execution\"}");
        }
        int legacyBridgePlanDelta = plans.size() - legacyBridgePlanStart;
        int legacyBridgeWarningDelta = ctx.ownershipWarningLines.size() - legacyBridgeWarningStart;
        recordLegacyBridgeMetrics(legacyBridgePlanStart, legacyBridgeWarningStart, legacyBridgeSkipped);
        recordLegacyBridgeDiagnostics(legacyBridgePlanStart, preBridgePlanJsons, legacyBridgeSkipped);
        timed("ensureOwnedTextFramePlansForVisibleTextShells", this::ensureOwnedTextFramePlansForVisibleTextShells);
        timed("ensureSourceTextWrapContracts", this::ensureSourceTextWrapContracts);
        timed("declareSourceBundleTextRangeShellPlans", this::declareSourceBundleTextRangeShellPlans);
        timed("finalizeOwnedTextFrameDepthContracts.afterShellOwnedTextPlans", this::finalizeOwnedTextFrameDepthContracts);
        timed("normalizeStage1ContractsBeforeValidation", this::normalizeStage1ContractsBeforeValidation);
        timed("dropNativeSourceShapePlans", this::dropNativeSourceShapePlans);
        writeAndValidatePlans();
        ConversionTiming.metric("stage1.ownershipPlanner.importedPreplannedPlans",
                importedPreplannedObjectPlanCount);
        ConversionTiming.metric("stage1.ownershipPlanner.plans", plans.size());
        ConversionTiming.metric("stage1.ownershipPlanner.warnings", ctx.ownershipWarningLines.size());
        System.err.println("[OwnershipPlanner] observation plans=" + plans.size()
                + " warnings=" + ctx.ownershipWarningLines.size()
                + " importedPreplanned=" + importedPreplannedObjectPlanCount
                + " legacyBridgePlanDelta=" + legacyBridgePlanDelta
                + " legacyBridgeWarningDelta=" + legacyBridgeWarningDelta
                + " legacyBridgeSkipped=" + legacyBridgeSkipped);
    }

    /**
     * Migration bridge for Java-side ownership mutations.
     *
     * <p>The extractor's ObjectPlan is already imported before this method.
     * Each step below is a candidate for migration into extraction Stage 1 or
     * Stage 4 validation. Stage 2/3 executors should eventually consume the
     * imported ObjectPlan directly and this bridge should disappear.</p>
     */
    private void runLegacyOwnershipMutationBridge() {
        timed("planTextFrames", this::planTextFrames);
        timed("resolveSiblingGroupTextShellOwners", this::resolveSiblingGroupTextShellOwners);
        timed("resolveIndependentSiblingTextShellOwners", this::resolveIndependentSiblingTextShellOwners);
        timed("normalizeSiblingGroupTextShellOwners", this::normalizeSiblingGroupTextShellOwners);
        timed("normalizeCrossPageTextShellOwnership", this::normalizeCrossPageTextShellOwnership);
        timed("resolveHwpxTextOwnedNonShellVisuals", this::resolveHwpxTextOwnedNonShellVisuals);
        timed("resolveInlineCompositeHwpxTextParents", this::resolveInlineCompositeHwpxTextParents);
        timed("resolvePairedInlinePageObjectChannelOwners", this::resolvePairedInlinePageObjectChannelOwners);
        timed("resolveInlineFloatingSameDom", this::resolveInlineFloatingSameDom);
        timed("resolveFloatingChildrenOwnedByInlineParent", this::resolveFloatingChildrenOwnedByInlineParent);
        timed("resolveFloatingPageObjectsOwnedByInlineHwpxText", this::resolveFloatingPageObjectsOwnedByInlineHwpxText);
        timed("resolveDuplicateRenderedChannels", this::resolveDuplicateRenderedChannels);
        timed("resolveFloatingInlineObjectPageObjectDuplicates", this::resolveFloatingInlineObjectPageObjectDuplicates);
        timed("resolveVisualBackdropClusterSources", this::resolveVisualBackdropClusterSources);
        timed("resolveTextShellSharedSources", this::resolveTextShellSharedSources);
        timed("resolveCoveredParentGroups", this::resolveCoveredParentGroups);
        timed("resolveParentGroupsWithMoreSpecificChildren", this::resolveParentGroupsWithMoreSpecificChildren);
        timed("resolveOverlappingImageExportDuplicates", this::resolveOverlappingImageExportDuplicates);
        timed("resolveLargeLayeredImageExportBackdrops", this::resolveLargeLayeredImageExportBackdrops);
        timed("resolveClippedDecorationParents", this::resolveClippedDecorationParents);
        timed("resolveLayeredContainerFaces", this::resolveLayeredContainerFaces);
        timed("resolveParentTextShellDescendantVisuals.1", this::resolveParentTextShellDescendantVisuals);
        timed("resolveCompositeBakedChildVisuals.1", this::resolveCompositeBakedChildVisuals);
        timed("resolveNestedTextShellSources", this::resolveNestedTextShellSources);
        timed("resolveClusterOwnedTextFrameShells", this::resolveClusterOwnedTextFrameShells);
        timed("resolveGraphicOnlyAtomicRootDescendantVisuals", this::resolveGraphicOnlyAtomicRootDescendantVisuals);
        timed("normalizeCompositeParentChildSourceSlots", this::normalizeCompositeParentChildSourceSlots);
        timed("resolveNonVisibleFloatingVisuals", this::resolveNonVisibleFloatingVisuals);
        timed("resolveDroppedRenderedTextOwnership.1", this::resolveDroppedRenderedTextOwnership);
        timed("resolveVisibleVisualHwpxTextSourceSlots", this::resolveVisibleVisualHwpxTextSourceSlots);
        timed("resolveNonTextVisualEditableTextSources", this::resolveNonTextVisualEditableTextSources);
        timed("declareInlineTextShellOwners", this::declareInlineTextShellOwners);
        timed("declareAtomicOwnershipRootTextHiddenShellOwners", this::declareAtomicOwnershipRootTextHiddenShellOwners);
        timed("declareDirectTextHiddenShellOwners", this::declareDirectTextHiddenShellOwners);
        timed("normalizeDuplicateSiblingLabelTextOwners", this::normalizeDuplicateSiblingLabelTextOwners);
        timed("planUnownedVisualOnlyChildShellSlots", this::planUnownedVisualOnlyChildShellSlots);
        timed("dropCompositeTextShellParentsCoveredByDirectShellSlots", this::dropCompositeTextShellParentsCoveredByDirectShellSlots);
        timed("dropCompositeTextShellParentsContainingDroppedContainerSources", this::dropCompositeTextShellParentsContainingDroppedContainerSources);
        timed("resolveParentTextShellDescendantVisuals.2", this::resolveParentTextShellDescendantVisuals);
        timed("resolveTextShellSourceDuplicates", this::resolveTextShellSourceDuplicates);
        timed("normalizeDistinctChildTextShellSourceSlots", this::normalizeDistinctChildTextShellSourceSlots);
        timed("normalizeParentTextShellChildInlineSlots", this::normalizeParentTextShellChildInlineSlots);
        timed("resolveDroppedRenderedTextOwnership.2", this::resolveDroppedRenderedTextOwnership);
        timed("resolveDuplicateTextShellTextOwners", this::resolveDuplicateTextShellTextOwners);
        timed("resolveCompositeCarrierTextFrameOwners", this::resolveCompositeCarrierTextFrameOwners);
        timed("splitDirectLabelShellsFromCompositeCarriers", this::splitDirectLabelShellsFromCompositeCarriers);
        timed("splitLabelChromeCompositeCarrierTextOwners", this::splitLabelChromeCompositeCarrierTextOwners);
        timed("dropNativeTextShellsBakedIntoVisibleCompositeCarriers", this::dropNativeTextShellsBakedIntoVisibleCompositeCarriers);
        timed("dropInlineGraphicOnlyTextShellWithoutTextlessCarrier", this::dropInlineGraphicOnlyTextShellWithoutTextlessCarrier);
        timed("dropPlansOwnedByInlineCompletePng", this::dropPlansOwnedByInlineCompletePng);
        timed("normalizeTextShellEditableTextOwnership", this::normalizeTextShellEditableTextOwnership);
        timed("normalizeTextShellPlacementToResolvedAnchors.1", this::normalizeTextShellPlacementToResolvedAnchors);
        timed("normalizeCompositeAssociatedInlineTextShellsToPage.1", this::normalizeCompositeAssociatedInlineTextShellsToPage);
        timed("dropChildLabelShellVisualsBakedIntoFloatingCompositeParents.1", this::dropChildLabelShellVisualsBakedIntoFloatingCompositeParents);
        timed("resolveCompositeTextOwnershipClaimedByLeafShells", this::resolveCompositeTextOwnershipClaimedByLeafShells);
        timed("resolveDuplicateRenderedIdentityPlans", this::resolveDuplicateRenderedIdentityPlans);
        timed("declareContentVisualChildrenClaimedByTextShellParents", this::declareContentVisualChildrenClaimedByTextShellParents);
        timed("normalizeClippedImageContentOwners", this::normalizeClippedImageContentOwners);
        timed("normalizeContentVisualSlotsExcludeVisibleTextShellSlots.1", this::normalizeContentVisualSlotsExcludeVisibleTextShellSlots);
        timed("dropVisualChildrenBakedIntoTextShellParents", this::dropVisualChildrenBakedIntoTextShellParents);
        timed("normalizeVisibleDescendantContracts", this::normalizeVisibleDescendantContracts);
        timed("dropVisualOnlyCompositeShellCarriersCoveredByChildShellSlots.1", this::dropVisualOnlyCompositeShellCarriersCoveredByChildShellSlots);
        timed("dropChildVisualFragmentsOwnedByCompositeShellCarriers.1", this::dropChildVisualFragmentsOwnedByCompositeShellCarriers);
        timed("normalizeVisualSourcesExcludeOwnedTextFrames", this::normalizeVisualSourcesExcludeOwnedTextFrames);
        timed("completeTextFrameShellStyleSources", this::completeTextFrameShellStyleSources);
        timed("normalizeTextShellPlacementToResolvedAnchors.2", this::normalizeTextShellPlacementToResolvedAnchors);
        timed("normalizeCompositeAssociatedInlineTextShellsToPage.2", this::normalizeCompositeAssociatedInlineTextShellsToPage);
        timed("dropChildLabelShellVisualsBakedIntoFloatingCompositeParents.2", this::dropChildLabelShellVisualsBakedIntoFloatingCompositeParents);
        timed("makePagePositionedStoryFlowInlineShellsVisualOnly", this::makePagePositionedStoryFlowInlineShellsVisualOnly);
        timed("declareInlineSimpleButtonLabelShellOwners", this::declareInlineSimpleButtonLabelShellOwners);
        timed("dropNonExecutableSimpleButtonTextOwners", this::dropNonExecutableSimpleButtonTextOwners);
        timed("normalizeRenderedPngTextOwnershipToTextFrames", this::normalizeRenderedPngTextOwnershipToTextFrames);
        timed("normalizeDroppedRenderedTextOwnershipToTextFrames", this::normalizeDroppedRenderedTextOwnershipToTextFrames);
        timed("completeVisibleTextShellRelationsFromSourceIds", this::completeVisibleTextShellRelationsFromSourceIds);
        timed("declareInlineTextShellTextOwnership", this::declareInlineTextShellTextOwnership);
        timed("normalizeDuplicateHwpxTextOwners", this::normalizeDuplicateHwpxTextOwners);
        timed("normalizeContentVisualSlotsExcludeVisibleTextShellSlots.2", this::normalizeContentVisualSlotsExcludeVisibleTextShellSlots);
        timed("normalizeVisualOnlyTextShellsDoNotOwnTextSlots", this::normalizeVisualOnlyTextShellsDoNotOwnTextSlots);
        timed("declareOrphanedPureDecorationVisuals", this::declareOrphanedPureDecorationVisuals);
        timed("resolveCompositeBakedChildVisuals.2", this::resolveCompositeBakedChildVisuals);
        timed("dropNonCanonicalRenderedGraphicFrameSlots", this::dropNonCanonicalRenderedGraphicFrameSlots);
        timed("normalizeVisualSlotsExcludeTableStyleSources", this::normalizeVisualSlotsExcludeTableStyleSources);
        timed("dropTableOnlyCarrierTextShellVisualOwners", this::dropTableOnlyCarrierTextShellVisualOwners);
        timed("normalizeDuplicateVisibleSourceSlots", this::normalizeDuplicateVisibleSourceSlots);
        timed("normalizeStoryFlowInlineVisualMaterialSlots", this::normalizeStoryFlowInlineVisualMaterialSlots);
        timed("bindPaperInlineAnchorsToPageMaterialSlots", this::bindPaperInlineAnchorsToPageMaterialSlots);
        timed("dropFloatingPageClonesOwnedByStoryFlowInlineSlots", this::dropFloatingPageClonesOwnedByStoryFlowInlineSlots);
        timed("dropVisualOnlyCompositeShellCarriersCoveredByChildShellSlots.2", this::dropVisualOnlyCompositeShellCarriersCoveredByChildShellSlots);
        timed("dropChildVisualFragmentsOwnedByCompositeShellCarriers.2", this::dropChildVisualFragmentsOwnedByCompositeShellCarriers);
        timed("dropVisualOnlyChildShellsBakedIntoCompleteCompositeParents", this::dropVisualOnlyChildShellsBakedIntoCompleteCompositeParents);
        timed("normalizeCompositeAssociatedInlineTextShellsToPage.3", this::normalizeCompositeAssociatedInlineTextShellsToPage);
        timed("normalizeDirectInlineAnchoredTextShellsToStoryFlow", this::normalizeDirectInlineAnchoredTextShellsToStoryFlow);
        timed("normalizeOwnedTextFrameCoordinatesToVisibleShellOwners", this::normalizeOwnedTextFrameCoordinatesToVisibleShellOwners);
        timed("normalizeVisibleVisualSourcesToPlanPage", this::normalizeVisibleVisualSourcesToPlanPage);
        timed("normalizeVisualSlotsExcludeTableStyleSources.final", this::normalizeVisualSlotsExcludeTableStyleSources);
        timed("dropNativeSourceShapesOwnedByRenderedBundles", this::dropNativeSourceShapesOwnedByRenderedBundles);
        timed("normalizePaperPageMaterialVisualPlans", this::normalizePaperPageMaterialVisualPlans);
        timed("normalizePageSpanningBackdropTextShellPlans", this::normalizePageSpanningBackdropTextShellPlans);
        timed("normalizePageSpanningBackdropVisualFragments", this::normalizePageSpanningBackdropVisualFragments);
        timed("normalizeTextShellBoundsToConcreteVisualSources", this::normalizeTextShellBoundsToConcreteVisualSources);
        timed("normalizeTextlessFragmentBoundsToCropSource", this::normalizeTextlessFragmentBoundsToCropSource);
        timed("completeTextFrameShellStyleSources.final", this::completeTextFrameShellStyleSources);
        timed("dropEditableTextFrameFallbackShellsOwnedByCompositeSlots", this::dropEditableTextFrameFallbackShellsOwnedByCompositeSlots);
        timed("finalizeVisualDepthContracts", this::finalizeVisualDepthContracts);
        timed("restorePlannerDeclaredInlineTextShellContracts", this::restorePlannerDeclaredInlineTextShellContracts);
        timed("finalizeOwnedTextFrameDepthContracts.final", this::finalizeOwnedTextFrameDepthContracts);
        timed("completeRenderedExtractionSourceContracts", this::completeRenderedExtractionSourceContracts);
        timed("normalizeTextShellsWithMaterializedTextOwners", this::normalizeTextShellsWithMaterializedTextOwners);
        timed("restoreInlineCarrierVisualContracts", this::restoreInlineCarrierVisualContracts);
        timed("normalizePlannerDeclaredInlineCompletePngWithoutTextOwner",
                this::normalizePlannerDeclaredInlineCompletePngWithoutTextOwner);
        timed("completeSourceTreeDiagnostics", this::completeSourceTreeDiagnostics);
        timed("restoreDroppedRenderedTextShellSourceContracts",
                this::restoreDroppedRenderedTextShellSourceContracts);
        timed("completeTextFrameShellStyleSources.recovered", this::completeTextFrameShellStyleSources);
    }

    private void writeAndValidatePlans() {
        timed("writePlans", this::writePlans);
        timed("validate", this::validate);
    }

    private void recordLegacyBridgeMetrics(int planStart, int warningStart, boolean skipped) {
        ConversionTiming.metric("stage1.ownershipPlanner.legacyBridge.skipped", skipped ? 1 : 0);
        ConversionTiming.metric("stage1.ownershipPlanner.legacyBridge.startPlans", planStart);
        ConversionTiming.metric("stage1.ownershipPlanner.legacyBridge.postBridgePlans", plans.size());
        ConversionTiming.metric("stage1.ownershipPlanner.legacyBridge.planDelta", plans.size() - planStart);
        ConversionTiming.metric("stage1.ownershipPlanner.legacyBridge.startWarnings", warningStart);
        ConversionTiming.metric("stage1.ownershipPlanner.legacyBridge.postBridgeWarnings",
                ctx.ownershipWarningLines.size());
        ConversionTiming.metric("stage1.ownershipPlanner.legacyBridge.warningDelta",
                ctx.ownershipWarningLines.size() - warningStart);
    }

    private List<String> snapshotPlanJsons() {
        List<String> snapshot = new ArrayList<>(plans.size());
        for (ObjectPlan plan : plans) {
            snapshot.add(plan != null ? plan.toJson() : "null");
        }
        return snapshot;
    }

    private void recordLegacyBridgeDiagnostics(
            int planStart,
            List<String> preBridgePlanJsons,
            boolean skipped) {
        ctx.legacyBridgeAddedPlanLines.clear();
        ctx.legacyBridgeMutatedPlanLines.clear();
        ctx.legacyBridgeSummaryLines.clear();
        Map<String, Integer> addedByCategory = new LinkedHashMap<>();
        Map<String, Integer> mutatedByCategory = new LinkedHashMap<>();
        Map<String, Integer> addedByMigrationKey = new LinkedHashMap<>();
        Map<String, Integer> mutatedByMigrationKey = new LinkedHashMap<>();
        int mutated = 0;
        int comparable = Math.min(planStart, Math.min(preBridgePlanJsons.size(), plans.size()));
        for (int i = 0; i < comparable; i++) {
            ObjectPlan plan = plans.get(i);
            String before = preBridgePlanJsons.get(i);
            String after = plan != null ? plan.toJson() : "null";
            if (before.equals(after)) continue;
            mutated++;
            String category = legacyBridgePlanCategory(plan);
            increment(mutatedByCategory, category);
            increment(mutatedByMigrationKey, legacyBridgePlanMigrationKey(plan));
            ctx.legacyBridgeMutatedPlanLines.add("{\"index\":" + i
                    + ",\"category\":\"" + ObjectPlan.escape(category) + "\""
                    + ",\"migrationKey\":\"" + ObjectPlan.escape(legacyBridgePlanMigrationKey(plan)) + "\""
                    + ",\"before\":" + before
                    + ",\"after\":" + after
                    + "}");
        }
        for (int i = planStart; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null) continue;
            String category = legacyBridgePlanCategory(plan);
            increment(addedByCategory, category);
            increment(addedByMigrationKey, legacyBridgePlanMigrationKey(plan));
            ctx.legacyBridgeAddedPlanLines.add("{\"index\":" + i
                    + ",\"category\":\"" + ObjectPlan.escape(category) + "\""
                    + ",\"migrationKey\":\"" + ObjectPlan.escape(legacyBridgePlanMigrationKey(plan)) + "\""
                    + ",\"plan\":" + plan.toJson()
                    + "}");
        }
        ctx.legacyBridgeSummaryLines.add("{\"skipped\":" + skipped
                + ",\"startPlans\":" + planStart
                + ",\"postBridgePlans\":" + plans.size()
                + ",\"addedPlans\":" + Math.max(0, plans.size() - planStart)
                + ",\"mutatedPreplannedPlans\":" + mutated
                + ",\"addedByCategory\":" + countMapJson(addedByCategory)
                + ",\"mutatedByCategory\":" + countMapJson(mutatedByCategory)
                + ",\"addedByMigrationKey\":" + countMapJson(addedByMigrationKey)
                + ",\"mutatedByMigrationKey\":" + countMapJson(mutatedByMigrationKey)
                + "}");
        ConversionTiming.metric("stage1.ownershipPlanner.legacyBridge.mutatedPreplannedPlans", mutated);
    }

    private static String legacyBridgePlanCategory(ObjectPlan plan) {
        if (plan == null) return "NULL";
        if (plan.visualAction == VisualAction.PLACE_TABLE_STYLE
                || plan.materialization == Materialization.HWPX_TABLE_STYLE
                || (plan.styleSourceObjectIds != null && plan.styleSourceObjectIds.length > 0)) {
            return "TABLE_STYLE";
        }
        if (plan.materialization == Materialization.HWPX_TEXT
                || plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                || plan.visualAction == VisualAction.ABSORB_TEXT_STYLE) {
            return "HWPX_TEXT";
        }
        if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                || (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0)) {
            return "TEXT_SHELL";
        }
        if (plan.visualAction == VisualAction.PLACE_INLINE_PNG
                || plan.visualAction == VisualAction.PLACE_FLOATING_PNG) {
            if (plan.materialization == Materialization.COMPLETE_PNG
                    || plan.textAction == TextAction.OWNED_BY_PNG) {
                return "COMPLETE_PNG";
            }
            if (plan.visualLayer == VisualLayer.PAGE_BACKGROUND
                    || plan.visualLayer == VisualLayer.CONTAINER_BACKDROP) {
                return plan.visualLayer == VisualLayer.PAGE_BACKGROUND
                        ? "BACKGROUND_GRAPHIC"
                        : "TEXTLESS_IMAGE_GROUP";
            }
            if (plan.visualLayer == VisualLayer.CONTENT_VISUAL
                    || plan.visualLayer == VisualLayer.CONTENT_BACKDROP) {
                return "CONTENT_VISUAL";
            }
            return "DECORATION";
        }
        if (plan.visualAction == VisualAction.DROP_VISUAL || plan.textAction == TextAction.DROP_TEXT) {
            return "DROP_OR_CLEANUP";
        }
        return "OTHER";
    }

    private static String legacyBridgePlanMigrationKey(ObjectPlan plan) {
        if (plan == null) return "NULL";
        return legacyBridgePlanCategory(plan)
                + "|kind=" + migrationKeyPart(plan.kind)
                + "|materialization=" + (plan.materialization != null ? plan.materialization.name() : "NONE")
                + "|text=" + (plan.textAction != null ? plan.textAction.name() : "NONE")
                + "|visual=" + (plan.visualAction != null ? plan.visualAction.name() : "NONE")
                + "|layer=" + (plan.visualLayer != null ? plan.visualLayer.name() : "NONE")
                + "|placement=" + (plan.placement != null ? plan.placement.name() : "NONE")
                + "|reason=" + migrationKeyPart(plan.reason);
    }

    private static String migrationKeyPart(String value) {
        if (value == null || value.isBlank()) return "";
        int colon = value.indexOf(':');
        String primary = colon >= 0 ? value.substring(0, colon) : value;
        return primary.length() <= 64 ? primary : primary.substring(0, 64);
    }

    private static void increment(Map<String, Integer> counts, String key) {
        counts.put(key, counts.getOrDefault(key, 0) + 1);
    }

    private static String countMapJson(Map<String, Integer> counts) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(ObjectPlan.escape(entry.getKey())).append('"')
                    .append(':')
                    .append(entry.getValue());
        }
        sb.append('}');
        return sb.toString();
    }

    private static boolean shouldSkipLegacyOwnershipBridge() {
        String property = System.getProperty("idml.ownership.skipLegacyBridge");
        if (property != null) {
            return Boolean.parseBoolean(property);
        }
        String env = System.getenv("IDML_SKIP_LEGACY_OWNERSHIP_BRIDGE");
        if (env != null) {
            return Boolean.parseBoolean(env);
        }
        String runProperty = System.getProperty("idml.ownership.runLegacyBridge");
        if (runProperty != null) {
            return !Boolean.parseBoolean(runProperty);
        }
        String runEnv = System.getenv("IDML_RUN_LEGACY_OWNERSHIP_BRIDGE");
        if (runEnv != null) {
            return !Boolean.parseBoolean(runEnv);
        }
        return true;
    }

    private static void timed(String name, Runnable action) {
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage1.ownershipPlanner." + name)) {
            action.run();
        }
    }

    private void importPreplannedObjectPlans() {
        importedPreplannedObjectPlanCount = 0;
        if (ctx.ownershipPlans == null || ctx.ownershipPlans.isEmpty()) return;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            plans.add(canonicalizeImportedPreplannedObjectPlan(plan));
        }
        importedPreplannedObjectPlanCount = ctx.ownershipPlans.size();
        for (ObjectPlan plan : plans) {
            recordPlannerDeclaredInlineTextShellContract(plan);
        }
        ctx.clearOwnershipPlansForRewrite();
    }

    private void ensureImportedInlineTextFramePlans() {
        if (data == null || data.stories() == null) return;
        int added = 0;
        LinkedHashSet<Integer> seen = new LinkedHashSet<>();
        for (ResolvedStory story : data.stories()) {
            if (story == null || story.paragraphs() == null) continue;
            for (ResolvedParagraph paragraph : story.paragraphs()) {
                if (paragraph == null || paragraph.runs() == null) continue;
                for (ResolvedRun run : paragraph.runs()) {
                    if (run == null || !run.isInlineAnchor() || run.anchoredObjectId() == null) continue;
                    int anchoredId = run.anchoredObjectId();
                    if (!seen.add(anchoredId)) continue;
                    if (ensureImportedInlineTextFramePlan(anchoredId)) {
                        added++;
                    }
                }
            }
        }
        ConversionTiming.metric("stage1.ownershipPlanner.ensureImportedInlineTextFramePlans.added", added);
    }

    private boolean ensureImportedInlineTextFramePlan(int anchoredId) {
        if (anchoredId < 0 || data == null) return false;
        ResolvedTextFrame tf = data.getTextFrame(String.valueOf(anchoredId));
        if (tf == null || !tf.isInline() || tf.sourceHidden()) return false;
        if (data.isTextOwnedByIndesignPng(tf.id())) return false;
        if (!textFrameHasVisibleSemanticText(tf)) return false;
        if (hasTextSlotDecisionForTextFrame(anchoredId)) return false;

        plans.add(new ObjectPlan(
                anchoredId,
                "text_frame:imported_inline_anchor",
                tf.pageIndex(),
                TextAction.OWNED_BY_HWPX_TEXT,
                VisualAction.DROP_VISUAL,
                VisualLayer.CONTENT_VISUAL,
                Placement.INLINE,
                null,
                new int[] { anchoredId },
                new int[] { anchoredId },
                new int[0],
                new int[] { anchoredId },
                new int[0],
                "p" + tf.pageIndex() + ":tf:" + anchoredId,
                Materialization.HWPX_TEXT,
                CoordinateSpace.STORY_FLOW,
                null,
                textFrameSourceZOrder(tf),
                "imported_preplanned_missing_inline_text_frame",
                null,
                textFramePlanBounds(tf, anchoredId, false),
                tf.layerId(),
                tf.layerName(),
                tf.layerIndex()));
        return true;
    }

    private void ensureEmptyInlineTextFrameVisualCarrierPlans() {
        if (data == null || data.stories() == null) return;
        int added = 0;
        int suppressedNestedPlans = 0;
        LinkedHashSet<Integer> anchors = new LinkedHashSet<>();
        for (ResolvedStory story : data.stories()) {
            if (story == null || story.paragraphs() == null) continue;
            for (ResolvedParagraph paragraph : story.paragraphs()) {
                if (paragraph == null || paragraph.runs() == null) continue;
                for (ResolvedRun run : paragraph.runs()) {
                    if (run == null || !run.isInlineAnchor() || run.anchoredObjectId() == null) continue;
                    anchors.add(run.anchoredObjectId());
                }
            }
        }
        for (Integer anchoredId : anchors) {
            if (anchoredId == null) continue;
            ResolvedTextFrame carrier = data.getTextFrame(String.valueOf(anchoredId));
            if (!isEmptyInlineTextFrameVisualCarrier(carrier)) continue;
            ObjectPlan visualPlan = nestedStoryInlineVisualPlan(carrier);
            if (visualPlan == null) continue;
            if (hasDirectInlineVisualCarrierPlan(anchoredId, visualPlan.file)) continue;

            int[] visualIds = visualIdsForCarrierPlan(visualPlan);
            int[] sourceIds = prependUnique(anchoredId, visualPlan.sourceObjectIds);
            ObjectPlan carrierPlan = new ObjectPlan(
                    anchoredId,
                    "text_frame:inline_visual_carrier",
                    carrier.pageIndex(),
                    TextAction.DROP_TEXT,
                    VisualAction.PLACE_INLINE_PNG,
                    VisualLayer.CONTENT_VISUAL,
                    Placement.INLINE,
                    visualPlan.renderId != null ? visualPlan.renderId : visualPlan.domId,
                    sourceIds,
                    visualIds,
                    new int[0],
                    new int[0],
                    visualIds,
                    "p" + carrier.pageIndex() + ":inline_visual_carrier:" + anchoredId,
                    Materialization.EXTRACTED_PNG_VECTOR,
                    CoordinateSpace.STORY_FLOW,
                    null,
                    textFrameSourceZOrder(carrier),
                    "empty_inline_text_frame_visual_carrier",
                    visualPlan.file,
                    visualPlan.bounds,
                    carrier.layerId(),
                    carrier.layerName(),
                    carrier.layerIndex())
                    .withExtractionSourceObjectIds(visualPlan.exportSourceObjectIds,
                            visualPlan.hiddenVisualSourceObjectIds)
                    .withInlineFlowContract(true, sourceIds);
            plans.add(carrierPlan);
            added++;
            suppressedNestedPlans += suppressNestedStoryInlineVisualPlans(carrier, carrierPlan);
        }
        ConversionTiming.metric("stage1.ownershipPlanner.emptyInlineTextFrameVisualCarriers.added", added);
        ConversionTiming.metric("stage1.ownershipPlanner.emptyInlineTextFrameVisualCarriers.suppressedNestedPlans",
                suppressedNestedPlans);
    }

    private boolean isEmptyInlineTextFrameVisualCarrier(ResolvedTextFrame tf) {
        if (tf == null || !tf.isInline() || tf.sourceHidden()) return false;
        if (tf.storyId() == null || tf.storyId().isEmpty()) return false;
        if (textFrameHasVisibleSemanticText(tf)) return false;
        return !inlineAnchorIdsInCarrierStory(tf).isEmpty();
    }

    private ObjectPlan nestedStoryInlineVisualPlan(ResolvedTextFrame carrier) {
        for (Integer anchoredId : inlineAnchorIdsInCarrierStory(carrier)) {
            if (anchoredId == null) continue;
            ObjectPlan plan = executableInlineVisualPlanForAnchor(anchoredId);
            if (plan != null) return plan;
        }
        return null;
    }

    private LinkedHashSet<Integer> inlineAnchorIdsInCarrierStory(ResolvedTextFrame carrier) {
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        if (carrier == null || carrier.storyId() == null || carrier.storyId().isEmpty()) return out;
        ResolvedStory story = data.getStory(carrier.storyId());
        if (story != null && story.paragraphs() != null) {
            for (ResolvedParagraph paragraph : story.paragraphs()) {
                if (paragraph == null || paragraph.runs() == null) continue;
                for (ResolvedRun run : paragraph.runs()) {
                    if (run != null && run.isInlineAnchor() && run.anchoredObjectId() != null) {
                        out.add(run.anchoredObjectId());
                    }
                }
            }
        }
        IDMLStory idmlStory = loadStory(carrier.storyId());
        if (idmlStory != null && idmlStory.paragraphs() != null) {
            for (IDMLParagraph paragraph : idmlStory.paragraphs()) {
                collectIdmlParagraphInlineAnchorIds(paragraph, out);
            }
        }
        if (idmlStory != null && idmlStory.tables() != null) {
            for (IDMLTable table : idmlStory.tables()) {
                if (table == null || table.rows() == null) continue;
                for (IDMLTableRow row : table.rows()) {
                    if (row == null || row.cells() == null) continue;
                    for (IDMLTableCell cell : row.cells()) {
                        if (cell == null || cell.paragraphs() == null) continue;
                        for (IDMLParagraph paragraph : cell.paragraphs()) {
                            collectIdmlParagraphInlineAnchorIds(paragraph, out);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static void collectIdmlParagraphInlineAnchorIds(
            IDMLParagraph paragraph,
            Set<Integer> out) {
        if (paragraph == null || paragraph.characterRuns() == null || out == null) return;
        for (IDMLCharacterRun run : paragraph.characterRuns()) {
            if (run == null || run.inlineAnchors() == null || run.inlineAnchors().isEmpty()) continue;
            for (IDMLCharacterRun.InlineAnchor anchor : run.inlineAnchors()) {
                int id = idmlInlineAnchorDomId(run, anchor);
                if (id >= 0) out.add(id);
            }
        }
    }

    private ObjectPlan executableInlineVisualPlanForAnchor(int anchoredId) {
        ObjectPlan best = null;
        for (ObjectPlan plan : plans) {
            if (plan == null) continue;
            if (plan.placement != Placement.INLINE) continue;
            if (plan.coordinateSpace != CoordinateSpace.STORY_FLOW) continue;
            if (plan.visualAction != VisualAction.PLACE_INLINE_PNG) continue;
            if (plan.textAction != TextAction.DROP_TEXT) continue;
            if (plan.file == null || plan.file.isEmpty()) continue;
            if (plan.domId != anchoredId
                    && (plan.renderId == null || plan.renderId != anchoredId)
                    && !containsInt(plan.sourceObjectIds, anchoredId)
                    && !containsInt(plan.visualSourceObjectIds, anchoredId)
                    && !containsInt(plan.exportSourceObjectIds, anchoredId)) {
                continue;
            }
            if (best == null || inlineVisualPlanPriority(plan) > inlineVisualPlanPriority(best)) {
                best = plan;
            }
        }
        return best;
    }

    private static int inlineVisualPlanPriority(ObjectPlan plan) {
        int score = 0;
        if (plan == null) return score;
        if (plan.renderId != null && plan.renderId == plan.domId) score += 8;
        if (plan.visualSourceObjectIds != null && plan.visualSourceObjectIds.length > 0) score += 4;
        if (plan.exportSourceObjectIds != null && plan.exportSourceObjectIds.length > 0) score += 2;
        if (plan.bounds != null && plan.bounds.length >= 4) score += 1;
        return score;
    }

    private boolean hasDirectInlineVisualCarrierPlan(int anchoredId, String file) {
        for (ObjectPlan plan : plans) {
            if (plan == null) continue;
            if (plan.domId != anchoredId) continue;
            if (plan.placement != Placement.INLINE) continue;
            if (plan.coordinateSpace != CoordinateSpace.STORY_FLOW) continue;
            if (plan.visualAction != VisualAction.PLACE_INLINE_PNG) continue;
            if (file == null || file.isEmpty() || file.equals(plan.file)) return true;
        }
        return false;
    }

    private int suppressNestedStoryInlineVisualPlans(ResolvedTextFrame carrier, ObjectPlan carrierPlan) {
        if (carrier == null || carrier.storyId() == null || carrierPlan == null) return 0;
        LinkedHashSet<Integer> nestedAnchors = inlineAnchorIdsInCarrierStory(carrier);
        int changed = 0;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null || plan == carrierPlan) continue;
            if (plan.visualAction != VisualAction.PLACE_INLINE_PNG) continue;
            if (plan.placement != Placement.INLINE) continue;
            if (plan.coordinateSpace != CoordinateSpace.STORY_FLOW) continue;
            if (plan.file == null || !plan.file.equals(carrierPlan.file)) continue;
            if (!planOwnsAnyNestedAnchor(plan, nestedAnchors)) continue;
            plans.set(i, plan.withVisualAction(VisualAction.DROP_VISUAL,
                    appendReason(plan.reason, "owned_by_inline_text_frame_visual_carrier")));
            changed++;
        }
        return changed;
    }

    private static boolean planOwnsAnyNestedAnchor(ObjectPlan plan, Set<Integer> nestedAnchors) {
        if (plan == null || nestedAnchors == null || nestedAnchors.isEmpty()) return false;
        for (Integer id : nestedAnchors) {
            if (id == null) continue;
            if (plan.domId == id) return true;
            if (plan.renderId != null && plan.renderId == id) return true;
            if (containsInt(plan.sourceObjectIds, id)) return true;
            if (containsInt(plan.visualSourceObjectIds, id)) return true;
            if (containsInt(plan.exportSourceObjectIds, id)) return true;
        }
        return false;
    }

    private static int[] visualIdsForCarrierPlan(ObjectPlan visualPlan) {
        if (visualPlan == null) return new int[0];
        if (visualPlan.visualSourceObjectIds != null && visualPlan.visualSourceObjectIds.length > 0) {
            return visualPlan.visualSourceObjectIds;
        }
        if (visualPlan.exportSourceObjectIds != null && visualPlan.exportSourceObjectIds.length > 0) {
            return visualPlan.exportSourceObjectIds;
        }
        return visualPlan.sourceObjectIds != null ? visualPlan.sourceObjectIds : new int[0];
    }

    private static int[] prependUnique(int first, int[] rest) {
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        ids.add(first);
        if (rest != null) {
            for (int id : rest) ids.add(id);
        }
        int[] out = new int[ids.size()];
        int i = 0;
        for (Integer id : ids) out[i++] = id != null ? id : -1;
        return out;
    }

    private static boolean containsInt(int[] values, int target) {
        if (values == null || values.length == 0) return false;
        for (int value : values) {
            if (value == target) return true;
        }
        return false;
    }

    private void ensurePngOwnedChildMarkerTextFramePlans() {
        if (data == null) return;
        for (ResolvedTextFrame tf : data.textFrames()) {
            if (tf == null || tf.id() == null) continue;
            int domId = parseInt(tf.id(), -1);
            if (domId < 0) continue;
            if (!isPngOwnedChildMarkerTextFrame(tf, domId)) continue;
            if (hasTextSlotDecisionForTextFrame(domId)) continue;
            plans.add(new ObjectPlan(
                    domId,
                    "text_frame:png_owned_child_marker",
                    tf.pageIndex(),
                    TextAction.OWNED_BY_PNG,
                    VisualAction.DROP_VISUAL,
                    VisualLayer.CONTENT_VISUAL,
                    Placement.FLOATING,
                    null,
                    new int[] { domId },
                    new int[] { domId },
                    new int[0],
                    new int[] { domId },
                    new int[0],
                    "p" + tf.pageIndex() + ":tf:" + domId,
                    Materialization.HWPX_TEXT,
                    CoordinateSpace.PAGE,
                    null,
                    textFrameSourceZOrder(tf),
                    "child_marker_text_owned_by_png_carrier",
                    null,
                    textFramePlanBounds(tf, domId, false),
                    tf.layerId(),
                    tf.layerName(),
                    tf.layerIndex()));
        }
    }

    private boolean hasTextSlotDecisionForTextFrame(int textFrameId) {
        if (textFrameId < 0) return false;
        for (ObjectPlan plan : plans) {
            if (plan == null) continue;
            if (!contains(plan.ownedTextFrameIds, textFrameId) && plan.domId != textFrameId) continue;
            if (plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                    || plan.textAction == TextAction.OWNED_BY_PNG
                    || plan.textAction == TextAction.DROP_TEXT) {
                return true;
            }
        }
        return false;
    }

    private void ensureSiblingTextShellBoundsTextFramePlans() {
        if (data == null) return;
        int added = 0;
        for (ResolvedTextFrame tf : data.textFrames()) {
            if (!isVisibleEditableTextFrameSource(tf)) continue;
            int domId = parseInt(tf.id(), -1);
            if (domId < 0) continue;
            if (hasTextSlotDecisionForTextFrame(domId)) continue;
            ResolvedPageItem shell = directSiblingTextShellForTextFrame(tf);
            if (shell == null) continue;
            double[] bounds = pageRelativeBoundsOf(shell);
            if (bounds == null || bounds.length < 4) continue;
            int shellId = parseInt(shell.id(), -1);
            if (shellId < 0) continue;
            Placement placement = placementOfTextFrame(tf, domId,
                    TextAction.OWNED_BY_HWPX_TEXT, VisualAction.DROP_VISUAL);
            CoordinateSpace coordinateSpace = placement == Placement.INLINE
                    ? CoordinateSpace.STORY_FLOW : CoordinateSpace.PAGE;
            plans.add(new ObjectPlan(
                    domId,
                    "text_frame:sibling_text_shell_bounds",
                    tf.pageIndex(),
                    TextAction.OWNED_BY_HWPX_TEXT,
                    VisualAction.DROP_VISUAL,
                    VisualLayer.CONTENT_VISUAL,
                    placement,
                    null,
                    new int[] { shellId, domId },
                    new int[0],
                    new int[] { shellId },
                    new int[] { domId },
                    new int[0],
                    "p" + tf.pageIndex() + ":tf-shell-bounds:" + shellId + ":t_" + domId,
                    Materialization.HWPX_TEXT,
                    coordinateSpace,
                    null,
                    textFrameSourceZOrder(tf),
                    "source_sibling_text_shell_bounds",
                    null,
                    bounds,
                    tf.layerId(),
                    tf.layerName(),
                    tf.layerIndex()));
            added++;
        }
        ConversionTiming.metric("stage1.ownershipPlanner.siblingTextShellBoundsTextFrames.added", added);
    }

    private ResolvedPageItem directSiblingTextShellForTextFrame(ResolvedTextFrame tf) {
        if (tf == null || tf.id() == null || data == null) return null;
        ResolvedPageItem textItem = data.getPageItem(tf.id());
        if (textItem == null || textItem.parentId() == null || textItem.parentId().isBlank()) {
            return null;
        }
        double[] textBounds = boundsOf(textItem);
        if (textBounds == null || textBounds.length < 4 || area(textBounds) <= 0.0) {
            return null;
        }
        ResolvedPageItem best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        boolean ambiguous = false;
        for (ResolvedPageItem sibling : data.pageItems()) {
            if (sibling == null || sibling.id() == null) continue;
            if (sibling.id().equals(textItem.id())) continue;
            if (!safe(textItem.parentId()).equals(safe(sibling.parentId()))) continue;
            if (!isNativeTextShellShape(sibling)) continue;
            double[] shellBounds = boundsOf(sibling);
            if (!textFrameFitsShell(shellBounds, textBounds)) continue;
            double score = siblingTextShellBoundsScore(shellBounds, textBounds, sibling.zOrder(), tf.zOrder());
            if (score > bestScore + 0.001) {
                best = sibling;
                bestScore = score;
                ambiguous = false;
            } else if (Math.abs(score - bestScore) <= 0.001) {
                ambiguous = true;
            }
        }
        return ambiguous ? null : best;
    }

    private static double siblingTextShellBoundsScore(
            double[] shellBounds,
            double[] textBounds,
            int shellZOrder,
            int textZOrder) {
        double shellArea = area(shellBounds);
        double textArea = area(textBounds);
        if (shellArea <= 0.0 || textArea <= 0.0) return Double.NEGATIVE_INFINITY;
        double shellW = Math.abs(shellBounds[3] - shellBounds[1]);
        double shellH = Math.abs(shellBounds[2] - shellBounds[0]);
        double textW = Math.abs(textBounds[3] - textBounds[1]);
        double textH = Math.abs(textBounds[2] - textBounds[0]);
        double areaRatio = Math.min(shellArea, textArea) / Math.max(shellArea, textArea);
        double centerScore = boundsContainCenter(shellBounds, textBounds) ? 2.0 : 0.0;
        double containmentScore = boundsContains(shellBounds, textBounds, 3.0) ? 4.0 : 0.0;
        double sizeScore = Math.min(shellW / Math.max(0.01, textW), 4.0)
                + Math.min(shellH / Math.max(0.01, textH), 4.0);
        double depthScore = shellZOrder <= textZOrder ? 1.0 : 0.0;
        return containmentScore + centerScore + sizeScore + areaRatio + depthScore;
    }

    private static boolean textFrameHasVisibleSemanticText(ResolvedTextFrame tf) {
        if (tf == null) return false;
        String text = safe(tf.frameVisibleText())
                .replace('\uFFFC', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        if (!text.isEmpty()) return true;
        if (tf.frameParaTexts() == null) return false;
        for (String paragraphText : tf.frameParaTexts()) {
            String normalized = safe(paragraphText)
                    .replace('\uFFFC', ' ')
                    .replace('\r', ' ')
                    .replace('\n', ' ')
                    .trim();
            if (!normalized.isEmpty()) return true;
        }
        return false;
    }

    private void ensureSourceTextWrapContracts() {
        if (data == null || data.textFrames() == null) return;
        int observed = 0;
        int attached = 0;
        int addedPlans = 0;
        int skipped = 0;
        for (ResolvedTextFrame tf : data.textFrames()) {
            int textFrameId = parseFlexibleId(tf != null ? tf.id() : null);
            if (textFrameId < 0) continue;
            TextLayoutContract contract = sourceTextWrapContractForTextFrame(tf, textFrameId);
            if (contract == null) continue;
            observed++;
            int planIndex = findHwpxTextSlotPlanIndexForTextFrame(textFrameId);
            if (planIndex >= 0) {
                ObjectPlan plan = plans.get(planIndex);
                if (plan != null) {
                    plan.withTextLayoutContract(contract);
                    attached++;
                } else {
                    skipped++;
                }
                continue;
            }
            if (addSourceTextWrapTextFramePlan(tf, textFrameId, contract)) {
                addedPlans++;
                attached++;
            } else {
                skipped++;
            }
        }
        ConversionTiming.metric("stage1.ownershipPlanner.sourceTextWrap.contractsObserved", observed);
        ConversionTiming.metric("stage1.ownershipPlanner.sourceTextWrap.contractsAttached", attached);
        ConversionTiming.metric("stage1.ownershipPlanner.sourceTextWrap.plansAdded", addedPlans);
        ConversionTiming.metric("stage1.ownershipPlanner.sourceTextWrap.contractsSkipped", skipped);
        if (observed > 0 && ctx.ownershipWarningLines != null) {
            ctx.ownershipWarningLines.add("{\"code\":\"STAGE1_SOURCE_TEXT_WRAP_CONTRACTS_DECLARED\""
                    + ",\"observed\":" + observed
                    + ",\"attached\":" + attached
                    + ",\"plansAdded\":" + addedPlans
                    + ",\"skipped\":" + skipped
                    + ",\"detail\":\"SOURCE_TEXT_WRAP contracts are declared from resolved.composedLines wrap indents and executed by Stage 2\"}");
        }
    }

    private void declareSourceBundleTextRangeShellPlans() {
        if (data == null || data.pageItems() == null) return;
        int observed = 0;
        int planned = 0;
        int skipped = 0;
        Set<Integer> usedShellIds = new HashSet<>();
        Set<String> usedRanges = new HashSet<>();
        for (ResolvedPageItem group : data.pageItems()) {
            if (group == null || !"Group".equals(safe(group.type())) || group.childIds() == null) continue;
            TextRangeShellBundle source = textRangeShellBundle(group);
            if (source == null) {
                skipped++;
                continue;
            }
            observed++;
            int pairCount = Math.min(source.shells.size(), source.ranges.size());
            for (int i = 0; i < pairCount; i++) {
                ResolvedPageItem shell = source.shells.get(i);
                TextRangeCandidate rangeCandidate = source.ranges.get(i);
                TextRangeRef range = rangeCandidate != null ? rangeCandidate.range : null;
                int shellId = parseFlexibleId(shell != null ? shell.id() : null);
                String rangeKey = textRangeKey(range);
                if (shellId < 0 || usedShellIds.contains(shellId) || usedRanges.contains(rangeKey)) {
                    skipped++;
                    continue;
                }
                TextRangeShellPlan textRangeShell = textRangeShellPlanFromGroupedSource(
                        shell, rangeCandidate != null ? rangeCandidate.textFrame : null, range);
                ObjectPlan objectPlan = objectPlanFromGroupedTextRangeShell(
                        textRangeShell, rangeCandidate != null ? rangeCandidate.textFrame : null);
                if (textRangeShell == null || objectPlan == null) {
                    skipped++;
                    continue;
                }
                ctx.addTextRangeShellPlan(textRangeShell);
                if (!hasImportedSourceBundleTextRangeShellPlan(shellId)) {
                    plans.add(objectPlan);
                }
                usedShellIds.add(shellId);
                usedRanges.add(rangeKey);
                planned++;
            }
            skipped += Math.abs(source.shells.size() - source.ranges.size());
        }
        ConversionTiming.metric("stage1.ownershipPlanner.sourceBundleTextRangeShells.observed", observed);
        ConversionTiming.metric("stage1.ownershipPlanner.sourceBundleTextRangeShells.plansAdded", planned);
        ConversionTiming.metric("stage1.ownershipPlanner.sourceBundleTextRangeShells.skipped", skipped);
        if (planned > 0 && ctx.ownershipWarningLines != null) {
            ctx.ownershipWarningLines.add("{\"code\":\"STAGE1_SOURCE_BUNDLE_TEXT_RANGE_SHELLS_DECLARED\""
                    + ",\"observed\":" + observed
                    + ",\"plansAdded\":" + planned
                    + ",\"skipped\":" + skipped
                    + ",\"detail\":\"source Group exposes editable TextFrame children and textless closed-shell children; styled leading story ranges are assigned to shell slots within the same source bundle\"}");
        }
    }

    private TextRangeShellBundle textRangeShellBundle(ResolvedPageItem group) {
        if (group == null || group.childIds() == null || group.childIds().length < 2) return null;
        if (!isSourceBundleTextRangeShellInlineFlow(group)) return null;
        List<ResolvedTextFrame> childTextFrames = new ArrayList<>();
        List<ResolvedPageItem> childShells = new ArrayList<>();
        int otherVisibleChildCount = 0;
        for (int childId : group.childIds()) {
            ResolvedTextFrame tf = data.getTextFrame(String.valueOf(childId));
            if (isVisibleEditableTextFrameSource(tf)) {
                childTextFrames.add(tf);
                continue;
            }
            ResolvedPageItem item = data.getPageItem(String.valueOf(childId));
            if (isVisibleTextlessShellSource(item)) {
                childShells.add(item);
                continue;
            }
            if (item != null && !item.sourceHidden()) otherVisibleChildCount++;
        }
        if (childTextFrames.isEmpty() || childShells.isEmpty()) return null;
        if (otherVisibleChildCount > 0) return null;
        List<TextRangeCandidate> ranges = new ArrayList<>();
        for (ResolvedTextFrame tf : childTextFrames) {
            ResolvedStory story = data.getStory(tf.storyId());
            if (story == null || story.paragraphs() == null || story.paragraphs().isEmpty()) continue;
            ranges.addAll(leadingStyledStoryRunRanges(tf, story));
        }
        if (ranges.isEmpty()) return null;
        childShells.sort((a, b) -> {
            double[] ab = sourceBounds(a);
            double[] bb = sourceBounds(b);
            int cmp = Double.compare(centerY(ab), centerY(bb));
            if (cmp != 0) return cmp;
            cmp = Double.compare(centerX(ab), centerX(bb));
            if (cmp != 0) return cmp;
            return Integer.compare(a.zOrder(), b.zOrder());
        });
        ranges.sort((a, b) -> {
            int cmp = Integer.compare(a.pageIndex, b.pageIndex);
            if (cmp != 0) return cmp;
            cmp = Double.compare(a.lineTop, b.lineTop);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(a.range.textFrameId, b.range.textFrameId);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(a.range.paragraphIndex, b.range.paragraphIndex);
            if (cmp != 0) return cmp;
            return Integer.compare(a.range.runIndex, b.range.runIndex);
        });
        return new TextRangeShellBundle(childShells, ranges);
    }

    private boolean isSourceBundleTextRangeShellInlineFlow(ResolvedPageItem group) {
        if (hasSourceBundleTextRangeShellInlineSignal(group)) return true;
        if (group == null || group.childIds() == null) return false;
        for (int childId : group.childIds()) {
            ResolvedPageItem item = data != null ? data.getPageItem(String.valueOf(childId)) : null;
            if (hasSourceBundleTextRangeShellInlineSignal(item)) return true;
            ResolvedTextFrame tf = data != null ? data.getTextFrame(String.valueOf(childId)) : null;
            if (tf != null && tf.isInline()) return true;
        }
        return false;
    }

    private boolean hasSourceBundleTextRangeShellInlineSignal(ResolvedPageItem item) {
        if (item == null) return false;
        String placement = safe(item.storyAnchorPlacement()).toUpperCase(Locale.ROOT);
        String anchoredPosition = safe(item.anchoredPosition()).toUpperCase(Locale.ROOT);
        if ("FLOATING_ANCHORED".equals(placement) || "ANCHORED".equals(anchoredPosition)) {
            return false;
        }
        if (item.storyTextInlineSlot() || item.isInline()) return true;
        return "INLINE".equals(placement)
                || "INLINE_POSITION".equals(anchoredPosition)
                || "INLINEPOSITION".equals(anchoredPosition);
    }

    private List<TextRangeCandidate> leadingStyledStoryRunRanges(ResolvedTextFrame tf, ResolvedStory story) {
        List<TextRangeCandidate> ranges = new ArrayList<>();
        int textFrameId = parseFlexibleId(tf != null ? tf.id() : null);
        if (textFrameId < 0 || tf == null || story == null || story.paragraphs() == null) return ranges;
        for (int paragraphIndex = 0; paragraphIndex < story.paragraphs().size(); paragraphIndex++) {
            ResolvedParagraph paragraph = story.paragraphs().get(paragraphIndex);
            if (paragraph == null || paragraph.runs() == null || paragraph.runs().size() < 2) continue;
            // 화학식(H₂O, 2H₂O 등)은 첫 원소 런과 첨자 런 사이에 스타일 경계(charStyle=
            // 하부자, position=SUBSCRIPT)가 있어 leading-run 셸 분리에 오검된다. 이 첫
            // 글자를 별도 셸로 떼어내면 원본 스타일을 잃고(빈 charPr) 레이아웃이 깨진다
            // (실측: 과학 u1 p17 파랑 H₂O 의 H 가 58pt rect 로 분리). 화학식 문단은
            // 배지/라벨이 아니므로 range 분리 대상에서 제외한다.
            if (isChemicalFormulaParagraph(paragraph)) continue;
            int paragraphVisibleCursor = 0;
            int firstTextRunIndex = -1;
            ResolvedRun firstTextRun = null;
            for (int runIndex = 0; runIndex < paragraph.runs().size(); runIndex++) {
                ResolvedRun run = paragraph.runs().get(runIndex);
                if (run == null || run.isInlineAnchor()) continue;
                String visible = textRangeVisibleText(run.text());
                if (visible.trim().isEmpty()) {
                    paragraphVisibleCursor += visible.length();
                    continue;
                }
                firstTextRunIndex = runIndex;
                firstTextRun = run;
                break;
            }
            if (firstTextRunIndex < 0 || firstTextRun == null) continue;
            int nextTextRunIndex = nextVisibleTextRunIndex(paragraph, firstTextRunIndex + 1);
            if (nextTextRunIndex < 0) continue;
            ResolvedRun nextTextRun = paragraph.runs().get(nextTextRunIndex);
            if (!hasSourceStyleBoundary(firstTextRun, nextTextRun)) continue;
            String raw = firstTextRun.text();
            String text = textRangeVisibleText(raw).trim();
            if (text.isEmpty()) continue;
            int start = firstNonRangeWhitespace(raw);
            int end = lastNonRangeWhitespace(raw);
            int paragraphStart = paragraphVisibleCursor + textRangeVisibleLength(raw, 0, start);
            int paragraphEnd = paragraphStart + text.length();
            TextRangeRef range = new TextRangeRef(
                    textFrameId,
                    tf.storyId(),
                    paragraphIndex,
                    firstTextRunIndex,
                    start,
                    end,
                    paragraphStart,
                    paragraphEnd,
                    text);
            ranges.add(new TextRangeCandidate(tf, range, composedLineTop(tf, paragraphIndex), tf.pageIndex()));
        }
        return ranges;
    }

    private TextRangeShellPlan textRangeShellPlanFromGroupedSource(
            ResolvedPageItem shell,
            ResolvedTextFrame tf,
            TextRangeRef range) {
        if (shell == null || tf == null || range == null) return null;
        int shellId = parseFlexibleId(shell.id());
        if (shellId < 0) return null;
        double[] bounds = sourceBounds(shell);
        if (!validBounds(bounds)) return null;
        String fill = data.resolveTintedColorHex(shell.fillColorName(), shell.fillTint());
        String stroke = data.resolveTintedColorHex(shell.strokeColorName(), shell.strokeTint());
        return new TextRangeShellPlan(
                shellId,
                range.textFrameId,
                tf.storyId(),
                tf.pageIndex(),
                shell.zOrder(),
                range,
                shell.type(),
                bounds,
                fill,
                stroke,
                shell.strokeWeight(),
                shell.cornerRadius());
    }

    private ObjectPlan objectPlanFromGroupedTextRangeShell(
            TextRangeShellPlan shellPlan,
            ResolvedTextFrame tf) {
        if (shellPlan == null || shellPlan.range == null || shellPlan.shellBounds == null) return null;
        ObjectPlan plan = new ObjectPlan(
                shellPlan.shellDomId,
                "text_range_shell:source_bundle",
                shellPlan.pageIndex,
                TextAction.OWNED_BY_HWPX_TEXT,
                VisualAction.PLACE_TEXT_SHELL,
                VisualLayer.LABEL_BACKDROP,
                Placement.INLINE,
                null,
                new int[] { shellPlan.shellDomId, shellPlan.textFrameId },
                new int[] { shellPlan.shellDomId },
                new int[] { shellPlan.shellDomId },
                new int[0],
                new int[0],
                "p" + shellPlan.pageIndex + ":text-range-shell:" + shellPlan.shellDomId,
                Materialization.EXTRACTED_PNG_VECTOR,
                CoordinateSpace.STORY_FLOW,
                null,
                shellPlan.zOrder,
                "source_bundle_text_range_shell",
                null,
                shellPlan.shellBounds,
                tf != null ? tf.layerId() : null,
                tf != null ? tf.layerName() : null,
                tf != null ? tf.layerIndex() : -1);
        return plan.withExtractionCandidate(
                        "cand.source_bundle_text_range_shell." + shellPlan.shellDomId,
                        "pass.source_bundle_text_range_shells",
                        "text_range_shell_slot")
                .withExtractionSourceObjectIds(new int[] { shellPlan.shellDomId }, new int[0])
                .withOwnedTextRanges(new TextRangeRef[] { shellPlan.range })
                .withObjectPlanId("objectPlan.source_bundle_text_range_shell." + shellPlan.shellDomId);
    }

    private boolean hasImportedSourceBundleTextRangeShellPlan(int shellId) {
        if (shellId < 0 || plans == null) return false;
        for (ObjectPlan plan : plans) {
            if (plan == null) continue;
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (plan.placement != Placement.INLINE) continue;
            if (plan.materialization != Materialization.EXTRACTED_PNG_VECTOR) continue;
            if (!contains(plan.visualSourceObjectIds, shellId)
                    && !contains(plan.exportSourceObjectIds, shellId)) {
                continue;
            }
            String slot = safe(plan.slotRole);
            String kind = safe(plan.kind);
            if ("source_bundle_text_range_shell_slot".equals(slot)
                    || kind.contains("source_bundle_text_range_shell")
                    || (plan.ownedTextRanges != null && plan.ownedTextRanges.length > 0)) {
                return true;
            }
        }
        return false;
    }

    private boolean isVisibleTextlessShellSource(ResolvedPageItem item) {
        if (item == null || item.sourceHidden()) return false;
        String type = safe(item.type());
        if (!"Polygon".equals(type) && !"Rectangle".equals(type) && !"Oval".equals(type)) return false;
        if (!hasVisibleSourceStyle(item)) return false;
        return validBounds(sourceBounds(item));
    }

    private static boolean hasVisibleSourceStyle(ResolvedPageItem item) {
        if (item == null) return false;
        String fill = item.fillColorName();
        if (fill != null && !"None".equals(fill) && !"[None]".equals(fill)
                && !"Swatch/None".equals(fill)) {
            return true;
        }
        String stroke = item.strokeColorName();
        return stroke != null && !"None".equals(stroke) && !"[None]".equals(stroke)
                && !"Swatch/None".equals(stroke) && item.strokeWeight() > 0;
    }

    private static String textRangeKey(TextRangeRef range) {
        if (range == null) return "";
        return range.textFrameId + ":" + range.paragraphIndex + ":" + range.runIndex
                + ":" + range.start + ":" + range.end;
    }

    private static String textRangeVisibleText(String text) {
        if (text == null) return "";
        return text.replace("\uFFFC", "")
                .replace("\u0003", "")
                .replace("\u0007", "")
                .replace("\b", "")
                .replace("\r", "")
                .replace("\n", "");
    }

    private static int nextVisibleTextRunIndex(ResolvedParagraph paragraph, int startIndex) {
        if (paragraph == null || paragraph.runs() == null) return -1;
        for (int i = Math.max(0, startIndex); i < paragraph.runs().size(); i++) {
            ResolvedRun run = paragraph.runs().get(i);
            if (run == null || run.isInlineAnchor()) continue;
            if (!textRangeVisibleText(run.text()).trim().isEmpty()) return i;
        }
        return -1;
    }

    /**
     * 문단이 화학식(H₂O, 2H₂O, 2H₂+O₂ 등)인가.
     *
     * <p>화학식은 첫 원소와 첨자 사이에 정상적인 스타일 경계가 있어 leading-run 셸
     * 분리에 오검된다. 가시 텍스트가 원소기호(H,O,Na…)·숫자·화학 연산자(+,→ 등)로만
     * 이루어지고 원소기호가 하나 이상이면 화학식으로 본다. 배지/라벨(한글·다양한
     * 라틴 단어)은 이 조건에 걸리지 않는다.
     */
    private static boolean isChemicalFormulaParagraph(ResolvedParagraph paragraph) {
        if (paragraph == null || paragraph.runs() == null) return false;
        StringBuilder sb = new StringBuilder();
        boolean sawSubscriptRun = false;
        for (ResolvedRun run : paragraph.runs()) {
            if (run == null || run.isInlineAnchor()) continue;
            String v = textRangeVisibleText(run.text());
            if (v == null) continue;
            sb.append(v);
            String pos = run.position();
            String cs = run.charStyle();
            if ((pos != null && pos.toLowerCase(java.util.Locale.ROOT).contains("subscript"))
                    || (cs != null && (cs.contains("하부자") || cs.contains("첨자")))) {
                sawSubscriptRun = true;
            }
        }
        String text = sb.toString().replace(" ", "").replace(" ", "")
                .replace("￼", "").trim();
        if (text.isEmpty() || text.length() > 24) return false;
        // 첨자 런이 있어야 화학식 오검 대상(단순 라틴 단어 배제)
        if (!sawSubscriptRun) return false;
        boolean sawUpper = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'A' && c <= 'Z') { sawUpper = true; continue; }
            if (c >= 'a' && c <= 'z') continue;              // 원소 두 번째 글자 (He, Na…)
            if (Character.isDigit(c)) continue;              // 첨자·계수
            if (c == '+' || c == '-' || c == '→'        // 연산자·화살표
                    || c == '(' || c == ')') continue;
            if (Character.isWhitespace(c)) continue;
            return false;                                    // 그 외 문자(한글 등) → 화학식 아님
        }
        return sawUpper;
    }

    private static boolean hasSourceStyleBoundary(ResolvedRun a, ResolvedRun b) {
        if (a == null || b == null) return false;
        if (!sameNullable(a.fontFamily(), b.fontFamily())) return true;
        if (!sameNullable(a.fontStyle(), b.fontStyle())) return true;
        if (!sameNullable(a.fontSize(), b.fontSize())) return true;
        if (!sameNullable(a.fillColor(), b.fillColor())) return true;
        if (!sameNullable(a.charStyle(), b.charStyle())) return true;
        if (!sameNullable(a.tracking(), b.tracking())) return true;
        if (!sameNullable(a.horizontalScale(), b.horizontalScale())) return true;
        if (!sameNullable(a.verticalScale(), b.verticalScale())) return true;
        if (!sameNullable(a.baselineShift(), b.baselineShift())) return true;
        if (!sameNullable(a.position(), b.position())) return true;
        if (!sameNullable(a.underline(), b.underline())) return true;
        return !sameNullable(a.strikeThru(), b.strikeThru());
    }

    private static boolean sameNullable(Object a, Object b) {
        if (a == null) return b == null;
        return a.equals(b);
    }

    private static double composedLineTop(ResolvedTextFrame tf, int paragraphIndex) {
        if (tf == null || tf.composedLines() == null) return 0.0;
        double top = Double.POSITIVE_INFINITY;
        for (ResolvedTextFrame.ComposedLine line : tf.composedLines()) {
            if (line == null || line.paraIndex() != paragraphIndex) continue;
            double[] b = line.bounds();
            if (!validBounds(b)) continue;
            top = Math.min(top, b[0]);
        }
        return Double.isFinite(top) ? top : 0.0;
    }

    private static int textRangeVisibleLength(String text, int start, int end) {
        if (text == null || end <= start) return 0;
        int safeStart = Math.max(0, Math.min(start, text.length()));
        int safeEnd = Math.max(safeStart, Math.min(end, text.length()));
        return textRangeVisibleText(text.substring(safeStart, safeEnd)).length();
    }

    private static int firstNonRangeWhitespace(String text) {
        if (text == null || text.isEmpty()) return 0;
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (!isRangeWhitespaceOrControl(ch)) break;
            i++;
        }
        return i;
    }

    private static int lastNonRangeWhitespace(String text) {
        if (text == null || text.isEmpty()) return 0;
        int end = text.length();
        while (end > 0) {
            char ch = text.charAt(end - 1);
            if (!isRangeWhitespaceOrControl(ch)) break;
            end--;
        }
        return end;
    }

    private static boolean isRangeWhitespaceOrControl(char ch) {
        return Character.isWhitespace(ch) || ch == '\u0007' || ch == '\u0008'
                || ch == '\uFFFC' || ch == '\u0003';
    }

    private static final class TextRangeShellBundle {
        final List<ResolvedPageItem> shells;
        final List<TextRangeCandidate> ranges;

        TextRangeShellBundle(
                List<ResolvedPageItem> shells,
                List<TextRangeCandidate> ranges) {
            this.shells = shells;
            this.ranges = ranges;
        }
    }

    private static final class TextRangeCandidate {
        final ResolvedTextFrame textFrame;
        final TextRangeRef range;
        final double lineTop;
        final int pageIndex;

        TextRangeCandidate(
                ResolvedTextFrame textFrame,
                TextRangeRef range,
                double lineTop,
                int pageIndex) {
            this.textFrame = textFrame;
            this.range = range;
            this.lineTop = lineTop;
            this.pageIndex = pageIndex;
        }
    }

    private double[] sourceBounds(ResolvedPageItem item) {
        if (item == null) return null;
        double[] b = item.pageRelativeBounds();
        if (!validBounds(b)) b = item.geometricBounds();
        return validBounds(b) ? b : null;
    }

    private static boolean validBounds(double[] b) {
        return b != null && b.length >= 4
                && Double.isFinite(b[0]) && Double.isFinite(b[1])
                && Double.isFinite(b[2]) && Double.isFinite(b[3])
                && b[2] > b[0] && b[3] > b[1];
    }

    private static double centerX(double[] b) {
        return validBounds(b) ? (b[1] + b[3]) / 2.0 : 0.0;
    }

    private static double centerY(double[] b) {
        return validBounds(b) ? (b[0] + b[2]) / 2.0 : 0.0;
    }

    private TextLayoutContract sourceTextWrapContractForTextFrame(
            ResolvedTextFrame tf,
            int textFrameId) {
        if (tf == null || textFrameId < 0) return null;
        if (tf.sourceHidden()) return null;
        if (data != null && data.isTextOwnedByIndesignPng(tf.id())) return null;
        if (!textFrameHasVisibleSemanticText(tf)) return null;
        if (tf.composedLines() == null || tf.composedLines().size() < 2) return null;
        if (isVerticalComposedTextFrameSource(tf)) return null;
        SourceWrapEvidence evidence = sourceWrapEvidence(tf);
        if (evidence == null) return null;
        return new TextLayoutContract(
                TextLayoutContract.SOURCE_TEXT_WRAP,
                "resolved.composedLines",
                textFrameId,
                evidence.wrapSide,
                new int[0],
                new String[0],
                evidence.lineCount,
                "source_composed_wrap_indent");
    }

    private SourceWrapEvidence sourceWrapEvidence(ResolvedTextFrame tf) {
        if (tf == null || tf.composedLines() == null || tf.composedLines().isEmpty()) return null;
        double[] bounds = tf.pageRelativeBounds();
        if (bounds == null || bounds.length < 4) bounds = tf.geometricBounds();
        if (bounds == null || bounds.length < 4) return null;
        double frameWidth = Math.abs(bounds[3] - bounds[1]);
        if (frameWidth <= 0.0) return null;
        double threshold = Math.max(24.0, frameWidth * 0.18);

        int leftCount = 0;
        int rightCount = 0;
        int visibleCount = 0;
        Map<Integer, int[]> countsByPara = new HashMap<>();
        for (ResolvedTextFrame.ComposedLine line : tf.composedLines()) {
            if (line == null || !hasVisibleTextExcludingObjectControls(line.text())) continue;
            visibleCount++;
            int[] counts = countsByPara.computeIfAbsent(line.paraIndex(), k -> new int[2]);
            if (line.wrapIndentLeft() >= threshold) {
                leftCount++;
                counts[0]++;
            }
            if (line.wrapIndentRight() >= threshold) {
                rightCount++;
                counts[1]++;
            }
        }
        if (visibleCount < 2) return null;

        boolean paraHasRepeatedLeft = false;
        boolean paraHasRepeatedRight = false;
        for (int[] counts : countsByPara.values()) {
            if (counts == null) continue;
            if (counts[0] >= 2) paraHasRepeatedLeft = true;
            if (counts[1] >= 2) paraHasRepeatedRight = true;
        }
        if (!paraHasRepeatedLeft && !paraHasRepeatedRight) return null;

        String side;
        if (paraHasRepeatedLeft && paraHasRepeatedRight) {
            side = "BOTH";
        } else if (paraHasRepeatedLeft) {
            side = "LEFT";
        } else {
            side = "RIGHT";
        }
        int lineCount = Math.max(leftCount, rightCount);
        return new SourceWrapEvidence(side, lineCount);
    }

    private int findHwpxTextSlotPlanIndexForTextFrame(int textFrameId) {
        if (textFrameId < 0) return -1;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null) continue;
            if (!contains(plan.ownedTextFrameIds, textFrameId) && plan.domId != textFrameId) continue;
            if (plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                    && plan.materialization == Materialization.HWPX_TEXT) {
                return i;
            }
        }
        return -1;
    }

    private boolean addSourceTextWrapTextFramePlan(
            ResolvedTextFrame tf,
            int textFrameId,
            TextLayoutContract contract) {
        if (tf == null || textFrameId < 0 || contract == null) return false;
        TextAction textAction = TextAction.OWNED_BY_HWPX_TEXT;
        VisualAction visualAction = VisualAction.DROP_VISUAL;
        Placement placement = placementOfTextFrame(tf, textFrameId, textAction, visualAction);
        CoordinateSpace coordinateSpace = placement == Placement.INLINE
                ? CoordinateSpace.STORY_FLOW
                : CoordinateSpace.PAGE;
        ObjectPlan plan = new ObjectPlan(
                textFrameId,
                "text_frame:source_text_wrap",
                tf.pageIndex(),
                textAction,
                visualAction,
                VisualLayer.CONTENT_VISUAL,
                placement,
                null,
                new int[] { textFrameId },
                new int[] { textFrameId },
                new int[0],
                new int[] { textFrameId },
                new int[0],
                "p" + tf.pageIndex() + ":tf:" + textFrameId,
                Materialization.HWPX_TEXT,
                coordinateSpace,
                null,
                textFrameSourceZOrder(tf),
                "source_text_wrap_text_frame",
                null,
                textFramePlanBounds(tf, textFrameId, false),
                tf.layerId(),
                tf.layerName(),
                tf.layerIndex());
        plan.withTextLayoutContract(contract);
        plans.add(plan);
        return true;
    }

    private static boolean isVerticalComposedTextFrameSource(ResolvedTextFrame tf) {
        if (tf == null || tf.composedLines() == null || tf.composedLines().isEmpty()) return false;
        double[] frameBounds = tf.pageRelativeBounds();
        if (frameBounds == null || frameBounds.length < 4) frameBounds = tf.geometricBounds();
        if (frameBounds == null || frameBounds.length < 4) return false;
        double frameW = Math.abs(frameBounds[3] - frameBounds[1]);
        double frameH = Math.abs(frameBounds[2] - frameBounds[0]);
        if (frameW <= 0.0 || frameH <= 0.0 || frameH <= frameW * 1.2) return false;

        int checked = 0;
        int verticalLike = 0;
        for (ResolvedTextFrame.ComposedLine line : tf.composedLines()) {
            if (line == null || line.bounds() == null || line.bounds().length < 4) continue;
            if (!hasVisibleTextExcludingObjectControls(line.text())) continue;
            double[] b = line.bounds();
            double lineW = Math.abs(b[3] - b[1]);
            double lineH = Math.abs(b[2] - b[0]);
            if (lineW <= 0.0 || lineH <= 0.0) continue;
            checked++;
            if (lineH > lineW * 1.8) {
                verticalLike++;
            }
        }
        return checked > 0 && verticalLike == checked;
    }

    private static boolean hasVisibleTextExcludingObjectControls(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\uFFFC' || ch == '\u0003' || ch == '\u0007' || ch == '\b') continue;
            if (Character.isWhitespace(ch)) continue;
            return true;
        }
        return false;
    }

    private static final class SourceWrapEvidence {
        final String wrapSide;
        final int lineCount;

        SourceWrapEvidence(String wrapSide, int lineCount) {
            this.wrapSide = wrapSide;
            this.lineCount = lineCount;
        }
    }

    private ObjectPlan canonicalizeImportedPreplannedObjectPlan(ObjectPlan plan) {
        if (isPlannerDeclaredInlineGraphicLineTextStyleMarker(plan)) {
            warnImportedPlanRepairSuppressed(plan,
                    "IMPORTED_INLINE_TEXT_STYLE_MARKER_REPAIR_SUPPRESSED",
                    "expectedVisualAction=ABSORB_TEXT_STYLE expectedStyleSourceObjectIds="
                            + ObjectPlan.intArrayJson(visualSourceIds(plan)));
            return plan;
        }
        if (isInlineTextStyleMarkerSourcePlan(plan)
                && plan.visualAction != VisualAction.ABSORB_TEXT_STYLE) {
            return absorbTextStyleMarkerSource(plan,
                    "direct_story_inline_text_style_marker");
        }
        if (isImportedFloatingDirectStoryFlowInlineVisual(plan)) {
            if (isStoryFlowInlineShellDecorationSource(plan)) {
                return dropStoryFlowInlineShellDecoration(plan,
                        "story_flow_inline_shell_decoration_covered_by_text_shell_or_page_plane");
            }
            if (hasTextStyleMarkerSource(plan)) {
                return absorbTextStyleMarkerSource(plan,
                        "direct_story_inline_text_style_marker");
            }
            if (isCompactDirectStoryFlowInlineVisual(plan)) {
                return plan
                        .withPlacementAndCoordinateSpace(Placement.INLINE, CoordinateSpace.STORY_FLOW)
                        .withVisualAction(VisualAction.PLACE_INLINE_PNG,
                                "direct_compact_story_inline_visual")
                        .withMaterialization(Materialization.EXTRACTED_PNG_VECTOR);
            }
            ObjectPlan repaired = plan
                    .withPlacementAndCoordinateSpace(Placement.FLOATING, CoordinateSpace.PAGE)
                    .withVisualAction(VisualAction.PLACE_FLOATING_PNG,
                            "direct_page_positioned_story_anchor_visual")
                    .withMaterialization(Materialization.EXTRACTED_PNG_VECTOR);
            if (isThinInlineTextStyleMarkerPlanBounds(repaired, repaired.sourceObjectIds)
                    || isThinPagePositionedTextStyleMarkerPlanBounds(repaired)) {
                repaired = repaired.withVisualLayer(VisualLayer.LABEL_BACKDROP);
            }
            return repaired;
        }
        if (isImportedStoryFlowInlineVisualWithPagePosition(plan)) {
            warnImportedPlanRepairSuppressed(plan,
                    "IMPORTED_STORY_FLOW_INLINE_PAGE_POSITION_REPAIR_SUPPRESSED",
                    "expected repair was FLOATING/PAGE based on anchored page position");
            return plan;
        }
        if (isClosedInlineCarrierVisualPlan(plan)) {
            warnImportedPlanRepairSuppressed(plan,
                    "IMPORTED_CLOSED_INLINE_CARRIER_REPAIR_SUPPRESSED",
                    "expected repair was DROP_TEXT/PLACE_INLINE_PNG/INLINE/STORY_FLOW");
            return plan;
        }
        if (pageTextlessGraphicGroupNeedsContractRepair(plan)) {
            warnImportedPlanRepairSuppressed(plan,
                    "IMPORTED_PAGE_TEXTLESS_GRAPHIC_GROUP_REPAIR_SUPPRESSED",
                    "expected repair was DROP_TEXT/PLACE_FLOATING_PNG/PAGE_BACKGROUND/FLOATING/PAGE/PAGE_PLANE_PNG");
            return plan;
        }
        if (isImportedStoryFlowInlineShellSlotWithPagePosition(plan)) {
            warnImportedPlanRepairSuppressed(plan,
                    "IMPORTED_STORY_FLOW_INLINE_SHELL_SLOT_PAGE_POSITION_REPAIR_SUPPRESSED",
                    "expected repair was PLACE_TEXT_SHELL/FLOATING/PAGE based on anchored page position");
            return plan;
        }
        if (isPlannerDeclaredStandaloneInlineVisual(plan)) {
            warnImportedPlanRepairSuppressed(plan,
                    "IMPORTED_STANDALONE_INLINE_VISUAL_REPAIR_SUPPRESSED",
                    "expected repair was PLACE_INLINE_PNG");
        }
        return plan;
    }

    private boolean isImportedFloatingDirectStoryFlowInlineVisual(ObjectPlan plan) {
        if (plan == null) return false;
        if (!hasInlineObjectPlanSignal(plan)) return false;
        if (plan.placement != Placement.FLOATING) return false;
        if (effectiveCoordinateSpace(plan) != CoordinateSpace.PAGE) return false;
        if (plan.textAction != TextAction.DROP_TEXT) return false;
        if (plan.visualAction != VisualAction.PLACE_FLOATING_PNG) return false;
        if (plan.materialization != Materialization.EXTRACTED_PNG_VECTOR) return false;
        if (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) return false;
        return directStoryFlowInlineGraphicAnchorId(plan) > 0;
    }

    private boolean isInlineTextStyleMarkerSourcePlan(ObjectPlan plan) {
        if (plan == null) return false;
        if (!hasInlineObjectPlanSignal(plan)) return false;
        if (plan.textAction != TextAction.DROP_TEXT) return false;
        if (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) return false;
        return hasTextStyleMarkerSource(plan);
    }

    private int directStoryFlowInlineGraphicAnchorId(ObjectPlan plan) {
        if (plan == null || data == null) return -1;
        if (isDirectStoryFlowInlineGraphicAnchor(plan.domId, plan.sourceObjectIds)) {
            return plan.domId;
        }
        int[] visualIds = visualSourceIds(plan);
        if (visualIds != null) {
            for (int visualId : visualIds) {
                if (isDirectStoryFlowInlineGraphicAnchor(visualId, plan.sourceObjectIds)) {
                    return visualId;
                }
            }
        }
        if (plan.sourceObjectIds != null) {
            for (int sourceId : plan.sourceObjectIds) {
                if (isDirectStoryFlowInlineGraphicAnchor(sourceId, plan.sourceObjectIds)) {
                    return sourceId;
                }
            }
        }
        return -1;
    }

    private ObjectPlan absorbTextStyleMarkerSource(ObjectPlan plan, String reason) {
        int[] styleIds = textStyleMarkerSourceIds(plan);
        if (styleIds.length == 0) styleIds = visualSourceIds(plan);
        if (styleIds.length == 0) styleIds = plan.sourceObjectIds;
        int[] ownedTextFrameIds = storyTextFrameIdsForInlineAnchor(plan);
        if (ownedTextFrameIds.length == 0) ownedTextFrameIds = plan.ownedTextFrameIds;
        return plan
                .withPlacementAndCoordinateSpace(Placement.INLINE, CoordinateSpace.STORY_FLOW)
                .withVisualAction(VisualAction.ABSORB_TEXT_STYLE, reason)
                .withMaterialization(Materialization.HWPX_TEXT)
                .withVisualSourceObjectIds(new int[0])
                .withStyleSourceObjectIds(styleIds)
                .withOwnedTextFrameIds(ownedTextFrameIds);
    }

    private int[] storyTextFrameIdsForInlineAnchor(ObjectPlan plan) {
        if (plan == null || data == null) return new int[0];
        LinkedHashSet<Integer> anchorIds = new LinkedHashSet<>();
        int direct = directStoryFlowInlineGraphicAnchorId(plan);
        if (direct > 0) anchorIds.add(direct);
        if (plan.sourceObjectIds != null) {
            for (int id : plan.sourceObjectIds) anchorIds.add(id);
        }
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        for (ResolvedStory story : data.stories()) {
            if (story == null || story.paragraphs() == null) continue;
            for (int paraIndex = 0; paraIndex < story.paragraphs().size(); paraIndex++) {
                ResolvedParagraph paragraph = story.paragraphs().get(paraIndex);
                if (!paragraphHasInlineAnchor(paragraph, anchorIds)) continue;
                for (ResolvedTextFrame tf : data.getTextFramesForStory(story.id())) {
                    if (tf == null || tf.id() == null) continue;
                    if (paraIndex < tf.paragraphStart() || paraIndex > tf.paragraphEnd()) continue;
                    try {
                        out.add(Integer.parseInt(tf.id()));
                    } catch (NumberFormatException ignored) {
                        // Non-numeric synthetic ids cannot be encoded in ObjectPlan ownedTextFrameIds.
                    }
                }
            }
        }
        int[] ids = new int[out.size()];
        int index = 0;
        for (Integer id : out) ids[index++] = id;
        return ids;
    }

    private boolean paragraphHasInlineAnchor(ResolvedParagraph paragraph, Set<Integer> anchorIds) {
        if (paragraph == null || paragraph.runs() == null || anchorIds == null || anchorIds.isEmpty()) {
            return false;
        }
        for (ResolvedRun run : paragraph.runs()) {
            if (run == null || !run.isInlineAnchor() || run.anchoredObjectId() == null) continue;
            if (anchorIds.contains(run.anchoredObjectId())) return true;
        }
        return false;
    }

    private boolean isCompactDirectStoryFlowInlineVisual(ObjectPlan plan) {
        if (plan == null) return false;
        if (hasTextStyleMarkerSource(plan)) return false;
        if (directStoryFlowInlineGraphicAnchorId(plan) <= 0) return false;
        if (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) return false;
        if (plan.sourceObjectIds == null || plan.sourceObjectIds.length == 0) return false;
        if (plan.sourceObjectIds.length > 6) return false;
        double[] b = plan.bounds;
        if (b == null || b.length < 4) return false;
        double w = Math.abs(b[3] - b[1]);
        double h = Math.abs(b[2] - b[0]);
        if (w <= 0.0 || h <= 0.0) return false;
        double longAxis = Math.max(w, h);
        double shortAxis = Math.min(w, h);
        return longAxis <= 18.0 && shortAxis <= 6.5;
    }

    private void warnImportedPlanRepairSuppressed(ObjectPlan plan, String code, String detail) {
        warn(code, "plan=" + planRef(plan) + " " + detail);
    }

    private boolean isImportedStoryFlowInlineVisualWithPagePosition(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.placement != Placement.INLINE) return false;
        if (effectiveCoordinateSpace(plan) != CoordinateSpace.STORY_FLOW) return false;
        if (plan.visualAction != VisualAction.PLACE_INLINE_PNG
                && plan.visualAction != VisualAction.PLACE_TEXT_SHELL) {
            return false;
        }
        return hasIdmlAnchoredPagePositionSource(plan);
    }

    private boolean isImportedStoryFlowInlineShellSlotWithPagePosition(ObjectPlan plan) {
        if (plan == null) return false;
        if (!isShellOnlyVisualSlot(plan)) return false;
        if (plan.placement != Placement.INLINE) return false;
        if (effectiveCoordinateSpace(plan) != CoordinateSpace.STORY_FLOW) return false;
        if (plan.visualAction != VisualAction.PLACE_INLINE_PNG
                && plan.visualAction != VisualAction.PLACE_TEXT_SHELL) {
            return false;
        }
        if (!hasIdmlAnchoredPagePositionSource(plan)) return false;
        return true;
    }

    private boolean hasIdmlAnchoredPagePositionSource(ObjectPlan plan) {
        if (plan == null || data == null) return false;
        if (plan.domId >= 0 && hasResolvedAnchoredPagePosition(plan.domId)) return true;
        if (plan.domId >= 0 && hasIdmlAnchoredPagePosition(plan.domId)) return true;
        for (int sourceId : plan.sourceObjectIds) {
            if (hasResolvedAnchoredPagePosition(sourceId)) return true;
            if (hasIdmlAnchoredPagePosition(sourceId)) return true;
        }
        for (int sourceId : visualSourceIds(plan)) {
            if (hasResolvedAnchoredPagePosition(sourceId)) return true;
            if (hasIdmlAnchoredPagePosition(sourceId)) return true;
        }
        return false;
    }

    private boolean hasResolvedAnchoredPagePosition(int domId) {
        if (data == null || domId < 0) return false;
        ResolvedPageItem item = data.getPageItem(String.valueOf(domId));
        if (item == null) return false;
        String storyAnchorPlacement = safe(item.storyAnchorPlacement()).toUpperCase(Locale.ROOT);
        String anchoredPosition = safe(item.anchoredPosition()).toUpperCase(Locale.ROOT);
        return "FLOATING_ANCHORED".equals(storyAnchorPlacement)
                || "ANCHORED".equals(anchoredPosition);
    }

    private boolean isPlannerDeclaredStandaloneInlineVisual(ObjectPlan plan) {
        if (plan == null) return false;
        if (!safe(plan.kind).startsWith("planner_declared_rendered:")) return false;
        if (!"planner_declared_object_plan".equals(safe(plan.reason))) return false;
        if (!hasInlineObjectPlanSignal(plan)) return false;
        if (plan.placement != Placement.INLINE) return false;
        if (effectiveCoordinateSpace(plan) != CoordinateSpace.STORY_FLOW) return false;
        if (plan.textAction != TextAction.DROP_TEXT) return false;
        if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) return false;
        if (safe(plan.file).isEmpty()) return false;
        return plan.visualSourceObjectIds != null && plan.visualSourceObjectIds.length > 0;
    }

    private boolean isPlannerDeclaredInlineGraphicLineTextStyleMarker(ObjectPlan plan) {
        if (plan == null || data == null) return false;
        if (!hasInlineObjectPlanSignal(plan)) return false;
        String reason = safe(plan.reason);
        if (!"planner_declared_object_plan".equals(reason)
                && !reason.startsWith("diagnostic_from_planner_bundle:")
                && !"inline_graphic_only".equals(reason)) {
            return false;
        }
        if (plan.placement != Placement.INLINE) return false;
        if (effectiveCoordinateSpace(plan) != CoordinateSpace.STORY_FLOW) return false;
        if (plan.textAction != TextAction.DROP_TEXT) return false;
        if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL
                && plan.visualAction != VisualAction.PLACE_INLINE_PNG) {
            return false;
        }
        if (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) return false;
        int[] visualIds = visualSourceIds(plan);
        if (visualIds.length == 0) return false;
        if (!hasVisibleTextStyleMarkerPaintSource(visualIds)
                && !hasTextStyleMarkerSource(plan)) return false;
        if (isThinInlineTextStyleMarkerPlanBounds(plan, visualIds)) return true;
        for (int visualId : visualIds) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(visualId));
            if (!isInlineTextStyleMarkerVector(item, plan.bounds)) return false;
            if (hasDescendantTextFrameExcluding(String.valueOf(visualId),
                    new HashSet<>(), new HashSet<>())) {
                return false;
            }
        }
        for (int sourceId : plan.sourceObjectIds) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item == null || contains(visualIds, sourceId)) continue;
            String type = safe(item.type());
            if ("Group".equals(type)) continue;
            if (isInlineTextStyleMarkerVector(item, plan.bounds)) continue;
            if (isInlineTextStyleMarkerCompanionVector(item)) continue;
            return false;
        }
        return true;
    }

    private boolean hasVisibleTextStyleMarkerPaintSource(int[] sourceIds) {
        if (sourceIds == null || data == null) return false;
        for (int sourceId : sourceIds) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item == null) continue;
            if (isTextStyleMarkerPaintName(item.fillColorName())
                    || isTextStyleMarkerPaintName(item.strokeColorName())) {
                return true;
            }
            if (!isNoneColor(item.fillColorName()) && !isPaperColor(item.fillColorName())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasTextStyleMarkerSource(ObjectPlan plan) {
        return textStyleMarkerSourceIds(plan).length > 0;
    }

    private int[] textStyleMarkerSourceIds(ObjectPlan plan) {
        if (plan == null || data == null) return new int[0];
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        collectTextStyleMarkerSourceIds(plan.sourceObjectIds, out);
        collectTextStyleMarkerSourceIds(visualSourceIds(plan), out);
        int[] ids = new int[out.size()];
        int index = 0;
        for (Integer id : out) ids[index++] = id;
        return ids;
    }

    private void collectTextStyleMarkerSourceIds(int[] ids, LinkedHashSet<Integer> out) {
        if (ids == null || out == null || data == null) return;
        for (int id : ids) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (item == null) continue;
            if (isTextStyleMarkerPaintName(item.fillColorName())
                    || isTextStyleMarkerPaintName(item.strokeColorName())) {
                out.add(id);
            }
        }
    }

    private boolean isTextStyleMarkerPaintName(String name) {
        String value = safe(name).toLowerCase(Locale.ROOT);
        return value.contains("형광펜")
                || value.contains("highlight")
                || value.contains("highlighter")
                || value.contains("underline")
                || value.contains("밑줄")
                || value.contains("강조")
                || value.contains("emphasis");
    }

    private boolean isThinInlineTextStyleMarkerPlanBounds(ObjectPlan plan, int[] visualIds) {
        if (plan == null || visualIds == null || visualIds.length == 0) return false;
        if (plan.sourceObjectIds == null || plan.sourceObjectIds.length == 0) return false;
        if (plan.sourceObjectIds.length > 4) return false;
        double[] b = plan.bounds;
        if (b == null || b.length < 4) return false;
        double w = Math.abs(b[3] - b[1]);
        double h = Math.abs(b[2] - b[0]);
        double longAxis = Math.max(w, h);
        double shortAxis = Math.min(w, h);
        if (longAxis < 6.0 || shortAxis > 3.2) return false;
        if (longAxis / Math.max(0.1, shortAxis) < 3.0) return false;
        for (int visualId : visualIds) {
            if (hasDescendantTextFrameExcluding(String.valueOf(visualId),
                    new HashSet<>(), new HashSet<>())) {
                return false;
            }
        }
        return true;
    }

    private boolean isThinPagePositionedTextStyleMarkerPlanBounds(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.sourceObjectIds == null || plan.sourceObjectIds.length == 0) return false;
        if (plan.sourceObjectIds.length > 4) return false;
        double[] b = plan.bounds;
        if (b == null || b.length < 4) return false;
        double w = Math.abs(b[3] - b[1]);
        double h = Math.abs(b[2] - b[0]);
        double longAxis = Math.max(w, h);
        double shortAxis = Math.min(w, h);
        if (longAxis < 6.0 || shortAxis > 4.0) return false;
        if (longAxis / Math.max(0.1, shortAxis) < 3.0) return false;
        for (int sourceId : plan.sourceObjectIds) {
            if (hasDescendantTextFrameExcluding(String.valueOf(sourceId),
                    new HashSet<>(), new HashSet<>())) {
                return false;
            }
        }
        return true;
    }

    private boolean isInlineTextStyleMarkerVector(ResolvedPageItem item, double[] fallbackBounds) {
        if (item == null) return false;
        if (!item.isInline()) return false;
        if (isNoneColor(item.fillColorName()) || isPaperColor(item.fillColorName())) return false;
        String type = safe(item.type());
        if (!"GraphicLine".equals(type)
                && !"Polygon".equals(type)
                && !"Rectangle".equals(type)) {
            return false;
        }
        double[] b = boundsOf(item);
        if (b == null || b.length < 4) b = fallbackBounds;
        if (b == null || b.length < 4) return true;
        double w = Math.abs(b[3] - b[1]);
        double h = Math.abs(b[2] - b[0]);
        double longAxis = Math.max(w, h);
        double shortAxis = Math.min(w, h);
        if (longAxis < 6.0 || shortAxis > 3.2) return false;
        return longAxis / Math.max(0.1, shortAxis) >= 3.0;
    }

    private boolean isInlineTextStyleMarkerCompanionVector(ResolvedPageItem item) {
        if (item == null) return false;
        if (!item.isInline()) return false;
        String type = safe(item.type());
        if (!"GraphicLine".equals(type)
                && !"Polygon".equals(type)
                && !"Rectangle".equals(type)) {
            return false;
        }
        if (hasDescendantTextFrameExcluding(item.id(), new HashSet<>(), new HashSet<>())) {
            return false;
        }
        double[] b = boundsOf(item);
        if (b == null || b.length < 4) return true;
        double w = Math.abs(b[3] - b[1]);
        double h = Math.abs(b[2] - b[0]);
        return Math.max(w, h) <= 8.0 && Math.min(w, h) <= 3.2;
    }

    private boolean hasInlineObjectPlanSignal(ObjectPlan plan) {
        if (plan == null) return false;
        if ("pass.inline_objects".equals(safe(plan.planPassId))) return true;
        if (safe(plan.sourceBundleKey).contains("pass.inline_objects")) return true;
        if (safe(plan.kind).contains(":INLINE_OBJECT")) return true;
        if (safe(plan.kind).contains(":inline_object")) return true;
        return safe(plan.file).startsWith("rendered_frames/inline_");
    }

    private void recordPlannerDeclaredInlineTextShellContract(ObjectPlan plan) {
        if (!isPlannerDeclaredInlineTextShellContract(plan)) return;
        if (plan.renderId == null) return;
        plannerDeclaredInlineTextShellContracts.put(plan.renderId, plan);
    }

    private boolean isPlannerDeclaredInlineTextShellContract(ObjectPlan plan) {
        if (plan == null) return false;
        if (!safe(plan.kind).startsWith("planner_declared_rendered:")) return false;
        if (!"planner_declared_object_plan".equals(safe(plan.reason))) return false;
        if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.materialization != Materialization.EXTRACTED_PNG_VECTOR) return false;
        if (plan.placement != Placement.INLINE) return false;
        if (effectiveCoordinateSpace(plan) != CoordinateSpace.STORY_FLOW) return false;
        if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) return false;
        if (plan.renderId == null) return false;
        return planHasExecutableRenderedShellSource(plan);
    }

    private boolean planHasExecutableRenderedShellSource(ObjectPlan plan) {
        if (plan == null || safe(plan.file).isEmpty()) return false;
        RenderedGroup rendered = renderedGroupForPlan(plan);
        int[] exported = rendered != null ? rendered.exportSourceObjectIds() : null;
        if (exported != null && exported.length > 0) return true;
        return plan.visualSourceObjectIds != null && plan.visualSourceObjectIds.length > 0;
    }

    private void restorePlannerDeclaredInlineTextShellContracts() {
        if (plannerDeclaredInlineTextShellContracts.isEmpty()) return;
        LinkedHashSet<Integer> restoredTextFrames = new LinkedHashSet<>();
        int restored = 0;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan current = plans.get(i);
            if (current == null || current.renderId == null) continue;
            ObjectPlan contract = plannerDeclaredInlineTextShellContracts.get(current.renderId);
            if (contract == null) continue;
            if (sameInlineTextShellContract(current, contract)) {
                addAll(contract.ownedTextFrameIds, restoredTextFrames);
                continue;
            }
            plans.set(i, contract);
            addAll(contract.ownedTextFrameIds, restoredTextFrames);
            restored++;
        }
        if (!restoredTextFrames.isEmpty()) {
            alignOwnedTextFramePlans(toIntArray(restoredTextFrames), Placement.INLINE);
        }
        ConversionTiming.metric("stage1.ownershipPlanner.plannerDeclaredInlineTextShellContracts",
                plannerDeclaredInlineTextShellContracts.size());
        ConversionTiming.metric("stage1.ownershipPlanner.plannerDeclaredInlineTextShellContracts.restored",
                restored);
    }

    private static boolean sameInlineTextShellContract(ObjectPlan current, ObjectPlan contract) {
        if (current == null || contract == null) return false;
        return current.textAction == contract.textAction
                && current.visualAction == contract.visualAction
                && current.materialization == contract.materialization
                && current.placement == contract.placement
                && effectiveCoordinateSpace(current) == effectiveCoordinateSpace(contract)
                && sameIntSet(current.ownedTextFrameIds, contract.ownedTextFrameIds)
                && sameIntSet(visualSourceIds(current), visualSourceIds(contract));
    }

    private boolean isPageObject(RenderedGroup rg) {
        return VisualLayeringRules.isPageObject(rg);
    }

    private Set<Integer> collectEditableLabelShells(List<RenderedGroup> floatingItems) {
        Set<Integer> ids = new HashSet<>();
        if (ctx == null || ctx.resolvedData == null || floatingItems == null) return ids;
        for (RenderedGroup rg : floatingItems) {
            if (!isEditableLabelShellCandidate(rg)) continue;
            ids.add(rg.id());
        }
        return ids;
    }

    private static double[] boundsOf(ResolvedTextFrame tf) {
        if (tf == null) return null;
        double[] b = tf.pageRelativeBounds();
        if (b != null && b.length >= 4) return b;
        return tf.geometricBounds();
    }

    private boolean isEditableLabelShellCandidate(RenderedGroup rg) {
        return VisualLayeringRules.isEditableLabelShellCandidate(rg);
    }

    private boolean shouldDecomposeToEditableLabelShell(
            RenderedGroup rg, Set<Integer> editableLabelShellIds,
            Map<Integer, RenderedGroup> idToRendered) {
        if (rg == null || editableLabelShellIds == null || editableLabelShellIds.isEmpty()) return false;
        String reason = rg.reason();
        if ("visual_label_text_hidden_shell".equals(reason)
                || "editable_composite_text_hidden_shell".equals(reason)) return false;
        if (rg.childIds() == null || rg.childIds().length == 0) return false;
        boolean hasProtectedShell = false;
        for (int cid : rg.childIds()) {
            if (editableLabelShellIds.contains(cid)) {
                hasProtectedShell = true;
                break;
            }
        }
        if (!hasProtectedShell) return false;
        if (hasSubstantialVisualOutsideEditableLabelShell(rg, editableLabelShellIds, idToRendered)) {
            return false;
        }
        return "visual_label_indesign_png".equals(reason)
                || (reason != null && reason.contains("text_hidden"));
    }

    private boolean hasSubstantialVisualOutsideEditableLabelShell(
            RenderedGroup parent, Set<Integer> editableLabelShellIds,
            Map<Integer, RenderedGroup> idToRendered) {
        if (parent == null || editableLabelShellIds == null || editableLabelShellIds.isEmpty()
                || parent.childIds() == null || parent.childIds().length == 0) {
            return false;
        }
        double[] pb = parent.bounds();
        if (pb == null || pb.length < 4) return false;
        double parentW = Math.max(0, pb[3] - pb[1]);
        double parentH = Math.max(0, pb[2] - pb[0]);
        double parentArea = parentW * parentH;
        if (parentArea <= 0) return false;

        for (int cid : parent.childIds()) {
            if (!editableLabelShellIds.contains(cid)) continue;
            RenderedGroup child = idToRendered != null ? idToRendered.get(cid) : null;
            if (child == null) continue;
            double[] cb = child.bounds();
            if (cb == null || cb.length < 4) continue;
            double childW = Math.max(0, cb[3] - cb[1]);
            double childH = Math.max(0, cb[2] - cb[0]);
            double childArea = childW * childH;
            if (childArea <= 0) continue;

            double below = pb[2] - cb[2];
            double above = cb[0] - pb[0];
            double left = cb[1] - pb[1];
            double right = pb[3] - cb[3];
            boolean hasLargeFrameRemainder = below >= Math.max(8.0, childH * 1.25)
                    || above >= Math.max(8.0, childH * 1.25)
                    || left >= Math.max(12.0, childW * 0.35)
                    || right >= Math.max(12.0, childW * 0.35);
            boolean parentMuchLarger = parentArea / childArea >= 2.4
                    || parentH / Math.max(childH, 0.1) >= 2.2;
            if (hasLargeFrameRemainder && parentMuchLarger) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldPreserveEditableLabelShell(
            RenderedGroup rg, Set<Integer> editableLabelShellIds) {
        if (rg == null) return false;
        if (editableLabelShellIds != null && editableLabelShellIds.contains(rg.id())) return true;
        if (isAtomicOwnershipRootTextHiddenShell(rg)) return true;
        if (!isEditableLabelShellCandidate(rg)) return false;
        if (!"hwpx_tf".equals(rg.textOwner())) return false;
        return true;
    }

    private static boolean isAtomicOwnershipRootTextHiddenShell(RenderedGroup rg) {
        return rg != null
                && isAtomicOwnershipRootTextHiddenShellReason(rg.reason())
                && "indesign_png".equals(rg.visualOwner())
                && "hwpx_tf".equals(rg.textOwner())
                && rg.editableTextFrameIds() != null
                && rg.editableTextFrameIds().length > 0
                && !Boolean.TRUE.equals(rg.containsText())
                && !Boolean.TRUE.equals(rg.containsEditableText());
    }

    private static boolean isAtomicOwnershipRootTextHiddenShellReason(String reason) {
        return "atomic_ownership_root_text_hidden_shell".equals(reason)
                || "leaf_group_text_hidden_shell".equals(reason);
    }

    private boolean shouldKeepPairedInlinePageShell(RenderedGroup rg) {
        if (rg == null || !isPageObject(rg)) return false;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (!"hwpx_tf".equals(rg.textOwner())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return false;
        if (!rg.hasEditableTextHiddenFromPng()) return false;
        String reason = rg.reason() == null ? "" : rg.reason();
        if (!reason.contains("text_composite_editable_text_hidden")
                && !reason.contains("editable_composite_text_hidden_shell")
                && !reason.contains("visual_label_text_hidden_shell")
                && !reason.contains("concept_label_shell")) {
            return false;
        }
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double w = b[3] - b[1];
        double h = b[2] - b[0];
        return w >= 6.0 && w <= 95.0
                && h >= 2.0 && h <= 18.0
                && w / Math.max(h, 0.1) >= 1.6;
    }

    private double[] normalizeSpreadBoundsToPage(int pageIdx, double[] bounds) {
        if (ctx == null || ctx.resolvedData == null || bounds == null || bounds.length < 4) {
            return bounds;
        }
        if (ctx.resolvedData.pages() == null || pageIdx < 0 || pageIdx >= ctx.resolvedData.pages().size()) {
            return bounds;
        }
        double[] pb = ctx.resolvedData.pages().get(pageIdx).bounds();
        if (pb == null || pb.length < 4) return bounds;

        double pageTop = pb[0];
        double pageLeft = pb[1];
        double pageBottom = pb[2];
        double pageRight = pb[3];
        double pageWidth = pageRight - pageLeft;
        double pageHeight = pageBottom - pageTop;
        if (pageWidth <= 0.0 || pageHeight <= 0.0) return bounds;

        boolean xInSpreadPage = bounds[1] >= pageLeft - 0.5
                && bounds[3] <= pageRight + 0.5
                && pageLeft > 1.0;
        boolean yInSpreadPage = bounds[0] >= pageTop - 0.5
                && bounds[2] <= pageBottom + 0.5
                && pageTop > 1.0;
        boolean xInRightSpreadPage = !xInSpreadPage
                && pageLeft <= 1.0
                && bounds[1] >= pageWidth - 0.5
                && bounds[3] <= pageWidth * 2.0 + 0.5;
        boolean yInBottomSpreadPage = !yInSpreadPage
                && pageTop <= 1.0
                && bounds[0] >= pageHeight - 0.5
                && bounds[2] <= pageHeight * 2.0 + 0.5;
        double scale = ctx.scaleFactor != 0.0 ? ctx.scaleFactor : 1.0;
        double localPageLeft = pageLeft / scale;
        double localPageTop = pageTop / scale;
        double localPageRight = pageRight / scale;
        double localPageBottom = pageBottom / scale;
        double localPageWidth = pageWidth / scale;
        double localPageHeight = pageHeight / scale;
        boolean xInLocalSpreadPage = !xInSpreadPage
                && bounds[1] >= localPageLeft - 0.5
                && bounds[3] <= localPageRight + 0.5
                && localPageLeft > 1.0;
        boolean yInLocalSpreadPage = !yInSpreadPage
                && bounds[0] >= localPageTop - 0.5
                && bounds[2] <= localPageBottom + 0.5
                && localPageTop > 1.0;
        boolean xInRightLocalSpreadPage = !xInSpreadPage
                && !xInRightSpreadPage
                && !xInLocalSpreadPage
                && pageLeft <= 1.0
                && localPageWidth > 0.0
                && bounds[1] >= localPageWidth - 0.5
                && bounds[3] <= localPageWidth * 2.0 + 0.5;
        boolean yInBottomLocalSpreadPage = !yInSpreadPage
                && !yInBottomSpreadPage
                && !yInLocalSpreadPage
                && pageTop <= 1.0
                && localPageHeight > 0.0
                && bounds[0] >= localPageHeight - 0.5
                && bounds[2] <= localPageHeight * 2.0 + 0.5;
        if (xInRightSpreadPage) {
            pageLeft = pageWidth;
            xInSpreadPage = true;
        } else if (xInLocalSpreadPage) {
            pageLeft = localPageLeft;
            xInSpreadPage = true;
        } else if (xInRightLocalSpreadPage) {
            pageLeft = localPageWidth;
            xInSpreadPage = true;
        }
        if (yInBottomSpreadPage) {
            pageTop = pageHeight;
            yInSpreadPage = true;
        } else if (yInLocalSpreadPage) {
            pageTop = localPageTop;
            yInSpreadPage = true;
        } else if (yInBottomLocalSpreadPage) {
            pageTop = localPageHeight;
            yInSpreadPage = true;
        }
        if (!xInSpreadPage && !yInSpreadPage) return bounds;

        return new double[] {
                bounds[0] - (yInSpreadPage ? pageTop : 0.0),
                bounds[1] - (xInSpreadPage ? pageLeft : 0.0),
                bounds[2] - (yInSpreadPage ? pageTop : 0.0),
                bounds[3] - (xInSpreadPage ? pageLeft : 0.0)
        };
    }

    private double[] clipPageRelativeBoundsToPage(int pageIdx, double[] bounds) {
        if (bounds == null || bounds.length < 4) return bounds;
        double[] page = pageBounds(pageIdx);
        if (page == null || page.length < 4) return bounds;
        double pageWidth = page[3] - page[1];
        double pageHeight = page[2] - page[0];
        if (pageWidth <= 0.0 || pageHeight <= 0.0) return bounds;

        double top = Math.max(0.0, bounds[0]);
        double left = Math.max(0.0, bounds[1]);
        double bottom = Math.min(pageHeight, bounds[2]);
        double right = Math.min(pageWidth, bounds[3]);
        if (bottom <= top || right <= left) return bounds;
        return new double[] { top, left, bottom, right };
    }

    private double pageWidthMm(int pageIdx) {
        double[] b = pageBounds(pageIdx);
        if (b == null || b.length < 4) return 1e9;
        return (b[3] - b[1]) / safeScaleFactor();
    }

    private double pageHeightMm(int pageIdx) {
        double[] b = pageBounds(pageIdx);
        if (b == null || b.length < 4) return 1e9;
        return (b[2] - b[0]) / safeScaleFactor();
    }

    private double[] pageBounds(int pageIdx) {
        if (ctx == null || ctx.resolvedData == null || ctx.resolvedData.pages() == null) return null;
        for (ResolvedPage page : ctx.resolvedData.pages()) {
            if (page != null && page.index() == pageIdx) {
                return page.bounds();
            }
        }
        if (pageIdx < 0 || pageIdx >= ctx.resolvedData.pages().size()) return null;
        return ctx.resolvedData.pages().get(pageIdx).bounds();
    }

    private double safeScaleFactor() {
        if (ctx != null && ctx.scaleFactor != 0.0) return ctx.scaleFactor;
        if (data != null && data.scaleFactor() != 0.0) return data.scaleFactor();
        return 1.0;
    }

    private void addAll(int[] ids, Set<Integer> target) {
        if (ids == null || target == null) return;
        for (int id : ids) {
            target.add(id);
        }
    }

    private static boolean hasSemanticText(ResolvedTextFrame tf) {
        return !visibleText(tf).isEmpty();
    }

    private static String visibleText(ResolvedTextFrame tf) {
        String text = tf != null ? tf.frameVisibleText() : null;
        if (text == null) return "";
        return text.replace("\uFFFC", "")
                .replace("\u0016", "")
                .replace("\u0018", "")
                .trim();
    }

    private static boolean isCompletePngSimpleButtonLabel(ResolvedBuildContext ctx, RenderedGroup rg) {
        return VisualLayeringRules.isCompletePngSimpleButtonLabel(ctx, rg);
    }

    private boolean isStandaloneGraphicOnlyInlineObject(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (!data.isInlineObjectId(rg.id())) return false;
        if (!"inline_graphic_only".equals(rg.reason())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (!"none".equals(rg.textOwner())) return false;
        if (hasDescendantTextFrameExcluding(String.valueOf(rg.id()), new HashSet<>(), new HashSet<>())) {
            return false;
        }
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) {
            return false;
        }
        if (rg.editableTextFrameIds() != null && rg.editableTextFrameIds().length > 0) {
            return false;
        }
        return rg.file() != null && !rg.file().isEmpty();
    }

    private static boolean isStandaloneGraphicOnlyInlineObjectPlan(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.placement != Placement.INLINE) return false;
        if (!"inline_graphic_only".equals(plan.reason)) return false;
        if (plan.textAction != TextAction.DROP_TEXT) return false;
        if (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) return false;
        return plan.file != null && !plan.file.isEmpty();
    }

    private void resolvePairedInlinePageObjectChannelOwners() {
        Map<String, List<Integer>> inlinePlanIndexesByPageDom = new HashMap<>();
        for (int j = 0; j < plans.size(); j++) {
            ObjectPlan inline = plans.get(j);
            if (!isRenderedVisualPlan(inline)) continue;
            if (!safe(inline.kind).contains("inline_object")) continue;
            if (inline.domId < 0) continue;
            inlinePlanIndexesByPageDom
                    .computeIfAbsent(pageDomKey(inline.pageIndex, inline.domId), k -> new ArrayList<>())
                    .add(j);
        }
        if (inlinePlanIndexesByPageDom.isEmpty()) return;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan pageObject = plans.get(i);
            if (!isVisibleRenderedVisual(pageObject) && !isAtomicOwnershipRootTextHiddenShellPlan(pageObject)) continue;
            if (!safe(pageObject.kind).contains("page_object")) continue;
            if (pageObject.domId < 0) continue;

            List<Integer> inlineIndexes = inlinePlanIndexesByPageDom.get(pageDomKey(pageObject.pageIndex, pageObject.domId));
            if (inlineIndexes == null || inlineIndexes.isEmpty()) continue;
            for (int j : inlineIndexes) {
                if (i == j) continue;
                ObjectPlan inline = plans.get(j);
                if (!pageObjectShouldOwnPairedInlineChannel(pageObject, inline)) continue;

                ObjectPlan nextPageObject = pageObject;
                if (isAtomicOwnershipRootTextHiddenShellPlan(pageObject)) {
                    nextPageObject = pageObject
                            .withVisualAction(VisualAction.PLACE_TEXT_SHELL, pageObject.reason)
                            .withVisualLayer(textShellVisualLayer(
                                    pageObject,
                                    pageObject.ownedTextFrameIds,
                                    VisualLayer.CONTAINER_BACKDROP));
                    plans.set(i, nextPageObject);
                    alignOwnedTextFramePlans(nextPageObject.ownedTextFrameIds, nextPageObject.placement);
                }
                plans.set(j, inline.withVisualAction(VisualAction.DROP_VISUAL,
                        "owned_by_page_object_channel"));
                pageObject = nextPageObject;
            }
        }
    }

    private boolean pageObjectShouldOwnPairedInlineChannel(ObjectPlan pageObject, ObjectPlan inline) {
        RenderedGroup pageGroup = renderedGroupForPlan(pageObject);
        RenderedGroup inlineGroup = renderedGroupForPlan(inline);
        if (pageGroup == null || inlineGroup == null) return false;
        if (!isRenderedPageObject(pageGroup)) return false;
        if (!"inline_graphic_only".equals(inlineGroup.reason())) return false;
        if (isResolvedStoryInlineAnchor(inline) && !hasIdmlAnchoredPagePosition(inline.domId)) return false;

        if (isAtomicOwnershipRootTextHiddenShellPlan(pageObject)
                && pageObject.ownedTextFrameIds != null
                && pageObject.ownedTextFrameIds.length > 1) {
            return true;
        }
        if (isGraphicOnlyAtomicObject(pageGroup)) {
            return true;
        }
        return "pure_decoration_group".equals(pageGroup.reason())
                && InlineSemanticLabelPolicy.isStandaloneSemanticGraphicInlineGroup(data, inlineGroup);
    }

    private boolean isResolvedStoryInlineAnchor(ObjectPlan plan) {
        if (plan == null || plan.domId < 0) return false;
        if (plan.placement != Placement.INLINE) return false;
        return hasResolvedInlineAnchor(plan.domId);
    }

    private boolean isResolvedStoryInlineAnchor(RenderedGroup rg) {
        return rg != null && rg.id() >= 0 && hasResolvedInlineAnchor(rg.id());
    }

    private void alignOwnedTextFramePlans(int[] textFrameIds, Placement placement) {
        if (textFrameIds == null || textFrameIds.length == 0 || placement == null) return;
        CoordinateSpace coordinateSpace = placement == Placement.INLINE
                ? CoordinateSpace.STORY_FLOW
                : CoordinateSpace.PAGE;
        Map<Integer, List<Integer>> textFramePlanIndexes = new HashMap<>();
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null || !"text_frame".equals(plan.kind)) continue;
            textFramePlanIndexes
                    .computeIfAbsent(plan.domId, k -> new ArrayList<>())
                    .add(i);
        }
        for (int textFrameId : textFrameIds) {
            List<Integer> indexes = textFramePlanIndexes.get(textFrameId);
            if (indexes == null) continue;
            for (int i : indexes) {
                ObjectPlan plan = plans.get(i);
                if (plan == null) continue;
            if (plan.placement == placement && plan.coordinateSpace == coordinateSpace) continue;
            plans.set(i, plan.withPlacementAndCoordinateSpace(placement, coordinateSpace));
            }
        }
    }

    private void normalizeOwnedTextFrameCoordinatesToVisibleShellOwners() {
        Map<Integer, ObjectPlan> ownerByTextFrame = new LinkedHashMap<>();
        Set<Integer> ambiguousTextFrames = new HashSet<>();
        for (ObjectPlan plan : plans) {
            if (!isVisibleTextShellOwningTextFrame(plan)) continue;
            CoordinateSpace coordinateSpace = effectiveCoordinateSpace(plan);
            if (plan.placement == null || coordinateSpace == null) continue;
            for (int textFrameId : plan.ownedTextFrameIds) {
                ObjectPlan existing = ownerByTextFrame.get(textFrameId);
                if (existing == null) {
                    ownerByTextFrame.put(textFrameId, plan);
                    continue;
                }
                if (existing.placement != plan.placement
                        || effectiveCoordinateSpace(existing) != coordinateSpace) {
                    ambiguousTextFrames.add(textFrameId);
                }
            }
        }
        if (ownerByTextFrame.isEmpty()) return;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null || !"text_frame".equals(plan.kind)) continue;
            if (ambiguousTextFrames.contains(plan.domId)) continue;
            ObjectPlan owner = ownerByTextFrame.get(plan.domId);
            if (owner == null) continue;
            CoordinateSpace coordinateSpace = effectiveCoordinateSpace(owner);
            if (plan.placement == owner.placement && plan.coordinateSpace == coordinateSpace) continue;
            plans.set(i, plan.withPlacementAndCoordinateSpace(owner.placement, coordinateSpace));
        }
    }

    private void finalizeOwnedTextFrameDepthContracts() {
        Map<Integer, Integer> maxShellZByTextFrame = new LinkedHashMap<>();
        for (ObjectPlan owner : plans) {
            if (!isVisibleTextShellOwningTextFrame(owner)) continue;
            if (isBackPlaneTextShell(owner)) continue;
            for (int textFrameId : owner.ownedTextFrameIds) {
                Integer existing = maxShellZByTextFrame.get(textFrameId);
                if (existing == null || owner.zOrder > existing) {
                    maxShellZByTextFrame.put(textFrameId, owner.zOrder);
                }
            }
        }
        if (maxShellZByTextFrame.isEmpty()) return;
        int suppressed = 0;
        for (ObjectPlan plan : plans) {
            if (plan == null || !isTextFramePlanKind(plan.kind)) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            Integer shellZ = maxShellZByTextFrame.get(plan.domId);
            if (shellZ == null) continue;
            int requiredTextZ = shellZ + 1;
            if (plan.zOrder >= requiredTextZ) continue;
            warn("OWNED_TEXT_FRAME_ZORDER_REPAIR_SUPPRESSED",
                    "plan=" + planRef(plan)
                            + " currentZ=" + plan.zOrder
                            + " requiredMinZ=" + requiredTextZ
                            + " shellZ=" + shellZ);
            suppressed++;
        }
        ConversionTiming.metric("stage1.ownershipPlanner.finalizeOwnedTextFrameDepthContracts.suppressed",
                suppressed);
    }

    private void ensureOwnedTextFramePlansForVisibleTextShells() {
        if (data == null) return;
        Map<Integer, ObjectPlan> existingTextPlanById = new LinkedHashMap<>();
        Map<Integer, ObjectPlan> shellOwnerByTextFrame = new LinkedHashMap<>();
        for (ObjectPlan plan : plans) {
            if (plan == null) continue;
            if (isMaterializedHwpxTextOwner(plan)) {
                if (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) {
                    for (int textFrameId : plan.ownedTextFrameIds) {
                        existingTextPlanById.putIfAbsent(textFrameId, plan);
                    }
                } else if (plan.domId >= 0) {
                    existingTextPlanById.putIfAbsent(plan.domId, plan);
                }
            }
            if (isDirectInlineTextFrameDrawTextPlan(plan)) {
                for (int textFrameId : plan.ownedTextFrameIds) {
                    existingTextPlanById.putIfAbsent(textFrameId, plan);
                }
            }
            if (isTextFramePlanKind(plan.kind)
                    && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                    && plan.domId >= 0) {
                existingTextPlanById.putIfAbsent(plan.domId, plan);
            }
            if (!isExecutableShellOwningTextFrame(plan)) continue;
            if (isBackPlaneTextShell(plan)) continue;
            for (int textFrameId : plan.ownedTextFrameIds) {
                ObjectPlan existingOwner = shellOwnerByTextFrame.get(textFrameId);
                if (existingOwner == null || plan.zOrder > existingOwner.zOrder) {
                    shellOwnerByTextFrame.put(textFrameId, plan);
                }
            }
        }
        if (shellOwnerByTextFrame.isEmpty()) return;
        int missing = 0;
        for (Map.Entry<Integer, ObjectPlan> entry : shellOwnerByTextFrame.entrySet()) {
            int textFrameId = entry.getKey();
            if (existingTextPlanById.containsKey(textFrameId)) continue;
            ResolvedTextFrame tf = data.getTextFrame(String.valueOf(textFrameId));
            if (tf == null) continue;
            ObjectPlan owner = entry.getValue();
            warn("SHELL_OWNED_TEXT_FRAME_MISSING_HWPX_TEXT_PLAN",
                    "textFrame=" + textFrameId
                            + " shellDom=" + owner.domId
                            + " shellRender=" + (owner.renderId != null ? owner.renderId : -1)
                            + " shellReason=" + safe(owner.reason));
            missing++;
        }
        ConversionTiming.metric("stage1.ownershipPlanner.ensureOwnedTextFramePlansForVisibleTextShells.missing", missing);
    }

    private void normalizeStage1ContractsBeforeValidation() {
        timed("normalizeTextShellsWithMaterializedTextOwners.final", this::normalizeTextShellsWithMaterializedTextOwners);
        timed("restorePageTextlessGraphicGroupContracts.final", this::restorePageTextlessGraphicGroupContracts);
        timed("normalizeTextlessShellsWithoutOwnedText.final", this::normalizeTextlessShellsWithoutOwnedText);
        timed("normalizeInlineOwnedTextShellsToStoryFlow.final", this::normalizeInlineOwnedTextShellsToStoryFlow);
        timed("normalizeInlineTextStyleMarkerVisuals.final", this::normalizeInlineTextStyleMarkerVisuals);
        timed("normalizeRawClippedImageVisualSources.final", this::normalizeRawClippedImageVisualSources);
        timed("normalizeVisibleVisualSourcesToPlanPage.final", this::normalizeVisibleVisualSourcesToPlanPage);
        timed("normalizeVisualSlotsExcludeTableStyleSources.final", this::normalizeVisualSlotsExcludeTableStyleSources);
        timed("normalizeDuplicateVisibleSourceSlots.final", this::normalizeDuplicateVisibleSourceSlots);
    }

    private void normalizeInlineTextStyleMarkerVisuals() {
        int suppressed = 0;
        for (ObjectPlan plan : plans) {
            if (!isPlannerDeclaredInlineGraphicLineTextStyleMarker(plan)) continue;
            boolean needsRepair = plan.visualAction != VisualAction.ABSORB_TEXT_STYLE
                    || plan.materialization != Materialization.HWPX_TEXT
                    || !sameIntSet(plan.styleSourceObjectIds, visualSourceIds(plan))
                    || (plan.visualSourceObjectIds != null && plan.visualSourceObjectIds.length > 0)
                    || (plan.descendantVisualObjectIds != null
                    && plan.descendantVisualObjectIds.length > 0);
            if (!needsRepair) continue;
            warn("INLINE_TEXT_STYLE_MARKER_CONTRACT_REPAIR_SUPPRESSED",
                    "plan=" + planRef(plan)
                            + " expectedVisualAction=ABSORB_TEXT_STYLE"
                            + " expectedMaterialization=HWPX_TEXT"
                            + " expectedStyleSourceObjectIds="
                            + ObjectPlan.intArrayJson(visualSourceIds(plan)));
            suppressed++;
        }
        ConversionTiming.metric("stage1.ownershipPlanner.normalizeInlineTextStyleMarkerVisuals.suppressed",
                suppressed);
    }

    private void restorePageTextlessGraphicGroupContracts() {
        int suppressed = 0;
        for (ObjectPlan plan : plans) {
            if (!isPageTextlessGraphicGroupPlan(plan)) continue;
            if (!pageTextlessGraphicGroupNeedsContractRepair(plan)) continue;
            warn("PAGE_TEXTLESS_GRAPHIC_GROUP_CONTRACT_REPAIR_SUPPRESSED",
                    "plan=" + planRef(plan)
                            + " expected=text:DROP_TEXT visual:PLACE_FLOATING_PNG"
                            + " layer:PAGE_BACKGROUND placement:FLOATING coordinate:PAGE"
                            + " materialization:PAGE_PLANE_PNG");
            suppressed++;
        }
        ConversionTiming.metric("stage1.ownershipPlanner.restorePageTextlessGraphicGroupContracts.suppressed",
                suppressed);
    }

    private void normalizeTextShellsWithMaterializedTextOwners() {
        LinkedHashSet<Integer> materializedTextOwners = new LinkedHashSet<>();
        for (ObjectPlan plan : plans) {
            if (!isMaterializedHwpxTextOwner(plan)) continue;
            if (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) {
                addAll(plan.ownedTextFrameIds, materializedTextOwners);
            } else if (plan.domId >= 0) {
                materializedTextOwners.add(plan.domId);
            }
        }
        if (materializedTextOwners.isEmpty()) return;
        int suppressed = 0;
        for (ObjectPlan shell : plans) {
            if (shell == null) continue;
            if (shell.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (shell.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (shell.ownedTextFrameIds == null || shell.ownedTextFrameIds.length == 0) continue;
            if (!allContainedIn(shell.ownedTextFrameIds, materializedTextOwners)) continue;
            warn("TEXT_SHELL_MATERIALIZED_TEXT_OWNER_REPAIR_SUPPRESSED",
                    "plan=" + planRef(shell)
                            + " ownedTextFrameIds="
                            + ObjectPlan.intArrayJson(shell.ownedTextFrameIds)
                            + " expectedTextAction=DROP_TEXT because separate HWPX text owner exists");
            suppressed++;
        }
        ConversionTiming.metric("stage1.ownershipPlanner.normalizeTextShellsWithMaterializedTextOwners.suppressed",
                suppressed);
    }

    private static boolean isMaterializedHwpxTextOwner(ObjectPlan plan) {
        if (plan == null || plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        if (plan.materialization == Materialization.HWPX_TEXT
                && plan.visualAction == VisualAction.DROP_VISUAL) {
            return true;
        }
        return plan.materialization == Materialization.HWPX_TABLE_STYLE
                && plan.visualAction == VisualAction.PLACE_TABLE_STYLE;
    }

    private void normalizeTextlessShellsWithoutOwnedText() {
        int suppressed = 0;
        for (ObjectPlan shell : plans) {
            if (shell == null) continue;
            if (shell.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (shell.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (shell.ownedTextFrameIds != null && shell.ownedTextFrameIds.length > 0) continue;
            if (shell.ownedTextRanges != null && shell.ownedTextRanges.length > 0) continue;
            warn("TEXTLESS_SHELL_TEXT_ACTION_REPAIR_SUPPRESSED",
                    "plan=" + planRef(shell)
                            + " expectedTextAction=DROP_TEXT because no ownedTextFrameIds are declared");
            suppressed++;
        }
        ConversionTiming.metric("stage1.ownershipPlanner.normalizeTextlessShellsWithoutOwnedText.suppressed",
                suppressed);
    }

    private void normalizeInlineOwnedTextShellsToStoryFlow() {
        if (data == null) return;
        int suppressed = 0;
        for (ObjectPlan shell : plans) {
            if (shell == null) continue;
            if (shell.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (shell.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (shell.placement == Placement.INLINE
                    && shell.coordinateSpace == CoordinateSpace.STORY_FLOW) continue;
            if (shell.ownedTextFrameIds == null || shell.ownedTextFrameIds.length == 0) continue;
            if (!ownedTextFramesAreInlineSource(shell)) continue;
            if (!hasInlineSourceObject(shell)) continue;
            if (isTableCellAnchoredExternalLabelShell(shell)) continue;
            if (hasIdmlAnchoredPagePosition(shell.domId)) continue;
            if (textShellUsesPagePositionCompositeAssociation(shell)) continue;

            warn("INLINE_OWNED_TEXT_SHELL_PLACEMENT_REPAIR_SUPPRESSED",
                    "plan=" + planRef(shell)
                            + " ownedTextFrameIds="
                            + ObjectPlan.intArrayJson(shell.ownedTextFrameIds)
                            + " expected=INLINE/STORY_FLOW");
            suppressed++;
        }
        ConversionTiming.metric("stage1.ownershipPlanner.normalizeInlineOwnedTextShellsToStoryFlow.suppressed",
                suppressed);
    }

    private void normalizeRawClippedImageVisualSources() {
        if (data == null) return;
        int suppressed = 0;
        for (ObjectPlan plan : plans) {
            if (plan == null || !plan.hasVisibleVisual()) continue;
            if (plan.visualAction != VisualAction.PLACE_FLOATING_PNG
                    && plan.visualAction != VisualAction.PLACE_INLINE_PNG) {
                continue;
            }
            int[] visualSources = visualSourceIds(plan);
            if (visualSources.length == 0) continue;
            LinkedHashSet<Integer> retained = new LinkedHashSet<>();
            boolean changed = false;
            for (int sourceId : visualSources) {
                int clipParentId = rawClippedImageParentId(sourceId);
                if (clipParentId >= 0) {
                    retained.add(clipParentId);
                    changed = true;
                } else {
                    retained.add(sourceId);
                }
            }
            if (!changed) continue;
            warn("RAW_CLIPPED_IMAGE_VISUAL_SOURCE_REPAIR_SUPPRESSED",
                    "plan=" + planRef(plan)
                            + " visualSourceObjectIds="
                            + ObjectPlan.intArrayJson(visualSources)
                            + " clippedParentVisualSourceObjectIds="
                            + ObjectPlan.intArrayJson(toIntArray(retained)));
            suppressed++;
        }
        ConversionTiming.metric("stage1.ownershipPlanner.normalizeRawClippedImageVisualSources.suppressed",
                suppressed);
    }

    private boolean hasInlineSourceObject(ObjectPlan plan) {
        if (plan == null || data == null) return false;
        if (isInlineSourceObject(plan.domId)) return true;
        if (plan.sourceObjectIds != null) {
            for (int sourceId : plan.sourceObjectIds) {
                if (isInlineSourceObject(sourceId)) return true;
            }
        }
        for (int sourceId : visualSourceIds(plan)) {
            if (isInlineSourceObject(sourceId)) return true;
        }
        return false;
    }

    private boolean isInlineSourceObject(int sourceId) {
        if (sourceId < 0 || data == null) return false;
        if (data.isInlineObjectId(sourceId)) return true;
        ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
        return item != null && item.isInline();
    }

    private int rawClippedImageParentId(int sourceId) {
        if (data == null) return -1;
        ResolvedPageItem image = data.getPageItem(String.valueOf(sourceId));
        if (image == null || !"Image".equals(safe(image.type()))) return -1;
        ResolvedPageItem parent = directImageClipParent(image);
        return parent != null ? parseFlexibleId(parent.id()) : -1;
    }

    private static boolean allContainedIn(int[] ids, LinkedHashSet<Integer> ownerIds) {
        if (ids == null || ids.length == 0 || ownerIds == null || ownerIds.isEmpty()) return false;
        for (int id : ids) {
            if (!ownerIds.contains(id)) return false;
        }
        return true;
    }

    private static boolean isVisibleTextShellOwningTextFrame(ObjectPlan plan) {
        return isVisibleTextShell(plan)
                && plan.ownedTextFrameIds != null
                && plan.ownedTextFrameIds.length > 0;
    }

    private static boolean isExecutableShellOwningTextFrame(ObjectPlan plan) {
        return plan != null
                && plan.hasVisibleVisual()
                && plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                && plan.ownedTextFrameIds != null
                && plan.ownedTextFrameIds.length > 0;
    }

    private static boolean isBackPlaneTextShell(ObjectPlan plan) {
        return plan != null && (plan.visualLayer == VisualLayer.PAGE_BACKGROUND
                || plan.visualLayer == VisualLayer.CONTAINER_BACKDROP);
    }

    private static CoordinateSpace effectiveCoordinateSpace(ObjectPlan plan) {
        if (plan == null) return null;
        if (plan.coordinateSpace != null) return plan.coordinateSpace;
        if (plan.placement == Placement.INLINE) return CoordinateSpace.STORY_FLOW;
        if (plan.placement == Placement.FLOATING) return CoordinateSpace.PAGE;
        return null;
    }

    private static String pageDomKey(int pageIndex, int domId) {
        return pageIndex + ":" + domId;
    }

    private void planRenderedItems() {
        int before = plans.size();
        int floatingItems = 0;
        int graphicFrames = 0;
        int imageFrames = 0;
        int pdfFrames = 0;
        for (RenderedGroup rg : data.allRenderedFloatingItems()) {
            floatingItems++;
            if (hasPreplannedRenderedPlan(rg)) continue;
            addPlanForRendered(rg, "rendered_floating_item");
        }
        for (RenderedGroup rg : data.allRenderedGraphicFrames()) {
            graphicFrames++;
            if (hasPreplannedRenderedPlan(rg)) continue;
            addPlanForRendered(rg, "rendered_graphic_frame");
        }
        for (RenderedGroup rg : data.allRenderedImageFrames()) {
            imageFrames++;
            if (hasPreplannedRenderedPlan(rg)) continue;
            addPlanForRendered(rg, "rendered_image_frame");
        }
        for (RenderedGroup rg : data.allRenderedPdfFrames()) {
            pdfFrames++;
            if (hasPreplannedRenderedPlan(rg)) continue;
            addPlanForRendered(rg, "rendered_pdf_frame");
        }
        ConversionTiming.metric("stage1.ownershipPlanner.planRenderedItems.floatingItems", floatingItems);
        ConversionTiming.metric("stage1.ownershipPlanner.planRenderedItems.graphicFrames", graphicFrames);
        ConversionTiming.metric("stage1.ownershipPlanner.planRenderedItems.imageFrames", imageFrames);
        ConversionTiming.metric("stage1.ownershipPlanner.planRenderedItems.pdfFrames", pdfFrames);
        ConversionTiming.metric("stage1.ownershipPlanner.planRenderedItems.createdPlans", plans.size() - before);
    }

    private boolean hasPreplannedRenderedPlan(RenderedGroup rg) {
        if (rg == null) return false;
        String renderedCandidateId = safe(rg.candidateId());
        for (ObjectPlan plan : plans) {
            if (plan == null) continue;
            if (!safe(plan.kind).startsWith("planner_declared_rendered:")) continue;
            if (!renderedCandidateId.isEmpty()
                    && renderedCandidateId.equals(safe(plan.candidateId))
                    && renderedCandidateIdIsUnique(renderedCandidateId)) {
                return true;
            }
            if (plan.renderId == null) continue;
            if (plan.renderId != rg.id()) continue;
            if (plan.pageIndex != rg.pageIndex()) continue;
            if (!safe(plan.file).equals(safe(rg.file()))) continue;
            return true;
        }
        return false;
    }

    private boolean renderedCandidateIdIsUnique(String candidateId) {
        String key = safe(candidateId);
        if (key.isEmpty()) return false;
        if (renderedGroupCountByCandidateIdCache == null) {
            renderedGroupCountByCandidateIdCache = new HashMap<>();
            for (RenderedGroup rendered : allRenderedGroups()) {
                String renderedCandidateId = safe(rendered != null ? rendered.candidateId() : null);
                if (renderedCandidateId.isEmpty()) continue;
                renderedGroupCountByCandidateIdCache.merge(renderedCandidateId, 1, Integer::sum);
            }
        }
        return renderedGroupCountByCandidateIdCache.getOrDefault(key, 0) <= 1;
    }

    private void resolveHwpxTextOwnedNonShellVisuals() {
        HashSet<Integer> hwpxTextSources = new HashSet<>();
        for (ObjectPlan plan : plans) {
            if (plan == null) continue;
            if (!isTextFramePlanKind(plan.kind)) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (plan.sourceObjectIds == null || plan.sourceObjectIds.length == 0) continue;
            for (int sourceId : plan.sourceObjectIds) {
                ResolvedTextFrame tf = data.getTextFrame(String.valueOf(sourceId));
                if (tf != null) {
                    hwpxTextSources.add(sourceId);
                }
            }
            if (plan.ownedTextFrameIds != null) {
                for (int ownedTfId : plan.ownedTextFrameIds) {
                    if (data.getTextFrame(String.valueOf(ownedTfId)) != null) {
                        hwpxTextSources.add(ownedTfId);
                    }
                }
            }
        }
        if (hwpxTextSources.isEmpty()) return;

        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!isVisibleRenderedVisual(plan)) continue;
            if (plan.placement == Placement.INLINE) continue;
            if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                    || plan.visualAction == VisualAction.ABSORB_TEXT_STYLE
                    || plan.visualAction == VisualAction.PLACE_TABLE_STYLE) {
                continue;
            }
            RenderedGroup rendered = renderedGroupForPlan(plan);
            if (rendered != null && isEditableVisualShellWithSeparateHwpxText(rendered)) {
                continue;
            }
            if (rendered != null && hasIndependentContentVisualBesideOwnedText(rendered)) {
                continue;
            }
            if (plan.sourceObjectIds == null || plan.sourceObjectIds.length == 0) continue;
            for (int sourceId : plan.sourceObjectIds) {
                if (hwpxTextSources.contains(sourceId)) {
                    plans.set(i, plan.withVisualAction(VisualAction.DROP_VISUAL,
                            "complete_visual_contains_hwpx_text_source"));
                    break;
                }
            }
        }
    }

    private static boolean isTextFramePlanKind(String kind) {
        return kind != null && (kind.equals("text_frame") || kind.startsWith("text_frame:"));
    }

    private void normalizeCrossPageTextShellOwnership() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null || plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (plan.placement != Placement.FLOATING) continue;
            if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) continue;
            if (ownedTextFramesAreOnPlanPage(plan)) continue;
            if (hasVisualSourceOnPlanPage(plan) || hasExtractorPageLocalVisualSlot(plan)) {
                RenderedGroup rendered = renderedGroupForPlan(plan);
                VisualAction residualAction = crossPageResidualVisualAction(plan);
                plans.set(i, plan
                        .withTextAction(TextAction.DROP_TEXT)
                        .withOwnedTextFrameIds(new int[0])
                        .withSourceBundleKey(sourceBundleKeyOf(rendered, plan.sourceObjectIds, new int[0]))
                        .withVisualLayer(crossPageResidualVisualLayer(plan))
                        .withVisualAction(residualAction,
                                "cross_page_text_shell_visual_fragment"));
                continue;
            }
            plans.set(i, plan.withVisualAction(VisualAction.DROP_VISUAL,
                    "cross_page_text_shell_owned_text_not_on_plan_page"));
        }
    }

    private boolean hasExtractorPageLocalVisualSlot(ObjectPlan plan) {
        if (plan == null) return false;
        if (!isPageLocalTextFrameShellVisualFragment(plan)) return false;
        if (plan.file == null || plan.file.isBlank()) return false;
        if (plan.pageIndex < 0 || !planBoundsIntersectPlanPage(plan)) return false;
        int[] visualIds = visualSourceIds(plan);
        if (visualIds.length == 0) return false;
        for (int sourceId : visualIds) {
            if (!contains(plan.ownedTextFrameIds, sourceId)) return true;
        }
        return false;
    }

    private boolean hasVisualSourceOnPlanPage(ObjectPlan plan) {
        if (plan == null || data == null || plan.visualSourceObjectIds == null) return false;
        for (int sourceId : plan.visualSourceObjectIds) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item == null || item.sourceHidden()) continue;
            boolean ownedTextFrameSource = contains(plan.ownedTextFrameIds, sourceId)
                    && data.getTextFrame(String.valueOf(sourceId)) != null;
            if (ownedTextFrameSource) {
                if (isPageLocalTextFrameShellVisualFragment(plan)
                        && sourceItemIntersectsPlanPage(item, plan)) {
                    return true;
                }
                continue;
            }
            if (data.getTextFrame(String.valueOf(sourceId)) != null) {
                if (isPageLocalTextFrameShellVisualFragment(plan)
                        && sourceItemIntersectsPlanPage(item, plan)) {
                    return true;
                }
                continue;
            }
            if (item.pageIndex() == plan.pageIndex || sourceItemIntersectsPlanPage(item, plan)) {
                return true;
            }
        }
        return false;
    }

    private boolean planBoundsIntersectPlanPage(ObjectPlan plan) {
        if (plan == null || plan.bounds == null || plan.bounds.length < 4) return false;
        return hasMainPageIntersectionInPageBoundsUnits(plan.pageIndex, plan.bounds);
    }

    private static VisualAction crossPageResidualVisualAction(ObjectPlan plan) {
        if (isPageLocalTextFrameShellVisualFragment(plan)) {
            return VisualAction.PLACE_TEXT_SHELL;
        }
        return VisualAction.PLACE_FLOATING_PNG;
    }

    private static boolean isPageLocalTextFrameShellVisualFragment(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.placement != Placement.FLOATING) return false;
        if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.materialization == Materialization.TEXTLESS_VISUAL_FRAGMENT) return true;
        if (plan.cropSourceBounds != null && plan.cropSourceBounds.length >= 4) return true;
        String reason = safe(plan.reason);
        return "editable_textframe_visual_shell".equals(reason);
    }

    private static VisualLayer crossPageResidualVisualLayer(ObjectPlan plan) {
        if (plan == null) return VisualLayer.CONTAINER_BACKDROP;
        if (plan.visualLayer == VisualLayer.PAGE_BACKGROUND
                || plan.visualLayer == VisualLayer.CONTAINER_BACKDROP) {
            return plan.visualLayer;
        }
        if (isBackgroundLayerName(plan.sourceLayerName)) {
            return VisualLayer.CONTAINER_BACKDROP;
        }
        return plan.visualLayer;
    }

    private boolean ownedTextFramesAreOnPlanPage(ObjectPlan plan) {
        if (plan == null || data == null || plan.ownedTextFrameIds == null) return true;
        for (int tfId : plan.ownedTextFrameIds) {
            ResolvedTextFrame tf = data.getTextFrame(String.valueOf(tfId));
            if (tf == null) return false;
            if (tf.pageIndex() != plan.pageIndex) return false;
        }
        return true;
    }

    private void addPlanForRendered(RenderedGroup rg, String channel) {
        if (rg == null) return;
        Placement placement = placementOf(rg);
        TextAction textAction = textActionOf(rg);
        VisualAction visualAction = visualActionOf(rg, placement, textAction);
        int[] sourceIds = sourceIdsOrSelf(rg);
        int[] ownedTextFrameIds = editableTextFrameIdsOf(rg);
        VisualLayer visualLayer = visualLayerOf(rg, sourceIds, visualAction, textAction, ownedTextFrameIds);
        if (!isImageBackedContentShell(rg)
                && hasIndependentContentVisualBesideOwnedText(rg)
                && (visualAction == VisualAction.PLACE_FLOATING_PNG
                || visualAction == VisualAction.PLACE_INLINE_PNG)) {
            sourceIds = independentContentVisualSourceIds(rg, sourceIds);
        }
        String reason = safe(rg.reason());
        if (hasHiddenSourceObject(sourceIds)) {
            textAction = TextAction.DROP_TEXT;
            visualAction = VisualAction.DROP_VISUAL;
            visualLayer = VisualLayer.CONTENT_VISUAL;
            reason = "hidden_by_source_visibility";
        }
        int[] visualSourceIds = visualSourceIdsForRendered(rg, sourceIds, ownedTextFrameIds, visualAction);
        if (visualAction == VisualAction.PLACE_TEXT_SHELL
                && !hasExecutableTextShellVisualMaterial(visualSourceIds)) {
            textAction = TextAction.DROP_TEXT;
            visualAction = VisualAction.DROP_VISUAL;
            visualLayer = VisualLayer.CONTENT_VISUAL;
            reason = "text_shell_without_visible_visual_source";
            visualSourceIds = new int[0];
        }
        int[] concreteShellTextFrameIds = concreteVisualShellOwnedTextFrameIds(
                rg, visualSourceIds, ownedTextFrameIds, visualAction, placement);
        if (concreteShellTextFrameIds.length > 0
                && concreteShellTextFrameIds.length < ownedTextFrameIds.length) {
            ownedTextFrameIds = concreteShellTextFrameIds;
        }
        if (visualAction == VisualAction.PLACE_TEXT_SHELL
                && ownedTextFrameIdsUseCalloutOverlayStyle(ownedTextFrameIds)
                && !hasImageSource(rg)
                && hasDrawableBackdropShapeSource(rg)
                && !isHwpxTextShellBackdropContract(rg, ownedTextFrameIds)) {
            visualLayer = VisualLayer.LABEL_OVERLAY_BACKDROP;
        }
        if (shouldPlanRenderedChildShellFragmentAsNonExecutable(
                rg, sourceIds, visualAction)
                || shouldPlanAdjacentTextShellChromeAsNonExecutable(
                rg, sourceIds, visualAction)) {
            textAction = TextAction.DROP_TEXT;
            visualAction = VisualAction.DROP_VISUAL;
            visualLayer = VisualLayer.CONTENT_VISUAL;
            reason = "visual_fragment_owned_by_composite_shell_carrier";
        }
        String sourceBundleKey = sourceBundleKeyOf(rg, sourceIds, ownedTextFrameIds);
        int zOrder = zOrderOf(rg, visualAction, visualSourceIds);
        CoordinateSpace coordinateSpace = placement == Placement.INLINE
                ? CoordinateSpace.STORY_FLOW
                : CoordinateSpace.PAGE;
        double[] planBounds = renderedPlanBounds(rg, visualSourceIds, ownedTextFrameIds,
                visualAction, placement);
        if (coordinateSpace == CoordinateSpace.STORY_FLOW) {
            planBounds = normalizeSpreadBoundsToPage(rg.pageIndex(), planBounds);
        }
        Materialization materialization = materializationOfRenderedFragment(
                rg, visualAction, placement, visualLayer, planBounds, visualSourceIds);
        double[] cropSourceBounds = materialization == Materialization.TEXTLESS_VISUAL_FRAGMENT
                ? cropSourceBoundsOfRenderedFragment(rg)
                : null;
        ObjectPlan plan = new ObjectPlan(
                rg.id(),
                channel + ":" + safe(rg.type()) + ":" + safe(rg.itemType()),
                rg.pageIndex(),
                textAction,
                visualAction,
                visualLayer,
                placement,
                rg.id(),
                sourceIds,
                visualSourceIds,
                new int[0],
                ownedTextFrameIds,
                new int[0],
                sourceBundleKey,
                materialization,
                coordinateSpace,
                null,
                zOrder,
                reason,
                rg.file(),
                planBounds,
                null,
                sourceLayerId(rg, sourceIds),
                sourceLayerName(rg, sourceIds),
                sourceLayerIndex(rg, sourceIds));
        plans.add(plan.withCropSourceBounds(cropSourceBounds));
    }

    private double[] renderedPlanBounds(
            RenderedGroup rg,
            int[] visualSourceIds,
            int[] ownedTextFrameIds,
            VisualAction visualAction,
            Placement placement) {
        double[] fallback = rg != null ? rg.bounds() : null;
        if (rg == null || data == null) return fallback;
        if (placement != Placement.FLOATING) return fallback;
        if (visualAction != VisualAction.PLACE_TEXT_SHELL) return fallback;
        // Rendered shell PNG bounds are the visible slot contract declared by
        // the extractor.  Recomputing bounds from child visual sources narrows
        // composite shells and decouples the text channel from its native shell.
        return fallback;
    }

    private int[] concreteVisualShellOwnedTextFrameIds(
            RenderedGroup rg,
            int[] visualSourceIds,
            int[] ownedTextFrameIds,
            VisualAction visualAction,
            Placement placement) {
        if (rg == null || data == null) return new int[0];
        if (placement != Placement.FLOATING) return new int[0];
        if (visualAction != VisualAction.PLACE_TEXT_SHELL) return new int[0];
        if (!"slot_only_textless_shell".equals(safe(rg.reason()))) return new int[0];
        // Slot-only rendered shells keep the source-declared text relationship.
        // Narrowing owned TF ids to the visual export subtree loses the shell/text
        // slot contract and later narrows the shell bounds as a side effect.
        return new int[0];
    }

    private boolean sourceShellHasOwnedTextFrameDescendant(
            ResolvedPageItem shell,
            int[] ownedTextFrameIds) {
        if (shell == null || shell.id() == null || ownedTextFrameIds == null || ownedTextFrameIds.length == 0) {
            return false;
        }
        Set<String> descendants = data.buildDescendantSet(shell.id(), 16);
        for (int tfId : ownedTextFrameIds) {
            if (descendants.contains(String.valueOf(tfId))) {
                return true;
            }
            ResolvedTextFrame tf = data.getTextFrame(String.valueOf(tfId));
            double[] shellBounds = normalizeSpreadBoundsToPage(
                    tf != null && tf.pageIndex() >= 0 ? tf.pageIndex() : shell.pageIndex(),
                    boundsOf(shell));
            double[] textBounds = normalizeSpreadBoundsToPage(
                    tf != null && tf.pageIndex() >= 0 ? tf.pageIndex() : shell.pageIndex(),
                    boundsOf(tf));
            if (tf != null && boundsContains(shellBounds, textBounds, 3.0)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPositiveBounds(double[] bounds) {
        return bounds != null
                && bounds.length >= 4
                && bounds[2] > bounds[0]
                && bounds[3] > bounds[1];
    }

    private static boolean isMeaningfullyNarrower(double[] candidate, double[] fallback) {
        if (!isPositiveBounds(candidate) || !isPositiveBounds(fallback)) return false;
        double candidateW = candidate[3] - candidate[1];
        double candidateH = candidate[2] - candidate[0];
        double fallbackW = fallback[3] - fallback[1];
        double fallbackH = fallback[2] - fallback[0];
        double dw = fallbackW - candidateW;
        double dh = fallbackH - candidateH;
        double fallbackArea = fallbackW * fallbackH;
        double candidateArea = candidateW * candidateH;
        return dw > 2.0
                || dh > 2.0
                || (fallbackArea > 0.0 && candidateArea / fallbackArea < 0.92);
    }

    private Materialization materializationOfRenderedFragment(
            RenderedGroup rg,
            VisualAction visualAction,
            Placement placement,
            VisualLayer visualLayer,
            double[] planBounds,
            int[] visualSourceIds) {
        if (rg == null) {
            return null;
        }
        if (placement != Placement.FLOATING) return null;
        if (visualAction != VisualAction.PLACE_FLOATING_PNG
                && visualAction != VisualAction.PLACE_TEXT_SHELL) {
            return null;
        }
        if (planBounds == null || planBounds.length < 4) return null;
        if (visualLayer != VisualLayer.PAGE_BACKGROUND
                && visualLayer != VisualLayer.CONTAINER_BACKDROP
                && visualLayer != VisualLayer.LABEL_BACKDROP
                && visualLayer != VisualLayer.FOREGROUND_MASK) {
            return Materialization.EXTRACTED_PNG_VECTOR;
        }
        if (hasCrossPageVisualSourceIntersectingPlanPage(rg.pageIndex(), visualSourceIds)) {
            return Materialization.TEXTLESS_VISUAL_FRAGMENT;
        }
        double[] source = cropSourceBoundsOfRenderedFragment(rg);
        if (source == null || source.length < 4) return null;
        boolean containsPlanBounds = source[0] <= planBounds[0] + 0.05
                && source[1] <= planBounds[1] + 0.05
                && source[2] >= planBounds[2] - 0.05
                && source[3] >= planBounds[3] - 0.05;
        if (!containsPlanBounds) return null;
        return Materialization.TEXTLESS_VISUAL_FRAGMENT;
    }

    private boolean hasCrossPageVisualSourceIntersectingPlanPage(
            int pageIndex,
            int[] visualSourceIds) {
        if (data == null || pageIndex < 0 || visualSourceIds == null || visualSourceIds.length == 0) {
            return false;
        }
        double[] page = pageBounds(pageIndex);
        if (page == null || page.length < 4) return false;
        for (int sourceId : visualSourceIds) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item == null || item.pageIndex() < 0 || item.pageIndex() == pageIndex) continue;
            if (overlapArea(page, boundsOf(item)) > 0.0) {
                return true;
            }
        }
        return false;
    }

    private double[] cropSourceBoundsOfRenderedFragment(RenderedGroup rg) {
        if (rg == null) return null;
        double[] crop = rg.cropSourceBounds();
        if (crop != null && crop.length >= 4 && isPositiveBounds(crop)) {
            return crop;
        }
        return null;
    }

    private boolean shouldPlanRenderedChildShellFragmentAsNonExecutable(
            RenderedGroup child,
            int[] childSourceIds,
            VisualAction childVisualAction) {
        if (child == null || data == null) return false;
        if (isDirectChildShellSlotRender(child)) return false;
        if (childVisualAction != VisualAction.PLACE_FLOATING_PNG
                && childVisualAction != VisualAction.PLACE_INLINE_PNG
                && childVisualAction != VisualAction.PLACE_TEXT_SHELL) {
            return false;
        }
        if (!isInitialVisibleChildShellFragment(child)) return false;
        if (childSourceIds == null || childSourceIds.length == 0) return false;
        for (RenderedGroup carrier : initialCompositeShellCarriersForPage(child.pageIndex())) {
            if (!isInitialCompositeShellCarrierCandidate(carrier)) continue;
            if (carrier.id() == child.id()) continue;
            if (carrier.pageIndex() != child.pageIndex()) continue;
            int[] carrierSourceIds = sourceIdsOrSelf(carrier);
            if (carrierSourceIds.length <= childSourceIds.length) continue;
            if (!containsAll(carrierSourceIds, childSourceIds)
                    && !contains(carrierSourceIds, child.id())) {
                continue;
            }
            if (carrier.bounds() != null && child.bounds() != null
                    && !boundsContains(carrier.bounds(), child.bounds(), 4.0)
                    && !boundsMostlyOverlap(carrier.bounds(), child.bounds(), 0.05)) {
                continue;
            }
            if (carrierHasVisibleMaterialOutsideInitialChildShellSlots(carrier, carrierSourceIds)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldPlanAdjacentTextShellChromeAsNonExecutable(
            RenderedGroup child,
            int[] childSourceIds,
            VisualAction childVisualAction) {
        if (child == null || data == null) return false;
        if (childVisualAction != VisualAction.PLACE_FLOATING_PNG
                && childVisualAction != VisualAction.PLACE_INLINE_PNG) {
            return false;
        }
        if (!isRenderedPageObject(child)) return false;
        if (!"indesign_png".equals(child.visualOwner())) return false;
        if (!"none".equals(child.textOwner())) return false;
        if (Boolean.TRUE.equals(child.containsText())
                || Boolean.TRUE.equals(child.containsEditableText())) {
            return false;
        }
        if (childSourceIds == null || childSourceIds.length == 0) return false;
        if (hasBackgroundRoleSourceLayer(child, childSourceIds)) return false;
        if (!isAdjacentTextShellChromeReason(child.reason())) return false;

        for (int adjacentPageIndex : adjacentPageIndexes(child.pageIndex())) {
            for (RenderedGroup carrier : adjacentTextShellCarriersForPage(adjacentPageIndex)) {
                if (!isAdjacentAtomicTextShellCarrier(carrier)) continue;
                int[] carrierSourceIds = sourceIdsOrSelf(carrier);
                if (!containsAll(carrierSourceIds, childSourceIds)
                        && !contains(carrierSourceIds, child.id())) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    private static boolean isAdjacentTextShellChromeReason(String reason) {
        String r = safe(reason);
        return "graphic_ownership_root".equals(r)
                || "decoration_group".equals(r)
                || "pure_decoration_group".equals(r)
                || "complex_graphic_text_hidden".equals(r);
    }

    private static int[] adjacentPageIndexes(int pageIndex) {
        if (pageIndex < 0) return new int[0];
        return new int[] { pageIndex - 1, pageIndex + 1 };
    }

    private static boolean isAdjacentAtomicTextShellCarrier(RenderedGroup carrier) {
        if (carrier == null) return false;
        if (!isRenderedPageObject(carrier)) return false;
        if (!"indesign_png".equals(carrier.visualOwner())) return false;
        if (!"hwpx_tf".equals(carrier.textOwner())) return false;
        if (carrier.editableTextFrameIds() == null
                || carrier.editableTextFrameIds().length == 0) {
            return false;
        }
        String reason = safe(carrier.reason());
        return reason.contains("atomic_ownership_root_text_hidden_shell")
                || reason.contains("mixed_group_text_hidden")
                || reason.contains("complex_graphic_text_hidden")
                || reason.contains("composite_shell_carrier");
    }

    private boolean isInitialVisibleChildShellFragment(RenderedGroup rg) {
        if (rg == null) return false;
        if (!isRenderedPageObject(rg)) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (!"none".equals(rg.textOwner())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) {
            return false;
        }
        if (rg.file() == null || rg.file().isEmpty()) return false;
        if (hasDistinctChildShellSlotSignalReason(rg.reason())) return true;
        if (!"complex_graphic_text_hidden".equals(safe(rg.reason()))) return false;
        if (isImageBackedContentShell(rg) || hasPlacedContentSourceTree(rg)) return false;
        return hasVisibleShellMaterialSource(rg);
    }

    private boolean hasVisibleShellMaterialSource(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        for (int sourceId : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (sourceItemHasVisibleShellMaterial(item)) return true;
        }
        return false;
    }

    private static boolean isInitialCompositeShellCarrierCandidate(RenderedGroup rg) {
        if (rg == null) return false;
        if (!isRenderedPageObject(rg)) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (rg.file() == null || rg.file().isEmpty()) return false;
        String reason = safe(rg.reason());
        return reason.contains("mixed_group_text_hidden")
                || reason.contains("complex_graphic_text_hidden")
                || reason.contains("image_group_text_hidden")
                || reason.contains("composite_shell_carrier");
    }

    private List<RenderedGroup> adjacentTextShellCarriersForPage(int pageIndex) {
        if (pageIndex < 0) return java.util.Collections.emptyList();
        List<RenderedGroup> out = new ArrayList<>();
        for (RenderedGroup rg : allRenderedGroups()) {
            if (rg == null || rg.pageIndex() != pageIndex) continue;
            if (isAdjacentAtomicTextShellCarrier(rg)) {
                out.add(rg);
            }
        }
        return out;
    }

    private boolean carrierHasVisibleMaterialOutsideInitialChildShellSlots(
            RenderedGroup carrier,
            int[] carrierSourceIds) {
        if (carrier == null || carrierSourceIds == null || data == null) return false;
        String cacheKey = carrier.pageIndex() + ":" + carrier.id();
        Boolean cached = carrierVisibleMaterialOutsideInitialChildShellSlotsCache.get(cacheKey);
        if (cached != null) return cached;
        LinkedHashSet<Integer> covered = new LinkedHashSet<>();
        for (RenderedGroup child : initialVisibleChildShellFragmentsForPage(carrier.pageIndex())) {
            if (!isInitialVisibleChildShellFragment(child)) continue;
            if (child.id() == carrier.id()) continue;
            if (child.pageIndex() != carrier.pageIndex()) continue;
            int[] childSourceIds = sourceIdsOrSelf(child);
            if (!containsAll(carrierSourceIds, childSourceIds)
                    && !contains(carrierSourceIds, child.id())) {
                continue;
            }
            if (carrier.bounds() != null && child.bounds() != null
                    && !boundsContains(carrier.bounds(), child.bounds(), 4.0)
                    && !boundsMostlyOverlap(carrier.bounds(), child.bounds(), 0.05)) {
                continue;
            }
            addAll(childSourceIds, covered);
            int[] childVisualSourceIds = visualSourceIdsForRendered(
                    child,
                    childSourceIds,
                    editableTextFrameIdsOf(child),
                    VisualAction.PLACE_FLOATING_PNG);
            addAll(childVisualSourceIds, covered);
        }
        if (covered.isEmpty()) return false;
        for (int sourceId : carrierSourceIds) {
            if (sourceId == carrier.id()) continue;
            if (covered.contains(sourceId)) continue;
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (sourceItemHasVisibleShellMaterial(item)) {
                carrierVisibleMaterialOutsideInitialChildShellSlotsCache.put(cacheKey, true);
                return true;
            }
        }
        carrierVisibleMaterialOutsideInitialChildShellSlotsCache.put(cacheKey, false);
        return false;
    }

    private void planTextFrames() {
        for (ResolvedTextFrame tf : data.textFrames()) {
            if (tf == null || tf.id() == null) continue;
            int domId = parseInt(tf.id(), -1);
            if (domId < 0) continue;
            TextAction textAction;
            if (tf.sourceHidden()) {
                textAction = TextAction.DROP_TEXT;
            } else if (data.isTextOwnedByIndesignPng(tf.id())) {
                textAction = TextAction.OWNED_BY_PNG;
            } else if (isPngOwnedChildMarkerTextFrame(tf, domId)) {
                textAction = TextAction.OWNED_BY_PNG;
            } else {
                textAction = TextAction.OWNED_BY_HWPX_TEXT;
            }
            IDMLStory idmlStory = loadStory(tf.storyId());
            boolean tableOnlyTextFrame =
                    TableFrameOwnershipPolicy.isTableOnlyTextFrame(tf, idmlStory);
            boolean ownedByAnchoredTablePlan =
                    tableOnlyTextFrame && isOwnedByAnchoredTablePlan(idmlStory);
            if (ownedByAnchoredTablePlan) {
                textAction = TextAction.DROP_TEXT;
            }
            int[] sourceIds = tableOnlyTextFrame
                    ? tableOnlySourceIds(domId, idmlStory)
                    : new int[] { domId };
            int[] styleSourceIds = tableOnlyTextFrame
                    ? tableStyleSourceIdsForTextFrame(domId, idmlStory)
                    : new int[0];
            boolean ownsTableStyle = styleSourceIds.length > 0;
            VisualAction visualAction = ownsTableStyle
                    ? VisualAction.PLACE_TABLE_STYLE
                    : VisualAction.DROP_VISUAL;
            if (tableOnlyTextFrame
                    && !ownedByAnchoredTablePlan
                    && !ownsTableStyle
                    && hasHwpxTextOwnerForTextFrame(domId)) {
                continue;
            }
            if (!tableOnlyTextFrame && hasDropTextDecisionForTextFrame(domId)) {
                continue;
            }
            if (!tableOnlyTextFrame
                    && textAction == TextAction.OWNED_BY_HWPX_TEXT
                    && hasHwpxTextOwnerForTextFrame(domId)) {
                continue;
            }
            if (!tableOnlyTextFrame
                    && textAction == TextAction.DROP_TEXT
                    && hasTextDecisionForTextFrame(domId)) {
                continue;
            }
            Placement placement = placementOfTextFrame(tf, domId, textAction, visualAction);
            plans.add(new ObjectPlan(
                    domId,
                    tableOnlyTextFrame ? "text_frame:table_only" : "text_frame",
                    tf.pageIndex(),
                    textAction,
                    visualAction,
                    VisualLayer.CONTENT_VISUAL,
                    placement,
                    null,
                    sourceIds,
                    tableOnlyTextFrame ? new int[0] : sourceIds,
                    styleSourceIds,
                    new int[] { domId },
                    new int[0],
                    "p" + tf.pageIndex() + ":tf:" + domId,
                    null,
                    null,
                    null,
                    textFrameSourceZOrder(tf),
                    ownedByAnchoredTablePlan ? "owned_by_anchored_table_plan"
                            : (tableOnlyTextFrame ? "table_only_text_frame" : textFrameReason(tf, textAction)),
                    null,
                    textFramePlanBounds(tf, domId, tableOnlyTextFrame),
                    tf.layerId(),
                    tf.layerName(),
                    tf.layerIndex()));
        }
    }

    private boolean isPngOwnedChildMarkerTextFrame(ResolvedTextFrame tf, int domId) {
        if (tf == null || data == null || domId < 0) return false;
        if (tf.sourceHidden() || tf.isInline()) return false;
        if (tf.previousFrameId() != null || tf.nextFrameId() != null) return false;
        if (!isSimplePngOwnedMarkerText(normalizeResolvedVisibleText(visibleText(tf)))) return false;
        ResolvedPageItem textItem = data.getPageItem(tf.id());
        if (textItem == null || textItem.parentId() == null || textItem.parentId().isBlank()) return false;
        ResolvedPageItem parent = data.getPageItem(textItem.parentId());
        if (!isVisibleGraphicCarrierForChildMarker(parent, domId)) return false;
        if (!boundsContainCenter(pageRelativeBoundsOf(parent), textFramePlanBounds(tf, domId, false))) {
            return false;
        }
        return true;
    }

    private static boolean isSimplePngOwnedMarkerText(String text) {
        if (text == null || text.isEmpty() || text.length() > 2) return false;
        if (text.chars().allMatch(Character::isDigit)) return true;
        return text.matches("[가-하ㄱ-ㅎ]");
    }

    private boolean isVisibleGraphicCarrierForChildMarker(ResolvedPageItem parent, int childTextFrameId) {
        if (parent == null || parent.sourceHidden() || parent.hiddenByParent() || !parent.visible()) {
            return false;
        }
        if (parent.isInline()) return false;
        String type = safe(parent.type());
        if (!("Oval".equals(type) || "Rectangle".equals(type) || "Polygon".equals(type))) {
            return false;
        }
        if (parent.childIds() != null && contains(parent.childIds(), childTextFrameId)) {
            return true;
        }
        return sourceItemHasVisibleShellMaterial(parent);
    }

    private boolean hasHwpxTextOwnerForTextFrame(int textFrameId) {
        if (textFrameId < 0) return false;
        for (ObjectPlan plan : plans) {
            if (plan == null) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (contains(plan.ownedTextFrameIds, textFrameId) || plan.domId == textFrameId) {
                return true;
            }
        }
        return false;
    }

    private boolean hasDropTextDecisionForTextFrame(int textFrameId) {
        if (textFrameId < 0) return false;
        for (ObjectPlan plan : plans) {
            if (plan == null || plan.textAction != TextAction.DROP_TEXT) continue;
            if (contains(plan.ownedTextFrameIds, textFrameId) || plan.domId == textFrameId) {
                return true;
            }
        }
        return false;
    }

    private boolean hasTextDecisionForTextFrame(int textFrameId) {
        if (textFrameId < 0) return false;
        for (ObjectPlan plan : plans) {
            if (plan == null) continue;
            if (contains(plan.ownedTextFrameIds, textFrameId) || plan.domId == textFrameId) {
                return true;
            }
        }
        return false;
    }

    private boolean hasTableStyleDecisionForTextFrame(int textFrameId) {
        if (textFrameId < 0) return false;
        for (ObjectPlan plan : plans) {
            if (plan == null) continue;
            if (plan.visualAction != VisualAction.PLACE_TABLE_STYLE) continue;
            if (contains(plan.ownedTextFrameIds, textFrameId) || plan.domId == textFrameId) {
                return true;
            }
        }
        return false;
    }

    private double[] textFramePlanBounds(
            ResolvedTextFrame tf,
            int textFrameDomId,
            boolean tableOnlyTextFrame) {
        if (tableOnlyTextFrame && data != null && textFrameDomId >= 0) {
            ResolvedPageItem sourceItem = data.getPageItem(String.valueOf(textFrameDomId));
            double[] sourceBounds = boundsOf(sourceItem);
            if (sourceBounds != null && sourceBounds.length >= 4) {
                return sourceBounds;
            }
        }
        return tf != null && tf.pageRelativeBounds() != null ? tf.pageRelativeBounds()
                : (tf != null ? tf.geometricBounds() : null);
    }

    private void normalizeDuplicateSiblingLabelTextOwners() {
        if (data == null) return;
        Map<String, List<Integer>> bySiblingTextSlot = new LinkedHashMap<>();
        for (ObjectPlan plan : plans) {
            if (plan == null || !"text_frame".equals(plan.kind)) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            ResolvedTextFrame tf = data.getTextFrame(String.valueOf(plan.domId));
            String key = siblingTextSlotKey(tf);
            if (key == null) continue;
            bySiblingTextSlot.computeIfAbsent(key, k -> new ArrayList<>()).add(plan.domId);
        }
        if (bySiblingTextSlot.isEmpty()) return;

        LinkedHashSet<Integer> duplicateTextFrameIds = new LinkedHashSet<>();
        for (List<Integer> ids : bySiblingTextSlot.values()) {
            if (ids == null || ids.size() < 2) continue;
            int canonicalId = canonicalSiblingLabelTextOwner(ids);
            if (canonicalId < 0) continue;
            ResolvedTextFrame canonical = data.getTextFrame(String.valueOf(canonicalId));
            if (!hasNativeFrameStyle(canonical)
                    && !hasExtractedTextlessSiblingShellOwner(canonicalId)
                    && !hasTextlessSiblingShellSourceOwner(canonicalId)) {
                continue;
            }
            for (int id : ids) {
                if (id == canonicalId) continue;
                ResolvedTextFrame candidate = data.getTextFrame(String.valueOf(id));
                if (isDuplicateSiblingLabelTextFrame(candidate, canonical)) {
                    duplicateTextFrameIds.add(id);
                }
            }
        }
        if (duplicateTextFrameIds.isEmpty()) return;

        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null) continue;
            if ("text_frame".equals(plan.kind)
                    && duplicateTextFrameIds.contains(plan.domId)
                    && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT) {
                plans.set(i, plan
                        .withTextAction(TextAction.DROP_TEXT)
                        .withVisualAction(VisualAction.DROP_VISUAL,
                                "duplicate_sibling_label_text_owned_by_styled_frame"));
                continue;
            }
            if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                    && ownedTextFramesAllIn(plan, duplicateTextFrameIds)) {
                plans.set(i, plan
                        .withTextAction(TextAction.DROP_TEXT)
                        .withVisualAction(VisualAction.DROP_VISUAL,
                                "duplicate_sibling_label_text_owned_by_styled_frame"));
            }
        }
    }

    private String siblingTextSlotKey(ResolvedTextFrame tf) {
        if (tf == null || tf.id() == null) return null;
        ResolvedPageItem item = data.getPageItem(tf.id());
        if (item == null || item.parentId() == null || item.parentId().isBlank()) return null;
        String text = normalizeResolvedVisibleText(visibleText(tf));
        if (text.isEmpty()) return null;
        double[] b = tf.pageRelativeBounds() != null ? tf.pageRelativeBounds() : tf.geometricBounds();
        if (b == null || b.length < 4 || area(b) <= 0.0) return null;
        return tf.pageIndex() + ":" + item.parentId() + ":" + text;
    }

    private int canonicalSiblingLabelTextOwner(List<Integer> textFrameIds) {
        int best = -1;
        int bestScore = Integer.MIN_VALUE;
        double bestArea = -1.0;
        int bestZ = Integer.MIN_VALUE;
        for (int id : textFrameIds) {
            ResolvedTextFrame tf = data.getTextFrame(String.valueOf(id));
            if (tf == null) continue;
            int score = hasNativeFrameStyle(tf) ? 1000 : 0;
            if (hasExtractedTextlessSiblingShellOwner(id)
                    || hasTextlessSiblingShellSourceOwner(id)) {
                score += 5000;
            }
            if (tf.fillColor() != null && !isNoneColor(tf.fillColor())) score += 200;
            if (tf.strokeColor() != null && !isNoneColor(tf.strokeColor())) score += 100;
            if (tf.cornerRadius() > 0.0) score += 50;
            double area = area(tf.pageRelativeBounds() != null ? tf.pageRelativeBounds() : tf.geometricBounds());
            int z = tf.zOrder();
            if (score > bestScore
                    || (score == bestScore && area > bestArea)
                    || (score == bestScore && area == bestArea && z > bestZ)) {
                best = id;
                bestScore = score;
                bestArea = area;
                bestZ = z;
            }
        }
        return best;
    }

    private boolean hasExtractedTextlessSiblingShellOwner(int textFrameId) {
        if (textFrameId < 0) return false;
        for (ObjectPlan plan : plans) {
            if (plan == null) continue;
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (plan.materialization != Materialization.EXTRACTED_PNG_VECTOR) continue;
            if (!contains(plan.ownedTextFrameIds, textFrameId)) continue;
            if (contains(plan.styleSourceObjectIds, textFrameId)) continue;
            if (plan.visualSourceObjectIds == null || plan.visualSourceObjectIds.length == 0) continue;
            if (plan.visualSourceObjectIds.length == 1 && plan.visualSourceObjectIds[0] == textFrameId) continue;
            return true;
        }
        return false;
    }

    private boolean hasTextlessSiblingShellSourceOwner(int textFrameId) {
        if (data == null || textFrameId < 0) return false;
        ResolvedPageItem textItem = data.getPageItem(String.valueOf(textFrameId));
        if (textItem == null || textItem.parentId() == null || textItem.parentId().isBlank()) {
            return false;
        }
        double[] textBounds = boundsOf(textItem);
        double textArea = area(textBounds);
        if (textBounds == null || textArea <= 0.0) return false;
        for (ResolvedPageItem sibling : data.pageItems()) {
            if (sibling == null || sibling.id() == null) continue;
            if (sibling.id().equals(textItem.id())) continue;
            if (!safe(textItem.parentId()).equals(safe(sibling.parentId()))) continue;
            if ("TextFrame".equals(safe(sibling.type()))) continue;
            if (!isNativeTextShellShape(sibling)) continue;
            double[] shellBounds = boundsOf(sibling);
            double shellArea = area(shellBounds);
            if (shellBounds == null || shellArea <= 0.0) continue;
            if (shellArea < textArea * 0.55 || shellArea > textArea * 3.0) continue;
            if (boundsContainCenter(shellBounds, textBounds)
                    || boundsMostlyOverlap(shellBounds, textBounds, 0.35)) {
                return true;
            }
        }
        return false;
    }

    private static boolean boundsContainCenter(double[] outer, double[] inner) {
        if (outer == null || inner == null || outer.length < 4 || inner.length < 4) return false;
        double cx = (inner[0] + inner[2]) / 2.0;
        double cy = (inner[1] + inner[3]) / 2.0;
        return cx >= Math.min(outer[0], outer[2])
                && cx <= Math.max(outer[0], outer[2])
                && cy >= Math.min(outer[1], outer[3])
                && cy <= Math.max(outer[1], outer[3]);
    }

    private boolean isDuplicateSiblingLabelTextFrame(
            ResolvedTextFrame candidate,
            ResolvedTextFrame canonical) {
        if (candidate == null || canonical == null) return false;
        if (candidate.id() == null || candidate.id().equals(canonical.id())) return false;
        if (candidate.pageIndex() != canonical.pageIndex()) return false;
        ResolvedPageItem candidateItem = data.getPageItem(candidate.id());
        ResolvedPageItem canonicalItem = data.getPageItem(canonical.id());
        if (candidateItem == null || canonicalItem == null) return false;
        if (!safe(candidateItem.parentId()).equals(safe(canonicalItem.parentId()))) return false;
        String candidateText = normalizeResolvedVisibleText(visibleText(candidate));
        String canonicalText = normalizeResolvedVisibleText(visibleText(canonical));
        if (candidateText.isEmpty() || !candidateText.equals(canonicalText)) return false;
        double[] cb = candidate.pageRelativeBounds() != null
                ? candidate.pageRelativeBounds() : candidate.geometricBounds();
        double[] kb = canonical.pageRelativeBounds() != null
                ? canonical.pageRelativeBounds() : canonical.geometricBounds();
        return boundsMostlyOverlap(cb, kb, 0.45) || boundsContains(kb, cb, 1.5);
    }

    private boolean hasNativeFrameStyle(ResolvedTextFrame tf) {
        if (tf == null) return false;
        if (tf.fillColor() != null && !isNoneColor(tf.fillColor())) return true;
        if (tf.strokeColor() != null && !isNoneColor(tf.strokeColor())) return true;
        if (tf.strokeWeight() > 0.0) return true;
        return tf.cornerRadius() > 0.0;
    }

    private static boolean ownedTextFramesAllIn(ObjectPlan plan, Set<Integer> ids) {
        if (plan == null || ids == null || ids.isEmpty()
                || plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) {
            return false;
        }
        for (int textFrameId : plan.ownedTextFrameIds) {
            if (!ids.contains(textFrameId)) return false;
        }
        return true;
    }

    /**
     * Source ownership policy: when an editable TextFrame is a child of a visible
     * source shape, the parent shape is the text shell. This is a source object,
     * not a rendered PNG, so Stage 1 must expose it explicitly for later phases.
     */
    private void planNativeParentTextShells() {
        if (nativeSourceShapeMaterializationDisabled()) return;
        if (data == null) return;
        for (ResolvedTextFrame tf : data.textFrames()) {
            if (!isVisibleEditableTextFrameSource(tf)) continue;
            int tfId = parseInt(tf.id(), -1);
            if (tfId < 0) continue;
            ResolvedPageItem tfItem = data.getPageItem(tf.id());
            if (tfItem == null || tfItem.parentId() == null) continue;
            ResolvedPageItem parent = data.getPageItem(tfItem.parentId());
            if (!isNativeTextShellShape(parent)) continue;
            double[] shellPlanBounds = pageRelativeBoundsOf(parent);
            if (shellPlanBounds == null) continue;

            int shellId = parseInt(parent.id(), -1);
            if (shellId < 0 || hasNativeParentTextShellPlan(shellId, tfId)) continue;
            if (hasExistingDirectExtractedTextShellPlanForSource(shellId)) continue;
            if (hasExistingExtractedTextShellOwnerForSourceAndTextFrame(shellId, tfId)) continue;
            if (hasExistingVisibleNonShellExtractedVisualPlanForSource(shellId)) continue;
            if (hasExistingDroppedExtractedContainerPlanForSource(shellId)) continue;
            if (isBakedIntoVisibleSlotOnlyParentShell(shellId, tfId)) continue;
            Placement placement = placementOfNativeSourceTextShell(tf, tfId, shellId);
            VisualLayer layer = nativeParentTextShellLayer(tf, parent);
            plans.add(new ObjectPlan(
                    shellId,
                    "native_parent_text_shell",
                    tf.pageIndex(),
                    TextAction.DROP_TEXT,
                    VisualAction.PLACE_TEXT_SHELL,
                    layer,
                    placement,
                    null,
                    new int[] { shellId, tfId },
                    new int[] { shellId },
                    new int[] { shellId },
                    new int[] { tfId },
                    new int[0],
                    "p" + tf.pageIndex() + ":native-shell:" + shellId + ":t_" + tfId,
                    Materialization.NATIVE_SOURCE_SHAPE,
                    placement == Placement.INLINE ? CoordinateSpace.STORY_FLOW : CoordinateSpace.PAGE,
                    null,
                    parent.zOrder(),
                    "native_parent_text_shell",
                    null,
                    shellPlanBounds,
                    parent.layerId(),
                    parent.layerName(),
                    parent.layerIndex()));
        }
    }

    private boolean hasNativeParentTextShellPlan(int shellId, int tfId) {
        for (ObjectPlan plan : plans) {
            if (plan == null) continue;
            if (plan.domId != shellId) continue;
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (plan.materialization != Materialization.NATIVE_SOURCE_SHAPE) continue;
            if (contains(plan.ownedTextFrameIds, tfId)) return true;
        }
        return false;
    }

    private VisualLayer nativeParentTextShellLayer(ResolvedTextFrame tf, ResolvedPageItem shell) {
        if (textFrameStoryContainsInlineAnchor(tf)) {
            return VisualLayer.CONTAINER_BACKDROP;
        }
        if (textFrameStoryUsesCalloutOverlayStyle(tf) && hasDrawableNativeShell(shell)) {
            return VisualLayer.LABEL_OVERLAY_BACKDROP;
        }
        return hasColoredNativeShell(shell)
                ? VisualLayer.LABEL_BACKDROP
                : VisualLayer.CONTAINER_BACKDROP;
    }

    private VisualLayer nativeSiblingTextShellLayer(ResolvedTextFrame tf, ResolvedPageItem shell) {
        if (textFrameStoryContainsInlineAnchor(tf)) {
            return VisualLayer.CONTAINER_BACKDROP;
        }
        if (textFrameStoryUsesCalloutOverlayStyle(tf) && hasDrawableNativeShell(shell)) {
            return VisualLayer.LABEL_OVERLAY_BACKDROP;
        }
        return hasColoredNativeShell(shell)
                ? VisualLayer.LABEL_BACKDROP
                : VisualLayer.CONTAINER_BACKDROP;
    }

    /**
     * Stage 1 source ownership: page/spread-level plain filled source shapes that
     * materially cover a page are page-local BACKGROUND owners. They are not
     * text shells, and later stages must execute this plan rather than trying to
     * recover missing backgrounds from rendered PNG output.
     */
    private void planNativePageBackdropShapes() {
        if (nativeSourceShapeMaterializationDisabled()) return;
        if (data == null || data.pageItems() == null || data.pages() == null) return;
        for (ResolvedPageItem item : data.pageItems()) {
            if (!isNativePageBackdropSource(item)) continue;
            int sourceId = parseInt(item.id(), -1);
            if (sourceId < 0) continue;
            for (ResolvedPage page : nativePageBackdropTargetPages(item)) {
                if (page == null || page.index() < 0) continue;
                double[] pageLocalBounds = pageLocalBoundsOf(item, page.index(), true);
                if (pageLocalBounds == null || pageLocalBounds.length < 4) continue;
                if (!isMaterialPageBackdropOnPage(item, page.index(), pageLocalBounds)) continue;
                if (hasAnyVisibleOrStyleOwnerForSourceOnPage(sourceId, page.index())) continue;

                plans.add(new ObjectPlan(
                        sourceId,
                        "native_page_backdrop_shape",
                        page.index(),
                        TextAction.DROP_TEXT,
                        VisualAction.PLACE_FLOATING_PNG,
                        VisualLayer.PAGE_BACKGROUND,
                        Placement.FLOATING,
                        null,
                        new int[] { sourceId },
                        new int[] { sourceId },
                        new int[] { sourceId },
                        new int[0],
                        new int[0],
                        "p" + page.index() + ":native-page-backdrop:" + sourceId,
                        Materialization.NATIVE_SOURCE_SHAPE,
                        CoordinateSpace.PAGE,
                        null,
                        sourceDepthOrderOrItemZOrder(sourceId, item.zOrder()),
                        "native_page_backdrop_shape",
                        null,
                        pageLocalBounds,
                        item.layerId(),
                        item.layerName(),
                        item.layerIndex()));
            }
        }
    }

    private boolean isNativePageBackdropSource(ResolvedPageItem item) {
        if (item == null || item.sourceHidden()) return false;
        if (item.isInline()) return false;
        if (item.parentId() != null) return false;
        String type = safe(item.type());
        if (!"Rectangle".equals(type) && !"Oval".equals(type) && !"Polygon".equals(type)) {
            return false;
        }
        if (!isNativeSourceShapeMaterializationAllowed(item)) return false;
        if (!sourceItemHasVisibleBackdropFillMaterial(item)) return false;
        if (hasTextFrameDescendant(item)) return false;
        int sourceId = parseInt(item.id(), -1);
        return sourceId < 0 || !hasPlacedContentSourceTree(sourceId);
    }

    private static boolean sourceItemHasVisibleBackdropFillMaterial(ResolvedPageItem item) {
        if (item == null || item.sourceHidden() || item.hiddenByParent() || !item.visible()) return false;
        String type = safe(item.type());
        if (!"Rectangle".equals(type) && !"Oval".equals(type) && !"Polygon".equals(type)) {
            return false;
        }
        if (isNoneColor(item.fillColorName())) return false;
        return true;
    }

    private int sourceDepthOrderOrItemZOrder(int sourceId, int fallbackZOrder) {
        int sourceDepth = maxPageItemSourceDepth(new int[] { sourceId });
        return sourceDepth >= 0 ? sourceDepth : fallbackZOrder;
    }

    private List<ResolvedPage> nativePageBackdropTargetPages(ResolvedPageItem item) {
        List<ResolvedPage> result = new ArrayList<>();
        if (item == null || data == null || data.pages() == null) return result;
        int basePageIndex = item.pageIndex();
        if (basePageIndex < 0) return result;
        addPageIfBackdropIntersects(item, basePageIndex, result);

        // A spread-level page item may cross into the facing page. The facing
        // page is chosen from the source page's spread-side bounds, not from
        // both adjacent document pages; otherwise the next spread can inherit
        // this background because page bounds repeat.
        int facingPageIndex = facingPageIndexInSameSpread(basePageIndex);
        if (facingPageIndex >= 0) {
            addPageIfBackdropIntersects(item, facingPageIndex, result);
        }
        return result;
    }

    private int facingPageIndexInSameSpread(int pageIndex) {
        ResolvedPage page = data != null ? data.getPage(pageIndex) : null;
        if (page == null || page.bounds() == null || page.bounds().length < 4
                || data.pages() == null || data.pages().isEmpty()) {
            return -1;
        }
        double minLeft = Double.POSITIVE_INFINITY;
        for (ResolvedPage candidate : data.pages()) {
            double[] b = candidate != null ? candidate.bounds() : null;
            if (b == null || b.length < 4) continue;
            minLeft = Math.min(minLeft, b[1]);
        }
        if (!Double.isFinite(minLeft)) return -1;
        double pageLeft = page.bounds()[1];
        double pageWidth = Math.max(0.0, page.bounds()[3] - page.bounds()[1]);
        double tolerance = Math.max(1.0, pageWidth * 0.05);
        int facingIndex = pageLeft <= minLeft + tolerance
                ? pageIndex + 1
                : pageIndex - 1;
        return data.getPage(facingIndex) != null ? facingIndex : -1;
    }

    private void addPageIfBackdropIntersects(
            ResolvedPageItem item,
            int pageIndex,
            List<ResolvedPage> result) {
        if (pageIndex < 0 || result == null) return;
        ResolvedPage page = data != null ? data.getPage(pageIndex) : null;
        if (page == null || page.bounds() == null || page.bounds().length < 4) return;
        double[] itemBounds = item != null ? item.geometricBounds() : null;
        if (overlapArea(page.bounds(), itemBounds) <= 0.0) return;
        for (ResolvedPage existing : result) {
            if (existing != null && existing.index() == page.index()) return;
        }
        result.add(page);
    }

    private boolean isMaterialPageBackdropOnPage(
            ResolvedPageItem item,
            int pageIndex,
            double[] pageLocalBounds) {
        double[] page = pageBounds(pageIndex);
        if (item == null || page == null || page.length < 4
                || pageLocalBounds == null || pageLocalBounds.length < 4) {
            return false;
        }
        double pageArea = pageArea(pageIndex);
        double overlap = area(pageLocalBounds);
        if (pageArea <= 0.0 || overlap <= 0.0) return false;
        if (overlap / pageArea >= 0.25) return true;

        double pageWidth = Math.max(0.0, page[3] - page[1]);
        double pageHeight = Math.max(0.0, page[2] - page[0]);
        double width = Math.max(0.0, pageLocalBounds[3] - pageLocalBounds[1]);
        double height = Math.max(0.0, pageLocalBounds[2] - pageLocalBounds[0]);
        boolean spansPageWidth = pageWidth > 0.0 && width / pageWidth >= 0.90;
        boolean spansPageHeight = pageHeight > 0.0 && height / pageHeight >= 0.90;
        return (spansPageWidth || spansPageHeight) && overlap / pageArea >= 0.10;
    }

    private double[] pageLocalBoundsOf(ResolvedPageItem item, int pageIndex, boolean clipToPage) {
        if (item == null) return null;
        double[] b = item.geometricBounds();
        double[] page = pageBounds(pageIndex);
        if (b == null || b.length < 4 || page == null || page.length < 4) return null;
        double top = b[0] - page[0];
        double left = b[1] - page[1];
        double bottom = b[2] - page[0];
        double right = b[3] - page[1];
        double[] result = new double[] { top, left, bottom, right };
        return clipToPage ? clipPageRelativeBoundsToPage(pageIndex, result) : result;
    }

    private boolean hasAnyVisibleOrStyleOwnerForSourceOnPage(int sourceId, int pageIndex) {
        if (sourceId < 0) return false;
        for (ObjectPlan plan : plans) {
            if (plan == null || plan.pageIndex != pageIndex) continue;
            if (plan.hasVisibleVisual() && contains(visualSourceIds(plan), sourceId)) {
                return true;
            }
            if (contains(plan.styleSourceObjectIds, sourceId)) {
                return true;
            }
            if (plan.visualAction == VisualAction.PLACE_TABLE_STYLE
                    && contains(visualSourceIds(plan), sourceId)) {
                return true;
            }
        }
        return false;
    }

    private boolean textFrameStoryContainsInlineAnchor(ResolvedTextFrame tf) {
        if (data == null || tf == null || tf.storyId() == null) return false;
        if (ctx != null && ctx.textFlowDocument != null) {
            TextFlowDocument.TextFlowUnit unit = ctx.textFlowDocument.byStoryId(tf.storyId());
            if (textFlowUnitContainsInlineSlot(unit)) return true;
        }
        ResolvedStory story = data.getStory(tf.storyId());
        if (story == null || story.paragraphs() == null) return false;
        for (ResolvedParagraph paragraph : story.paragraphs()) {
            if (paragraph == null || paragraph.runs() == null) continue;
            for (ResolvedRun run : paragraph.runs()) {
                if (run != null && run.isInlineAnchor()) return true;
            }
        }
        return false;
    }

    private boolean textFrameStoryUsesCalloutOverlayStyle(ResolvedTextFrame tf) {
        if (data == null || tf == null || tf.storyId() == null) return false;
        ResolvedStory story = data.getStory(tf.storyId());
        if (story == null || story.paragraphs() == null) return false;
        for (ResolvedParagraph paragraph : story.paragraphs()) {
            if (paragraph == null) continue;
            if (isCalloutOverlayStyleName(paragraph.styleName())) return true;
        }
        return false;
    }

    private boolean ownedTextUsesCalloutOverlayStyle(RenderedGroup rg) {
        if (rg == null) return false;
        int[] ids = editableTextFrameIdsOf(rg);
        if (ids.length == 0) return false;
        return ownedTextFrameIdsUseCalloutOverlayStyle(ids);
    }

    private boolean ownedTextFrameIdsUseCalloutOverlayStyle(int[] ids) {
        if (ids == null || ids.length == 0) return false;
        for (int id : ids) {
            ResolvedTextFrame tf = data != null ? data.getTextFrame(String.valueOf(id)) : null;
            if (textFrameStoryUsesCalloutOverlayStyle(tf)) return true;
        }
        return false;
    }

    private VisualLayer textShellVisualLayer(
            ObjectPlan shell,
            int[] ownedTextFrameIds,
            VisualLayer fallback) {
        if (isHwpxTextShellBackdropContract(shell, ownedTextFrameIds)) {
            return VisualLayer.CONTAINER_BACKDROP;
        }
        if (ownedTextFrameIdsUseCalloutOverlayStyle(ownedTextFrameIds)) {
            return VisualLayer.LABEL_OVERLAY_BACKDROP;
        }
        if (fallback != null) return fallback;
        return hasColoredShapeShellSource(shell)
                ? VisualLayer.LABEL_BACKDROP
                : VisualLayer.CONTAINER_BACKDROP;
    }

    private static boolean isHwpxTextShellBackdropContract(ObjectPlan shell, int[] ownedTextFrameIds) {
        if (ownedTextFrameIds == null || ownedTextFrameIds.length == 0) return false;
        if (shell == null) return true;
        if (shell.visualLayer == VisualLayer.FOREGROUND_MASK
                || shell.visualLayer == VisualLayer.CONTAINER_OUTLINE) {
            return false;
        }
        return shell.visualPolicyLayer() != PolicyLayer.CONTENT;
    }

    private VisualLayer textShellVisualLayerForOwnedTextFrames(int[] ids, VisualLayer fallback) {
        if (ownedTextFrameIdsUseCalloutOverlayStyle(ids)) {
            return VisualLayer.LABEL_OVERLAY_BACKDROP;
        }
        return fallback != null ? fallback : VisualLayer.LABEL_BACKDROP;
    }

    private static boolean isCalloutOverlayStyleName(String styleName) {
        String s = safe(styleName).toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty()) return false;
        return s.contains("말풍선")
                || s.contains("callout")
                || s.contains("speech")
                || s.contains("bubble");
    }

    private boolean hasExistingDirectExtractedTextShellPlanForSource(int shellId) {
        for (ObjectPlan plan : plans) {
            if (plan == null) continue;
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (plan.materialization == Materialization.NATIVE_SOURCE_SHAPE) continue;
            if (!contains(plan.visualSourceObjectIds, shellId)) continue;
            if (plan.visualSourceObjectIds != null && plan.visualSourceObjectIds.length == 1) return true;
            if (isAtomicOwnershipRootTextHiddenShellPlan(plan)) return true;
            if (isDirectInlineTextShellReason(plan.reason)) return true;
            if ("sibling_group_text_shell".equals(plan.reason)) return true;
        }
        return false;
    }

    private boolean hasExistingExtractedTextShellOwnerForSourceAndTextFrame(int shellId, int tfId) {
        for (ObjectPlan plan : plans) {
            if (plan == null) continue;
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (plan.materialization != Materialization.EXTRACTED_PNG_VECTOR) continue;
            if (!plan.hasVisibleVisual()) continue;
            if (!contains(plan.sourceObjectIds, shellId)
                    && !contains(plan.visualSourceObjectIds, shellId)
                    && !contains(plan.styleSourceObjectIds, shellId)) {
                continue;
            }
            if (!contains(plan.ownedTextFrameIds, tfId)) continue;
            return true;
        }
        return false;
    }

    private boolean hasExistingDroppedExtractedContainerPlanForSource(int shellId) {
        for (ObjectPlan plan : plans) {
            if (plan == null) continue;
            if (plan.materialization == Materialization.NATIVE_SOURCE_SHAPE) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (plan.visualAction != VisualAction.DROP_VISUAL) continue;
            if (plan.domId != shellId && !contains(plan.visualSourceObjectIds, shellId)) continue;
            if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) continue;
            return "text_owned_container_shell_duplicate_child".equals(plan.reason);
        }
        return false;
    }

    private boolean hasExistingVisibleNonShellExtractedVisualPlanForSource(int shellId) {
        for (ObjectPlan plan : plans) {
            if (plan == null) continue;
            if (!plan.hasVisibleVisual()) continue;
            if (plan.visualAction == VisualAction.DROP_VISUAL) continue;
            if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL) continue;
            if (!contains(visualSourceIds(plan), shellId)) continue;
            return true;
        }
        return false;
    }

    private boolean isBakedIntoVisibleSlotOnlyParentShell(int shellId, int tfId) {
        if (shellId < 0) return false;
        for (ObjectPlan plan : plans) {
            if (!isVisibleRenderedVisual(plan)) continue;
            if (!"slot_only_textless_shell".equals(safe(plan.reason))) continue;
            RenderedGroup rg = renderedGroupForPlan(plan);
            int[] parentSources = rg != null && rg.sourceObjectIds() != null
                    ? rg.sourceObjectIds()
                    : plan.sourceObjectIds;
            if (!contains(parentSources, shellId)) continue;
            if (rg != null && contains(rg.hiddenVisualSourceObjectIds(), shellId)) continue;
            if (!slotOnlyParentClaimsSameTextShellSlot(plan, rg, parentSources, tfId)) continue;
            return true;
        }
        return false;
    }

    private boolean slotOnlyParentClaimsSameTextShellSlot(
            ObjectPlan plan,
            RenderedGroup rg,
            int[] parentSources,
            int tfId) {
        if (tfId < 0) return true;
        if (contains(parentSources, tfId)) return true;
        if (contains(plan.ownedTextFrameIds, tfId)) return true;
        return rg != null && renderedGroupClaimsTextFrame(rg, String.valueOf(tfId));
    }

    /**
     * Source ownership policy: a direct visual shell shape and a direct editable
     * TextFrame under the same source group form their own shell slot. A parent
     * group may organize those slots, but it is not itself a larger shell owner.
     */
    private void planNativeSiblingTextShells() {
        if (nativeSourceShapeMaterializationDisabled()) return;
        if (data == null) return;
        Map<String, List<ResolvedTextFrame>> textFramesByParentId = editableTextFramesByParentId();
        for (ResolvedPageItem shell : data.pageItems()) {
            if (!isNativeTextShellShape(shell)) continue;
            if (shell.parentId() == null) continue;
            int shellId = parseInt(shell.id(), -1);
            if (shellId < 0) continue;
            if (hasExistingDirectExtractedTextShellPlanForSource(shellId)) continue;
            if (hasExistingVisibleNonShellExtractedVisualPlanForSource(shellId)) continue;
            if (hasExistingDroppedExtractedContainerPlanForSource(shellId)) continue;
            double[] shellSourceBounds = shell.geometricBounds();
            double[] shellPlanBounds = pageRelativeBoundsOf(shell);
            if (shellSourceBounds == null || shellPlanBounds == null) continue;

            List<ResolvedTextFrame> siblingTextFrames = textFramesByParentId.get(shell.parentId());
            if (siblingTextFrames == null || siblingTextFrames.isEmpty()) continue;
            for (ResolvedTextFrame tf : siblingTextFrames) {
                int tfId = parseInt(tf.id(), -1);
                if (tfId < 0) continue;
                ResolvedPageItem tfItem = data.getPageItem(tf.id());
                if (tfItem == null) continue;
                if (!textFrameFitsShell(shellSourceBounds, tf.geometricBounds())) continue;
                if (hasExistingDirectExtractedTextShellPlanForTextFrame(tfId)) continue;
                if (hasNativeParentTextShellPlan(shellId, tfId)) continue;
                if (hasExistingExtractedTextShellOwnerForSourceAndTextFrame(shellId, tfId)) continue;
                if (isBakedIntoVisibleSlotOnlyParentShell(shellId, tfId)) continue;
                if (wouldCreatePartialCompositeTextShellSplit(shellId, tfId)) continue;
                Placement placement = placementOfNativeSourceTextShell(tf, tfId, shellId);
                VisualLayer layer = nativeSiblingTextShellLayer(tf, shell);
                plans.add(new ObjectPlan(
                        shellId,
                        "native_sibling_text_shell",
                        tf.pageIndex(),
                        TextAction.DROP_TEXT,
                        VisualAction.PLACE_TEXT_SHELL,
                        layer,
                        placement,
                        null,
                        new int[] { shellId, tfId },
                        new int[] { shellId },
                        new int[] { shellId },
                        new int[] { tfId },
                        new int[0],
                        "p" + tf.pageIndex() + ":native-sibling-shell:" + shellId + ":t_" + tfId,
                        Materialization.NATIVE_SOURCE_SHAPE,
                        placement == Placement.INLINE ? CoordinateSpace.STORY_FLOW : CoordinateSpace.PAGE,
                        null,
                        shell.zOrder(),
                        "native_sibling_text_shell",
                        null,
                        shellPlanBounds,
                        shell.layerId(),
                        shell.layerName(),
                        shell.layerIndex()));
            }
        }
    }

    private void planUnownedVisualOnlyChildShellSlots() {
        if (nativeSourceShapeMaterializationDisabled()) return;
        if (data == null) return;
        LinkedHashSet<Integer> candidates = new LinkedHashSet<>();
        for (ObjectPlan composite : plans) {
            boolean incompleteExtractorCarrier = isExtractorDeclaredCompositeShellCarrier(composite);
            if (!isDroppedCompositeWithPossibleUnownedShellSlots(composite)
                    && !incompleteExtractorCarrier) {
                continue;
            }
            int[] candidateSourceIds = incompleteExtractorCarrier
                    ? hiddenVisualSourcesNotExportedBy(composite)
                    : visualSourceIds(composite);
            for (int sourceId : candidateSourceIds) {
                if (sourceId < 0 || sourceId == composite.domId) continue;
                if (hasAnyVisibleOrStyleOwnerForSourceExcept(sourceId, composite)) continue;
                ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
                if (!isUnownedVisualOnlyChildShellSource(item, composite)) continue;
                candidates.add(sourceId);
            }
        }
        for (int sourceId : candidates) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item == null) continue;
            double[] bounds = pageRelativeBoundsOf(item);
            if (bounds == null) continue;
            plans.add(new ObjectPlan(
                    sourceId,
                    "native_visual_only_child_shell",
                    item.pageIndex(),
                    TextAction.DROP_TEXT,
                    VisualAction.PLACE_TEXT_SHELL,
                    VisualLayer.CONTAINER_BACKDROP,
                    Placement.FLOATING,
                    null,
                    new int[] { sourceId },
                    new int[] { sourceId },
                    new int[] { sourceId },
                    new int[0],
                    new int[0],
                    "p" + item.pageIndex() + ":native-visual-shell:" + sourceId,
                    Materialization.NATIVE_SOURCE_SHAPE,
                    CoordinateSpace.PAGE,
                    null,
                    item.zOrder(),
                    "unowned_visual_only_child_shell_slot",
                    null,
                    bounds,
                    item.layerId(),
                    item.layerName(),
                    item.layerIndex()));
        }
    }

    private int[] hiddenVisualSourcesNotExportedBy(ObjectPlan plan) {
        RenderedGroup rendered = renderedGroupForPlan(plan);
        if (rendered == null || rendered.hiddenVisualSourceObjectIds() == null) {
            return new int[0];
        }
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        for (int sourceId : rendered.hiddenVisualSourceObjectIds()) {
            if (sourceId <= 0) continue;
            if (contains(rendered.exportSourceObjectIds(), sourceId)) continue;
            ids.add(sourceId);
        }
        return toIntArray(ids);
    }

    private boolean hasAnyVisibleOrStyleOwnerForSourceExcept(int sourceId, ObjectPlan excludedOwner) {
        if (sourceId < 0) return false;
        for (ObjectPlan plan : plans) {
            if (plan == null || plan == excludedOwner) continue;
            if (plan.hasVisibleVisual() && contains(visualSourceIds(plan), sourceId)) {
                return true;
            }
            if (contains(plan.styleSourceObjectIds, sourceId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDroppedCompositeWithPossibleUnownedShellSlots(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.visualAction != VisualAction.DROP_VISUAL) return false;
        if (visualSourceIds(plan).length == 0) return false;
        String reason = safe(plan.reason);
        return reason.contains("text_hidden")
                || reason.contains("text_composite")
                || reason.contains("composite");
    }

    private boolean isUnownedVisualOnlyChildShellSource(ResolvedPageItem item, ObjectPlan composite) {
        if (item == null || composite == null) return false;
        if (item.pageIndex() != composite.pageIndex) return false;
        if (!isNativeSourceShapeMaterializationAllowed(item)) return false;
        if (!sourceItemHasVisibleShellMaterial(item)) return false;
        if (item.parentId() == null) return false;
        int itemId = parseFlexibleId(item.id());
        int parentId = parseFlexibleId(item.parentId());
        if (parentId != composite.domId) return false;
        if (!contains(composite.sourceObjectIds, itemId)
                && !contains(visualSourceIds(composite), itemId)) {
            return false;
        }
        if (!contains(composite.sourceObjectIds, parentId)
                && !contains(visualSourceIds(composite), parentId)) {
            return false;
        }
        if (!hasVisibleSiblingOwnerInSameComposite(item, composite)) return false;
        return !hasTextFrameDescendant(item);
    }

    private boolean hasVisibleSiblingOwnerInSameComposite(ResolvedPageItem item, ObjectPlan composite) {
        if (item == null || item.parentId() == null || composite == null) return false;
        for (ObjectPlan plan : plans) {
            if (plan == null) continue;
            if (plan.pageIndex != composite.pageIndex) continue;
            boolean visibleShell = plan.hasVisibleVisual()
                    && plan.visualAction == VisualAction.PLACE_TEXT_SHELL;
            boolean tableStyle = plan.visualAction == VisualAction.PLACE_TABLE_STYLE;
            if (!visibleShell && !tableStyle) continue;
            for (int sourceId : visualSourceIds(plan)) {
                if (sourceId < 0 || sourceId == parseFlexibleId(item.id())) continue;
                if (!contains(composite.sourceObjectIds, sourceId)
                        && !contains(visualSourceIds(composite), sourceId)) {
                    continue;
                }
                ResolvedPageItem source = data.getPageItem(String.valueOf(sourceId));
                if (source != null && item.parentId().equals(source.parentId())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasAnyVisibleOrStyleOwnerForSource(int sourceId) {
        if (sourceId < 0) return false;
        for (ObjectPlan plan : plans) {
            if (plan == null) continue;
            if (plan.hasVisibleVisual() && contains(visualSourceIds(plan), sourceId)) {
                return true;
            }
            if (contains(plan.styleSourceObjectIds, sourceId)) {
                return true;
            }
            if (plan.visualAction == VisualAction.PLACE_TABLE_STYLE
                    && contains(visualSourceIds(plan), sourceId)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasTextFrameDescendant(ResolvedPageItem item) {
        if (item == null || item.id() == null || data == null) return false;
        for (ResolvedTextFrame tf : data.textFrames()) {
            if (tf == null || tf.id() == null) continue;
            ResolvedPageItem tfItem = data.getPageItem(tf.id());
            if (tfItem == null) continue;
            String parentId = tfItem.parentId();
            Set<String> seen = new HashSet<>();
            while (parentId != null && seen.add(parentId)) {
                if (item.id().equals(parentId)) return true;
                ResolvedPageItem parent = data.getPageItem(parentId);
                parentId = parent != null ? parent.parentId() : null;
            }
        }
        return false;
    }

    private Map<String, List<ResolvedTextFrame>> editableTextFramesByParentId() {
        if (editableTextFramesByParentIdCache != null) return editableTextFramesByParentIdCache;
        Map<String, List<ResolvedTextFrame>> result = new HashMap<>();
        if (data == null) {
            editableTextFramesByParentIdCache = result;
            return editableTextFramesByParentIdCache;
        }
        for (ResolvedTextFrame tf : data.textFrames()) {
            if (tf == null || tf.id() == null) continue;
            if (!isVisibleEditableTextFrameSource(tf)) continue;
            ResolvedPageItem tfItem = data.getPageItem(tf.id());
            if (tfItem == null || tfItem.parentId() == null) continue;
            result.computeIfAbsent(tfItem.parentId(), k -> new ArrayList<>()).add(tf);
        }
        editableTextFramesByParentIdCache = result;
        return editableTextFramesByParentIdCache;
    }

    private boolean hasExistingDirectExtractedTextShellPlanForTextFrame(int tfId) {
        for (ObjectPlan plan : plans) {
            if (plan == null) continue;
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (plan.materialization == Materialization.NATIVE_SOURCE_SHAPE) continue;
            if (!contains(plan.ownedTextFrameIds, tfId)) continue;
            if (isAtomicOwnershipRootTextHiddenShellPlan(plan)) return true;
            if (isDirectInlineTextShellReason(plan.reason)) return true;
            if ("sibling_group_text_shell".equals(plan.reason)) return true;
        }
        return false;
    }

    private Placement placementOfNativeSourceTextShell(
            ResolvedTextFrame tf,
            int tfId,
            int shellId) {
        ResolvedPageItem shell = data != null ? data.getPageItem(String.valueOf(shellId)) : null;
        if ((tf != null && tf.isInline()) || (shell != null && shell.isInline())) {
            return Placement.INLINE;
        }
        if (nativeShellSourceBelongsToPagePositionedComposite(shellId, tfId)) {
            return Placement.FLOATING;
        }
        if (!hasExecutableInlineAnchorForNativeSourceTextShell(tf, tfId, shellId)) {
            return Placement.FLOATING;
        }
        return placementOfTextFrame(tf, tfId,
                TextAction.OWNED_BY_HWPX_TEXT, VisualAction.PLACE_TEXT_SHELL);
    }

    private boolean hasExecutableInlineAnchorForNativeSourceTextShell(
            ResolvedTextFrame tf,
            int tfId,
            int shellId) {
        if (tf == null || !tf.isInline()) return false;
        return hasResolvedInlineAnchorForSourceId(shellId)
                || hasResolvedInlineAnchorForSourceId(tfId);
    }

    private boolean hasResolvedInlineAnchorForSourceId(int sourceId) {
        if (sourceId < 0 || data == null) return false;
        if (ctx != null && ctx.textFlowDocument != null) {
            TextFlowDocument.InlineSlotCarrier carrier = ctx.textFlowDocument.inlineSlotCarrier(sourceId);
            if (carrier != null) {
                return textFlowParagraphHasAnyVisibleText(carrier.paragraph);
            }
        }
        for (ResolvedStory story : data.stories()) {
            if (story == null || story.paragraphs() == null) continue;
            for (ResolvedParagraph paragraph : story.paragraphs()) {
                if (paragraph == null || paragraph.runs() == null) continue;
                boolean hasVisibleText = paragraphHasVisibleText(paragraph);
                for (ResolvedRun run : paragraph.runs()) {
                    if (run == null || !run.isInlineAnchor()) continue;
                    Integer anchoredObjectId = run.anchoredObjectId();
                    if (anchoredObjectId != null && anchoredObjectId == sourceId && hasVisibleText) {
                        return true;
                    }
                }
            }
        }
        for (ResolvedTable table : data.tables()) {
            if (table == null || table.cells() == null) continue;
            for (ResolvedTable.Cell cell : table.cells()) {
                if (cell == null || cell.inlineAnchorIds() == null) continue;
                for (Integer anchoredObjectId : cell.inlineAnchorIds()) {
                    if (anchoredObjectId != null && anchoredObjectId == sourceId && cell.hasTextRuns()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean textFlowUnitContainsInlineSlot(TextFlowDocument.TextFlowUnit unit) {
        if (unit == null || unit.paragraphs == null) return false;
        for (TextFlowDocument.TextFlowParagraph paragraph : unit.paragraphs) {
            if (paragraph == null || paragraph.atoms == null) continue;
            for (TextFlowDocument.TextFlowAtom atom : paragraph.atoms) {
                if (atom instanceof TextFlowDocument.InlineSlotAtom) return true;
            }
        }
        return false;
    }

    private static boolean textFlowParagraphHasAnyVisibleText(TextFlowDocument.TextFlowParagraph paragraph) {
        if (paragraph == null || paragraph.atoms == null) return false;
        for (TextFlowDocument.TextFlowAtom atom : paragraph.atoms) {
            if (!(atom instanceof TextFlowDocument.TextAtom)) continue;
            TextFlowDocument.TextAtom textAtom = (TextFlowDocument.TextAtom) atom;
            if (!normalizeResolvedVisibleText(textAtom.text).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean paragraphHasVisibleText(ResolvedParagraph paragraph) {
        if (paragraph == null || paragraph.runs() == null) return false;
        for (ResolvedRun run : paragraph.runs()) {
            if (run == null || run.isInlineAnchor()) continue;
            if (hasVisibleStoryText(run.text())) return true;
        }
        return false;
    }

    private static boolean hasVisibleStoryText(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t'
                    || c == '\u0007' || c == '\uFFFC' || c == '￼'
                    || Character.isWhitespace(c)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private boolean nativeShellSourceBelongsToPagePositionedComposite(int shellId, int tfId) {
        for (ObjectPlan plan : plans) {
            if (plan == null) continue;
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (plan.materialization != Materialization.EXTRACTED_PNG_VECTOR) continue;
            if (plan.placement != Placement.FLOATING) continue;
            if (!contains(plan.sourceObjectIds, shellId)) continue;
            if (!contains(plan.ownedTextFrameIds, tfId)) continue;
            if (!safe(plan.kind).contains("page_object")) continue;
            return true;
        }
        return false;
    }

    private boolean wouldCreatePartialCompositeTextShellSplit(int shellId, int candidateTextFrameId) {
        for (ObjectPlan parent : plans) {
            if (parent == null) continue;
            if (parent.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (parent.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (parent.materialization != Materialization.EXTRACTED_PNG_VECTOR) continue;
            if (!contains(parent.sourceObjectIds, shellId)) continue;
            if (!contains(parent.ownedTextFrameIds, candidateTextFrameId)) return true;
            if (parent.ownedTextFrameIds == null || parent.ownedTextFrameIds.length <= 1) return true;
            if (!compositeTextShellWouldBeFullySplit(parent, candidateTextFrameId)) return true;
        }
        return false;
    }

    private boolean compositeTextShellWouldBeFullySplit(ObjectPlan parent, int candidateTextFrameId) {
        LinkedHashSet<Integer> uncovered = new LinkedHashSet<>();
        for (int tfId : parent.ownedTextFrameIds) {
            uncovered.add(tfId);
        }
        int coveringSlotCount = 0;
        if (uncovered.remove(candidateTextFrameId)) {
            coveringSlotCount++;
        }
        for (ObjectPlan candidate : plans) {
            if (candidate == null || candidate == parent) continue;
            if (candidate.pageIndex != parent.pageIndex) continue;
            if (candidate.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (candidate.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (candidate.ownedTextFrameIds == null || candidate.ownedTextFrameIds.length == 0) continue;
            if (!isDirectTextShellSlot(candidate)) continue;
            if (!containsAll(parent.sourceObjectIds, candidate.sourceObjectIds)) continue;
            boolean coversAny = false;
            for (int tfId : candidate.ownedTextFrameIds) {
                if (uncovered.remove(tfId)) {
                    coversAny = true;
                }
            }
            if (coversAny) coveringSlotCount++;
            if (uncovered.isEmpty() && coveringSlotCount >= 2) return true;
        }
        return uncovered.isEmpty() && coveringSlotCount >= 2;
    }

    private void dropCompositeTextShellParentsCoveredByDirectShellSlots() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan parent = plans.get(i);
            if (!isExtractedCompositeTextShellParent(parent)) continue;
            if (!ownedTextFramesCoveredByDistinctDirectShellSlots(parent)) continue;
            if (compositeTextShellHasExtraMaterialOutsideDirectShellSlots(parent)) continue;
            plans.set(i, parent
                    .withTextAction(TextAction.DROP_TEXT)
                    .withVisualAction(VisualAction.DROP_VISUAL,
                            "composite_parent_split_into_direct_shell_slots")
                    .withOwnedTextFrameIds(new int[0])
                    .withDescendantVisualObjectIds(new int[0]));
        }
    }

    private void dropCompositeTextShellParentsContainingDroppedContainerSources() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan parent = plans.get(i);
            if (!isExtractedCompositeTextShellParent(parent)) continue;
            if (!containsDroppedContainerSource(parent)) continue;
            plans.set(i, parent
                    .withTextAction(TextAction.DROP_TEXT)
                    .withVisualAction(VisualAction.DROP_VISUAL,
                            "composite_parent_contains_dropped_container_source")
                    .withOwnedTextFrameIds(new int[0])
                    .withDescendantVisualObjectIds(new int[0]));
        }
    }

    private boolean containsDroppedContainerSource(ObjectPlan parent) {
        for (ObjectPlan child : plans) {
            if (child == null || child == parent) continue;
            if (child.pageIndex != parent.pageIndex) continue;
            if (child.visualAction != VisualAction.DROP_VISUAL) continue;
            if (child.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (!"text_owned_container_shell_duplicate_child".equals(child.reason)) continue;
            if (child.visualSourceObjectIds == null || child.visualSourceObjectIds.length == 0) continue;
            if (containsAny(parent.sourceObjectIds, child.visualSourceObjectIds)) return true;
        }
        return false;
    }

    private boolean compositeTextShellHasExtraMaterialOutsideDirectShellSlots(ObjectPlan parent) {
        if (parent == null || data == null) return false;
        if (parent.visualSourceObjectIds == null || parent.visualSourceObjectIds.length == 0) return false;
        int[] parentTextFrames = effectiveOwnedTextFrameIds(parent);

        LinkedHashSet<Integer> coveredShellSources = new LinkedHashSet<>();
        for (ObjectPlan child : plans) {
            if (child == null || child == parent) continue;
            if (child.pageIndex != parent.pageIndex) continue;
            if (child.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (child.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (child.ownedTextFrameIds == null || child.ownedTextFrameIds.length == 0) continue;
            if (!isDirectTextShellSlot(child)) continue;
            if (!containsAll(parent.sourceObjectIds, child.sourceObjectIds)) continue;
            if (!containsAny(parentTextFrames, child.ownedTextFrameIds)) continue;
            addAll(child.sourceObjectIds, coveredShellSources);
            for (int sourceId : visualSourceIds(child)) {
                coveredShellSources.add(sourceId);
            }
        }
        if (coveredShellSources.isEmpty()) return false;

        for (int sourceId : visualSourceIds(parent)) {
            if (coveredShellSources.contains(sourceId)) continue;
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (sourceItemHasCompositeCarrierExtraMaterial(item)) return true;
        }
        return false;
    }

    private int[] effectiveOwnedTextFrameIds(ObjectPlan plan) {
        if (plan == null) return new int[0];
        if (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) {
            return plan.ownedTextFrameIds;
        }
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        if (plan.sourceObjectIds != null && data != null) {
            for (int sourceId : plan.sourceObjectIds) {
                if (data.getTextFrame(String.valueOf(sourceId)) != null) {
                    ids.add(sourceId);
                }
            }
        }
        return toIntArray(ids);
    }

    private static boolean sourceItemHasCompositeCarrierExtraMaterial(ResolvedPageItem item) {
        if (item == null || item.sourceHidden()) return false;
        String type = safe(item.type());
        if ("TextFrame".equals(type)) return false;
        if ("Group".equals(type)) {
            return !isNoneColor(item.fillColorName())
                    || (!isNoneColor(item.strokeColorName()) && item.strokeWeight() > 0.01);
        }
        if ("Rectangle".equals(type) || "Oval".equals(type) || "Polygon".equals(type)) {
            return !isNoneColor(item.fillColorName())
                    || (!isNoneColor(item.strokeColorName()) && item.strokeWeight() > 0.01);
        }
        if ("GraphicLine".equals(type)) {
            return !isNoneColor(item.strokeColorName()) && item.strokeWeight() > 0.01;
        }
        return true;
    }

    private void dropVisualOnlyCompositeShellCarriersCoveredByChildShellSlots() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan carrier = plans.get(i);
            if (!isVisibleCompositeShellCarrier(carrier)) continue;
            List<ObjectPlan> childShells = visibleChildShellSlotsForCompositeCarrier(carrier);
            boolean coveredByDirectShellSlots = !childShells.isEmpty()
                    && !compositeCarrierHasVisibleMaterialOutsideChildShellSlots(carrier, childShells);
            boolean coveredByCanonicalSourceSlots =
                    compositeCarrierVisibleSourcesCoveredByCanonicalPlans(carrier);
            if (!coveredByDirectShellSlots && !coveredByCanonicalSourceSlots) continue;
            plans.set(i, carrier
                    .withTextAction(TextAction.DROP_TEXT)
                    .withVisualAction(VisualAction.DROP_VISUAL,
                            "composite_shell_carrier_covered_by_child_shell_slots")
                    .withOwnedTextFrameIds(new int[0])
                    .withDescendantVisualObjectIds(new int[0]));
        }
    }

    private static boolean isVisualOnlyCompositeShellCarrier(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.textAction != TextAction.DROP_TEXT) return false;
        if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.materialization != Materialization.EXTRACTED_PNG_VECTOR) return false;
        if (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) return false;
        if (visualSourceIds(plan).length == 0) return false;
        String reason = safe(plan.reason);
        return reason.contains("mixed_group_text_hidden")
                || reason.contains("complex_graphic_text_hidden")
                || reason.contains("image_group_text_hidden")
                || reason.contains("composite_shell_carrier");
    }

    private static boolean isTextHiddenCompositeShellCarrier(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.textAction != TextAction.DROP_TEXT) return false;
        if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.materialization != Materialization.EXTRACTED_PNG_VECTOR
                && plan.materialization != Materialization.TEXTLESS_VISUAL_FRAGMENT) {
            return false;
        }
        if (visualSourceIds(plan).length == 0) return false;
        if (!hasExplicitTextlessShellSignal(plan)) return false;
        String reason = safe(plan.reason);
        return reason.contains("slot_only_textless_shell")
                || reason.contains("sibling_group_text_shell")
                || reason.contains("mixed_group_text_hidden")
                || reason.contains("complex_graphic_text_hidden")
                || reason.contains("image_group_text_hidden")
                || reason.contains("composite_shell_carrier");
    }

    private List<ObjectPlan> visibleChildShellSlotsForCompositeCarrier(ObjectPlan carrier) {
        List<ObjectPlan> childShells = new ArrayList<>();
        if (carrier == null) return childShells;
        for (ObjectPlan child : plans) {
            if (child == null || child == carrier) continue;
            if (child.pageIndex != carrier.pageIndex) continue;
            if (!isVisibleChildShellSlotForCompositeCarrier(carrier, child)) continue;
            childShells.add(child);
        }
        return childShells;
    }

    private static boolean isVisibleChildShellSlotForCompositeCarrier(ObjectPlan carrier, ObjectPlan child) {
        if (carrier == null || child == null) return false;
        if (child.visualAction != VisualAction.PLACE_TEXT_SHELL
                && child.visualAction != VisualAction.PLACE_FLOATING_PNG
                && child.visualAction != VisualAction.PLACE_INLINE_PNG) {
            return false;
        }
        if (child.ownedTextFrameIds != null
                && child.ownedTextFrameIds.length > 0
                && child.textAction != TextAction.DROP_TEXT) {
            return false;
        }
        if (child.materialization != Materialization.EXTRACTED_PNG_VECTOR
                && child.materialization != Materialization.TEXTLESS_VISUAL_FRAGMENT) {
            return false;
        }
        if (visualSourceIds(child).length == 0) return false;
        if (!hasDistinctChildShellSlotSignal(child)
                && !isDeclaredVisualOnlyTextlessShellCarrier(child)) {
            return false;
        }
        if (sourceCount(child) >= sourceCount(carrier)) return false;
        if (!carrierDeclaredVisibleSlotContainsChild(carrier, child)) return false;
        if (carrier.bounds != null && child.bounds != null
                && !boundsContains(carrier.bounds, child.bounds, 4.0)
                && !boundsMostlyOverlap(carrier.bounds, child.bounds, 0.05)) {
            return false;
        }
        return true;
    }

    private static boolean isDeclaredVisualOnlyTextlessShellCarrier(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.textAction != TextAction.DROP_TEXT) return false;
        if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.materialization != Materialization.EXTRACTED_PNG_VECTOR
                && plan.materialization != Materialization.TEXTLESS_VISUAL_FRAGMENT) {
            return false;
        }
        if (visualSourceIds(plan).length == 0) return false;
        if (!hasExplicitTextlessShellSignal(plan)) return false;
        String reason = safe(plan.reason);
        return reason.contains("slot_only_textless_shell")
                || reason.contains("sibling_group_text_shell")
                || reason.contains("text_hidden");
    }

    private static boolean visualOnlyChildOwnsDistinctShellSlot(ObjectPlan parent, ObjectPlan child) {
        if (isDirectBackgroundVisualMaterialSlot(child)) return true;
        return isVisibleChildShellSlotForCompositeCarrier(parent, child);
    }

    private static boolean isDirectBackgroundVisualMaterialSlot(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.visualAction != VisualAction.PLACE_FLOATING_PNG
                && plan.visualAction != VisualAction.PLACE_INLINE_PNG) {
            return false;
        }
        if (plan.materialization != Materialization.EXTRACTED_PNG_VECTOR) return false;
        if (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) return false;
        if (plan.visualPolicyLayer() != PolicyLayer.BACKGROUND) return false;
        int[] visualIds = visualSourceIds(plan);
        if (visualIds.length != 1) return false;
        if (plan.sourceObjectIds == null || plan.sourceObjectIds.length != 1) return false;
        if (plan.sourceObjectIds[0] != visualIds[0]) return false;
        String base = basename(plan.file);
        return base.startsWith("shape_")
                || base.startsWith("deco_")
                || base.startsWith("graphic_");
    }

    private void dropChildVisualFragmentsOwnedByCompositeShellCarriers() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan carrier = plans.get(i);
            if (!isVisibleCompositeShellCarrier(carrier)) continue;
            for (int j = 0; j < plans.size(); j++) {
                if (i == j) continue;
                ObjectPlan child = plans.get(j);
                if (!isCompositeCarrierOwnedChildVisualFragment(carrier, child)) continue;
                plans.set(j, child
                        .withTextAction(TextAction.DROP_TEXT)
                        .withVisualAction(VisualAction.DROP_VISUAL,
                                "visual_fragment_owned_by_composite_shell_carrier")
                        .withOwnedTextFrameIds(new int[0])
                        .withDescendantVisualObjectIds(new int[0]));
            }
        }
    }

    private void dropVisualOnlyChildShellsBakedIntoCompleteCompositeParents() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan parent = plans.get(i);
            if (!isCompleteCompositeVisualOwner(parent)) continue;
            for (int j = 0; j < plans.size(); j++) {
                if (i == j) continue;
                ObjectPlan child = plans.get(j);
                if (!isVisualOnlyChildTextShellBakedIntoCompleteComposite(parent, child)) continue;
                plans.set(j, child
                        .withVisualAction(VisualAction.DROP_VISUAL,
                                "child_shell_visual_owned_by_complete_composite_parent")
                        .withVisualSourceObjectIds(new int[0])
                        .withDescendantVisualObjectIds(new int[0]));
            }
        }
    }

    private boolean isVisualOnlyChildTextShellBakedIntoCompleteComposite(
            ObjectPlan parent,
            ObjectPlan child) {
        if (parent == null || child == null || parent == child) return false;
        if (parent.domId == child.domId) return false;
        if (parent.pageIndex != child.pageIndex) return false;
        if (!isStrictChildPlan(parent, child)) return false;
        if (!hasSourceParentRelation(parent, child)) return false;
        if (child.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (child.textAction != TextAction.DROP_TEXT) return false;
        if (child.ownedTextFrameIds != null && child.ownedTextFrameIds.length > 0) return false;
        if (hasDistinctChildShellSlotSignal(child)) return false;
        if (parent.bounds != null && child.bounds != null
                && !boundsContains(parent.bounds, child.bounds, 4.0)
                && !boundsMostlyOverlap(parent.bounds, child.bounds, 0.20)) {
            return false;
        }
        return compositeParentVisuallyOwnsChildSlot(parent, child);
    }

    private boolean isCompositeCarrierOwnedChildVisualFragment(ObjectPlan carrier, ObjectPlan child) {
        if (carrier == null || child == null || carrier == child) return false;
        if (carrier.pageIndex != child.pageIndex) return false;
        if (!child.hasVisibleVisual()) return false;
        if (isDirectChildShellSlotPlan(child)) return false;
        if (ownsTextFrameShellStyleSource(child)) return false;
        if (isCompositeCarrierOwnedChildTextlessShell(carrier, child)) return true;
        if (child.materialization != Materialization.EXTRACTED_PNG_VECTOR) return false;
        if (child.ownedTextFrameIds != null
                && child.ownedTextFrameIds.length > 0
                && child.textAction == TextAction.OWNED_BY_HWPX_TEXT) {
            return false;
        }
        if (visualSourceIds(child).length == 0) return false;
        if (isImageBackedContentPlan(child)) return false;
        boolean sourceDescendant = hasSourceDescendantRelation(carrier, child);
        boolean declaredSlotContainsChild = carrierDeclaredVisibleSlotContainsChild(carrier, child);
        boolean carrierOwnedDecorationFragment =
                isCarrierOwnedDecorationFragment(carrier, child, sourceDescendant, declaredSlotContainsChild);
        boolean carrierOwnedNonPlacedChildFragment =
                isCarrierOwnedNonPlacedChildFragment(carrier, child, sourceDescendant, declaredSlotContainsChild);
        if (effectiveVisualPolicyLayer(child) == PolicyLayer.CONTENT
                && !carrierOwnedDecorationFragment
                && !carrierOwnedNonPlacedChildFragment) {
            return false;
        }
        if (visualOnlyChildOwnsDistinctShellSlot(carrier, child)) {
            if (hasDifferentTextShellRole(carrier, child)) return false;
            return compositeCarrierOwnsVisibleChildShellFragment(carrier, child);
        }
        return declaredSlotContainsChild;
    }

    private boolean isCompositeCarrierOwnedChildTextlessShell(ObjectPlan carrier, ObjectPlan child) {
        if (carrier == null || child == null) return false;
        if (!isVisibleCompositeShellCarrier(carrier)) return false;
        if (!isVisibleCompositeShellCarrier(child)) return false;
        if (child.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (child.textAction != TextAction.DROP_TEXT) return false;
        if (child.materialization != Materialization.TEXTLESS_VISUAL_FRAGMENT) return false;
        if (hasPlacedContentSourceTree(child) || hasPlacedContentSource(child)) return false;
        if (!hasSourceDescendantRelation(carrier, child)) return false;
        if (!carrierDeclaredVisibleSlotContainsChild(carrier, child)) return false;
        if (child.ownedTextFrameIds != null
                && child.ownedTextFrameIds.length > 0
                && carrier.ownedTextFrameIds != null
                && carrier.ownedTextFrameIds.length > 0
                && !containsAll(carrier.ownedTextFrameIds, child.ownedTextFrameIds)) {
            return false;
        }
        if (carrier.bounds != null && child.bounds != null
                && !boundsContains(carrier.bounds, child.bounds, 4.0)
                && !boundsMostlyOverlap(carrier.bounds, child.bounds, 0.05)) {
            return false;
        }
        return true;
    }

    private boolean isCarrierOwnedNonPlacedChildFragment(
            ObjectPlan carrier,
            ObjectPlan child,
            boolean sourceDescendant,
            boolean declaredSlotContainsChild) {
        if (!sourceDescendant) return false;
        if (!declaredSlotContainsChild) return false;
        if (!isVisibleCompositeShellCarrier(carrier)) return false;
        if (child.visualAction == VisualAction.PLACE_TEXT_SHELL) return false;
        if (isDirectBackgroundVisualMaterialSlot(child)) return false;
        if (hasPlacedContentSourceTree(child) || hasPlacedContentSource(child)) return false;
        if (child.bounds != null && carrier.bounds != null
                && !boundsContains(carrier.bounds, child.bounds, 4.0)
                && !boundsMostlyOverlap(carrier.bounds, child.bounds, 0.05)) {
            return false;
        }
        return true;
    }

    private boolean isCarrierOwnedDecorationFragment(
            ObjectPlan carrier,
            ObjectPlan child,
            boolean sourceDescendant,
            boolean declaredSlotContainsChild) {
        if (!sourceDescendant) return false;
        if (!declaredSlotContainsChild) return false;
        if (!isVisibleCompositeShellCarrier(carrier)) return false;
        if (child.visualAction == VisualAction.PLACE_TEXT_SHELL) return false;
        if (!safe(child.reason).contains("decoration")) return false;
        if (isDirectBackgroundVisualMaterialSlot(child)) return false;
        return true;
    }

    private static boolean carrierDeclaredVisibleSlotContainsChild(ObjectPlan carrier, ObjectPlan child) {
        if (carrier == null || child == null) return false;
        int[] carrierSources = carrier.sourceObjectIds != null ? carrier.sourceObjectIds : new int[0];
        int[] carrierVisuals = visualSourceIds(carrier);
        int[] childSources = child.sourceObjectIds != null ? child.sourceObjectIds : new int[0];
        int[] childVisuals = visualSourceIds(child);
        if (childSources.length > 0 && containsAll(carrierSources, childSources)) return true;
        if (child.domId >= 0 && contains(carrierSources, child.domId)) return true;
        if (childVisuals.length > 0 && containsAll(carrierVisuals, childVisuals)) return true;
        if (childSources.length > 0 && containsAll(carrierVisuals, childSources)) return true;
        return false;
    }

    private static boolean hasDifferentTextShellRole(ObjectPlan carrier, ObjectPlan child) {
        ShellRole carrierRole = ShellRole.from(carrier);
        ShellRole childRole = ShellRole.from(child);
        return carrierRole != ShellRole.NONE
                && childRole != ShellRole.NONE
                && carrierRole != childRole;
    }

    private boolean compositeCarrierOwnsVisibleChildShellFragment(ObjectPlan carrier, ObjectPlan child) {
        if (carrier == null || child == null) return false;
        if (!carrierDeclaredVisibleSlotContainsChild(carrier, child)) return false;
        List<ObjectPlan> childShells = visibleChildShellSlotsForCompositeCarrier(carrier);
        if (childShells.isEmpty()) return false;
        if (!containsPlanWithSameDomIdAndKind(childShells, child)) return false;
        return compositeCarrierHasVisibleMaterialOutsideChildShellSlots(carrier, childShells);
    }

    private static boolean containsPlanWithSameDomIdAndKind(List<ObjectPlan> plans, ObjectPlan target) {
        if (plans == null || target == null) return false;
        for (ObjectPlan plan : plans) {
            if (plan == null) continue;
            if (plan.domId == target.domId && safe(plan.kind).equals(safe(target.kind))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isVisibleCompositeShellCarrier(ObjectPlan plan) {
        return isVisualOnlyCompositeShellCarrier(plan)
                || isTextHiddenCompositeShellCarrier(plan);
    }

    private static boolean hasDistinctChildShellSlotSignal(ObjectPlan plan) {
        if (plan == null) return false;
        return hasDistinctChildShellSlotSignalReason(plan.reason);
    }

    private static boolean hasDistinctChildShellSlotSignalReason(String value) {
        String reason = safe(value);
        return "decoration_group".equals(reason)
                || "pure_decoration_group".equals(reason)
                || reason.contains("direct_label_shell_split_from_composite_carrier");
    }

    private boolean compositeCarrierHasVisibleMaterialOutsideChildShellSlots(
            ObjectPlan carrier,
            List<ObjectPlan> childShells) {
        if (carrier == null || childShells == null || childShells.isEmpty() || data == null) {
            return true;
        }
        LinkedHashSet<Integer> covered = new LinkedHashSet<>();
        for (ObjectPlan child : childShells) {
            covered.add(child.domId);
            addAll(child.sourceObjectIds, covered);
            addAll(visualSourceIds(child), covered);
        }
        for (int sourceId : visualSourceIds(carrier)) {
            if (covered.contains(sourceId)) continue;
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (sourceItemHasVisibleShellMaterial(item)) return true;
        }
        return false;
    }

    private boolean compositeCarrierVisibleSourcesCoveredByCanonicalPlans(ObjectPlan carrier) {
        if (carrier == null || data == null) return false;
        if (!isDeclaredVisualOnlyTextlessShellCarrier(carrier)) return false;
        if (isExtractorDeclaredCompositeShellCarrier(carrier)) return false;
        int[] visualSourceIds = visualSourceIds(carrier);
        if (visualSourceIds.length == 0) return false;

        boolean hasCoveredVisibleSource = false;
        for (int sourceId : visualSourceIds) {
            if (sourceId <= 0) continue;
            if (sourceId == carrier.domId && !sourceIdHasOwnVisibleMaterial(sourceId)) {
                continue;
            }
            if (sourceIdCoveredByCanonicalPlan(carrier, sourceId)) {
                hasCoveredVisibleSource = true;
                continue;
            }
            if (sourceIdHasOwnVisibleMaterial(sourceId)) {
                return false;
            }
        }
        return hasCoveredVisibleSource;
    }

    private boolean isExtractorDeclaredCompositeShellCarrier(ObjectPlan plan) {
        if (plan == null || data == null) return false;
        if (!"slot_only_textless_shell".equals(safe(plan.reason))) return false;
        RenderedGroup rendered = renderedGroupForPlan(plan);
        if (rendered == null) return false;
        if (!"indesign_png".equals(safe(rendered.visualOwner()))) return false;
        if (!"hwpx_tf".equals(safe(rendered.textOwner()))) return false;
        if (rendered.file() == null || rendered.file().isEmpty()) return false;
        return rendered.exportSourceObjectIds() != null
                && rendered.exportSourceObjectIds().length > 0
                && rendered.sourceObjectIds() != null
                && rendered.sourceObjectIds().length > rendered.exportSourceObjectIds().length;
    }

    private boolean sourceIdCoveredByCanonicalPlan(ObjectPlan carrier, int sourceId) {
        if (carrier == null || sourceId <= 0) return false;
        for (ObjectPlan candidate : plans) {
            if (candidate == null || candidate == carrier) continue;
            if (candidate.pageIndex != carrier.pageIndex
                    && !candidateCanOwnSourceAcrossPageIndex(candidate, sourceId)) {
                continue;
            }
            if (sourceCount(candidate) >= sourceCount(carrier)) continue;
            if (candidate.hasVisibleVisual()
                    && (contains(visualSourceIds(candidate), sourceId)
                    || contains(candidate.sourceObjectIds, sourceId))) {
                return true;
            }
            if (candidate.textAction == TextAction.OWNED_BY_HWPX_TEXT
                    && (contains(candidate.ownedTextFrameIds, sourceId)
                    || contains(candidate.sourceObjectIds, sourceId))) {
                return true;
            }
        }
        return false;
    }

    private boolean candidateCanOwnSourceAcrossPageIndex(ObjectPlan candidate, int sourceId) {
        if (candidate == null || sourceId <= 0) return false;
        if (!contains(candidate.sourceObjectIds, sourceId)
                && !contains(visualSourceIds(candidate), sourceId)
                && !contains(candidate.ownedTextFrameIds, sourceId)) {
            return false;
        }
        return sourceIdIsInlineSource(sourceId);
    }

    private boolean sourceIdIsInlineSource(int sourceId) {
        if (data == null || sourceId <= 0) return false;
        if (data.isInlineObjectId(sourceId)) return true;
        ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
        if (item != null && item.isInline()) return true;
        ResolvedTextFrame tf = data.getTextFrame(String.valueOf(sourceId));
        return tf != null && tf.isInline();
    }

    private boolean sourceIdHasOwnVisibleMaterial(int sourceId) {
        if (data == null || sourceId <= 0) return false;
        ResolvedTextFrame tf = data.getTextFrame(String.valueOf(sourceId));
        if (tf != null) return !tf.sourceHidden();
        ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
        return sourceItemHasVisibleShellMaterial(item);
    }

    private static boolean carrierVisuallyOwnsDirectShellSlot(ObjectPlan carrier, ObjectPlan child) {
        int[] carrierVisualSources = visualSourceIds(carrier);
        int[] childVisualSources = visualSourceIds(child);
        if (carrierVisualSources.length == 0 || childVisualSources.length == 0) return false;
        return containsAll(carrierVisualSources, childVisualSources);
    }

    private static boolean isExtractedCompositeTextShellParent(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        if (plan.materialization != Materialization.EXTRACTED_PNG_VECTOR) return false;
        if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length <= 1) return false;
        String reason = safe(plan.reason);
        return reason.contains("text_composite_editable_text_hidden")
                || reason.contains("editable_composite_text_hidden_shell")
                || reason.contains("mixed_group_text_hidden")
                || reason.contains("image_group_text_hidden");
    }

    private boolean ownedTextFramesCoveredByDistinctDirectShellSlots(ObjectPlan parent) {
        LinkedHashSet<Integer> uncovered = new LinkedHashSet<>();
        for (int tfId : parent.ownedTextFrameIds) {
            uncovered.add(tfId);
        }
        int coveringSlotCount = 0;
        for (ObjectPlan candidate : plans) {
            if (candidate == null || candidate == parent) continue;
            if (candidate.pageIndex != parent.pageIndex) continue;
            if (candidate.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (candidate.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (candidate.ownedTextFrameIds == null || candidate.ownedTextFrameIds.length == 0) continue;
            if (!isDirectTextShellSlot(candidate)) continue;
            if (!containsAll(parent.sourceObjectIds, candidate.sourceObjectIds)) continue;
            boolean coversAny = false;
            for (int tfId : candidate.ownedTextFrameIds) {
                if (uncovered.remove(tfId)) {
                    coversAny = true;
                }
            }
            if (coversAny) coveringSlotCount++;
            if (uncovered.isEmpty() && coveringSlotCount >= 2) return true;
        }
        return false;
    }

    private static boolean isDirectTextShellSlot(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.materialization == Materialization.NATIVE_SOURCE_SHAPE) return true;
        return isAtomicOwnershipRootTextHiddenShellPlan(plan)
                || "sibling_group_text_shell".equals(plan.reason)
                || isDirectExtractedChildTextShellSlot(plan)
                || isDirectInlineTextShellReason(plan.reason);
    }

    private static boolean isDirectExtractedChildTextShellSlot(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.materialization != Materialization.EXTRACTED_PNG_VECTOR) return false;
        if (visualSourceIds(plan).length != 1) return false;
        String reason = safe(plan.reason);
        return reason.contains("complex_graphic_text_hidden")
                || reason.contains("mixed_group_text_hidden")
                || reason.contains("image_group_text_hidden");
    }

    private static boolean isNativeTextShellShape(ResolvedPageItem item) {
        if (item == null || item.sourceHidden()) return false;
        if (!isNativeSourceShapeMaterializationAllowed(item)) return false;
        if (boundsOf(item) == null) return false;
        boolean hasFill = !isNoneColor(item.fillColorName());
        boolean hasStroke = !isNoneColor(item.strokeColorName()) && item.strokeWeight() > 0.01;
        return hasFill || hasStroke || item.cornerRadius() > 0;
    }

    private static boolean isNativeSourceShapeMaterializationAllowed(ResolvedPageItem item) {
        if (item == null) return false;
        String type = safe(item.type());
        return "Rectangle".equals(type)
                || "Oval".equals(type)
                || "Polygon".equals(type)
                || "GraphicLine".equals(type);
    }

    private static boolean hasColoredNativeShell(ResolvedPageItem item) {
        return item != null
                && !isNoneColor(item.fillColorName())
                && !isPaperColor(item.fillColorName());
    }

    private static boolean hasDrawableNativeShell(ResolvedPageItem item) {
        if (item == null) return false;
        String type = safe(item.type());
        if (!("Rectangle".equals(type) || "Polygon".equals(type) || "Oval".equals(type))) {
            return false;
        }
        boolean hasFill = !isNoneColor(item.fillColorName());
        boolean hasStroke = !isNoneColor(item.strokeColorName()) && item.strokeWeight() > 0.01;
        return hasFill || hasStroke || item.cornerRadius() > 0.01;
    }

    private double[] pageRelativeBoundsOf(ResolvedPageItem item) {
        if (item == null) return null;
        double[] direct = item.pageRelativeBounds();
        if (direct != null && direct.length >= 4) {
            return direct;
        }
        double[] b = item.geometricBounds();
        if (b == null || b.length < 4) return null;
        int pageIndex = item.pageIndex();
        double[] page = pageBounds(pageIndex);
        if (page == null || page.length < 4) {
            return b;
        }
        double scale = safeScaleFactor();
        double pageWidth = page[3] - page[1];
        double pageHeight = page[2] - page[0];
        boolean pageBoundsLookScaled = scale > 1.001
                && (pageWidth > 400.0 || pageHeight > 400.0);
        double divisor = pageBoundsLookScaled ? scale : 1.0;
        return new double[] {
                (b[0] - page[0]) / divisor,
                (b[1] - page[1]) / divisor,
                (b[2] - page[0]) / divisor,
                (b[3] - page[1]) / divisor
        };
    }

    /**
     * Source ownership policy: a visual-only shell and editable TextFrame that are siblings
     * under the same IDML group form one text-shell object. The shell owns only the extracted
     * visual; the sibling TextFrame owns the editable HWPX text.
     */
    private void resolveSiblingGroupTextShellOwners() {
        if (data == null) return;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan shell = plans.get(i);
            if (!isSiblingGroupTextShellCandidate(shell)) continue;
            int[] ownedTextFrameIds = siblingEditableTextFrameIdsForShell(shell);
            if (ownedTextFrameIds.length == 0) continue;
            VisualLayer shellLayer = textShellVisualLayer(shell, ownedTextFrameIds, null);

            plans.set(i, shell
                    .withTextAction(TextAction.OWNED_BY_HWPX_TEXT)
                    .withVisualAction(VisualAction.PLACE_TEXT_SHELL, "sibling_group_text_shell")
                    .withVisualLayer(shellLayer)
                    .withOwnedTextFrameIds(ownedTextFrameIds)
                    .withSourceObjectIds(appendMissing(shell.sourceObjectIds, ownedTextFrameIds)));
        }
    }

    private void normalizePaperPageMaterialVisualPlans() {
        if (data == null) return;
        int normalized = 0;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!isPageMaterialVisualPlan(plan)) continue;
            plans.set(i, plan
                    .withTextAction(TextAction.DROP_TEXT)
                    .withOwnedTextFrameIds(new int[0])
                    .withVisualLayer(VisualLayer.CONTENT_VISUAL)
                    .withMaterialization(plan.materialization)
                    .withVisualAction(VisualAction.PLACE_FLOATING_PNG,
                            "paper_page_material_backdrop"));
            normalized++;
        }
        if (normalized > 0) {
            ConversionTiming.metric("stage1.ownershipPlanner.normalizePaperPageMaterialVisualPlans.normalized", normalized);
        }
    }

    private boolean nativeSourceShapeMaterializationDisabled() {
        return true;
    }

    private void dropNativeSourceShapePlans() {
        if (plans == null || plans.isEmpty()) return;
        int before = plans.size();
        plans.removeIf(plan -> plan != null
                && plan.materialization == Materialization.NATIVE_SOURCE_SHAPE
                && !isAllowedNativeSourceShapePlan(plan));
        int dropped = before - plans.size();
        if (dropped > 0) {
            ctx.ownershipWarningLines.add("{\"code\":\"STAGE1_NATIVE_SOURCE_SHAPE_PLANS_DROPPED\""
                    + ",\"detail\":\"dropped=" + dropped
                    + "; unsupported HWP native shape materialization; graphics must be declared as an allowed source-native slot\"}");
            ConversionTiming.metric("stage1.ownershipPlanner.dropNativeSourceShapePlans.dropped", dropped);
        }
    }

    private boolean isAllowedNativeSourceShapePlan(ObjectPlan plan) {
        return isDirectInlineTextFrameDrawTextPlan(plan)
                || isDeclaredTextRangeNativeShellPlan(plan);
    }

    private static boolean isDeclaredTextRangeNativeShellPlan(ObjectPlan plan) {
        return plan != null
                && plan.materialization == Materialization.NATIVE_SOURCE_SHAPE
                && plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                && plan.placement == Placement.FLOATING
                && plan.coordinateSpace == CoordinateSpace.PAGE
                && plan.ownedTextRanges != null
                && plan.ownedTextRanges.length > 0;
    }

    private boolean isDirectInlineTextFrameDrawTextPlan(ObjectPlan plan) {
        if (plan == null || data == null) return false;
        if (plan.materialization != Materialization.NATIVE_SOURCE_SHAPE) return false;
        if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        if (plan.placement != Placement.INLINE) return false;
        if (plan.coordinateSpace != CoordinateSpace.STORY_FLOW) return false;
        if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length != 1) return false;
        int textFrameId = plan.ownedTextFrameIds[0];
        if (textFrameId < 0) return false;
        if (plan.domId != textFrameId && !contains(plan.sourceObjectIds, textFrameId)) return false;

        ResolvedPageItem item = data.getPageItem(String.valueOf(textFrameId));
        if (item == null || !"TextFrame".equals(safe(item.type()))) return false;
        ResolvedTextFrame tf = data.getTextFrame(String.valueOf(textFrameId));
        if (tf == null || !tf.isInline()) return false;
        return item.storyTextInlineSlot()
                || "INLINE".equals(safe(item.storyAnchorPlacement()))
                || "INLINE_POSITION".equals(safe(item.anchoredPosition()))
                || item.isInline();
    }

    private void normalizePageSpanningBackdropTextShellPlans() {
        if (data == null) return;
        int normalized = 0;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null || plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (plan.placement != Placement.FLOATING) continue;
            if (!isPageSpanningBackdropVisualPlan(plan)) continue;
            plans.set(i, pageSpanningBackdropVisualPlan(plan));
            normalized++;
        }
        if (normalized > 0) {
            ConversionTiming.metric("stage1.ownershipPlanner.normalizePageSpanningBackdropTextShellPlans.normalized", normalized);
        }
    }

    private void normalizePageSpanningBackdropVisualFragments() {
        if (data == null) return;
        int normalized = 0;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!isPageSpanningBackdropVisualFragmentPlan(plan)) continue;
            plans.set(i, plan
                    .withTextAction(TextAction.DROP_TEXT)
                    .withOwnedTextFrameIds(new int[0])
                    .withVisualLayer(VisualLayer.CONTENT_VISUAL)
                    .withVisualAction(VisualAction.PLACE_FLOATING_PNG,
                            "page_spanning_backdrop_visual_fragment"));
            normalized++;
        }
        if (normalized > 0) {
            ConversionTiming.metric("stage1.ownershipPlanner.normalizePageSpanningBackdropVisualFragments.normalized", normalized);
        }
    }

    private boolean isPageSpanningBackdropVisualFragmentPlan(ObjectPlan plan) {
        if (plan == null || !plan.hasVisibleVisual()) return false;
        if (plan.visualAction != VisualAction.PLACE_FLOATING_PNG) return false;
        if (plan.placement != Placement.FLOATING) return false;
        if (plan.materialization != Materialization.TEXTLESS_VISUAL_FRAGMENT) return false;
        if (plan.visualPolicyLayer() != PolicyLayer.BACKGROUND) return false;
        if (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) return false;
        if (plan.hiddenVisualSourceObjectIds != null && plan.hiddenVisualSourceObjectIds.length > 0) return false;
        if (!isBackgroundBoundsSanityCandidate(plan.bounds)) return false;
        if (!hasPageLevelSourceObject(plan.sourceObjectIds)) return false;
        return pageSpanningBackdropSourceIds(plan).length > 0;
    }

    private boolean hasPageLevelSourceObject(int[] sourceObjectIds) {
        if (sourceObjectIds == null || sourceObjectIds.length == 0 || data == null) return false;
        for (int sourceId : sourceObjectIds) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item == null) continue;
            if (item.parentId() == null || item.parentId().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private boolean isPageMaterialVisualPlan(ObjectPlan plan) {
        if (plan == null || !plan.hasVisibleVisual()) return false;
        if (plan.visualAction != VisualAction.PLACE_FLOATING_PNG) return false;
        if (plan.placement != Placement.FLOATING) return false;
        if (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) return false;
        int[] sourceIds = visualSourceIds(plan);
        if (sourceIds.length == 0) return false;
        boolean hasMaterialBackdrop = false;
        for (int sourceId : sourceIds) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item == null) return false;
            if (!isSingleColorPageBackgroundSourceItem(item)) return false;
            double[] pageLocal = pageLocalBoundsOf(item, plan.pageIndex, true);
            if (isMaterialPageBackdropOnPage(item, plan.pageIndex, pageLocal)) {
                hasMaterialBackdrop = true;
            }
        }
        return hasMaterialBackdrop && isLowestPageWideBackgroundSourceDepth(sourceIds, plan.pageIndex);
    }

    private boolean isNativePageMaterialShapeVisualPlan(ObjectPlan plan) {
        if (plan == null) return false;
        int[] sourceIds = visualSourceIds(plan);
        if (sourceIds.length == 0) return false;
        for (int sourceId : sourceIds) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (!isSingleColorPageBackgroundSourceItem(item)) return false;
        }
        return true;
    }

    private static boolean isPaperFillOnlyPageMaterialItem(ResolvedPageItem item) {
        if (item == null) return false;
        String type = safe(item.type());
        if (!"Rectangle".equals(type) && !"Polygon".equals(type)) return false;
        if (!isPaperColor(item.fillColorName())) return false;
        String stroke = safe(item.strokeColorName());
        return isNoneColor(stroke) || isPaperColor(stroke) || item.strokeWeight() <= 0.01;
    }

    private static boolean isPlainFillOnlyPageMaterialItem(ResolvedPageItem item) {
        if (item == null) return false;
        String type = safe(item.type());
        if (!"Rectangle".equals(type) && !"Polygon".equals(type)) return false;
        String fill = safe(item.fillColorName());
        if (fill.isEmpty() || isNoneColor(fill)) return false;
        String stroke = safe(item.strokeColorName());
        return isNoneColor(stroke) || item.strokeWeight() <= 0.01;
    }

    private static boolean isSingleColorPageBackgroundSourceItem(ResolvedPageItem item) {
        if (!isPlainFillOnlyPageMaterialItem(item)) return false;
        if (item.childIds() != null && item.childIds().length > 0) return false;
        if (Math.abs(item.absoluteRotationAngle()) > 0.1) return false;
        if (Math.abs(item.absoluteShearAngle()) > 0.1) return false;
        if (item.hasDropShadow()) return false;
        if (item.gradientFeatherApplied()) return false;
        return true;
    }

    private boolean isSingleSourcePageWideBackground(int[] sourceIds, int pageIndex) {
        if (sourceIds == null || sourceIds.length == 0 || data == null) return false;
        int[] roots = sourceRootObjectIds(sourceIds);
        if (roots.length != 1) return false;
        ResolvedPageItem root = data.getPageItem(String.valueOf(roots[0]));
        if (!isSingleColorPageBackgroundSourceItem(root)) return false;
        double[] pageLocal = pageLocalBoundsOf(root, pageIndex, true);
        if (!isMaterialPageBackdropOnPage(root, pageIndex, pageLocal)) return false;
        return isLowestPageWideBackgroundSourceDepth(roots, pageIndex);
    }

    private boolean isLowestPageWideBackgroundSourceDepth(int[] sourceIds, int pageIndex) {
        if (sourceIds == null || sourceIds.length == 0 || data == null) return false;
        int sourceZ = minPageItemZOrder(sourceIds);
        if (sourceZ < 0) return false;
        int lowestPageZ = minVisibleSourceZOrderOnPage(pageIndex);
        return lowestPageZ < 0 || sourceZ <= lowestPageZ;
    }

    private int minPageItemZOrder(int[] sourceIds) {
        if (sourceIds == null || sourceIds.length == 0 || data == null) return -1;
        int min = Integer.MAX_VALUE;
        for (int sourceId : sourceIds) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item != null) {
                min = Math.min(min, item.zOrder());
            }
        }
        return min == Integer.MAX_VALUE ? -1 : min;
    }

    private int minVisiblePageLevelSourceZOrder(int pageIndex) {
        if (data == null || data.pageItems() == null) return -1;
        int min = Integer.MAX_VALUE;
        for (ResolvedPageItem item : data.pageItems()) {
            if (item == null || item.sourceHidden() || item.isInline()) continue;
            if (item.parentId() != null && !item.parentId().isBlank()) continue;
            double[] pageLocal = pageLocalBoundsOf(item, pageIndex, true);
            if (pageLocal == null || pageLocal.length < 4 || area(pageLocal) <= 0.0) continue;
            min = Math.min(min, item.zOrder());
        }
        return min == Integer.MAX_VALUE ? -1 : min;
    }

    private int minVisibleSourceZOrderOnPage(int pageIndex) {
        if (data == null || data.pageItems() == null) return -1;
        int min = Integer.MAX_VALUE;
        for (ResolvedPageItem item : data.pageItems()) {
            if (item == null || item.sourceHidden() || item.isInline()) continue;
            double[] pageLocal = pageLocalBoundsOf(item, pageIndex, true);
            if (pageLocal == null || pageLocal.length < 4 || area(pageLocal) <= 0.0) continue;
            min = Math.min(min, item.zOrder());
        }
        return min == Integer.MAX_VALUE ? -1 : min;
    }

    private void resolveIndependentSiblingTextShellOwners() {
        if (data == null) return;
        int candidateShells = 0;
        int imageBackedCandidateShells = 0;
        int matchedShells = 0;
        int matchedTextFrames = 0;
        int textFrameCandidatesScanned = 0;
        Set<Integer> ownedTextFrameIds = visibleTextShellOwnedTextFrameIds();
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan shell = plans.get(i);
            boolean plainShell = isIndependentSiblingTextShellCandidate(shell);
            boolean imageBackedCalloutShell = !plainShell
                    && isIndependentImageBackedCalloutTextShellCandidate(shell);
            if (!plainShell && !imageBackedCalloutShell) continue;
            candidateShells++;
            if (imageBackedCalloutShell) {
                imageBackedCandidateShells++;
            }
            if (imageBackedCalloutShell && isPageSpanningBackdropVisualPlan(shell)) {
                plans.set(i, pageSpanningBackdropVisualPlan(shell));
                continue;
            }
            List<ResolvedTextFrame> pageTextFrames = visibleEditableTextFramesOnPage(shell.pageIndex);
            textFrameCandidatesScanned += pageTextFrames.size();
            int[] matchedTextFrameIds = imageBackedCalloutShell
                    ? independentCalloutEditableTextFrameIdsForShellSet(shell, pageTextFrames, ownedTextFrameIds)
                    : independentSiblingEditableTextFrameIdsForShell(shell, false, pageTextFrames, ownedTextFrameIds);
            if (matchedTextFrameIds.length == 0) continue;
            matchedShells++;
            matchedTextFrames += matchedTextFrameIds.length;
            for (int matchedTextFrameId : matchedTextFrameIds) {
                ownedTextFrameIds.add(matchedTextFrameId);
            }
            VisualLayer layer = textShellVisualLayer(shell, matchedTextFrameIds, VisualLayer.LABEL_BACKDROP);
            plans.set(i, shell
                    .withTextAction(TextAction.OWNED_BY_HWPX_TEXT)
                    .withVisualAction(VisualAction.PLACE_TEXT_SHELL,
                            imageBackedCalloutShell
                                    ? "independent_image_backed_callout_text_shell"
                                    : "independent_sibling_text_shell")
                    .withVisualLayer(layer)
                    .withOwnedTextFrameIds(matchedTextFrameIds)
                    .withSourceObjectIds(appendMissing(shell.sourceObjectIds, matchedTextFrameIds)));
        }
        ConversionTiming.metric("stage1.ownershipPlanner.resolveIndependentSiblingTextShellOwners.candidateShells", candidateShells);
        ConversionTiming.metric("stage1.ownershipPlanner.resolveIndependentSiblingTextShellOwners.imageBackedCandidateShells", imageBackedCandidateShells);
        ConversionTiming.metric("stage1.ownershipPlanner.resolveIndependentSiblingTextShellOwners.matchedShells", matchedShells);
        ConversionTiming.metric("stage1.ownershipPlanner.resolveIndependentSiblingTextShellOwners.matchedTextFrames", matchedTextFrames);
        ConversionTiming.metric("stage1.ownershipPlanner.resolveIndependentSiblingTextShellOwners.textFrameCandidatesScanned", textFrameCandidatesScanned);
    }

    private boolean isPageSpanningBackdropVisualPlan(ObjectPlan plan) {
        if (plan == null || !isBackgroundBoundsSanityCandidate(plan.bounds)) return false;
        if (isPlannerDeclaredObjectPlan(plan)) return false;
        int[] visualSourceIds = visualSourceIds(plan);
        if (visualSourceIds.length == 0) return false;
        if (!hasPageLevelSourceRoots(visualSourceIds)) return false;
        if (plan.hiddenVisualSourceObjectIds != null && plan.hiddenVisualSourceObjectIds.length > 0) return false;
        return pageSpanningBackdropSourceIds(plan).length > 0;
    }

    private ObjectPlan pageSpanningBackdropVisualPlan(ObjectPlan plan) {
        int[] backdropSourceIds = pageSpanningBackdropSourceIds(plan);
        if (backdropSourceIds.length == 0) {
            backdropSourceIds = visualSourceIds(plan);
        }
        return plan
                .withTextAction(TextAction.DROP_TEXT)
                .withOwnedTextFrameIds(new int[0])
                .withSourceObjectIds(backdropSourceIds)
                .withVisualSourceObjectIds(backdropSourceIds)
                .withSourceBundleKey(sourceBundleKeyOf(renderedGroupForPlan(plan), backdropSourceIds, new int[0]))
                .withVisualLayer(VisualLayer.CONTENT_VISUAL)
                .withMaterialization(Materialization.EXTRACTED_PNG_VECTOR)
                .withVisualAction(VisualAction.PLACE_FLOATING_PNG,
                        "page_spanning_backdrop_visual");
    }

    private int[] pageSpanningBackdropSourceIds(ObjectPlan plan) {
        if (plan == null || data == null) return new int[0];
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        for (int sourceId : visualSourceIds(plan)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item == null) continue;
            if (!isSingleColorPageBackgroundSourceItem(item)) continue;
            double[] pageLocal = pageLocalBoundsOf(item, plan.pageIndex, true);
            if (isMaterialPageBackdropOnPage(item, plan.pageIndex, pageLocal)) {
                ids.add(sourceId);
            }
        }
        int[] result = toIntArray(ids);
        return isLowestPageWideBackgroundSourceDepth(result, plan.pageIndex) ? result : new int[0];
    }

    private static boolean isLargeShellBounds(ObjectPlan shell) {
        if (shell == null || shell.bounds == null || shell.bounds.length < 4) return false;
        double h = Math.abs(shell.bounds[2] - shell.bounds[0]);
        double w = Math.abs(shell.bounds[3] - shell.bounds[1]);
        return w >= 120.0 || h >= 120.0 || (w * h) >= 6000.0;
    }

    private boolean hasColoredShapeShellSource(ObjectPlan shell) {
        if (shell == null || data == null) return false;
        for (int sourceId : visualSourceIds(shell)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item == null) continue;
            String type = safe(item.type());
            if (!("Rectangle".equals(type) || "Polygon".equals(type) || "Oval".equals(type))) {
                continue;
            }
            String fill = item.fillColorName();
            if (!isNoneColor(fill) && !isPaperColor(fill)) {
                return true;
            }
            String stroke = item.strokeColorName();
            if (!isNoneColor(stroke) && !isPaperColor(stroke) && item.strokeWeight() > 0.01) {
                return true;
            }
        }
        return false;
    }

    private boolean hasColoredShapeShellSource(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        for (int sourceId : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item == null) continue;
            String type = safe(item.type());
            if (!("Rectangle".equals(type) || "Polygon".equals(type) || "Oval".equals(type))) {
                continue;
            }
            String fill = item.fillColorName();
            if (!isNoneColor(fill) && !isPaperColor(fill)) {
                return true;
            }
            String stroke = item.strokeColorName();
            if (!isNoneColor(stroke) && !isPaperColor(stroke) && item.strokeWeight() > 0.01) {
                return true;
            }
        }
        return false;
    }

    private void normalizeSiblingGroupTextShellOwners() {
        Map<String, Integer> ownerByTextFrames = new LinkedHashMap<>();
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!isSiblingGroupTextShellOwner(plan)) continue;
            String key = siblingTextShellOwnerKey(plan);
            Integer currentIndex = ownerByTextFrames.get(key);
            if (currentIndex == null) {
                ownerByTextFrames.put(key, i);
                continue;
            }
            ObjectPlan current = plans.get(currentIndex);
            ObjectPlan better = betterSiblingGroupTextShellOwner(current, plan);
            if (better == plan) {
                plans.set(currentIndex, declareSecondarySiblingTextShellAsVisualOnly(current));
                ownerByTextFrames.put(key, i);
            } else {
                plans.set(i, declareSecondarySiblingTextShellAsVisualOnly(plan));
            }
        }
    }

    private boolean isSiblingGroupTextShellOwner(ObjectPlan plan) {
        return plan != null
                && ("sibling_group_text_shell".equals(plan.reason)
                || "independent_sibling_text_shell".equals(plan.reason))
                && plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                && plan.ownedTextFrameIds != null
                && plan.ownedTextFrameIds.length > 0;
    }

    private static String siblingTextShellOwnerKey(ObjectPlan plan) {
        int[] ids = plan.ownedTextFrameIds != null
                ? Arrays.copyOf(plan.ownedTextFrameIds, plan.ownedTextFrameIds.length)
                : new int[0];
        Arrays.sort(ids);
        StringBuilder sb = new StringBuilder();
        sb.append(plan.pageIndex).append(':');
        for (int id : ids) {
            sb.append(id).append('_');
        }
        return sb.toString();
    }

    private ObjectPlan betterSiblingGroupTextShellOwner(ObjectPlan a, ObjectPlan b) {
        if (compositeTextShellHasExtraMaterialOutsideChild(a, b)) return a;
        if (compositeTextShellHasExtraMaterialOutsideChild(b, a)) return b;
        double aScore = siblingGroupTextShellFitScore(a);
        double bScore = siblingGroupTextShellFitScore(b);
        if (bScore > aScore + 0.001) return b;
        if (aScore > bScore + 0.001) return a;
        int aSources = sourceCount(a);
        int bSources = sourceCount(b);
        if (aSources != bSources) return aSources < bSources ? a : b;
        return a.zOrder <= b.zOrder ? a : b;
    }

    private double siblingGroupTextShellFitScore(ObjectPlan plan) {
        if (plan == null || plan.bounds == null) return Double.NEGATIVE_INFINITY;
        double[] textBounds = unionOwnedTextFrameBounds(plan.ownedTextFrameIds);
        double textArea = area(textBounds);
        double shellArea = area(plan.bounds);
        if (textArea <= 0.0 || shellArea <= 0.0) return Double.NEGATIVE_INFINITY;

        double score = 0.0;
        if (boundsContains(plan.bounds, textBounds, 1.0)) score += 1000.0;
        else if (boundsContains(plan.bounds, textBounds, 3.0)) score += 700.0;
        score += (overlapArea(plan.bounds, textBounds) / textArea) * 200.0;
        if (shellArea >= textArea) score += 120.0;
        double areaRatio = shellArea / textArea;
        score += 80.0 / (1.0 + Math.abs(areaRatio - 1.45));
        if (hasNonGroupVisualSource(plan)) score += 80.0;
        score += 20.0 / Math.max(1, sourceCount(plan));
        return score;
    }

    private double[] unionOwnedTextFrameBounds(int[] textFrameIds) {
        if (data == null || textFrameIds == null || textFrameIds.length == 0) return null;
        double top = Double.POSITIVE_INFINITY;
        double left = Double.POSITIVE_INFINITY;
        double bottom = Double.NEGATIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY;
        boolean any = false;
        for (int id : textFrameIds) {
            ResolvedTextFrame tf = data.getTextFrame(String.valueOf(id));
            double[] b = boundsOf(tf);
            if (b == null || b.length < 4) continue;
            top = Math.min(top, b[0]);
            left = Math.min(left, b[1]);
            bottom = Math.max(bottom, b[2]);
            right = Math.max(right, b[3]);
            any = true;
        }
        return any ? new double[] { top, left, bottom, right } : null;
    }

    private boolean hasNonGroupVisualSource(ObjectPlan plan) {
        if (data == null || plan == null) return false;
        for (int sourceId : visualSourceIds(plan)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item != null && !"Group".equals(item.type())) return true;
        }
        return false;
    }

    private ObjectPlan declareSecondarySiblingTextShellAsVisualOnly(ObjectPlan plan) {
        int[] visualSources = visualSourceIds(plan);
        return plan
                .withTextAction(TextAction.DROP_TEXT)
                .withVisualAction(VisualAction.PLACE_FLOATING_PNG,
                        "sibling_group_secondary_visual")
                .withOwnedTextFrameIds(new int[0])
                .withSourceObjectIds(visualSources)
                .withDescendantVisualObjectIds(new int[0]);
    }

    private boolean isSiblingGroupTextShellCandidate(ObjectPlan plan) {
        if (plan == null || data == null) return false;
        if (!isRenderedVisualPlan(plan)) return false;
        if (!plan.hasVisibleVisual()) return false;
        if (plan.placement != Placement.FLOATING) return false;
        if (plan.visualAction != VisualAction.PLACE_FLOATING_PNG) return false;
        if (plan.textAction != TextAction.DROP_TEXT) return false;
        if (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) return false;
        if (plan.file == null || plan.file.isEmpty()) return false;
        if (plan.kind == null || !plan.kind.contains("page_object")) return false;
        RenderedGroup rg = renderedGroupForPlan(plan);
        if (rg == null) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return false;
        if (isLargeVisual(rg)) return false;
        return shellSourceHasGroupParent(plan);
    }

    private boolean shellSourceHasGroupParent(ObjectPlan shell) {
        for (int sourceId : visualSourceIds(shell)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item == null || item.parentId() == null) continue;
            ResolvedPageItem parent = data.getPageItem(item.parentId());
            if (parent != null && "Group".equals(parent.type())) return true;
        }
        return false;
    }

    private int[] siblingEditableTextFrameIdsForShell(ObjectPlan shell) {
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        LinkedHashSet<String> parentIds = visualSourceParentIds(shell);
        if (parentIds.isEmpty()) return new int[0];
        Map<String, List<ResolvedTextFrame>> textFramesByParentId = editableTextFramesByParentId();
        for (String parentId : parentIds) {
            List<ResolvedTextFrame> textFrames = textFramesByParentId.get(parentId);
            if (textFrames == null || textFrames.isEmpty()) continue;
            for (ResolvedTextFrame tf : textFrames) {
                if (tf == null || tf.id() == null) continue;
                if (data.isTextOwnedByIndesignPng(tf.id())) continue;
                if (!textFrameFitsShell(shell.bounds, boundsOf(tf))) continue;
                int id = parseInt(tf.id(), -1);
                if (id >= 0) ids.add(id);
            }
        }
        return toIntArray(ids);
    }

    private boolean isIndependentSiblingTextShellCandidate(ObjectPlan shell) {
        if (shell == null || data == null) return false;
        if (!isRenderedVisualPlan(shell)) return false;
        if (!shell.hasVisibleVisual()) return false;
        if (shell.placement != Placement.FLOATING) return false;
        if (shell.ownedTextFrameIds != null && shell.ownedTextFrameIds.length > 0) return false;
        if (shell.file == null || shell.file.isEmpty()) return false;
        if (isLargeShellBounds(shell)) return false;
        if (hasPlacedContentSourceTree(renderedGroupForPlan(shell))) return false;

        RenderedGroup rg = renderedGroupForPlan(shell);
        if (rg == null) return false;
        if (isPageOrSpreadBackdropImage(rg)) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) {
            return false;
        }
        if (rg.textOwner() != null && !"none".equals(rg.textOwner())) return false;
        if (shell.visualAction != VisualAction.PLACE_FLOATING_PNG
                && shell.visualAction != VisualAction.PLACE_TEXT_SHELL) {
            return false;
        }
        if (shell.textAction != TextAction.DROP_TEXT) return false;
        if (!hasColoredShapeShellSource(shell)) return false;
        return sourceTreeIsVisualOnlyShell(shell);
    }

    private boolean isIndependentImageBackedCalloutTextShellCandidate(ObjectPlan shell) {
        if (shell == null || data == null) return false;
        if (!isRenderedVisualPlan(shell)) return false;
        if (!shell.hasVisibleVisual()) return false;
        if (shell.placement != Placement.FLOATING) return false;
        if (shell.ownedTextFrameIds != null && shell.ownedTextFrameIds.length > 0) return false;
        if (shell.file == null || shell.file.isEmpty()) return false;
        if (shell.visualAction != VisualAction.PLACE_FLOATING_PNG
                && shell.visualAction != VisualAction.PLACE_TEXT_SHELL) {
            return false;
        }
        if (shell.textAction != TextAction.DROP_TEXT) return false;

        RenderedGroup rg = renderedGroupForPlan(shell);
        if (rg == null) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) {
            return false;
        }
        if (rg.textOwner() != null && !"none".equals(rg.textOwner())) return false;
        if (!hasPlacedContentSourceTree(rg)) return false;
        if (!hasVisibleShellMaterialSource(rg)) return false;
        return isImageBackedCalloutShellReason(rg.reason())
                || sourceSiblingCalloutTextFrameFits(shell);
    }

    private static boolean isImageBackedCalloutShellReason(String reason) {
        String r = safe(reason);
        return "complex_graphic_text_hidden".equals(r)
                || r.contains("image_group_text_hidden")
                || r.contains("callout")
                || r.contains("speech")
                || r.contains("bubble")
                || r.contains("말풍선");
    }

    private boolean sourceSiblingCalloutTextFrameFits(ObjectPlan shell) {
        if (shell == null || shell.bounds == null || shell.bounds.length < 4) return false;
        List<ResolvedTextFrame> pageTextFrames = visibleEditableTextFramesOnPage(shell.pageIndex);
        if (pageTextFrames == null || pageTextFrames.isEmpty()) return false;
        for (ResolvedTextFrame tf : pageTextFrames) {
            if (!sameSourceLayer(shell, tf)) continue;
            if (!textFrameStoryUsesCalloutOverlayStyle(tf)) continue;
            if (textFrameFitsShell(shell.bounds, boundsOf(tf))) return true;
        }
        return false;
    }

    private boolean sourceTreeIsVisualOnlyShell(ObjectPlan shell) {
        for (int sourceId : visualSourceIds(shell)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (!sourceTreeIsVisualOnlyShell(item, new HashSet<>())) return false;
        }
        return true;
    }

    private boolean sourceTreeIsVisualOnlyShell(ResolvedPageItem item, Set<String> visited) {
        if (item == null || item.id() == null || !visited.add(item.id())) return true;
        String type = safe(item.type());
        if ("TextFrame".equals(type) || "Image".equals(type) || "PDF".equals(type) || "EPS".equals(type)) {
            return false;
        }
        if ("Group".equals(type) || "page_object".equals(type)) {
            int[] childIds = item.childIds();
            if (childIds != null) {
                for (int childId : childIds) {
                    if (!sourceTreeIsVisualOnlyShell(
                            data.getPageItem(String.valueOf(childId)), visited)) {
                        return false;
                    }
                }
            }
            return true;
        }
        return "Rectangle".equals(type)
                || "Polygon".equals(type)
                || "Oval".equals(type)
                || "GraphicLine".equals(type);
    }

    private int[] independentSiblingEditableTextFrameIdsForShell(ObjectPlan shell) {
        return independentSiblingEditableTextFrameIdsForShell(shell, false);
    }

    private int[] independentCalloutEditableTextFrameIdsForShell(ObjectPlan shell) {
        return independentCalloutEditableTextFrameIdsForShellSet(shell);
    }

    private int[] independentSiblingEditableTextFrameIdsForShell(ObjectPlan shell, boolean requireCalloutStyle) {
        return independentSiblingEditableTextFrameIdsForShell(
                shell,
                requireCalloutStyle,
                visibleEditableTextFramesOnPage(shell != null ? shell.pageIndex : -1),
                visibleTextShellOwnedTextFrameIds());
    }

    private int[] independentSiblingEditableTextFrameIdsForShell(ObjectPlan shell,
                                                                 boolean requireCalloutStyle,
                                                                 List<ResolvedTextFrame> pageTextFrames,
                                                                 Set<Integer> ownedTextFrameIds) {
        if (shell == null || shell.bounds == null || shell.bounds.length < 4) return new int[0];
        ResolvedTextFrame best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        boolean ambiguous = false;
        if (pageTextFrames == null || pageTextFrames.isEmpty()) return new int[0];
        for (ResolvedTextFrame tf : pageTextFrames) {
            if (!sameSourceLayer(shell, tf)) continue;
            int id = parseFlexibleId(tf.id());
            if (id < 0) continue;
            if (ownedTextFrameIds != null && ownedTextFrameIds.contains(id)) continue;
            double[] tb = boundsOf(tf);
            if (!textFrameFitsShell(shell.bounds, tb)) continue;
            if (requireCalloutStyle && !ownedTextFrameIdsUseCalloutOverlayStyle(new int[] { id })) {
                continue;
            }
            double score = independentSiblingShellFitScore(shell.bounds, tb);
            if (score > bestScore + 0.001) {
                best = tf;
                bestScore = score;
                ambiguous = false;
            } else if (Math.abs(score - bestScore) <= 0.001) {
                ambiguous = true;
            }
        }
        if (best == null || ambiguous) return new int[0];
        int id = parseFlexibleId(best.id());
        return id >= 0 ? new int[] { id } : new int[0];
    }

    private int[] independentCalloutEditableTextFrameIdsForShellSet(ObjectPlan shell) {
        return independentCalloutEditableTextFrameIdsForShellSet(
                shell,
                visibleEditableTextFramesOnPage(shell != null ? shell.pageIndex : -1),
                visibleTextShellOwnedTextFrameIds());
    }

    private int[] independentCalloutEditableTextFrameIdsForShellSet(ObjectPlan shell,
                                                                    List<ResolvedTextFrame> pageTextFrames,
                                                                    Set<Integer> ownedTextFrameIds) {
        if (shell == null || shell.bounds == null || shell.bounds.length < 4) return new int[0];
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        if (pageTextFrames == null || pageTextFrames.isEmpty()) return new int[0];
        for (ResolvedTextFrame tf : pageTextFrames) {
            if (!sameSourceLayer(shell, tf)) continue;
            int id = parseFlexibleId(tf.id());
            if (id < 0) continue;
            if (ownedTextFrameIds != null && ownedTextFrameIds.contains(id)) continue;
            if (!ownedTextFrameIdsUseCalloutOverlayStyle(new int[] { id })) continue;
            double[] tb = boundsOf(tf);
            if (!textFrameFitsShell(shell.bounds, tb)) continue;
            ids.add(id);
        }
        return toIntArray(ids);
    }

    private List<ResolvedTextFrame> visibleEditableTextFramesOnPage(int pageIndex) {
        List<ResolvedTextFrame> frames = visibleEditableTextFramesByPage().get(pageIndex);
        return frames != null ? frames : java.util.Collections.emptyList();
    }

    private Map<Integer, List<ResolvedTextFrame>> visibleEditableTextFramesByPage() {
        if (visibleEditableTextFramesByPageCache != null) return visibleEditableTextFramesByPageCache;
        Map<Integer, List<ResolvedTextFrame>> result = new HashMap<>();
        if (data != null) {
            for (ResolvedTextFrame tf : data.textFrames()) {
                if (!isVisibleEditableTextFrameSource(tf)) continue;
                result.computeIfAbsent(tf.pageIndex(), k -> new ArrayList<>()).add(tf);
            }
        }
        visibleEditableTextFramesByPageCache = result;
        return visibleEditableTextFramesByPageCache;
    }

    private Set<Integer> visibleTextShellOwnedTextFrameIds() {
        Set<Integer> ids = new HashSet<>();
        for (ObjectPlan plan : plans) {
            if (plan == null || plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (!plan.hasVisibleVisual()) continue;
            if (plan.ownedTextFrameIds == null) continue;
            for (int id : plan.ownedTextFrameIds) {
                ids.add(id);
            }
        }
        return ids;
    }

    private boolean sameSourceLayer(ObjectPlan shell, ResolvedTextFrame tf) {
        if (shell == null || tf == null || tf.id() == null) return false;
        ResolvedPageItem tfItem = data.getPageItem(tf.id());
        if (tfItem == null) return false;
        if (shell.sourceLayerId != null && !shell.sourceLayerId.isBlank()
                && tfItem.layerId() != null && !tfItem.layerId().isBlank()) {
            return shell.sourceLayerId.equals(tfItem.layerId());
        }
        return shell.sourceLayerIndex < 0
                || tfItem.layerIndex() < 0
                || shell.sourceLayerIndex == tfItem.layerIndex();
    }

    private boolean hasVisibleTextShellOwnerForTextFrame(ResolvedTextFrame tf, ObjectPlan except) {
        if (tf == null || tf.id() == null) return false;
        int id = parseFlexibleId(tf.id());
        if (id < 0) return false;
        for (ObjectPlan plan : plans) {
            if (plan == null || plan == except) continue;
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (!plan.hasVisibleVisual()) continue;
            if (contains(plan.ownedTextFrameIds, id)) return true;
        }
        return false;
    }

    private static double independentSiblingShellFitScore(double[] shellBounds, double[] textBounds) {
        if (shellBounds == null || textBounds == null
                || shellBounds.length < 4 || textBounds.length < 4) {
            return Double.NEGATIVE_INFINITY;
        }
        double textArea = area(textBounds);
        double shellArea = area(shellBounds);
        if (textArea <= 0.0 || shellArea <= 0.0) return Double.NEGATIVE_INFINITY;
        double overlap = overlapArea(shellBounds, textBounds) / textArea;
        double areaRatio = shellArea / textArea;
        double ratioScore = 1.0 / (1.0 + Math.abs(areaRatio - 1.20));
        double centerScore = centerDistanceScore(shellBounds, textBounds);
        return overlap * 1000.0 + ratioScore * 100.0 + centerScore * 20.0;
    }

    private static double centerDistanceScore(double[] a, double[] b) {
        double acy = (a[0] + a[2]) / 2.0;
        double acx = (a[1] + a[3]) / 2.0;
        double bcy = (b[0] + b[2]) / 2.0;
        double bcx = (b[1] + b[3]) / 2.0;
        double ah = Math.max(1.0, Math.abs(a[2] - a[0]));
        double aw = Math.max(1.0, Math.abs(a[3] - a[1]));
        double dy = Math.abs(acy - bcy) / ah;
        double dx = Math.abs(acx - bcx) / aw;
        return 1.0 / (1.0 + dx + dy);
    }

    private LinkedHashSet<String> visualSourceParentIds(ObjectPlan shell) {
        LinkedHashSet<String> parentIds = new LinkedHashSet<>();
        for (int sourceId : visualSourceIds(shell)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item == null || item.parentId() == null) continue;
            ResolvedPageItem parent = data.getPageItem(item.parentId());
            if (parent == null || !"Group".equals(parent.type())) continue;
            parentIds.add(item.parentId());
        }
        return parentIds;
    }

    private static boolean textFrameFitsShell(double[] shellBounds, double[] textBounds) {
        if (shellBounds == null || textBounds == null
                || shellBounds.length < 4 || textBounds.length < 4) {
            return false;
        }
        double textArea = area(textBounds);
        if (textArea <= 0.0) return false;
        if (boundsContains(shellBounds, textBounds, 3.0)) return true;
        return overlapArea(shellBounds, textBounds) / textArea >= 0.80;
    }

    private int minTextFrameZOrder(int[] textFrameIds) {
        int min = Integer.MAX_VALUE;
        if (textFrameIds != null) {
            for (int id : textFrameIds) {
                ResolvedTextFrame tf = data.getTextFrame(String.valueOf(id));
                if (tf != null) min = Math.min(min, tf.zOrder());
            }
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    private static int[] appendMissing(int[] base, int[] extras) {
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        if (base != null) {
            for (int id : base) ids.add(id);
        }
        if (extras != null) {
            for (int id : extras) ids.add(id);
        }
        return toIntArray(ids);
    }

    private boolean isOwnedByAnchoredTablePlan(IDMLStory story) {
        if (ctx == null || story == null || story.tables() == null) return false;
        for (IDMLTable table : story.tables()) {
            if (table != null && ctx.isAnchoredTableSource(table.selfId())) {
                return true;
            }
        }
        return false;
    }

    private int[] editableTextFrameIdsOf(RenderedGroup rg) {
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        boolean rejectCompletePngTextOwner = shouldRejectRenderedCompletePngTextOwner(rg);
        if (rg != null && rg.editableTextFrameIds() != null) {
            for (String id : rg.editableTextFrameIds()) {
                int parsed = parseFlexibleId(id);
                if (parsed < 0) continue;
                if (!rejectCompletePngTextOwner
                        && data != null && !isVisibleEditableTextFrameSourceId(parsed)) continue;
                ids.add(parsed);
            }
        }
        if (ids.isEmpty() && rg != null
                && ("hwpx_tf".equals(rg.textOwner())
                || hasTextlessShellWithInferredEditableTextSource(rg))) {
            for (int id : inferredEditableTextFrameSourceIds(rg)) {
                ids.add(id);
            }
        }
        return toIntArray(ids);
    }

    private int[] inferredEditableTextFrameSourceIds(RenderedGroup rg) {
        if (rg == null || data == null) {
            return new int[0];
        }
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        LinkedHashSet<Integer> hiddenSourceIds = new LinkedHashSet<>();
        if (rg.hiddenVisualSourceObjectIds() != null) {
            for (int id : rg.hiddenVisualSourceObjectIds()) {
                if (id >= 0) hiddenSourceIds.add(id);
            }
        }
        LinkedHashSet<Integer> sourceRoots = new LinkedHashSet<>();
        if (rg.id() >= 0) sourceRoots.add(rg.id());
        if (rg.sourceObjectIds() != null) {
            for (int sourceId : rg.sourceObjectIds()) {
                if (sourceId >= 0) sourceRoots.add(sourceId);
            }
        }
        if (sourceRoots.isEmpty()) return new int[0];
        for (int sourceId : sourceRoots) {
            if (hiddenSourceIds.contains(sourceId)) continue;
            ResolvedTextFrame tf = data.getTextFrame(String.valueOf(sourceId));
            if (isVisibleEditableTextFrameSource(tf)) {
                ids.add(sourceId);
            }
            java.util.Set<String> descendants = data.buildDescendantSet(String.valueOf(sourceId), 16);
            for (String descendantId : descendants) {
                int parsed = parseFlexibleId(descendantId);
                if (parsed >= 0 && hiddenSourceIds.contains(parsed)) continue;
                ResolvedTextFrame childTf = data.getTextFrame(descendantId);
                if (!isVisibleEditableTextFrameSource(childTf)) continue;
                if (parsed >= 0) ids.add(parsed);
            }
        }
        return toIntArray(ids);
    }

    private boolean isVisibleEditableTextFrameSource(ResolvedTextFrame tf) {
        if (tf == null || tf.id() == null) return false;
        if (tf.sourceHidden()) return false;
        if (data != null && data.isTextOwnedByIndesignPng(tf.id())) return false;
        if (!hasSemanticText(tf)) return false;
        return true;
    }

    private boolean isVisibleEditableTextFrameSourceId(int sourceId) {
        if (sourceId < 0 || data == null) return false;
        return isVisibleEditableTextFrameSource(data.getTextFrame(String.valueOf(sourceId)));
    }

    private boolean hasVisibleEditableTextFrameSource(ObjectPlan plan) {
        if (plan == null || data == null) return false;
        if (plan.ownedTextFrameIds != null) {
            for (int id : plan.ownedTextFrameIds) {
                if (isVisibleEditableTextFrameSourceId(id)) return true;
            }
        }
        if (plan.sourceObjectIds != null) {
            for (int id : plan.sourceObjectIds) {
                if (isVisibleEditableTextFrameSourceId(id)) return true;
            }
        }
        return false;
    }

    private boolean hasSemanticEditableTextOwnerSignal(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (editableTextFrameIdsOf(rg).length > 0) return true;
        if (rg.sourceObjectIds() != null) {
            for (int sourceId : rg.sourceObjectIds()) {
                if (isVisibleEditableTextFrameSourceId(sourceId)) return true;
            }
        }
        return false;
    }

    private static String sourceBundleKeyOf(RenderedGroup rg, int[] sourceIds, int[] ownedTextFrameIds) {
        if (rg == null) return null;
        StringBuilder sb = new StringBuilder(64);
        sb.append('p').append(rg.pageIndex());
        if (sourceIds != null && sourceIds.length > 0) {
            sb.append(":s");
            for (int id : sourceIds) sb.append('_').append(id);
        } else {
            sb.append(":r").append(rg.id());
        }
        if (ownedTextFrameIds != null && ownedTextFrameIds.length > 0) {
            sb.append(":t");
            for (int id : ownedTextFrameIds) sb.append('_').append(id);
        }
        return sb.toString();
    }

    private int[] visualSourceIdsForRendered(
            RenderedGroup rg,
            int[] sourceIds,
            int[] ownedTextFrameIds,
            VisualAction visualAction) {
        if (sourceIds == null || sourceIds.length == 0) return new int[0];
        if (visualAction == VisualAction.DROP_VISUAL || visualAction == VisualAction.ABSORB_TEXT_STYLE
                || visualAction == VisualAction.PLACE_TABLE_STYLE) {
            return sourceIds;
        }
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        int[] executableSourceIds = renderedExportSourceIdsOrSourceIds(rg, sourceIds);
        LinkedHashSet<Integer> hiddenVisualSources = new LinkedHashSet<>();
        LinkedHashSet<Integer> hiddenTextlessShellSources = new LinkedHashSet<>();
        if (rg != null && rg.hiddenVisualSourceObjectIds() != null) {
            for (int hiddenSourceId : rg.hiddenVisualSourceObjectIds()) {
                hiddenVisualSources.add(hiddenSourceId);
                if (sourceIdIsTextlessVisibleTextFrameShellMaterial(hiddenSourceId)) {
                    hiddenTextlessShellSources.add(hiddenSourceId);
                }
            }
        }
        LinkedHashSet<Integer> executableCandidates = new LinkedHashSet<>();
        addAll(executableSourceIds, executableCandidates);
        for (int sourceId : hiddenTextlessShellSources) {
            if (contains(sourceIds, sourceId)) executableCandidates.add(sourceId);
        }
        for (int sourceId : executableCandidates) {
            boolean textlessShellSource = hiddenTextlessShellSources.contains(sourceId);
            if ((!contains(ownedTextFrameIds, sourceId) || textlessShellSource)
                    && (!hiddenVisualSources.contains(sourceId) || textlessShellSource)) {
                ids.add(sourceId);
            }
        }
        if (visualAction == VisualAction.PLACE_TEXT_SHELL) {
            ids.removeIf(this::isTableOnlySourceId);
        }
        if (visualAction == VisualAction.PLACE_TEXT_SHELL
                && rg != null
                && rg.nativeFillChildIds() != null
                && rg.nativeFillChildIds().length > 0) {
            for (int nativeFillChildId : rg.nativeFillChildIds()) {
                ids.remove(nativeFillChildId);
            }
        }
        if (ids.isEmpty() && !hiddenVisualSources.isEmpty()) {
            return new int[0];
        }
        return ids.isEmpty() ? executableSourceIds : toIntArray(ids);
    }

    private boolean hasExecutableTextShellVisualMaterial(int[] visualSourceIds) {
        if (data == null || visualSourceIds == null || visualSourceIds.length == 0) return false;
        for (int sourceId : visualSourceIds) {
            if (sourceIdHasVisibleTextFrameShellMaterial(sourceId)) return true;
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (sourceItemHasVisibleShellMaterial(item)) return true;
            if (hasPlacedContentSourceTree(sourceId)) return true;
        }
        return false;
    }

    private int[] renderedExportSourceIdsOrSourceIds(RenderedGroup rg, int[] sourceIds) {
        if (rg != null && rg.exportSourceObjectIds() != null && rg.exportSourceObjectIds().length > 0) {
            return rg.exportSourceObjectIds();
        }
        return sourceIds;
    }

    private boolean isTableOnlySourceId(int sourceId) {
        if (data == null) return false;
        ResolvedTextFrame tf = data.getTextFrame(String.valueOf(sourceId));
        if (tf == null) return false;
        IDMLStory story = loadStory(tf.storyId());
        return TableFrameOwnershipPolicy.isTableOnlyTextFrame(tf, story);
    }

    private void normalizeVisualSourcesExcludeOwnedTextFrames() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null) continue;
            if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL) continue;
            if (plan.visualSourceObjectIds == null || plan.visualSourceObjectIds.length == 0) continue;
            if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) continue;
            LinkedHashSet<Integer> ownedTextFrameIds = new LinkedHashSet<>();
            for (int tfId : plan.ownedTextFrameIds) {
                ownedTextFrameIds.add(tfId);
            }
            int[] retained = withoutSources(plan.visualSourceObjectIds, ownedTextFrameIds);
            if (retained.length == plan.visualSourceObjectIds.length) continue;
            if (retained.length == 0 && plan.hasVisibleVisual()) continue;
            plans.set(i, plan.withVisualSourceObjectIds(retained));
        }
    }

    private void completeTextFrameShellStyleSources() {
        if (data == null) return;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null) continue;
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (!plan.hasVisibleVisual()) continue;
            if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) continue;

            LinkedHashSet<Integer> completedSources = new LinkedHashSet<>();
            addAll(visualSourceIds(plan), completedSources);
            addAll(plan.styleSourceObjectIds, completedSources);

            for (int textFrameId : plan.ownedTextFrameIds) {
                if (!contains(plan.sourceObjectIds, textFrameId)) continue;
                if (!sourceIdHasVisibleTextFrameShellMaterial(textFrameId)) continue;
                completedSources.add(textFrameId);
            }
            if (completedSources.isEmpty()) continue;

            int[] visualSources = toIntArray(completedSources);
            if (Arrays.equals(visualSources, plan.visualSourceObjectIds)) continue;
            plans.set(i, plan.withVisualSourceObjectIds(visualSources));
        }
    }

    private void finalizeContainerOutlineDepthContracts() {
        if (plans == null || plans.isEmpty() || data == null) return;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan outline = plans.get(i);
            if (!isVisibleRenderedVisual(outline)) continue;
            if (outline.visualLayer != VisualLayer.CONTAINER_OUTLINE) continue;
            Set<String> parentIds = sourceParentIds(outline.sourceObjectIds);
            if (parentIds.isEmpty()) continue;

            int maxSiblingMaterializedZ = Integer.MIN_VALUE;
            for (ObjectPlan sibling : plans) {
                if (sibling == null || sibling == outline) continue;
                if (sibling.pageIndex != outline.pageIndex) continue;
                if (!sharesSourceParent(parentIds, sibling.sourceObjectIds)) continue;
                if (!isMaterializedSiblingForOutlineOrder(sibling)) continue;
                maxSiblingMaterializedZ = Math.max(maxSiblingMaterializedZ, sibling.zOrder);
            }
            if (maxSiblingMaterializedZ == Integer.MIN_VALUE) continue;
            int raisedZ = maxSiblingMaterializedZ + 1;
            if (outline.zOrder < raisedZ) {
                plans.set(i, outline.withZOrder(raisedZ));
            }
        }
    }

    private boolean isMaterializedSiblingForOutlineOrder(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                && plan.materialization == Materialization.HWPX_TEXT) {
            return true;
        }
        if (plan.visualLayer == VisualLayer.CONTAINER_BACKDROP
                || plan.visualLayer == VisualLayer.TEXT_CARD_BACKDROP
                || plan.visualLayer == VisualLayer.CONTAINER_FACE) {
            return true;
        }
        return plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                || plan.visualAction == VisualAction.PLACE_TABLE_STYLE;
    }

    private boolean sharesSourceParent(Set<String> parentIds, int[] sourceIds) {
        if (parentIds == null || parentIds.isEmpty() || sourceIds == null) return false;
        for (int sourceId : sourceIds) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item == null || item.parentId() == null || item.parentId().isEmpty()) continue;
            if (parentIds.contains(item.parentId())) return true;
        }
        return false;
    }

    private Set<String> sourceParentIds(int[] sourceIds) {
        HashSet<String> result = new HashSet<>();
        if (sourceIds == null || data == null) return result;
        for (int sourceId : sourceIds) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item == null || item.parentId() == null || item.parentId().isEmpty()) continue;
            result.add(item.parentId());
        }
        return result;
    }

    private String sourceLayerId(RenderedGroup rg, int[] sourceIds) {
        if (rg != null && rg.layerId() != null) return rg.layerId();
        ResolvedPageItem item = firstSourcePageItem(sourceIds);
        return item != null ? item.layerId() : null;
    }

    private String sourceLayerName(RenderedGroup rg, int[] sourceIds) {
        if (rg != null && rg.layerName() != null) return rg.layerName();
        ResolvedPageItem item = firstSourcePageItem(sourceIds);
        return item != null ? item.layerName() : null;
    }

    private int sourceLayerIndex(RenderedGroup rg, int[] sourceIds) {
        if (rg != null && rg.layerIndex() >= 0) return rg.layerIndex();
        ResolvedPageItem item = firstSourcePageItem(sourceIds);
        return item != null ? item.layerIndex() : -1;
    }

    private ResolvedPageItem firstSourcePageItem(int[] sourceIds) {
        if (data == null || sourceIds == null) return null;
        for (int sourceId : sourceIds) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item != null) return item;
        }
        return null;
    }

    private boolean hasHiddenSourceObject(int[] sourceIds) {
        if (data == null || sourceIds == null) return false;
        for (int sourceId : sourceIds) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item != null && item.sourceHidden()) return true;
            ResolvedTextFrame tf = data.getTextFrame(String.valueOf(sourceId));
            if (tf != null && tf.sourceHidden()) return true;
        }
        return false;
    }

    private Placement placementOfTextFrame(
            ResolvedTextFrame tf,
            int domId,
            TextAction textAction,
            VisualAction visualAction) {
        if (tf == null || !tf.isInline()) return Placement.FLOATING;
        if (textAction != TextAction.OWNED_BY_HWPX_TEXT) return Placement.INLINE;
        if (visualAction != VisualAction.DROP_VISUAL) return Placement.INLINE;
        Placement shellPlacement = visibleTextShellPlacementForTextFrame(tf.id(), domId);
        if (shellPlacement != null) return shellPlacement;
        if (inlineTextFrameNeedsPageTextCarrier(tf, domId)) {
            return Placement.FLOATING;
        }
        if (hasFloatingTextHiddenShellForTextFrame(tf.id(), domId)) {
            return Placement.FLOATING;
        }
        if (hasInlineTextHiddenShellForTextFrame(tf.id())) {
            return Placement.INLINE;
        }
        return Placement.INLINE;
    }

    private Placement visibleTextShellPlacementForTextFrame(String textFrameId, int domId) {
        if (textFrameId == null) return null;
        Placement placement = null;
        for (ObjectPlan plan : plans) {
            if (plan == null) continue;
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (!plan.hasVisibleVisual()) continue;
            if (!containsStringIdOrInt(plan.ownedTextFrameIds, textFrameId, domId)) continue;
            if (placement == null) {
                placement = plan.placement;
                continue;
            }
            if (placement != plan.placement) {
                return null;
            }
        }
        return placement;
    }

    private static boolean containsStringIdOrInt(int[] ids, String textFrameId, int domId) {
        if (ids == null || ids.length == 0) return false;
        int parsed = parseFlexibleId(textFrameId);
        int target = domId >= 0 ? domId : parsed;
        if (target < 0) return false;
        return contains(ids, target);
    }

    /**
     * Source ownership policy: an inline TextFrame can remain inline only when
     * its nearest source anchor is carried by an executable story text body. If
     * the carrier TextFrame is table/marker-only, later phases have no story
     * run in which to materialize the child frame, so the child keeps HWPX text
     * ownership but uses its resolved page bounds as a page text carrier.
     */
    private boolean inlineTextFrameNeedsPageTextCarrier(ResolvedTextFrame tf, int domId) {
        if (tf == null || !tf.isInline() || domId < 0) return false;
        if (!hasSemanticText(tf)) return false;
        AnchorCarrier carrier = nearestAnchorCarrierForTextFrame(tf, domId);
        if (carrier == null || carrier.story == null) {
            return hasInlineSourceChain(tf, domId);
        }
        if (hasVisibleResolvedStoryText(carrier.story)) return false;
        List<ResolvedTextFrame> carrierFrames = textFramesForStory(carrier.story.id());
        if (carrierFrames.isEmpty()) return false;
        for (ResolvedTextFrame owner : carrierFrames) {
            if (owner == null || owner.sourceHidden()) continue;
            IDMLStory idmlStory = loadStory(owner.storyId());
            if (TableFrameOwnershipPolicy.isTableOnlyTextFrame(owner, idmlStory)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasInlineSourceChain(ResolvedTextFrame tf, int domId) {
        if (data == null || tf == null || domId < 0) return false;
        Set<Integer> seen = new HashSet<>();
        int current = domId;
        while (current >= 0 && seen.add(current)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(current));
            if (item == null) break;
            if (item.isInline()) return true;
            if (item.parentId() == null) break;
            current = parseFlexibleId(item.parentId());
        }
        return false;
    }

    private AnchorCarrier nearestAnchorCarrierForTextFrame(ResolvedTextFrame tf, int domId) {
        if (data == null || tf == null) return null;
        Set<Integer> seen = new HashSet<>();
        int current = domId;
        while (current >= 0 && seen.add(current)) {
            AnchorCarrier carrier = carrierForAnchor(current);
            if (carrier != null) {
                return carrier;
            }
            ResolvedPageItem item = data.getPageItem(String.valueOf(current));
            if (item == null || item.parentId() == null) break;
            current = parseFlexibleId(item.parentId());
        }
        return null;
    }

    private ResolvedStory carrierStoryForAnchor(int anchoredObjectId) {
        AnchorCarrier carrier = carrierForAnchor(anchoredObjectId);
        return carrier != null ? carrier.story : null;
    }

    private AnchorCarrier carrierForAnchor(int anchoredObjectId) {
        if (data == null || anchoredObjectId < 0 || data.stories() == null) return null;
        if (ctx != null && ctx.textFlowDocument != null) {
            kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.textflow.TextFlowDocument.InlineSlotCarrier carrier =
                    ctx.textFlowDocument.inlineSlotCarrier(anchoredObjectId);
            if (carrier != null && carrier.unit != null) {
                ResolvedStory story = data.getStory(carrier.unit.storyId);
                ResolvedParagraph paragraph = carrier.paragraph != null ? carrier.paragraph.sourceParagraph : null;
                return new AnchorCarrier(anchoredObjectId, story, paragraph, carrier.paragraph);
            }
        }
        for (ResolvedStory story : data.stories()) {
            if (story == null || story.paragraphs() == null) continue;
            for (ResolvedParagraph paragraph : story.paragraphs()) {
                if (paragraph == null || paragraph.runs() == null) continue;
                for (ResolvedRun run : paragraph.runs()) {
                    if (run == null || !run.isInlineAnchor()) continue;
                    Integer anchoredId = run.anchoredObjectId();
                    if (anchoredId != null && anchoredId == anchoredObjectId) {
                        return new AnchorCarrier(anchoredObjectId, story, paragraph);
                    }
                }
            }
        }
        return null;
    }

    private List<ResolvedTextFrame> textFramesForStory(String storyId) {
        List<ResolvedTextFrame> frames = new ArrayList<>();
        if (data == null || storyId == null) return frames;
        for (ResolvedTextFrame tf : data.textFrames()) {
            if (tf != null && storyId.equals(tf.storyId())) {
                frames.add(tf);
            }
        }
        return frames;
    }

    private static boolean hasVisibleResolvedStoryText(ResolvedStory story) {
        if (story == null || story.paragraphs() == null) return false;
        for (ResolvedParagraph paragraph : story.paragraphs()) {
            if (paragraph == null || paragraph.runs() == null) continue;
            for (ResolvedRun run : paragraph.runs()) {
                if (run == null || run.isInlineAnchor()) continue;
                if (!normalizeResolvedVisibleText(run.text()).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
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
        final ResolvedStory story;
        final ResolvedParagraph paragraph;
        final TextFlowDocument.TextFlowParagraph textFlowParagraph;

        AnchorCarrier(int anchorId, ResolvedStory story, ResolvedParagraph paragraph) {
            this(anchorId, story, paragraph, null);
        }

        AnchorCarrier(
                int anchorId,
                ResolvedStory story,
                ResolvedParagraph paragraph,
                TextFlowDocument.TextFlowParagraph textFlowParagraph) {
            this.anchorId = anchorId;
            this.story = story;
            this.paragraph = paragraph;
            this.textFlowParagraph = textFlowParagraph;
        }
    }

    private boolean hasInlineTextHiddenShellForTextFrame(String textFrameId) {
        if (data == null || textFrameId == null) return false;
        for (RenderedGroup rg : data.allRenderedFloatingItems()) {
            if (rg == null) continue;
            if (!"indesign_png".equals(rg.visualOwner())) continue;
            if (!"hwpx_tf".equals(rg.textOwner())) continue;
            if (!containsString(rg.editableTextFrameIds(), textFrameId)) continue;
            ObjectPlan shellPlan = findRenderedPlan(rg.id(), rg.file());
            if (shellPlan == null || shellPlan.placement != Placement.INLINE) {
                continue;
            }
            if (!isDirectInlineTextShellReason(shellPlan.reason)) {
                continue;
            }
            return shellPlan.visualAction == VisualAction.PLACE_INLINE_PNG
                    || shellPlan.visualAction == VisualAction.PLACE_TEXT_SHELL;
        }
        return false;
    }

    private static boolean isDirectInlineTextShellReason(String reason) {
        if (reason == null) return false;
        return "visual_label_text_hidden_shell".equals(reason)
                || "editable_textframe_visual_shell".equals(reason)
                || "inline_text_hidden".equals(reason)
                || isAtomicOwnershipRootTextHiddenShellReason(reason);
    }

    private boolean renderedGroupClaimsTextFrame(RenderedGroup rg, String textFrameId) {
        if (rg == null || textFrameId == null) return false;
        if (containsString(rg.editableTextFrameIds(), textFrameId)) return true;
        int textFrameDomId = parseFlexibleId(textFrameId);
        if (textFrameDomId < 0 || rg.sourceObjectIds() == null) return false;
        for (int sourceObjectId : rg.sourceObjectIds()) {
            if (sourceObjectId == textFrameDomId) return true;
        }
        return false;
    }

    private boolean hasFloatingTextHiddenShellForTextFrame(String textFrameId, int domId) {
        if (data == null || textFrameId == null || domId < 0) return false;
        for (RenderedGroup rg : data.allRenderedFloatingItems()) {
            if (rg == null) continue;
            if (!"indesign_png".equals(rg.visualOwner())) continue;
            if (!renderedGroupClaimsTextFrame(rg, textFrameId)) continue;
            ObjectPlan shellPlan = findRenderedPlan(rg.id(), rg.file());
            if (shellPlan == null || shellPlan.placement != Placement.FLOATING) {
                continue;
            }
            if (!isDirectInlineTextShellReason(shellPlan.reason)) {
                continue;
            }
            if (shellPlan != null
                    && shellPlan.visualAction != VisualAction.PLACE_FLOATING_PNG
                    && shellPlan.visualAction != VisualAction.PLACE_TEXT_SHELL) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static boolean isRenderedPageObject(RenderedGroup rg) {
        if (rg == null) return false;
        return "page_object".equals(rg.type()) || "page_object".equals(rg.itemType());
    }

    private ObjectPlan findRenderedPlan(int renderId, String file) {
        for (ObjectPlan plan : plans) {
            if (plan == null || plan.renderId == null || plan.renderId != renderId) continue;
            if (file != null && plan.file != null && !file.equals(plan.file)) continue;
            if (plan.hasVisibleVisual()) return plan;
        }
        return null;
    }

    private static boolean containsString(String[] values, String target) {
        if (values == null || target == null) return false;
        for (String value : values) {
            if (target.equals(value)) return true;
        }
        return false;
    }

    private IDMLStory loadStory(String storyId) {
        if (storyId == null || ctx == null || ctx.loadIDMLStory == null) return null;
        try {
            return ctx.loadIDMLStory.apply(storyId);
        } catch (Exception e) {
            return null;
        }
    }

    private int[] tableOnlySourceIds(int textFrameDomId, IDMLStory story) {
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        ids.add(textFrameDomId);
        if (story != null && story.tables() != null) {
            for (IDMLTable table : story.tables()) {
                int tableId = parseFlexibleId(table != null ? table.selfId() : null);
                if (tableId >= 0) ids.add(tableId);
            }
        }
        int[] out = new int[ids.size()];
        int i = 0;
        for (Integer id : ids) out[i++] = id != null ? id : -1;
        return out;
    }

    private int[] tableOnlyStyleSourceIds(int textFrameDomId) {
        // Table style source ids must name concrete source-authored appearance
        // objects. The table carrier/table id alone is structure provenance;
        // direct wrapper and sibling grid sources are collected separately.
        return new int[0];
    }

    private int[] tableStyleSourceIdsForTextFrame(int textFrameDomId) {
        return tableStyleSourceIdsForTextFrame(textFrameDomId, null);
    }

    private int[] tableStyleSourceIdsForTextFrame(int textFrameDomId, IDMLStory story) {
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        addAll(tableOnlyStyleSourceIds(textFrameDomId), ids);
        if (story != null && story.tables() != null) {
            for (IDMLTable table : story.tables()) {
                int tableId = parseFlexibleId(table != null ? table.selfId() : null);
                if (tableId >= 0) ids.add(tableId);
            }
        }
        addParentTableCarrierStyleSourceId(textFrameDomId, ids);
        addSiblingTableCarrierStyleSourceIds(textFrameDomId, ids);
        addRenderedTableCarrierStyleSourceIds(textFrameDomId, ids);
        return toIntArray(ids);
    }

    private void addRenderedTableCarrierStyleSourceIds(int textFrameDomId, LinkedHashSet<Integer> ids) {
        if (data == null || ids == null || textFrameDomId < 0) return;
        for (RenderedGroup rg : data.allRenderedFloatingItems()) {
            if (rg == null) continue;
            if (!renderedGroupClaimsTextFrame(rg, String.valueOf(textFrameDomId))) continue;
            if (!isTableOwnedCompositeRender(rg) && !isSlotOnlyTableCarrierShell(rg)) continue;
            addTableStyleSourceIds(sourceIdsOrSelf(rg), textFrameDomId, ids);
            addTableStyleSourceIds(rg.hiddenVisualSourceObjectIds(), textFrameDomId, ids);
            addTableStyleSourceIds(rg.nativeFillChildIds(), textFrameDomId, ids);
        }
    }

    private void addParentTableCarrierStyleSourceId(int textFrameDomId, LinkedHashSet<Integer> ids) {
        if (data == null || ids == null || textFrameDomId < 0) return;
        ResolvedPageItem ownerItem = data.getPageItem(String.valueOf(textFrameDomId));
        if (ownerItem == null || ownerItem.parentId() == null) return;
        ResolvedPageItem parent = data.getPageItem(ownerItem.parentId());
        if (parent == null) return;
        double[] ownerBounds = boundsOf(ownerItem);
        if (ownerBounds == null || ownerBounds.length < 4) {
            ResolvedTextFrame ownerTf = data.getTextFrame(String.valueOf(textFrameDomId));
            ownerBounds = boundsOf(ownerTf);
        }
        collectTableCarrierStyleSourceItem(parent, textFrameDomId, ownerBounds, ids);
    }

    private void addSiblingTableCarrierStyleSourceIds(int textFrameDomId, LinkedHashSet<Integer> ids) {
        if (data == null || ids == null || textFrameDomId < 0) return;
        ResolvedTextFrame ownerTf = data.getTextFrame(String.valueOf(textFrameDomId));
        ResolvedPageItem ownerItem = data.getPageItem(String.valueOf(textFrameDomId));
        if (ownerItem == null || ownerItem.parentId() == null) return;
        double[] ownerBounds = boundsOf(ownerItem);
        if (ownerBounds == null || ownerBounds.length < 4) ownerBounds = boundsOf(ownerTf);
        if (ownerBounds == null || ownerBounds.length < 4) return;
        String parentId = ownerItem.parentId();
        for (ResolvedPageItem item : data.pageItems()) {
            if (item == null || item.id() == null) continue;
            if (!parentId.equals(item.parentId())) continue;
            collectTableCarrierStyleSourceItem(item, textFrameDomId, ownerBounds, ids);
        }
    }

    private void collectTableCarrierStyleSourceItem(
            ResolvedPageItem item,
            int ownerTextFrameId,
            double[] tableOwnerBounds,
            LinkedHashSet<Integer> ids) {
        if (item == null || ids == null) return;
        int itemId = parseFlexibleId(item.id());
        if (itemId < 0 || itemId == ownerTextFrameId) return;
        if ("Group".equals(item.type())) {
            if (item.childIds() == null) return;
            for (int childId : item.childIds()) {
                ResolvedPageItem child = data != null ? data.getPageItem(String.valueOf(childId)) : null;
                collectTableCarrierStyleSourceItem(child, ownerTextFrameId, tableOwnerBounds, ids);
            }
            return;
        }
        if (isCandidateTableStyleSourceItem(item, ownerTextFrameId, tableOwnerBounds)) {
            ids.add(itemId);
        }
    }

    private void addTableStyleSourceIds(int[] sourceIds, int ownerTextFrameId, LinkedHashSet<Integer> ids) {
        if (sourceIds == null || ids == null) return;
        ResolvedTextFrame ownerTf = data != null ? data.getTextFrame(String.valueOf(ownerTextFrameId)) : null;
        ResolvedPageItem ownerItem = data != null ? data.getPageItem(String.valueOf(ownerTextFrameId)) : null;
        double[] ownerBounds = boundsOf(ownerItem);
        if (ownerBounds == null || ownerBounds.length < 4) ownerBounds = boundsOf(ownerTf);
        for (int sourceId : sourceIds) {
            ResolvedPageItem item = data != null ? data.getPageItem(String.valueOf(sourceId)) : null;
            if (isCandidateTableStyleSourceItem(item, ownerTextFrameId, ownerBounds)) {
                ids.add(sourceId);
            }
        }
    }

    private boolean isCandidateTableStyleSourceItem(
            ResolvedPageItem item,
            int ownerTextFrameId,
            double[] tableOwnerBounds) {
        if (item == null || item.sourceHidden()) return false;
        int itemId = parseFlexibleId(item.id());
        if (itemId < 0 || itemId == ownerTextFrameId) return false;
        if (data != null && data.getTextFrame(String.valueOf(itemId)) != null) return false;
        if (item.isInline()) return false;
        if (subtreeContainsOtherTableOnlyTextFrame(item, ownerTextFrameId)) return false;
        if ("Group".equals(item.type())) return false;
        if (hasSourceChildren(item)) return false;
        double[] b = boundsOf(item);
        if (tableOwnerBounds == null || b == null || b.length < 4) return false;
        if (!boundsContains(tableOwnerBounds, b, 0.75)) {
            return false;
        }
        return hasTableGridStyleMaterial(item);
    }

    private boolean subtreeContainsOtherTableOnlyTextFrame(ResolvedPageItem item, int ownerTextFrameId) {
        if (item == null || item.childIds() == null) return false;
        for (int childId : item.childIds()) {
            if (childId == ownerTextFrameId) continue;
            ResolvedTextFrame tf = data != null ? data.getTextFrame(String.valueOf(childId)) : null;
            if (tf != null && TableFrameOwnershipPolicy.isTableOnlyTextFrame(tf, loadStory(tf.storyId()))) {
                return true;
            }
            ResolvedPageItem child = data != null ? data.getPageItem(String.valueOf(childId)) : null;
            if (child != null && subtreeContainsOtherTableOnlyTextFrame(child, ownerTextFrameId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTableStyleMaterial(ResolvedPageItem item) {
        if (item == null) return false;
        String type = safe(item.type());
        boolean shape = "Rectangle".equals(type)
                || "Polygon".equals(type)
                || "Oval".equals(type)
                || "GraphicLine".equals(type);
        if (!shape) return false;
        boolean visibleFill = !isNoneColor(safe(item.fillColorName()));
        boolean visibleStroke = item.strokeWeight() > 0 && !isNoneColor(safe(item.strokeColorName()));
        return visibleFill || visibleStroke;
    }

    private static boolean hasTableGridStyleMaterial(ResolvedPageItem item) {
        if (item == null) return false;
        String type = safe(item.type());
        if (!"Rectangle".equals(type) && !"GraphicLine".equals(type)) return false;
        if (item.cornerRadius() > 0.01) return false;
        if ("GraphicLine".equals(type)) {
            if (!isOrthogonalRotation(item.absoluteRotationAngle(), 0.1)) return false;
        } else if (!isAxisAlignedRotation(item.absoluteRotationAngle(), 0.1)) {
            return false;
        }
        if (Math.abs(item.absoluteShearAngle()) > 0.1) return false;
        if (item.hasDropShadow()) return false;
        if (item.gradientFeatherApplied()) return false;
        return hasTableStyleMaterial(item);
    }

    private static boolean isAxisAlignedRotation(double angle, double tolerance) {
        double normalized = Math.abs(angle) % 180.0;
        normalized = Math.min(normalized, 180.0 - normalized);
        return normalized <= tolerance;
    }

    private static boolean isOrthogonalRotation(double angle, double tolerance) {
        double normalized = Math.abs(angle) % 90.0;
        normalized = Math.min(normalized, 90.0 - normalized);
        return normalized <= tolerance;
    }

    private boolean hasSourceChildren(ResolvedPageItem item) {
        if (item == null) return false;
        if (item.childIds() != null && item.childIds().length > 0) return true;
        if (data == null || item.id() == null) return false;
        String id = item.id();
        for (ResolvedPageItem other : data.pageItems()) {
            if (other == null || other.parentId() == null) continue;
            if (id.equals(other.parentId())) return true;
        }
        return false;
    }

    private static double[] expandBounds(double[] b, double tolerance) {
        if (b == null || b.length < 4) return null;
        return new double[] {
                b[0] - tolerance,
                b[1] - tolerance,
                b[2] + tolerance,
                b[3] + tolerance
        };
    }

    private TextAction textActionOf(RenderedGroup rg) {
        if (data.shouldUseCompletePngForSimpleButtonLabel(rg)) {
            return TextAction.OWNED_BY_PNG;
        }
        if (data.shouldUseTextlessShellForAtomicMarkerLabel(rg)) {
            return TextAction.OWNED_BY_HWPX_TEXT;
        }
        if (isDirectInlineTextShellReason(rg.reason()) && hasEditableTextOwnerSignal(rg)) {
            return TextAction.OWNED_BY_HWPX_TEXT;
        }
        if (data.isNonCanonicalAtomicObjectRender(rg)) {
            return TextAction.DROP_TEXT;
        }
        if (data.shouldKeepVisualLabelTextEditable(rg)) {
            return TextAction.OWNED_BY_HWPX_TEXT;
        }
        if (shouldRejectRenderedCompletePngTextOwner(rg)) {
            return TextAction.OWNED_BY_HWPX_TEXT;
        }
        if ("indesign_png".equals(rg.textOwner())) {
            return TextAction.OWNED_BY_PNG;
        }
        if ("hidden_semantic".equals(rg.textOwner())) {
            return TextAction.HIDDEN_SEMANTIC;
        }
        if (hasTextlessShellWithInferredEditableTextSource(rg)) {
            return TextAction.OWNED_BY_HWPX_TEXT;
        }
        if (hasSemanticEditableTextOwnerSignal(rg)) {
            return TextAction.OWNED_BY_HWPX_TEXT;
        }
        return TextAction.DROP_TEXT;
    }

    private boolean shouldRejectRenderedCompletePngTextOwner(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (!"indesign_png".equals(rg.textOwner())) return false;
        if (data.shouldUseCompletePngForSimpleButtonLabel(rg)) return false;
        int[] textFrameIds = rawEditableTextFrameIdsOf(rg);
        if (textFrameIds.length == 0) return false;
        for (int textFrameId : textFrameIds) {
            ResolvedTextFrame tf = data.getTextFrame(String.valueOf(textFrameId));
            if (tf == null || tf.sourceHidden()) continue;
            if (!textFrameHasVisibleSemanticText(tf)) continue;
            if (!data.isSimpleButtonLabelTextFrame(tf.id())) {
                return true;
            }
        }
        return false;
    }

    private int[] rawEditableTextFrameIdsOf(RenderedGroup rg) {
        if (rg == null || data == null) return new int[0];
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        if (rg.editableTextFrameIds() != null) {
            for (String id : rg.editableTextFrameIds()) {
                int parsed = parseFlexibleId(id);
                if (parsed >= 0) ids.add(parsed);
            }
        }
        if (rg.sourceObjectIds() != null) {
            for (int sourceId : rg.sourceObjectIds()) {
                if (data.getTextFrame(String.valueOf(sourceId)) != null) {
                    ids.add(sourceId);
                }
            }
        }
        return toIntArray(ids);
    }

    private VisualAction visualActionOf(RenderedGroup rg, Placement placement, TextAction textAction) {
        if (isPureDecorationPageObject(rg)) {
            return VisualAction.PLACE_FLOATING_PNG;
        }
        if (isClipParentSourceSetCandidate(rg)) {
            return VisualAction.DROP_VISUAL;
        }
        if (data.isNonCanonicalAtomicObjectRender(rg)) {
            return VisualAction.DROP_VISUAL;
        }
        if (isStandaloneGraphicOnlyInlineObject(rg)) {
            return placement == Placement.INLINE
                    ? VisualAction.PLACE_INLINE_PNG
                    : VisualAction.PLACE_FLOATING_PNG;
        }
        if (isInlineCompleteRenderOfTextBearingSourceGroup(rg, placement, textAction)) {
            return VisualAction.DROP_VISUAL;
        }
        if (isTableOwnedCompositeRender(rg)) {
            return VisualAction.DROP_VISUAL;
        }
        if (isImageBackedContentShell(rg) && hasPlacedContentSource(rg)) {
            return placement == Placement.INLINE
                    ? VisualAction.PLACE_INLINE_PNG
                    : VisualAction.PLACE_FLOATING_PNG;
        }
        if (data.shouldUseTextlessShellForAtomicMarkerLabel(rg)) {
            return VisualAction.PLACE_TEXT_SHELL;
        }
        if (textAction == TextAction.OWNED_BY_HWPX_TEXT
                && isDirectInlineTextShellReason(rg.reason())
                && isEditableVisualShellWithSeparateHwpxText(rg)) {
            return VisualAction.PLACE_TEXT_SHELL;
        }
        if (isInlineCompleteGraphicWithSeparateTextHiddenShell(rg)) {
            return VisualAction.DROP_VISUAL;
        }
        if ("image_group_text_hidden".equals(rg.reason())
                && !isImageBackedContentShell(rg)
                && (textAction == TextAction.OWNED_BY_HWPX_TEXT || hasEditableTextOwnerSignal(rg))) {
            return VisualAction.PLACE_TEXT_SHELL;
        }
        if (isInlineCompleteGraphicWithHwpxTextSource(rg, placement, textAction)) {
            return VisualAction.DROP_VISUAL;
        }
        if (textAction == TextAction.OWNED_BY_HWPX_TEXT
                && hasTextlessShellWithInferredEditableTextSource(rg)) {
            return VisualAction.PLACE_TEXT_SHELL;
        }
        if (isInlineGraphicWithEditableTextSourcesButNoTextlessShell(rg, placement)) {
            return VisualAction.DROP_VISUAL;
        }
        if (isCompanionShellOfCompleteSimpleLabel(rg)) {
            return VisualAction.DROP_VISUAL;
        }
        if (isCompleteVisualLabelWithEditableText(rg)) {
            return VisualAction.DROP_VISUAL;
        }
        if (Boolean.FALSE.equals(rg.placementAllowed())
                && (textAction != TextAction.OWNED_BY_HWPX_TEXT || !hasSemanticEditableTextOwnerSignal(rg))) {
            return VisualAction.DROP_VISUAL;
        }
        if (isLabelBackdropGroupWithUnclaimedHwpxText(rg)) {
            return VisualAction.DROP_VISUAL;
        }
        if (isLabelBackdropGroupWithForeignSources(rg)) {
            return VisualAction.DROP_VISUAL;
        }
        if (isTextCardBackdropVector(rg)) {
            return textAction == TextAction.OWNED_BY_HWPX_TEXT
                    ? VisualAction.PLACE_TEXT_SHELL
                    : (placement == Placement.INLINE
                    ? VisualAction.PLACE_INLINE_PNG
                    : VisualAction.PLACE_FLOATING_PNG);
        }
        if (isUnabsorbedHwpxTextStyleInlineVisual(rg)) {
            return VisualAction.DROP_VISUAL;
        }
        if (textAction == TextAction.OWNED_BY_HWPX_TEXT
                && renderedVisualContainsTextPixels(rg)) {
            return VisualAction.DROP_VISUAL;
        }
        if (textAction == TextAction.OWNED_BY_HWPX_TEXT
                && hasSemanticEditableTextOwnerSignal(rg)) {
            if (isEditableVisualShellWithSeparateHwpxText(rg)) {
                return VisualAction.PLACE_TEXT_SHELL;
            }
            if (hasIndependentContentVisualBesideOwnedText(rg)) {
                return placement == Placement.INLINE
                        ? VisualAction.PLACE_INLINE_PNG
                        : VisualAction.PLACE_FLOATING_PNG;
            }
            if ("label_backdrop_group".equals(rg.reason())) {
                return VisualAction.PLACE_TEXT_SHELL;
            }
            if (isCalloutOrOutlineTextShell(rg)) {
                return VisualAction.PLACE_TEXT_SHELL;
            }
            if (canAbsorbEditableLabelShellAsTextStyle(rg)) {
                return VisualAction.ABSORB_TEXT_STYLE;
            }
            return VisualAction.PLACE_TEXT_SHELL;
        }
        if (rg.shouldSkipByOwnership() && textAction != TextAction.OWNED_BY_PNG) {
            return VisualAction.DROP_VISUAL;
        }
        if (placement == Placement.INLINE) {
            return VisualAction.PLACE_INLINE_PNG;
        }
        if (placement == Placement.FLOATING) {
            return VisualAction.PLACE_FLOATING_PNG;
        }
        return VisualAction.DROP_VISUAL;
    }

    private static boolean isClipParentSourceSetCandidate(RenderedGroup rg) {
        return rg != null && "clip_parent_source_set".equals(safe(rg.compositeRole()));
    }

    private boolean isPureDecorationPageObject(RenderedGroup rg) {
        if (rg == null) return false;
        if (!isRenderedPageObject(rg)) return false;
        String reason = safe(rg.reason());
        if (!"pure_decoration_group".equals(reason)
                && !"decoration_group".equals(reason)) {
            return false;
        }
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (!"none".equals(rg.textOwner())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) {
            return false;
        }
        return rg.file() != null && !rg.file().isEmpty();
    }

    private boolean isInlineCompleteRenderOfTextBearingSourceGroup(
            RenderedGroup rg,
            Placement placement,
            TextAction textAction) {
        if (rg == null || data == null) return false;
        if (placement != Placement.INLINE) return false;
        if (!data.isInlineObjectId(rg.id())) return false;
        if (!"inline_graphic_only".equals(rg.reason())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (!"none".equals(rg.textOwner())) return false;
        if (hasTextlessShellWithInferredEditableTextSource(rg)) return false;
        if (textAction == TextAction.OWNED_BY_PNG) return false;
        return hasDescendantTextFrameExcluding(String.valueOf(rg.id()), new HashSet<>(), new HashSet<>());
    }

    private boolean isTableOwnedCompositeRender(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (!isRenderedPageObject(rg)) return false;
        if (isAtomicTextlessShellWithEditableText(rg)) return false;
        if (isSourceDeclaredTextlessEditableShell(rg)) return false;
        String reason = safe(rg.reason());
        boolean compositeReason = reason.contains("text_hidden")
                || reason.contains("text_composite")
                || reason.contains("mixed_group")
                || reason.contains("complex_graphic")
                || "slot_only_textless_shell".equals(reason);
        return compositeReason && hasTableOnlyCarrierSource(sourceIdsOrSelf(rg));
    }

    private boolean isSlotOnlyTableCarrierShell(RenderedGroup rg) {
        return rg != null
                && "slot_only_textless_shell".equals(safe(rg.reason()))
                && hasTableOnlyCarrierSource(sourceIdsOrSelf(rg));
    }

    private boolean isAtomicTextlessShellWithEditableText(RenderedGroup rg) {
        if (rg == null) return false;
        if (!isAtomicOwnershipRootTextHiddenShellReason(rg.reason())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (!"hwpx_tf".equals(rg.textOwner())) return false;
        if (rg.file() == null || rg.file().isEmpty()) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) {
            return false;
        }
        return (rg.hasEditableTextHiddenFromPng() || hasEditableTextFrameIds(rg))
                && hasSemanticEditableTextOwnerSignal(rg);
    }

    private boolean hasTableOnlyCarrierSource(int[] sourceIds) {
        if (sourceIds == null || data == null) return false;
        for (int sourceId : sourceIds) {
            ResolvedTextFrame tf = data.getTextFrame(String.valueOf(sourceId));
            if (tf == null) continue;
            IDMLStory story = loadStory(tf.storyId());
            if (TableFrameOwnershipPolicy.isTableOnlyTextFrame(tf, story)) {
                return true;
            }
        }
        return false;
    }

    private static boolean renderedVisualContainsTextPixels(RenderedGroup rg) {
        return rg != null
                && (Boolean.TRUE.equals(rg.containsText())
                || Boolean.TRUE.equals(rg.containsEditableText()));
    }

    private VisualLayer visualLayerOf(
            RenderedGroup rg,
            int[] sourceIds,
            VisualAction visualAction,
            TextAction textAction,
            int[] ownedTextFrameIds) {
        if (visualAction == VisualAction.DROP_VISUAL
                || visualAction == VisualAction.ABSORB_TEXT_STYLE
                || visualAction == VisualAction.PLACE_TABLE_STYLE) {
            return VisualLayer.CONTENT_VISUAL;
        }
        if (rg.isPageBackground()) {
            return VisualLayer.CONTENT_VISUAL;
        }
        if (isSourceBackgroundLayer(rg, sourceIds)
                && !(visualAction == VisualAction.PLACE_TEXT_SHELL
                && textAction == TextAction.OWNED_BY_HWPX_TEXT)) {
            return VisualLayer.CONTENT_VISUAL;
        }
        if (isSourceAuthoredPageWashBackdropImage(rg, sourceIds)
                && !(visualAction == VisualAction.PLACE_TEXT_SHELL
                && textAction == TextAction.OWNED_BY_HWPX_TEXT)) {
            return VisualLayer.CONTENT_VISUAL;
        }
        if (isSourceDepthPageOrSpreadBackdropImage(rg, sourceIds)
                && !(visualAction == VisualAction.PLACE_TEXT_SHELL
                && textAction == TextAction.OWNED_BY_HWPX_TEXT)) {
            return VisualLayer.CONTAINER_BACKDROP;
        }
        if (visualAction == VisualAction.PLACE_INLINE_PNG) {
            return VisualLayer.CONTENT_VISUAL;
        }
        if (visualAction != VisualAction.PLACE_TEXT_SHELL
                && isEditableTextCarrierBackdrop(rg, textAction)) {
            return VisualLayer.CONTAINER_BACKDROP;
        }
        if (visualAction != VisualAction.PLACE_TEXT_SHELL
                && hasVisibleShellRootWithPlacedContentTree(rg)) {
            return VisualLayer.CONTAINER_BACKDROP;
        }
        if (visualAction != VisualAction.PLACE_TEXT_SHELL && hasPlacedContentSourceTree(rg)) {
            return VisualLayer.CONTENT_VISUAL;
        }
        if (visualAction != VisualAction.PLACE_TEXT_SHELL
                && hasIndependentContentVisualBesideOwnedText(rg)) {
            return VisualLayer.CONTENT_VISUAL;
        }
        if (visualAction == VisualAction.PLACE_TEXT_SHELL) {
            if (isHwpxTextShellBackdropContract(rg, ownedTextFrameIds)) {
                return VisualLayer.CONTAINER_BACKDROP;
            }
            if (isGroupChromeLabelBackdrop(rg)) {
                return VisualLayer.LABEL_OVERLAY_BACKDROP;
            }
            if ("editable_textframe_visual_shell".equals(safe(rg.reason()))) {
                return VisualLayer.LABEL_BACKDROP;
            }
            if (isSourceDeclaredTextlessEditableShell(rg)) {
                return VisualLayer.LABEL_BACKDROP;
            }
            if (isTextCardBackdropVector(rg)) {
                return VisualLayer.TEXT_CARD_BACKDROP;
            }
            if (isLabelChromeTextCarrierShell(rg)) {
                return VisualLayer.LABEL_BACKDROP;
            }
            if (isMultiTextCarrierShell(rg)) {
                return VisualLayer.CONTAINER_BACKDROP;
            }
            if (isEditableLabelCardShell(rg)) {
                return VisualLayer.LABEL_BACKDROP;
            }
            if (isCalloutOverlayTextShell(rg)) {
                return VisualLayer.LABEL_OVERLAY_BACKDROP;
            }
            if (isDirectChildTextOwningShellSlot(rg)) {
                return VisualLayer.LABEL_BACKDROP;
            }
            if (hasColoredShapeShellSource(rg)) {
                return VisualLayer.LABEL_BACKDROP;
            }
            if (isCalloutOrOutlineTextShell(rg)) {
                return VisualLayer.CONTAINER_OUTLINE;
            }
            if ("visual_label_text_hidden_shell".equals(rg.reason())
                    || "editable_composite_text_hidden_shell".equals(rg.reason())) {
                return VisualLayer.LABEL_BACKDROP;
            }
            if (isEditableVisualShellWithSeparateHwpxText(rg)) {
                if (isImageBackedContentShell(rg) && !isBackdropDominantImageShell(rg)) {
                    return VisualLayer.CONTENT_VISUAL;
                }
                return isLabelReason(rg) ? VisualLayer.LABEL_BACKDROP : VisualLayer.CONTAINER_BACKDROP;
            }
            if (isImageBackedContentShell(rg)) {
                if (isBackdropDominantImageShell(rg)) {
                    return VisualLayer.CONTAINER_BACKDROP;
                }
                return VisualLayer.CONTENT_VISUAL;
            }
            if ("label_backdrop_group".equals(rg.reason())) {
                return VisualLayer.LABEL_BACKDROP;
            }
            return isLabelReason(rg) ? VisualLayer.LABEL_BACKDROP : VisualLayer.CONTAINER_BACKDROP;
        }
        if ("visual_backdrop_cluster".equals(rg.reason())) {
            return VisualLayer.CONTAINER_BACKDROP;
        }
        if (isPaperStrokeContainerVisual(rg) || isPaperMaskInsideContainerBackdrop(rg)) {
            return VisualLayer.CONTAINER_BACKDROP;
        }
        if (isTextCardBackdropVector(rg)) {
            return VisualLayer.TEXT_CARD_BACKDROP;
        }
        if (isGraphicOnlyCompanionOfTextShellCluster(rg)) {
            return isBroadContainerCompanionOfTextShellCluster(rg)
                    ? VisualLayer.CONTAINER_BACKDROP
                    : VisualLayer.LABEL_OVERLAY_BACKDROP;
        }
        if (isOverlayMarkerDecoration(rg)) {
            return VisualLayer.LABEL_OVERLAY_BACKDROP;
        }
        if (isFlatImageExportBackdrop(rg)) {
            return VisualLayer.CONTAINER_BACKDROP;
        }
        if (isPlacedContentImage(rg)) {
            return VisualLayer.CONTENT_VISUAL;
        }
        if (isTextFrameBackdropVector(rg)) {
            return VisualLayer.CONTAINER_BACKDROP;
        }
        if (isOpaquePaperBackdrop(rg) || isPaperFillBackdropPatch(rg)) {
            return VisualLayer.CONTAINER_BACKDROP;
        }
        if (isPaperStrokeContainerVisual(rg)) {
            return VisualLayer.CONTAINER_OUTLINE;
        }
        if (isPaperStrokeBoxBackdrop(rg)) {
            return VisualLayer.CONTAINER_BACKDROP;
        }
        if (isFilledContainerBoxBackdrop(rg)) {
            return VisualLayer.CONTAINER_BACKDROP;
        }
        if (isRuleLineGroup(rg)) {
            return VisualLayer.CONTAINER_OUTLINE;
        }
        if (isLineLikeVisual(rg)) {
            return VisualLayer.CONTAINER_OUTLINE;
        }
        if (isMaskLikeVisual(rg)) {
            return VisualLayer.FOREGROUND_MASK;
        }
        if ("label_backdrop_group".equals(rg.reason())) {
            return VisualLayer.LABEL_BACKDROP;
        }
        if (isLabelBackdropLike(rg, textAction)) {
            return VisualLayer.LABEL_BACKDROP;
        }
        if (isContainerBackdropLike(rg)) {
            return VisualLayer.CONTAINER_BACKDROP;
        }
        return VisualLayer.CONTENT_VISUAL;
    }

    private boolean isHwpxTextShellBackdropContract(RenderedGroup rg, int[] ownedTextFrameIds) {
        if (rg == null) return false;
        if (ownedTextFrameIds == null || ownedTextFrameIds.length == 0) return false;
        if (isImageBackedContentShell(rg) && !isBackdropDominantImageShell(rg)) return false;
        return isDirectChildShellSlotRender(rg)
                || hasTextlessShellWithInferredEditableTextSource(rg)
                || isEditableVisualShellWithSeparateHwpxText(rg)
                || isSourceDeclaredTextlessEditableShell(rg)
                || isTextFrameBackdropVector(rg);
    }

    private boolean isSourceDeclaredTextlessEditableShell(RenderedGroup rg) {
        if (rg == null) return false;
        if (!"slot_only_textless_shell".equals(safe(rg.reason()))) return false;
        if (!hasEditableTextOwnerSignal(rg)) return false;
        return isExtractedTextlessVisual(rg);
    }

    private boolean isSourceBackgroundLayer(RenderedGroup rg, int[] sourceIds) {
        String layerName = sourceLayerName(rg, sourceIds);
        if (!isBackgroundLayerName(layerName)) return false;
        if (rg != null && rg.type() != null && "inline_object".equals(rg.type())) return false;
        return isSourceDeclaredPageBackgroundVisual(rg, sourceIds);
    }

    private boolean hasBackgroundRoleSourceLayer(RenderedGroup rg, int[] sourceIds) {
        String layerName = sourceLayerName(rg, sourceIds);
        return isBackgroundLayerName(layerName);
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

    private boolean isSourceDeclaredPageBackgroundVisual(RenderedGroup rg, int[] sourceIds) {
        if (rg == null || data == null) return false;
        if (editableTextFrameIdsOf(rg).length > 0) return false;
        if (renderedVisualContainsTextPixels(rg)) return false;
        if (!hasPageLevelSourceRoots(sourceIds)) return false;
        if (!isBackgroundBoundsSanityCandidate(rg.bounds())) return false;
        if (!isSingleSourcePageWideBackground(sourceIds, rg.pageIndex())) return false;
        int sourceZ = maxPageItemZOrder(sourceIds);
        if (sourceZ < 0) sourceZ = rg.zOrder();
        return isBehindLocalHwpxTextBySourceDepth(rg, rg.pageIndex(), sourceZ);
    }

    private boolean isEditableTextCarrierBackdrop(RenderedGroup rg, TextAction textAction) {
        if (rg == null) return false;
        if (textAction != TextAction.OWNED_BY_HWPX_TEXT
                && !hasSemanticEditableTextOwnerSignal(rg)) {
            return false;
        }
        return isMultiTextCarrierShell(rg);
    }

    private boolean isMultiTextCarrierShell(RenderedGroup rg) {
        if (rg == null) return false;
        String reason = safe(rg.reason());
        boolean carrierReason = reason.contains("complex_graphic_text_hidden")
                || reason.contains("mixed_group_text_hidden")
                || reason.contains("image_group_text_hidden");
        if (!carrierReason) return false;
        String[] editableIds = rg.editableTextFrameIds();
        return editableIds != null && editableIds.length > 1;
    }

    private boolean isLabelChromeTextCarrierShell(RenderedGroup rg) {
        if (!isMultiTextCarrierShell(rg) || data == null) return false;
        double[] carrier = rg.bounds();
        if (carrier == null || carrier.length < 4) return false;
        double[] textUnion = ownedTextFrameBoundsUnion(rg);
        double[] carrierForChrome = textUnion != null ? textUnion : carrier;
        double carrierArea = area(carrierForChrome);
        double carrierW = Math.abs(carrierForChrome[3] - carrierForChrome[1]);
        double carrierH = Math.abs(carrierForChrome[2] - carrierForChrome[0]);
        if (carrierArea <= 0.0 || carrierW <= 0.0 || carrierH <= 0.0) return false;

        double[] visualUnion = visualOnlyChromeBoundsUnion(rg, textUnion);
        if (visualUnion == null) return false;
        double visualArea = area(visualUnion);
        if (visualArea <= 0.0) return false;
        double visualW = Math.abs(visualUnion[3] - visualUnion[1]);
        double visualH = Math.abs(visualUnion[2] - visualUnion[0]);

        boolean smallLocalChrome = visualArea <= carrierArea * 0.25
                && visualH <= Math.max(18.0, carrierH * 0.45)
                && visualW <= Math.max(36.0, carrierW * 0.80);
        if (!smallLocalChrome) return false;

        if (textUnion == null) return true;
        double topBand = Math.max(16.0, Math.abs(textUnion[2] - textUnion[0]) * 0.35);
        return visualUnion[0] <= textUnion[0] + topBand
                || visualUnion[2] <= textUnion[0] + topBand;
    }

    private boolean isGroupChromeLabelBackdrop(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (!"label_backdrop_group".equals(rg.reason())) return false;
        LinkedHashSet<Integer> ownedTextFrameIds = editableTextFrameIds(rg);
        if (ownedTextFrameIds.isEmpty()) return false;

        double[] labelBounds = null;
        for (int sourceId : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item == null || "TextFrame".equals(safe(item.type()))) continue;
            labelBounds = unionBounds(labelBounds, boundsOf(item));
        }
        if (labelBounds == null || area(labelBounds) <= 0.0) return false;

        LinkedHashSet<String> commonGroupIds = new LinkedHashSet<>();
        for (int sourceId : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item == null || item.parentId() == null || item.parentId().isBlank()) continue;
            String groupId = item.parentId();
            if (isAncestorOfAnyTextFrame(groupId, ownedTextFrameIds)) {
                commonGroupIds.add(groupId);
            }
        }

        for (String groupId : commonGroupIds) {
            if (hasSiblingContentCarrierInGroup(groupId, ownedTextFrameIds, labelBounds)) {
                return true;
            }
        }
        return false;
    }

    private LinkedHashSet<Integer> editableTextFrameIds(RenderedGroup rg) {
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        if (rg == null || rg.editableTextFrameIds() == null) return out;
        for (String id : rg.editableTextFrameIds()) {
            int parsed = parseFlexibleId(id);
            if (parsed >= 0) out.add(parsed);
        }
        return out;
    }

    private boolean isAncestorOfAnyTextFrame(String ancestorId, Set<Integer> textFrameIds) {
        if (ancestorId == null || ancestorId.isBlank() || textFrameIds == null) return false;
        for (int textFrameId : textFrameIds) {
            if (isAncestorOf(ancestorId, String.valueOf(textFrameId))) {
                return true;
            }
        }
        return false;
    }

    private boolean isAncestorOf(String ancestorId, String childId) {
        if (ancestorId == null || ancestorId.isBlank()
                || childId == null || childId.isBlank()
                || data == null) {
            return false;
        }
        String current = childId;
        HashSet<String> visited = new HashSet<>();
        while (current != null && !current.isBlank() && visited.add(current)) {
            ResolvedPageItem item = data.getPageItem(current);
            if (item == null) return false;
            String parentId = item.parentId();
            if (ancestorId.equals(parentId)) return true;
            current = parentId;
        }
        return false;
    }

    private boolean hasSiblingContentCarrierInGroup(
            String groupId,
            Set<Integer> ownedTextFrameIds,
            double[] labelBounds) {
        ResolvedPageItem group = data.getPageItem(groupId);
        if (group == null || labelBounds == null || area(labelBounds) <= 0.0) return false;
        int[] childIds = group.childIds();
        if (childIds == null || childIds.length == 0) return false;
        for (int childId : childIds) {
            if (ownedTextFrameIds != null && ownedTextFrameIds.contains(childId)) continue;
            ResolvedPageItem child = data.getPageItem(String.valueOf(childId));
            if (!isContentCarrierSibling(child, labelBounds)) continue;
            if (hasDescendantTextFrameExcluding(child.id(), ownedTextFrameIds, new HashSet<>())) {
                return true;
            }
        }
        return false;
    }

    private boolean isContentCarrierSibling(ResolvedPageItem item, double[] labelBounds) {
        if (item == null || labelBounds == null) return false;
        String type = safe(item.type());
        if (!"Rectangle".equals(type) && !"Polygon".equals(type) && !"Oval".equals(type)) {
            return false;
        }
        if (!hasVisibleChromePaint(item)) return false;
        double[] b = boundsOf(item);
        double labelArea = area(labelBounds);
        double itemArea = area(b);
        if (labelArea <= 0.0 || itemArea <= labelArea * 1.5) return false;
        return b != null && b.length >= 4 && b[2] > labelBounds[2];
    }

    private boolean hasDescendantTextFrameExcluding(
            String sourceId,
            Set<Integer> excludedTextFrameIds,
            Set<String> visited) {
        if (sourceId == null || sourceId.isBlank() || !visited.add(sourceId)) return false;
        ResolvedPageItem item = data.getPageItem(sourceId);
        if (item == null) return false;
        int parsed = parseFlexibleId(sourceId);
        if ("TextFrame".equals(safe(item.type()))
                && (excludedTextFrameIds == null || !excludedTextFrameIds.contains(parsed))) {
            return true;
        }
        int[] childIds = item.childIds();
        if (childIds != null) {
            for (int childId : childIds) {
                if (hasDescendantTextFrameExcluding(
                        String.valueOf(childId), excludedTextFrameIds, visited)) {
                    return true;
                }
            }
        }
        for (ResolvedPageItem candidate : data.pageItems()) {
            if (candidate == null || candidate.parentId() == null) continue;
            if (!candidate.parentId().equals(sourceId)) continue;
            if (hasDescendantTextFrameExcluding(candidate.id(), excludedTextFrameIds, visited)) {
                return true;
            }
        }
        return false;
    }

    private double[] visualOnlyChromeBoundsUnion(RenderedGroup rg, double[] ownedTextBounds) {
        if (rg == null || data == null) return null;
        int[] ids = rg.visualOnlyChildIds();
        if (ids == null || ids.length == 0) ids = sourceIdsOrSelf(rg);
        double[] union = null;
        Set<String> visited = new HashSet<>();
        for (int id : ids) {
            union = collectVisualChromeBounds(union, String.valueOf(id), visited, ownedTextBounds);
        }
        return union;
    }

    private double[] collectVisualChromeBounds(double[] union, String id, Set<String> visited, double[] ownedTextBounds) {
        if (id == null || data == null || visited == null || !visited.add(id)) return union;
        ResolvedPageItem item = data.getPageItem(id);
        if (item == null) return union;
        String type = safe(item.type());
        if ("TextFrame".equals(type)) return union;
        if ("Group".equals(type) || "page_object".equals(type)) {
            int[] childIds = item.childIds();
            if (childIds != null) {
                for (int childId : childIds) {
                    union = collectVisualChromeBounds(union, String.valueOf(childId), visited, ownedTextBounds);
                }
            }
            return union;
        }
        if (!hasVisibleChromePaint(item)) return union;
        double[] b = boundsOf(item);
        if (b == null || b.length < 4 || area(b) <= 0.0) return union;
        if (isBroadOwnedTextCarrierPaint(b, ownedTextBounds)) return union;
        return unionBounds(union, b);
    }

    private static boolean isBroadOwnedTextCarrierPaint(double[] paintBounds, double[] ownedTextBounds) {
        if (paintBounds == null || ownedTextBounds == null
                || paintBounds.length < 4 || ownedTextBounds.length < 4) {
            return false;
        }
        double paintArea = area(paintBounds);
        double textArea = area(ownedTextBounds);
        if (paintArea <= 0.0 || textArea <= 0.0) return false;
        if (paintArea < textArea * 0.35) return false;
        return boundsContains(paintBounds, ownedTextBounds, 3.0)
                || boundsContains(ownedTextBounds, paintBounds, 3.0)
                || overlapRatio(paintBounds, ownedTextBounds) >= 0.85;
    }

    private static boolean hasVisibleChromePaint(ResolvedPageItem item) {
        if (item == null) return false;
        String type = safe(item.type());
        if ("Image".equals(type) || "PDF".equals(type) || "EPS".equals(type)) return true;
        String fill = safe(item.fillColorName());
        if (!fill.isEmpty()
                && !"None".equals(fill)
                && !"[None]".equals(fill)
                && !"Paper".equals(fill)
                && !"White".equals(fill)
                && isVisibleTint(item.fillTint())) {
            return true;
        }
        String stroke = safe(item.strokeColorName());
        return !stroke.isEmpty()
                && !"None".equals(stroke)
                && !"[None]".equals(stroke)
                && item.strokeWeight() > 0.0
                && isVisibleTint(item.strokeTint());
    }

    private static boolean isVisibleTint(double tint) {
        return tint < 0.0 || tint > 0.0;
    }

    private double[] ownedTextFrameBoundsUnion(RenderedGroup rg) {
        if (rg == null || data == null) return null;
        String[] ids = rg.editableTextFrameIds();
        if (ids == null || ids.length == 0) return null;
        double[] union = null;
        for (String id : ids) {
            ResolvedTextFrame tf = data.getTextFrame(id);
            if (tf == null) continue;
            double[] b = tf.geometricBounds();
            if (b == null || b.length < 4 || area(b) <= 0.0) continue;
            union = unionBounds(union, b);
        }
        return union;
    }

    private static double[] unionBounds(double[] a, double[] b) {
        if (b == null || b.length < 4) return a;
        if (a == null || a.length < 4) {
            return new double[] { b[0], b[1], b[2], b[3] };
        }
        return new double[] {
                Math.min(a[0], b[0]),
                Math.min(a[1], b[1]),
                Math.max(a[2], b[2]),
                Math.max(a[3], b[3])
        };
    }

    private boolean isGraphicOnlyCompanionOfTextShellCluster(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (hasEditableTextFrameIds(rg)) return false;
        int[] sourceIds = sourceIdsOrSelf(rg);
        if (sourceIds == null || sourceIds.length == 0) return false;
        if (isPlacedContentImage(rg)) return false;
        String reason = safe(rg.reason());
        if (!reason.contains("graphic_ownership_root")
                && !reason.contains("decoration_group")
                && !reason.contains("pure_decoration_group")) {
            return false;
        }
        double[] bounds = rg.bounds();
        if (bounds == null || bounds.length < 4) return false;
        for (RenderedGroup owner : allRenderedGroups()) {
            if (owner == null || owner == rg || owner.pageIndex() != rg.pageIndex()) continue;
            if (!hasEditableTextFrameIds(owner)) continue;
            String ownerReason = safe(owner.reason());
            if (!ownerReason.contains("text_hidden")
                    && !ownerReason.contains("atomic_ownership_root")) {
                continue;
            }
            int[] ownerSources = sourceIdsOrSelf(owner);
            if (!containsAll(ownerSources, sourceIds) && !sharesSourceAncestor(ownerSources, sourceIds)) {
                continue;
            }
            double[] ownerBounds = owner.bounds();
            if (ownerBounds == null || ownerBounds.length < 4) continue;
            if (boundsContains(ownerBounds, bounds, 4.0)
                    || boundsMostlyOverlap(ownerBounds, bounds, 0.20)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBroadContainerCompanionOfTextShellCluster(RenderedGroup rg) {
        if (rg == null) return false;
        if (isLargeVisual(rg)) return true;
        String reason = safe(rg.reason());
        return reason.contains("container")
                || reason.contains("textframe_visual_shell")
                || reason.contains("visual_shell");
    }

    private boolean sharesSourceAncestor(int[] ownerSources, int[] childSources) {
        if (data == null || ownerSources == null || childSources == null) return false;
        for (int childSource : childSources) {
            ResolvedPageItem child = data.getPageItem(String.valueOf(childSource));
            if (child == null) continue;
            String parentId = child.parentId();
            int guard = 0;
            while (parentId != null && !parentId.isEmpty() && guard++ < 16) {
                int parsed = parseFlexibleId(parentId);
                if (parsed >= 0 && contains(ownerSources, parsed)) return true;
                ResolvedPageItem parent = data.getPageItem(parentId);
                parentId = parent != null ? parent.parentId() : null;
            }
        }
        return false;
    }

    private boolean isOverlayMarkerDecoration(RenderedGroup rg) {
        if (rg == null || data == null || isLargeVisual(rg)) return false;
        if (isLineLikeVisual(rg) || isMaskLikeVisual(rg)) return false;
        String reason = safe(rg.reason());
        if (!"decoration_group".equals(reason)
                && !"pure_decoration_group".equals(reason)
                && !"vector_shape".equals(reason)) {
            return false;
        }
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double h = Math.abs(b[2] - b[0]);
        double w = Math.abs(b[3] - b[1]);
        double min = Math.min(w, h);
        double max = Math.max(w, h);
        if (min < 3.0 || max > 18.0 || min / Math.max(1.0, max) < 0.55) return false;

        boolean hasColoredOval = false;
        boolean hasPaperMarker = false;
        for (int id : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (item == null) continue;
            String type = safe(item.type());
            String fill = safe(item.fillColorName());
            if ("Oval".equals(type)
                    && !fill.isEmpty()
                    && !isNoneColor(fill)
                    && !isPaperColor(fill)) {
                hasColoredOval = true;
            }
            if (("Polygon".equals(type) || "GraphicLine".equals(type) || "Oval".equals(type))
                    && isPaperColor(fill)) {
                hasPaperMarker = true;
            }
        }
        return hasColoredOval && hasPaperMarker;
    }

    private boolean isEditableLabelCardShell(RenderedGroup rg) {
        if (rg == null) return false;
        if (!"mixed_group_text_hidden".equals(rg.reason())) return false;
        if (!hasSemanticEditableTextOwnerSignal(rg)) return false;
        if (editableTextFrameIdsOf(rg).length < 2) return false;
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double h = Math.abs(b[2] - b[0]);
        double w = Math.abs(b[3] - b[1]);
        // 여러 editable TF가 한 장식 카드 묶음에 들어간 경우에는 shell의
        // 외곽선/박스도 텍스트보다 뒤에 있어야 한다. CONTAINER_OUTLINE으로
        // 올리면 HWPX의 in-front 평면에서 owned text를 덮는다.
        // w 상한 180: 작품+구조도/개관처럼 알약 옆에 넓은 빈 영역이 붙어 bounds가 넓은
        // 테이블 셀 배지(w≈173)도 backdrop(텍스트 뒤)으로 잡는다. 알약은 불투명이라
        // foreground로 두면 셀 텍스트를 가린다.
        return h <= 80.0 && w <= 180.0;
    }

    private boolean hasIndependentContentVisualBesideOwnedText(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        String reason = safe(rg.reason());
        if (!reason.contains("mixed_group_text_hidden")
                && !reason.contains("image_group_text_hidden")
                && !reason.contains("complex_graphic_text_hidden")) {
            return false;
        }
        if (!hasSemanticEditableTextOwnerSignal(rg)) {
            return false;
        }
        List<double[]> textBounds = new ArrayList<>();
        List<double[]> visualBounds = new ArrayList<>();
        int drawableVisuals = 0;
        for (int id : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (item == null) continue;
            String type = safe(item.type());
            double[] b = boundsOf(item);
            if (b == null || b.length < 4 || area(b) <= 0.0) continue;
            if ("TextFrame".equals(type)) {
                textBounds.add(b);
                continue;
            }
            if ("Group".equals(type)) continue;
            if (!isSubstantialDrawableContentVisual(item, b)) continue;
            visualBounds.add(b);
            drawableVisuals++;
        }
        if (textBounds.isEmpty() || visualBounds.isEmpty() || drawableVisuals == 0) return false;

        for (double[] vb : visualBounds) {
            boolean overlapsOwnedText = false;
            for (double[] tb : textBounds) {
                if (overlapRatio(vb, tb) >= 0.35 || containsCenter(vb, tb) || containsCenter(tb, vb)) {
                    overlapsOwnedText = true;
                    break;
                }
            }
            if (!overlapsOwnedText) return true;
        }
        return false;
    }

    private int[] independentContentVisualSourceIds(RenderedGroup rg, int[] fallback) {
        if (rg == null || data == null) return fallback;
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        for (int id : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (item == null) continue;
            double[] b = boundsOf(item);
            if (isSubstantialDrawableContentVisual(item, b)) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) return fallback;
        int[] out = new int[ids.size()];
        int i = 0;
        for (Integer id : ids) out[i++] = id != null ? id : -1;
        return out;
    }

    private static boolean isSubstantialDrawableContentVisual(ResolvedPageItem item, double[] b) {
        if (item == null || b == null || b.length < 4) return false;
        String type = safe(item.type());
        if (!("Rectangle".equals(type) || "Polygon".equals(type) || "Oval".equals(type)
                || "GraphicLine".equals(type) || "Image".equals(type)
                || "PDF".equals(type) || "EPS".equals(type))) {
            return false;
        }
        double w = Math.abs(b[3] - b[1]);
        double h = Math.abs(b[2] - b[0]);
        if (w < 3.0 || h < 3.0 || area(b) < 18.0) return false;
        if ("GraphicLine".equals(type) && Math.min(w, h) < 1.5) return false;
        if ("Image".equals(type) || "PDF".equals(type) || "EPS".equals(type)) return true;
        boolean hasFill = !isNoneColor(item.fillColorName()) && !isPaperColor(item.fillColorName());
        boolean hasStroke = !isNoneColor(item.strokeColorName()) && item.strokeWeight() > 0.01;
        return hasFill || hasStroke;
    }

    private boolean isCompanionShellOfCompleteSimpleLabel(RenderedGroup rg) {
        if (rg == null) return false;
        if (!"visual_label_text_hidden_shell".equals(rg.reason())) return false;
        if (data.shouldUseTextlessShellForAtomicMarkerLabel(rg)) return false;
        return data.shouldUseCompletePngForSimpleButtonLabel(rg);
    }

    private boolean isCompleteVisualLabelWithEditableText(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        return data.shouldKeepVisualLabelTextEditable(rg);
    }

    private boolean canAbsorbEditableLabelShellAsTextStyle(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        // visual_label_text_hidden_shell is an extractor-owned visual-only shell:
        // text pixels are already hidden and the editable TextFrame is expected
        // to be placed above it.  Absorbing it into drawText style drops the
        // shell, and small badge labels lose both their backdrop and alignment.
        if ("visual_label_text_hidden_shell".equals(rg.reason())
                || "editable_composite_text_hidden_shell".equals(rg.reason())) return false;
        if (isCalloutOrOutlineTextShell(rg)) return false;
        if (isLargeVisual(rg) || isLineLikeVisual(rg) || isMaskLikeVisual(rg)) return false;
        if (isImageBackedContentShell(rg) || isPlacedContentImage(rg)) return false;
        if (isPaperStrokeContainerVisual(rg) || isPaperStrokeForegroundMask(rg)) return false;
        if (!looksLikeAbsorbableEditableLabelShell(rg)) return false;
        if (!hasOnlyAbsorbableEditableLabelShellSources(rg)) return false;

        List<ResolvedTextFrame> ownedTextFrames = ownedTextFramesOf(rg);
        if (ownedTextFrames.isEmpty()) return false;
        if (safe(rg.reason()).contains("text_composite_editable_text_hidden")) {
            return false;
        }
        for (ResolvedTextFrame tf : ownedTextFrames) {
            if (canTextFrameAbsorbVisualStyle(tf)) {
                return true;
            }
        }
        return false;
    }

    private boolean isEditableVisualShellWithSeparateHwpxText(RenderedGroup rg) {
        if (rg == null) return false;
        if (!hasEditableTextOwnerSignal(rg)) return false;
        if (hasTableOnlyTextFrameSource(rg)) return false;
        return isExtractedTextlessVisual(rg);
    }

    private boolean isDirectChildTextOwningShellSlot(RenderedGroup rg) {
        if (!isDirectChildShellSlotRender(rg)) return false;
        if (!hasEditableTextOwnerSignal(rg)) return false;
        if (!isExtractedTextlessVisual(rg)) return false;
        return !isMultiTextCarrierShell(rg);
    }

    private boolean hasTableOnlyTextFrameSource(RenderedGroup rg) {
        return rg != null && hasTableOnlyTextFrameSource(rg.sourceObjectIds());
    }

    private boolean hasTableOnlyTextFrameSource(int[] sourceIds) {
        if (sourceIds == null || sourceIds.length == 0 || data == null) return false;
        for (int sourceId : sourceIds) {
            ResolvedTextFrame tf = data.getTextFrame(String.valueOf(sourceId));
            if (tf == null) continue;
            IDMLStory story = loadStory(tf.storyId());
            if (TableFrameOwnershipPolicy.isTableOnlyTextFrame(tf, story)) {
                return true;
            }
        }
        return false;
    }

    private boolean isExtractedTextlessVisual(RenderedGroup rg) {
        if (rg == null) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (rg.file() == null || rg.file().isEmpty()) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) {
            return false;
        }
        return rg.hasEditableTextHiddenFromPng()
                || hasTextlessShellWithInferredEditableTextSource(rg);
    }

    private boolean isInlineCompleteGraphicWithHwpxTextSource(RenderedGroup rg, Placement placement, TextAction textAction) {
        if (rg == null || placement != Placement.INLINE) return false;
        if (textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        if (!"inline_graphic_only".equals(rg.reason())) return false;
        if (isExtractedTextlessVisual(rg)) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return true;
        return hasEditableTextOwnerSignal(rg);
    }

    private boolean isInlineGraphicWithEditableTextSourcesButNoTextlessShell(RenderedGroup rg, Placement placement) {
        if (rg == null || placement != Placement.INLINE) return false;
        if (!"inline_graphic_only".equals(rg.reason())) return false;
        if (hasTextlessShellWithInferredEditableTextSource(rg)) return false;
        if (hasExplicitTextlessShellSignal(rg)) return false;
        return inferredEditableTextFrameSourceIds(rg).length > 0;
    }

    private boolean hasTextlessShellWithInferredEditableTextSource(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (rg.file() == null || rg.file().isEmpty()) return false;
        if ("indesign_png".equals(rg.textOwner())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return false;
        if (!hasExplicitTextlessShellSignal(rg)
                && !isInlineTextlessShellWithInferredEditableTextSourceCandidate(rg)) {
            return false;
        }
        return inferredEditableTextFrameSourceIds(rg).length > 0;
    }

    private boolean isInlineTextlessShellWithInferredEditableTextSourceCandidate(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (!data.isInlineObjectId(rg.id())) return false;
        if (!"inline_graphic_only".equals(safe(rg.reason()))) return false;
        if (!"indesign_png".equals(safe(rg.visualOwner()))) return false;
        if ("indesign_png".equals(safe(rg.textOwner()))) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) {
            return false;
        }
        return hasVisibleShellMaterialSource(rg);
    }

    private static boolean hasExplicitTextlessShellSignal(RenderedGroup rg) {
        if (rg == null) return false;
        if (rg.isTextHiddenBeforeExport()) return true;
        String reason = safe(rg.reason());
        if (reason.contains("text_hidden")) return true;
        String base = basename(rg.file());
        return base.startsWith("deco_")
                || base.startsWith("tf_shell_")
                || base.startsWith("label_backdrop_");
    }

    private static boolean hasExplicitTextlessShellSignal(ObjectPlan plan) {
        if (plan == null) return false;
        String reason = safe(plan.reason);
        if (reason.contains("text_hidden")) return true;
        String base = basename(plan.file);
        return base.startsWith("deco_")
                || base.startsWith("tf_shell_")
                || base.startsWith("label_backdrop_");
    }

    private static String basename(String path) {
        if (path == null) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private boolean isInlineCompleteGraphicWithSeparateTextHiddenShell(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (!"inline_graphic_only".equals(rg.reason())) return false;
        if (!"inline_object".equals(rg.type()) && !"inline_object".equals(rg.itemType())) return false;
        for (RenderedGroup other : data.allRenderedFloatingItems()) {
            if (other == null || other == rg) continue;
            if (other.id() != rg.id()) continue;
            if (!isRenderedPageObject(other)) continue;
            if (isEditableVisualShellWithSeparateHwpxText(other)) return true;
        }
        return false;
    }

    private boolean hasEditableTextOwnerSignal(RenderedGroup rg) {
        return hasSemanticEditableTextOwnerSignal(rg);
    }

    private boolean hasTextFrameSource(ObjectPlan plan) {
        return hasTextFrameSource(plan != null ? plan.sourceObjectIds : null);
    }

    private boolean hasTextFrameSource(int[] sourceIds) {
        if (sourceIds == null || sourceIds.length == 0 || data == null) return false;
        for (int sourceId : sourceIds) {
            if (data.getTextFrame(String.valueOf(sourceId)) != null) {
                return true;
            }
        }
        return false;
    }

    private int[] withoutTextFrameSourceIds(int[] sourceIds) {
        if (sourceIds == null || sourceIds.length == 0 || data == null) return sourceIds;
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        for (int sourceId : sourceIds) {
            if (data.getTextFrame(String.valueOf(sourceId)) == null) {
                ids.add(sourceId);
            }
        }
        if (ids.isEmpty()) return sourceIds;
        int[] out = new int[ids.size()];
        int i = 0;
        for (Integer id : ids) {
            out[i++] = id != null ? id : -1;
        }
        return out;
    }

    private int[] visualShellSourceIds(RenderedGroup rg, int[] fallback) {
        if (rg == null || data == null) return fallback;
        if (hasEditableTextOwnerSignal(rg)) {
            int[] filtered = withoutTextFrameSourceIds(rg.sourceObjectIds());
            if (filtered != null && filtered.length > 0) {
                return filtered;
            }
        }
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        int selfId = rg.id();
        if (selfId >= 0) ids.add(selfId);
        int[] sources = rg.sourceObjectIds() != null ? rg.sourceObjectIds() : fallback;
        if (sources != null) {
            for (int id : sources) {
                ResolvedPageItem item = data.getPageItem(String.valueOf(id));
                if (item != null && "TextFrame".equals(safe(item.type()))) continue;
                ids.add(id);
            }
        }
        if (ids.isEmpty()) return fallback;
        int[] out = new int[ids.size()];
        int i = 0;
        for (Integer id : ids) out[i++] = id != null ? id : -1;
        return out;
    }

    private boolean looksLikeAbsorbableEditableLabelShell(RenderedGroup rg) {
        if (rg == null) return false;
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double h = Math.abs(b[2] - b[0]);
        double w = Math.abs(b[3] - b[1]);
        if (w < 8.0 || h < 3.0 || h > 28.0 || w > 140.0) return false;
        if (w / Math.max(1.0, h) < 1.15) return false;

        String reason = safe(rg.reason());
        return reason.contains("label")
                || reason.contains("textframe_visual_shell")
                || reason.contains("text_composite_editable_text_hidden")
                || reason.contains("visual_shell")
                || hasSemanticEditableTextOwnerSignal(rg);
    }

    private boolean hasOnlyAbsorbableEditableLabelShellSources(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        boolean hasTextFrame = false;
        boolean hasRect = false;
        for (int id : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (item == null) continue;
            String type = safe(item.type());
            if ("Group".equals(type)) {
                continue;
            }
            if ("TextFrame".equals(type)) {
                hasTextFrame = true;
                continue;
            }
            if (!"Rectangle".equals(type)) {
                return false;
            }
            if (!isSimpleDrawableShape(item)) return false;
            if (Math.abs(item.absoluteRotationAngle()) > 0.5) return false;
            if (Math.abs(item.absoluteShearAngle()) > 0.5) return false;
            hasRect = true;
        }
        return hasTextFrame && hasRect;
    }

    private List<ResolvedTextFrame> ownedTextFramesOf(RenderedGroup rg) {
        List<ResolvedTextFrame> out = new ArrayList<>();
        if (rg == null) return out;
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (rg.editableTextFrameIds() != null) {
            for (String id : rg.editableTextFrameIds()) {
                if (id != null && !id.isBlank()) ids.add(id);
            }
        }
        if (rg.sourceObjectIds() != null) {
            for (int id : rg.sourceObjectIds()) {
                ids.add(String.valueOf(id));
            }
        }
        ids.add(String.valueOf(rg.id()));
        for (String id : ids) {
            ResolvedTextFrame tf = data.getTextFrame(id);
            if (tf != null && !tf.sourceHidden()) {
                out.add(tf);
            }
        }
        return out;
    }

    private boolean canTextFrameAbsorbVisualStyle(ResolvedTextFrame tf) {
        if (tf == null) return false;
        if (hasOwnTextFrameShapeStyle(tf)) return true;
        return hasAbsorbableSiblingShape(tf);
    }

    private static boolean hasOwnTextFrameShapeStyle(ResolvedTextFrame tf) {
        if (tf == null) return false;
        if (!isNoneColor(tf.fillColor()) && !isPaperColor(tf.fillColor())) return true;
        if (!isNoneColor(tf.strokeColor()) && tf.strokeWeight() > 0.01) return true;
        return false;
    }

    private boolean hasAbsorbableSiblingShape(ResolvedTextFrame tf) {
        ResolvedPageItem tfItem = data.getPageItem(tf.id());
        if (tfItem == null || tfItem.parentId() == null) return false;
        double[] tfBounds = tf.pageRelativeBounds() != null ? tf.pageRelativeBounds() : tf.geometricBounds();
        if (tfBounds == null || tfBounds.length < 4) return false;
        boolean foundAbsorbableRect = false;
        for (ResolvedPageItem item : data.pageItems()) {
            if (item == null || item.id() == null || item.id().equals(tf.id())) continue;
            if (!tfItem.parentId().equals(item.parentId())) continue;
            String type = safe(item.type());
            if ("TextFrame".equals(type)) continue;
            if (!isSimpleDrawableShape(item)) {
                if ("GraphicLine".equals(type)) return false;
                continue;
            }
            if (Math.abs(item.absoluteRotationAngle()) > 0.5) continue;
            if (Math.abs(item.absoluteShearAngle()) > 0.5) continue;
            if (!"Rectangle".equals(type)) return false;
            if (overlapRatio(tfBounds, boundsOf(item)) >= 0.70) {
                foundAbsorbableRect = true;
            } else {
                return false;
            }
        }
        return foundAbsorbableRect;
    }

    private static boolean isSimpleDrawableShape(ResolvedPageItem item) {
        if (item == null) return false;
        String type = safe(item.type());
        if (!("Rectangle".equals(type) || "Polygon".equals(type) || "Oval".equals(type))) {
            return false;
        }
        boolean hasFill = !isNoneColor(item.fillColorName());
        boolean hasStroke = !isNoneColor(item.strokeColorName()) && item.strokeWeight() > 0.01;
        return hasFill || hasStroke || item.cornerRadius() > 0.01;
    }

    private boolean isCalloutOrOutlineTextShell(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        String reason = safe(rg.reason());
        if (!reason.contains("mixed_group_text_hidden")
                && !reason.contains("image_group_text_hidden")
                && !reason.contains("complex_graphic_text_hidden")) {
            return false;
        }
        if (hasImageSource(rg)) {
            return false;
        }
        int textFrames = 0;
        int drawableShapes = 0;
        int polygonShapes = 0;
        int roundedOrStrokedShapes = 0;
        if (rg.sourceObjectIds() != null) {
            for (int id : rg.sourceObjectIds()) {
                ResolvedPageItem item = data.getPageItem(String.valueOf(id));
                if (item == null) continue;
                String type = safe(item.type());
                if ("TextFrame".equals(type)) {
                    textFrames++;
                    continue;
                }
                if (!isSimpleDrawableShape(item)) continue;
                drawableShapes++;
                if ("Polygon".equals(type)) {
                    polygonShapes++;
                }
                boolean hasStroke = !isNoneColor(item.strokeColorName()) && item.strokeWeight() > 0.01;
                if (hasStroke || item.cornerRadius() > 0.01) {
                    roundedOrStrokedShapes++;
                }
            }
        }
        if (textFrames <= 0 || drawableShapes <= 0) return false;

        double[] b = rg.bounds();
        double h = b != null && b.length >= 4 ? Math.abs(b[2] - b[0]) : 0.0;
        double w = b != null && b.length >= 4 ? Math.abs(b[3] - b[1]) : 0.0;
        boolean calloutTail = polygonShapes > 0 && roundedOrStrokedShapes > 0;
        boolean outlineBox = roundedOrStrokedShapes > 0 && (h > 16.0 || w > 45.0);
        return calloutTail || outlineBox;
    }

    private boolean isCalloutOverlayTextShell(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (!ownedTextUsesCalloutOverlayStyle(rg)) return false;
        if (hasImageSource(rg)) return false;
        if (!hasDrawableBackdropShapeSource(rg)) return false;
        return editableTextFrameIdsOf(rg).length > 0;
    }

    private boolean hasImageSource(RenderedGroup rg) {
        if (rg == null || data == null || rg.sourceObjectIds() == null) return false;
        for (int id : rg.sourceObjectIds()) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (item != null && "Image".equals(safe(item.type()))) return true;
        }
        return false;
    }

    private Placement placementOf(RenderedGroup rg) {
        if (isDirectChildShellSlotRender(rg) && hasEditableTextOwnerSignal(rg)) {
            return Placement.FLOATING;
        }
        if (isRenderedPageObject(rg)
                && hasDirectResolvedInlineAnchorSource(rg)) {
            return Placement.INLINE;
        }
        if ("inline_object".equals(rg.type()) || "inline_object".equals(rg.itemType())) {
            if (hasAnchoredPagePositionSource(rg)) {
                return Placement.FLOATING;
            }
            if (isDirectStoryFlowInlineGraphicOwner(rg)) {
                return Placement.INLINE;
            }
            if (InlineSemanticLabelPolicy.isStandaloneSemanticGraphicInlineGroup(data, rg)) {
                return Placement.FLOATING;
            }
            if (hasInlineSourceObject(rg)) {
                return Placement.INLINE;
            }
            if (!hasResolvedInlineAnchor(rg.id())) {
                return Placement.FLOATING;
            }
            return Placement.INLINE;
        }
        if (isRenderedPageObject(rg) && isInlineLeafTextShellAtom(rg)) {
            return Placement.INLINE;
        }
        if (isRenderedPageObject(rg)) {
            return Placement.FLOATING;
        }
        if (isTextlessShellForInlineOwnedTextFrame(rg)) {
            return Placement.INLINE;
        }
        if (isTextHiddenShellForInlineAnchor(rg)) {
            return Placement.INLINE;
        }
        if (isMultiTextVisualLabelShell(rg)) {
            return Placement.FLOATING;
        }
        if (rg.isPageBackground()) {
            return Placement.FLOATING;
        }
        return Placement.FLOATING;
    }

    private boolean isDirectStoryFlowInlineGraphicOwner(RenderedGroup rg) {
        if (!isStandaloneGraphicOnlyInlineObject(rg)) return false;
        int anchorId = directStoryFlowInlineGraphicAnchorId(rg);
        return anchorId > 0;
    }

    private int directStoryFlowInlineGraphicAnchorId(RenderedGroup rg) {
        if (rg == null || data == null) return -1;
        int declaredAnchorSourceId = rg.inlineAnchorSourceObjectId();
        if (isDirectStoryFlowInlineGraphicAnchor(declaredAnchorSourceId, sourceIdsOrSelf(rg))) {
            return declaredAnchorSourceId;
        }
        if (isDirectStoryFlowInlineGraphicAnchor(rg.id(), sourceIdsOrSelf(rg))) {
            return rg.id();
        }
        int[] sourceIds = sourceIdsOrSelf(rg);
        if (sourceIds == null) return -1;
        for (int sourceId : sourceIds) {
            if (isDirectStoryFlowInlineGraphicAnchor(sourceId, sourceIds)) {
                return sourceId;
            }
        }
        return -1;
    }

    private boolean isDirectStoryFlowInlineGraphicAnchor(int anchorSourceId, int[] sourceIds) {
        if (anchorSourceId <= 0) return false;
        ResolvedPageItem item = data.getPageItem(String.valueOf(anchorSourceId));
        if (item == null || item.sourceHidden()) return false;
        if (hasResolvedAnchoredPagePosition(anchorSourceId)
                || hasIdmlAnchoredPagePosition(anchorSourceId)) {
            return false;
        }
        if (!item.storyTextInlineSlot() && !hasIdmlStoryInlineAnchor(anchorSourceId)) return false;
        if (!idmlInlineAnchorParagraphHasVisibleText(anchorSourceId)
                && !hasResolvedInlineAnchorForSourceId(anchorSourceId)) {
            return false;
        }
        return allSourcesBelongToInlineAnchorTree(anchorSourceId, sourceIds);
    }

    private boolean hasStoryFlowPlacementContext(RenderedGroup rg) {
        if (rg == null) return false;
        if (!safe(rg.parentStoryId()).isEmpty()) return true;
        int id = rg.id();
        for (RenderedGroup owner : allRenderedGroups()) {
            if (owner == null || owner == rg) continue;
            if (contains(owner.tfInlineVisualIds(), id)) return true;
        }
        return false;
    }

    private boolean hasAnchoredPagePositionSource(RenderedGroup rg) {
        if (rg == null) return false;
        if (hasResolvedAnchoredPagePosition(rg.id())) return true;
        if (hasIdmlAnchoredPagePosition(rg.id())) return true;
        if (rg.sourceObjectIds() == null) return false;
        for (int sourceId : rg.sourceObjectIds()) {
            if (hasResolvedAnchoredPagePosition(sourceId)) return true;
            if (hasIdmlAnchoredPagePosition(sourceId)) return true;
        }
        return false;
    }

    private boolean isInlineLeafTextShellAtom(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (!hasSemanticEditableTextOwnerSignal(rg)) return false;
        if (!hasExplicitTextlessShellSignal(rg)) return false;
        ResolvedPageItem root = data.getPageItem(String.valueOf(rg.id()));
        if (root == null || !root.isInline()) return false;
        if (!"Group".equals(safe(root.type()))) return false;
        int[] textFrameIds = editableTextFrameIdsOf(rg);
        if (textFrameIds.length != 1) return false;
        int textChildren = 0;
        int shellChildren = 0;
        if (root.childIds() == null || root.childIds().length == 0) return false;
        for (int childId : root.childIds()) {
            ResolvedPageItem child = data.getPageItem(String.valueOf(childId));
            if (child == null || child.sourceHidden()) continue;
            String type = safe(child.type());
            if ("TextFrame".equals(type)) {
                if (!contains(textFrameIds, childId)) return false;
                textChildren++;
                continue;
            }
            if (isDirectShellShape(child)) {
                shellChildren++;
                continue;
            }
            return false;
        }
        return textChildren == 1 && shellChildren == 1;
    }

    private static boolean isDirectShellShape(ResolvedPageItem item) {
        if (item == null) return false;
        String type = safe(item.type());
        return "Rectangle".equals(type)
                || "Polygon".equals(type)
                || "Oval".equals(type)
                || "GraphicLine".equals(type);
    }

    private boolean isTextlessShellForInlineOwnedTextFrame(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (!hasSemanticEditableTextOwnerSignal(rg)) return false;
        if (!hasExplicitTextlessShellSignal(rg)) return false;
        int[] textFrameIds = editableTextFrameIdsOf(rg);
        if (textFrameIds.length == 0) return false;
        for (int textFrameId : textFrameIds) {
            ResolvedTextFrame tf = data.getTextFrame(String.valueOf(textFrameId));
            if (tf == null || !tf.isInline()) return false;
        }
        return true;
    }

    private boolean isMultiTextVisualLabelShell(RenderedGroup rg) {
        if (rg == null) return false;
        if (!"visual_label_text_hidden_shell".equals(rg.reason())) return false;
        String[] ids = rg.editableTextFrameIds();
        return ids != null && ids.length > 1;
    }

    private boolean isTextHiddenShellForInlineAnchor(RenderedGroup rg) {
        if (rg == null) return false;
        if (hasAnchoredPagePositionSource(rg)) return false;
        if (!isDirectInlineTextShellReason(rg.reason())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (!"hwpx_tf".equals(rg.textOwner())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return false;
        if (!rg.hasEditableTextHiddenFromPng()) return false;
        if (!hasInlineSourceObject(rg) && !hasResolvedInlineAnchor(rg.id())) return false;
        String[] ids = rg.editableTextFrameIds();
        if (ids == null || ids.length == 0) return false;
        for (String id : ids) {
            ResolvedTextFrame tf = data != null ? data.getTextFrame(id) : null;
            if (tf == null || !tf.isInline()) return false;
        }
        return true;
    }

    private boolean hasInlineSourceObject(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (data.isInlineObjectId(rg.id())) return true;
        ResolvedPageItem self = data.getPageItem(String.valueOf(rg.id()));
        if (self != null && self.isInline()) return true;
        if (rg.sourceObjectIds() == null) return false;
        for (int sourceId : rg.sourceObjectIds()) {
            if (data.isInlineObjectId(sourceId)) return true;
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item != null && item.isInline()) return true;
        }
        return false;
    }

    private boolean hasDirectResolvedInlineAnchorSource(RenderedGroup rg) {
        return directResolvedInlineAnchorSourceId(rg) != null;
    }

    private Integer directResolvedInlineAnchorSourceId(RenderedGroup rg) {
        if (rg == null || data == null) return null;
        int declaredAnchorSourceId = rg.inlineAnchorSourceObjectId();
        if (declaredAnchorSourceId > 0) {
            if (hasResolvedInlineAnchor(declaredAnchorSourceId)
                    && !hasResolvedAnchoredPagePosition(declaredAnchorSourceId)
                    && !hasIdmlAnchoredPagePosition(declaredAnchorSourceId)
                    && rg.inlineSourceTreeClosed()) {
                return declaredAnchorSourceId;
            }
        }
        int[] sourceIds = sourceIdsOrSelf(rg);
        if (sourceIds == null || sourceIds.length == 0) return null;
        for (int sourceId : sourceIds) {
            if (sourceId <= 0) continue;
            if (!hasResolvedInlineAnchor(sourceId)) continue;
            if (hasResolvedAnchoredPagePosition(sourceId)) continue;
            if (hasIdmlAnchoredPagePosition(sourceId)) continue;
            if (!allSourcesBelongToInlineAnchorTree(sourceId, sourceIds)) continue;
            int[] exportSourceIds = rg.exportSourceObjectIds();
            if (exportSourceIds != null
                    && exportSourceIds.length > 0
                    && !allSourcesBelongToInlineAnchorTree(sourceId, exportSourceIds)) {
                continue;
            }
            return sourceId;
        }
        return null;
    }

    private boolean allSourcesBelongToInlineAnchorTree(int anchorSourceId, int[] sourceIds) {
        if (anchorSourceId <= 0 || sourceIds == null || sourceIds.length == 0) return false;
        LinkedHashSet<String> allowed = new LinkedHashSet<>();
        allowed.add(String.valueOf(anchorSourceId));
        if (data != null) {
            Set<String> descendants = data.buildDescendantSet(String.valueOf(anchorSourceId), 8);
            if (descendants != null) allowed.addAll(descendants);
        }
        for (int sourceId : sourceIds) {
            if (sourceId <= 0) continue;
            if (!allowed.contains(String.valueOf(sourceId))) {
                return false;
            }
        }
        return true;
    }

    private boolean hasResolvedInlineAnchor(int domId) {
        if (data == null || domId < 0 || data.stories() == null) return false;
        Boolean cached = resolvedInlineAnchorCache.get(domId);
        if (cached != null) return cached;
        boolean found = hasResolvedPageItemInlineAnchor(domId)
                || hasResolvedStoryInlineAnchor(domId)
                || hasIdmlStoryInlineAnchor(domId);
        resolvedInlineAnchorCache.put(domId, found);
        return found;
    }

    private boolean hasResolvedPageItemInlineAnchor(int domId) {
        if (data == null || domId < 0) return false;
        ResolvedPageItem item = data.getPageItem(String.valueOf(domId));
        if (item == null) return false;
        String anchoredPosition = safe(item.anchoredPosition());
        String storyAnchorPlacement = safe(item.storyAnchorPlacement());
        if ("ANCHORED".equals(anchoredPosition)
                || "FLOATING_ANCHORED".equals(storyAnchorPlacement)) {
            return false;
        }
        if (item.isInline()) return true;
        return "INLINE_POSITION".equals(anchoredPosition)
                || "INLINE".equals(storyAnchorPlacement);
    }

    private boolean hasResolvedStoryInlineAnchor(int domId) {
        if (ctx != null && ctx.textFlowDocument != null && ctx.textFlowDocument.hasInlineSlot(domId)) {
            return true;
        }
        if (ctx != null && ctx.textFlowIndex != null && ctx.textFlowIndex.indexedInlineAnchorCount() > 0) {
            return !ctx.textFlowIndex.inlineSlotsByAnchorId(domId).isEmpty();
        }
        for (ResolvedStory story : data.stories()) {
            if (story == null || story.paragraphs() == null) continue;
            for (ResolvedParagraph paragraph : story.paragraphs()) {
                if (paragraph == null || paragraph.runs() == null) continue;
                for (ResolvedRun run : paragraph.runs()) {
                    if (run == null || !run.isInlineAnchor()) continue;
                    Integer anchoredId = run.anchoredObjectId();
                    if (anchoredId != null && anchoredId == domId) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasIdmlStoryInlineAnchor(int domId) {
        if (ctx == null || ctx.loadIDMLStory == null || data == null || data.textFrames() == null) return false;
        HashSet<String> visitedStoryIds = new HashSet<>();
        for (ResolvedTextFrame tf : data.textFrames()) {
            if (tf == null || tf.storyId() == null || tf.storyId().isEmpty()) continue;
            if (!visitedStoryIds.add(tf.storyId())) continue;
            IDMLStory story = loadStory(tf.storyId());
            if (idmlStoryContainsInlineAnchor(story, domId)) return true;
        }
        return false;
    }

    private boolean hasIdmlAnchoredPagePosition(int domId) {
        if (ctx == null || ctx.loadIDMLStory == null || data == null || data.textFrames() == null) return false;
        Boolean cached = idmlAnchoredPagePositionCache.get(domId);
        if (cached != null) return cached;
        boolean found = false;
        HashSet<String> visitedStoryIds = new HashSet<>();
        for (ResolvedTextFrame tf : data.textFrames()) {
            if (tf == null || tf.storyId() == null || tf.storyId().isEmpty()) continue;
            if (!visitedStoryIds.add(tf.storyId())) continue;
            IDMLStory story = loadStory(tf.storyId());
            if (idmlStoryContainsAnchoredPagePosition(story, domId)) {
                found = true;
                break;
            }
        }
        idmlAnchoredPagePositionCache.put(domId, found);
        return found;
    }

    private static boolean idmlStoryContainsInlineAnchor(IDMLStory story, int domId) {
        if (story == null) return false;
        if (story.paragraphs() != null) {
            for (IDMLParagraph paragraph : story.paragraphs()) {
                if (idmlParagraphContainsInlineAnchor(paragraph, domId)) return true;
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
                        if (idmlParagraphContainsInlineAnchor(paragraph, domId)) return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean idmlParagraphContainsInlineAnchor(IDMLParagraph paragraph, int domId) {
        if (paragraph == null || paragraph.characterRuns() == null) return false;
        for (IDMLCharacterRun run : paragraph.characterRuns()) {
            if (run == null || run.inlineAnchors() == null || run.inlineAnchors().isEmpty()) continue;
            for (IDMLCharacterRun.InlineAnchor anchor : run.inlineAnchors()) {
                if (idmlInlineAnchorDomId(run, anchor) == domId) return true;
            }
        }
        return false;
    }

    private static boolean idmlStoryContainsAnchoredPagePosition(IDMLStory story, int domId) {
        if (story == null) return false;
        if (story.paragraphs() != null) {
            for (IDMLParagraph paragraph : story.paragraphs()) {
                if (idmlParagraphContainsAnchoredPagePosition(paragraph, domId)) return true;
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
                        if (idmlParagraphContainsAnchoredPagePosition(paragraph, domId)) return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean idmlParagraphContainsAnchoredPagePosition(IDMLParagraph paragraph, int domId) {
        if (paragraph == null || paragraph.characterRuns() == null) return false;
        for (IDMLCharacterRun run : paragraph.characterRuns()) {
            if (run == null || run.inlineAnchors() == null || run.inlineAnchors().isEmpty()) continue;
            for (IDMLCharacterRun.InlineAnchor anchor : run.inlineAnchors()) {
                if (idmlInlineAnchorDomId(run, anchor) != domId) continue;
                IDMLCharacterRun.InlineGraphic graphic = idmlInlineAnchorGraphic(run, anchor);
                if (graphic == null) continue;
                return "Anchored".equalsIgnoreCase(graphic.anchoredPosition());
            }
        }
        return false;
    }

    private static int idmlInlineAnchorDomId(IDMLCharacterRun run, IDMLCharacterRun.InlineAnchor anchor) {
        if (run == null || anchor == null) return -1;
        if (anchor.type() == IDMLCharacterRun.InlineAnchorType.FRAME) {
            if (run.inlineFrames() == null || anchor.index() < 0 || anchor.index() >= run.inlineFrames().size()) {
                return -1;
            }
            IDMLTextFrame frame = run.inlineFrames().get(anchor.index());
            return parseFlexibleId(frame != null ? frame.selfId() : null);
        }
        if (anchor.type() == IDMLCharacterRun.InlineAnchorType.GRAPHIC) {
            if (run.inlineGraphics() == null || anchor.index() < 0 || anchor.index() >= run.inlineGraphics().size()) {
                return -1;
            }
            IDMLCharacterRun.InlineGraphic graphic = run.inlineGraphics().get(anchor.index());
            return parseFlexibleId(graphic != null ? graphic.selfId() : null);
        }
        return -1;
    }

    private static IDMLCharacterRun.InlineGraphic idmlInlineAnchorGraphic(
            IDMLCharacterRun run,
            IDMLCharacterRun.InlineAnchor anchor) {
        if (run == null || anchor == null) return null;
        if (anchor.type() != IDMLCharacterRun.InlineAnchorType.GRAPHIC) return null;
        if (run.inlineGraphics() == null || anchor.index() < 0 || anchor.index() >= run.inlineGraphics().size()) {
            return null;
        }
        return run.inlineGraphics().get(anchor.index());
    }

    private void resolveFloatingInlineObjectPageObjectDuplicates() {
        Map<String, Boolean> floatingInlineObjectByPageDom = new HashMap<>();
        for (ObjectPlan plan : plans) {
            if (!plan.hasVisibleVisual()) continue;
            if (plan.placement != Placement.FLOATING) continue;
            if (plan.domId < 0) continue;
            if (!safe(plan.kind).contains("inline_object")) continue;
            floatingInlineObjectByPageDom.put(pageDomKey(plan), Boolean.TRUE);
        }
        if (floatingInlineObjectByPageDom.isEmpty()) return;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!plan.hasVisibleVisual()) continue;
            if (plan.domId < 0) continue;
            if (safe(plan.kind).contains("inline_object")) continue;
            if (!Boolean.TRUE.equals(floatingInlineObjectByPageDom.get(pageDomKey(plan)))) continue;
            plans.set(i, plan.withVisualAction(VisualAction.DROP_VISUAL,
                    "page_object_duplicate_of_floating_inline_object"));
        }
    }

    private void writePlans() {
        for (ObjectPlan plan : plans) {
            ctx.addOwnershipPlan(plan);
            ctx.ownershipPlanLines.add(plan.toJson());
        }
    }

    private void completeRenderedExtractionSourceContracts() {
        int completed = 0;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            RenderedGroup rendered = renderedGroupForPlan(plan);
            if (rendered == null) continue;
            int[] exportSourceIds = rendered.exportSourceObjectIds();
            int[] hiddenVisualSourceIds = rendered.hiddenVisualSourceObjectIds();
            if ((exportSourceIds == null || exportSourceIds.length == 0)
                    && (hiddenVisualSourceIds == null || hiddenVisualSourceIds.length == 0)) {
                ObjectPlan next = plan.withExtractionCandidate(
                        rendered.candidateId(),
                        rendered.planPassId(),
                        renderedSlotRoleForPlan(plan, rendered));
                plans.set(i, next);
                completed++;
                continue;
            }
            ObjectPlan next = plan.withExtractionSourceObjectIds(exportSourceIds, hiddenVisualSourceIds)
                    .withExtractionCandidate(
                            rendered.candidateId(),
                            rendered.planPassId(),
                            renderedSlotRoleForPlan(plan, rendered));
            plans.set(i, next);
            completed++;
        }
        ConversionTiming.metric("stage1.ownershipPlanner.renderedExtractionSourceContracts", completed);
    }

    private void restoreInlineCarrierVisualContracts() {
        int restored = 0;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null || !plan.hasVisibleVisual()) continue;
            RenderedGroup rendered = renderedGroupForPlan(plan);
            if (!isClosedInlineCarrierVisual(rendered)) continue;
            if (plan.placement == Placement.INLINE
                    && plan.coordinateSpace == CoordinateSpace.STORY_FLOW
                    && plan.visualAction == VisualAction.PLACE_INLINE_PNG) {
                continue;
            }
            ObjectPlan replacement = plan
                    .withTextAction(TextAction.DROP_TEXT)
                    .withPlacementAndCoordinateSpace(Placement.INLINE, CoordinateSpace.STORY_FLOW)
                    .withVisualAction(VisualAction.PLACE_INLINE_PNG,
                            plan.reason != null ? plan.reason : "inline_carrier_visual_contract")
                    .withMaterialization(Materialization.EXTRACTED_PNG_VECTOR)
                    .withOwnedTextFrameIds(new int[0]);
            plans.set(i, replacement);
            restored++;
        }
        ConversionTiming.metric("stage1.ownershipPlanner.inlineCarrierVisualContracts.restored",
                restored);
    }

    private boolean isClosedInlineCarrierVisual(RenderedGroup rendered) {
        if (rendered == null) return false;
        if (rendered.inlineAnchorSourceObjectId() <= 0) return false;
        return rendered.inlineSourceTreeClosed();
    }

    private static String renderedSlotRoleForPlan(ObjectPlan plan, RenderedGroup rendered) {
        String renderedSlotRole = rendered != null ? safe(rendered.slotRole()) : "";
        if (plan == null) return renderedSlotRole;
        if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                && "CONTENT_VISUAL_SLOT".equals(renderedSlotRole)) {
            return plan.slotRole;
        }
        return renderedSlotRole;
    }

    private void completeSourceTreeDiagnostics() {
        if (data == null) return;
        Map<Integer, int[]> clusterByRoot = new HashMap<>();
        int completed = 0;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null || plan.sourceObjectIds == null || plan.sourceObjectIds.length == 0) {
                continue;
            }
            int[] roots = sourceRootObjectIds(plan.sourceObjectIds);
            int[] cluster = clusterSourceObjectIds(roots, clusterByRoot);
            int[] omitted = omittedClusterSourceObjectIds(plan, cluster);
            plans.set(i, plan.withSourceTreeDiagnostics(roots, cluster, omitted));
            completed++;
        }
        ConversionTiming.metric("stage1.ownershipPlanner.sourceTreeDiagnostics", completed);
    }

    private void restoreDroppedRenderedTextShellSourceContracts() {
        if (data == null) return;
        int restored = 0;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!isDroppedRenderedTextShellWithoutVisibleSource(plan)) continue;

            int[] recoveredVisualSources = recoverDroppedRenderedTextShellVisualSources(plan);
            if (recoveredVisualSources.length == 0) continue;

            int[] mergedStyleSources = mergeIds(plan.styleSourceObjectIds, recoveredVisualSources);
            ObjectPlan shellPlan = plan
                    .withVisualSourceObjectIds(recoveredVisualSources)
                    .withStyleSourceObjectIds(mergedStyleSources)
                    .withVisualAction(VisualAction.PLACE_TEXT_SHELL,
                            "recovered_rendered_text_shell_source_contract")
                    .withMaterialization(Materialization.EXTRACTED_PNG_VECTOR);
            shellPlan = shellPlan.withVisualLayer(
                    textShellVisualLayer(shellPlan, shellPlan.ownedTextFrameIds, VisualLayer.CONTAINER_BACKDROP));
            plans.set(i, shellPlan);
            restored++;
        }
        ConversionTiming.metric("stage1.ownershipPlanner.renderedTextShellSourceContracts.restored",
                restored);
    }

    private boolean isDroppedRenderedTextShellWithoutVisibleSource(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.renderId == null) return false;
        if (plan.visualAction != VisualAction.DROP_VISUAL) return false;
        if (!"text_shell_without_visible_visual_source".equals(safe(plan.reason))) return false;
        if (!safe(plan.kind).startsWith("rendered_")) return false;
        return plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0;
    }

    private int[] recoverDroppedRenderedTextShellVisualSources(ObjectPlan plan) {
        LinkedHashSet<Integer> recovered = new LinkedHashSet<>();
        if (plan == null) return new int[0];

        collectRecoveredDroppedRenderedTextShellVisualSources(
                plan,
                plan.omittedClusterSourceObjectIds,
                recovered);
        collectRecoveredDroppedRenderedTextShellVisualSources(
                plan,
                plan.sourceObjectIds,
                recovered);

        return toIntArray(recovered);
    }

    private void collectRecoveredDroppedRenderedTextShellVisualSources(
            ObjectPlan plan,
            int[] sourceIds,
            LinkedHashSet<Integer> recovered) {
        if (plan == null || sourceIds == null || recovered == null) return;
        for (int sourceId : sourceIds) {
            if (!isRecoverableDroppedRenderedTextShellMaterialSource(plan, sourceId)) continue;
            recovered.add(sourceId);
        }
    }

    private boolean isRecoverableDroppedRenderedTextShellMaterialSource(ObjectPlan plan, int sourceId) {
        if (sourceId <= 0 || data == null) return false;
        if (contains(plan.hiddenVisualSourceObjectIds, sourceId)) return false;
        if (contains(plan.ownedTextFrameIds, sourceId)) return false;

        ResolvedTextFrame tf = data.getTextFrame(String.valueOf(sourceId));
        if (tf != null) {
            if (tf.sourceHidden()) return false;
            if (hasSemanticText(tf)) return false;
            return sourceIdHasVisibleTextFrameShellMaterial(sourceId);
        }

        ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
        return sourceItemHasVisibleShellMaterial(item);
    }

    private int[] sourceRootObjectIds(int[] sourceObjectIds) {
        LinkedHashSet<Integer> sourceSet = toLinkedSet(sourceObjectIds);
        LinkedHashSet<Integer> roots = new LinkedHashSet<>();
        for (int sourceId : sourceSet) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item == null || item.parentId() == null || item.parentId().isBlank()) {
                roots.add(sourceId);
                continue;
            }
            int parentId = parseFlexibleId(item.parentId());
            if (parentId < 0 || !sourceSet.contains(parentId)) {
                roots.add(sourceId);
            }
        }
        if (roots.isEmpty()) roots.addAll(sourceSet);
        return toIntArray(roots);
    }

    private boolean hasPageLevelSourceRoots(int[] sourceObjectIds) {
        if (sourceObjectIds == null || sourceObjectIds.length == 0 || data == null) return false;
        int[] roots = sourceRootObjectIds(sourceObjectIds);
        if (roots.length == 0) return false;
        for (int rootId : roots) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(rootId));
            if (item == null) return false;
            if (item.parentId() != null && !item.parentId().isBlank()) {
                return false;
            }
        }
        return true;
    }

    private int[] clusterSourceObjectIds(int[] sourceRootObjectIds, Map<Integer, int[]> clusterByRoot) {
        LinkedHashSet<Integer> cluster = new LinkedHashSet<>();
        if (sourceRootObjectIds == null) return new int[0];
        for (int rootId : sourceRootObjectIds) {
            int[] rootCluster = clusterByRoot.computeIfAbsent(rootId, this::clusterSourceObjectIdsForRoot);
            addAll(rootCluster, cluster);
        }
        return toIntArray(cluster);
    }

    private int[] clusterSourceObjectIdsForRoot(int rootId) {
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        ids.add(rootId);
        if (data != null) {
            for (String descendantId : data.buildDescendantSet(String.valueOf(rootId), 256)) {
                int parsed = parseFlexibleId(descendantId);
                if (parsed >= 0) ids.add(parsed);
            }
        }
        return toIntArray(ids);
    }

    private int[] omittedClusterSourceObjectIds(ObjectPlan plan, int[] clusterSourceObjectIds) {
        if (plan == null || clusterSourceObjectIds == null || clusterSourceObjectIds.length == 0) {
            return new int[0];
        }
        LinkedHashSet<Integer> claimed = new LinkedHashSet<>();
        addAll(plan.sourceObjectIds, claimed);
        addAll(plan.visualSourceObjectIds, claimed);
        addAll(plan.styleSourceObjectIds, claimed);
        addAll(plan.exportSourceObjectIds, claimed);
        addAll(plan.hiddenVisualSourceObjectIds, claimed);
        addAll(plan.ownedTextFrameIds, claimed);
        addAll(plan.descendantVisualObjectIds, claimed);
        LinkedHashSet<Integer> omitted = new LinkedHashSet<>();
        for (int sourceId : clusterSourceObjectIds) {
            if (!claimed.contains(sourceId)) {
                omitted.add(sourceId);
            }
        }
        return toIntArray(omitted);
    }

    private LinkedHashSet<Integer> toLinkedSet(int[] values) {
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        addAll(values, ids);
        return ids;
    }

    private void validate() {
        warnDuplicateVisibleSourceIds();
        warnConflictingTextOwnership();
        warnVisibleVisualContainsHwpxTextSource();
        warnInlineFloatingSameDomId();
        warnDuplicateRenderedBounds();
        warnTextShellZOrder();
    }

    private void resolveInlineFloatingSameDom() {
        Map<String, Boolean> inlineVisibleByPageDom = new HashMap<>();
        for (ObjectPlan plan : plans) {
            if (!plan.hasVisibleVisual()) continue;
            if (plan.placement != Placement.INLINE) continue;
            inlineVisibleByPageDom.put(pageDomKey(plan), Boolean.TRUE);
        }
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!plan.hasVisibleVisual()) continue;
            if (plan.placement != Placement.FLOATING) continue;
            if (!Boolean.TRUE.equals(inlineVisibleByPageDom.get(pageDomKey(plan)))) continue;
            plans.set(i, plan.withVisualAction(VisualAction.DROP_VISUAL, plan.reason));
        }
    }

    private boolean isFloatingVisualShellWithSeparateHwpxText(ObjectPlan plan) {
        if (plan == null || plan.placement != Placement.FLOATING) return false;
        if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        if (plan.visualAction != VisualAction.PLACE_FLOATING_PNG
                && plan.visualAction != VisualAction.PLACE_TEXT_SHELL) {
            return false;
        }
        RenderedGroup rendered = renderedGroupForPlan(plan);
        return rendered != null && isEditableVisualShellWithSeparateHwpxText(rendered);
    }

    private void resolveDuplicateRenderedChannels() {
        Map<String, List<Integer>> byRenderedIdentity = new LinkedHashMap<>();
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!plan.hasVisibleVisual()) continue;
            if ("text_frame".equals(plan.kind)) continue;
            if (plan.renderId == null) continue;
            String key = renderedIdentityKey(plan);
            byRenderedIdentity.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }
        for (List<Integer> indexes : byRenderedIdentity.values()) {
            if (indexes.size() <= 1) continue;
            int winner = indexes.get(0);
            for (int idx : indexes) {
                if (renderedChannelPriority(plans.get(idx)) < renderedChannelPriority(plans.get(winner))) {
                    winner = idx;
                }
            }
            for (int idx : indexes) {
                if (idx == winner) continue;
                ObjectPlan loser = plans.get(idx);
                plans.set(idx, loser.withVisualAction(VisualAction.DROP_VISUAL, loser.reason));
            }
        }
    }

    private void resolveDuplicateRenderedIdentityPlans() {
        Map<String, List<Integer>> byIdentity = new LinkedHashMap<>();
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!isVisibleRenderedVisual(plan)) continue;
            String key = canonicalRenderedPlanKey(plan);
            if (key == null) continue;
            byIdentity.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }
        for (List<Integer> indexes : byIdentity.values()) {
            if (indexes.size() <= 1) continue;
            int winner = indexes.get(0);
            for (int idx : indexes) {
                if (canonicalRenderedPlanPriority(plans.get(idx))
                        < canonicalRenderedPlanPriority(plans.get(winner))) {
                    winner = idx;
                }
            }
            for (int idx : indexes) {
                if (idx == winner) continue;
                ObjectPlan loser = plans.get(idx);
                plans.set(idx, loser.withVisualAction(VisualAction.DROP_VISUAL,
                        "duplicate_rendered_identity_plan"));
            }
        }
    }

    private static String canonicalRenderedPlanKey(ObjectPlan plan) {
        if (plan == null) return null;
        String artifactKey = renderedArtifactIdentity(plan);
        if (artifactKey != null) {
            return plan.pageIndex + ":" + plan.domId + ":" + artifactKey;
        }
        if (plan.renderId != null) {
            return plan.pageIndex + ":" + plan.domId + ":render:" + plan.renderId;
        }
        if (plan.file != null && !plan.file.isEmpty()) {
            return plan.pageIndex + ":" + plan.domId + ":file:" + plan.file;
        }
        return null;
    }

    private static int canonicalRenderedPlanPriority(ObjectPlan plan) {
        if (plan == null) return 1000;
        String kind = safe(plan.kind);
        if (kind.startsWith("simple_button_label:")) return 0;
        if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT) {
            return 10;
        }
        if (plan.textAction == TextAction.OWNED_BY_PNG) return 20;
        return 100 + renderedChannelPriority(plan);
    }

    private void resolveFloatingChildrenOwnedByInlineParent() {
        List<ObjectPlan> inlineOwners = new ArrayList<>();
        for (ObjectPlan plan : plans) {
            if (!isVisibleRenderedVisual(plan)) continue;
            if (plan.placement != Placement.INLINE) continue;
            if (plan.visualAction != VisualAction.PLACE_INLINE_PNG) continue;
            if (plan.sourceObjectIds == null || plan.sourceObjectIds.length <= 1) continue;
            inlineOwners.add(plan);
        }
        if (inlineOwners.isEmpty()) return;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan child = plans.get(i);
            if (!isVisibleRenderedVisual(child)) continue;
            if (child.placement != Placement.FLOATING) continue;
            if (isLabelBackdropGroupPlan(child)) continue;
            if (child.sourceObjectIds == null || child.sourceObjectIds.length == 0) continue;
            for (ObjectPlan parent : inlineOwners) {
                if (child.pageIndex != parent.pageIndex) continue;
                if (child.domId == parent.domId) continue;
                if (!sourceSetContainsAll(parent.sourceObjectIds, child.sourceObjectIds)) continue;
                plans.set(i, child.withVisualAction(VisualAction.DROP_VISUAL,
                        "floating_child_owned_by_inline_parent"));
                break;
            }
        }
    }

    private void resolveFloatingPageObjectsOwnedByInlineHwpxText() {
        List<ObjectPlan> inlineHwpxOwnedPlans = new ArrayList<>();
        for (ObjectPlan plan : plans) {
            if (plan == null) continue;
            if (plan.placement != Placement.INLINE) continue;
            if (plan.visualAction != VisualAction.DROP_VISUAL) continue;
            if (!safe(plan.kind).contains("inline_object")) continue;
            if (!"owned_by_hwpx_text_frame".equals(plan.reason)
                    && !"inline_parent_contains_hwpx_text_sources".equals(plan.reason)) {
                continue;
            }
            inlineHwpxOwnedPlans.add(plan);
        }
        if (inlineHwpxOwnedPlans.isEmpty()) return;

        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!isVisibleRenderedVisual(plan)) continue;
            if (plan.placement != Placement.FLOATING) continue;
            if (!isTextHiddenContainerRender(plan)) continue;
            RenderedGroup rendered = renderedGroupForPlan(plan);
            if (rendered != null && hasIndependentContentVisualBesideOwnedText(rendered)) continue;
            if (!isOwnedByInlineHwpxTextSource(plan, inlineHwpxOwnedPlans)) continue;
            plans.set(i, plan.withVisualAction(VisualAction.DROP_VISUAL,
                    "floating_page_object_owned_by_inline_hwpx_text"));
        }
    }

    private static boolean isOwnedByInlineHwpxTextSource(ObjectPlan floating, List<ObjectPlan> inlineOwners) {
        if (floating == null || inlineOwners == null || inlineOwners.isEmpty()) return false;
        for (ObjectPlan inline : inlineOwners) {
            if (inline == null) continue;
            if (floating.pageIndex != inline.pageIndex) continue;
            if (floating.domId == inline.domId) return true;
            if (sourceSetContainsAll(inline.sourceObjectIds, floating.sourceObjectIds)) return true;
        }
        return false;
    }

    private static boolean isTextHiddenContainerRender(ObjectPlan plan) {
        String reason = safe(plan != null ? plan.reason : null);
        return reason.contains("mixed_group_text_hidden")
                || reason.contains("complex_graphic_text_hidden")
                || reason.contains("inline_text_hidden")
                || reason.contains("container_face_shadow_pair");
    }

    private void resolveInlineCompositeHwpxTextParents() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!isVisibleRenderedVisual(plan)) continue;
            if (plan.placement != Placement.INLINE) continue;
            if (plan.visualAction != VisualAction.PLACE_INLINE_PNG) continue;
            if (plan.textAction == TextAction.OWNED_BY_PNG) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (plan.sourceObjectIds == null || plan.sourceObjectIds.length <= 1) continue;
            if (!hasInlineCompositeHwpxTextSignal(plan)) continue;
            plans.set(i, plan
                    .withTextAction(TextAction.DROP_TEXT)
                    .withOwnedTextFrameIds(new int[0])
                    .withVisualAction(VisualAction.DROP_VISUAL,
                            "inline_composite_requires_complete_png_text_owner")
                    .withDescendantVisualObjectIds(new int[0]));
        }
    }

    private boolean hasInlineCompositeHwpxTextSignal(ObjectPlan plan) {
        if (containsHwpxOwnedTextFrameSource(plan)) return true;
        RenderedGroup rg = renderedGroupForPlan(plan);
        if (rg == null) return false;
        return hasSemanticEditableTextOwnerSignal(rg)
                && (rg.hasEditableTextHiddenFromPng()
                || hasEditableTextFrameIds(rg)
                || Boolean.TRUE.equals(rg.containsEditableText()));
    }

    private void resolveTextShellSharedSources() {
        Map<String, Boolean> visibleNonShellSources = new HashMap<>();
        for (ObjectPlan plan : plans) {
            if (!plan.hasVisibleVisual()) continue;
            if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL) continue;
            for (int sourceId : visualSourceIds(plan)) {
                visibleNonShellSources.put(pageSourceKey(plan.pageIndex, sourceId), Boolean.TRUE);
            }
        }
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            List<Integer> retained = new ArrayList<>();
            boolean changed = false;
            for (int sourceId : visualSourceIds(plan)) {
                boolean sourceOwnedByNonShell = Boolean.TRUE.equals(
                        visibleNonShellSources.get(pageSourceKey(plan.pageIndex, sourceId)));
                if (sourceOwnedByNonShell && sourceId != plan.domId) {
                    changed = true;
                    continue;
                }
                retained.add(sourceId);
            }
            if (!changed) continue;
            plans.set(i, plan.withVisualSourceObjectIds(toIntArray(retained)));
        }
    }

    private void resolveVisualBackdropClusterSources() {
        List<ObjectPlan> clusters = new ArrayList<>();
        for (ObjectPlan plan : plans) {
            if (!isVisualBackdropCluster(plan)) continue;
            clusters.add(plan);
        }
        if (clusters.isEmpty()) return;

        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan child = plans.get(i);
            if (!isVisibleRenderedVisual(child)) continue;
            if (isVisualBackdropCluster(child)) continue;
            if ("text_frame".equals(child.kind)) continue;
            if (child.visualLayer == VisualLayer.CONTENT_VISUAL) continue;
            if (child.visualAction == VisualAction.PLACE_TEXT_SHELL) continue;
            for (ObjectPlan cluster : clusters) {
                if (child.pageIndex != cluster.pageIndex) continue;
                if (!sharesAnySource(cluster, child)) continue;
                if (!boundsMostlyOverlap(cluster.bounds, child.bounds, 0.20)
                        && !boundsContains(cluster.bounds, child.bounds, 3.0)) {
                    continue;
                }
                plans.set(i, child.withVisualAction(VisualAction.DROP_VISUAL,
                        "owned_by_visual_backdrop_cluster"));
                break;
            }
        }
    }

    /**
     * Source ownership policy: when a large composite render and a smaller shell/decorative render
     * must both stay visible, do not let both plans claim the same child source slot.
     * Keep both visuals; trim the child-owned source ids from the composite parent.
     */
    private void normalizeCompositeParentChildSourceSlots() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan parent = plans.get(i);
            if (!isCompositeSourceParent(parent)) continue;
            for (int j = 0; j < plans.size(); j++) {
                if (i == j) continue;
                ObjectPlan child = plans.get(j);
                if (!isVisibleRenderedVisual(child)) continue;
                if (child.pageIndex != parent.pageIndex) continue;
                if (!shouldSplitChildSourceSlotFromComposite(parent, child)) continue;
                ObjectPlan retainedParent = parent.withVisualSourceObjectIds(
                        withoutChildVisualSources(parent, child));
                if (visualSourceIds(retainedParent).length != visualSourceIds(parent).length) {
                    plans.set(i, retainedParent);
                    parent = retainedParent;
                }
            }
        }
    }

    private void resolveGraphicOnlyAtomicRootDescendantVisuals() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan parent = plans.get(i);
            if (!isVisibleGraphicOnlyAtomicRoot(parent)) continue;
            for (int j = 0; j < plans.size(); j++) {
                if (i == j) continue;
                ObjectPlan child = plans.get(j);
                if (!isVisibleRenderedVisual(child)) continue;
                if (child.pageIndex != parent.pageIndex) continue;
                if (!isStrictChildPlan(parent, child)) continue;
                if (!hasSourceParentRelation(parent, child)
                        && !containsAll(parent.sourceObjectIds, child.sourceObjectIds)) {
                    continue;
                }
                plans.set(j, child.withVisualAction(VisualAction.DROP_VISUAL,
                        "owned_by_graphic_only_atomic_root"));
            }
        }
    }

    private boolean isVisibleGraphicOnlyAtomicRoot(ObjectPlan plan) {
        if (!isVisibleRenderedVisual(plan)) return false;
        RenderedGroup rg = renderedGroupForPlan(plan);
        return isGraphicOnlyAtomicObject(rg);
    }

    private boolean isCompositeSourceParent(ObjectPlan plan) {
        if (!isVisibleRenderedVisual(plan)) return false;
        if (plan.sourceObjectIds == null || plan.sourceObjectIds.length <= 1) return false;
        if (plan.visualAction != VisualAction.PLACE_FLOATING_PNG
                && plan.visualAction != VisualAction.PLACE_INLINE_PNG
                && plan.visualAction != VisualAction.PLACE_TEXT_SHELL) {
            return false;
        }
        String reason = safe(plan.reason);
        return reason.contains("complex_graphic")
                || reason.contains("inline_graphic")
                || reason.contains("text_hidden")
                || reason.contains("composite")
                || reason.contains("group");
    }

    private boolean shouldSplitChildSourceSlotFromComposite(ObjectPlan parent, ObjectPlan child) {
        if (parent == null || child == null) return false;
        if (parent.domId == child.domId) return false;
        if (child.sourceObjectIds == null || child.sourceObjectIds.length == 0) return false;
        if (!sharesAnySource(parent, child)) return false;
        boolean sourceParentRelation = hasSourceParentRelation(parent, child);
        if (!sourceParentRelation
                && !containsAnyDirectSource(parent.sourceObjectIds, child.sourceObjectIds)) {
            return false;
        }
        if (!sourceParentRelation
                && !boundsMostlyOverlap(parent.bounds, child.bounds, 0.05)
                && !boundsContains(parent.bounds, child.bounds, 4.0)) {
            return false;
        }
        if (isNestedCompositeVisualChild(child)) return true;
        if (isBackgroundParentWithContentChild(parent, child)) return true;
        if (child.visualPolicyLayer() == PolicyLayer.DECORATION) return true;
        return child.visualAction == VisualAction.PLACE_TEXT_SHELL
                || child.visualLayer == VisualLayer.LABEL_BACKDROP
                || child.visualLayer == VisualLayer.LABEL_OVERLAY_BACKDROP
                || child.visualLayer == VisualLayer.CONTAINER_OUTLINE
                || child.visualLayer == VisualLayer.FOREGROUND_MASK;
    }

    private static boolean isNestedCompositeVisualChild(ObjectPlan child) {
        if (child == null) return false;
        if (child.placement != Placement.FLOATING) return false;
        if (child.visualAction != VisualAction.PLACE_FLOATING_PNG
                && child.visualAction != VisualAction.PLACE_TEXT_SHELL) {
            return false;
        }
        String reason = safe(child.reason);
        return reason.contains("group")
                || reason.contains("composite")
                || reason.contains("complex_graphic")
                || reason.contains("text_hidden");
    }

    private static boolean containsAnyDirectSource(int[] parentSources, int[] childSources) {
        if (parentSources == null || childSources == null) return false;
        for (int childSource : childSources) {
            if (contains(parentSources, childSource)) return true;
        }
        return false;
    }

    private void resolveCoveredParentGroups() {
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < plans.size(); i++) {
                ObjectPlan parent = plans.get(i);
                if (!isDroppableParentGroup(parent)) continue;
                LinkedHashSet<Integer> childSources = new LinkedHashSet<>();
                List<ObjectPlan> children = new ArrayList<>();
                int childCount = 0;
                for (int j = 0; j < plans.size(); j++) {
                    if (i == j) continue;
                    ObjectPlan child = plans.get(j);
                    if (!isVisibleRenderedVisual(child)) continue;
                    if (child.pageIndex != parent.pageIndex) continue;
                    if (!isStrictChildPlan(parent, child)) continue;
                    childCount++;
                    children.add(child);
                    for (int sourceId : child.sourceObjectIds) {
                        childSources.add(sourceId);
                    }
                }
                if (childCount == 0) continue;
                if (!coversAllParentSources(parent, childSources)) continue;
                if (parentHasVisiblePixelsOutsideChildren(parent, children)) continue;
                plans.set(i, parent.withVisualAction(VisualAction.DROP_VISUAL, parent.reason));
                changed = true;
            }
        } while (changed);
    }

    private void resolveParentGroupsWithMoreSpecificChildren() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan parent = plans.get(i);
            if (!isDroppableParentGroup(parent)) continue;
            if (parent.visualAction != VisualAction.PLACE_FLOATING_PNG) continue;
            for (int j = 0; j < plans.size(); j++) {
                if (i == j) continue;
                ObjectPlan child = plans.get(j);
                if (!isVisibleRenderedVisual(child)) continue;
                if (child.visualAction == VisualAction.PLACE_TEXT_SHELL) continue;
                if (child.pageIndex != parent.pageIndex) continue;
                if (!isStrictChildPlan(parent, child)) continue;
                if (isBackgroundParentWithContentChild(parent, child)) {
                    ObjectPlan retainedParent = parent.withVisualSourceObjectIds(
                            withoutChildVisualSources(parent, child));
                    if (visualSourceIds(retainedParent).length != visualSourceIds(parent).length) {
                        plans.set(i, retainedParent);
                        parent = retainedParent;
                    }
                    continue;
                }
                if (parentSelfContributesVisibleVisual(parent)
                        || parentHasVisiblePixelsOutsideChildren(parent, Arrays.asList(child))
                        || shouldPreferCompositeParent(parent, child)) {
                    ObjectPlan retainedParent = parent;
                    if (parent.visualLayer == VisualLayer.CONTAINER_OUTLINE
                            && parentHasPaperBackdrop(parent)) {
                        retainedParent = parent.withVisualLayer(VisualLayer.CONTAINER_BACKDROP);
                        plans.set(i, retainedParent);
                    }
                    plans.set(j, child.withVisualAction(VisualAction.DROP_VISUAL, child.reason));
                    parent = retainedParent;
                    continue;
                }
                plans.set(i, parent.withVisualAction(VisualAction.DROP_VISUAL, parent.reason));
                break;
            }
        }
    }

    private void resolveOverlappingImageExportDuplicates() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan a = plans.get(i);
            if (!isImageExportVisual(a)) continue;
            for (int j = i + 1; j < plans.size(); j++) {
                ObjectPlan b = plans.get(j);
                if (!isImageExportVisual(b)) continue;
                if (a.pageIndex != b.pageIndex) continue;
                if (!boundsMostlyOverlap(a.bounds, b.bounds, 0.78)) continue;
                if (isLargeLayeredImageExportPair(a, b)) {
                    a = markLayeredImageExportPairAsContainerBackdrops(i, j, a, b);
                    continue;
                }
                if (!isLikelyDuplicateImageExport(a, b)) {
                    continue;
                }
                if (isBackdropAndContentImagePair(a, b)) continue;
                double aScore = visualInkScore(a);
                double bScore = visualInkScore(b);
                if (Math.abs(aScore - bScore) < 0.004) continue;
                if (aScore > bScore) {
                    plans.set(j, b.withVisualAction(VisualAction.DROP_VISUAL, "duplicate_image_export"));
                } else {
                    plans.set(i, a.withVisualAction(VisualAction.DROP_VISUAL, "duplicate_image_export"));
                    break;
                }
            }
        }
    }

    private void resolveLargeLayeredImageExportBackdrops() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan a = plans.get(i);
            if (!isImageExportVisual(a)) continue;
            for (int j = i + 1; j < plans.size(); j++) {
                ObjectPlan b = plans.get(j);
                if (!isImageExportVisual(b)) continue;
                if (!isLargeLayeredImageExportPair(a, b)) continue;
                a = markLayeredImageExportPairAsContainerBackdrops(i, j, a, b);
            }
        }
    }

    private ObjectPlan markLayeredImageExportPairAsContainerBackdrops(
            int aIndex,
            int bIndex,
            ObjectPlan a,
            ObjectPlan b) {
        ObjectPlan layerA = a.withVisualLayer(VisualLayer.CONTAINER_BACKDROP);
        ObjectPlan layerB = b.withVisualLayer(VisualLayer.CONTAINER_BACKDROP);
        plans.set(aIndex, layerA);
        plans.set(bIndex, layerB);
        return layerA;
    }

    private void resolveClippedDecorationParents() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan parent = plans.get(i);
            if (!isRenderedClippingParentCandidate(parent)) continue;
            for (int j = 0; j < plans.size(); j++) {
                if (i == j) continue;
                ObjectPlan child = plans.get(j);
                if (!isVisibleRenderedVisual(child)) continue;
                if (child.pageIndex != parent.pageIndex) continue;
                if (!isUnclippedDecorationChild(parent, child)) continue;
                ObjectPlan retainedParent = parent
                        .withVisualAction(VisualAction.PLACE_FLOATING_PNG, parent.reason)
                        .withVisualLayer(VisualLayer.CONTAINER_BACKDROP);
                plans.set(i, retainedParent);
                plans.set(j, child.withVisualAction(VisualAction.DROP_VISUAL,
                        "owned_by_clipped_decoration_parent"));
                parent = retainedParent;
            }
        }
    }

    private boolean isRenderedClippingParentCandidate(ObjectPlan plan) {
        if (plan == null || plan.renderId == null || plan.file == null || plan.file.isBlank()) return false;
        if (plan.placement != Placement.FLOATING) return false;
        if (!plan.kind.startsWith("rendered_floating_item:")) return false;
        String reason = safe(plan.reason);
        if (!reason.contains("complex_graphic")) return false;
        ResolvedPageItem item = data.getPageItem(String.valueOf(plan.domId));
        if (item == null) return false;
        String type = safe(item.type());
        return "Polygon".equals(type) || "Rectangle".equals(type) || "Oval".equals(type);
    }

    private boolean isUnclippedDecorationChild(ObjectPlan parent, ObjectPlan child) {
        if (parent == null || child == null) return false;
        if (!safe(child.reason).contains("decoration")) return false;
        if (!isStrictChildPlan(parent, child)) return false;
        if (!hasSourceParentRelation(parent, child)) return false;
        double parentArea = area(parent.bounds);
        double childArea = area(child.bounds);
        if (parentArea <= 0.0 || childArea <= 0.0) return false;
        return childArea > parentArea * 1.35
                || !boundsContains(parent.bounds, child.bounds, 2.0);
    }

    private boolean hasSourceParentRelation(ObjectPlan parent, ObjectPlan child) {
        if (parent == null || child == null || data == null) return false;
        for (int sourceId : child.sourceObjectIds) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item == null || item.parentId() == null) continue;
            if (item.parentId().equals(String.valueOf(parent.domId))) {
                return true;
            }
            ResolvedPageItem directParent = data.getPageItem(item.parentId());
            if (directParent != null && String.valueOf(parent.domId).equals(directParent.parentId())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSourceDescendantRelation(ObjectPlan ancestor, ObjectPlan descendant) {
        if (ancestor == null || descendant == null || data == null) return false;
        LinkedHashSet<Integer> descendantIds = new LinkedHashSet<>();
        descendantIds.add(descendant.domId);
        addAll(descendant.sourceObjectIds, descendantIds);
        addAll(visualSourceIds(descendant), descendantIds);
        for (int sourceId : descendantIds) {
            if (sourceDescendsFromPlan(sourceId, ancestor)) return true;
        }
        return false;
    }

    private boolean sourceDescendsFromPlan(int sourceId, ObjectPlan ancestor) {
        if (sourceId <= 0 || ancestor == null || data == null) return false;
        LinkedHashSet<Integer> ancestorIds = new LinkedHashSet<>();
        ancestorIds.add(ancestor.domId);
        addAll(ancestor.sourceObjectIds, ancestorIds);
        addAll(visualSourceIds(ancestor), ancestorIds);
        Set<Integer> visited = new HashSet<>();
        int current = sourceId;
        for (int depth = 0; depth < 64; depth++) {
            if (!visited.add(current)) return false;
            ResolvedPageItem item = data.getPageItem(String.valueOf(current));
            if (item == null || item.parentId() == null || item.parentId().isBlank()) {
                return false;
            }
            int parentId = parseInt(item.parentId(), -1);
            if (parentId <= 0) return false;
            if (ancestorIds.contains(parentId)) return true;
            current = parentId;
        }
        return false;
    }

    private void resolveLayeredContainerFaces() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan face = plans.get(i);
            if (!isVisibleRenderedVisual(face)) continue;
            if (isVectorShapeFramePlan(face)) continue;
            if (isComplexGraphicFramePlan(face)) continue;
            if (face.placement != Placement.FLOATING) continue;
            if (!isPaperLikeContainerFace(face)) continue;
            if (isLineLikePlan(face)) continue;

            for (int j = 0; j < plans.size(); j++) {
                if (i == j) continue;
                ObjectPlan shadow = plans.get(j);
                if (!isVisibleRenderedVisual(shadow)) continue;
                if (isVectorShapeFramePlan(shadow)) continue;
                if (isComplexGraphicFramePlan(shadow)) continue;
                if (shadow.placement != Placement.FLOATING) continue;
                if (shadow.pageIndex != face.pageIndex) continue;
                if (!isColoredContainerShadow(shadow)) continue;
                if (!sameContainerFootprint(face, shadow)) continue;
                ObjectPlan textShellOwner = chooseTextShellContainerOwner(face, shadow);
                if (textShellOwner != null) {
                    boolean faceOwns = textShellOwner == face;
                    int ownerIndex = faceOwns ? i : j;
                    int duplicateIndex = faceOwns ? j : i;
                    ObjectPlan duplicate = faceOwns ? shadow : face;
                    if (!textShellVisuallyOwnsDuplicate(textShellOwner, duplicate)) {
                        continue;
                    }
                    plans.set(duplicateIndex, duplicate.withVisualAction(
                            VisualAction.DROP_VISUAL,
                            "text_owned_container_shell_duplicate_child"));
                    break;
                }

                ObjectPlan originalOwner = chooseOriginalContainerFaceOwner(face, shadow);
                if (originalOwner != null) {
                    int ownerIndex = originalOwner == face ? i : j;
                    int duplicateIndex = originalOwner == face ? j : i;
                    ObjectPlan duplicate = originalOwner == face ? shadow : face;
                    ObjectPlan retained = originalOwner
                            .withVisualLayer(VisualLayer.CONTAINER_BACKDROP);
                    if (retained.visualAction == VisualAction.PLACE_TEXT_SHELL) {
                        VisualAction convertedVisual = retained.placement == Placement.INLINE
                                ? VisualAction.PLACE_INLINE_PNG
                                : VisualAction.PLACE_FLOATING_PNG;
                        retained = retained.withVisualAction(convertedVisual, "container_face_shadow_original_owner");
                    }
                    plans.set(ownerIndex, retained);
                    plans.set(duplicateIndex, duplicate.withVisualAction(
                            VisualAction.DROP_VISUAL,
                            "container_face_shadow_duplicate_child"));
                } else {
                    plans.set(i, face.withVisualLayer(VisualLayer.CONTAINER_FACE));
                    plans.set(j, shadow.withVisualLayer(VisualLayer.CONTAINER_FACE));
                }
                break;
            }
        }
    }

    private static boolean isVectorShapeFramePlan(ObjectPlan plan) {
        if (plan == null) return false;
        return safe(plan.kind).contains("pass.vector_shape_frames")
                || safe(plan.sourceBundleKey).contains("pass.vector_shape_frames");
    }

    private static boolean isComplexGraphicFramePlan(ObjectPlan plan) {
        if (plan == null) return false;
        return safe(plan.kind).contains("pass.complex_graphic_frames")
                || safe(plan.kind).contains("rendered_graphic_frame")
                || safe(plan.file).contains("rendered_frames/graphic_");
    }

    private static boolean textShellVisuallyOwnsDuplicate(ObjectPlan owner, ObjectPlan duplicate) {
        if (owner == null || duplicate == null) return false;
        int[] ownerVisualSources = visualSourceIds(owner);
        int[] duplicateVisualSources = visualSourceIds(duplicate);
        if (ownerVisualSources.length == 0 || duplicateVisualSources.length == 0) return false;
        if (containsAll(ownerVisualSources, duplicateVisualSources)) return true;
        return duplicate.domId >= 0 && contains(ownerVisualSources, duplicate.domId);
    }

    private ObjectPlan chooseOriginalContainerFaceOwner(ObjectPlan a, ObjectPlan b) {
        if (a == null || b == null) return null;
        if (isEditableVisualShellPlan(a) && !isEditableVisualShellPlan(b)) return a;
        if (isEditableVisualShellPlan(b) && !isEditableVisualShellPlan(a)) return b;
        if (isEditableVisualShellPlan(a) && isEditableVisualShellPlan(b)) {
            return sourceCount(a) >= sourceCount(b) ? a : b;
        }
        if (sourceCount(a) != sourceCount(b)) {
            return sourceCount(a) > sourceCount(b) ? a : b;
        }
        return null;
    }

    private static boolean isEditableVisualShellPlan(ObjectPlan plan) {
        return plan != null
                && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                && isTextHiddenContainerRender(plan);
    }

    private static int sourceCount(ObjectPlan plan) {
        return plan != null && plan.sourceObjectIds != null ? plan.sourceObjectIds.length : 0;
    }

    private void resolveCompositeBakedChildVisuals() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan parent = plans.get(i);
            if (!isCompleteCompositeVisualOwner(parent)) continue;
            RenderedGroup parentRender = renderedGroupForPlan(parent);
            for (int j = 0; j < plans.size(); j++) {
                if (i == j) continue;
                ObjectPlan child = plans.get(j);
                if (!isVisibleRenderedVisual(child)) continue;
                if (child.pageIndex != parent.pageIndex) continue;
                if (isProtectedCompositeChildVisual(parent, child)) continue;
                if (!isBakedIntoCompositeParent(parent, parentRender, child)) continue;
                plans.set(j, child.withVisualAction(
                        VisualAction.DROP_VISUAL,
                        "baked_into_composite_parent"));
            }
        }
    }

    private boolean isCompleteCompositeVisualOwner(ObjectPlan plan) {
        if (!isVisibleRenderedVisual(plan)) return false;
        if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.sourceObjectIds == null || plan.sourceObjectIds.length <= 1) return false;
        String reason = safe(plan.reason);
        return reason.contains("mixed_group_text_hidden")
                || reason.contains("complex_graphic_text_hidden")
                || reason.contains("image_group_text_hidden")
                || reason.contains("composite")
                || reason.contains("group");
    }

    private boolean isProtectedCompositeChildVisual(ObjectPlan parent, ObjectPlan child) {
        if (child == null) return true;
        if (isDroppableBakedCompositeDecorationChild(parent, child)) return false;
        if (child.visualAction == VisualAction.PLACE_TEXT_SHELL
                && !isDroppableBakedVisualOnlyTextShell(parent, child)) {
            return true;
        }
        if (isLabelBackdropGroupPlan(child)) return true;
        return child.visualLayer == VisualLayer.LABEL_BACKDROP
                || child.visualLayer == VisualLayer.LABEL_OVERLAY_BACKDROP;
    }

    private static boolean isDroppableBakedCompositeDecorationChild(ObjectPlan parent, ObjectPlan child) {
        if (parent == null || child == null) return false;
        if (!isCompleteCompositeReason(parent.reason)) return false;
        if (!child.hasVisibleVisual()) return false;
        if (child.textAction != TextAction.DROP_TEXT) return false;
        if (child.ownedTextFrameIds != null && child.ownedTextFrameIds.length > 0) return false;
        PolicyLayer layer = child.visualPolicyLayer();
        return layer == PolicyLayer.BACKGROUND || layer == PolicyLayer.DECORATION;
    }

    private boolean isDroppableBakedVisualOnlyTextShell(ObjectPlan parent, ObjectPlan child) {
        if (parent == null || child == null) return false;
        if (isDirectChildShellSlotPlan(child)) return false;
        if (child.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (child.textAction != TextAction.DROP_TEXT) return false;
        if (child.ownedTextFrameIds != null && child.ownedTextFrameIds.length > 0) return false;
        return isCompleteCompositeVisualOwner(parent)
                && compositeParentVisuallyOwnsChildSlot(parent, child)
                && !hasDistinctChildShellSlotSignal(child);
    }

    private static boolean isCompleteCompositeReason(String reason) {
        String value = safe(reason);
        return value.contains("mixed_group_text_hidden")
                || value.contains("complex_graphic_text_hidden")
                || value.contains("image_group_text_hidden")
                || value.contains("composite")
                || value.contains("group");
    }

    private static boolean compositeParentVisuallyOwnsChildSlot(ObjectPlan parent, ObjectPlan child) {
        if (parent == null || child == null) return false;
        int[] parentVisualSources = visualSourceIds(parent);
        if (parentVisualSources.length == 0) return false;
        return contains(parentVisualSources, child.domId)
                || containsAny(parentVisualSources, child.sourceObjectIds)
                || containsAny(parentVisualSources, visualSourceIds(child));
    }

    private boolean isBakedIntoCompositeParent(
            ObjectPlan parent, RenderedGroup parentRender, ObjectPlan child) {
        if (parent == null || child == null) return false;
        if (parent.domId == child.domId) return false;
        if (parent.bounds == null || child.bounds == null) return false;
        double parentArea = area(parent.bounds);
        double childArea = area(child.bounds);
        if (parentArea <= 0.0 || childArea <= 0.0 || parentArea <= childArea * 1.05) {
            return false;
        }
        if (!boundsContains(parent.bounds, child.bounds, 4.0)
                && !boundsMostlyOverlap(parent.bounds, child.bounds, 0.20)) {
            return false;
        }
        if (contains(parent.sourceObjectIds, child.domId)) return true;
        if (sharesAnySource(parent, child)) return true;
        RenderedGroup childRender = renderedGroupForPlan(child);
        if (childRender != null) {
            if (contains(parent.sourceObjectIds, childRender.id())) return true;
            if (containsAny(parent.sourceObjectIds, childRender.childIds())) return true;
            if (containsAny(parent.sourceObjectIds, childRender.sourceObjectIds())) return true;
        }
        if (parentRender != null && parentRender.childIds() != null) {
            if (contains(parentRender.childIds(), child.domId)) return true;
            return childRender != null && contains(parentRender.childIds(), childRender.id());
        }
        return false;
    }

    private static boolean isHwpxTextOwnedContainerShell(ObjectPlan plan) {
        return plan != null
                && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                && plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                && plan.visualLayer == VisualLayer.CONTAINER_BACKDROP;
    }

    private RenderedGroup renderedGroupForPlan(ObjectPlan plan) {
        if (plan == null || plan.renderId == null) return null;
        RenderedGroup best = null;
        int bestScore = Integer.MIN_VALUE;
        for (RenderedGroup rg : renderedGroupsForPageId(plan.pageIndex, plan.renderId.intValue())) {
            if (rg == null) continue;
            int score = 0;
            if (plan.candidateId != null && !plan.candidateId.isEmpty()
                    && plan.candidateId.equals(rg.candidateId())) {
                score += 32;
            }
            if (plan.file != null && plan.file.equals(rg.file())) score += 16;
            if (plan.placement == placementOf(rg)) score += 8;
            if (plan.reason != null && plan.reason.equals(rg.reason())) score += 4;
            if (plan.bounds != null && rg.bounds() != null && overlapRatio(plan.bounds, rg.bounds()) > 0.95) {
                score += 2;
            }
            if (score > bestScore) {
                best = rg;
                bestScore = score;
            }
        }
        return best;
    }

    private static int[] mergeSourceIds(int[] a, int[] b) {
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        if (a != null) {
            for (int id : a) ids.add(id);
        }
        if (b != null) {
            for (int id : b) ids.add(id);
        }
        int[] out = new int[ids.size()];
        int i = 0;
        for (Integer id : ids) out[i++] = id;
        Arrays.sort(out);
        return out;
    }

    private boolean isColoredContainerShadow(ObjectPlan plan) {
        if (plan == null || plan.bounds == null || plan.bounds.length < 4) return false;
        if (isLineLikePlan(plan)) return false;
        if (area(plan.bounds) < 800.0) return false;
        for (int sourceId : plan.sourceObjectIds) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (isFilledContainerBoxItem(item)) return true;
        }
        return false;
    }

    private static boolean sameContainerFootprint(ObjectPlan a, ObjectPlan b) {
        if (a == null || b == null) return false;
        if (a.bounds == null || b.bounds == null || a.bounds.length < 4 || b.bounds.length < 4) return false;
        double aArea = area(a.bounds);
        double bArea = area(b.bounds);
        if (aArea <= 0.0 || bArea <= 0.0) return false;
        double ratio = Math.min(aArea, bArea) / Math.max(aArea, bArea);
        if (ratio < 0.72) return false;
        if (!boundsMostlyOverlap(a.bounds, b.bounds, 0.82)) return false;
        double aCy = (a.bounds[0] + a.bounds[2]) / 2.0;
        double aCx = (a.bounds[1] + a.bounds[3]) / 2.0;
        double bCy = (b.bounds[0] + b.bounds[2]) / 2.0;
        double bCx = (b.bounds[1] + b.bounds[3]) / 2.0;
        double h = Math.max(1.0, Math.min(Math.abs(a.bounds[2] - a.bounds[0]), Math.abs(b.bounds[2] - b.bounds[0])));
        double w = Math.max(1.0, Math.min(Math.abs(a.bounds[3] - a.bounds[1]), Math.abs(b.bounds[3] - b.bounds[1])));
        return Math.abs(aCy - bCy) <= h * 0.08 + 3.0
                && Math.abs(aCx - bCx) <= w * 0.08 + 3.0;
    }

    private ObjectPlan chooseTextShellContainerOwner(ObjectPlan a, ObjectPlan b) {
        if (a == null || b == null) return null;
        boolean aShell = isHwpxTextOwnedShell(a);
        boolean bShell = isHwpxTextOwnedShell(b);
        if (!aShell && !bShell) return null;
        if (aShell && !bShell) return a;
        if (!aShell) return b;
        boolean aContainsB = containsAllSources(a, b) || contains(a.sourceObjectIds, b.domId);
        boolean bContainsA = containsAllSources(b, a) || contains(b.sourceObjectIds, a.domId);
        if (aContainsB && !bContainsA) return a;
        if (bContainsA && !aContainsB) return b;
        double aArea = area(a.bounds);
        double bArea = area(b.bounds);
        if (aArea > bArea * 1.05) return a;
        if (bArea > aArea * 1.05) return b;
        if (a.visualLayer == VisualLayer.LABEL_BACKDROP && b.visualLayer != VisualLayer.LABEL_BACKDROP) {
            return a;
        }
        if (b.visualLayer == VisualLayer.LABEL_BACKDROP && a.visualLayer != VisualLayer.LABEL_BACKDROP) {
            return b;
        }
        return a.zOrder <= b.zOrder ? a : b;
    }

    private static boolean isHwpxTextOwnedShell(ObjectPlan plan) {
        return plan != null
                && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                && plan.visualAction == VisualAction.PLACE_TEXT_SHELL;
    }

    private void resolveParentTextShellDescendantVisuals() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan parent = plans.get(i);
            if (!isParentTextShellOwner(parent)) continue;
            LinkedHashSet<Integer> descendants = new LinkedHashSet<>();
            for (int j = 0; j < plans.size(); j++) {
                if (i == j) continue;
                ObjectPlan child = plans.get(j);
                if (!isVisibleRenderedVisual(child)) continue;
                if (child.pageIndex != parent.pageIndex) continue;
                if (!parentTextShellMayOwnDescendantVisual(parent, child)) continue;
                if (isImageBackedSourcePositionedInlineTextShell(parent, child)) continue;
                if (inlineTextShellOwnsMoreSpecificText(child, parent)) continue;
                if (isStandaloneGraphicOnlyInlineObjectPlan(child)) continue;
                if (!isDescendantVisualOfParentTextShell(parent, child)) continue;
                if (childOwnsDistinctShellSlot(parent, child)) continue;
                collectDescendantVisualIds(parent, child, descendants);
                ObjectPlan dropped = child.withVisualAction(VisualAction.DROP_VISUAL,
                        "owned_by_parent_text_shell");
                if (!"text_frame".equals(dropped.kind)) {
                    dropped = dropped.withTextAction(TextAction.DROP_TEXT);
                }
                plans.set(j, dropped);
            }
            if (!descendants.isEmpty()) {
                plans.set(i, parent.withDescendantVisualObjectIds(toIntArray(descendants)));
            }
        }
    }

    private static boolean isParentTextShellOwner(ObjectPlan plan) {
        return plan != null
                && plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                && plan.hasVisibleVisual()
                && plan.ownedTextFrameIds != null
                && plan.ownedTextFrameIds.length > 0;
    }

    private boolean isDescendantVisualOfParentTextShell(ObjectPlan parent, ObjectPlan child) {
        if (parent == null || child == null) return false;
        if ("text_frame".equals(child.kind)) return false;
        if (parent.domId == child.domId) return false;
        if (isSameRenderPlan(parent, child)) return false;
        if (isAncestorOrBroaderCompositeOfTextShell(parent, child)) return false;
        if (child.visualAction == VisualAction.PLACE_TEXT_SHELL
                && !ownedTextFramesCoveredBy(parent, child)) {
            return false;
        }
        if (isStrictChildPlan(parent, child)) return true;
        if (!sharesAnySource(parent, child) && !containsAny(parent.sourceObjectIds, visualSourceIds(child))) {
            return false;
        }
        if (parent.bounds == null || child.bounds == null) return false;
        return boundsContains(parent.bounds, child.bounds, 4.0)
                || boundsMostlyOverlap(parent.bounds, child.bounds, 0.70);
    }

    private static boolean isAncestorOrBroaderCompositeOfTextShell(ObjectPlan parent, ObjectPlan child) {
        if (parent == null || child == null) return false;
        if (parent.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (parent.bounds == null || child.bounds == null) return false;
        double parentArea = area(parent.bounds);
        double childArea = area(child.bounds);
        if (parentArea <= 0.0 || childArea <= 0.0) return false;

        boolean childContainsParentSource = contains(child.sourceObjectIds, parent.domId)
                || containsAll(child.sourceObjectIds, parent.sourceObjectIds);
        boolean broaderThanParent = childArea > parentArea * 1.15
                || boundsContains(child.bounds, parent.bounds, 4.0);
        return childContainsParentSource && broaderThanParent;
    }

    private static boolean ownedTextFramesCoveredBy(ObjectPlan parent, ObjectPlan child) {
        if (parent == null || child == null) return false;
        if (child.ownedTextFrameIds == null || child.ownedTextFrameIds.length == 0) return true;
        for (int id : child.ownedTextFrameIds) {
            if (!contains(parent.ownedTextFrameIds, id)) return false;
        }
        return true;
    }

    private static boolean isSameRenderPlan(ObjectPlan a, ObjectPlan b) {
        if (a == null || b == null) return false;
        if (a.renderId == null || b.renderId == null) return false;
        if (!a.renderId.equals(b.renderId)) return false;
        if (a.file == null || b.file == null) return true;
        return a.file.equals(b.file);
    }

    private void collectDescendantVisualIds(
            ObjectPlan parent,
            ObjectPlan child,
            LinkedHashSet<Integer> descendants) {
        if (child == null || descendants == null) return;
        if (child.domId >= 0) descendants.add(child.domId);
        if (child.renderId != null && child.renderId >= 0) descendants.add(child.renderId);
        int[] parentVisualSources = visualSourceIds(parent);
        for (int sourceId : visualSourceIds(child)) {
            if (contains(child.ownedTextFrameIds, sourceId)) continue;
            if (!contains(parentVisualSources, sourceId)) continue;
            if (sourceId >= 0) descendants.add(sourceId);
        }
    }

    private static boolean containsAllSources(ObjectPlan owner, ObjectPlan child) {
        if (owner == null || child == null || child.sourceObjectIds == null) return false;
        for (int sourceId : child.sourceObjectIds) {
            if (!contains(owner.sourceObjectIds, sourceId)) return false;
        }
        return true;
    }

    private void resolveNestedTextShellSources() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan parent = plans.get(i);
            if (parent.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            LinkedHashSet<Integer> childSources = new LinkedHashSet<>();
            for (int j = 0; j < plans.size(); j++) {
                if (i == j) continue;
                ObjectPlan child = plans.get(j);
                if (child.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
                if (child.pageIndex != parent.pageIndex) continue;
                if (!isStrictChildPlan(parent, child)) continue;
                for (int sourceId : visualSourceIds(child)) {
                    childSources.add(sourceId);
                }
            }
            if (childSources.isEmpty()) continue;
            List<Integer> retained = new ArrayList<>();
            boolean changed = false;
            for (int sourceId : visualSourceIds(parent)) {
                if (sourceId != parent.domId && childSources.contains(sourceId)) {
                    changed = true;
                    continue;
                }
                retained.add(sourceId);
            }
            if (!changed) continue;
            plans.set(i, parent.withVisualSourceObjectIds(toIntArray(retained)));
        }
    }

    private void declareAtomicOwnershipRootTextHiddenShellOwners() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!isAtomicOwnershipRootTextHiddenShellPlan(plan)) continue;
            if (isNonCanonicalAtomicObjectPlan(plan)) continue;
            if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL) continue;
            if (hasAlternativeVisibleTextShellOwner(plan)) continue;
            plans.set(i, plan
                    .withTextAction(TextAction.OWNED_BY_HWPX_TEXT)
                    .withVisualAction(VisualAction.PLACE_TEXT_SHELL, plan.reason)
                    .withVisualLayer(textShellVisualLayer(
                            plan,
                            plan.ownedTextFrameIds,
                            VisualLayer.CONTAINER_BACKDROP))
                    .withMaterialization(Materialization.EXTRACTED_PNG_VECTOR));
        }
    }

    private boolean hasAlternativeVisibleTextShellOwner(ObjectPlan shell) {
        if (shell == null) return false;
        for (ObjectPlan candidate : plans) {
            if (candidate == null || candidate == shell) continue;
            if (candidate.pageIndex != shell.pageIndex) continue;
            if (candidate.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (!candidate.hasVisibleVisual()) continue;
            if (!ownedTextFramesCoveredBy(candidate, shell)) continue;
            if (!parentTextShellMayOwnDescendantVisual(candidate, shell)) continue;
            if (containsAll(visualSourceIds(candidate), visualSourceIds(shell))) return true;
        }
        return false;
    }

    private static boolean isAtomicOwnershipRootTextHiddenShellPlan(ObjectPlan plan) {
        return plan != null
                && isAtomicOwnershipRootTextHiddenShellReason(plan.reason)
                && safe(plan.kind).contains("page_object")
                && plan.ownedTextFrameIds != null
                && plan.ownedTextFrameIds.length > 0
                && visualSourceIds(plan).length > 0;
    }

    private void declareDirectTextHiddenShellOwners() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!isDirectTextHiddenShellPlan(plan)) continue;
            if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL) continue;
            plans.set(i, plan
                    .withVisualAction(VisualAction.PLACE_TEXT_SHELL, plan.reason)
                    .withVisualLayer(textShellVisualLayerForOwnedTextFrames(
                            plan.ownedTextFrameIds, VisualLayer.LABEL_BACKDROP)));
        }
    }

    private boolean isDirectTextHiddenShellPlan(ObjectPlan plan) {
        if (plan == null || !isRenderedVisualPlan(plan)) return false;
        if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        if (!isDirectInlineTextShellReason(plan.reason)) return false;
        RenderedGroup rendered = renderedGroupForPlan(plan);
        if (rendered == null) return false;
        if (!isEditableVisualShellWithSeparateHwpxText(rendered)) return false;
        if (data.shouldUseCompletePngForSimpleButtonLabel(rendered)
                && !data.shouldUseTextlessShellForAtomicMarkerLabel(rendered)) {
            return false;
        }
        return true;
    }

    private void declareInlineTextShellOwners() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!isInlineTextShellPlan(plan)) continue;
            if (!hasExplicitTextlessShellSignal(plan)) continue;
            if (isNonCanonicalAtomicObjectPlan(plan)) continue;
            if ("owned_by_page_object_channel".equals(plan.reason)) continue;
            if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL) continue;
            if (hasAlternativeVisibleInlineTextShellOwner(plan)) continue;
            plans.set(i, plan.withVisualAction(VisualAction.PLACE_TEXT_SHELL, plan.reason));
        }
    }

    private boolean hasAlternativeVisibleInlineTextShellOwner(ObjectPlan shell) {
        if (shell == null) return false;
        for (ObjectPlan candidate : plans) {
            if (candidate == null || candidate == shell) continue;
            if (candidate.pageIndex != shell.pageIndex) continue;
            if (candidate.placement != Placement.INLINE) continue;
            if (candidate.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (!candidate.hasVisibleVisual()) continue;
            if (!ownedTextFramesCoveredBy(candidate, shell)) continue;
            if (containsAll(visualSourceIds(candidate), visualSourceIds(shell))) return true;
        }
        return false;
    }

    private void normalizeTextShellEditableTextOwnership() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null) continue;
            if (plan.textAction != TextAction.DROP_TEXT) continue;
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (plan.placement != Placement.INLINE) continue;
            if (!safe(plan.kind).contains("inline_object")) continue;
            if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) continue;
            if (!hasExplicitTextlessShellSignal(plan)) continue;
            plans.set(i, plan.withTextAction(TextAction.OWNED_BY_HWPX_TEXT));
        }
    }

    private void dropNonExecutableSimpleButtonTextOwners() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null) continue;
            if (!safe(plan.kind).startsWith("simple_button_label:")) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (plan.visualAction != VisualAction.DROP_VISUAL) continue;
            int[] textFrameIds = textFrameIdsForPlan(plan);
            if (textFrameIds.length == 0) continue;
            if (!allTextFramesHaveVisibleHwpxTextOwner(textFrameIds)) continue;
            plans.set(i, plan
                    .withTextAction(TextAction.DROP_TEXT)
                    .withVisualAction(VisualAction.DROP_VISUAL,
                            "simple_button_text_slot_owned_by_visible_hwpx_text")
                    .withOwnedTextFrameIds(new int[0]));
        }
    }

    private void normalizeRenderedPngTextOwnershipToTextFrames() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (plan.visualAction != VisualAction.PLACE_FLOATING_PNG
                    && plan.visualAction != VisualAction.PLACE_INLINE_PNG) {
                continue;
            }
            if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) continue;
            if (!allOwnedTextFramesHaveSeparateHwpxTextOwner(plan)) continue;
            plans.set(i, plan.withTextAction(TextAction.DROP_TEXT));
        }
    }

    private void normalizeDroppedRenderedTextOwnershipToTextFrames() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null) continue;
            if (!isRenderedVisualPlan(plan)) continue;
            if ("text_frame".equals(plan.kind)) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (plan.visualAction != VisualAction.DROP_VISUAL) continue;
            if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) continue;
            if (!allOwnedTextFramesHaveSeparateHwpxTextOwner(plan)) continue;
            plans.set(i, plan.withTextAction(TextAction.DROP_TEXT));
        }
    }

    private void completeVisibleTextShellRelationsFromSourceIds() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null) continue;
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (!plan.hasVisibleVisual()) continue;
            if (plan.textAction == TextAction.DROP_TEXT
                    && !hasVisibleEditableTextFrameSource(plan)) continue;

            LinkedHashSet<Integer> restored = new LinkedHashSet<>();
            if (plan.ownedTextFrameIds != null) {
                for (int id : plan.ownedTextFrameIds) {
                    if (hasDirectHwpxTextFrameOwner(id) || isVisibleEditableTextFrameSourceId(id)) {
                        restored.add(id);
                    }
                }
            }
            if (plan.sourceObjectIds != null && data != null) {
                for (int sourceId : plan.sourceObjectIds) {
                    if (!isVisibleEditableTextFrameSourceId(sourceId)) continue;
                    restored.add(sourceId);
                }
            }
            if (restored.isEmpty()) continue;
            int[] restoredIds = toIntArray(restored);
            if (sameIntSet(plan.ownedTextFrameIds, restoredIds)) continue;
            plans.set(i, plan.withOwnedTextFrameIds(restoredIds));
        }
    }

    private void declareInlineTextShellTextOwnership() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null) continue;
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (plan.placement != Placement.INLINE) continue;
            if (plan.textAction == TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) continue;
            if (!ownedTextFramePlansRemainHwpxText(plan.ownedTextFrameIds)) continue;
            plans.set(i, plan.withTextAction(TextAction.OWNED_BY_HWPX_TEXT));
        }
    }

    private void normalizeDuplicateHwpxTextOwners() {
        LinkedHashMap<Integer, List<Integer>> ownersByTextFrame = new LinkedHashMap<>();
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null || plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            for (int textFrameId : textFrameIdsForPlan(plan)) {
                ownersByTextFrame.computeIfAbsent(textFrameId, k -> new ArrayList<>()).add(i);
            }
        }

        LinkedHashMap<Integer, LinkedHashSet<Integer>> textFramesToRemoveByPlan = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<Integer>> entry : ownersByTextFrame.entrySet()) {
            List<Integer> ownerIndexes = entry.getValue();
            if (ownerIndexes == null || ownerIndexes.size() <= 1) continue;
            int keeper = bestHwpxTextOwner(ownerIndexes);
            for (int ownerIndex : ownerIndexes) {
                if (ownerIndex == keeper) continue;
                textFramesToRemoveByPlan
                        .computeIfAbsent(ownerIndex, k -> new LinkedHashSet<>())
                        .add(entry.getKey());
            }
        }

        for (Map.Entry<Integer, LinkedHashSet<Integer>> entry : textFramesToRemoveByPlan.entrySet()) {
            int planIndex = entry.getKey();
            if (planIndex < 0 || planIndex >= plans.size()) continue;
            ObjectPlan plan = plans.get(planIndex);
            if (plan == null || plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            int[] retainedOwnedTextFrames = removeIds(plan.ownedTextFrameIds, entry.getValue());
            boolean preserveShellTextRelation = preserveTextlessShellVisualAfterTextOwnerDedup(plan);
            int[] preservedShellRelationTextFrames = retainedOwnedTextFrames;
            if (preserveShellTextRelation) {
                int[] concreteRelation = concreteVisualShellOwnedTextFrameIds(plan);
                if (concreteRelation.length > 0) {
                    preservedShellRelationTextFrames = concreteRelation;
                } else if (preservedShellRelationTextFrames.length == 0) {
                    preservedShellRelationTextFrames = plan.ownedTextFrameIds;
                }
            }
            ObjectPlan replacement = preserveShellTextRelation
                    ? plan.withOwnedTextFrameIds(preservedShellRelationTextFrames)
                    : plan.withOwnedTextFrameIds(retainedOwnedTextFrames);
            if (preserveShellTextRelation && preservedShellRelationTextFrames.length > 0) {
                ObjectPlan relationScopedPlan = replacement.withOwnedTextFrameIds(preservedShellRelationTextFrames);
                if (!hasExtractedTextlessShellVisual(relationScopedPlan)) {
                    double[] concreteBounds = visualShellSourceBounds(relationScopedPlan);
                    if (concreteBounds != null && concreteBounds.length >= 4) {
                        relationScopedPlan = relationScopedPlan.withBounds(concreteBounds);
                    }
                }
                replacement = relationScopedPlan;
            }
            if (retainedOwnedTextFrames.length == 0 && plan.domId >= 0
                    && entry.getValue().contains(plan.domId)
                    && "text_frame".equals(plan.kind)) {
                replacement = replacement.withTextAction(TextAction.DROP_TEXT);
            } else if (retainedOwnedTextFrames.length == 0 && !"text_frame".equals(plan.kind)) {
                replacement = replacement.withTextAction(TextAction.DROP_TEXT);
                if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL) {
                    if (preserveTextlessShellVisualAfterTextOwnerDedup(plan)) {
                        // TEXT_SLOT and SHELL_SLOT are separate ownership channels.
                        // When the direct TextFrame keeps editable text, keep the textless shell visual.
                        replacement = replacement.withVisualAction(VisualAction.PLACE_TEXT_SHELL,
                                plan.reason);
                    } else {
                        replacement = replacement.withVisualAction(VisualAction.DROP_VISUAL,
                                "duplicate_hwpx_text_owner_removed");
                    }
                }
            }
            plans.set(planIndex, replacement);
        }
    }

    private boolean preserveTextlessShellVisualAfterTextOwnerDedup(ObjectPlan plan) {
        if (plan == null || plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.materialization == Materialization.NATIVE_SOURCE_SHAPE) return true;
        if (hasExtractedTextlessShellVisual(plan)) return true;
        String reason = safe(plan.reason);
        return "sibling_group_text_shell".equals(reason)
                || isDirectExtractedChildTextShellSlot(plan)
                || isDirectInlineTextShellReason(reason);
    }

    private int[] concreteVisualShellOwnedTextFrameIds(ObjectPlan plan) {
        if (plan == null || data == null) return new int[0];
        if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return new int[0];
        if (hasExtractedTextlessShellVisual(plan)) return new int[0];
        if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length <= 1) return new int[0];
        int[] visualIds = visualSourceIds(plan);
        if (visualIds.length == 0) return new int[0];

        LinkedHashSet<Integer> retained = new LinkedHashSet<>();
        for (int sourceId : visualIds) {
            if (sourceId < 0 || contains(plan.ownedTextFrameIds, sourceId)) continue;
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item == null || item.sourceHidden()) continue;
            if ("TextFrame".equals(safe(item.type()))) continue;
            if ("Group".equals(safe(item.type()))) continue;
            for (int tfId : plan.ownedTextFrameIds) {
                if (sourceShellHasOwnedTextFrameDescendant(item, new int[] { tfId })) {
                    retained.add(tfId);
                }
            }
        }
        return toIntArray(retained);
    }

    private boolean hasExtractedTextlessShellVisual(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.file == null || plan.file.isEmpty()) return false;
        if (plan.visualSourceObjectIds == null || plan.visualSourceObjectIds.length == 0) return false;
        return plan.materialization == Materialization.TEXTLESS_VISUAL_FRAGMENT
                || plan.materialization == Materialization.EXTRACTED_PNG_VECTOR
                || plan.materialization == Materialization.COMPLETE_PNG;
    }

    private void normalizeTextShellBoundsToConcreteVisualSources() {
        if (data == null) return;
        int candidates = 0;
        int concrete = 0;
        int rootConcrete = 0;
        int updated = 0;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null) continue;
            if (plan.placement != Placement.FLOATING) continue;
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (!shouldNormalizeTextShellBoundsToConcreteSource(plan)) continue;
            candidates++;

            double[] concreteBounds = concreteShellRootBounds(plan);
            if (concreteBounds != null && concreteBounds.length >= 4 && isPositiveBounds(concreteBounds)) {
                rootConcrete++;
            } else {
                concreteBounds = visualShellSourceBounds(plan);
            }
            if (concreteBounds == null || concreteBounds.length < 4) continue;
            concrete++;
            plans.set(i, plan.withBounds(concreteBounds));
            updated++;
        }
        ConversionTiming.metric("stage1.ownershipPlanner.textShellBoundsToConcrete.candidates", candidates);
        ConversionTiming.metric("stage1.ownershipPlanner.textShellBoundsToConcrete.concrete", concrete);
        ConversionTiming.metric("stage1.ownershipPlanner.textShellBoundsToConcrete.rootConcrete", rootConcrete);
        ConversionTiming.metric("stage1.ownershipPlanner.textShellBoundsToConcrete.updated", updated);
    }

    private void normalizeTextlessFragmentBoundsToCropSource() {
        int candidates = 0;
        int updated = 0;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!shouldPlaceTextlessFragmentOnCropSourceBounds(plan)) continue;
            candidates++;

            double[] clipped = clipPageRelativeBoundsToPage(plan.pageIndex, plan.cropSourceBounds);
            if (clipped == null || clipped.length < 4 || !isPositiveBounds(clipped)) continue;
            if (sameBounds(plan.bounds, clipped, 0.05)) continue;
            plans.set(i, plan.withBounds(clipped));
            updated++;
        }
        ConversionTiming.metric("stage1.ownershipPlanner.textlessFragmentBoundsToCropSource.candidates", candidates);
        ConversionTiming.metric("stage1.ownershipPlanner.textlessFragmentBoundsToCropSource.updated", updated);
    }

    private static boolean shouldPlaceTextlessFragmentOnCropSourceBounds(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.materialization != Materialization.TEXTLESS_VISUAL_FRAGMENT) return false;
        if (plan.placement != Placement.FLOATING) return false;
        if (plan.visualAction != VisualAction.PLACE_FLOATING_PNG) return false;
        return plan.cropSourceBounds != null
                && plan.cropSourceBounds.length >= 4
                && isPositiveBounds(plan.cropSourceBounds);
    }

    private boolean shouldNormalizeTextShellBoundsToConcreteSource(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.placement != Placement.FLOATING) return false;
        if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (isPageTextlessGraphicGroupPlan(plan)) return false;
        // A rendered textless shell already has an extractor-declared display
        // extent.  Narrowing it to a concrete child source breaks the shell/TF
        // slot contract and reintroduces split or clipped composite shells.
        if (hasExtractedTextlessShellVisual(plan)) return false;
        if (isTextShellWithSeparatedHiddenTextChannel(plan)) return true;
        String reason = safe(plan.reason);
        return "sibling_group_text_shell".equals(reason)
                || "story_flow_inline_shell_visual_only".equals(reason);
    }

    private static boolean isPageTextlessGraphicGroupPlan(ObjectPlan plan) {
        return plan != null
                && "pass.page_textless_graphic_groups".equals(safe(plan.planPassId));
    }

    private static boolean pageTextlessGraphicGroupNeedsContractRepair(ObjectPlan plan) {
        if (!isPageTextlessGraphicGroupPlan(plan)) return false;
        if (isClosedInlineCarrierVisualPlan(plan)) return false;
        return plan.textAction != TextAction.DROP_TEXT
                || plan.visualAction != VisualAction.PLACE_FLOATING_PNG
                || plan.visualLayer != VisualLayer.PAGE_BACKGROUND
                || plan.placement != Placement.FLOATING
                || plan.coordinateSpace != CoordinateSpace.PAGE
                || plan.materialization != Materialization.PAGE_PLANE_PNG;
    }

    private static boolean isClosedInlineCarrierVisualPlan(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.placement != Placement.INLINE) return false;
        if (plan.coordinateSpace != CoordinateSpace.STORY_FLOW) return false;
        String slotRole = safe(plan.slotRole);
        String candidateId = safe(plan.candidateId);
        String kind = safe(plan.kind);
        return "page_textless_inline_carrier_visual".equals(slotRole)
                || candidateId.contains("inline_carrier_")
                || kind.contains("page_textless_inline_carrier_visual");
    }

    private boolean isTextShellWithSeparatedHiddenTextChannel(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) return false;
        if (plan.exportSourceObjectIds == null || plan.exportSourceObjectIds.length == 0) return false;
        if (plan.hiddenVisualSourceObjectIds == null || plan.hiddenVisualSourceObjectIds.length == 0) return false;
        for (int tfId : plan.ownedTextFrameIds) {
            if (contains(plan.hiddenVisualSourceObjectIds, tfId)) {
                return true;
            }
        }
        return false;
    }

    private double[] visualShellSourceBounds(ObjectPlan plan) {
        if (plan == null || data == null) return null;
        double[] rootBounds = concreteShellRootBounds(plan);
        if (rootBounds != null && rootBounds.length >= 4 && isPositiveBounds(rootBounds)) {
            return rootBounds;
        }
        int[] visualIds = visualSourceIds(plan);
        if (visualIds.length == 0) return null;
        double[] union = null;
        for (int sourceId : visualIds) {
            if (sourceId < 0 || contains(plan.ownedTextFrameIds, sourceId)) continue;
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item == null || item.sourceHidden()) continue;
            if ("TextFrame".equals(safe(item.type()))) continue;
            if ("Group".equals(safe(item.type()))) continue;
            double[] b = pageRelativeBoundsOf(item);
            if (b == null || b.length < 4 || !isPositiveBounds(b)) continue;
            union = unionBounds(union, b);
        }
        return union;
    }

    private double[] concreteShellRootBounds(ObjectPlan plan) {
        if (plan == null || data == null) return null;
        if (!shouldPreferRootBoundsForConcreteTextShell(plan)) return null;
        int rootId = plan.domId;
        if (rootId < 0 && plan.sourceObjectIds != null && plan.sourceObjectIds.length > 0) {
            rootId = plan.sourceObjectIds[0];
        }
        if (rootId < 0) return null;
        ResolvedPageItem root = data.getPageItem(String.valueOf(rootId));
        if (!isConcreteTextShellRoot(root)) return null;
        return pageRelativeBoundsOfConcreteShellRoot(root);
    }

    private double[] pageRelativeBoundsOfConcreteShellRoot(ResolvedPageItem item) {
        if (item == null) return null;
        double[] direct = item.pageRelativeBounds();
        if (direct != null && direct.length >= 4) {
            return direct;
        }
        double[] b = item.geometricBounds();
        if (b == null || b.length < 4) return null;
        int pageIndex = item.pageIndex();
        double[] page = pageBounds(pageIndex);
        if (page == null || page.length < 4) {
            return b;
        }
        double scale = safeScaleFactor();
        double pageWidth = page[3] - page[1];
        double pageHeight = page[2] - page[0];
        boolean pageBoundsLookScaled = scale > 1.001
                && (pageWidth > 400.0 || pageHeight > 400.0);
        boolean itemBoundsLookScaledAgainstUnscaledPage = scale > 1.001
                && !pageBoundsLookScaled
                && (b[0] > page[2] + 4.0
                || b[1] > page[3] + 4.0
                || b[2] > page[2] + 4.0
                || b[3] > page[3] + 4.0);
        if (pageBoundsLookScaled) {
            return new double[] {
                    (b[0] - page[0]) / scale,
                    (b[1] - page[1]) / scale,
                    (b[2] - page[0]) / scale,
                    (b[3] - page[1]) / scale
            };
        }
        if (itemBoundsLookScaledAgainstUnscaledPage) {
            return new double[] {
                    (b[0] - page[0] * scale) / scale,
                    (b[1] - page[1] * scale) / scale,
                    (b[2] - page[0] * scale) / scale,
                    (b[3] - page[1] * scale) / scale
            };
        }
        return new double[] {
                b[0] - page[0],
                b[1] - page[1],
                b[2] - page[0],
                b[3] - page[1]
        };
    }

    private boolean shouldPreferRootBoundsForConcreteTextShell(ObjectPlan plan) {
        if (plan == null || plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        String reason = safe(plan.reason);
        return "sibling_group_text_shell".equals(reason)
                || "story_flow_inline_shell_visual_only".equals(reason)
                || isDirectExtractedChildTextShellSlot(plan);
    }

    private static boolean isConcreteTextShellRoot(ResolvedPageItem item) {
        if (item == null || item.sourceHidden()) return false;
        String type = safe(item.type());
        return "Rectangle".equals(type)
                || "Oval".equals(type)
                || "Polygon".equals(type);
    }

    private int bestHwpxTextOwner(List<Integer> ownerIndexes) {
        int best = ownerIndexes.get(0);
        for (int index : ownerIndexes) {
            if (compareHwpxTextOwner(plans.get(index), plans.get(best)) < 0) {
                best = index;
            }
        }
        return best;
    }

    private int compareHwpxTextOwner(ObjectPlan a, ObjectPlan b) {
        int priority = Integer.compare(hwpxTextOwnerPriority(a), hwpxTextOwnerPriority(b));
        if (priority != 0) return priority;
        int sources = Integer.compare(visualSourceIds(a).length, visualSourceIds(b).length);
        if (sources != 0) return sources;
        double areaA = a != null && a.bounds != null ? area(a.bounds) : Double.MAX_VALUE;
        double areaB = b != null && b.bounds != null ? area(b.bounds) : Double.MAX_VALUE;
        int areaCompare = Double.compare(areaA, areaB);
        if (areaCompare != 0) return areaCompare;
        return Integer.compare(a != null ? a.zOrder : Integer.MAX_VALUE,
                b != null ? b.zOrder : Integer.MAX_VALUE);
    }

    private int hwpxTextOwnerPriority(ObjectPlan plan) {
        if (plan == null) return 100;
        if (plan.visualAction == VisualAction.PLACE_TABLE_STYLE) return 0;
        if (isLocalAtomicLabelShellSlot(plan)) return 1;
        if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                && plan.placement == Placement.INLINE
                && plan.materialization != Materialization.NATIVE_SOURCE_SHAPE) return 1;
        if ("text_frame".equals(plan.kind)) return 2;
        if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                && plan.materialization == Materialization.NATIVE_SOURCE_SHAPE) return 3;
        if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL) return 3;
        if (plan.visualAction == VisualAction.DROP_VISUAL) return 4;
        return 5;
    }

    private boolean allOwnedTextFramesHaveSeparateHwpxTextOwner(ObjectPlan carrier) {
        if (carrier == null || carrier.ownedTextFrameIds == null || carrier.ownedTextFrameIds.length == 0) {
            return false;
        }
        for (int textFrameId : carrier.ownedTextFrameIds) {
            if (!hasSeparateHwpxTextOwner(carrier, textFrameId)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasSeparateHwpxTextOwner(ObjectPlan carrier, int textFrameId) {
        if (carrier == null || textFrameId < 0) return false;
        for (ObjectPlan candidate : plans) {
            if (candidate == null || candidate == carrier) continue;
            if (candidate.pageIndex != carrier.pageIndex) continue;
            if (candidate.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (!contains(candidate.ownedTextFrameIds, textFrameId)
                    && candidate.domId != textFrameId) {
                continue;
            }
            return true;
        }
        return false;
    }

    private boolean allTextFramesHaveVisibleHwpxTextOwner(int[] textFrameIds) {
        if (textFrameIds == null || textFrameIds.length == 0) return false;
        for (int textFrameId : textFrameIds) {
            if (!hasDirectHwpxTextFrameOwner(textFrameId)
                    && !hasMaterializedTextShellOwner(textFrameId)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasDirectHwpxTextFrameOwner(int textFrameId) {
        if (textFrameId < 0) return false;
        for (ObjectPlan owner : plans) {
            if (owner == null) continue;
            if (!"text_frame".equals(owner.kind)) continue;
            if (owner.domId != textFrameId) continue;
            if (owner.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            return true;
        }
        return false;
    }

    private void declareInlineSimpleButtonLabelShellOwners() {
        if (ctx == null) return;
        LinkedHashSet<Integer> restoredAnchors = new LinkedHashSet<>();
        LinkedHashSet<Integer> restoredSourceIds = new LinkedHashSet<>();
        LinkedHashSet<Integer> restoredTextFrames = new LinkedHashSet<>();
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null) continue;
            if (!safe(plan.kind).startsWith("simple_button_label:")) continue;
            if (plan.placement != Placement.INLINE) continue;
            SimpleButtonLabelPlan labelPlan = ctx.simpleButtonLabelPlan(plan.domId);
            if (labelPlan == null || labelPlan.mode != SimpleButtonLabelPlan.Mode.TEXT_SHELL) continue;
            if (labelPlan.labelTextFrameDomId < 0) continue;
            double[] storyFlowBounds = normalizeSpreadBoundsToPage(plan.pageIndex, plan.bounds);
            ObjectPlan restored = new ObjectPlan(
                    plan.domId,
                    plan.kind,
                    plan.pageIndex,
                    TextAction.OWNED_BY_HWPX_TEXT,
                    VisualAction.PLACE_TEXT_SHELL,
                    VisualLayer.LABEL_BACKDROP,
                    Placement.INLINE,
                    plan.renderId,
                    plan.sourceObjectIds,
                    plan.visualSourceObjectIds,
                    plan.styleSourceObjectIds,
                    new int[] { labelPlan.labelTextFrameDomId },
                    new int[0],
                    plan.sourceBundleKey,
                    Materialization.EXTRACTED_PNG_VECTOR,
                    CoordinateSpace.STORY_FLOW,
                    plan.anchorOwner,
                    plan.zOrder,
                    "simple_button_label_inline_text_shell",
                    plan.file,
                    storyFlowBounds,
                    plan.sourceLayerId,
                    plan.sourceLayerName,
                    plan.sourceLayerIndex);
            plans.set(i, restored);
            restoredAnchors.add(plan.domId);
            for (int sourceId : plan.sourceObjectIds) {
                restoredSourceIds.add(sourceId);
            }
            for (int sourceId : plan.visualSourceObjectIds) {
                restoredSourceIds.add(sourceId);
            }
            restoredTextFrames.add(labelPlan.labelTextFrameDomId);
        }
        if (restoredAnchors.isEmpty()) return;
        int[] anchorIds = toIntArray(restoredAnchors);
        int[] sourceIds = toIntArray(restoredSourceIds);
        int[] textFrameIds = toIntArray(restoredTextFrames);
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null) continue;
            if (safe(plan.kind).startsWith("simple_button_label:")) continue;
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (!restoredAnchors.contains(plan.domId)
                    && !containsAny(plan.sourceObjectIds, anchorIds)
                    && !containsAny(plan.sourceObjectIds, sourceIds)
                    && !containsAny(plan.visualSourceObjectIds, sourceIds)
                    && !containsAny(plan.ownedTextFrameIds, textFrameIds)) {
                continue;
            }
            if (!containsAny(plan.sourceObjectIds, textFrameIds)
                    && !containsAny(plan.visualSourceObjectIds, sourceIds)
                    && !containsAny(plan.ownedTextFrameIds, textFrameIds)) {
                continue;
            }
            int[] retainedVisualSources = removeIds(visualSourceIds(plan), restoredSourceIds);
            int[] retainedOwnedTextFrames = removeIds(plan.ownedTextFrameIds, restoredTextFrames);
            int[] retainedDescendants = removeIds(plan.descendantVisualObjectIds, restoredSourceIds);
            boolean directDuplicate = restoredAnchors.contains(plan.domId)
                    || containsAll(sourceIds, plan.sourceObjectIds);
            ObjectPlan replacement = plan
                    .withVisualSourceObjectIds(retainedVisualSources)
                    .withOwnedTextFrameIds(retainedOwnedTextFrames)
                    .withDescendantVisualObjectIds(retainedDescendants);
            if (directDuplicate
                    || (retainedVisualSources.length == 0
                    && retainedOwnedTextFrames.length == 0)) {
                replacement = replacement
                        .withTextAction(TextAction.DROP_TEXT)
                        .withVisualAction(VisualAction.DROP_VISUAL,
                                "inline_simple_button_label_owns_shell_slot")
                        .withOwnedTextFrameIds(new int[0])
                        .withDescendantVisualObjectIds(new int[0]);
            }
            plans.set(i, replacement);
        }
    }

    private boolean hasMaterializedTextShellOwner(int textFrameId) {
        if (textFrameId < 0) return false;
        for (ObjectPlan owner : plans) {
            if (owner == null) continue;
            if (owner.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (owner.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (isCompositeCarrierTextShellReason(owner.reason)) continue;
            if (owner.materialization == Materialization.NATIVE_SOURCE_SHAPE) continue;
            if (isDirectTextShellSlot(owner)) continue;
            if (!contains(owner.ownedTextFrameIds, textFrameId)) continue;
            return true;
        }
        return false;
    }

    private void dropInlineGraphicOnlyTextShellWithoutTextlessCarrier() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!isInlineGraphicOnlyTextShellWithoutTextlessCarrier(plan)) continue;
            plans.set(i, plan
                    .withTextAction(TextAction.DROP_TEXT)
                    .withVisualAction(VisualAction.DROP_VISUAL,
                            "inline_complete_png_text_owned_by_child_frames"));
        }
    }

    private boolean hasExplicitTextlessShellCoverageForOwnedText(ObjectPlan ownerPlan) {
        if (ownerPlan == null || ownerPlan.ownedTextFrameIds == null
                || ownerPlan.ownedTextFrameIds.length == 0) {
            return false;
        }
        LinkedHashSet<Integer> remaining = new LinkedHashSet<>();
        for (int tfId : ownerPlan.ownedTextFrameIds) {
            remaining.add(tfId);
        }
        for (ObjectPlan plan : plans) {
            if (plan == null || plan == ownerPlan) continue;
            if (plan.pageIndex != ownerPlan.pageIndex) continue;
            if (plan.placement != Placement.INLINE) continue;
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (!hasExplicitTextlessShellSignal(plan)) continue;
            if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) continue;
            for (int tfId : plan.ownedTextFrameIds) {
                remaining.remove(tfId);
            }
            if (remaining.isEmpty()) return true;
        }
        return false;
    }

    private void dropPlansOwnedByInlineCompletePng() {
        LinkedHashSet<Integer> ownedTextFrames = new LinkedHashSet<>();
        for (ObjectPlan plan : plans) {
            if (plan == null) continue;
            if (plan.visualAction != VisualAction.PLACE_INLINE_PNG
                    && plan.visualAction != VisualAction.PLACE_FLOATING_PNG) continue;
            if (plan.textAction != TextAction.OWNED_BY_PNG) continue;
            if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) continue;
            for (int tfId : plan.ownedTextFrameIds) {
                ownedTextFrames.add(tfId);
            }
        }
        if (ownedTextFrames.isEmpty()) return;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null) continue;
            if ("text_frame".equals(plan.kind) && ownedTextFrames.contains(plan.domId)) {
                plans.set(i, plan
                        .withTextAction(TextAction.DROP_TEXT)
                        .withVisualAction(VisualAction.DROP_VISUAL, "owned_by_inline_complete_png"));
                continue;
            }
            if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                    && allOwnedTextFramesIn(plan, ownedTextFrames)) {
                plans.set(i, plan
                        .withTextAction(TextAction.DROP_TEXT)
                        .withVisualAction(VisualAction.DROP_VISUAL, "owned_by_inline_complete_png"));
            }
        }
    }

    private void normalizePlannerDeclaredInlineCompletePngWithoutTextOwner() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null) continue;
            if (!safe(plan.kind).startsWith("planner_declared_rendered:pass.inline_objects:")) continue;
            if (!"planner_declared_object_plan".equals(safe(plan.reason))) continue;
            if (plan.placement != Placement.INLINE) continue;
            if (plan.textAction != TextAction.OWNED_BY_PNG
                    && plan.materialization != Materialization.COMPLETE_PNG) {
                continue;
            }
            if (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) continue;
            RenderedGroup rendered = renderedGroupForPlan(plan);
            if (rendered == null) continue;
            if ("indesign_png".equals(safe(rendered.textOwner()))) continue;
            if (Boolean.TRUE.equals(rendered.containsEditableText())) continue;
            if (hasEditableTextFrameIds(rendered)) continue;
            VisualAction visualAction = plan.visualAction == VisualAction.PLACE_FLOATING_PNG
                    ? VisualAction.PLACE_FLOATING_PNG
                    : VisualAction.PLACE_INLINE_PNG;
            plans.set(i, plan
                    .withTextAction(TextAction.DROP_TEXT)
                    .withVisualAction(visualAction, "planner_declared_inline_graphic_visual_only")
                    .withMaterialization(Materialization.EXTRACTED_PNG_VECTOR)
                    .withOwnedTextFrameIds(new int[0])
                    .withDescendantVisualObjectIds(new int[0]));
        }
    }

    private boolean allOwnedTextFramesIn(ObjectPlan plan, Set<Integer> ownedTextFrames) {
        if (plan == null || plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) {
            return false;
        }
        for (int tfId : plan.ownedTextFrameIds) {
            if (!ownedTextFrames.contains(tfId)) return false;
        }
        return true;
    }

    private void normalizeTextShellPlacementToResolvedAnchors() {
        LinkedHashSet<Integer> inlinedTextFrames = new LinkedHashSet<>();
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null) continue;
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (plan.placement != Placement.FLOATING) continue;
            if (!isGroupInternalTextShell(plan)) continue;
            if (isTableCellAnchoredExternalLabelShell(plan)) continue;
            if (!canNormalizeTextShellToInlineFromSource(plan)) continue;
            boolean sourcePositionedInlineShell = isSourcePositionedInlineTextShell(plan);
            if (!hasResolvedInlineAnchor(plan.domId) && !sourcePositionedInlineShell) continue;
            if (hasIdmlAnchoredPagePosition(plan.domId)) continue;
            if (!ownedTextFramesAreInlineSource(plan)) continue;
            if (textShellUsesPagePositionCompositeAssociation(plan)) continue;

            ObjectPlan replacement = plan.withPlacementAndCoordinateSpace(
                    Placement.INLINE,
                    CoordinateSpace.STORY_FLOW);
            plans.set(i, replacement);
            for (int textFrameId : textFrameIdsForPlanIncludingOwned(replacement)) {
                inlinedTextFrames.add(textFrameId);
            }
        }
        if (!inlinedTextFrames.isEmpty()) {
            alignOwnedTextFramePlans(toIntArray(inlinedTextFrames), Placement.INLINE);
        }

        LinkedHashSet<Integer> floatedTextFrames = new LinkedHashSet<>();
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null) continue;
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (plan.placement != Placement.INLINE) continue;
            if (isTableCellAnchoredExternalLabelShell(plan)) {
                ObjectPlan replacement = plan.withPlacementAndCoordinateSpace(
                        Placement.FLOATING,
                        CoordinateSpace.PAGE);
                plans.set(i, replacement);
                for (int textFrameId : textFrameIdsForPlanIncludingOwned(replacement)) {
                    floatedTextFrames.add(textFrameId);
                }
                continue;
            }
            if (isDirectStoryFlowInlineTextShell(plan)) {
                continue;
            }
            if (isSourcePositionedInlineTextShell(plan)
                    && !hasIdmlAnchoredPagePosition(plan.domId)
                    && !hasFloatingCompositeSourceParentCarrier(plan)) {
                continue;
            }
            if (isResolvedStoryInlineAnchor(plan)
                    && !inlineTextShellNeedsPageCarrier(plan)
                    && !hasFloatingCompositeSourceParentCarrier(plan)) {
                continue;
            }
            if (!isGroupInternalTextShell(plan)) continue;

            ObjectPlan replacement = plan.withPlacementAndCoordinateSpace(
                    Placement.FLOATING,
                    CoordinateSpace.PAGE);
            if (inlineTextShellCarrierOwnedByFloatingTextShell(plan)
                    && plan.visualLayer == VisualLayer.CONTAINER_BACKDROP) {
                replacement = replacement.withVisualLayer(textShellVisualLayer(
                        plan,
                        textFrameIdsForPlanIncludingOwned(plan),
                        VisualLayer.LABEL_OVERLAY_BACKDROP));
            }
            plans.set(i, replacement);
            for (int textFrameId : textFrameIdsForPlanIncludingOwned(replacement)) {
                floatedTextFrames.add(textFrameId);
            }
        }
        if (!floatedTextFrames.isEmpty()) {
            alignOwnedTextFramePlans(toIntArray(floatedTextFrames), Placement.FLOATING);
        }
    }

    private boolean isDirectStoryFlowInlineTextShell(ObjectPlan plan) {
        if (plan == null || plan.domId < 0) return false;
        if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        if (isTableCellAnchoredExternalLabelShell(plan)) return false;
        return hasResolvedInlineAnchor(plan.domId)
                && !hasIdmlAnchoredPagePosition(plan.domId)
                && !textShellUsesPagePositionCompositeAssociation(plan);
    }

    private boolean canNormalizeTextShellToInlineFromSource(ObjectPlan plan) {
        if (plan == null) return false;
        if (isInlineLeafAtomicTextShellSlot(plan)) return true;
        String kind = safe(plan.kind);
        if (kind.contains("inline_object")) return true;

        String reason = safe(plan.reason);
        if (isCompositeCarrierTextShellReason(reason)) return false;

        return "visual_label_text_hidden_shell".equals(reason)
                || "atomic_ownership_root_text_hidden_shell".equals(reason)
                || "simple_button_label_inline_text_shell".equals(reason)
                || "inline_text_hidden".equals(reason)
                || "editable_textframe_visual_shell".equals(reason);
    }

    private void dropChildLabelShellVisualsBakedIntoFloatingCompositeParents() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan child = plans.get(i);
            if (child == null) continue;
            if (child.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (child.visualLayer != VisualLayer.LABEL_BACKDROP
                    && child.visualLayer != VisualLayer.LABEL_OVERLAY_BACKDROP) {
                continue;
            }
            if (isStoryFlowInlineShell(child)) continue;
            if (!isSimpleChildLabelShellBakedIntoComposite(child)) continue;
            if (child.ownedTextFrameIds == null || child.ownedTextFrameIds.length == 0) continue;
            if (!hasFloatingCompositeParentCarrier(child)) continue;
            if (!ownedTextFramePlansRemainHwpxText(child.ownedTextFrameIds)) continue;
            if (isLocalAtomicLabelShellSlot(child)) {
                removeLocalLabelShellSlotFromCompositeCarriers(child);
                continue;
            }
            plans.set(i, child
                    .withTextAction(TextAction.DROP_TEXT)
                    .withVisualAction(VisualAction.DROP_VISUAL,
                            "label_shell_visual_owned_by_floating_composite_parent")
                    .withOwnedTextFrameIds(new int[0])
                    .withDescendantVisualObjectIds(new int[0]));
        }
    }

    private void makePagePositionedStoryFlowInlineShellsVisualOnly() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null) continue;
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (plan.placement != Placement.FLOATING) continue;
            if (!isStoryFlowInlineShell(plan)) continue;
            if (isInlineLeafAtomicTextShellSlot(plan)) continue;
            if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) continue;
            if (!ownedTextFramePlansRemainHwpxText(plan.ownedTextFrameIds)) continue;
            plans.set(i, plan
                    .withTextAction(TextAction.DROP_TEXT)
                    .withVisualAction(VisualAction.PLACE_TEXT_SHELL,
                            "story_flow_inline_shell_visual_only")
                    .withOwnedTextFrameIds(new int[0]));
        }
    }

    private boolean isStoryFlowInlineShell(ObjectPlan plan) {
        if (plan == null) return false;
        if (isTableCellAnchoredExternalLabelShell(plan)) return false;
        if (plan.placement == Placement.INLINE) return true;
        if (plan.coordinateSpace == CoordinateSpace.STORY_FLOW) return true;
        if (plan.placement == Placement.FLOATING
                && plan.coordinateSpace == CoordinateSpace.PAGE
                && "planner_declared_object_plan".equals(safe(plan.reason))) {
            return false;
        }
        if (plan.kind != null && plan.kind.contains(":inline_object")) return true;
        if (data == null || plan.domId < 0) return false;
        ResolvedPageItem item = data.getPageItem(String.valueOf(plan.domId));
        return item != null && item.isInline();
    }

    private boolean isSimpleChildLabelShellBakedIntoComposite(ObjectPlan child) {
        if (child == null) return false;
        String reason = safe(child.reason);
        if ("visual_label_text_hidden_shell".equals(reason)
                || "atomic_ownership_root_text_hidden_shell".equals(reason)
                || "inline_text_hidden".equals(reason)
                || "editable_textframe_visual_shell".equals(reason)) {
            return true;
        }
        // Mixed shells have their own visual-only children. They are a distinct
        // SHELL_SLOT and must survive even when a larger composite carrier exists.
        return false;
    }

    private boolean ownedTextFramePlansRemainHwpxText(int[] textFrameIds) {
        if (textFrameIds == null || textFrameIds.length == 0) return false;
        for (int textFrameId : textFrameIds) {
            boolean found = false;
            for (ObjectPlan plan : plans) {
                if (plan == null || !"text_frame".equals(plan.kind)) continue;
                if (plan.domId != textFrameId) continue;
                if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
                if (plan.visualAction != VisualAction.DROP_VISUAL) return false;
                found = true;
                break;
            }
            if (!found) return false;
        }
        return true;
    }

    private boolean hasFloatingCompositeParentCarrier(ObjectPlan child) {
        if (child == null || child.domId < 0 || child.bounds == null || child.bounds.length < 4) {
            return false;
        }
        for (ObjectPlan parent : plans) {
            if (parent == null || parent == child) continue;
            if (parent.pageIndex != child.pageIndex) continue;
            if (parent.placement != Placement.FLOATING) continue;
            if (parent.visualAction != VisualAction.PLACE_TEXT_SHELL
                    && parent.visualAction != VisualAction.PLACE_FLOATING_PNG) {
                continue;
            }
            if (parent.visualPolicyLayer() != PolicyLayer.BACKGROUND) continue;
            if (!isCompositeCarrierTextShellReason(safe(parent.reason))) continue;
            if (!boundsContains(parent.bounds, child.bounds, 4.0)
                    && !boundsMostlyOverlap(parent.bounds, child.bounds, 0.45)) {
                continue;
            }
            if (contains(parent.sourceObjectIds, child.domId)
                    || containsAny(parent.sourceObjectIds, child.sourceObjectIds)
                    || containsAny(visualSourceIds(parent), child.sourceObjectIds)
                    || containsAny(parent.descendantVisualObjectIds, child.sourceObjectIds)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasFloatingCompositeSourceParentCarrier(ObjectPlan child) {
        if (hasFloatingCompositeParentCarrier(child)) return true;
        if (child == null || child.domId < 0 || child.bounds == null || child.bounds.length < 4
                || data == null) {
            return false;
        }
        for (RenderedGroup rg : data.allRenderedFloatingItems()) {
            if (rg == null) continue;
            if (rg.pageIndex() != child.pageIndex) continue;
            if (!isRenderedPageObject(rg)) continue;
            if (!isCompositeCarrierTextShellReason(safe(rg.reason()))) continue;
            if (rg.bounds() == null || rg.bounds().length < 4) continue;
            if (!boundsContains(rg.bounds(), child.bounds, 4.0)
                    && !boundsMostlyOverlap(rg.bounds(), child.bounds, 0.45)) {
                continue;
            }
            if (contains(rg.sourceObjectIds(), child.domId)
                    || containsAny(rg.sourceObjectIds(), child.sourceObjectIds)
                    || containsAnyStringIds(rg.editableTextFrameIds(), child.ownedTextFrameIds)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAnyStringIds(String[] sourceIds, int[] targetIds) {
        if (sourceIds == null || sourceIds.length == 0 || targetIds == null || targetIds.length == 0) {
            return false;
        }
        for (String sourceId : sourceIds) {
            int parsed = parseFlexibleId(sourceId);
            if (parsed >= 0 && contains(targetIds, parsed)) return true;
        }
        return false;
    }

    private boolean isLocalAtomicLabelShellSlot(ObjectPlan child) {
        if (child == null || data == null) return false;
        if (child.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (child.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        if (child.ownedTextFrameIds == null || child.ownedTextFrameIds.length != 1) return false;
        if (!isAtomicOwnershipRootTextHiddenShellReason(safe(child.reason))) return false;
        if (child.materialization != Materialization.EXTRACTED_PNG_VECTOR) return false;
        ResolvedPageItem group = data.getPageItem(String.valueOf(child.domId));
        if (group == null || !"Group".equals(safe(group.type()))) return false;
        if (group.childIds() == null || group.childIds().length < 2) return false;

        boolean hasOwnedTextChild = false;
        boolean hasDirectVisualChild = false;
        for (int childId : group.childIds()) {
            if (contains(child.ownedTextFrameIds, childId)) {
                hasOwnedTextChild = true;
                continue;
            }
            ResolvedPageItem item = data.getPageItem(String.valueOf(childId));
            if (item != null && isVisibleTextlessLabelMarkerSource(item)) {
                hasDirectVisualChild = true;
            }
        }
        return hasOwnedTextChild && hasDirectVisualChild;
    }

    private static boolean isVisibleTextlessLabelMarkerSource(ResolvedPageItem item) {
        if (item == null || item.sourceHidden() || !item.visible() || item.hiddenByParent()) return false;
        String type = safe(item.type());
        if ("TextFrame".equals(type) || "Image".equals(type) || "PDF".equals(type) || "EPS".equals(type)) {
            return false;
        }
        return "Group".equals(type)
                || "Rectangle".equals(type)
                || "Oval".equals(type)
                || "Polygon".equals(type)
                || "GraphicLine".equals(type);
    }

    private void removeLocalLabelShellSlotFromCompositeCarriers(ObjectPlan labelShell) {
        if (labelShell == null || labelShell.ownedTextFrameIds == null
                || labelShell.ownedTextFrameIds.length == 0) {
            return;
        }
        LinkedHashSet<Integer> slotSources = new LinkedHashSet<>();
        addAll(labelShell.sourceObjectIds, slotSources);
        addAll(visualSourceIds(labelShell), slotSources);
        addAll(labelShell.ownedTextFrameIds, slotSources);

        LinkedHashSet<Integer> slotTextFrames = new LinkedHashSet<>();
        addAll(labelShell.ownedTextFrameIds, slotTextFrames);

        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan carrier = plans.get(i);
            if (carrier == null || carrier == labelShell) continue;
            if (carrier.pageIndex != labelShell.pageIndex) continue;
            if (!isCompositeCarrierWithLocalLabelSlot(carrier)) continue;
            if (!containsAll(carrier.sourceObjectIds, labelShell.sourceObjectIds)) continue;
            if (!containsAny(carrier.ownedTextFrameIds, labelShell.ownedTextFrameIds)
                    && !containsAny(visualSourceIds(carrier), labelShell.sourceObjectIds)) {
                continue;
            }

            int[] retainedSources = withoutSourcesAllowEmpty(carrier.sourceObjectIds, slotSources);
            int[] retainedVisualSources = withoutSourcesAllowEmpty(visualSourceIds(carrier), slotSources);
            int[] retainedOwnedTextFrames = withoutSourcesAllowEmpty(carrier.ownedTextFrameIds, slotTextFrames);
            int[] retainedDescendants = withoutSourcesAllowEmpty(carrier.descendantVisualObjectIds, slotSources);
            ObjectPlan replacement = carrier
                    .withSourceObjectIds(retainedSources)
                    .withVisualSourceObjectIds(retainedVisualSources)
                    .withOwnedTextFrameIds(retainedOwnedTextFrames)
                    .withDescendantVisualObjectIds(retainedDescendants);
            if (retainedVisualSources.length == 0 && retainedOwnedTextFrames.length == 0) {
                replacement = replacement.withVisualAction(VisualAction.DROP_VISUAL,
                        "composite_carrier_split_into_local_label_shell_slots");
            } else if (retainedOwnedTextFrames.length == 0
                    && carrier.textAction == TextAction.OWNED_BY_HWPX_TEXT) {
                replacement = replacement.withTextAction(TextAction.DROP_TEXT);
            }
            plans.set(i, replacement);
        }
    }

    private boolean isCompositeCarrierWithLocalLabelSlot(ObjectPlan carrier) {
        if (carrier == null) return false;
        if (carrier.materialization != Materialization.EXTRACTED_PNG_VECTOR) return false;
        if (carrier.visualAction != VisualAction.PLACE_TEXT_SHELL
                && carrier.visualAction != VisualAction.PLACE_FLOATING_PNG) {
            return false;
        }
        String reason = safe(carrier.reason);
        return reason.contains("image_group_text_hidden")
                || reason.contains("mixed_group_text_hidden")
                || reason.contains("complex_graphic_text_hidden")
                || reason.contains("composite_shell_carrier");
    }

    private boolean isSourcePositionedInlineTextShell(ObjectPlan plan) {
        if (plan == null) return false;
        if (isDirectChildShellSlotPlan(plan)) return false;
        if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        if (isTableCellAnchoredExternalLabelShell(plan)) return false;
        if (!ownedTextFramesAreInlineSource(plan)) return false;
        if (!hasExplicitTextlessShellSignal(plan)) return false;
        if (isInlineLeafAtomicTextShellSlot(plan)) return true;
        RenderedGroup rendered = renderedGroupForPlan(plan);
        if (rendered != null && hasExecutableInlineSourceObject(rendered)) return true;
        if (data == null) return false;
        if (plan.domId >= 0 && data.isInlineObjectId(plan.domId)) return true;
        if (plan.domId >= 0) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(plan.domId));
            if (item != null && item.isInline()) return true;
        }
        for (int sourceId : visualSourceIds(plan)) {
            if (data.isInlineObjectId(sourceId)) return true;
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item != null && item.isInline()) return true;
        }
        return false;
    }

    private boolean hasExecutableInlineSourceObject(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (isDirectChildShellSlotRender(rg)) return false;
        if ("inline_object".equals(rg.type()) || "inline_object".equals(rg.itemType())) {
            return true;
        }
        if (data.isInlineObjectId(rg.id())) return true;
        if (hasResolvedInlineAnchor(rg.id())) return true;
        if (rg.sourceObjectIds() == null) return false;
        for (int sourceId : rg.sourceObjectIds()) {
            if (data.isInlineObjectId(sourceId)) return true;
            if (hasResolvedInlineAnchor(sourceId)) return true;
        }
        return false;
    }

    private boolean isDirectChildShellSlotPlan(ObjectPlan plan) {
        if (plan == null) return false;
        if ("direct_child_shell_slot".equals(safe(plan.slotRole))) return true;
        String candidateId = safe(plan.candidateId);
        if (candidateId.contains(".direct_child_shell_slot")
                || candidateId.endsWith("direct_child_shell_slot")) {
            return true;
        }
        return isDirectChildShellSlotRender(renderedGroupForPlan(plan));
    }

    private boolean isDirectChildShellSlotRender(RenderedGroup rg) {
        if (rg == null) return false;
        if ("direct_child_shell_slot".equals(safe(rg.slotRole()))) return true;
        if ("direct_child_shell_slot".equals(safe(rg.compositeRole()))) return true;
        String candidateId = safe(rg.candidateId());
        return candidateId.contains(".direct_child_shell_slot")
                || candidateId.endsWith("direct_child_shell_slot");
    }

    private boolean isTableCellAnchoredExternalLabelShell(ObjectPlan plan) {
        if (plan == null || data == null || plan.domId < 0) return false;
        if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) return false;
        if (!hasExplicitTextlessShellSignal(plan)) return false;
        ResolvedTable table = resolvedTableContainingInlineAnchor(plan.domId);
        if (table == null) return false;
        ResolvedTable.Cell cell = resolvedCellContainingInlineAnchor(table, plan.domId);
        if (cell == null || !cell.hasTextRuns()) return false;
        AnchorCarrier carrier = nearestAnchorCarrierForSource(plan.domId);
        if (anchorCarrierParagraphHasVisibleText(carrier)
                || idmlInlineAnchorParagraphHasVisibleText(plan.domId)) {
            return false;
        }
        return sourceBoundsOutsideTable(plan.bounds, table.bounds());
    }

    private ResolvedTable resolvedTableContainingInlineAnchor(int domId) {
        if (data == null || data.tables() == null) return null;
        for (ResolvedTable table : data.tables()) {
            if (resolvedCellContainingInlineAnchor(table, domId) != null) {
                return table;
            }
        }
        return null;
    }

    private static ResolvedTable.Cell resolvedCellContainingInlineAnchor(ResolvedTable table, int domId) {
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

    private static boolean isCompositeCarrierTextShellReason(String reason) {
        if (reason == null || reason.isEmpty()) return false;
        return reason.contains("mixed_group")
                || reason.contains("image_group")
                || reason.contains("complex_graphic")
                || reason.contains("composite_text_shell");
    }

    private boolean ownedTextFramesAreInlineSource(ObjectPlan plan) {
        if (plan == null || plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) {
            return false;
        }
        for (int textFrameId : plan.ownedTextFrameIds) {
            ResolvedTextFrame tf = data != null ? data.getTextFrame(String.valueOf(textFrameId)) : null;
            if (tf == null || !tf.isInline()) return false;
        }
        return true;
    }

    private boolean isGroupInternalTextShell(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) return false;
        if (safe(plan.kind).contains("inline_object")) return true;
        if (safe(plan.kind).contains("page_object")) return true;
        String reason = safe(plan.reason);
        return reason.contains("text_hidden_shell")
                || reason.contains("atomic_ownership_root")
                || reason.contains("leaf_group");
    }

    private boolean textShellUsesPagePositionCompositeAssociation(ObjectPlan plan) {
        if (plan == null || plan.domId < 0) return false;
        if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) return false;
        if (!hasExplicitTextlessShellSignal(plan)) return false;
        if (isInlineLeafAtomicTextShellSlot(plan)) return false;
        if (!hasFloatingCompositeSourceParentCarrier(plan)
                && !hasCompositeSourceParentCarrierByBundle(plan)) {
            return false;
        }
        AnchorCarrier carrier = nearestAnchorCarrierForSource(plan.domId);
        if (carrier == null) return true;
        return anchorCarrierParagraphIsAnchorOnly(carrier);
    }

    private void normalizeCompositeAssociatedInlineTextShellsToPage() {
        LinkedHashSet<Integer> floatedTextFrames = new LinkedHashSet<>();
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null || plan.placement != Placement.INLINE) continue;
            if (!textShellUsesPagePositionCompositeAssociation(plan)) continue;
            ObjectPlan replacement = plan.withPlacementAndCoordinateSpace(
                    Placement.FLOATING,
                    CoordinateSpace.PAGE);
            plans.set(i, replacement);
            for (int textFrameId : textFrameIdsForPlanIncludingOwned(replacement)) {
                floatedTextFrames.add(textFrameId);
            }
        }
        if (!floatedTextFrames.isEmpty()) {
            alignOwnedTextFramePlans(toIntArray(floatedTextFrames), Placement.FLOATING);
        }
    }

    private void normalizeDirectInlineAnchoredTextShellsToStoryFlow() {
        LinkedHashSet<Integer> inlinedTextFrames = new LinkedHashSet<>();
        int inlineLeafCandidates = 0;
        int inlinedShells = 0;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (isInlineLeafAtomicTextShellSlot(plan)) {
                inlineLeafCandidates++;
            }
            if (!directInlineAnchoredTextShellMustFlowWithStory(plan)) continue;
            if (plan.placement == Placement.INLINE
                    && plan.coordinateSpace == CoordinateSpace.STORY_FLOW) {
                for (int textFrameId : textFrameIdsForPlanIncludingOwned(plan)) {
                    inlinedTextFrames.add(textFrameId);
                }
                continue;
            }
            ObjectPlan replacement = plan.withPlacementAndCoordinateSpace(
                    Placement.INLINE,
                    CoordinateSpace.STORY_FLOW);
            plans.set(i, replacement);
            inlinedShells++;
            for (int textFrameId : textFrameIdsForPlanIncludingOwned(replacement)) {
                inlinedTextFrames.add(textFrameId);
            }
        }
        ConversionTiming.metric("stage1.ownershipPlanner.directInlineTextShell.leafCandidates",
                inlineLeafCandidates);
        ConversionTiming.metric("stage1.ownershipPlanner.directInlineTextShell.inlined",
                inlinedShells);
        if (!inlinedTextFrames.isEmpty()) {
            alignOwnedTextFramePlans(toIntArray(inlinedTextFrames), Placement.INLINE);
        }
    }

    private boolean directInlineAnchoredTextShellMustFlowWithStory(ObjectPlan plan) {
        if (plan == null || plan.domId < 0) return false;
        if (isDirectChildShellSlotPlan(plan)) return false;
        if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) return false;
        if (!hasExplicitTextlessShellSignal(plan)) return false;
        if (isTableCellAnchoredExternalLabelShell(plan)) return false;
        if (!ownedTextFramesAreInlineSource(plan)) return false;
        if (isInlineLeafAtomicTextShellSlot(plan)) return true;
        if (hasIdmlAnchoredPagePosition(plan.domId)) return false;
        if (textShellUsesPagePositionCompositeAssociation(plan)) return false;
        if (isSourcePositionedInlineTextShell(plan)) return true;

        AnchorCarrier carrier = nearestAnchorCarrierForSource(plan.domId);
        return anchorCarrierParagraphHasVisibleText(carrier)
                || idmlInlineAnchorParagraphHasVisibleText(plan.domId);
    }

    private boolean isInlineLeafAtomicTextShellSlot(ObjectPlan plan) {
        if (plan == null || data == null || plan.domId < 0) return false;
        if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        if (!hasExplicitTextlessShellSignal(plan)) return false;
        if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length != 1) return false;
        ResolvedPageItem group = data.getPageItem(String.valueOf(plan.domId));
        if (group == null || !"Group".equals(group.type()) || !group.isInline()) return false;
        int[] childIds = group.childIds();
        if (childIds == null || childIds.length == 0) return false;
        int textChildCount = 0;
        int visualShellChildCount = 0;
        for (int childId : childIds) {
            ResolvedTextFrame childTf = data.getTextFrame(String.valueOf(childId));
            if (childTf != null) {
                if (childTf.sourceHidden()) continue;
                if (!contains(plan.ownedTextFrameIds, childId)) return false;
                textChildCount++;
                continue;
            }
            ResolvedPageItem childItem = data.getPageItem(String.valueOf(childId));
            if (childItem == null || childItem.sourceHidden()) continue;
            if (!isInlineLeafTextShellVisualChild(childItem)) return false;
            visualShellChildCount++;
        }
        return textChildCount == 1 && visualShellChildCount >= 1;
    }

    private static boolean isSimpleButtonLabelTextShellSlot(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.materialization != Materialization.EXTRACTED_PNG_VECTOR) return false;
        if (plan.kind == null || !plan.kind.startsWith("simple_button_label:")) return false;
        if (!"simple_button_label_inline_text_shell".equals(safe(plan.reason))) return false;
        String base = basename(plan.file);
        return base.startsWith("deco_")
                || base.startsWith("tf_shell_")
                || base.startsWith("label_backdrop_");
    }

    private static boolean isInlineLeafTextShellVisualChild(ResolvedPageItem item) {
        if (item == null || item.sourceHidden()) return false;
        String type = safe(item.type());
        if (!"Rectangle".equals(type) && !"Oval".equals(type) && !"Polygon".equals(type)) return false;
        boolean hasFill = !isNoneColor(item.fillColorName());
        boolean hasStroke = !isNoneColor(item.strokeColorName()) && item.strokeWeight() > 0.01;
        return hasFill || hasStroke || item.cornerRadius() > 0 || item.hasDropShadow();
    }

    private boolean hasCompositeSourceParentCarrierByBundle(ObjectPlan child) {
        if (child == null || child.pageIndex < 0) return false;
        for (ObjectPlan parent : plans) {
            if (parent == null || parent == child) continue;
            if (parent.pageIndex != child.pageIndex) continue;
            if (!isCompositeCarrierTextShellReason(safe(parent.reason))) continue;
            if (sourceCount(parent) <= sourceCount(child)) continue;
            if (contains(parent.sourceObjectIds, child.domId)
                    || containsAny(parent.sourceObjectIds, child.sourceObjectIds)
                    || containsAny(parent.sourceObjectIds, child.ownedTextFrameIds)
                    || containsAny(visualSourceIds(parent), child.sourceObjectIds)
                    || containsAny(parent.descendantVisualObjectIds, child.sourceObjectIds)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anchorCarrierParagraphIsAnchorOnly(AnchorCarrier carrier) {
        Boolean textFlowResult = anchorCarrierTextFlowParagraphIsAnchorOnly(carrier);
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
                return false;
            }
        }
        return sawCarrierAnchor;
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

    private static Boolean anchorCarrierTextFlowParagraphIsAnchorOnly(AnchorCarrier carrier) {
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
                    continue;
                }
                return false;
            }
            if (atom instanceof TextFlowDocument.TextAtom) {
                TextFlowDocument.TextAtom textAtom = (TextFlowDocument.TextAtom) atom;
                if (!normalizeResolvedVisibleText(textAtom.text).isEmpty()) {
                    return false;
                }
            }
        }
        return sawCarrierAnchor;
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

    private boolean idmlInlineAnchorParagraphHasVisibleText(int domId) {
        if (ctx == null || ctx.loadIDMLStory == null || data == null || data.textFrames() == null) {
            return false;
        }
        HashSet<String> visitedStoryIds = new HashSet<>();
        for (ResolvedTextFrame tf : data.textFrames()) {
            if (tf == null || tf.storyId() == null || tf.storyId().isEmpty()) continue;
            if (!visitedStoryIds.add(tf.storyId())) continue;
            IDMLStory story = loadStory(tf.storyId());
            if (idmlStoryInlineAnchorParagraphHasVisibleText(story, domId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean idmlStoryInlineAnchorParagraphHasVisibleText(IDMLStory story, int domId) {
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

    private boolean inlineTextShellNeedsPageCarrier(ObjectPlan plan) {
        if (plan == null || plan.domId < 0) return false;
        if (plan.placement != Placement.INLINE) return false;
        if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) return false;

        AnchorCarrier carrier = nearestAnchorCarrierForSource(plan.domId);
        if (carrier == null || carrier.story == null) {
            return true;
        }
        if (textShellUsesPagePositionCompositeAssociation(plan)) {
            return true;
        }
        if (carrierStoryOwnedByFloatingTextShell(carrier.story)) {
            return true;
        }
        return !hasVisibleResolvedStoryText(carrier.story);
    }

    private boolean inlineTextShellCarrierOwnedByFloatingTextShell(ObjectPlan plan) {
        if (plan == null || plan.domId < 0) return false;
        if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) return false;

        AnchorCarrier carrier = nearestAnchorCarrierForSource(plan.domId);
        return carrier != null
                && carrier.story != null
                && carrierStoryOwnedByFloatingTextShell(carrier.story);
    }

    private boolean carrierStoryOwnedByFloatingTextShell(ResolvedStory story) {
        if (story == null || story.id() == null || data == null) return false;
        for (ResolvedTextFrame tf : textFramesForStory(story.id())) {
            if (tf == null || tf.id() == null) continue;
            int tfId = parseFlexibleId(tf.id());
            if (tfId < 0) continue;
            for (ObjectPlan owner : plans) {
                if (owner == null) continue;
                if (owner.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
                if (owner.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
                if (owner.placement != Placement.FLOATING) continue;
                if (contains(owner.ownedTextFrameIds, tfId)) return true;
            }
        }
        return false;
    }

    private AnchorCarrier nearestAnchorCarrierForSource(int domId) {
        if (data == null || domId < 0) return null;
        Set<Integer> seen = new HashSet<>();
        int current = domId;
        while (current >= 0 && seen.add(current)) {
            AnchorCarrier carrier = carrierForAnchor(current);
            if (carrier != null) {
                return carrier;
            }
            ResolvedPageItem item = data.getPageItem(String.valueOf(current));
            if (item == null || item.parentId() == null) break;
            current = parseFlexibleId(item.parentId());
        }
        return null;
    }

    private void resolveCompositeTextOwnershipClaimedByLeafShells() {
        LinkedHashSet<Integer> leafShellOwnedTextFrames = new LinkedHashSet<>();
        for (ObjectPlan plan : plans) {
            if (!isLeafOwnedTextShell(plan)) continue;
            for (int textFrameId : textFrameIdsForPlanIncludingOwned(plan)) {
                leafShellOwnedTextFrames.add(textFrameId);
            }
        }
        if (leafShellOwnedTextFrames.isEmpty()) return;

        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null) continue;
            if ("text_frame".equals(plan.kind)) continue;
            if (isLeafOwnedTextShell(plan)) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) continue;

            int[] retainedOwned = removeIds(plan.ownedTextFrameIds, leafShellOwnedTextFrames);
            if (retainedOwned.length == plan.ownedTextFrameIds.length) continue;

            ObjectPlan next = plan.withOwnedTextFrameIds(retainedOwned);
            if (!hasTextFrameSource(next) && retainedOwned.length == 0) {
                next = next.withTextAction(TextAction.DROP_TEXT);
            }
            plans.set(i, next);
        }
    }

    private void splitDirectLabelShellsFromCompositeCarriers() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan labelShell = plans.get(i);
            if (!isDroppedDirectLabelShellOwnedByCompositeCarrier(labelShell)) continue;
            int textFrameId = singleTextFrameSourceId(labelShell);
            if (textFrameId < 0) continue;
            if (compositeCarrierForDirectLabelShell(labelShell, textFrameId) == null) continue;

            ObjectPlan restored = labelShell
                    .withTextAction(TextAction.OWNED_BY_HWPX_TEXT)
                    .withVisualAction(VisualAction.PLACE_TEXT_SHELL,
                            "direct_label_shell_split_from_composite_carrier")
                    .withVisualLayer(textShellVisualLayer(
                            labelShell,
                            new int[] { textFrameId },
                            VisualLayer.LABEL_OVERLAY_BACKDROP))
                    .withOwnedTextFrameIds(new int[] { textFrameId })
                    .withMaterialization(Materialization.NATIVE_SOURCE_SHAPE);
            plans.set(i, restored);
            declareDirectLabelTextFramePlan(textFrameId, restored);
            removeDirectLabelShellOwnershipFromCompositeCarriers(restored, textFrameId);
        }
    }

    private void splitLabelChromeCompositeCarrierTextOwners() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan carrier = plans.get(i);
            if (!isLabelChromeCompositeCarrierTextOwner(carrier)) continue;
            LinkedHashSet<Integer> restoredTextFrames = new LinkedHashSet<>();
            for (int textFrameId : carrier.ownedTextFrameIds) {
                if (textFrameId < 0) continue;
                if (hasVisibleLeafShellTextOwner(textFrameId, carrier)) continue;
                if (declareDirectLabelTextFramePlan(textFrameId, carrier,
                        "label_chrome_carrier_direct_text_owner")) {
                    restoredTextFrames.add(textFrameId);
                }
            }
            if (restoredTextFrames.isEmpty()) continue;

            int[] retainedOwnedTextFrames = removeIds(carrier.ownedTextFrameIds, restoredTextFrames);
            ObjectPlan replacement = carrier.withOwnedTextFrameIds(retainedOwnedTextFrames);
            if (retainedOwnedTextFrames.length == 0) {
                replacement = replacement
                        .withTextAction(TextAction.DROP_TEXT)
                        .withVisualAction(carrier.visualAction,
                                "label_chrome_carrier_visual_only");
            }
            plans.set(i, replacement);
        }
    }

    private void dropNativeTextShellsBakedIntoVisibleCompositeCarriers() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan child = plans.get(i);
            if (!isNativeTextShellVisualOnlyChild(child)) continue;
            if (!hasOtherHwpxTextOwnerForAll(child.ownedTextFrameIds, child)) continue;
            ObjectPlan carrier = visibleCompositeCarrierForNativeTextShell(child);
            if (carrier == null) continue;

            plans.set(i, child
                    .withTextAction(TextAction.DROP_TEXT)
                    .withVisualAction(VisualAction.DROP_VISUAL,
                            "native_text_shell_owned_by_composite_carrier")
                    .withOwnedTextFrameIds(new int[0])
                    .withDescendantVisualObjectIds(new int[0]));
        }
    }

    private boolean isNativeTextShellVisualOnlyChild(ObjectPlan plan) {
        if (plan == null) return false;
        if (!"native_parent_text_shell".equals(plan.kind)) return false;
        if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.textAction != TextAction.DROP_TEXT) return false;
        if (plan.materialization != Materialization.NATIVE_SOURCE_SHAPE) return false;
        if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) return false;
        return visualSourceIds(plan).length > 0;
    }

    private ObjectPlan visibleCompositeCarrierForNativeTextShell(ObjectPlan child) {
        if (child == null) return null;
        for (ObjectPlan carrier : plans) {
            if (carrier == null || carrier == child) continue;
            if (carrier.pageIndex != child.pageIndex) continue;
            if (!isVisibleCompositeCarrierShell(carrier)) continue;
            if (containsAll(carrier.sourceObjectIds, child.sourceObjectIds)) return carrier;
            if (containsAll(visualSourceIds(carrier), visualSourceIds(child))) return carrier;
        }
        return null;
    }

    private boolean isVisibleCompositeCarrierShell(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.materialization != Materialization.EXTRACTED_PNG_VECTOR) return false;
        if (plan.placement != Placement.FLOATING) return false;
        if (!safe(plan.kind).contains("page_object")) return false;
        String reason = safe(plan.reason);
        return reason.endsWith("_visual_only")
                || "label_chrome_carrier_visual_only".equals(reason)
                || "composite_carrier_visual_only".equals(reason)
                || "text_composite_editable_text_hidden".equals(reason)
                || "mixed_group_text_hidden".equals(reason)
                || "complex_graphic_text_hidden".equals(reason)
                || "composite_shell_carrier_with_extra_source_material".equals(reason)
                || "editable_composite_text_hidden_shell".equals(reason);
    }

    private boolean hasOtherHwpxTextOwnerForAll(int[] textFrameIds, ObjectPlan excluded) {
        if (textFrameIds == null || textFrameIds.length == 0) return false;
        for (int textFrameId : textFrameIds) {
            if (textFrameId < 0) return false;
            if (!hasOtherHwpxTextOwner(textFrameId, excluded)) return false;
        }
        return true;
    }

    private boolean hasOtherHwpxTextOwner(int textFrameId, ObjectPlan excluded) {
        for (ObjectPlan plan : plans) {
            if (plan == null || plan == excluded) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (plan.domId == textFrameId && "text_frame".equals(plan.kind)) return true;
            if (contains(plan.ownedTextFrameIds, textFrameId)) return true;
        }
        return false;
    }

    private boolean isLabelChromeCompositeCarrierTextOwner(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.visualLayer != VisualLayer.LABEL_BACKDROP) return false;
        if (plan.materialization != Materialization.EXTRACTED_PNG_VECTOR) return false;
        if (plan.placement != Placement.FLOATING) return false;
        if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) return false;
        return "mixed_group_text_hidden".equals(plan.reason)
                || "composite_text_shell_with_extra_source_material".equals(plan.reason);
    }

    private boolean hasVisibleLeafShellTextOwner(int textFrameId, ObjectPlan carrier) {
        if (textFrameId < 0) return false;
        for (ObjectPlan owner : plans) {
            if (owner == null || owner == carrier) continue;
            if (owner.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (owner.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (!contains(owner.ownedTextFrameIds, textFrameId)) continue;
            if (owner.placement != carrier.placement) continue;
            if (owner.visualLayer == VisualLayer.LABEL_BACKDROP
                    || owner.visualLayer == VisualLayer.LABEL_OVERLAY_BACKDROP
                    || owner.materialization == Materialization.NATIVE_SOURCE_SHAPE) {
                return true;
            }
        }
        return false;
    }

    private void declareDirectLabelTextFramePlan(int textFrameId, ObjectPlan shellPlan) {
        declareDirectLabelTextFramePlan(textFrameId, shellPlan,
                "direct_label_text_split_from_composite_carrier");
    }

    private boolean declareDirectLabelTextFramePlan(
            int textFrameId,
            ObjectPlan shellPlan,
            String reason) {
        if (textFrameId < 0 || shellPlan == null) return false;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan textPlan = plans.get(i);
            if (textPlan == null) continue;
            if (!isTextFramePlanKind(textPlan)) continue;
            if (textPlan.domId != textFrameId) continue;
            ObjectPlan restored = textPlan
                    .withTextAction(TextAction.OWNED_BY_HWPX_TEXT)
                    .withVisualAction(VisualAction.DROP_VISUAL,
                            reason)
                    .withOwnedTextFrameIds(new int[] { textFrameId })
                    .withMaterialization(Materialization.HWPX_TEXT)
                    .withPlacementAndCoordinateSpace(Placement.FLOATING, CoordinateSpace.PAGE);
            plans.set(i, restored);
            return true;
        }
        ResolvedTextFrame tf = data != null ? data.getTextFrame(String.valueOf(textFrameId)) : null;
        if (tf == null || tf.sourceHidden()) return false;
        plans.add(new ObjectPlan(
                textFrameId,
                "planner_declared_text_frame:inline_composite_child",
                tf.pageIndex() >= 0 ? tf.pageIndex() : shellPlan.pageIndex,
                TextAction.OWNED_BY_HWPX_TEXT,
                VisualAction.DROP_VISUAL,
                VisualLayer.CONTENT_VISUAL,
                Placement.FLOATING,
                null,
                new int[] { textFrameId },
                new int[0],
                new int[0],
                new int[] { textFrameId },
                new int[0],
                sourceBundleKeyOf(null, new int[] { textFrameId }, new int[] { textFrameId }),
                Materialization.HWPX_TEXT,
                CoordinateSpace.PAGE,
                shellPlan.anchorOwner,
                textFrameSourceZOrder(tf),
                reason,
                null,
                textFramePlanBounds(tf, textFrameId, false),
                tf.layerId(),
                tf.layerName(),
                tf.layerIndex()));
        return true;
    }

    private static boolean isTextFramePlanKind(ObjectPlan plan) {
        String kind = safe(plan != null ? plan.kind : null);
        return "text_frame".equals(kind)
                || kind.startsWith("planner_declared_text_frame:");
    }

    private boolean isDroppedDirectLabelShellOwnedByCompositeCarrier(ObjectPlan plan) {
        if (plan == null) return false;
        if (!"native_parent_text_shell".equals(plan.kind)) return false;
        if (!"text_shell_owned_by_composite_carrier".equals(plan.reason)) return false;
        if (plan.textAction != TextAction.DROP_TEXT) return false;
        if (plan.visualAction != VisualAction.DROP_VISUAL) return false;
        if (plan.materialization != Materialization.NATIVE_SOURCE_SHAPE) return false;
        if (plan.visualLayer != VisualLayer.LABEL_BACKDROP) return false;
        return singleTextFrameSourceId(plan) >= 0 && visualSourceIds(plan).length > 0;
    }

    private int singleTextFrameSourceId(ObjectPlan plan) {
        if (plan == null || plan.sourceObjectIds == null || data == null) return -1;
        int found = -1;
        for (int sourceId : plan.sourceObjectIds) {
            if (data.getTextFrame(String.valueOf(sourceId)) == null) continue;
            if (found >= 0 && found != sourceId) return -1;
            found = sourceId;
        }
        return found;
    }

    private ObjectPlan compositeCarrierForDirectLabelShell(ObjectPlan labelShell, int textFrameId) {
        if (labelShell == null || textFrameId < 0) return null;
        for (ObjectPlan carrier : plans) {
            if (carrier == null || carrier == labelShell) continue;
            if (carrier.pageIndex != labelShell.pageIndex) continue;
            if (!isExtractedCompositeTextShellParent(carrier)) continue;
            if (!contains(carrier.ownedTextFrameIds, textFrameId)) continue;
            if (!containsAll(carrier.sourceObjectIds, labelShell.sourceObjectIds)) continue;
            return carrier;
        }
        return null;
    }

    private void removeDirectLabelShellOwnershipFromCompositeCarriers(
            ObjectPlan labelShell,
            int textFrameId) {
        if (labelShell == null || textFrameId < 0) return;
        LinkedHashSet<Integer> childSources = new LinkedHashSet<>();
        addAll(labelShell.sourceObjectIds, childSources);
        LinkedHashSet<Integer> childVisualSources = new LinkedHashSet<>();
        for (int sourceId : visualSourceIds(labelShell)) {
            childVisualSources.add(sourceId);
        }
        LinkedHashSet<Integer> childTextFrames = new LinkedHashSet<>();
        childTextFrames.add(textFrameId);

        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan carrier = plans.get(i);
            if (carrier == null || carrier == labelShell) continue;
            if (carrier.pageIndex != labelShell.pageIndex) continue;
            if (!isExtractedCompositeTextShellParent(carrier)) continue;
            if (!contains(carrier.ownedTextFrameIds, textFrameId)) continue;
            if (!containsAll(carrier.sourceObjectIds, labelShell.sourceObjectIds)) continue;

            int[] retainedSources = withoutSourcesAllowEmpty(carrier.sourceObjectIds, childSources);
            int[] retainedVisualSources = withoutSourcesAllowEmpty(visualSourceIds(carrier), childVisualSources);
            int[] retainedOwnedTextFrames = withoutSourcesAllowEmpty(carrier.ownedTextFrameIds, childTextFrames);
            int[] retainedDescendants = withoutSourcesAllowEmpty(carrier.descendantVisualObjectIds, childSources);
            ObjectPlan replacement = carrier
                    .withSourceObjectIds(retainedSources)
                    .withVisualSourceObjectIds(retainedVisualSources)
                    .withOwnedTextFrameIds(retainedOwnedTextFrames)
                    .withDescendantVisualObjectIds(retainedDescendants);
            if (retainedOwnedTextFrames.length == 0) {
                replacement = replacement
                        .withTextAction(TextAction.DROP_TEXT)
                        .withVisualAction(VisualAction.DROP_VISUAL,
                                "composite_carrier_split_into_direct_label_shells");
            }
            plans.set(i, replacement);
        }
    }

    private boolean isLeafOwnedTextShell(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) return false;
        String reason = safe(plan.reason);
        return reason.contains("atomic_ownership_root_text_hidden_shell")
                || reason.contains("leaf_group_text_hidden_shell")
                || reason.contains("direct_text_hidden_shell")
                || reason.contains("direct_label_shell_split_from_composite_carrier")
                || isDirectInlineTextShellReason(reason);
    }

    private int[] textFrameIdsForPlanIncludingOwned(ObjectPlan plan) {
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        if (plan != null && plan.ownedTextFrameIds != null) {
            for (int id : plan.ownedTextFrameIds) {
                if (id >= 0) ids.add(id);
            }
        }
        if (plan != null && plan.sourceObjectIds != null) {
            for (int sourceId : plan.sourceObjectIds) {
                if (data.getTextFrame(String.valueOf(sourceId)) != null) {
                    ids.add(sourceId);
                }
            }
        }
        return toIntArray(ids);
    }

    private static int[] removeIds(int[] values, Set<Integer> removed) {
        if (values == null || values.length == 0 || removed == null || removed.isEmpty()) {
            return values != null ? values : new int[0];
        }
        List<Integer> retained = new ArrayList<>();
        for (int value : values) {
            if (removed.contains(value)) continue;
            retained.add(value);
        }
        return toIntArray(retained);
    }

    private boolean isInlineGraphicOnlyTextShellWithoutTextlessCarrier(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.placement != Placement.INLINE) return false;
        if (!safe(plan.kind).contains("inline_object")) return false;
        if (!"inline_graphic_only".equals(plan.reason)) return false;
        if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL
                && plan.visualAction != VisualAction.PLACE_INLINE_PNG
                && plan.visualAction != VisualAction.DROP_VISUAL) {
            return false;
        }
        if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) return false;
        if (hasExplicitTextlessShellSignal(plan)) return false;
        RenderedGroup rendered = renderedGroupForPlan(plan);
        return rendered == null || !hasExplicitTextlessShellSignal(rendered);
    }

    private boolean isNonCanonicalAtomicObjectPlan(ObjectPlan plan) {
        if (plan == null || data == null) return false;
        RenderedGroup rg = renderedGroupForPlan(plan);
        return rg != null && data.isNonCanonicalAtomicObjectRender(rg);
    }

    private boolean isInlineTextShellPlan(ObjectPlan plan) {
        if (plan == null || plan.placement != Placement.INLINE) return false;
        if (!safe(plan.kind).contains("inline_object")) return false;
        if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) return false;
        if (visualSourceIds(plan).length == 0) return false;
        for (int tfId : plan.ownedTextFrameIds) {
            ResolvedTextFrame tf = data != null ? data.getTextFrame(String.valueOf(tfId)) : null;
            if (tf == null || !tf.isInline()) return false;
        }
        return true;
    }

    private void resolveTextShellSourceDuplicates() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan owner = plans.get(i);
            if (!isVisibleTextShell(owner)) continue;
            for (int j = 0; j < plans.size(); j++) {
                if (i == j) continue;
                ObjectPlan child = plans.get(j);
                if (!isVisibleTextShell(child)) continue;
                boolean sourcePositionedInlineShell = ownsSourcePositionedInlineTextShell(owner, child);
                if (child.placement == Placement.INLINE && !sourcePositionedInlineShell) continue;
                if (child.pageIndex != owner.pageIndex) continue;
                if (child.domId == owner.domId && isSameRenderPlan(owner, child)) continue;
                if (!parentTextShellMayOwnDescendantVisual(owner, child)
                        && !sourcePositionedInlineShell) {
                    continue;
                }
                if (inlineTextShellOwnsMoreSpecificText(child, owner)) continue;
                if (!textShellOwnerCoversChild(owner, child)) continue;
                if (childOwnsDistinctShellSlot(owner, child)) continue;
                if (nativeSourceShellHasDirectTextOwner(child)) continue;
                ObjectPlan dropped = child.withVisualAction(VisualAction.DROP_VISUAL,
                        "visual_source_owned_by_parent_text_shell");
                if (!"text_frame".equals(dropped.kind)) {
                    dropped = dropped.withTextAction(TextAction.DROP_TEXT);
                }
                plans.set(j, dropped);
            }
        }
    }

    private void normalizeDistinctChildTextShellSourceSlots() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan owner = plans.get(i);
            if (!isVisibleTextShell(owner)) continue;
            LinkedHashSet<Integer> childSources = new LinkedHashSet<>();
            for (int j = 0; j < plans.size(); j++) {
                if (i == j) continue;
                ObjectPlan child = plans.get(j);
                if (!isVisibleTextShell(child)) continue;
                if (child.pageIndex != owner.pageIndex) continue;
                if (!childOwnsDistinctShellSlot(owner, child)) continue;
                if (!textShellOwnerCoversChild(owner, child)
                        && !ownsSourcePositionedInlineTextShell(owner, child)
                        && !isDescendantVisualOfParentTextShell(owner, child)) {
                    continue;
                }
                for (int sourceId : visualSourceIds(child)) {
                    if (sourceId != owner.domId) childSources.add(sourceId);
                }
            }
            if (childSources.isEmpty()) continue;
            int[] retained = withoutSources(visualSourceIds(owner), childSources);
            int[] retainedDescendants = withoutSources(owner.descendantVisualObjectIds, childSources);
            boolean visualChanged = retained.length != visualSourceIds(owner).length;
            boolean descendantChanged = retainedDescendants.length != owner.descendantVisualObjectIds.length;
            if (!visualChanged && !descendantChanged) continue;
            plans.set(i, owner
                    .withVisualSourceObjectIds(retained)
                    .withDescendantVisualObjectIds(retainedDescendants));
        }
    }

    private void normalizeParentTextShellChildInlineSlots() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan owner = plans.get(i);
            if (!isVisibleTextShell(owner)) continue;
            if (owner.placement != Placement.FLOATING) continue;
            if (owner.ownedTextFrameIds == null || owner.ownedTextFrameIds.length == 0) continue;

            LinkedHashSet<Integer> childSources = new LinkedHashSet<>();
            LinkedHashSet<Integer> childTextFrames = new LinkedHashSet<>();
            for (ObjectPlan child : plans) {
                if (child == null || child == owner) continue;
                if (!isVisibleTextShell(child)) continue;
                if (child.placement != Placement.INLINE) continue;
                if (child.pageIndex != owner.pageIndex) continue;
                if (child.ownedTextFrameIds == null || child.ownedTextFrameIds.length == 0) continue;
                if (!containsAny(owner.ownedTextFrameIds, child.ownedTextFrameIds)) continue;
                if (!ownsSourcePositionedInlineTextShell(owner, child)
                        && !isDescendantVisualOfParentTextShell(owner, child)
                        && !containsAny(owner.sourceObjectIds, child.sourceObjectIds)) {
                    continue;
                }
                addAll(child.sourceObjectIds, childSources);
                addAll(child.ownedTextFrameIds, childTextFrames);
            }
            if (childSources.isEmpty() && childTextFrames.isEmpty()) continue;

            int[] retainedSources = withoutSources(owner.sourceObjectIds, childSources);
            int[] retainedOwnedTextFrames = withoutSources(owner.ownedTextFrameIds, childTextFrames);
            int[] retainedDescendants = withoutSources(owner.descendantVisualObjectIds, childSources);
            if (retainedSources.length == owner.sourceObjectIds.length
                    && retainedOwnedTextFrames.length == owner.ownedTextFrameIds.length
                    && retainedDescendants.length == owner.descendantVisualObjectIds.length) {
                continue;
            }
            plans.set(i, owner
                    .withSourceObjectIds(retainedSources)
                    .withOwnedTextFrameIds(retainedOwnedTextFrames)
                    .withDescendantVisualObjectIds(retainedDescendants));
        }
    }

    private static boolean sameIntSet(int[] a, int[] b) {
        if (a == null || b == null) return a == b;
        return containsAll(a, b) && containsAll(b, a);
    }

    private static int[] mergeIds(int[] first, int[] second) {
        LinkedHashSet<Integer> merged = new LinkedHashSet<>();
        if (first != null) {
            for (int id : first) merged.add(id);
        }
        if (second != null) {
            for (int id : second) merged.add(id);
        }
        return toIntArray(merged);
    }

    private void resolveDuplicateTextShellTextOwners() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan a = plans.get(i);
            if (!isVisibleTextShellWithOwnedText(a)) continue;
            for (int j = i + 1; j < plans.size(); j++) {
                ObjectPlan b = plans.get(j);
                if (!isVisibleTextShellWithOwnedText(b)) continue;
                if (a.pageIndex != b.pageIndex) continue;
                if (!sameIntSet(a.ownedTextFrameIds, b.ownedTextFrameIds)) continue;
                int keep = chooseCanonicalTextShellOwnerIndex(i, j);
                int drop = keep == i ? j : i;
                ObjectPlan loser = plans.get(drop);
                if (sameTextOwnerButDistinctShellSlot(a, b)) {
                    plans.set(drop, loser.withTextAction(TextAction.DROP_TEXT));
                    if (drop == i) {
                        break;
                    }
                    continue;
                }
                plans.set(drop, loser
                        .withTextAction(TextAction.DROP_TEXT)
                        .withVisualAction(VisualAction.DROP_VISUAL,
                                "duplicate_text_shell_text_owner")
                        .withOwnedTextFrameIds(new int[0])
                        .withDescendantVisualObjectIds(new int[0]));
                if (drop == i) {
                    break;
                }
            }
        }
    }

    private static boolean isVisibleTextShellWithOwnedText(ObjectPlan plan) {
        return isVisibleTextShell(plan)
                && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                && plan.ownedTextFrameIds != null
                && plan.ownedTextFrameIds.length > 0;
    }

    private static boolean sameTextOwnerButDistinctShellSlot(ObjectPlan a, ObjectPlan b) {
        if (a == null || b == null) return false;
        if (a.visualAction != VisualAction.PLACE_TEXT_SHELL
                || b.visualAction != VisualAction.PLACE_TEXT_SHELL) {
            return false;
        }
        int[] aVisual = visualSourceIds(a);
        int[] bVisual = visualSourceIds(b);
        if (aVisual.length == 0 || bVisual.length == 0) return false;
        return !containsAny(aVisual, bVisual);
    }

    private void resolveCompositeCarrierTextFrameOwners() {
        List<ObjectPlan> carriers = new ArrayList<>();
        List<Integer> carrierIndexes = new ArrayList<>();
        LinkedHashSet<Integer> carrierOwnedTextFrames = new LinkedHashSet<>();
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!isCompositeCarrierTextFrameOwner(plan)) continue;
            carrierIndexes.add(i);
            carriers.add(plan);
            addAll(plan.ownedTextFrameIds, carrierOwnedTextFrames);
        }
        if (carriers.isEmpty() || carrierOwnedTextFrames.isEmpty()) return;

        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null) continue;
            if (carriers.contains(plan)) continue;

            if ("text_frame".equals(plan.kind) && carrierOwnedTextFrames.contains(plan.domId)) {
                ObjectPlan carrier = compositeCarrierForTextFrame(plan.domId, carriers);
                if (carrier != null) {
                    plans.set(i, declareDirectTextFrameFromCompositeCarrier(plan, carrier));
                }
                continue;
            }

            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) continue;
            if (!containsAnySet(plan.ownedTextFrameIds, carrierOwnedTextFrames)) continue;
            if (!coveredByCompositeCarrier(plan, carriers)) continue;

            plans.set(i, plan
                    .withTextAction(TextAction.DROP_TEXT)
                    .withVisualAction(VisualAction.DROP_VISUAL,
                            "text_shell_owned_by_composite_carrier")
                    .withOwnedTextFrameIds(new int[0])
                    .withDescendantVisualObjectIds(new int[0]));
        }

        for (int index : carrierIndexes) {
            if (index < 0 || index >= plans.size()) continue;
            ObjectPlan carrier = plans.get(index);
            if (carrier == null) continue;
            plans.set(index, carrier
                    .withTextAction(TextAction.DROP_TEXT)
                    .withVisualAction(VisualAction.PLACE_TEXT_SHELL,
                            "composite_carrier_visual_only")
                    .withOwnedTextFrameIds(new int[0])
                    .withDescendantVisualObjectIds(new int[0]));
        }
    }

    private void normalizeVisualOnlyTextShellsDoNotOwnTextSlots() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null) continue;
            if (plan.textAction != TextAction.DROP_TEXT) continue;
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (hasVisibleEditableTextFrameSource(plan)) continue;
            if ((plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0)
                    && (plan.descendantVisualObjectIds == null || plan.descendantVisualObjectIds.length == 0)) {
                continue;
            }
            plans.set(i, plan
                    .withOwnedTextFrameIds(new int[0])
                    .withDescendantVisualObjectIds(new int[0]));
        }
    }

    private void declareOrphanedPureDecorationVisuals() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!isDroppedPureDecorationVisual(plan)) continue;
            if (hasVisibleVisualOwnerForSameDom(plan)) continue;
            VisualAction action = plan.placement == Placement.INLINE
                    ? VisualAction.PLACE_INLINE_PNG
                    : VisualAction.PLACE_FLOATING_PNG;
            plans.set(i, plan
                    .withTextAction(TextAction.DROP_TEXT)
                    .withVisualAction(action, plan.reason));
        }
    }

    private void dropNonCanonicalRenderedGraphicFrameSlots() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null || !plan.hasVisibleVisual()) continue;
            if (!safe(plan.kind).startsWith("rendered_graphic_frame:")) continue;
            if (!hasCanonicalNonGraphicRenderedChannelForSameSlot(plan)) continue;
            plans.set(i, plan.withVisualAction(VisualAction.DROP_VISUAL,
                    "alternate_pass_candidate_owned_by_canonical_channel"));
        }
    }

    private boolean hasCanonicalNonGraphicRenderedChannelForSameSlot(ObjectPlan graphicPlan) {
        if (graphicPlan == null) return false;
        for (ObjectPlan candidate : plans) {
            if (candidate == null || candidate == graphicPlan) continue;
            if (candidate.pageIndex != graphicPlan.pageIndex) continue;
            if (!isCanonicalNonGraphicRenderedChannel(candidate)) continue;
            if (sameRenderedSlot(candidate, graphicPlan)) return true;
        }
        return false;
    }

    private static boolean isCanonicalNonGraphicRenderedChannel(ObjectPlan plan) {
        if (plan == null) return false;
        String kind = safe(plan.kind);
        return kind.startsWith("rendered_floating_item:")
                || kind.startsWith("rendered_image_frame:")
                || kind.startsWith("rendered_pdf_frame:")
                || (kind.startsWith("rendered_floating_item:") && plan.placement == Placement.INLINE);
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

    private void normalizeDuplicateVisibleSourceSlots() {
        int suppressed = 0;
        Map<String, List<Integer>> bySource = new LinkedHashMap<>();
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null || !plan.hasVisibleVisual()) continue;
            for (int sourceId : duplicateVisibleSourceIds(plan)) {
                String key = plan.pageIndex + ":" + sourceId;
                bySource.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
            }
        }
        for (Map.Entry<String, List<Integer>> e : bySource.entrySet()) {
            List<Integer> indexes = e.getValue();
            if (indexes.size() <= 1) continue;
            int sourceId = parseDuplicateSourceKey(e.getKey());
            int winner = chooseVisibleSourceOwner(sourceId, indexes);
            warn("DUPLICATE_VISIBLE_SOURCE_SLOT_REPAIR_SUPPRESSED",
                    "sourceId=" + sourceId
                            + " canonicalIndex=" + winner
                            + " candidateIndexes=" + indexes);
            suppressed++;
        }
        ConversionTiming.metric("stage1.ownershipPlanner.normalizeDuplicateVisibleSourceSlots.suppressed",
                suppressed);
    }

    private void dropNativeSourceShapesOwnedByRenderedBundles() {
        if (data == null) return;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan nativeShape = plans.get(i);
            if (!isVisibleNativeSourceShapeFallback(nativeShape)) continue;
            if (renderedBundleOwnerForNativeSourceShape(nativeShape) == null) continue;
            plans.set(i, nativeShape
                    .withTextAction(TextAction.DROP_TEXT)
                    .withVisualAction(VisualAction.DROP_VISUAL,
                            "native_source_shape_owned_by_rendered_source_bundle")
                    .withVisualSourceObjectIds(new int[0])
                    .withStyleSourceObjectIds(new int[0])
                    .withOwnedTextFrameIds(new int[0])
                    .withDescendantVisualObjectIds(new int[0]));
        }
    }

    private static boolean isVisibleNativeSourceShapeFallback(ObjectPlan plan) {
        return plan != null
                && plan.hasVisibleVisual()
                && plan.materialization == Materialization.NATIVE_SOURCE_SHAPE
                && plan.textAction == TextAction.DROP_TEXT
                && (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0)
                && (plan.sourceObjectIds != null && plan.sourceObjectIds.length > 0);
    }

    private ObjectPlan renderedBundleOwnerForNativeSourceShape(ObjectPlan nativeShape) {
        if (nativeShape == null) return null;
        for (ObjectPlan owner : plans) {
            if (!isRenderedBundleVisualOwner(owner, nativeShape)) continue;
            if (renderedBundleContainsNativeSourceShape(owner, nativeShape)) return owner;
        }
        return null;
    }

    private static boolean isRenderedBundleVisualOwner(ObjectPlan owner, ObjectPlan nativeShape) {
        if (!isVisibleRenderedVisual(owner)) return false;
        if (owner == nativeShape) return false;
        if (owner.pageIndex != nativeShape.pageIndex) return false;
        if (owner.materialization == Materialization.NATIVE_SOURCE_SHAPE) return false;
        if (owner.visualAction != VisualAction.PLACE_FLOATING_PNG
                && owner.visualAction != VisualAction.PLACE_INLINE_PNG
                && owner.visualAction != VisualAction.PLACE_TEXT_SHELL) {
            return false;
        }
        if (safe(owner.file).isEmpty()) return false;
        return owner.sourceObjectIds != null
                && nativeShape.sourceObjectIds != null
                && owner.sourceObjectIds.length > nativeShape.sourceObjectIds.length;
    }

    private boolean renderedBundleContainsNativeSourceShape(ObjectPlan owner, ObjectPlan nativeShape) {
        if (owner == null || nativeShape == null || data == null) return false;
        RenderedGroup rendered = renderedGroupForPlan(owner);
        int[] exportedSources = rendered != null ? rendered.exportSourceObjectIds() : null;
        boolean hasExplicitExportSources = exportedSources != null && exportedSources.length > 0;
        for (int sourceId : nativeShape.sourceObjectIds) {
            if (!contains(owner.sourceObjectIds, sourceId)) continue;
            if (hasExplicitExportSources && !contains(exportedSources, sourceId)) continue;
            if (sourceId == owner.domId) continue;
            if (isSourceDescendantOf(sourceId, owner.domId)) return true;
        }
        for (int sourceId : visualSourceIds(nativeShape)) {
            if (!contains(owner.sourceObjectIds, sourceId)) continue;
            if (hasExplicitExportSources && !contains(exportedSources, sourceId)) continue;
            if (sourceId == owner.domId) continue;
            if (isSourceDescendantOf(sourceId, owner.domId)) return true;
        }
        return false;
    }

    private boolean isSourceDescendantOf(int sourceId, int ancestorId) {
        if (sourceId <= 0 || ancestorId <= 0 || sourceId == ancestorId || data == null) return false;
        ResolvedPageItem source = data.getPageItem(String.valueOf(sourceId));
        for (int depth = 0; depth < 64 && source != null; depth++) {
            String parentId = source.parentId();
            if (parentId == null || parentId.isEmpty()) return false;
            int parent = parseInt(parentId, -1);
            if (parent == ancestorId) return true;
            if (parent <= 0 || parent == sourceId) return false;
            source = data.getPageItem(parentId);
        }
        return false;
    }

    private void dropEditableTextFrameFallbackShellsOwnedByCompositeSlots() {
        if (data == null) return;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan fallback = plans.get(i);
            if (!isEditableTextFrameFallbackShell(fallback)) continue;
            LinkedHashSet<Integer> shellStyleSources = visibleTextFrameShellStyleSources(fallback);
            if (shellStyleSources.isEmpty()) continue;
            if (!hasCompositeShellOwnerForAnySource(fallback, shellStyleSources)) continue;
            plans.set(i, fallback
                    .withTextAction(TextAction.DROP_TEXT)
                    .withVisualAction(VisualAction.DROP_VISUAL,
                            "textframe_shell_style_source_owned_by_composite_shell_slot")
                    .withVisualSourceObjectIds(new int[0])
                    .withStyleSourceObjectIds(new int[0])
                    .withOwnedTextFrameIds(new int[0])
                    .withDescendantVisualObjectIds(new int[0]));
        }
    }

    private boolean isEditableTextFrameFallbackShell(ObjectPlan plan) {
        return isVisibleRenderedVisual(plan)
                && plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                && "editable_textframe_visual_shell".equals(safe(plan.reason));
    }

    private LinkedHashSet<Integer> visibleTextFrameShellStyleSources(ObjectPlan plan) {
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        if (plan == null) return result;
        collectVisibleTextFrameShellStyleSources(plan.visualSourceObjectIds, result);
        collectVisibleTextFrameShellStyleSources(plan.styleSourceObjectIds, result);
        collectVisibleTextFrameShellStyleSources(plan.sourceObjectIds, result);
        return result;
    }

    private void collectVisibleTextFrameShellStyleSources(int[] sourceIds, LinkedHashSet<Integer> result) {
        if (sourceIds == null || result == null) return;
        for (int sourceId : sourceIds) {
            if (sourceIdHasVisibleTextFrameShellMaterial(sourceId)) {
                result.add(sourceId);
            }
        }
    }

    private boolean hasCompositeShellOwnerForAnySource(ObjectPlan fallback, Set<Integer> shellStyleSources) {
        if (fallback == null || shellStyleSources == null || shellStyleSources.isEmpty()) return false;
        for (ObjectPlan owner : plans) {
            if (owner == null || owner == fallback) continue;
            if (!isVisibleRenderedVisual(owner)) continue;
            if (owner.pageIndex != fallback.pageIndex) continue;
            if (owner.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if ("editable_textframe_visual_shell".equals(safe(owner.reason))) continue;
            if (!planExecutesAnyTextFrameShellStyleSource(owner, shellStyleSources)) continue;
            return true;
        }
        return false;
    }

    private boolean planExecutesAnyTextFrameShellStyleSource(ObjectPlan plan, Set<Integer> sourceIds) {
        if (plan == null || sourceIds == null || sourceIds.isEmpty()) return false;
        RenderedGroup rendered = renderedGroupForPlan(plan);
        if (rendered != null && rendered.exportSourceObjectIds() != null) {
            for (int sourceId : rendered.exportSourceObjectIds()) {
                if (sourceIds.contains(sourceId)) return true;
            }
        }
        for (int sourceId : plan.visualSourceObjectIds) {
            if (sourceIds.contains(sourceId)) return true;
        }
        for (int sourceId : plan.styleSourceObjectIds) {
            if (sourceIds.contains(sourceId)) return true;
        }
        for (int sourceId : plan.sourceObjectIds) {
            if (sourceIds.contains(sourceId)) return true;
        }
        return false;
    }

    private void bindPaperInlineAnchorsToPageMaterialSlots() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan inline = plans.get(i);
            if (!isCanonicalStoryFlowInlineVisualSlot(inline)) continue;
            if (!isPaperOnlyVisualSource(inline)) continue;
            ObjectPlan material = findPageMaterialForPaperInlineAnchor(inline);
            if (material == null) continue;
            plans.set(i, inline.withVisibleMaterialFrom(material,
                    "paper_inline_anchor_uses_page_material_slot"));
            dropConsumedPageMaterialPlan(material.domId,
                    "page_material_slot_consumed_by_paper_inline_anchor");
        }
    }

    private void normalizeStoryFlowInlineVisualMaterialSlots() {
        int normalized = 0;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!isStoryFlowInlineVisualMaterialSlot(plan)) continue;
            if (isStoryFlowInlineShellDecorationSource(plan)) {
                plans.set(i, dropStoryFlowInlineShellDecoration(plan,
                        "story_flow_inline_shell_decoration_covered_by_text_shell_or_page_plane"));
                normalized++;
                continue;
            }
            ObjectPlan replacement = plan
                    .withVisualAction(VisualAction.PLACE_INLINE_PNG,
                            "story_flow_inline_anchor_material_slot")
                    .withPlacementAndCoordinateSpace(Placement.INLINE, CoordinateSpace.STORY_FLOW);
            plans.set(i, replacement);
            normalized++;
        }
        ConversionTiming.metric("stage1.ownershipPlanner.storyFlowInlineVisualMaterial.normalized",
                normalized);
    }

    private ObjectPlan dropStoryFlowInlineShellDecoration(ObjectPlan plan, String reason) {
        return plan
                .withTextAction(TextAction.DROP_TEXT)
                .withVisualAction(VisualAction.DROP_VISUAL, reason)
                .withVisualSourceObjectIds(new int[0])
                .withDescendantVisualObjectIds(new int[0])
                .withOwnedTextFrameIds(new int[0]);
    }

    private boolean isStoryFlowInlineShellDecorationSource(ObjectPlan plan) {
        if (plan == null || data == null) return false;
        if (!hasInlineObjectPlanSignal(plan) && !hasStoryFlowInlineAnchorMaterialSignal(plan)) return false;
        if (hasPlacedContentSourceTree(plan)) return false;
        if (hasTextFrameSourceTree(plan)) return false;
        String slotRole = safe(plan.slotRole);
        String candidateId = safe(plan.candidateId);
        if (!"inline_flow_visual_root".equals(slotRole)
                && !candidateId.contains("inline_flow_visual_root")) {
            return false;
        }
        int[] visualIds = visualSourceIds(plan);
        if (visualIds.length == 0) visualIds = plan.sourceObjectIds != null ? plan.sourceObjectIds : new int[0];
        if (visualIds.length != 1) return false;
        ResolvedPageItem item = data.getPageItem(String.valueOf(visualIds[0]));
        if (!isInlineTextShellDecorationItem(item)) return false;
        return hasResolvedInlineAnchor(visualIds[0]) || item.isInline() || item.storyTextInlineSlot();
    }

    private static boolean isInlineTextShellDecorationItem(ResolvedPageItem item) {
        if (item == null || item.sourceHidden()) return false;
        String type = safe(item.type());
        if (!"Polygon".equals(type)
                && !"Rectangle".equals(type)
                && !"Oval".equals(type)
                && !"GraphicLine".equals(type)) {
            return false;
        }
        if ("GraphicLine".equals(type)) return false;
        if (!isPaperColor(item.fillColorName())) return false;
        return item.strokeWeight() > 0.01
                && !isNoneColor(item.strokeColorName())
                && !isPaperColor(item.strokeColorName());
    }

    private boolean isStoryFlowInlineVisualMaterialSlot(ObjectPlan plan) {
        if (!isVisibleRenderedVisual(plan)) return false;
        if ("pass.master_page_graphics".equals(safe(plan.planPassId))) return false;
        if (isMasterPageGraphicPlan(plan)) return false;
        if (plan.placement != Placement.FLOATING || plan.coordinateSpace != CoordinateSpace.PAGE) {
            return false;
        }
        if (plan.visualAction != VisualAction.PLACE_FLOATING_PNG
                && plan.visualAction != VisualAction.PLACE_TEXT_SHELL) {
            return false;
        }
        if (isShellOnlyVisualSlot(plan)) return false;
        if (hasIdmlAnchoredPagePositionSource(plan)) return false;
        if (!isVisualOnlyPlan(plan)) return false;
        if (!hasStoryFlowInlineAnchorMaterialSignal(plan)) return false;
        if (hasPlacedContentSourceTree(plan)) return false;
        if (hasTextFrameSourceTree(plan)) return false;
        return true;
    }

    private static boolean isMasterPageGraphicPlan(ObjectPlan plan) {
        if (plan == null) return false;
        String reason = safe(plan.reason);
        if ("master_graphic".equals(reason)
                || "master_graphic_textless".equals(reason)
                || "master_page_graphic".equals(reason)
                || "master_side_composite".equals(reason)) {
            return true;
        }
        String kind = safe(plan.kind);
        if (kind.contains("pass.master_page_graphics")) return true;
        String candidateId = safe(plan.candidateId);
        if (candidateId.contains("pass.master_page_graphics")) return true;
        String file = safe(plan.file);
        return file.contains("rendered_frames/master_")
                || file.startsWith("master_")
                || file.contains("/master_");
    }

    private static boolean isShellOnlyVisualSlot(ObjectPlan plan) {
        if (plan == null) return false;
        String slotRole = safe(plan.slotRole);
        if ("shell_slot_only".equals(slotRole)) return true;
        if ("direct_child_shell_slot".equals(slotRole)) return true;
        if ("TEXTLESS_SHELL_SLOT".equals(slotRole)) return true;
        String candidateId = safe(plan.candidateId);
        return candidateId.contains(".shell_slot_only")
                || candidateId.endsWith("shell_slot_only")
                || candidateId.contains(".direct_child_shell_slot")
                || candidateId.endsWith("direct_child_shell_slot");
    }

    private boolean hasStoryFlowInlineAnchorMaterialSignal(ObjectPlan plan) {
        if (plan == null || data == null) return false;
        if (hasResolvedInlineAnchor(plan.domId)) return true;
        for (int sourceId : plan.sourceObjectIds) {
            if (hasResolvedInlineAnchor(sourceId)) return true;
        }
        for (int sourceId : visualSourceIds(plan)) {
            if (hasResolvedInlineAnchor(sourceId)) return true;
        }
        return false;
    }

    private ObjectPlan findPageMaterialForPaperInlineAnchor(ObjectPlan inline) {
        ObjectPlan best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (ObjectPlan candidate : plans) {
            if (!isFloatingPageVisualOnlyCloneCandidate(candidate)) continue;
            if (!samePaperInlineMaterialSlot(inline, candidate)) continue;
            double score = boundsOverlapRatio(inline.bounds, candidate.bounds) * 1000.0
                    + sameBoundsDeltaScore(inline.bounds, candidate.bounds);
            if (best == null || score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private void dropConsumedPageMaterialPlan(int domId, String reason) {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null || plan.domId != domId) continue;
            plans.set(i, plan
                    .withTextAction(TextAction.DROP_TEXT)
                    .withVisualAction(VisualAction.DROP_VISUAL, reason)
                    .withVisualSourceObjectIds(new int[0])
                    .withDescendantVisualObjectIds(new int[0])
                    .withOwnedTextFrameIds(new int[0]));
        }
    }

    private boolean samePaperInlineMaterialSlot(ObjectPlan inline, ObjectPlan material) {
        if (inline == null || material == null) return false;
        if (inline.pageIndex != material.pageIndex) return false;
        if (!sameKnownSourceLayer(inline, material)) return false;
        if (!sameBounds(inline.bounds, material.bounds, 0.35)) return false;
        if (!hasVisibleNonPaperMaterial(material)) return false;
        double inlineArea = area(inline.bounds);
        double materialArea = area(material.bounds);
        if (inlineArea <= 0.0 || materialArea <= 0.0) return false;
        double areaRatio = Math.min(inlineArea, materialArea) / Math.max(inlineArea, materialArea);
        return areaRatio >= 0.92 && boundsMostlyOverlap(inline.bounds, material.bounds, 0.95);
    }

    private double sameBoundsDeltaScore(double[] a, double[] b) {
        if (a == null || b == null || a.length < 4 || b.length < 4) return 0.0;
        return -Math.abs(a[0] - b[0])
                - Math.abs(a[1] - b[1])
                - Math.abs(a[2] - b[2])
                - Math.abs(a[3] - b[3]);
    }

    private double boundsOverlapRatio(double[] a, double[] b) {
        if (a == null || b == null || a.length < 4 || b.length < 4) return 0.0;
        double top = Math.max(Math.min(a[0], a[2]), Math.min(b[0], b[2]));
        double left = Math.max(Math.min(a[1], a[3]), Math.min(b[1], b[3]));
        double bottom = Math.min(Math.max(a[0], a[2]), Math.max(b[0], b[2]));
        double right = Math.min(Math.max(a[1], a[3]), Math.max(b[1], b[3]));
        double overlap = Math.max(0.0, bottom - top) * Math.max(0.0, right - left);
        double area = Math.min(area(a), area(b));
        return area > 0.0 ? overlap / area : 0.0;
    }

    private void dropFloatingPageClonesOwnedByStoryFlowInlineSlots() {
        List<ObjectPlan> inlineOwners = new ArrayList<>();
        for (ObjectPlan plan : plans) {
            if (isCanonicalStoryFlowInlineVisualSlot(plan)) {
                inlineOwners.add(plan);
            }
        }
        if (inlineOwners.isEmpty()) return;

        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan floating = plans.get(i);
            if (!isFloatingPageVisualOnlyCloneCandidate(floating)) continue;
            for (ObjectPlan inline : inlineOwners) {
                if (!sameInlineVisualSlotClone(inline, floating)) continue;
                plans.set(i, floating
                        .withTextAction(TextAction.DROP_TEXT)
                        .withVisualAction(VisualAction.DROP_VISUAL,
                                "story_flow_inline_slot_owns_page_clone")
                        .withVisualSourceObjectIds(new int[0])
                        .withDescendantVisualObjectIds(new int[0])
                        .withOwnedTextFrameIds(new int[0]));
                break;
            }
        }
    }

    private boolean isCanonicalStoryFlowInlineVisualSlot(ObjectPlan plan) {
        if (!isVisibleRenderedVisual(plan)) return false;
        if (plan.placement != Placement.INLINE) return false;
        if (plan.coordinateSpace != CoordinateSpace.STORY_FLOW) return false;
        if (plan.visualAction != VisualAction.PLACE_INLINE_PNG
                && plan.visualAction != VisualAction.PLACE_TEXT_SHELL) {
            return false;
        }
        if (!isVisualOnlyPlan(plan)) return false;
        if (!hasInlineSourceSignal(plan)) return false;
        if (hasPlacedContentSourceTree(plan)) return false;
        if (hasTextFrameSourceTree(plan)) return false;
        return true;
    }

    private boolean isFloatingPageVisualOnlyCloneCandidate(ObjectPlan plan) {
        if (!isVisibleRenderedVisual(plan)) return false;
        if (plan.placement != Placement.FLOATING) return false;
        if (plan.coordinateSpace != CoordinateSpace.PAGE) return false;
        if (plan.visualAction != VisualAction.PLACE_FLOATING_PNG
                && plan.visualAction != VisualAction.PLACE_TEXT_SHELL) {
            return false;
        }
        if (!isVisualOnlyPlan(plan)) return false;
        if (hasPlacedContentSourceTree(plan)) return false;
        if (hasTextFrameSourceTree(plan)) return false;
        String kind = safe(plan.kind);
        return kind.startsWith("rendered_floating_item:")
                || kind.startsWith("rendered_graphic_frame:");
    }

    private boolean sameInlineVisualSlotClone(ObjectPlan inline, ObjectPlan floating) {
        if (inline == null || floating == null) return false;
        if (inline.pageIndex != floating.pageIndex) return false;
        if (!sameKnownSourceLayer(inline, floating)) return false;
        if (!sameBounds(inline.bounds, floating.bounds, 0.35)) return false;
        if (!sameVisualSourceStyleFingerprint(inline, floating)) return false;
        double inlineArea = area(inline.bounds);
        double floatingArea = area(floating.bounds);
        if (inlineArea <= 0.0 || floatingArea <= 0.0) return false;
        double areaRatio = Math.min(inlineArea, floatingArea) / Math.max(inlineArea, floatingArea);
        return areaRatio >= 0.92 && boundsMostlyOverlap(inline.bounds, floating.bounds, 0.95);
    }

    private boolean sameVisualSourceStyleFingerprint(ObjectPlan a, ObjectPlan b) {
        String af = visualSourceStyleFingerprint(a);
        String bf = visualSourceStyleFingerprint(b);
        return !af.isEmpty() && af.equals(bf);
    }

    private String visualSourceStyleFingerprint(ObjectPlan plan) {
        if (plan == null || data == null) return "";
        List<String> parts = new ArrayList<>();
        LinkedHashSet<Integer> roots = new LinkedHashSet<>();
        addAll(visualSourceIds(plan), roots);
        if (roots.isEmpty()) addAll(plan.sourceObjectIds, roots);
        for (int sourceId : roots) {
            collectVisualSourceStyleFingerprint(sourceId, parts, 0);
        }
        if (parts.isEmpty()) return "";
        parts.sort(String::compareTo);
        return String.join(";", parts);
    }

    private void collectVisualSourceStyleFingerprint(int sourceId, List<String> parts, int depth) {
        if (data == null || sourceId <= 0 || depth > 8) return;
        ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
        if (item == null || item.sourceHidden()) return;
        String type = safe(item.type());
        if ("TextFrame".equals(type)) return;
        if (item.childIds() != null && item.childIds().length > 0) {
            for (int childId : item.childIds()) {
                collectVisualSourceStyleFingerprint(childId, parts, depth + 1);
            }
            return;
        }
        if ("Group".equals(type)) return;
        parts.add(type
                + "|fill=" + safe(item.fillColorName())
                + "@" + rounded(item.fillTint())
                + "|stroke=" + safe(item.strokeColorName())
                + "@" + rounded(item.strokeTint())
                + "/" + rounded(item.strokeWeight())
                + "|opacity=" + rounded(item.opacity())
                + "|corner=" + rounded(item.cornerRadius()));
    }

    private boolean isPaperOnlyVisualSource(ObjectPlan plan) {
        SourceMaterialStats stats = visualSourceMaterialStats(plan);
        return stats.paperLike > 0 && stats.nonPaper == 0;
    }

    private boolean hasVisibleNonPaperMaterial(ObjectPlan plan) {
        return visualSourceMaterialStats(plan).nonPaper > 0;
    }

    private SourceMaterialStats visualSourceMaterialStats(ObjectPlan plan) {
        SourceMaterialStats stats = new SourceMaterialStats();
        if (plan == null || data == null) return stats;
        LinkedHashSet<Integer> roots = new LinkedHashSet<>();
        addAll(visualSourceIds(plan), roots);
        if (roots.isEmpty()) addAll(plan.sourceObjectIds, roots);
        for (int sourceId : roots) {
            collectSourceMaterialStats(sourceId, stats, 0);
        }
        return stats;
    }

    private void collectSourceMaterialStats(int sourceId, SourceMaterialStats stats, int depth) {
        if (data == null || stats == null || sourceId <= 0 || depth > 8) return;
        ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
        if (item == null || item.sourceHidden()) return;
        String type = safe(item.type());
        if ("TextFrame".equals(type)) return;
        if (item.childIds() != null && item.childIds().length > 0) {
            for (int childId : item.childIds()) {
                collectSourceMaterialStats(childId, stats, depth + 1);
            }
            return;
        }
        if ("Group".equals(type)) return;
        collectSourcePaint(item.fillColorName(), item.fillTint(), item.opacity(), true, stats);
        collectSourcePaint(item.strokeColorName(), item.strokeTint(), item.opacity(),
                item.strokeWeight() > 0.01, stats);
    }

    private void collectSourcePaint(
            String colorName,
            double tint,
            double opacity,
            boolean active,
            SourceMaterialStats stats) {
        if (!active || stats == null) return;
        String color = safe(colorName);
        if (color.isEmpty() || isNoneColor(color)) return;
        if (isPaperColor(color)) {
            stats.paperLike++;
        } else if (opacity > 0.01) {
            stats.nonPaper++;
        }
    }

    private static final class SourceMaterialStats {
        int paperLike;
        int nonPaper;
    }

    private static String rounded(double value) {
        return String.valueOf(Math.round(value * 100.0) / 100.0);
    }

    private static boolean isVisualOnlyPlan(ObjectPlan plan) {
        return plan != null
                && plan.textAction == TextAction.DROP_TEXT
                && (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0)
                && (plan.descendantVisualObjectIds == null || plan.descendantVisualObjectIds.length == 0);
    }

    private boolean hasInlineSourceSignal(ObjectPlan plan) {
        if (plan == null || data == null) return false;
        if (data.isInlineObjectId(plan.domId)) return true;
        ResolvedPageItem self = data.getPageItem(String.valueOf(plan.domId));
        if (self != null && self.isInline()) return true;
        for (int sourceId : visualSourceIds(plan)) {
            if (data.isInlineObjectId(sourceId)) return true;
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item != null && item.isInline()) return true;
        }
        for (int sourceId : plan.sourceObjectIds) {
            if (data.isInlineObjectId(sourceId)) return true;
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item != null && item.isInline()) return true;
        }
        return false;
    }

    private boolean hasTextFrameSourceTree(ObjectPlan plan) {
        if (plan == null || data == null) return false;
        for (int sourceId : plan.sourceObjectIds) {
            if (hasTextFrameSourceTree(sourceId)) return true;
        }
        for (int sourceId : visualSourceIds(plan)) {
            if (hasTextFrameSourceTree(sourceId)) return true;
        }
        return false;
    }

    private boolean hasTextFrameSourceTree(int sourceId) {
        if (data == null || sourceId <= 0) return false;
        if (data.getTextFrame(String.valueOf(sourceId)) != null) return true;
        ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
        if (item != null && "TextFrame".equals(safe(item.type()))) return true;
        for (String childId : data.buildDescendantSet(String.valueOf(sourceId), 8)) {
            if (data.getTextFrame(childId) != null) return true;
            ResolvedPageItem child = data.getPageItem(childId);
            if (child != null && "TextFrame".equals(safe(child.type()))) return true;
        }
        return false;
    }

    private static boolean sameKnownSourceLayer(ObjectPlan a, ObjectPlan b) {
        String aLayerId = safe(a.sourceLayerId);
        String bLayerId = safe(b.sourceLayerId);
        if (!aLayerId.isEmpty() && !bLayerId.isEmpty()) {
            return aLayerId.equals(bLayerId);
        }
        if (a.sourceLayerIndex >= 0 && b.sourceLayerIndex >= 0) {
            return a.sourceLayerIndex == b.sourceLayerIndex;
        }
        String aLayerName = safe(a.sourceLayerName);
        String bLayerName = safe(b.sourceLayerName);
        if (!aLayerName.isEmpty() && !bLayerName.isEmpty()) {
            return aLayerName.equals(bLayerName);
        }
        return true;
    }

    private void normalizeVisualSlotsExcludeTableStyleSources() {
        if (data == null || plans.isEmpty()) return;
        LinkedHashMap<Integer, List<ObjectPlan>> tableStylePlansByPage = new LinkedHashMap<>();
        for (ObjectPlan plan : plans) {
            if (!planCarriesTableStyleSourceChannel(plan)) continue;
            tableStylePlansByPage
                    .computeIfAbsent(plan.pageIndex, k -> new ArrayList<>())
                    .add(plan);
        }
        if (tableStylePlansByPage.isEmpty()) return;

        int suppressed = 0;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan visualPlan = plans.get(i);
            if (!isTableStyleCarrierVisualDuplicate(visualPlan,
                    tableStylePlansByPage.get(visualPlan != null ? visualPlan.pageIndex : -1))) {
                continue;
            }
            plans.set(i, visualPlan.withVisualAction(
                    VisualAction.DROP_VISUAL,
                    "table_style_slot_owned_by_hwpx_table"));
            suppressed++;
        }
        ConversionTiming.metric("stage1.ownershipPlanner.normalizeVisualSlotsExcludeTableStyleSources.suppressed",
                suppressed);
    }

    private void ensureTableStyleSourceObjectIds() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null || !planCarriesTableStyleSourceChannel(plan)) continue;
            int[] canonical = tableStyleSourceIdsForPlan(plan);
            if (canonical.length == 0) continue;
            int[] merged = mergeIds(plan.styleSourceObjectIds, canonical);
            if (sameIntSet(plan.styleSourceObjectIds, merged)) continue;
            plans.set(i, plan.withStyleSourceObjectIds(merged));
        }
    }

    private int[] tableStyleSourceIdsForPlan(ObjectPlan plan) {
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        if (plan == null) return new int[0];
        addAll(tableStyleSourceIdsForTextFrame(tableStyleOwnerTextFrameId(plan)), ids);
        addDirectTableStyleSiblingSourceIds(plan, ids);
        return toIntArray(ids);
    }

    private static boolean planCarriesTableStyleSourceChannel(ObjectPlan plan) {
        if (plan == null) return false;
        return plan.materialization == Materialization.HWPX_TABLE_STYLE
                || plan.visualAction == VisualAction.PLACE_TABLE_STYLE;
    }

    private boolean isTableStyleCarrierVisualDuplicate(ObjectPlan visualPlan, List<ObjectPlan> tableStylePlans) {
        if (visualPlan == null || tableStylePlans == null || tableStylePlans.isEmpty()) return false;
        if (!visualPlan.hasVisibleVisual()) return false;
        if (visualPlan.visualAction == VisualAction.PLACE_TEXT_SHELL
                && visualPlan.ownedTextFrameIds != null
                && visualPlan.ownedTextFrameIds.length > 0) {
            return false;
        }
        if (visualPlan.materialization == Materialization.HWPX_TABLE_STYLE) return false;
        if (visualPlan.domId < 0) return false;

        for (ObjectPlan tableStylePlan : tableStylePlans) {
            if (tableStylePlan == null || tableStylePlan == visualPlan) continue;
            if (sameTableStyleCarrierSource(visualPlan, tableStylePlan)) return true;
        }
        return false;
    }

    private boolean sameTableStyleCarrierSource(ObjectPlan visualPlan, ObjectPlan tableStylePlan) {
        int[] tableStyleSources = mergeIds(tableStylePlan.sourceObjectIds, tableStylePlan.styleSourceObjectIds);
        int[] visualSources = visualSourceIds(visualPlan);
        if (containsAny(tableStyleSources, visualSources)
                && visualSourceOwnsAnyTableStyleTextFrame(visualSources, tableStylePlan)) {
            return true;
        }
        if (contains(tableStyleSources, visualPlan.domId)
                && visualSourceOwnsAnyTableStyleTextFrame(new int[] { visualPlan.domId }, tableStylePlan)) {
            return true;
        }
        return false;
    }

    private boolean visualSourceOwnsAnyTableStyleTextFrame(int[] visualSources, ObjectPlan tableStylePlan) {
        if (visualSources == null || visualSources.length == 0 || tableStylePlan == null) return false;
        int[] textFrameIds = textFrameIdsForPlan(tableStylePlan);
        if (textFrameIds.length == 0) textFrameIds = tableStylePlan.ownedTextFrameIds;
        for (int visualSourceId : visualSources) {
            for (int textFrameId : textFrameIds) {
                if (sourceTreeOwnsTextFrame(visualSourceId, textFrameId)) return true;
            }
        }
        return false;
    }

    private boolean sourceTreeOwnsTextFrame(int sourceId, int textFrameId) {
        if (sourceId < 0 || textFrameId < 0 || data == null) return false;
        if (sourceId == textFrameId) return true;
        ResolvedPageItem textItem = data.getPageItem(String.valueOf(textFrameId));
        if (textItem == null) return false;
        String current = textItem.parentId();
        HashSet<String> visited = new HashSet<>();
        while (current != null && !current.isBlank() && visited.add(current)) {
            if (current.equals(String.valueOf(sourceId))) return true;
            ResolvedPageItem parent = data.getPageItem(current);
            current = parent != null ? parent.parentId() : null;
        }
        return false;
    }

    private int tableStyleOwnerTextFrameId(ObjectPlan plan) {
        if (plan == null) return -1;
        if ("table_only_text_frame".equals(safe(plan.reason))
                && plan.ownedTextFrameIds != null
                && plan.ownedTextFrameIds.length > 0) {
            return plan.ownedTextFrameIds[0];
        }
        if (plan.domId >= 0) return plan.domId;
        if (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) {
            return plan.ownedTextFrameIds[0];
        }
        return -1;
    }

    private void addDirectTableStyleSiblingSourceIds(ObjectPlan plan, LinkedHashSet<Integer> ids) {
        if (data == null || plan == null || ids == null) return;
        int ownerTextFrameId = tableStyleOwnerTextFrameId(plan);
        if (ownerTextFrameId < 0) return;
        ResolvedPageItem ownerItem = data.getPageItem(String.valueOf(ownerTextFrameId));
        if (ownerItem == null) return;
        double[] ownerBounds = boundsOf(ownerItem);
        if (ownerBounds == null || ownerBounds.length < 4) ownerBounds = plan.bounds;
        String parentId = ownerItem.parentId();
        for (ResolvedPageItem item : data.pageItems()) {
            if (item == null || item.id() == null) continue;
            if (!sameNullableId(parentId, item.parentId())) continue;
            if (ownerItem.pageIndex() >= 0 && item.pageIndex() >= 0
                    && ownerItem.pageIndex() != item.pageIndex()) continue;
            int sourceId = parseFlexibleId(item.id());
            collectDirectTableStyleSiblingSourceId(sourceId, ownerTextFrameId, ownerBounds, ids);
        }
    }

    private static boolean sameNullableId(String a, String b) {
        boolean aBlank = a == null || a.isBlank();
        boolean bBlank = b == null || b.isBlank();
        if (aBlank || bBlank) return aBlank == bBlank;
        return a.equals(b);
    }

    private void collectDirectTableStyleSiblingSourceId(
            int sourceId,
            int ownerTextFrameId,
            double[] tableOwnerBounds,
            LinkedHashSet<Integer> ids) {
        if (sourceId < 0 || sourceId == ownerTextFrameId || data == null || ids == null) return;
        ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
        if (item == null || item.sourceHidden() || item.isInline()) return;
        if (data.getTextFrame(String.valueOf(sourceId)) != null) return;
        if (subtreeContainsOtherTableOnlyTextFrame(item, ownerTextFrameId)) return;
        if ("Group".equals(item.type())) {
            if (item.childIds() == null) return;
            for (int childId : item.childIds()) {
                collectDirectTableStyleSiblingSourceId(childId, ownerTextFrameId, tableOwnerBounds, ids);
            }
            return;
        }
        if (isCandidateTableStyleSourceItem(item, ownerTextFrameId, tableOwnerBounds)) {
            ids.add(sourceId);
        }
    }

    private int chooseVisibleSourceOwner(int sourceId, List<Integer> indexes) {
        int winner = -1;
        int winnerScore = Integer.MIN_VALUE;
        for (int index : indexes) {
            ObjectPlan plan = index >= 0 && index < plans.size() ? plans.get(index) : null;
            int score = visibleSourceOwnerScore(plan, sourceId);
            if (winner < 0 || score > winnerScore) {
                winner = index;
                winnerScore = score;
            }
        }
        return winner;
    }

    private static int visibleSourceOwnerScore(ObjectPlan plan, int sourceId) {
        if (plan == null) return Integer.MIN_VALUE;
        int score = 0;
        if (isExtractedTextShellOwner(plan)) {
            score += 1300;
            if (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) score += 120;
            if (visualSourceIds(plan).length > 1) score += 80;
        }
        if (plan.materialization == Materialization.NATIVE_SOURCE_SHAPE) {
            score -= 120;
        }
        if ("native_parent_text_shell".equals(safe(plan.kind))
                && plan.domId == sourceId
                && plan.ownedTextFrameIds != null
                && plan.ownedTextFrameIds.length > 0) {
            score += 800;
        }
        if (plan.domId == sourceId) score += 1000;
        if (contains(plan.sourceObjectIds, sourceId)) score += 120;
        if (contains(plan.visualSourceObjectIds, sourceId)) score += 80;
        if (plan.textAction == TextAction.OWNED_BY_HWPX_TEXT) score += 70;
        if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL) score += 60;
        if (plan.visualAction == VisualAction.PLACE_INLINE_PNG) score += 40;
        if (plan.placement == Placement.INLINE) score += 30;
        int visualCount = visualSourceIds(plan).length;
        if (visualCount == 1) score += 25;
        score -= Math.min(visualCount, 200);
        String reason = safe(plan.reason);
        if (reason.contains("image_group")) score -= 120;
        if (reason.contains("decoration_group") && plan.domId != sourceId) score -= 60;
        if (reason.contains("text_hidden") && plan.domId != sourceId) score -= 40;
        return score;
    }

    private static boolean isExtractedTextShellOwner(ObjectPlan plan) {
        return plan != null
                && plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                && plan.materialization == Materialization.EXTRACTED_PNG_VECTOR;
    }

    private void dropTableOnlyCarrierTextShellVisualOwners() {
        Map<Integer, List<ObjectPlan>> tableOnlyPlansByPage = new HashMap<>();
        for (ObjectPlan plan : plans) {
            if (!isTableOnlyTextFramePlan(plan)) continue;
            tableOnlyPlansByPage
                    .computeIfAbsent(plan.pageIndex, k -> new ArrayList<>())
                    .add(plan);
        }
        if (tableOnlyPlansByPage.isEmpty()) return;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!isExtractedTextShellOwner(plan)) continue;
            List<ObjectPlan> tableOnlyPlans = tableOnlyPlansByPage.get(plan.pageIndex);
            if (tableOnlyPlans == null || tableOnlyPlans.isEmpty()) continue;
            if (!isTableOnlyCarrierVisualDuplicate(plan, tableOnlyPlans)) continue;
            plans.set(i, plan.withVisualAction(
                    VisualAction.DROP_VISUAL,
                    "table_only_text_frame_visual_owned_by_hwpx_table"));
        }
    }

    private boolean isTableOnlyCarrierVisualDuplicate(ObjectPlan visualPlan, List<ObjectPlan> tableOnlyPlans) {
        if (visualPlan == null || tableOnlyPlans == null || tableOnlyPlans.isEmpty()) return false;
        int[] visualPlanSources = mergeIds(visualPlan.sourceObjectIds, visualPlan.styleSourceObjectIds);
        int[] visualRoots = mergeIds(visualPlan.sourceRootObjectIds, visualPlan.styleSourceObjectIds);
        for (ObjectPlan tableOnlyPlan : tableOnlyPlans) {
            if (tableOnlyPlan == null) continue;
            int[] tableSources = mergeIds(tableOnlyPlan.sourceObjectIds, tableOnlyPlan.styleSourceObjectIds);
            if (sameIntSet(visualPlanSources, tableSources)) return true;
            if (sameIntSet(visualRoots, tableSources)) return true;
            if (containsAny(visualPlan.ownedTextFrameIds, tableOnlyPlan.ownedTextFrameIds)
                    && containsAll(tableSources, visualPlanSources)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTableOnlyTextFramePlan(ObjectPlan plan) {
        return plan != null
                && "text_frame:table_only".equals(safe(plan.kind))
                && plan.materialization == Materialization.HWPX_TEXT
                && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT;
    }

    private int[] duplicateVisibleSourceIds(ObjectPlan plan) {
        if (plan == null) return new int[0];
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        int[] visualIds = visualSourceIds(plan);
        if (visualIds != null) {
            for (int sourceId : visualIds) ids.add(sourceId);
        }
        if (isExtractedTextShellOwner(plan) && ids.isEmpty()) {
            RenderedGroup rendered = renderedGroupForPlan(plan);
            if (rendered != null && rendered.exportSourceObjectIds() != null) {
                for (int sourceId : rendered.exportSourceObjectIds()) ids.add(sourceId);
            }
        }
        if (isExtractedTextShellOwner(plan) && plan.styleSourceObjectIds != null) {
            for (int sourceId : plan.styleSourceObjectIds) ids.add(sourceId);
        }
        return toIntArray(ids);
    }

    private ObjectPlan removeVisibleSourceFromPlan(ObjectPlan plan, int sourceId, String reason) {
        if (plan == null || !plan.hasVisibleVisual()) return plan;
        int[] current = visualSourceIds(plan);
        if (!contains(current, sourceId)) return plan;
        int[] retained = withoutSource(current, sourceId);
        ObjectPlan next = plan.withVisualSourceObjectIds(retained);
        if (retained.length == 0) {
            next = next.withVisualAction(VisualAction.DROP_VISUAL, reason);
        }
        return next;
    }

    private static int[] withoutSource(int[] values, int sourceId) {
        if (values == null || values.length == 0) return new int[0];
        LinkedHashSet<Integer> retained = new LinkedHashSet<>();
        for (int value : values) {
            if (value == sourceId) continue;
            retained.add(value);
        }
        return toIntArray(retained);
    }

    private static int parseDuplicateSourceKey(String key) {
        if (key == null) return -1;
        int colon = key.indexOf(':');
        String value = colon >= 0 ? key.substring(colon + 1) : key;
        return parseInt(value, -1);
    }

    private boolean isDroppedPureDecorationVisual(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.visualAction != VisualAction.DROP_VISUAL) return false;
        if (plan.textAction != TextAction.DROP_TEXT) return false;
        if (plan.file == null || plan.file.isEmpty()) return false;
        if (visualSourceIds(plan).length == 0) return false;
        if (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) return false;
        String reason = safe(plan.reason);
        return "pure_decoration_group".equals(reason)
                || "decoration_group".equals(reason);
    }

    private boolean hasVisibleVisualOwnerForSameDom(ObjectPlan target) {
        if (target == null) return false;
        if (target.domId < 0) return false;
        for (ObjectPlan candidate : plans) {
            if (candidate == null || candidate == target) continue;
            if (candidate.pageIndex != target.pageIndex) continue;
            if (candidate.domId != target.domId) continue;
            if (!candidate.hasVisibleVisual()) continue;
            if (candidate.visualAction != VisualAction.PLACE_FLOATING_PNG
                    && candidate.visualAction != VisualAction.PLACE_INLINE_PNG
                    && candidate.visualAction != VisualAction.PLACE_TEXT_SHELL) {
                continue;
            }
            return true;
        }
        return false;
    }

    private ObjectPlan compositeCarrierForTextFrame(int textFrameId, List<ObjectPlan> carriers) {
        if (textFrameId < 0 || carriers == null) return null;
        for (ObjectPlan carrier : carriers) {
            if (carrier == null) continue;
            if (contains(carrier.ownedTextFrameIds, textFrameId)) return carrier;
        }
        return null;
    }

    private ObjectPlan declareDirectTextFrameFromCompositeCarrier(ObjectPlan textPlan, ObjectPlan carrier) {
        int textFrameId = textPlan != null ? textPlan.domId : -1;
        return textPlan
                .withTextAction(TextAction.OWNED_BY_HWPX_TEXT)
                .withVisualAction(VisualAction.DROP_VISUAL,
                        "text_frame_split_from_composite_carrier")
                .withOwnedTextFrameIds(new int[] { textFrameId })
                .withMaterialization(Materialization.HWPX_TEXT)
                .withPlacementAndCoordinateSpace(
                        carrier != null ? carrier.placement : textPlan.placement,
                        carrier != null ? carrier.coordinateSpace : textPlan.coordinateSpace);
    }

    private boolean isCompositeCarrierTextFrameOwner(ObjectPlan plan) {
        if (!isVisibleTextShellWithOwnedText(plan)) return false;
        if (plan.materialization != Materialization.EXTRACTED_PNG_VECTOR) return false;
        if (plan.placement != Placement.FLOATING) return false;
        if (!"mixed_group_text_hidden".equals(plan.reason)) return false;
        if (plan.ownedTextFrameIds.length < 2) return false;
        if (compositeTextShellHasExtraMaterialOutsideDirectShellSlots(plan)) return true;
        if (plan.bounds == null || plan.bounds.length < 4) return false;
        double h = Math.abs(plan.bounds[2] - plan.bounds[0]);
        double w = Math.abs(plan.bounds[3] - plan.bounds[1]);
        return h >= 45.0 && w >= 90.0;
    }

    private static boolean coveredByCompositeCarrier(ObjectPlan plan, List<ObjectPlan> carriers) {
        if (plan == null || carriers == null || carriers.isEmpty()) return false;
        for (ObjectPlan carrier : carriers) {
            if (carrier == null || carrier.pageIndex != plan.pageIndex) continue;
            if (!containsAny(carrier.ownedTextFrameIds, plan.ownedTextFrameIds)) continue;
            if (containsAll(visualSourceIds(carrier), visualSourceIds(plan))) return true;
            if (containsAll(carrier.sourceObjectIds, plan.sourceObjectIds)) return true;
        }
        return false;
    }

    private static boolean containsAnySet(int[] values, Set<Integer> candidates) {
        if (values == null || values.length == 0 || candidates == null || candidates.isEmpty()) {
            return false;
        }
        for (int value : values) {
            if (candidates.contains(value)) return true;
        }
        return false;
    }

    private int chooseCanonicalTextShellOwnerIndex(int aIndex, int bIndex) {
        ObjectPlan a = plans.get(aIndex);
        ObjectPlan b = plans.get(bIndex);
        boolean aNativeInlineAtom = isNativeInlineTextShellAtomPlan(a);
        boolean bNativeInlineAtom = isNativeInlineTextShellAtomPlan(b);
        if (aNativeInlineAtom != bNativeInlineAtom) {
            return aNativeInlineAtom ? aIndex : bIndex;
        }
        int sourceCompare = Integer.compare(sourceCount(a), sourceCount(b));
        if (sourceCompare != 0) return sourceCompare < 0 ? aIndex : bIndex;
        int visualCompare = Integer.compare(visualSourceIds(a).length, visualSourceIds(b).length);
        if (visualCompare != 0) return visualCompare < 0 ? aIndex : bIndex;
        int areaCompare = Double.compare(area(a.bounds), area(b.bounds));
        if (areaCompare != 0) return areaCompare < 0 ? aIndex : bIndex;
        return aIndex;
    }

    private boolean isNativeInlineTextShellAtomPlan(ObjectPlan plan) {
        if (plan == null || data == null || plan.domId < 0) return false;
        if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) return false;
        if (isTableCellAnchoredExternalLabelShell(plan)) return false;
        if (!hasExplicitTextlessShellSignal(plan)) return false;
        if (!hasResolvedInlineAnchor(plan.domId) && !data.isInlineObjectId(plan.domId)) return false;
        if (!ownedTextFramesAreInlineSource(plan)) return false;

        RenderedGroup rendered = renderedGroupForPlan(plan);
        if (rendered != null
                && "TEXTLESS_SHELL_WITH_TF".equals(safe(rendered.atomicObjectKind()))
                && "inline_object".equals(safe(rendered.type()))) {
            return true;
        }

        ResolvedPageItem root = data.getPageItem(String.valueOf(plan.domId));
        if (root == null || !"Group".equals(safe(root.type())) || !root.isInline()) return false;
        for (int textFrameId : plan.ownedTextFrameIds) {
            ResolvedTextFrame tf = data.getTextFrame(String.valueOf(textFrameId));
            if (tf == null || tf.sourceHidden()) return false;
            ResolvedPageItem tfItem = data.getPageItem(String.valueOf(textFrameId));
            if (tfItem == null) return false;
            if (!String.valueOf(plan.domId).equals(tfItem.parentId())) return false;
        }
        return true;
    }

    private ObjectPlan visibleChildTextShellCoveredByComposite(ObjectPlan composite) {
        ObjectPlan best = null;
        for (ObjectPlan child : plans) {
            if (!isVisibleTextShell(child)) continue;
            if (child.pageIndex != composite.pageIndex) continue;
            if (!containsAll(composite.ownedTextFrameIds, child.ownedTextFrameIds)) continue;
            if (!containsAll(visualSourceIds(composite), visualSourceIds(child))) continue;
            if (!compositeTextShellHasExtraMaterialOutsideChild(composite, child)) continue;
            if (best == null || sourceCount(child) > sourceCount(best)) {
                best = child;
            }
        }
        return best;
    }

    private boolean compositeTextShellHasExtraMaterialOutsideChild(ObjectPlan composite, ObjectPlan child) {
        if (composite == null || child == null || data == null) return false;
        if (composite == child) return false;
        if (composite.pageIndex != child.pageIndex) return false;
        if (!containsAll(visualSourceIds(composite), visualSourceIds(child))) return false;
        if (composite.bounds == null || child.bounds == null) return false;
        double compositeArea = area(composite.bounds);
        double childArea = area(child.bounds);
        if (compositeArea <= childArea * 1.05) return false;
        for (int sourceId : visualSourceIds(composite)) {
            if (contains(visualSourceIds(child), sourceId)) continue;
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (sourceItemHasVisibleShellMaterial(item)) return true;
        }
        return false;
    }

    private static boolean sourceItemHasVisibleShellMaterial(ResolvedPageItem item) {
        if (item == null || item.sourceHidden() || item.hiddenByParent() || !item.visible()) return false;
        String type = safe(item.type());
        if ("TextFrame".equals(type)) return false;
        if ("Group".equals(type)) {
            return !isNoneColor(item.fillColorName())
                    || (!isNoneColor(item.strokeColorName()) && item.strokeWeight() > 0.01);
        }
        if ("Rectangle".equals(type) || "Oval".equals(type) || "Polygon".equals(type)) {
            return !isNoneColor(item.fillColorName())
                    || (!isNoneColor(item.strokeColorName()) && item.strokeWeight() > 0.01);
        }
        if ("GraphicLine".equals(type)) {
            return !isNoneColor(item.strokeColorName()) && item.strokeWeight() > 0.01;
        }
        return true;
    }

    private boolean sourceIdHasVisibleTextFrameShellMaterial(int sourceId) {
        if (data == null || sourceId <= 0) return false;
        ResolvedTextFrame tf = data.getTextFrame(String.valueOf(sourceId));
        if (tf != null) {
            if (tf.sourceHidden()) return false;
            if (!isNoneColor(tf.fillColor())) return true;
            if (!isNoneColor(tf.strokeColor()) && tf.strokeWeight() > 0.01) return true;
            return tf.cornerRadius() > 0.01;
        }

        ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
        if (item == null || item.sourceHidden() || item.hiddenByParent() || !item.visible()) return false;
        if (!"TextFrame".equals(safe(item.type()))) return false;
        if (!isNoneColor(item.fillColorName())) return true;
        if (!isNoneColor(item.strokeColorName()) && item.strokeWeight() > 0.01) return true;
        return item.cornerRadius() > 0.01;
    }

    private boolean sourceIdIsTextlessVisibleTextFrameShellMaterial(int sourceId) {
        if (!sourceIdHasVisibleTextFrameShellMaterial(sourceId)) return false;
        if (data == null || sourceId <= 0) return false;
        ResolvedTextFrame tf = data.getTextFrame(String.valueOf(sourceId));
        if (tf != null) return !hasSemanticText(tf);
        return true;
    }

    private boolean ownsTextFrameShellStyleSource(ObjectPlan plan) {
        if (plan == null) return false;
        LinkedHashSet<Integer> sourceIds = new LinkedHashSet<>();
        addAll(visualSourceIds(plan), sourceIds);
        addAll(plan.styleSourceObjectIds, sourceIds);
        if (sourceIds.isEmpty()) return false;
        for (int sourceId : sourceIds) {
            if (sourceIdHasVisibleTextFrameShellMaterial(sourceId)) return true;
        }
        return false;
    }

    private void normalizeVisibleDescendantContracts() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan parent = plans.get(i);
            if (parent == null || parent.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (parent.descendantVisualObjectIds == null
                    || parent.descendantVisualObjectIds.length == 0) {
                continue;
            }
            LinkedHashSet<Integer> retained = new LinkedHashSet<>();
            for (int id : parent.descendantVisualObjectIds) {
                retained.add(id);
            }
            for (int j = 0; j < plans.size(); j++) {
                if (i == j) continue;
                ObjectPlan candidate = plans.get(j);
                if (!isVisibleRenderedVisual(candidate)) continue;
                if (candidate.pageIndex != parent.pageIndex) continue;
                if (!referencesAnyDescendant(candidate, parent.descendantVisualObjectIds)) continue;
                retained.remove(candidate.domId);
                if (candidate.renderId != null) retained.remove(candidate.renderId);
                for (int sourceId : visualSourceIds(candidate)) {
                    retained.remove(sourceId);
                }
            }
            if (retained.size() == parent.descendantVisualObjectIds.length) continue;
            plans.set(i, parent.withDescendantVisualObjectIds(toIntArray(retained)));
        }
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

    private void dropVisualChildrenBakedIntoTextShellParents() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan child = plans.get(i);
            if (!isVisibleRenderedVisual(child)) continue;
            if (!isVisualOnlyChildCandidateForTextShellParent(child)) continue;
            for (ObjectPlan parent : plans) {
                if (!textShellParentBakesVisualChild(parent, child)) continue;
                ObjectPlan dropped = child.withVisualAction(
                        VisualAction.DROP_VISUAL,
                        "owned_by_parent_text_shell");
                if (!"text_frame".equals(dropped.kind)) {
                    dropped = dropped.withTextAction(TextAction.DROP_TEXT);
                }
                plans.set(i, dropped);
                break;
            }
        }
    }

    private boolean isVisualOnlyChildCandidateForTextShellParent(ObjectPlan child) {
        if (child == null) return false;
        if (child.visualAction == VisualAction.PLACE_TEXT_SHELL) return false;
        if (child.ownedTextFrameIds != null && child.ownedTextFrameIds.length > 0) return false;
        if (isImageBackedContentPlan(child)) return false;
        int[] childVisualSources = visualSourceIds(child);
        return childVisualSources.length > 0;
    }

    private static boolean textShellParentBakesVisualChild(ObjectPlan parent, ObjectPlan child) {
        if (parent == null || child == null || parent == child) return false;
        if (isInlineStoryFlowVisual(child)) return false;
        if (parent.pageIndex != child.pageIndex) return false;
        if (parent.domId == child.domId) return false;
        if (parent.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (parent.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        if (visualOnlyChildOwnsDistinctShellSlot(parent, child)) return false;
        int[] childVisualSources = visualSourceIds(child);
        if (childVisualSources.length == 0) return false;
        if (!containsAll(visualSourceIds(parent), childVisualSources)) return false;
        return sourceCount(parent) > sourceCount(child);
    }

    private void declareContentVisualChildrenClaimedByTextShellParents() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan child = plans.get(i);
            if (!isDroppedContentVisualClaimedByTextShellParent(child)) continue;
            ObjectPlan owner = preferredContentVisualDeclarationOwner(child);
            if (owner == null) continue;
            int ownerIndex = plans.indexOf(owner);
            if (ownerIndex < 0) continue;

            ObjectPlan declared = declareContentVisualPlan(owner);
            plans.set(ownerIndex, declared);
            trimTextShellParentsForContentChild(declared);
            trimSiblingContentChannels(declared);
        }
        declareOrphanedBakedInlineContentVisualsClaimedByTextShellParents();
        dropCompositeImageBackedVisualsOwnedByLeafChannels();
    }

    private void normalizeClippedImageContentOwners() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan rawImage = plans.get(i);
            ClippedImageSource clipped = clippedImageSourceForRawVisiblePlan(rawImage);
            if (clipped == null) continue;

            ObjectPlan owner = preferredClippedImageOwner(rawImage, clipped);
            if (owner == null || owner == rawImage) continue;
            int ownerIndex = plans.indexOf(owner);
            if (ownerIndex < 0) continue;

            ObjectPlan declaredOwner = declareClippedImageOwnerPlan(owner);
            plans.set(ownerIndex, declaredOwner);
            dropContentDescendantPlansOwnedByClippedImageOwner(declaredOwner, clipped);
            trimTextShellParentsForContentChild(declaredOwner);
        }
    }

    private boolean isVisiblePngVisualPlan(ObjectPlan plan) {
        if (plan == null || !plan.hasVisibleVisual()) return false;
        return plan.visualAction == VisualAction.PLACE_FLOATING_PNG
                || plan.visualAction == VisualAction.PLACE_INLINE_PNG;
    }

    private boolean hasOwnedTextFrame(ObjectPlan plan) {
        return plan != null && plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0;
    }

    private boolean hasAnyTextFrameSource(ObjectPlan plan) {
        if (plan == null || data == null) return false;
        if (hasTextFrameSource(plan.sourceObjectIds)) return true;
        return hasTextFrameSource(visualSourceIds(plan));
    }

    private ClippedImageSource clippedImageSourceForRawVisiblePlan(ObjectPlan plan) {
        if (plan == null || data == null) return null;
        if (!isVisibleContentVisualPlan(plan)) return null;
        if (!isImageBackedContentPlan(plan)) return null;
        for (int sourceId : visualSourceIds(plan)) {
            ClippedImageSource clipped = clippedImageSourceForRawImage(sourceId, plan);
            if (clipped != null) return clipped;
        }
        for (int sourceId : plan.sourceObjectIds) {
            ClippedImageSource clipped = clippedImageSourceForRawImage(sourceId, plan);
            if (clipped != null) return clipped;
        }
        return null;
    }

    private ClippedImageSource clippedImageSourceForRawImage(int sourceId, ObjectPlan plan) {
        ResolvedPageItem image = data.getPageItem(String.valueOf(sourceId));
        if (image == null || !"Image".equals(safe(image.type()))) return null;
        ResolvedPageItem clip = directImageClipParent(image);
        if (clip == null) return null;
        int clipId = parseFlexibleId(clip.id());
        if (clipId < 0) return null;
        if (contains(plan.sourceObjectIds, clipId) || contains(visualSourceIds(plan), clipId)) {
            return null;
        }
        return new ClippedImageSource(sourceId, clipId);
    }

    private ResolvedPageItem directImageClipParent(ResolvedPageItem image) {
        if (image == null || data == null || image.parentId() == null) return null;
        ResolvedPageItem parent = data.getPageItem(image.parentId());
        if (parent == null) return null;
        String type = safe(parent.type());
        if (!"Oval".equals(type) && !"Polygon".equals(type) && !"Rectangle".equals(type)) {
            return null;
        }
        if ("Oval".equals(type) || "Polygon".equals(type) || parent.clipContent()) {
            return parent;
        }
        return !boundsContains(boundsOf(parent), boundsOf(image), 0.25) ? parent : null;
    }

    private ObjectPlan preferredClippedImageOwner(ObjectPlan rawImage, ClippedImageSource clipped) {
        ObjectPlan best = null;
        int bestScore = Integer.MIN_VALUE;
        for (ObjectPlan candidate : plans) {
            if (candidate == null || candidate == rawImage) continue;
            if (candidate.pageIndex != rawImage.pageIndex) continue;
            if (!isRenderedClippedImageOwnerCandidate(candidate, clipped)) continue;
            int score = clippedImageOwnerScore(candidate, rawImage, clipped);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private boolean isRenderedClippedImageOwnerCandidate(ObjectPlan candidate, ClippedImageSource clipped) {
        if (candidate == null || clipped == null) return false;
        if (candidate.renderId == null) return false;
        if (candidate.file == null || candidate.file.isBlank()) return false;
        if (clippedImageOwnerTreeDistance(candidate, clipped) < 0) return false;
        if (!contains(candidate.sourceObjectIds, clipped.imageId)
                && !contains(visualSourceIds(candidate), clipped.imageId)) {
            return false;
        }
        return contains(candidate.sourceObjectIds, clipped.clipParentId)
                || contains(visualSourceIds(candidate), clipped.clipParentId);
    }

    private int clippedImageOwnerTreeDistance(ObjectPlan candidate, ClippedImageSource clipped) {
        if (candidate == null || clipped == null || data == null) return -1;
        int candidateId = candidate.domId >= 0
                ? candidate.domId
                : (candidate.renderId != null ? candidate.renderId.intValue() : -1);
        if (candidateId < 0) return -1;
        if (candidateId == clipped.clipParentId) return 0;

        int distance = 1;
        String current = String.valueOf(clipped.clipParentId);
        HashSet<String> visited = new HashSet<>();
        while (current != null && !current.isBlank() && visited.add(current)) {
            ResolvedPageItem item = data.getPageItem(current);
            if (item == null || item.parentId() == null || item.parentId().isBlank()) {
                return -1;
            }
            int parentId = parseFlexibleId(item.parentId());
            if (parentId < 0) return -1;
            if (parentId == candidateId) return distance;
            current = item.parentId();
            distance++;
        }
        return -1;
    }

    private int clippedImageOwnerScore(ObjectPlan candidate, ObjectPlan rawImage, ClippedImageSource clipped) {
        int score = 0;
        String file = safe(candidate.file);
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        String basename = slash >= 0 ? file.substring(slash + 1) : file;
        int treeDistance = clippedImageOwnerTreeDistance(candidate, clipped);
        if (treeDistance >= 0) score += 5000 - treeDistance * 500;
        score -= Math.max(0, candidate.sourceObjectIds.length) * 20;
        if (basename.startsWith("img_")) score += 100;
        if (safe(candidate.kind).contains("rendered_floating_item")) score += 50;
        if (candidate.placement == rawImage.placement) score += 30;
        if (candidate.hasVisibleVisual()) score += 20;
        return score;
    }

    private ObjectPlan declareClippedImageOwnerPlan(ObjectPlan owner) {
        int[] visualSources = visualSourceIds(owner);
        if (visualSources.length == 0) {
            visualSources = owner.sourceObjectIds;
        }
        VisualAction action = owner.placement == Placement.INLINE
                ? VisualAction.PLACE_INLINE_PNG
                : VisualAction.PLACE_FLOATING_PNG;
        Materialization materialization = owner.placement == Placement.INLINE
                ? Materialization.COMPLETE_PNG
                : Materialization.EXTRACTED_PNG_VECTOR;
        return owner
                .withTextAction(TextAction.DROP_TEXT)
                .withVisualAction(action, "clipped_image_content_owned_by_clip_bundle")
                .withMaterialization(materialization)
                .withOwnedTextFrameIds(new int[0])
                .withDescendantVisualObjectIds(new int[0])
                .withVisualSourceObjectIds(withoutTextFrameSources(visualSources));
    }

    private void dropContentDescendantPlansOwnedByClippedImageOwner(
            ObjectPlan owner,
            ClippedImageSource clipped) {
        if (owner == null || owner.sourceObjectIds == null || owner.sourceObjectIds.length == 0) return;
        LinkedHashSet<Integer> clipSlotSources = clippedImageSlotSources(clipped);
        if (clipSlotSources.isEmpty()) return;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan candidate = plans.get(i);
            if (candidate == null || candidate == owner) continue;
            if (candidate.pageIndex != owner.pageIndex) continue;
            if (!isVisibleContentVisualPlan(candidate)) continue;
            if (!isImageBackedContentPlan(candidate)) continue;
            if (!planContainsAnySource(candidate, clipSlotSources)) continue;
            if (!containsAny(owner.sourceObjectIds, candidate.sourceObjectIds)
                    && !containsAny(owner.sourceObjectIds, visualSourceIds(candidate))
                    && !contains(owner.sourceObjectIds, candidate.domId)) {
                continue;
            }
            if (candidate.sourceObjectIds.length >= owner.sourceObjectIds.length) continue;
            plans.set(i, candidate
                    .withTextAction(TextAction.DROP_TEXT)
                    .withVisualAction(VisualAction.DROP_VISUAL,
                            "content_visual_owned_by_clipped_image_bundle")
                    .withOwnedTextFrameIds(new int[0])
                    .withVisualSourceObjectIds(new int[0])
                    .withDescendantVisualObjectIds(new int[0]));
        }
    }

    private LinkedHashSet<Integer> clippedImageSlotSources(ClippedImageSource clipped) {
        LinkedHashSet<Integer> sources = new LinkedHashSet<>();
        if (clipped == null) return sources;
        if (clipped.clipParentId >= 0) sources.add(clipped.clipParentId);
        if (clipped.imageId >= 0) sources.add(clipped.imageId);
        return sources;
    }

    private static boolean planContainsAnySource(ObjectPlan plan, LinkedHashSet<Integer> sources) {
        if (plan == null || sources == null || sources.isEmpty()) return false;
        if (sources.contains(plan.domId)) return true;
        if (plan.renderId != null && sources.contains(plan.renderId.intValue())) return true;
        for (int sourceId : plan.sourceObjectIds) {
            if (sources.contains(sourceId)) return true;
        }
        for (int sourceId : visualSourceIds(plan)) {
            if (sources.contains(sourceId)) return true;
        }
        return false;
    }

    private static final class ClippedImageSource {
        final int imageId;
        final int clipParentId;

        ClippedImageSource(int imageId, int clipParentId) {
            this.imageId = imageId;
            this.clipParentId = clipParentId;
        }
    }

    private void declareOrphanedBakedInlineContentVisualsClaimedByTextShellParents() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan child = plans.get(i);
            if (child == null) continue;
            if (child.visualAction != VisualAction.DROP_VISUAL) continue;
            if (child.placement != Placement.INLINE) continue;
            if (!isInlineContentVisualOrphanedByPageCompositeCarrier(child)) continue;
            ObjectPlan pageChild = child.withPlacementAndCoordinateSpace(
                    Placement.FLOATING,
                    CoordinateSpace.PAGE);
            ObjectPlan declared = declareContentVisualPlan(pageChild);
            plans.set(i, declared);
            trimTextShellParentsForContentChild(declared);
            trimSiblingContentChannels(declared);
        }
    }

    private boolean isDroppedContentVisualClaimedByTextShellParent(ObjectPlan plan) {
        if (plan == null || data == null) return false;
        if (plan.visualAction != VisualAction.DROP_VISUAL) return false;
        if (plan.placement != Placement.FLOATING && plan.placement != Placement.INLINE) return false;
        if (!isImageBackedContentPlan(plan)) return false;
        if (hasMoreSpecificImageBackedContentPlan(plan)) return false;
        return textShellParentClaimsContentVisual(plan);
    }

    private boolean textShellParentClaimsContentVisual(ObjectPlan child) {
        if (child == null) return false;
        for (ObjectPlan parent : plans) {
            if (parent == null || parent == child) continue;
            if (parent.pageIndex != child.pageIndex) continue;
            if (parent.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (parent.visualPolicyLayer() != PolicyLayer.BACKGROUND) continue;
            if (!containsAny(visualSourceIds(parent), child.sourceObjectIds)
                    && !containsAny(parent.sourceObjectIds, child.sourceObjectIds)
                    && !containsAny(parent.descendantVisualObjectIds, child.sourceObjectIds)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private ObjectPlan preferredContentVisualDeclarationOwner(ObjectPlan child) {
        if (child == null) return null;
        ObjectPlan clipOwner = preferredSpecificClipOwnerForDroppedContent(child);
        if (clipOwner != null) return clipOwner;
        if (child.placement == Placement.FLOATING
                && safe(child.kind).contains("rendered_floating_item")) {
            return child;
        }
        ObjectPlan floating = pairedFloatingContentVisualPlanFor(child);
        if (floating != null) return floating;
        if (child.placement == Placement.INLINE
                && isInlineContentVisualOrphanedByPageCompositeCarrier(child)) {
            return child.withPlacementAndCoordinateSpace(Placement.FLOATING, CoordinateSpace.PAGE);
        }
        if (child.placement == Placement.INLINE && hasIdmlAnchoredPagePosition(child.domId)) {
            return null;
        }
        return child;
    }

    private ObjectPlan preferredSpecificClipOwnerForDroppedContent(ObjectPlan child) {
        ClippedImageSource clipped = clippedImageSourceOwnedByPlan(child);
        if (clipped == null) return null;
        ObjectPlan best = null;
        int bestScore = Integer.MIN_VALUE;
        int childSources = sourceCount(child);
        for (ObjectPlan candidate : plans) {
            if (candidate == null || candidate == child) continue;
            if (candidate.pageIndex != child.pageIndex) continue;
            if (sourceCount(candidate) >= childSources) continue;
            if (!isRenderedClippedImageOwnerCandidate(candidate, clipped)) continue;
            int score = clippedImageOwnerScore(candidate, child, clipped);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private ClippedImageSource clippedImageSourceOwnedByPlan(ObjectPlan plan) {
        if (plan == null || data == null) return null;
        for (int sourceId : visualSourceIds(plan)) {
            ClippedImageSource clipped = clippedImageSourceOwnedByPlanSource(sourceId, plan);
            if (clipped != null) return clipped;
        }
        for (int sourceId : plan.sourceObjectIds) {
            ClippedImageSource clipped = clippedImageSourceOwnedByPlanSource(sourceId, plan);
            if (clipped != null) return clipped;
        }
        return null;
    }

    private ClippedImageSource clippedImageSourceOwnedByPlanSource(int sourceId, ObjectPlan plan) {
        ResolvedPageItem image = data.getPageItem(String.valueOf(sourceId));
        if (image == null || !"Image".equals(safe(image.type()))) return null;
        ResolvedPageItem clip = directImageClipParent(image);
        if (clip == null) return null;
        int clipId = parseFlexibleId(clip.id());
        if (clipId < 0) return null;
        if (!contains(plan.sourceObjectIds, clipId) && !contains(visualSourceIds(plan), clipId)) {
            return null;
        }
        return new ClippedImageSource(sourceId, clipId);
    }

    private boolean isInlineContentVisualOrphanedByPageCompositeCarrier(ObjectPlan child) {
        if (child == null || child.placement != Placement.INLINE) return false;
        if (!isImageBackedContentPlan(child)) return false;
        if (!"baked_into_composite_parent".equals(safe(child.reason))) return false;
        if (hasMoreSpecificVisibleImageBackedContentOwner(child)) return false;
        for (ObjectPlan parent : plans) {
            if (parent == null || parent == child) continue;
            if (parent.pageIndex != child.pageIndex) continue;
            if (parent.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (parent.placement != Placement.FLOATING) continue;
            if (parent.visualPolicyLayer() != PolicyLayer.BACKGROUND) continue;
            if (!containsAny(visualSourceIds(parent), child.sourceObjectIds)
                    && !containsAny(parent.sourceObjectIds, child.sourceObjectIds)
                    && !containsAny(parent.descendantVisualObjectIds, child.sourceObjectIds)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private ObjectPlan pairedFloatingContentVisualPlanFor(ObjectPlan child) {
        if (child == null || child.domId < 0) return null;
        ObjectPlan best = null;
        for (ObjectPlan plan : plans) {
            if (plan == null || plan == child) continue;
            if (plan.domId != child.domId) continue;
            if (plan.pageIndex != child.pageIndex) continue;
            if (plan.placement != Placement.FLOATING) continue;
            if (!safe(plan.kind).contains("page_object")) continue;
            if (!isImageBackedContentPlan(plan)) continue;
            if (!containsAny(plan.sourceObjectIds, child.sourceObjectIds)) {
                continue;
            }
            if (safe(plan.kind).contains("rendered_floating_item")) return plan;
            best = plan;
        }
        return best;
    }

    private boolean hasMoreSpecificImageBackedContentPlan(ObjectPlan parent) {
        if (parent == null || parent.sourceObjectIds == null || parent.sourceObjectIds.length == 0) {
            return false;
        }
        for (ObjectPlan candidate : plans) {
            if (candidate == null || candidate == parent) continue;
            if (candidate.pageIndex != parent.pageIndex) continue;
            if (!isImageBackedContentPlan(candidate)) continue;
            if (candidate.domId == parent.domId) continue;
            if (candidate.sourceObjectIds == null || candidate.sourceObjectIds.length == 0) continue;
            if (candidate.sourceObjectIds.length >= parent.sourceObjectIds.length) continue;
            if (!containsAny(parent.sourceObjectIds, candidate.sourceObjectIds)) continue;
            if (!containsAny(visualSourceIds(parent), visualSourceIds(candidate))
                    && !containsAny(parent.sourceObjectIds, visualSourceIds(candidate))) {
                continue;
            }
            return true;
        }
        return false;
    }

    private void dropCompositeImageBackedVisualsOwnedByLeafChannels() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan parent = plans.get(i);
            if (parent == null) continue;
            if (!isImageBackedContentShellPlan(parent)) continue;
            if (parent.visualAction != VisualAction.PLACE_FLOATING_PNG
                    && parent.visualAction != VisualAction.PLACE_INLINE_PNG) {
                continue;
            }
            if (!hasMoreSpecificVisibleImageBackedContentOwner(parent)) continue;
            plans.set(i, parent
                    .withVisualAction(VisualAction.DROP_VISUAL,
                            "content_visual_owned_by_leaf_channels")
                    .withVisualSourceObjectIds(new int[0])
                    .withDescendantVisualObjectIds(new int[0]));
        }
    }

    private boolean hasMoreSpecificVisibleImageBackedContentOwner(ObjectPlan parent) {
        if (parent == null || parent.sourceObjectIds == null || parent.sourceObjectIds.length == 0) {
            return false;
        }
        for (ObjectPlan candidate : plans) {
            if (candidate == null || candidate == parent) continue;
            if (candidate.pageIndex != parent.pageIndex) continue;
            if (!isImageBackedContentPlan(candidate)) continue;
            if (candidate.domId == parent.domId) continue;
            if (candidate.visualAction != VisualAction.PLACE_FLOATING_PNG
                    && candidate.visualAction != VisualAction.PLACE_INLINE_PNG) {
                continue;
            }
            if (candidate.sourceObjectIds == null || candidate.sourceObjectIds.length == 0) continue;
            if (candidate.sourceObjectIds.length >= parent.sourceObjectIds.length) continue;
            if (!containsAny(parent.sourceObjectIds, candidate.sourceObjectIds)) continue;
            if (!containsAny(visualSourceIds(parent), visualSourceIds(candidate))
                    && !containsAny(parent.sourceObjectIds, visualSourceIds(candidate))) {
                continue;
            }
            return true;
        }
        return false;
    }

    private ObjectPlan declareContentVisualPlan(ObjectPlan plan) {
        VisualAction visualAction = plan.placement == Placement.INLINE
                ? VisualAction.PLACE_INLINE_PNG
                : VisualAction.PLACE_FLOATING_PNG;
        Materialization materialization = plan.placement == Placement.INLINE
                ? Materialization.COMPLETE_PNG
                : Materialization.EXTRACTED_PNG_VECTOR;
        return plan
                .withTextAction(TextAction.DROP_TEXT)
                .withVisualAction(visualAction, "content_visual_child_split_from_text_shell_parent")
                .withMaterialization(materialization)
                .withOwnedTextFrameIds(new int[0])
                .withVisualSourceObjectIds(withoutTextFrameSources(visualSourceIds(plan)));
    }

    private void normalizeContentVisualSlotsExcludeVisibleTextShellSlots() {
        LinkedHashMap<Integer, LinkedHashSet<Integer>> shellVisualSourcesByPage = new LinkedHashMap<>();
        for (ObjectPlan shell : plans) {
            if (!isVisibleTextShell(shell)) continue;
            LinkedHashSet<Integer> ids = shellVisualSourcesByPage.computeIfAbsent(
                    shell.pageIndex, k -> new LinkedHashSet<>());
            addAll(visualSourceIds(shell), ids);
        }
        if (shellVisualSourcesByPage.isEmpty()) return;

        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!isVisibleContentVisualPlan(plan)) continue;
            LinkedHashSet<Integer> shellSources = shellVisualSourcesByPage.get(plan.pageIndex);
            int[] retainedVisualSources = shellSources == null || shellSources.isEmpty()
                    ? withoutTextFrameSources(visualSourceIds(plan))
                    : withoutSourcesAllowEmpty(withoutTextFrameSources(visualSourceIds(plan)), shellSources);
            if (retainedVisualSources.length == visualSourceIds(plan).length
                    && (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0)) {
                continue;
            }
            ObjectPlan replacement = plan
                    .withOwnedTextFrameIds(new int[0])
                    .withVisualSourceObjectIds(retainedVisualSources);
            if (retainedVisualSources.length == 0) {
                replacement = replacement.withVisualAction(VisualAction.DROP_VISUAL,
                        "visual_slot_owned_by_visible_text_shell");
            }
            plans.set(i, replacement);
        }
    }

    private boolean isVisibleContentVisualPlan(ObjectPlan plan) {
        if (plan == null || !plan.hasVisibleVisual()) return false;
        if (plan.visualAction != VisualAction.PLACE_FLOATING_PNG
                && plan.visualAction != VisualAction.PLACE_INLINE_PNG) {
            return false;
        }
        return plan.visualPolicyLayer() == PolicyLayer.CONTENT
                || plan.visualLayer == VisualLayer.CONTENT_VISUAL;
    }

    private int[] withoutTextFrameSources(int[] values) {
        if (values == null || values.length == 0 || data == null) return values;
        LinkedHashSet<Integer> retained = new LinkedHashSet<>();
        for (int value : values) {
            if (data.getTextFrame(String.valueOf(value)) != null) continue;
            retained.add(value);
        }
        return toIntArray(retained);
    }

    private void trimTextShellParentsForContentChild(ObjectPlan child) {
        if (child == null) return;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan parent = plans.get(i);
            if (parent == null || parent == child) continue;
            if (parent.pageIndex != child.pageIndex) continue;
            if (parent.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (parent.visualPolicyLayer() != PolicyLayer.BACKGROUND) continue;

            LinkedHashSet<Integer> childSources = intSetOf(child.sourceObjectIds);
            int[] retainedVisual = withoutSources(visualSourceIds(parent), childSources);
            int[] retainedDescendants = withoutSources(parent.descendantVisualObjectIds, childSources);
            if (retainedVisual.length == visualSourceIds(parent).length
                    && retainedDescendants.length == parent.descendantVisualObjectIds.length) {
                continue;
            }
            plans.set(i, parent
                    .withVisualSourceObjectIds(retainedVisual)
                    .withDescendantVisualObjectIds(retainedDescendants));
        }
    }

    private static LinkedHashSet<Integer> intSetOf(int[] values) {
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        if (values == null) return out;
        for (int value : values) {
            out.add(value);
        }
        return out;
    }

    private void trimSiblingContentChannels(ObjectPlan owner) {
        if (owner == null || owner.sourceObjectIds == null || owner.sourceObjectIds.length == 0) return;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan candidate = plans.get(i);
            if (candidate == null || candidate == owner) continue;
            if (candidate.pageIndex != owner.pageIndex) continue;
            if (candidate.domId != owner.domId) continue;
            if (!containsAny(candidate.sourceObjectIds, owner.sourceObjectIds)) continue;
            if (candidate.visualAction != VisualAction.PLACE_FLOATING_PNG
                    && candidate.visualAction != VisualAction.PLACE_INLINE_PNG
                    && candidate.visualAction != VisualAction.PLACE_TEXT_SHELL) {
                continue;
            }
            plans.set(i, candidate
                    .withTextAction(TextAction.DROP_TEXT)
                    .withVisualAction(VisualAction.DROP_VISUAL,
                            "content_visual_owned_by_paired_channel")
                    .withOwnedTextFrameIds(new int[0])
                    .withDescendantVisualObjectIds(new int[0]));
        }
    }

    private boolean hasPlacedContentSource(ObjectPlan plan) {
        if (plan == null || data == null) return false;
        for (int sourceId : plan.sourceObjectIds) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (isPlacedContentItem(item)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPlacedContentSourceTree(ObjectPlan plan) {
        if (plan == null || data == null) return false;
        for (int sourceId : plan.sourceObjectIds) {
            if (hasPlacedContentSourceTree(sourceId)) return true;
        }
        return false;
    }

    private boolean hasVisibleShellRootWithPlacedContentTree(ObjectPlan plan) {
        if (plan == null || data == null) return false;
        if (!isTextlessCompositeShellContainerReason(plan.reason)) return false;
        RenderedGroup rg = renderedGroupForPlan(plan);
        if (hasVisibleShellRootWithPlacedContentTree(rg)) return true;
        return hasVisibleShellRootWithPlacedContentTree(plan.domId);
    }

    private boolean hasVisibleShellRootWithPlacedContentTree(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (!isTextlessCompositeShellContainerReason(rg.reason())) return false;
        return hasVisibleShellRootWithPlacedContentTree(rg.id());
    }

    private static boolean isTextlessCompositeShellContainerReason(String reason) {
        String value = safe(reason);
        return value.contains("complex_graphic_text_hidden")
                || value.contains("mixed_group_text_hidden")
                || value.contains("image_group_text_hidden")
                || value.contains("clip_carrying_textless_shell_owner")
                || value.contains("composite_shell_carrier");
    }

    private boolean hasVisibleShellRootWithPlacedContentTree(int sourceId) {
        if (sourceId <= 0 || data == null) return false;
        ResolvedPageItem root = data.getPageItem(String.valueOf(sourceId));
        if (!isVisibleShapeShellMaterial(root)) return false;
        return hasPlacedContentDescendantSourceTree(sourceId);
    }

    private boolean hasPlacedContentDescendantSourceTree(int sourceId) {
        if (sourceId <= 0 || data == null) return false;
        for (String childId : data.buildDescendantSet(String.valueOf(sourceId), 8)) {
            if (isPlacedContentItem(data.getPageItem(childId))) return true;
        }
        return false;
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

    private boolean isImageBackedContentPlan(ObjectPlan plan) {
        if (plan == null) return false;
        boolean hasPlacedContent = hasPlacedContentSource(plan);
        if (!hasPlacedContent && !isImageBackedContentShellPlan(plan)) return false;
        String file = safe(plan.file);
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        String basename = slash >= 0 ? file.substring(slash + 1) : file;
        if (basename.startsWith("img_")) return true;
        if (hasPlacedContent && basename.startsWith("inline_")) return true;
        if (hasPlacedContent && basename.startsWith("deco_")) return true;
        return false;
    }

    private static boolean isVisibleTextShell(ObjectPlan plan) {
        return plan != null
                && plan.hasVisibleVisual()
                && plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                && visualSourceIds(plan).length > 0;
    }

    private static boolean inlineTextShellOwnsMoreSpecificText(ObjectPlan candidate, ObjectPlan parent) {
        if (candidate == null || parent == null) return false;
        if (candidate.placement != Placement.INLINE) return false;
        if (candidate.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (candidate.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        if (candidate.ownedTextFrameIds == null || candidate.ownedTextFrameIds.length == 0) return false;
        if (parent.ownedTextFrameIds == null || parent.ownedTextFrameIds.length == 0) return true;
        if (!containsAll(parent.ownedTextFrameIds, candidate.ownedTextFrameIds)) return true;
        return candidate.ownedTextFrameIds.length > parent.ownedTextFrameIds.length;
    }

    private boolean parentTextShellMayOwnDescendantVisual(ObjectPlan parent, ObjectPlan child) {
        if (parent == null || child == null) return false;
        if (isImageBackedContentShellPlan(parent) || isImageBackedContentShellPlan(child)) return false;
        if (visualOnlyChildOwnsDistinctShellSlot(parent, child)) return false;
        if (visualOnlyChildFullyClaimedByTextShellParent(parent, child)) return true;
        return false;
    }

    private static boolean isInlineStoryFlowVisual(ObjectPlan plan) {
        return plan != null
                && plan.placement == Placement.INLINE
                && plan.coordinateSpace == CoordinateSpace.STORY_FLOW
                && safe(plan.kind).contains("inline_object");
    }

    private static boolean visualOnlyChildFullyClaimedByTextShellParent(ObjectPlan parent, ObjectPlan child) {
        if (parent == null || child == null) return false;
        if (isInlineStoryFlowVisual(child)) return false;
        if (parent.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (parent.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        if (child.textAction != TextAction.DROP_TEXT) return false;
        if (child.visualAction == VisualAction.PLACE_TEXT_SHELL) return false;
        if (child.ownedTextFrameIds != null && child.ownedTextFrameIds.length > 0) return false;
        int[] childVisualSources = visualSourceIds(child);
        if (childVisualSources.length == 0) return false;
        return containsAll(visualSourceIds(parent), childVisualSources);
    }

    private static boolean childOwnsDistinctShellSlot(ObjectPlan parent, ObjectPlan child) {
        if (parent == null || child == null) return false;
        if (child.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        boolean ownsText = child.ownedTextFrameIds != null && child.ownedTextFrameIds.length > 0;
        boolean ownsVisualOnlyShell = isDirectVisualOnlyShellSlot(child);
        if (!ownsText && !ownsVisualOnlyShell) return false;
        if (child.visualSourceObjectIds == null || child.visualSourceObjectIds.length == 0) return false;
        if (child.materialization != Materialization.NATIVE_SOURCE_SHAPE
                && !hasExplicitTextlessShellSignal(child)) return false;
        if (parent.ownedTextFrameIds != null
                && parent.ownedTextFrameIds.length > 0
                && ownsText
                && !containsAll(parent.ownedTextFrameIds, child.ownedTextFrameIds)) {
            return false;
        }
        return parent.visualSourceObjectIds == null
                || parent.visualSourceObjectIds.length == 0
                || sourceCount(child) < sourceCount(parent);
    }

    private static boolean isDirectVisualOnlyShellSlot(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.textAction != TextAction.DROP_TEXT) return false;
        if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.materialization != Materialization.EXTRACTED_PNG_VECTOR) return false;
        if (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) return false;
        if (visualSourceIds(plan).length == 0) return false;
        if (!hasExplicitTextlessShellSignal(plan)) return false;
        PolicyLayer layer = plan.visualPolicyLayer();
        return layer == PolicyLayer.DECORATION || layer == PolicyLayer.BACKGROUND;
    }

    private static PolicyLayer effectiveVisualPolicyLayer(ObjectPlan plan) {
        if (plan == null) return PolicyLayer.CONTENT;
        if (isAtomicOwnershipRootTextHiddenShellPlan(plan)) {
            return PolicyLayer.DECORATION;
        }
        if (isImageBackedContentShellPlan(plan)) {
            return PolicyLayer.CONTENT;
        }
        return plan.visualPolicyLayer();
    }

    private static boolean isImageBackedContentShellPlan(ObjectPlan plan) {
        if (plan == null) return false;
        if (!safe(plan.reason).contains("image_group_text_hidden")) return false;
        String file = safe(plan.file);
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        String basename = slash >= 0 ? file.substring(slash + 1) : file;
        return basename.startsWith("img_");
    }

    private static boolean textShellOwnerCoversChild(ObjectPlan owner, ObjectPlan child) {
        if (owner == null || child == null) return false;
        if (ownsSourcePositionedInlineTextShell(owner, child)) return true;
        if (owner.bounds != null && child.bounds != null) {
            if (!boundsContains(owner.bounds, child.bounds, 4.0)
                    && !boundsMostlyOverlap(owner.bounds, child.bounds, 0.70)) {
                return false;
            }
        }
        if (!containsAll(visualSourceIds(owner), visualSourceIds(child))) return false;
        return sourceCount(owner) > sourceCount(child)
                || (owner.ownedTextFrameIds != null
                    && child.ownedTextFrameIds != null
                    && owner.ownedTextFrameIds.length > child.ownedTextFrameIds.length);
    }

    private boolean nativeSourceShellHasDirectTextOwner(ObjectPlan child) {
        if (child == null || child.materialization != Materialization.NATIVE_SOURCE_SHAPE) return false;
        if (child.ownedTextFrameIds == null || child.ownedTextFrameIds.length == 0) return false;
        for (int textFrameId : child.ownedTextFrameIds) {
            if (hasDirectHwpxTextFrameOwner(textFrameId)) return true;
        }
        return false;
    }

    private static boolean ownsSourcePositionedInlineTextShell(ObjectPlan owner, ObjectPlan child) {
        if (!isSourcePositionedInlineTextShellRelation(owner, child)) return false;
        return !isImageBackedContentShellPlan(owner);
    }

    private static boolean isImageBackedSourcePositionedInlineTextShell(ObjectPlan owner, ObjectPlan child) {
        return isSourcePositionedInlineTextShellRelation(owner, child)
                && isImageBackedContentShellPlan(owner);
    }

    private static boolean isSourcePositionedInlineTextShellRelation(ObjectPlan owner, ObjectPlan child) {
        if (owner == null || child == null) return false;
        if (owner.placement != Placement.FLOATING || child.placement != Placement.INLINE) return false;
        if (owner.visualAction != VisualAction.PLACE_TEXT_SHELL
                || child.visualAction != VisualAction.PLACE_TEXT_SHELL) {
            return false;
        }
        if (!safe(child.kind).contains("inline_object")) return false;
        if (!isDirectInlineTextShellReason(child.reason)) return false;
        if (!containsAll(owner.sourceObjectIds, child.sourceObjectIds)) return false;
        return ownedTextFramesCoveredBy(owner, child);
    }

    private void resolveClusterOwnedTextFrameShells() {
        List<ObjectPlan> clusterShells = new ArrayList<>();
        for (ObjectPlan plan : plans) {
            if (isWideShortMultiTextShell(plan)) {
                clusterShells.add(plan);
            }
        }
        if (clusterShells.isEmpty()) return;

        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan child = plans.get(i);
            if (!isVisibleRenderedVisual(child)) continue;
            if (!"editable_textframe_visual_shell".equals(child.reason)) continue;
            for (ObjectPlan cluster : clusterShells) {
                if (child.pageIndex != cluster.pageIndex) continue;
                if (!boundsMostlyOverlap(cluster.bounds, child.bounds, 0.92)) continue;
                if (!isComparableOrSmallerShell(child, cluster)) continue;
                plans.set(i, child.withVisualAction(VisualAction.DROP_VISUAL,
                        "covered_by_cluster_text_shell"));
                break;
            }
        }
    }

    private void resolveNonVisibleFloatingVisuals() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!isVisibleRenderedVisual(plan)) continue;
            if (plan.placement != Placement.FLOATING) continue;
            if (plan.visualAction != VisualAction.PLACE_FLOATING_PNG) continue;
            if ("planner_declared_object_plan".equals(safe(plan.reason))) continue;
            if ("anchored_inline_visual_uses_page_position".equals(safe(plan.reason))) continue;
            double[] bounds = plan.bounds;
            if (bounds == null || bounds.length < 4) {
                warn("VISIBLE_FLOATING_PLAN_MISSING_BOUNDS",
                        "dom=" + plan.domId
                                + " render=" + (plan.renderId != null ? plan.renderId : -1)
                                + " reason=" + safe(plan.reason));
                continue;
            }
            double[] pageBounds = normalizeSpreadBoundsToPage(plan.pageIndex, bounds);
            if (hasMainPageIntersection(plan.pageIndex, pageBounds)) continue;
            if (isLabelBackdropGroupPlan(plan)
                    && hasMainPageIntersectionInPageBoundsUnits(plan.pageIndex, pageBounds)) {
                continue;
            }
            plans.set(i, plan.withVisualAction(VisualAction.DROP_VISUAL,
                    "no_visible_page_intersection"));
        }
    }

    private boolean hasMainPageIntersectionInPageBoundsUnits(int pageIndex, double[] bounds) {
        if (bounds == null || bounds.length < 4) return true;
        double[] page = pageBounds(pageIndex);
        if (page == null || page.length < 4) return false;
        double pageWidth = page[3] - page[1];
        double pageHeight = page[2] - page[0];
        if (pageWidth <= 0.0 || pageHeight <= 0.0) return false;
        return bounds[3] > 0.0 && bounds[1] < pageWidth
                && bounds[2] > 0.0 && bounds[0] < pageHeight;
    }

    private boolean hasMainPageIntersection(int pageIndex, double[] bounds) {
        if (bounds == null || bounds.length < 4) return true;
        double pageWidth = pageWidthMm(pageIndex);
        double pageHeight = pageHeightMm(pageIndex);
        return bounds[3] > 0.0 && bounds[1] < pageWidth
                && bounds[2] > 0.0 && bounds[0] < pageHeight;
    }

    private void resolveDroppedRenderedTextOwnership() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if ("text_frame".equals(plan.kind)) continue;
            if (plan.visualAction != VisualAction.DROP_VISUAL) continue;
            if (!shouldDropTextForDroppedRenderedPlan(plan)) continue;
            plans.set(i, plan
                    .withTextAction(TextAction.DROP_TEXT)
                    .withOwnedTextFrameIds(new int[0])
                    .withDescendantVisualObjectIds(new int[0]));
        }
    }

    private static boolean shouldDropTextForDroppedRenderedPlan(ObjectPlan plan) {
        if (plan == null || plan.textAction == TextAction.DROP_TEXT) return false;
        if (plan.textAction == TextAction.OWNED_BY_PNG) return true;
        if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        String reason = safe(plan.reason);
        return reason.contains("text_hidden")
                || reason.contains("complex_graphic")
                || reason.contains("image_group")
                || reason.contains("mixed_group")
                || reason.contains("text_owned_container_shell_duplicate_child")
                || reason.contains("floating_child_owned_by_inline_parent")
                || reason.contains("no_visible_page_intersection");
    }

    private void resolveVisibleVisualHwpxTextSourceSlots() {
        HashSet<String> textOwnedSlots = new HashSet<>();
        for (ObjectPlan plan : plans) {
            if (!"text_frame".equals(plan.kind)) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            for (int textFrameId : textFrameIdsForPlan(plan)) {
                textOwnedSlots.add(pageSourceKey(plan.pageIndex, textFrameId));
            }
        }
        if (textOwnedSlots.isEmpty()) return;

        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if ("text_frame".equals(plan.kind)) continue;
            if (!isVisibleRenderedVisual(plan)) continue;
            if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            List<Integer> retained = new ArrayList<>();
            boolean changed = false;
            for (int sourceId : visualSourceIds(plan)) {
                if (sourceId != plan.domId
                        && data.getTextFrame(String.valueOf(sourceId)) != null
                        && textOwnedSlots.contains(pageSourceKey(plan.pageIndex, sourceId))) {
                    changed = true;
                    continue;
                }
                retained.add(sourceId);
            }
            if (!changed || retained.isEmpty()) continue;
            ObjectPlan next = plan.withVisualSourceObjectIds(toIntArray(retained));
            if (textFrameIdsForPlan(next).length == 0) {
                next = next.withTextAction(TextAction.DROP_TEXT);
            }
            plans.set(i, next);
        }
    }

    private void resolveNonTextVisualEditableTextSources() {
        HashSet<Integer> hwpxTextSources = new HashSet<>();
        for (ObjectPlan plan : plans) {
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            for (int sourceId : textFrameIdsForPlan(plan)) {
                hwpxTextSources.add(sourceId);
            }
        }
        if (hwpxTextSources.isEmpty()) return;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!isVisibleRenderedVisual(plan)) continue;
            if (plan.textAction == TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (plan.textAction == TextAction.OWNED_BY_PNG) continue;
            List<Integer> retained = new ArrayList<>();
            boolean changed = false;
            for (int sourceId : visualSourceIds(plan)) {
                if (sourceId != plan.domId && hwpxTextSources.contains(sourceId)) {
                    changed = true;
                    continue;
                }
                retained.add(sourceId);
            }
            if (!changed) continue;
            plans.set(i, plan.withVisualSourceObjectIds(toIntArray(retained)));
        }
    }

    private void warnDuplicateVisibleSourceIds() {
        Map<String, List<ObjectPlan>> byPageSource = new LinkedHashMap<>();
        for (ObjectPlan plan : plans) {
            if (!plan.hasVisibleVisual()) continue;
            for (int sourceId : visualSourceIds(plan)) {
                String key = plan.pageIndex + ":" + sourceId;
                byPageSource.computeIfAbsent(key, k -> new ArrayList<>()).add(plan);
            }
        }
        Map<String, LinkedHashSet<String>> groupedSources = new LinkedHashMap<>();
        for (Map.Entry<String, List<ObjectPlan>> e : byPageSource.entrySet()) {
            if (e.getValue().size() <= 1) continue;
            String[] parts = e.getKey().split(":", 2);
            String page = parts.length > 0 ? parts[0] : "";
            String source = parts.length > 1 ? parts[1] : e.getKey();
            String planRefs = planRefs(e.getValue());
            String groupKey = "page=" + page + " plans=" + planRefs;
            groupedSources.computeIfAbsent(groupKey, k -> new LinkedHashSet<>()).add(source);
        }
        for (Map.Entry<String, LinkedHashSet<String>> e : groupedSources.entrySet()) {
            warn("DUPLICATE_VISIBLE_SOURCE",
                    e.getKey() + " sources=" + joinSources(e.getValue(), 24));
        }
    }

    private void warnConflictingTextOwnership() {
        Map<Integer, Boolean> pngOwned = new HashMap<>();
        Map<Integer, Boolean> hwpxOwned = new HashMap<>();
        for (ObjectPlan plan : plans) {
            int[] textIds = textFrameIdsForPlan(plan);
            if (textIds.length == 0) continue;
            for (int id : textIds) {
                if (plan.textAction == TextAction.OWNED_BY_PNG) {
                    pngOwned.put(id, Boolean.TRUE);
                } else if (plan.textAction == TextAction.OWNED_BY_HWPX_TEXT) {
                    hwpxOwned.put(id, Boolean.TRUE);
                }
            }
        }
        for (Integer id : pngOwned.keySet()) {
            if (Boolean.TRUE.equals(hwpxOwned.get(id))) {
                warn("CONFLICTING_TEXT_OWNER",
                        "textFrameId=" + id + " has OWNED_BY_PNG and OWNED_BY_HWPX_TEXT");
            }
        }
    }

    private void warnVisibleVisualContainsHwpxTextSource() {
        for (ObjectPlan plan : plans) {
            if (!plan.hasVisibleVisual()) continue;
            if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL) continue;
            if (plan.visualAction == VisualAction.ABSORB_TEXT_STYLE) continue;
            if (plan.visualAction == VisualAction.PLACE_TABLE_STYLE) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            int[] textIds = textFrameIdsForPlan(plan);
            if (textIds.length == 0) continue;
            if (!visualSourcesContainAny(plan, textIds)) continue;
            warn("VISIBLE_VISUAL_CONTAINS_HWPX_TEXT_SOURCE",
                    "plan=" + planRefs(java.util.Collections.singletonList(plan))
                            + " textFrameIds=" + ObjectPlan.intArrayJson(textIds));
        }
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

    private void warnInlineFloatingSameDomId() {
        Map<Integer, Boolean> inlineVisible = new HashMap<>();
        Map<Integer, Boolean> floatingVisible = new HashMap<>();
        for (ObjectPlan plan : plans) {
            if (!plan.hasVisibleVisual()) continue;
            if (plan.placement == Placement.INLINE) {
                inlineVisible.put(plan.domId, Boolean.TRUE);
            } else if (plan.placement == Placement.FLOATING) {
                floatingVisible.put(plan.domId, Boolean.TRUE);
            }
        }
        for (Integer id : inlineVisible.keySet()) {
            if (Boolean.TRUE.equals(floatingVisible.get(id))) {
                warn("INLINE_FLOATING_SAME_DOM",
                        "domId=" + id + " has visible inline and floating plans");
            }
        }
    }

    private void warnDuplicateRenderedBounds() {
        Map<String, List<ObjectPlan>> byBounds = new LinkedHashMap<>();
        for (ObjectPlan plan : plans) {
            if (!plan.hasVisibleVisual()) continue;
            if (plan.file == null || plan.file.isEmpty()) continue;
            if (plan.bounds == null || plan.bounds.length < 4) continue;
            String key = plan.pageIndex + ":" + plan.file + ":" + roundedBounds(plan.bounds);
            byBounds.computeIfAbsent(key, k -> new ArrayList<>()).add(plan);
        }
        for (List<ObjectPlan> same : byBounds.values()) {
            if (same.size() <= 1) continue;
            warn("DUPLICATE_VISIBLE_FILE_BOUNDS", "plans=" + planRefs(same));
        }
    }

    private void warnTextShellZOrder() {
        Map<Integer, Integer> hwpxTextZByTextFrame = new LinkedHashMap<>();
        for (ObjectPlan plan : plans) {
            if (plan == null || plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            for (int textFrameId : textFrameIdsForPlan(plan)) {
                Integer existing = hwpxTextZByTextFrame.get(textFrameId);
                if (existing == null || plan.zOrder > existing) {
                    hwpxTextZByTextFrame.put(textFrameId, plan.zOrder);
                }
            }
        }
        for (ObjectPlan plan : plans) {
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (isBackPlaneTextShell(plan)) continue;
            if (isDirectInlineTextFrameDrawTextPlan(plan)) continue;
            for (int id : ownedTextFrameIdsForPlan(plan)) {
                Integer textZ = hwpxTextZByTextFrame.get(id);
                if (textZ == null) continue;
                if (plan.zOrder >= textZ) {
                    warn("TEXT_SHELL_ZORDER_GE_TEXT",
                            "shell=" + plan.domId + " textFrame=" + id
                                    + " shellZ=" + plan.zOrder + " textZ=" + textZ);
                }
            }
        }
    }

    private int[] textFrameIdsForPlan(ObjectPlan plan) {
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        if (plan.ownedTextFrameIds != null) {
            for (int ownedId : plan.ownedTextFrameIds) {
                if (data.getTextFrame(String.valueOf(ownedId)) != null) {
                    ids.add(ownedId);
                }
            }
        }
        for (int sourceId : plan.sourceObjectIds) {
            if (data.getTextFrame(String.valueOf(sourceId)) != null) {
                ids.add(sourceId);
            }
        }
        int[] out = new int[ids.size()];
        int i = 0;
        for (Integer id : ids) out[i++] = id;
        return out;
    }

    private int[] ownedTextFrameIdsForPlan(ObjectPlan plan) {
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        if (plan != null && plan.ownedTextFrameIds != null) {
            for (int ownedId : plan.ownedTextFrameIds) {
                if (data.getTextFrame(String.valueOf(ownedId)) != null) {
                    ids.add(ownedId);
                }
            }
        }
        int[] out = new int[ids.size()];
        int i = 0;
        for (Integer id : ids) out[i++] = id;
        return out;
    }

    private void warn(String code, String detail) {
        ctx.ownershipWarningLines.add("{\"code\":\"" + ObjectPlan.escape(code)
                + "\",\"detail\":\"" + ObjectPlan.escape(detail) + "\"}");
    }

    private static boolean hasEditableTextFrameIds(RenderedGroup rg) {
        return rg.editableTextFrameIds() != null && rg.editableTextFrameIds().length > 0;
    }

    private boolean isUnabsorbedHwpxTextStyleInlineVisual(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (!"inline_object".equals(rg.itemType()) && !"inline_object".equals(rg.type())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return false;
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double w = Math.abs(b[3] - b[1]);
        double h = Math.abs(b[2] - b[0]);
        if (w <= 0.0) return false;
        boolean thinTextStyleStrip = h <= 3.0 && w >= 6.0 && w / Math.max(0.1, h) >= 4.0;
        if (!thinTextStyleStrip) return false;
        if (!safe(rg.parentStoryId()).isEmpty()) return true;

        int id = rg.id();
        for (RenderedGroup owner : allRenderedGroups()) {
            if (owner == null) continue;
            if (!"hwpx_tf".equals(owner.textOwner()) && !hasEditableTextFrameIds(owner)) continue;
            if (!contains(owner.tfInlineVisualIds(), id)
                    && !contains(sourceIdsOrSelf(owner), id)) {
                continue;
            }
            if (hasHwpxOwnedTextFrame(owner)) return true;
        }
        return false;
    }

    private boolean isInlineGraphicLineTextStyleMarker(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (!data.isInlineObjectId(rg.id())) return false;
        if (!"inline_graphic_only".equals(rg.reason())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return false;
        int[] sourceIds = sourceIdsOrSelf(rg);
        if (sourceIds.length != 1) return false;
        ResolvedPageItem item = data.getPageItem(String.valueOf(sourceIds[0]));
        if (item == null || !item.isInline()) return false;
        if (!"GraphicLine".equals(safe(item.type()))) return false;
        double[] b = boundsOf(item);
        if (b == null || b.length < 4) b = rg.bounds();
        if (b == null || b.length < 4) return true;
        double w = Math.abs(b[3] - b[1]);
        double h = Math.abs(b[2] - b[0]);
        return w > 0.0 && h <= 3.0;
    }

    private boolean containsHwpxOwnedTextFrameSource(ObjectPlan plan) {
        if (plan == null || plan.sourceObjectIds == null) return false;
        for (int sourceId : plan.sourceObjectIds) {
            ResolvedTextFrame tf = data.getTextFrame(String.valueOf(sourceId));
            if (tf != null && data.isHwpxOwnedTextFrame(tf.id())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasHwpxOwnedTextFrame(RenderedGroup rg) {
        if (rg == null) return false;
        if (rg.editableTextFrameIds() != null) {
            for (String id : rg.editableTextFrameIds()) {
                if (id != null && data.isHwpxOwnedTextFrame(id)) return true;
            }
        }
        if (rg.sourceObjectIds() != null) {
            for (int sourceId : rg.sourceObjectIds()) {
                ResolvedTextFrame tf = data.getTextFrame(String.valueOf(sourceId));
                if (tf != null && data.isHwpxOwnedTextFrame(tf.id())) return true;
            }
        }
        return false;
    }

    private boolean isLabelBackdropGroupWithUnclaimedHwpxText(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (!"label_backdrop_group".equals(rg.reason())) return false;
        HashSet<Integer> claimedTextFrames = new HashSet<>();
        if (rg.editableTextFrameIds() != null) {
            for (String id : rg.editableTextFrameIds()) {
                int parsed = parseFlexibleId(id);
                if (parsed >= 0) claimedTextFrames.add(parsed);
            }
        }
        HashSet<Integer> visited = new HashSet<>();
        for (int sourceId : sourceIdsOrSelf(rg)) {
            if (sourceId < 0) continue;
            if (containsUnclaimedHwpxTextFrame(sourceId, claimedTextFrames, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLabelBackdropGroupWithForeignSources(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (!"label_backdrop_group".equals(rg.reason())) return false;
        return labelBackdropGroupHasForeignSources(rg);
    }

    private boolean labelBackdropGroupHasForeignSources(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (!"label_backdrop_group".equals(rg.reason())) return false;
        String[] editableIds = rg.editableTextFrameIds();
        if (editableIds == null || editableIds.length == 0) return false;
        HashSet<Integer> claimedTextFrames = new HashSet<>();
        HashSet<String> allowedAncestorIds = new HashSet<>();
        HashSet<String> allowedParentIds = new HashSet<>();
        for (String tfId : editableIds) {
            if (tfId == null) continue;
            int parsed = parseFlexibleId(tfId);
            if (parsed >= 0) claimedTextFrames.add(parsed);
            ResolvedPageItem tfItem = data.getPageItem(tfId);
            if (tfItem == null) continue;
            String parentId = tfItem.parentId();
            if (parentId == null || parentId.isEmpty()) continue;
            allowedParentIds.add(parentId);
            String cur = parentId;
            HashSet<String> visited = new HashSet<>();
            while (cur != null && !cur.isEmpty() && visited.add(cur)) {
                allowedAncestorIds.add(cur);
                ResolvedPageItem parent = data.getPageItem(cur);
                cur = parent != null ? parent.parentId() : null;
            }
        }
        if (allowedAncestorIds.isEmpty() && allowedParentIds.isEmpty()) return false;
        for (int sourceId : sourceIdsOrSelf(rg)) {
            if (sourceId < 0 || claimedTextFrames.contains(sourceId)) continue;
            String sid = String.valueOf(sourceId);
            if (allowedAncestorIds.contains(sid)) continue;
            ResolvedPageItem item = data.getPageItem(sid);
            if (item == null) continue;
            String parentId = item.parentId();
            if (parentId != null
                    && (allowedParentIds.contains(parentId) || allowedAncestorIds.contains(parentId))) {
                continue;
            }
            return true;
        }
        return false;
    }

    private boolean containsUnclaimedHwpxTextFrame(
            int sourceId,
            HashSet<Integer> claimedTextFrames,
            HashSet<Integer> visited) {
        if (!visited.add(sourceId)) return false;
        ResolvedTextFrame tf = data.getTextFrame(String.valueOf(sourceId));
        if (tf != null && data.isHwpxOwnedTextFrame(tf.id()) && !claimedTextFrames.contains(sourceId)) {
            return true;
        }
        ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
        if (item != null && item.childIds() != null) {
            for (int childId : item.childIds()) {
                if (containsUnclaimedHwpxTextFrame(childId, claimedTextFrames, visited)) {
                    return true;
                }
            }
        }
        for (ResolvedPageItem candidate : data.pageItems()) {
            if (candidate == null || candidate.parentId() == null) continue;
            if (!candidate.parentId().equals(String.valueOf(sourceId))) continue;
            int childId = parseInt(candidate.id(), -1);
            if (childId >= 0 && containsUnclaimedHwpxTextFrame(childId, claimedTextFrames, visited)) {
                return true;
            }
        }
        return false;
    }

    private List<RenderedGroup> allRenderedGroups() {
        if (allRenderedGroupsCache != null) return allRenderedGroupsCache;
        List<RenderedGroup> out = new ArrayList<>();
        out.addAll(data.allRenderedFloatingItems());
        out.addAll(data.allRenderedGraphicFrames());
        out.addAll(data.allRenderedImageFrames());
        out.addAll(data.allRenderedPdfFrames());
        allRenderedGroupsCache = out;
        return allRenderedGroupsCache;
    }

    private List<RenderedGroup> renderedGroupsForPageId(int pageIndex, int renderId) {
        if (renderedGroupsByPageIdCache == null) {
            renderedGroupsByPageIdCache = new HashMap<>();
            for (RenderedGroup rg : allRenderedGroups()) {
                if (rg == null) continue;
                renderedGroupsByPageIdCache
                        .computeIfAbsent(pageDomKey(rg.pageIndex(), rg.id()), k -> new ArrayList<>())
                        .add(rg);
            }
        }
        List<RenderedGroup> groups = renderedGroupsByPageIdCache.get(pageDomKey(pageIndex, renderId));
        return groups != null ? groups : java.util.Collections.emptyList();
    }

    private List<RenderedGroup> initialCompositeShellCarriersForPage(int pageIndex) {
        if (initialCompositeShellCarriersByPageCache == null) {
            initialCompositeShellCarriersByPageCache = new HashMap<>();
            for (RenderedGroup rg : allRenderedGroups()) {
                if (!isInitialCompositeShellCarrierCandidate(rg)) continue;
                initialCompositeShellCarriersByPageCache
                        .computeIfAbsent(rg.pageIndex(), k -> new ArrayList<>())
                        .add(rg);
            }
        }
        List<RenderedGroup> groups = initialCompositeShellCarriersByPageCache.get(pageIndex);
        return groups != null ? groups : java.util.Collections.emptyList();
    }

    private List<RenderedGroup> initialVisibleChildShellFragmentsForPage(int pageIndex) {
        if (initialVisibleChildShellFragmentsByPageCache == null) {
            initialVisibleChildShellFragmentsByPageCache = new HashMap<>();
            for (RenderedGroup rg : allRenderedGroups()) {
                if (!isInitialVisibleChildShellFragment(rg)) continue;
                initialVisibleChildShellFragmentsByPageCache
                        .computeIfAbsent(rg.pageIndex(), k -> new ArrayList<>())
                        .add(rg);
            }
        }
        List<RenderedGroup> groups = initialVisibleChildShellFragmentsByPageCache.get(pageIndex);
        return groups != null ? groups : java.util.Collections.emptyList();
    }

    private static int[] sourceIdsOrSelf(RenderedGroup rg) {
        if (isAtomicObject(rg)
                && rg.atomicSourceObjectIds() != null
                && rg.atomicSourceObjectIds().length > 0) {
            int[] copy = Arrays.copyOf(rg.atomicSourceObjectIds(), rg.atomicSourceObjectIds().length);
            Arrays.sort(copy);
            return copy;
        }
        int[] ids = rg.sourceObjectIds();
        if (ids == null || ids.length == 0) return new int[] { rg.id() };
        int[] copy = Arrays.copyOf(ids, ids.length);
        Arrays.sort(copy);
        return copy;
    }

    private static boolean isGraphicOnlyAtomicObject(RenderedGroup rg) {
        return rg != null
                && "GRAPHIC_ONLY".equals(rg.atomicObjectKind())
                && !"indesign_png".equals(rg.textOwner())
                && !Boolean.TRUE.equals(rg.containsText())
                && !Boolean.TRUE.equals(rg.containsEditableText());
    }

    private static boolean isAtomicObject(RenderedGroup rg) {
        return rg != null
                && rg.atomicObjectKind() != null
                && !rg.atomicObjectKind().isBlank();
    }

    private int zOrderOf(RenderedGroup rg, VisualAction visualAction, int[] sourceIds) {
        int sourceZ = maxPageItemZOrder(sourceIds);
        if (sourceZ >= 0) {
            return sourceZ;
        }
        return rg.zOrder();
    }

    /**
     * Final Stage 1 gate for visual depth.
     *
     * <p>Earlier planner passes may rewrite ownership slots while migrating
     * legacy candidates into ObjectPlan records.  After this point, only
     * write/validate may run.  Executors must therefore treat the resulting
     * {@code visualLayer} and {@code zOrder} as the complete source-depth
     * contract and must not promote, demote, or band objects again.</p>
     */
    private void finalizeVisualDepthContracts() {
        if (data == null) return;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null || !plan.hasVisibleVisual()) continue;
            if (isPlannerDeclaredInlineTextShellContract(plan)) {
                if (isInlineOwnedTextShellBackPlane(plan, plan.visualLayer)) {
                    plans.set(i, plan.withVisualLayer(VisualLayer.TEXT_CARD_BACKDROP));
                }
                continue;
            }
            int sourceZ = canonicalVisualSourceZOrder(plan);
            VisualLayer layer = canonicalVisualPlane(plan, sourceZ);
            int plannedZ = canonicalVisualDepthZOrder(layer, plan.sourceLayerIndex, sourceZ);
            ObjectPlan next = plan;
            if (plannedZ != plan.zOrder) {
                next = next.withZOrder(plannedZ);
            }
            if (layer != null && layer != next.visualLayer) {
                next = next.withVisualLayer(layer);
            }
            if (next != plan) {
                plans.set(i, next);
            }
        }
        finalizeContainerOutlineDepthContracts();
        finalizeOwnedTextFrameDepthContracts();
    }

    private static int canonicalVisualDepthZOrder(
            VisualLayer layer,
            int sourceLayerIndex,
            int sourceZ) {
        return VisualPlanePolicy.textlessGraphicZOrder(layer, sourceLayerIndex, sourceZ);
    }

    private static PolicyLayer policyLayerForVisualDepth(VisualLayer layer) {
        if (layer == VisualLayer.PAGE_BACKGROUND) {
            return PolicyLayer.BACKGROUND;
        }
        return PolicyLayer.CONTENT;
    }

    private int canonicalVisualSourceZOrder(ObjectPlan plan) {
        boolean useLowest = usesLowestVisualSourceZOrder(plan);
        int sourceZ = useLowest
                ? minPageItemZOrder(visualDepthSourceRootObjectIds(plan))
                : maxPageItemZOrder(visualDepthSourceRootObjectIds(plan));
        if (sourceZ >= 0) return sourceZ;
        sourceZ = useLowest
                ? minPageItemZOrder(visualDepthSourceObjectIds(plan))
                : maxPageItemZOrder(visualDepthSourceObjectIds(plan));
        if (sourceZ >= 0) return sourceZ;
        sourceZ = useLowest
                ? minPageItemZOrder(visualSourceIds(plan))
                : maxPageItemZOrder(visualSourceIds(plan));
        if (sourceZ >= 0) return sourceZ;
        sourceZ = useLowest
                ? minPageItemZOrder(plan.sourceObjectIds)
                : maxPageItemZOrder(plan.sourceObjectIds);
        return sourceZ >= 0 ? sourceZ : plan.zOrder;
    }

    private static boolean usesLowestVisualSourceZOrder(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.visualSourceObjectIds == null || plan.visualSourceObjectIds.length <= 1) return false;
        String slotRole = safe(plan.slotRole);
        String candidateId = safe(plan.candidateId);
        String passId = safe(plan.planPassId);
        return "shell_slot_only".equals(slotRole)
                || "direct_child_shell_slot".equals(slotRole)
                || candidateId.contains("table_carrier_sibling_decoration")
                || candidateId.contains("direct_child_shell_slot")
                || "pass.decoration_groups".equals(passId)
                || "pass.editable_textframe_visual_shells".equals(passId);
    }

    private int[] visualDepthSourceRootObjectIds(ObjectPlan plan) {
        if (plan == null) return new int[0];
        int[] roots = withoutSources(plan.sourceRootObjectIds, toLinkedSet(plan.ownedTextFrameIds));
        if (roots.length > 0) return roots;
        return sourceRootObjectIds(visualDepthSourceObjectIds(plan));
    }

    private int[] visualDepthSourceObjectIds(ObjectPlan plan) {
        if (plan == null) return new int[0];
        int[] sourceIds = withoutSources(plan.sourceObjectIds, toLinkedSet(plan.ownedTextFrameIds));
        if (sourceIds.length > 0) return sourceIds;
        return withoutSources(visualSourceIds(plan), toLinkedSet(plan.ownedTextFrameIds));
    }

    private static boolean isPlannerDeclaredObjectPlan(ObjectPlan plan) {
        return plan != null && "planner_declared_object_plan".equals(safe(plan.reason));
    }

    private VisualLayer canonicalVisualPlane(ObjectPlan plan, int zOrder) {
        if (plan == null) return null;
        VisualLayer layer = canonicalPlacedContentVisualLayer(plan);
        if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                && hasPlacedContentContract(plan)) {
            return isBehindLocalHwpxTextBySourceDepth(plan.bounds, plan.pageIndex, zOrder)
                    ? VisualLayer.CONTENT_BACKDROP
                    : VisualLayer.CONTENT_VISUAL;
        }
        if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                && layer == VisualLayer.CONTAINER_BACKDROP
                && !isPlanAllowedBackgroundPlane(plan, zOrder)) {
            layer = VisualLayer.LABEL_BACKDROP;
        }
        if (isInlineOwnedTextShellBackPlane(plan, layer)) {
            return VisualLayer.TEXT_CARD_BACKDROP;
        }
        if (isOwnedTextShellBackPlane(plan, layer)) {
            return VisualLayer.TEXT_CARD_BACKDROP;
        }
        if (isTextCarrierBackdropShell(plan, layer, zOrder)) {
            return VisualLayer.CONTAINER_BACKDROP;
        }
        if (layer == VisualLayer.CONTENT_BACKDROP
                && plan.visualAction == VisualAction.PLACE_FLOATING_PNG
                && plan.placement == Placement.FLOATING
                && hasPlacedContentContract(plan)
                && !isBehindLocalHwpxTextBySourceDepth(renderedGroupForPlan(plan), plan.pageIndex, zOrder)) {
            return VisualLayer.CONTENT_VISUAL;
        }
        if (layer != VisualLayer.CONTENT_VISUAL) return layer;
        if (plan.visualAction != VisualAction.PLACE_FLOATING_PNG) return layer;
        if (plan.placement != Placement.FLOATING) return layer;
        RenderedGroup rendered = renderedGroupForPlan(plan);
        if (isBehindLocalHwpxTextBySourceDepth(rendered, plan.pageIndex, zOrder)) {
            return VisualLayer.CONTENT_BACKDROP;
        }
        return layer;
    }

    private static boolean isInlineOwnedTextShellBackPlane(ObjectPlan plan, VisualLayer layer) {
        return plan != null
                && plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                && layer == VisualLayer.LABEL_BACKDROP
                && plan.placement == Placement.INLINE
                && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                && plan.ownedTextFrameIds != null
                && plan.ownedTextFrameIds.length > 0;
    }

    private static boolean isOwnedTextShellBackPlane(ObjectPlan plan, VisualLayer layer) {
        return plan != null
                && plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                && (layer == VisualLayer.CONTAINER_BACKDROP
                || layer == VisualLayer.LABEL_BACKDROP
                || layer == VisualLayer.CONTENT_BACKDROP)
                && plan.ownedTextFrameIds != null
                && plan.ownedTextFrameIds.length > 0;
    }

    private boolean isTextCarrierBackdropShell(
            ObjectPlan plan,
            VisualLayer layer,
            int zOrder) {
        if (plan == null || data == null) return false;
        if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.placement != Placement.FLOATING) return false;
        if (effectiveCoordinateSpace(plan) != CoordinateSpace.PAGE) return false;
        if (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) return false;
        if (layer != VisualLayer.LABEL_BACKDROP) return false;
        double[] b = plan.bounds != null ? plan.bounds : null;
        if (b == null) {
            RenderedGroup rg = renderedGroupForPlan(plan);
            b = rg != null ? rg.bounds() : null;
        }
        return hasPageLevelSourceRoots(plan.sourceObjectIds)
                && isBackgroundBoundsSanityCandidate(b)
                && isBehindLocalHwpxTextBySourceDepth(b, plan.pageIndex, zOrder);
    }

    private boolean hasPlacedContentContract(ObjectPlan plan) {
        return hasPlacedContentSourceTree(plan) || isContentVisualSlotPlan(plan);
    }

    private static boolean isContentVisualSlotPlan(ObjectPlan plan) {
        if (plan == null) return false;
        if ("CONTENT_VISUAL_SLOT".equals(safe(plan.slotRole))) return true;
        String candidateId = safe(plan.candidateId);
        return candidateId.contains("CONTENT_VISUAL_SLOT")
                || candidateId.contains(".content_visual_slot")
                || candidateId.endsWith("content_visual_slot");
    }

    private VisualLayer canonicalPlacedContentVisualLayer(ObjectPlan plan) {
        if (plan == null) return null;
        VisualLayer layer = plan.visualLayer;
        if (plan.visualAction != VisualAction.PLACE_FLOATING_PNG
                && plan.visualAction != VisualAction.PLACE_INLINE_PNG) {
            return layer;
        }
        if (isPageSpanningBackdropVisualFragmentContract(plan)) {
            return VisualLayer.CONTENT_VISUAL;
        }
        if (isSourceAuthoredPageWashBackdropImage(
                renderedGroupForPlan(plan), plan.sourceObjectIds)) {
            return VisualLayer.CONTENT_VISUAL;
        }
        if (!hasPlacedContentSourceTree(plan)) return layer;
        if (hasVisibleShellRootWithPlacedContentTree(plan)) return layer;
        if (isBackgroundLayerName(plan.sourceLayerName)
                && (layer == VisualLayer.PAGE_BACKGROUND || layer == VisualLayer.CONTAINER_BACKDROP)) {
            return VisualLayer.CONTENT_VISUAL;
        }
        if (layer == VisualLayer.PAGE_BACKGROUND
                || layer == VisualLayer.CONTAINER_BACKDROP) {
            return VisualLayer.CONTENT_VISUAL;
        }
        if (layer == VisualLayer.CONTENT_BACKDROP) {
            if (plan.visualAction == VisualAction.PLACE_FLOATING_PNG
                    || plan.visualAction == VisualAction.PLACE_INLINE_PNG) {
                return VisualLayer.CONTAINER_BACKDROP;
            }
            if (isSourceDepthPageOrSpreadBackdropImage(
                    renderedGroupForPlan(plan), plan.sourceObjectIds)) {
                return VisualLayer.CONTAINER_BACKDROP;
            }
            return layer;
        }
        if (isSourceDepthPageOrSpreadBackdropImage(
                renderedGroupForPlan(plan), plan.sourceObjectIds)) {
            return layer;
        }
        return VisualLayer.CONTENT_VISUAL;
    }

    private static boolean isPageSpanningBackdropVisualFragmentContract(ObjectPlan plan) {
        return plan != null
                && "page_spanning_backdrop_visual_fragment".equals(safe(plan.reason));
    }

    private boolean isPlanAllowedBackgroundPlane(ObjectPlan plan, int zOrder) {
        if (plan == null || data == null) return false;
        if (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) return false;
        if (plan.hiddenVisualSourceObjectIds != null && plan.hiddenVisualSourceObjectIds.length > 0) return false;
        if (!hasPageLevelSourceRoots(plan.sourceObjectIds)) return false;
        double[] b = plan.bounds;
        if (b == null) {
            RenderedGroup rg = renderedGroupForPlan(plan);
            b = rg != null ? rg.bounds() : null;
        }
        if (!isBackgroundBoundsSanityCandidate(b)) return false;
        return isBehindLocalHwpxTextBySourceDepth(b, plan.pageIndex, zOrder);
    }

    private boolean isBehindLocalHwpxTextBySourceDepth(RenderedGroup rg, int pageIndex, int zOrder) {
        if (rg == null || data == null) return false;
        return isBehindLocalHwpxTextBySourceDepth(rg.bounds(), pageIndex, zOrder);
    }

    private boolean isBehindLocalHwpxTextBySourceDepth(double[] rb, int pageIndex, int zOrder) {
        if (rb == null || rb.length < 4 || area(rb) <= 0.0 || data == null) return false;
        for (ResolvedTextFrame tf : data.textFrames()) {
            if (!isVisibleEditableTextFrameSource(tf)) continue;
            if (tf.pageIndex() != pageIndex) continue;
            int textZOrder = textFrameSourceZOrder(tf);
            // Resolved source-depth order is the normalized IDML stacking
            // contract: smaller values are behind, larger values are in front
            // within the same stacking context.  When editable text is in
            // front of a placed content visual, the visual must use a
            // behind-text plane so HWPX text remains visible.  This is a
            // Stage 1 source-depth decision, not a later occlusion repair.
            if (textZOrder <= zOrder) continue;
            double[] tb = tf.pageRelativeBounds() != null ? tf.pageRelativeBounds() : tf.geometricBounds();
            if (tb == null || tb.length < 4 || area(tb) <= 0.0) continue;
            if (overlapArea(rb, tb) <= 0.0) continue;
            return true;
        }
        return false;
    }

    /**
     * Placed images can be real CONTENT, but a page/spread-sized placed image
     * whose source depth is behind local editable text is a source-owned
     * backdrop. It still remains a placed backdrop, not a synthetic page
     * background, unless the source itself was declared as a page background.
     * This keeps Stage 1 as the only promotion/demotion point while avoiding a
     * second page-background execution path for ordinary placed images.
     */
    private boolean isSourceDepthPageOrSpreadBackdropImage(RenderedGroup rg, int[] sourceIds) {
        if (rg == null || data == null) return false;
        if (!isImageOrPdfExportReason(rg.reason())) return false;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return false;
        if ("inline_object".equals(safe(rg.type()))) return false;
        if (renderedVisualContainsTextPixels(rg)) return false;
        if (editableTextFrameIdsOf(rg).length > 0) return false;
        if (!hasPageLevelSourceRoots(sourceIds)) return false;
        if (!isBackgroundBoundsSanityCandidate(rg.bounds())) return false;
        int sourceZ = maxPageItemZOrder(sourceIds);
        if (sourceZ < 0) sourceZ = rg.zOrder();
        return isBehindLocalHwpxTextBySourceDepth(rg, rg.pageIndex(), sourceZ);
    }

    private boolean isSourceAuthoredPageWashBackdropImage(RenderedGroup rg, int[] sourceIds) {
        if (rg == null || data == null) return false;
        if (!isImageOrPdfExportReason(rg.reason())) return false;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return false;
        if ("inline_object".equals(safe(rg.type()))) return false;
        if (renderedVisualContainsTextPixels(rg)) return false;
        if (editableTextFrameIdsOf(rg).length > 0) return false;
        if (!hasPageLevelSourceRoots(sourceIds)) return false;
        if (!isBackgroundBoundsSanityCandidate(rg.bounds())) return false;

        for (int rootId : sourceRootObjectIds(sourceIds)) {
            ResolvedPageItem root = data.getPageItem(String.valueOf(rootId));
            if (!isPageWashRootFrame(root, rg.pageIndex())) continue;
            if (hasLowOpacityPlacedDescendant(rootId, sourceIds)) return true;
        }
        return false;
    }

    private boolean isPageWashRootFrame(ResolvedPageItem item, int pageIndex) {
        if (item == null || item.sourceHidden()) return false;
        if (item.isInline()) return false;
        if (item.parentId() != null && !item.parentId().isBlank()) return false;
        if (!isNativeSourceShapeMaterializationAllowed(item)) return false;
        if (!sourceItemHasVisibleShellMaterial(item)) return false;
        double[] pageLocal = pageLocalBoundsOf(item, pageIndex, true);
        return isMaterialPageBackdropOnPage(item, pageIndex, pageLocal);
    }

    private boolean hasLowOpacityPlacedDescendant(int rootId, int[] sourceIds) {
        if (data == null || sourceIds == null) return false;
        HashSet<Integer> sourceSet = new HashSet<>();
        for (int sourceId : sourceIds) {
            sourceSet.add(sourceId);
        }
        for (String childId : data.buildDescendantSet(String.valueOf(rootId), 8)) {
            int id = parseFlexibleId(childId);
            if (id < 0 || !sourceSet.contains(id)) continue;
            ResolvedPageItem child = data.getPageItem(childId);
            if (!isPlacedContentItem(child)) continue;
            if (child.opacity() > 0.0 && child.opacity() <= 35.0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBackgroundBoundsSanityCandidate(double[] b) {
        if (b == null || b.length < 4 || area(b) <= 0.0) return false;
        double h = Math.abs(b[2] - b[0]);
        double w = Math.abs(b[3] - b[1]);
        if (w < 180.0 || h < 90.0) return false;
        boolean touchesPageEdge = b[0] <= 1.0 || b[1] <= 1.0;
        boolean spansPageOrSpread = w >= 180.0 || h >= 180.0;
        return touchesPageEdge && spansPageOrSpread;
    }

    private int textFrameSourceZOrder(ResolvedTextFrame tf) {
        if (tf == null || tf.id() == null) return -1;
        int textId = parseFlexibleId(tf.id());
        if (textId < 0) return tf.zOrder();
        int sourceZ = maxPageItemZOrder(new int[] { textId });
        return sourceZ >= 0 ? sourceZ : tf.zOrder();
    }

    private int maxPageItemZOrder(int[] sourceIds) {
        int sourceDepth = maxPageItemSourceDepth(sourceIds);
        if (sourceDepth >= 0) return sourceDepth;

        int max = -1;
        if (sourceIds == null || data == null) return max;
        for (int sourceId : sourceIds) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item != null) max = Math.max(max, item.zOrder());
        }
        return max;
    }

    private int maxPageItemSourceDepth(int[] sourceIds) {
        if (sourceIds == null || sourceIds.length == 0 || data == null) return -1;
        HashSet<Integer> sourceSet = new HashSet<>();
        for (int sourceId : sourceIds) {
            sourceSet.add(sourceId);
        }
        int max = -1;
        List<ResolvedPageItem> pageItems = data.pageItems();
        for (ResolvedPageItem item : pageItems) {
            if (item == null || item.id() == null) continue;
            int id = parseInt(item.id(), -1);
            if (sourceSet.contains(id)) {
                max = Math.max(max, item.zOrder());
            }
        }
        return max;
    }

    private static boolean isLineLikeVisual(RenderedGroup rg) {
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double h = Math.abs(b[2] - b[0]);
        double w = Math.abs(b[3] - b[1]);
        double min = Math.min(w, h);
        double max = Math.max(w, h);
        if (min <= 2.8 && max >= 12.0) return true;
        String reason = safe(rg.reason());
        return "vector_shape".equals(reason) && min <= 3.5 && max >= 8.0;
    }

    private static boolean isMaskLikeVisual(RenderedGroup rg) {
        if (rg == null) return false;
        if (isLargeVisual(rg)) return false;
        String reason = safe(rg.reason());
        String file = safe(rg.file());
        if (reason.contains("paper") || reason.contains("mask")) return true;
        if (file.contains("mask")) return true;
        return false;
    }

    private boolean isRuleLineGroup(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        String reason = safe(rg.reason());
        if (!reason.contains("decoration") && !reason.contains("line")) return false;
        int graphicLines = 0;
        int filledShapes = 0;
        for (int id : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (item == null) continue;
            String type = safe(item.type());
            if ("GraphicLine".equals(type)) {
                graphicLines++;
                continue;
            }
            if (isSimpleDrawableShape(item)
                    && !isPaperColor(item.fillColorName())
                    && !isNoneColor(item.fillColorName())) {
                filledShapes++;
            }
        }
        return graphicLines > 0 && filledShapes == 0;
    }

    private boolean isLabelBackdropLike(RenderedGroup rg, TextAction textAction) {
        if (rg == null || isLargeVisual(rg)) return false;
        if (textAction == TextAction.OWNED_BY_PNG) return false;
        return isLabelReason(rg);
    }

    private static boolean isImageBackedContentShell(RenderedGroup rg) {
        if (rg == null) return false;
        String reason = safe(rg.reason());
        if (!reason.contains("image_group")) return false;
        String file = safe(rg.file());
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        String basename = slash >= 0 ? file.substring(slash + 1) : file;
        return basename.startsWith("img_");
    }

    private boolean isBackdropDominantImageShell(RenderedGroup rg) {
        if (rg == null || !isLargeVisual(rg)) return false;
        return whiteOpaqueScore(rg.file()) >= 0.25;
    }

    private boolean isTextFrameBackdropVector(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (!"vector_shape".equals(safe(rg.reason()))) return false;
        double[] rb = rg.bounds();
        if (rb == null || rb.length < 4) return false;
        if (!hasDrawableBackdropShapeSource(rg)) return false;

        double rArea = area(rb);
        if (rArea < 20.0) return false;
        for (ResolvedTextFrame tf : data.textFrames()) {
            if (tf == null || tf.id() == null) continue;
            if (tf.pageIndex() != rg.pageIndex()) continue;
            if (tf.sourceHidden()) continue;
            if (data.isTextOwnedByIndesignPng(tf.id())) continue;
            double[] tb = tf.pageRelativeBounds() != null ? tf.pageRelativeBounds() : tf.geometricBounds();
            if (tb == null || tb.length < 4) continue;
            double tArea = area(tb);
            if (tArea < 10.0) continue;
            if (overlapRatio(rb, tb) < 0.58) continue;
            if (rArea > tArea * 1.65 && !boundsContains(rb, tb, 2.0)) continue;
            return true;
        }
        return false;
    }

    private boolean isTextCardBackdropVector(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (!"vector_shape".equals(safe(rg.reason()))) return false;
        double[] rb = rg.bounds();
        if (rb == null || rb.length < 4) return false;
        double rArea = area(rb);
        if (rArea < 40.0) return false;
        double pageArea = pageArea(rg.pageIndex());
        if (pageArea > 0.0 && rArea / pageArea > 0.30) return false;
        if (!hasPaperFillOnlyCardShapeSource(rg)) return false;

        for (ResolvedTextFrame tf : data.textFrames()) {
            if (!isEditableHwpxTextFrameOnPage(tf, rg.pageIndex())) continue;
            double[] tb = tf.pageRelativeBounds() != null ? tf.pageRelativeBounds() : tf.geometricBounds();
            if (tb == null || tb.length < 4) continue;
            if (area(tb) < 10.0) continue;
            if (isTextCardBackdropForTextBounds(rb, tb)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPaperFillOnlyCardShapeSource(RenderedGroup rg) {
        for (int id : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (isPaperFillOnlyCardShapeItem(item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPaperFillOnlyCardShapeItem(ResolvedPageItem item) {
        if (item == null) return false;
        String type = safe(item.type());
        if (!("Rectangle".equals(type) || "Polygon".equals(type) || "Oval".equals(type))) {
            return false;
        }
        if (!isPaperColor(item.fillColorName())) return false;
        if (item.strokeWeight() > 0.01 && !isNoneColor(item.strokeColorName())) return false;
        if (item.opacity() < 0.5) return false;
        double[] b = boundsOf(item);
        if (b == null || b.length < 4) return false;
        double h = Math.abs(b[2] - b[0]);
        double w = Math.abs(b[3] - b[1]);
        return w >= 8.0 && h >= 8.0 && (w * h) >= 80.0;
    }

    private boolean isEditableHwpxTextFrameOnPage(ResolvedTextFrame tf, int pageIndex) {
        if (tf == null || tf.id() == null) return false;
        if (tf.pageIndex() != pageIndex) return false;
        if (tf.sourceHidden()) return false;
        if (data.isTextOwnedByIndesignPng(tf.id())) return false;
        String text = safe(tf.frameVisibleText()).trim();
        return !text.isEmpty();
    }

    private static boolean isTextCardBackdropForTextBounds(double[] card, double[] text) {
        double overlap = overlapRatio(card, text);
        if (overlap < 0.52 && !containsCenter(card, text) && !containsCenter(text, card)) {
            return false;
        }
        double cardH = Math.max(0.001, Math.abs(card[2] - card[0]));
        double cardW = Math.max(0.001, Math.abs(card[3] - card[1]));
        double textH = Math.max(0.001, Math.abs(text[2] - text[0]));
        double textW = Math.max(0.001, Math.abs(text[3] - text[1]));
        double hRatio = Math.min(cardH, textH) / Math.max(cardH, textH);
        double wRatio = Math.min(cardW, textW) / Math.max(cardW, textW);
        if (boundsContains(card, text, 8.0) || boundsContains(text, card, 8.0)) {
            return hRatio >= 0.35 && wRatio >= 0.35;
        }
        return overlap >= 0.70 && hRatio >= 0.55 && wRatio >= 0.55;
    }

    private boolean hasDrawableBackdropShapeSource(RenderedGroup rg) {
        for (int id : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (item == null) continue;
            String type = safe(item.type());
            if (!("Rectangle".equals(type) || "Polygon".equals(type) || "Oval".equals(type))) {
                continue;
            }
            boolean hasFill = !isNoneColor(item.fillColorName());
            boolean hasStroke = !isNoneColor(item.strokeColorName()) && item.strokeWeight() > 0.01;
            if (hasFill || hasStroke || item.cornerRadius() > 0.01) {
                return true;
            }
        }
        return false;
    }

    private static boolean isContainerBackdropLike(RenderedGroup rg) {
        if (rg == null) return false;
        if (isPlacedContentImage(rg)) return false;
        if (isLargeVisual(rg)) return true;
        String reason = safe(rg.reason());
        return reason.contains("container")
                || reason.contains("textframe_visual_shell")
                || reason.contains("visual_shell");
    }

    private boolean isPaperStrokeContainerVisual(RenderedGroup rg) {
        if (rg == null || !isLargeVisual(rg)) return false;
        ResolvedPageItem self = data.getPageItem(String.valueOf(rg.id()));
        if (selfContributesFilledVisual(self)) return false;
        int[] ids = sourceIdsOrSelf(rg);
        for (int id : ids) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (item == null) continue;
            if (!isPaperColor(item.fillColorName())) continue;
            if (item.strokeWeight() <= 0.01) continue;
            if (isNoneColor(item.strokeColorName())) continue;
            String type = safe(item.type());
            if ("Polygon".equals(type) || "Rectangle".equals(type) || "Oval".equals(type)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPaperStrokeForegroundMask(RenderedGroup rg) {
        if (!isPaperStrokeContainerVisual(rg)) return false;
        double[] rb = rg.bounds();
        if (rb == null || rb.length < 4) return false;

        for (RenderedGroup other : data.allRenderedFloatingItems()) {
            if (other == null || other.id() == rg.id()) continue;
            if (other.pageIndex() != rg.pageIndex()) continue;
            if (!looksLikeShortColoredLabel(other)) continue;
            if (partiallyClips(rb, other.bounds())) return true;
        }

        int sourceZ = maxSourceZOrder(rg);
        int sourceOrder = maxSourcePageItemOrder(rg);
        if (sourceZ < 0 && sourceOrder < 0) return false;

        List<ResolvedPageItem> pageItems = data.pageItems();
        for (int i = 0; i < pageItems.size(); i++) {
            ResolvedPageItem item = pageItems.get(i);
            if (item == null || item.id() == null) continue;
            if (item.pageIndex() != rg.pageIndex()) continue;
            if (contains(sourceIdsOrSelf(rg), parseInt(item.id(), -1))) continue;
            if (!looksLikeShortColoredLabel(item)) continue;
            boolean aboveByZ = sourceZ >= 0 && sourceZ > item.zOrder();
            boolean aboveBySourceOrder = sourceOrder >= 0 && sourceOrder > i;
            boolean clipsLabel = partiallyClips(rb, boundsOf(item));
            if (!aboveByZ && !aboveBySourceOrder && !clipsLabel) continue;
            if (intersectsOrContainsCenter(rb, boundsOf(item))) {
                return true;
            }
        }
        return false;
    }

    private boolean isPaperMaskInsideContainerBackdrop(RenderedGroup rg) {
        if (rg == null || !"vector_shape".equals(safe(rg.reason()))) return false;
        double[] rb = rg.bounds();
        if (rb == null || rb.length < 4) return false;
        double maskArea = area(rb);
        if (maskArea < 800.0) return false;
        double pageArea = pageArea(rg.pageIndex());
        if (pageArea > 0.0 && maskArea / pageArea > 0.45) return false;
        if (!isPaperMaskVisual(rg)) return false;

        for (RenderedGroup other : data.allRenderedFloatingItems()) {
            if (other == null || other.id() == rg.id()) continue;
            if (other.pageIndex() != rg.pageIndex()) continue;
            if (!isContainerBackdropCandidate(other)) continue;
            if (boundsMostlyOverlap(rb, other.bounds(), 0.70)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPlacedContentImage(RenderedGroup rg) {
        if (rg == null) return false;
        String reason = safe(rg.reason());
        if (!"image_export".equals(reason)) return false;
        if (isPageOrSpreadBackdropImage(rg)) return false;
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double area = area(b);
        return area >= 400.0;
    }

    private boolean hasPlacedContentSource(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        for (int sourceId : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (isPlacedContentItem(item)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPlacedContentSourceTree(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        for (int sourceId : sourceIdsOrSelf(rg)) {
            if (hasPlacedContentSourceTree(sourceId)) return true;
        }
        return false;
    }

    private boolean hasPlacedContentSourceTree(int sourceId) {
        if (data == null) return false;
        ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
        if (isPlacedContentItem(item)) return true;
        for (String childId : data.buildDescendantSet(String.valueOf(sourceId), 8)) {
            if (isPlacedContentItem(data.getPageItem(childId))) return true;
        }
        return false;
    }

    private static boolean isPlacedContentItem(ResolvedPageItem item) {
        if (item == null) return false;
        String type = safe(item.type());
        return "Image".equals(type) || "PDF".equals(type) || "EPS".equals(type);
    }

    private static boolean isPageOrSpreadBackdropImage(RenderedGroup rg) {
        if (rg == null) return false;
        if (!isImageOrPdfExportReason(rg.reason())) return false;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return false;
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double h = Math.abs(b[2] - b[0]);
        double w = Math.abs(b[3] - b[1]);
        if (w < 180.0 || h < 90.0) return false;
        boolean touchesBleedOrPageEdge = b[0] <= 1.0 || b[1] <= 1.0;
        boolean spansPageOrSpread = w >= 260.0 || h >= 180.0;
        boolean backgroundOrder = rg.zOrder() <= 1 || !rg.zOrderKnown();
        return touchesBleedOrPageEdge && spansPageOrSpread && backgroundOrder;
    }

    private static boolean isImageOrPdfExportReason(String reason) {
        String r = safe(reason);
        return "image_export".equals(r) || "pdf_export".equals(r);
    }

    private boolean isPaperMaskVisual(RenderedGroup rg) {
        if (whiteOpaqueScore(rg.file()) >= 0.70) return true;
        for (int id : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (item == null) continue;
            String fill = safe(item.fillColorName());
            if (isPaperColor(fill)) return true;
        }
        return false;
    }

    private boolean isContainerBackdropCandidate(RenderedGroup rg) {
        if (rg == null || !isLargeVisual(rg)) return false;
        String reason = safe(rg.reason());
        if (reason.contains("image_group")
                || reason.contains("container")
                || reason.contains("textframe_visual_shell")
                || reason.contains("visual_shell")) {
            return true;
        }
        if (isFilledContainerBoxBackdrop(rg)) return true;
        if (isOpaquePaperBackdrop(rg)) return true;
        return false;
    }

    private boolean isPaperStrokeBoxBackdrop(RenderedGroup rg) {
        if (rg == null || isLineLikeVisual(rg)) return false;
        String reason = safe(rg.reason());
        if (!("vector_shape".equals(reason)
                || reason.contains("decoration")
                || reason.contains("visual_shell"))) {
            return false;
        }
        double[] rb = rg.bounds();
        if (rb == null || rb.length < 4) return false;
        double h = Math.abs(rb[2] - rb[0]);
        double w = Math.abs(rb[3] - rb[1]);
        if (w < 18.0 || h < 18.0 || (w * h) < 800.0) return false;
        for (int id : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (isPaperStrokeBoxItem(item)) return true;
        }
        return false;
    }

    private static boolean isPaperStrokeBoxItem(ResolvedPageItem item) {
        if (item == null) return false;
        String type = safe(item.type());
        if (!("Rectangle".equals(type) || "Polygon".equals(type) || "Oval".equals(type))) {
            return false;
        }
        if (!isPaperColor(item.fillColorName())) return false;
        if (item.strokeWeight() <= 0.01) return false;
        if (isNoneColor(item.strokeColorName())) return false;
        double[] b = boundsOf(item);
        if (b == null || b.length < 4) return false;
        double h = Math.abs(b[2] - b[0]);
        double w = Math.abs(b[3] - b[1]);
        return w >= 18.0 && h >= 18.0 && (w * h) >= 800.0;
    }

    private boolean isPaperFillBackdropPatch(RenderedGroup rg) {
        if (rg == null || isLineLikeVisual(rg)) return false;
        if (!"vector_shape".equals(safe(rg.reason()))) return false;
        double[] rb = rg.bounds();
        if (rb == null || rb.length < 4) return false;
        double h = Math.abs(rb[2] - rb[0]);
        double w = Math.abs(rb[3] - rb[1]);
        if (w < 2.0 || h < 2.0 || (w * h) < 40.0) return false;
        for (int id : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (isPaperFillBackdropPatchItem(item)) return true;
        }
        return false;
    }

    private static boolean isPaperFillBackdropPatchItem(ResolvedPageItem item) {
        if (item == null) return false;
        String type = safe(item.type());
        if (!("Rectangle".equals(type) || "Polygon".equals(type) || "Oval".equals(type))) {
            return false;
        }
        if (!isPaperColor(item.fillColorName())) return false;
        double[] b = boundsOf(item);
        if (b == null || b.length < 4) return false;
        double h = Math.abs(b[2] - b[0]);
        double w = Math.abs(b[3] - b[1]);
        return w >= 2.0 && h >= 2.0 && (w * h) >= 40.0;
    }

    private boolean isFilledContainerBoxBackdrop(RenderedGroup rg) {
        if (rg == null || isLineLikeVisual(rg)) return false;
        if (!"vector_shape".equals(safe(rg.reason()))) return false;
        double[] rb = rg.bounds();
        if (rb == null || rb.length < 4) return false;
        double h = Math.abs(rb[2] - rb[0]);
        double w = Math.abs(rb[3] - rb[1]);
        if (w < 24.0 || h < 24.0 || (w * h) < 1800.0) return false;
        for (int id : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (isFilledContainerBoxItem(item)) return true;
        }
        return false;
    }

    private static boolean isFilledContainerBoxItem(ResolvedPageItem item) {
        if (item == null) return false;
        String type = safe(item.type());
        if (!("Rectangle".equals(type) || "Polygon".equals(type) || "Oval".equals(type))) {
            return false;
        }
        String fill = safe(item.fillColorName());
        if (fill.isEmpty() || isNoneColor(fill) || isPaperColor(fill)) return false;
        if (looksLikeShortColoredLabel(item)) return false;
        double[] b = boundsOf(item);
        if (b == null || b.length < 4) return false;
        double h = Math.abs(b[2] - b[0]);
        double w = Math.abs(b[3] - b[1]);
        if (w < 24.0 || h < 24.0 || (w * h) < 1800.0) return false;
        return true;
    }

    private boolean looksLikeShortColoredLabel(RenderedGroup rg) {
        if (rg == null) return false;
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double h = Math.abs(b[2] - b[0]);
        double w = Math.abs(b[3] - b[1]);
        if (w < 8.0 || h < 3.0 || h > 32.0) return false;
        if (w / Math.max(1.0, h) < 1.2) return false;
        if (!"vector_shape".equals(safe(rg.reason()))) return false;
        for (int id : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (looksLikeShortColoredLabel(item)) return true;
        }
        return false;
    }

    private int maxSourceZOrder(RenderedGroup rg) {
        int max = -1;
        for (int id : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (item != null) max = Math.max(max, item.zOrder());
        }
        return max;
    }

    private int maxSourcePageItemOrder(RenderedGroup rg) {
        int max = -1;
        int[] ids = sourceIdsOrSelf(rg);
        List<ResolvedPageItem> pageItems = data.pageItems();
        for (int i = 0; i < pageItems.size(); i++) {
            ResolvedPageItem item = pageItems.get(i);
            if (item == null || item.id() == null) continue;
            if (contains(ids, parseInt(item.id(), -1))) {
                max = Math.max(max, i);
            }
        }
        return max;
    }

    private static boolean looksLikeShortColoredLabel(ResolvedPageItem item) {
        if (item == null) return false;
        double[] b = boundsOf(item);
        if (b == null || b.length < 4) return false;
        double h = Math.abs(b[2] - b[0]);
        double w = Math.abs(b[3] - b[1]);
        if (w < 8.0 || h < 3.0 || h > 32.0) return false;
        if (w / Math.max(1.0, h) < 1.2) return false;
        String fill = safe(item.fillColorName());
        if (fill.isEmpty() || isNoneColor(fill) || isPaperColor(fill)) return false;
        String type = safe(item.type());
        return "Rectangle".equals(type) || "Polygon".equals(type) || "Oval".equals(type);
    }

    private static double[] boundsOf(ResolvedPageItem item) {
        if (item == null) return null;
        return item.pageRelativeBounds() != null ? item.pageRelativeBounds() : item.geometricBounds();
    }

    private static boolean intersectsOrContainsCenter(double[] container, double[] item) {
        if (container == null || item == null || container.length < 4 || item.length < 4) return false;
        double overlapTop = Math.max(container[0], item[0]);
        double overlapLeft = Math.max(container[1], item[1]);
        double overlapBottom = Math.min(container[2], item[2]);
        double overlapRight = Math.min(container[3], item[3]);
        if (overlapRight > overlapLeft && overlapBottom > overlapTop) {
            return true;
        }
        double centerY = (item[0] + item[2]) / 2.0;
        double centerX = (item[1] + item[3]) / 2.0;
        return centerY >= container[0] && centerY <= container[2]
                && centerX >= container[1] && centerX <= container[3];
    }

    private static boolean containsCenter(double[] container, double[] item) {
        if (container == null || item == null || container.length < 4 || item.length < 4) return false;
        double centerY = (item[0] + item[2]) / 2.0;
        double centerX = (item[1] + item[3]) / 2.0;
        return centerY >= container[0] && centerY <= container[2]
                && centerX >= container[1] && centerX <= container[3];
    }

    private static boolean partiallyClips(double[] container, double[] item) {
        if (container == null || item == null || container.length < 4 || item.length < 4) return false;
        double overlapTop = Math.max(container[0], item[0]);
        double overlapLeft = Math.max(container[1], item[1]);
        double overlapBottom = Math.min(container[2], item[2]);
        double overlapRight = Math.min(container[3], item[3]);
        if (!(overlapRight > overlapLeft && overlapBottom > overlapTop)) return false;
        return item[0] < container[0] || item[1] < container[1]
                || item[2] > container[2] || item[3] > container[3];
    }

    private boolean isOpaquePaperBackdrop(RenderedGroup rg) {
        if (rg == null || !isLargeVisual(rg)) return false;
        return whiteOpaqueScore(rg.file()) >= 0.25;
    }

    private static boolean isLabelReason(RenderedGroup rg) {
        String reason = safe(rg != null ? rg.reason() : null);
        return reason.contains("label") || reason.contains("visual_label");
    }

    private static boolean isLargeVisual(RenderedGroup rg) {
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double h = Math.abs(b[2] - b[0]);
        double w = Math.abs(b[3] - b[1]);
        return w >= 120.0 || h >= 120.0 || (w * h) >= 6000.0;
    }

    private double pageArea(int pageIndex) {
        double[] b = pageBounds(pageIndex);
        return area(b);
    }

    private static boolean isPaperColor(String colorName) {
        return "Paper".equals(colorName) || "White".equals(colorName);
    }

    private static boolean isNoneColor(String colorName) {
        if (colorName == null || colorName.isBlank()) return true;
        return "None".equals(colorName) || "[None]".equals(colorName);
    }

    private static String textFrameReason(ResolvedTextFrame tf, TextAction action) {
        if (!tf.visible() || tf.hiddenByParent()) return "hidden_by_source_visibility";
        if (tf.onHiddenLayer()) return "hidden_layer";
        if (tf.nonprinting()) return "nonprinting";
        if (action == TextAction.OWNED_BY_PNG) return "text_owned_by_indesign_png";
        return "editable_text_frame";
    }

    private static String pageDomKey(ObjectPlan plan) {
        return plan.pageIndex + ":" + plan.domId;
    }

    private static String pageSourceKey(int pageIndex, int sourceId) {
        return pageIndex + ":" + sourceId;
    }

    private static String renderedIdentityKey(ObjectPlan plan) {
        String artifactKey = renderedArtifactIdentity(plan);
        if (artifactKey != null) {
            return plan.pageIndex + ":" + plan.domId + ":" + artifactKey;
        }
        return plan.pageIndex + ":" + plan.domId;
    }

    private static String renderedArtifactIdentity(ObjectPlan plan) {
        if (plan == null) return null;
        String candidateId = safe(plan.candidateId);
        if (!candidateId.isEmpty()
                && (safe(plan.kind).startsWith("planner_declared_rendered:")
                || "planner_declared_object_plan".equals(safe(plan.reason)))) {
            return "candidate:" + candidateId;
        }
        String file = safe(plan.file);
        if (!file.isEmpty()) {
            return "file:" + file;
        }
        if (plan.renderId != null) {
            return "render:" + plan.renderId;
        }
        return null;
    }

    private static int renderedChannelPriority(ObjectPlan plan) {
        if (plan.kind.startsWith("rendered_floating_item:")) return 0;
        if (plan.kind.startsWith("rendered_graphic_frame:")) return 1;
        if (plan.kind.startsWith("rendered_image_frame:")) return 2;
        if (plan.kind.startsWith("rendered_pdf_frame:")) return 3;
        return 10;
    }

    private static boolean isVisibleRenderedVisual(ObjectPlan plan) {
        return plan != null
                && plan.hasVisibleVisual()
                && plan.renderId != null
                && !"text_frame".equals(plan.kind);
    }

    private static boolean isRenderedVisualPlan(ObjectPlan plan) {
        return plan != null
                && plan.renderId != null
                && !"text_frame".equals(plan.kind);
    }

    private static boolean isLabelBackdropGroupPlan(ObjectPlan plan) {
        return plan != null && "label_backdrop_group".equals(plan.reason);
    }

    private static boolean isDroppableParentGroup(ObjectPlan plan) {
        if (!isVisibleRenderedVisual(plan)) return false;
        if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.placement == Placement.INLINE) return false;
        return plan.sourceObjectIds.length > 1;
    }

    private static boolean isStrictChildPlan(ObjectPlan parent, ObjectPlan child) {
        if (parent.domId == child.domId) return false;
        if (containsAll(parent.sourceObjectIds, child.sourceObjectIds)) {
            return parent.sourceObjectIds.length > child.sourceObjectIds.length
                    || sameSourceNestedFootprint(parent, child);
        }
        return contains(parent.sourceObjectIds, child.domId);
    }

    private static boolean sameSourceNestedFootprint(ObjectPlan parent, ObjectPlan child) {
        if (parent == null || child == null) return false;
        if (!containsAll(parent.sourceObjectIds, child.sourceObjectIds)) return false;
        double parentArea = area(parent.bounds);
        double childArea = area(child.bounds);
        if (parentArea <= 0.0 || childArea <= 0.0) return false;
        if (parentArea <= childArea * 1.05) return false;
        return boundsMostlyOverlap(parent.bounds, child.bounds, 0.35)
                || boundsContains(parent.bounds, child.bounds, 8.0);
    }

    private boolean shouldPreferCompositeParent(ObjectPlan parent, ObjectPlan child) {
        if (parent == null || child == null) return false;
        if (!boundsMostlyOverlap(parent.bounds, child.bounds, 0.90)) return false;
        if (parent.sourceObjectIds.length <= child.sourceObjectIds.length) return false;
        double parentScore = visualInkScore(parent);
        double childScore = visualInkScore(child);
        return parentScore > childScore + 0.006;
    }

    private static boolean isBackgroundParentWithContentChild(ObjectPlan parent, ObjectPlan child) {
        if (parent == null || child == null) return false;
        return parent.visualPolicyLayer() == PolicyLayer.BACKGROUND
                && child.visualPolicyLayer() == PolicyLayer.CONTENT;
    }

    private static int[] withoutChildVisualSources(ObjectPlan parent, ObjectPlan child) {
        if (parent == null || parent.sourceObjectIds == null) return new int[0];
        LinkedHashSet<Integer> childSources = new LinkedHashSet<>();
        if (child != null) {
            childSources.add(child.domId);
            if (child.sourceObjectIds != null) {
                for (int sourceId : child.sourceObjectIds) {
                    childSources.add(sourceId);
                }
            }
        }
        if (childSources.isEmpty()) return parent.sourceObjectIds;
        List<Integer> retained = new ArrayList<>();
        for (int sourceId : parent.sourceObjectIds) {
            if (sourceId != parent.domId && childSources.contains(sourceId)) {
                continue;
            }
            retained.add(sourceId);
        }
        return retained.isEmpty() ? parent.sourceObjectIds : toIntArray(retained);
    }

    private boolean parentHasPaperBackdrop(ObjectPlan parent) {
        if (parent == null || parent.file == null || parent.file.isBlank()) return false;
        return whiteOpaqueScore(parent.file) >= 0.45;
    }

    private boolean parentHasVisiblePixelsOutsideChildren(ObjectPlan parent, List<ObjectPlan> children) {
        if (parent == null || children == null || children.isEmpty()) return false;
        if (parent.file == null || parent.file.isBlank()) return false;
        if (parent.bounds == null || parent.bounds.length < 4) return false;
        File imageFile = new File(parent.file);
        if (!imageFile.isAbsolute()) {
            if (data == null || data.basePath() == null) return false;
            imageFile = new File(data.basePath(), parent.file);
        }
        if (!imageFile.exists()) return false;
        try {
            BufferedImage image = ImageIO.read(imageFile);
            if (image == null) return false;
            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= 0) return false;
            double parentTop = parent.bounds[0];
            double parentLeft = parent.bounds[1];
            double parentBottom = parent.bounds[2];
            double parentRight = parent.bounds[3];
            double parentW = Math.max(0.0001, parentRight - parentLeft);
            double parentH = Math.max(0.0001, parentBottom - parentTop);
            int[][] childRects = childPixelRects(parent, children, width, height, parentTop, parentLeft, parentW, parentH);
            int step = Math.max(1, (int) Math.sqrt((width * (double) height) / 160_000.0));
            int outsideSamples = 0;
            int outsideVisible = 0;
            for (int y = 0; y < height; y += step) {
                for (int x = 0; x < width; x += step) {
                    if (insideAnyRect(x, y, childRects)) continue;
                    outsideSamples++;
                    if (isNonWhiteVisiblePixel(image.getRGB(x, y))) {
                        outsideVisible++;
                    }
                }
            }
            if (outsideSamples == 0) return false;
            double ratio = outsideVisible / (double) outsideSamples;
            return outsideVisible >= 8 && ratio >= 0.0012;
        } catch (IOException e) {
            return false;
        }
    }

    private int[][] childPixelRects(ObjectPlan parent, List<ObjectPlan> children,
                                           int imageW, int imageH,
                                           double parentTop, double parentLeft,
                                           double parentW, double parentH) {
        List<int[]> rects = new ArrayList<>();
        for (ObjectPlan child : children) {
            if (child == null || child.bounds == null || child.bounds.length < 4) continue;
            // 자식이 선언한 bounds가 아니라 PNG에서 실제로 칠해진 알파 영역만 커버로 본다.
            // (예: 스프레드 전체폭 bounds를 가졌지만 왼쪽 카드만 그리는 셸이 부모의 우측
            //  카드 픽셀까지 "커버"한 것으로 오판해 부모 합성본을 잘못 드롭하는 것을 방지.)
            double[] painted = childPaintedBoundsMm(child);
            double childTop = child.bounds[0], childLeft = child.bounds[1];
            double childBottom = child.bounds[2], childRight = child.bounds[3];
            if (painted != null) {
                childTop = Math.max(childTop, painted[0]);
                childLeft = Math.max(childLeft, painted[1]);
                childBottom = Math.min(childBottom, painted[2]);
                childRight = Math.min(childRight, painted[3]);
            }
            double top = Math.max(parentTop, childTop);
            double left = Math.max(parentLeft, childLeft);
            double bottom = Math.min(parentTop + parentH, childBottom);
            double right = Math.min(parentLeft + parentW, childRight);
            if (bottom <= top || right <= left) continue;
            int x0 = clamp((int) Math.floor((left - parentLeft) / parentW * imageW), 0, imageW);
            int x1 = clamp((int) Math.ceil((right - parentLeft) / parentW * imageW), 0, imageW);
            int y0 = clamp((int) Math.floor((top - parentTop) / parentH * imageH), 0, imageH);
            int y1 = clamp((int) Math.ceil((bottom - parentTop) / parentH * imageH), 0, imageH);
            if (x1 <= x0 || y1 <= y0) continue;
            rects.add(new int[] { x0, y0, x1, y1 });
        }
        return rects.toArray(new int[0][]);
    }

    /**
     * 자식 PNG에서 실제로 칠해진(불투명) 픽셀의 경계를 부모와 동일한 문서 좌표(mm)로 환산.
     * 반환: [top, left, bottom, right] (mm), 계산 불가 시 null.
     */
    private double[] childPaintedBoundsMm(ObjectPlan child) {
        if (child == null || child.file == null || child.file.isBlank()) return null;
        if (child.bounds == null || child.bounds.length < 4) return null;
        File imageFile = new File(child.file);
        if (!imageFile.isAbsolute()) {
            if (data == null || data.basePath() == null) return null;
            imageFile = new File(data.basePath(), child.file);
        }
        if (!imageFile.exists()) return null;
        try {
            BufferedImage image = ImageIO.read(imageFile);
            if (image == null) return null;
            int w = image.getWidth(), h = image.getHeight();
            if (w <= 0 || h <= 0) return null;
            int minX = w, minY = h, maxX = -1, maxY = -1;
            int step = Math.max(1, (int) Math.sqrt((w * (double) h) / 160_000.0));
            for (int y = 0; y < h; y += step) {
                for (int x = 0; x < w; x += step) {
                    if (isNonWhiteVisiblePixel(image.getRGB(x, y))) {
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
            }
            if (maxX < 0 || maxY < 0) return null;
            // step 샘플링 보정: 경계를 한 step 만큼 확장
            minX = Math.max(0, minX - step);
            minY = Math.max(0, minY - step);
            maxX = Math.min(w - 1, maxX + step);
            maxY = Math.min(h - 1, maxY + step);
            double childTop = child.bounds[0], childLeft = child.bounds[1];
            double childH = Math.max(0.0001, child.bounds[2] - childTop);
            double childW = Math.max(0.0001, child.bounds[3] - childLeft);
            double top = childTop + (minY / (double) h) * childH;
            double bottom = childTop + ((maxY + 1) / (double) h) * childH;
            double left = childLeft + (minX / (double) w) * childW;
            double right = childLeft + ((maxX + 1) / (double) w) * childW;
            return new double[] { top, left, bottom, right };
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean insideAnyRect(int x, int y, int[][] rects) {
        if (rects == null) return false;
        for (int[] rect : rects) {
            if (rect == null || rect.length < 4) continue;
            if (x >= rect[0] && x < rect[2] && y >= rect[1] && y < rect[3]) {
                return true;
            }
        }
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean isNonWhiteVisiblePixel(int argb) {
        int alpha = (argb >>> 24) & 0xff;
        if (alpha <= 32) return false;
        int r = (argb >>> 16) & 0xff;
        int g = (argb >>> 8) & 0xff;
        int b = argb & 0xff;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        double brightness = (r + g + b) / 3.0;
        int saturation = max - min;
        return !(brightness >= 245.0 && saturation <= 12);
    }

    private boolean isWideShortMultiTextShell(ObjectPlan plan) {
        if (plan == null || plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (!"mixed_group_text_hidden".equals(plan.reason)) return false;
        if (textFrameIdsForPlan(plan).length < 2) return false;
        if (plan.bounds == null || plan.bounds.length < 4) return false;
        double h = Math.abs(plan.bounds[2] - plan.bounds[0]);
        double w = Math.abs(plan.bounds[3] - plan.bounds[1]);
        return h >= 6.0
                && h <= 28.0
                && w >= 35.0
                && w / Math.max(1.0, h) >= 2.6;
    }

    private static boolean isComparableOrSmallerShell(ObjectPlan child, ObjectPlan cluster) {
        double childArea = area(child != null ? child.bounds : null);
        double clusterArea = area(cluster != null ? cluster.bounds : null);
        if (childArea <= 0.0 || clusterArea <= 0.0) return false;
        return childArea <= clusterArea * 1.35;
    }

    private static boolean isImageExportVisual(ObjectPlan plan) {
        return isVisibleRenderedVisual(plan)
                && plan.placement == Placement.FLOATING
                && "image_export".equals(plan.reason)
                && plan.bounds != null
                && plan.bounds.length >= 4;
    }

    private boolean isBackdropAndContentImagePair(ObjectPlan a, ObjectPlan b) {
        boolean aBackdrop = isFlatImageExportBackdrop(a);
        boolean bBackdrop = isFlatImageExportBackdrop(b);
        if (aBackdrop == bBackdrop) return false;
        ObjectPlan backdrop = aBackdrop ? a : b;
        ObjectPlan content = aBackdrop ? b : a;
        double backdropScore = visualInkScore(backdrop);
        double contentScore = visualInkScore(content);
        return contentScore > backdropScore + 0.035;
    }

    private boolean isFlatImageExportBackdrop(ObjectPlan plan) {
        if (!isImageExportVisual(plan)) return false;
        if (area(plan.bounds) < 1200.0) return false;
        return visualInkScore(plan) <= 0.035;
    }

    private boolean isLargeLayeredImageExportPair(ObjectPlan a, ObjectPlan b) {
        if (!isImageExportVisual(a) || !isImageExportVisual(b)) return false;
        if (a.pageIndex != b.pageIndex) return false;
        if (a.domId == b.domId) return false;
        if (!boundsMostlyOverlap(a.bounds, b.bounds, 0.55)) return false;
        double aArea = area(a.bounds);
        double bArea = area(b.bounds);
        if (Math.min(aArea, bArea) < 6000.0) return false;
        double pageArea = pageArea(a.pageIndex);
        if (pageArea > 0.0) {
            double aRatio = aArea / pageArea;
            double bRatio = bArea / pageArea;
            if (Math.min(aRatio, bRatio) < 0.12 && Math.min(aArea, bArea) < 10000.0) return false;
            if (Math.max(aRatio, bRatio) < 0.18 && Math.max(aArea, bArea) < 14000.0) return false;
        }

        double areaRatio = Math.min(aArea, bArea) / Math.max(1.0, Math.max(aArea, bArea));
        boolean nearSameGeometry = areaRatio >= 0.94 && boundsMostlyOverlap(a.bounds, b.bounds, 0.96);
        boolean nearSamePixels = Math.abs(visualInkScore(a) - visualInkScore(b)) < 0.04
                && Math.abs(whiteOpaqueScore(a.file) - whiteOpaqueScore(b.file)) < 0.04;
        if (sharesAnySource(a, b) && nearSameGeometry) return false;
        return !(nearSameGeometry && nearSamePixels);
    }

    private boolean isFlatImageExportBackdrop(RenderedGroup rg) {
        if (rg == null) return false;
        if (!"image_export".equals(safe(rg.reason()))) return false;
        if (!isLargeVisual(rg)) return false;
        String file = rg.file();
        if (file == null || file.isBlank()) return false;
        Double cached = imageInkScoreCache.get(file);
        double score = cached != null ? cached : readVisualInkScore(file);
        imageInkScoreCache.put(file, score);
        return score <= 0.035;
    }

    private boolean isLikelyDuplicateImageExport(ObjectPlan a, ObjectPlan b) {
        if (sharesAnySource(a, b)) return true;
        double aArea = area(a != null ? a.bounds : null);
        double bArea = area(b != null ? b.bounds : null);
        if (aArea <= 0.0 || bArea <= 0.0) return false;
        double areaRatio = Math.min(aArea, bArea) / Math.max(aArea, bArea);
        if (areaRatio < 0.70) return false;
        if (!boundsMostlyOverlap(a.bounds, b.bounds, 0.86)) return false;
        boolean nearSameGeometry = areaRatio >= 0.94 && boundsMostlyOverlap(a.bounds, b.bounds, 0.96);
        boolean nearSamePixels = Math.abs(visualInkScore(a) - visualInkScore(b)) < 0.04
                && Math.abs(whiteOpaqueScore(a.file) - whiteOpaqueScore(b.file)) < 0.04;
        return nearSameGeometry && nearSamePixels;
    }

    private static double overlapArea(double[] a, double[] b) {
        if (a == null || b == null || a.length < 4 || b.length < 4) return 0.0;
        double left = Math.max(a[1], b[1]);
        double top = Math.max(a[0], b[0]);
        double right = Math.min(a[3], b[3]);
        double bottom = Math.min(a[2], b[2]);
        double w = right - left;
        double h = bottom - top;
        return w > 0 && h > 0 ? w * h : 0.0;
    }

    private static boolean boundsMostlyOverlap(double[] a, double[] b, double threshold) {
        if (a == null || b == null || a.length < 4 || b.length < 4) return false;
        double overlapTop = Math.max(a[0], b[0]);
        double overlapLeft = Math.max(a[1], b[1]);
        double overlapBottom = Math.min(a[2], b[2]);
        double overlapRight = Math.min(a[3], b[3]);
        double overlapW = Math.max(0.0, overlapRight - overlapLeft);
        double overlapH = Math.max(0.0, overlapBottom - overlapTop);
        double overlapArea = overlapW * overlapH;
        double aArea = area(a);
        double bArea = area(b);
        double minArea = Math.min(aArea, bArea);
        return minArea > 0.0 && overlapArea / minArea >= threshold;
    }

    private static boolean boundsContains(double[] outer, double[] inner, double tolerance) {
        if (outer == null || inner == null || outer.length < 4 || inner.length < 4) return false;
        return inner[0] >= outer[0] - tolerance
                && inner[1] >= outer[1] - tolerance
                && inner[2] <= outer[2] + tolerance
                && inner[3] <= outer[3] + tolerance;
    }

    private static boolean sharesAnySource(ObjectPlan a, ObjectPlan b) {
        if (a == null || b == null || a.sourceObjectIds == null || b.sourceObjectIds == null) return false;
        for (int ai : a.sourceObjectIds) {
            for (int bi : b.sourceObjectIds) {
                if (ai == bi) return true;
            }
        }
        return false;
    }

    private static boolean sourceSetContainsAll(int[] ownerSources, int[] childSources) {
        if (ownerSources == null || childSources == null || childSources.length == 0) return false;
        for (int childSource : childSources) {
            boolean found = false;
            for (int ownerSource : ownerSources) {
                if (ownerSource == childSource) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static boolean isVisualBackdropCluster(ObjectPlan plan) {
        return plan != null && "visual_backdrop_cluster".equals(plan.reason);
    }

    private boolean isPaperLikeContainerFace(ObjectPlan plan) {
        if (plan == null || plan.bounds == null || plan.bounds.length < 4) return false;
        if (hasPlacedContentSourceTree(plan)) return false;
        if (isLineLikePlan(plan)) return false;
        double h = Math.abs(plan.bounds[2] - plan.bounds[0]);
        double w = Math.abs(plan.bounds[3] - plan.bounds[1]);
        if (w < 18.0 || h < 18.0 || (w * h) < 800.0) return false;
        for (int sourceId : plan.sourceObjectIds) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (isPaperStrokeBoxItem(item) || isPaperFillBackdropPatchItem(item)) {
                return true;
            }
        }
        return whiteOpaqueScore(plan.file) >= 0.45;
    }

    private static boolean isLineLikePlan(ObjectPlan plan) {
        if (plan == null || plan.bounds == null || plan.bounds.length < 4) return false;
        double h = Math.abs(plan.bounds[2] - plan.bounds[0]);
        double w = Math.abs(plan.bounds[3] - plan.bounds[1]);
        double min = Math.min(w, h);
        double max = Math.max(w, h);
        return min <= 3.5 && max >= 8.0;
    }

    private static double area(double[] b) {
        if (b == null || b.length < 4) return 0.0;
        return Math.max(0.0, b[3] - b[1]) * Math.max(0.0, b[2] - b[0]);
    }

    private static double overlapRatio(double[] a, double[] b) {
        if (a == null || b == null || a.length < 4 || b.length < 4) return 0.0;
        double y1 = Math.max(a[0], b[0]);
        double x1 = Math.max(a[1], b[1]);
        double y2 = Math.min(a[2], b[2]);
        double x2 = Math.min(a[3], b[3]);
        if (y2 <= y1 || x2 <= x1) return 0.0;
        double overlap = (y2 - y1) * (x2 - x1);
        double denom = Math.min(area(a), area(b));
        return denom > 0.0 ? overlap / denom : 0.0;
    }

    private double visualInkScore(ObjectPlan plan) {
        if (plan == null || plan.file == null || plan.file.isBlank()) return 0.0;
        String key = plan.file;
        Double cached = imageInkScoreCache.get(key);
        if (cached != null) return cached;
        double score = readVisualInkScore(plan.file);
        imageInkScoreCache.put(key, score);
        return score;
    }

    private double whiteOpaqueScore(String file) {
        if (file == null || file.isBlank()) return 0.0;
        Double cached = imageWhiteOpaqueScoreCache.get(file);
        if (cached != null) return cached;
        double score = readWhiteOpaqueScore(file);
        imageWhiteOpaqueScoreCache.put(file, score);
        return score;
    }

    private double readWhiteOpaqueScore(String file) {
        File imageFile = resolveImageFile(file);
        if (!imageFile.exists()) return 0.0;
        try {
            BufferedImage image = ImageIO.read(imageFile);
            if (image == null) return 0.0;
            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= 0) return 0.0;
            int step = Math.max(1, (int) Math.sqrt((width * (double) height) / 120_000.0));
            int samples = 0;
            int white = 0;
            for (int y = 0; y < height; y += step) {
                for (int x = 0; x < width; x += step) {
                    int argb = image.getRGB(x, y);
                    int alpha = (argb >>> 24) & 0xff;
                    if (alpha <= 240) continue;
                    int r = (argb >>> 16) & 0xff;
                    int g = (argb >>> 8) & 0xff;
                    int b = argb & 0xff;
                    samples++;
                    if (r >= 245 && g >= 245 && b >= 245) {
                        white++;
                    }
                }
            }
            return samples > 0 ? white / (double) samples : 0.0;
        } catch (IOException e) {
            return 0.0;
        }
    }

    private double readVisualInkScore(String file) {
        File imageFile = resolveImageFile(file);
        if (!imageFile.exists()) return 0.0;
        try {
            BufferedImage image = ImageIO.read(imageFile);
            if (image == null) return 0.0;
            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= 0) return 0.0;
            int step = Math.max(1, (int) Math.sqrt((width * (double) height) / 120_000.0));
            int samples = 0;
            int ink = 0;
            for (int y = 0; y < height; y += step) {
                for (int x = 0; x < width; x += step) {
                    int argb = image.getRGB(x, y);
                    int alpha = (argb >>> 24) & 0xff;
                    if (alpha <= 32) continue;
                    int r = (argb >>> 16) & 0xff;
                    int g = (argb >>> 8) & 0xff;
                    int b = argb & 0xff;
                    int max = Math.max(r, Math.max(g, b));
                    int min = Math.min(r, Math.min(g, b));
                    int saturation = max - min;
                    double brightness = (r + g + b) / 3.0;
                    samples++;
                    if (brightness < 130.0
                            || (brightness < 190.0 && saturation > 25)
                            || (brightness < 235.0 && saturation > 85)) {
                        ink++;
                    }
                }
            }
            return samples > 0 ? ink / (double) samples : 0.0;
        } catch (IOException e) {
            return 0.0;
        }
    }

    private File resolveImageFile(String file) {
        File imageFile = new File(file);
        if (imageFile.isAbsolute()) return imageFile;
        if (data == null || data.basePath() == null) return imageFile;
        return new File(data.basePath(), file);
    }

    private boolean coversAllParentSources(ObjectPlan parent, LinkedHashSet<Integer> childSources) {
        if (parent.sourceObjectIds.length == 0) return false;
        for (int sourceId : parent.sourceObjectIds) {
            if (sourceId == parent.domId && !parentSelfContributesVisibleVisual(parent)) continue;
            if (!childSources.contains(sourceId)) return false;
        }
        return true;
    }

    private boolean parentSelfContributesVisibleVisual(ObjectPlan parent) {
        if (parent == null) return false;
        ResolvedPageItem item = data.getPageItem(String.valueOf(parent.domId));
        return selfContributesFilledVisual(item);
    }

    private static boolean selfContributesFilledVisual(ResolvedPageItem item) {
        if (item == null) return false;
        if (item.opacity() <= 0.01) return false;
        String type = safe(item.type());
        if (!("Polygon".equals(type) || "Rectangle".equals(type) || "Oval".equals(type))) {
            return false;
        }
        String fill = safe(item.fillColorName());
        if (isNoneColor(fill) || isPaperColor(fill)) return false;
        return true;
    }

    private static boolean containsAll(int[] values, int[] candidates) {
        if (values == null || candidates == null) return false;
        for (int candidate : candidates) {
            if (!contains(values, candidate)) return false;
        }
        return true;
    }

    private static boolean contains(int[] values, int candidate) {
        if (values == null) return false;
        for (int value : values) {
            if (value == candidate) return true;
        }
        return false;
    }

    private static boolean containsAny(int[] values, int[] candidates) {
        if (values == null || candidates == null) return false;
        for (int candidate : candidates) {
            if (contains(values, candidate)) return true;
        }
        return false;
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < values.size(); i++) out[i] = values.get(i);
        return out;
    }

    private static int[] toIntArray(LinkedHashSet<Integer> values) {
        int[] out = new int[values.size()];
        int i = 0;
        for (Integer value : values) {
            out[i++] = value != null ? value : -1;
        }
        return out;
    }

    private static int[] withoutSources(int[] sourceIds, LinkedHashSet<Integer> removed) {
        if (sourceIds == null || sourceIds.length == 0 || removed == null || removed.isEmpty()) {
            return sourceIds != null ? sourceIds : new int[0];
        }
        List<Integer> retained = new ArrayList<>();
        for (int sourceId : sourceIds) {
            if (removed.contains(sourceId)) continue;
            retained.add(sourceId);
        }
        if (retained.isEmpty()) {
            return sourceIds;
        }
        return toIntArray(retained);
    }

    private static int[] withoutSourcesAllowEmpty(int[] sourceIds, LinkedHashSet<Integer> removed) {
        if (sourceIds == null || sourceIds.length == 0 || removed == null || removed.isEmpty()) {
            return sourceIds != null ? sourceIds : new int[0];
        }
        List<Integer> retained = new ArrayList<>();
        for (int sourceId : sourceIds) {
            if (removed.contains(sourceId)) continue;
            retained.add(sourceId);
        }
        return toIntArray(retained);
    }

    private static int[] visualSourceIds(ObjectPlan plan) {
        if (plan == null) return new int[0];
        if (plan.visualSourceObjectIds != null && plan.visualSourceObjectIds.length > 0) {
            return plan.visualSourceObjectIds;
        }
        return plan.sourceObjectIds != null ? plan.sourceObjectIds : new int[0];
    }

    private void normalizeVisibleVisualSourcesToPlanPage() {
        if (data == null) return;
        int suppressed = 0;
        for (ObjectPlan plan : plans) {
            if (plan == null || !plan.hasVisibleVisual()) continue;
            if (plan.pageIndex < 0) continue;

            int[] sourceIds = retainPlanPageSourceIds(plan.sourceObjectIds, plan);
            int[] visualIds = retainPlanPageSourceIdsAllowEmpty(plan.visualSourceObjectIds, plan);
            int[] exportIds = retainPlanPageSourceIdsAllowEmpty(plan.exportSourceObjectIds, plan);
            int[] descendantIds = retainPlanPageSourceIdsAllowEmpty(plan.descendantVisualObjectIds, plan);

            boolean sourceChanged = !sourceArraysEquivalentEmpty(sourceIds, plan.sourceObjectIds);
            boolean visualChanged = !sourceArraysEquivalentEmpty(visualIds, plan.visualSourceObjectIds);
            boolean exportChanged = !sourceArraysEquivalentEmpty(exportIds, plan.exportSourceObjectIds);
            boolean descendantChanged = !sourceArraysEquivalentEmpty(descendantIds, plan.descendantVisualObjectIds);
            if (!sourceChanged && !visualChanged && !exportChanged && !descendantChanged) {
                continue;
            }
            boolean hasExplicitVisibleSourceContract = hasAny(plan.visualSourceObjectIds)
                    || hasAny(plan.exportSourceObjectIds)
                    || hasAny(plan.descendantVisualObjectIds);
            if (sourceChanged
                    && !visualChanged
                    && !exportChanged
                    && !descendantChanged
                    && hasExplicitVisibleSourceContract) {
                continue;
            }
            warn("VISIBLE_VISUAL_SOURCE_PAGE_REWRITE_SUPPRESSED",
                    "plan=" + planRef(plan)
                            + " sourceObjectIds=" + ObjectPlan.intArrayJson(plan.sourceObjectIds)
                            + " retainedSourceObjectIds=" + ObjectPlan.intArrayJson(sourceIds)
                            + " visualSourceObjectIds=" + ObjectPlan.intArrayJson(plan.visualSourceObjectIds)
                            + " retainedVisualSourceObjectIds=" + ObjectPlan.intArrayJson(visualIds)
                            + " exportSourceObjectIds=" + ObjectPlan.intArrayJson(plan.exportSourceObjectIds)
                            + " retainedExportSourceObjectIds=" + ObjectPlan.intArrayJson(exportIds)
                            + " descendantVisualObjectIds=" + ObjectPlan.intArrayJson(plan.descendantVisualObjectIds)
                            + " retainedDescendantVisualObjectIds=" + ObjectPlan.intArrayJson(descendantIds));
            suppressed++;
        }
        ConversionTiming.metric("stage1.ownershipPlanner.normalizeVisibleVisualSourcesToPlanPage.suppressed",
                suppressed);
    }

    private int[] retainPlanPageSourceIds(int[] ids, ObjectPlan plan) {
        int[] retained = retainPlanPageSourceIdsAllowEmpty(ids, plan);
        if (retained.length == 0 && ids != null && ids.length > 0) return ids;
        return retained;
    }

    private int[] retainPlanPageSourceIdsAllowEmpty(int[] ids, ObjectPlan plan) {
        if (ids == null || ids.length == 0 || data == null) {
            return ids != null ? ids : new int[0];
        }
        int pageIndex = plan != null ? plan.pageIndex : -1;
        LinkedHashSet<Integer> retained = new LinkedHashSet<>();
        for (int id : ids) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (item == null || item.pageIndex() < 0 || item.pageIndex() == pageIndex
                    || sourceItemIntersectsPlanPage(item, plan)) {
                retained.add(id);
            }
        }
        return toIntArray(retained);
    }

    private static boolean sourceArraysEquivalentEmpty(int[] a, int[] b) {
        boolean aEmpty = a == null || a.length == 0;
        boolean bEmpty = b == null || b.length == 0;
        if (aEmpty && bEmpty) return true;
        return Arrays.equals(a, b);
    }

    private static boolean hasAny(int[] values) {
        return values != null && values.length > 0;
    }

    private boolean sourceItemIntersectsPlanPage(ResolvedPageItem item, ObjectPlan plan) {
        if (item == null || plan == null || plan.pageIndex < 0) return false;
        double[] b = boundsOf(item);
        double[] page = pageBounds(plan.pageIndex);
        return overlapArea(page, b) > 0.0;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int parseFlexibleId(String value) {
        if (value == null || value.isEmpty()) return -1;
        int decimal = parseInt(value, -1);
        if (decimal >= 0) return decimal;
        String s = value;
        int marker = Math.max(s.lastIndexOf('u'), s.lastIndexOf('U'));
        marker = Math.max(marker, Math.max(s.lastIndexOf('i'), s.lastIndexOf('I')));
        if (marker >= 0 && marker + 1 < s.length()) {
            String tail = s.substring(marker + 1);
            int slash = tail.indexOf('/');
            if (slash >= 0) tail = tail.substring(0, slash);
            try {
                return Integer.parseInt(tail, 16);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    private static String roundedBounds(double[] b) {
        StringBuilder sb = new StringBuilder(48);
        for (int i = 0; i < 4; i++) {
            if (i > 0) sb.append(',');
            sb.append(Math.round(b[i] * 10.0) / 10.0);
        }
        return sb.toString();
    }

    private static String planRefs(List<ObjectPlan> plans) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < plans.size(); i++) {
            if (i > 0) sb.append(';');
            ObjectPlan p = plans.get(i);
            sb.append(p.domId)
                    .append('/')
                    .append(p.kind)
                    .append('/')
                    .append(p.visualAction)
                    .append('/')
                    .append(p.visualPolicyLayer())
                    .append('/')
                    .append(p.placement);
        }
        return sb.toString();
    }

    private static String planRef(ObjectPlan plan) {
        if (plan == null) return "null";
        return planRefs(java.util.Collections.singletonList(plan));
    }

    private static String joinSources(LinkedHashSet<String> sources, int limit) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String source : sources) {
            if (count > 0) sb.append(',');
            if (count >= limit) {
                sb.append("...");
                break;
            }
            sb.append(source);
            count++;
        }
        if (sources.size() > limit) {
            sb.append("(total=").append(sources.size()).append(')');
        }
        return sb.toString();
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    private static boolean sameBounds(double[] a, double[] b, double tolerance) {
        if (a == null || b == null || a.length < 4 || b.length < 4) return false;
        for (int i = 0; i < 4; i++) {
            if (Math.abs(a[i] - b[i]) > tolerance) return false;
        }
        return true;
    }

    private static String appendReason(String reason, String suffix) {
        if (suffix == null || suffix.isBlank()) return reason;
        if (reason == null || reason.isBlank()) return suffix;
        if (reason.contains(suffix)) return reason;
        return reason + "|" + suffix;
    }
}
