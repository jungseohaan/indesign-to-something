/*
 * ObjectPlan-shaped diagnostics for extract_indd.jsx.
 *
 * This module does not execute ownership. It maps planner-declared bundles to
 * the policy ObjectPlan contract and validates that contract. The legacy
 * execution candidate adapter lives in execution_candidates.jsx while executors
 * are still being migrated.
 */

function _buildObjectPlanDiagnostics(sourceItems, candidates) {
    var plannerBundles = _buildPlannerBundles(sourceItems, candidates);
    return _buildObjectPlanDiagnosticsFromPlannerBundles(plannerBundles, sourceItems);
}

function _buildObjectPlanDiagnosticsFromPlannerBundles(plannerBundles, sourceItems) {
    var bundles = plannerBundles && plannerBundles.bundles ? plannerBundles.bundles : [];
    var objectPlans = [];
    var summary = {
        planCount: 0,
        executablePlanCount: 0,
        readyExactClusterCount: 0,
        migrationStatusCounts: {},
        textActionCounts: {},
        visualActionCounts: {},
        materializationCounts: {},
        placementCounts: {},
        coordinateSpaceCounts: {},
        visualLayerCounts: {},
        plansWithVisualSources: 0,
        plansWithStyleSources: 0,
        plansWithOwnedTextFrames: 0,
        importReadyPlanCount: 0,
        issueCount: 0,
        contractStatusCounts: {},
        issueCodeCounts: {},
        migrationBlockerCounts: {}
    };

    for (var i = 0; i < bundles.length; i++) {
        var plan = _objectPlanFromPlannerBundle(bundles[i], i);
        objectPlans.push(plan);
        summary.planCount++;
        if (plan.executable) summary.executablePlanCount++;
        if (plan.migrationStatus === "READY_EXACT_CLUSTER") summary.readyExactClusterCount++;
        _incrementObjectPlanSummary(summary.migrationStatusCounts, plan.migrationStatus);
        _incrementObjectPlanSummary(summary.textActionCounts, plan.textAction);
        _incrementObjectPlanSummary(summary.visualActionCounts, plan.visualAction);
        _incrementObjectPlanSummary(summary.materializationCounts, plan.materialization);
        _incrementObjectPlanSummary(summary.placementCounts, plan.placement);
        _incrementObjectPlanSummary(summary.coordinateSpaceCounts, plan.coordinateSpace);
        _incrementObjectPlanSummary(summary.visualLayerCounts, plan.visualLayer);
        if (plan.visualSourceObjectIds && plan.visualSourceObjectIds.length > 0) summary.plansWithVisualSources++;
        if (plan.styleSourceObjectIds && plan.styleSourceObjectIds.length > 0) summary.plansWithStyleSources++;
        if (plan.ownedTextFrameIds && plan.ownedTextFrameIds.length > 0) summary.plansWithOwnedTextFrames++;
        _incrementObjectPlanSummary(summary.migrationBlockerCounts, plan.migrationBlocker || "NONE");
    }
    _appendEditableTextFrameObjectPlans(objectPlans, sourceItems);
    _appendVisibleTextFrameObjectPlans(objectPlans, sourceItems);
    _appendEmptyEditableTextFrameObjectPlans(objectPlans, sourceItems);
    _appendTableOnlyTextFrameObjectPlans(objectPlans, sourceItems);
    _appendTextFrameCleanupObjectPlans(objectPlans, sourceItems);
    var textOwnershipResolution = _resolveObjectPlanDuplicateTextOwners(objectPlans);
    var depthFinalization = _finalizeObjectPlanVisualDepthContracts(objectPlans, sourceItems);
    var validation = _validateObjectPlanDiagnostics(objectPlans);
    summary = _summarizeObjectPlans(objectPlans, validation);
    summary.textOwnershipResolution = textOwnershipResolution.summary;
    summary.visualDepthFinalization = depthFinalization.summary;

    return {
        schemaVersion: 1,
        policy: "POLICY-source-ownership",
        mode: "object-plan-diagnostics",
        summary: summary,
        validation: validation,
        objectPlans: objectPlans
    };
}

function _finalizeObjectPlanVisualDepthContracts(objectPlans, sourceItems) {
    var sourceById = _objectPlanSourceInfoById(sourceItems);
    var editableTextFrames = _objectPlanEditableTextFrames(sourceItems);
    var zOrderUpdates = 0;
    var layerUpdates = 0;
    for (var i = 0; objectPlans && i < objectPlans.length; i++) {
        var plan = objectPlans[i];
        if (!_objectPlanHasVisibleVisual(plan)) continue;
        var sourceZ = _objectPlanCanonicalVisualSourceZOrder(plan, sourceById);
        if (sourceZ >= 0 && plan.zOrder !== sourceZ) {
            plan.zOrder = sourceZ;
            zOrderUpdates++;
        }
        var layer = _objectPlanCanonicalVisualLayer(plan, sourceById, editableTextFrames, sourceZ);
        if (layer && plan.visualLayer !== layer) {
            plan.visualLayer = layer;
            plan.policyLayer = _objectPlanPolicyLayerForVisualLayer(layer);
            layerUpdates++;
        }
    }
    return {
        summary: {
            zOrderUpdates: zOrderUpdates,
            layerUpdates: layerUpdates
        }
    };
}

function _objectPlanSourceInfoById(sourceItems) {
    var out = {};
    for (var i = 0; sourceItems && i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!src || src.id === null || src.id === undefined) continue;
        out[String(src.id)] = src;
    }
    return out;
}

function _objectPlanEditableTextFrames(sourceItems) {
    var out = [];
    for (var i = 0; sourceItems && i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!src || String(src.kind || "") !== "TextFrame") continue;
        if (src.textFrameClass !== "editable") continue;
        if (src.hasText !== true) continue;
        if (!src.bounds || src.bounds.length < 4) continue;
        out.push(src);
    }
    return out;
}

function _objectPlanCanonicalVisualSourceZOrder(plan, sourceById) {
    var sourceZ = _objectPlanMaxSourceZOrder(plan ? plan.sourceRootObjectIds : null, sourceById);
    if (sourceZ >= 0) return sourceZ;
    sourceZ = _objectPlanMaxNormalizedSourceZOrder(plan ? plan.visualSourceObjectIds : null, sourceById);
    if (sourceZ >= 0) return sourceZ;
    sourceZ = _objectPlanMaxNormalizedSourceZOrder(plan ? plan.sourceObjectIds : null, sourceById);
    if (sourceZ >= 0) return sourceZ;
    sourceZ = _objectPlanMaxSourceZOrder(plan ? plan.visualSourceObjectIds : null, sourceById);
    if (sourceZ >= 0) return sourceZ;
    sourceZ = _objectPlanMaxSourceZOrder(plan ? plan.sourceObjectIds : null, sourceById);
    return sourceZ >= 0 ? sourceZ : (plan && plan.zOrder !== null && plan.zOrder !== undefined ? plan.zOrder : -1);
}

function _objectPlanMaxNormalizedSourceZOrder(ids, sourceById) {
    var max = -1;
    for (var i = 0; ids && i < ids.length; i++) {
        var src = sourceById ? sourceById[String(ids[i])] : null;
        if (!src || src.zOrder === null || src.zOrder === undefined) continue;
        if (src.zOrderSource && String(src.zOrderSource) !== "idml_spread") continue;
        var z = Number(src.zOrder);
        if (!isNaN(z) && z > max) max = z;
    }
    return max;
}

function _objectPlanMaxSourceZOrder(ids, sourceById) {
    var max = -1;
    for (var i = 0; ids && i < ids.length; i++) {
        var src = sourceById ? sourceById[String(ids[i])] : null;
        if (!src || src.zOrder === null || src.zOrder === undefined) continue;
        var z = Number(src.zOrder);
        if (!isNaN(z) && z > max) max = z;
    }
    return max;
}

function _objectPlanCanonicalVisualLayer(plan, sourceById, editableTextFrames, zOrder) {
    if (!plan) return null;
    if (plan.compositeRole === "background_vector_source") {
        return "PAGE_BACKGROUND";
    }
    if (plan.visualLayer === "PAGE_BACKGROUND"
            && plan.passId !== "pass.page_backgrounds"
            && !_objectPlanMayUseBackgroundPlane(plan, sourceById, editableTextFrames, zOrder)) {
        return "CONTENT_VISUAL";
    }
    if (plan.visualLayer === "CONTAINER_BACKDROP"
            && !_objectPlanMayUseBackgroundPlane(plan, sourceById, editableTextFrames, zOrder)) {
        return plan.visualAction === "PLACE_TEXT_SHELL" ? "LABEL_BACKDROP" : "CONTENT_VISUAL";
    }
    if (plan.visualAction === "PLACE_TEXT_SHELL"
            && plan.placement === "FLOATING"
            && plan.coordinateSpace === "PAGE"
            && (!plan.ownedTextFrameIds || plan.ownedTextFrameIds.length === 0)
            && plan.visualLayer === "LABEL_BACKDROP"
            && _objectPlanMayUseBackgroundPlane(plan, sourceById, editableTextFrames, zOrder)) {
        return "CONTAINER_BACKDROP";
    }
    if (plan.visualAction === "PLACE_FLOATING_PNG"
            && plan.placement === "FLOATING"
            && plan.visualLayer === "CONTENT_VISUAL"
            && _objectPlanIsBehindLocalText(plan.bounds, plan.pageIndex, zOrder, editableTextFrames)) {
        return "CONTENT_BACKDROP";
    }
    return plan.visualLayer;
}

