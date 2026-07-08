/**
 * Planned inline/page-background render executors.
 *
 * These functions execute pass candidates only. They must not reinterpret
 * inline/floating ownership after Stage 1 planning.
 */

function exportPageBackgrounds(doc, outputDir, startPage, endPage,
                               pageBackgroundCandidates, skipRenderPagesMap) {
    return { items: [] };
}

/**
 * Story-anchored inline object extraction.
 *
 * Executes only `pass.inline_objects` candidates planned before export.
 */
function exportInlineObjects(doc, outputDir, startPage, endPage,
                             allItems, itemById, inlineCandidates) {
    var renderDir = Folder(outputDir + "/rendered_frames");
    renderDir.create();

    app.pngExportPreferences.exportResolution = CONFIG.rendering.pngExportResolution || 220;
    app.pngExportPreferences.antiAlias = true;
    // 페이지 배경은 흰 배경으로 렌더링 — transparentBackground=true 시 연한 CMYK 색상(C=0 M=0.1 Y=0.08 K=0 등)이
    // "흰 바탕 언멀티플라이" 처리로 거의 투명하게 렌더되어 배경 도형이 사라지는 현상 방지
    app.pngExportPreferences.transparentBackground = false;
    app.pngExportPreferences.pngQuality = PNGQualityEnum.MAXIMUM;

    // 고품질 이미지 표시로 전환 (배치 이미지를 원본 해상도로 렌더)
    var savedDisplayPerf = null;
    try {
        savedDisplayPerf = doc.viewPreferences.displayPerformance;
        doc.viewPreferences.displayPerformance = ViewDisplaySettings.HIGH_QUALITY;
    } catch (e) {}

    // 개별 페이지 아이템의 localDisplaySetting도 HIGH_QUALITY로 강제
    // (문서 레벨 설정을 개별 아이템이 Typical/Fast로 오버라이드하면 저품질 렌더링됨)
    var savedLocalSettings = [];
    try {
        for (var li = 0; li < allItems.length; li++) {
            try {
                var localSetting = allItems[li].localDisplaySetting;
                if (localSetting !== ViewDisplaySettings.DEFAULT_VALUE
                    && localSetting !== ViewDisplaySettings.HIGH_QUALITY) {
                    savedLocalSettings.push({ item: allItems[li], setting: localSetting });
                    allItems[li].localDisplaySetting = ViewDisplaySettings.HIGH_QUALITY;
                }
            } catch (e2) {}
        }
    } catch (e) {}

    var results = [];

    // Table-inline whole-text PNG fallback removed: editable table text is owned by HWPX.
    var tableInlineRendered = [];  // kept for result schema compatibility
    var inlineObjects = [];  // { id, file, parentStoryId, bounds, pageIndex }
    var inlineStats = {
        candidates: inlineCandidates ? inlineCandidates.length : 0,
        missingItem: 0,
        textFrameSkipped: 0,
        notInlineSkipped: 0,
        parentInlineSkipped: 0,
        spacerSkipped: 0,
        outOfRangeSkipped: 0,
        exportAttempts: 0,
        exportOk: 0,
        resultItems: 0
    };

    function _parentStoryIdForInlineItem(item) {
        try {
            if (item.parentStory && item.parentStory.id !== undefined) {
                return item.parentStory.id.toString();
            }
        } catch (eSelfStory) {}
        try {
            var cur = item.parent;
            while (cur) {
                try {
                    if (cur.parentStory && cur.parentStory.id !== undefined) {
                        return cur.parentStory.id.toString();
                    }
                } catch (eCurStory) {}
                try {
                    if (cur.constructor && cur.constructor.name === "Story" && cur.id !== undefined) {
                        return cur.id.toString();
                    }
                } catch (eStoryCtor) {}
                var pn = "";
                try { pn = cur.constructor.name; } catch (ePn) {}
                if (pn === "Spread" || pn === "Page" || pn === "Document") break;
                try { cur = cur.parent; } catch (eParent) { break; }
            }
        } catch (eParentStory) {}
        return null;
    }

    function _inlineItemForCandidate(candidate) {
        if (!candidate || !itemById) return null;
        if (candidate.exportTargetObjectId !== null && candidate.exportTargetObjectId !== undefined) {
            var exportTarget = itemById[String(candidate.exportTargetObjectId)];
            if (exportTarget) return exportTarget;
        }
        var sourceId = candidate.primarySourceObjectId;
        if (sourceId !== null && sourceId !== undefined) {
            var primary = itemById[String(sourceId)];
            if (primary) return primary;
        }
        if (candidate.sourceObjectIds && candidate.sourceObjectIds.length > 0) {
            for (var si = 0; si < candidate.sourceObjectIds.length; si++) {
                var item = itemById[String(candidate.sourceObjectIds[si])];
                if (item) return item;
            }
        }
        return null;
    }

    function _plannedInlineCandidateHasVisibleCarrierContent(candidate, itemId) {
        if (!candidate) return false;
        var sourceIds = candidate.sourceObjectIds || [];
        var visualIds = candidate.visualSourceObjectIds || [];
        var exportIds = candidate.exportSourceObjectIds || [];
        if (sourceIds.length > 1) return true;
        if (visualIds.length > 1 || exportIds.length > 1) return true;
        for (var vi = 0; vi < visualIds.length; vi++) {
            if (String(visualIds[vi]) !== String(itemId)) return true;
        }
        for (var ei = 0; ei < exportIds.length; ei++) {
            if (String(exportIds[ei]) !== String(itemId)) return true;
        }
        return false;
    }

    try {

    // 인라인 객체 추출: ExtractionPlan의 inline 후보만 개별 PNG로 렌더
    for (var ei = 0; inlineCandidates && ei < inlineCandidates.length; ei++) {
        var inlineCandidate = inlineCandidates[ei];
        var inItem = _inlineItemForCandidate(inlineCandidate);
        try {
                if (!inItem) {
                    inlineStats.missingItem++;
                    continue;
                }
                var plannedTextFrameShell = inlineCandidate.visualAction === "PLACE_TEXT_SHELL"
                        && (inlineCandidate.slotRole === "direct_child_shell_slot"
                            || inlineCandidate.compositeRole === "direct_child_shell_slot")
                        && (inlineCandidate.exportSourceObjectIds && inlineCandidate.exportSourceObjectIds.length > 0);
                if (inItem.constructor.name === "TextFrame" && !plannedTextFrameShell) {
                    inlineStats.textFrameSkipped++;
                    continue;
                }
                var plannedDirectChildShellSlot = inlineCandidate.slotRole === "direct_child_shell_slot";
                var plannedPageVisual = inlineCandidate.placement === "FLOATING"
                        && inlineCandidate.visualAction === "PLACE_FLOATING_PNG";
                if (!isInlineItem(inItem) && !plannedDirectChildShellSlot && !plannedPageVisual) {
                    inlineStats.notInlineSkipped++;
                    continue;
                }
                try {
                    var inParent = inItem.parent;
                    if (!plannedDirectChildShellSlot && !plannedPageVisual
                            && inParent && inParent.constructor.name === "Group" && isInlineItem(inParent)) {
                        inlineStats.parentInlineSkipped++;
                        continue;
                    }
                    if (!plannedDirectChildShellSlot && !plannedPageVisual
                            && inParent && inParent.constructor.name === "Rectangle" && isInlineItem(inParent)) {
                        inlineStats.parentInlineSkipped++;
                        continue;
                    }
                } catch (ep) {}

                // 투명 스페이서 객체 건너뛰기 (fillColor=None, strokeColor=None, 콘텐츠 없음)
                try {
                    var inType = inItem.constructor.name;
                    if (inType === "Rectangle" || inType === "Polygon" || inType === "Oval") {
                        var inFill = inItem.fillColor ? inItem.fillColor.name : "None";
                        var inStroke = inItem.strokeColor ? inItem.strokeColor.name : "None";
                        var inSW = inItem.strokeWeight || 0;
                        // 채움/선 없고, 그래픽 콘텐츠 없는 빈 프레임 → 스페이서
                        if (!plannedPageVisual &&
                            (inFill === "None" || inFill === "[None]") &&
                            ((inStroke === "None" || inStroke === "[None]") || inSW === 0)) {
                            var hasPlannedCarrierContent =
                                    _plannedInlineCandidateHasVisibleCarrierContent(inlineCandidate, inItem.id);
                            // allGraphics 확인 — 이미지가 있으면 건너뛰지 않음
                            var hasGraphic = false;
                            try { hasGraphic = inItem.allGraphics && inItem.allGraphics.length > 0; } catch(eg) {}
                            if (!hasPlannedCarrierContent && !hasGraphic) {
                                inlineStats.spacerSkipped++;
                                continue;
                            }
                        }
                    }
                } catch (eSpacer) {}

                var inId = inItem.id;
                var _inlinePlanPageIdx = inlineCandidate.pageIndex;
                if (_inlinePlanPageIdx !== null && _inlinePlanPageIdx !== undefined
                        && _inlinePlanPageIdx >= 0
                        && !_candidatePageInRange(_inlinePlanPageIdx, { startPage: startPage, endPage: endPage })) {
                    inlineStats.outOfRangeSkipped++;
                    continue;
                }
                var _inlineCandidateMatch = _candidateMatch(inlineCandidate, "candidate_direct");
                var _inlineEntrySourceIds = null;
                try {
                    _inlineEntrySourceIds = inlineCandidate.sourceObjectIds && inlineCandidate.sourceObjectIds.length > 0
                            ? inlineCandidate.sourceObjectIds
                            : _collectSourceObjectIds(inItem);
                } catch (eInlineEntrySourceIds) {}
                if (!_inlineEntrySourceIds || _inlineEntrySourceIds.length === 0) _inlineEntrySourceIds = [inItem.id];
                var inFileName = "inline_" + inId + ".png";
                var inOutFile = File(renderDir + "/" + inFileName);
                // Group 내부의 TextFrame 텍스트는 HWPX 텍스트로 별도 배치되므로
                // 렌더링 PNG에는 텍스트를 제외한다. fillColor=None 방식은 일부
                // inline export에서 픽셀에 텍스트가 남을 수 있어 TF 자체를 잠시 숨긴다.
                var savedInlineTextFrames = [];
                var inlineHiddenTextFrameIds = [];
                var savedInlineOutOfScopeItems = [];
                var inlineCompleteMarkerEditableIds = [];
                var inlineCompleteMarker = false;
                try {
                    inlineCompleteMarkerEditableIds = inlineCandidate.editableTextFrameIds || [];
                    // Stage 1 contract: the planner decides whether inline text is
                    // owned by HWPX and must be hidden in the PNG. Export only obeys
                    // the candidate; it does not reclassify inline labels.
                    var shouldHideText = inlineCandidate.requiresTextHidden === true;
                    if (shouldHideText) {
                        inlineHiddenTextFrameIds = (inlineCandidate.hiddenTextFrameIds && inlineCandidate.hiddenTextFrameIds.length > 0)
                                ? inlineCandidate.hiddenTextFrameIds.slice(0)
                                : (inlineCandidate.editableTextFrameIds || []).slice(0);
                        if (inlineHiddenTextFrameIds.length > 0) {
                            savedInlineTextFrames = hideTextFrames(inItem);
                        }
                    }
                } catch (eWalk) {}
                var _inlineExportOk = false;
                try {
                    // 인라인 객체는 배경 위에 얹히므로 투명 배경 필요
                    try { app.pngExportPreferences.transparentBackground = true; } catch (e) {}
                    inlineStats.exportAttempts++;
                    try {
                        var inlineExportSourceIds = inlineCandidate.exportSourceObjectIds || [];
                        if (inlineExportSourceIds.length > 0) {
                            var inlineOutOfScope = _collectOutOfScopeChildrenForSourceIds(
                                    inItem, inlineExportSourceIds);
                            if (inlineOutOfScope.length > 0) {
                                savedInlineOutOfScopeItems = _hideItemsForExport(inlineOutOfScope);
                            }
                        }
                    } catch (eInlineOutOfScope) {}
                    try {
                        inItem.exportFile(ExportFormat.PNG_FORMAT, inOutFile);
                        _inlineExportOk = true;
                    } catch (eInlineExport) {
                        try { _inlineExportOk = File(renderDir + "/" + inFileName).exists; } catch (eExists) {}
                    }
                    try { app.pngExportPreferences.transparentBackground = false; } catch (e) {}
                    if (_inlineExportOk) inlineStats.exportOk++;
                    var _inlineWrittenFile = File(renderDir + "/" + inFileName);
                    // The source inline anchor is ownership evidence. PNG export
                    // status only describes the materialized visual file.
                    if (inItem) {
                        var inBounds = null;
                        try {
                            // 텍스트 숨김 상태에서는 visibleBounds가 축소될 수 있으므로 geometricBounds 우선
                            inBounds = arrCopy(inItem.geometricBounds);
                        } catch (eb) {}

                        var inPageIdx = -1;
                        try {
                            var inPage = _resolveParentPage(inItem, doc);
                            if (inPage) inPageIdx = inPage.documentOffset;
                        } catch (ep) {}
                        if (inPageIdx < 0 && _inlinePlanPageIdx !== null && _inlinePlanPageIdx !== undefined) {
                            inPageIdx = _inlinePlanPageIdx;
                        }
                        var parentStoryId = _parentStoryIdForInlineItem(inItem);

                        var _inlineSourceBounds = null;
                        try { _inlineSourceBounds = arrCopy(inItem.visibleBounds); } catch (eInlineVb) {}
                        if (!_inlineSourceBounds) {
                            try { _inlineSourceBounds = arrCopy(inItem.geometricBounds); } catch (eInlineGb) {}
                        }

                        var _inlineHasHiddenText = inlineHiddenTextFrameIds && inlineHiddenTextFrameIds.length > 0;
                        var _inlineBaseEntry = {
                            id: inId,
                            planPassId: "pass.inline_objects",
                            candidateId: _inlineCandidateMatch.candidateId,
                            candidateMatchStrategy: _inlineCandidateMatch.strategy,
                            file: "rendered_frames/" + inFileName,
                            parentStoryId: parentStoryId,
                            bounds: inBounds,
                            pageIndex: inPageIdx,
                            type: "inline_object",
                            placementRole: "inline_object",
                            visualOwner: "indesign_png",
                            textOwner: _inlineHasHiddenText ? "hwpx_tf" : "none",
                            containsText: false,
                            containsEditableText: false,
                            placementAllowed: true,
                            reason: _inlineHasHiddenText ? "inline_text_hidden" : "inline_graphic_only",
                            sourceObjectIds: _inlineEntrySourceIds,
                            childIds: inlineHiddenTextFrameIds,
                            exportSanity: {
                                fileBytes: _inlineWrittenFile.exists ? _inlineWrittenFile.length : 0,
                                exportOk: _inlineExportOk ? true : false,
                                guarded: false,
                                duplicatedForExport: false,
                                textHiddenBeforeExport: _inlineHasHiddenText,
                                exportTargetType: inItem && inItem.constructor ? inItem.constructor.name : null,
                                sourceBounds: _inlineSourceBounds,
                                pageRelativeBounds: inBounds
                            }
                        };
                        try {
                            inlineObjects.push(applyRenderOwnership(_inlineBaseEntry, inItem, {
                                textHiddenBeforeExport: _inlineHasHiddenText,
                                textOwner: _inlineBaseEntry.textOwner,
                                containsText: _inlineBaseEntry.containsText,
                                containsEditableText: _inlineBaseEntry.containsEditableText,
                                placementAllowed: true,
                                editableTextFrameIds: inlineHiddenTextFrameIds,
                                sourceObjectIds: _inlineEntrySourceIds,
                                exportSourceObjectIds: inlineCandidate.exportSourceObjectIds || [],
                                hiddenVisualSourceObjectIds: inlineCandidate.hiddenVisualSourceObjectIds || [],
                                exportTargetObjectId: inlineCandidate.exportTargetObjectId,
                                slotRole: inlineCandidate.slotRole || null,
                                renderMode: inlineCandidate.mode || null,
                                hiddenTextFrameIds: inlineHiddenTextFrameIds,
                                visualOnlyChildIds: inlineCandidate.exportSourceObjectIds
                                        && inlineCandidate.exportSourceObjectIds.length > 0
                                        ? inlineCandidate.exportSourceObjectIds
                                        : [inId],
                                reason: _inlineBaseEntry.reason
                            }));
                        } catch (eOwnership) {
                            _inlineBaseEntry.ownershipFallback = "inline_anchor_source_metadata";
                            try { _copyLayerInfo(_inlineBaseEntry, inItem); } catch (eLayer) {}
                            if (_inlineHasHiddenText) _inlineBaseEntry.textHiddenBeforeExport = true;
                            inlineObjects.push(_inlineBaseEntry);
                        }
                    }
                } catch (eRender) {}
                try {
                    if (savedInlineOutOfScopeItems && savedInlineOutOfScopeItems.length > 0) {
                        _restoreItemsForExport(savedInlineOutOfScopeItems);
                    }
                } catch (eRestoreInlineOutOfScope) {}
                restoreTextFrames(savedInlineTextFrames);
        } catch (eInline) {}
    }
    } finally {
        // localDisplaySetting 복원
        for (var ri = 0; ri < savedLocalSettings.length; ri++) {
            try { savedLocalSettings[ri].item.localDisplaySetting = savedLocalSettings[ri].setting; } catch (e) {}
        }
        // Display Performance 복원
        if (savedDisplayPerf !== null) {
            try { doc.viewPreferences.displayPerformance = savedDisplayPerf; } catch (e) {}
        }
        // transparentBackground 복원 — inline render가 false로 두고 예외 탈출할 경우 대비
        try { app.pngExportPreferences.transparentBackground = true; } catch (e) {}
    }

    // 인라인 객체를 결과에 추가
    for (var ioi = 0; ioi < inlineObjects.length; ioi++) {
        results.push(inlineObjects[ioi]);
    }
    inlineStats.resultItems = results.length;
    try { writeJson(outputDir + "/_inline_export_stats.json", inlineStats); } catch (eInlineStats) {}

    return { items: results, tableInlineRendered: tableInlineRendered };
}
