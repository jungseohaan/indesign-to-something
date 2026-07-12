/*
 * Export unit helpers for extract_indd.jsx.
 *
 * Stage 0/1 bridge: the legacy renderer still receives executable candidates,
 * but every executed render is normalized into an ExportUnit contract. Java
 * ownership can then match source/slot/file facts without depending on page
 * symptoms or candidate-only identities.
 */

function _exportUnitSortedIds(ids) {
    var out = [];
    if (!ids) return out;
    for (var i = 0; i < ids.length; i++) {
        if (ids[i] === null || ids[i] === undefined || ids[i] === "") continue;
        out.push(String(ids[i]));
    }
    out.sort(function(a, b) {
        var na = parseInt(a, 10), nb = parseInt(b, 10);
        if (!isNaN(na) && !isNaN(nb) && na !== nb) return na - nb;
        return a < b ? -1 : (a > b ? 1 : 0);
    });
    return out;
}

function _exportUnitIdsKey(ids) {
    return _exportUnitSortedIds(ids).join(".");
}

function _exportUnitSafeToken(value) {
    var s = String(value || "none");
    s = s.replace(/^pass\./, "");
    s = s.replace(/[^A-Za-z0-9_]+/g, "_");
    s = s.replace(/^_+|_+$/g, "");
    return s || "none";
}

function _exportUnitHash(value) {
    var h = 2166136261;
    var s = String(value || "");
    for (var i = 0; i < s.length; i++) {
        h ^= s.charCodeAt(i);
        h += (h << 1) + (h << 4) + (h << 7) + (h << 8) + (h << 24);
    }
    if (h < 0) h = h * -1;
    return String(h);
}

function _exportUnitSlotRole(item, planCandidate) {
    var slot = item && item.slotRole ? item.slotRole : (planCandidate ? planCandidate.slotRole : null);
    if (slot) return String(slot);
    if (item && item.textOwner === "hwpx_tf") return "TEXTLESS_SHELL_SLOT";
    if (item && item.textOwner === "indesign_png") return "COMPLETE_PNG_SLOT";
    return "CONTENT_VISUAL_SLOT";
}

function _exportUnitMaterialization(item, planCandidate) {
    if (planCandidate && planCandidate.materialization) return planCandidate.materialization;
    if (item && item.textOwner === "indesign_png") return "COMPLETE_PNG";
    if (item && item.textOwner === "hwpx_tf") return "EXTRACTED_PNG_VECTOR";
    return "EXTRACTED_PNG_VECTOR";
}

function _exportUnitContract(item, planCandidate) {
    var pageIndex = item && item.pageIndex !== undefined ? item.pageIndex
            : (planCandidate ? planCandidate.pageIndex : null);
    var planPassId = item && item.planPassId ? item.planPassId
            : (planCandidate ? planCandidate.passId : null);
    var slotRole = _exportUnitSlotRole(item, planCandidate);
    var exportSourceObjectIds = _exportUnitSortedIds(
            item && item.exportSourceObjectIds && item.exportSourceObjectIds.length
                    ? item.exportSourceObjectIds
                    : (planCandidate ? planCandidate.exportSourceObjectIds : null));
    var sourceObjectIds = _exportUnitSortedIds(
            item && item.sourceObjectIds && item.sourceObjectIds.length
                    ? item.sourceObjectIds
                    : (planCandidate ? planCandidate.sourceObjectIds : null));
    var hiddenVisualSourceObjectIds = _exportUnitSortedIds(
            item && item.hiddenVisualSourceObjectIds && item.hiddenVisualSourceObjectIds.length
                    ? item.hiddenVisualSourceObjectIds
                    : (planCandidate ? planCandidate.hiddenVisualSourceObjectIds : null));
    var excludedInlineSourceObjectIds = _exportUnitSortedIds(
            item && item.excludedInlineSourceObjectIds && item.excludedInlineSourceObjectIds.length
                    ? item.excludedInlineSourceObjectIds
                    : (planCandidate ? planCandidate.excludedInlineSourceObjectIds : null));
    var editableTextFrameIds = _exportUnitSortedIds(
            item && item.editableTextFrameIds && item.editableTextFrameIds.length
                    ? item.editableTextFrameIds
                    : (planCandidate ? planCandidate.editableTextFrameIds : null));
    var materialization = _exportUnitMaterialization(item, planCandidate);
    var placement = item && item.placement ? item.placement : (planCandidate ? planCandidate.placement : null);
    var idSeed = [
        pageIndex,
        planPassId,
        slotRole,
        materialization,
        placement,
        exportSourceObjectIds.join(","),
        sourceObjectIds.join(","),
        hiddenVisualSourceObjectIds.join(","),
        excludedInlineSourceObjectIds.join(","),
        editableTextFrameIds.join(",")
    ].join("|");
    return {
        pageIndex: pageIndex,
        planPassId: planPassId,
        slotRole: slotRole,
        materialization: materialization,
        placement: placement,
        sourceObjectIds: sourceObjectIds,
        exportSourceObjectIds: exportSourceObjectIds,
        hiddenVisualSourceObjectIds: hiddenVisualSourceObjectIds,
        excludedInlineSourceObjectIds: excludedInlineSourceObjectIds,
        editableTextFrameIds: editableTextFrameIds,
        contractKey: idSeed,
        exportUnitId: "eu.p" + String(pageIndex)
                + "." + _exportUnitSafeToken(planPassId)
                + "." + _exportUnitSafeToken(slotRole)
                + ".h" + _exportUnitHash(idSeed)
    };
}