function _objectPlanMayUseBackgroundPlane(plan, sourceById, editableTextFrames, zOrder) {
    if (!plan) return false;
    if (!_objectPlanHasPageLevelSourceRoot(plan, sourceById)) return false;
    if (_objectPlanHasTextOwnershipSignal(plan)) return false;
    if (!_objectPlanIsBackgroundBoundsSanityCandidate(plan.bounds)) return false;
    return _objectPlanIsBehindLocalText(plan.bounds, plan.pageIndex, zOrder, editableTextFrames);
}

function _objectPlanHasTextOwnershipSignal(plan) {
    return !!(plan
            && ((plan.ownedTextFrameIds && plan.ownedTextFrameIds.length > 0)
                    || (plan.hiddenVisualSourceObjectIds && plan.hiddenVisualSourceObjectIds.length > 0)
                    || plan.textAction === "OWNED_BY_HWPX_TEXT"));
}

function _objectPlanHasPageLevelSourceRoot(plan, sourceById) {
    var roots = plan ? (plan.sourceRootObjectIds && plan.sourceRootObjectIds.length > 0
            ? plan.sourceRootObjectIds
            : plan.sourceObjectIds) : null;
    if (!roots || roots.length === 0) return false;
    for (var i = 0; i < roots.length; i++) {
        var src = sourceById ? sourceById[String(roots[i])] : null;
        if (!src) return false;
        if (src.parentId !== null && src.parentId !== undefined && String(src.parentId) !== "") {
            return false;
        }
    }
    return true;
}

function _objectPlanIsBehindLocalText(bounds, pageIndex, zOrder, editableTextFrames) {
    if (!bounds || bounds.length < 4 || _objectPlanArea(bounds) <= 0) return false;
    if (zOrder === null || zOrder === undefined || zOrder < 0) return false;
    for (var i = 0; editableTextFrames && i < editableTextFrames.length; i++) {
        var tf = editableTextFrames[i];
        if (!tf || tf.pageIndex !== pageIndex) continue;
        if (tf.zOrder === null || tf.zOrder === undefined) continue;
        if (Number(tf.zOrder) <= Number(zOrder)) continue;
        if (_objectPlanOverlapArea(bounds, tf.bounds) > 0) return true;
    }
    return false;
}

function _objectPlanIsBackgroundBoundsSanityCandidate(bounds) {
    if (!bounds || bounds.length < 4 || _objectPlanArea(bounds) <= 0) return false;
    var h = Math.abs(bounds[2] - bounds[0]);
    var w = Math.abs(bounds[3] - bounds[1]);
    if (w < 180 || h < 90) return false;
    return (bounds[0] <= 1 || bounds[1] <= 1) && (w >= 180 || h >= 180);
}

function _objectPlanArea(bounds) {
    if (!bounds || bounds.length < 4) return 0;
    return Math.abs(bounds[2] - bounds[0]) * Math.abs(bounds[3] - bounds[1]);
}

function _objectPlanOverlapArea(a, b) {
    if (!a || !b || a.length < 4 || b.length < 4) return 0;
    var top = Math.max(a[0], b[0]);
    var left = Math.max(a[1], b[1]);
    var bottom = Math.min(a[2], b[2]);
    var right = Math.min(a[3], b[3]);
    if (bottom <= top || right <= left) return 0;
    return (bottom - top) * (right - left);
}

function _objectPlanPolicyLayerForVisualLayer(visualLayer) {
    if (visualLayer === "PAGE_BACKGROUND" || visualLayer === "CONTAINER_BACKDROP") {
        return "BACKGROUND";
    }
    if (visualLayer === "LABEL_BACKDROP"
            || visualLayer === "LABEL_OVERLAY_BACKDROP"
            || visualLayer === "LABEL_CONNECTOR_BACKDROP"
            || visualLayer === "CONTAINER_OUTLINE"
            || visualLayer === "FOREGROUND_MASK") {
        return "DECORATION";
    }
    return "CONTENT";
}

function _appendEditableTextFrameObjectPlans(objectPlans, sourceItems) {
    if (!objectPlans || !sourceItems) return;
    for (var i = 0; i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!src || String(src.kind || "") !== "TextFrame") continue;
        if (src.textFrameClass !== "editable") continue;
        if (src.hasText !== true) continue;
        var id = Number(src.id);
        if (isNaN(id)) continue;
        var pageIndex = src.pageIndex !== undefined && src.pageIndex !== null ? src.pageIndex : -1;
        var zOrder = src.zOrder !== undefined && src.zOrder !== null ? src.zOrder : 0;
        objectPlans.push(_textFrameObjectPlan(src, id, pageIndex, zOrder,
                "pass.editable_text_frames", "editable_text_frame"));
    }
}

function _appendVisibleTextFrameObjectPlans(objectPlans, sourceItems) {
    if (!objectPlans || !sourceItems) return;
    for (var i = 0; i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!src || String(src.kind || "") !== "TextFrame") continue;
        if (src.textFrameClass === "editable") continue;
        if (!_objectPlanSourceHasVisibleTextContent(src)) continue;
        var id = Number(src.id);
        if (isNaN(id)) continue;
        var pageIndex = src.pageIndex !== undefined && src.pageIndex !== null ? src.pageIndex : -1;
        var zOrder = src.zOrder !== undefined && src.zOrder !== null ? src.zOrder : 0;
        objectPlans.push(_textFrameObjectPlan(src, id, pageIndex, zOrder,
                "pass.visible_text_frames", "visible_text_frame"));
    }
}

function _appendEmptyEditableTextFrameObjectPlans(objectPlans, sourceItems) {
    if (!objectPlans || !sourceItems) return;
    for (var i = 0; i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!src || String(src.kind || "") !== "TextFrame") continue;
        if (src.textFrameClass !== "editable") continue;
        if (src.hasText === true) continue;
        if (Number(src.textLength || 0) !== 0) continue;
        if (src.hasTablesInStory === true) continue;
        if (src.visible === false) continue;
        if (src.hiddenLayer === true || src.nonprinting === true) continue;
        var id = Number(src.id);
        if (isNaN(id)) continue;
        var pageIndex = src.pageIndex !== undefined && src.pageIndex !== null ? src.pageIndex : -1;
        var zOrder = src.zOrder !== undefined && src.zOrder !== null ? src.zOrder : 0;
        if (src.storyAnchorPlacement === "INLINE"
                && _objectPlansHaveVisibleShellForFrame(objectPlans, id)) {
            objectPlans.push(_textFrameCleanupObjectPlan(src, id, pageIndex, zOrder,
                    "empty_text_frame_visual_shell"));
            continue;
        }
        if (_objectPlansHaveTextDecisionForFrame(objectPlans, id)) continue;
        objectPlans.push(_textFrameObjectPlan(src, id, pageIndex, zOrder,
                "pass.empty_editable_text_frames", "editable_text_frame"));
    }
}

function _appendTextFrameCleanupObjectPlans(objectPlans, sourceItems) {
    if (!objectPlans || !sourceItems) return;
    for (var i = 0; i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!src || String(src.kind || "") !== "TextFrame") continue;
        if (src.hiddenLayer !== true && src.nonprinting !== true) continue;
        var id = Number(src.id);
        if (isNaN(id)) continue;
        var pageIndex = src.pageIndex !== undefined && src.pageIndex !== null ? src.pageIndex : -1;
        var zOrder = src.zOrder !== undefined && src.zOrder !== null ? src.zOrder : 0;
        var reason = src.hiddenLayer === true ? "hidden_layer" : "nonprinting";
        objectPlans.push(_textFrameCleanupObjectPlan(src, id, pageIndex, zOrder, reason));
    }
}

function _appendTableOnlyTextFrameObjectPlans(objectPlans, sourceItems) {
    if (!objectPlans || !sourceItems) return;
    for (var i = 0; i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!src || String(src.kind || "") !== "TextFrame") continue;
        if (src.hasTablesInStory !== true) continue;
        if (src.storyHasVisibleTableCellText !== true) continue;
        if (src.markerOnlyContents !== true) continue;
        if (src.visible === false) continue;
        if (src.hiddenLayer === true || src.nonprinting === true) continue;
        var id = Number(src.id);
        if (isNaN(id)) continue;
        if (_objectPlansHaveTableStyleDecisionForFrame(objectPlans, id)) continue;
        var pageIndex = src.pageIndex !== undefined && src.pageIndex !== null ? src.pageIndex : -1;
        var zOrder = src.zOrder !== undefined && src.zOrder !== null ? src.zOrder : 0;
        objectPlans.push(_tableOnlyTextFrameObjectPlan(src, id, pageIndex, zOrder));
    }
}

