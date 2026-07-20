/*
 * ObjectPlan-shaped diagnostics for extract_indd.jsx.
 *
 * This module does not execute ownership. It maps planner-declared bundles to
 * the policy ObjectPlan contract and validates that contract. The legacy
 * execution candidate adapter lives in execution_candidates.jsx while executors
 * are still being migrated.
 */

var OBJECT_PLAN_MASTER_PLANE_Z_ORDER = -1000000;
var OBJECT_PLAN_PAGE_BACKGROUND_PLANE_Z_ORDER = -900000;

function _buildObjectPlanDiagnostics(sourceItems, candidates) {
    var plannerBundles = _buildPlannerBundles(sourceItems, candidates);
    return _buildObjectPlanDiagnosticsFromPlannerBundles(plannerBundles, sourceItems);
}

function _objectPlanDiagnosticsProgress(options, step, current, total, desc) {
    if (!options || !options.outputDir || typeof writeProgress !== "function") return;
    try {
        writeProgress(options.outputDir, step, current || 0, total || 0, desc || "");
    } catch (eObjectPlanDiagnosticsProgress) {}
}

var _objectPlanDiagnosticsCursorLastWriteMs = 0;
function _objectPlanDiagnosticsCursor(options, stage, index, total, bundle) {
    if (!options || !options.outputDir || typeof writeJson !== "function") return;
    var detailed = options.objectPlanCursorDetail === true;
    if (!detailed) {
        var indexNumber = Number(index);
        var hasIndex = index !== null && index !== undefined && !isNaN(indexNumber);
        var periodicIndex = hasIndex && (indexNumber === 0 || (indexNumber % 100) === 0);
        var terminalStage = stage === "plannerBundle.after"
                && Number(indexNumber) === Number(total) - 1;
        var now = (new Date()).getTime();
        if (!terminalStage && !periodicIndex && now - _objectPlanDiagnosticsCursorLastWriteMs < 750) {
            return;
        }
        _objectPlanDiagnosticsCursorLastWriteMs = now;
    }
    var sourceObjectIds = bundle && bundle.sourceObjectIds ? bundle.sourceObjectIds : [];
    var visualSourceObjectIds = bundle && bundle.visualSourceObjectIds ? bundle.visualSourceObjectIds : [];
    var ownedTextFrameIds = bundle && bundle.ownedTextFrameIds ? bundle.ownedTextFrameIds : [];
    try {
        writeJson(options.outputDir + "/_object_plan_cursor.json", {
            stage: stage,
            bundleIndex: index,
            total: total,
            bundleId: bundle && bundle.bundleId !== undefined ? bundle.bundleId : null,
            bundleKind: bundle && bundle.kind !== undefined ? bundle.kind : null,
            materialization: bundle && bundle.materialization !== undefined ? bundle.materialization : null,
            textAction: bundle && bundle.textAction !== undefined ? bundle.textAction : null,
            visualAction: bundle && bundle.visualAction !== undefined ? bundle.visualAction : null,
            placement: bundle && bundle.placement !== undefined ? bundle.placement : null,
            coordinateSpace: bundle && bundle.coordinateSpace !== undefined ? bundle.coordinateSpace : null,
            sourceObjectIdCount: sourceObjectIds.length,
            sourceObjectIds: sourceObjectIds.slice ? sourceObjectIds.slice(0, 24) : sourceObjectIds,
            visualSourceObjectIdCount: visualSourceObjectIds.length,
            visualSourceObjectIds: visualSourceObjectIds.slice ? visualSourceObjectIds.slice(0, 24) : visualSourceObjectIds,
            ownedTextFrameIdCount: ownedTextFrameIds.length,
            ownedTextFrameIds: ownedTextFrameIds.slice ? ownedTextFrameIds.slice(0, 24) : ownedTextFrameIds
        });
    } catch (eObjectPlanDiagnosticsCursor) {}
}

function _buildObjectPlanDiagnosticsFromPlannerBundles(plannerBundles, sourceItems, options) {
    var bundles = plannerBundles && plannerBundles.bundles ? plannerBundles.bundles : [];
    var objectPlans = [];
    var buildTimings = [];
    function _objectPlanNowMs() {
        try { return (new Date()).getTime(); } catch (eObjectPlanNow) { return 0; }
    }
    function _recordObjectPlanTiming(tag, startedAt, extra) {
        var row = {
            tag: tag,
            ms: _objectPlanNowMs() - startedAt
        };
        if (extra) {
            for (var key in extra) {
                if (extra.hasOwnProperty && !extra.hasOwnProperty(key)) continue;
                row[key] = extra[key];
            }
        }
        buildTimings.push(row);
    }
    _objectPlanDiagnosticsProgress(options, "object_plan_source_index", 0,
            sourceItems ? sourceItems.length : 0, "build object plan source indexes");
    var _timingStartedAt = _objectPlanNowMs();
    var sourceById = _objectPlanSourceInfoById(sourceItems);
    var childrenByParentId = _objectPlanSourceChildrenByParentId(sourceItems);
    _recordObjectPlanTiming("sourceIndex", _timingStartedAt, {
        sourceItemCount: sourceItems ? sourceItems.length : 0
    });

    _objectPlanDiagnosticsProgress(options, "object_plan_planner_bundles", 0,
            bundles.length, "build object plans from planner bundles");
    _timingStartedAt = _objectPlanNowMs();
    for (var i = 0; i < bundles.length; i++) {
        if (i === 0 || (i % 100) === 0) {
            _objectPlanDiagnosticsProgress(options, "object_plan_planner_bundles", i,
                    bundles.length, "build object plans from planner bundles");
        }
        _objectPlanDiagnosticsCursor(options, "plannerBundle.before", i, bundles.length, bundles[i]);
        var plan = _objectPlanFromPlannerBundle(bundles[i], i, sourceById);
        _objectPlanDiagnosticsCursor(options, "plannerBundle.after", i, bundles.length, bundles[i]);
        objectPlans.push(plan);
    }
    _objectPlanDiagnosticsProgress(options, "object_plan_planner_bundles", bundles.length,
            bundles.length, "planner bundle object plans ready");
    _recordObjectPlanTiming("plannerBundlePlans", _timingStartedAt, {
        bundleCount: bundles.length,
        objectPlanCount: objectPlans.length
    });
    _timingStartedAt = _objectPlanNowMs();
    var deduplication = _deduplicateObjectPlansByIdentity(objectPlans);
    _recordObjectPlanTiming("deduplicateByIdentity", _timingStartedAt, {
        objectPlanCount: objectPlans.length,
        removedPlanCount: deduplication.summary ? deduplication.summary.removedPlanCount : 0
    });
    _timingStartedAt = _objectPlanNowMs();
    _appendEditableTextFrameObjectPlans(objectPlans, sourceItems);
    _recordObjectPlanTiming("appendEditableTextFramePlans", _timingStartedAt, {
        objectPlanCount: objectPlans.length
    });
    _timingStartedAt = _objectPlanNowMs();
    _appendVisibleTextFrameObjectPlans(objectPlans, sourceItems);
    _recordObjectPlanTiming("appendVisibleTextFramePlans", _timingStartedAt, {
        objectPlanCount: objectPlans.length
    });
    _timingStartedAt = _objectPlanNowMs();
    var sourceBundleTextRangeShells = _appendSourceBundleTextRangeShellObjectPlans(
            objectPlans, sourceItems, sourceById, childrenByParentId);
    _recordObjectPlanTiming("appendSourceBundleTextRangeShellPlans", _timingStartedAt, {
        objectPlanCount: objectPlans.length,
        createdPlanCount: sourceBundleTextRangeShells.summary.createdPlanCount
    });
    _timingStartedAt = _objectPlanNowMs();
    _appendEmptyEditableTextFrameObjectPlans(objectPlans, sourceItems);
    _recordObjectPlanTiming("appendEmptyEditableTextFramePlans", _timingStartedAt, {
        objectPlanCount: objectPlans.length
    });
    _timingStartedAt = _objectPlanNowMs();
    var inlineVisibleTextFrameShellInventory =
            _appendInlineVisibleTextFrameShellObjectPlans(objectPlans, sourceItems);
    _recordObjectPlanTiming("appendInlineVisibleTextFrameShellPlans", _timingStartedAt, {
        objectPlanCount: objectPlans.length,
        createdPlanCount: inlineVisibleTextFrameShellInventory.summary.createdPlanCount
    });
    _timingStartedAt = _objectPlanNowMs();
    var repeatedEmptyInlineTextFramePlaceholders =
            _resolveRepeatedEmptyInlineTextFramePlaceholders(objectPlans);
    _recordObjectPlanTiming("resolveRepeatedEmptyInlineTextFramePlaceholders", _timingStartedAt, {
        objectPlanCount: objectPlans.length,
        mutatedPlanCount: repeatedEmptyInlineTextFramePlaceholders.mutatedPlanCount
    });
    _timingStartedAt = _objectPlanNowMs();
    _appendTableOnlyTextFrameObjectPlans(objectPlans, sourceItems);
    _recordObjectPlanTiming("appendTableOnlyTextFramePlans", _timingStartedAt, {
        objectPlanCount: objectPlans.length
    });
    _timingStartedAt = _objectPlanNowMs();
    _appendTextFrameCleanupObjectPlans(objectPlans, sourceItems);
    _recordObjectPlanTiming("appendTextFrameCleanupPlans", _timingStartedAt, {
        objectPlanCount: objectPlans.length
    });
    _timingStartedAt = _objectPlanNowMs();
    var pngOwnedTextFrameCleanup = _applyPngOwnedTextFrameCleanupObjectPlans(objectPlans, sourceItems);
    _recordObjectPlanTiming("pngOwnedTextFrameCleanup", _timingStartedAt, {
        objectPlanCount: objectPlans.length
    });
    _timingStartedAt = _objectPlanNowMs();
    var inlineVisibleTextFrameShells =
            _promoteInlineVisibleTextFrameShellObjectPlans(objectPlans, sourceById);
    _recordObjectPlanTiming("promoteInlineVisibleTextFrameShellPlans", _timingStartedAt, {
        objectPlanCount: objectPlans.length,
        mutatedPlanCount: inlineVisibleTextFrameShells.summary.mutatedPlanCount
    });
    _timingStartedAt = _objectPlanNowMs();
    var textOwnershipResolution = _resolveObjectPlanDuplicateTextOwners(objectPlans, sourceById);
    _recordObjectPlanTiming("resolveDuplicateTextOwners", _timingStartedAt, {
        objectPlanCount: objectPlans.length
    });
    _timingStartedAt = _objectPlanNowMs();
    var nestedInlineTextShellResolution =
            _resolveObjectPlanNestedInlineTextShellOwners(objectPlans, sourceById);
    _recordObjectPlanTiming("resolveNestedInlineTextShellOwners", _timingStartedAt, {
        objectPlanCount: objectPlans.length
    });
    _timingStartedAt = _objectPlanNowMs();
    var visibleVisualSourceResolution = _resolveObjectPlanDuplicateVisibleVisualSources(objectPlans);
    _recordObjectPlanTiming("resolveDuplicateVisibleVisualSources", _timingStartedAt, {
        objectPlanCount: objectPlans.length
    });
    _timingStartedAt = _objectPlanNowMs();
    var inlineCompletePngTextOwnerResolution =
            _resolveObjectPlanDuplicateInlineCompletePngTextOwners(objectPlans, sourceById);
    _recordObjectPlanTiming("resolveDuplicateInlineCompletePngTextOwners", _timingStartedAt, {
        objectPlanCount: objectPlans.length
    });
    _timingStartedAt = _objectPlanNowMs();
    var inlineVisualInventory =
            _appendInlineVisualInventoryObjectPlans(objectPlans, sourceItems, sourceById);
    _recordObjectPlanTiming("appendInlineVisualInventoryPlans", _timingStartedAt, {
        objectPlanCount: objectPlans.length
    });
    _timingStartedAt = _objectPlanNowMs();
    var layoutOnlyInlineSlots =
            _appendLayoutOnlyInlineSlotObjectPlans(objectPlans, sourceItems);
    _recordObjectPlanTiming("appendLayoutOnlyInlineSlotPlans", _timingStartedAt, {
        objectPlanCount: objectPlans.length,
        createdPlanCount: layoutOnlyInlineSlots.summary.createdPlanCount
    });
    _timingStartedAt = _objectPlanNowMs();
    var pageRootTextlessPlaneInventory = {
        summary: {
            createdPlaneCount: 0,
            visualSourceCount: 0,
            excludedInlineSourceCount: 0,
            createdObjectPlanIds: [],
            reason: "page_visuals_are_exported_by_canonical_single_textless_page_plane"
        }
    };
    _recordObjectPlanTiming("appendPageRootTextlessPlanePlans", _timingStartedAt, {
        objectPlanCount: objectPlans.length
    });
    _timingStartedAt = _objectPlanNowMs();
    var pageLocalVisibleSourceResolution = _resolveObjectPlanPageLocalVisibleSources(objectPlans, sourceById);
    _recordObjectPlanTiming("resolvePageLocalVisibleSources", _timingStartedAt, {
        objectPlanCount: objectPlans.length
    });
    _timingStartedAt = _objectPlanNowMs();
    var rawClippedImageVisualSourceResolution =
            _resolveObjectPlanRawClippedImageVisualSources(objectPlans, sourceById);
    _recordObjectPlanTiming("resolveRawClippedImageVisualSources", _timingStartedAt, {
        objectPlanCount: objectPlans.length
    });
    _timingStartedAt = _objectPlanNowMs();
    var pageBackgroundPlaneMaterialization = {
        summary: {
            createdPlaneCount: 0,
            absorbedPlanCount: 0,
            createdObjectPlanIds: [],
            absorbedObjectPlanIds: [],
            normalizedExistingPlaneCount: 0,
            normalizedExistingObjectPlanIds: [],
            protectedTextlessGroupSlotCount: 0,
            protectedTextlessGroupSlots: [],
            disabled: true,
            reason: "canonical_single_textless_page_plane_is_the_only_page_visual_owner"
        }
    };
    _recordObjectPlanTiming("applyPageBackgroundPlaneMaterialization", _timingStartedAt, {
        objectPlanCount: objectPlans.length,
        disabled: true
    });
    _timingStartedAt = _objectPlanNowMs();
    var depthFinalization = _finalizeObjectPlanVisualDepthContracts(objectPlans, sourceItems);
    _recordObjectPlanTiming("finalizeVisualDepthContracts", _timingStartedAt, {
        objectPlanCount: objectPlans.length
    });
    _timingStartedAt = _objectPlanNowMs();
    var inlineFlowContractFinalization = _finalizeObjectPlanInlineFlowContracts(objectPlans);
    _recordObjectPlanTiming("finalizeInlineFlowContracts", _timingStartedAt, {
        objectPlanCount: objectPlans.length
    });
    _timingStartedAt = _objectPlanNowMs();
    var validation = _validateObjectPlanDiagnostics(objectPlans, sourceById);
    _recordObjectPlanTiming("validateObjectPlans", _timingStartedAt, {
        objectPlanCount: objectPlans.length
    });
    _timingStartedAt = _objectPlanNowMs();
    var sourceSetRefs = _attachObjectPlanSourceSetRefs(objectPlans);
    _recordObjectPlanTiming("attachSourceSetRefs", _timingStartedAt, {
        objectPlanCount: objectPlans.length
    });
    _timingStartedAt = _objectPlanNowMs();
    var summary = _summarizeObjectPlans(objectPlans, validation);
    _recordObjectPlanTiming("summarizeObjectPlans", _timingStartedAt, {
        objectPlanCount: objectPlans.length
    });
    summary.objectPlanDeduplication = deduplication.summary;
    summary.inlineVisibleTextFrameShellInventory = inlineVisibleTextFrameShellInventory.summary;
    summary.pngOwnedTextFrameCleanup = pngOwnedTextFrameCleanup.summary;
    summary.inlineVisibleTextFrameShells = inlineVisibleTextFrameShells.summary;
    summary.textOwnershipResolution = textOwnershipResolution.summary;
    summary.visibleVisualSourceResolution = visibleVisualSourceResolution.summary;
    summary.nestedInlineTextShellResolution = nestedInlineTextShellResolution.summary;
    summary.inlineCompletePngTextOwnerResolution = inlineCompletePngTextOwnerResolution.summary;
    summary.inlineVisualInventory = inlineVisualInventory.summary;
    summary.layoutOnlyInlineSlots = layoutOnlyInlineSlots.summary;
    summary.pageRootTextlessPlaneInventory = pageRootTextlessPlaneInventory.summary;
    summary.pageLocalVisibleSourceResolution = pageLocalVisibleSourceResolution.summary;
    summary.rawClippedImageVisualSourceResolution = rawClippedImageVisualSourceResolution.summary;
    summary.pageBackgroundPlaneMaterialization = pageBackgroundPlaneMaterialization.summary;
    summary.visualDepthFinalization = depthFinalization.summary;
    summary.inlineFlowContractFinalization = inlineFlowContractFinalization.summary;
    summary.sourceSetInterning = sourceSetRefs.summary;
    summary.objectPlanBuildTimings = buildTimings;

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
    // Stage 1 ObjectPlan is the ownership/layer contract. This final gate may
    // validate and summarize, but it must not rewrite `visualLayer`, `zOrder`,
    // placement, or ownership after the planner has decided them. Older code
    // recomputed background/content from source z-depth and local text overlap,
    // which reintroduced exactly the class of layer regressions the ownership
    // policy is meant to prevent.
    return {
        summary: {
            zOrderUpdates: 0,
            layerUpdates: 0
        }
    };
}

function _objectPlanIsMasterPageVisualPlane(plan) {
    if (!plan) return false;
    var passId = String(plan.passId || plan.planPassId || "");
    var candidateId = String(plan.candidateId || "");
    var isMaster = passId === "pass.master_page_graphics"
            || candidateId.indexOf(".pass.master_page_graphics.") >= 0
            || plan.isMasterGraphic === true;
    if (!isMaster) return false;
    if (plan.placement === "INLINE" || plan.coordinateSpace === "STORY_FLOW") return false;
    return true;
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
    return plan.slotRole === "shell_slot_only"
            || plan.slotRole === "inline_editable_text_shell_composite"
            || plan.slotRole === "direct_child_shell_slot"
            || plan.compositeRole === "table_carrier_sibling_decoration"
            || plan.compositeRole === "inline_editable_text_shell_composite"
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
    if (_objectPlanIsExplicitPageBackgroundPlane(plan)) {
        return "PAGE_BACKGROUND";
    }
    if (plan.visualLayer === "PAGE_BACKGROUND"
            && !_objectPlanIsExplicitPageBackgroundPlane(plan)) {
        return "CONTENT_VISUAL";
    }
    if (plan.visualLayer === "CONTAINER_BACKDROP"
            && !_objectPlanMayUseBackgroundPlane(plan, sourceById, editableTextFramesByPage, zOrder)) {
        return plan.visualAction === "PLACE_TEXT_SHELL" ? "LABEL_BACKDROP" : "CONTENT_VISUAL";
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

function _objectPlanIsExplicitPageBackgroundPlane(plan) {
    return !!(plan
            && plan.slotRole === "page_background_plane"
            && plan.compositeRole === "page_background_plane");
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
    if (visualLayer === "PAGE_BACKGROUND") {
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
    var supersededMasterTextFrameKeys =
            _objectPlanSupersededMasterTextFrameKeys(sourceItems);
    for (var i = 0; i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!src || String(src.kind || "") !== "TextFrame") continue;
        if (src.textFrameClass !== "editable") continue;
        if (src.hasText !== true) continue;
        var id = _objectPlanTextFrameIdValue(src);
        if (id === null || id === undefined) continue;
        if (supersededMasterTextFrameKeys[String(id)] === true) continue;
        var pageIndex = src.pageIndex !== undefined && src.pageIndex !== null ? src.pageIndex : -1;
        var zOrder = src.zOrder !== undefined && src.zOrder !== null ? src.zOrder : 0;
        objectPlans.push(_textFrameObjectPlan(src, id, pageIndex, zOrder,
                "pass.editable_text_frames", "editable_text_frame"));
    }
}

function _objectPlanSupersededMasterTextFrameKeys(sourceItems) {
    var out = {};
    if (!sourceItems) return out;
    for (var i = 0; i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!_objectPlanIsSuppressibleMasterTextFrame(src)) continue;
        if (_objectPlanHasPageLocalTextFrameReplacingMasterSlot(sourceItems, src)) {
            out[String(src.id)] = true;
        }
    }
    return out;
}

function _objectPlanIsSuppressibleMasterTextFrame(src) {
    if (!src || String(src.kind || "") !== "TextFrame") return false;
    if (src.isMasterInstance !== true) return false;
    if (String(src.masterSpecialType || "") === "pagenum") return false;
    if (src.pageIndex === undefined || src.pageIndex === null || src.pageIndex < 0) return false;
    return src.id !== undefined && src.id !== null && String(src.id) !== "";
}

function _objectPlanHasPageLocalTextFrameReplacingMasterSlot(sourceItems, masterSrc) {
    var masterBounds = masterSrc ? masterSrc.bounds : null;
    if (!masterBounds || masterBounds.length < 4) return false;
    var masterLayer = String(masterSrc.layerName || "");
    if (!masterLayer) return false;
    for (var i = 0; i < sourceItems.length; i++) {
        var candidate = sourceItems[i];
        if (!candidate || candidate === masterSrc) continue;
        if (String(candidate.kind || "") !== "TextFrame") continue;
        if (candidate.isMasterInstance === true) continue;
        if (candidate.hiddenLayer === true || candidate.nonprinting === true) continue;
        if (candidate.pageIndex !== masterSrc.pageIndex) continue;
        if (String(candidate.layerName || "") !== masterLayer) continue;
        if (_objectPlanSameSourceSlotBounds(masterBounds, candidate.bounds)) return true;
    }
    return false;
}

function _objectPlanSameSourceSlotBounds(a, b) {
    if (!a || !b || a.length < 4 || b.length < 4) return false;
    var maxDelta = 0.75;
    if (Math.abs(a[0] - b[0]) <= maxDelta
            && Math.abs(a[1] - b[1]) <= maxDelta
            && Math.abs(a[2] - b[2]) <= maxDelta
            && Math.abs(a[3] - b[3]) <= maxDelta) {
        return true;
    }
    var overlap = _objectPlanOverlapArea(a, b);
    var minArea = Math.min(_objectPlanArea(a), _objectPlanArea(b));
    return minArea > 0 && overlap / minArea >= 0.95;
}

function _appendVisibleTextFrameObjectPlans(objectPlans, sourceItems) {
    if (!objectPlans || !sourceItems) return;
    for (var i = 0; i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!src || String(src.kind || "") !== "TextFrame") continue;
        if (src.textFrameClass === "editable") continue;
        if (!_objectPlanSourceHasVisibleTextContent(src)) continue;
        var id = _objectPlanTextFrameIdValue(src);
        if (id === null || id === undefined) continue;
        var pageIndex = src.pageIndex !== undefined && src.pageIndex !== null ? src.pageIndex : -1;
        var zOrder = src.zOrder !== undefined && src.zOrder !== null ? src.zOrder : 0;
        objectPlans.push(_textFrameObjectPlan(src, id, pageIndex, zOrder,
                "pass.visible_text_frames", "visible_text_frame"));
    }
}

function _objectPlanTextFrameIdValue(src) {
    if (!src || src.id === null || src.id === undefined) return null;
    var numeric = Number(src.id);
    if (!isNaN(numeric)) return numeric;
    var key = String(src.id);
    if (key.indexOf("_pi") >= 0 || key.indexOf("_oc") >= 0) return key;
    return null;
}

function _objectPlanEmptyInlineTextFrameHasVisibleFramePaint(src) {
    if (!src || String(src.kind || "") !== "TextFrame") return false;
    if (src.storyAnchorPlacement !== "INLINE" && src.storyTextInlineSlot !== true) return false;
    if (src.hasVisibleFill === true || src.hasVisibleStroke === true) return true;
    return false;
}

function _objectPlanIsRepeatedEmptyInlineTextFramePlaceholder(src, sourceItems) {
    if (!_objectPlanEmptyInlineTextFrameHasVisibleFramePaint(src)) return false;
    if (!sourceItems || !src.bounds || src.bounds.length < 4) return false;
    if (src.hasText === true || Number(src.textLength || 0) !== 0) return false;
    if (src.hasPlacedVisual === true || src.hasChildren === true) return false;
    var metrics = _objectPlanSourceBoundsMetrics(src.bounds);
    if (!metrics) return false;
    var width = metrics.width;
    var height = metrics.height;
    if (width <= 0 || height <= 0) return false;

    // Empty inline frame paint can be either a real marker (for example a
    // checkbox) or a repeated placeholder run that composes a blank/underline
    // slot.  Only the repeated baseline run is layout-owned; isolated markers
    // keep their own visual slot.
    if (width > 12 || height > 12) return false;
    var pageIndex = String(src.pageIndex);
    var centerY = metrics.centerY;
    var baselineTolerance = Math.max(1.25, height * 0.55);
    var sizeTolerance = Math.max(1.0, Math.max(width, height) * 0.35);
    var minRunCount = 4;
    var count = 0;
    for (var i = 0; i < sourceItems.length; i++) {
        var other = sourceItems[i];
        if (!other || other === src) continue;
        if (!_objectPlanEmptyInlineTextFrameHasVisibleFramePaint(other)) continue;
        if (other.hasText === true || Number(other.textLength || 0) !== 0) continue;
        if (other.hasPlacedVisual === true || other.hasChildren === true) continue;
        if (String(other.pageIndex) !== pageIndex) continue;
        var om = _objectPlanSourceBoundsMetrics(other.bounds);
        if (!om) continue;
        var ow = om.width;
        var oh = om.height;
        if (ow <= 0 || oh <= 0 || ow > 12 || oh > 12) continue;
        if (Math.abs(om.centerY - centerY) > baselineTolerance) continue;
        if (Math.abs(ow - width) > sizeTolerance) continue;
        if (Math.abs(oh - height) > sizeTolerance) continue;
        count++;
        if (count + 1 >= minRunCount) return true;
    }
    return false;
}

function _objectPlanSourceBoundsMetrics(bounds) {
    if (!bounds || bounds.length < 4) return null;
    var top = Number(bounds[0]);
    var left = Number(bounds[1]);
    var bottom = Number(bounds[2]);
    var right = Number(bounds[3]);
    if (isNaN(left) || isNaN(top) || isNaN(right) || isNaN(bottom)) return null;
    return {
        left: Math.min(left, right),
        top: Math.min(top, bottom),
        right: Math.max(left, right),
        bottom: Math.max(top, bottom),
        width: Math.abs(right - left),
        height: Math.abs(bottom - top),
        centerX: (left + right) / 2.0,
        centerY: (top + bottom) / 2.0
    };
}

function _resolveRepeatedEmptyInlineTextFramePlaceholders(objectPlans) {
    var summary = {
        mutatedPlanCount: 0,
        mutatedObjectPlanIds: []
    };
    if (!objectPlans || objectPlans.length === 0) return summary;
    var candidates = [];
    for (var i = 0; i < objectPlans.length; i++) {
        var plan = objectPlans[i];
        if (!_objectPlanIsEmptyInlineTextFrameVisualPlan(plan)) continue;
        var metrics = _objectPlanSourceBoundsMetrics(plan.bounds);
        if (!metrics) continue;
        var width = metrics.width;
        var height = metrics.height;
        if (width <= 0 || height <= 0 || width > 12 || height > 12) continue;
        candidates.push({
            plan: plan,
            bounds: plan.bounds,
            width: width,
            height: height,
            centerY: metrics.centerY,
            pageIndex: String(plan.pageIndex)
        });
    }
    for (var j = 0; j < candidates.length; j++) {
        var current = candidates[j];
        var runCount = 1;
        var baselineTolerance = Math.max(1.25, current.height * 0.55);
        var sizeTolerance = Math.max(1.0, Math.max(current.width, current.height) * 0.35);
        for (var k = 0; k < candidates.length; k++) {
            if (k === j) continue;
            var other = candidates[k];
            if (other.pageIndex !== current.pageIndex) continue;
            if (Math.abs(other.centerY - current.centerY) > baselineTolerance) continue;
            if (Math.abs(other.width - current.width) > sizeTolerance) continue;
            if (Math.abs(other.height - current.height) > sizeTolerance) continue;
            runCount++;
            if (runCount >= 4) break;
        }
        if (runCount < 4) continue;
        _mutateEmptyInlineTextFrameVisualPlanToLayoutOnly(current.plan);
        summary.mutatedPlanCount++;
        summary.mutatedObjectPlanIds.push(current.plan.objectPlanId || null);
    }
    return summary;
}

function _objectPlanIsEmptyInlineTextFrameVisualPlan(plan) {
    return !!plan
            && plan.reason === "empty_inline_text_frame_visible_frame_paint"
            && plan.kind === "TextFrame"
            && plan.placement === "INLINE"
            && plan.coordinateSpace === "STORY_FLOW"
            && plan.visualAction === "PLACE_INLINE_PNG"
            && plan.materialization === "EXTRACTED_PNG_VECTOR";
}

