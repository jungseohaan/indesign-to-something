// ObjectPlan -> legacy execution candidate adapter.
//
// This is still a migration bridge: executors consume candidate-shaped rows.
// Keep all ObjectPlan execution-field copying here until the executor contract
// is narrowed to a canonical execution row.

function _buildExecutionCandidatesFromObjectPlans(candidates, objectPlanDiagnostics) {
    var sourceCandidates = candidates || [];
    var byCandidateId = _objectPlansByCandidateId(objectPlanDiagnostics);
    var executionCandidates = [];
    var executionCandidateIds = {};
    var skippedWithoutObjectPlan = [];
    var skippedIncompleteObjectPlan = [];
    for (var ci = 0; ci < sourceCandidates.length; ci++) {
        var candidate = _copyExecutionCandidate(sourceCandidates[ci]);
        var objectPlan = candidate && candidate.candidateId
                ? byCandidateId[String(candidate.candidateId)]
                : null;
        if (!objectPlan) {
            skippedWithoutObjectPlan.push(_executionCandidateBridgeSkipRow(
                    candidate, "missing_object_plan"));
            continue;
        }
        var contractIssue = _objectPlanExecutionContractIssue(objectPlan);
        if (contractIssue) {
            _markObjectPlanExecutionBridgeSkipped(
                    objectPlan,
                    "EXECUTION_CANDIDATE_CONTRACT_INVALID",
                    contractIssue);
            skippedIncompleteObjectPlan.push(_executionCandidateBridgeSkipRow(
                    candidate, contractIssue, objectPlan));
            continue;
        }
        _applyObjectPlanExecutionFields(candidate, objectPlan);
        executionCandidates.push(candidate);
        if (candidate.candidateId) executionCandidateIds[String(candidate.candidateId)] = true;
    }
    var objectPlans = objectPlanDiagnostics && objectPlanDiagnostics.objectPlans
            ? objectPlanDiagnostics.objectPlans
            : [];
    var synthesizedFromObjectPlans = [];
    for (var oi = 0; oi < objectPlans.length; oi++) {
        var plan = objectPlans[oi];
        if (!_objectPlanNeedsStandaloneExecutionCandidate(plan)) continue;
        var planCandidateId = plan.candidateId ? String(plan.candidateId) : null;
        if (planCandidateId && executionCandidateIds[planCandidateId]) continue;
        var standaloneContractIssue = _objectPlanExecutionContractIssue(plan);
        if (standaloneContractIssue) {
            _markObjectPlanExecutionBridgeSkipped(
                    plan,
                    "EXECUTION_CANDIDATE_CONTRACT_INVALID",
                    standaloneContractIssue);
            skippedIncompleteObjectPlan.push(_executionCandidateBridgeSkipRow(
                    null, standaloneContractIssue, plan));
            continue;
        }
        var synthesizedCandidate = _createExecutionCandidateFromObjectPlan(plan);
        if (!synthesizedCandidate || !synthesizedCandidate.candidateId) {
            _markObjectPlanExecutionBridgeSkipped(
                    plan,
                    "EXECUTION_CANDIDATE_SYNTHESIS_FAILED",
                    "missing_candidate_id_after_synthesis");
            skippedIncompleteObjectPlan.push(_executionCandidateBridgeSkipRow(
                    null, "missing_candidate_id_after_synthesis", plan));
            continue;
        }
        executionCandidates.push(synthesizedCandidate);
        executionCandidateIds[String(synthesizedCandidate.candidateId)] = true;
        synthesizedFromObjectPlans.push({
            candidateId: synthesizedCandidate.candidateId,
            objectPlanId: plan.objectPlanId || null,
            passId: synthesizedCandidate.passId || null,
            pageIndex: synthesizedCandidate.pageIndex,
            sourceObjectIds: synthesizedCandidate.sourceObjectIds || [],
            ownershipSlot: synthesizedCandidate.ownershipSlot || null,
            materialization: synthesizedCandidate.materialization || null,
            visualAction: synthesizedCandidate.visualAction || null
        });
    }
    _recordExecutionCandidateBridgeDiagnostics(
            objectPlanDiagnostics,
            sourceCandidates.length,
            executionCandidates.length,
            skippedWithoutObjectPlan,
            skippedIncompleteObjectPlan,
            synthesizedFromObjectPlans);
    _refreshObjectPlanExecutionBridgeSummary(objectPlanDiagnostics);
    return executionCandidates;
}