function _buildSourceGraphFromExtractionPlan(plan) {
    var nodes = [];
    var childCountByParent = {};
    var pageCount = {};
    var sourceItems = plan && plan.sourceItems ? plan.sourceItems : [];
    for (var i = 0; i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!src) continue;
        nodes.push({
            id: src.id,
            pageIndex: src.pageIndex,
            kind: src.kind,
            contentType: src.contentType || null,
            isGraphicContentFrame: src.isGraphicContentFrame === true,
            parentId: src.parentId,
            parentKind: src.parentKind,
            anchoredPosition: src.anchoredPosition,
            storyAnchorPlacement: src.storyAnchorPlacement,
            bounds: src.bounds,
            sourceOrder: src.sourceOrder,
            rawZOrder: src.rawZOrder,
            zOrder: src.zOrder,
            visible: src.visible,
            hiddenLayer: src.hiddenLayer,
            hasText: src.hasText,
            textLength: src.textLength,
            hasChildren: src.hasChildren,
            hasPlacedVisual: src.hasPlacedVisual,
            hasVisibleFill: src.hasVisibleFill,
            hasVisibleStroke: src.hasVisibleStroke
        });
        if (src.parentId !== null && src.parentId !== undefined) {
            var pk = String(src.parentId);
            childCountByParent[pk] = (childCountByParent[pk] || 0) + 1;
        }
        var pageKey = String(src.pageIndex);
        pageCount[pageKey] = (pageCount[pageKey] || 0) + 1;
    }
    return {
        schemaVersion: 1,
        policy: "POLICY-source-ownership",
        mode: "source-graph",
        sourceDocument: plan ? plan.sourceDocument : null,
        pageRange: plan ? plan.pageRange : null,
        summary: {
            nodeCount: nodes.length,
            parentCount: Object.keys ? Object.keys(childCountByParent).length : 0,
            pageCounts: pageCount
        },
        nodes: nodes
    };
}

function _buildSourceGraphSummaryFromExtractionPlan(plan, previewLimit) {
    previewLimit = previewLimit === undefined || previewLimit === null ? 100 : previewLimit;
    var childCountByParent = {};
    var pageCount = {};
    var kindCounts = {};
    var sourceItems = plan && plan.sourceItems ? plan.sourceItems : [];
    var preview = [];
    var visibleCount = 0;
    var hiddenLayerCount = 0;
    var textFrameCount = 0;
    var textFrameWithTextCount = 0;
    var placedVisualCount = 0;
    var visibleFillCount = 0;
    var visibleStrokeCount = 0;
    for (var i = 0; i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!src) continue;
        if (preview.length < previewLimit) preview.push(_sourceGraphNodePreview(src));
        if (src.parentId !== null && src.parentId !== undefined) {
            var pk = String(src.parentId);
            childCountByParent[pk] = (childCountByParent[pk] || 0) + 1;
        }
        _incrementSourceGraphCount(pageCount, src.pageIndex !== undefined
                ? String(src.pageIndex)
                : "UNKNOWN");
        _incrementSourceGraphCount(kindCounts, src.kind || "UNKNOWN");
        if (src.visible === true) visibleCount++;
        if (src.hiddenLayer === true) hiddenLayerCount++;
        if (src.kind === "TextFrame") {
            textFrameCount++;
            if (src.hasText === true) textFrameWithTextCount++;
        }
        if (src.hasPlacedVisual === true) placedVisualCount++;
        if (src.hasVisibleFill === true) visibleFillCount++;
        if (src.hasVisibleStroke === true) visibleStrokeCount++;
    }
    return {
        schemaVersion: 1,
        policy: "POLICY-source-ownership",
        mode: "source-graph-summary",
        sourceDocument: plan ? plan.sourceDocument : null,
        pageRange: plan ? plan.pageRange : null,
        summary: {
            nodeCount: sourceItems.length,
            parentCount: Object.keys ? Object.keys(childCountByParent).length : 0,
            pageCounts: pageCount,
            kindCounts: kindCounts,
            visibleCount: visibleCount,
            hiddenLayerCount: hiddenLayerCount,
            textFrameCount: textFrameCount,
            textFrameWithTextCount: textFrameWithTextCount,
            placedVisualCount: placedVisualCount,
            visibleFillCount: visibleFillCount,
            visibleStrokeCount: visibleStrokeCount,
            previewCount: preview.length
        },
        fullDiagnosticsSkipped: true,
        nodePreview: preview
    };
}

