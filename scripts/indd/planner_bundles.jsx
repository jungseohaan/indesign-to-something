/*
 * Planner-declared bundle diagnostics for extract_indd.jsx.
 *
 * Source clusters describe the recursive IDML source tree. Planner bundles
 * describe the actual source set and visible slot declared by extraction
 * candidates. This module is diagnostic and must not change placement.
 */

function _buildPlannerBundles(sourceItems, candidates, options) {
    options = options || {};
    var clusterDoc = options.sourceClusterDiagnostics || options.sourceClusterDocument || null;
    var clusterIndex = options.sourceClusterIndex || null;
    if (!clusterIndex) {
        if (!clusterDoc) clusterDoc = _buildSourceClusters(sourceItems);
        clusterIndex = _createSourceClusterIndex(sourceItems, clusterDoc);
    } else if (!clusterDoc && clusterIndex.diagnostics) {
        clusterDoc = clusterIndex.diagnostics;
    }
    var bundles = [];
    var summary = {
        candidateCount: candidates ? candidates.length : 0,
        reusedSourceClusterIndex: options.sourceClusterIndex ? true : false,
        bundleCount: 0,
        slotCounts: {},
        policyLayerCounts: {},
        relationCounts: {},
        executableBundleCount: 0,
        requiredBundleCount: 0,
        bundlesWithVisualSources: 0,
        bundlesWithStyleSources: 0,
        bundlesWithOwnedTextFrames: 0
    };

    if (!candidates) {
        return _plannerBundleDocument(bundles, summary);
    }

    for (var i = 0; i < candidates.length; i++) {
        var candidate = candidates[i];
        if (!candidate) continue;
        var bundle = _plannerBundleFromCandidate(candidate, clusterIndex);
        bundles.push(bundle);
        summary.bundleCount++;
        _incrementPlannerSummary(summary.slotCounts, bundle.ownershipSlot);
        _incrementPlannerSummary(summary.policyLayerCounts, bundle.policyLayer);
        _incrementPlannerSummary(summary.relationCounts, bundle.clusterRelation);
        if (bundle.executable) summary.executableBundleCount++;
        if (bundle.required) summary.requiredBundleCount++;
        if (bundle.visualSourceObjectIds && bundle.visualSourceObjectIds.length > 0) summary.bundlesWithVisualSources++;
        if (bundle.styleSourceObjectIds && bundle.styleSourceObjectIds.length > 0) summary.bundlesWithStyleSources++;
        if (bundle.ownedTextFrameIds && bundle.ownedTextFrameIds.length > 0) summary.bundlesWithOwnedTextFrames++;
    }

    return _plannerBundleDocument(bundles, summary);
}

function _plannerBundleDocument(bundles, summary) {
    return {
        schemaVersion: 1,
        policy: "POLICY-source-ownership",
        mode: "planner-declared-bundle-diagnostics",
        summary: summary,
        bundles: bundles || []
    };
}

function _syncPlannerBundleDiagnosticsToExecutionCandidates(plannerBundleDiagnostics, executionCandidates, options) {
    options = options || {};
    if (!plannerBundleDiagnostics || !plannerBundleDiagnostics.bundles) {
        return {
            diagnostics: plannerBundleDiagnostics,
            summary: {
                originalBundleCount: 0,
                retainedBundleCount: 0,
                prunedBundleCount: 0
            },
            pruned: []
        };
    }
    var activeCandidateIds = {};
    var activeObjectPlanIds = {};
    var activeBundleIds = {};
    var rows = executionCandidates || [];
    for (var i = 0; i < rows.length; i++) {
        var candidate = rows[i];
        if (candidate && candidate.candidateId) activeCandidateIds[String(candidate.candidateId)] = true;
        if (candidate && candidate.objectPlanId) activeObjectPlanIds[String(candidate.objectPlanId)] = true;
    }
    var plans = options.objectPlanDiagnostics && options.objectPlanDiagnostics.objectPlans
            ? options.objectPlanDiagnostics.objectPlans
            : [];
    for (var p = 0; p < plans.length; p++) {
        var plan = plans[p];
        if (!plan || !plan.bundleId) continue;
        if (plan.objectPlanId && activeObjectPlanIds[String(plan.objectPlanId)]) {
            activeBundleIds[String(plan.bundleId)] = true;
        } else if (plan.candidateId && activeCandidateIds[String(plan.candidateId)]) {
            activeBundleIds[String(plan.bundleId)] = true;
        }
    }

    var bundles = plannerBundleDiagnostics.bundles || [];
    var kept = [];
    var pruned = [];
    for (var b = 0; b < bundles.length; b++) {
        var bundle = bundles[b];
        if (!bundle) continue;
        if ((bundle.bundleId && activeBundleIds[String(bundle.bundleId)])
                || !bundle.candidateId
                || activeCandidateIds[String(bundle.candidateId)]) {
            kept.push(bundle);
        } else {
            pruned.push({
                bundleId: bundle.bundleId || null,
                candidateId: bundle.candidateId || null,
                passId: bundle.passId || null,
                pageIndex: bundle.pageIndex,
                ownershipSlot: bundle.ownershipSlot || null,
                materialization: bundle.materialization || null,
                reason: options.reason || "execution_candidate_suppressed"
            });
        }
    }

    var previousSummary = plannerBundleDiagnostics.summary || {};
    var summary = _summarizePlannerBundles(kept);
    for (var summaryKey in previousSummary) {
        if (!previousSummary.hasOwnProperty(summaryKey)) continue;
        if (summary[summaryKey] !== undefined) continue;
        summary[summaryKey] = previousSummary[summaryKey];
    }
    summary.candidateCount = rows.length;
    summary.executionCandidateSync = {
        originalBundleCount: bundles.length,
        retainedBundleCount: kept.length,
        prunedBundleCount: pruned.length,
        activeExecutionCandidateCount: rows.length,
        reason: options.reason || "execution_candidate_suppressed"
    };
    plannerBundleDiagnostics.summary = summary;
    plannerBundleDiagnostics.bundles = kept;
    return {
        diagnostics: plannerBundleDiagnostics,
        summary: summary.executionCandidateSync,
        pruned: pruned
    };
}

function _summarizePlannerBundles(bundles) {
    bundles = bundles || [];
    var summary = {
        candidateCount: bundles.length,
        reusedSourceClusterIndex: true,
        bundleCount: 0,
        slotCounts: {},
        policyLayerCounts: {},
        relationCounts: {},
        executableBundleCount: 0,
        requiredBundleCount: 0,
        bundlesWithVisualSources: 0,
        bundlesWithStyleSources: 0,
        bundlesWithOwnedTextFrames: 0
    };
    for (var i = 0; i < bundles.length; i++) {
        var bundle = bundles[i];
        if (!bundle) continue;
        summary.bundleCount++;
        _incrementPlannerSummary(summary.slotCounts, bundle.ownershipSlot);
        _incrementPlannerSummary(summary.policyLayerCounts, bundle.policyLayer);
        _incrementPlannerSummary(summary.relationCounts, bundle.clusterRelation);
        if (bundle.executable) summary.executableBundleCount++;
        if (bundle.required) summary.requiredBundleCount++;
        if (bundle.visualSourceObjectIds && bundle.visualSourceObjectIds.length > 0) summary.bundlesWithVisualSources++;
        if (bundle.styleSourceObjectIds && bundle.styleSourceObjectIds.length > 0) summary.bundlesWithStyleSources++;
        if (bundle.ownedTextFrameIds && bundle.ownedTextFrameIds.length > 0) summary.bundlesWithOwnedTextFrames++;
    }
    return summary;
}

function _plannerBundleFromCandidate(candidate, clusterIndex) {
    var sourceIds = _internSourceSetIds(candidate.sourceObjectIds || []);
    if (_plannerBundleIsPageRootTextlessVisualPlane(candidate)
            && candidate.exportSourceObjectIds
            && candidate.exportSourceObjectIds.length > 0) {
        sourceIds = _internSourceSetIds(candidate.exportSourceObjectIds || []);
    }
    var primarySourceObjectId = candidate.primarySourceObjectId !== undefined
            ? candidate.primarySourceObjectId
            : (sourceIds.length > 0 ? sourceIds[0] : null);
    var slot = _plannerBundleOwnershipSlot(candidate, clusterIndex);
    var sourceRootObjectIds = _plannerBundleIsPageRootTextlessVisualPlane(candidate)
            && candidate.sourceRootObjectIds
            && candidate.sourceRootObjectIds.length > 0
            ? _internSourceSetIds(candidate.sourceRootObjectIds || [])
            : _plannerBundleSourceRootObjectIds(sourceIds, clusterIndex);
    var clusterSourceObjectIds = _plannerBundleClusterSourceObjectIds(
            primarySourceObjectId, sourceRootObjectIds, clusterIndex);
    var clusterRelation = _plannerBundleClusterRelation(
            sourceIds, clusterSourceObjectIds, primarySourceObjectId, clusterIndex);
    var clusterProfile = _plannerBundleClusterProfile(
            clusterSourceObjectIds, sourceIds, candidate.pageIndex, clusterIndex);
    var declaredCandidate = _plannerBundleWithInferredSlotContract(
            candidate, slot, sourceIds, clusterRelation, clusterProfile);
    var slotSources = _plannerBundleSlotSources(declaredCandidate, slot, sourceIds, clusterIndex);
    var completedCandidate = _plannerBundleWithCompletedVisibleFragmentContract(
            declaredCandidate, slot, clusterRelation, clusterProfile, slotSources);
    if (completedCandidate !== declaredCandidate) {
        declaredCandidate = completedCandidate;
        slotSources = _plannerBundleSlotSources(declaredCandidate, slot, sourceIds, clusterIndex);
    } else {
        declaredCandidate = completedCandidate;
    }
    slotSources = _plannerBundlePruneShellOwnedTextAndTableStructureSources(
            declaredCandidate, slot, slotSources, clusterIndex);
    slotSources = _plannerBundleWithoutOwnedTextVisualSources(slot, slotSources, clusterIndex);
    slotSources = _plannerBundleWithoutPlacedContentBranches(
            declaredCandidate, slot, slotSources, clusterIndex);
    slotSources = _plannerBundleWithoutClippedPlacedContentLeafSources(
            declaredCandidate, slot, slotSources, clusterIndex);
    var declaredExportSourceObjectIds = _internSourceSetIds(
            declaredCandidate.exportSourceObjectIds || []);
    var exportSourceObjectIds = _internSourceSetIds(declaredExportSourceObjectIds || []);
    if (_plannerBundleShouldExportInlineSimpleMarkerCompletePng(
            declaredCandidate, slot, slotSources, sourceIds, exportSourceObjectIds, clusterIndex)) {
        exportSourceObjectIds = _internSourceSetIds(sourceIds || []);
    }
    if (_plannerBundleShouldFillMissingSlotOnlyExport(
            declaredCandidate, exportSourceObjectIds, slotSources)) {
        exportSourceObjectIds = _internSourceSetIds(slotSources.visualSourceObjectIds || []);
    }
    if (_plannerBundleShouldKeepShellVisualExportSources(
            slot, exportSourceObjectIds, slotSources)) {
        exportSourceObjectIds = _plannerBundleSourceIdsUnion(
                exportSourceObjectIds, slotSources.visualSourceObjectIds);
    }
    if (_plannerBundleShouldUseAppliedMasterVisualSourcesAsExport(
            declaredCandidate, slot, exportSourceObjectIds, slotSources)) {
        exportSourceObjectIds = _internSourceSetIds(slotSources.visualSourceObjectIds || []);
    }
    if (_plannerBundleShouldUseBackgroundShellCompleteSourceSetAsExport(
            declaredCandidate, slot, slotSources, exportSourceObjectIds, clusterIndex)) {
        exportSourceObjectIds = _internSourceSetIds(sourceIds || []);
    }
    if (slot === "SHELL_SLOT" && slotSources.styleSourceObjectIds
            && slotSources.styleSourceObjectIds.length > 0) {
        exportSourceObjectIds = _plannerBundleSourceIdsUnion(
                exportSourceObjectIds, slotSources.styleSourceObjectIds);
    }
    exportSourceObjectIds = _plannerBundlePrunePlacedContentBranches(
            declaredCandidate, slot, exportSourceObjectIds, clusterIndex);
    exportSourceObjectIds = _plannerBundlePruneClippedPlacedContentLeafIds(
            declaredCandidate, slot, exportSourceObjectIds, clusterIndex);
    exportSourceObjectIds = _plannerBundlePruneShellOwnedTextAndTableStructureExportSources(
            declaredCandidate, slot, slotSources, exportSourceObjectIds, clusterIndex);
    exportSourceObjectIds = _plannerBundleSourceIdsIntersect(exportSourceObjectIds, sourceIds);
    if (_plannerBundleShouldRestoreBackgroundShellRootExport(
            declaredCandidate, slot, exportSourceObjectIds, declaredExportSourceObjectIds)) {
        exportSourceObjectIds = _internSourceSetIds(declaredExportSourceObjectIds || []);
    }
    if (slot === "CONTENT_VISUAL_SLOT"
            && (!slotSources.visualSourceObjectIds || slotSources.visualSourceObjectIds.length === 0)
            && exportSourceObjectIds && exportSourceObjectIds.length > 0) {
        slotSources.visualSourceObjectIds = _internSourceSetIds(exportSourceObjectIds);
    }
    var hiddenVisualSourceObjectIds = _plannerBundleSourceIdsMinus(
            declaredCandidate.hiddenVisualSourceObjectIds || [], exportSourceObjectIds);
    var excludedInlineSourceObjectIds = _internSourceSetIds(
            declaredCandidate.excludedInlineSourceObjectIds || []);
    var hiddenScopeSourceIds = declaredCandidate.mode === "SLOT_ONLY"
            && clusterRelation === "BUNDLE_NARROWER_THAN_CLUSTER"
            ? clusterSourceObjectIds
            : sourceIds;
    hiddenVisualSourceObjectIds = _plannerBundleSourceIdsIntersect(
            hiddenVisualSourceObjectIds, hiddenScopeSourceIds);
    hiddenVisualSourceObjectIds = _plannerBundlePruneClosedImageTextlessGroupHiddenIds(
            declaredCandidate, slot, hiddenVisualSourceObjectIds, clusterIndex);
    var restoredHiddenShellMaterial = _plannerBundleRestoreHiddenTextShellMaterialExportSources(
            declaredCandidate, slot, slotSources, exportSourceObjectIds,
            hiddenVisualSourceObjectIds, clusterIndex);
    exportSourceObjectIds = restoredHiddenShellMaterial.exportSourceObjectIds;
    hiddenVisualSourceObjectIds = restoredHiddenShellMaterial.hiddenVisualSourceObjectIds;
    if (restoredHiddenShellMaterial.restoredSourceObjectIds.length > 0) {
        slotSources.visualSourceObjectIds = _plannerBundleSourceIdsUnion(
                slotSources.visualSourceObjectIds || [],
                restoredHiddenShellMaterial.restoredSourceObjectIds);
    }
    var materialization = _plannerBundleMaterialization(
            declaredCandidate, slot, slotSources, exportSourceObjectIds,
            hiddenVisualSourceObjectIds, clusterIndex);
    var inlineFlowSourceObjectIds = _plannerBundleInlineFlowSourceObjectIds(
            declaredCandidate, slotSources, exportSourceObjectIds,
            hiddenVisualSourceObjectIds, clusterIndex);
    var executable = candidate.disabled !== true;
    if (slot === "SHELL_SLOT"
            && !_plannerBundleHasExecutableShellMaterial(slotSources, clusterIndex)) {
        executable = _plannerBundleIsExecutableAppliedMasterShell(
                declaredCandidate, slotSources);
    }
    var zOrder = _plannerBundleZOrder(candidate, primarySourceObjectId, clusterIndex);
    var tableCellInlineAnchorSource = _plannerBundleSourceSetHasTableCellInlineAnchor(
            sourceIds, clusterIndex);
    var candidatePagePositionedAnchoredSource = candidate.pagePositionedAnchoredSource === true
            || declaredCandidate.pagePositionedAnchoredSource === true;
    var pagePositionedAnchoredSource = tableCellInlineAnchorSource
            ? false
            : (candidatePagePositionedAnchoredSource
                    || _plannerBundleSourceSetHasPagePositionedAnchor(sourceIds, clusterIndex));
    var sourceInlineFlow = tableCellInlineAnchorSource
            ? true
            : (pagePositionedAnchoredSource
                    ? false
                    : (candidate.sourceInlineFlow === true
                        || declaredCandidate.sourceInlineFlow === true
                        || _plannerBundleSourceSetIsInlineFlow(sourceIds, clusterIndex)));
    var inlineAnchorSourceObjectId = candidate.inlineAnchorSourceObjectId
            || _plannerBundleStoryTextInlineAnchorSourceObjectId(sourceIds, clusterIndex);
    if (pagePositionedAnchoredSource) inlineAnchorSourceObjectId = null;
    var inlineCompositeLayoutDescendant = _plannerBundleIsInsideInlineCompositeLayout(
            sourceIds, clusterIndex);
    var connectorDecorationVisual = _plannerBundleIsInlineCompositeTextlessVectorDecoration(
            candidate, slot, sourceIds, inlineCompositeLayoutDescendant, clusterIndex);
    var layer = connectorDecorationVisual
            ? "DECORATION"
            : _plannerBundlePolicyLayer(candidate, slot, clusterIndex);
    var sourceExtentBounds = _plannerBundleSourceExtentBounds(
            candidate, declaredCandidate, slotSources, sourceIds, clusterIndex);
    var renderSourceBounds = candidate.renderSourceBounds || sourceExtentBounds;
    var cropSourceBounds = candidate.cropSourceBounds
            ? candidate.cropSourceBounds
            : _plannerBundleCropSourceBounds(candidate, sourceExtentBounds);

    return {
        bundleId: _plannerBundleId(candidate, sourceIds, clusterIndex),
        candidateId: candidate.candidateId || null,
        passId: candidate.passId || null,
        pageIndex: candidate.pageIndex,
        unit: candidate.unit || null,
        mode: declaredCandidate.mode || null,
        candidatePurpose: candidate.candidatePurpose || null,
        compositeRole: candidate.compositeRole || null,
        slotRole: declaredCandidate.slotRole || null,
        textRangeDecorationShell: declaredCandidate.textRangeDecorationShell === true,
        decoratedTextFrameIds: _sortedNumericIds(
                declaredCandidate.decoratedTextFrameIds || []),
        layoutOnlyInlineSlot: candidate.layoutOnlyInlineSlot === true,
        ownedByNativeShellSourceObjectIds: _sortedNumericIds(
                candidate.ownedByNativeShellSourceObjectIds || []),
        sourceObjectIds: sourceIds,
        sourceRootObjectIds: sourceRootObjectIds,
        clusterSourceObjectIds: clusterSourceObjectIds,
        primarySourceObjectId: primarySourceObjectId,
        clusterKindCounts: clusterProfile.clusterKindCounts,
        omittedClusterSourceObjectIds: clusterProfile.omittedClusterSourceObjectIds,
        omittedClusterKindCounts: clusterProfile.omittedClusterKindCounts,
        clusterHasEditableText: clusterProfile.clusterHasEditableText,
        clusterHasTextFrame: clusterProfile.clusterHasTextFrame,
        clusterHasPlacedContent: clusterProfile.clusterHasPlacedContent,
        clusterHasVisualSource: clusterProfile.clusterHasVisualSource,
            sourceSetId: _sourceSetId(sourceIds),
            clusterSourceSetId: _sourceSetId(clusterSourceObjectIds),
            exportSourceSetId: _sourceSetId(exportSourceObjectIds),
            hiddenSourceSetId: _sourceSetId(hiddenVisualSourceObjectIds),
            exportSourceObjectIds: exportSourceObjectIds,
            excludedInlineSourceObjectIds: excludedInlineSourceObjectIds,
        exportTargetObjectId: declaredCandidate.exportTargetObjectId !== undefined
                ? declaredCandidate.exportTargetObjectId
                : null,
        atomicExportTargetObjectId: declaredCandidate.atomicExportTargetObjectId !== undefined
                ? declaredCandidate.atomicExportTargetObjectId
                : null,
        atomicSourceObjectIds: _internSourceSetIds(
                declaredCandidate.atomicSourceObjectIds || []),
        atomicVisualSourceObjectIds: _internSourceSetIds(
                declaredCandidate.atomicVisualSourceObjectIds || []),
        atomicOwnedTextFrameIds: _internSourceSetIds(
                declaredCandidate.atomicOwnedTextFrameIds || []),
        atomicExportTargetObjectIds: _internSourceSetIds(
                declaredCandidate.atomicExportTargetObjectIds || []),
        atomicTextlessVectorContent: declaredCandidate.atomicTextlessVectorContent === true,
        atomicContentVisualSlot: declaredCandidate.atomicContentVisualSlot === true,
        textWrapMode: declaredCandidate.textWrapMode || null,
        textWrapSide: declaredCandidate.textWrapSide || null,
        textWrapTop: declaredCandidate.textWrapTop !== undefined
                ? declaredCandidate.textWrapTop
                : null,
        textWrapLeft: declaredCandidate.textWrapLeft !== undefined
                ? declaredCandidate.textWrapLeft
                : null,
        textWrapBottom: declaredCandidate.textWrapBottom !== undefined
                ? declaredCandidate.textWrapBottom
                : null,
        textWrapRight: declaredCandidate.textWrapRight !== undefined
                ? declaredCandidate.textWrapRight
                : null,
        textWrapSourceObjectId: declaredCandidate.textWrapSourceObjectId !== undefined
                ? declaredCandidate.textWrapSourceObjectId
                : null,
        hiddenVisualSourceObjectIds: hiddenVisualSourceObjectIds,
        visualSourceObjectIds: slotSources.visualSourceObjectIds,
        styleSourceObjectIds: slotSources.styleSourceObjectIds,
        ownedTextFrameIds: slotSources.ownedTextFrameIds,
        hiddenTextFrameIds: _sortedNumericIds(declaredCandidate.hiddenTextFrameIds || []),
        editableTextFrameIds: _sortedNumericIds(declaredCandidate.editableTextFrameIds || []),
        textOwner: declaredCandidate.textOwner || null,
        requiresTextHidden: declaredCandidate.requiresTextHidden === true,
        completePngTextAllowed: declaredCandidate.completePngTextAllowed === true,
        textAction: declaredCandidate.textAction || null,
        visualAction: declaredCandidate.visualAction || null,
        ownershipSlot: slot,
        policyLayer: layer,
        materialization: materialization,
        clusterRelation: clusterRelation,
        executable: executable,
        required: candidate.required === true,
        sourceInlineFlow: sourceInlineFlow,
        tableCellInlineAnchorSource: tableCellInlineAnchorSource,
        pagePositionedAnchoredSource: pagePositionedAnchoredSource,
        storyAnchorPlacement: declaredCandidate.storyAnchorPlacement || null,
        anchoredPosition: declaredCandidate.anchoredPosition || null,
        storyTextInlineSlot: declaredCandidate.storyTextInlineSlot === true,
        tableCellStoryTextInlineSlot: declaredCandidate.tableCellStoryTextInlineSlot === true,
        inlineCompositeLayoutDescendant: inlineCompositeLayoutDescendant,
        inlineAnchorSourceObjectId: inlineAnchorSourceObjectId || null,
        inlineSourceTreeClosed: !pagePositionedAnchoredSource && candidate.inlineSourceTreeClosed === true,
        inlineFlowSourceObjectIds: inlineFlowSourceObjectIds,
        inlineTextStyleMarkerSource: _plannerBundleHasInlineTextStyleMarkerSource(
                candidate, sourceIds, clusterIndex),
        connectorDecorationVisual: connectorDecorationVisual,
        sourceDeclaredNonReflowableTextCompletePng:
                declaredCandidate.sourceDeclaredNonReflowableTextCompletePng === true,
        zOrder: zOrder,
        bounds: candidate.bounds || null,
        renderSourceBounds: renderSourceBounds,
        cropSourceBounds: cropSourceBounds
    };
}