function _mutateEmptyInlineTextFrameVisualPlanToLayoutOnly(plan) {
    if (!plan) return;
    plan.mode = "LAYOUT_ONLY";
    plan.candidatePurpose = "INLINE_LAYOUT_SLOT";
    plan.compositeRole = "repeated_empty_inline_text_frame_placeholder";
    plan.slotRole = "layout_only_inline_slot";
    plan.layoutOnlyInlineSlot = true;
    plan.layoutFootprintExpanded = false;
    plan.layoutFootprintReason = "repeated_empty_inline_text_frame_placeholder";
    plan.layoutFootprintSourceObjectIds = [];
    plan.layoutFootprintClusterBounds = null;
    plan.layoutFootprintOverlapRatio = null;
    plan.layoutFootprintFlowClearance = null;
    plan.clusterHasVisualSource = false;
    plan.visualSourceObjectIds = [];
    plan.styleSourceObjectIds = [];
    plan.exportSourceObjectIds = [];
    plan.visualSourceSetId = _sourceSetId([]);
    plan.exportSourceSetId = _sourceSetId([]);
    plan.atomicTextlessVectorContent = false;
    plan.atomicContentVisualSlot = false;
    plan.materialization = "HWPX_TEXT";
    plan.textAction = "DROP_TEXT";
    plan.visualAction = "DROP_VISUAL";
    plan.ownershipSlot = "CONTENT_VISUAL_SLOT";
    plan.policyLayer = "CONTENT";
    plan.migrationStatus = "READY_LAYOUT_ONLY_INLINE_SLOT";
    plan.reason = "repeated_empty_inline_text_frame_placeholder";
    plan.contractStatus = "READY_FOR_STAGE1_IMPORT";
}

function _appendLayoutOnlyInlineSlotObjectPlans(objectPlans, sourceItems) {
    var summary = {
        createdPlanCount: 0,
        expandedFootprintCount: 0,
        skippedPlannedCount: 0,
        skippedTextFrameCount: 0,
        skippedInvalidBoundsCount: 0,
        decisionIndexMs: 0,
        loopMs: 0,
        decisionCheckMs: 0,
        footprintMs: 0,
        addDecisionMs: 0,
        scannedSourceCount: 0,
        directInlineSourceCount: 0,
        createdObjectPlanIds: []
    };
    if (!objectPlans || !sourceItems) return { summary: summary };
    var startedAt = _objectPlanTimingNow();
    var decisionIndex = _createObjectPlanDecisionIndex(objectPlans);
    summary.decisionIndexMs = _objectPlanTimingNow() - startedAt;
    var sourceById = _objectPlanSourceInfoById(sourceItems);
    var childrenByParentId = _objectPlanSourceChildrenByParentId(sourceItems);
    var footprintCandidateIndex = _objectPlanLayoutFootprintCandidatesByPage(sourceItems);
    var loopStartedAt = _objectPlanTimingNow();
    for (var i = 0; i < sourceItems.length; i++) {
        summary.scannedSourceCount++;
        var src = sourceItems[i];
        if (!_objectPlanIsDirectInlineLayoutSource(src)) continue;
        summary.directInlineSourceCount++;
        var id = Number(src.id);
        if (isNaN(id)) continue;
        var decisionStartedAt = _objectPlanTimingNow();
        if (_objectPlanDecisionIndexHasVisualDecision(decisionIndex, id)
                || _objectPlanDecisionIndexHasTextDecision(decisionIndex, id)) {
            summary.decisionCheckMs += _objectPlanTimingNow() - decisionStartedAt;
            summary.skippedPlannedCount++;
            continue;
        }
        summary.decisionCheckMs += _objectPlanTimingNow() - decisionStartedAt;
        if (String(src.kind || "") === "TextFrame") {
            summary.skippedTextFrameCount++;
            continue;
        }
        if (!_objectPlanHasUsableInlineLayoutBounds(src)) {
            summary.skippedInvalidBoundsCount++;
            continue;
        }
        var pageIndex = src.pageIndex !== undefined && src.pageIndex !== null ? Number(src.pageIndex) : -1;
        if (isNaN(pageIndex) || pageIndex < 0) continue;
        var zOrder = src.zOrder !== undefined && src.zOrder !== null ? src.zOrder : 0;
        var footprintStartedAt = _objectPlanTimingNow();
        var footprint = _objectPlanLayoutOnlyInlineFootprint(
                src, footprintCandidateIndex, sourceById, childrenByParentId);
        summary.footprintMs += _objectPlanTimingNow() - footprintStartedAt;
        if (footprint && footprint.expanded === true) summary.expandedFootprintCount++;
        var plan = _layoutOnlyInlineSlotObjectPlan(src, id, pageIndex, zOrder, footprint);
        objectPlans.push(plan);
        var addStartedAt = _objectPlanTimingNow();
        _addObjectPlanToDecisionIndex(decisionIndex, plan);
        summary.addDecisionMs += _objectPlanTimingNow() - addStartedAt;
        summary.createdPlanCount++;
        summary.createdObjectPlanIds.push(plan.objectPlanId);
    }
    summary.loopMs = _objectPlanTimingNow() - loopStartedAt;
    return { summary: summary };
}

function _objectPlanTimingNow() {
    try { return (new Date()).getTime(); } catch (e) {}
    return 0;
}

function _objectPlanIsDirectInlineLayoutSource(src) {
    if (!src) return false;
    if (src.visible === false) return false;
    if (src.hiddenLayer === true || src.nonprinting === true) return false;
    return src.storyTextInlineSlot === true
            || String(src.storyAnchorPlacement || "").toUpperCase() === "INLINE"
            || String(src.anchoredPosition || "").toUpperCase() === "INLINE_POSITION";
}

function _objectPlanHasUsableInlineLayoutBounds(src) {
    var b = src && src.bounds ? src.bounds : null;
    if (!b || b.length < 4) return false;
    var h = Math.abs(Number(b[2]) - Number(b[0]));
    var w = Math.abs(Number(b[3]) - Number(b[1]));
    return !isNaN(h) && !isNaN(w) && h > 0 && w > 0;
}

function _objectPlanLayoutFootprintCandidatesByPage(sourceItems) {
    var byPage = {};
    for (var i = 0; sourceItems && i < sourceItems.length; i++) {
        var candidate = sourceItems[i];
        if (!_objectPlanIsLayoutFootprintClusterCandidateStatic(candidate)) continue;
        var pageIndex = candidate.pageIndex !== undefined && candidate.pageIndex !== null
                ? Number(candidate.pageIndex) : -1;
        if (isNaN(pageIndex) || pageIndex < 0) continue;
        var pageKey = String(pageIndex);
        if (!byPage[pageKey]) byPage[pageKey] = [];
        byPage[pageKey].push(candidate);
    }
    return byPage;
}

function _objectPlanIsLayoutFootprintClusterCandidateStatic(candidate) {
    if (!candidate || candidate.id === null || candidate.id === undefined) return false;
    if (candidate.visible === false || candidate.hiddenLayer === true || candidate.nonprinting === true) return false;
    if (candidate.hiddenByParent === true) return false;
    if (_objectPlanIsDirectInlineLayoutSource(candidate)) return false;
    if (String(candidate.storyAnchorPlacement || "").toUpperCase() === "INLINE") return false;
    if (candidate.isInline === true) return false;
    if (!_objectPlanHasUsableInlineLayoutBounds(candidate)) return false;
    var kind = String(candidate.kind || candidate.type || "");
    if (kind === "TextFrame") return false;
    var parentKind = String(candidate.parentKind || "").toLowerCase();
    if (candidate.parentId !== null
            && candidate.parentId !== undefined
            && String(candidate.parentId) !== ""
            && parentKind !== "spread"
            && parentKind !== "page") {
        return false;
    }
    if (kind === "Group") return true;
    if (candidate.hasPlacedVisual === true || candidate.hasVisibleFill === true || candidate.hasVisibleStroke === true) {
        return true;
    }
    if (candidate.childIds && candidate.childIds.length > 0) return true;
    return false;
}

function _objectPlanLayoutOnlyInlineFootprint(src, footprintCandidateIndex, sourceById, childrenByParentId) {
    var anchorBounds = src && src.bounds ? _objectPlanNormalizeBounds(src.bounds) : null;
    if (!anchorBounds) {
        return {
            bounds: src ? src.bounds : null,
            sourceObjectIds: [],
            expanded: false,
            reason: "anchor_bounds_unavailable"
        };
    }
    var anchorArea = _objectPlanArea(anchorBounds);
    if (anchorArea <= 0) {
        return {
            bounds: anchorBounds,
            sourceObjectIds: [],
            expanded: false,
            reason: "anchor_bounds_empty"
        };
    }
    var pageIndex = src.pageIndex !== undefined && src.pageIndex !== null ? Number(src.pageIndex) : -1;
    var pageCandidates = footprintCandidateIndex && footprintCandidateIndex[String(pageIndex)]
            ? footprintCandidateIndex[String(pageIndex)]
            : [];
    var best = null;
    var bestScore = 0;
    for (var i = 0; i < pageCandidates.length; i++) {
        var candidate = pageCandidates[i];
        if (!_objectPlanIsLayoutFootprintClusterCandidate(src, candidate, sourceById)) continue;
        var cb = _objectPlanNormalizeBounds(candidate.bounds);
        if (!cb) continue;
        var overlap = _objectPlanOverlapArea(anchorBounds, cb);
        if (overlap <= 0) continue;
        var candidateArea = _objectPlanArea(cb);
        if (candidateArea <= 0) continue;
        var overlapRatio = overlap / Math.min(anchorArea, candidateArea);
        if (overlapRatio < 0.55) continue;
        var areaRatio = candidateArea / anchorArea;
        if (areaRatio > 8.0) continue;
        var kindScore = String(candidate.kind || candidate.type || "") === "Group" ? 1000 : 0;
        var score = kindScore + (overlapRatio * 100) - Math.abs(areaRatio - 1.5);
        if (!best || score > bestScore) {
            best = candidate;
            bestScore = score;
        }
    }
    if (!best) {
        return {
            bounds: anchorBounds,
            sourceObjectIds: [],
            expanded: false,
            reason: "no_overlapping_page_cluster"
        };
    }
    var bestBounds = _objectPlanNormalizeBounds(best.bounds);
    var union = _objectPlanUnionTwoBounds(anchorBounds, bestBounds);
    var flowClearance = _objectPlanLayoutFootprintFlowClearance(
            best, sourceById, childrenByParentId);
    if (union && flowClearance > 0) union[2] += flowClearance;
    var bestId = _objectPlanNumericSourceId(best);
    return {
        bounds: union || anchorBounds,
        sourceObjectIds: bestId === null ? [] : [bestId],
        expanded: !!union && _objectPlanBoundsDifferent(union, anchorBounds, 0.01),
        reason: "overlapping_page_level_visual_cluster",
        overlapRatio: _objectPlanOverlapArea(anchorBounds, bestBounds) / Math.min(anchorArea, _objectPlanArea(bestBounds)),
        clusterBounds: bestBounds,
        flowClearance: flowClearance
    };
}

function _objectPlanIsLayoutFootprintClusterCandidate(anchor, candidate, sourceById) {
    if (!anchor || !candidate || candidate.id === null || candidate.id === undefined) return false;
    if (String(anchor.id) === String(candidate.id)) return false;
    if (candidate.visible === false || candidate.hiddenLayer === true || candidate.nonprinting === true) return false;
    if (candidate.hiddenByParent === true) return false;
    if (_objectPlanIsDirectInlineLayoutSource(candidate)) return false;
    if (String(candidate.storyAnchorPlacement || "").toUpperCase() === "INLINE") return false;
    if (candidate.isInline === true) return false;
    if (!_objectPlanHasUsableInlineLayoutBounds(candidate)) return false;
    var kind = String(candidate.kind || candidate.type || "");
    if (kind === "TextFrame") return false;
    var parentKind = String(candidate.parentKind || "").toLowerCase();
    if (candidate.parentId !== null
            && candidate.parentId !== undefined
            && String(candidate.parentId) !== ""
            && parentKind !== "spread"
            && parentKind !== "page") {
        return false;
    }
    if (kind === "Group") return true;
    if (candidate.hasPlacedVisual === true || candidate.hasVisibleFill === true || candidate.hasVisibleStroke === true) {
        return true;
    }
    if (candidate.childIds && candidate.childIds.length > 0) return true;
    return false;
}

function _objectPlanNormalizeBounds(bounds) {
    if (!bounds || bounds.length < 4) return null;
    var top = Number(bounds[0]);
    var left = Number(bounds[1]);
    var bottom = Number(bounds[2]);
    var right = Number(bounds[3]);
    if (isNaN(top) || isNaN(left) || isNaN(bottom) || isNaN(right)) return null;
    return [
        Math.min(top, bottom),
        Math.min(left, right),
        Math.max(top, bottom),
        Math.max(left, right)
    ];
}

function _objectPlanUnionTwoBounds(a, b) {
    a = _objectPlanNormalizeBounds(a);
    b = _objectPlanNormalizeBounds(b);
    if (!a) return b;
    if (!b) return a;
    return [
        Math.min(a[0], b[0]),
        Math.min(a[1], b[1]),
        Math.max(a[2], b[2]),
        Math.max(a[3], b[3])
    ];
}

function _objectPlanTranslateBounds(bounds, deltaTop, deltaLeft) {
    bounds = _objectPlanNormalizeBounds(bounds);
    if (!bounds) return null;
    var dt = Number(deltaTop || 0);
    var dl = Number(deltaLeft || 0);
    return [
        bounds[0] + dt,
        bounds[1] + dl,
        bounds[2] + dt,
        bounds[3] + dl
    ];
}

function _objectPlanBoundsDifferent(a, b, eps) {
    a = _objectPlanNormalizeBounds(a);
    b = _objectPlanNormalizeBounds(b);
    if (!a || !b) return false;
    eps = eps || 0;
    for (var i = 0; i < 4; i++) {
        if (Math.abs(a[i] - b[i]) > eps) return true;
    }
    return false;
}

function _objectPlanNumericSourceId(src) {
    if (!src || src.id === null || src.id === undefined) return null;
    var n = Number(src.id);
    return isNaN(n) ? null : n;
}

function _objectPlanSourceChildrenByParentId(sourceItems) {
    var out = {};
    for (var i = 0; sourceItems && i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!src || src.parentId === null || src.parentId === undefined || String(src.parentId) === "") continue;
        var key = String(src.parentId);
        if (!out[key]) out[key] = [];
        out[key].push(src);
    }
    return out;
}

function _objectPlanLayoutFootprintFlowClearance(cluster, sourceById, childrenByParentId) {
    if (!_objectPlanClusterContainsKind(cluster, sourceById, childrenByParentId, "TextFrame", 0)) return 0;
    // HWPX TOP_AND_BOTTOM inline carrier reserves the object box, but the
    // original story composition also has a line-leading gap below the
    // inline placeholder paragraph.  Use one small text-leading clearance
    // for page-level clusters that include editable text labels/captions.
    return 8.0;
}

function _objectPlanClusterContainsKind(src, sourceById, childrenByParentId, kind, depth) {
    if (!src || depth > 8) return false;
    if (String(src.kind || src.type || "") === kind) return true;
    var childIds = src.childIds || [];
    for (var i = 0; i < childIds.length; i++) {
        var child = sourceById ? sourceById[String(childIds[i])] : null;
        if (_objectPlanClusterContainsKind(child, sourceById, childrenByParentId, kind, depth + 1)) return true;
    }
    var parentId = src.id === null || src.id === undefined ? null : String(src.id);
    var children = parentId === null || !childrenByParentId ? null : childrenByParentId[parentId];
    for (var j = 0; children && j < children.length; j++) {
        if (_objectPlanClusterContainsKind(children[j], sourceById, childrenByParentId, kind, depth + 1)) return true;
    }
    return false;
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
        if (_objectPlanEmptyInlineTextFrameHasVisibleFramePaint(src)) {
            if (_objectPlanIsRepeatedEmptyInlineTextFramePlaceholder(src, sourceItems)) {
                var placeholderPlan = _layoutOnlyInlineSlotObjectPlan(src, id, pageIndex, zOrder, {
                    bounds: src.bounds || null,
                    sourceObjectIds: [],
                    expanded: false,
                    reason: "repeated_empty_inline_text_frame_placeholder"
                });
                placeholderPlan.compositeRole = "repeated_empty_inline_text_frame_placeholder";
                placeholderPlan.layoutFootprintReason = "repeated_empty_inline_text_frame_placeholder";
                placeholderPlan.reason = "repeated_empty_inline_text_frame_placeholder";
                objectPlans.push(placeholderPlan);
                _addObjectPlanToDecisionIndex(decisionIndex, placeholderPlan);
                continue;
            }
            var inlineVisualPlan = _emptyInlineTextFrameVisualObjectPlan(src, id, pageIndex, zOrder);
            objectPlans.push(inlineVisualPlan);
            _addObjectPlanToDecisionIndex(decisionIndex, inlineVisualPlan);
            continue;
        }
        if (_objectPlanDecisionIndexHasTextDecision(decisionIndex, id)) continue;
        var textPlan = _textFrameObjectPlan(src, id, pageIndex, zOrder,
                "pass.empty_editable_text_frames", "editable_text_frame");
        objectPlans.push(textPlan);
        _addObjectPlanToDecisionIndex(decisionIndex, textPlan);
    }
}

function _appendInlineVisibleTextFrameShellObjectPlans(objectPlans, sourceItems) {
    var summary = {
        createdPlanCount: 0,
        skippedExistingVisualDecisionCount: 0,
        createdObjectPlanIds: []
    };
    if (!objectPlans || !sourceItems) return { summary: summary };
    var decisionIndex = _createObjectPlanDecisionIndex(objectPlans);
    for (var i = 0; i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!_objectPlanSourceIsInlineVisibleTextFrameShell(src)) continue;
        var id = _objectPlanTextFrameIdValue(src);
        if (id === null || id === undefined || typeof id !== "number" || isNaN(id)) continue;
        if (_objectPlanDecisionIndexHasVisualDecision(decisionIndex, id)) {
            summary.skippedExistingVisualDecisionCount++;
            continue;
        }
        var pageIndex = src.pageIndex !== undefined && src.pageIndex !== null ? src.pageIndex : -1;
        var zOrder = src.zOrder !== undefined && src.zOrder !== null ? src.zOrder : 0;
        var plan = _inlineVisibleTextFrameShellObjectPlan(src, id, pageIndex, zOrder);
        objectPlans.push(plan);
        _addObjectPlanToDecisionIndex(decisionIndex, plan);
        summary.createdPlanCount++;
        summary.createdObjectPlanIds.push(plan.objectPlanId);
    }
    return { summary: summary };
}

function _appendSourceBundleTextRangeShellObjectPlans(objectPlans, sourceItems, sourceById, childrenByParentId) {
    var summary = {
        observedBundleCount: 0,
        createdPlanCount: 0,
        skippedCount: 0,
        createdObjectPlanIds: []
    };
    if (!objectPlans || !sourceItems || !sourceById || !childrenByParentId) return { summary: summary };
    var decisionIndex = _createObjectPlanDecisionIndex(objectPlans);
    var usedShellIds = {};
    var usedRanges = {};
    for (var i = 0; i < sourceItems.length; i++) {
        var group = sourceItems[i];
        if (!group || String(group.kind || group.type || "") !== "Group") continue;
        var bundle = _sourceBundleTextRangeShellBundle(group, sourceById, childrenByParentId);
        if (!bundle) {
            summary.skippedCount++;
            continue;
        }
        summary.observedBundleCount++;
        var pairCount = Math.min(bundle.shells.length, bundle.ranges.length);
        for (var pi = 0; pi < pairCount; pi++) {
            var shell = bundle.shells[pi];
            var range = bundle.ranges[pi];
            var shellId = _objectPlanNumericSourceId(shell);
            var textFrameId = _objectPlanNumericSourceId(range ? range.textFrame : null);
            var rangeRef = range ? range.range : null;
            var rangeKey = _sourceBundleTextRangeShellRangeKey(rangeRef, textFrameId);
            if (shellId === null || textFrameId === null || !rangeRef
                    || usedShellIds[String(shellId)] === true
                    || usedRanges[rangeKey] === true
                    || _objectPlanDecisionIndexHasVisualDecision(decisionIndex, shellId)) {
                summary.skippedCount++;
                continue;
            }
            var plan = _sourceBundleTextRangeShellObjectPlan(shell, textFrameId, rangeRef);
            if (!plan) {
                summary.skippedCount++;
                continue;
            }
            objectPlans.push(plan);
            _addObjectPlanToDecisionIndex(decisionIndex, plan);
            usedShellIds[String(shellId)] = true;
            usedRanges[rangeKey] = true;
            summary.createdPlanCount++;
            summary.createdObjectPlanIds.push(plan.objectPlanId);
        }
        summary.skippedCount += Math.abs(bundle.shells.length - bundle.ranges.length);
    }
    return { summary: summary };
}

function _sourceBundleTextRangeShellBundle(group, sourceById, childrenByParentId) {
    if (!group || !sourceById || !childrenByParentId) return null;
    var children = childrenByParentId[String(group.id)] || [];
    if (!children || children.length < 2) return null;
    var textFrames = [];
    var shells = [];
    var otherVisibleChildCount = 0;
    for (var i = 0; i < children.length; i++) {
        var child = children[i];
        if (!child) continue;
        if (_sourceBundleTextRangeShellEditableTextFrame(child)) {
            textFrames.push(child);
            continue;
        }
        if (_sourceBundleTextRangeShellVisibleShell(child)) {
            shells.push(child);
            continue;
        }
        if (child.visible !== false && child.hiddenLayer !== true && child.nonprinting !== true) {
            otherVisibleChildCount++;
        }
    }
    if (textFrames.length === 0 || shells.length === 0 || otherVisibleChildCount > 0) return null;
    var ranges = [];
    for (var ti = 0; ti < textFrames.length; ti++) {
        var tf = textFrames[ti];
        var tfId = _objectPlanNumericSourceId(tf);
        if (tfId === null) continue;
        var tfRanges = tf.leadingStyledTextRanges || [];
        for (var ri = 0; ri < tfRanges.length; ri++) {
            var range = _sourceBundleTextRangeShellRange(tfRanges[ri], tf, tfId);
            if (range) ranges.push({ textFrame: tf, range: range });
        }
    }
    if (ranges.length === 0) return null;
    shells.sort(function(a, b) {
        var ay = _sourceBundleBoundsCenter(a && a.bounds, 0);
        var by = _sourceBundleBoundsCenter(b && b.bounds, 0);
        if (ay !== by) return ay - by;
        var ax = _sourceBundleBoundsCenter(a && a.bounds, 1);
        var bx = _sourceBundleBoundsCenter(b && b.bounds, 1);
        if (ax !== bx) return ax - bx;
        return Number(a.zOrder || 0) - Number(b.zOrder || 0);
    });
    ranges.sort(function(a, b) {
        var ar = a.range;
        var br = b.range;
        if (ar.paragraphIndex !== br.paragraphIndex) return ar.paragraphIndex - br.paragraphIndex;
        if (ar.runIndex !== br.runIndex) return ar.runIndex - br.runIndex;
        return Number(a.textFrame.zOrder || 0) - Number(b.textFrame.zOrder || 0);
    });
    return { shells: shells, ranges: ranges };
}

function _sourceBundleTextRangeShellEditableTextFrame(src) {
    return !!(src
            && String(src.kind || src.type || "") === "TextFrame"
            && src.textFrameClass === "editable"
            && src.hasText === true
            && src.visible !== false
            && src.hiddenLayer !== true
            && src.nonprinting !== true
            && src.leadingStyledTextRanges
            && src.leadingStyledTextRanges.length > 0);
}

function _sourceBundleTextRangeShellVisibleShell(src) {
    if (!src || src.visible === false || src.hiddenLayer === true || src.nonprinting === true) return false;
    var kind = String(src.kind || src.type || "");
    if (kind !== "Polygon" && kind !== "Rectangle" && kind !== "Oval") return false;
    if (!src.bounds || src.bounds.length < 4) return false;
    return src.hasVisibleFill === true || src.hasVisibleStroke === true;
}

function _sourceBundleTextRangeShellRange(raw, tf, textFrameId) {
    if (!raw || !raw.text) return null;
    return {
        textFrameId: textFrameId,
        storyId: tf && tf.storyId !== undefined && tf.storyId !== null ? String(tf.storyId) : null,
        paragraphIndex: Number(raw.paragraphIndex || 0),
        runIndex: Number(raw.runIndex || 0),
        start: Number(raw.start || 0),
        end: Number(raw.end || 0),
        paragraphStart: Number(raw.paragraphStart || 0),
        paragraphEnd: Number(raw.paragraphEnd || String(raw.text || "").length),
        text: String(raw.text || "")
    };
}

function _sourceBundleBoundsCenter(bounds, axis) {
    if (!bounds || bounds.length < 4) return 0;
    return axis === 0 ? (Number(bounds[0]) + Number(bounds[2])) / 2
            : (Number(bounds[1]) + Number(bounds[3])) / 2;
}

function _sourceBundleTextRangeShellRangeKey(range, textFrameId) {
    if (!range) return "";
    return String(textFrameId) + ":" + String(range.paragraphIndex) + ":"
            + String(range.runIndex) + ":" + String(range.start) + ":" + String(range.end);
}

function _sourceBundleTextRangeShellObjectPlan(shell, textFrameId, range) {
    var shellId = _objectPlanNumericSourceId(shell);
    if (shellId === null || textFrameId === null || !range) return null;
    var sourceIds = _internSourceSetIds([shellId, textFrameId]);
    var shellIds = _internSourceSetIds([shellId]);
    var textIds = _internSourceSetIds([textFrameId]);
    var pageIndex = shell.pageIndex !== undefined && shell.pageIndex !== null ? shell.pageIndex : -1;
    var sourceSetId = _sourceSetId(sourceIds);
    var shellSetId = _sourceSetId(shellIds);
    return {
        objectPlanId: "objectPlan.source_bundle_text_range_shell." + String(shellId),
        bundleId: "sourceBundle.textRangeShell." + String(shellId),
        candidateId: _candidateId("pass.inline_objects", shellId, pageIndex)
                + ".source_bundle_text_range_shell",
        passId: "pass.inline_objects",
        pageIndex: pageIndex,
        kind: shell.kind || "Polygon",
        unit: "INLINE_OBJECT",
        mode: "TEXTLESS_CANDIDATE",
        candidatePurpose: "INLINE_CANDIDATE",
        compositeRole: "source_bundle_text_range_shell",
        slotRole: "source_bundle_text_range_shell_slot",
        layoutOnlyInlineSlot: false,
        sourceInlineFlow: true,
        inlineCompositeLayoutDescendant: false,
        inlineAnchorSourceObjectId: textFrameId,
        inlineSourceTreeClosed: false,
        inlineFlowSourceObjectIds: textIds,
        connectorDecorationVisual: false,
        primarySourceObjectId: shellId,
        sourceSetId: sourceSetId,
        sourceRootSetId: sourceSetId,
        clusterSourceSetId: sourceSetId,
        visualSourceSetId: shellSetId,
        styleSourceSetId: shellSetId,
        exportSourceSetId: shellSetId,
        hiddenSourceSetId: _sourceSetId([]),
        ownedByNativeShellSourceObjectIds: [],
        sourceObjectIds: sourceIds,
        sourceRootObjectIds: sourceIds,
        clusterSourceObjectIds: sourceIds,
        clusterKindCounts: { TextFrame: 1, Polygon: shell.kind === "Polygon" ? 1 : 0 },
        omittedClusterSourceObjectIds: [],
        omittedClusterKindCounts: {},
        clusterHasEditableText: true,
        clusterHasTextFrame: true,
        clusterHasPlacedContent: false,
        clusterHasVisualSource: true,
        visualSourceObjectIds: shellIds,
        styleSourceObjectIds: shellIds,
        ownedTextFrameIds: [],
        exportSourceObjectIds: shellIds,
        exportTargetObjectId: shellId,
        atomicExportTargetObjectId: shellId,
        atomicExportTargetObjectIds: shellIds,
        atomicTextlessVectorContent: true,
        atomicContentVisualSlot: false,
        hiddenVisualSourceObjectIds: [],
        excludedInlineSourceObjectIds: [],
        ownedTextRanges: [range],
        materialization: "EXTRACTED_PNG_VECTOR",
        textAction: "OWNED_BY_HWPX_TEXT",
        visualAction: "PLACE_TEXT_SHELL",
        placement: "INLINE",
        coordinateSpace: "STORY_FLOW",
        visualLayer: "LABEL_BACKDROP",
        zOrder: shell.zOrder !== undefined && shell.zOrder !== null ? shell.zOrder : 0,
        reason: "source_bundle_text_range_shell",
        bounds: shell.bounds || null,
        renderSourceBounds: shell.bounds || null,
        cropSourceBounds: shell.bounds || null,
        ownershipSlot: "SHELL_SLOT",
        policyLayer: "DECORATION",
        clusterRelation: "SOURCE_BUNDLE_TEXT_RANGE_SHELL",
        migrationStatus: "READY_EXACT_CLUSTER",
        migrationBlocker: "NONE",
        migrationBlockerDetail: {},
        contractStatus: "READY_FOR_STAGE1_IMPORT",
        executable: true,
        required: true
    };
}