function _markObjectPlanExecutionBridgeSkipped(objectPlan, blockerCode, reason) {
    if (!objectPlan) return;
    objectPlan.executable = false;
    objectPlan.contractStatus = "NEEDS_MIGRATION_POLICY";
    objectPlan.migrationBlocker = blockerCode || "EXECUTION_CANDIDATE_BRIDGE_SKIPPED";
    objectPlan.migrationBlockerDetail = objectPlan.migrationBlockerDetail || {};
    objectPlan.migrationBlockerDetail.executionBridgeReason = reason || "unknown";
    objectPlan.migrationBlockerDetail.nextPolicyQuestion =
            "Stage 1 must not declare a READY ObjectPlan unless it can be bridged to an executable RenderUnit candidate.";
    objectPlan.reason = (objectPlan.reason || "object_plan")
            + ":execution_bridge_skipped:" + (reason || "unknown");
}

function _refreshObjectPlanExecutionBridgeSummary(objectPlanDiagnostics) {
    if (!objectPlanDiagnostics || !objectPlanDiagnostics.objectPlans) return;
    var plans = objectPlanDiagnostics.objectPlans || [];
    var importReadyPlanCount = 0;
    var contractStatusCounts = {};
    var executablePlanCount = 0;
    var migrationBlockerCounts = {};
    for (var i = 0; i < plans.length; i++) {
        var plan = plans[i];
        if (!plan) continue;
        if (plan.executable === true) executablePlanCount++;
        if (plan.contractStatus === "READY_FOR_STAGE1_IMPORT") importReadyPlanCount++;
        _incrementExecutionCandidateContractCount(
                contractStatusCounts,
                plan.contractStatus || "UNKNOWN");
        _incrementExecutionCandidateContractCount(
                migrationBlockerCounts,
                plan.migrationBlocker || "NONE");
    }
    if (objectPlanDiagnostics.summary) {
        objectPlanDiagnostics.summary.executablePlanCount = executablePlanCount;
        objectPlanDiagnostics.summary.importReadyPlanCount = importReadyPlanCount;
        objectPlanDiagnostics.summary.contractStatusCounts = contractStatusCounts;
        objectPlanDiagnostics.summary.migrationBlockerCounts = migrationBlockerCounts;
    }
    if (objectPlanDiagnostics.validation) {
        objectPlanDiagnostics.validation.importReadyPlanCount = importReadyPlanCount;
        objectPlanDiagnostics.validation.contractStatusCounts = contractStatusCounts;
    }
}

function _objectPlanExecutionContractIssue(objectPlan) {
    if (!objectPlan) return "missing_object_plan";
    if (objectPlan.contractStatus !== "READY_FOR_STAGE1_IMPORT") {
        return "contract_not_ready:" + (objectPlan.contractStatus || "UNKNOWN");
    }
    var required = [
        "objectPlanId",
        "materialization",
        "textAction",
        "visualAction",
        "placement",
        "coordinateSpace",
        "ownershipSlot"
    ];
    for (var i = 0; i < required.length; i++) {
        var field = required[i];
        var value = objectPlan[field];
        if (value === null || value === undefined || value === "") {
            return "missing_" + field;
        }
    }
    return null;
}

function _executionCandidateBridgeSkipRow(candidate, reason, objectPlan) {
    candidate = candidate || {};
    objectPlan = objectPlan || null;
    return {
        candidateId: candidate.candidateId || (objectPlan ? objectPlan.candidateId || null : null),
        objectPlanId: objectPlan ? objectPlan.objectPlanId || null : null,
        passId: candidate.passId || (objectPlan ? objectPlan.passId || null : null),
        pageIndex: candidate.pageIndex !== undefined ? candidate.pageIndex
                : (objectPlan ? objectPlan.pageIndex : null),
        reason: reason || "unknown",
        contractStatus: objectPlan ? objectPlan.contractStatus || null : null,
        migrationStatus: objectPlan ? objectPlan.migrationStatus || null : null,
        migrationBlocker: objectPlan ? objectPlan.migrationBlocker || null : null,
        sourceObjectIds: candidate.sourceObjectIds || (objectPlan ? objectPlan.sourceObjectIds || [] : [])
    };
}