function _plannerBundleInlineFlowSourceObjectIds(
        candidate, slotSources, exportSourceObjectIds, hiddenVisualSourceObjectIds,
        clusterIndex) {
    if (!candidate || candidate.inlineSourceTreeClosed !== true) return [];
    if (candidate.passId !== "pass.inline_objects") return [];
    if (!hiddenVisualSourceObjectIds || hiddenVisualSourceObjectIds.length === 0) return [];
    var ids = [];
    var seen = {};
    function addAll(values) {
        for (var i = 0; values && i < values.length; i++) {
            var id = Number(values[i]);
            if (isNaN(id)) continue;
            var key = String(id);
            if (seen[key]) continue;
            seen[key] = true;
            ids.push(id);
        }
    }
    addAll(exportSourceObjectIds || []);
    if (ids.length === 0 && slotSources) addAll(slotSources.visualSourceObjectIds || []);
    addAll(hiddenVisualSourceObjectIds || []);
    if (ids.length < 2) return [];
    ids.sort(function(a, b) {
        var ao = _plannerBundleSourceOrder(a, clusterIndex);
        var bo = _plannerBundleSourceOrder(b, clusterIndex);
        if (ao !== bo) return ao - bo;
        return a - b;
    });
    return ids;
}

function _plannerBundleSourceOrder(sourceId, clusterIndex) {
    if (!clusterIndex || !clusterIndex.sourceInfo) return Number(sourceId);
    var src = clusterIndex.sourceInfo(sourceId);
    if (src && src.sourceOrder !== undefined && src.sourceOrder !== null) {
        var order = Number(src.sourceOrder);
        if (!isNaN(order)) return order;
    }
    return Number(sourceId);
}

function _plannerBundleSourceExtentBounds(
        candidate, declaredCandidate, slotSources, sourceIds, clusterIndex) {
    if (!candidate || !candidate.bounds || candidate.bounds.length < 4) return null;
    if (!clusterIndex || !clusterIndex.sourceInfo) return null;
    var targetPageIndex = Number(candidate.pageIndex);
    if (isNaN(targetPageIndex) || targetPageIndex < 0) return null;

    var ids = _plannerBundleExtentSourceIds(
            candidate, declaredCandidate, slotSources, sourceIds, clusterIndex);
    var union = null;
    for (var i = 0; ids && i < ids.length; i++) {
        var src = clusterIndex.sourceInfo(ids[i]);
        if (!src || !src.bounds || src.bounds.length < 4) continue;
        var adjusted = _plannerBundleBoundsRelativeToTargetPage(
                src.bounds, src.pageIndex, targetPageIndex, clusterIndex, candidate.bounds);
        if (!adjusted) continue;
        union = union ? _plannerBundleUnionBounds(union, adjusted) : adjusted;
    }
    if (!union) return null;
    if (!_plannerBundleBoundsContains(union, candidate.bounds, 0.05)) return null;
    if (!_plannerBundleBoundsMateriallyLarger(union, candidate.bounds, 0.5)) return null;
    return union;
}

function _plannerBundleCropSourceBounds(candidate, sourceExtentBounds) {
    if (!candidate || !candidate.bounds || candidate.bounds.length < 4) return null;
    if (!sourceExtentBounds || sourceExtentBounds.length < 4) return null;
    if (!_plannerBundleBoundsContains(sourceExtentBounds, candidate.bounds, 0.05)) return null;
    if (!_plannerBundleBoundsMateriallyLarger(sourceExtentBounds, candidate.bounds, 0.5)) return null;
    return sourceExtentBounds.slice(0);
}

function _plannerBundleExtentSourceIds(
        candidate, declaredCandidate, slotSources, sourceIds, clusterIndex) {
    var ids = [];
    if (declaredCandidate && declaredCandidate.exportSourceObjectIds
            && declaredCandidate.exportSourceObjectIds.length > 0) {
        ids = declaredCandidate.exportSourceObjectIds;
    } else if (slotSources && slotSources.visualSourceObjectIds
            && slotSources.visualSourceObjectIds.length > 0) {
        ids = slotSources.visualSourceObjectIds;
    } else {
        ids = sourceIds || [];
    }
    ids = _sortedNumericIds(ids);
    if (!clusterIndex || !clusterIndex.sourceInfo || ids.length < 2) return ids;
    var idSet = _sourceIdSet(ids);
    var pruned = [];
    for (var i = 0; i < ids.length; i++) {
        if (_plannerBundleShouldDropPlacedImageExtentSource(ids[i], idSet, clusterIndex)) continue;
        pruned.push(ids[i]);
    }
    return pruned.length > 0 ? pruned : ids;
}

function _plannerBundleShouldDropPlacedImageExtentSource(sourceId, sourceIdSet, clusterIndex) {
    if (sourceId === null || sourceId === undefined || !clusterIndex || !clusterIndex.sourceInfo) {
        return false;
    }
    var src = clusterIndex.sourceInfo(sourceId);
    if (!src || String(src.kind || "") !== "Image") return false;
    var parentId = src.parentId;
    if (parentId === null || parentId === undefined) return false;
    if (!sourceIdSet[String(parentId)]) return false;
    var parent = clusterIndex.sourceInfo(parentId);
    if (!parent) return false;
    var parentKind = String(parent.kind || "");
    if (parentKind !== "Rectangle" && parentKind !== "Oval" && parentKind !== "Polygon") {
        return false;
    }
    if (!src.bounds || src.bounds.length < 4 || !parent.bounds || parent.bounds.length < 4) {
        return false;
    }
    return true;
}

function _plannerBundleBoundsRelativeToTargetPage(
        bounds, sourcePageIndex, targetPageIndex, clusterIndex, targetBounds) {
    if (!bounds || bounds.length < 4) return null;
    var out = [
        Number(bounds[0]),
        Number(bounds[1]),
        Number(bounds[2]),
        Number(bounds[3])
    ];
    if (isNaN(out[0]) || isNaN(out[1]) || isNaN(out[2]) || isNaN(out[3])) return null;
    sourcePageIndex = Number(sourcePageIndex);
    if (isNaN(sourcePageIndex) || sourcePageIndex < 0 || sourcePageIndex === targetPageIndex) {
        return out;
    }
    if (clusterIndex.sameSpread && clusterIndex.sameSpread(sourcePageIndex, targetPageIndex) !== true) {
        return out;
    }
    if (!clusterIndex.pageBounds) {
        return _plannerBundleAdjacentPageShift(out, sourcePageIndex, targetPageIndex, targetBounds) || out;
    }
    var sourcePageBounds = clusterIndex.pageBounds(sourcePageIndex);
    var targetPageBounds = clusterIndex.pageBounds(targetPageIndex);
    if (!sourcePageBounds || !targetPageBounds
            || sourcePageBounds.length < 4 || targetPageBounds.length < 4) {
        return _plannerBundleAdjacentPageShift(out, sourcePageIndex, targetPageIndex, targetBounds) || out;
    }
    var dTop = Number(sourcePageBounds[0]) - Number(targetPageBounds[0]);
    var dLeft = Number(sourcePageBounds[1]) - Number(targetPageBounds[1]);
    if (isNaN(dTop) || isNaN(dLeft)) return out;
    if (Math.abs(dTop) <= 0.05 && Math.abs(dLeft) <= 0.05) {
        return _plannerBundleAdjacentPageShift(out, sourcePageIndex, targetPageIndex, targetBounds) || out;
    }
    return [
        out[0] + dTop,
        out[1] + dLeft,
        out[2] + dTop,
        out[3] + dLeft
    ];
}

