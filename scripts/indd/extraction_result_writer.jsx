/*
 * Extraction result write-shape helpers for extract_indd.jsx.
 *
 * This module serializes already-planned extraction output. It must not create
 * ownership, change placement, or reinterpret source slots.
 */

function _resultExportId(item) {
    if (!item) return "";
    return [
        item.type || item.itemType || "unknown",
        item.id !== undefined ? item.id : "-",
        item.pageIndex !== undefined ? item.pageIndex : "-",
        item.file || "-"
    ].join(":");
}

function _pushUniqueExtractionResultRow(results, seen, row) {
    if (!row) return;
    var key = row.exportId || [
        row.type || "unknown",
        row.id !== undefined ? row.id : "-",
        row.pageIndex !== undefined ? row.pageIndex : "-",
        row.file || "-"
    ].join(":");
    if (seen[key]) return;
    seen[key] = true;
    results.push(row);
}

function _buildExtractionResults(ctx, renderedFloatingItems, renderedImageFrames,
                                 renderedGraphicFrames, renderedVectorFrames,
                                 renderedMasterGraphics, tfShellFrames,
                                 extractionDiagnostics) {
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
    function summarize(item) {
        if (!item) return null;
        var stampedCandidateId = item.candidateId || null;
        var planCandidate = stampedCandidateId ? candidateById[String(stampedCandidateId)] : null;
        return {
            exportId: _resultExportId(item),
            status: item.file ? "OK" : "NO_FILE",
            id: item.id,
            pageIndex: item.pageIndex,
            type: item.type || item.itemType || null,
            planPassId: item.planPassId || null,
            candidateId: stampedCandidateId,
            stampedCandidateId: stampedCandidateId,
            renderUnitId: item.renderUnitId || (planCandidate ? planCandidate.renderUnitId : null) || null,
            renderUnitSlotIdentityKey: item.renderUnitSlotIdentityKey
                    || (planCandidate ? planCandidate.renderUnitSlotIdentityKey : null)
                    || null,
            exportUnitId: item.exportUnitId || null,
            exportUnitContractKey: item.exportUnitContractKey || null,
            candidateMatchStrategy: item.candidateMatchStrategy || null,
            file: item.file || null,
            bounds: item.bounds || null,
            reason: item.reason || null,
            visualOwner: item.visualOwner || null,
            textOwner: item.textOwner || null,
            sourceObjectIds: item.sourceObjectIds || [],
            executionSourceObjectIds: item.executionSourceObjectIds || item.sourceObjectIds || [],
            exportSourceObjectIds: item.exportSourceObjectIds || [],
            exportTargetObjectId: item.exportTargetObjectId || null,
            hiddenVisualSourceObjectIds: item.hiddenVisualSourceObjectIds || [],
            slotRole: item.slotRole || (planCandidate ? planCandidate.slotRole : null) || null,
            renderMode: item.renderMode || (planCandidate ? planCandidate.mode : null) || null,
            ownedTextFrameIds: item.ownedTextFrameIds || (planCandidate ? planCandidate.ownedTextFrameIds : null) || [],
            editableTextFrameIds: item.editableTextFrameIds || (planCandidate ? planCandidate.editableTextFrameIds : null) || [],
            textFrameIds: item.textFrameIds || [],
            textHiddenBeforeExport: item.textHiddenBeforeExport === true,
            hiddenTextFrameIds: item.hiddenTextFrameIds || item.childIds || (planCandidate ? planCandidate.hiddenTextFrameIds : null) || [],
            placement: item.placement || (planCandidate ? planCandidate.placement : null) || null,
            coordinateSpace: item.coordinateSpace || (planCandidate ? planCandidate.coordinateSpace : null) || null,
            inlineAnchorSourceObjectId: item.inlineAnchorSourceObjectId
                    || (planCandidate ? planCandidate.inlineAnchorSourceObjectId : null)
                    || null,
            inlineSourceTreeClosed: item.inlineSourceTreeClosed === true
                    || (planCandidate && planCandidate.inlineSourceTreeClosed === true)
        };
    }
    var results = [];
    var resultSeen = {};
    for (var i = 0; i < renderedFloatingItems.length; i++) {
        var row = summarize(renderedFloatingItems[i]);
        _pushUniqueExtractionResultRow(results, resultSeen, row);
    }
    var extractionResults = {
        schemaVersion: 1,
        policy: "POLICY-extraction-planning",
        scriptVersion: EXTRACT_SCRIPT_VERSION,
        mode: "legacy-pass-candidate-results",
        sourceDocument: ctx.inddPath,
        outputDir: ctx.outputDir,
        pageRange: {
            requestedStartPage: ctx.requestedStartPage,
            requestedEndPage: ctx.requestedEndPage,
            startPage: ctx.startPage,
            endPage: ctx.endPage,
            pageCount: ctx.pageCount,
            rangePageCount: ctx.rangePageCount,
            rangeInputMode: ctx.rangeInputMode,
            resolvedRangeMode: ctx.resolvedRangeMode
        },
        counts: {
            renderedFloatingItems: renderedFloatingItems.length,
            renderedImageFrames: renderedImageFrames.length,
            renderedGraphicFrames: renderedGraphicFrames.length,
            renderedVectorFrames: renderedVectorFrames.length,
            renderedMasterGraphics: renderedMasterGraphics.length,
            textFrameShellFrames: tfShellFrames.length
        },
        results: results,
        diagnostics: extractionDiagnostics || []
    };
    extractionResults.exportUnits = _buildExportUnitsFromExtractionResults(extractionResults);
    extractionResults.validation = _validateExtractionResults(ctx.extractionPlan, extractionResults);
    return extractionResults;
}

