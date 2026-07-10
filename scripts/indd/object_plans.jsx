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
    var sourceById = _objectPlanSourceInfoById(sourceItems);
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
        var plan = _objectPlanFromPlannerBundle(bundles[i], i, sourceById);
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
    var deduplication = _deduplicateObjectPlansByIdentity(objectPlans);
    _appendEditableTextFrameObjectPlans(objectPlans, sourceItems);
    _appendVisibleTextFrameObjectPlans(objectPlans, sourceItems);
    _appendEmptyEditableTextFrameObjectPlans(objectPlans, sourceItems);
    _appendTableOnlyTextFrameObjectPlans(objectPlans, sourceItems);
    _appendTextFrameCleanupObjectPlans(objectPlans, sourceItems);
    var pngOwnedTextFrameCleanup = _applyPngOwnedTextFrameCleanupObjectPlans(objectPlans, sourceItems);
    var textOwnershipResolution = _resolveObjectPlanDuplicateTextOwners(objectPlans);
    var visibleVisualSourceResolution = _resolveObjectPlanDuplicateVisibleVisualSources(objectPlans);
    var pageLocalVisibleSourceResolution = _resolveObjectPlanPageLocalVisibleSources(objectPlans, sourceById);
    var rawClippedImageVisualSourceResolution =
            _resolveObjectPlanRawClippedImageVisualSources(objectPlans, sourceById);
    var depthFinalization = _finalizeObjectPlanVisualDepthContracts(objectPlans, sourceItems);
    var inlineFlowContractFinalization = _finalizeObjectPlanInlineFlowContracts(objectPlans);
    var validation = _validateObjectPlanDiagnostics(objectPlans);
    var sourceSetRefs = _attachObjectPlanSourceSetRefs(objectPlans);
    summary = _summarizeObjectPlans(objectPlans, validation);
    summary.objectPlanDeduplication = deduplication.summary;
    summary.pngOwnedTextFrameCleanup = pngOwnedTextFrameCleanup.summary;
    summary.textOwnershipResolution = textOwnershipResolution.summary;
    summary.visibleVisualSourceResolution = visibleVisualSourceResolution.summary;
    summary.pageLocalVisibleSourceResolution = pageLocalVisibleSourceResolution.summary;
    summary.rawClippedImageVisualSourceResolution = rawClippedImageVisualSourceResolution.summary;
    summary.visualDepthFinalization = depthFinalization.summary;
    summary.inlineFlowContractFinalization = inlineFlowContractFinalization.summary;
    summary.sourceSetInterning = sourceSetRefs.summary;

    return {
        schemaVersion: 1,
        policy: "POLICY-source-ownership",
        mode: "object-plan-diagnostics",
        summary: summary,
        validation: validation,
        sourceSetRefs: sourceSetRefs,
        objectPlans: objectPlans
    };
}

function _finalizeObjectPlanInlineFlowContracts(objectPlans) {
    var clearedPlanCount = 0;
    for (var i = 0; objectPlans && i < objectPlans.length; i++) {
        var plan = objectPlans[i];
        if (!plan) continue;
        if (plan.placement === "INLINE") continue;
        if (plan.inlineSourceTreeClosed === true
                || (plan.inlineFlowSourceObjectIds && plan.inlineFlowSourceObjectIds.length > 0)) {
            plan.inlineSourceTreeClosed = false;
            plan.inlineFlowSourceObjectIds = [];
            clearedPlanCount++;
        }
    }
    return {
        summary: {
            clearedPlanCount: clearedPlanCount
        }
    };
}