function _plannerBundleAdjacentPageShift(bounds, sourcePageIndex, targetPageIndex, targetBounds) {
    if (!bounds || !targetBounds || targetBounds.length < 4) return null;
    var pageDelta = Number(targetPageIndex) - Number(sourcePageIndex);
    if (isNaN(pageDelta) || Math.abs(pageDelta) !== 1) return null;
    var targetPageWidth = Number(targetBounds[3]) - Number(targetBounds[1]);
    if (isNaN(targetPageWidth) || targetPageWidth <= 1.0) return null;
    var shifted = [
        bounds[0],
        bounds[1] - pageDelta * targetPageWidth,
        bounds[2],
        bounds[3] - pageDelta * targetPageWidth
    ];
    return _plannerBundleBoundsOverlap(shifted, targetBounds, 0.5) ? shifted : null;
}

function _plannerBundleUnionBounds(a, b) {
    return [
        Math.min(a[0], b[0]),
        Math.min(a[1], b[1]),
        Math.max(a[2], b[2]),
        Math.max(a[3], b[3])
    ];
}

function _plannerBundleBoundsContains(outer, inner, eps) {
    if (!outer || !inner || outer.length < 4 || inner.length < 4) return false;
    eps = eps || 0;
    return outer[0] <= inner[0] + eps
            && outer[1] <= inner[1] + eps
            && outer[2] >= inner[2] - eps
            && outer[3] >= inner[3] - eps;
}

function _plannerBundleBoundsMateriallyLarger(outer, inner, eps) {
    if (!outer || !inner || outer.length < 4 || inner.length < 4) return false;
    eps = eps || 0;
    return (outer[3] - outer[1]) > (inner[3] - inner[1]) + eps
            || (outer[2] - outer[0]) > (inner[2] - inner[0]) + eps;
}

function _plannerBundleBoundsOverlap(a, b, eps) {
    if (!a || !b || a.length < 4 || b.length < 4) return false;
    eps = eps || 0;
    return Math.min(a[3], b[3]) > Math.max(a[1], b[1]) - eps
            && Math.min(a[2], b[2]) > Math.max(a[0], b[0]) - eps;
}

function _plannerBundleSourceSetIsInlineFlow(sourceIds, clusterIndex) {
    if (!sourceIds || sourceIds.length === 0 || !clusterIndex || !clusterIndex.sourceInfo) {
        return false;
    }
    var cache = _plannerBundleCache(clusterIndex, "inlineFlowBySourceSet");
    var cacheKey = _plannerBundleSourceSetKey(sourceIds, clusterIndex);
    if (cache[cacheKey] !== undefined) return cache[cacheKey];
    var sawInline = false;
    for (var i = 0; i < sourceIds.length; i++) {
        var src = clusterIndex.sourceInfo(sourceIds[i]);
        if (!src) continue;
        var placement = String(src.storyAnchorPlacement || "").toUpperCase();
        var anchoredPosition = String(src.anchoredPosition || "").toUpperCase();
        if (placement === "FLOATING_ANCHORED" || anchoredPosition === "ANCHORED") {
            cache[cacheKey] = false;
            return false;
        }
        if (src.storyTextInlineSlot === true) {
            sawInline = true;
            continue;
        }
        var kind = String(src.kind || "");
        if (kind === "Story" || kind === "Character" || kind === "InsertionPoint" || kind === "Cell") {
            continue;
        }
        if (typeof _isInlineFlowItemBySourceInfo === "function"
                && _isInlineFlowItemBySourceInfo(src)) {
            sawInline = true;
            continue;
        }
        if (src.isInline === true || src.inline === true || src.anchored === true
                || anchoredPosition === "INLINE_POSITION"
                || placement === "INLINE") {
            sawInline = true;
            continue;
        }
        cache[cacheKey] = false;
        return false;
    }
    cache[cacheKey] = sawInline;
    return cache[cacheKey];
}

function _plannerBundleSourceSetHasPagePositionedAnchor(sourceIds, clusterIndex) {
    if (!sourceIds || sourceIds.length === 0 || !clusterIndex || !clusterIndex.sourceInfo) {
        return false;
    }
    var cache = _plannerBundleCache(clusterIndex, "pagePositionedAnchorBySourceSet");
    var cacheKey = _plannerBundleSourceSetKey(sourceIds, clusterIndex);
    if (cache[cacheKey] !== undefined) return cache[cacheKey];
    for (var i = 0; i < sourceIds.length; i++) {
        var src = clusterIndex.sourceInfo(sourceIds[i]);
        if (!src) continue;
        if (_plannerBundleSourceIsStoryFlowInline(src)) {
            continue;
        }
        if (String(src.storyAnchorPlacement || "").toUpperCase() === "FLOATING_ANCHORED"
                || String(src.anchoredPosition || "").toUpperCase() === "ANCHORED") {
            cache[cacheKey] = true;
            return true;
        }
    }
    cache[cacheKey] = false;
    return false;
}

function _plannerBundleSourceIsGaugeLikePagePositionedAnchor(src, clusterIndex) {
    if (!src || String(src.kind || "") !== "Group") return false;
    if (src.storyTextInlineSlot !== true) return false;
    if (String(src.storyAnchorPlacement || "").toUpperCase() !== "FLOATING_ANCHORED") return false;
    var cache = _plannerBundleCache(clusterIndex, "gaugeLikePagePositionedAnchorBySource");
    var cacheKey = String(src.id);
    if (cache[cacheKey] !== undefined) return cache[cacheKey];
    var descendants = _plannerBundleDescendantSourceObjectIds(clusterIndex, src.id);
    var count = 0;
    for (var i = 0; i < descendants.length; i++) {
        if (String(descendants[i]) === String(src.id)) continue;
        var child = clusterIndex && clusterIndex.sourceInfo
                ? clusterIndex.sourceInfo(descendants[i])
                : null;
        if (child && String(child.kind || "") === "GraphicLine") count++;
    }
    cache[cacheKey] = count >= _gaugeLikeGraphicLineMin();
    return cache[cacheKey];
}

function _plannerBundleSourceIsStoryFlowInline(src) {
    if (!src) return false;
    var placement = String(src.storyAnchorPlacement || "").toUpperCase();
    var anchoredPosition = String(src.anchoredPosition || "").toUpperCase();
    if (placement === "FLOATING_ANCHORED" || anchoredPosition === "ANCHORED") {
        return false;
    }
    if (src.storyTextInlineSlot === true) return true;
    return placement === "INLINE"
            || anchoredPosition === "INLINE_POSITION"
            || anchoredPosition === "INLINEPOSITION";
}

function _plannerBundleSourceSetHasTableCellInlineAnchor(sourceIds, clusterIndex) {
    if (!sourceIds || sourceIds.length === 0 || !clusterIndex || !clusterIndex.sourceInfo) {
        return false;
    }
    var cache = _plannerBundleCache(clusterIndex, "tableCellInlineAnchorBySourceSet");
    var cacheKey = _plannerBundleSourceSetKey(sourceIds, clusterIndex);
    if (cache[cacheKey] !== undefined) return cache[cacheKey];
    var tableCellAnchorRoots = {};
    var sawTableCellInlineAnchor = false;
    for (var i = 0; i < sourceIds.length; i++) {
        var src = clusterIndex.sourceInfo(sourceIds[i]);
        if (!src) continue;
        if (src.tableCellStoryTextInlineSlot === true) {
            tableCellAnchorRoots[String(src.id)] = true;
            sawTableCellInlineAnchor = true;
        }
    }
    if (!sawTableCellInlineAnchor) {
        cache[cacheKey] = false;
        return false;
    }
    for (var j = 0; j < sourceIds.length; j++) {
        var src = clusterIndex.sourceInfo(sourceIds[j]);
        if (!src) continue;
        var kind = String(src.kind || "");
        if (kind === "Story" || kind === "Character" || kind === "InsertionPoint" || kind === "Cell") {
            continue;
        }
        if (src.tableCellStoryTextInlineSlot === true) {
            continue;
        }
        if (_plannerBundleSourceHasAncestorInSet(src.id, tableCellAnchorRoots, clusterIndex)) {
            continue;
        }
        if (src.storyTextInlineSlot === true || src.isInline === true || src.inline === true
                || src.anchored === true) {
            continue;
        }
        cache[cacheKey] = false;
        return false;
    }
    cache[cacheKey] = sawTableCellInlineAnchor;
    return cache[cacheKey];
}

function _plannerBundleSourceHasAncestorInSet(sourceId, ancestorSet, clusterIndex) {
    if (sourceId === null || sourceId === undefined || !ancestorSet
            || !clusterIndex || !clusterIndex.sourceInfo) return false;
    var current = clusterIndex.sourceInfo(sourceId);
    var guard = 0;
    while (current && guard < 32) {
        guard++;
        var parentId = current.parentId;
        if (parentId === null || parentId === undefined) return false;
        if (ancestorSet[String(parentId)]) return true;
        current = clusterIndex.sourceInfo(parentId);
    }
    return false;
}

function _plannerBundleStoryTextInlineAnchorSourceObjectId(sourceIds, clusterIndex) {
    if (!sourceIds || sourceIds.length < 1 || !clusterIndex || !clusterIndex.sourceInfo) return null;
    var roots = _plannerBundleSourceRootObjectIds(sourceIds, clusterIndex);
    var candidates = roots && roots.length > 0 ? roots : sourceIds;
    for (var i = 0; i < candidates.length; i++) {
        var src = clusterIndex.sourceInfo(candidates[i]);
        if (!src) continue;
        if (!_plannerBundleSourceIsStoryFlowInline(src)) continue;
        var id = Number(candidates[i]);
        if (!isNaN(id)) return id;
    }
    return null;
}

function _plannerBundleCache(clusterIndex, name) {
    if (!clusterIndex) return {};
    if (!clusterIndex._plannerBundleCache) clusterIndex._plannerBundleCache = {};
    if (!clusterIndex._plannerBundleCache[name]) clusterIndex._plannerBundleCache[name] = {};
    return clusterIndex._plannerBundleCache[name];
}

function _plannerBundleSourceSet(sourceIds, clusterIndex) {
    var ids = sourceIds || [];
    var cache = _plannerBundleCache(clusterIndex, "sourceSetByKey");
    var key = _sourceSetKey(ids);
    if (!cache[key]) cache[key] = _sourceSetMembership(ids);
    return cache[key];
}

function _plannerBundleSourceSetKey(sourceIds, clusterIndex) {
    var ids = sourceIds || [];
    var cache = _plannerBundleCache(clusterIndex, "sourceSetKeyByRawKey");
    var rawKey = String(ids.length) + "|" + String(ids.join ? ids.join(",") : ids);
    if (cache[rawKey] === undefined) cache[rawKey] = _sourceSetKey(ids);
    return cache[rawKey];
}

function _plannerBundleSourceSetContainsAll(sourceIds, requiredIds, clusterIndex) {
    if (!requiredIds || requiredIds.length === 0) return true;
    if (!sourceIds || sourceIds.length === 0) return false;
    var sourceSet = _plannerBundleSourceSet(sourceIds, clusterIndex);
    for (var i = 0; i < requiredIds.length; i++) {
        if (!sourceSet[String(requiredIds[i])]) return false;
    }
    return true;
}

function _plannerBundleDescendantSourceObjectIds(clusterIndex, rootId) {
    if (!clusterIndex || !clusterIndex.descendantSourceObjectIds
            || rootId === null || rootId === undefined) {
        return [];
    }
    var cache = _plannerBundleCache(clusterIndex, "descendantsByRoot");
    var key = String(rootId);
    if (!cache[key]) {
        cache[key] = _sortedNumericIds(clusterIndex.descendantSourceObjectIds(rootId) || []);
    }
    return cache[key].slice(0);
}

function _plannerBundleIsInsideInlineCompositeLayout(sourceIds, clusterIndex) {
    if (!sourceIds || sourceIds.length === 0 || !clusterIndex || !clusterIndex.sourceInfo) {
        return false;
    }
    var cache = _plannerBundleCache(clusterIndex, "insideInlineCompositeLayoutBySourceSet");
    var cacheKey = _plannerBundleSourceSetKey(sourceIds, clusterIndex);
    if (cache[cacheKey] !== undefined) return cache[cacheKey];
    var sourceSet = _plannerBundleSourceSet(sourceIds, clusterIndex);
    var ancestorId = null;
    for (var si = 0; si < sourceIds.length; si++) {
        var current = clusterIndex.sourceInfo(sourceIds[si]);
        var guard = 0;
        while (current && guard < 64) {
            if (_plannerBundleSourceIsInlineAnchorRoot(current)) {
                if (!sourceSet[String(current.id)]
                        && _plannerBundleInlineAnchorRootHasCompositeLayout(current.id, clusterIndex)) {
                    if (ancestorId === null) ancestorId = String(current.id);
                    else if (ancestorId !== String(current.id)) {
                        cache[cacheKey] = false;
                        return false;
                    }
                }
                break;
            }
            if (current.parentId === null || current.parentId === undefined) break;
            current = clusterIndex.sourceInfo(current.parentId);
            guard++;
        }
    }
    cache[cacheKey] = ancestorId !== null;
    return cache[cacheKey];
}

function _plannerBundleSourceIsInlineAnchorRoot(source) {
    if (!source) return false;
    if (String(source.anchoredPosition || "").toUpperCase() === "INLINE_POSITION") return true;
    return typeof _isInlineFlowItemBySourceInfo === "function"
            && _isInlineFlowItemBySourceInfo(source);
}

function _plannerBundleInlineAnchorRootHasCompositeLayout(rootId, clusterIndex) {
    if (!clusterIndex || !clusterIndex.descendantSourceObjectIds || !clusterIndex.sourceInfo) return false;
    var cache = _plannerBundleCache(clusterIndex, "inlineAnchorCompositeLayoutByRoot");
    var cacheKey = String(rootId);
    if (cache[cacheKey] !== undefined) return cache[cacheKey];
    var descendants = _plannerBundleDescendantSourceObjectIds(clusterIndex, rootId);
    if (!descendants || descendants.length === 0) return false;
    var editableTextCount = 0;
    var visualCount = 0;
    for (var i = 0; i < descendants.length; i++) {
        var src = clusterIndex.sourceInfo(descendants[i]);
        if (!src) continue;
        var kind = String(src.kind || "");
        if (kind === "TextFrame" && src.textFrameClass === "editable" && src.hasText === true) {
            editableTextCount++;
            continue;
        }
        if (kind === "Rectangle" || kind === "Oval" || kind === "Polygon"
                || kind === "GraphicLine" || kind === "Group"
                || _plannerBundleIsPlacedContentKind(kind)) {
            visualCount++;
        }
    }
    cache[cacheKey] = editableTextCount > 1 && visualCount > 0;
    return cache[cacheKey];
}

function _plannerBundleIsInlineCompositeTextlessVectorDecoration(
        candidate, slot, sourceIds, inlineCompositeLayoutDescendant, clusterIndex) {
    if (!candidate || candidate.passId !== "pass.inline_objects") return false;
    if (slot !== "CONTENT_VISUAL_SLOT") return false;
    if (inlineCompositeLayoutDescendant !== true) return false;
    if (candidate.completePngTextAllowed === true || candidate.textOwner === "indesign_png") {
        return false;
    }
    if ((candidate.editableTextFrameIds && candidate.editableTextFrameIds.length > 0)
            || (candidate.hiddenTextFrameIds && candidate.hiddenTextFrameIds.length > 0)) {
        return false;
    }
    if (!sourceIds || sourceIds.length === 0 || !clusterIndex || !clusterIndex.sourceInfo) {
        return false;
    }
    for (var i = 0; i < sourceIds.length; i++) {
        var src = clusterIndex.sourceInfo(sourceIds[i]);
        if (!_plannerBundleSourceIsTextlessVectorDecoration(src, clusterIndex)) return false;
    }
    return true;
}