function _objectPlansHaveTextDecisionForFrame(objectPlans, textFrameId) {
    var key = String(textFrameId);
    for (var i = 0; objectPlans && i < objectPlans.length; i++) {
        var plan = objectPlans[i];
        if (!plan) continue;
        if (String(plan.primarySourceObjectId) === key) return true;
        for (var t = 0; plan.ownedTextFrameIds && t < plan.ownedTextFrameIds.length; t++) {
            if (String(plan.ownedTextFrameIds[t]) === key) return true;
        }
    }
    return false;
}

function _objectPlansHaveTableStyleDecisionForFrame(objectPlans, textFrameId) {
    var key = String(textFrameId);
    for (var i = 0; objectPlans && i < objectPlans.length; i++) {
        var plan = objectPlans[i];
        if (!plan || plan.visualAction !== "PLACE_TABLE_STYLE") continue;
        if (String(plan.primarySourceObjectId) === key) return true;
        for (var t = 0; plan.ownedTextFrameIds && t < plan.ownedTextFrameIds.length; t++) {
            if (String(plan.ownedTextFrameIds[t]) === key) return true;
        }
    }
    return false;
}

function _objectPlansHaveVisibleShellForFrame(objectPlans, textFrameId) {
    var key = String(textFrameId);
    for (var i = 0; objectPlans && i < objectPlans.length; i++) {
        var plan = objectPlans[i];
        if (!plan || plan.visualAction !== "PLACE_TEXT_SHELL") continue;
        if (String(plan.primarySourceObjectId) === key) return true;
        for (var s = 0; plan.sourceObjectIds && s < plan.sourceObjectIds.length; s++) {
            if (String(plan.sourceObjectIds[s]) === key) return true;
        }
    }
    return false;
}

function _objectPlanSourceHasVisibleTextContent(src) {
    if (!src || src.hasText !== true) return false;
    if (src.visible === false) return false;
    if (src.hiddenLayer === true) return false;
    if (src.nonprinting === true) return false;
    if (src.markerOnlyContents === true) return false;
    return true;
}

function _textFrameCleanupObjectPlan(src, id, pageIndex, zOrder, reason) {
    var plan = _textFrameObjectPlan(src, id, pageIndex, zOrder,
            "pass.textframe_cleanup", reason);
    plan.textAction = "DROP_TEXT";
    plan.visualAction = "DROP_VISUAL";
    plan.candidatePurpose = reason;
    plan.reason = reason;
    plan.migrationStatus = "READY_TEXT_CLEANUP";
    return plan;
}

function _tableOnlyTextFrameObjectPlan(src, id, pageIndex, zOrder) {
    var tableIds = _sortedNumericIds(src.tableSourceObjectIds || []);
    var sourceIds = _sortedNumericIds([id].concat(tableIds));
    return {
        objectPlanId: "objectPlan.table_only_text_frame." + String(id),
        bundleId: "textFrame.tableOnly." + String(id),
        candidateId: null,
        passId: "pass.table_only_text_frames",
        pageIndex: pageIndex,
        kind: "TextFrame",
        mode: "TEXT_ONLY",
        candidatePurpose: "table_only_text_frame",
        compositeRole: null,
        slotRole: "TABLE_STYLE_SLOT",
        layoutOnlyInlineSlot: false,
        sourceInlineFlow: src.storyAnchorPlacement === "INLINE",
        inlineCompositeLayoutDescendant: false,
        connectorDecorationVisual: false,
        primarySourceObjectId: id,
        ownedByNativeShellSourceObjectIds: [],
        sourceObjectIds: sourceIds,
        sourceRootObjectIds: sourceIds,
        clusterSourceObjectIds: sourceIds,
        clusterKindCounts: { TextFrame: 1, Table: tableIds.length },
        omittedClusterSourceObjectIds: [],
        omittedClusterKindCounts: {},
        clusterHasEditableText: true,
        clusterHasTextFrame: true,
        clusterHasPlacedContent: false,
        clusterHasVisualSource: false,
        visualSourceObjectIds: [],
        styleSourceObjectIds: [],
        ownedTextFrameIds: [id],
        exportSourceObjectIds: [],
        hiddenVisualSourceObjectIds: [],
        materialization: "HWPX_TABLE_STYLE",
        textAction: "OWNED_BY_HWPX_TEXT",
        visualAction: "PLACE_TABLE_STYLE",
        placement: src.storyAnchorPlacement === "INLINE" ? "INLINE" : "FLOATING",
        coordinateSpace: src.storyAnchorPlacement === "INLINE" ? "STORY_FLOW" : "PAGE",
        visualLayer: "CONTENT_VISUAL",
        zOrder: zOrder,
        reason: "table_only_text_frame",
        bounds: src.bounds || null,
        ownershipSlot: "TABLE_STYLE_SLOT",
        policyLayer: "TEXT",
        clusterRelation: "EXACT_SOURCE_CLUSTER",
        migrationStatus: "READY_TABLE_STYLE",
        migrationBlocker: "NONE",
        migrationBlockerDetail: {},
        executable: true,
        required: true
    };
}

function _textFrameObjectPlan(src, id, pageIndex, zOrder, passId, reason) {
    return {
        objectPlanId: "objectPlan." + String(passId).replace(/^pass\./, "") + "." + String(id),
        bundleId: "textFrame." + String(id),
        candidateId: null,
        passId: passId,
        pageIndex: pageIndex,
        kind: "TextFrame",
        mode: "TEXT_ONLY",
        candidatePurpose: reason,
        compositeRole: null,
        slotRole: "TEXT_SLOT",
        layoutOnlyInlineSlot: false,
        sourceInlineFlow: src.storyAnchorPlacement === "INLINE",
        inlineCompositeLayoutDescendant: false,
        connectorDecorationVisual: false,
        primarySourceObjectId: id,
        ownedByNativeShellSourceObjectIds: [],
        sourceObjectIds: [id],
        sourceRootObjectIds: [id],
        clusterSourceObjectIds: [id],
        clusterKindCounts: { TextFrame: 1 },
        omittedClusterSourceObjectIds: [],
        omittedClusterKindCounts: {},
        clusterHasEditableText: src.textFrameClass === "editable",
        clusterHasTextFrame: true,
        clusterHasPlacedContent: false,
        clusterHasVisualSource: false,
        visualSourceObjectIds: [],
        styleSourceObjectIds: [],
        ownedTextFrameIds: [id],
        exportSourceObjectIds: [],
        hiddenVisualSourceObjectIds: [],
        materialization: "HWPX_TEXT",
        textAction: "OWNED_BY_HWPX_TEXT",
        visualAction: "DROP_VISUAL",
        placement: src.storyAnchorPlacement === "INLINE" ? "INLINE" : "FLOATING",
        coordinateSpace: src.storyAnchorPlacement === "INLINE" ? "STORY_FLOW" : "PAGE",
        visualLayer: "CONTENT_VISUAL",
        zOrder: zOrder,
        reason: reason,
        bounds: src.bounds || null,
        ownershipSlot: "TEXT_SLOT",
        policyLayer: "TEXT",
        clusterRelation: "EXACT_SOURCE_CLUSTER",
        migrationStatus: "READY_TEXT_ONLY",
        migrationBlocker: "NONE",
        migrationBlockerDetail: {},
        executable: true,
        required: true
    };
}

function _summarizeObjectPlans(objectPlans, validation) {
    var plans = objectPlans || [];
    var summary = {
        planCount: 0,
        executablePlanCount: 0,
        readyExactClusterCount: 0,
        migrationStatusCounts: {},
        textActionCounts: {},
        visualActionCounts: {},
        materializationCounts: {},
        placementCounts: {},
        coordinateSpaceCounts: {},
        visualLayerCounts: {},
        plansWithVisualSources: 0,
        plansWithStyleSources: 0,
        plansWithOwnedTextFrames: 0,
        importReadyPlanCount: validation ? validation.importReadyPlanCount : 0,
        issueCount: validation && validation.issues ? validation.issues.length : 0,
        contractStatusCounts: validation ? validation.contractStatusCounts || {} : {},
        issueCodeCounts: validation ? validation.issueCodeCounts || {} : {},
        migrationBlockerCounts: {}
    };
    for (var i = 0; i < plans.length; i++) {
        var plan = plans[i];
        if (!plan) continue;
        summary.planCount++;
        if (plan.executable) summary.executablePlanCount++;
        if (plan.migrationStatus === "READY_EXACT_CLUSTER") summary.readyExactClusterCount++;
        _incrementObjectPlanSummary(summary.migrationStatusCounts, plan.migrationStatus);
        _incrementObjectPlanSummary(summary.textActionCounts, plan.textAction);
        _incrementObjectPlanSummary(summary.visualActionCounts, plan.visualAction);
        _incrementObjectPlanSummary(summary.materializationCounts, plan.materialization);
        _incrementObjectPlanSummary(summary.placementCounts, plan.placement);
        _incrementObjectPlanSummary(summary.coordinateSpaceCounts, plan.coordinateSpace);
        _incrementObjectPlanSummary(summary.visualLayerCounts, plan.visualLayer);
        if (plan.visualSourceObjectIds && plan.visualSourceObjectIds.length > 0) summary.plansWithVisualSources++;
        if (plan.styleSourceObjectIds && plan.styleSourceObjectIds.length > 0) summary.plansWithStyleSources++;
        if (plan.ownedTextFrameIds && plan.ownedTextFrameIds.length > 0) summary.plansWithOwnedTextFrames++;
        _incrementObjectPlanSummary(summary.migrationBlockerCounts, plan.migrationBlocker || "NONE");
    }
    return summary;
}