function _recordExecutionCandidateBridgeDiagnostics(
        objectPlanDiagnostics,
        sourceCandidateCount,
        executionCandidateCount,
        skippedWithoutObjectPlan,
        skippedIncompleteObjectPlan,
        synthesizedFromObjectPlans) {
    if (!objectPlanDiagnostics) return;
    var withoutPlan = skippedWithoutObjectPlan || [];
    var incomplete = skippedIncompleteObjectPlan || [];
    var synthesized = synthesizedFromObjectPlans || [];
    objectPlanDiagnostics.executionCandidateBridge = {
        schemaVersion: 1,
        policy: "POLICY-source-ownership",
        mode: "execution-candidate-bridge-diagnostics",
        summary: {
            sourceCandidateCount: sourceCandidateCount || 0,
            executionCandidateCount: executionCandidateCount || 0,
            synthesizedFromObjectPlanCount: synthesized.length,
            skippedWithoutObjectPlanCount: withoutPlan.length,
            skippedIncompleteObjectPlanCount: incomplete.length
        },
        synthesizedFromObjectPlans: synthesized.slice(0, 200),
        skippedWithoutObjectPlan: withoutPlan.slice(0, 200),
        skippedIncompleteObjectPlan: incomplete.slice(0, 200)
    };
    if (objectPlanDiagnostics.summary) {
        objectPlanDiagnostics.summary.executionCandidateBridge = {
            sourceCandidateCount: sourceCandidateCount || 0,
            executionCandidateCount: executionCandidateCount || 0,
            synthesizedFromObjectPlanCount: synthesized.length,
            skippedWithoutObjectPlanCount: withoutPlan.length,
            skippedIncompleteObjectPlanCount: incomplete.length
        };
    }
}

function _buildExecutionCandidateContractDiagnostics(executionCandidates) {
    var rows = executionCandidates || [];
    var requiredFields = [
        "candidateId",
        "passId",
        "pageIndex",
        "sourceObjectIds",
        "materialization",
        "visualAction",
        "placement",
        "coordinateSpace",
        "ownershipSlot"
    ];
    var executorReadFields = _executionCandidateContractFields();
    var executorReadSet = {};
    for (var ri = 0; ri < executorReadFields.length; ri++) {
        executorReadSet[executorReadFields[ri]] = true;
    }
    var fieldCounts = {};
    var extraFieldCounts = {};
    var missingRequiredCounts = {};
    var rowsWithMissingRequired = [];
    for (var i = 0; i < rows.length; i++) {
        var row = rows[i] || {};
        for (var key in row) {
            if (row.hasOwnProperty && !row.hasOwnProperty(key)) continue;
            _incrementExecutionCandidateContractCount(fieldCounts, key);
            if (!executorReadSet[key]) {
                _incrementExecutionCandidateContractCount(extraFieldCounts, key);
            }
        }
        var missing = [];
        for (var rfi = 0; rfi < requiredFields.length; rfi++) {
            var required = requiredFields[rfi];
            if (row[required] === null || row[required] === undefined
                    || (row[required] && row[required].constructor === Array && row[required].length === 0)) {
                missing.push(required);
                _incrementExecutionCandidateContractCount(missingRequiredCounts, required);
            }
        }
        if (missing.length > 0) {
            rowsWithMissingRequired.push({
                candidateId: row.candidateId || null,
                passId: row.passId || null,
                pageIndex: row.pageIndex,
                missingFields: missing
            });
        }
    }
    return {
        schemaVersion: 1,
        policy: "POLICY-source-ownership",
        mode: "execution-candidate-contract-diagnostics",
        summary: {
            rowCount: rows.length,
            executorReadFieldCount: executorReadFields.length,
            extraFieldNameCount: _executionCandidateContractMapSize(extraFieldCounts),
            rowsWithMissingRequiredCount: rowsWithMissingRequired.length,
            fieldCounts: fieldCounts,
            extraFieldCounts: extraFieldCounts,
            missingRequiredCounts: missingRequiredCounts
        },
        requiredFields: requiredFields,
        executorReadFields: executorReadFields,
        rowsWithMissingRequired: rowsWithMissingRequired
    };
}