function _plannerBundleSourceIsTextlessVectorDecoration(source, clusterIndex) {
    if (!source) return false;
    var kind = String(source.kind || source.type || "");
    if (kind === "TextFrame" || kind === "Story" || kind === "Character"
            || kind === "InsertionPoint" || kind === "Cell") {
        return false;
    }
    if (_plannerBundleIsPlacedContentKind(kind)
            || source.hasPlacedVisual === true
            || source.hasPlacedVisualInSubtree === true) {
        return false;
    }
    if (kind === "Group" || source.hasChildren === true) {
        if (!clusterIndex || !clusterIndex.descendantSourceObjectIds || !clusterIndex.sourceInfo) {
            return false;
        }
        var cache = _plannerBundleCache(clusterIndex, "textlessVectorDecorationBySource");
        var cacheKey = String(source.id);
        if (cache[cacheKey] !== undefined) return cache[cacheKey];
        var descendants = _plannerBundleDescendantSourceObjectIds(clusterIndex, source.id);
        if (!descendants || descendants.length === 0) return false;
        for (var i = 0; i < descendants.length; i++) {
            if (String(descendants[i]) === String(source.id)) continue;
            var child = clusterIndex.sourceInfo(descendants[i]);
            if (!_plannerBundleSourceIsTextlessVectorDecorationLeaf(child)) {
                cache[cacheKey] = false;
                return false;
            }
        }
        cache[cacheKey] = true;
        return true;
    }
    return _plannerBundleSourceIsTextlessVectorDecorationLeaf(source);
}

function _plannerBundleSourceIsTextlessVectorDecorationLeaf(source) {
    if (!source) return false;
    var kind = String(source.kind || source.type || "");
    if (kind !== "Rectangle" && kind !== "Oval" && kind !== "Polygon"
            && kind !== "GraphicLine") {
        return false;
    }
    if (source.hasChildren === true
            || source.hasPlacedVisual === true
            || source.hasPlacedVisualInSubtree === true) {
        return false;
    }
    return true;
}

function _plannerBundleHasExecutableShellMaterial(slotSources, clusterIndex) {
    if (!slotSources) return false;
    if (slotSources.styleSourceObjectIds && slotSources.styleSourceObjectIds.length > 0) return true;
    var ids = slotSources.visualSourceObjectIds || [];
    for (var i = 0; i < ids.length; i++) {
        if (_plannerBundleSourceHasExecutableVisualMaterial(ids[i], clusterIndex)) return true;
    }
    return false;
}

function _plannerBundleSourceHasExecutableVisualMaterial(sourceId, clusterIndex) {
    if (!clusterIndex || sourceId === null || sourceId === undefined) {
        return _plannerBundleSourceHasExecutableVisualMaterialInSubtree(sourceId, clusterIndex, {});
    }
    var cache = _plannerBundleCache(clusterIndex, "executableVisualMaterialBySource");
    var key = String(sourceId);
    if (cache[key] !== undefined) return cache[key];
    cache[key] = _plannerBundleSourceHasExecutableVisualMaterialInSubtree(sourceId, clusterIndex, {});
    return cache[key];
}

function _plannerBundleSourceHasExecutableVisualMaterialInSubtree(sourceId, clusterIndex, visiting) {
    if (!clusterIndex || !clusterIndex.sourceInfo) return false;
    if (sourceId === null || sourceId === undefined) return false;
    var key = String(sourceId);
    visiting = visiting || {};
    if (visiting[key]) return false;
    visiting[key] = true;
    var source = clusterIndex.sourceInfo(sourceId);
    if (!source) return false;
    var kind = String(source.kind || "");
    if (_plannerBundleIsPlacedContentKind(kind)) return true;
    if (source.hasPlacedVisual === true || source.hasPlacedVisualInSubtree === true) return true;
    if (kind === "Rectangle" || kind === "Oval" || kind === "Polygon"
            || kind === "GraphicLine" || kind === "TextFrame") {
        return _plannerBundleSourceHasPaint(source);
    }
    if (kind === "Group") {
        if (_plannerBundleSourceHasPaint(source)) return true;
        var children = clusterIndex.childIdsByParentId
                ? (clusterIndex.childIdsByParentId[key] || [])
                : [];
        for (var ci = 0; ci < children.length; ci++) {
            if (_plannerBundleSourceHasExecutableVisualMaterialInSubtree(
                    children[ci], clusterIndex, visiting)) {
                return true;
            }
        }
        return false;
    }
    return false;
}

function _plannerBundleWithoutOwnedTextVisualSources(slot, slotSources, clusterIndex) {
    if (slot !== "SHELL_SLOT" || !slotSources) return slotSources;
    if (!slotSources.ownedTextFrameIds || slotSources.ownedTextFrameIds.length === 0) return slotSources;
    var removableOwnedTextIds = _plannerBundleOwnedTextVisualSourceIdsToPrune(
            slotSources.ownedTextFrameIds, clusterIndex);
    if (!removableOwnedTextIds || removableOwnedTextIds.length === 0) return slotSources;
    return {
        visualSourceObjectIds: _plannerBundleSourceIdsMinus(
                slotSources.visualSourceObjectIds || [], removableOwnedTextIds),
        styleSourceObjectIds: _sortedNumericIds(slotSources.styleSourceObjectIds || []),
        ownedTextFrameIds: _sortedNumericIds(slotSources.ownedTextFrameIds || [])
    };
}

function _plannerBundleOwnedTextVisualSourceIdsToPrune(ownedTextFrameIds, clusterIndex) {
    var out = [];
    var seen = {};
    for (var i = 0; ownedTextFrameIds && i < ownedTextFrameIds.length; i++) {
        var id = Number(ownedTextFrameIds[i]);
        if (isNaN(id)) continue;
        var src = clusterIndex && clusterIndex.sourceInfo
                ? clusterIndex.sourceInfo(id)
                : null;
        if (_plannerBundleOwnedTextMustRemainShellVisualSource(src)) continue;
        _pushUniqueId(out, seen, id);
    }
    return _sortedNumericIds(out);
}

function _plannerBundleOwnedTextMustRemainShellVisualSource(src) {
    if (!src || src.kind !== "TextFrame") return false;
    if (_plannerBundleIsTableOnlyCarrierTextFrame(src)) return false;
    return _plannerBundleHasTextFrameShellStyle(src) === true;
}

function _plannerBundleWithoutPlacedContentBranches(candidate, slot, slotSources, clusterIndex) {
    if (slot !== "SHELL_SLOT" || !slotSources) return slotSources;
    if (_plannerBundleAllowsClosedShellPlacedContentOwnership(candidate)
            || _plannerBundleKeepsClosedBackgroundPlacedContent(candidate)) {
        return slotSources;
    }
    var copy = {
        visualSourceObjectIds: _plannerBundlePrunePlacedContentBranches(
                candidate, slot, slotSources.visualSourceObjectIds || [], clusterIndex),
        styleSourceObjectIds: _sortedNumericIds(slotSources.styleSourceObjectIds || []),
        ownedTextFrameIds: _sortedNumericIds(slotSources.ownedTextFrameIds || [])
    };
    return copy;
}

function _plannerBundleWithoutClippedPlacedContentLeafSources(candidate, slot, slotSources, clusterIndex) {
    if (!slotSources) return slotSources;
    var copy = {
        visualSourceObjectIds: _plannerBundlePruneClippedPlacedContentLeafIds(
                candidate, slot, slotSources.visualSourceObjectIds || [], clusterIndex),
        styleSourceObjectIds: _sortedNumericIds(slotSources.styleSourceObjectIds || []),
        ownedTextFrameIds: _sortedNumericIds(slotSources.ownedTextFrameIds || [])
    };
    return copy;
}

function _plannerBundlePrunePlacedContentBranches(candidate, slot, ids, clusterIndex) {
    if (slot !== "SHELL_SLOT" || !ids || ids.length === 0
            || !clusterIndex || !clusterIndex.sourceInfo) {
        return _sortedNumericIds(ids || []);
    }
    if (_plannerBundleAllowsClosedShellPlacedContentOwnership(candidate)) {
        return _sortedNumericIds(ids || []);
    }
    var primary = candidate && candidate.primarySourceObjectId !== undefined
            ? candidate.primarySourceObjectId
            : null;
    var out = [];
    var seen = {};
    for (var i = 0; i < ids.length; i++) {
        var id = ids[i];
        if (_plannerBundleSourceIsInsidePlacedContentBranch(id, primary, clusterIndex)) {
            continue;
        }
        _pushUniqueId(out, seen, id);
    }
    return _sortedNumericIds(out);
}

function _plannerBundlePruneClippedPlacedContentLeafIds(candidate, slot, ids, clusterIndex) {
    if (slot !== "CONTENT_VISUAL_SLOT" || !candidate || !ids || ids.length === 0
            || !clusterIndex || !clusterIndex.sourceInfo) {
        return _sortedNumericIds(ids || []);
    }
    if (candidate.passId !== "pass.image_placed_frames") {
        return _sortedNumericIds(ids || []);
    }
    var idSet = _plannerBundleSourceSet(ids, clusterIndex);
    var out = [];
    var seen = {};
    var removed = false;
    for (var i = 0; i < ids.length; i++) {
        var id = ids[i];
        var src = clusterIndex.sourceInfo(id);
        if (_plannerBundleIsPlacedContentKind(src && src.kind)
                && _plannerBundleHasNonPlacedAncestorInSet(src, idSet, clusterIndex)) {
            removed = true;
            continue;
        }
        _pushUniqueId(out, seen, id);
    }
    if (!removed || out.length === 0) return _sortedNumericIds(ids || []);
    return _sortedNumericIds(out);
}

function _plannerBundleHasNonPlacedAncestorInSet(source, sourceSet, clusterIndex) {
    if (!source || !sourceSet || !clusterIndex || !clusterIndex.sourceInfo) return false;
    var current = source;
    var guard = 0;
    while (current && current.parentId !== null && current.parentId !== undefined && guard < 200) {
        var parentId = current.parentId;
        if (sourceSet[String(parentId)]) {
            var parent = clusterIndex.sourceInfo(parentId);
            return parent && !_plannerBundleIsPlacedContentKind(parent.kind);
        }
        current = clusterIndex.sourceInfo(parentId);
        guard++;
    }
    return false;
}

function _plannerBundleAllowsClosedShellPlacedContentOwnership(candidate) {
    if (!candidate) return false;
    if (candidate.passId !== "pass.decoration_groups") return false;
    if (candidate.candidatePurpose !== "SHELL_CANDIDATE") return false;
    if (candidate.compositeRole !== "textless_group_visual_slot"
            && candidate.slotRole !== "textless_group_visual_slot") return false;
    if (candidate.textOwner && candidate.textOwner !== "hwpx_tf") return false;
    return candidate.visualSourceObjectIds && candidate.visualSourceObjectIds.length > 1;
}

function _plannerBundleKeepsClosedBackgroundPlacedContent(candidate) {
    if (!candidate) return false;
    if (candidate.compositeRole !== "background_vector_source") return false;
    if (candidate.textOwner && candidate.textOwner !== "none") return false;
    if (candidate.ownedTextFrameIds && candidate.ownedTextFrameIds.length > 0) return false;
    if (candidate.editableTextFrameIds && candidate.editableTextFrameIds.length > 0) return false;
    if (candidate.hiddenTextFrameIds && candidate.hiddenTextFrameIds.length > 0) return false;
    return true;
}

function _plannerBundleSourceIsInsidePlacedContentBranch(sourceId, rootSourceId, clusterIndex) {
    if (sourceId === null || sourceId === undefined
            || rootSourceId === null || rootSourceId === undefined
            || !clusterIndex || !clusterIndex.sourceInfo) {
        return false;
    }
    var current = clusterIndex.sourceInfo(sourceId);
    for (var depth = 0; depth < 64 && current; depth++) {
        if (String(current.id) === String(rootSourceId)) return false;
        if (_plannerBundleSourceHasPlacedContentBranch(current.id, clusterIndex)) return true;
        if (current.parentId === null || current.parentId === undefined) return false;
        current = clusterIndex.sourceInfo(current.parentId);
    }
    return false;
}

function _plannerBundleSourceHasPlacedContentBranch(sourceId, clusterIndex, visiting) {
    if (sourceId === null || sourceId === undefined || !clusterIndex || !clusterIndex.sourceInfo) {
        return false;
    }
    var key = String(sourceId);
    if (!visiting) {
        var cache = _plannerBundleCache(clusterIndex, "placedContentBranchBySource");
        if (cache[key] !== undefined) return cache[key];
        var cachedResult = _plannerBundleSourceHasPlacedContentBranch(sourceId, clusterIndex, {});
        cache[key] = cachedResult;
        return cachedResult;
    }
    visiting = visiting || {};
    if (visiting[key]) return false;
    visiting[key] = true;
    var source = clusterIndex.sourceInfo(sourceId);
    if (!source) return false;
    var kind = String(source.kind || "");
    if (kind === "Image" || kind === "PDF") return true;
    if (source.hasPlacedVisual === true || source.hasPlacedVisualInSubtree === true) return true;
    var children = clusterIndex.childIdsByParentId
            ? (clusterIndex.childIdsByParentId[key] || [])
            : [];
    for (var ci = 0; ci < children.length; ci++) {
        if (_plannerBundleSourceHasPlacedContentBranch(children[ci], clusterIndex, visiting)) {
            return true;
        }
    }
    return false;
}

function _plannerBundleIsPageRootTextlessVisualPlane(candidate) {
    if (!candidate) return false;
    return candidate.slotRole === "page_root_textless_visual_plane"
            || candidate.compositeRole === "page_root_textless_visual_plane"
            || candidate.slotRole === "page_root_textless_visual_group"
            || candidate.compositeRole === "page_root_textless_visual_group"
            || candidate.kind === "PageRootTextlessVisualPlane"
            || candidate.kind === "PageRootTextlessVisualGroup";
}

function _plannerBundleSourceIdsUnion(a, b) {
    var ids = [];
    var seen = {};
    for (var ai = 0; a && ai < a.length; ai++) _pushUniqueId(ids, seen, a[ai]);
    for (var bi = 0; b && bi < b.length; bi++) _pushUniqueId(ids, seen, b[bi]);
    return _sortedNumericIds(ids);
}

function _plannerBundleSourceIdsMinus(sourceIds, removedIds) {
    var removed = _sourceSetMembership(removedIds || []);
    var ids = [];
    var seen = {};
    for (var i = 0; sourceIds && i < sourceIds.length; i++) {
        if (removed[String(sourceIds[i])]) continue;
        _pushUniqueId(ids, seen, sourceIds[i]);
    }
    return _sortedNumericIds(ids);
}

function _plannerBundleSourceIdsIntersect(sourceIds, allowedIds) {
    var allowed = _sourceSetMembership(allowedIds || []);
    var ids = [];
    var seen = {};
    for (var i = 0; sourceIds && i < sourceIds.length; i++) {
        if (!allowed[String(sourceIds[i])]) continue;
        _pushUniqueId(ids, seen, sourceIds[i]);
    }
    return _sortedNumericIds(ids);
}

function _plannerBundleWithCompletedVisibleFragmentContract(candidate, slot, clusterRelation, clusterProfile, slotSources) {
    if (!_plannerBundleShouldCompleteVisibleFragmentContract(
            candidate, slot, clusterRelation, clusterProfile, slotSources)) {
        return candidate || {};
    }
    var copy = {};
    for (var key in candidate) {
        if (candidate.hasOwnProperty(key)) copy[key] = candidate[key];
    }
    copy.exportSourceObjectIds = candidate.exportSourceObjectIds
            && candidate.exportSourceObjectIds.length > 0
            ? _sortedNumericIds(candidate.exportSourceObjectIds || [])
            : _sortedNumericIds(slotSources.visualSourceObjectIds || []);
    copy.hiddenVisualSourceObjectIds = _sortedNumericIds(clusterProfile.omittedClusterSourceObjectIds || []);
    copy.mode = "SLOT_ONLY";
    if (slot === "SHELL_SLOT") copy.slotRole = copy.slotRole || "shell_slot_only";
    return copy;
}