function _slimExtractionPlanForWrite(plan) {
    if (!plan) return plan;
    return {
        schemaVersion: plan.schemaVersion,
        policy: plan.policy,
        scriptVersion: plan.scriptVersion,
        mode: plan.mode,
        sourceDocument: plan.sourceDocument,
        outputDir: plan.outputDir,
        pageRange: plan.pageRange,
        renderOptions: plan.renderOptions,
        sourceItemSummary: _slimSourceItemsForWrite(plan.sourceItems || [], 100),
        sourceClusterSummary: plan.sourceClusterSummary,
        sourceClusterQuerySummary: plan.sourceClusterQuerySummary,
        plannerBundleSummary: plan.plannerBundleSummary,
        objectPlanSummary: plan.objectPlanSummary,
        sourceCoverageSummary: plan.sourceCoverageSummary,
        sourceOwnershipModelSummary: plan.sourceOwnershipModelSummary,
        sourceOwnershipStageGateSummary: plan.sourceOwnershipStageGateSummary,
        renderUnitSummary: _slimRenderUnitsForWrite(plan.renderUnits || [], 50),
        preObjectPlanTextlessShellSuppressionSummary:
                plan.preObjectPlanTextlessShellSuppressionSummary,
        crossPageClipParentDecorationSuppressionSummary:
                plan.crossPageClipParentDecorationSuppressionSummary,
        pageTextlessGraphicGroupSummary: plan.pageTextlessGraphicGroupSummary,
        sourceSlotCanonicalizationSummary: plan.sourceSlotCanonicalizationSummary,
        executionCandidateContractSummary: plan.executionCandidateContractSummary,
        exactShellSlotDuplicateSummary: plan.exactShellSlotDuplicateSummary,
        sourceSlotRegistrySummary: plan.sourceSlotRegistrySummary,
        candidateSummary: _slimCandidatesForWrite(plan.candidates || [], 100),
        exportPasses: plan.exportPasses,
        slimForExecution: true,
        fullDiagnostics: "run with --diagnostics or perfMode diagnostics/debug"
    };
}

function _slimCandidatesForWrite(candidates, previewLimit) {
    previewLimit = previewLimit === undefined || previewLimit === null ? 100 : previewLimit;
    var summary = {
        candidateCount: candidates ? candidates.length : 0,
        passCounts: {},
        modeCounts: {},
        purposeCounts: {},
        materializationCounts: {},
        textActionCounts: {},
        visualActionCounts: {},
        placementCounts: {},
        ownershipSlotCounts: {},
        disabledCount: 0,
        requiredCount: 0,
        previewCount: 0
    };
    var preview = [];
    for (var i = 0; candidates && i < candidates.length; i++) {
        var c = candidates[i];
        if (!c) continue;
        _incrementSlimCount(summary.passCounts, c.passId || "UNKNOWN");
        _incrementSlimCount(summary.modeCounts, c.mode || "UNKNOWN");
        _incrementSlimCount(summary.purposeCounts, c.candidatePurpose || "UNKNOWN");
        _incrementSlimCount(summary.materializationCounts, c.materialization || "UNKNOWN");
        _incrementSlimCount(summary.textActionCounts, c.textAction || "UNKNOWN");
        _incrementSlimCount(summary.visualActionCounts, c.visualAction || "UNKNOWN");
        _incrementSlimCount(summary.placementCounts, c.placement || "UNKNOWN");
        _incrementSlimCount(summary.ownershipSlotCounts, c.ownershipSlot || "UNKNOWN");
        if (c.disabled === true) summary.disabledCount++;
        if (c.required === true) summary.requiredCount++;
        if (preview.length < previewLimit) {
            preview.push(_candidatePreviewRow(c));
        }
    }
    summary.previewCount = preview.length;
    return {
        schemaVersion: 1,
        policy: "POLICY-extraction-planning",
        mode: "candidate-summary",
        summary: summary,
        fullDiagnosticsSkipped: true,
        candidatePreview: preview
    };
}