function _objectPlanDecisionIndexHasSourceShellDecision(objectPlans, sourceId) {
    var key = String(sourceId);
    for (var i = 0; objectPlans && i < objectPlans.length; i++) {
        var plan = objectPlans[i];
        if (!plan || plan.ownershipSlot !== "SHELL_SLOT") continue;
        if (plan.primarySourceObjectId !== undefined
                && plan.primarySourceObjectId !== null
                && String(plan.primarySourceObjectId) === key) {
            return true;
        }
        var sourceIds = plan.sourceObjectIds || [];
        for (var s = 0; s < sourceIds.length; s++) {
            if (String(sourceIds[s]) === key) return true;
        }
    }
    return false;
}

function _objectPlanSourceIsInlineVisibleTextFrameShell(src) {
    var kind = String(src && (src.kind || src.type || src.itemType) || "");
    if (!src || kind !== "TextFrame") return false;
    if (src.textFrameClass !== "editable") return false;
    if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) return false;
    if (src.storyAnchorPlacement !== "INLINE"
            && src.storyTextInlineSlot !== true
            && src.isInline !== true
            && String(src.anchoredPosition || "").toUpperCase() !== "INLINE_POSITION"
            && String(src.anchoredPosition || "").toUpperCase() !== "INLINEPOSITION") {
        return false;
    }
    if (src.hasTablesInStory === true || src.storyHasVisibleTableCellText === true) return false;
    return _objectPlanSourceHasVisibleFramePaint(src);
}