function _plannerBundleShouldCompleteVisibleFragmentContract(candidate, slot, clusterRelation, clusterProfile, slotSources) {
    if (!candidate) return false;
    if (_plannerBundleIsPageRootTextlessVisualPlane(candidate)) return false;
    if (clusterRelation !== "BUNDLE_NARROWER_THAN_CLUSTER") return false;
    if (slot !== "SHELL_SLOT" && slot !== "CONTENT_VISUAL_SLOT") return false;
    if (candidate.hiddenVisualSourceObjectIds && candidate.hiddenVisualSourceObjectIds.length > 0) return false;
    if (!clusterProfile || !clusterProfile.omittedClusterSourceObjectIds
            || clusterProfile.omittedClusterSourceObjectIds.length === 0) return false;
    if (!slotSources || !slotSources.visualSourceObjectIds
            || slotSources.visualSourceObjectIds.length === 0) return false;
    if (candidate.exportSourceObjectIds && candidate.exportSourceObjectIds.length > 0
            && !_plannerBundleSourceSetContainsAll(
                    candidate.exportSourceObjectIds || [],
                    slotSources.visualSourceObjectIds || [],
                    null)
            && !_plannerBundleAllowsRootExportVisibleFragmentContract(candidate, slot, clusterProfile)) {
        return false;
    }
    if (slot === "SHELL_SLOT") {
        if (clusterProfile.clusterHasEditableText === true
                && (!slotSources.ownedTextFrameIds || slotSources.ownedTextFrameIds.length === 0)) {
            return false;
        }
        return true;
    }
    if (clusterProfile.clusterHasEditableText === true || clusterProfile.clusterHasTextFrame === true) return false;
    return true;
}

function _plannerBundleAllowsRootExportVisibleFragmentContract(candidate, slot, clusterProfile) {
    if (!candidate) return false;
    if (candidate.passId !== "pass.complex_graphic_frames") return false;
    if (slot !== "SHELL_SLOT") return false;
    if (candidate.slotRole !== "background_shell_slot"
            && candidate.compositeRole !== "background_vector_source"
            && candidate.compositeRole !== "complex_graphic_source_set") return false;
    if (candidate.exportTargetObjectId === null || candidate.exportTargetObjectId === undefined) return false;
    if (!candidate.exportSourceObjectIds || candidate.exportSourceObjectIds.length === 0) return false;
    if (!clusterProfile) return false;
    if (clusterProfile.clusterHasEditableText === true || clusterProfile.clusterHasTextFrame === true) return false;
    return true;
}

function _plannerBundleShouldFillMissingSlotOnlyExport(candidate, exportSourceObjectIds, slotSources) {
    if (!candidate) return false;
    if (candidate.slotRole !== "shell_slot_only" && candidate.mode !== "SLOT_ONLY") return false;
    if (exportSourceObjectIds && exportSourceObjectIds.length > 0) return false;
    if (!candidate.hiddenVisualSourceObjectIds || candidate.hiddenVisualSourceObjectIds.length === 0) return false;
    return slotSources && slotSources.visualSourceObjectIds
            && slotSources.visualSourceObjectIds.length > 0;
}

function _plannerBundleShouldExportInlineSimpleMarkerCompletePng(
        candidate, slot, slotSources, sourceIds, exportSourceObjectIds, clusterIndex) {
    if (!candidate || candidate.passId !== "pass.inline_objects") return false;
    if (slot !== "CONTENT_VISUAL_SLOT") return false;
    if (candidate.slotRole === "direct_child_shell_slot"
            || candidate.compositeRole === "direct_child_shell_slot") return false;
    if (candidate.completePngTextAllowed === true || candidate.textOwner === "indesign_png") {
        return true;
    }
    if (exportSourceObjectIds && exportSourceObjectIds.length > 0) return false;
    if (!slotSources || !slotSources.ownedTextFrameIds
            || slotSources.ownedTextFrameIds.length === 0) return false;
    if (!sourceIds || sourceIds.length < 2) return false;
    for (var i = 0; i < slotSources.ownedTextFrameIds.length; i++) {
        var src = clusterIndex && clusterIndex.sourceInfo
                ? clusterIndex.sourceInfo(slotSources.ownedTextFrameIds[i])
                : null;
        if (!src || src.kind !== "TextFrame") return false;
        if (src.simpleMarkerLabelContents !== true) return false;
    }
    return true;
}

function _plannerBundleShouldKeepShellVisualExportSources(slot, exportSourceObjectIds, slotSources) {
    if (slot !== "SHELL_SLOT" || !slotSources) return false;
    if (!slotSources.visualSourceObjectIds || slotSources.visualSourceObjectIds.length === 0) return false;
    if (exportSourceObjectIds && exportSourceObjectIds.length > 0) return false;
    if ((!slotSources.ownedTextFrameIds || slotSources.ownedTextFrameIds.length === 0)
            && (!slotSources.styleSourceObjectIds || slotSources.styleSourceObjectIds.length === 0)) {
        return false;
    }
    return true;
}

function _plannerBundleShouldUseAppliedMasterVisualSourcesAsExport(
        candidate, slot, exportSourceObjectIds, slotSources) {
    if (!candidate || candidate.passId !== "pass.master_page_graphics") return false;
    if (slot !== "SHELL_SLOT") return false;
    if (exportSourceObjectIds && exportSourceObjectIds.length > 0) return false;
    return slotSources && slotSources.visualSourceObjectIds
            && slotSources.visualSourceObjectIds.length > 0;
}

function _plannerBundleShouldUseBackgroundShellCompleteSourceSetAsExport(
        candidate, slot, slotSources, exportSourceObjectIds, clusterIndex) {
    if (!candidate || slot !== "SHELL_SLOT" || !slotSources) return false;
    if (candidate.passId !== "pass.decoration_groups") return false;
    if (candidate.slotRole !== "background_shell_slot"
            && candidate.compositeRole !== "background_vector_source") return false;
    if (_plannerBundlePolicyLayer(candidate, slot, clusterIndex) !== "BACKGROUND") return false;
    if (candidate.ownedTextFrameIds && candidate.ownedTextFrameIds.length > 0) return false;
    if (candidate.editableTextFrameIds && candidate.editableTextFrameIds.length > 0) return false;
    if (candidate.hiddenTextFrameIds && candidate.hiddenTextFrameIds.length > 0) return false;
    if (slotSources.ownedTextFrameIds && slotSources.ownedTextFrameIds.length > 0) return false;
    if (slotSources.styleSourceObjectIds && slotSources.styleSourceObjectIds.length > 0) return false;
    if (!slotSources.visualSourceObjectIds || slotSources.visualSourceObjectIds.length < 2) return false;
    if (exportSourceObjectIds && exportSourceObjectIds.length > 1) return false;
    var leafIds = _plannerBundleTextlessVectorLeafSourceIds(
            slotSources.visualSourceObjectIds || [], clusterIndex);
    return leafIds.length >= 8;
}

function _plannerBundleShouldRestoreBackgroundShellRootExport(
        candidate, slot, exportSourceObjectIds, declaredExportSourceObjectIds) {
    if (!candidate || slot !== "SHELL_SLOT") return false;
    if (candidate.passId !== "pass.decoration_groups") return false;
    if (candidate.slotRole !== "background_shell_slot"
            && candidate.compositeRole !== "background_vector_source") return false;
    if (exportSourceObjectIds && exportSourceObjectIds.length > 0) return false;
    return declaredExportSourceObjectIds && declaredExportSourceObjectIds.length > 0;
}

function _plannerBundleIsExecutableAppliedMasterShell(candidate, slotSources) {
    if (!candidate || candidate.passId !== "pass.master_page_graphics") return false;
    return slotSources && slotSources.visualSourceObjectIds
            && slotSources.visualSourceObjectIds.length > 0;
}

function _plannerBundleWithInferredSlotContract(candidate, slot, sourceIds, clusterRelation, clusterProfile) {
    if (!_plannerBundleShouldInferDecorationFragmentSlotContract(
            candidate, slot, sourceIds, clusterRelation, clusterProfile)) {
        return candidate || {};
    }
    var copy = {};
    for (var key in candidate) {
        if (candidate.hasOwnProperty(key)) copy[key] = candidate[key];
    }
    copy.exportSourceObjectIds = _sortedNumericIds(sourceIds || []);
    copy.hiddenVisualSourceObjectIds = _sortedNumericIds(clusterProfile.omittedClusterSourceObjectIds || []);
    copy.slotRole = copy.slotRole || "shell_slot_only";
    copy.mode = "SLOT_ONLY";
    return copy;
}

function _plannerBundleShouldInferDecorationFragmentSlotContract(
        candidate, slot, sourceIds, clusterRelation, clusterProfile) {
    if (!candidate) return false;
    var canDeclareDecorationFragment = candidate.passId === "pass.decoration_groups"
            || (candidate.passId === "pass.vector_shape_frames" && slot === "SHELL_SLOT");
    if (!canDeclareDecorationFragment) return false;
    if (slot !== "SHELL_SLOT") return false;
    if (clusterRelation !== "BUNDLE_NARROWER_THAN_CLUSTER") return false;
    if (!sourceIds || sourceIds.length === 0) return false;
    if (candidate.exportSourceObjectIds && candidate.exportSourceObjectIds.length > 0) return false;
    if (candidate.hiddenVisualSourceObjectIds && candidate.hiddenVisualSourceObjectIds.length > 0) return false;
    if (!clusterProfile || !clusterProfile.omittedClusterSourceObjectIds
            || clusterProfile.omittedClusterSourceObjectIds.length === 0) return false;
    if (clusterProfile.clusterHasEditableText === true) return false;
    if (clusterProfile.clusterHasTextFrame === true) return false;
    if (clusterProfile.clusterHasPlacedContent === true) return false;
    return true;
}

function _plannerBundleClusterProfile(clusterSourceObjectIds, sourceIds, pageIndex, clusterIndex) {
    var sourceSet = _plannerBundleSourceSet(sourceIds || [], clusterIndex);
    var profile = {
        clusterKindCounts: {},
        omittedClusterSourceObjectIds: [],
        omittedClusterKindCounts: {},
        clusterHasEditableText: false,
        clusterHasTextFrame: false,
        clusterHasPlacedContent: false,
        clusterHasVisualSource: false
    };
    if (!clusterSourceObjectIds || !clusterIndex || !clusterIndex.sourceInfo) return profile;

    var omittedSeen = {};
    for (var i = 0; i < clusterSourceObjectIds.length; i++) {
        var id = clusterSourceObjectIds[i];
        var src = clusterIndex.sourceInfo(id);
        var kind = src && src.kind ? src.kind : "Unknown";
        _incrementPlannerSummary(profile.clusterKindCounts, kind);
        if (_plannerBundleIsPlacedContentKind(kind)) profile.clusterHasPlacedContent = true;
        var isSamePage = src
                && (pageIndex === null || pageIndex === undefined || src.pageIndex === pageIndex);
        if (kind === "TextFrame" && isSamePage) {
            profile.clusterHasTextFrame = true;
            if (src && src.textFrameClass === "editable" && src.hasText === true) {
                profile.clusterHasEditableText = true;
            }
        } else if (kind !== "Story" && kind !== "Character" && kind !== "InsertionPoint" && kind !== "Cell") {
            profile.clusterHasVisualSource = true;
        }

        if (!sourceSet[String(id)]) {
            _pushUniqueId(profile.omittedClusterSourceObjectIds, omittedSeen, id);
            _incrementPlannerSummary(profile.omittedClusterKindCounts, kind);
        }
    }
    profile.omittedClusterSourceObjectIds = _sortedNumericIds(profile.omittedClusterSourceObjectIds);
    return profile;
}

function _plannerBundleZOrder(candidate, primarySourceObjectId, clusterIndex) {
    if (candidate && candidate.zOrder !== null && candidate.zOrder !== undefined) {
        return candidate.zOrder;
    }
    var src = clusterIndex && clusterIndex.sourceInfo && primarySourceObjectId !== null
            && primarySourceObjectId !== undefined
            ? clusterIndex.sourceInfo(primarySourceObjectId)
            : null;
    if (src && src.zOrder !== null && src.zOrder !== undefined) return src.zOrder;
    return null;
}

function _plannerBundleSlotSources(candidate, slot, sourceIds, clusterIndex) {
    var explicitVisualIds = candidate.visualSourceObjectIds || null;
    var explicitStyleIds = candidate.styleSourceObjectIds || null;
    var explicitOwnedTextIds = _plannerBundleDeclaredOwnedTextFrameIds(candidate, clusterIndex);
    var hiddenVisualIdSet = _plannerBundleClosedImageTextlessGroupHiddenIdSet(
            candidate, slot, candidate.hiddenVisualSourceObjectIds || [], clusterIndex);
    var visualSourceObjectIds = [];
    var styleSourceObjectIds = [];
    var ownedTextFrameIds = [];
    var allowInlineAnchorVisualSource = _plannerBundleAllowsInlineAnchorVisualSource(
            candidate, slot);
    if (_plannerBundleIsClosedImageTextlessGroup(candidate, slot)) {
        visualSourceObjectIds = _plannerBundleNonTextSourceIds(
                sourceIds, hiddenVisualIdSet, clusterIndex);
    } else if (_plannerBundleAllowsClosedShellPlacedContentOwnership(candidate)
            && clusterIndex
            && clusterIndex.descendantSourceObjectIds
            && candidate.primarySourceObjectId !== null
            && candidate.primarySourceObjectId !== undefined) {
        var closedShellSourceIds = _plannerBundleDescendantSourceObjectIds(
                clusterIndex, candidate.primarySourceObjectId);
        visualSourceObjectIds = _plannerBundleNonTextSourceIds(
                closedShellSourceIds, hiddenVisualIdSet, clusterIndex);
    } else if (explicitVisualIds && explicitVisualIds.length > 0) {
        visualSourceObjectIds = allowInlineAnchorVisualSource
                ? _sortedNumericIds(explicitVisualIds)
                : _plannerBundleSourceIdsWithoutInlineAnchorDescendants(
                        explicitVisualIds, clusterIndex);
        if (visualSourceObjectIds.length === 0
                && candidate.passId === "pass.editable_textframe_visual_shells") {
            visualSourceObjectIds = _plannerBundleTextFrameShellSourceIds(
                    explicitVisualIds, clusterIndex);
        }
    } else {
        var visualBase = candidate.exportSourceObjectIds && candidate.exportSourceObjectIds.length > 0
                ? candidate.exportSourceObjectIds
                : sourceIds;
        if (_plannerBundleShouldUseClosedPlacedContentFrame(candidate, slot, visualBase, clusterIndex)) {
            visualBase = _plannerBundleDescendantSourceObjectIds(
                    clusterIndex, candidate.primarySourceObjectId);
        }
        visualSourceObjectIds = _plannerBundleNonTextSourceIds(visualBase, hiddenVisualIdSet, clusterIndex);
        if (visualSourceObjectIds.length === 0
                && candidate.passId === "pass.editable_textframe_visual_shells") {
            visualSourceObjectIds = _plannerBundleTextFrameShellSourceIds(visualBase, clusterIndex);
        }
    }

    var allowInlineAnchorStyleSource = _plannerBundleAllowsInlineAnchorStyleSource(
            candidate, slot, clusterIndex);
    if (explicitStyleIds && explicitStyleIds.length > 0) {
        styleSourceObjectIds = allowInlineAnchorStyleSource
                ? _sortedNumericIds(explicitStyleIds)
                : _plannerBundleSourceIdsWithoutInlineAnchorDescendants(
                        explicitStyleIds, clusterIndex);
    } else if (slot === "SHELL_SLOT" || slot === "TABLE_STYLE_SLOT") {
        styleSourceObjectIds = _plannerBundleTextFrameStyleSourceIds(
                sourceIds, clusterIndex, allowInlineAnchorStyleSource);
    }

    if (explicitOwnedTextIds && explicitOwnedTextIds.length > 0) {
        ownedTextFrameIds = _sortedNumericIds(explicitOwnedTextIds);
    } else if (slot === "SHELL_SLOT") {
        ownedTextFrameIds = _plannerBundleEditableTextFrameIds(sourceIds, clusterIndex, hiddenVisualIdSet);
    }
    if (candidate && candidate.passId === "pass.inline_objects"
            && slot === "CONTENT_VISUAL_SLOT"
            && ownedTextFrameIds.length > 0
            && _plannerBundleTextFramesAreSimpleMarkers(ownedTextFrameIds, clusterIndex)
            && (!visualSourceObjectIds || visualSourceObjectIds.length === 0)) {
        visualSourceObjectIds = _plannerBundleNonTextSourceIds(sourceIds, {}, clusterIndex);
    }

    return {
        visualSourceObjectIds: visualSourceObjectIds,
        styleSourceObjectIds: styleSourceObjectIds,
        ownedTextFrameIds: ownedTextFrameIds
    };
}