function _finalizeObjectPlanVisualDepthContracts(objectPlans, sourceItems) {
    var sourceById = _objectPlanSourceInfoById(sourceItems);
    var editableTextFrames = _objectPlanEditableTextFrames(sourceItems);
    var editableTextFramesByPage = _objectPlanEditableTextFramesByPage(editableTextFrames);
    var sourceZOrderCache = {};
    var zOrderUpdates = 0;
    var layerUpdates = 0;
    for (var i = 0; objectPlans && i < objectPlans.length; i++) {
        var plan = objectPlans[i];
        if (!_objectPlanHasVisibleVisual(plan)) continue;
        var sourceZ = _objectPlanCanonicalVisualSourceZOrder(plan, sourceById, sourceZOrderCache);
        if (sourceZ >= 0 && plan.zOrder !== sourceZ) {
            plan.zOrder = sourceZ;
            zOrderUpdates++;
        }
        var layer = _objectPlanCanonicalVisualLayer(plan, sourceById, editableTextFramesByPage, sourceZ);
        if (layer && plan.visualLayer !== layer) {
            plan.visualLayer = layer;
            plan.policyLayer = _objectPlanPolicyLayerForVisualLayer(layer);
            layerUpdates++;
        }
        if (plan.visualLayer === "PAGE_BACKGROUND" && plan.zOrder !== 0) {
            plan.zOrder = 0;
            zOrderUpdates++;
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
    if (typeof _buildSourceItemIndexes === "function") {
        try {
            return _buildSourceItemIndexes(sourceItems || []).sourceInfoById || {};
        } catch (eObjectPlanSourceInfoIndex) {}
    }
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

function _objectPlanEditableTextFramesByPage(editableTextFrames) {
    var out = {};
    for (var i = 0; editableTextFrames && i < editableTextFrames.length; i++) {
        var tf = editableTextFrames[i];
        if (!tf) continue;
        var key = String(tf.pageIndex !== undefined && tf.pageIndex !== null ? tf.pageIndex : -1);
        if (!out[key]) out[key] = [];
        out[key].push(tf);
    }
    return out;
}

function _objectPlanCanonicalVisualSourceZOrder(plan, sourceById, cache) {
    var useMin = _objectPlanUsesLowestVisualSourceZOrder(plan);
    var sourceZ = _objectPlanAggregateSourceZOrder(plan ? plan.sourceRootObjectIds : null,
            sourceById, useMin, false, cache);
    if (sourceZ >= 0) return sourceZ;
    sourceZ = _objectPlanAggregateSourceZOrder(plan ? plan.visualSourceObjectIds : null,
            sourceById, useMin, true, cache);
    if (sourceZ >= 0) return sourceZ;
    sourceZ = _objectPlanAggregateSourceZOrder(plan ? plan.sourceObjectIds : null,
            sourceById, useMin, true, cache);
    if (sourceZ >= 0) return sourceZ;
    sourceZ = _objectPlanAggregateSourceZOrder(plan ? plan.visualSourceObjectIds : null,
            sourceById, useMin, false, cache);
    if (sourceZ >= 0) return sourceZ;
    sourceZ = _objectPlanAggregateSourceZOrder(plan ? plan.sourceObjectIds : null,
            sourceById, useMin, false, cache);
    return sourceZ >= 0 ? sourceZ : (plan && plan.zOrder !== null && plan.zOrder !== undefined ? plan.zOrder : -1);
}

function _objectPlanAggregateSourceZOrder(ids, sourceById, useMin, normalizedOnly, cache) {
    if (!ids || ids.length === 0) return -1;
    var key = null;
    if (cache) {
        key = (useMin ? "min" : "max") + "|" + (normalizedOnly ? "normalized" : "all") + "|" + _sourceSetKey(ids);
        if (cache.hasOwnProperty(key)) return cache[key];
    }
    var value = useMin
            ? _objectPlanMinSourceZOrder(ids, sourceById, normalizedOnly)
            : _objectPlanMaxSourceZOrder(ids, sourceById, normalizedOnly);
    if (cache && key !== null) cache[key] = value;
    return value;
}

function _objectPlanUsesLowestVisualSourceZOrder(plan) {
    if (!plan) return false;
    if (plan.visualAction !== "PLACE_TEXT_SHELL") return false;
    if (!plan.visualSourceObjectIds || plan.visualSourceObjectIds.length <= 1) return false;
    return plan.slotRole === "shell_slot_only"
            || plan.slotRole === "direct_child_shell_slot"
            || plan.compositeRole === "table_carrier_sibling_decoration"
            || plan.compositeRole === "direct_child_shell_slot"
            || plan.passId === "pass.decoration_groups"
            || plan.passId === "pass.editable_textframe_visual_shells";
}

function _objectPlanMaxSourceZOrder(ids, sourceById, normalizedOnly) {
    var max = -1;
    for (var i = 0; ids && i < ids.length; i++) {
        var src = sourceById ? sourceById[String(ids[i])] : null;
        if (!src || src.zOrder === null || src.zOrder === undefined) continue;
        if (normalizedOnly && src.zOrderSource && String(src.zOrderSource) !== "idml_spread") continue;
        var z = Number(src.zOrder);
        if (!isNaN(z) && z > max) max = z;
    }
    return max;
}

function _objectPlanMinSourceZOrder(ids, sourceById, normalizedOnly) {
    var min = null;
    for (var i = 0; ids && i < ids.length; i++) {
        var src = sourceById ? sourceById[String(ids[i])] : null;
        if (!src || src.zOrder === null || src.zOrder === undefined) continue;
        if (normalizedOnly && src.zOrderSource && String(src.zOrderSource) !== "idml_spread") continue;
        var z = Number(src.zOrder);
        if (isNaN(z)) continue;
        min = min === null ? z : Math.min(min, z);
    }
    return min === null ? -1 : min;
}

function _objectPlanCanonicalVisualLayer(plan, sourceById, editableTextFramesByPage, zOrder) {
    if (!plan) return null;
    if (plan.compositeRole === "background_vector_source") {
        return "PAGE_BACKGROUND";
    }
    if (plan.visualLayer === "PAGE_BACKGROUND"
            && plan.passId !== "pass.page_backgrounds"
            && !_objectPlanMayUseBackgroundPlane(plan, sourceById, editableTextFramesByPage, zOrder)) {
        return "CONTENT_VISUAL";
    }
    if (plan.visualLayer === "CONTAINER_BACKDROP"
            && !_objectPlanMayUseBackgroundPlane(plan, sourceById, editableTextFramesByPage, zOrder)) {
        return plan.visualAction === "PLACE_TEXT_SHELL" ? "LABEL_BACKDROP" : "CONTENT_VISUAL";
    }
    if (plan.visualAction === "PLACE_FLOATING_PNG"
            && plan.placement === "FLOATING"
            && plan.visualLayer === "CONTENT_VISUAL"
            && _objectPlanHasBackgroundLayerSource(plan, sourceById)
            && _objectPlanMayUseBackgroundPlane(plan, sourceById, editableTextFramesByPage, zOrder)) {
        return "PAGE_BACKGROUND";
    }
    if (plan.visualAction === "PLACE_TEXT_SHELL"
            && plan.placement === "FLOATING"
            && plan.coordinateSpace === "PAGE"
            && (!plan.ownedTextFrameIds || plan.ownedTextFrameIds.length === 0)
            && plan.visualLayer === "LABEL_BACKDROP"
            && _objectPlanMayUseBackgroundPlane(plan, sourceById, editableTextFramesByPage, zOrder)) {
        return "CONTAINER_BACKDROP";
    }
    return plan.visualLayer;
}

function _objectPlanHasBackgroundLayerSource(plan, sourceById) {
    if (!plan || !sourceById) return false;
    var ids = _objectPlanRootCandidateIds(plan);
    for (var i = 0; ids && i < ids.length; i++) {
        var src = sourceById[String(ids[i])];
        if (src && _objectPlanIsBackgroundLayerName(src.layerName)) return true;
    }
    return false;
}

function _objectPlanIsBackgroundLayerName(layerName) {
    if (!layerName) return false;
    var lower = String(layerName).toLowerCase();
    return lower.indexOf("\uBC30\uACBD") >= 0
            || lower.indexOf("\uBC14\uD0D5") >= 0
            || lower.indexOf("background") >= 0
            || lower === "bg"
            || lower.indexOf("backdrop") >= 0;
}

function _objectPlanMayUseBackgroundPlane(plan, sourceById, editableTextFramesByPage, zOrder) {
    if (!plan) return false;
    if (!_objectPlanHasPageLevelSourceRoot(plan, sourceById)) return false;
    if (_objectPlanHasTextOwnershipSignal(plan)) return false;
    if (!_objectPlanIsBackgroundBoundsSanityCandidate(plan.bounds)) return false;
    return _objectPlanIsBehindLocalText(plan.bounds, plan.pageIndex, zOrder, editableTextFramesByPage);
}

function _objectPlanHasTextOwnershipSignal(plan) {
    return !!(plan
            && ((plan.ownedTextFrameIds && plan.ownedTextFrameIds.length > 0)
                    || (plan.hiddenVisualSourceObjectIds && plan.hiddenVisualSourceObjectIds.length > 0)
                    || plan.textAction === "OWNED_BY_HWPX_TEXT"));
}

function _objectPlanHasPageLevelSourceRoot(plan, sourceById) {
    var roots = _objectPlanRootCandidateIds(plan);
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

function _objectPlanRootCandidateIds(plan) {
    if (!plan) return null;
    if (plan.sourceRootObjectIds && plan.sourceRootObjectIds.length > 0) return plan.sourceRootObjectIds;
    if (plan.primarySourceObjectId !== null && plan.primarySourceObjectId !== undefined) {
        return [ plan.primarySourceObjectId ];
    }
    return plan.sourceObjectIds || null;
}

function _objectPlanIsBehindLocalText(bounds, pageIndex, zOrder, editableTextFramesByPage) {
    if (!bounds || bounds.length < 4 || _objectPlanArea(bounds) <= 0) return false;
    if (zOrder === null || zOrder === undefined || zOrder < 0) return false;
    var pageKey = String(pageIndex !== undefined && pageIndex !== null ? pageIndex : -1);
    var pageTextFrames = editableTextFramesByPage ? editableTextFramesByPage[pageKey] : null;
    for (var i = 0; pageTextFrames && i < pageTextFrames.length; i++) {
        var tf = pageTextFrames[i];
        if (!tf) continue;
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
    var decisionIndex = _createObjectPlanDecisionIndex(objectPlans);
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
                && _objectPlanDecisionIndexHasVisibleShell(decisionIndex, id)) {
            var shellCleanupPlan = _textFrameCleanupObjectPlan(src, id, pageIndex, zOrder,
                    "empty_text_frame_visual_shell");
            objectPlans.push(shellCleanupPlan);
            _addObjectPlanToDecisionIndex(decisionIndex, shellCleanupPlan);
            continue;
        }
        if (_objectPlanDecisionIndexHasVisualDecision(decisionIndex, id)) {
            var visualCleanupPlan = _textFrameCleanupObjectPlan(src, id, pageIndex, zOrder,
                    "empty_text_frame_visual_source");
            objectPlans.push(visualCleanupPlan);
            _addObjectPlanToDecisionIndex(decisionIndex, visualCleanupPlan);
            continue;
        }
        if (_objectPlanDecisionIndexHasTextDecision(decisionIndex, id)) continue;
        var textPlan = _textFrameObjectPlan(src, id, pageIndex, zOrder,
                "pass.empty_editable_text_frames", "editable_text_frame");
        objectPlans.push(textPlan);
        _addObjectPlanToDecisionIndex(decisionIndex, textPlan);
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

function _deduplicateObjectPlansByIdentity(objectPlans) {
    var summary = { removedPlanCount: 0 };
    if (!objectPlans || objectPlans.length < 2) return { summary: summary };
    var seen = {};
    var keptIndexByKey = {};
    var kept = [];
    for (var i = 0; i < objectPlans.length; i++) {
        var plan = objectPlans[i];
        if (!plan) continue;
        var key = plan.objectPlanId
                ? ("objectPlanId:" + String(plan.objectPlanId))
                : _objectPlanIdentityKey(plan);
        if (seen[key] === true) {
            var keptIndex = keptIndexByKey[key];
            if (keptIndex !== undefined
                    && _compareObjectPlanIdentityPriority(plan, kept[keptIndex]) < 0) {
                kept[keptIndex] = plan;
            }
            summary.removedPlanCount++;
            continue;
        }
        seen[key] = true;
        keptIndexByKey[key] = kept.length;
        kept.push(plan);
    }
    objectPlans.length = 0;
    for (var k = 0; k < kept.length; k++) {
        objectPlans.push(kept[k]);
    }
    return { summary: summary };
}

function _compareObjectPlanIdentityPriority(a, b) {
    var scoreA = _objectPlanIdentityPriority(a);
    var scoreB = _objectPlanIdentityPriority(b);
    if (scoreA !== scoreB) return scoreB - scoreA;
    var aId = a && a.candidateId ? String(a.candidateId) : "";
    var bId = b && b.candidateId ? String(b.candidateId) : "";
    if (aId < bId) return -1;
    if (aId > bId) return 1;
    return 0;
}

function _objectPlanIdentityPriority(plan) {
    if (!plan) return 0;
    var score = 0;
    if (plan.executable === true) score += 1000;
    if (_objectPlanHasVisibleVisual(plan)) score += 100;
    if (plan.slotRole === "direct_child_shell_slot") score += 60;
    if (plan.compositeRole === "direct_child_shell_slot") score += 50;
    if (plan.slotRole === "shell_slot_only" || plan.mode === "SLOT_ONLY") score += 30;
    if (plan.visualAction === "PLACE_TEXT_SHELL") score += 20;
    if (plan.ownedTextFrameIds && plan.ownedTextFrameIds.length > 0) score += 10;
    return score;
}

function _objectPlanIdentityKey(plan) {
    if (!plan) return "null";
    return [
        plan.objectPlanId || "",
        plan.bundleId || "",
        plan.candidateId || "",
        plan.passId || "",
        plan.pageIndex !== undefined ? String(plan.pageIndex) : "",
        plan.kind || "",
        plan.materialization || "",
        plan.textAction || "",
        plan.visualAction || "",
        plan.placement || "",
        plan.coordinateSpace || "",
        plan.visualLayer || "",
        plan.ownershipSlot || "",
        plan.slotRole || "",
        _sourceSetKey(plan.sourceObjectIds || []),
        _sourceSetKey(plan.visualSourceObjectIds || []),
        _sourceSetKey(plan.styleSourceObjectIds || []),
        _sourceSetKey(plan.exportSourceObjectIds || []),
        _sourceSetKey(plan.hiddenVisualSourceObjectIds || []),
        _sourceSetKey(plan.ownedTextFrameIds || [])
    ].join("|");
}

function _attachObjectPlanSourceSetRefs(objectPlans) {
    var interner = _createObjectPlanSourceSetInterner();
    var fields = [
        { ids: "sourceRootObjectIds", ref: "sourceRootSetId" },
        { ids: "clusterSourceObjectIds", ref: "clusterSourceSetId" },
        { ids: "omittedClusterSourceObjectIds", ref: "omittedClusterSourceSetId" }
    ];
    var attachedRefCount = 0;
    for (var i = 0; objectPlans && i < objectPlans.length; i++) {
        var plan = objectPlans[i];
        if (!plan) continue;
        if (!_objectPlanSlimPlanNeedsDiagnosticSourceSets(plan)) continue;
        for (var f = 0; f < fields.length; f++) {
            var field = fields[f];
            var ids = plan[field.ids] || [];
            var ref = interner.intern(ids);
            plan[field.ref] = ref;
            if (ids.length > 0) attachedRefCount++;
        }
    }
    var result = interner.result();
    result.summary.attachedRefCount = attachedRefCount;
    result.summary.planCount = objectPlans ? objectPlans.length : 0;
    result.summary.refFieldCount = fields.length;
    return result;
}

function _createObjectPlanSourceSetInterner() {
    var byKey = {};
    var sets = [];
    function intern(ids) {
        var key = _sourceSetKey(ids || []);
        if (key === "") return null;
        if (byKey[key]) return byKey[key].sourceSetId;
        var sorted = _sortedNumericIds(ids || []);
        var id = "ss" + String(sets.length + 1);
        var row = {
            sourceSetId: id,
            sourceObjectCount: sorted.length,
            sourceObjectIds: sorted
        };
        byKey[key] = row;
        sets.push(row);
        return id;
    }
    function result() {
        return {
            schemaVersion: 1,
            mode: "object-plan-source-set-refs",
            summary: {
                uniqueSourceSetCount: sets.length
            },
            sourceSets: sets
        };
    }
    return {
        intern: intern,
        result: result
    };
}

function _slimObjectPlanDiagnosticsForWrite(diagnostics) {
    if (!diagnostics) return diagnostics;
    var validation = diagnostics.validation || {};
    var slimPlans = [];
    var plans = diagnostics.objectPlans || [];
    for (var i = 0; i < plans.length; i++) {
        slimPlans.push(_slimObjectPlanForWrite(plans[i]));
    }
    return {
        schemaVersion: diagnostics.schemaVersion || 1,
        policy: diagnostics.policy || "POLICY-source-ownership",
        mode: "object-plan-diagnostics-slim",
        summary: diagnostics.summary || {},
        validation: {
            issueCount: validation.issueCount || 0,
            importReadyPlanCount: validation.importReadyPlanCount || 0,
            contractStatusCounts: validation.contractStatusCounts || {},
            issueCodeCounts: validation.issueCodeCounts || {},
            issuesOmitted: validation.issues && validation.issues.length > 0,
            issuePreview: _objectPlanIssuePreview(validation.issues || [], 50)
        },
        sourceSetRefs: _slimObjectPlanSourceSetRefsForWrite(diagnostics.sourceSetRefs, slimPlans),
        fullDiagnosticsSkipped: true,
        objectPlans: slimPlans
    };
}

function _slimObjectPlanSourceSetRefsForWrite(sourceSetRefs, slimPlans) {
    if (!sourceSetRefs || !sourceSetRefs.sourceSets) return null;
    var used = {};
    var refFields = [
        "sourceRootSetId",
        "clusterSourceSetId",
        "omittedClusterSourceSetId"
    ];
    for (var i = 0; slimPlans && i < slimPlans.length; i++) {
        var plan = slimPlans[i];
        if (!plan) continue;
        if (!_objectPlanSlimPlanNeedsDiagnosticSourceSets(plan)) continue;
        for (var f = 0; f < refFields.length; f++) {
            var ref = plan[refFields[f]];
            if (ref) used[String(ref)] = true;
        }
    }

    var sets = [];
    for (var s = 0; s < sourceSetRefs.sourceSets.length; s++) {
        var row = sourceSetRefs.sourceSets[s];
        if (!row || !used[String(row.sourceSetId || "")]) continue;
        sets.push(row);
    }
    return {
        schemaVersion: sourceSetRefs.schemaVersion || 1,
        mode: "object-plan-source-set-refs-slim",
        summary: {
            uniqueSourceSetCount: sets.length,
            fullUniqueSourceSetCount: sourceSetRefs.summary
                    ? sourceSetRefs.summary.uniqueSourceSetCount || 0
                    : 0,
            retainedRefFieldCount: refFields.length
        },
        sourceSets: sets
    };
}

function _slimObjectPlanForWrite(plan) {
    if (!plan) return plan;
    var out = {};
    var fields = [
        "objectPlanId",
        "bundleId",
        "candidateId",
        "passId",
        "pageIndex",
        "kind",
        "mode",
        "candidatePurpose",
        "compositeRole",
        "slotRole",
        "layoutOnlyInlineSlot",
        "sourceInlineFlow",
        "inlineCompositeLayoutDescendant",
        "inlineAnchorSourceObjectId",
        "inlineSourceTreeClosed",
        "inlineFlowSourceObjectIds",
        "connectorDecorationVisual",
        "primarySourceObjectId",
        "ownedByNativeShellSourceObjectIds",
        "ownedByNativeShellSourceSetId",
        "sourceObjectIds",
        "sourceSetId",
        "sourceRootSetId",
        "clusterSourceSetId",
        "omittedClusterSourceSetId",
        "clusterHasEditableText",
        "clusterHasTextFrame",
        "clusterHasPlacedContent",
        "clusterHasVisualSource",
        "visualSourceObjectIds",
        "visualSourceSetId",
        "styleSourceObjectIds",
        "styleSourceSetId",
        "ownedTextFrameIds",
        "ownedTextFrameSetId",
        "exportSourceObjectIds",
        "exportSourceSetId",
        "exportTargetObjectId",
        "atomicExportTargetObjectId",
        "atomicExportTargetObjectIds",
        "atomicTextlessVectorContent",
        "atomicContentVisualSlot",
        "hiddenVisualSourceObjectIds",
        "hiddenVisualSourceSetId",
        "materialization",
        "textAction",
        "visualAction",
        "placement",
        "coordinateSpace",
        "visualLayer",
        "zOrder",
        "reason",
        "bounds",
        "renderSourceBounds",
        "cropSourceBounds",
        "ownershipSlot",
        "policyLayer",
        "clusterRelation",
        "migrationStatus",
        "migrationBlocker",
        "migrationBlockerDetail",
        "contractStatus",
        "executable",
        "required"
    ];
    for (var i = 0; i < fields.length; i++) {
        var key = fields[i];
        if (_objectPlanSlimFieldIsWriteOnlyDiagnostic(key, plan)) continue;
        if (key === "migrationBlockerDetail" && plan.migrationBlocker === "NONE") continue;
        if (plan[key] !== undefined && !_objectPlanSlimValueIsEmpty(plan[key])) out[key] = plan[key];
    }
    return out;
}

function _objectPlanSlimFieldIsWriteOnlyDiagnostic(key, plan) {
    if (key === "sourceSetId") return true;
    if (key === "visualSourceSetId") return true;
    if (key === "styleSourceSetId") return true;
    if (key === "ownedTextFrameSetId") return true;
    if (key === "exportSourceSetId") return true;
    if (key === "hiddenVisualSourceSetId") return true;
    if (key === "ownedByNativeShellSourceSetId") return true;
    if ((key === "sourceRootSetId" || key === "clusterSourceSetId"
            || key === "omittedClusterSourceSetId")
            && !_objectPlanSlimPlanNeedsDiagnosticSourceSets(plan)) return true;
    if (key === "sourceInlineFlow") return true;
    if (key === "inlineCompositeLayoutDescendant") return true;
    if (key === "connectorDecorationVisual") return true;
    if (key === "clusterHasEditableText") return true;
    if (key === "clusterHasTextFrame") return true;
    if (key === "clusterHasPlacedContent") return true;
    if (key === "clusterHasVisualSource") return true;
    if (key === "policyLayer") return true;
    if (key === "clusterRelation") return true;
    if (key === "migrationStatus") return true;
    if (key === "migrationBlocker" && (!plan || plan.migrationBlocker === "NONE")) return true;
    return false;
}

function _objectPlanSlimPlanNeedsDiagnosticSourceSets(plan) {
    if (!plan) return false;
    if (plan.contractStatus && plan.contractStatus !== "READY_FOR_STAGE1_IMPORT") return true;
    if (plan.migrationBlocker && plan.migrationBlocker !== "NONE") return true;
    return false;
}

function _objectPlanSlimValueIsEmpty(value) {
    if (value === null || value === undefined) return true;
    if (value === "") return true;
    if (value instanceof Array) return value.length === 0;
    if (typeof value === "object") {
        for (var key in value) {
            if (value.hasOwnProperty(key)) return false;
        }
        return true;
    }
    return false;
}

function _objectPlanIssuePreview(issues, limit) {
    var out = [];
    limit = limit || 0;
    for (var i = 0; issues && i < issues.length && i < limit; i++) {
        var issue = issues[i] || {};
        out.push({
            code: issue.code || null,
            detail: issue.detail || {},
            planCount: issue.plans ? issue.plans.length : 0
        });
    }
    return out;
}

function _applyPngOwnedTextFrameCleanupObjectPlans(objectPlans, sourceItems) {
    var summary = {
        ownedTextFrameCount: 0,
        mutatedTextFramePlanCount: 0,
        mutatedShellPlanCount: 0,
        addedCleanupPlanCount: 0
    };
    if (!objectPlans || !sourceItems) return { summary: summary };

    var ownedTextFrameIds = {};
    for (var i = 0; i < objectPlans.length; i++) {
        var ownerPlan = objectPlans[i];
        if (!ownerPlan) continue;
        if (ownerPlan.textAction !== "OWNED_BY_PNG") continue;
        if (ownerPlan.visualAction !== "PLACE_INLINE_PNG"
                && ownerPlan.visualAction !== "PLACE_FLOATING_PNG") continue;
        if (!ownerPlan.ownedTextFrameIds || ownerPlan.ownedTextFrameIds.length === 0) continue;
        for (var t = 0; t < ownerPlan.ownedTextFrameIds.length; t++) {
            var tfId = Number(ownerPlan.ownedTextFrameIds[t]);
            if (isNaN(tfId)) continue;
            ownedTextFrameIds[String(tfId)] = tfId;
        }
    }

    var sourceById = _objectPlanSourceInfoById(sourceItems);
    for (var key in ownedTextFrameIds) {
        if (!ownedTextFrameIds.hasOwnProperty(key)) continue;
        var src = sourceById[key];
        if (!src || String(src.kind || "") !== "TextFrame") {
            delete ownedTextFrameIds[key];
        }
    }

    for (var countKey in ownedTextFrameIds) {
        if (ownedTextFrameIds.hasOwnProperty(countKey)) summary.ownedTextFrameCount++;
    }
    if (summary.ownedTextFrameCount === 0) return { summary: summary };

    var textFramePlansById = {};
    for (var p = 0; p < objectPlans.length; p++) {
        var plan = objectPlans[p];
        if (!plan) continue;
        if (String(plan.kind || "") === "TextFrame"
                && plan.primarySourceObjectId !== undefined
                && plan.primarySourceObjectId !== null) {
            textFramePlansById[String(plan.primarySourceObjectId)] = plan;
        }
    }

    for (var mutateKey in ownedTextFrameIds) {
        if (!ownedTextFrameIds.hasOwnProperty(mutateKey)) continue;
        var textFramePlan = textFramePlansById[mutateKey];
        if (!textFramePlan) continue;
        if (textFramePlan.textAction !== "DROP_TEXT"
                || textFramePlan.visualAction !== "DROP_VISUAL"
                || textFramePlan.reason !== "owned_by_inline_complete_png") {
            _markObjectPlanOwnedByPngCleanup(textFramePlan);
            summary.mutatedTextFramePlanCount++;
        }
    }

    for (var s = 0; s < objectPlans.length; s++) {
        var shellPlan = objectPlans[s];
        if (!shellPlan || shellPlan.visualAction !== "PLACE_TEXT_SHELL") continue;
        if (!_objectPlanAllOwnedTextFramesIn(shellPlan, ownedTextFrameIds)) continue;
        _markObjectPlanOwnedByPngCleanup(shellPlan);
        summary.mutatedShellPlanCount++;
    }

    for (var addKey in ownedTextFrameIds) {
        if (!ownedTextFrameIds.hasOwnProperty(addKey)) continue;
        if (textFramePlansById[addKey]) continue;
        var cleanupSrc = sourceById[addKey];
        if (!cleanupSrc) continue;
        var id = Number(addKey);
        var pageIndex = cleanupSrc.pageIndex !== undefined && cleanupSrc.pageIndex !== null
                ? cleanupSrc.pageIndex
                : -1;
        var zOrder = cleanupSrc.zOrder !== undefined && cleanupSrc.zOrder !== null
                ? cleanupSrc.zOrder
                : 0;
        var cleanupPlan = _textFrameCleanupObjectPlan(cleanupSrc, id, pageIndex, zOrder,
                "owned_by_inline_complete_png");
        objectPlans.push(cleanupPlan);
        textFramePlansById[addKey] = cleanupPlan;
        summary.addedCleanupPlanCount++;
    }

    return { summary: summary };
}

function _markObjectPlanOwnedByPngCleanup(plan) {
    if (!plan) return;
    plan.passId = "pass.textframe_cleanup";
    plan.textAction = "DROP_TEXT";
    plan.visualAction = "DROP_VISUAL";
    plan.materialization = "HWPX_TEXT";
    plan.reason = "owned_by_inline_complete_png";
    plan.candidatePurpose = "owned_by_inline_complete_png";
    plan.migrationStatus = "READY_TEXT_CLEANUP";
    plan.migrationBlocker = "NONE";
    plan.migrationBlockerDetail = {};
    plan.executable = true;
}

function _objectPlanAllOwnedTextFramesIn(plan, ownedTextFrameIds) {
    if (!plan || !plan.ownedTextFrameIds || plan.ownedTextFrameIds.length === 0) return false;
    for (var i = 0; i < plan.ownedTextFrameIds.length; i++) {
        var key = String(plan.ownedTextFrameIds[i]);
        if (!ownedTextFrameIds[key]) return false;
    }
    return true;
}

function _appendTableOnlyTextFrameObjectPlans(objectPlans, sourceItems) {
    if (!objectPlans || !sourceItems) return;
    var decisionIndex = _createObjectPlanDecisionIndex(objectPlans);
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
        if (_objectPlanDecisionIndexHasTableStyleDecision(decisionIndex, id)) continue;
        var pageIndex = src.pageIndex !== undefined && src.pageIndex !== null ? src.pageIndex : -1;
        var zOrder = src.zOrder !== undefined && src.zOrder !== null ? src.zOrder : 0;
        var tablePlan = _tableOnlyTextFrameObjectPlan(src, id, pageIndex, zOrder);
        objectPlans.push(tablePlan);
        _addObjectPlanToDecisionIndex(decisionIndex, tablePlan);
    }
}

function _createObjectPlanDecisionIndex(objectPlans) {
    var index = {
        textDecisionByFrameId: {},
        visualDecisionByFrameId: {},
        tableStyleDecisionByFrameId: {},
        visibleShellByFrameId: {}
    };
    for (var i = 0; objectPlans && i < objectPlans.length; i++) {
        _addObjectPlanToDecisionIndex(index, objectPlans[i]);
    }
    return index;
}

function _addObjectPlanToDecisionIndex(index, plan) {
    if (!index || !plan) return;
    _markObjectPlanTextDecisionIndex(index, plan);
    _markObjectPlanVisualDecisionIndex(index, plan);
    _markObjectPlanTableStyleDecisionIndex(index, plan);
    _markObjectPlanVisibleShellIndex(index, plan);
}

function _markObjectPlanTextDecisionIndex(index, plan) {
    if (plan.primarySourceObjectId !== undefined && plan.primarySourceObjectId !== null) {
        index.textDecisionByFrameId[String(plan.primarySourceObjectId)] = true;
    }
    for (var t = 0; plan.ownedTextFrameIds && t < plan.ownedTextFrameIds.length; t++) {
        index.textDecisionByFrameId[String(plan.ownedTextFrameIds[t])] = true;
    }
}

function _markObjectPlanVisualDecisionIndex(index, plan) {
    _markObjectPlanIds(index.visualDecisionByFrameId, plan.visualSourceObjectIds);
    _markObjectPlanIds(index.visualDecisionByFrameId, plan.styleSourceObjectIds);
    _markObjectPlanIds(index.visualDecisionByFrameId, plan.exportSourceObjectIds);
}

function _markObjectPlanTableStyleDecisionIndex(index, plan) {
    if (plan.visualAction !== "PLACE_TABLE_STYLE") return;
    if (plan.primarySourceObjectId !== undefined && plan.primarySourceObjectId !== null) {
        index.tableStyleDecisionByFrameId[String(plan.primarySourceObjectId)] = true;
    }
    _markObjectPlanIds(index.tableStyleDecisionByFrameId, plan.ownedTextFrameIds);
}

function _markObjectPlanVisibleShellIndex(index, plan) {
    if (plan.visualAction !== "PLACE_TEXT_SHELL") return;
    if (plan.primarySourceObjectId !== undefined && plan.primarySourceObjectId !== null) {
        index.visibleShellByFrameId[String(plan.primarySourceObjectId)] = true;
    }
    _markObjectPlanIds(index.visibleShellByFrameId, plan.sourceObjectIds);
}

function _markObjectPlanIds(target, ids) {
    for (var i = 0; ids && i < ids.length; i++) {
        target[String(ids[i])] = true;
    }
}

function _objectPlanDecisionIndexHasTextDecision(index, textFrameId) {
    return index && index.textDecisionByFrameId[String(textFrameId)] === true;
}

function _objectPlanDecisionIndexHasVisualDecision(index, textFrameId) {
    return index && index.visualDecisionByFrameId[String(textFrameId)] === true;
}

function _objectPlanDecisionIndexHasTableStyleDecision(index, textFrameId) {
    return index && index.tableStyleDecisionByFrameId[String(textFrameId)] === true;
}

function _objectPlanDecisionIndexHasVisibleShell(index, textFrameId) {
    return index && index.visibleShellByFrameId[String(textFrameId)] === true;
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
        slotRole: "TEXT_SLOT",
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
        materialization: "HWPX_TEXT",
        textAction: "OWNED_BY_HWPX_TEXT",
        visualAction: "DROP_VISUAL",
        placement: src.storyAnchorPlacement === "INLINE" ? "INLINE" : "FLOATING",
        coordinateSpace: src.storyAnchorPlacement === "INLINE" ? "STORY_FLOW" : "PAGE",
        visualLayer: "CONTENT_VISUAL",
        zOrder: zOrder,
        reason: "table_only_text_frame",
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
        if (!plan || (plan.textAction !== "OWNED_BY_HWPX_TEXT" && plan.textAction !== "OWNED_BY_PNG")) continue;
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
        var retainedTextFrameIds = [];
        var droppedTextFrameIds = [];
        var duplicateOwnedCount = 0;
        var canonicalOwnedCount = 0;
        for (var c = 0; c < candidate.ownedTextFrameIds.length; c++) {
            var originalOwnedTextId = candidate.ownedTextFrameIds[c];
            var ownedTextId = String(originalOwnedTextId);
            var canonicalPlan = canonicalByTextFrameId[ownedTextId];
            if (!canonicalPlan) {
                retainedTextFrameIds.push(originalOwnedTextId);
                continue;
            }
            duplicateOwnedCount++;
            if (canonicalPlan === candidate) {
                canonicalOwnedCount++;
                retainedTextFrameIds.push(originalOwnedTextId);
            } else {
                droppedTextFrameIds.push(originalOwnedTextId);
            }
        }
        if (duplicateOwnedCount === 0) continue;
        if (droppedTextFrameIds.length === 0 && canonicalOwnedCount > 0) {
            candidate.textOwnershipResolution = "CANONICAL_TEXT_OWNER";
            continue;
        }
        if (retainedTextFrameIds.length > 0) {
            candidate.ownedTextFrameIds = retainedTextFrameIds;
            candidate.textOwnershipResolution = "PARTIAL_DUPLICATE_TEXT_OWNER_RESOLVED";
            candidate.textOwnershipResolutionReason = "kept_only_text_frames_for_which_this_plan_is_canonical";
            candidate.reason = String(candidate.reason || "")
                    + ":partial_text_owner_duplicate_resolved";
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

function _resolveObjectPlanDuplicateVisibleVisualSources(objectPlans) {
    var plans = objectPlans || [];
    var ownersByPageSource = {};
    for (var i = 0; i < plans.length; i++) {
        var plan = plans[i];
        if (!_objectPlanHasVisibleVisual(plan)) continue;
        if (plan.visualAction === "PLACE_TABLE_STYLE") continue;
        if (!plan.visualSourceObjectIds || plan.visualSourceObjectIds.length === 0) continue;
        var pageKey = String(plan.pageIndex);
        for (var vi = 0; vi < plan.visualSourceObjectIds.length; vi++) {
            var sourceId = plan.visualSourceObjectIds[vi];
            var key = pageKey + "|" + String(sourceId);
            if (!ownersByPageSource[key]) ownersByPageSource[key] = [];
            ownersByPageSource[key].push(plan);
        }
    }

    var canonicalByPageSource = {};
    var duplicateSourceCount = 0;
    for (var sourceKey in ownersByPageSource) {
        if (!ownersByPageSource.hasOwnProperty(sourceKey)) continue;
        var owners = ownersByPageSource[sourceKey];
        if (!owners || owners.length < 2) continue;
        duplicateSourceCount++;
        var canonical = owners[0];
        for (var oi = 1; oi < owners.length; oi++) {
            if (_compareObjectPlanVisibleVisualSourcePriority(owners[oi], canonical) < 0) {
                canonical = owners[oi];
            }
        }
        canonicalByPageSource[sourceKey] = canonical;
    }

    var mutatedPlanCount = 0;
    var droppedPlanCount = 0;
    var removedSourceCount = 0;
    var mutatedPlanIds = [];
    for (var pi = 0; pi < plans.length; pi++) {
        var candidate = plans[pi];
        if (!_objectPlanHasVisibleVisual(candidate)) continue;
        if (candidate.visualAction === "PLACE_TABLE_STYLE") continue;
        if (!candidate.visualSourceObjectIds || candidate.visualSourceObjectIds.length === 0) continue;
        var retainedVisualIds = [];
        var removedVisualIds = [];
        var seenRetained = {};
        var seenRemoved = {};
        var candidatePageKey = String(candidate.pageIndex);
        for (var ci = 0; ci < candidate.visualSourceObjectIds.length; ci++) {
            var originalSourceId = candidate.visualSourceObjectIds[ci];
            var ownerKey = candidatePageKey + "|" + String(originalSourceId);
            var canonicalPlan = canonicalByPageSource[ownerKey];
            if (!canonicalPlan || canonicalPlan === candidate) {
                _pushUniqueId(retainedVisualIds, seenRetained, originalSourceId);
            } else {
                _pushUniqueId(removedVisualIds, seenRemoved, originalSourceId);
            }
        }
        if (removedVisualIds.length === 0) continue;
        removedSourceCount += removedVisualIds.length;
        candidate.hiddenVisualSourceObjectIds = _sourceIdsUnion(
                candidate.hiddenVisualSourceObjectIds || [], removedVisualIds);
        candidate.exportSourceObjectIds = _sourceIdsMinus(
                candidate.exportSourceObjectIds || [], removedVisualIds);
        candidate.visualSourceObjectIds = _sortedNumericIds(retainedVisualIds);
        candidate.visibleVisualOwnershipResolution =
                retainedVisualIds.length > 0
                        ? "PARTIAL_DUPLICATE_VISIBLE_SOURCE_RESOLVED"
                        : "DROPPED_DUPLICATE_VISIBLE_SOURCE_OWNER";
        candidate.visibleVisualOwnershipResolutionReason =
                "kept_only_visual_sources_for_which_this_plan_is_canonical_on_the_page";
        candidate.reason = String(candidate.reason || "")
                + ":duplicate_visible_visual_source_resolved";
        mutatedPlanCount++;
        mutatedPlanIds.push(candidate.objectPlanId || candidate.bundleId || candidate.candidateId || ("plan.index." + pi));
        if (retainedVisualIds.length === 0) {
            candidate.visualAction = "DROP_VISUAL";
            candidate.materialization = "HWPX_TEXT";
            droppedPlanCount++;
        }
    }

    return {
        summary: {
            duplicateSourceCount: duplicateSourceCount,
            mutatedPlanCount: mutatedPlanCount,
            droppedPlanCount: droppedPlanCount,
            removedSourceCount: removedSourceCount,
            mutatedObjectPlanIds: mutatedPlanIds
        }
    };
}

function _compareObjectPlanVisibleVisualSourcePriority(a, b) {
    var scoreA = _objectPlanVisibleVisualSourcePriority(a);
    var scoreB = _objectPlanVisibleVisualSourcePriority(b);
    if (scoreA !== scoreB) return scoreB - scoreA;

    var aVisualCount = a && a.visualSourceObjectIds ? a.visualSourceObjectIds.length : 0;
    var bVisualCount = b && b.visualSourceObjectIds ? b.visualSourceObjectIds.length : 0;
    if (aVisualCount !== bVisualCount) return aVisualCount - bVisualCount;

    var aSourceCount = a && a.sourceObjectIds ? a.sourceObjectIds.length : 0;
    var bSourceCount = b && b.sourceObjectIds ? b.sourceObjectIds.length : 0;
    if (aSourceCount !== bSourceCount) return aSourceCount - bSourceCount;

    var aId = a && a.objectPlanId ? String(a.objectPlanId) : "";
    var bId = b && b.objectPlanId ? String(b.objectPlanId) : "";
    if (aId < bId) return -1;
    if (aId > bId) return 1;
    return 0;
}

function _objectPlanVisibleVisualSourcePriority(plan) {
    if (!plan) return 0;
    var score = 0;
    if (plan.visualAction === "PLACE_TEXT_SHELL") score += 220;
    if (plan.ownershipSlot === "SHELL_SLOT") score += 160;
    if (plan.slotRole === "shell_slot_only" || plan.mode === "SLOT_ONLY") score += 80;
    if (plan.slotRole === "direct_child_shell_slot") score += 70;
    if (plan.compositeRole === "direct_child_shell_slot") score += 60;
    if (plan.passId === "pass.image_placed_frames") score += 120;
    if (plan.passId === "pass.inline_objects") score += 100;
    if (plan.passId === "pass.decoration_groups") score += 50;
    if (plan.passId === "pass.page_textless_graphic_groups") score += 20;
    if (plan.passId === "pass.master_page_graphics") score += 15;
    if (plan.materialization === "COMPLETE_PNG") score += 40;
    if (plan.materialization === "EXTRACTED_PNG_VECTOR") score += 20;
    if (plan.ownedTextFrameIds && plan.ownedTextFrameIds.length > 0) score += 10;
    return score;
}

function _resolveObjectPlanPageLocalVisibleSources(objectPlans, sourceById) {
    var plans = objectPlans || [];
    var mutatedPlanCount = 0;
    var droppedPlanCount = 0;
    var removedSourceCount = 0;
    var retainedCrossPageFragmentCount = 0;
    var mutatedPlanIds = [];

    function sourceBelongsToPlanPage(sourceId, plan) {
        if (sourceId === null || sourceId === undefined || !plan) return true;
        if (plan.pageIndex === null || plan.pageIndex === undefined || Number(plan.pageIndex) < 0) return true;
        var src = sourceById ? sourceById[String(sourceId)] : null;
        if (!src) return true;
        if (src.pageIndex === null || src.pageIndex === undefined || Number(src.pageIndex) < 0) return true;
        return Number(src.pageIndex) === Number(plan.pageIndex);
    }

    function retainPageLocal(ids, plan) {
        var retained = [];
        var removed = [];
        var seenRetained = {};
        var seenRemoved = {};
        for (var i = 0; ids && i < ids.length; i++) {
            var id = ids[i];
            if (sourceBelongsToPlanPage(id, plan)) {
                _pushUniqueId(retained, seenRetained, id);
            } else {
                _pushUniqueId(removed, seenRemoved, id);
            }
        }
        return {
            retained: _sortedNumericIds(retained),
            removed: _sortedNumericIds(removed)
        };
    }

    function sameIds(a, b) {
        a = _sortedNumericIds(a || []);
        b = _sortedNumericIds(b || []);
        if (a.length !== b.length) return false;
        for (var i = 0; i < a.length; i++) {
            if (String(a[i]) !== String(b[i])) return false;
        }
        return true;
    }

    function boundsHasArea(bounds) {
        return bounds && bounds.length >= 4
                && Number(bounds[2]) > Number(bounds[0])
                && Number(bounds[3]) > Number(bounds[1]);
    }

    function shouldRetainCrossPageFragment(plan, visual, exported, descendants) {
        if (!plan || !boundsHasArea(plan.bounds)) return false;
        if (String(plan.coordinateSpace || "") !== "PAGE") return false;
        if (String(plan.placement || "") !== "FLOATING") return false;
        var retainedAny = (visual.retained && visual.retained.length > 0)
                || (exported.retained && exported.retained.length > 0)
                || (descendants.retained && descendants.retained.length > 0);
        if (retainedAny) return false;
        var removedAny = (visual.removed && visual.removed.length > 0)
                || (exported.removed && exported.removed.length > 0)
                || (descendants.removed && descendants.removed.length > 0);
        if (!removedAny) return false;
        var passId = String(plan.planPassId || plan.passId || "");
        return passId === "pass.image_placed_frames"
                || passId === "pass.image_textless_groups"
                || passId === "pass.page_textless_graphic_groups"
                || passId === "pass.master_page_graphics";
    }

    for (var pi = 0; pi < plans.length; pi++) {
        var plan = plans[pi];
        if (!_objectPlanHasVisibleVisual(plan)) continue;
        if (plan.visualAction === "PLACE_TABLE_STYLE") continue;

        var visual = retainPageLocal(plan.visualSourceObjectIds || [], plan);
        var exported = retainPageLocal(plan.exportSourceObjectIds || [], plan);
        var descendants = retainPageLocal(plan.descendantVisualObjectIds || [], plan);
        var removed = _sourceIdsUnion(
                _sourceIdsUnion(visual.removed, exported.removed),
                descendants.removed);
        if (removed.length === 0) continue;

        if (shouldRetainCrossPageFragment(plan, visual, exported, descendants)) {
            plan.visibleVisualPageLocalResolution = "RETAINED_CROSS_PAGE_FRAGMENT_VISIBLE_SOURCES";
            plan.visibleVisualPageLocalResolutionReason =
                    "source geometry may live on a spread sibling while ObjectPlan PAGE bounds declare the visible fragment";
            plan.reason = String(plan.reason || "") + ":cross_page_visible_source_retained";
            retainedCrossPageFragmentCount++;
            continue;
        }

        plan.hiddenVisualSourceObjectIds = _sourceIdsUnion(
                plan.hiddenVisualSourceObjectIds || [], removed);
        if (!sameIds(visual.retained, plan.visualSourceObjectIds || [])) {
            plan.visualSourceObjectIds = visual.retained;
        }
        if (!sameIds(exported.retained, plan.exportSourceObjectIds || [])) {
            plan.exportSourceObjectIds = exported.retained;
        }
        if (!sameIds(descendants.retained, plan.descendantVisualObjectIds || [])) {
            plan.descendantVisualObjectIds = descendants.retained;
        }
        removedSourceCount += removed.length;
        mutatedPlanCount++;
        mutatedPlanIds.push(plan.objectPlanId || plan.bundleId || plan.candidateId || ("plan.index." + pi));

        if ((!plan.visualSourceObjectIds || plan.visualSourceObjectIds.length === 0)
                && (!plan.exportSourceObjectIds || plan.exportSourceObjectIds.length === 0)) {
            plan.visualAction = "DROP_VISUAL";
            plan.materialization = "HWPX_TEXT";
            plan.visibleVisualPageLocalResolution = "DROPPED_NON_PAGE_LOCAL_VISIBLE_SOURCE_OWNER";
            droppedPlanCount++;
        } else {
            plan.visibleVisualPageLocalResolution = "PRUNED_NON_PAGE_LOCAL_VISIBLE_SOURCES";
        }
        plan.visibleVisualPageLocalResolutionReason =
                "visible/export sources must belong to the ObjectPlan page before Stage 2 execution";
        plan.reason = String(plan.reason || "") + ":page_local_visible_source_resolved";
    }

    return {
        summary: {
            mutatedPlanCount: mutatedPlanCount,
            droppedPlanCount: droppedPlanCount,
            removedSourceCount: removedSourceCount,
            retainedCrossPageFragmentCount: retainedCrossPageFragmentCount,
            mutatedObjectPlanIds: mutatedPlanIds
        }
    };
}

function _resolveObjectPlanRawClippedImageVisualSources(objectPlans, sourceById) {
    var plans = objectPlans || [];
    var mutatedPlanCount = 0;
    var replacedSourceCount = 0;
    var prunedSourceCount = 0;
    var mutatedPlanIds = [];

    function sourceType(src) {
        return String((src && (src.type || src.kind || src.itemType)) || "");
    }

    function boundsContains(outer, inner, tolerance) {
        if (!outer || !inner || outer.length < 4 || inner.length < 4) return false;
        tolerance = tolerance || 0;
        return Number(outer[0]) <= Number(inner[0]) + tolerance
                && Number(outer[1]) <= Number(inner[1]) + tolerance
                && Number(outer[2]) >= Number(inner[2]) - tolerance
                && Number(outer[3]) >= Number(inner[3]) - tolerance;
    }

    function clippedParentIdForImageSource(sourceId) {
        var image = sourceById ? sourceById[String(sourceId)] : null;
        if (!image || sourceType(image) !== "Image") return null;
        if (image.parentId === null || image.parentId === undefined) return null;
        var parent = sourceById[String(image.parentId)];
        if (!parent) return null;
        var parentType = sourceType(parent);
        if (parentType !== "Oval" && parentType !== "Polygon" && parentType !== "Rectangle") {
            return null;
        }
        if (parentType === "Oval" || parentType === "Polygon" || parent.clipContent === true) {
            return parent.id;
        }
        if (!boundsContains(parent.bounds || parent.geometricBounds,
                image.bounds || image.geometricBounds, 0.25)) {
            return parent.id;
        }
        return null;
    }

    function idSet(ids) {
        var out = {};
        for (var i = 0; ids && i < ids.length; i++) {
            out[String(ids[i])] = true;
        }
        return out;
    }

    for (var pi = 0; pi < plans.length; pi++) {
        var plan = plans[pi];
        if (!_objectPlanHasVisibleVisual(plan)) continue;
        if (plan.visualAction !== "PLACE_FLOATING_PNG"
                && plan.visualAction !== "PLACE_INLINE_PNG") {
            continue;
        }
        if (!plan.visualSourceObjectIds || plan.visualSourceObjectIds.length === 0) continue;
        var retained = [];
        var seen = {};
        var hiddenSet = idSet(plan.hiddenVisualSourceObjectIds || []);
        var changed = false;
        var pruned = false;
        for (var vi = 0; vi < plan.visualSourceObjectIds.length; vi++) {
            var sourceId = plan.visualSourceObjectIds[vi];
            var clipParentId = clippedParentIdForImageSource(sourceId);
            if (clipParentId !== null && clipParentId !== undefined) {
                if (hiddenSet[String(clipParentId)] === true) {
                    changed = true;
                    pruned = true;
                    prunedSourceCount++;
                    continue;
                }
                _pushUniqueId(retained, seen, clipParentId);
                changed = true;
                replacedSourceCount++;
            } else {
                _pushUniqueId(retained, seen, sourceId);
            }
        }
        if (!changed) continue;
        plan.visualSourceObjectIds = _sortedNumericIds(retained);
        plan.rawClippedImageVisualSourceResolution = pruned === true
                ? "PRUNED_RAW_IMAGE_VISUAL_SOURCE_WITH_HIDDEN_CLIP_PARENT"
                : "REPLACED_RAW_IMAGE_VISUAL_SOURCE_WITH_CLIP_PARENT";
        plan.rawClippedImageVisualSourceResolutionReason = pruned === true
                ? "clipped Image leaf ids are provenance and the clip-carrying parent was already hidden by slot ownership resolution"
                : "clipped Image leaf ids are provenance; the clip-carrying frame owns visible CONTENT_VISUAL_SLOT material";
        plan.reason = String(plan.reason || "") + ":raw_clipped_image_visual_source_resolved";
        mutatedPlanCount++;
        mutatedPlanIds.push(plan.objectPlanId || plan.bundleId || plan.candidateId || ("plan.index." + pi));
    }

    return {
        summary: {
            mutatedPlanCount: mutatedPlanCount,
            replacedSourceCount: replacedSourceCount,
            prunedSourceCount: prunedSourceCount,
            mutatedObjectPlanIds: mutatedPlanIds
        }
    };
}

function _objectPlanTextOwnerPriority(plan) {
    if (!plan) return 0;
    var score = 0;
    if (plan.textAction === "OWNED_BY_PNG") score += 200;
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

function _objectPlanFromPlannerBundle(bundle, index, sourceById) {
    bundle = _normalizeObjectPlanBundle(bundle || {});
    var textAction = _objectPlanTextAction(bundle, sourceById);
    var visualAction = _objectPlanVisualAction(bundle);
    var placement = _objectPlanPlacement(bundle);
    var coordinateSpace = _objectPlanCoordinateSpace(bundle, placement);
    var materialization = _objectPlanMaterialization(bundle, visualAction);
    var migrationStatus = _objectPlanMigrationStatus(bundle);
    var migrationBlocker = _objectPlanMigrationBlocker(bundle, migrationStatus);
    var ownedTextFrameIds = _sortedNumericIds(bundle.ownedTextFrameIds || []);
    var visualSourceObjectIds = _objectPlanPolicyVisualSourceIds(
            bundle.visualSourceObjectIds || [], ownedTextFrameIds, textAction, visualAction);
    if (visualSourceObjectIds.length === 0) {
        visualSourceObjectIds = _objectPlanFallbackVisualSourceIds(
                bundle, ownedTextFrameIds, textAction, visualAction, sourceById);
    }
    var styleSourceObjectIds = _objectPlanStyleSourceObjectIds(bundle, visualAction);
    if (visualAction === "ABSORB_TEXT_STYLE") {
        visualSourceObjectIds = [];
    }
    var inlineSourceTreeClosed = placement === "INLINE" && bundle.inlineSourceTreeClosed === true;
    var inlineFlowSourceObjectIds = inlineSourceTreeClosed
            ? _objectPlanInlineFlowSourceObjectIds(bundle)
            : [];

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
        inlineAnchorSourceObjectId: bundle.sourceInlineFlow === true
                ? (bundle.inlineAnchorSourceObjectId || null)
                : null,
        inlineSourceTreeClosed: inlineSourceTreeClosed,
        inlineFlowSourceObjectIds: inlineFlowSourceObjectIds,
        connectorDecorationVisual: bundle.connectorDecorationVisual === true,
        primarySourceObjectId: bundle.primarySourceObjectId !== undefined
                ? bundle.primarySourceObjectId
                : null,
        sourceSetId: bundle.sourceSetId || _sourceSetId(bundle.sourceObjectIds || []),
        sourceRootSetId: bundle.sourceRootSetId || _sourceSetId(bundle.sourceRootObjectIds || []),
        clusterSourceSetId: bundle.clusterSourceSetId || _sourceSetId(bundle.clusterSourceObjectIds || []),
        visualSourceSetId: bundle.visualSourceSetId || _sourceSetId(bundle.visualSourceObjectIds || []),
        exportSourceSetId: bundle.exportSourceSetId || _sourceSetId(bundle.exportSourceObjectIds || []),
        hiddenSourceSetId: bundle.hiddenSourceSetId || _sourceSetId(bundle.hiddenVisualSourceObjectIds || []),
        ownedByNativeShellSourceObjectIds: _internSourceSetIds(
                bundle.ownedByNativeShellSourceObjectIds || []),
        sourceObjectIds: _internSourceSetIds(bundle.sourceObjectIds || []),
        sourceRootObjectIds: _internSourceSetIds(bundle.sourceRootObjectIds || []),
        clusterSourceObjectIds: _internSourceSetIds(bundle.clusterSourceObjectIds || []),
        clusterKindCounts: bundle.clusterKindCounts || {},
        omittedClusterSourceObjectIds: _internSourceSetIds(bundle.omittedClusterSourceObjectIds || []),
        omittedClusterKindCounts: bundle.omittedClusterKindCounts || {},
        clusterHasEditableText: bundle.clusterHasEditableText === true,
        clusterHasTextFrame: bundle.clusterHasTextFrame === true,
        clusterHasPlacedContent: bundle.clusterHasPlacedContent === true,
        clusterHasVisualSource: bundle.clusterHasVisualSource === true,
        visualSourceObjectIds: visualSourceObjectIds,
        styleSourceObjectIds: styleSourceObjectIds,
        ownedTextFrameIds: ownedTextFrameIds,
        exportSourceObjectIds: _internSourceSetIds(bundle.exportSourceObjectIds || []),
        exportTargetObjectId: bundle.exportTargetObjectId !== undefined
                ? bundle.exportTargetObjectId
                : null,
        atomicExportTargetObjectId: bundle.atomicExportTargetObjectId !== undefined
                ? bundle.atomicExportTargetObjectId
                : null,
        atomicExportTargetObjectIds: _internSourceSetIds(
                bundle.atomicExportTargetObjectIds || []),
        atomicTextlessVectorContent: bundle.atomicTextlessVectorContent === true,
        atomicContentVisualSlot: bundle.atomicContentVisualSlot === true,
        hiddenVisualSourceObjectIds: _internSourceSetIds(bundle.hiddenVisualSourceObjectIds || []),
        materialization: materialization,
        textAction: textAction,
        visualAction: visualAction,
        placement: placement,
        coordinateSpace: coordinateSpace,
        visualLayer: _objectPlanVisualLayer(bundle),
        zOrder: bundle.zOrder !== undefined ? bundle.zOrder : null,
        reason: _objectPlanReason(bundle, migrationStatus),
        bounds: bundle.bounds || null,
        renderSourceBounds: bundle.renderSourceBounds || null,
        cropSourceBounds: bundle.cropSourceBounds || null,
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
    if (_objectPlanBundleIsClosedBackgroundVisibleCarrier(bundle)) {
        return _closedBackgroundVisibleCarrierBundle(bundle);
    }
    if (_objectPlanBundleIsClosedPlacedContentCarrier(bundle)) {
        return _closedPlacedContentCarrierBundle(bundle);
    }
    return bundle || {};
}

function _objectPlanSourceSetMembership(ids) {
    var out = {};
    for (var i = 0; ids && i < ids.length; i++) {
        out[String(ids[i])] = true;
    }
    return out;
}

function _objectPlanSourceSetContainsAll(containerIds, memberIds) {
    var container = _objectPlanSourceSetMembership(containerIds || []);
    for (var i = 0; memberIds && i < memberIds.length; i++) {
        if (!container[String(memberIds[i])]) return false;
    }
    return true;
}

function _objectPlanSourceSetsEqual(a, b) {
    var aa = _sortedNumericIds(a || []);
    var bb = _sortedNumericIds(b || []);
    if (aa.length !== bb.length) return false;
    for (var i = 0; i < aa.length; i++) {
        if (String(aa[i]) !== String(bb[i])) return false;
    }
    return true;
}

function _objectPlanSourceIdsMinus(sourceIds, removedIds) {
    var removed = _objectPlanSourceSetMembership(removedIds || []);
    var out = [];
    var seen = {};
    for (var i = 0; sourceIds && i < sourceIds.length; i++) {
        var id = Number(sourceIds[i]);
        if (isNaN(id)) continue;
        if (removed[String(id)]) continue;
        if (seen[String(id)]) continue;
        seen[String(id)] = true;
        out.push(id);
    }
    return _sortedNumericIds(out);
}

function _objectPlanSourceIdsUnion(a, b) {
    var out = [];
    var seen = {};
    var lists = [a || [], b || []];
    for (var li = 0; li < lists.length; li++) {
        for (var i = 0; i < lists[li].length; i++) {
            var id = Number(lists[li][i]);
            if (isNaN(id)) continue;
            if (seen[String(id)]) continue;
            seen[String(id)] = true;
            out.push(id);
        }
    }
    return _sortedNumericIds(out);
}

function _objectPlanInlineFlowSourceObjectIds(bundle) {
    if (!bundle || bundle.inlineSourceTreeClosed !== true) return [];
    var ids = bundle.inlineFlowSourceObjectIds || [];
    var out = [];
    var seen = {};
    for (var i = 0; i < ids.length; i++) {
        var id = Number(ids[i]);
        if (isNaN(id)) continue;
        var key = String(id);
        if (seen[key]) continue;
        seen[key] = true;
        out.push(id);
    }
    return out;
}

function _objectPlanPolicyVisualSourceIds(visualSourceIds, ownedTextFrameIds, textAction, visualAction) {
    var visualIds = _sortedNumericIds(visualSourceIds || []);
    var ownedIds = _sortedNumericIds(ownedTextFrameIds || []);
    if (visualIds.length === 0 || ownedIds.length === 0) return visualIds;
    if (textAction === "OWNED_BY_PNG") return visualIds;
    var owned = _sourceIdSet(ownedIds);
    var out = [];
    for (var i = 0; i < visualIds.length; i++) {
        if (!owned[String(visualIds[i])]) out.push(visualIds[i]);
    }
    return out;
}

function _objectPlanFallbackVisualSourceIds(bundle, ownedTextFrameIds, textAction, visualAction, sourceById) {
    if (!bundle) return [];
    if (textAction === "OWNED_BY_PNG") return [];
    if (visualAction !== "PLACE_INLINE_PNG"
            && visualAction !== "PLACE_FLOATING_PNG"
            && visualAction !== "PLACE_TEXT_SHELL") {
        return [];
    }
    if (!ownedTextFrameIds || ownedTextFrameIds.length === 0) return [];
    var candidates = [];
    candidates = candidates.concat(bundle.sourceRootObjectIds || []);
    candidates = candidates.concat(bundle.visualSourceObjectIds || []);
    candidates = candidates.concat(bundle.exportSourceObjectIds || []);
    candidates = candidates.concat(bundle.sourceObjectIds || []);
    return _objectPlanNonTextVisualSourceIds(candidates, ownedTextFrameIds, sourceById);
}

function _objectPlanNonTextVisualSourceIds(ids, ownedTextFrameIds, sourceById) {
    var owned = _sourceIdSet(ownedTextFrameIds || []);
    var out = [];
    var seen = {};
    for (var i = 0; ids && i < ids.length; i++) {
        var id = Number(ids[i]);
        if (isNaN(id)) continue;
        if (owned[String(id)]) continue;
        var src = sourceById ? sourceById[String(id)] : null;
        if (src && _objectPlanSourceKindIsTextOnly(src.kind)) continue;
        _pushUniqueId(out, seen, id);
    }
    return _sortedNumericIds(out);
}

function _objectPlanSourceKindIsTextOnly(kind) {
    kind = String(kind || "");
    return kind === "TextFrame"
            || kind === "Story"
            || kind === "Character"
            || kind === "InsertionPoint"
            || kind === "Cell";
}

function _objectPlanStyleSourceObjectIds(bundle, visualAction) {
    if (!bundle) return [];
    if (visualAction === "ABSORB_TEXT_STYLE") {
        var ids = bundle.styleSourceObjectIds && bundle.styleSourceObjectIds.length > 0
                ? bundle.styleSourceObjectIds
                : (bundle.sourceObjectIds || []);
        return _sortedNumericIds(ids);
    }
    return _sortedNumericIds(bundle.styleSourceObjectIds || []);
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

function _objectPlanBundleIsClosedBackgroundVisibleCarrier(bundle) {
    if (!bundle) return false;
    if (bundle.passId !== "pass.complex_graphic_frames") return false;
    if (bundle.ownershipSlot !== "SHELL_SLOT") return false;
    if (bundle.slotRole !== "background_shell_slot"
            && bundle.compositeRole !== "background_vector_source") return false;
    if (bundle.clusterHasEditableText === true || bundle.clusterHasTextFrame === true) return false;
    if (bundle.ownedTextFrameIds && bundle.ownedTextFrameIds.length > 0) return false;
    if (!bundle.sourceObjectIds || bundle.sourceObjectIds.length < 2) return false;
    if (!bundle.visualSourceObjectIds || bundle.visualSourceObjectIds.length === 0) return false;
    if (!_objectPlanSourceSetContainsAll(bundle.sourceObjectIds || [], bundle.visualSourceObjectIds || [])) return false;
    if (_objectPlanSourceSetsEqual(bundle.sourceObjectIds || [], bundle.visualSourceObjectIds || [])) return false;
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

function _closedBackgroundVisibleCarrierBundle(bundle) {
    var closed = {};
    for (var key in bundle) {
        if (bundle.hasOwnProperty(key)) closed[key] = bundle[key];
    }
    var originalSourceIds = _sortedNumericIds(bundle.sourceObjectIds || []);
    var visibleIds = _sortedNumericIds(bundle.visualSourceObjectIds || []);
    var omittedIds = _sortedNumericIds(
            _objectPlanSourceIdsMinus(originalSourceIds, visibleIds));
    closed.sourceObjectIds = visibleIds;
    closed.clusterSourceObjectIds = visibleIds.slice(0);
    closed.visualSourceObjectIds = visibleIds.slice(0);
    if (!closed.exportSourceObjectIds || closed.exportSourceObjectIds.length === 0) {
        closed.exportSourceObjectIds = visibleIds.slice(0);
    }
    closed.omittedClusterSourceObjectIds = _sortedNumericIds(
            _objectPlanSourceIdsUnion(
                    bundle.omittedClusterSourceObjectIds || [],
                    omittedIds));
    closed.clusterRelation = "EXACT_SOURCE_CLUSTER";
    closed.closedBackgroundVisibleCarrier = true;
    return closed;
}

function _objectPlanId(bundle, index) {
    var id = bundle && bundle.bundleId ? String(bundle.bundleId) : ("bundle.index." + index);
    return "objectPlan." + id.replace(/[^A-Za-z0-9_.-]/g, "_");
}

function _objectPlanTextAction(bundle, sourceById) {
    if (bundle && bundle.textAction === "OWNED_BY_PNG") {
        return "OWNED_BY_PNG";
    }
    if (bundle && bundle.textAction === "DROP_TEXT") {
        return "DROP_TEXT";
    }
    if (bundle && bundle.ownershipSlot === "SHELL_SLOT"
            && _objectPlanVisualAction(bundle) === "PLACE_TEXT_SHELL"
            && bundle.textOwner !== "indesign_png") {
        return "DROP_TEXT";
    }
    if (bundle && bundle.ownershipSlot === "SHELL_SLOT"
            && _objectPlanVisualAction(bundle) === "DROP_VISUAL") {
        return "DROP_TEXT";
    }
    if (_objectPlanBundleOwnsOnlySimpleInlineMarkerText(bundle, sourceById)) {
        return "OWNED_BY_PNG";
    }
    if (_objectPlanBundleOwnsInlineCompletePngText(bundle)) {
        return "OWNED_BY_PNG";
    }
    if (bundle.ownedTextFrameIds && bundle.ownedTextFrameIds.length > 0) {
        return "OWNED_BY_HWPX_TEXT";
    }
    if (!bundle || bundle.executable !== true) return "DROP_TEXT";
    if (bundle.ownershipSlot === "SHELL_SLOT") return "DROP_TEXT";
    return "DROP_TEXT";
}

function _objectPlanBundleOwnsInlineCompletePngText(bundle) {
    if (!bundle || bundle.passId !== "pass.inline_objects") return false;
    if (bundle.ownershipSlot === "SHELL_SLOT") return false;
    if (bundle.slotRole === "direct_child_shell_slot"
            || bundle.compositeRole === "direct_child_shell_slot") return false;
    return bundle.ownedTextFrameIds && bundle.ownedTextFrameIds.length > 0
            && ((bundle.visualSourceObjectIds && bundle.visualSourceObjectIds.length > 0)
                || (bundle.exportSourceObjectIds && bundle.exportSourceObjectIds.length > 0));
}

function _objectPlanBundleOwnsOnlySimpleInlineMarkerText(bundle, sourceById) {
    if (!bundle) return false;
    if (bundle.ownershipSlot === "SHELL_SLOT"
            && (bundle.slotRole === "direct_child_shell_slot"
                || bundle.compositeRole === "direct_child_shell_slot")) {
        return false;
    }
    if (bundle.passId !== "pass.inline_objects"
            && bundle.passId !== "pass.page_textless_graphic_groups") {
        return false;
    }
    if (!bundle.ownedTextFrameIds || bundle.ownedTextFrameIds.length === 0) return false;
    if ((!bundle.visualSourceObjectIds || bundle.visualSourceObjectIds.length === 0)
            && (!bundle.exportSourceObjectIds || bundle.exportSourceObjectIds.length === 0)) {
        return false;
    }
    for (var i = 0; i < bundle.ownedTextFrameIds.length; i++) {
        var src = sourceById ? sourceById[String(bundle.ownedTextFrameIds[i])] : null;
        if (!src || String(src.kind || "") !== "TextFrame") return false;
        if (src.simpleMarkerLabelContents !== true) return false;
    }
    return true;
}

function _objectPlanVisualAction(bundle) {
    if (!bundle || bundle.executable !== true) return "DROP_VISUAL";
    if (bundle.layoutOnlyInlineSlot === true) return "DROP_VISUAL";
    if (bundle.ownershipSlot === "TABLE_STYLE_SLOT") return "PLACE_TABLE_STYLE";
    if (_objectPlanBundleIsInlineVectorTextStyleMarker(bundle)) return "ABSORB_TEXT_STYLE";
    if (bundle.ownershipSlot === "CONTENT_VISUAL_SLOT"
            && (!bundle.visualSourceObjectIds || bundle.visualSourceObjectIds.length === 0)) {
        return "DROP_VISUAL";
    }
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
        return _objectPlanPlacement(bundle) === "INLINE"
                ? "PLACE_INLINE_PNG"
                : "PLACE_FLOATING_PNG";
    }
    return "DROP_VISUAL";
}

function _objectPlanBundleIsInlineVectorTextStyleMarker(bundle) {
    if (!bundle || bundle.passId !== "pass.inline_objects") return false;
    if (_objectPlanPlacement(bundle) !== "INLINE") return false;
    if (bundle.clusterHasTextFrame === true || bundle.clusterHasEditableText === true) return false;
    if (bundle.clusterHasPlacedContent === true) return false;
    var counts = bundle.clusterKindCounts || {};
    var graphicLineCount = Number(counts.GraphicLine || counts.graphicLine || 0);
    if (graphicLineCount > 0) {
        for (var lineKind in counts) {
            if (!counts.hasOwnProperty(lineKind)) continue;
            if (Number(counts[lineKind] || 0) <= 0) continue;
            if (lineKind !== "GraphicLine") return false;
        }
        return true;
    }
    if (!_objectPlanBundleHasOnlyInlineVectorMarkerKinds(counts)) return false;
    if (!_objectPlanBundleHasTextStyleMarkerBounds(bundle)) return false;
    return true;
}

function _objectPlanBundleHasOnlyInlineVectorMarkerKinds(counts) {
    var visibleKindCount = 0;
    var rectangleCount = 0;
    for (var kind in counts) {
        if (!counts.hasOwnProperty(kind)) continue;
        if (Number(counts[kind] || 0) <= 0) continue;
        visibleKindCount += Number(counts[kind] || 0);
        if (kind === "Rectangle") {
            rectangleCount += Number(counts[kind] || 0);
            continue;
        }
        if (kind === "Group") continue;
        return false;
    }
    return visibleKindCount > 0 && rectangleCount > 0;
}

function _objectPlanBundleHasTextStyleMarkerBounds(bundle) {
    var bounds = bundle ? bundle.bounds : null;
    if (!bounds || bounds.length < 4) return false;
    var h = Math.abs(Number(bounds[2]) - Number(bounds[0]));
    var w = Math.abs(Number(bounds[3]) - Number(bounds[1]));
    if (isNaN(h) || isNaN(w) || h <= 0 || w <= 0) return false;
    return h <= 3 && w >= h * 4;
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
    if (bundle && bundle.inlineAnchorSourceObjectId && bundle.sourceInlineFlow === true) return "INLINE";
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
    if (_objectPlanBundleOwnsInlineCompletePngText(bundle)) return "COMPLETE_PNG";
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
    if (_objectPlanBundleOwnsInlineCompletePngText(bundle)) return "READY_INLINE_COMPLETE_PNG_TEXT_OWNER";
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
    if (_objectPlanBundleIsInlineVectorTextStyleMarker(bundle)) {
        return "inline_vector_text_style_marker";
    }
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
    if (!_sourceSetContainsAll(bundle.exportSourceObjectIds || [], bundle.visualSourceObjectIds || [])
            && !_objectPlanAllowsRootExportVisibleFragmentContract(bundle)) return false;
    if (_sourceSetsIntersect(bundle.visualSourceObjectIds || [], bundle.hiddenVisualSourceObjectIds || [])) return false;
    return true;
}

function _objectPlanAllowsRootExportVisibleFragmentContract(bundle) {
    if (!bundle) return false;
    if (bundle.passId !== "pass.complex_graphic_frames") return false;
    if (bundle.ownershipSlot !== "SHELL_SLOT") return false;
    if (bundle.slotRole !== "background_shell_slot"
            && bundle.compositeRole !== "background_vector_source"
            && bundle.compositeRole !== "complex_graphic_source_set") return false;
    if (bundle.exportTargetObjectId === null || bundle.exportTargetObjectId === undefined) return false;
    if (!bundle.exportSourceObjectIds || bundle.exportSourceObjectIds.length === 0) return false;
    if (bundle.clusterHasEditableText === true || bundle.clusterHasTextFrame === true) return false;
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
            || status === "READY_INLINE_COMPLETE_PNG_TEXT_OWNER"
            || status === "READY_LAYOUT_ONLY_INLINE_SLOT"
            || status === "READY_TEXT_CLEANUP";
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
    var visibleVisualSourceOwners = {};
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
            _collectObjectPlanVisibleVisualSourceOwners(visibleVisualSourceOwners, plan);
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

    for (var sourceKey in visibleVisualSourceOwners) {
        if (!visibleVisualSourceOwners.hasOwnProperty(sourceKey)) continue;
        if (visibleVisualSourceOwners[sourceKey].length > 1) {
            var sourceKeyParts = String(sourceKey).split("|");
            _pushObjectPlanIssue(issues, issueCodeCounts, issuePlanIds,
                    "duplicate_visible_visual_source", visibleVisualSourceOwners[sourceKey],
                    {
                        pageIndex: sourceKeyParts.length > 1 ? sourceKeyParts[0] : null,
                        sourceObjectId: sourceKeyParts.length > 1 ? sourceKeyParts[1] : sourceKey
                    });
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

function _collectObjectPlanVisibleVisualSourceOwners(ownersBySourceId, plan) {
    if (!ownersBySourceId || !plan) return;
    if (plan.visualAction === "PLACE_TABLE_STYLE") return;
    if (!plan.visualSourceObjectIds || plan.visualSourceObjectIds.length === 0) return;
    var pageKey = String(plan.pageIndex);
    for (var i = 0; i < plan.visualSourceObjectIds.length; i++) {
        var sourceId = plan.visualSourceObjectIds[i];
        if (sourceId === null || sourceId === undefined) continue;
        var key = pageKey + "|" + String(sourceId);
        if (!ownersBySourceId[key]) ownersBySourceId[key] = [];
        ownersBySourceId[key].push(plan);
    }
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
    if (overlap.length > 0 && _objectPlanAllowsCompletePngTextSourceOverlap(plan, overlap)) {
        return;
    }
    if (overlap.length > 0) {
        _pushObjectPlanIssue(issues, issueCodeCounts, issuePlanIds,
                "visual_sources_include_owned_text_frames", [plan],
                { overlappingIds: overlap });
    }
}

function _objectPlanAllowsCompletePngTextSourceOverlap(plan, overlap) {
    if (!plan || !overlap || overlap.length === 0) return false;
    if (plan.textAction !== "OWNED_BY_PNG") return false;
    if (plan.materialization !== "COMPLETE_PNG") return false;
    if (plan.visualAction !== "PLACE_INLINE_PNG"
            && plan.visualAction !== "PLACE_FLOATING_PNG") return false;
    return plan.ownershipSlot === "CONTENT_VISUAL_SLOT";
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