function _resolveObjectPlanDuplicateTextOwners(objectPlans) {
    var plans = objectPlans || [];
    var ownersByTextFrameId = {};
    for (var i = 0; i < plans.length; i++) {
        var plan = plans[i];
        if (!plan || plan.textAction !== "OWNED_BY_HWPX_TEXT") continue;
        if (!plan.ownedTextFrameIds || plan.ownedTextFrameIds.length === 0) continue;
        for (var t = 0; t < plan.ownedTextFrameIds.length; t++) {
            var textId = String(plan.ownedTextFrameIds[t]);
            if (!ownersByTextFrameId[textId]) ownersByTextFrameId[textId] = [];
            ownersByTextFrameId[textId].push(plan);
        }
    }

    var canonicalByTextFrameId = {};
    var duplicateTextFrameCount = 0;
    for (var textKey in ownersByTextFrameId) {
        if (!ownersByTextFrameId.hasOwnProperty(textKey)) continue;
        var owners = ownersByTextFrameId[textKey];
        if (!owners || owners.length < 2) continue;
        duplicateTextFrameCount++;
        var canonical = owners[0];
        for (var o = 1; o < owners.length; o++) {
            if (_compareObjectPlanTextOwnerPriority(owners[o], canonical) < 0) {
                canonical = owners[o];
            }
        }
        canonicalByTextFrameId[textKey] = canonical;
    }

    var demoted = [];
    for (var p = 0; p < plans.length; p++) {
        var candidate = plans[p];
        if (!candidate || candidate.textAction !== "OWNED_BY_HWPX_TEXT") continue;
        if (!candidate.ownedTextFrameIds || candidate.ownedTextFrameIds.length === 0) continue;
        var duplicateOwnedCount = 0;
        var canonicalOwnedCount = 0;
        for (var c = 0; c < candidate.ownedTextFrameIds.length; c++) {
            var ownedTextId = String(candidate.ownedTextFrameIds[c]);
            var canonicalPlan = canonicalByTextFrameId[ownedTextId];
            if (!canonicalPlan) continue;
            duplicateOwnedCount++;
            if (canonicalPlan === candidate) canonicalOwnedCount++;
        }
        if (duplicateOwnedCount === 0) continue;
        if (canonicalOwnedCount > 0) {
            candidate.textOwnershipResolution = "CANONICAL_TEXT_OWNER";
            continue;
        }
        if (duplicateOwnedCount < candidate.ownedTextFrameIds.length) {
            candidate.textOwnershipResolution = "PARTIAL_DUPLICATE_TEXT_OWNER_UNRESOLVED";
            continue;
        }
        candidate.textAction = "DROP_TEXT";
        candidate.textOwnershipResolution = "DROPPED_DUPLICATE_TEXT_OWNER";
        candidate.textOwnershipResolutionReason = "more_local_text_shell_or_text_plan_owns_the_same_text_frame";
        candidate.reason = String(candidate.reason || "")
                + ":text_owner_dropped_duplicate";
        demoted.push(candidate.objectPlanId || candidate.bundleId || candidate.candidateId || ("plan.index." + p));
    }

    return {
        summary: {
            duplicateTextFrameCount: duplicateTextFrameCount,
            demotedPlanCount: demoted.length,
            demotedObjectPlanIds: demoted
        }
    };
}

function _compareObjectPlanTextOwnerPriority(a, b) {
    var scoreA = _objectPlanTextOwnerPriority(a);
    var scoreB = _objectPlanTextOwnerPriority(b);
    if (scoreA !== scoreB) return scoreB - scoreA;

    var aSourceCount = a && a.sourceObjectIds ? a.sourceObjectIds.length : 0;
    var bSourceCount = b && b.sourceObjectIds ? b.sourceObjectIds.length : 0;
    if (aSourceCount !== bSourceCount) return aSourceCount - bSourceCount;

    var aVisualCount = a && a.visualSourceObjectIds ? a.visualSourceObjectIds.length : 0;
    var bVisualCount = b && b.visualSourceObjectIds ? b.visualSourceObjectIds.length : 0;
    if (aVisualCount !== bVisualCount) return aVisualCount - bVisualCount;

    var aId = a && a.objectPlanId ? String(a.objectPlanId) : "";
    var bId = b && b.objectPlanId ? String(b.objectPlanId) : "";
    if (aId < bId) return -1;
    if (aId > bId) return 1;
    return 0;
}

function _objectPlanTextOwnerPriority(plan) {
    if (!plan) return 0;
    var score = 0;
    if (plan.visualAction === "DROP_VISUAL") score += 20;
    if (plan.visualAction === "PLACE_TEXT_SHELL") score += 40;
    if (plan.ownershipSlot === "SHELL_SLOT") score += 20;
    if (plan.slotRole === "direct_child_shell_slot") score += 120;
    if (plan.compositeRole === "direct_child_shell_slot") score += 100;
    if (plan.compositeRole === "native_parent_text_shell_slot") score += 80;
    if (plan.slotRole === "shell_slot_only" || plan.mode === "SLOT_ONLY") score += 40;
    if (plan.passId === "pass.inline_objects") score += 30;
    if (plan.passId === "pass.editable_textframe_visual_shells") score += 25;
    if (plan.passId === "pass.decoration_groups") score += 10;
    if (plan.sourceInlineFlow === true) score += 10;
    return score;
}

function _objectPlanFromPlannerBundle(bundle, index) {
    bundle = _normalizeObjectPlanBundle(bundle || {});
    var textAction = _objectPlanTextAction(bundle);
    var visualAction = _objectPlanVisualAction(bundle);
    var placement = _objectPlanPlacement(bundle);
    var coordinateSpace = _objectPlanCoordinateSpace(bundle, placement);
    var materialization = _objectPlanMaterialization(bundle, visualAction);
    var migrationStatus = _objectPlanMigrationStatus(bundle);
    var migrationBlocker = _objectPlanMigrationBlocker(bundle, migrationStatus);

    return {
        objectPlanId: _objectPlanId(bundle, index),
        bundleId: bundle.bundleId || null,
        candidateId: bundle.candidateId || null,
        passId: bundle.passId || null,
        pageIndex: bundle.pageIndex,
        kind: bundle.unit || null,
        mode: bundle.mode || null,
        candidatePurpose: bundle.candidatePurpose || null,
        compositeRole: bundle.compositeRole || null,
        slotRole: bundle.slotRole || null,
        layoutOnlyInlineSlot: bundle.layoutOnlyInlineSlot === true,
        sourceInlineFlow: bundle.sourceInlineFlow === true,
        inlineCompositeLayoutDescendant: bundle.inlineCompositeLayoutDescendant === true,
        connectorDecorationVisual: bundle.connectorDecorationVisual === true,
        primarySourceObjectId: bundle.primarySourceObjectId !== undefined
                ? bundle.primarySourceObjectId
                : null,
        ownedByNativeShellSourceObjectIds: _sortedNumericIds(
                bundle.ownedByNativeShellSourceObjectIds || []),
        sourceObjectIds: _sortedNumericIds(bundle.sourceObjectIds || []),
        sourceRootObjectIds: _sortedNumericIds(bundle.sourceRootObjectIds || []),
        clusterSourceObjectIds: _sortedNumericIds(bundle.clusterSourceObjectIds || []),
        clusterKindCounts: bundle.clusterKindCounts || {},
        omittedClusterSourceObjectIds: _sortedNumericIds(bundle.omittedClusterSourceObjectIds || []),
        omittedClusterKindCounts: bundle.omittedClusterKindCounts || {},
        clusterHasEditableText: bundle.clusterHasEditableText === true,
        clusterHasTextFrame: bundle.clusterHasTextFrame === true,
        clusterHasPlacedContent: bundle.clusterHasPlacedContent === true,
        clusterHasVisualSource: bundle.clusterHasVisualSource === true,
        visualSourceObjectIds: _sortedNumericIds(bundle.visualSourceObjectIds || []),
        styleSourceObjectIds: _sortedNumericIds(bundle.styleSourceObjectIds || []),
        ownedTextFrameIds: _sortedNumericIds(bundle.ownedTextFrameIds || []),
        exportSourceObjectIds: _sortedNumericIds(bundle.exportSourceObjectIds || []),
        hiddenVisualSourceObjectIds: _sortedNumericIds(bundle.hiddenVisualSourceObjectIds || []),
        materialization: materialization,
        textAction: textAction,
        visualAction: visualAction,
        placement: placement,
        coordinateSpace: coordinateSpace,
        visualLayer: _objectPlanVisualLayer(bundle),
        zOrder: bundle.zOrder !== undefined ? bundle.zOrder : null,
        reason: _objectPlanReason(bundle, migrationStatus),
        bounds: bundle.bounds || null,
        ownershipSlot: bundle.ownershipSlot || null,
        policyLayer: bundle.policyLayer || null,
        clusterRelation: bundle.clusterRelation || null,
        migrationStatus: migrationStatus,
        migrationBlocker: migrationBlocker.code,
        migrationBlockerDetail: migrationBlocker.detail,
        executable: bundle.executable === true,
        required: bundle.required === true
    };
}