function _plannerBundleIsClosedImageTextlessGroup(candidate, slot) {
    if (!candidate || slot !== "CONTENT_VISUAL_SLOT") return false;
    if (candidate.passId !== "pass.image_textless_groups") return false;
    if (candidate.unit && candidate.unit !== "GROUP") return false;
    if (candidate.mode && candidate.mode !== "TEXTLESS_CANDIDATE") return false;
    return candidate.compositeRole === "image_group_textless_source_set"
            || candidate.slotRole === "image_group_textless_source_set"
            || candidate.kind === "Group";
}

function _plannerBundleClosedImageTextlessGroupHiddenIdSet(
        candidate, slot, hiddenVisualSourceObjectIds, clusterIndex) {
    if (!_plannerBundleIsClosedImageTextlessGroup(candidate, slot)) {
        return _sourceIdSet(hiddenVisualSourceObjectIds || []);
    }
    var kept = [];
    var seen = {};
    for (var i = 0; hiddenVisualSourceObjectIds && i < hiddenVisualSourceObjectIds.length; i++) {
        var id = hiddenVisualSourceObjectIds[i];
        var src = clusterIndex && clusterIndex.sourceInfo ? clusterIndex.sourceInfo(id) : null;
        var kind = String(src && src.kind || "");
        if (kind === "TextFrame" || kind === "Story" || kind === "Character"
                || kind === "InsertionPoint" || kind === "Cell"
                || _plannerBundleSourceHasInlineAnchorAncestor(id, clusterIndex)) {
            _pushUniqueId(kept, seen, id);
        }
    }
    return _sourceIdSet(kept);
}

function _plannerBundlePruneClosedImageTextlessGroupHiddenIds(
        candidate, slot, hiddenVisualSourceObjectIds, clusterIndex) {
    if (!_plannerBundleIsClosedImageTextlessGroup(candidate, slot)) {
        return _sortedNumericIds(hiddenVisualSourceObjectIds || []);
    }
    var kept = [];
    var seen = {};
    for (var i = 0; hiddenVisualSourceObjectIds && i < hiddenVisualSourceObjectIds.length; i++) {
        var id = hiddenVisualSourceObjectIds[i];
        var src = clusterIndex && clusterIndex.sourceInfo ? clusterIndex.sourceInfo(id) : null;
        var kind = String(src && src.kind || "");
        if (kind === "TextFrame" || kind === "Story" || kind === "Character"
                || kind === "InsertionPoint" || kind === "Cell"
                || _plannerBundleSourceHasInlineAnchorAncestor(id, clusterIndex)) {
            _pushUniqueId(kept, seen, id);
        }
    }
    return _sortedNumericIds(kept);
}

function _plannerBundleRestoreHiddenTextShellMaterialExportSources(
        candidate, slot, slotSources, exportSourceObjectIds,
        hiddenVisualSourceObjectIds, clusterIndex) {
    if (slot !== "SHELL_SLOT") {
        return {
            exportSourceObjectIds: _sortedNumericIds(exportSourceObjectIds || []),
            hiddenVisualSourceObjectIds: _sortedNumericIds(hiddenVisualSourceObjectIds || []),
            restoredSourceObjectIds: []
        };
    }
    var restored = [];
    var restoredSeen = {};
    for (var i = 0; hiddenVisualSourceObjectIds && i < hiddenVisualSourceObjectIds.length; i++) {
        var sourceId = hiddenVisualSourceObjectIds[i];
        var src = clusterIndex && clusterIndex.sourceInfo
                ? clusterIndex.sourceInfo(sourceId)
                : null;
        if (!_plannerBundleSourceIsExportableTextShellMaterial(src)) continue;
        _pushUniqueId(restored, restoredSeen, sourceId);
    }
    if (restored.length === 0) {
        return {
            exportSourceObjectIds: _sortedNumericIds(exportSourceObjectIds || []),
            hiddenVisualSourceObjectIds: _sortedNumericIds(hiddenVisualSourceObjectIds || []),
            restoredSourceObjectIds: []
        };
    }
    return {
        exportSourceObjectIds: _plannerBundleSourceIdsUnion(
                exportSourceObjectIds || [], restored),
        hiddenVisualSourceObjectIds: _plannerBundleSourceIdsMinus(
                hiddenVisualSourceObjectIds || [], restored),
        restoredSourceObjectIds: _sortedNumericIds(restored)
    };
}

function _plannerBundleSourceIsExportableTextShellMaterial(src) {
    if (!src) return false;
    if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) return false;
    if (src.sourceHidden === true || src.hiddenByParent === true) return false;
    var opacity = src.opacity !== null && src.opacity !== undefined ? Number(src.opacity) : 100;
    if (!isNaN(opacity) && opacity <= 0.01) return false;
    var kind = String(src.kind || src.type || src.itemType || "");
    if (kind === "TextFrame" || kind === "Image" || kind === "PDF" || kind === "EPS") return false;
    if (src.hasPlacedVisual === true) return false;
    var hasFill = _plannerBundleSourceHasVisibleNonPaperFill(src);
    var hasStroke = _plannerBundleSourceHasVisibleNonPaperStroke(src);
    if (kind === "Rectangle" || kind === "Oval" || kind === "Polygon" || kind === "Group") {
        return hasFill || hasStroke;
    }
    if (kind === "GraphicLine") return hasStroke;
    return false;
}

function _plannerBundleSourceHasVisibleNonPaperFill(src) {
    var fill = String(src && (src.fillColorName || src.fillColor) || "");
    if (!fill && src && src.hasVisibleFill === true) return true;
    return !_plannerBundleColorIsNone(fill) && !_plannerBundleColorIsPaper(fill);
}

function _plannerBundleSourceHasVisibleNonPaperStroke(src) {
    var stroke = String(src && (src.strokeColorName || src.strokeColor) || "");
    var weight = Number(src && src.strokeWeight || 0);
    if (!stroke && src && src.hasVisibleStroke === true) return true;
    return weight > 0
            && !_plannerBundleColorIsNone(stroke)
            && !_plannerBundleColorIsPaper(stroke);
}

function _plannerBundleColorIsNone(colorName) {
    if (!colorName) return true;
    return colorName === "None" || colorName === "[None]";
}

function _plannerBundleColorIsPaper(colorName) {
    return colorName === "Paper" || colorName === "[Paper]" || colorName === "White";
}

function _plannerBundleTextFramesAreSimpleMarkers(textFrameIds, clusterIndex) {
    if (!textFrameIds || textFrameIds.length === 0) return false;
    for (var i = 0; i < textFrameIds.length; i++) {
        var src = clusterIndex && clusterIndex.sourceInfo
                ? clusterIndex.sourceInfo(textFrameIds[i])
                : null;
        if (!src || src.kind !== "TextFrame") return false;
        if (src.simpleMarkerLabelContents !== true) return false;
    }
    return true;
}

function _plannerBundleDeclaredOwnedTextFrameIds(candidate, clusterIndex) {
    var ids = [];
    var seen = {};
    if (_plannerBundleIsTextRangeDecorationShellCandidate(candidate)) {
        for (var di = 0; candidate.decoratedTextFrameIds && di < candidate.decoratedTextFrameIds.length; di++) {
            var decoratedId = candidate.decoratedTextFrameIds[di];
            var decoratedSource = clusterIndex && clusterIndex.sourceInfo
                    ? clusterIndex.sourceInfo(decoratedId)
                    : null;
            if (!decoratedSource || decoratedSource.kind !== "TextFrame") continue;
            if (decoratedSource.textFrameClass !== "editable") continue;
            _pushUniqueId(ids, seen, decoratedId);
        }
        return _sortedNumericIds(ids);
    }
    if (candidate && (candidate.compositeRole === "table_carrier_textless_shell"
            || candidate.compositeRole === "table_carrier_sibling_decoration"
            || candidate.tableDecorationRole === "table_carrier_sibling_decoration")) {
        return [];
    }
    if (candidate && candidate.passId === "pass.page_textless_graphic_groups") {
        for (var pi = 0; candidate.ownedTextFrameIds && pi < candidate.ownedTextFrameIds.length; pi++) {
            var pageGroupTextId = candidate.ownedTextFrameIds[pi];
            var pageGroupTextSource = clusterIndex && clusterIndex.sourceInfo
                    ? clusterIndex.sourceInfo(pageGroupTextId)
                    : null;
            if (!pageGroupTextSource || pageGroupTextSource.kind !== "TextFrame") continue;
            if (pageGroupTextSource.textFrameClass !== "editable") continue;
            if (pageGroupTextSource.hasText !== true) continue;
            if (pageGroupTextSource.simpleMarkerLabelContents !== true) continue;
            _pushUniqueId(ids, seen, pageGroupTextId);
        }
        return _sortedNumericIds(ids);
    }
    if (candidate && candidate.passId === "pass.inline_objects") {
        var inlineEditable = [];
        var inlineSeen = {};
        for (var ii = 0; candidate.sourceObjectIds && ii < candidate.sourceObjectIds.length; ii++) {
            var inlineId = candidate.sourceObjectIds[ii];
            var inlineSource = clusterIndex && clusterIndex.sourceInfo
                    ? clusterIndex.sourceInfo(inlineId)
                    : null;
            if (!inlineSource || inlineSource.kind !== "TextFrame") continue;
            if (inlineSource.textFrameClass !== "editable") continue;
            if (inlineSource.hasText !== true) continue;
            _pushUniqueId(inlineEditable, inlineSeen, inlineId);
        }
        if (inlineEditable.length > 0) {
            var allSimpleMarkers = true;
            for (var im = 0; im < inlineEditable.length; im++) {
                var markerSource = clusterIndex && clusterIndex.sourceInfo
                        ? clusterIndex.sourceInfo(inlineEditable[im])
                        : null;
                if (!markerSource || markerSource.simpleMarkerLabelContents !== true) {
                    allSimpleMarkers = false;
                    break;
                }
            }
            if (allSimpleMarkers) return _sortedNumericIds(inlineEditable);
        }
    }
    function addDeclared(sourceIds) {
        for (var i = 0; sourceIds && i < sourceIds.length; i++) {
            var id = sourceIds[i];
            var src = clusterIndex && clusterIndex.sourceInfo ? clusterIndex.sourceInfo(id) : null;
            if (!src || src.kind !== "TextFrame") continue;
            if (src.textFrameClass !== "editable") continue;
            if (src.hasText !== true) continue;
            if (candidate && candidate.compositeRole === "source_declared_closed_text_shell"
                    && _plannerBundleSourceIsInlineOwnedForParentShell(src)) {
                continue;
            }
            _pushUniqueId(ids, seen, id);
        }
    }
    addDeclared(candidate ? candidate.ownedTextFrameIds : null);
    addDeclared(candidate ? candidate.editableTextFrameIds : null);
    addDeclared(candidate ? candidate.hiddenTextFrameIds : null);
    return _sortedNumericIds(ids);
}

function _plannerBundleIsTextRangeDecorationShellCandidate(candidate) {
    if (!candidate) return false;
    if (candidate.textRangeDecorationShell !== true) return false;
    if (candidate.ownershipSlot !== "SHELL_SLOT") return false;
    if (!candidate.decoratedTextFrameIds || candidate.decoratedTextFrameIds.length === 0) return false;
    return candidate.containsEditableText !== true
            && candidate.completePngTextAllowed !== true;
}

function _plannerBundleSourceIsInlineOwnedForParentShell(src) {
    if (!src) return false;
    if (src.isInline === true || src.inline === true || src.anchored === true) return true;
    if (String(src.anchoredPosition || "").toUpperCase() === "INLINE_POSITION") return true;
    if (String(src.storyAnchorPlacement || "").toUpperCase() === "INLINE") return true;
    return _plannerBundleSourceIsInlineAnchorRoot(src);
}

function _plannerBundleSourceRootObjectIds(sourceIds, clusterIndex) {
    if (!sourceIds || sourceIds.length === 0) return [];
    if (!clusterIndex || !clusterIndex.sourceInfo) return _sortedNumericIds(sourceIds);
    var cache = _plannerBundleCache(clusterIndex, "rootObjectIdsBySourceSet");
    var cacheKey = _plannerBundleSourceSetKey(sourceIds, clusterIndex);
    if (cache[cacheKey]) return cache[cacheKey].slice(0);
    var sourceSet = _plannerBundleSourceSet(sourceIds, clusterIndex);
    var roots = [];
    for (var i = 0; i < sourceIds.length; i++) {
        var id = sourceIds[i];
        var src = clusterIndex.sourceInfo(id);
        var parentInSet = false;
        var guard = 0;
        while (src && src.parentId !== null && src.parentId !== undefined && guard < 200) {
            if (sourceSet[String(src.parentId)]) {
                parentInSet = true;
                break;
            }
            src = clusterIndex.sourceInfo(src.parentId);
            guard++;
        }
        if (!parentInSet) roots.push(id);
    }
    roots = _sortedNumericIds(roots);
    cache[cacheKey] = roots.slice(0);
    return roots;
}

function _plannerBundleTextlessVectorLeafSourceIds(sourceIds, clusterIndex) {
    if (!sourceIds || sourceIds.length === 0) return [];
    if (!clusterIndex || !clusterIndex.sourceInfo) return _sortedNumericIds(sourceIds);
    var cache = _plannerBundleCache(clusterIndex, "textlessVectorLeafObjectIdsBySourceSet");
    var cacheKey = _plannerBundleSourceSetKey(sourceIds, clusterIndex);
    if (cache[cacheKey]) return cache[cacheKey].slice(0);
    var sourceSet = _plannerBundleSourceSet(sourceIds, clusterIndex);
    var leaves = [];
    var seen = {};
    for (var i = 0; i < sourceIds.length; i++) {
        var id = sourceIds[i];
        var src = clusterIndex.sourceInfo(id);
        if (!_plannerBundleSourceIsTextlessVectorDecorationLeaf(src)) continue;
        var children = clusterIndex.childIdsByParentId
                ? (clusterIndex.childIdsByParentId[String(id)] || [])
                : [];
        var hasChildInSet = false;
        for (var ci = 0; ci < children.length; ci++) {
            if (sourceSet[String(children[ci])]) {
                hasChildInSet = true;
                break;
            }
        }
        if (!hasChildInSet) _pushUniqueId(leaves, seen, id);
    }
    leaves = _sortedNumericIds(leaves);
    cache[cacheKey] = leaves.slice(0);
    return leaves;
}

function _plannerBundleClusterSourceObjectIds(primarySourceObjectId, sourceRootObjectIds, clusterIndex) {
    if (!clusterIndex || !clusterIndex.descendantSourceObjectIds) {
        return [];
    }
    var roots = sourceRootObjectIds && sourceRootObjectIds.length > 0
            ? sourceRootObjectIds
            : (primarySourceObjectId !== null && primarySourceObjectId !== undefined ? [primarySourceObjectId] : []);
    var cache = _plannerBundleCache(clusterIndex, "clusterSourceIdsByRoots");
    var cacheKey = _plannerBundleSourceSetKey(roots, clusterIndex);
    if (cache[cacheKey]) return cache[cacheKey].slice(0);
    var out = [];
    var seen = {};
    for (var i = 0; i < roots.length; i++) {
        var descendants = _plannerBundleDescendantSourceObjectIds(clusterIndex, roots[i]);
        for (var j = 0; descendants && j < descendants.length; j++) {
            _pushUniqueId(out, seen, descendants[j]);
        }
    }
    out = _sortedNumericIds(out);
    cache[cacheKey] = out.slice(0);
    return out;
}