function _executionCandidateContractFields() {
    return [
        "candidateId",
        "passId",
        "id",
        "pageIndex",
        "primarySourceObjectId",
        "sourceObjectIds",
        "sourceRootObjectIds",
        "clusterSourceObjectIds",
        "omittedClusterSourceObjectIds",
        "executionSourceObjectIds",
        "visualSourceObjectIds",
        "styleSourceObjectIds",
        "exportSourceObjectIds",
        "hiddenVisualSourceObjectIds",
        "excludedInlineSourceObjectIds",
        "ownedTextFrameIds",
        "editableTextFrameIds",
        "hiddenTextFrameIds",
        "exportTargetObjectId",
        "atomicExportTargetObjectId",
        "atomicExportTargetObjectIds",
        "atomicTextlessVectorContent",
        "atomicContentVisualSlot",
        "materialization",
        "textAction",
        "visualAction",
        "visualLayer",
        "placement",
        "coordinateSpace",
        "ownershipSlot",
        "candidatePurpose",
        "composite",
        "compositeRole",
        "slotRole",
        "clusterRelation",
        "clusterHasEditableText",
        "clusterHasTextFrame",
        "clusterHasPlacedContent",
        "clusterHasVisualSource",
        "unit",
        "bounds",
        "zOrder",
        "required",
        "requiredSlot",
        "requiredSlotReason",
        "disabled",
        "suffix",
        "mode",
        "requiresTextHidden",
        "completePngTextAllowed",
        "textOwner",
        "reason",
        "objectPlanId",
        "renderUnitId",
        "renderUnitSlotIdentityKey",
        "inlineAnchorSourceObjectId",
        "inlineSourceTreeClosed"
    ];
}

function _incrementExecutionCandidateContractCount(map, key) {
    key = key || "UNKNOWN";
    if (!map[key]) map[key] = 0;
    map[key]++;
}

function _executionCandidateContractMapSize(map) {
    var count = 0;
    for (var key in map) {
        if (map.hasOwnProperty && !map.hasOwnProperty(key)) continue;
        count++;
    }
    return count;
}

function _objectPlansByCandidateId(objectPlanDiagnostics) {
    var byCandidateId = {};
    var objectPlans = objectPlanDiagnostics && objectPlanDiagnostics.objectPlans
            ? objectPlanDiagnostics.objectPlans
            : [];
    for (var i = 0; i < objectPlans.length; i++) {
        var plan = objectPlans[i];
        if (!plan || !plan.candidateId) continue;
        byCandidateId[String(plan.candidateId)] = plan;
    }
    return byCandidateId;
}

function _objectPlanNeedsStandaloneExecutionCandidate(objectPlan) {
    if (!objectPlan || !objectPlan.candidateId) return false;
    if (objectPlan.executable !== true) return false;
    if (objectPlan.visualAction === "DROP_VISUAL") return false;
    if (objectPlan.materialization === "HWPX_TEXT"
            || objectPlan.materialization === "HWPX_TABLE_STYLE"
            || objectPlan.materialization === "NATIVE_SOURCE_SHAPE") {
        return false;
    }
    return true;
}

function _copyExecutionCandidate(candidate) {
    var copy = {};
    if (!candidate) return copy;
    var fields = _executionCandidateContractFields();
    for (var i = 0; i < fields.length; i++) {
        var key = fields[i];
        if (!candidate.hasOwnProperty || !candidate.hasOwnProperty(key)) continue;
        var value = candidate[key];
        copy[key] = value && value.constructor === Array ? value.slice(0) : value;
    }
    return copy;
}