function _normalizeObjectPlanBundle(bundle) {
    if (_objectPlanBundleIsClosedPlacedContentCarrier(bundle)) {
        return _closedPlacedContentCarrierBundle(bundle);
    }
    return bundle || {};
}

function _objectPlanBundleIsClosedPlacedContentCarrier(bundle) {
    if (!bundle) return false;
    if (bundle.passId !== "pass.image_placed_frames") return false;
    if (bundle.ownershipSlot !== "CONTENT_VISUAL_SLOT") return false;
    if (bundle.clusterRelation !== "BUNDLE_NARROWER_THAN_CLUSTER") return false;
    if (bundle.clusterHasPlacedContent !== true) return false;
    if (bundle.clusterHasTextFrame === true || bundle.clusterHasEditableText === true) return false;
    if (!bundle.clusterSourceObjectIds || bundle.clusterSourceObjectIds.length === 0) return false;
    if (!bundle.sourceObjectIds || bundle.sourceObjectIds.length === 0) return false;
    if (!_sourceSetContainsAll(bundle.clusterSourceObjectIds || [], bundle.sourceObjectIds || [])) return false;
    if (bundle.ownedTextFrameIds && bundle.ownedTextFrameIds.length > 0) return false;
    if (!_objectPlanOmittedClusterSourcesArePlacedMedia(bundle)) return false;
    return true;
}

function _objectPlanOmittedClusterSourcesArePlacedMedia(bundle) {
    if (!bundle || !bundle.omittedClusterKindCounts) return false;
    var omittedCount = 0;
    for (var key in bundle.omittedClusterKindCounts) {
        if (!bundle.omittedClusterKindCounts.hasOwnProperty(key)) continue;
        var count = Number(bundle.omittedClusterKindCounts[key] || 0);
        if (count <= 0) continue;
        omittedCount += count;
        if (key !== "Image" && key !== "PDF") return false;
    }
    return omittedCount > 0;
}

function _closedPlacedContentCarrierBundle(bundle) {
    var closed = {};
    for (var key in bundle) {
        if (bundle.hasOwnProperty(key)) closed[key] = bundle[key];
    }
    var clusterIds = _sortedNumericIds(bundle.clusterSourceObjectIds || []);
    closed.sourceObjectIds = clusterIds;
    closed.visualSourceObjectIds = clusterIds.slice(0);
    closed.exportSourceObjectIds = clusterIds.slice(0);
    closed.sourceRootObjectIds = _sortedNumericIds(bundle.sourceRootObjectIds || bundle.sourceObjectIds || []);
    closed.omittedClusterSourceObjectIds = [];
    closed.omittedClusterKindCounts = {};
    closed.clusterRelation = "EXACT_SOURCE_CLUSTER";
    closed.closedPlacedContentCarrier = true;
    return closed;
}

function _objectPlanId(bundle, index) {
    var id = bundle && bundle.bundleId ? String(bundle.bundleId) : ("bundle.index." + index);
    return "objectPlan." + id.replace(/[^A-Za-z0-9_.-]/g, "_");
}

function _objectPlanTextAction(bundle) {
    if (bundle && bundle.ownershipSlot === "SHELL_SLOT"
            && _objectPlanVisualAction(bundle) === "DROP_VISUAL") {
        return "DROP_TEXT";
    }
    if (bundle.ownedTextFrameIds && bundle.ownedTextFrameIds.length > 0) {
        return "OWNED_BY_HWPX_TEXT";
    }
    if (!bundle || bundle.executable !== true) return "DROP_TEXT";
    if (bundle.ownershipSlot === "SHELL_SLOT") return "DROP_TEXT";
    return "DROP_TEXT";
}

function _objectPlanVisualAction(bundle) {
    if (!bundle || bundle.executable !== true) return "DROP_VISUAL";
    if (bundle.layoutOnlyInlineSlot === true) return "DROP_VISUAL";
    if (bundle.ownershipSlot === "TABLE_STYLE_SLOT") return "PLACE_TABLE_STYLE";
    if (bundle.policyLayer === "BACKGROUND") return "PLACE_FLOATING_PNG";
    if (bundle.passId === "pass.inline_objects"
            && (!bundle.ownedTextFrameIds || bundle.ownedTextFrameIds.length === 0)) {
        return _objectPlanPlacement(bundle) === "INLINE" ? "PLACE_INLINE_PNG" : "PLACE_FLOATING_PNG";
    }
    if (bundle.ownershipSlot === "SHELL_SLOT") return "PLACE_TEXT_SHELL";
    if (_objectPlanBundleIsInlineTextWithoutVisibleVisual(bundle)) return "DROP_VISUAL";
    if (bundle.passId === "pass.inline_objects") {
        return _objectPlanPlacement(bundle) === "INLINE" ? "PLACE_INLINE_PNG" : "PLACE_FLOATING_PNG";
    }
    if (bundle.ownershipSlot === "CONTENT_VISUAL_SLOT") {
        return bundle.passId === "pass.inline_objects" && _objectPlanPlacement(bundle) === "INLINE"
                ? "PLACE_INLINE_PNG"
                : "PLACE_FLOATING_PNG";
    }
    return "DROP_VISUAL";
}

function _objectPlanBundleIsInlineTextWithoutVisibleVisual(bundle) {
    if (!bundle || bundle.passId !== "pass.inline_objects") return false;
    if (!bundle.ownedTextFrameIds || bundle.ownedTextFrameIds.length === 0) return false;
    if (bundle.visualSourceObjectIds && bundle.visualSourceObjectIds.length > 0) return false;
    if (bundle.styleSourceObjectIds && bundle.styleSourceObjectIds.length > 0) return false;
    if (bundle.exportSourceObjectIds && bundle.exportSourceObjectIds.length > 0) return false;
    return true;
}

function _objectPlanPlacement(bundle) {
    if (_objectPlanBundleIsInlineCompositeLayoutDescendantVisual(bundle)) return "FLOATING";
    if (_objectPlanBundleIsInlineFlowShell(bundle)) return "INLINE";
    if (_objectPlanBundleIsInlineTextOwningShell(bundle)) return "INLINE";
    if (bundle && bundle.passId === "pass.inline_objects") {
        var anchoredPosition = String(bundle.anchoredPosition || "").toUpperCase();
        if (bundle.storyAnchorPlacement === "FLOATING_ANCHORED" || anchoredPosition === "ANCHORED") {
            return "FLOATING";
        }
        return "INLINE";
    }
    return "FLOATING";
}

function _objectPlanBundleIsInlineFlowShell(bundle) {
    if (!bundle || bundle.sourceInlineFlow !== true) return false;
    if (bundle.ownershipSlot !== "SHELL_SLOT") return false;
    return bundle.slotRole === "direct_child_shell_slot"
            || bundle.compositeRole === "direct_child_shell_slot";
}

function _objectPlanBundleIsInlineTextOwningShell(bundle) {
    if (!bundle || bundle.passId !== "pass.inline_objects") return false;
    if (bundle.ownershipSlot !== "SHELL_SLOT") return false;
    if (!bundle.ownedTextFrameIds || bundle.ownedTextFrameIds.length === 0) return false;
    return bundle.slotRole === "direct_child_shell_slot"
            || bundle.compositeRole === "direct_child_shell_slot";
}

function _objectPlanBundleIsInlineCompositeLayoutDescendantShell(bundle) {
    if (!bundle || bundle.inlineCompositeLayoutDescendant !== true) return false;
    if (bundle.ownershipSlot !== "SHELL_SLOT") return false;
    return bundle.slotRole === "direct_child_shell_slot"
            || bundle.compositeRole === "direct_child_shell_slot"
            || bundle.compositeRole === "native_parent_text_shell_slot";
}

function _objectPlanBundleIsInlineCompositeLayoutDescendantVisual(bundle) {
    if (!bundle || bundle.inlineCompositeLayoutDescendant !== true) return false;
    if (bundle.ownershipSlot === "SHELL_SLOT") {
        return _objectPlanBundleIsInlineCompositeLayoutDescendantShell(bundle);
    }
    return bundle.ownershipSlot === "CONTENT_VISUAL_SLOT"
            && bundle.visualSourceObjectIds
            && bundle.visualSourceObjectIds.length > 0;
}