function _plannerBundleShouldUseClosedPlacedContentFrame(candidate, slot, sourceIds, clusterIndex) {
    if (!candidate || slot !== "CONTENT_VISUAL_SLOT") return false;
    if (candidate.passId !== "pass.image_placed_frames") return false;
    if (!sourceIds || sourceIds.length !== 1) return false;
    if (!clusterIndex || !clusterIndex.descendantSourceObjectIds || !clusterIndex.sourceInfo) return false;
    var primary = candidate.primarySourceObjectId;
    if (primary === null || primary === undefined) return false;
    var descendants = _plannerBundleDescendantSourceObjectIds(clusterIndex, primary);
    if (!descendants || descendants.length <= sourceIds.length) return false;
    for (var i = 0; i < descendants.length; i++) {
        var src = clusterIndex.sourceInfo(descendants[i]);
        if (_plannerBundleIsPlacedContentKind(src && src.kind)) return true;
    }
    return false;
}

function _plannerBundleIsPlacedContentKind(kind) {
    return kind === "Image" || kind === "PDF" || kind === "EPS";
}

function _plannerBundleNonTextSourceIds(sourceIds, hiddenVisualIdSet, clusterIndex) {
    var ids = [];
    if (!sourceIds) return ids;
    for (var i = 0; i < sourceIds.length; i++) {
        var id = sourceIds[i];
        if (hiddenVisualIdSet && hiddenVisualIdSet[String(id)]) continue;
        var src = clusterIndex && clusterIndex.sourceInfo ? clusterIndex.sourceInfo(id) : null;
        if (src && src.kind === "TextFrame" && !_plannerBundleHasTextFrameShellStyle(src)) continue;
        ids.push(id);
    }
    return _sortedNumericIds(ids);
}

function _plannerBundlePruneShellOwnedTextAndTableStructureSources(
        candidate, slot, slotSources, clusterIndex) {
    if (slot !== "SHELL_SLOT" || !slotSources) return slotSources;
    var disallowed = _plannerBundleShellIneligibleVisualSourceIds(
            candidate, slotSources, clusterIndex);
    if (!disallowed || disallowed.length === 0) return slotSources;
    return {
        visualSourceObjectIds: _plannerBundleSourceIdsMinus(
                slotSources.visualSourceObjectIds || [], disallowed),
        styleSourceObjectIds: _plannerBundleSourceIdsMinus(
                slotSources.styleSourceObjectIds || [], disallowed),
        ownedTextFrameIds: slotSources.ownedTextFrameIds || []
    };
}

function _plannerBundlePruneShellOwnedTextAndTableStructureExportSources(
        candidate, slot, slotSources, exportSourceObjectIds, clusterIndex) {
    if (slot !== "SHELL_SLOT") return exportSourceObjectIds;
    var disallowed = _plannerBundleShellIneligibleVisualSourceIds(
            candidate, slotSources, clusterIndex);
    if (!disallowed || disallowed.length === 0) return exportSourceObjectIds;
    return _plannerBundleSourceIdsMinus(exportSourceObjectIds || [], disallowed);
}

function _plannerBundleShellIneligibleVisualSourceIds(candidate, slotSources, clusterIndex) {
    var ids = [];
    var seen = {};
    function addId(id) {
        _pushUniqueId(ids, seen, id);
    }
    function addTableCarrierAndStructure(sourceId) {
        var src = clusterIndex && clusterIndex.sourceInfo
                ? clusterIndex.sourceInfo(sourceId)
                : null;
        if (!_plannerBundleIsTableOnlyCarrierTextFrame(src)) return;
        addId(sourceId);
        for (var i = 0; src.tableSourceObjectIds && i < src.tableSourceObjectIds.length; i++) {
            addId(src.tableSourceObjectIds[i]);
        }
    }

    for (var i = 0; slotSources && slotSources.ownedTextFrameIds && i < slotSources.ownedTextFrameIds.length; i++) {
        addId(slotSources.ownedTextFrameIds[i]);
        addTableCarrierAndStructure(slotSources.ownedTextFrameIds[i]);
    }
    for (var j = 0; candidate && candidate.sourceObjectIds && j < candidate.sourceObjectIds.length; j++) {
        addTableCarrierAndStructure(candidate.sourceObjectIds[j]);
    }
    return _sortedNumericIds(ids);
}

function _plannerBundleIsTableOnlyCarrierTextFrame(src) {
    if (!src || src.kind !== "TextFrame") return false;
    if (src.textFrameClass !== "editable") return false;
    if (src.hasTablesInStory !== true && Number(src.tableCountInStory || 0) <= 0) return false;
    if (src.hasText === true || Number(src.textLength || 0) > 0) return false;
    return src.markerOnlyContents !== false;
}

function _plannerBundleTextFrameShellSourceIds(sourceIds, clusterIndex) {
    var ids = [];
    if (!sourceIds || !clusterIndex || !clusterIndex.sourceInfo) return ids;
    for (var i = 0; i < sourceIds.length; i++) {
        var src = clusterIndex.sourceInfo(sourceIds[i]);
        if (!src || src.kind !== "TextFrame") continue;
        ids.push(sourceIds[i]);
    }
    return _sortedNumericIds(ids);
}

function _plannerBundleTextFrameStyleSourceIds(sourceIds, clusterIndex, allowInlineAnchorStyleSource) {
    var ids = [];
    if (!sourceIds || !clusterIndex || !clusterIndex.sourceInfo) return ids;
    for (var i = 0; i < sourceIds.length; i++) {
        var src = clusterIndex.sourceInfo(sourceIds[i]);
        if (!src || src.kind !== "TextFrame") continue;
        if (allowInlineAnchorStyleSource !== true
                && _plannerBundleSourceHasInlineAnchorAncestor(sourceIds[i], clusterIndex)) {
            continue;
        }
        if (!_plannerBundleHasTextFrameShellStyle(src)) continue;
        ids.push(sourceIds[i]);
    }
    return _sortedNumericIds(ids);
}

function _plannerBundleSourceHasInlineAnchorAncestorExcludingSelf(sourceId, clusterIndex) {
    if (sourceId === null || sourceId === undefined || !clusterIndex || !clusterIndex.sourceInfo) {
        return false;
    }
    var src = clusterIndex.sourceInfo(sourceId);
    if (!src || src.parentId === null || src.parentId === undefined) return false;
    return _plannerBundleSourceHasInlineAnchorAncestor(src.parentId, clusterIndex);
}

function _plannerBundleIsSelfInlineTextFrameShellCandidate(candidate, slot, clusterIndex) {
    if (!candidate || slot !== "SHELL_SLOT") return false;
    if (candidate.passId !== "pass.editable_textframe_visual_shells") return false;
    var ids = candidate.styleSourceObjectIds && candidate.styleSourceObjectIds.length > 0
            ? candidate.styleSourceObjectIds
            : candidate.sourceObjectIds;
    if (!ids || ids.length !== 1) return false;
    var sourceId = ids[0];
    var src = clusterIndex && clusterIndex.sourceInfo ? clusterIndex.sourceInfo(sourceId) : null;
    if (!src || src.kind !== "TextFrame") return false;
    if (_plannerBundleHasTextFrameShellStyle(src) !== true) return false;
    return _plannerBundleSourceHasInlineAnchorAncestorExcludingSelf(sourceId, clusterIndex) !== true
            && _plannerBundleSourceHasInlineAnchorAncestor(sourceId, clusterIndex) === true;
}

function _plannerBundleAllowsInlineAnchorStyleSource(candidate, slot, clusterIndex) {
    if (!candidate || slot !== "SHELL_SLOT") return false;
    if (_plannerBundleIsSelfInlineTextFrameShellCandidate(candidate, slot, clusterIndex)) {
        return true;
    }
    if (candidate.passId !== "pass.inline_objects") return false;
    return _plannerBundleInlineObjectIsTextShell(candidate);
}

function _plannerBundleAllowsInlineAnchorVisualSource(candidate, slot) {
    if (!candidate) return false;
    if (candidate.passId !== "pass.inline_objects") return false;
    if (slot === "SHELL_SLOT") {
        if ((!candidate.ownedTextFrameIds || candidate.ownedTextFrameIds.length === 0)
                && (!candidate.hiddenTextFrameIds || candidate.hiddenTextFrameIds.length === 0)
                && (!candidate.editableTextFrameIds || candidate.editableTextFrameIds.length === 0)) {
            return true;
        }
        return _plannerBundleInlineObjectIsTextShell(candidate);
    }
    if (slot === "CONTENT_VISUAL_SLOT"
            && candidate.textOwner === "indesign_png"
            && candidate.completePngTextAllowed === true) {
        return true;
    }
    if (slot === "CONTENT_VISUAL_SLOT"
            && (candidate.slotRole === "direct_child_shell_slot"
                || candidate.compositeRole === "direct_child_shell_slot")
            && candidate.textOwner === "indesign_png"
            && candidate.completePngTextAllowed === true) {
        return true;
    }
    return false;
}

function _plannerBundleSourceIdsWithoutInlineAnchorDescendants(sourceIds, clusterIndex) {
    var ids = [];
    var seen = {};
    for (var i = 0; sourceIds && i < sourceIds.length; i++) {
        if (_plannerBundleSourceHasInlineAnchorAncestor(sourceIds[i], clusterIndex)) continue;
        _pushUniqueId(ids, seen, sourceIds[i]);
    }
    return _sortedNumericIds(ids);
}

function _plannerBundleSourceHasInlineAnchorAncestor(sourceId, clusterIndex) {
    if (sourceId === null || sourceId === undefined || !clusterIndex || !clusterIndex.sourceInfo) return false;
    var cache = _plannerBundleCache(clusterIndex, "inlineAnchorAncestorBySource");
    var cacheKey = String(sourceId);
    if (cache[cacheKey] !== undefined) return cache[cacheKey];
    var current = clusterIndex.sourceInfo(sourceId);
    for (var depth = 0; depth < 200 && current; depth++) {
        if (typeof _isInlineFlowItemBySourceInfo === "function"
                ? _isInlineFlowItemBySourceInfo(current)
                : (String(current.storyAnchorPlacement || "").toUpperCase() === "INLINE"
                    || String(current.anchoredPosition || "").toUpperCase() === "INLINE_POSITION"
                    || String(current.anchoredPosition || "").toUpperCase() === "INLINEPOSITION")) {
            cache[cacheKey] = true;
            return true;
        }
        if (current.parentId === null || current.parentId === undefined) {
            cache[cacheKey] = false;
            return false;
        }
        current = clusterIndex.sourceInfo(current.parentId);
    }
    cache[cacheKey] = false;
    return false;
}

function _plannerBundleHasTextFrameShellStyle(src) {
    if (!src || src.kind !== "TextFrame") return false;
    var fill = String(src.fillColorName || src.fillColor || "");
    if (fill !== "" && fill !== "None" && fill !== "[None]") return true;
    var stroke = String(src.strokeColorName || src.strokeColor || "");
    var weight = src.strokeWeight !== null && src.strokeWeight !== undefined ? Number(src.strokeWeight) : 0;
    return stroke !== "" && stroke !== "None" && stroke !== "[None]" && !isNaN(weight) && weight > 0.01;
}

function _plannerBundleEditableTextFrameIds(sourceIds, clusterIndex, hiddenVisualIdSet) {
    var ids = [];
    if (!sourceIds || !clusterIndex || !clusterIndex.sourceInfo) return ids;
    for (var i = 0; i < sourceIds.length; i++) {
        var src = clusterIndex.sourceInfo(sourceIds[i]);
        if (!src || src.kind !== "TextFrame") continue;
        if (src.textFrameClass !== "editable") continue;
        if (src.hasText !== true) continue;
        ids.push(sourceIds[i]);
    }
    return _sortedNumericIds(ids);
}

function _plannerBundleId(candidate, sourceIds, clusterIndex) {
    var passId = String(candidate.passId || "pass.unknown").replace(/[^A-Za-z0-9_.-]/g, "_");
    var pageIndex = candidate.pageIndex !== null && candidate.pageIndex !== undefined ? candidate.pageIndex : "none";
    var sourceKey = sourceIds && sourceIds.length > 0
            ? _plannerBundleSourceSetKey(sourceIds, clusterIndex).replace(/,/g, "_")
            : "page";
    return "bundle." + passId + ".page." + pageIndex + ".src." + sourceKey;
}

function _plannerBundleOwnershipSlot(candidate, clusterIndex) {
    if (!candidate) return "UNKNOWN_SLOT";
    if (candidate.ownershipSlot) {
        if (candidate.ownershipSlot === "TEXTLESS_GROUP_VISUAL_SLOT") return "CONTENT_VISUAL_SLOT";
        return candidate.ownershipSlot;
    }
    if (_plannerBundleCandidateIsStoryTextInlineStandaloneVisual(candidate, clusterIndex)) {
        return "CONTENT_VISUAL_SLOT";
    }
    if (candidate.slotRole === "shell_slot_only") return "SHELL_SLOT";
    if (candidate.passId === "pass.editable_textframe_visual_shells") return "SHELL_SLOT";
    if (candidate.passId === "pass.decoration_groups") return "SHELL_SLOT";
    if (candidate.passId === "pass.page_backgrounds") return "SHELL_SLOT";
    if (candidate.passId === "pass.vector_shape_frames") {
        return _plannerBundleHasContentVisualEvidence(candidate, clusterIndex)
                ? "CONTENT_VISUAL_SLOT"
                : "SHELL_SLOT";
    }
    if (candidate.passId === "pass.image_placed_frames") {
        return _plannerBundleHasContentVisualEvidence(candidate, clusterIndex)
                ? "CONTENT_VISUAL_SLOT"
                : "SHELL_SLOT";
    }
    if (candidate.passId === "pass.image_textless_groups") {
        return _plannerBundleHasContentVisualEvidence(candidate, clusterIndex)
                ? "CONTENT_VISUAL_SLOT"
                : "SHELL_SLOT";
    }
    if (candidate.passId === "pass.page_textless_graphic_groups") {
        if (_plannerBundlePageTextlessGroupIsDecorationOnly(candidate, clusterIndex)) {
            return "SHELL_SLOT";
        }
        return "CONTENT_VISUAL_SLOT";
    }
    if (candidate.passId === "pass.complex_graphic_frames") {
        return _plannerBundleHasContentVisualEvidence(candidate, clusterIndex)
                ? "CONTENT_VISUAL_SLOT"
                : "SHELL_SLOT";
    }
    if (candidate.passId === "pass.master_page_graphics") return "SHELL_SLOT";
    if (candidate.passId === "pass.inline_objects") {
        if (_plannerBundleIsDirectChildShellSlot(candidate)) return "SHELL_SLOT";
        if (_plannerBundleInlineObjectIsTextShell(candidate)) return "SHELL_SLOT";
        if ((!candidate.ownedTextFrameIds || candidate.ownedTextFrameIds.length === 0)
                && (!candidate.editableTextFrameIds || candidate.editableTextFrameIds.length === 0)
                && (!candidate.hiddenTextFrameIds || candidate.hiddenTextFrameIds.length === 0)
                && ((candidate.visualSourceObjectIds && candidate.visualSourceObjectIds.length > 0)
                    || (candidate.exportSourceObjectIds && candidate.exportSourceObjectIds.length > 0))) {
            return "CONTENT_VISUAL_SLOT";
        }
        return _plannerBundleHasContentVisualEvidence(candidate, clusterIndex)
                ? "CONTENT_VISUAL_SLOT"
                : "SHELL_SLOT";
    }
    if (candidate.candidatePurpose === "SHELL_CANDIDATE") return "SHELL_SLOT";
    if (candidate.compositeRole === "background_vector_source") return "SHELL_SLOT";
    return _plannerBundleHasContentVisualEvidence(candidate, clusterIndex)
            ? "CONTENT_VISUAL_SLOT"
            : "SHELL_SLOT";
}