function _createExecutionCandidateFromObjectPlan(objectPlan) {
    if (!objectPlan) return null;
    var candidate = {
        candidateId: objectPlan.candidateId || null,
        passId: objectPlan.passId || null,
        pageIndex: objectPlan.pageIndex,
        kind: objectPlan.kind || null,
        unit: objectPlan.unit || null,
        mode: objectPlan.mode || null,
        candidatePurpose: objectPlan.candidatePurpose || null,
        compositeRole: objectPlan.compositeRole || null,
        slotRole: objectPlan.slotRole || null,
        primarySourceObjectId: objectPlan.primarySourceObjectId !== undefined
                ? objectPlan.primarySourceObjectId
                : null,
        bounds: objectPlan.bounds || null,
        zOrder: objectPlan.zOrder !== undefined ? objectPlan.zOrder : null,
        required: objectPlan.required === true,
        requiredSlot: objectPlan.requiredSlot || null,
        requiredSlotReason: objectPlan.requiredSlotReason || null,
        disabled: false,
        reason: objectPlan.reason || null,
        objectPlanId: objectPlan.objectPlanId || null,
        id: objectPlan.primarySourceObjectId !== undefined ? objectPlan.primarySourceObjectId : null
    };
    _applyObjectPlanExecutionFields(candidate, objectPlan);
    if (objectPlan.candidateId) candidate.candidateId = objectPlan.candidateId;
    return candidate;
}