function _objectPlanSourceHasVisibleFramePaint(src) {
    if (!src) return false;
    if (src.hasVisibleFill === true || src.hasVisibleStroke === true) return true;
    var fillName = String(src.fillColorName || src.fillColor || "");
    if (fillName && fillName !== "None" && fillName !== "[None]") return true;
    var strokeName = String(src.strokeColorName || src.strokeColor || "");
    var strokeWeight = Number(src.strokeWeight || 0);
    return strokeName && strokeName !== "None" && strokeName !== "[None]" && strokeWeight > 0;
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

function _syncObjectPlanDiagnosticsToExecutionCandidates(objectPlanDiagnostics, executionCandidates, options) {
    options = options || {};
    if (!objectPlanDiagnostics || !objectPlanDiagnostics.objectPlans) {
        return {
            diagnostics: objectPlanDiagnostics,
            summary: {
                originalPlanCount: 0,
                retainedPlanCount: 0,
                prunedPlanCount: 0,
                retainedVisiblePlanCount: 0,
                prunedVisiblePlanCount: 0
            },
            pruned: []
        };
    }

    var activeObjectPlanIds = {};
    var activeCandidateIds = {};
    var rows = executionCandidates || [];
    for (var i = 0; i < rows.length; i++) {
        var candidate = rows[i];
        if (!candidate) continue;
        if (candidate.objectPlanId) activeObjectPlanIds[String(candidate.objectPlanId)] = true;
        if (candidate.candidateId) activeCandidateIds[String(candidate.candidateId)] = true;
    }

    var plans = objectPlanDiagnostics.objectPlans || [];
    var kept = [];
    var pruned = [];
    var retainedVisiblePlanCount = 0;
    var prunedVisiblePlanCount = 0;
    for (var p = 0; p < plans.length; p++) {
        var plan = plans[p];
        if (!plan) continue;
        var visible = _objectPlanHasVisibleVisual(plan);
        var keep = _shouldKeepObjectPlanAfterExecutionCandidateSync(
                plan, activeObjectPlanIds, activeCandidateIds);
        if (keep) {
            kept.push(plan);
            if (visible) retainedVisiblePlanCount++;
        } else {
            pruned.push({
                objectPlanId: plan.objectPlanId || null,
                candidateId: plan.candidateId || null,
                passId: plan.passId || null,
                pageIndex: plan.pageIndex,
                ownershipSlot: plan.ownershipSlot || null,
                materialization: plan.materialization || null,
                visualAction: plan.visualAction || null,
                reason: options.reason || "execution_candidate_suppressed"
            });
            if (visible) prunedVisiblePlanCount++;
        }
    }

    var previousSummary = objectPlanDiagnostics.summary || {};
    objectPlanDiagnostics.objectPlans = kept;
    var validation = _validateObjectPlanDiagnostics(kept, null);
    var sourceSetRefs = _attachObjectPlanSourceSetRefs(kept);
    var summary = _summarizeObjectPlans(kept, validation);
    for (var summaryKey in previousSummary) {
        if (!previousSummary.hasOwnProperty(summaryKey)) continue;
        if (summary[summaryKey] !== undefined) continue;
        summary[summaryKey] = previousSummary[summaryKey];
    }
    summary.sourceSetInterning = sourceSetRefs.summary;
    summary.executionCandidateSync = {
        originalPlanCount: plans.length,
        retainedPlanCount: kept.length,
        prunedPlanCount: pruned.length,
        retainedVisiblePlanCount: retainedVisiblePlanCount,
        prunedVisiblePlanCount: prunedVisiblePlanCount,
        activeExecutionCandidateCount: rows.length,
        reason: options.reason || "execution_candidate_suppressed"
    };
    objectPlanDiagnostics.summary = summary;
    objectPlanDiagnostics.validation = validation;
    objectPlanDiagnostics.sourceSetRefs = sourceSetRefs;

    return {
        diagnostics: objectPlanDiagnostics,
        summary: summary.executionCandidateSync,
        pruned: pruned
    };
}

function _shouldKeepObjectPlanAfterExecutionCandidateSync(plan, activeObjectPlanIds, activeCandidateIds) {
    if (!plan) return false;
    if (!_objectPlanHasVisibleVisual(plan)) return true;
    if (plan.visualAction === "PLACE_TABLE_STYLE"
            || plan.materialization === "HWPX_TABLE_STYLE"
            || plan.ownershipSlot === "TABLE_STYLE_SLOT") return true;
    if (_objectPlanIsInlineVisibleTextFrameShellVisualOwner(plan)) return true;
    if (plan.objectPlanId && activeObjectPlanIds[String(plan.objectPlanId)]) return true;
    if (plan.candidateId && activeCandidateIds[String(plan.candidateId)]) return true;
    if (!plan.objectPlanId && !plan.candidateId) return true;
    return false;
}

function _slimObjectPlanDiagnosticsForWrite(diagnostics, options) {
    if (!diagnostics) return diagnostics;
    if (!options) options = {};
    var validation = diagnostics.validation || {};
    var slimPlans = [];
    var plans = diagnostics.objectPlans || [];
    for (var i = 0; i < plans.length; i++) {
        if (options.importReadyOnly === true && !_objectPlanShouldWriteForStage1Import(plans[i])) {
            continue;
        }
        slimPlans.push(_slimObjectPlanForWrite(plans[i]));
    }
    return {
        schemaVersion: diagnostics.schemaVersion || 1,
        policy: diagnostics.policy || "POLICY-source-ownership",
        mode: "object-plan-diagnostics-slim",
        summary: _objectPlanWriteSummary(diagnostics.summary || {}, plans.length, slimPlans.length,
                options.importReadyOnly === true),
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

function _objectPlanWriteSummary(summary, fullPlanCount, writtenPlanCount, importReadyOnly) {
    var out = {};
    for (var key in summary) {
        if (summary.hasOwnProperty(key)) out[key] = summary[key];
    }
    out.fullObjectPlanCount = fullPlanCount || 0;
    out.writtenObjectPlanCount = writtenPlanCount || 0;
    out.importReadyOnlyWrite = importReadyOnly === true;
    return out;
}

function _objectPlanShouldWriteForStage1Import(plan) {
    if (!plan) return false;
    if (plan.compositeRole === "inline_visual_inventory") return false;
    if (plan.contractStatus === "READY_FOR_STAGE1_IMPORT") return true;
    if (_objectPlanIsInlineVisibleTextFrameShellVisualOwner(plan)) return true;
    if (plan.layoutOnlyInlineSlot === true) return true;
    if (plan.compositeRole === "clip_parent_source_set") return true;
    return false;
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
        "tableCellInlineAnchorSource",
        "pagePositionedAnchoredSource",
        "inlineCompositeLayoutDescendant",
        "inlineAnchorSourceObjectId",
        "inlineSourceTreeClosed",
        "inlineFlowSourceObjectIds",
        "connectorDecorationVisual",
        "primarySourceObjectId",
        "primarySourceObjectIdKey",
        "ownedByNativeShellSourceObjectIds",
        "ownedByNativeShellSourceSetId",
        "sourceObjectIds",
        "sourceObjectIdKeys",
        "sourceSetId",
        "sourceRootObjectIds",
        "sourceRootObjectIdKeys",
        "sourceRootSetId",
        "clusterSourceSetId",
        "clusterSourceObjectIdKeys",
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
        "ownedTextFrameIdKeys",
        "ownedTextFrameSetId",
        "ownedTextRanges",
        "exportSourceObjectIds",
        "exportSourceSetId",
        "exportTargetObjectId",
        "atomicExportTargetObjectId",
        "atomicExportTargetObjectIds",
        "atomicTextlessVectorContent",
        "atomicContentVisualSlot",
        "hiddenVisualSourceObjectIds",
        "hiddenTextFrameIds",
        "excludedInlineSourceObjectIds",
        "hiddenVisualSourceSetId",
        "hiddenTextFrameSetId",
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
        "absorbedByObjectPlanId",
        "absorbedByMaterialization",
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
    if (key === "tableCellInlineAnchorSource") return true;
    if (key === "pagePositionedAnchoredSource") return true;
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

function _promoteInlineVisibleTextFrameShellObjectPlans(objectPlans, sourceById) {
    var summary = {
        candidatePlanCount: 0,
        mutatedPlanCount: 0,
        skippedPlanCount: 0
    };
    if (!objectPlans || !sourceById) return { summary: summary };

    for (var i = 0; i < objectPlans.length; i++) {
        var plan = objectPlans[i];
        if (!_objectPlanShouldPromoteInlineVisibleTextFrameShell(plan, sourceById)) {
            continue;
        }
        summary.candidatePlanCount++;
        var sourceId = Number(plan.primarySourceObjectId);
        if (isNaN(sourceId)) {
            summary.skippedPlanCount++;
            continue;
        }
        var src = sourceById[String(sourceId)];
        if (!src) {
            summary.skippedPlanCount++;
            continue;
        }
        _promoteObjectPlanInlineVisibleTextFrameShell(plan, src, sourceId);
        summary.mutatedPlanCount++;
    }

    return { summary: summary };
}

function _objectPlanShouldPromoteInlineVisibleTextFrameShell(plan, sourceById) {
    if (!plan || !sourceById) return false;
    if (plan.ownershipSlot !== "SHELL_SLOT") return false;
    if (plan.placement !== "INLINE" || plan.coordinateSpace !== "STORY_FLOW") return false;
    if (plan.visualAction !== "DROP_VISUAL") return false;
    if (plan.textAction !== "DROP_TEXT") return false;
    if (plan.materialization !== "HWPX_TEXT") return false;
    var planKind = String(plan.kind || "");
    if (planKind !== "TextFrame" && planKind !== "TEXT_FRAME") return false;
    if (plan.primarySourceObjectId === null || plan.primarySourceObjectId === undefined) return false;
    var sourceId = Number(plan.primarySourceObjectId);
    if (isNaN(sourceId)) return false;
    var src = sourceById[String(sourceId)];
    var sourceKind = src ? String(src.kind || src.type || "") : "";
    if (!src || sourceKind !== "TextFrame") return false;
    if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) return false;
    if (src.storyAnchorPlacement !== "INLINE" && src.storyTextInlineSlot !== true) return false;
    if (src.hasVisibleFill !== true && src.hasVisibleStroke !== true) return false;
    if (src.hasTablesInStory === true || src.storyHasVisibleTableCellText === true) return false;
    if (src.textFrameClass !== "editable") return false;
    return true;
}

function _promoteObjectPlanInlineVisibleTextFrameShell(plan, src, sourceId) {
    var sourceIds = _internSourceSetIds([sourceId]);
    var sourceSetId = _sourceSetId(sourceIds);
    var pageIndex = plan.pageIndex !== undefined && plan.pageIndex !== null
            ? plan.pageIndex
            : (src.pageIndex !== undefined && src.pageIndex !== null ? src.pageIndex : -1);
    plan.candidateId = _candidateId("pass.inline_objects", sourceId, pageIndex)
            + ".inline_textframe_shell";
    plan.passId = "pass.inline_objects";
    plan.mode = "TEXTLESS_CANDIDATE";
    plan.unit = "INLINE_OBJECT";
    plan.candidatePurpose = "INLINE_CANDIDATE";
    plan.compositeRole = "inline_visible_text_frame_shell";
    plan.slotRole = "inline_text_frame_shell_slot";
    plan.sourceInlineFlow = true;
    plan.inlineCompositeLayoutDescendant = false;
    plan.connectorDecorationVisual = false;
    plan.sourceObjectIds = sourceIds;
    plan.sourceRootObjectIds = sourceIds;
    plan.clusterSourceObjectIds = sourceIds;
    plan.clusterKindCounts = { TextFrame: 1 };
    plan.omittedClusterSourceObjectIds = [];
    plan.omittedClusterKindCounts = {};
    plan.clusterHasEditableText = src.textFrameClass === "editable";
    plan.clusterHasTextFrame = true;
    plan.clusterHasPlacedContent = false;
    plan.clusterHasVisualSource = true;
    plan.visualSourceObjectIds = sourceIds;
    plan.styleSourceObjectIds = sourceIds;
    plan.exportSourceObjectIds = sourceIds;
    plan.exportTargetObjectId = sourceId;
    plan.atomicExportTargetObjectId = sourceId;
    plan.atomicExportTargetObjectIds = sourceIds;
    plan.hiddenVisualSourceObjectIds = [];
    plan.excludedInlineSourceObjectIds = [];
    plan.sourceSetId = sourceSetId;
    plan.sourceRootSetId = sourceSetId;
    plan.clusterSourceSetId = sourceSetId;
    plan.visualSourceSetId = sourceSetId;
    plan.exportSourceSetId = sourceSetId;
    plan.hiddenSourceSetId = _sourceSetId([]);
    plan.materialization = "EXTRACTED_PNG_VECTOR";
    plan.textAction = "OWNED_BY_HWPX_TEXT";
    plan.ownedTextFrameIds = [sourceId];
    plan.visualAction = "PLACE_TEXT_SHELL";
    plan.ownershipSlot = "SHELL_SLOT";
    plan.visualLayer = "LABEL_BACKDROP";
    plan.policyLayer = "DECORATION";
    plan.clusterRelation = "EXACT_SOURCE_CLUSTER";
    plan.migrationStatus = "READY_EXACT_CLUSTER";
    plan.migrationBlocker = "NONE";
    plan.migrationBlockerDetail = {};
    plan.contractStatus = "READY_FOR_STAGE1_IMPORT";
    plan.executable = true;
    plan.required = true;
    plan.bounds = src.bounds || plan.bounds || null;
    plan.renderSourceBounds = src.bounds || plan.renderSourceBounds || null;
    plan.cropSourceBounds = src.bounds || plan.cropSourceBounds || null;
    plan.reason = String(plan.reason || "editable_text_frame")
            + ":inline_visible_text_frame_shell";
}

function _markObjectPlanOwnedByPngCleanup(plan) {
    if (!plan) return;
    // Cleanup plans suppress duplicate text/shell execution after a COMPLETE_PNG
    // owner has been chosen. They must not keep the original candidate identity,
    // or the execution bridge will overwrite the visible owner plan by
    // candidateId during execution-candidate materialization.
    plan.candidateId = null;
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
    var sourceById = _objectPlanSourceInfoById(sourceItems);
    for (var i = 0; i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!src || String(src.kind || "") !== "TextFrame") continue;
        if (src.hasTablesInStory !== true) continue;
        if (src.storyHasVisibleTableCellText !== true) continue;
        if (src.markerOnlyContents === false) continue;
        if (src.visible === false) continue;
        if (src.hiddenLayer === true || src.nonprinting === true) continue;
        var id = Number(src.id);
        if (isNaN(id)) continue;
        if (_objectPlanDecisionIndexHasTableStyleDecision(decisionIndex, id)) continue;
        var pageIndex = src.pageIndex !== undefined && src.pageIndex !== null ? src.pageIndex : -1;
        var zOrder = src.zOrder !== undefined && src.zOrder !== null ? src.zOrder : 0;
        var anchoredNestedTable =
                _isTableOnlyTextFrameNestedInInlineShell(src, sourceById);
        var tablePlan = _tableOnlyTextFrameObjectPlan(
                src,
                id,
                sourceById,
                pageIndex,
                zOrder,
                anchoredNestedTable ? "DROP_TEXT" : null,
                anchoredNestedTable ? "owned_by_anchored_table_plan" : null);
        objectPlans.push(tablePlan);
        _addObjectPlanToDecisionIndex(decisionIndex, tablePlan);
    }
}

function _isTableOnlyTextFrameNestedInInlineShell(src, sourceById) {
    if (!src || !sourceById) return false;
    var placement = String(src.storyAnchorPlacement || "").toUpperCase();
    if (placement !== "INLINE") return false;
    if (src.parentId === null || src.parentId === undefined || String(src.parentId) === "") return false;
    var parent = sourceById[String(src.parentId)];
    if (!parent) return false;
    var parentKind = String(parent.kind || parent.type || "");
    if (parentKind === "TextFrame") return false;
    var parentPlacement = String(parent.storyAnchorPlacement || "").toUpperCase();
    return parentPlacement === "INLINE"
            || parent.storyTextInlineSlot === true
            || parent.isInline === true;
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
    if (plan.primarySourceObjectIdKey !== undefined && plan.primarySourceObjectIdKey !== null) {
        index.textDecisionByFrameId[String(plan.primarySourceObjectIdKey)] = true;
    }
    for (var t = 0; plan.ownedTextFrameIds && t < plan.ownedTextFrameIds.length; t++) {
        index.textDecisionByFrameId[String(plan.ownedTextFrameIds[t])] = true;
    }
    for (var k = 0; plan.ownedTextFrameIdKeys && k < plan.ownedTextFrameIdKeys.length; k++) {
        index.textDecisionByFrameId[String(plan.ownedTextFrameIdKeys[k])] = true;
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

function _tableOnlyTextFrameObjectPlan(src, id, sourceById, pageIndex, zOrder, textActionOverride, reasonOverride) {
    var tableIds = _sortedNumericIds(src.tableSourceObjectIds || []);
    var sourceIds = _sortedNumericIds([id].concat(tableIds));
    var styleSourceObjectIds = _tableOnlyTextFrameStyleSourceObjectIds(src, id, sourceById);
    var ownsTableStyle = styleSourceObjectIds.length > 0;
    var textAction = textActionOverride || "OWNED_BY_HWPX_TEXT";
    return {
        objectPlanId: "objectPlan.table_only_text_frame." + String(id),
        bundleId: "textFrame.tableOnly." + String(id),
        candidateId: null,
        passId: "pass.table_only_text_frames",
        pageIndex: pageIndex,
        kind: "TextFrame",
        mode: ownsTableStyle ? "TABLE_STYLE" : "TEXT_ONLY",
        candidatePurpose: "table_only_text_frame",
        compositeRole: null,
        slotRole: ownsTableStyle ? "TABLE_STYLE_SLOT" : "TEXT_SLOT",
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
        clusterHasPlacedContent: ownsTableStyle,
        clusterHasVisualSource: ownsTableStyle,
        visualSourceObjectIds: [],
        styleSourceObjectIds: styleSourceObjectIds,
        ownedTextFrameIds: [id],
        exportSourceObjectIds: [],
        hiddenVisualSourceObjectIds: [],
        materialization: ownsTableStyle ? "HWPX_TABLE_STYLE" : "HWPX_TEXT",
        textAction: textAction,
        visualAction: ownsTableStyle ? "PLACE_TABLE_STYLE" : "DROP_VISUAL",
        placement: src.storyAnchorPlacement === "INLINE" ? "INLINE" : "FLOATING",
        coordinateSpace: src.storyAnchorPlacement === "INLINE" ? "STORY_FLOW" : "PAGE",
        visualLayer: "CONTENT_VISUAL",
        zOrder: zOrder,
        reason: reasonOverride || "table_only_text_frame",
        bounds: src.bounds || null,
        ownershipSlot: ownsTableStyle ? "TABLE_STYLE_SLOT" : "TEXT_SLOT",
        policyLayer: "TEXT",
        clusterRelation: "EXACT_SOURCE_CLUSTER",
        migrationStatus: ownsTableStyle ? "READY_EXACT_CLUSTER" : "READY_TEXT_ONLY",
        migrationBlocker: "NONE",
        migrationBlockerDetail: {},
        executable: true,
        required: true
    };
}

function _tableOnlyTextFrameStyleSourceObjectIds(src, textFrameId, sourceById) {
    var ids = {};
    _collectTableOnlyIntrinsicTableStyleSources(src, ids);
    _collectTableOnlyDirectStyleOwner(src, textFrameId, sourceById, ids);
    _collectTableOnlyCarrierStyleSiblings(src, textFrameId, sourceById, ids);
    return _internSourceSetIds(_sortedNumericIds(_objectPlanSetKeysAsNumbers(ids)));
}

function _collectTableOnlyIntrinsicTableStyleSources(src, ids) {
    if (!src || !ids || !src.tableSourceObjectIds) return;
    for (var i = 0; i < src.tableSourceObjectIds.length; i++) {
        var id = Number(src.tableSourceObjectIds[i]);
        if (isNaN(id)) continue;
        ids[String(id)] = true;
    }
}

function _collectTableOnlyDirectStyleOwner(src, textFrameId, sourceById, ids) {
    if (!src || !sourceById || !ids || !src.parentId) return;
    var parent = sourceById[String(src.parentId)];
    if (_isTableAttributeStyleSource(parent, textFrameId)) {
        ids[String(parent.id)] = true;
    }
}

function _collectTableOnlyCarrierStyleSiblings(src, textFrameId, sourceById, ids) {
    if (!src || !sourceById || !ids) return;
    var hasParentId = src.parentId !== null && src.parentId !== undefined;
    var parent = hasParentId ? sourceById[String(src.parentId)] : null;
    var carrier = parent;
    if (parent && parent.parentId) {
        var grandparent = sourceById[String(parent.parentId)];
        if (grandparent) carrier = grandparent;
    }
    var tableOwnerBounds = _objectPlanSourceBounds(src);
    var seenChildIds = {};
    if (carrier) {
        if (_isTableAttributeStyleSource(carrier, textFrameId, tableOwnerBounds, sourceById, true)) {
            ids[String(carrier.id)] = true;
        }
        var carrierChildIds = _objectPlanSourceChildIds(carrier, sourceById);
        for (var i = 0; i < carrierChildIds.length; i++) {
            seenChildIds[String(carrierChildIds[i])] = true;
            _collectTableOnlyCarrierStyleSourceId(
                    carrierChildIds[i], textFrameId, tableOwnerBounds, sourceById, ids);
        }
    }
    var siblingParentId = carrier && carrier.id !== undefined && carrier.id !== null
            ? carrier.id
            : (hasParentId ? src.parentId : null);
    for (var key in sourceById) {
        if (!sourceById.hasOwnProperty(key)) continue;
        var item = sourceById[key];
        if (!item) continue;
        if (Number(item.id) === Number(textFrameId)) continue;
        if (siblingParentId === null || siblingParentId === undefined) {
            if (item.parentId !== null && item.parentId !== undefined) continue;
        } else if (String(item.parentId) !== String(siblingParentId)) {
            continue;
        }
        if (src.pageIndex !== undefined && item.pageIndex !== undefined
                && Number(src.pageIndex) !== Number(item.pageIndex)) continue;
        if (seenChildIds[String(item.id)]) continue;
        _collectTableOnlyCarrierStyleSourceId(
                item.id, textFrameId, tableOwnerBounds, sourceById, ids);
    }
}

function _collectTableOnlyCarrierStyleSourceId(sourceId, textFrameId, tableOwnerBounds, sourceById, ids) {
    var numericId = Number(sourceId);
    if (isNaN(numericId) || numericId === Number(textFrameId)) return;
    var item = sourceById ? sourceById[String(numericId)] : null;
    if (!item) return;
    if (item.visible === false || item.hiddenLayer === true || item.nonprinting === true) return;
    if (item.sourceHidden === true || item.hiddenByParent === true) return;
    if (item.isInline === true || item.storyAnchorPlacement === "INLINE") return;
    if (_objectPlanSourceSubtreeContainsOtherTextFrame(item, textFrameId, sourceById)) return;
    if (_objectPlanSourceKind(item) === "Group") {
        var childIds = _objectPlanSourceChildIds(item, sourceById);
        for (var i = 0; i < childIds.length; i++) {
            _collectTableOnlyCarrierStyleSourceId(
                    childIds[i], textFrameId, tableOwnerBounds, sourceById, ids);
        }
        return;
    }
    if (_isTableAttributeStyleSource(item, textFrameId, tableOwnerBounds, sourceById, false)) {
        ids[String(numericId)] = true;
    }
}

function _isTableAttributeStyleSource(item, textFrameId, tableOwnerBounds, sourceById, allowOwnerSubtreeContainer) {
    if (!item) return false;
    var id = Number(item.id);
    if (isNaN(id) || id === Number(textFrameId)) return false;
    if (item.visible === false || item.hiddenLayer === true || item.nonprinting === true) return false;
    if (item.sourceHidden === true || item.hiddenByParent === true) return false;
    if (item.isInline === true || item.storyAnchorPlacement === "INLINE") return false;
    var kind = _objectPlanSourceKind(item);
    if (kind === "TextFrame" || kind === "Image" || kind === "PDF" || kind === "EPS") return false;
    if (kind !== "Rectangle" && kind !== "GraphicLine") return false;
    if (_objectPlanSourceChildIds(item, sourceById).length > 0) {
        var childIds = _objectPlanSourceChildIds(item, sourceById);
        var directOwnerChild = childIds.length === 1 && Number(childIds[0]) === Number(textFrameId);
        if (!directOwnerChild
                && (!allowOwnerSubtreeContainer
                || !_objectPlanSourceSubtreeContainsOnlyTableOwnerContent(
                        item, textFrameId, sourceById))) {
            return false;
        }
    }
    if (!_objectPlanSourceHasAxisAlignedRotation(item.absoluteRotationAngle)) return false;
    if (Math.abs(Number(item.absoluteShearAngle || 0)) > 0.1) return false;
    if (item.hasDropShadow === true || item.gradientFeatherApplied === true) return false;
    if (!_objectPlanSourceHasVisibleFillOrStroke(item)) return false;
    if (tableOwnerBounds) {
        var itemBounds = _objectPlanSourceBounds(item);
        if (!_objectPlanBoundsNearOrInside(tableOwnerBounds, itemBounds, 2.0)) return false;
    }
    return true;
}

function _objectPlanSourceHasAxisAlignedRotation(angle) {
    var value = Math.abs(Number(angle || 0));
    if (isNaN(value)) return true;
    var normalized = value % 180;
    normalized = Math.min(normalized, 180 - normalized);
    return normalized <= 0.1;
}

function _objectPlanSourceHasVisibleFillOrStroke(item) {
    if (!item) return false;
    var fill = String(item.fillColorName || item.fillColor || "");
    var stroke = String(item.strokeColorName || item.strokeColor || "");
    var strokeWeight = Number(item.strokeWeight || 0);
    var visibleFill = fill && fill !== "None" && fill !== "[None]";
    var visibleStroke = stroke && stroke !== "None" && stroke !== "[None]" && strokeWeight > 0;
    return visibleFill || visibleStroke;
}

function _objectPlanBoundsNearOrInside(container, child, tolerance) {
    if (!container || !child || container.length < 4 || child.length < 4) return false;
    var t = Number(tolerance || 0);
    return Number(child[0]) >= Number(container[0]) - t
            && Number(child[1]) >= Number(container[1]) - t
            && Number(child[2]) <= Number(container[2]) + t
            && Number(child[3]) <= Number(container[3]) + t;
}

function _objectPlanSourceBounds(item) {
    if (!item) return null;
    return item.bounds || item.pageRelativeBounds || item.geometricBounds || null;
}

function _objectPlanUnionSourceBoundsForIds(ids, sourceById) {
    if (!ids || !sourceById) return null;
    var out = null;
    var seen = {};
    for (var i = 0; i < ids.length; i++) {
        var id = Number(ids[i]);
        if (isNaN(id)) continue;
        var key = String(id);
        if (seen[key]) continue;
        seen[key] = true;
        var item = sourceById[key];
        var bounds = _objectPlanSourceBounds(item);
        if (!bounds) continue;
        out = _objectPlanUnionTwoBounds(out, bounds);
    }
    return out;
}

function _objectPlanSourceChildIds(item, sourceById) {
    if (!item) return [];
    if (item.childIds && item.childIds.length !== undefined) return item.childIds;
    if (!sourceById || item.id === null || item.id === undefined) return [];
    var ids = [];
    var parentKey = String(item.id);
    for (var key in sourceById) {
        if (!sourceById.hasOwnProperty(key)) continue;
        var child = sourceById[key];
        if (!child || child.parentId === null || child.parentId === undefined) continue;
        if (String(child.parentId) === parentKey) ids.push(child.id);
    }
    return ids;
}

function _objectPlanSourceSubtreeContainsOnlyTableOwnerContent(item, textFrameId, sourceById) {
    if (!item || !sourceById) return false;
    var foundOwner = false;

    function visit(sourceId, depth) {
        if (depth > 32) return false;
        var id = Number(sourceId);
        if (isNaN(id)) return true;
        if (id === Number(textFrameId)) {
            foundOwner = true;
            return true;
        }
        var child = sourceById[String(id)];
        if (!child) return true;
        var kind = _objectPlanSourceKind(child);
        if (kind === "TextFrame" || kind === "Image" || kind === "PDF" || kind === "EPS") {
            return false;
        }
        var childIds = _objectPlanSourceChildIds(child, sourceById);
        for (var i = 0; i < childIds.length; i++) {
            if (!visit(childIds[i], depth + 1)) return false;
        }
        return true;
    }

    var childIds = _objectPlanSourceChildIds(item, sourceById);
    for (var i = 0; i < childIds.length; i++) {
        if (!visit(childIds[i], 0)) return false;
    }
    return foundOwner;
}

function _objectPlanSourceSubtreeContainsOtherTextFrame(item, textFrameId, sourceById) {
    if (!item || !sourceById) return false;

    function visit(sourceId, depth) {
        if (depth > 32) return false;
        var id = Number(sourceId);
        if (isNaN(id) || id === Number(textFrameId)) return false;
        var child = sourceById[String(id)];
        if (!child) return false;
        if (_objectPlanSourceKind(child) === "TextFrame") return true;
        var childIds = _objectPlanSourceChildIds(child, sourceById);
        for (var i = 0; i < childIds.length; i++) {
            if (visit(childIds[i], depth + 1)) return true;
        }
        return false;
    }

    var childIds = _objectPlanSourceChildIds(item, sourceById);
    for (var i = 0; i < childIds.length; i++) {
        if (visit(childIds[i], 0)) return true;
    }
    return false;
}

function _objectPlanSetKeysAsNumbers(set) {
    var values = [];
    for (var key in set) {
        if (!set.hasOwnProperty(key)) continue;
        var n = Number(key);
        if (!isNaN(n)) values.push(n);
    }
    return values;
}

function _textFrameObjectPlan(src, id, pageIndex, zOrder, passId, reason) {
    var idIsNumeric = typeof id === "number" && !isNaN(id);
    var sourceIds = idIsNumeric ? [id] : [];
    var textFrameIdKeys = idIsNumeric ? [] : [String(id)];
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
        primarySourceObjectId: idIsNumeric ? id : null,
        primarySourceObjectIdKey: idIsNumeric ? null : String(id),
        ownedByNativeShellSourceObjectIds: [],
        sourceObjectIds: sourceIds,
        sourceObjectIdKeys: textFrameIdKeys,
        sourceRootObjectIds: sourceIds,
        sourceRootObjectIdKeys: textFrameIdKeys,
        clusterSourceObjectIds: sourceIds,
        clusterSourceObjectIdKeys: textFrameIdKeys,
        clusterKindCounts: { TextFrame: 1 },
        omittedClusterSourceObjectIds: [],
        omittedClusterKindCounts: {},
        clusterHasEditableText: src.textFrameClass === "editable",
        clusterHasTextFrame: true,
        clusterHasPlacedContent: false,
        clusterHasVisualSource: false,
        visualSourceObjectIds: [],
        styleSourceObjectIds: [],
        ownedTextFrameIds: sourceIds,
        ownedTextFrameIdKeys: textFrameIdKeys,
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

function _emptyInlineTextFrameVisualObjectPlan(src, id, pageIndex, zOrder) {
    var sourceIds = _internSourceSetIds([id]);
    var sourceSetId = _sourceSetId(sourceIds);
    return {
        objectPlanId: "objectPlan.empty_inline_textframe_visual." + String(id),
        bundleId: "textFrame.emptyInlineVisual." + String(id),
        candidateId: _candidateId("pass.inline_objects", id, pageIndex),
        passId: "pass.inline_objects",
        pageIndex: pageIndex,
        kind: "TextFrame",
        unit: "INLINE_OBJECT",
        mode: "TEXTLESS_CANDIDATE",
        candidatePurpose: "INLINE_CANDIDATE",
        compositeRole: "empty_inline_textframe_visual",
        slotRole: "content_visual_slot",
        layoutOnlyInlineSlot: false,
        sourceInlineFlow: true,
        inlineCompositeLayoutDescendant: false,
        inlineAnchorSourceObjectId: id,
        inlineSourceTreeClosed: true,
        inlineFlowSourceObjectIds: sourceIds,
        connectorDecorationVisual: false,
        primarySourceObjectId: id,
        sourceSetId: sourceSetId,
        sourceRootSetId: sourceSetId,
        clusterSourceSetId: sourceSetId,
        visualSourceSetId: sourceSetId,
        exportSourceSetId: sourceSetId,
        hiddenSourceSetId: _sourceSetId([]),
        ownedByNativeShellSourceObjectIds: [],
        sourceObjectIds: sourceIds,
        sourceRootObjectIds: sourceIds,
        clusterSourceObjectIds: sourceIds,
        clusterKindCounts: { TextFrame: 1 },
        omittedClusterSourceObjectIds: [],
        omittedClusterKindCounts: {},
        clusterHasEditableText: false,
        clusterHasTextFrame: true,
        clusterHasPlacedContent: false,
        clusterHasVisualSource: true,
        visualSourceObjectIds: sourceIds,
        styleSourceObjectIds: sourceIds,
        ownedTextFrameIds: [],
        exportSourceObjectIds: sourceIds,
        exportTargetObjectId: id,
        atomicExportTargetObjectId: id,
        atomicExportTargetObjectIds: sourceIds,
        atomicTextlessVectorContent: true,
        atomicContentVisualSlot: true,
        hiddenVisualSourceObjectIds: [],
        excludedInlineSourceObjectIds: [],
        materialization: "EXTRACTED_PNG_VECTOR",
        textAction: "DROP_TEXT",
        visualAction: "PLACE_INLINE_PNG",
        placement: "INLINE",
        coordinateSpace: "STORY_FLOW",
        visualLayer: "CONTENT_VISUAL",
        zOrder: zOrder,
        reason: "empty_inline_text_frame_visible_frame_paint",
        bounds: src.bounds || null,
        renderSourceBounds: src.bounds || null,
        cropSourceBounds: src.bounds || null,
        ownershipSlot: "CONTENT_VISUAL_SLOT",
        policyLayer: "CONTENT",
        clusterRelation: "EXACT_SOURCE_CLUSTER",
        migrationStatus: "READY_EXACT_CLUSTER",
        migrationBlocker: "NONE",
        migrationBlockerDetail: {},
        contractStatus: "READY_FOR_STAGE1_IMPORT",
        executable: true,
        required: true
    };
}

function _inlineVisibleTextFrameShellObjectPlan(src, id, pageIndex, zOrder) {
    var sourceIds = _internSourceSetIds([id]);
    var sourceSetId = _sourceSetId(sourceIds);
    return {
        objectPlanId: "objectPlan.inline_visible_textframe_shell." + String(id),
        bundleId: "textFrame.inlineVisibleShell." + String(id),
        candidateId: _candidateId("pass.inline_objects", id, pageIndex)
                + ".inline_textframe_shell",
        passId: "pass.inline_objects",
        pageIndex: pageIndex,
        kind: "TextFrame",
        unit: "INLINE_OBJECT",
        mode: "TEXTLESS_CANDIDATE",
        candidatePurpose: "INLINE_CANDIDATE",
        compositeRole: "inline_visible_text_frame_shell",
        slotRole: "inline_text_frame_shell_slot",
        layoutOnlyInlineSlot: false,
        sourceInlineFlow: true,
        inlineCompositeLayoutDescendant: false,
        inlineAnchorSourceObjectId: id,
        inlineSourceTreeClosed: true,
        inlineFlowSourceObjectIds: sourceIds,
        connectorDecorationVisual: false,
        primarySourceObjectId: id,
        sourceSetId: sourceSetId,
        sourceRootSetId: sourceSetId,
        clusterSourceSetId: sourceSetId,
        visualSourceSetId: sourceSetId,
        exportSourceSetId: sourceSetId,
        hiddenSourceSetId: _sourceSetId([]),
        ownedByNativeShellSourceObjectIds: [],
        sourceObjectIds: sourceIds,
        sourceRootObjectIds: sourceIds,
        clusterSourceObjectIds: sourceIds,
        clusterKindCounts: { TextFrame: 1 },
        omittedClusterSourceObjectIds: [],
        omittedClusterKindCounts: {},
        clusterHasEditableText: src.textFrameClass === "editable",
        clusterHasTextFrame: true,
        clusterHasPlacedContent: false,
        clusterHasVisualSource: true,
        visualSourceObjectIds: sourceIds,
        styleSourceObjectIds: sourceIds,
        ownedTextFrameIds: [id],
        exportSourceObjectIds: sourceIds,
        exportTargetObjectId: id,
        atomicExportTargetObjectId: id,
        atomicExportTargetObjectIds: sourceIds,
        hiddenVisualSourceObjectIds: [],
        excludedInlineSourceObjectIds: [],
        materialization: "EXTRACTED_PNG_VECTOR",
        textAction: "OWNED_BY_HWPX_TEXT",
        visualAction: "PLACE_TEXT_SHELL",
        placement: "INLINE",
        coordinateSpace: "STORY_FLOW",
        visualLayer: "LABEL_BACKDROP",
        zOrder: zOrder,
        reason: "stage1_inline_visible_text_frame_shell_from_source_inventory",
        bounds: src.bounds || null,
        renderSourceBounds: src.bounds || null,
        cropSourceBounds: src.bounds || null,
        ownershipSlot: "SHELL_SLOT",
        policyLayer: "DECORATION",
        clusterRelation: "EXACT_SOURCE_CLUSTER",
        migrationStatus: "READY_EXACT_CLUSTER",
        migrationBlocker: "NONE",
        migrationBlockerDetail: {},
        contractStatus: "READY_FOR_STAGE1_IMPORT",
        executable: true,
        required: true
    };
}

function _layoutOnlyInlineSlotObjectPlan(src, id, pageIndex, zOrder, footprint) {
    var sourceIds = _internSourceSetIds([id]);
    var sourceSetId = _sourceSetId(sourceIds);
    var layoutBounds = footprint && footprint.bounds ? footprint.bounds : (src.bounds || null);
    var layoutFootprintSourceObjectIds = _internSourceSetIds(
            footprint && footprint.sourceObjectIds ? footprint.sourceObjectIds : []);
    return {
        objectPlanId: "objectPlan.layout_only_inline_slot." + String(id),
        bundleId: "inline.layoutOnlySlot." + String(id),
        candidateId: _candidateId("pass.inline_objects", id, pageIndex) + ".layout_only",
        passId: "pass.inline_objects",
        pageIndex: pageIndex,
        kind: src.kind || "InlineObject",
        unit: "INLINE_OBJECT",
        mode: "LAYOUT_ONLY",
        candidatePurpose: "INLINE_LAYOUT_SLOT",
        compositeRole: "page_plane_absorbed_inline_layout_slot",
        slotRole: "layout_only_inline_slot",
        layoutOnlyInlineSlot: true,
        layoutFootprintExpanded: footprint && footprint.expanded === true,
        layoutFootprintReason: footprint && footprint.reason ? footprint.reason : "anchor_bounds",
        layoutFootprintSourceObjectIds: layoutFootprintSourceObjectIds,
        layoutFootprintClusterBounds: footprint && footprint.clusterBounds ? footprint.clusterBounds : null,
        layoutFootprintOverlapRatio: footprint && footprint.overlapRatio !== undefined
                ? footprint.overlapRatio : null,
        layoutFootprintFlowClearance: footprint && footprint.flowClearance !== undefined
                ? footprint.flowClearance : null,
        sourceInlineFlow: true,
        inlineCompositeLayoutDescendant: false,
        inlineAnchorSourceObjectId: id,
        inlineSourceTreeClosed: true,
        inlineFlowSourceObjectIds: sourceIds,
        connectorDecorationVisual: false,
        primarySourceObjectId: id,
        sourceSetId: sourceSetId,
        sourceRootSetId: sourceSetId,
        clusterSourceSetId: sourceSetId,
        visualSourceSetId: _sourceSetId([]),
        exportSourceSetId: _sourceSetId([]),
        hiddenSourceSetId: _sourceSetId([]),
        ownedByNativeShellSourceObjectIds: [],
        sourceObjectIds: sourceIds,
        sourceRootObjectIds: sourceIds,
        clusterSourceObjectIds: sourceIds,
        clusterKindCounts: {},
        omittedClusterSourceObjectIds: [],
        omittedClusterKindCounts: {},
        clusterHasEditableText: false,
        clusterHasTextFrame: false,
        clusterHasPlacedContent: src.hasPlacedVisual === true,
        clusterHasVisualSource: false,
        visualSourceObjectIds: [],
        styleSourceObjectIds: [],
        ownedTextFrameIds: [],
        exportSourceObjectIds: [],
        hiddenVisualSourceObjectIds: [],
        excludedInlineSourceObjectIds: [],
        materialization: "HWPX_TEXT",
        textAction: "DROP_TEXT",
        visualAction: "DROP_VISUAL",
        placement: "INLINE",
        coordinateSpace: "STORY_FLOW",
        visualLayer: "CONTENT_VISUAL",
        zOrder: zOrder,
        reason: "page_plane_absorbed_inline_anchor_layout_slot",
        bounds: layoutBounds,
        renderSourceBounds: layoutBounds,
        cropSourceBounds: layoutBounds,
        ownershipSlot: "CONTENT_VISUAL_SLOT",
        policyLayer: "CONTENT",
        clusterRelation: "EXACT_SOURCE_CLUSTER",
        migrationStatus: "READY_LAYOUT_ONLY_INLINE_SLOT",
        migrationBlocker: "NONE",
        migrationBlockerDetail: {},
        contractStatus: "READY_FOR_STAGE1_IMPORT",
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
        if ((plan.ownedTextFrameIds && plan.ownedTextFrameIds.length > 0)
                || (plan.ownedTextFrameIdKeys && plan.ownedTextFrameIdKeys.length > 0)) {
            summary.plansWithOwnedTextFrames++;
        }
        _incrementObjectPlanSummary(summary.migrationBlockerCounts, plan.migrationBlocker || "NONE");
    }
    return summary;
}

function _resolveObjectPlanDuplicateTextOwners(objectPlans, sourceById) {
    var plans = objectPlans || [];
    var ownersByTextFrameId = {};
    for (var i = 0; i < plans.length; i++) {
        var plan = plans[i];
        if (!plan || (plan.textAction !== "OWNED_BY_HWPX_TEXT" && plan.textAction !== "OWNED_BY_PNG")) continue;
        if ((!plan.ownedTextFrameIds || plan.ownedTextFrameIds.length === 0)
                && (!plan.ownedTextFrameIdKeys || plan.ownedTextFrameIdKeys.length === 0)) continue;
        for (var t = 0; plan.ownedTextFrameIds && t < plan.ownedTextFrameIds.length; t++) {
            var textId = String(plan.ownedTextFrameIds[t]);
            if (!ownersByTextFrameId[textId]) ownersByTextFrameId[textId] = [];
            ownersByTextFrameId[textId].push(plan);
        }
        for (var kt = 0; plan.ownedTextFrameIdKeys && kt < plan.ownedTextFrameIdKeys.length; kt++) {
            var textKey = String(plan.ownedTextFrameIdKeys[kt]);
            if (!ownersByTextFrameId[textKey]) ownersByTextFrameId[textKey] = [];
            ownersByTextFrameId[textKey].push(plan);
        }
    }

    var canonicalByTextFrameId = {};
    var duplicateTextFrameCount = 0;
    for (var textKey in ownersByTextFrameId) {
        if (!ownersByTextFrameId.hasOwnProperty(textKey)) continue;
        var owners = ownersByTextFrameId[textKey];
        if (!owners || owners.length < 2) continue;
        duplicateTextFrameCount++;
        var canonical = null;
        for (var inlineOwnerIndex = 0; inlineOwnerIndex < owners.length; inlineOwnerIndex++) {
            var inlineOwner = owners[inlineOwnerIndex];
            if (_objectPlanIsInlineVisibleTextFrameShellPlan(inlineOwner)
                    || _objectPlanOwnsInlineVisibleTextFrameSource(inlineOwner, sourceById)) {
                if (!canonical
                        || _objectPlanInlineVisibleTextFrameShellPriority(inlineOwner)
                            > _objectPlanInlineVisibleTextFrameShellPriority(canonical)) {
                    canonical = inlineOwner;
                }
            }
        }
        if (!canonical) {
            canonical = owners[0];
            for (var o = 1; o < owners.length; o++) {
                if (_compareObjectPlanTextOwnerPriority(owners[o], canonical) < 0) {
                    canonical = owners[o];
                }
            }
        }
        canonicalByTextFrameId[textKey] = canonical;
    }

    var demoted = [];
    for (var p = 0; p < plans.length; p++) {
        var candidate = plans[p];
        if (!candidate || candidate.textAction !== "OWNED_BY_HWPX_TEXT") continue;
        if (_objectPlanIsInlineVisibleTextFrameShellPlan(candidate)
                || _objectPlanOwnsInlineVisibleTextFrameSource(candidate, sourceById)) {
            var inlineRetainedTextFrameIds = [];
            var inlineDroppedTextFrameIds = [];
            for (var it = 0; candidate.ownedTextFrameIds && it < candidate.ownedTextFrameIds.length; it++) {
                var inlineOriginalOwnedTextId = candidate.ownedTextFrameIds[it];
                var inlineOwnedTextId = String(inlineOriginalOwnedTextId);
                var inlineCanonicalPlan = canonicalByTextFrameId[inlineOwnedTextId];
                if (inlineCanonicalPlan && inlineCanonicalPlan !== candidate) {
                    inlineDroppedTextFrameIds.push(inlineOriginalOwnedTextId);
                } else {
                    inlineRetainedTextFrameIds.push(inlineOriginalOwnedTextId);
                }
            }
            if (inlineDroppedTextFrameIds.length > 0) {
                if (inlineRetainedTextFrameIds.length > 0) {
                    candidate.ownedTextFrameIds = inlineRetainedTextFrameIds;
                    candidate.textOwnershipResolution = "PARTIAL_DUPLICATE_INLINE_TEXT_SHELL_OWNER_RESOLVED";
                    candidate.textOwnershipResolutionReason =
                            "kept_only_inline_text_frames_for_which_this_shell_plan_is_canonical";
                    candidate.reason = String(candidate.reason || "")
                            + ":partial_inline_text_shell_owner_duplicate_resolved";
                    continue;
                }
                candidate.textAction = "DROP_TEXT";
                candidate.visualAction = "DROP_VISUAL";
                candidate.materialization = "HWPX_TEXT";
                candidate.textOwnershipResolution = "DROPPED_DUPLICATE_INLINE_TEXT_SHELL_OWNER";
                candidate.textOwnershipResolutionReason =
                        "more_complete_inline_shell_plan_owns_the_same_text_frame";
                candidate.reason = String(candidate.reason || "")
                        + ":inline_text_shell_owner_dropped_duplicate";
                demoted.push(candidate.objectPlanId || candidate.bundleId || candidate.candidateId || ("plan.index." + p));
                continue;
            }
            if (!_objectPlanHasExecutableInlineShellCarrier(candidate, sourceById)) {
                _promoteObjectPlanInlineVisibleTextFrameShellFromOwnedSource(candidate, sourceById);
            }
            candidate.textOwnershipResolution = "CANONICAL_INLINE_TEXT_FRAME_SHELL_OWNER";
            continue;
        }
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

function _objectPlanIsInlineVisibleTextFrameShellTextOwner(plan) {
    if (!plan) return false;
    if (plan.textAction !== "OWNED_BY_HWPX_TEXT") return false;
    if (plan.visualAction !== "PLACE_TEXT_SHELL") return false;
    if (plan.materialization !== "NATIVE_SOURCE_SHAPE"
            && plan.materialization !== "EXTRACTED_PNG_VECTOR") {
        return false;
    }
    if (plan.placement !== "INLINE" || plan.coordinateSpace !== "STORY_FLOW") return false;
    if (plan.ownershipSlot !== "SHELL_SLOT") return false;
    if (plan.slotRole !== "inline_text_frame_shell_slot"
            && plan.compositeRole !== "inline_visible_text_frame_shell"
            && plan.slotRole !== "direct_child_shell_slot"
            && plan.compositeRole !== "direct_child_shell_slot"
            && plan.slotRole !== "inline_editable_text_shell_composite"
            && plan.compositeRole !== "inline_editable_text_shell_composite") {
        return false;
    }
    return plan.ownedTextFrameIds && plan.ownedTextFrameIds.length > 0;
}

function _objectPlanIsInlineVisibleTextFrameShellPlan(plan) {
    if (!plan) return false;
    if (plan.placement !== "INLINE" || plan.coordinateSpace !== "STORY_FLOW") return false;
    if (plan.slotRole === "inline_text_frame_shell_slot"
            || plan.compositeRole === "inline_visible_text_frame_shell"
            || plan.slotRole === "direct_child_shell_slot"
            || plan.compositeRole === "direct_child_shell_slot"
            || plan.slotRole === "inline_editable_text_shell_composite"
            || plan.compositeRole === "inline_editable_text_shell_composite") {
        return true;
    }
    return String(plan.reason || "").indexOf("inline_visible_text_frame_shell") >= 0;
}

function _objectPlanOwnsInlineVisibleTextFrameSource(plan, sourceById) {
    if (!plan || !sourceById) return false;
    if (plan.placement !== "INLINE" || plan.coordinateSpace !== "STORY_FLOW") return false;
    var ids = [];
    if (plan.ownedTextFrameIds && plan.ownedTextFrameIds.length > 0) {
        ids = ids.concat(plan.ownedTextFrameIds);
    }
    if (plan.primarySourceObjectId !== null && plan.primarySourceObjectId !== undefined) {
        ids.push(plan.primarySourceObjectId);
    }
    for (var i = 0; i < ids.length; i++) {
        var id = Number(ids[i]);
        if (isNaN(id)) continue;
        var src = sourceById[String(id)];
        if (_objectPlanSourceIsInlineVisibleTextFrameShell(src)) return true;
    }
    return false;
}

function _objectPlanHasExecutableInlineShellCarrier(plan, sourceById) {
    if (!plan || plan.visualAction !== "PLACE_TEXT_SHELL") return false;
    if (plan.placement !== "INLINE" || plan.coordinateSpace !== "STORY_FLOW") return false;
    if (!plan.visualSourceObjectIds || plan.visualSourceObjectIds.length === 0) return false;
    var owned = _sourceIdSet(plan.ownedTextFrameIds || []);
    for (var i = 0; i < plan.visualSourceObjectIds.length; i++) {
        var id = Number(plan.visualSourceObjectIds[i]);
        if (isNaN(id)) continue;
        if (owned[String(id)]) continue;
        var src = sourceById ? sourceById[String(id)] : null;
        if (!src || !_objectPlanSourceKindIsTextOnly(_objectPlanSourceKind(src))) {
            return true;
        }
    }
    return false;
}

function _promoteObjectPlanInlineVisibleTextFrameShellFromOwnedSource(plan, sourceById) {
    if (!plan || !sourceById) return false;
    var ids = [];
    if (plan.ownedTextFrameIds && plan.ownedTextFrameIds.length > 0) {
        ids = ids.concat(plan.ownedTextFrameIds);
    }
    if (plan.primarySourceObjectId !== null && plan.primarySourceObjectId !== undefined) {
        ids.push(plan.primarySourceObjectId);
    }
    for (var i = 0; i < ids.length; i++) {
        var id = Number(ids[i]);
        if (isNaN(id)) continue;
        var src = sourceById[String(id)];
        if (!_objectPlanSourceIsInlineVisibleTextFrameShell(src)) continue;
        _promoteObjectPlanInlineVisibleTextFrameShell(plan, src, id);
        return true;
    }
    return false;
}

function _objectPlanInlineVisibleTextFrameShellPriority(plan) {
    if (!_objectPlanIsInlineVisibleTextFrameShellPlan(plan)) {
        return 0;
    }
    var score = 1000;
    var objectPlanId = String(plan.objectPlanId || "");
    if (objectPlanId.indexOf("objectPlan.inline_visible_textframe_shell.") === 0) score += 500;
    if (plan.slotRole === "direct_child_shell_slot"
            || plan.compositeRole === "direct_child_shell_slot") {
        score += 900;
    }
    if (plan.slotRole === "inline_editable_text_shell_composite"
            || plan.compositeRole === "inline_editable_text_shell_composite") {
        score += 700;
    }
    if (_objectPlanHasExecutableInlineShellCarrier(plan, null)) score += 300;
    if (plan.materialization === "NATIVE_SOURCE_SHAPE"
            || plan.materialization === "EXTRACTED_PNG_VECTOR") {
        score += 100;
    }
    if (plan.visualAction === "PLACE_TEXT_SHELL") score += 50;
    return score;
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
        var canonical = null;
        for (var inlineOwnerIndex = 0; inlineOwnerIndex < owners.length; inlineOwnerIndex++) {
            var inlineOwner = owners[inlineOwnerIndex];
            if (!_objectPlanIsInlineVisibleTextFrameShellVisualOwner(inlineOwner)) continue;
            if (!canonical
                    || _objectPlanInlineVisibleTextFrameShellPriority(inlineOwner)
                        > _objectPlanInlineVisibleTextFrameShellPriority(canonical)) {
                canonical = inlineOwner;
            }
        }
        if (!canonical) {
            canonical = owners[0];
            for (var oi = 1; oi < owners.length; oi++) {
                if (_compareObjectPlanVisibleVisualSourcePriority(owners[oi], canonical) < 0) {
                    canonical = owners[oi];
                }
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

function _resolveObjectPlanNestedInlineTextShellOwners(objectPlans, sourceById) {
    var plans = objectPlans || [];
    var droppedPlanCount = 0;
    var droppedObjectPlanIds = [];
    var nestedShellDuplicateCount = 0;
    var anchoredTableStyleShellCount = 0;

    var anchoredTableTextFrameIds = {};
    for (var a = 0; a < plans.length; a++) {
        var anchoredTableTextPlan = plans[a];
        if (!anchoredTableTextPlan
                || anchoredTableTextPlan.reason !== "owned_by_anchored_table_plan"
                || !anchoredTableTextPlan.ownedTextFrameIds) {
            continue;
        }
        for (var ai = 0; ai < anchoredTableTextPlan.ownedTextFrameIds.length; ai++) {
            anchoredTableTextFrameIds[String(anchoredTableTextPlan.ownedTextFrameIds[ai])] = true;
        }
    }

    for (var s = 0; s < plans.length; s++) {
        var shell = plans[s];
        if (!_objectPlanIsAnchoredTableStyleShell(shell, anchoredTableTextFrameIds)) continue;
        anchoredTableStyleShellCount++;
        shell.hiddenVisualSourceObjectIds = _sourceIdsUnion(
                shell.hiddenVisualSourceObjectIds || [],
                _sourceIdsUnion(
                        shell.visualSourceObjectIds || [],
                        shell.exportSourceObjectIds || []));
        shell.visualSourceObjectIds = [];
        shell.exportSourceObjectIds = [];
        shell.visualAction = "DROP_VISUAL";
        shell.materialization = "HWPX_TABLE_STYLE";
        shell.anchoredTableStyleShellResolution = "DROPPED_SHELL_TABLE_STYLE_OWNED_BY_HWPX_TABLE";
        shell.reason = String(shell.reason || "")
                + ":anchored_table_style_owned_by_hwpx_table";
        droppedPlanCount++;
        droppedObjectPlanIds.push(
                shell.objectPlanId || shell.bundleId || shell.candidateId
                        || ("anchored.table.style.shell.plan." + String(s)));
    }

    for (var i = 0; i < plans.length; i++) {
        var child = plans[i];
        if (!_objectPlanIsNestedInlineTextShellChild(child)) continue;
        var parent = _objectPlanFindContainingInlineTextShellPlan(child, plans);
        if (!parent) continue;
        nestedShellDuplicateCount++;
        if (_objectPlanTrimContainingInlineTextShellParent(parent, child, sourceById)) {
            continue;
        }
        child.hiddenVisualSourceObjectIds = _sourceIdsUnion(
                child.hiddenVisualSourceObjectIds || [],
                _sourceIdsUnion(
                        child.visualSourceObjectIds || [],
                        child.exportSourceObjectIds || []));
        child.visualSourceObjectIds = [];
        child.exportSourceObjectIds = [];
        child.visualAction = "DROP_VISUAL";
        child.materialization = "HWPX_TEXT";
        child.nestedInlineTextShellResolution = "DROPPED_NESTED_DIRECT_CHILD_SHELL";
        child.nestedInlineTextShellResolutionReason =
                "same_inline_text_shell_slot_is_covered_by_containing_parent_shell_source_bundle";
        child.nestedInlineTextShellCanonicalObjectPlanId =
                parent.objectPlanId || parent.bundleId || parent.candidateId || null;
        child.reason = String(child.reason || "")
                + ":nested_inline_text_shell_owner_resolved";
        droppedPlanCount++;
        droppedObjectPlanIds.push(
                child.objectPlanId || child.bundleId || child.candidateId
                        || ("nested.inline.shell.plan." + String(i)));
    }

    return {
        summary: {
            anchoredTableStyleShellCount: anchoredTableStyleShellCount,
            nestedShellDuplicateCount: nestedShellDuplicateCount,
            droppedPlanCount: droppedPlanCount,
            droppedObjectPlanIds: droppedObjectPlanIds
        }
    };
}

function _objectPlanTrimContainingInlineTextShellParent(parent, child, sourceById) {
    if (!parent || !child) return false;
    var originalPlanBounds = _objectPlanNormalizeBounds(parent.bounds);
    var originalSourceBounds = _objectPlanUnionSourceBoundsForIds(parent.sourceObjectIds || [], sourceById);
    var childSourceIds = _sourceIdsUnion(
            child.sourceObjectIds || [],
            _sourceIdsUnion(child.visualSourceObjectIds || [], child.exportSourceObjectIds || []));
    var childOwnedTextIds = child.ownedTextFrameIds || [];
    var removedIds = _sourceIdsUnion(childSourceIds, childOwnedTextIds);
    if (!removedIds || removedIds.length === 0) return false;

    parent.ownedTextFrameIds = _sourceIdsMinus(parent.ownedTextFrameIds || [], childOwnedTextIds);
    var residualVisualIds = _objectPlanExecutableResidualInlineShellVisualIds(
            _sourceIdsMinus(parent.visualSourceObjectIds || [], removedIds), sourceById);
    var residualExportIds = _objectPlanExecutableResidualInlineShellVisualIds(
            _sourceIdsMinus(parent.exportSourceObjectIds || [], removedIds), sourceById);
    var residualStyleIds = _objectPlanResidualInlineShellStyleSourceIds(parent, sourceById);
    parent.visualSourceObjectIds = _sourceIdsUnion(residualVisualIds, residualStyleIds);
    parent.exportSourceObjectIds = _sourceIdsUnion(residualExportIds, residualStyleIds);
    parent.styleSourceObjectIds = _sourceIdsUnion(parent.styleSourceObjectIds || [], residualStyleIds);
    parent.hiddenVisualSourceObjectIds = _sourceIdsMinus(
            _sourceIdsUnion(parent.hiddenVisualSourceObjectIds || [], removedIds),
            _sourceIdsUnion(parent.visualSourceObjectIds || [],
                    _sourceIdsUnion(parent.exportSourceObjectIds || [], parent.styleSourceObjectIds || [])));
    var residualBoundsIds = _sourceIdsUnion(parent.visualSourceObjectIds || [],
            _sourceIdsUnion(parent.exportSourceObjectIds || [],
                    _sourceIdsUnion(parent.styleSourceObjectIds || [], parent.ownedTextFrameIds || [])));
    var residualSourceBounds = _objectPlanUnionSourceBoundsForIds(residualBoundsIds, sourceById);
    if (residualSourceBounds) {
        var residualPlanBounds = residualSourceBounds;
        if (originalPlanBounds && originalSourceBounds) {
            residualPlanBounds = _objectPlanTranslateBounds(
                    residualSourceBounds,
                    originalPlanBounds[0] - originalSourceBounds[0],
                    originalPlanBounds[1] - originalSourceBounds[1]);
        }
        parent.bounds = residualPlanBounds;
        parent.sourceBounds = residualSourceBounds;
        parent.boundsResolution = "TRIMMED_TO_RESIDUAL_INLINE_TEXT_SHELL_SLOT";
    }
    parent.nestedInlineTextShellResolution = "PARENT_TRIMMED_FOR_NESTED_CHILD_SLOT";
    if (!parent.nestedInlineTextShellChildObjectPlanIds) {
        parent.nestedInlineTextShellChildObjectPlanIds = [];
    }
    var childPlanId = child.objectPlanId || child.bundleId || child.candidateId || child.primarySourceObjectId;
    var childPlanSeen = false;
    for (var cpi = 0; cpi < parent.nestedInlineTextShellChildObjectPlanIds.length; cpi++) {
        if (String(parent.nestedInlineTextShellChildObjectPlanIds[cpi]) === String(childPlanId)) {
            childPlanSeen = true;
            break;
        }
    }
    if (!childPlanSeen) parent.nestedInlineTextShellChildObjectPlanIds.push(childPlanId);
    parent.reason = String(parent.reason || "") + ":nested_inline_child_shell_slot_trimmed";

    if (parent.visualSourceObjectIds.length === 0
            && parent.exportSourceObjectIds.length === 0
            && parent.ownedTextFrameIds.length === 0) {
        parent.visualAction = "DROP_VISUAL";
        parent.materialization = parent.ownedTextFrameIds.length > 0 ? "HWPX_TEXT" : "HWPX_TEXT";
    }
    if (parent.ownedTextFrameIds.length === 0) {
        parent.textAction = "DROP_TEXT";
    }
    if (parent.visualAction === "DROP_VISUAL" && parent.ownedTextFrameIds.length === 0) {
        parent.layoutOnlyInlineSlot = true;
        parent.nestedInlineTextShellResolution = "PARENT_DROPPED_AFTER_CHILD_SLOT_SPLIT";
    }

    child.nestedInlineTextShellResolution = "KEPT_NESTED_CHILD_SLOT_PARENT_TRIMMED";
    child.nestedInlineTextShellCanonicalObjectPlanId =
            child.objectPlanId || child.bundleId || child.candidateId || null;
    child.reason = String(child.reason || "") + ":nested_inline_child_shell_slot_kept";
    return true;
}

function _objectPlanExecutableResidualInlineShellVisualIds(ids, sourceById) {
    var out = [];
    var seen = {};
    for (var i = 0; ids && i < ids.length; i++) {
        var id = Number(ids[i]);
        if (isNaN(id)) continue;
        var src = sourceById ? sourceById[String(id)] : null;
        var kind = src ? _objectPlanSourceKind(src) : "";
        if (kind === "Group") continue;
        _pushUniqueId(out, seen, id);
    }
    return _sortedNumericIds(out);
}

function _objectPlanResidualInlineShellStyleSourceIds(plan, sourceById) {
    var out = [];
    var seen = {};
    for (var i = 0; plan && plan.ownedTextFrameIds && i < plan.ownedTextFrameIds.length; i++) {
        var id = Number(plan.ownedTextFrameIds[i]);
        if (isNaN(id)) continue;
        var src = sourceById ? sourceById[String(id)] : null;
        if (!_objectPlanSourceIsInlineVisibleTextFrameShell(src)) continue;
        _pushUniqueId(out, seen, id);
    }
    return _sortedNumericIds(out);
}

function _objectPlanIsAnchoredTableStyleShell(plan, anchoredTableTextFrameIds) {
    if (!plan || plan.visualAction !== "PLACE_TEXT_SHELL") return false;
    if (!anchoredTableTextFrameIds) return false;
    if (plan.placement !== "INLINE" || plan.coordinateSpace !== "STORY_FLOW") return false;
    if (!plan.ownedTextFrameIds || plan.ownedTextFrameIds.length === 0) return false;
    for (var i = 0; i < plan.ownedTextFrameIds.length; i++) {
        if (anchoredTableTextFrameIds[String(plan.ownedTextFrameIds[i])]) return true;
    }
    return false;
}

function _objectPlanFindContainingInlineTextShellPlan(child, plans) {
    var best = null;
    for (var i = 0; plans && i < plans.length; i++) {
        var parent = plans[i];
        if (parent === child) continue;
        if (!_objectPlanIsVisibleInlineTextShell(parent)) continue;
        if (parent.pageIndex !== child.pageIndex) continue;
        if (!_objectPlanSourceIdsContainAll(parent.sourceObjectIds || [], child.sourceObjectIds || [])) continue;
        if (!_objectPlanParentInlineShellContainsChildSlot(parent, child)) continue;
        if ((parent.sourceObjectIds || []).length <= (child.sourceObjectIds || []).length) continue;
        if (!best || (parent.sourceObjectIds || []).length > (best.sourceObjectIds || []).length) {
            best = parent;
        }
    }
    return best;
}

function _objectPlanParentInlineShellContainsChildSlot(parent, child) {
    if (!parent || !child) return false;
    if (_objectPlanOwnedTextFramesOverlap(parent, child)) return true;
    if (!_objectPlanIsNestedInlineTextShellChild(child)) return false;
    var childVisualIds = _sourceIdsUnion(
            child.visualSourceObjectIds || [],
            _sourceIdsUnion(child.exportSourceObjectIds || [], child.sourceObjectIds || []));
    if (!childVisualIds || childVisualIds.length === 0) return false;
    var parentIds = _sourceIdSet(_sourceIdsUnion(
            parent.visualSourceObjectIds || [],
            _sourceIdsUnion(parent.exportSourceObjectIds || [], parent.sourceObjectIds || [])));
    for (var i = 0; i < childVisualIds.length; i++) {
        if (parentIds[String(childVisualIds[i])]) return true;
    }
    return false;
}

function _objectPlanIsDirectChildInlineTextShell(plan) {
    if (!_objectPlanIsVisibleInlineTextShell(plan)) return false;
    return plan.slotRole === "direct_child_shell_slot"
            || plan.compositeRole === "direct_child_shell_slot";
}

function _objectPlanIsNestedInlineTextShellChild(plan) {
    if (!_objectPlanIsVisibleInlineTextShell(plan)) return false;
    return _objectPlanIsDirectChildInlineTextShell(plan)
            || plan.slotRole === "inline_text_frame_shell_slot"
            || plan.compositeRole === "inline_visible_text_frame_shell";
}

function _objectPlanIsVisibleInlineTextShell(plan) {
    return !!(plan
            && plan.passId === "pass.inline_objects"
            && plan.placement === "INLINE"
            && plan.coordinateSpace === "STORY_FLOW"
            && plan.visualAction === "PLACE_TEXT_SHELL"
            && plan.ownedTextFrameIds
            && plan.ownedTextFrameIds.length > 0);
}

function _objectPlanOwnedTextFramesOverlap(a, b) {
    var ids = _sourceIdSet(a && a.ownedTextFrameIds ? a.ownedTextFrameIds : []);
    for (var i = 0; b && b.ownedTextFrameIds && i < b.ownedTextFrameIds.length; i++) {
        if (ids[String(b.ownedTextFrameIds[i])]) return true;
    }
    return false;
}

function _objectPlanSourceIdsContainAll(parentIds, childIds) {
    if (!parentIds || !childIds || childIds.length === 0) return false;
    var parentSet = _sourceIdSet(parentIds);
    for (var i = 0; i < childIds.length; i++) {
        if (!parentSet[String(childIds[i])]) return false;
    }
    return true;
}

function _resolveObjectPlanDuplicateInlineCompletePngTextOwners(objectPlans, sourceById) {
    var plans = objectPlans || [];
    var ownersBySlot = {};
    for (var i = 0; i < plans.length; i++) {
        var plan = plans[i];
        if (!_objectPlanIsInlineCompletePngTextOwner(plan)) continue;
        var slotKey = _objectPlanInlineCompletePngTextOwnerSlotKey(plan);
        if (!slotKey) continue;
        if (!ownersBySlot[slotKey]) ownersBySlot[slotKey] = [];
        ownersBySlot[slotKey].push(plan);
    }

    var duplicateSlotCount = 0;
    var droppedPlanCount = 0;
    var droppedObjectPlanIds = [];
    for (var key in ownersBySlot) {
        if (!ownersBySlot.hasOwnProperty(key)) continue;
        var owners = ownersBySlot[key];
        if (!owners || owners.length < 2) continue;
        duplicateSlotCount++;
        var canonical = owners[0];
        for (var oi = 1; oi < owners.length; oi++) {
            if (_compareObjectPlanInlineCompletePngTextOwnerPriority(
                    owners[oi], canonical, sourceById) < 0) {
                canonical = owners[oi];
            }
        }
        for (var di = 0; di < owners.length; di++) {
            var duplicate = owners[di];
            if (duplicate === canonical) continue;
            duplicate.hiddenVisualSourceObjectIds = _sourceIdsUnion(
                    duplicate.hiddenVisualSourceObjectIds || [],
                    _sourceIdsUnion(
                            duplicate.visualSourceObjectIds || [],
                            duplicate.exportSourceObjectIds || []));
            duplicate.visualSourceObjectIds = [];
            duplicate.exportSourceObjectIds = [];
            duplicate.textAction = "DROP_TEXT";
            duplicate.visualAction = "DROP_VISUAL";
            duplicate.materialization = "HWPX_TEXT";
            duplicate.duplicateInlineCompletePngTextOwnerResolution =
                    "DROPPED_DUPLICATE_INLINE_COMPLETE_PNG_TEXT_OWNER";
            duplicate.duplicateInlineCompletePngTextOwnerResolutionReason =
                    "same_inline_text_slot_already_owned_by_canonical_complete_png_with_stronger_visual_evidence";
            duplicate.duplicateInlineCompletePngCanonicalObjectPlanId =
                    canonical.objectPlanId || null;
            duplicate.reason = String(duplicate.reason || "")
                    + ":duplicate_inline_complete_png_text_owner_resolved";
            droppedPlanCount++;
            droppedObjectPlanIds.push(
                    duplicate.objectPlanId || duplicate.bundleId || duplicate.candidateId
                            || ("duplicate.inline.complete.png.plan." + String(di)));
        }
    }

    return {
        summary: {
            duplicateSlotCount: duplicateSlotCount,
            droppedPlanCount: droppedPlanCount,
            droppedObjectPlanIds: droppedObjectPlanIds
        }
    };
}

function _objectPlanIsInlineCompletePngTextOwner(plan) {
    if (!plan) return false;
    if (plan.passId !== "pass.inline_objects") return false;
    if (plan.placement !== "INLINE" || plan.coordinateSpace !== "STORY_FLOW") return false;
    if (plan.textAction !== "OWNED_BY_PNG") return false;
    if (plan.visualAction !== "PLACE_INLINE_PNG") return false;
    if (plan.materialization !== "COMPLETE_PNG") return false;
    if (!plan.ownedTextFrameIds || plan.ownedTextFrameIds.length === 0) return false;
    return true;
}

function _objectPlanInlineCompletePngTextOwnerSlotKey(plan) {
    if (!plan || !plan.ownedTextFrameIds || plan.ownedTextFrameIds.length === 0) return "";
    return [
        String(plan.pageIndex),
        "INLINE_COMPLETE_PNG_TEXT_SLOT",
        _sourceSetKey(plan.ownedTextFrameIds || [])
    ].join("|");
}

function _compareObjectPlanInlineCompletePngTextOwnerPriority(a, b, sourceById) {
    var scoreA = _objectPlanInlineCompletePngTextOwnerPriority(a, sourceById);
    var scoreB = _objectPlanInlineCompletePngTextOwnerPriority(b, sourceById);
    if (scoreA !== scoreB) return scoreB - scoreA;
    var aId = a && a.objectPlanId ? String(a.objectPlanId) : "";
    var bId = b && b.objectPlanId ? String(b.objectPlanId) : "";
    if (aId < bId) return -1;
    if (aId > bId) return 1;
    return 0;
}

function _objectPlanInlineCompletePngTextOwnerPriority(plan, sourceById) {
    if (!plan) return 0;
    var score = 0;
    var owned = _sourceIdSet(plan.ownedTextFrameIds || []);
    var nonOwnedVisualCount = 0;
    var directVisibleMaterialCount = 0;
    var visualIds = _sourceIdsUnion(
            plan.visualSourceObjectIds || [],
            plan.exportSourceObjectIds || []);
    for (var i = 0; i < visualIds.length; i++) {
        var id = String(visualIds[i]);
        if (owned[id]) continue;
        nonOwnedVisualCount++;
        var src = sourceById ? sourceById[id] : null;
        if (src && (src.hasPlacedVisual === true
                || src.hasVisibleFill === true
                || src.hasVisibleStroke === true)) {
            directVisibleMaterialCount++;
        }
    }
    score += nonOwnedVisualCount * 1000;
    score += directVisibleMaterialCount * 200;
    if (plan.ownershipSlot === "SHELL_SLOT") score += 120;
    if (plan.policyLayer === "DECORATION" || plan.visualLayer === "LABEL_BACKDROP") score += 80;
    if (plan.clusterRelation === "EXACT_SOURCE_CLUSTER") score += 40;
    score += (plan.sourceObjectIds ? plan.sourceObjectIds.length : 0);
    return score;
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
    if (_objectPlanIsInlineVisibleTextFrameShellVisualOwner(plan)) {
        score += _objectPlanInlineVisibleTextFrameShellPriority(plan);
    }
    if (plan.passId === "pass.image_textless_groups"
            && plan.ownershipSlot === "CONTENT_VISUAL_SLOT") {
        score += 520;
    }
    if (plan.visualAction === "PLACE_TEXT_SHELL") score += 220;
    if (plan.ownershipSlot === "SHELL_SLOT") score += 160;
    if (plan.slotRole === "direct_child_shell_slot") score += 180;
    if (plan.compositeRole === "direct_child_shell_slot") score += 160;
    if (plan.slotRole === "shell_slot_only" || plan.mode === "SLOT_ONLY") score += 80;
    if (plan.passId === "pass.image_placed_frames") score += 120;
    if (plan.passId === "pass.inline_objects") score += 100;
    if (plan.passId === "pass.decoration_groups") score += 50;
    if (plan.passId === "pass.page_textless_graphic_groups") score += 20;
    if (plan.passId === "pass.master_page_graphics") score += 15;
    if (plan.materialization === "COMPLETE_PNG") score += 40;
    if (plan.materialization === "NATIVE_SOURCE_SHAPE") score += 35;
    if (plan.materialization === "EXTRACTED_PNG_VECTOR") score += 20;
    if (plan.ownedTextFrameIds && plan.ownedTextFrameIds.length > 0) score += 10;
    return score;
}

function _objectPlanIsInlineVisibleTextFrameShellVisualOwner(plan) {
    if (!plan) return false;
    if (plan.visualAction !== "PLACE_TEXT_SHELL") return false;
    if (plan.placement !== "INLINE" || plan.coordinateSpace !== "STORY_FLOW") return false;
    if (plan.ownershipSlot !== "SHELL_SLOT") return false;
    if (plan.slotRole !== "inline_text_frame_shell_slot"
            && plan.compositeRole !== "inline_visible_text_frame_shell"
            && plan.slotRole !== "inline_editable_text_shell_composite"
            && plan.compositeRole !== "inline_editable_text_shell_composite") {
        return false;
    }
    return (plan.styleSourceObjectIds && plan.styleSourceObjectIds.length > 0)
            || (plan.visualSourceObjectIds && plan.visualSourceObjectIds.length > 0);
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
        if (plan.visualLayer === "PAGE_BACKGROUND"
                || plan.slotRole === "background_shell_slot") {
            return true;
        }
        if (plan.visualAction === "PLACE_TEXT_SHELL"
                && plan.ownershipSlot === "SHELL_SLOT"
                && plan.visualLayer === "LABEL_BACKDROP") {
            return true;
        }
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
        if (plan.visualSourceObjectIds.length === 0 && pruned === true) {
            plan.visualAction = "DROP_VISUAL";
            plan.disabled = true;
            plan.executable = false;
            plan.contractStatus = "READY_FOR_STAGE1_IMPORT";
            plan.rawClippedImageVisualSourceResolution =
                    "DROPPED_RAW_IMAGE_VISUAL_SOURCE_WITH_HIDDEN_CLIP_PARENT";
            plan.rawClippedImageVisualSourceResolutionReason =
                    "clipped Image leaf ids are provenance; the clip-carrying parent is already hidden by another visible slot owner";
            plan.reason = String(plan.reason || "") + ":raw_clipped_image_visual_source_dropped";
            mutatedPlanCount++;
            mutatedPlanIds.push(plan.objectPlanId || plan.bundleId || plan.candidateId || ("plan.index." + pi));
            continue;
        }
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

function _applyObjectPlanPageBackgroundPlaneMaterialization(objectPlans, sourceById) {
    var plans = objectPlans || [];
    var byComponent = {};
    var existingByComponent = {};
    var created = [];
    var absorbed = [];
    var normalizedExisting = [];
    var protectedTextlessGroupSlots = [];
    var protectedTextlessGroupSourceIdsByPage = {};

    function normalizePageBackgroundPlane(plan) {
        if (!plan || !_objectPlanHasPlaneExportableVisualSource(plan)) return false;
        var shouldUseMasterGraphicsPass =
                String(plan.passId || "") === "pass.master_page_graphics"
                || String(plan.planPassId || "") === "pass.master_page_graphics"
                || String(plan.candidateId || "").indexOf(".pass.master_page_graphics.") >= 0;
        if (shouldUseMasterGraphicsPass) {
            plan.passId = "pass.master_page_graphics";
            plan.candidatePurpose = "MASTER_CANDIDATE";
            plan.unit = "MASTER_ITEM";
            plan.zOrder = OBJECT_PLAN_MASTER_PLANE_Z_ORDER;
        } else {
            plan.passId = "pass.page_textless_graphic_groups";
            plan.candidatePurpose = "SHELL_CANDIDATE";
            plan.unit = "PAGE_GRAPHIC_GROUP";
            plan.zOrder = OBJECT_PLAN_PAGE_BACKGROUND_PLANE_Z_ORDER;
        }
        plan.sourceSetId = _sourceSetId(plan.sourceObjectIds || []);
        plan.sourceRootSetId = _sourceSetId(plan.sourceRootObjectIds || plan.sourceObjectIds || []);
        plan.clusterSourceSetId = _sourceSetId(plan.clusterSourceObjectIds || plan.sourceObjectIds || []);
        if ((!plan.visualSourceObjectIds || plan.visualSourceObjectIds.length === 0)
                && plan.exportSourceObjectIds && plan.exportSourceObjectIds.length > 0) {
            plan.visualSourceObjectIds = _internSourceSetIds(plan.exportSourceObjectIds);
        }
        plan.visualSourceSetId = _sourceSetId(plan.visualSourceObjectIds || plan.exportSourceObjectIds || []);
        plan.exportSourceSetId = _sourceSetId(plan.exportSourceObjectIds || plan.visualSourceObjectIds || []);
        plan.hiddenSourceSetId = _sourceSetId(plan.hiddenVisualSourceObjectIds || []);
        plan.excludedInlineSourceObjectIds = _internSourceSetIds(plan.excludedInlineSourceObjectIds || []);
        plan.kind = "PAGE_BACKGROUND_PLANE";
        plan.mode = "TEXTLESS_CANDIDATE";
        plan.compositeRole = "page_background_plane";
        plan.slotRole = "page_background_plane";
        plan.materialization = "EXTRACTED_PNG_VECTOR";
        plan.textAction = "DROP_TEXT";
        plan.visualAction = "PLACE_TEXT_SHELL";
        plan.placement = "FLOATING";
        plan.coordinateSpace = "PAGE";
        plan.visualLayer = "PAGE_BACKGROUND";
        plan.ownershipSlot = "SHELL_SLOT";
        plan.policyLayer = "BACKGROUND";
        plan.clusterRelation = plan.clusterRelation || "PAGE_BACKGROUND_PLANE";
        plan.migrationStatus = "READY_PAGE_BACKGROUND_PLANE";
        plan.migrationBlocker = "NONE";
        plan.executable = true;
        plan.required = true;
        plan.reason = String(plan.reason || "stage1_page_background_plane")
                + ":normalized_explicit_page_background_plane"
                + (shouldUseMasterGraphicsPass ? ":master_plane_bottom_depth" : "");
        return true;
    }

    function isProtectedTextlessGroupSlot(plan) {
        if (!plan) return false;
        if (plan.absorbedByObjectPlanId) return false;
        if (String(plan.slotRole || "") !== "textless_group_visual_slot"
                && String(plan.compositeRole || "") !== "textless_group_visual_slot") {
            return false;
        }
        if (plan.placement !== "FLOATING" || plan.coordinateSpace !== "PAGE") return false;
        if (plan.layoutOnlyInlineSlot === true
                || plan.sourceInlineFlow === true
                || plan.pagePositionedAnchoredSource === true
                || plan.inlineCompositeLayoutDescendant === true
                || plan.inlineAnchorSourceObjectId !== null
                        && plan.inlineAnchorSourceObjectId !== undefined) {
            return false;
        }
        if (plan.visualAction !== "PLACE_TEXT_SHELL"
                && plan.visualAction !== "PLACE_FLOATING_PNG") {
            return false;
        }
        if (plan.materialization !== "EXTRACTED_PNG_VECTOR"
                && plan.materialization !== "COMPLETE_PNG") {
            return false;
        }
        return !!((plan.visualSourceObjectIds && plan.visualSourceObjectIds.length > 0)
                || (plan.exportSourceObjectIds && plan.exportSourceObjectIds.length > 0));
    }

    function _objectPlanSourceSetHasPlacedMedia(ids) {
        for (var i = 0; ids && i < ids.length; i++) {
            var src = sourceById ? sourceById[String(ids[i])] : null;
            if (!src) continue;
            var kind = String(src.kind || src.type || src.itemType || "");
            if (kind === "Image" || kind === "PDF" || kind === "EPS") return true;
            if (src.hasPlacedVisual === true) return true;
        }
        return false;
    }

    function isProtectedPlacedBackgroundShellSlot(plan) {
        if (!plan) return false;
        if (plan.absorbedByObjectPlanId) return false;
        if (String(plan.passId || "") !== "pass.decoration_groups") return false;
        if (String(plan.ownershipSlot || "") !== "SHELL_SLOT") return false;
        if (String(plan.slotRole || "") !== "background_shell_slot"
                && String(plan.compositeRole || "") !== "background_vector_source"
                && String(plan.visualLayer || "") !== "PAGE_BACKGROUND") {
            return false;
        }
        if (plan.placement !== "FLOATING" || plan.coordinateSpace !== "PAGE") return false;
        if (plan.visualAction !== "PLACE_TEXT_SHELL"
                && plan.visualAction !== "PLACE_FLOATING_PNG") {
            return false;
        }
        if (plan.materialization !== "EXTRACTED_PNG_VECTOR"
                && plan.materialization !== "COMPLETE_PNG") {
            return false;
        }
        var candidateIds = _sourceIdsUnion(
                _sourceIdsUnion(plan.sourceObjectIds || [], plan.visualSourceObjectIds || []),
                plan.exportSourceObjectIds || []);
        return _objectPlanSourceSetHasPlacedMedia(candidateIds);
    }

    function protectPageBackgroundAbsorptionSlot(plan, reason) {
        var pageKey = String(plan.pageIndex);
        var protectedIds = _sourceIdsUnion(
                _sourceIdsUnion(plan.visualSourceObjectIds || [], plan.exportSourceObjectIds || []),
                plan.sourceObjectIds || []);
        if (!protectedTextlessGroupSourceIdsByPage[pageKey]) {
            protectedTextlessGroupSourceIdsByPage[pageKey] = [];
        }
        protectedTextlessGroupSourceIdsByPage[pageKey] = _sourceIdsUnion(
                protectedTextlessGroupSourceIdsByPage[pageKey],
                protectedIds);
        plan.pageBackgroundPlaneProtected = true;
        plan.pageBackgroundPlaneProtectedReason = reason;
        protectedTextlessGroupSlots.push({
            objectPlanId: plan.objectPlanId || null,
            candidateId: plan.candidateId || null,
            pageIndex: plan.pageIndex,
            ownershipSlot: plan.ownershipSlot || null,
            slotRole: plan.slotRole || null,
            compositeRole: plan.compositeRole || null,
            reason: reason,
            protectedSourceObjectIds: protectedIds
        });
    }

    for (var pre = 0; pre < plans.length; pre++) {
        if (isProtectedTextlessGroupSlot(plans[pre])) {
            protectPageBackgroundAbsorptionSlot(
                    plans[pre],
                    "textless_group_visual_slot_is_visible_slot_owner_before_page_background_plane");
        } else if (isProtectedPlacedBackgroundShellSlot(plans[pre])) {
            protectPageBackgroundAbsorptionSlot(
                    plans[pre],
                    "placed_background_shell_slot_is_visible_slot_owner_before_page_background_plane");
        }
    }

    for (var i = 0; i < plans.length; i++) {
        var plan = plans[i];
        if (plan && (plan.slotRole === "page_background_plane"
                || plan.compositeRole === "page_background_plane"
                || plan.visualAction === "PLACE_PAGE_BACKGROUND_PNG")) {
            if (normalizePageBackgroundPlane(plan)) {
                normalizedExisting.push(plan.objectPlanId || plan.bundleId || plan.candidateId || ("plan.index." + i));
            }
            var existingPageKey = String(plan.pageIndex);
            var existingComponentKey = _objectPlanPageBackgroundComponentKey(plan, sourceById);
            existingByComponent[existingPageKey + "|" + existingComponentKey] = plan;
            if (!byComponent[existingPageKey + "|" + existingComponentKey]) {
                byComponent[existingPageKey + "|" + existingComponentKey] = {
                    pageKey: existingPageKey,
                    componentKey: existingComponentKey,
                    members: []
                };
            }
            byComponent[existingPageKey + "|" + existingComponentKey].members.push(plan);
            continue;
        }
        if (isProtectedTextlessGroupSlot(plan) || isProtectedPlacedBackgroundShellSlot(plan)) continue;
        if (!_objectPlanEligibleForPageBackgroundPlane(plan)) continue;
        var pageKey = String(plan.pageIndex);
        var componentKey = _objectPlanPageBackgroundComponentKey(plan, sourceById);
        var groupKey = pageKey + "|" + componentKey;
        if (!byComponent[groupKey]) {
            byComponent[groupKey] = {
                pageKey: pageKey,
                componentKey: componentKey,
                members: []
            };
        }
        byComponent[groupKey].members.push(plan);
    }

    var existingUseCountByPage = {};
    for (var key in byComponent) {
        if (!byComponent.hasOwnProperty(key)) continue;
        var group = byComponent[key];
        var members = group.members;
        if (!members || members.length < 1) continue;
        var reusableExisting = existingByComponent[key] || null;
        var sourceMembers = members;
        members.sort(function(a, b) {
            var az = a.zOrder !== undefined && a.zOrder !== null ? Number(a.zOrder) : 0;
            var bz = b.zOrder !== undefined && b.zOrder !== null ? Number(b.zOrder) : 0;
            if (az !== bz) return az - bz;
            var ai = a.objectPlanId || a.candidateId || "";
            var bi = b.objectPlanId || b.candidateId || "";
            return String(ai) < String(bi) ? -1 : (String(ai) > String(bi) ? 1 : 0);
        });

        var sourceObjectIds = _objectPlanUnionPlanIds(sourceMembers, "sourceObjectIds");
        var sourceRootObjectIds = _objectPlanUnionPlanIds(sourceMembers, "sourceRootObjectIds");
        var visualSourceObjectIds = _objectPlanUnionPlanIds(sourceMembers, "visualSourceObjectIds");
        var exportSourceObjectIds = _objectPlanUnionPlanIds(sourceMembers, "exportSourceObjectIds");
        var hiddenVisualSourceObjectIds = _objectPlanUnionPlanIds(sourceMembers, "hiddenVisualSourceObjectIds");
        var hiddenTextFrameIds = _objectPlanUnionPlanIds(sourceMembers, "hiddenTextFrameIds");
        var excludedInlineSourceObjectIds = _objectPlanUnionPlanIds(sourceMembers, "excludedInlineSourceObjectIds");
        var protectedTextlessGroupSourceIds =
                protectedTextlessGroupSourceIdsByPage[group.pageKey] || [];
        exportSourceObjectIds = _objectPlanPromotePageRootVisibleExportSources(
                sourceObjectIds, visualSourceObjectIds, exportSourceObjectIds, sourceById);
        exportSourceObjectIds = _objectPlanCarrierExportSourceIds(
                exportSourceObjectIds, visualSourceObjectIds, sourceById);
        exportSourceObjectIds = _objectPlanPromotePageRootPlacedImageAtoms(
                sourceObjectIds,
                visualSourceObjectIds,
                exportSourceObjectIds,
                excludedInlineSourceObjectIds,
                sourceById);
        sourceObjectIds = _objectPlanSourceIdsMinus(
                sourceObjectIds, protectedTextlessGroupSourceIds);
        sourceRootObjectIds = _objectPlanSourceIdsMinus(
                sourceRootObjectIds, protectedTextlessGroupSourceIds);
        visualSourceObjectIds = _objectPlanSourceIdsMinus(
                visualSourceObjectIds, protectedTextlessGroupSourceIds);
        exportSourceObjectIds = _objectPlanSourceIdsMinus(
                exportSourceObjectIds, protectedTextlessGroupSourceIds);
        hiddenVisualSourceObjectIds = _sourceIdsUnion(
                hiddenVisualSourceObjectIds, protectedTextlessGroupSourceIds);
        hiddenTextFrameIds = _sourceIdsUnion(
                hiddenTextFrameIds, _objectPlanUnionPlanIds(sourceMembers, "ownedTextFrameIds"));
        hiddenTextFrameIds = _sourceIdsUnion(
                hiddenTextFrameIds, _objectPlanUnionPlanIds(sourceMembers, "editableTextFrameIds"));
        var visibleTextFrameShellIds = _objectPlanVisibleTextFrameShellIds(sourceObjectIds, sourceById);
        hiddenTextFrameIds = _sourceIdsUnion(hiddenTextFrameIds, visibleTextFrameShellIds);
        exportSourceObjectIds = _sourceIdsUnion(exportSourceObjectIds, visibleTextFrameShellIds);
        visualSourceObjectIds = _sourceIdsUnion(visualSourceObjectIds, visibleTextFrameShellIds);
        hiddenVisualSourceObjectIds = _sourceIdsUnion(
                hiddenVisualSourceObjectIds,
                _objectPlanSourceIdsMinus(hiddenTextFrameIds, visibleTextFrameShellIds));
        if (exportSourceObjectIds.length === 0) exportSourceObjectIds = sourceRootObjectIds.slice(0);
        exportSourceObjectIds = _objectPlanPruneExportSourcesWithHiddenDescendants(
                exportSourceObjectIds, hiddenVisualSourceObjectIds, sourceById);
        if (visualSourceObjectIds.length === 0) visualSourceObjectIds = exportSourceObjectIds.slice(0);
        if (sourceObjectIds.length === 0) sourceObjectIds = visualSourceObjectIds.slice(0);
        var nonExportSourceObjectIds = _objectPlanSourceIdsMinus(sourceObjectIds, exportSourceObjectIds);
        hiddenVisualSourceObjectIds = _sourceIdsUnion(
                hiddenVisualSourceObjectIds,
                _objectPlanSourceIdsMinus(nonExportSourceObjectIds, excludedInlineSourceObjectIds));
        hiddenVisualSourceObjectIds = _objectPlanSourceIdsMinus(
                hiddenVisualSourceObjectIds, exportSourceObjectIds);
        hiddenVisualSourceObjectIds = _objectPlanSourceIdsMinus(
                hiddenVisualSourceObjectIds, visibleTextFrameShellIds);
        hiddenVisualSourceObjectIds = _objectPlanHiddenSourceIdsMinusExportedPlacedChildren(
                hiddenVisualSourceObjectIds, exportSourceObjectIds, sourceById);
        hiddenVisualSourceObjectIds = _objectPlanHiddenSourceIdsMinusExportAncestors(
                hiddenVisualSourceObjectIds, exportSourceObjectIds, sourceById);
        var hiddenPlacedVisualIds = _objectPlanHiddenPlacedVisualLeafIds(
                hiddenVisualSourceObjectIds, excludedInlineSourceObjectIds, sourceById);

        var pageIndex = Number(group.pageKey);
        var componentIdPart = _objectPlanSafeIdPart(group.componentKey);
        var plane = reusableExisting;
        var primarySourceObjectId = plane && plane.primarySourceObjectId !== undefined
                && plane.primarySourceObjectId !== null
                ? plane.primarySourceObjectId
                : (members[0].primarySourceObjectId !== undefined
                        && members[0].primarySourceObjectId !== null
                        ? members[0].primarySourceObjectId
                        : (exportSourceObjectIds.length > 0 ? exportSourceObjectIds[0] : null));
        var objectPlanId = plane && plane.objectPlanId
                ? plane.objectPlanId
                : "object-plan.page-background-plane." + String(pageIndex) + "." + componentIdPart;
        var candidateId = plane && plane.candidateId
                ? plane.candidateId
                : "cand.pass.page_textless_graphic_groups.page." + String(pageIndex)
                        + "." + componentIdPart
                        + ".page_background_plane.n" + String(exportSourceObjectIds.length)
                        + ".h" + _sourceSetId(exportSourceObjectIds);
        if (plane) {
            var existingPlaneIsMaster = _objectPlanIsMasterPageVisualPlane(plane);
            plane.sourceSetId = _sourceSetId(sourceObjectIds);
            plane.sourceRootSetId = _sourceSetId(sourceRootObjectIds);
            plane.clusterSourceSetId = _sourceSetId(sourceObjectIds);
            plane.visualSourceSetId = _sourceSetId(visualSourceObjectIds);
            plane.exportSourceSetId = _sourceSetId(exportSourceObjectIds);
            plane.hiddenSourceSetId = _sourceSetId(hiddenVisualSourceObjectIds);
            plane.hiddenTextFrameSetId = _sourceSetId(hiddenTextFrameIds);
            plane.excludedInlineSourceObjectIds = _internSourceSetIds(excludedInlineSourceObjectIds);
            plane.sourceObjectIds = _internSourceSetIds(sourceObjectIds);
            plane.sourceRootObjectIds = _internSourceSetIds(sourceRootObjectIds);
            plane.clusterSourceObjectIds = _internSourceSetIds(sourceObjectIds);
            plane.visualSourceObjectIds = _internSourceSetIds(visualSourceObjectIds);
            plane.exportSourceObjectIds = _internSourceSetIds(exportSourceObjectIds);
            plane.hiddenVisualSourceObjectIds = _internSourceSetIds(hiddenVisualSourceObjectIds);
            plane.hiddenTextFrameIds = _internSourceSetIds(hiddenTextFrameIds);
            plane.pageBackgroundHiddenPlacedVisualIds = _internSourceSetIds(hiddenPlacedVisualIds);
            plane.pageBackgroundHiddenPlacedVisualWarning = hiddenPlacedVisualIds.length > 0
                    ? "page_background_hidden_placed_visual"
                    : null;
            plane.ownedTextFrameIds = [];
            plane.kind = "PAGE_BACKGROUND_PLANE";
            plane.mode = "TEXTLESS_CANDIDATE";
            plane.candidatePurpose = existingPlaneIsMaster ? "MASTER_CANDIDATE" : "SHELL_CANDIDATE";
            plane.compositeRole = "page_background_plane";
            plane.slotRole = "page_background_plane";
            plane.primarySourceObjectId = primarySourceObjectId;
            plane.materialization = "EXTRACTED_PNG_VECTOR";
            plane.textAction = "DROP_TEXT";
            plane.visualAction = "PLACE_TEXT_SHELL";
            plane.placement = "FLOATING";
            plane.coordinateSpace = "PAGE";
            plane.visualLayer = "PAGE_BACKGROUND";
            plane.zOrder = existingPlaneIsMaster
                    ? OBJECT_PLAN_MASTER_PLANE_Z_ORDER
                    : OBJECT_PLAN_PAGE_BACKGROUND_PLANE_Z_ORDER;
            plane.reason = String(plane.reason || "stage1_page_background_plane")
                    + ":expanded_page_background_plane_sources"
                    + (existingPlaneIsMaster ? ":master_plane_bottom_depth" : "");
            plane.bounds = _objectPlanUnionBounds(sourceMembers);
            plane.ownershipSlot = "SHELL_SLOT";
            plane.policyLayer = "BACKGROUND";
            plane.clusterRelation = "PAGE_BACKGROUND_PLANE";
            plane.pageBackgroundComponentKey = group.componentKey;
            plane.migrationStatus = "READY_PAGE_BACKGROUND_PLANE";
            plane.migrationBlocker = "NONE";
            plane.migrationBlockerDetail = {
                absorbedObjectPlanIds: _objectPlanMemberIds(members)
            };
            plane.executable = true;
            plane.required = true;
            created.push(objectPlanId);
        } else {
            var planeObjectPlanId = objectPlanId;
            var planeCandidateId = candidateId;
            plane = {
                objectPlanId: planeObjectPlanId,
                bundleId: "bundle.page-background-plane." + String(pageIndex) + "." + componentIdPart,
                candidateId: planeCandidateId,
                passId: "pass.page_textless_graphic_groups",
                pageIndex: pageIndex,
                kind: "PAGE_BACKGROUND_PLANE",
                mode: "TEXTLESS_CANDIDATE",
                candidatePurpose: "SHELL_CANDIDATE",
                compositeRole: "page_background_plane",
                slotRole: "page_background_plane",
                layoutOnlyInlineSlot: false,
                sourceInlineFlow: false,
                inlineCompositeLayoutDescendant: false,
                inlineAnchorSourceObjectId: null,
                inlineSourceTreeClosed: false,
                inlineFlowSourceObjectIds: [],
                connectorDecorationVisual: false,
                primarySourceObjectId: primarySourceObjectId,
                sourceSetId: _sourceSetId(sourceObjectIds),
                sourceRootSetId: _sourceSetId(sourceRootObjectIds),
                clusterSourceSetId: _sourceSetId(sourceObjectIds),
                visualSourceSetId: _sourceSetId(visualSourceObjectIds),
                exportSourceSetId: _sourceSetId(exportSourceObjectIds),
                hiddenSourceSetId: _sourceSetId(hiddenVisualSourceObjectIds),
                hiddenTextFrameSetId: _sourceSetId(hiddenTextFrameIds),
                ownedByNativeShellSourceObjectIds: [],
                sourceObjectIds: _internSourceSetIds(sourceObjectIds),
                sourceRootObjectIds: _internSourceSetIds(sourceRootObjectIds),
                clusterSourceObjectIds: _internSourceSetIds(sourceObjectIds),
                clusterKindCounts: {},
                omittedClusterSourceObjectIds: [],
                omittedClusterKindCounts: {},
                clusterHasEditableText: false,
                clusterHasTextFrame: false,
                clusterHasPlacedContent: false,
                clusterHasVisualSource: true,
                visualSourceObjectIds: _internSourceSetIds(visualSourceObjectIds),
                styleSourceObjectIds: [],
                ownedTextFrameIds: [],
                exportSourceObjectIds: _internSourceSetIds(exportSourceObjectIds),
                exportTargetObjectId: null,
                atomicExportTargetObjectId: null,
                atomicExportTargetObjectIds: [],
                atomicTextlessVectorContent: false,
                atomicContentVisualSlot: false,
                hiddenVisualSourceObjectIds: _internSourceSetIds(hiddenVisualSourceObjectIds),
                hiddenTextFrameIds: _internSourceSetIds(hiddenTextFrameIds),
                pageBackgroundHiddenPlacedVisualIds: _internSourceSetIds(hiddenPlacedVisualIds),
                pageBackgroundHiddenPlacedVisualWarning: hiddenPlacedVisualIds.length > 0
                        ? "page_background_hidden_placed_visual"
                        : null,
                excludedInlineSourceObjectIds: _internSourceSetIds(excludedInlineSourceObjectIds),
                materialization: "EXTRACTED_PNG_VECTOR",
                textAction: "DROP_TEXT",
                visualAction: "PLACE_TEXT_SHELL",
                placement: "FLOATING",
                coordinateSpace: "PAGE",
                visualLayer: "PAGE_BACKGROUND",
                zOrder: OBJECT_PLAN_PAGE_BACKGROUND_PLANE_Z_ORDER,
                reason: "stage1_page_background_plane_from_objectplans",
                bounds: _objectPlanUnionBounds(sourceMembers),
                renderSourceBounds: null,
                cropSourceBounds: null,
                ownershipSlot: "SHELL_SLOT",
                policyLayer: "BACKGROUND",
                clusterRelation: "PAGE_BACKGROUND_PLANE",
                pageBackgroundComponentKey: group.componentKey,
                migrationStatus: "READY_PAGE_BACKGROUND_PLANE",
                migrationBlocker: "NONE",
                migrationBlockerDetail: {
                    absorbedObjectPlanIds: _objectPlanMemberIds(members)
                },
                executable: true,
                required: true
            };
            plans.push(plane);
            created.push(planeObjectPlanId);
        }

        for (var mi = 0; mi < members.length; mi++) {
            var member = members[mi];
            if (member === plane) continue;
            member.absorbedByObjectPlanId = objectPlanId;
            member.absorbedByMaterialization = "PAGE_PLANE_PNG";
            member.visualAction = "DROP_VISUAL";
            member.materialization = "HWPX_TEXT";
            if (member.textAction === "OWNED_BY_HWPX_TEXT") {
                member.executable = true;
                member.required = true;
            } else {
                member.executable = false;
                member.required = false;
            }
            member.reason = String(member.reason || "") + ":absorbed_by_page_background_plane";
            absorbed.push(member.objectPlanId || member.candidateId || ("page-background-member." + String(mi)));
        }
    }

    return {
        summary: {
            createdPlaneCount: created.length,
            absorbedPlanCount: absorbed.length,
            createdObjectPlanIds: created,
            absorbedObjectPlanIds: absorbed
            ,
            normalizedExistingPlaneCount: normalizedExisting.length,
            normalizedExistingObjectPlanIds: normalizedExisting,
            protectedTextlessGroupSlotCount: protectedTextlessGroupSlots.length,
            protectedTextlessGroupSlots: protectedTextlessGroupSlots
        }
    };
}

function _objectPlanPageBackgroundComponentKey(plan, sourceById) {
    if (!plan) return "empty";
    var pass = String(plan.passId || "pass.unknown");
    if (pass === "pass.master_page_graphics"
            || String(plan.planPassId || "") === "pass.master_page_graphics"
            || String(plan.candidateId || "").indexOf(".pass.master_page_graphics.") >= 0) {
        var masterRoots = _sortedNumericIds(plan.sourceRootObjectIds || plan.sourceObjectIds || []);
        return pass + ".master." + (masterRoots.length > 0 ? _sourceSetKey(masterRoots) : "page");
    }
    return "page-root-textless-plane";
}

function _objectPlanSafeIdPart(value) {
    var raw = String(value || "component");
    var out = raw.replace(/[^A-Za-z0-9_.-]+/g, "_");
    if (out.length > 120) {
        out = out.substring(0, 96) + ".h" + _sourceSetId([raw.length]);
    }
    return out || "component";
}

function _objectPlanEligibleForPageBackgroundPlane(plan) {
    if (!plan) return false;
    if (plan.absorbedByObjectPlanId) return false;
    if (plan.compositeRole === "table_carrier_textless_shell"
            || plan.slotRole === "table_textless_shell_slot") {
        return false;
    }
    if (!_objectPlanHasPlaneExportableVisualSource(plan)) return false;
    if (plan.slotRole === "page_background_plane"
            || plan.compositeRole === "page_background_plane"
            || plan.visualAction === "PLACE_PAGE_BACKGROUND_PNG") return false;

    if (plan.placement !== "FLOATING" || plan.coordinateSpace !== "PAGE") return false;
    if (plan.layoutOnlyInlineSlot === true
            || plan.sourceInlineFlow === true
            || plan.pagePositionedAnchoredSource === true
            || plan.inlineCompositeLayoutDescendant === true
            || plan.inlineAnchorSourceObjectId !== null
                    && plan.inlineAnchorSourceObjectId !== undefined) return false;

    if (!_objectPlanCanJoinPageRootTextlessPlane(plan)) return false;
    if (plan.textAction === "OWNED_BY_PNG") return false;
    if (plan.completePngTextAllowed === true) return false;
    if (plan.materialization === "COMPLETE_PNG") return false;
    if (_objectPlanUsesAmbiguousSingleRootSlotOnlyExport(plan)) return false;

    if (plan.visualAction !== "PLACE_FLOATING_PNG"
            && plan.visualAction !== "PLACE_TEXT_SHELL"
            && plan.visualAction !== "DROP_VISUAL") return false;
    if (plan.materialization !== "EXTRACTED_PNG_VECTOR"
            && plan.materialization !== "TEXTLESS_VISUAL_FRAGMENT"
            && plan.materialization !== "NATIVE_SOURCE_SHAPE"
            && plan.materialization !== "HWPX_TEXT") return false;

    if (!plan.exportSourceObjectIds || plan.exportSourceObjectIds.length === 0) {
        if (!plan.visualSourceObjectIds || plan.visualSourceObjectIds.length === 0) return false;
    }
    return true;
}

function _objectPlanCanJoinPageRootTextlessPlane(plan) {
    if (!plan) return false;
    var ownershipSlot = String(plan.ownershipSlot || "");
    var slotRole = String(plan.slotRole || "").toLowerCase();

    if (ownershipSlot === "TEXT_SLOT" || slotRole === "text_slot") return false;
    if (ownershipSlot === "TABLE_STYLE_SLOT"
            || slotRole === "table_style_slot"
            || plan.visualAction === "PLACE_TABLE_STYLE") return false;
    if (plan.materialization === "HWPX_TABLE_STYLE") return false;
    return true;
}

function _objectPlanHasPlaneExportableVisualSource(plan) {
    if (!plan) return false;
    if (plan.visualAction === "PLACE_TABLE_STYLE") return false;
    if (plan.styleSourceObjectIds && plan.styleSourceObjectIds.length > 0
            && (!plan.visualSourceObjectIds || plan.visualSourceObjectIds.length === 0)
            && (!plan.exportSourceObjectIds || plan.exportSourceObjectIds.length === 0)) {
        return false;
    }
    return !!((plan.exportSourceObjectIds && plan.exportSourceObjectIds.length > 0)
            || (plan.visualSourceObjectIds && plan.visualSourceObjectIds.length > 0));
}

function _objectPlanUnionPlanIds(plans, field) {
    var ids = [];
    var seen = {};
    for (var i = 0; plans && i < plans.length; i++) {
        var values = plans[i] && plans[i][field] ? plans[i][field] : [];
        for (var v = 0; v < values.length; v++) {
            var id = values[v];
            if (id === null || id === undefined) continue;
            var key = String(id);
            if (seen[key]) continue;
            seen[key] = true;
            ids.push(id);
        }
    }
    return _sortedNumericIds(ids);
}

function _objectPlanCarrierExportSourceIds(exportSourceObjectIds, visualSourceObjectIds, sourceById) {
    var visualSet = _objectPlanSourceSetMembership(visualSourceObjectIds || []);
    var out = [];
    var seen = {};

    function sourceInfo(sourceId) {
        return sourceById ? sourceById[String(sourceId)] || null : null;
    }

    function sourceKind(src) {
        return String((src && (src.kind || src.type || src.itemType)) || "");
    }

    function isPlacedGraphicLeaf(src) {
        var kind = sourceKind(src);
        return kind === "Image" || kind === "PDF" || kind === "EPS";
    }

    function nearestVisualCarrierId(sourceId) {
        var current = sourceInfo(sourceId);
        var guard = 0;
        while (current && guard++ < 64) {
            var parentId = current.parentId;
            if (parentId === null || parentId === undefined || String(parentId) === "") return null;
            if (visualSet[String(parentId)] === true) return parentId;
            current = sourceInfo(parentId);
        }
        return null;
    }

    for (var i = 0; exportSourceObjectIds && i < exportSourceObjectIds.length; i++) {
        var sourceId = Number(exportSourceObjectIds[i]);
        if (isNaN(sourceId)) continue;
        var src = sourceInfo(sourceId);
        var carrierId = isPlacedGraphicLeaf(src) ? nearestVisualCarrierId(sourceId) : null;
        _pushUniqueId(out, seen, carrierId !== null && carrierId !== undefined ? carrierId : sourceId);
    }
    return _sortedNumericIds(out);
}

function _objectPlanPromotePageRootPlacedImageAtoms(
        sourceObjectIds,
        visualSourceObjectIds,
        exportSourceObjectIds,
        excludedInlineSourceObjectIds,
        sourceById) {
    var out = [];
    var seen = {};
    var sourceSet = _objectPlanSourceSetMembership(sourceObjectIds || []);
    var visualSet = _objectPlanSourceSetMembership(visualSourceObjectIds || []);
    var inlineExcludedSet = _objectPlanSourceSetMembership(excludedInlineSourceObjectIds || []);
    var childrenByParent = null;

    function sourceInfo(sourceId) {
        return sourceById ? sourceById[String(sourceId)] || null : null;
    }

    function sourceKind(src) {
        return String((src && (src.kind || src.type || src.itemType)) || "");
    }

    function isTextSource(src) {
        return sourceKind(src) === "TextFrame";
    }

    function isPlacedGraphicLeaf(src) {
        var kind = sourceKind(src);
        return kind === "Image" || kind === "PDF" || kind === "EPS";
    }

    function buildChildrenByParent() {
        if (childrenByParent !== null) return;
        childrenByParent = {};
        if (!sourceById) return;
        try {
            if (sourceById.__objectPlanChildrenByParentId) {
                childrenByParent = sourceById.__objectPlanChildrenByParentId;
                return;
            }
        } catch (eCachedChildrenRead) {}
        for (var key in sourceById) {
            if (!sourceById.hasOwnProperty(key)) continue;
            if (String(key).indexOf("__objectPlan") === 0) continue;
            var src = sourceById[key];
            if (!src || src.id === null || src.id === undefined) continue;
            var parentId = src.parentId;
            if (parentId === null || parentId === undefined || String(parentId) === "") continue;
            var parentKey = String(parentId);
            if (!childrenByParent[parentKey]) childrenByParent[parentKey] = [];
            childrenByParent[parentKey].push(src);
        }
        try { sourceById.__objectPlanChildrenByParentId = childrenByParent; } catch (eCachedChildrenWrite) {}
    }

    function isInlineExcluded(sourceId) {
        var current = sourceInfo(sourceId);
        var guard = 0;
        var currentId = sourceId;
        while (current && guard++ < 64) {
            if (inlineExcludedSet[String(currentId)] === true) return true;
            var parentId = current.parentId;
            if (parentId === null || parentId === undefined || String(parentId) === "") return false;
            currentId = parentId;
            current = sourceInfo(parentId);
        }
        return inlineExcludedSet[String(sourceId)] === true;
    }

    function nearestCarrierId(sourceId) {
        var current = sourceInfo(sourceId);
        var guard = 0;
        while (current && guard++ < 64) {
            var parentId = current.parentId;
            if (parentId === null || parentId === undefined || String(parentId) === "") return null;
            var parent = sourceInfo(parentId);
            if (!parent || isTextSource(parent)) return null;
            if (sourceSet[String(parentId)] === true || visualSet[String(parentId)] === true) {
                return parentId;
            }
            current = parent;
        }
        return null;
    }

    for (var i = 0; exportSourceObjectIds && i < exportSourceObjectIds.length; i++) {
        _pushUniqueId(out, seen, exportSourceObjectIds[i]);
    }

    for (var si = 0; sourceObjectIds && si < sourceObjectIds.length; si++) {
        var sourceId = Number(sourceObjectIds[si]);
        if (isNaN(sourceId)) continue;
        if (isInlineExcluded(sourceId)) continue;
        var src = sourceInfo(sourceId);
        if (!src || isTextSource(src)) continue;
        if (isPlacedGraphicLeaf(src)) {
            _pushUniqueId(out, seen, sourceId);
            var carrierId = nearestCarrierId(sourceId);
            if (carrierId !== null && carrierId !== undefined) {
                _pushUniqueId(out, seen, carrierId);
            }
            continue;
        }
        if (src.hasPlacedVisual === true && (sourceSet[String(sourceId)] === true
                || visualSet[String(sourceId)] === true)) {
            _pushUniqueId(out, seen, sourceId);
        }
    }

    buildChildrenByParent();
    var carrierIds = _sourceIdsUnion(sourceObjectIds || [], visualSourceObjectIds || []);
    for (var ci = 0; carrierIds && ci < carrierIds.length; ci++) {
        promotePlacedDescendants(carrierIds[ci], 0);
    }
    return _sortedNumericIds(out);

    function promotePlacedDescendants(rootId, guard) {
        if (rootId === null || rootId === undefined || guard > 64) return;
        if (isInlineExcluded(rootId)) return;
        var children = childrenByParent ? childrenByParent[String(rootId)] || [] : [];
        for (var i = 0; i < children.length; i++) {
            var child = children[i];
            if (!child || child.id === null || child.id === undefined) continue;
            if (isInlineExcluded(child.id)) continue;
            if (isPlacedGraphicLeaf(child)) {
                _pushUniqueId(out, seen, child.id);
                _pushUniqueId(out, seen, rootId);
            }
            promotePlacedDescendants(child.id, guard + 1);
        }
    }
}

function _objectPlanPromotePageRootVisibleExportSources(
        sourceObjectIds, visualSourceObjectIds, exportSourceObjectIds, sourceById) {
    var out = [];
    var seen = {};
    var visualSet = _objectPlanSourceSetMembership(visualSourceObjectIds || []);

    function sourceInfo(sourceId) {
        return sourceById ? sourceById[String(sourceId)] || null : null;
    }

    function sourceKind(src) {
        return String((src && (src.kind || src.type || src.itemType)) || "");
    }

    function isTextSource(src) {
        return sourceKind(src) === "TextFrame";
    }

    function isPlacedGraphicLeaf(src) {
        var kind = sourceKind(src);
        return kind === "Image" || kind === "PDF" || kind === "EPS";
    }

    function nearestVisualCarrierId(sourceId) {
        var current = sourceInfo(sourceId);
        var guard = 0;
        while (current && guard++ < 64) {
            var parentId = current.parentId;
            if (parentId === null || parentId === undefined || String(parentId) === "") return null;
            if (visualSet[String(parentId)] === true) return parentId;
            current = sourceInfo(parentId);
        }
        return null;
    }

    for (var i = 0; exportSourceObjectIds && i < exportSourceObjectIds.length; i++) {
        _pushUniqueId(out, seen, exportSourceObjectIds[i]);
    }

    for (var vi = 0; visualSourceObjectIds && vi < visualSourceObjectIds.length; vi++) {
        _pushUniqueId(out, seen, visualSourceObjectIds[vi]);
    }

    for (var si = 0; sourceObjectIds && si < sourceObjectIds.length; si++) {
        var sourceId = Number(sourceObjectIds[si]);
        if (isNaN(sourceId)) continue;
        var src = sourceInfo(sourceId);
        if (!src || isTextSource(src)) continue;
        if (isPlacedGraphicLeaf(src)) {
            var carrierId = nearestVisualCarrierId(sourceId);
            _pushUniqueId(out, seen,
                    carrierId !== null && carrierId !== undefined ? carrierId : sourceId);
            continue;
        }
        if (src.hasPlacedVisual === true
                || src.hasVisibleFill === true
                || src.hasVisibleStroke === true
                || src.hasCandidateVectorPaint === true) {
            _pushUniqueId(out, seen, sourceId);
        }
    }
    return _sortedNumericIds(out);
}

function _objectPlanHiddenPlacedVisualLeafIds(hiddenSourceObjectIds, excludedInlineSourceObjectIds, sourceById) {
    var out = [];
    var seen = {};
    var excludedSet = _objectPlanSourceSetMembership(excludedInlineSourceObjectIds || []);

    function sourceInfo(sourceId) {
        return sourceById ? sourceById[String(sourceId)] || null : null;
    }

    function sourceKind(src) {
        return String((src && (src.kind || src.type || src.itemType)) || "");
    }

    function isPlacedGraphicLeaf(src) {
        var kind = sourceKind(src);
        return kind === "Image" || kind === "PDF" || kind === "EPS";
    }

    function isExcluded(sourceId) {
        var current = sourceInfo(sourceId);
        var guard = 0;
        var currentId = sourceId;
        while (current && guard++ < 64) {
            if (excludedSet[String(currentId)] === true) return true;
            var parentId = current.parentId;
            if (parentId === null || parentId === undefined || String(parentId) === "") return false;
            currentId = parentId;
            current = sourceInfo(parentId);
        }
        return excludedSet[String(sourceId)] === true;
    }

    for (var i = 0; hiddenSourceObjectIds && i < hiddenSourceObjectIds.length; i++) {
        var sourceId = Number(hiddenSourceObjectIds[i]);
        if (isNaN(sourceId) || isExcluded(sourceId)) continue;
        if (isPlacedGraphicLeaf(sourceInfo(sourceId))) {
            _pushUniqueId(out, seen, sourceId);
        }
    }
    return _sortedNumericIds(out);
}

function _objectPlanHiddenSourceIdsMinusExportedPlacedChildren(hiddenSourceObjectIds, exportSourceObjectIds, sourceById) {
    var exportSet = _objectPlanSourceSetMembership(exportSourceObjectIds || []);
    var exportAncestorSet = {};
    var out = [];
    var seen = {};

    function sourceInfo(sourceId) {
        return sourceById ? sourceById[String(sourceId)] || null : null;
    }

    function sourceKind(src) {
        return String((src && (src.kind || src.type || src.itemType)) || "");
    }

    function isPlacedGraphicLeaf(src) {
        var kind = sourceKind(src);
        return kind === "Image" || kind === "PDF" || kind === "EPS";
    }

    for (var ei = 0; exportSourceObjectIds && ei < exportSourceObjectIds.length; ei++) {
        var exportId = Number(exportSourceObjectIds[ei]);
        if (isNaN(exportId)) continue;
        var exported = sourceInfo(exportId);
        var guardExport = 0;
        while (exported && guardExport++ < 64) {
            var exportedParentId = exported.parentId;
            if (exportedParentId === null
                    || exportedParentId === undefined
                    || String(exportedParentId) === "") {
                break;
            }
            exportAncestorSet[String(exportedParentId)] = true;
            exported = sourceInfo(exportedParentId);
        }
    }

    function hasExportedAncestor(sourceId) {
        var current = sourceInfo(sourceId);
        var guard = 0;
        while (current && guard++ < 64) {
            var parentId = current.parentId;
            if (parentId === null || parentId === undefined || String(parentId) === "") return false;
            if (exportSet[String(parentId)] === true) return true;
            current = sourceInfo(parentId);
        }
        return false;
    }

    for (var i = 0; hiddenSourceObjectIds && i < hiddenSourceObjectIds.length; i++) {
        var sourceId = Number(hiddenSourceObjectIds[i]);
        if (isNaN(sourceId)) continue;
        var src = sourceInfo(sourceId);
        if (exportAncestorSet[String(sourceId)] === true) continue;
        if (isPlacedGraphicLeaf(src) && hasExportedAncestor(sourceId)) continue;
        _pushUniqueId(out, seen, sourceId);
    }
    return _sortedNumericIds(out);
}

function _objectPlanHiddenSourceIdsMinusExportAncestors(hiddenSourceObjectIds, exportSourceObjectIds, sourceById) {
    if (!hiddenSourceObjectIds || hiddenSourceObjectIds.length === 0
            || !exportSourceObjectIds || exportSourceObjectIds.length === 0
            || !sourceById) {
        return _sortedNumericIds(hiddenSourceObjectIds || []);
    }
    var exportAncestorSet = {};
    var out = [];
    var seen = {};

    function sourceInfo(sourceId) {
        return sourceById ? sourceById[String(sourceId)] || null : null;
    }

    for (var ei = 0; ei < exportSourceObjectIds.length; ei++) {
        var exportId = Number(exportSourceObjectIds[ei]);
        if (isNaN(exportId)) continue;
        var current = sourceInfo(exportId);
        var guard = 0;
        while (current && guard++ < 64) {
            var parentId = current.parentId;
            if (parentId === null || parentId === undefined || String(parentId) === "") break;
            exportAncestorSet[String(parentId)] = true;
            current = sourceInfo(parentId);
        }
    }

    for (var i = 0; i < hiddenSourceObjectIds.length; i++) {
        var sourceId = hiddenSourceObjectIds[i];
        if (exportAncestorSet[String(sourceId)] === true) continue;
        _pushUniqueId(out, seen, sourceId);
    }
    return _sortedNumericIds(out);
}

function _objectPlanVisibleTextFrameShellIds(sourceObjectIds, sourceById) {
    var out = [];
    var seen = {};
    if (!sourceObjectIds || sourceObjectIds.length === 0 || !sourceById) return out;
    for (var i = 0; i < sourceObjectIds.length; i++) {
        var sourceId = Number(sourceObjectIds[i]);
        if (isNaN(sourceId)) continue;
        var src = sourceById[String(sourceId)] || null;
        if (!src) continue;
        var kind = String(src.kind || src.type || src.itemType || "");
        if (kind !== "TextFrame") continue;
        if (src.hasVisibleFill === true || src.hasVisibleStroke === true) {
            _pushUniqueId(out, seen, sourceId);
        }
    }
    return _sortedNumericIds(out);
}

function _objectPlanPruneExportSourcesWithHiddenDescendants(exportSourceObjectIds, hiddenSourceObjectIds, sourceById) {
    var hiddenSet = _objectPlanSourceSetMembership(hiddenSourceObjectIds || []);
    var childrenByParent = {};
    var hiddenDescendantMemo = {};
    var out = [];
    var seen = {};

    function sourceInfo(sourceId) {
        return sourceById ? sourceById[String(sourceId)] || null : null;
    }

    function sourceKind(src) {
        return String((src && (src.kind || src.type || src.itemType)) || "");
    }

    function isTextSource(src) {
        return sourceKind(src) === "TextFrame";
    }

    function sourceSortValue(src) {
        if (!src) return 0;
        var z = Number(src.zOrder);
        if (!isNaN(z)) return z;
        var id = Number(src.id);
        return isNaN(id) ? 0 : id;
    }

    function buildChildrenIndex() {
        if (childrenByParent.__built === true) return;
        try {
            if (sourceById && sourceById.__objectPlanChildrenByParentId) {
                childrenByParent = sourceById.__objectPlanChildrenByParentId;
                return;
            }
        } catch (eCachedChildrenRead) {}
        var built = {};
        for (var key in sourceById) {
            if (!sourceById.hasOwnProperty(key)) continue;
            if (String(key).indexOf("__objectPlan") === 0) continue;
            var src = sourceById[key];
            if (!src || src.id === null || src.id === undefined) continue;
            var parentId = src.parentId;
            if (parentId === null || parentId === undefined || String(parentId) === "") continue;
            var parentKey = String(parentId);
            if (!built[parentKey]) built[parentKey] = [];
            built[parentKey].push(src);
        }
        for (var parentKey2 in built) {
            if (!built.hasOwnProperty(parentKey2)) continue;
            built[parentKey2].sort(function(a, b) {
                var az = sourceSortValue(a);
                var bz = sourceSortValue(b);
                if (az !== bz) return az - bz;
                var ai = Number(a && a.id);
                var bi = Number(b && b.id);
                if (isNaN(ai)) ai = 0;
                if (isNaN(bi)) bi = 0;
                return ai - bi;
            });
        }
        built.__built = true;
        childrenByParent = built;
        try { sourceById.__objectPlanChildrenByParentId = built; } catch (eCachedChildrenWrite) {}
    }

    function hasHiddenDescendant(sourceId) {
        var key = String(sourceId);
        if (hiddenDescendantMemo.hasOwnProperty(key)) return hiddenDescendantMemo[key];
        var children = childrenByParent[String(sourceId)] || [];
        for (var i = 0; i < children.length; i++) {
            var child = children[i];
            if (!child || child.id === null || child.id === undefined) continue;
            if (hiddenSet[String(child.id)] === true) {
                hiddenDescendantMemo[key] = true;
                return true;
            }
            if (hasHiddenDescendant(child.id)) {
                hiddenDescendantMemo[key] = true;
                return true;
            }
        }
        hiddenDescendantMemo[key] = false;
        return false;
    }

    function emitVisibleExportBranch(sourceId, guard) {
        if (sourceId === null || sourceId === undefined) return;
        if (guard > 64) return;
        var src = sourceInfo(sourceId);
        if (!src) return;
        if (hiddenSet[String(sourceId)] === true) return;
        if (isTextSource(src)) return;
        if (!hasHiddenDescendant(sourceId)) {
            _pushUniqueId(out, seen, sourceId);
            return;
        }
        var children = childrenByParent[String(sourceId)] || [];
        for (var i = 0; i < children.length; i++) {
            var child = children[i];
            if (!child || child.id === null || child.id === undefined) continue;
            emitVisibleExportBranch(child.id, guard + 1);
        }
    }

    if (!exportSourceObjectIds || exportSourceObjectIds.length === 0
            || !hiddenSourceObjectIds || hiddenSourceObjectIds.length === 0
            || !sourceById) {
        return _sortedNumericIds(exportSourceObjectIds || []);
    }

    buildChildrenIndex();
    for (var i = 0; i < exportSourceObjectIds.length; i++) {
        emitVisibleExportBranch(exportSourceObjectIds[i], 0);
    }
    return _sortedNumericIds(out);
}

function _objectPlanMemberIds(plans) {
    var ids = [];
    for (var i = 0; plans && i < plans.length; i++) {
        ids.push(plans[i].objectPlanId || plans[i].candidateId || ("member." + String(i)));
    }
    return ids;
}

function _appendInlineVisualInventoryObjectPlans(objectPlans, sourceItems, sourceById) {
    var summary = {
        createdPlanCount: 0,
        skippedAlreadyOwnedCount: 0,
        createdObjectPlanIds: []
    };
    if (!objectPlans || !sourceItems || sourceItems.length === 0) return { summary: summary };
    sourceById = sourceById || _objectPlanSourceInfoById(sourceItems);
    var visibleOwned = {};
    var tableStyleOwned = {};

    function markOwned(ids) {
        for (var i = 0; ids && i < ids.length; i++) {
            if (ids[i] === null || ids[i] === undefined) continue;
            visibleOwned[String(ids[i])] = true;
        }
    }

    function markTableStyleOwned(ids) {
        for (var i = 0; ids && i < ids.length; i++) {
            if (ids[i] === null || ids[i] === undefined) continue;
            tableStyleOwned[String(ids[i])] = true;
        }
    }

    for (var pi = 0; pi < objectPlans.length; pi++) {
        var plan = objectPlans[pi];
        if (!plan) continue;
        if (plan.materialization === "HWPX_TABLE_STYLE"
                || plan.visualAction === "PLACE_TABLE_STYLE"
                || plan.ownershipSlot === "TABLE_STYLE_SLOT"
                || plan.slotRole === "table_textless_shell_slot"
                || plan.compositeRole === "table_carrier_textless_shell") {
            markTableStyleOwned(plan.sourceObjectIds || []);
            markTableStyleOwned(plan.styleSourceObjectIds || []);
            markTableStyleOwned(plan.visualSourceObjectIds || []);
            markTableStyleOwned(plan.exportSourceObjectIds || []);
            markTableStyleOwned(plan.hiddenVisualSourceObjectIds || []);
        }
        if (!_objectPlanHasVisibleVisual(plan)) continue;
        if (plan.visualAction === "DROP_VISUAL") continue;
        markOwned(plan.visualSourceObjectIds || []);
        markOwned(plan.exportSourceObjectIds || []);
    }

    function sourceKind(src) {
        return String((src && (src.kind || src.type || src.itemType)) || "");
    }

    function hasDirectVisibleMaterial(src) {
        return !!(src && (src.hasPlacedVisual === true
                || src.hasVisibleFill === true
                || src.hasVisibleStroke === true
                || src.hasCandidateVectorPaint === true));
    }

    function isInlineSourceOrDescendant(src) {
        var current = src;
        var guard = 0;
        while (current && guard++ < 64) {
            if (current.storyTextInlineSlot === true) return true;
            var placement = String(current.storyAnchorPlacement || "").toUpperCase();
            if (placement === "INLINE" || placement === "ABOVE_LINE") return true;
            var parentId = current.parentId;
            if (parentId === null || parentId === undefined || String(parentId) === "") return false;
            current = sourceById ? sourceById[String(parentId)] || null : null;
        }
        return false;
    }

    for (var i = 0; i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!src || src.id === null || src.id === undefined) continue;
        if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) continue;
        var kind = sourceKind(src);
        if (kind === "TextFrame") continue;
        if (kind === "Image" || kind === "PDF" || kind === "EPS") continue;
        if (!hasDirectVisibleMaterial(src)) continue;
        if (!isInlineSourceOrDescendant(src)) continue;
        var id = Number(src.id);
        if (isNaN(id)) continue;
        if (visibleOwned[String(id)] === true) {
            summary.skippedAlreadyOwnedCount++;
            continue;
        }
        if (tableStyleOwned[String(id)] === true) {
            summary.skippedAlreadyOwnedCount++;
            continue;
        }
        var pageIndex = src.pageIndex !== undefined && src.pageIndex !== null ? Number(src.pageIndex) : -1;
        if (isNaN(pageIndex) || pageIndex < 0) continue;
        var sourceIds = _internSourceSetIds([id]);
        var sourceSetId = _sourceSetId(sourceIds);
        var objectPlanId = "object-plan.inline-visual-inventory.page." + String(pageIndex)
                + ".src." + String(id);
        var candidateId = "cand.pass.inline_objects.page." + String(pageIndex)
                + ".src." + String(id) + ".inline_visual_inventory";
        objectPlans.push({
            objectPlanId: objectPlanId,
            bundleId: "bundle.inline-visual-inventory.page." + String(pageIndex)
                    + ".src." + String(id),
            candidateId: candidateId,
            passId: "pass.inline_objects",
            pageIndex: pageIndex,
            kind: src.kind || "InlineVisual",
            mode: "TEXTLESS_CANDIDATE",
            candidatePurpose: "INLINE_CANDIDATE",
            compositeRole: "inline_visual_inventory",
            slotRole: "content_visual_slot",
            layoutOnlyInlineSlot: false,
            sourceInlineFlow: true,
            inlineCompositeLayoutDescendant: true,
            inlineAnchorSourceObjectId: src.storyTextInlineSlot === true
                    ? id
                    : (src.parentId !== undefined && src.parentId !== null
                    ? src.parentId
                    : null),
            inlineSourceTreeClosed: true,
            inlineFlowSourceObjectIds: sourceIds,
            connectorDecorationVisual: false,
            primarySourceObjectId: id,
            sourceSetId: sourceSetId,
            sourceRootSetId: sourceSetId,
            clusterSourceSetId: sourceSetId,
            visualSourceSetId: sourceSetId,
            exportSourceSetId: sourceSetId,
            hiddenSourceSetId: _sourceSetId([]),
            sourceObjectIds: sourceIds,
            sourceRootObjectIds: sourceIds,
            clusterSourceObjectIds: sourceIds,
            clusterKindCounts: {},
            omittedClusterSourceObjectIds: [],
            omittedClusterKindCounts: {},
            clusterHasEditableText: false,
            clusterHasTextFrame: false,
            clusterHasPlacedContent: src.hasPlacedVisual === true,
            clusterHasVisualSource: true,
            visualSourceObjectIds: sourceIds,
            styleSourceObjectIds: [],
            ownedTextFrameIds: [],
            exportSourceObjectIds: sourceIds,
            exportTargetObjectId: id,
            atomicExportTargetObjectId: id,
            atomicExportTargetObjectIds: sourceIds,
            atomicTextlessVectorContent: true,
            atomicContentVisualSlot: true,
            hiddenVisualSourceObjectIds: [],
            excludedInlineSourceObjectIds: [],
            materialization: "EXTRACTED_PNG_VECTOR",
            textAction: "DROP_TEXT",
            visualAction: "PLACE_INLINE_PNG",
            placement: "INLINE",
            coordinateSpace: "STORY_FLOW",
            visualLayer: "CONTENT_VISUAL",
            zOrder: src.zOrder !== undefined && src.zOrder !== null ? src.zOrder : 0,
            reason: "stage1_inline_visible_material_from_source_inventory",
            bounds: src.bounds || null,
            renderSourceBounds: src.bounds || null,
            cropSourceBounds: src.bounds || null,
            ownershipSlot: "CONTENT_VISUAL_SLOT",
            policyLayer: "CONTENT",
            clusterRelation: "INLINE_VISIBLE_MATERIAL",
            migrationStatus: "READY_EXACT_CLUSTER",
            migrationBlocker: "NONE",
            contractStatus: "READY_FOR_STAGE1_IMPORT",
            executable: true,
            required: true
        });
        visibleOwned[String(id)] = true;
        summary.createdPlanCount++;
        summary.createdObjectPlanIds.push(objectPlanId);
    }

    return { summary: summary };
}

function _appendPageRootTextlessPlaneObjectPlans(objectPlans, sourceItems, sourceById) {
    var summary = {
        createdPlaneCount: 0,
        visualSourceCount: 0,
        excludedInlineSourceCount: 0,
        textFrameStyleSourceCount: 0,
        createdObjectPlanIds: []
    };
    if (!objectPlans || !sourceItems || sourceItems.length === 0) return { summary: summary };
    sourceById = sourceById || _objectPlanSourceInfoById(sourceItems);

    var visualByPage = {};
    var inlineByPage = {};
    var textFrameStyleByPage = {};
    var boundsByPage = {};

    function sourceKind(src) {
        return String((src && (src.kind || src.type || src.itemType)) || "");
    }

    function numericPageIndex(src) {
        var pageIndex = src && src.pageIndex !== undefined && src.pageIndex !== null
                ? Number(src.pageIndex)
                : -1;
        return isNaN(pageIndex) ? -1 : pageIndex;
    }

    function hasDirectVisibleMaterial(src) {
        return !!(src && (src.hasPlacedVisual === true
                || src.hasVisibleFill === true
                || src.hasVisibleStroke === true
                || src.hasCandidateVectorPaint === true));
    }

    function isVisibleTextFramePaintSource(src) {
        return sourceKind(src) === "TextFrame"
                && (src.hasVisibleFill === true || src.hasVisibleStroke === true);
    }

    function isInlineSourceOrDescendant(src) {
        var current = src;
        var guard = 0;
        while (current && guard++ < 64) {
            if (current.storyTextInlineSlot === true) return true;
            var placement = String(current.storyAnchorPlacement || "").toUpperCase();
            if (placement === "INLINE" || placement === "ABOVE_LINE") return true;
            var parentId = current.parentId;
            if (parentId === null || parentId === undefined || String(parentId) === "") return false;
            current = sourceById ? sourceById[String(parentId)] || null : null;
        }
        return false;
    }

    function isMasterSource(src) {
        if (!src) return false;
        if (src.isMasterGraphic === true) return true;
        if (src.masterSourceId !== undefined && src.masterSourceId !== null) return true;
        if (src.masterPageItemId !== undefined && src.masterPageItemId !== null) return true;
        if (String(src.sourcePageKind || "").toLowerCase() === "master") return true;
        if (String(src.pageKind || "").toLowerCase() === "master") return true;
        return false;
    }

    function unionBounds(union, bounds) {
        if (!bounds || bounds.length < 4) return union;
        var b = [Number(bounds[0]), Number(bounds[1]), Number(bounds[2]), Number(bounds[3])];
        if (isNaN(b[0]) || isNaN(b[1]) || isNaN(b[2]) || isNaN(b[3])) return union;
        if (!union) return b;
        union[0] = Math.min(union[0], b[0]);
        union[1] = Math.min(union[1], b[1]);
        union[2] = Math.max(union[2], b[2]);
        union[3] = Math.max(union[3], b[3]);
        return union;
    }

    for (var i = 0; i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!src || src.id === null || src.id === undefined) continue;
        if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) continue;
        if (sourceKind(src) === "TextFrame" && !isVisibleTextFramePaintSource(src)) continue;
        if (isMasterSource(src)) continue;
        if (!hasDirectVisibleMaterial(src)) continue;
        var pageIndex = numericPageIndex(src);
        if (pageIndex < 0) continue;
        var pageKey = String(pageIndex);
        var id = Number(src.id);
        if (isNaN(id)) continue;
        if (isInlineSourceOrDescendant(src)) {
            if (!inlineByPage[pageKey]) inlineByPage[pageKey] = [];
            inlineByPage[pageKey].push(id);
            summary.excludedInlineSourceCount++;
            continue;
        }
        if (!visualByPage[pageKey]) visualByPage[pageKey] = [];
        visualByPage[pageKey].push(id);
        if (isVisibleTextFramePaintSource(src)) {
            if (!textFrameStyleByPage[pageKey]) textFrameStyleByPage[pageKey] = [];
            textFrameStyleByPage[pageKey].push(id);
            summary.textFrameStyleSourceCount++;
        }
        boundsByPage[pageKey] = unionBounds(boundsByPage[pageKey], src.bounds);
        summary.visualSourceCount++;
    }

    for (var key in visualByPage) {
        if (!visualByPage.hasOwnProperty(key)) continue;
        var visualSourceObjectIds = _internSourceSetIds(_sortedNumericIds(visualByPage[key]));
        if (!visualSourceObjectIds || visualSourceObjectIds.length === 0) continue;
        var pageIndexNum = Number(key);
        var excludedInlineSourceObjectIds = _internSourceSetIds(
                _sortedNumericIds(inlineByPage[key] || []));
        var styleSourceObjectIds = _internSourceSetIds(
                _sortedNumericIds(textFrameStyleByPage[key] || []));
        var sourceSetId = _sourceSetId(visualSourceObjectIds);
        var objectPlanId = "object-plan.page-root-textless-plane." + key + ".h" + sourceSetId;
        var candidateId = "cand.pass.page_textless_graphic_groups.page." + key
                + ".page_root_textless_plane.n" + String(visualSourceObjectIds.length)
                + ".h" + sourceSetId;
        var plan = {
            objectPlanId: objectPlanId,
            bundleId: "bundle.page-root-textless-plane." + key + ".h" + sourceSetId,
            candidateId: candidateId,
            passId: "pass.page_textless_graphic_groups",
            pageIndex: pageIndexNum,
            kind: "PAGE_BACKGROUND_PLANE",
            mode: "TEXTLESS_CANDIDATE",
            candidatePurpose: "SHELL_CANDIDATE",
            compositeRole: "page_background_plane",
            slotRole: "page_background_plane",
            layoutOnlyInlineSlot: false,
            sourceInlineFlow: false,
            inlineCompositeLayoutDescendant: false,
            inlineAnchorSourceObjectId: null,
            inlineSourceTreeClosed: false,
            inlineFlowSourceObjectIds: [],
            connectorDecorationVisual: false,
            primarySourceObjectId: visualSourceObjectIds[0],
            sourceSetId: sourceSetId,
            sourceRootSetId: sourceSetId,
            clusterSourceSetId: sourceSetId,
            visualSourceSetId: sourceSetId,
            exportSourceSetId: sourceSetId,
            hiddenSourceSetId: _sourceSetId([]),
            ownedByNativeShellSourceObjectIds: [],
            sourceObjectIds: visualSourceObjectIds,
            sourceRootObjectIds: visualSourceObjectIds,
            clusterSourceObjectIds: visualSourceObjectIds,
            clusterKindCounts: {},
            omittedClusterSourceObjectIds: [],
            omittedClusterKindCounts: {},
            clusterHasEditableText: false,
            clusterHasTextFrame: styleSourceObjectIds.length > 0,
            clusterHasPlacedContent: true,
            clusterHasVisualSource: true,
            visualSourceObjectIds: visualSourceObjectIds,
            styleSourceObjectIds: styleSourceObjectIds,
            ownedTextFrameIds: [],
            exportSourceObjectIds: visualSourceObjectIds,
            exportTargetObjectId: null,
            atomicExportTargetObjectId: null,
            atomicExportTargetObjectIds: [],
            atomicTextlessVectorContent: false,
            atomicContentVisualSlot: false,
            hiddenVisualSourceObjectIds: [],
            excludedInlineSourceObjectIds: excludedInlineSourceObjectIds,
            materialization: "EXTRACTED_PNG_VECTOR",
            textAction: "DROP_TEXT",
            visualAction: "PLACE_TEXT_SHELL",
            placement: "FLOATING",
            coordinateSpace: "PAGE",
            visualLayer: "PAGE_BACKGROUND",
            zOrder: OBJECT_PLAN_PAGE_BACKGROUND_PLANE_Z_ORDER,
            reason: "stage1_page_root_textless_plane_from_source_inventory",
            bounds: boundsByPage[key] || null,
            renderSourceBounds: null,
            cropSourceBounds: null,
            ownershipSlot: "SHELL_SLOT",
            policyLayer: "BACKGROUND",
            clusterRelation: "PAGE_BACKGROUND_PLANE",
            pageBackgroundComponentKey: "page-root-textless-plane",
            migrationStatus: "READY_PAGE_BACKGROUND_PLANE",
            migrationBlocker: "NONE",
            migrationBlockerDetail: {
                sourceInventoryVisualCount: visualSourceObjectIds.length,
                textFrameStyleSourceCount: styleSourceObjectIds.length,
                excludedInlineSourceCount: excludedInlineSourceObjectIds.length
            },
            executable: true,
            required: true
        };
        objectPlans.push(plan);
        summary.createdPlaneCount++;
        summary.createdObjectPlanIds.push(objectPlanId);
    }

    return { summary: summary };
}

function _objectPlanUnionBounds(plans) {
    var union = null;
    for (var i = 0; plans && i < plans.length; i++) {
        var b = plans[i] ? plans[i].bounds : null;
        if (!b || b.length < 4) continue;
        if (!union) {
            union = [Number(b[0]), Number(b[1]), Number(b[2]), Number(b[3])];
        } else {
            union[0] = Math.min(union[0], Number(b[0]));
            union[1] = Math.min(union[1], Number(b[1]));
            union[2] = Math.max(union[2], Number(b[2]));
            union[3] = Math.max(union[3], Number(b[3]));
        }
    }
    return union;
}

function _objectPlanTextOwnerPriority(plan) {
    if (!plan) return 0;
    var score = 0;
    if (plan.textAction === "OWNED_BY_PNG") score += 400;
    if (plan.textAction === "OWNED_BY_HWPX_TEXT"
            && plan.visualAction === "PLACE_TEXT_SHELL"
            && plan.placement === "INLINE"
            && plan.coordinateSpace === "STORY_FLOW"
            && (plan.materialization === "NATIVE_SOURCE_SHAPE"
                || plan.materialization === "EXTRACTED_PNG_VECTOR")
            && (plan.slotRole === "inline_text_frame_shell_slot"
                || plan.compositeRole === "inline_visible_text_frame_shell")) {
        score += 380;
    }
    if (plan.textAction === "OWNED_BY_HWPX_TEXT"
            && plan.ownershipSlot === "TEXT_SLOT"
            && plan.materialization === "HWPX_TEXT") {
        score += 300;
    }
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
    bundle = _normalizeObjectPlanBundle(bundle || {}, sourceById);
    var textAction = _objectPlanTextAction(bundle, sourceById);
    var visualAction = _objectPlanVisualAction(bundle, sourceById);
    var placement = _objectPlanPlacement(bundle);
    var coordinateSpace = _objectPlanCoordinateSpace(bundle, placement);
    var materialization = _objectPlanMaterialization(bundle, visualAction, sourceById);
    var migrationStatus = _objectPlanMigrationStatus(bundle, sourceById);
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
    var ownershipSlot = bundle.ownershipSlot || _objectPlanSlotFromActions({
        visualAction: visualAction,
        textAction: textAction
    });
    if (ownershipSlot === "SHELL_SLOT"
            && visualAction !== "PLACE_TEXT_SHELL"
            && ownedTextFrameIds.length === 0) {
        ownershipSlot = _objectPlanSlotFromActions({
            visualAction: visualAction,
            textAction: textAction
        });
    }

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
        tableCellInlineAnchorSource: bundle.tableCellInlineAnchorSource === true,
        pagePositionedAnchoredSource: bundle.pagePositionedAnchoredSource === true,
        inlineCompositeLayoutDescendant: bundle.inlineCompositeLayoutDescendant === true,
        inlineAnchorSourceObjectId: placement === "INLINE"
                ? (bundle.inlineAnchorSourceObjectId || null)
                : null,
        inlineSourceTreeClosed: inlineSourceTreeClosed,
        inlineFlowSourceObjectIds: inlineFlowSourceObjectIds,
        inlineTextStyleMarkerSource: bundle.inlineTextStyleMarkerSource === true,
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
        excludedInlineSourceObjectIds: _internSourceSetIds(bundle.excludedInlineSourceObjectIds || []),
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
        ownershipSlot: ownershipSlot,
        policyLayer: bundle.policyLayer || null,
        clusterRelation: bundle.clusterRelation || null,
        migrationStatus: migrationStatus,
        migrationBlocker: migrationBlocker.code,
        migrationBlockerDetail: migrationBlocker.detail,
        executable: bundle.executable === true,
        required: bundle.required === true
    };
}

function _normalizeObjectPlanBundle(bundle, sourceById) {
    if (_objectPlanBundleIsInlineEditableTextShellComposite(bundle, sourceById)) {
        return _inlineEditableTextShellCompositeBundle(bundle, sourceById);
    }
    if (_objectPlanBundleIsDeclaredTextlessShellComposite(bundle)) {
        return _declaredTextlessShellCompositeBundle(bundle);
    }
    if (_objectPlanBundleIsClosedBackgroundVisibleCarrier(bundle)) {
        return _closedBackgroundVisibleCarrierBundle(bundle);
    }
    if (_objectPlanBundleIsClosedPlacedContentCarrier(bundle)) {
        return _closedPlacedContentCarrierBundle(bundle);
    }
    return bundle || {};
}

function _objectPlanBundleIsInlineEditableTextShellComposite(bundle, sourceById) {
    if (!bundle) return false;
    if (bundle.passId !== "pass.inline_objects") return false;
    if (!bundle.ownedTextFrameIds || bundle.ownedTextFrameIds.length === 0) return false;
    if (_objectPlanBundleOwnsInlinePngText(bundle, sourceById)) return false;
    var visualIds = _objectPlanInlineEditableTextShellVisualIds(bundle, sourceById);
    return visualIds.length > 0;
}

function _inlineEditableTextShellCompositeBundle(bundle, sourceById) {
    var normalized = {};
    for (var key in bundle) {
        if (bundle.hasOwnProperty(key)) normalized[key] = bundle[key];
    }
    var rootIds = _objectPlanInlineEditableTextShellRootIds(bundle);
    var expandedIds = _objectPlanInlineRootExpandedSourceIds(rootIds, sourceById);
    var sourceIds = _sourceIdsUnion(bundle.sourceObjectIds || [], expandedIds);
    var ownedTextFrameIds = _sourceIdsUnion(
            bundle.ownedTextFrameIds || [],
            _objectPlanTextFrameSourceIds(sourceIds, sourceById));
    var visualIds = _objectPlanNonTextVisualSourceIds(
            sourceIds, ownedTextFrameIds, sourceById);
    var exportIds = _sourceIdsUnion(rootIds, visualIds);
    var hiddenIds = _objectPlanSourceIdsMinus(
            _sourceIdsUnion(bundle.hiddenVisualSourceObjectIds || [], ownedTextFrameIds),
            visualIds);

    normalized.sourceObjectIds = sourceIds;
    normalized.sourceRootObjectIds = rootIds.length > 0 ? rootIds : _sortedNumericIds(bundle.sourceRootObjectIds || []);
    normalized.clusterSourceObjectIds = _sourceIdsUnion(bundle.clusterSourceObjectIds || [], sourceIds);
    normalized.visualSourceObjectIds = visualIds;
    normalized.exportSourceObjectIds = exportIds;
    normalized.hiddenVisualSourceObjectIds = hiddenIds;
    normalized.hiddenTextFrameIds = _sourceIdsUnion(
            bundle.hiddenTextFrameIds || [], ownedTextFrameIds);
    normalized.ownedTextFrameIds = ownedTextFrameIds;
    normalized.ownershipSlot = "SHELL_SLOT";
    normalized.policyLayer = "DECORATION";
    normalized.visualLayer = "LABEL_BACKDROP";
    normalized.requiredSlot = "SHELL_SLOT";
    normalized.requiredSlotReason = "inline_editable_text_shell_composite";
    if (!_objectPlanInlineEditableTextShellKeepsRole(normalized.slotRole)) {
        normalized.slotRole = "inline_editable_text_shell_composite";
    }
    if (!_objectPlanInlineEditableTextShellKeepsRole(normalized.compositeRole)) {
        normalized.compositeRole = "inline_editable_text_shell_composite";
    }
    normalized.inlineTextShellComposite = true;
    normalized.clusterRelation = normalized.clusterRelation || "EXACT_SOURCE_CLUSTER";
    normalized.executable = true;
    normalized.required = true;
    return normalized;
}

function _objectPlanInlineEditableTextShellKeepsRole(role) {
    role = String(role || "");
    return role === "shell_slot_only"
            || role === "direct_child_shell_slot"
            || role === "inline_editable_text_shell_composite";
}

function _objectPlanInlineEditableTextShellRootIds(bundle) {
    var roots = [];
    var seen = {};
    function add(id) {
        var n = Number(id);
        if (isNaN(n)) return;
        _pushUniqueId(roots, seen, n);
    }
    for (var r = 0; bundle && bundle.sourceRootObjectIds && r < bundle.sourceRootObjectIds.length; r++) {
        add(bundle.sourceRootObjectIds[r]);
    }
    if (roots.length === 0 && bundle) add(bundle.primarySourceObjectId);
    if (roots.length === 0 && bundle) add(bundle.exportTargetObjectId);
    if (roots.length === 0 && bundle && bundle.sourceObjectIds && bundle.sourceObjectIds.length > 0) {
        add(bundle.sourceObjectIds[0]);
    }
    return _sortedNumericIds(roots);
}

function _objectPlanInlineRootExpandedSourceIds(rootIds, sourceById) {
    var out = [];
    var seen = {};
    function visit(id) {
        var n = Number(id);
        if (isNaN(n)) return;
        if (seen[String(n)]) return;
        seen[String(n)] = true;
        out.push(n);
        var src = sourceById ? sourceById[String(n)] : null;
        var childIds = src && src.childIds ? src.childIds : [];
        for (var i = 0; i < childIds.length; i++) {
            visit(childIds[i]);
        }
    }
    for (var r = 0; rootIds && r < rootIds.length; r++) {
        visit(rootIds[r]);
    }
    return _sortedNumericIds(out);
}

function _objectPlanTextFrameSourceIds(sourceIds, sourceById) {
    var out = [];
    var seen = {};
    for (var i = 0; sourceIds && i < sourceIds.length; i++) {
        var id = Number(sourceIds[i]);
        if (isNaN(id)) continue;
        var src = sourceById ? sourceById[String(id)] : null;
        if (_objectPlanSourceKind(src) !== "TextFrame") continue;
        if (src.hasText !== true) continue;
        _pushUniqueId(out, seen, id);
    }
    return _sortedNumericIds(out);
}

function _objectPlanInlineEditableTextShellVisualIds(bundle, sourceById) {
    var rootIds = _objectPlanInlineEditableTextShellRootIds(bundle);
    var expandedIds = _objectPlanInlineRootExpandedSourceIds(rootIds, sourceById);
    var sourceIds = _sourceIdsUnion(bundle ? bundle.sourceObjectIds || [] : [], expandedIds);
    var ownedTextFrameIds = _sourceIdsUnion(
            bundle ? bundle.ownedTextFrameIds || [] : [],
            _objectPlanTextFrameSourceIds(sourceIds, sourceById));
    return _objectPlanNonTextVisualSourceIds(sourceIds, ownedTextFrameIds, sourceById);
}

function _objectPlanBundleIsDeclaredTextlessShellComposite(bundle) {
    if (!bundle) return false;
    if (bundle.passId !== "pass.decoration_groups") return false;
    if (bundle.slotRole !== "textless_group_visual_slot"
            && bundle.compositeRole !== "textless_group_visual_slot") {
        return false;
    }
    if (bundle.clusterHasPlacedContent === true) return false;
    if ((!bundle.ownedTextFrameIds || bundle.ownedTextFrameIds.length === 0)
            && (!bundle.hiddenVisualSourceObjectIds || bundle.hiddenVisualSourceObjectIds.length === 0)) {
        return false;
    }
    if (!bundle.visualSourceObjectIds || bundle.visualSourceObjectIds.length === 0) return false;
    return true;
}

function _declaredTextlessShellCompositeBundle(bundle) {
    var normalized = {};
    for (var key in bundle) {
        if (bundle.hasOwnProperty(key)) normalized[key] = bundle[key];
    }
    normalized.ownershipSlot = "SHELL_SLOT";
    normalized.policyLayer = "DECORATION";
    if (!normalized.visualLayer || normalized.visualLayer === "CONTENT_VISUAL") {
        normalized.visualLayer = "LABEL_BACKDROP";
    }
    if (!normalized.requiredSlot || normalized.requiredSlot === "CONTENT_VISUAL_SLOT") {
        normalized.requiredSlot = "SHELL_SLOT";
    }
    if (!normalized.requiredSlotReason
            || normalized.requiredSlotReason === "visible_content_visual_material") {
        normalized.requiredSlotReason = "declared_textless_shell_composite";
    }
    return normalized;
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
        if (src && _objectPlanSourceKindIsTextOnly(_objectPlanSourceKind(src))) continue;
        _pushUniqueId(out, seen, id);
    }
    return _sortedNumericIds(out);
}

function _objectPlanSourceKind(src) {
    return String((src && (src.kind || src.type || src.itemType)) || "");
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
    if (bundle && bundle.textAction === "DROP_TEXT"
            && (!bundle.ownedTextFrameIds || bundle.ownedTextFrameIds.length === 0)) {
        return "DROP_TEXT";
    }
    if (_objectPlanBundleOwnsInlinePngText(bundle, sourceById)) {
        return "OWNED_BY_PNG";
    }
    if (bundle && bundle.ownershipSlot === "SHELL_SLOT"
            && _objectPlanVisualAction(bundle, sourceById) === "PLACE_TEXT_SHELL"
            && bundle.textOwner !== "indesign_png"
            && bundle.ownedTextFrameIds
            && bundle.ownedTextFrameIds.length > 0) {
        return "OWNED_BY_HWPX_TEXT";
    }
    if (bundle && bundle.ownershipSlot === "SHELL_SLOT"
            && _objectPlanVisualAction(bundle, sourceById) === "PLACE_TEXT_SHELL"
            && bundle.textOwner !== "indesign_png") {
        return "DROP_TEXT";
    }
    if (bundle && bundle.ownershipSlot === "SHELL_SLOT"
            && _objectPlanVisualAction(bundle, sourceById) === "DROP_VISUAL") {
        return "DROP_TEXT";
    }
    if (bundle.ownedTextFrameIds && bundle.ownedTextFrameIds.length > 0) {
        return "OWNED_BY_HWPX_TEXT";
    }
    if (!bundle || bundle.executable !== true) return "DROP_TEXT";
    if (bundle.ownershipSlot === "SHELL_SLOT") return "DROP_TEXT";
    return "DROP_TEXT";
}

function _objectPlanBundleOwnsInlinePngText(bundle, sourceById) {
    return _objectPlanBundleOwnsOnlySimpleInlineMarkerText(bundle, sourceById)
            || _objectPlanBundleOwnsInlineCompletePngText(bundle);
}

function _objectPlanBundleOwnsInlineCompletePngText(bundle) {
    if (!bundle || bundle.passId !== "pass.inline_objects") return false;
    if (bundle.ownershipSlot === "SHELL_SLOT") return false;
    if (bundle.slotRole === "direct_child_shell_slot"
            || bundle.compositeRole === "direct_child_shell_slot") return false;
    if (!bundle.ownedTextFrameIds || bundle.ownedTextFrameIds.length === 0) return false;
    if ((!bundle.visualSourceObjectIds || bundle.visualSourceObjectIds.length === 0)
            && (!bundle.exportSourceObjectIds || bundle.exportSourceObjectIds.length === 0)) {
        return false;
    }
    return bundle.completePngTextAllowed === true || bundle.textOwner === "indesign_png";
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
        if (!src || _objectPlanSourceKind(src) !== "TextFrame") return false;
        if (src.simpleMarkerLabelContents !== true) return false;
    }
    return true;
}

function _objectPlanVisualAction(bundle, sourceById) {
    if (!bundle || bundle.executable !== true) return "DROP_VISUAL";
    if (bundle.layoutOnlyInlineSlot === true) return "DROP_VISUAL";
    if (bundle.ownershipSlot === "TABLE_STYLE_SLOT") return "PLACE_TABLE_STYLE";
    if (_objectPlanBundleIsInlineVectorTextStyleMarker(bundle)) return "ABSORB_TEXT_STYLE";
    if (_objectPlanUsesAmbiguousSingleRootSlotOnlyExport(bundle)) return "DROP_VISUAL";
    if (_objectPlanBundleOwnsInlinePngText(bundle, sourceById)) {
        return _objectPlanPlacement(bundle) === "INLINE"
                ? "PLACE_INLINE_PNG"
                : "PLACE_FLOATING_PNG";
    }
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
    if (bundle.inlineTextStyleMarkerSource === true) return true;
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
        // A GraphicLine can be either a text-style strip (underline/highlight
        // surrogate) or a compact inline marker such as a bullet. Only the
        // elongated source strip belongs to ABSORB_TEXT_STYLE; compact markers
        // remain ordinary inline visual owners.
        return _objectPlanBundleHasTextStyleMarkerBounds(bundle);
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
    if (_objectPlanBundleHasExecutableInlineStoryContract(bundle)) return "INLINE";
    if (bundle && bundle.inlineTextStyleMarkerSource === true) return "INLINE";
    if (_objectPlanBundleIsDirectCompactStoryInlineVisual(bundle)) return "INLINE";
    if (_objectPlanBundleIsInlineCompositeLayoutDescendantVisual(bundle)) return "FLOATING";
    if (_objectPlanBundleIsTextShellWithoutInlineStoryContract(bundle)) return "FLOATING";
    if (_objectPlanBundleIsInlineFlowShell(bundle)) return "INLINE";
    if (_objectPlanBundleIsInlineTextOwningShell(bundle)) return "INLINE";
    if (bundle && bundle.pagePositionedAnchoredSource === true) return "FLOATING";
    if (bundle && bundle.passId === "pass.inline_objects") {
        var anchoredPosition = String(bundle.anchoredPosition || "").toUpperCase();
        if (_objectPlanBundleIsDirectCompactStoryInlineVisual(bundle)) return "INLINE";
        if (_objectPlanBundleHasExecutableInlineStoryContract(bundle)) return "INLINE";
        if (bundle.sourceInlineFlow === true
                || bundle.storyTextInlineSlot === true
                || bundle.storyAnchorPlacement === "INLINE") {
            return "INLINE";
        }
        if (bundle.pagePositionedAnchoredSource === true) return "FLOATING";
        if (bundle.storyAnchorPlacement === "FLOATING_ANCHORED" || anchoredPosition === "ANCHORED") {
            return "FLOATING";
        }
        return "INLINE";
    }
    return "FLOATING";
}

function _objectPlanBundleHasExecutableInlineStoryContract(bundle) {
    if (!bundle || !bundle.inlineAnchorSourceObjectId) return false;
    if (bundle.tableCellInlineAnchorSource === true && bundle.sourceInlineFlow === true) return true;
    if (bundle.tableCellStoryTextInlineSlot === true) return true;
    if (bundle.storyTextInlineSlot === true) return true;
    if (bundle.sourceInlineFlow === true || bundle.storyAnchorPlacement === "INLINE") return true;
    var anchoredPosition = String(bundle.anchoredPosition || "").toUpperCase();
    if (bundle.pagePositionedAnchoredSource === true
            || bundle.storyAnchorPlacement === "FLOATING_ANCHORED"
            || anchoredPosition === "ANCHORED") {
        return false;
    }
    return bundle.sourceInlineFlow === true;
}

function _objectPlanBundleIsTextShellWithoutInlineStoryContract(bundle) {
    if (!bundle || bundle.passId !== "pass.inline_objects") return false;
    if (bundle.ownershipSlot !== "SHELL_SLOT") return false;
    if (!bundle.ownedTextFrameIds || bundle.ownedTextFrameIds.length === 0) return false;
    return !_objectPlanBundleHasExecutableInlineStoryContract(bundle);
}

function _objectPlanBundleIsDirectCompactStoryInlineVisual(bundle) {
    if (!bundle || bundle.passId !== "pass.inline_objects") return false;
    if (bundle.inlineTextStyleMarkerSource === true) return false;
    if (bundle.storyTextInlineSlot !== true && bundle.sourceInlineFlow !== true) return false;
    if (!bundle.inlineAnchorSourceObjectId) return false;
    if (bundle.ownedTextFrameIds && bundle.ownedTextFrameIds.length > 0) return false;
    if (bundle.clusterHasPlacedContent === true) return false;
    if (bundle.tableCellInlineAnchorSource === true) return false;
    var bounds = bundle.bounds;
    if (!bounds || bounds.length < 4) return false;
    var h = Math.abs(Number(bounds[2]) - Number(bounds[0]));
    var w = Math.abs(Number(bounds[3]) - Number(bounds[1]));
    if (isNaN(h) || isNaN(w) || h <= 0 || w <= 0) return false;
    var longAxis = Math.max(w, h);
    var shortAxis = Math.min(w, h);
    return longAxis <= 18.0 && shortAxis <= 6.5;
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

function _objectPlanMaterialization(bundle, visualAction, sourceById) {
    if (!bundle) return "EXTRACTED_PNG_VECTOR";
    if (visualAction === "PLACE_TABLE_STYLE") return "HWPX_TABLE_STYLE";
    if (visualAction === "ABSORB_TEXT_STYLE") return "HWPX_TEXT";
    if (visualAction === "DROP_VISUAL") return "HWPX_TEXT";
    if (_objectPlanBundleOwnsInlinePngText(bundle, sourceById)) return "COMPLETE_PNG";
    return bundle.materialization || "EXTRACTED_PNG_VECTOR";
}

function _objectPlanVisualLayer(bundle) {
    if (!bundle || !bundle.policyLayer) return "CONTENT_VISUAL";
    if (bundle.slotRole === "page_background_plane"
            || bundle.compositeRole === "page_background_plane"
            || bundle.visualAction === "PLACE_PAGE_BACKGROUND_PNG"
            || bundle.passId === "pass.master_page_graphics") {
        return "PAGE_BACKGROUND";
    }
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

function _objectPlanMigrationStatus(bundle, sourceById) {
    if (!bundle) return "NEEDS_BUNDLE_SOURCE_POLICY";
    if (bundle.layoutOnlyInlineSlot === true) return "READY_LAYOUT_ONLY_INLINE_SLOT";
    if (_objectPlanBundleOwnsInlinePngText(bundle, sourceById)) return "READY_INLINE_COMPLETE_PNG_TEXT_OWNER";
    if (_objectPlanUsesAmbiguousSingleRootSlotOnlyExport(bundle)) return "NEEDS_VISIBLE_SLOT_EXPLICITNESS";
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
    if (_objectPlanUsesAmbiguousSingleRootSlotOnlyExport(bundle)) return false;
    return true;
}

function _objectPlanUsesAmbiguousSingleRootSlotOnlyExport(bundle) {
    if (!bundle) return false;
    if (bundle.ownershipSlot !== "SHELL_SLOT") return false;
    if (bundle.slotRole !== "shell_slot_only" && bundle.mode !== "SLOT_ONLY") return false;
    if (!bundle.exportSourceObjectIds || bundle.exportSourceObjectIds.length !== 1) return false;
    if (!bundle.visualSourceObjectIds || bundle.visualSourceObjectIds.length !== 1) return false;
    if (String(bundle.exportSourceObjectIds[0]) !== String(bundle.visualSourceObjectIds[0])) return false;
    if (bundle.exportTargetObjectId !== null
            && bundle.exportTargetObjectId !== undefined
            && String(bundle.exportTargetObjectId) !== String(bundle.exportSourceObjectIds[0])) {
        return false;
    }
    if (bundle.ownedTextFrameIds && bundle.ownedTextFrameIds.length > 0) return true;
    if (bundle.editableTextFrameIds && bundle.editableTextFrameIds.length > 0) return true;
    if (bundle.styleSourceObjectIds && bundle.styleSourceObjectIds.length > 0) return true;
    if (bundle.clusterHasEditableText === true || bundle.clusterHasTextFrame === true) return true;
    return false;
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
            || status === "READY_PAGE_BACKGROUND_PLANE"
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

function _validateObjectPlanDiagnostics(objectPlans, sourceById) {
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
        _validateInlineCompletePngTextOwnerContract(
                plan, issues, issueCodeCounts, issuePlanIds, sourceById);

        if (!_objectPlanMigrationStatusIsImportReady(plan.migrationStatus)
                || plan.executable === false) {
            continue;
        }
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
        if (plan.textAction === "OWNED_BY_HWPX_TEXT" && plan.ownedTextFrameIdKeys) {
            for (var kt = 0; kt < plan.ownedTextFrameIdKeys.length; kt++) {
                var textKey = String(plan.ownedTextFrameIdKeys[kt]);
                if (!textOwners[textKey]) textOwners[textKey] = [];
                textOwners[textKey].push(plan);
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
        var status = objectPlan.executable === false
                ? "NEEDS_POLICY_OR_METADATA"
                : (issuePlanIds[objectPlan.objectPlanId]
                ? "NEEDS_POLICY_OR_METADATA"
                : (_objectPlanMigrationStatusIsImportReady(objectPlan.migrationStatus)
                        ? "READY_FOR_STAGE1_IMPORT"
                        : "NEEDS_MIGRATION_POLICY"));
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
    if (plan.compositeRole === "table_carrier_textless_shell"
            || plan.slotRole === "table_textless_shell_slot") {
        if (plan.passId !== "pass.editable_textframe_visual_shells"
                && plan.passId !== "pass.inline_objects") return false;
        if (plan.ownershipSlot !== "SHELL_SLOT") return false;
        if (plan.visualAction !== "PLACE_TEXT_SHELL") return false;
        if (plan.textAction !== "DROP_TEXT") return false;
        if (plan.materialization !== "EXTRACTED_PNG_VECTOR") return false;
        var tableStyleIds = _sourceIdSet(plan.styleSourceObjectIds || []);
        for (var tableOverlapIndex = 0; tableOverlapIndex < overlap.length; tableOverlapIndex++) {
            if (!tableStyleIds[String(overlap[tableOverlapIndex])]) return false;
        }
        return true;
    }
    if (plan.ownershipSlot !== "SHELL_SLOT") return false;
    if (plan.visualAction !== "PLACE_TEXT_SHELL") return false;
    if (plan.materialization !== "EXTRACTED_PNG_VECTOR") return false;
    if (plan.passId !== "pass.editable_textframe_visual_shells"
            && plan.passId !== "pass.inline_objects") return false;
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

function _validateInlineCompletePngTextOwnerContract(
        plan, issues, issueCodeCounts, issuePlanIds, sourceById) {
    if (!plan || plan.textAction !== "OWNED_BY_PNG") return;
    if (plan.materialization !== "COMPLETE_PNG") {
        _pushObjectPlanIssue(issues, issueCodeCounts, issuePlanIds,
                "png_text_owner_without_complete_png", [plan],
                { expectedMaterialization: "COMPLETE_PNG", actual: plan.materialization });
        return;
    }
    if (plan.visualAction !== "PLACE_INLINE_PNG"
            && plan.visualAction !== "PLACE_FLOATING_PNG") {
        _pushObjectPlanIssue(issues, issueCodeCounts, issuePlanIds,
                "complete_png_text_owner_without_png_visual_action", [plan],
                { actualVisualAction: plan.visualAction });
    }
    if (!plan.ownedTextFrameIds || plan.ownedTextFrameIds.length === 0) {
        _pushObjectPlanIssue(issues, issueCodeCounts, issuePlanIds,
                "complete_png_text_owner_without_owned_text_frame", [plan], {});
    }
    if (!plan.visualSourceObjectIds || plan.visualSourceObjectIds.length === 0) {
        _pushObjectPlanIssue(issues, issueCodeCounts, issuePlanIds,
                "complete_png_text_owner_without_visual_source", [plan], {});
    }
    var closureIssue = _inlineCompletePngTextOwnerClosureIssue(plan, sourceById);
    if (closureIssue) {
        _pushObjectPlanIssue(issues, issueCodeCounts, issuePlanIds,
                closureIssue.code, [plan], closureIssue.detail || {});
    }
}

function _inlineCompletePngTextOwnerClosureIssue(plan, sourceById) {
    if (!plan || !sourceById) return null;
    if (plan.placement === "INLINE" && plan.passId !== "pass.inline_objects") {
        return {
            code: "inline_complete_png_text_owner_not_inline_object_plan",
            detail: { passId: plan.passId || null }
        };
    }
    var ownedIds = _sortedNumericIds(plan.ownedTextFrameIds || []);
    var visualIds = _sortedNumericIds(plan.visualSourceObjectIds || []);
    var sourceIds = _sortedNumericIds(plan.sourceObjectIds || []);
    if (ownedIds.length === 0 || visualIds.length === 0 || sourceIds.length === 0) return null;

    var sourceSet = _sourceIdSet(sourceIds);
    var missing = [];
    for (var oi = 0; oi < ownedIds.length; oi++) {
        if (!sourceSet[String(ownedIds[oi])]) missing.push(ownedIds[oi]);
    }
    for (var vi = 0; vi < visualIds.length; vi++) {
        if (!sourceSet[String(visualIds[vi])]) missing.push(visualIds[vi]);
    }
    if (missing.length > 0) {
        return {
            code: "complete_png_text_owner_sources_not_closed",
            detail: { missingFromSourceObjectIds: missing }
        };
    }

    var atomRoot = null;
    var splitRoots = {};
    for (var si = 0; si < sourceIds.length; si++) {
        var root = _objectPlanOutermostPlanAncestor(sourceIds[si], sourceSet, sourceById);
        if (root === null || root === undefined) root = sourceIds[si];
        splitRoots[String(root)] = root;
        if (atomRoot === null || atomRoot === undefined) atomRoot = root;
    }
    if (_objectPlanMapKeyCount(splitRoots) > 1) {
        return {
            code: "complete_png_text_owner_split_source_atom",
            detail: { atomRootIds: _objectPlanMapValues(splitRoots) }
        };
    }
    if (!_objectPlanCompletePngAtomRootIsSourceDeclared(atomRoot, ownedIds, visualIds, sourceById)) {
        return {
            code: "complete_png_text_owner_without_source_declared_atom",
            detail: { atomRootId: atomRoot }
        };
    }
    return null;
}

function _objectPlanOutermostPlanAncestor(id, planSourceSet, sourceById) {
    var current = sourceById ? sourceById[String(id)] || null : null;
    var last = Number(id);
    var guard = 0;
    while (current && guard++ < 128) {
        var currentId = Number(current.id);
        if (!isNaN(currentId) && planSourceSet[String(currentId)]) {
            last = currentId;
        }
        var parentId = current.parentId;
        if (parentId === null || parentId === undefined || parentId === "") break;
        if (!planSourceSet[String(parentId)]) break;
        current = sourceById[String(parentId)] || null;
    }
    return last;
}

function _objectPlanCompletePngAtomRootIsSourceDeclared(atomRoot, ownedIds, visualIds, sourceById) {
    var root = sourceById ? sourceById[String(atomRoot)] || null : null;
    if (!root) return false;
    if (root.storyTextInlineSlot === true) return true;
    if (String(root.storyAnchorPlacement || "").toUpperCase() === "INLINE") return true;
    var visualSet = _sourceIdSet(visualIds || []);
    for (var oi = 0; oi < ownedIds.length; oi++) {
        var owned = sourceById[String(ownedIds[oi])] || null;
        if (!owned) continue;
        var parentId = owned.parentId;
        if (parentId !== null && parentId !== undefined && visualSet[String(parentId)]) return true;
    }
    return false;
}

function _objectPlanMapValues(map) {
    var out = [];
    for (var key in map) {
        if (!map.hasOwnProperty(key)) continue;
        out.push(map[key]);
    }
    return _sortedNumericIds(out);
}

function _objectPlanHasVisibleVisual(plan) {
    return plan && (plan.visualAction === "PLACE_INLINE_PNG"
            || plan.visualAction === "PLACE_FLOATING_PNG"
            || plan.visualAction === "PLACE_PAGE_BACKGROUND_PNG"
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
    if (plan.visualAction === "PLACE_PAGE_BACKGROUND_PNG") return "SHELL_SLOT";
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
            ownedTextFrameIds: plan.ownedTextFrameIds || [],
            ownedTextFrameIdKeys: plan.ownedTextFrameIdKeys || []
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
