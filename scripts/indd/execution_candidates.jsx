// ObjectPlan -> legacy execution candidate adapter.
//
// This is still a migration bridge: executors consume candidate-shaped rows.
// Keep all ObjectPlan execution-field copying here until the executor contract
// is narrowed to a canonical execution row.

function _buildExecutionCandidatesFromObjectPlans(candidates, objectPlanDiagnostics) {
    var sourceCandidates = candidates || [];
    var byCandidateId = _objectPlansByCandidateId(objectPlanDiagnostics);
    var executionCandidates = [];
    for (var ci = 0; ci < sourceCandidates.length; ci++) {
        var candidate = _copyExecutionCandidate(sourceCandidates[ci]);
        var objectPlan = candidate && candidate.candidateId
                ? byCandidateId[String(candidate.candidateId)]
                : null;
        if (objectPlan) {
            _applyObjectPlanExecutionFields(candidate, objectPlan);
        }
        executionCandidates.push(candidate);
    }
    return executionCandidates;
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
        "executionSourceObjectIds",
        "visualSourceObjectIds",
        "styleSourceObjectIds",
        "exportSourceObjectIds",
        "hiddenVisualSourceObjectIds",
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

function _applyObjectPlanExecutionFields(candidate, objectPlan) {
    candidate.objectPlanId = objectPlan.objectPlanId || null;
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
    } else if (objectPlan.visualAction === "DROP_VISUAL") {
        candidate.textOwner = "none";
        candidate.requiresTextHidden = false;
        candidate.completePngTextAllowed = false;
    }
    var executionSourceObjectIds = _objectPlanExecutionSourceObjectIds(objectPlan);
    if (objectPlan.sourceObjectIds) {
        candidate.sourceObjectIds = _sortedNumericIds(objectPlan.sourceObjectIds);
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
    if ((!candidate.ownedTextFrameIds || candidate.ownedTextFrameIds.length === 0)
            && candidate.textAction === "OWNED_BY_HWPX_TEXT") {
        candidate.ownedTextFrameIds = _sortedNumericIds(
                (candidate.editableTextFrameIds && candidate.editableTextFrameIds.length > 0)
                        ? candidate.editableTextFrameIds
                        : (candidate.hiddenTextFrameIds || []));
    }
    if (!candidate.coordinateSpace && candidate.placement) {
        candidate.coordinateSpace = candidate.placement === "INLINE" ? "STORY_FLOW" : "PAGE";
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
    _applyDroppedObjectPlanExecutionShape(candidate, objectPlan);
    candidate.composite = candidate.sourceObjectIds && candidate.sourceObjectIds.length > 1;
    if (candidate.sourceObjectIds && candidate.sourceObjectIds.length > 0) {
        candidate.candidateId = candidate.composite
                ? _candidateCompositeId(candidate.passId, candidate.pageIndex,
                        candidate.sourceObjectIds, candidate.slotRole || candidate.suffix)
                : _candidateId(candidate.passId, candidate.sourceObjectIds[0], candidate.pageIndex);
    }
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
