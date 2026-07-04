/*
 * Planner-declared bundle diagnostics for extract_indd.jsx.
 *
 * Source clusters describe the recursive IDML source tree. Planner bundles
 * describe the actual source set and visible slot declared by extraction
 * candidates. This module is diagnostic and must not change placement.
 */

function _buildPlannerBundles(sourceItems, candidates) {
    var clusterDoc = _buildSourceClusters(sourceItems);
    var clusterIndex = _createSourceClusterIndex(sourceItems, clusterDoc);
    var bundles = [];
    var summary = {
        candidateCount: candidates ? candidates.length : 0,
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

function _plannerBundleFromCandidate(candidate, clusterIndex) {
    var sourceIds = _sortedNumericIds(candidate.sourceObjectIds || []);
    var primarySourceObjectId = candidate.primarySourceObjectId !== undefined
            ? candidate.primarySourceObjectId
            : (sourceIds.length > 0 ? sourceIds[0] : null);
    var slot = _plannerBundleOwnershipSlot(candidate, clusterIndex);
    var sourceRootObjectIds = _plannerBundleSourceRootObjectIds(sourceIds, clusterIndex);
    var clusterSourceObjectIds = _plannerBundleClusterSourceObjectIds(
            primarySourceObjectId, sourceRootObjectIds, clusterIndex);
    var clusterRelation = _plannerBundleClusterRelation(
            sourceIds, clusterSourceObjectIds, primarySourceObjectId, clusterIndex);
    var clusterProfile = _plannerBundleClusterProfile(
            clusterSourceObjectIds, sourceIds, candidate.pageIndex, clusterIndex);
    var declaredCandidate = _plannerBundleWithInferredSlotContract(
            candidate, slot, sourceIds, clusterRelation, clusterProfile);
    var slotSources = _plannerBundleSlotSources(declaredCandidate, slot, sourceIds, clusterIndex);
    declaredCandidate = _plannerBundleWithCompletedVisibleFragmentContract(
            declaredCandidate, slot, clusterRelation, clusterProfile, slotSources);
    slotSources = _plannerBundleSlotSources(declaredCandidate, slot, sourceIds, clusterIndex);
    slotSources = _plannerBundleWithoutOwnedTextVisualSources(slot, slotSources);
    slotSources = _plannerBundleWithoutPlacedContentBranches(
            declaredCandidate, slot, slotSources, clusterIndex);
    var exportSourceObjectIds = _sortedNumericIds(declaredCandidate.exportSourceObjectIds || []);
    if (_plannerBundleShouldFillMissingSlotOnlyExport(
            declaredCandidate, exportSourceObjectIds, slotSources)) {
        exportSourceObjectIds = _sortedNumericIds(slotSources.visualSourceObjectIds || []);
    }
    if (_plannerBundleShouldKeepShellVisualExportSources(
            slot, exportSourceObjectIds, slotSources)) {
        exportSourceObjectIds = _plannerBundleSourceIdsUnion(
                exportSourceObjectIds, slotSources.visualSourceObjectIds);
    }
    if (_plannerBundleShouldUseAppliedMasterVisualSourcesAsExport(
            declaredCandidate, slot, exportSourceObjectIds, slotSources)) {
        exportSourceObjectIds = _sortedNumericIds(slotSources.visualSourceObjectIds || []);
    }
    if (slot === "SHELL_SLOT" && slotSources.styleSourceObjectIds
            && slotSources.styleSourceObjectIds.length > 0) {
        exportSourceObjectIds = _plannerBundleSourceIdsUnion(
                exportSourceObjectIds, slotSources.styleSourceObjectIds);
    }
    exportSourceObjectIds = _plannerBundlePrunePlacedContentBranches(
            declaredCandidate, slot, exportSourceObjectIds, clusterIndex);
    exportSourceObjectIds = _plannerBundleSourceIdsIntersect(exportSourceObjectIds, sourceIds);
    var hiddenVisualSourceObjectIds = _plannerBundleSourceIdsMinus(
            declaredCandidate.hiddenVisualSourceObjectIds || [], exportSourceObjectIds);
    hiddenVisualSourceObjectIds = _plannerBundleSourceIdsIntersect(hiddenVisualSourceObjectIds, sourceIds);
    var materialization = _plannerBundleMaterialization(
            declaredCandidate, slot, slotSources, exportSourceObjectIds,
            hiddenVisualSourceObjectIds, clusterIndex);
    var executable = candidate.disabled !== true;
    if (slot === "SHELL_SLOT"
            && !_plannerBundleHasExecutableShellMaterial(slotSources, clusterIndex)) {
        executable = _plannerBundleIsExecutableAppliedMasterShell(
                declaredCandidate, slotSources);
    }
    var zOrder = _plannerBundleZOrder(candidate, primarySourceObjectId, clusterIndex);
    var sourceInlineFlow = _plannerBundleSourceSetIsInlineFlow(sourceIds, clusterIndex);
    var inlineCompositeLayoutDescendant = _plannerBundleIsInsideInlineCompositeLayout(
            sourceIds, clusterIndex);
    var connectorDecorationVisual = _plannerBundleIsInlineCompositeTextlessVectorDecoration(
            candidate, slot, sourceIds, inlineCompositeLayoutDescendant, clusterIndex);
    var layer = connectorDecorationVisual
            ? "DECORATION"
            : _plannerBundlePolicyLayer(candidate, slot, clusterIndex);

    return {
        bundleId: _plannerBundleId(candidate, sourceIds),
        candidateId: candidate.candidateId || null,
        passId: candidate.passId || null,
        pageIndex: candidate.pageIndex,
        unit: candidate.unit || null,
        mode: declaredCandidate.mode || null,
        candidatePurpose: candidate.candidatePurpose || null,
        compositeRole: candidate.compositeRole || null,
        slotRole: declaredCandidate.slotRole || null,
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
        exportSourceObjectIds: exportSourceObjectIds,
        hiddenVisualSourceObjectIds: hiddenVisualSourceObjectIds,
        visualSourceObjectIds: slotSources.visualSourceObjectIds,
        styleSourceObjectIds: slotSources.styleSourceObjectIds,
        ownedTextFrameIds: slotSources.ownedTextFrameIds,
        ownershipSlot: slot,
        policyLayer: layer,
        materialization: materialization,
        clusterRelation: clusterRelation,
        executable: executable,
        required: candidate.required === true,
        sourceInlineFlow: sourceInlineFlow,
        inlineCompositeLayoutDescendant: inlineCompositeLayoutDescendant,
        connectorDecorationVisual: connectorDecorationVisual,
        zOrder: zOrder,
        bounds: candidate.bounds || null
    };
}

function _plannerBundleSourceSetIsInlineFlow(sourceIds, clusterIndex) {
    if (!sourceIds || sourceIds.length === 0 || !clusterIndex || !clusterIndex.sourceInfo) {
        return false;
    }
    var sawInline = false;
    for (var i = 0; i < sourceIds.length; i++) {
        var src = clusterIndex.sourceInfo(sourceIds[i]);
        if (!src) continue;
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
                || String(src.anchoredPosition || "").toUpperCase() === "INLINE_POSITION"
                || String(src.storyAnchorPlacement || "").toUpperCase() === "INLINE") {
            sawInline = true;
            continue;
        }
        return false;
    }
    return sawInline;
}

function _plannerBundleIsInsideInlineCompositeLayout(sourceIds, clusterIndex) {
    if (!sourceIds || sourceIds.length === 0 || !clusterIndex || !clusterIndex.sourceInfo) {
        return false;
    }
    var sourceSet = {};
    for (var i = 0; i < sourceIds.length; i++) sourceSet[String(sourceIds[i])] = true;
    var ancestorId = null;
    for (var si = 0; si < sourceIds.length; si++) {
        var current = clusterIndex.sourceInfo(sourceIds[si]);
        var guard = 0;
        while (current && guard < 64) {
            if (_plannerBundleSourceIsInlineAnchorRoot(current)) {
                if (!sourceSet[String(current.id)]
                        && _plannerBundleInlineAnchorRootHasCompositeLayout(current.id, clusterIndex)) {
                    if (ancestorId === null) ancestorId = String(current.id);
                    else if (ancestorId !== String(current.id)) return false;
                }
                break;
            }
            if (current.parentId === null || current.parentId === undefined) break;
            current = clusterIndex.sourceInfo(current.parentId);
            guard++;
        }
    }
    return ancestorId !== null;
}

function _plannerBundleSourceIsInlineAnchorRoot(source) {
    if (!source) return false;
    if (String(source.anchoredPosition || "").toUpperCase() === "INLINE_POSITION") return true;
    return typeof _isInlineFlowItemBySourceInfo === "function"
            && _isInlineFlowItemBySourceInfo(source);
}

function _plannerBundleInlineAnchorRootHasCompositeLayout(rootId, clusterIndex) {
    if (!clusterIndex || !clusterIndex.descendantSourceObjectIds || !clusterIndex.sourceInfo) return false;
    var descendants = clusterIndex.descendantSourceObjectIds(rootId);
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
    return editableTextCount > 1 && visualCount > 0;
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
        var descendants = clusterIndex.descendantSourceObjectIds(source.id);
        if (!descendants || descendants.length === 0) return false;
        for (var i = 0; i < descendants.length; i++) {
            if (String(descendants[i]) === String(source.id)) continue;
            var child = clusterIndex.sourceInfo(descendants[i]);
            if (!_plannerBundleSourceIsTextlessVectorDecorationLeaf(child)) return false;
        }
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
    return _plannerBundleSourceHasExecutableVisualMaterialInSubtree(sourceId, clusterIndex, {});
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

function _plannerBundleWithoutOwnedTextVisualSources(slot, slotSources) {
    if (slot !== "SHELL_SLOT" || !slotSources) return slotSources;
    if (!slotSources.ownedTextFrameIds || slotSources.ownedTextFrameIds.length === 0) return slotSources;
    var copy = {
        visualSourceObjectIds: _plannerBundleSourceIdsMinus(
                slotSources.visualSourceObjectIds || [], slotSources.ownedTextFrameIds),
        styleSourceObjectIds: _sortedNumericIds(slotSources.styleSourceObjectIds || []),
        ownedTextFrameIds: _sortedNumericIds(slotSources.ownedTextFrameIds || [])
    };
    return copy;
}

function _plannerBundleWithoutPlacedContentBranches(candidate, slot, slotSources, clusterIndex) {
    if (slot !== "SHELL_SLOT" || !slotSources) return slotSources;
    if (_plannerBundleAllowsClosedShellPlacedContentOwnership(candidate)) return slotSources;
    var copy = {
        visualSourceObjectIds: _plannerBundlePrunePlacedContentBranches(
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

function _plannerBundleAllowsClosedShellPlacedContentOwnership(candidate) {
    if (!candidate) return false;
    if (candidate.passId !== "pass.decoration_groups") return false;
    if (candidate.candidatePurpose !== "SHELL_CANDIDATE") return false;
    if (candidate.compositeRole !== "textless_group_visual_slot"
            && candidate.slotRole !== "textless_group_visual_slot") return false;
    if (candidate.textOwner && candidate.textOwner !== "hwpx_tf") return false;
    return candidate.visualSourceObjectIds && candidate.visualSourceObjectIds.length > 1;
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

function _plannerBundleSourceIdsUnion(a, b) {
    var ids = [];
    var seen = {};
    for (var ai = 0; a && ai < a.length; ai++) _pushUniqueId(ids, seen, a[ai]);
    for (var bi = 0; b && bi < b.length; bi++) _pushUniqueId(ids, seen, b[bi]);
    return _sortedNumericIds(ids);
}

function _plannerBundleSourceIdsMinus(sourceIds, removedIds) {
    var removed = _sourceIdSet(removedIds || []);
    var ids = [];
    var seen = {};
    for (var i = 0; sourceIds && i < sourceIds.length; i++) {
        if (removed[String(sourceIds[i])]) continue;
        _pushUniqueId(ids, seen, sourceIds[i]);
    }
    return _sortedNumericIds(ids);
}

function _plannerBundleSourceIdsIntersect(sourceIds, allowedIds) {
    var allowed = _sourceIdSet(allowedIds || []);
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
    copy.exportSourceObjectIds = _sortedNumericIds(slotSources.visualSourceObjectIds || []);
    copy.hiddenVisualSourceObjectIds = _sortedNumericIds(clusterProfile.omittedClusterSourceObjectIds || []);
    copy.mode = "SLOT_ONLY";
    if (slot === "SHELL_SLOT") copy.slotRole = copy.slotRole || "shell_slot_only";
    return copy;
}

function _plannerBundleShouldCompleteVisibleFragmentContract(candidate, slot, clusterRelation, clusterProfile, slotSources) {
    if (!candidate) return false;
    if (clusterRelation !== "BUNDLE_NARROWER_THAN_CLUSTER") return false;
    if (slot !== "SHELL_SLOT" && slot !== "CONTENT_VISUAL_SLOT") return false;
    if (candidate.exportSourceObjectIds && candidate.exportSourceObjectIds.length > 0) return false;
    if (candidate.hiddenVisualSourceObjectIds && candidate.hiddenVisualSourceObjectIds.length > 0) return false;
    if (!clusterProfile || !clusterProfile.omittedClusterSourceObjectIds
            || clusterProfile.omittedClusterSourceObjectIds.length === 0) return false;
    if (!slotSources || !slotSources.visualSourceObjectIds
            || slotSources.visualSourceObjectIds.length === 0) return false;
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

function _plannerBundleShouldFillMissingSlotOnlyExport(candidate, exportSourceObjectIds, slotSources) {
    if (!candidate) return false;
    if (candidate.slotRole !== "shell_slot_only" && candidate.mode !== "SLOT_ONLY") return false;
    if (exportSourceObjectIds && exportSourceObjectIds.length > 0) return false;
    if (!candidate.hiddenVisualSourceObjectIds || candidate.hiddenVisualSourceObjectIds.length === 0) return false;
    return slotSources && slotSources.visualSourceObjectIds
            && slotSources.visualSourceObjectIds.length > 0;
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
    var sourceSet = _sourceIdSet(sourceIds || []);
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
    var hiddenVisualIdSet = _sourceIdSet(candidate.hiddenVisualSourceObjectIds || []);
    var visualSourceObjectIds = [];
    var styleSourceObjectIds = [];
    var ownedTextFrameIds = [];
    var allowInlineAnchorVisualSource = _plannerBundleAllowsInlineAnchorVisualSource(
            candidate, slot);
    if (_plannerBundleAllowsClosedShellPlacedContentOwnership(candidate)
            && clusterIndex
            && clusterIndex.descendantSourceObjectIds
            && candidate.primarySourceObjectId !== null
            && candidate.primarySourceObjectId !== undefined) {
        var closedShellSourceIds = clusterIndex.descendantSourceObjectIds(candidate.primarySourceObjectId);
        visualSourceObjectIds = _plannerBundleNonTextSourceIds(
                closedShellSourceIds, hiddenVisualIdSet, clusterIndex);
    } else if (explicitVisualIds && explicitVisualIds.length > 0) {
        visualSourceObjectIds = allowInlineAnchorVisualSource
                ? _sortedNumericIds(explicitVisualIds)
                : _plannerBundleSourceIdsWithoutInlineAnchorDescendants(
                        explicitVisualIds, clusterIndex);
    } else {
        var visualBase = candidate.exportSourceObjectIds && candidate.exportSourceObjectIds.length > 0
                ? candidate.exportSourceObjectIds
                : sourceIds;
        if (_plannerBundleShouldUseClosedPlacedContentFrame(candidate, slot, visualBase, clusterIndex)) {
            visualBase = clusterIndex.descendantSourceObjectIds(candidate.primarySourceObjectId);
        }
        visualSourceObjectIds = _plannerBundleNonTextSourceIds(visualBase, hiddenVisualIdSet, clusterIndex);
        if (visualSourceObjectIds.length === 0
                && candidate.passId === "pass.editable_textframe_visual_shells") {
            visualSourceObjectIds = _plannerBundleTextFrameShellSourceIds(visualBase, clusterIndex);
        }
    }

    var allowInlineAnchorStyleSource = _plannerBundleAllowsInlineAnchorStyleSource(
            candidate, slot);
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

    return {
        visualSourceObjectIds: visualSourceObjectIds,
        styleSourceObjectIds: styleSourceObjectIds,
        ownedTextFrameIds: ownedTextFrameIds
    };
}

function _plannerBundleDeclaredOwnedTextFrameIds(candidate, clusterIndex) {
    var ids = [];
    var seen = {};
    function addDeclared(sourceIds) {
        for (var i = 0; sourceIds && i < sourceIds.length; i++) {
            var id = sourceIds[i];
            var src = clusterIndex && clusterIndex.sourceInfo ? clusterIndex.sourceInfo(id) : null;
            if (!src || src.kind !== "TextFrame") continue;
            if (src.textFrameClass !== "editable") continue;
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
    var sourceSet = _sourceIdSet(sourceIds);
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
    return _sortedNumericIds(roots);
}

function _plannerBundleClusterSourceObjectIds(primarySourceObjectId, sourceRootObjectIds, clusterIndex) {
    if (!clusterIndex || !clusterIndex.descendantSourceObjectIds) {
        return [];
    }
    var roots = sourceRootObjectIds && sourceRootObjectIds.length > 0
            ? sourceRootObjectIds
            : (primarySourceObjectId !== null && primarySourceObjectId !== undefined ? [primarySourceObjectId] : []);
    var out = [];
    var seen = {};
    for (var i = 0; i < roots.length; i++) {
        var descendants = clusterIndex.descendantSourceObjectIds(roots[i]);
        for (var j = 0; descendants && j < descendants.length; j++) {
            _pushUniqueId(out, seen, descendants[j]);
        }
    }
    return _sortedNumericIds(out);
}

function _plannerBundleShouldUseClosedPlacedContentFrame(candidate, slot, sourceIds, clusterIndex) {
    if (!candidate || slot !== "CONTENT_VISUAL_SLOT") return false;
    if (candidate.passId !== "pass.image_placed_frames") return false;
    if (!sourceIds || sourceIds.length !== 1) return false;
    if (!clusterIndex || !clusterIndex.descendantSourceObjectIds || !clusterIndex.sourceInfo) return false;
    var primary = candidate.primarySourceObjectId;
    if (primary === null || primary === undefined) return false;
    var descendants = clusterIndex.descendantSourceObjectIds(primary);
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

function _plannerBundleAllowsInlineAnchorStyleSource(candidate, slot) {
    if (!candidate || slot !== "SHELL_SLOT") return false;
    if (candidate.passId !== "pass.inline_objects") return false;
    return _plannerBundleInlineObjectIsTextShell(candidate);
}

function _plannerBundleAllowsInlineAnchorVisualSource(candidate, slot) {
    if (!candidate || slot !== "SHELL_SLOT") return false;
    if (candidate.passId !== "pass.inline_objects") return false;
    return _plannerBundleInlineObjectIsTextShell(candidate);
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
    var current = clusterIndex.sourceInfo(sourceId);
    for (var depth = 0; depth < 200 && current; depth++) {
        if (typeof _isInlineFlowItemBySourceInfo === "function"
                ? _isInlineFlowItemBySourceInfo(current)
                : (String(current.parentKind || "") === "Character"
                    || String(current.parentKind || "") === "InsertionPoint")) {
            return true;
        }
        if (current.parentId === null || current.parentId === undefined) return false;
        current = clusterIndex.sourceInfo(current.parentId);
    }
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

function _plannerBundleId(candidate, sourceIds) {
    var passId = String(candidate.passId || "pass.unknown").replace(/[^A-Za-z0-9_.-]/g, "_");
    var pageIndex = candidate.pageIndex !== null && candidate.pageIndex !== undefined ? candidate.pageIndex : "none";
    var sourceKey = sourceIds && sourceIds.length > 0 ? _sourceSetKey(sourceIds).replace(/,/g, "_") : "page";
    return "bundle." + passId + ".page." + pageIndex + ".src." + sourceKey;
}

function _plannerBundleOwnershipSlot(candidate, clusterIndex) {
    if (!candidate) return "UNKNOWN_SLOT";
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
    if (candidate.passId === "pass.complex_graphic_frames") {
        return _plannerBundleHasContentVisualEvidence(candidate, clusterIndex)
                ? "CONTENT_VISUAL_SLOT"
                : "SHELL_SLOT";
    }
    if (candidate.passId === "pass.master_page_graphics") return "SHELL_SLOT";
    if (candidate.passId === "pass.inline_objects") {
        if (_plannerBundleInlineObjectIsTextShell(candidate)) return "SHELL_SLOT";
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
    if (candidate.completePngTextAllowed === true || candidate.textOwner === "indesign_png") return false;
    if (candidate.requiresTextHidden === true) return true;
    if (candidate.hiddenTextFrameIds && candidate.hiddenTextFrameIds.length > 0) return true;
    if (candidate.editableTextFrameIds && candidate.editableTextFrameIds.length > 0
            && candidate.textOwner === "hwpx_tf") return true;
    return false;
}

function _plannerBundlePolicyLayer(candidate, slot, clusterIndex) {
    if (!candidate) return "DECORATION";
    if (candidate.passId === "pass.page_backgrounds") return "BACKGROUND";
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
    if (candidate.compositeRole === "background_vector_source") return "EXTRACTED_PNG_VECTOR";
    if (candidate.passId === "pass.vector_shape_frames") return "EXTRACTED_PNG_VECTOR";
    if (candidate.passId === "pass.page_backgrounds") return "EXTRACTED_PNG_VECTOR";
    if (slot === "SHELL_SLOT") return "EXTRACTED_PNG_VECTOR";
    return "EXTRACTED_PNG_VECTOR";
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
    var bundleKey = _sourceSetKey(sourceIds);
    var clusterKey = _sourceSetKey(clusterIds);
    if (bundleKey === clusterKey) return "EXACT_SOURCE_CLUSTER";
    if (_sourceSetContainsAll(sourceIds, clusterIds)) return "BUNDLE_BROADER_THAN_CLUSTER";
    if (_sourceSetContainsAll(clusterIds, sourceIds)) return "BUNDLE_NARROWER_THAN_CLUSTER";
    return "BUNDLE_DIVERGES_FROM_CLUSTER";
}

function _incrementPlannerSummary(map, key) {
    key = key || "UNKNOWN";
    if (!map[key]) map[key] = 0;
    map[key]++;
}