function _objectPlanCoordinateSpace(bundle, placement) {
    if (placement === "INLINE") return "STORY_FLOW";
    return "PAGE";
}

function _objectPlanMaterialization(bundle, visualAction) {
    if (!bundle) return "EXTRACTED_PNG_VECTOR";
    if (visualAction === "PLACE_TABLE_STYLE") return "HWPX_TABLE_STYLE";
    if (visualAction === "ABSORB_TEXT_STYLE") return "HWPX_TEXT";
    if (visualAction === "DROP_VISUAL") return "HWPX_TEXT";
    return bundle.materialization || "EXTRACTED_PNG_VECTOR";
}

function _objectPlanVisualLayer(bundle) {
    if (!bundle || !bundle.policyLayer) return "CONTENT_VISUAL";
    if (bundle.policyLayer === "BACKGROUND") return "PAGE_BACKGROUND";
    if (bundle.policyLayer === "DECORATION") {
        if (bundle.connectorDecorationVisual === true) {
            return "LABEL_CONNECTOR_BACKDROP";
        }
        if (bundle.compositeRole === "native_parent_text_shell_slot") {
            return "LABEL_OVERLAY_BACKDROP";
        }
        if (bundle.passId === "pass.vector_shape_frames"
                && bundle.ownershipSlot === "SHELL_SLOT"
                && bundle.materialization === "NATIVE_SOURCE_SHAPE") {
            return "CONTAINER_OUTLINE";
        }
        return "LABEL_BACKDROP";
    }
    return "CONTENT_VISUAL";
}

function _objectPlanMigrationStatus(bundle) {
    if (!bundle) return "NEEDS_BUNDLE_SOURCE_POLICY";
    if (bundle.layoutOnlyInlineSlot === true) return "READY_LAYOUT_ONLY_INLINE_SLOT";
    if (bundle.clusterRelation === "EXACT_SOURCE_CLUSTER") return "READY_EXACT_CLUSTER";
    if (_objectPlanHasInlineTextlessSiblingDecorationContract(bundle)) return "READY_TEXTLESS_CONNECTOR_FRAGMENT";
    if (_objectPlanHasExplicitSlotOnlyContract(bundle)) return "READY_SLOT_ONLY_CLUSTER_FRAGMENT";
    if (_objectPlanHasClosedPlacedContentFrameContract(bundle)) return "READY_CLOSED_PLACED_CONTENT_FRAME";
    if (_objectPlanHasPageTextlessGraphicGroupContract(bundle)) return "READY_PAGE_TEXTLESS_GRAPHIC_GROUP";
    if (bundle.clusterRelation === "PAGE_OR_SYNTHETIC_BUNDLE") return "NEEDS_SYNTHETIC_SOURCE_MODEL";
    if (bundle.clusterRelation === "NO_CLUSTER_REFERENCE") return "NEEDS_SYNTHETIC_SOURCE_MODEL";
    if (bundle.clusterRelation === "BUNDLE_NARROWER_THAN_CLUSTER") return "NEEDS_VISIBLE_SLOT_EXPLICITNESS";
    if (bundle.clusterRelation === "BUNDLE_BROADER_THAN_CLUSTER") return "NEEDS_COMPOSITE_BUNDLE_POLICY";
    return "NEEDS_BUNDLE_SOURCE_POLICY";
}

function _objectPlanReason(bundle, migrationStatus) {
    var parts = ["diagnostic_from_planner_bundle", migrationStatus || "UNKNOWN"];
    if (bundle && bundle.passId) parts.push(bundle.passId);
    if (bundle && bundle.ownershipSlot) parts.push(bundle.ownershipSlot);
    if (bundle && bundle.clusterRelation) parts.push(bundle.clusterRelation);
    return parts.join(":");
}

function _objectPlanMigrationBlocker(bundle, migrationStatus) {
    if (_objectPlanMigrationStatusIsImportReady(migrationStatus)) {
        return _objectPlanMigrationBlockerResult("NONE", bundle, {
            note: migrationStatus === "READY_SLOT_ONLY_CLUSTER_FRAGMENT"
                    ? "sourceObjectIds keep broad ancestry while exportSourceObjectIds and hiddenVisualSourceObjectIds declare the slot-only visual contract"
                    : (migrationStatus === "READY_CLOSED_PLACED_CONTENT_FRAME"
                            ? "visualSourceObjectIds include the placed content descendant source tree for the frame"
                            : "sourceObjectIds match the recursive source cluster")
        });
    }
    if (!bundle) {
        return _objectPlanMigrationBlockerResult("MISSING_PLANNER_BUNDLE", bundle, {});
    }
    if (migrationStatus === "NEEDS_SYNTHETIC_SOURCE_MODEL") {
        if (bundle.passId === "pass.page_backgrounds") {
            return _objectPlanMigrationBlockerResult("SYNTHETIC_PAGE_BACKGROUND_NEEDS_SOURCE_MODEL", bundle, {
                nextPolicyQuestion: "Stage 1 must point each page background render at the source bundle/page fragment that owns the visible background slot."
            });
        }
        return _objectPlanMigrationBlockerResult("NO_CLUSTER_REFERENCE_NEEDS_SOURCE_MODEL", bundle, {
            nextPolicyQuestion: "Stage 1 must declare the source root for this candidate before it can be imported."
        });
    }
    if (migrationStatus === "NEEDS_VISIBLE_SLOT_EXPLICITNESS") {
        if (bundle.hiddenVisualSourceObjectIds && bundle.hiddenVisualSourceObjectIds.length > 0) {
            return _objectPlanMigrationBlockerResult("SLOT_ONLY_HIDDEN_CHILDREN_NEEDS_OBJECTPLAN", bundle, {
                nextPolicyQuestion: "Stage 1 must keep ancestry broad, but write the hidden child ids as an explicit slot-only export contract."
            });
        }
        if (_objectPlanNarrowerVectorCompetesWithPlacedContent(bundle)) {
            return _objectPlanMigrationBlockerResult("NATIVE_SHAPE_WITH_PLACED_CONTENT_NEEDS_CONTENT_OWNER_POLICY", bundle, {
                nextPolicyQuestion: "Stage 1 must choose the placed-content owner for this source cluster or declare a closed native-shape owner; a partial vector source is not import-ready."
            });
        }
        if (_objectPlanNarrowerShellOmitsEditableText(bundle)) {
            return _objectPlanMigrationBlockerResult("TEXT_OWNING_SHELL_FRAGMENT_NEEDS_TEXT_SLOT_SPLIT", bundle, {
                nextPolicyQuestion: "Stage 1 must keep shell visuals and editable TextFrame owners in one source bundle with explicit visual/text slot fields."
            });
        }
        if (_objectPlanNarrowerDecorationFragment(bundle)) {
            return _objectPlanMigrationBlockerResult("DECORATION_FRAGMENT_NEEDS_CLOSED_SHELL_CONTRACT", bundle, {
                nextPolicyQuestion: "Stage 1 must declare whether this decoration fragment is a closed textless shell owner or a slot-only fragment with explicit omitted descendants."
            });
        }
        return _objectPlanMigrationBlockerResult("BUNDLE_NARROWER_THAN_CLUSTER_NEEDS_SLOT_POLICY", bundle, {
            nextPolicyQuestion: "Stage 1 must explain which descendant slot this narrower visible source set owns."
        });
    }
    if (migrationStatus === "NEEDS_COMPOSITE_BUNDLE_POLICY") {
        if (bundle.passId === "pass.master_page_graphics") {
            return _objectPlanMigrationBlockerResult("COMPOSITE_MASTER_BUNDLE_NEEDS_PAGE_APPLIED_POLICY", bundle, {
                nextPolicyQuestion: "Stage 1 must declare the applied master source bundle and the page-local visible fragment as separate identities."
            });
        }
        return _objectPlanMigrationBlockerResult("COMPOSITE_BUNDLE_NEEDS_EXPLICIT_SLOT_SPLIT", bundle, {
            nextPolicyQuestion: "Stage 1 must split a broad composite into explicit visible slots or declare it as one closed composite owner."
        });
    }
    return _objectPlanMigrationBlockerResult("DIVERGENT_BUNDLE_NEEDS_SOURCE_BUNDLE_POLICY", bundle, {
        nextPolicyQuestion: "Stage 1 must reconcile a candidate source set that neither contains nor is contained by the recursive source cluster."
    });
}