function _applyObjectPlanExecutionFields(candidate, objectPlan) {
    candidate.objectPlanId = objectPlan.objectPlanId || null;
    if (objectPlan.passId) candidate.passId = objectPlan.passId;
    if (objectPlan.unit) candidate.unit = objectPlan.unit;
    candidate.mode = objectPlan.mode || candidate.mode || null;
    candidate.candidatePurpose = objectPlan.candidatePurpose || candidate.candidatePurpose || null;
    candidate.compositeRole = objectPlan.compositeRole || candidate.compositeRole || null;
    candidate.slotRole = objectPlan.slotRole || candidate.slotRole || null;
    candidate.clusterRelation = objectPlan.clusterRelation || candidate.clusterRelation || null;
    candidate.clusterHasEditableText = objectPlan.clusterHasEditableText === true;
    candidate.clusterHasTextFrame = objectPlan.clusterHasTextFrame === true;
    candidate.clusterHasPlacedContent = objectPlan.clusterHasPlacedContent === true;
    candidate.clusterHasVisualSource = objectPlan.clusterHasVisualSource === true;
    candidate.materialization = objectPlan.materialization || null;
    candidate.textAction = objectPlan.textAction || null;
    candidate.visualAction = objectPlan.visualAction || null;
    candidate.visualLayer = objectPlan.visualLayer || null;
    candidate.placement = objectPlan.placement || null;
    candidate.coordinateSpace = objectPlan.coordinateSpace || null;
    candidate.ownershipSlot = objectPlan.ownershipSlot || candidate.ownershipSlot || null;
    candidate.inlineAnchorSourceObjectId = objectPlan.inlineAnchorSourceObjectId || null;
    candidate.inlineSourceTreeClosed = objectPlan.inlineSourceTreeClosed === true;
    if (objectPlan.textAction === "OWNED_BY_HWPX_TEXT") {
        candidate.textOwner = "hwpx_tf";
        candidate.requiresTextHidden = objectPlan.visualAction !== "DROP_VISUAL";
        candidate.completePngTextAllowed = false;
    } else if (objectPlan.textAction === "OWNED_BY_PNG") {
        candidate.textOwner = "indesign_png";
        candidate.requiresTextHidden = false;
        candidate.completePngTextAllowed = true;
    } else {
        candidate.textOwner = "none";
        candidate.requiresTextHidden = false;
        candidate.completePngTextAllowed = false;
    }
    var executionSourceObjectIds = _objectPlanExecutionSourceObjectIds(objectPlan);
    if (objectPlan.sourceObjectIds) {
        candidate.sourceObjectIds = _sortedNumericIds(objectPlan.sourceObjectIds);
    }
    if (objectPlan.sourceRootObjectIds) {
        candidate.sourceRootObjectIds = _sortedNumericIds(objectPlan.sourceRootObjectIds);
    }
    if (objectPlan.clusterSourceObjectIds) {
        candidate.clusterSourceObjectIds = _sortedNumericIds(objectPlan.clusterSourceObjectIds);
    }
    if (objectPlan.omittedClusterSourceObjectIds) {
        candidate.omittedClusterSourceObjectIds = _sortedNumericIds(objectPlan.omittedClusterSourceObjectIds);
    }
    if (executionSourceObjectIds.length > 0) {
        candidate.executionSourceObjectIds = executionSourceObjectIds;
    } else if (candidate.sourceObjectIds && candidate.sourceObjectIds.length > 0) {
        candidate.executionSourceObjectIds = candidate.sourceObjectIds.slice(0);
    }
    if (objectPlan.visualSourceObjectIds) {
        candidate.visualSourceObjectIds = _sortedNumericIds(objectPlan.visualSourceObjectIds);
    }
    if (objectPlan.styleSourceObjectIds) {
        candidate.styleSourceObjectIds = _sortedNumericIds(objectPlan.styleSourceObjectIds);
    }
    if (objectPlan.ownedTextFrameIds) {
        candidate.ownedTextFrameIds = _sortedNumericIds(objectPlan.ownedTextFrameIds);
        candidate.editableTextFrameIds = _sortedNumericIds(objectPlan.ownedTextFrameIds);
    }
    if (objectPlan.hiddenTextFrameIds) {
        candidate.hiddenTextFrameIds = _sortedNumericIds(objectPlan.hiddenTextFrameIds);
    }
    if (objectPlan.textAction === "DROP_TEXT") {
        candidate.ownedTextFrameIds = [];
        if (objectPlan.visualAction === "PLACE_TEXT_SHELL"
                && objectPlan.textAction !== "OWNED_BY_PNG"
                && objectPlan.ownedTextFrameIds
                && objectPlan.ownedTextFrameIds.length > 0) {
            candidate.hiddenTextFrameIds = _sortedNumericIds(
                    _executionCandidateIdsUnion(
                            objectPlan.hiddenTextFrameIds || [],
                            objectPlan.ownedTextFrameIds || []));
            candidate.editableTextFrameIds = candidate.hiddenTextFrameIds.slice(0);
            candidate.requiresTextHidden = candidate.hiddenTextFrameIds.length > 0;
            if (candidate.requiresTextHidden) candidate.textOwner = "hwpx_tf";
        } else {
            candidate.editableTextFrameIds = [];
            candidate.hiddenTextFrameIds = [];
            candidate.requiresTextHidden = false;
        }
    } else if (objectPlan.textAction !== "OWNED_BY_HWPX_TEXT") {
        candidate.hiddenTextFrameIds = [];
        candidate.requiresTextHidden = false;
    }
    if ((!candidate.ownedTextFrameIds || candidate.ownedTextFrameIds.length === 0)
            && candidate.textAction === "OWNED_BY_HWPX_TEXT") {
        candidate.ownedTextFrameIds = _sortedNumericIds(
                (candidate.editableTextFrameIds && candidate.editableTextFrameIds.length > 0)
                        ? candidate.editableTextFrameIds
                        : (candidate.hiddenTextFrameIds || []));
    }
    if (objectPlan.exportSourceObjectIds) {
        candidate.exportSourceObjectIds = _sortedNumericIds(objectPlan.exportSourceObjectIds);
    }
    if (objectPlan.exportTargetObjectId !== undefined) {
        candidate.exportTargetObjectId = objectPlan.exportTargetObjectId;
    }
    if (objectPlan.atomicExportTargetObjectId !== undefined) {
        candidate.atomicExportTargetObjectId = objectPlan.atomicExportTargetObjectId;
    }
    if (objectPlan.atomicExportTargetObjectIds) {
        candidate.atomicExportTargetObjectIds = _sortedNumericIds(objectPlan.atomicExportTargetObjectIds);
    }
    candidate.atomicTextlessVectorContent = objectPlan.atomicTextlessVectorContent === true;
    candidate.atomicContentVisualSlot = objectPlan.atomicContentVisualSlot === true;
    candidate.hiddenVisualSourceObjectIds = _objectPlanExecutionHiddenVisualSourceObjectIds(
            objectPlan,
            candidate.executionSourceObjectIds || candidate.sourceObjectIds || [],
            candidate.exportSourceObjectIds || []);
    if (objectPlan.excludedInlineSourceObjectIds) {
        candidate.excludedInlineSourceObjectIds = _sortedNumericIds(
                objectPlan.excludedInlineSourceObjectIds || []);
    }
    if (objectPlan.required !== undefined) candidate.required = objectPlan.required === true;
    if (objectPlan.requiredSlot !== undefined) candidate.requiredSlot = objectPlan.requiredSlot;
    if (objectPlan.requiredSlotReason !== undefined) {
        candidate.requiredSlotReason = objectPlan.requiredSlotReason;
    }
    if (objectPlan.bounds) candidate.bounds = objectPlan.bounds;
    if (objectPlan.zOrder !== undefined) candidate.zOrder = objectPlan.zOrder;
    _applyDroppedObjectPlanExecutionShape(candidate, objectPlan);
    candidate.composite = candidate.sourceObjectIds && candidate.sourceObjectIds.length > 1;
    if (!_shouldPreserveObjectPlanCandidateId(objectPlan)
            && candidate.sourceObjectIds && candidate.sourceObjectIds.length > 0) {
        candidate.candidateId = candidate.composite
                ? _candidateCompositeId(candidate.passId, candidate.pageIndex,
                        candidate.sourceObjectIds, candidate.slotRole || candidate.suffix)
                : _candidateId(candidate.passId, candidate.sourceObjectIds[0], candidate.pageIndex);
    }
}

