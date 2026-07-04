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
            coordinateSpace: item.coordinateSpace || (planCandidate ? planCandidate.coordinateSpace : null) || null
        };
    }
    var results = [];
    for (var i = 0; i < renderedFloatingItems.length; i++) {
        var row = summarize(renderedFloatingItems[i]);
        if (row) results.push(row);
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
    function copySourceItem(src) {
        if (!src) return src;
        return {
            id: src.id,
            pageIndex: src.pageIndex,
            kind: src.kind,
            parentId: src.parentId,
            parentKind: src.parentKind,
            anchoredPosition: src.anchoredPosition,
            storyAnchorPlacement: src.storyAnchorPlacement,
            bounds: src.bounds,
            sourceOrder: src.sourceOrder,
            rawZOrder: src.rawZOrder,
            zOrder: src.zOrder,
            layerName: src.layerName,
            visible: src.visible,
            hiddenLayer: src.hiddenLayer,
            textFrameClass: src.textFrameClass,
            textLength: src.textLength,
            hasText: src.hasText,
            hasChildren: src.hasChildren,
            hasPlacedVisual: src.hasPlacedVisual,
            hasCandidateVectorPaint: src.hasCandidateVectorPaint,
            hasVisibleFill: src.hasVisibleFill,
            hasVisibleStroke: src.hasVisibleStroke,
            fillColorName: src.fillColorName,
            strokeColorName: src.strokeColorName,
            strokeWeight: src.strokeWeight,
            cornerRadius: src.cornerRadius
        };
    }
    function copyCandidate(c) {
        if (!c) return c;
        return {
            candidateId: c.candidateId,
            exportUnitId: c.exportUnitId,
            passId: c.passId,
            pageIndex: c.pageIndex,
            unit: c.unit,
            mode: c.mode,
            candidatePurpose: c.candidatePurpose,
            required: c.required,
            disabled: c.disabled === true,
            parentId: c.parentId,
            parentKind: c.parentKind,
            anchoredPosition: c.anchoredPosition,
            storyAnchorPlacement: c.storyAnchorPlacement,
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
            composite: c.composite,
            compositeRole: c.compositeRole,
            slotRole: c.slotRole,
            layoutOnlyInlineSlot: c.layoutOnlyInlineSlot === true,
            ownedByNativeShellSourceObjectIds: c.ownedByNativeShellSourceObjectIds,
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
    var sourceItems = [];
    for (var si = 0; plan.sourceItems && si < plan.sourceItems.length; si++) {
        sourceItems.push(copySourceItem(plan.sourceItems[si]));
    }
    var candidates = [];
    for (var ci = 0; plan.candidates && ci < plan.candidates.length; ci++) {
        candidates.push(copyCandidate(plan.candidates[ci]));
    }
    return {
        schemaVersion: plan.schemaVersion,
        policy: plan.policy,
        scriptVersion: plan.scriptVersion,
        mode: plan.mode,
        sourceDocument: plan.sourceDocument,
        outputDir: plan.outputDir,
        pageRange: plan.pageRange,
        renderOptions: plan.renderOptions,
        sourceItems: sourceItems,
        sourceClusterSummary: plan.sourceClusterSummary,
        sourceClusterQuerySummary: plan.sourceClusterQuerySummary,
        plannerBundleSummary: plan.plannerBundleSummary,
        objectPlanSummary: plan.objectPlanSummary,
        preObjectPlanTextlessShellSuppressionSummary:
                plan.preObjectPlanTextlessShellSuppressionSummary,
        sourceSlotCanonicalizationSummary: plan.sourceSlotCanonicalizationSummary,
        executionCandidateContractSummary: plan.executionCandidateContractSummary,
        exactShellSlotDuplicateSummary: plan.exactShellSlotDuplicateSummary,
        sourceSlotRegistrySummary: plan.sourceSlotRegistrySummary,
        candidates: candidates,
        exportPasses: plan.exportPasses,
        slimForExecution: true,
        fullDiagnostics: "run with --diagnostics or perfMode diagnostics/debug"
    };
}