function _candidatePreviewRow(c) {
    if (!c) return c;
    return {
        candidateId: c.candidateId,
        exportUnitId: c.exportUnitId,
        renderUnitId: c.renderUnitId,
        renderUnitSlotIdentityKey: c.renderUnitSlotIdentityKey,
        passId: c.passId,
        pageIndex: c.pageIndex,
        unit: c.unit,
        mode: c.mode,
        candidatePurpose: c.candidatePurpose,
        required: c.required,
        disabled: c.disabled === true,
        parentId: c.parentId,
        parentKind: c.parentKind,
        bounds: c.bounds,
        sourceObjectIds: c.sourceObjectIds,
        executionSourceObjectIds: c.executionSourceObjectIds,
        primarySourceObjectId: c.primarySourceObjectId,
        exportTargetObjectId: c.exportTargetObjectId,
        exportSourceObjectIds: c.exportSourceObjectIds,
        hiddenVisualSourceObjectIds: c.hiddenVisualSourceObjectIds,
        visualSourceObjectIds: c.visualSourceObjectIds,
        styleSourceObjectIds: c.styleSourceObjectIds,
        ownedTextFrameIds: c.ownedTextFrameIds,
        hiddenTextFrameIds: c.hiddenTextFrameIds,
        editableTextFrameIds: c.editableTextFrameIds,
        compositeRole: c.compositeRole,
        slotRole: c.slotRole,
        layoutOnlyInlineSlot: c.layoutOnlyInlineSlot === true,
        materialization: c.materialization,
        textAction: c.textAction,
        visualAction: c.visualAction,
        visualLayer: c.visualLayer,
        ownershipSlot: c.ownershipSlot,
        textOwner: c.textOwner,
        placement: c.placement,
        coordinateSpace: c.coordinateSpace,
        reason: c.reason,
        suffix: c.suffix
    };
}

function _slimSourceItemsForWrite(sourceItems, previewLimit) {
    previewLimit = previewLimit === undefined || previewLimit === null ? 100 : previewLimit;
    var summary = {
        sourceItemCount: sourceItems ? sourceItems.length : 0,
        kindCounts: {},
        pageCounts: {},
        visibleCount: 0,
        hiddenLayerCount: 0,
        nonprintingCount: 0,
        textFrameCount: 0,
        textFrameWithTextCount: 0,
        placedVisualCount: 0,
        vectorPaintCount: 0,
        visibleFillCount: 0,
        visibleStrokeCount: 0,
        previewCount: 0
    };
    var preview = [];
    for (var i = 0; sourceItems && i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!src) continue;
        _incrementSlimCount(summary.kindCounts, src.kind || "UNKNOWN");
        _incrementSlimCount(summary.pageCounts, src.pageIndex !== undefined
                ? String(src.pageIndex)
                : "UNKNOWN");
        if (src.visible === true) summary.visibleCount++;
        if (src.hiddenLayer === true) summary.hiddenLayerCount++;
        if (src.nonprinting === true) summary.nonprintingCount++;
        if (src.kind === "TextFrame") {
            summary.textFrameCount++;
            if (src.hasText === true) summary.textFrameWithTextCount++;
        }
        if (src.hasPlacedVisual === true) summary.placedVisualCount++;
        if (src.hasCandidateVectorPaint === true) summary.vectorPaintCount++;
        if (src.hasVisibleFill === true) summary.visibleFillCount++;
        if (src.hasVisibleStroke === true) summary.visibleStrokeCount++;
        if (preview.length < previewLimit) {
            preview.push(_sourceItemPreviewRow(src));
        }
    }
    summary.previewCount = preview.length;
    return {
        schemaVersion: 1,
        policy: "POLICY-extraction-planning",
        mode: "source-item-summary",
        summary: summary,
        fullDiagnosticsSkipped: true,
        sourceItemPreview: preview
    };
}

function _sourceItemPreviewRow(src) {
    if (!src) return src;
    return {
        id: src.id,
        pageIndex: src.pageIndex,
        kind: src.kind || null,
        parentId: src.parentId !== undefined ? src.parentId : null,
        parentKind: src.parentKind || null,
        bounds: src.bounds || null,
        zOrder: src.zOrder !== undefined ? src.zOrder : null,
        layerName: src.layerName || null,
        visible: src.visible,
        hiddenLayer: src.hiddenLayer === true,
        nonprinting: src.nonprinting === true,
        textFrameClass: src.textFrameClass || null,
        contentType: src.contentType || null,
        hasText: src.hasText,
        textLength: src.textLength,
        hasChildren: src.hasChildren === true,
        hasPlacedVisual: src.hasPlacedVisual === true,
        hasCandidateVectorPaint: src.hasCandidateVectorPaint === true,
        hasVisibleFill: src.hasVisibleFill === true,
        hasVisibleStroke: src.hasVisibleStroke === true
    };
}

function _incrementSlimCount(map, key) {
    key = key || "UNKNOWN";
    if (!map[key]) map[key] = 0;
    map[key]++;
}