function _objectPlanHasExplicitSlotOnlyContract(bundle) {
    if (!bundle) return false;
    if (bundle.clusterRelation !== "BUNDLE_NARROWER_THAN_CLUSTER") return false;
    if (bundle.ownershipSlot !== "SHELL_SLOT" && bundle.ownershipSlot !== "CONTENT_VISUAL_SLOT") return false;
    if (bundle.mode !== "SLOT_ONLY"
            && !(bundle.ownershipSlot === "SHELL_SLOT" && bundle.slotRole === "shell_slot_only")) return false;
    if (!bundle.exportSourceObjectIds || bundle.exportSourceObjectIds.length === 0) return false;
    if (!bundle.hiddenVisualSourceObjectIds || bundle.hiddenVisualSourceObjectIds.length === 0) return false;
    if (!bundle.visualSourceObjectIds || bundle.visualSourceObjectIds.length === 0) return false;
    if (!_sourceSetContainsAll(bundle.sourceObjectIds || [], bundle.visualSourceObjectIds || [])) return false;
    if (!_sourceSetContainsAll(bundle.exportSourceObjectIds || [], bundle.visualSourceObjectIds || [])) return false;
    if (_sourceSetsIntersect(bundle.visualSourceObjectIds || [], bundle.hiddenVisualSourceObjectIds || [])) return false;
    return true;
}

function _objectPlanHasInlineTextlessSiblingDecorationContract(bundle) {
    if (!bundle) return false;
    if (bundle.clusterRelation !== "BUNDLE_NARROWER_THAN_CLUSTER") return false;
    if (bundle.passId !== "pass.inline_objects") return false;
    if (bundle.slotRole !== "inline_textless_sibling_decoration_slot"
            && bundle.compositeRole !== "inline_textless_sibling_decoration_slot") return false;
    if (bundle.connectorDecorationVisual !== true) return false;
    if (bundle.clusterHasEditableText === true || bundle.clusterHasTextFrame === true) return false;
    if (bundle.clusterHasPlacedContent === true) return false;
    if (!bundle.visualSourceObjectIds || bundle.visualSourceObjectIds.length === 0) return false;
    if (!bundle.exportSourceObjectIds || bundle.exportSourceObjectIds.length === 0) return false;
    if (!_sourceSetContainsAll(bundle.sourceObjectIds || [], bundle.visualSourceObjectIds || [])) return false;
    if (!_sourceSetContainsAll(bundle.exportSourceObjectIds || [], bundle.visualSourceObjectIds || [])) return false;
    return true;
}

function _objectPlanNarrowerVectorCompetesWithPlacedContent(bundle) {
    if (!bundle) return false;
    return bundle.passId === "pass.vector_shape_frames"
            && bundle.ownershipSlot === "CONTENT_VISUAL_SLOT"
            && bundle.clusterHasPlacedContent === true;
}

function _objectPlanNarrowerShellOmitsEditableText(bundle) {
    if (!bundle) return false;
    return bundle.ownershipSlot === "SHELL_SLOT"
            && bundle.clusterHasEditableText === true
            && bundle.ownedTextFrameIds
            && bundle.ownedTextFrameIds.length === 0;
}

function _objectPlanNarrowerDecorationFragment(bundle) {
    if (!bundle) return false;
    return bundle.passId === "pass.decoration_groups"
            && bundle.ownershipSlot === "SHELL_SLOT"
            && bundle.clusterRelation === "BUNDLE_NARROWER_THAN_CLUSTER";
}

function _objectPlanHasClosedPlacedContentFrameContract(bundle) {
    if (!bundle) return false;
    if (bundle.clusterRelation !== "BUNDLE_NARROWER_THAN_CLUSTER") return false;
    if (bundle.passId !== "pass.image_placed_frames") return false;
    if (bundle.ownershipSlot !== "CONTENT_VISUAL_SLOT") return false;
    if (bundle.ownedTextFrameIds && bundle.ownedTextFrameIds.length > 0) return false;
    if (!bundle.clusterSourceObjectIds || bundle.clusterSourceObjectIds.length === 0) return false;
    if (!bundle.visualSourceObjectIds || bundle.visualSourceObjectIds.length === 0) return false;
    return _sourceSetKey(bundle.clusterSourceObjectIds) === _sourceSetKey(bundle.visualSourceObjectIds);
}

function _objectPlanHasPageTextlessGraphicGroupContract(bundle) {
    if (!bundle) return false;
    if (bundle.passId !== "pass.page_textless_graphic_groups") return false;
    if (bundle.ownershipSlot !== "CONTENT_VISUAL_SLOT") return false;
    if (bundle.materialization !== "EXTRACTED_PNG_VECTOR") return false;
    if (bundle.ownedTextFrameIds && bundle.ownedTextFrameIds.length > 0) return false;
    if (!bundle.sourceObjectIds || bundle.sourceObjectIds.length < 2) return false;
    if (!bundle.visualSourceObjectIds || bundle.visualSourceObjectIds.length < 2) return false;
    if (!bundle.exportSourceObjectIds || bundle.exportSourceObjectIds.length < 2) return false;
    if (!_sourceSetContainsAll(bundle.sourceObjectIds || [], bundle.visualSourceObjectIds || [])) return false;
    if (!_sourceSetContainsAll(bundle.exportSourceObjectIds || [], bundle.visualSourceObjectIds || [])) return false;
    return true;
}

function _objectPlanMigrationStatusIsImportReady(status) {
    return status === "READY_EXACT_CLUSTER"
            || status === "READY_SLOT_ONLY_CLUSTER_FRAGMENT"
            || status === "READY_CLOSED_PLACED_CONTENT_FRAME"
            || status === "READY_PAGE_TEXTLESS_GRAPHIC_GROUP"
            || status === "READY_TEXTLESS_CONNECTOR_FRAGMENT"
            || status === "READY_LAYOUT_ONLY_INLINE_SLOT";
}

function _objectPlanMigrationBlockerResult(code, bundle, extraDetail) {
    var detail = {
        passId: bundle && bundle.passId ? bundle.passId : null,
        ownershipSlot: bundle && bundle.ownershipSlot ? bundle.ownershipSlot : null,
        clusterRelation: bundle && bundle.clusterRelation ? bundle.clusterRelation : null,
        primarySourceObjectId: bundle && bundle.primarySourceObjectId !== undefined ? bundle.primarySourceObjectId : null,
        sourceObjectCount: bundle && bundle.sourceObjectIds ? bundle.sourceObjectIds.length : 0,
        visualSourceObjectCount: bundle && bundle.visualSourceObjectIds ? bundle.visualSourceObjectIds.length : 0,
        styleSourceObjectCount: bundle && bundle.styleSourceObjectIds ? bundle.styleSourceObjectIds.length : 0,
        ownedTextFrameCount: bundle && bundle.ownedTextFrameIds ? bundle.ownedTextFrameIds.length : 0,
        exportSourceObjectCount: bundle && bundle.exportSourceObjectIds ? bundle.exportSourceObjectIds.length : 0,
        hiddenVisualSourceObjectCount: bundle && bundle.hiddenVisualSourceObjectIds ? bundle.hiddenVisualSourceObjectIds.length : 0,
        omittedClusterSourceObjectCount: bundle && bundle.omittedClusterSourceObjectIds ? bundle.omittedClusterSourceObjectIds.length : 0,
        clusterKindCounts: bundle && bundle.clusterKindCounts ? bundle.clusterKindCounts : {},
        omittedClusterKindCounts: bundle && bundle.omittedClusterKindCounts ? bundle.omittedClusterKindCounts : {},
        clusterHasEditableText: bundle && bundle.clusterHasEditableText === true,
        clusterHasTextFrame: bundle && bundle.clusterHasTextFrame === true,
        clusterHasPlacedContent: bundle && bundle.clusterHasPlacedContent === true,
        clusterHasVisualSource: bundle && bundle.clusterHasVisualSource === true
    };
    for (var key in extraDetail) {
        if (extraDetail.hasOwnProperty(key)) detail[key] = extraDetail[key];
    }
    return {
        code: code || "UNKNOWN_MIGRATION_BLOCKER",
        detail: detail
    };
}