function _plannerBundleCandidateIsStoryTextInlineStandaloneVisual(candidate, clusterIndex) {
    if (!candidate || !clusterIndex || !clusterIndex.sourceInfo) return false;
    var sourceIds = candidate.sourceObjectIds || [];
    if (sourceIds.length !== 1) return false;
    if ((candidate.ownedTextFrameIds && candidate.ownedTextFrameIds.length > 0)
            || (candidate.editableTextFrameIds && candidate.editableTextFrameIds.length > 0)
            || (candidate.hiddenTextFrameIds && candidate.hiddenTextFrameIds.length > 0)) {
        return false;
    }
    var src = clusterIndex.sourceInfo(sourceIds[0]);
    if (!src || src.storyTextInlineSlot !== true) return false;
    var kind = String(src.kind || "");
    return kind !== "TextFrame" && kind !== "Story" && kind !== "Character" && kind !== "InsertionPoint";
}

function _plannerBundlePageTextlessGroupIsDecorationOnly(candidate, clusterIndex) {
    if (!candidate || candidate.passId !== "pass.page_textless_graphic_groups") return false;
    if (_plannerBundleHasContentVisualEvidence(candidate, clusterIndex)) return false;
    if (candidate.completePngTextAllowed === true || candidate.textOwner === "indesign_png") return false;
    if (!clusterIndex || !clusterIndex.sourceInfo) return false;
    var ids = candidate.visualSourceObjectIds && candidate.visualSourceObjectIds.length > 0
            ? candidate.visualSourceObjectIds
            : (candidate.exportSourceObjectIds && candidate.exportSourceObjectIds.length > 0
                    ? candidate.exportSourceObjectIds
                    : (candidate.sourceObjectIds || []));
    var visualCount = 0;
    for (var i = 0; ids && i < ids.length; i++) {
        var src = clusterIndex.sourceInfo(ids[i]);
        if (!src || String(src.kind || "") === "TextFrame") continue;
        visualCount++;
        if (!_plannerBundleSourceIsTextlessVectorDecoration(src, clusterIndex)) return false;
    }
    return visualCount > 0;
}

function _plannerBundleHasContentVisualEvidence(candidate, clusterIndex) {
    if (!candidate) return false;
    if (candidate.completePngTextAllowed === true || candidate.textOwner === "indesign_png") {
        return true;
    }
    if (!clusterIndex || !clusterIndex.sourceInfo) return false;
    var ids = candidate.visualSourceObjectIds && candidate.visualSourceObjectIds.length > 0
            ? candidate.visualSourceObjectIds
            : (candidate.exportSourceObjectIds && candidate.exportSourceObjectIds.length > 0
                    ? candidate.exportSourceObjectIds
                    : (candidate.sourceObjectIds || []));
    for (var i = 0; ids && i < ids.length; i++) {
        if (_plannerBundleSourceHasContentVisualEvidence(ids[i], clusterIndex)) return true;
    }
    return false;
}

function _plannerBundleSourceHasContentVisualEvidence(sourceId, clusterIndex, visiting) {
    if (sourceId === null || sourceId === undefined || !clusterIndex || !clusterIndex.sourceInfo) {
        return false;
    }
    var key = String(sourceId);
    if (!visiting) {
        var cache = _plannerBundleCache(clusterIndex, "contentVisualEvidenceBySource");
        if (cache[key] !== undefined) return cache[key];
        var cachedResult = _plannerBundleSourceHasContentVisualEvidence(sourceId, clusterIndex, {});
        cache[key] = cachedResult;
        return cachedResult;
    }
    visiting = visiting || {};
    if (visiting[key]) return false;
    visiting[key] = true;
    var src = clusterIndex.sourceInfo(sourceId);
    if (!src) return false;
    var kind = String(src.kind || "");
    if (_plannerBundleIsPlacedContentKind(kind)) return true;
    if (src.hasPlacedVisual === true || src.hasPlacedVisualInSubtree === true) return true;
    var children = clusterIndex.childIdsByParentId
            ? (clusterIndex.childIdsByParentId[key] || [])
            : [];
    for (var ci = 0; ci < children.length; ci++) {
        if (_plannerBundleSourceHasContentVisualEvidence(children[ci], clusterIndex, visiting)) {
            return true;
        }
    }
    return false;
}

function _plannerBundleInlineObjectIsTextShell(candidate) {
    if (!candidate || candidate.passId !== "pass.inline_objects") return false;
    if (_plannerBundleIsDirectChildShellSlot(candidate)
            && ((candidate.ownedTextFrameIds && candidate.ownedTextFrameIds.length > 0)
                || (candidate.hiddenTextFrameIds && candidate.hiddenTextFrameIds.length > 0)
                || (candidate.editableTextFrameIds && candidate.editableTextFrameIds.length > 0))) {
        return true;
    }
    if (candidate.completePngTextAllowed === true || candidate.textOwner === "indesign_png") return false;
    if (candidate.requiresTextHidden === true) return true;
    if (candidate.hiddenTextFrameIds && candidate.hiddenTextFrameIds.length > 0) return true;
    if (candidate.editableTextFrameIds && candidate.editableTextFrameIds.length > 0
            && candidate.textOwner === "hwpx_tf") return true;
    return false;
}

function _plannerBundleIsDirectChildShellSlot(candidate) {
    if (!candidate) return false;
    return candidate.slotRole === "direct_child_shell_slot"
            || candidate.compositeRole === "direct_child_shell_slot";
}

function _plannerBundlePolicyLayer(candidate, slot, clusterIndex) {
    if (!candidate) return "DECORATION";
    if (candidate.passId === "pass.page_backgrounds") return "BACKGROUND";
    if (candidate.passId === "pass.master_page_graphics") return "BACKGROUND";
    if (candidate.compositeRole === "background_vector_source") return "BACKGROUND";
    if (candidate.passId === "pass.vector_shape_frames"
            && _plannerBundleVectorShapeIsBackgroundLayer(candidate, clusterIndex)) {
        return "BACKGROUND";
    }
    if (slot === "SHELL_SLOT") return "DECORATION";
    return "CONTENT";
}

function _plannerBundleVectorShapeIsBackgroundLayer(candidate, clusterIndex) {
    if (!candidate || candidate.passId !== "pass.vector_shape_frames") return false;
    var sourceIds = candidate.sourceObjectIds || [];
    if (!clusterIndex || !clusterIndex.sourceInfo) return false;
    for (var i = 0; i < sourceIds.length; i++) {
        if (_plannerBundleSourceInfoIsBackgroundVector(clusterIndex.sourceInfo(sourceIds[i]))) {
            return true;
        }
    }
    return false;
}

function _plannerBundleSourceInfoIsBackgroundVector(source) {
    if (!source) return false;
    var kind = String(source.kind || "");
    if (kind !== "Rectangle" && kind !== "Oval" && kind !== "Polygon"
            && kind !== "GraphicLine") return false;
    if (source.hasChildren === true || source.hasPlacedVisual === true) return false;
    if (!_plannerBundleSourceHasPaint(source)) return false;
    return _plannerBundleIsBackgroundLayerName(source.layerName);
}

function _plannerBundleIsBackgroundLayerName(layerName) {
    if (!layerName) return false;
    var lower = String(layerName).toLowerCase();
    return lower.indexOf("\uBC30\uACBD") >= 0
            || lower.indexOf("\uBC14\uD0D5") >= 0
            || lower.indexOf("background") >= 0
            || lower === "bg"
            || lower.indexOf("backdrop") >= 0;
}

function _plannerBundleMaterialization(
        candidate, slot, slotSources, exportSourceObjectIds,
        hiddenVisualSourceObjectIds, clusterIndex) {
    if (!candidate) return "EXTRACTED_PNG_VECTOR";
    if (candidate.materialization === "COMPLETE_PNG"
            || candidate.mode === "COMPLETE_PNG"
            || (slot === "CONTENT_VISUAL_SLOT"
                && candidate.textOwner === "indesign_png"
                && candidate.completePngTextAllowed === true
                && ((candidate.ownedTextFrameIds && candidate.ownedTextFrameIds.length > 0)
                    || (slotSources && slotSources.ownedTextFrameIds
                        && slotSources.ownedTextFrameIds.length > 0)))) {
        return "COMPLETE_PNG";
    }
    if (_plannerBundleNeedsTextlessVisualFragment(
            candidate, slot, slotSources, exportSourceObjectIds, clusterIndex)) {
        return "TEXTLESS_VISUAL_FRAGMENT";
    }
    if (candidate.compositeRole === "background_vector_source") return "EXTRACTED_PNG_VECTOR";
    if (candidate.passId === "pass.vector_shape_frames") return "EXTRACTED_PNG_VECTOR";
    if (candidate.passId === "pass.page_backgrounds") return "EXTRACTED_PNG_VECTOR";
    if (slot === "SHELL_SLOT") return "EXTRACTED_PNG_VECTOR";
    return "EXTRACTED_PNG_VECTOR";
}

function _plannerBundleNeedsTextlessVisualFragment(
        candidate, slot, slotSources, exportSourceObjectIds, clusterIndex) {
    if (!candidate || !clusterIndex || !clusterIndex.sourceInfo) return false;
    if (_plannerBundlePolicyLayer(candidate, slot, clusterIndex) !== "BACKGROUND") return false;
    var targetPageIndex = Number(candidate.pageIndex);
    if (isNaN(targetPageIndex) || targetPageIndex < 0) return false;
    var ids = exportSourceObjectIds && exportSourceObjectIds.length > 0
            ? exportSourceObjectIds
            : (slotSources && slotSources.visualSourceObjectIds
                    && slotSources.visualSourceObjectIds.length > 0
                    ? slotSources.visualSourceObjectIds
                    : (candidate.sourceObjectIds || []));
    for (var i = 0; ids && i < ids.length; i++) {
        var source = clusterIndex.sourceInfo(ids[i]);
        if (!source) continue;
        var sourcePageIndex = Number(source.pageIndex);
        if (isNaN(sourcePageIndex) || sourcePageIndex < 0
                || sourcePageIndex === targetPageIndex) {
            continue;
        }
        if (clusterIndex.sameSpread
                && clusterIndex.sameSpread(sourcePageIndex, targetPageIndex) !== true) {
            continue;
        }
        var adjusted = _plannerBundleBoundsRelativeToTargetPage(
                source.bounds, sourcePageIndex, targetPageIndex,
                clusterIndex, candidate.bounds);
        if (_plannerBundleBoundsOverlap(adjusted, candidate.bounds, 0.5)) {
            return true;
        }
    }
    return false;
}

function _plannerBundleNativeSourceShapeEligible(
        candidate, slotSources, exportSourceObjectIds,
        hiddenVisualSourceObjectIds, clusterIndex) {
    if (!candidate || !slotSources || !clusterIndex || !clusterIndex.sourceInfo) return false;
    if (candidate.passId !== "pass.decoration_groups"
            && candidate.passId !== "pass.inline_objects") return false;
    if (candidate.slotRole === "shell_slot_only" || candidate.mode === "SLOT_ONLY") return false;
    if (candidate.passId === "pass.inline_objects"
            && !_plannerBundleInlineObjectIsTextShell(candidate)) return false;

    var visibleIds = slotSources.visualSourceObjectIds && slotSources.visualSourceObjectIds.length > 0
            ? slotSources.visualSourceObjectIds
            : exportSourceObjectIds;
    visibleIds = _sortedNumericIds(visibleIds || []);
    if (visibleIds.length !== 1) return false;

    var source = clusterIndex.sourceInfo(visibleIds[0]);
    if (!source) return false;
    var kind = String(source.kind || "");
    if (kind !== "Rectangle" && kind !== "Oval" && kind !== "GraphicLine") return false;
    if (!_plannerBundleSourceHasPaint(source)) return false;

    var hiddenNonText = _plannerBundleNonTextOnlySourceIds(
            hiddenVisualSourceObjectIds || [], clusterIndex);
    if (hiddenNonText.length > 0) return false;

    if (source.hasChildren === true
            && (!slotSources.ownedTextFrameIds || slotSources.ownedTextFrameIds.length === 0)) {
        return false;
    }
    return true;
}

function _plannerBundleSourceHasPaint(source) {
    if (!source) return false;
    var fillName = String(source.fillColorName || source.fillColor || "").toLowerCase();
    var strokeName = String(source.strokeColorName || source.strokeColor || "").toLowerCase();
    var strokeWeight = Number(source.strokeWeight || 0);
    var hasFill = fillName && fillName !== "none" && fillName !== "[none]" && fillName !== "n/a";
    var hasStroke = strokeWeight > 0 && strokeName && strokeName !== "none"
            && strokeName !== "[none]" && strokeName !== "n/a";
    return hasFill || hasStroke;
}

function _plannerBundleHasInlineTextStyleMarkerSource(candidate, sourceIds, clusterIndex) {
    if (!candidate || candidate.passId !== "pass.inline_objects") return false;
    if (candidate.storyTextInlineSlot !== true && candidate.sourceInlineFlow !== true) return false;
    if (!sourceIds || sourceIds.length === 0 || !clusterIndex || !clusterIndex.sourceInfo) return false;
    for (var i = 0; i < sourceIds.length; i++) {
        var source = clusterIndex.sourceInfo(sourceIds[i]);
        if (!source) continue;
        if (_plannerBundleSourceDeclaresTextStyleMarker(source)) return true;
    }
    return false;
}

function _plannerBundleSourceDeclaresTextStyleMarker(source) {
    if (!source) return false;
    var fillName = String(source.fillColorName || source.fillColor || "").toLowerCase();
    var strokeName = String(source.strokeColorName || source.strokeColor || "").toLowerCase();
    var names = fillName + " " + strokeName;
    return names.indexOf("형광펜") >= 0
            || names.indexOf("highlight") >= 0
            || names.indexOf("highlighter") >= 0
            || names.indexOf("underline") >= 0
            || names.indexOf("밑줄") >= 0
            || names.indexOf("강조") >= 0
            || names.indexOf("emphasis") >= 0;
}

function _plannerBundleNonTextOnlySourceIds(ids, clusterIndex) {
    var out = [];
    var seen = {};
    if (!ids || !clusterIndex || !clusterIndex.sourceInfo) return out;
    for (var i = 0; i < ids.length; i++) {
        var source = clusterIndex.sourceInfo(ids[i]);
        var kind = source ? String(source.kind || "") : "";
        if (kind !== "TextFrame" && kind !== "Story" && kind !== "Character"
                && kind !== "InsertionPoint" && kind !== "Cell") {
            _pushUniqueId(out, seen, ids[i]);
        }
    }
    return _sortedNumericIds(out);
}

function _plannerBundleClusterRelation(sourceIds, clusterIds, primarySourceObjectId, clusterIndex) {
    if (!sourceIds || sourceIds.length === 0) return "PAGE_OR_SYNTHETIC_BUNDLE";
    if (!clusterIndex || primarySourceObjectId === null || primarySourceObjectId === undefined) {
        return "NO_CLUSTER_REFERENCE";
    }
    if (!clusterIds || clusterIds.length === 0) return "NO_CLUSTER_REFERENCE";
    var bundleKey = _plannerBundleSourceSetKey(sourceIds, clusterIndex);
    var clusterKey = _plannerBundleSourceSetKey(clusterIds, clusterIndex);
    if (bundleKey === clusterKey) return "EXACT_SOURCE_CLUSTER";
    if (_plannerBundleSourceSetContainsAll(sourceIds, clusterIds, clusterIndex)) return "BUNDLE_BROADER_THAN_CLUSTER";
    if (_plannerBundleSourceSetContainsAll(clusterIds, sourceIds, clusterIndex)) return "BUNDLE_NARROWER_THAN_CLUSTER";
    return "BUNDLE_DIVERGES_FROM_CLUSTER";
}

function _incrementPlannerSummary(map, key) {
    key = key || "UNKNOWN";
    if (!map[key]) map[key] = 0;
    map[key]++;
}