function _sourceGraphNodePreview(src) {
    if (!src) return src;
    return {
        id: src.id,
        pageIndex: src.pageIndex,
        kind: src.kind,
        contentType: src.contentType || null,
        parentId: src.parentId,
        parentKind: src.parentKind,
        bounds: src.bounds,
        zOrder: src.zOrder,
        visible: src.visible,
        hiddenLayer: src.hiddenLayer,
        hasText: src.hasText,
        textLength: src.textLength,
        hasChildren: src.hasChildren,
        hasPlacedVisual: src.hasPlacedVisual,
        hasVisibleFill: src.hasVisibleFill,
        hasVisibleStroke: src.hasVisibleStroke
    };
}

function _incrementSourceGraphCount(map, key) {
    key = key || "UNKNOWN";
    if (!map[key]) map[key] = 0;
    map[key]++;
}

function _stampExportUnitsOnRenderedItems(ctx, renderedFloatingItems) {
    if (!renderedFloatingItems) return;
    var candidateById = {};
    try {
        var planCandidates = ctx && ctx.extractionPlan ? ctx.extractionPlan.candidates || [] : [];
        for (var pci = 0; pci < planCandidates.length; pci++) {
            var pc = planCandidates[pci];
            if (pc && pc.candidateId) candidateById[String(pc.candidateId)] = pc;
        }
    } catch (eCandidateLookup) {
        candidateById = {};
    }
    for (var i = 0; i < renderedFloatingItems.length; i++) {
        var item = renderedFloatingItems[i];
        if (!item) continue;
        var planCandidate = item.candidateId ? candidateById[String(item.candidateId)] : null;
        var contract = _exportUnitContract(item, planCandidate);
        item.exportUnitId = contract.exportUnitId;
        item.exportUnitContractKey = contract.contractKey;
        if (!item.renderUnitId && planCandidate && planCandidate.renderUnitId) {
            item.renderUnitId = planCandidate.renderUnitId;
            item.renderUnitSlotIdentityKey = planCandidate.renderUnitSlotIdentityKey || null;
        }
        if (!item.slotRole) item.slotRole = contract.slotRole;
    }
}

function _buildExportUnitsFromExtractionResults(extractionResults) {
    var unitsById = {};
    var order = [];
    var rows = extractionResults && extractionResults.results ? extractionResults.results : [];
    for (var i = 0; i < rows.length; i++) {
        var row = rows[i];
        if (!row) continue;
        var id = row.exportUnitId || null;
        if (!id) {
            var contract = _exportUnitContract(row, null);
            id = contract.exportUnitId;
            row.exportUnitId = id;
            row.exportUnitContractKey = contract.contractKey;
        }
        if (!unitsById[id]) {
            unitsById[id] = {
                exportUnitId: id,
                contractKey: row.exportUnitContractKey || null,
                pageIndex: row.pageIndex,
                planPassId: row.planPassId,
                slotRole: row.slotRole,
                materialization: row.materialization || null,
                placement: row.placement || null,
                textOwner: row.textOwner || null,
                visualOwner: row.visualOwner || null,
                sourceObjectIds: row.sourceObjectIds || [],
                exportSourceObjectIds: row.exportSourceObjectIds || [],
                hiddenVisualSourceObjectIds: row.hiddenVisualSourceObjectIds || [],
                excludedInlineSourceObjectIds: row.excludedInlineSourceObjectIds || [],
                editableTextFrameIds: row.editableTextFrameIds || [],
                files: [],
                resultExportIds: [],
                candidateIds: []
            };
            order.push(id);
        }
        var unit = unitsById[id];
        if (row.file) unit.files.push(row.file);
        if (row.exportId) unit.resultExportIds.push(row.exportId);
        if (row.candidateId) unit.candidateIds.push(row.candidateId);
    }
    var units = [];
    for (var oi = 0; oi < order.length; oi++) units.push(unitsById[order[oi]]);
    return {
        schemaVersion: 1,
        policy: "POLICY-source-ownership",
        mode: "executed-export-units",
        sourceDocument: extractionResults ? extractionResults.sourceDocument : null,
        pageRange: extractionResults ? extractionResults.pageRange : null,
        summary: {
            unitCount: units.length,
            resultCount: rows.length
        },
        units: units
    };
}