function _validateObjectPlanDiagnostics(objectPlans) {
    var plans = objectPlans || [];
    var issues = [];
    var issueCodeCounts = {};
    var issuePlanIds = {};
    var visibleSlotOwners = {};
    var textOwners = {};
    var placementBySlot = {};
    var importReadyPlanCount = 0;
    var contractStatusCounts = {};

    for (var i = 0; i < plans.length; i++) {
        var plan = plans[i];
        if (!plan) continue;
        _validateObjectPlanRequiredFields(plan, issues, issueCodeCounts, issuePlanIds);
        _validateObjectPlanTextVisualSeparation(plan, issues, issueCodeCounts, issuePlanIds);
        _validateObjectPlanCoordinateContract(plan, issues, issueCodeCounts, issuePlanIds);

        if (_objectPlanHasVisibleVisual(plan)) {
            var slotKey = _objectPlanVisibleSlotKey(plan);
            if (slotKey) {
                if (!visibleSlotOwners[slotKey]) visibleSlotOwners[slotKey] = [];
                visibleSlotOwners[slotKey].push(plan);
                if (!placementBySlot[slotKey]) placementBySlot[slotKey] = {};
                placementBySlot[slotKey][plan.placement || "UNKNOWN"] = true;
            }
        }
        if (plan.textAction === "OWNED_BY_HWPX_TEXT" && plan.ownedTextFrameIds) {
            for (var t = 0; t < plan.ownedTextFrameIds.length; t++) {
                var textId = String(plan.ownedTextFrameIds[t]);
                if (!textOwners[textId]) textOwners[textId] = [];
                textOwners[textId].push(plan);
            }
        }
    }

    for (var key in visibleSlotOwners) {
        if (!visibleSlotOwners.hasOwnProperty(key)) continue;
        if (visibleSlotOwners[key].length > 1) {
            _pushObjectPlanIssue(issues, issueCodeCounts, issuePlanIds,
                    "duplicate_visible_source_slot", visibleSlotOwners[key],
                    { slotKey: key });
        }
        if (_objectPlanMapKeyCount(placementBySlot[key]) > 1) {
            _pushObjectPlanIssue(issues, issueCodeCounts, issuePlanIds,
                    "inline_floating_visible_slot_conflict", visibleSlotOwners[key],
                    { slotKey: key });
        }
    }

    for (var textKey in textOwners) {
        if (!textOwners.hasOwnProperty(textKey)) continue;
        if (textOwners[textKey].length > 1) {
            _pushObjectPlanIssue(issues, issueCodeCounts, issuePlanIds,
                    "duplicate_hwpx_text_owner", textOwners[textKey],
                    { textFrameId: textKey });
        }
    }

    for (var p = 0; p < plans.length; p++) {
        var objectPlan = plans[p];
        if (!objectPlan) continue;
        var status = issuePlanIds[objectPlan.objectPlanId]
                ? "NEEDS_POLICY_OR_METADATA"
                : (_objectPlanMigrationStatusIsImportReady(objectPlan.migrationStatus)
                        ? "READY_FOR_STAGE1_IMPORT"
                        : "NEEDS_MIGRATION_POLICY");
        objectPlan.contractStatus = status;
        if (status === "READY_FOR_STAGE1_IMPORT") importReadyPlanCount++;
        _incrementObjectPlanSummary(contractStatusCounts, status);
    }

    return {
        issueCount: issues.length,
        importReadyPlanCount: importReadyPlanCount,
        contractStatusCounts: contractStatusCounts,
        issueCodeCounts: issueCodeCounts,
        issues: issues
    };
}

function _validateObjectPlanRequiredFields(plan, issues, issueCodeCounts, issuePlanIds) {
    var missing = [];
    if (!plan.textAction) missing.push("textAction");
    if (!plan.visualAction) missing.push("visualAction");
    if (!plan.placement) missing.push("placement");
    if (!plan.visualLayer) missing.push("visualLayer");
    if (!plan.materialization) missing.push("materialization");
    if (!plan.coordinateSpace) missing.push("coordinateSpace");
    if (plan.zOrder === null || plan.zOrder === undefined) missing.push("zOrder");
    if (missing.length > 0) {
        _pushObjectPlanIssue(issues, issueCodeCounts, issuePlanIds,
                "missing_required_object_plan_fields", [plan],
                { missingFields: missing });
    }
    if (_objectPlanHasVisibleVisual(plan)
            && plan.visualAction !== "PLACE_TABLE_STYLE"
            && !_objectPlanHasVisibleSourceEvidence(plan)
            && plan.passId !== "pass.page_backgrounds") {
        _pushObjectPlanIssue(issues, issueCodeCounts, issuePlanIds,
                "visible_visual_without_visual_sources", [plan], {});
    }
}

function _objectPlanHasVisibleSourceEvidence(plan) {
    if (!plan) return false;
    if (plan.visualSourceObjectIds && plan.visualSourceObjectIds.length > 0) return true;
    if (plan.visualAction === "PLACE_TEXT_SHELL"
            && plan.styleSourceObjectIds
            && plan.styleSourceObjectIds.length > 0) {
        return true;
    }
    return false;
}

function _validateObjectPlanTextVisualSeparation(plan, issues, issueCodeCounts, issuePlanIds) {
    if (!plan.visualSourceObjectIds || !plan.ownedTextFrameIds) return;
    var owned = _sourceIdSet(plan.ownedTextFrameIds);
    var overlap = [];
    for (var i = 0; i < plan.visualSourceObjectIds.length; i++) {
        var id = plan.visualSourceObjectIds[i];
        if (owned[String(id)]) overlap.push(id);
    }
    if (overlap.length > 0 && _objectPlanAllowsTextFrameShellSourceOverlap(plan, overlap)) {
        return;
    }
    if (overlap.length > 0) {
        _pushObjectPlanIssue(issues, issueCodeCounts, issuePlanIds,
                "visual_sources_include_owned_text_frames", [plan],
                { overlappingIds: overlap });
    }
}

function _objectPlanAllowsTextFrameShellSourceOverlap(plan, overlap) {
    if (!plan || !overlap || overlap.length === 0) return false;
    if (plan.ownershipSlot !== "SHELL_SLOT") return false;
    if (plan.visualAction !== "PLACE_TEXT_SHELL") return false;
    if (plan.materialization !== "EXTRACTED_PNG_VECTOR") return false;
    if (plan.passId !== "pass.editable_textframe_visual_shells") return false;
    if (!plan.styleSourceObjectIds || plan.styleSourceObjectIds.length === 0) return false;
    var styleIds = _sourceIdSet(plan.styleSourceObjectIds);
    for (var i = 0; i < overlap.length; i++) {
        if (!styleIds[String(overlap[i])]) return false;
    }
    return true;
}

function _validateObjectPlanCoordinateContract(plan, issues, issueCodeCounts, issuePlanIds) {
    if (plan.placement === "INLINE" && plan.coordinateSpace !== "STORY_FLOW") {
        _pushObjectPlanIssue(issues, issueCodeCounts, issuePlanIds,
                "inline_coordinate_space_mismatch", [plan],
                { expected: "STORY_FLOW", actual: plan.coordinateSpace });
    }
    if (plan.placement === "FLOATING" && plan.coordinateSpace !== "PAGE") {
        _pushObjectPlanIssue(issues, issueCodeCounts, issuePlanIds,
                "floating_coordinate_space_mismatch", [plan],
                { expected: "PAGE", actual: plan.coordinateSpace });
    }
}

function _objectPlanHasVisibleVisual(plan) {
    return plan && (plan.visualAction === "PLACE_INLINE_PNG"
            || plan.visualAction === "PLACE_FLOATING_PNG"
            || plan.visualAction === "PLACE_TEXT_SHELL"
            || plan.visualAction === "PLACE_TABLE_STYLE");
}

function _objectPlanVisibleSlotKey(plan) {
    if (!plan) return "";
    var slot = plan.ownershipSlot || _objectPlanSlotFromActions(plan);
    var ids = plan.visualSourceObjectIds && plan.visualSourceObjectIds.length > 0
            ? plan.visualSourceObjectIds
            : (plan.styleSourceObjectIds && plan.styleSourceObjectIds.length > 0
                    ? plan.styleSourceObjectIds
                    : plan.sourceObjectIds);
    var sourceKey = _sourceSetKey(ids || []);
    if (!sourceKey) sourceKey = "synthetic:" + (plan.bundleId || plan.objectPlanId || "unknown");
    return String(plan.pageIndex) + "|" + slot + "|" + sourceKey;
}

function _objectPlanSlotFromActions(plan) {
    if (!plan) return "UNKNOWN_SLOT";
    if (plan.visualAction === "PLACE_TEXT_SHELL") return "SHELL_SLOT";
    if (plan.visualAction === "PLACE_TABLE_STYLE") return "TABLE_STYLE_SLOT";
    if (plan.textAction === "OWNED_BY_PNG") return "TEXT_SLOT";
    return "CONTENT_VISUAL_SLOT";
}

function _pushObjectPlanIssue(issues, issueCodeCounts, issuePlanIds, code, plans, detail) {
    _incrementObjectPlanSummary(issueCodeCounts, code);
    var refs = [];
    for (var i = 0; plans && i < plans.length; i++) {
        var plan = plans[i];
        if (!plan) continue;
        refs.push({
            objectPlanId: plan.objectPlanId || null,
            bundleId: plan.bundleId || null,
            candidateId: plan.candidateId || null,
            pageIndex: plan.pageIndex,
            passId: plan.passId || null,
            textAction: plan.textAction || null,
            visualAction: plan.visualAction || null,
            placement: plan.placement || null,
            ownershipSlot: plan.ownershipSlot || null,
            sourceObjectIds: plan.sourceObjectIds || [],
            visualSourceObjectIds: plan.visualSourceObjectIds || [],
            styleSourceObjectIds: plan.styleSourceObjectIds || [],
            ownedTextFrameIds: plan.ownedTextFrameIds || []
        });
        if (plan.objectPlanId) issuePlanIds[plan.objectPlanId] = true;
    }
    issues.push({
        code: code,
        detail: detail || {},
        plans: refs
    });
}

function _objectPlanMapKeyCount(map) {
    var count = 0;
    for (var key in map) {
        if (map.hasOwnProperty(key)) count++;
    }
    return count;
}

function _incrementObjectPlanSummary(map, key) {
    key = key || "UNKNOWN";
    if (!map[key]) map[key] = 0;
    map[key]++;
}