function _shouldPreserveObjectPlanCandidateId(objectPlan) {
    if (!objectPlan || !objectPlan.candidateId) return false;
    return objectPlan.materialization === "PAGE_PLANE_PNG"
            || objectPlan.visualAction === "PLACE_PAGE_BACKGROUND_PNG"
            || objectPlan.slotRole === "page_background_plane"
            || objectPlan.compositeRole === "page_background_plane";
}

function _applyDroppedObjectPlanExecutionShape(candidate, objectPlan) {
    if (!candidate || !objectPlan) return;
    if (objectPlan.visualAction !== "DROP_VISUAL" || objectPlan.textAction !== "DROP_TEXT") return;
    candidate.editableTextFrameIds = [];
    candidate.hiddenTextFrameIds = [];
    candidate.ownedTextFrameIds = [];
    candidate.requiresTextHidden = false;
}

function _objectPlanExecutionSourceObjectIds(objectPlan) {
    if (!objectPlan) return [];
    if (objectPlan.visualAction === "PLACE_TEXT_SHELL"
            && ((objectPlan.visualSourceObjectIds && objectPlan.visualSourceObjectIds.length > 0)
                || (objectPlan.styleSourceObjectIds && objectPlan.styleSourceObjectIds.length > 0))) {
        return _sortedNumericIds(_executionCandidateIdsUnion(
                _executionCandidateIdsUnion(
                        objectPlan.visualSourceObjectIds || [],
                        objectPlan.styleSourceObjectIds || []),
                objectPlan.ownedTextFrameIds || []));
    }
    return _sortedNumericIds(objectPlan.sourceObjectIds || []);
}

function _objectPlanExecutionHiddenVisualSourceObjectIds(objectPlan, executionSourceObjectIds, exportSourceObjectIds) {
    if (!objectPlan) return [];
    if (objectPlan.slotRole === "page_background_plane"
            || objectPlan.compositeRole === "page_background_plane") {
        return _sortedNumericIds(objectPlan.hiddenVisualSourceObjectIds || []);
    }
    if (objectPlan.visualAction === "PLACE_TEXT_SHELL") {
        return _sortedNumericIds(_executionCandidateIdsUnion(
                objectPlan.hiddenVisualSourceObjectIds || [],
                _executionCandidateIdsMinus(
                        executionSourceObjectIds || [], exportSourceObjectIds || [])));
    }
    return _sortedNumericIds(objectPlan.hiddenVisualSourceObjectIds || []);
}

function _executionCandidateIdsUnion(a, b) {
    var ids = [];
    var seen = {};
    for (var ai = 0; a && ai < a.length; ai++) {
        if (seen[String(a[ai])]) continue;
        seen[String(a[ai])] = true;
        ids.push(a[ai]);
    }
    for (var bi = 0; b && bi < b.length; bi++) {
        if (seen[String(b[bi])]) continue;
        seen[String(b[bi])] = true;
        ids.push(b[bi]);
    }
    return ids;
}

function _executionCandidateIdsMinus(a, b) {
    var removed = {};
    for (var bi = 0; b && bi < b.length; bi++) removed[String(b[bi])] = true;
    var ids = [];
    var seen = {};
    for (var ai = 0; a && ai < a.length; ai++) {
        if (removed[String(a[ai])]) continue;
        if (seen[String(a[ai])]) continue;
        seen[String(a[ai])] = true;
        ids.push(a[ai]);
    }
    return ids;
}
