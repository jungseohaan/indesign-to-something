/**
 * Render planned image/graphic extraction candidates.
 *
 * This module must execute ObjectPlan/candidate decisions only.
 * It must not create new ownership, placement, or fallback shell decisions.
 */

/**
 * 복합 장식 그래픽 프레임을 PNG로 렌더링한다.
 */
function exportComplexGraphicFrames(doc, outputDir, startPage, endPage, itemById, complexCandidates) {
    var renderDir = Folder(outputDir + "/rendered_frames");
    renderDir.create();

    var renderedGraphicFrames = [];

    for (var i = 0; complexCandidates && i < complexCandidates.length; i++) {
        var candidate = complexCandidates[i];
        if (!candidate) continue;
        var sourceId = candidate.exportTargetObjectId !== undefined
                && candidate.exportTargetObjectId !== null
                ? candidate.exportTargetObjectId
                : candidate.primarySourceObjectId;
        if (sourceId === null || sourceId === undefined) {
            if (candidate.sourceObjectIds && candidate.sourceObjectIds.length > 0) {
                sourceId = candidate.sourceObjectIds[0];
            }
        }
        var item = itemById ? itemById[String(sourceId)] : null;
        if (!item) continue;
        var cName = item.constructor.name;
        var isCompositeSourceSetCandidate = candidate.sourceObjectIds
                && candidate.sourceObjectIds.length > 1;

        if (cName !== "Rectangle" && cName !== "Oval"
            && cName !== "Polygon") continue;
        if (isOnHiddenLayer(item)) continue;

        try {
            if (!isCompositeSourceSetCandidate
                    && item.contentType !== ContentType.GRAPHIC_TYPE) continue;
        } catch (e) { continue; }

        var hasPlacedContent = false;
        try { hasPlacedContent = item.images && item.images.length > 0; } catch (e) {}
        if (!hasPlacedContent) {
            try { hasPlacedContent = item.pdfs && item.pdfs.length > 0; } catch (e) {}
        }
        if (!hasPlacedContent) {
            try { hasPlacedContent = item.epss && item.epss.length > 0; } catch (e) {}
        }
        if (hasPlacedContent
                && !(candidate.exportTargetObjectId !== undefined
                    && candidate.exportTargetObjectId !== null
                    && String(candidate.exportTargetObjectId) === String(sourceId)
                    && candidate.exportSourceObjectIds
                    && candidate.exportSourceObjectIds.length > 0)) {
            continue;
        }

        var nestedItems = null;
        try { nestedItems = item.allPageItems; } catch (e) {}
        if (!nestedItems || nestedItems.length === 0) continue;

        var onlyTextFrames = true;
        try {
            for (var ni = 0; ni < nestedItems.length; ni++) {
                if (nestedItems[ni].constructor.name !== "TextFrame") {
                    onlyTextFrames = false;
                    break;
                }
            }
        } catch (e) { onlyTextFrames = false; }
        if (onlyTextFrames) continue;

        var parentPage = null;
        try { parentPage = item.parentPage; } catch (e) {}
        if (!parentPage) continue;
        var targetPage = parentPage;
        if (candidate.pageIndex !== null && candidate.pageIndex !== undefined
                && candidate.pageIndex >= 0) {
            try { targetPage = doc.pages[candidate.pageIndex]; } catch (eTargetPage) { targetPage = null; }
        }
        if (!targetPage) continue;
        var parentPageIndex = parentPage.documentOffset;
        var targetPageIndex = targetPage.documentOffset;
        if (!_sameSpreadPageIndex(doc, parentPageIndex, targetPageIndex)) continue;
        var pgIdx = targetPageIndex + 1;
        if (pgIdx < startPage || pgIdx > endPage) continue;
        var complexCandidateStrategy = candidate.sourceObjectIds && candidate.sourceObjectIds.length > 1
                ? "candidate_source_set_direct"
                : "candidate_direct";
        var complexCandidateMatch = _candidateMatch(candidate, complexCandidateStrategy);

        // 스프레드를 걸치는 프레임은 extraction/resolved 단계에서 page-local fragment로 기록되어야 한다.
        // 실행 단계(BackgroundInjector 등)는 여기서 결정된 bounds/cropSourceBounds만 따른다.

        var domId = item.id;
        var fileName = "graphic_" + domId + "_p" + (targetPageIndex + 1) + ".png";
        var outFile = File(renderDir + "/" + fileName);
        var _gPageIdx  = targetPageIndex;  // 단순 정수, 이미 검증됨
        var _gZOrder   = 0;   // try 블록 안에서 갱신
        var _gBounds   = null;
        var _gCropSourceBounds = null;
        var _gExportOk = false;
        var _gChildIds = [];
        var _gTfInlineVisualIds = [];
        var _gSourceIds = [];
        var _gEditableTextFrameIds = [];
        var _gTextFrameIds = [];
        var _gVisualOnlyChildIds = [];
        var _gSavedOutOfScopeChildren = null;
        var hiddenTFs = null;

        try {
            // bounds를 try 안에서 계산 (visibleBounds/geometricBounds도 예외 가능)
            try { _gBounds = arrCopy(item.visibleBounds); } catch (e) {}
            if (!_gBounds) { try { _gBounds = arrCopy(item.geometricBounds); } catch (e) {} }
            if (_gBounds) {
                var _gOriginalBounds = arrCopy(_gBounds);
                var _gPageIntersection = null;
                try { _gPageIntersection = _boundsIntersectionStrict(_gOriginalBounds, targetPage.bounds); } catch (eIntersect) {}
                if (!_gPageIntersection) continue;
                if (_boundsDiffer(_gOriginalBounds, _gPageIntersection, 0.01)) {
                    _gCropSourceBounds = _pageRelativeBoundsCopy(_gOriginalBounds, targetPage);
                }
                _gBounds = _pageRelativeBoundsCopy(_gPageIntersection, targetPage);
            }

            var _gCandidateSourceIds = candidate.exportSourceObjectIds && candidate.exportSourceObjectIds.length > 0
                    ? candidate.exportSourceObjectIds
                    : (candidate.visualSourceObjectIds && candidate.visualSourceObjectIds.length > 0
                            ? candidate.visualSourceObjectIds
                            : (candidate.sourceObjectIds && candidate.sourceObjectIds.length > 0
                                    ? candidate.sourceObjectIds
                                    : null));
            if (_gCandidateSourceIds && _gCandidateSourceIds.length > 0) {
                var _gOutOfScopeChildren = _collectOutOfScopeChildrenForSourceIds(item, _gCandidateSourceIds);
                if (_gOutOfScopeChildren.length > 0) {
                    _gSavedOutOfScopeChildren = _hideItemsForExport(_gOutOfScopeChildren);
                }
            }
            _gTfInlineVisualIds = collectTfInlineVisualIdsFromNestedItems(item, nestedItems);
            hiddenTFs = hideTextFramesAndOwnedInlineVisualsFromNestedItems(nestedItems, _gTfInlineVisualIds);
            try {
                item.exportFile(ExportFormat.PNG_FORMAT, outFile);
                _gExportOk = true;
            } catch (eExp) {
                try { _gExportOk = outFile.exists; } catch (e2) {}
            }

            // zOrder/childIds를 restore 전에 저장 — restore 예외가 push를 막지 않도록.
            if (_gExportOk) {
                try { _gZOrder = getItemZOrder(item); } catch (e) {}
                try {
                    for (var ci = 0; ci < nestedItems.length; ci++) {
                        if (_gCandidateSourceIds
                                && !_sourceObjectIdsAllowId(_gCandidateSourceIds, nestedItems[ci].id)) {
                            continue;
                        }
                        _gChildIds.push(nestedItems[ci].id);
                    }
                } catch (e2) {}
                _gSourceIds = _gCandidateSourceIds && _gCandidateSourceIds.length > 0
                        ? _gCandidateSourceIds
                        : _collectSourceObjectIdsFromNestedItems(item, nestedItems);
                _gEditableTextFrameIds = _collectTextFrameIdsFromNestedItems(item, nestedItems, true, true);
                _gTextFrameIds = _collectTextFrameIdsFromNestedItems(item, nestedItems, false, true);
                _gVisualOnlyChildIds = _collectVisualOnlyChildIdsFromNestedItems(nestedItems);
                if (_gCandidateSourceIds && _gCandidateSourceIds.length > 0) {
                    _gTfInlineVisualIds = _filterIdsAllowedBySourceObjectIds(_gTfInlineVisualIds, _gCandidateSourceIds);
                    _gEditableTextFrameIds = _filterIdsAllowedBySourceObjectIds(_gEditableTextFrameIds, _gCandidateSourceIds);
                    _gTextFrameIds = _filterIdsAllowedBySourceObjectIds(_gTextFrameIds, _gCandidateSourceIds);
                    _gVisualOnlyChildIds = _filterIdsAllowedBySourceObjectIds(_gVisualOnlyChildIds, _gCandidateSourceIds);
                }
            }

        } catch (e) {
            try { _gExportOk = outFile.exists; } catch (e2) {}
        } finally {
            try { if (hiddenTFs && hiddenTFs.length > 0) restoreTextFrames(hiddenTFs); } catch (e3) {}
            try { if (_gSavedOutOfScopeChildren && _gSavedOutOfScopeChildren.length > 0) _restoreItemsForExport(_gSavedOutOfScopeChildren); } catch (e4) {}
        }

        if (_gExportOk) {
            var _gEntry = {
                id: domId,
                file: "rendered_frames/" + fileName,
                bounds: _gBounds,
                pageIndex: _gPageIdx,
                candidateId: complexCandidateMatch.candidateId,
                candidateMatchStrategy: complexCandidateMatch.strategy,
                childIds: _gChildIds.length > 0 ? _gChildIds : undefined,
                zOrder: _gZOrder
            };
            if (_gCropSourceBounds) _gEntry.cropSourceBounds = _gCropSourceBounds;
            renderedGraphicFrames.push(applyRenderOwnership(_gEntry, item, {
                textHiddenBeforeExport: true,
                tfInlineVisualIds: _gTfInlineVisualIds,
                sourceObjectIds: _gSourceIds,
                editableTextFrameIds: _gEditableTextFrameIds,
                textFrameIds: _gTextFrameIds,
                visualOnlyChildIds: _gVisualOnlyChildIds,
                reason: "complex_graphic_text_hidden"
            }));
        }
    }

    return renderedGraphicFrames;
}

/**
 * PDF 배치 프레임을 PNG로 렌더링한다.
 */
/**
 * 이미지 배치 프레임을 처리한다.
 *
 * standalone 프레임 (그룹 없음): 소스 파일 직접 복사 — exportFile 불필요, 메모리 0.
 * 그룹 내 프레임 (텍스트/도형과 함께): exportFile(PNG) + 아이템별 $.gc().
 *
 * 이전 exportFile 전용 방식은 57개 이상 처리 시 InDesign 메모리 고갈 크래시 발생.
 */
function exportImagePlacedFrames(doc, outputDir, startPage, endPage,
                                 itemById, imageGroupCandidates, imagePlacedCandidates) {
    var renderDir = Folder(outputDir + "/rendered_frames");
    renderDir.create();

    var renderedImageFrames = [];
    var processedGroupIds = {};
    var imageExportCache = {};
    var bgPolyExportCache = {};
    var imageRenderDiagnostics = [];

    function _itemForCandidate(candidate, preferredKind) {
        if (!candidate) return null;
        var sourceId = candidate.primarySourceObjectId;
        if (sourceId !== null && sourceId !== undefined && itemById) {
            var primary = itemById[String(sourceId)];
            if (primary && (!preferredKind || primary.constructor.name === preferredKind)) return primary;
        }
        if (candidate.sourceObjectIds && itemById) {
            for (var si = 0; si < candidate.sourceObjectIds.length; si++) {
                var item = itemById[String(candidate.sourceObjectIds[si])];
                if (item && (!preferredKind || item.constructor.name === preferredKind)) return item;
            }
        }
        return null;
    }

    function _hasPlacedInItem(item, placedName) {
        try {
            if (placedName === "pdf" && item.pdfs && item.pdfs.length > 0) return true;
            if (placedName === "image" && item.images && item.images.length > 0) return true;
        } catch (eSelf) {}
        try {
            var nested = item.allPageItems;
            for (var ni = 0; ni < nested.length; ni++) {
                try {
                    if (placedName === "pdf" && nested[ni].pdfs && nested[ni].pdfs.length > 0) return true;
                    if (placedName === "image" && nested[ni].images && nested[ni].images.length > 0) return true;
                } catch (eNested) {}
            }
        } catch (e) {}
        return false;
    }

    function _candidatePageIndex(candidate) {
        try {
            if (!candidate || candidate.pageIndex === null || candidate.pageIndex === undefined) return -1;
            var pageIndex = Number(candidate.pageIndex);
            return pageIndex >= 0 ? pageIndex : -1;
        } catch (e) {}
        return -1;
    }

    function _fragmentsForCandidatePage(fragments, candidate) {
        var candidatePageIndex = _candidatePageIndex(candidate);
        if (candidatePageIndex < 0) return fragments || [];
        var out = [];
        for (var i = 0; fragments && i < fragments.length; i++) {
            if (fragments[i] && fragments[i].pageIndex === candidatePageIndex) out.push(fragments[i]);
        }
        return out;
    }

    function _imageCandidateMatchForFragment(passId, item, sourceObjectIds, isSourceSetCandidate, fragment) {
        if (!fragment || fragment.pageIndex === null || fragment.pageIndex === undefined) return null;
        var match = null;
        if (isSourceSetCandidate && sourceObjectIds && sourceObjectIds.length > 1) {
            match = _findPlannedExtractionSourceSet(passId, sourceObjectIds, fragment.pageIndex);
            return match && match.candidate
                    ? _candidateMatch(match.candidate, "candidate_source_set_direct")
                    : null;
        }
        match = _findPlannedExtractionCandidate(passId, item, fragment.pageIndex);
        return match && match.candidate ? _candidateMatch(match.candidate, "candidate_direct") : null;
    }

    function _imageExportCacheKey(passId, item, isGroupRender) {
        var id = null;
        try { id = item && item.id !== undefined ? item.id : null; } catch (e) {}
        return String(passId || "") + "|" + (isGroupRender ? "group" : "item") + "|" + String(id);
    }

    var plannedEntries = [];
    for (var gci = 0; imageGroupCandidates && gci < imageGroupCandidates.length; gci++) {
        plannedEntries.push({ candidate: imageGroupCandidates[gci], isGroupRender: true });
    }
    for (var pci = 0; imagePlacedCandidates && pci < imagePlacedCandidates.length; pci++) {
        plannedEntries.push({ candidate: imagePlacedCandidates[pci], isGroupRender: false });
    }

    for (var i = 0; i < plannedEntries.length; i++) {
        var planned = plannedEntries[i];
        var candidate = planned.candidate;
        var isGroupRender = planned.isGroupRender;
        var diag = {
            candidateId: candidate ? candidate.candidateId : null,
            passId: candidate ? candidate.passId : null,
            pageIndex: candidate ? candidate.pageIndex : null,
            sourceObjectIds: candidate ? candidate.sourceObjectIds : null,
            exportSourceObjectIds: candidate ? candidate.exportSourceObjectIds : null,
            isGroupRender: isGroupRender,
            stage: "start",
            result: "pending"
        };
        var renderTarget = isGroupRender
                ? _itemForCandidate(candidate, "Group")
                : _itemForCandidate(candidate, null);
        if (!renderTarget) {
            diag.result = "skipped";
            diag.stage = "resolve_target";
            diag.reason = "render_target_not_found";
            imageRenderDiagnostics.push(diag);
            continue;
        }
        var item = renderTarget;
        var cName = item.constructor.name;
        diag.renderTargetId = item.id;
        diag.renderTargetKind = cName;

        if (isGroupRender) {
            if (cName !== "Group") {
                diag.result = "skipped";
                diag.stage = "kind_check";
                diag.reason = "group_candidate_target_not_group";
                imageRenderDiagnostics.push(diag);
                continue;
            }
        } else if (cName !== "Rectangle" && cName !== "Oval" && cName !== "Polygon") {
            diag.result = "skipped";
            diag.stage = "kind_check";
            diag.reason = "placed_candidate_target_kind_unsupported";
            imageRenderDiagnostics.push(diag);
            continue;
        }
        if (isOnHiddenLayer(item)) {
            diag.result = "skipped";
            diag.stage = "visibility_check";
            diag.reason = "hidden_layer";
            imageRenderDiagnostics.push(diag);
            continue;
        }

        var hasPdf = _hasPlacedInItem(item, "pdf");
        var hasImage = _hasPlacedInItem(item, "image");
        diag.hasPdf = hasPdf;
        diag.hasImage = hasImage;
        if (!hasImage && !hasPdf) {
            diag.result = "skipped";
            diag.stage = "placed_content_check";
            diag.reason = "no_placed_content_detected";
            imageRenderDiagnostics.push(diag);
            continue;
        }

        if (!isGroupRender) {
            try {
                if (item.parent && item.parent.constructor.name === "Group" && processedGroupIds[item.parent.id]) {
                    if (candidate && candidate.required === true) {
                        diag.stage = "group_dedup";
                        diag.parentGroupId = item.parent.id;
                        diag.groupDedupBypassed = true;
                    } else {
                    diag.result = "skipped";
                    diag.stage = "group_dedup";
                    diag.reason = "parent_group_already_rendered";
                    diag.parentGroupId = item.parent.id;
                    imageRenderDiagnostics.push(diag);
                    continue;
                    }
                }
            } catch (eProcessedGroup) {}
        }

        var parentPage = null;
        try { parentPage = renderTarget.parentPage; } catch (e) {}
        if (!parentPage) {
            try {
                var p = renderTarget.parent;
                while (p && !parentPage) {
                    try { parentPage = p.parentPage; } catch (e2) {}
                    if (!parentPage) p = p.parent;
                }
            } catch (e) {}
        }
        if (!parentPage && candidate && candidate.pageIndex !== null && candidate.pageIndex !== undefined) {
            try { parentPage = doc.pages[Number(candidate.pageIndex)]; } catch (eCandidateParentPage) {}
        }
        if (!parentPage) {
            diag.result = "skipped";
            diag.stage = "page_resolution";
            diag.reason = "parent_page_not_found";
            imageRenderDiagnostics.push(diag);
            continue;
        }
        if (candidate && candidate.pageIndex !== null && candidate.pageIndex !== undefined) {
            try {
                var candidatePage = doc.pages[Number(candidate.pageIndex)];
                if (candidatePage) parentPage = candidatePage;
            } catch (eCandidatePageOverride) {}
        }
        try { diag.parentPageIndex = parentPage.documentOffset; } catch (eDiagParentPage) {}

        var domId = renderTarget.id;
        var imagePlanPassId = isGroupRender ? "pass.image_textless_groups" : "pass.image_placed_frames";
        var imageSourceIds = candidate.sourceObjectIds && candidate.sourceObjectIds.length > 0
                ? candidate.sourceObjectIds
                : (isGroupRender ? _collectSourceObjectIds(renderTarget) : [domId]);
        var imageIsSourceSetCandidate = imageSourceIds && imageSourceIds.length > 1;

        var sourceBounds = null;
        try { sourceBounds = arrCopy(renderTarget.visibleBounds); } catch (e) {}
        if (!sourceBounds) try { sourceBounds = arrCopy(renderTarget.geometricBounds); } catch (e) {}
        var pageFragments = _pageLocalVisualFragmentsForBounds(doc, parentPage, sourceBounds, startPage, endPage);
        var candidatePageFragments = _fragmentsForCandidatePage(pageFragments, candidate);
        if ((!candidatePageFragments || candidatePageFragments.length === 0)
                && candidate
                && candidate.pageIndex !== null && candidate.pageIndex !== undefined
                && candidate.bounds && candidate.bounds.length === 4) {
            candidatePageFragments = [{
                page: parentPage,
                pageIndex: Number(candidate.pageIndex),
                bounds: arrCopy(candidate.bounds),
                cropSourceBounds: null
            }];
            pageFragments = candidatePageFragments.slice(0);
            diag.fragmentFallback = "candidate_bounds";
        }
        diag.pageFragmentCount = pageFragments ? pageFragments.length : 0;
        diag.candidatePageFragmentCount = candidatePageFragments ? candidatePageFragments.length : 0;
        if (!candidatePageFragments || candidatePageFragments.length === 0) {
            diag.result = "skipped";
            diag.stage = "fragment_resolution";
            diag.reason = "candidate_page_fragments_empty";
            imageRenderDiagnostics.push(diag);
            continue;
        }
        var sourceNeedsPageCrop = _fragmentsNeedCropSource(candidatePageFragments);

        if (!isGroupRender && hasImage) {
            // standalone: 소스 파일 직접 복사 (exportFile 없음 → 메모리 부하 0)
            var srcPath = null;
            var srcExt = "jpg";
            try {
                var lnk = item.images[0].itemLink;
                if (lnk && lnk.filePath) {
                    srcPath = lnk.filePath;
                    var m = srcPath.match(/\.([^.]+)$/);
                    if (m) srcExt = m[1].toLowerCase();
                }
            } catch (e) {}

            // JPEG/PNG만 직접 복사; 그 외(PSD/AI/EPS)는 exportFile 경로로 fallback
            var isCopyable = (srcExt === "jpg" || srcExt === "jpeg" || srcExt === "png"
                              || srcExt === "gif" || srcExt === "bmp");
            if (srcPath && isCopyable && candidatePageFragments.length === 1 && !sourceNeedsPageCrop) {
                var srcFile = File(srcPath);
                if (!srcFile.exists) {
                    // 상대 경로 폴백: document folder 기준
                    try {
                        var docFolder = Folder(doc.filePath);
                        srcFile = File(docFolder + "/" + srcPath);
                    } catch (e) {}
                }
                if (srcFile.exists) {
                    var copyFragment = candidatePageFragments[0];
                    var copyCandidateMatch = _imageCandidateMatchForFragment(
                            imagePlanPassId, renderTarget, imageSourceIds, imageIsSourceSetCandidate, copyFragment);
                    if (!copyCandidateMatch) {
                        diag.result = "skipped";
                        diag.stage = "candidate_match";
                        diag.reason = "copy_path_candidate_match_failed";
                        imageRenderDiagnostics.push(diag);
                        continue;
                    }
                    var dstFileName = "img_" + domId + "." + srcExt;
                    var dstFile = File(renderDir + "/" + dstFileName);
                    try { srcFile.copy(dstFile); } catch (e) {}
                    renderedImageFrames.push(applyRenderOwnership({
                        id: domId,
                        file: "rendered_frames/" + dstFileName,
                        imageFormat: srcExt,
                        bounds: copyFragment.bounds,
                        pageIndex: copyFragment.pageIndex,
                        candidateId: copyCandidateMatch.candidateId,
                        candidateMatchStrategy: copyCandidateMatch.strategy,
                        childImageIds: null
                    }, item, {
                        textOwner: "none",
                        sourceObjectIds: imageSourceIds,
                        reason: "standalone_image_copy"
                    }));
                    diag.result = "rendered";
                    diag.stage = "copy_path";
                    diag.outputFile = dstFileName;
                    diag.matchStrategy = copyCandidateMatch.strategy;
                    imageRenderDiagnostics.push(diag);
                    continue;
                }
                // 소스 파일 없음 → exportFile fallback
            }
            // isCopyable 아니거나 경로 없음 → exportFile fallback (아래 공통 경로)
        }

        // 그룹 렌더링 또는 exportFile fallback
        var fileName = "img_" + domId + ".png";
        var outFile = File(renderDir + "/" + fileName);
        var _imgZOrder   = 0;   // try 블록 안에서 갱신
        var _imgChildIds = null;
        var _imgExportOk = false;
        var _imgBgPolygons = [];
        var _imgHiddenTextFrameIds = [];
        var _imgTfInlineVisualIds = [];
        var hiddenTFs = [];
        var _imgExportCacheKey = _imageExportCacheKey(imagePlanPassId, renderTarget, isGroupRender);
        var _imgCachedExport = imageExportCache[_imgExportCacheKey];
        var _imgExportError = null;
        if (_imgCachedExport) {
            _marker(outputDir, "08_img_" + domId + "_cache");
            fileName = _imgCachedExport.fileName || fileName;
            _imgZOrder = _imgCachedExport.zOrder || 0;
            _imgChildIds = _imgCachedExport.childImageIds || null;
            _imgBgPolygons = _imgCachedExport.bgPolygons || [];
            _imgHiddenTextFrameIds = _imgCachedExport.hiddenTextFrameIds || [];
            _imgTfInlineVisualIds = _imgCachedExport.tfInlineVisualIds || [];
            _imgExportOk = true;
            diag.stage = "cache_hit";
        } else {
        try {
            _marker(outputDir, "08_img_" + domId + "_hide");
            _imgTfInlineVisualIds = isGroupRender ? collectTfInlineVisualIds(renderTarget) : [];
            hiddenTFs = isGroupRender ? hideTextFramesAndOwnedInlineVisuals(renderTarget) : [];
            _imgHiddenTextFrameIds = _hiddenTextFrameIdsFromSaved(hiddenTFs);

            // 그룹 직계 자식 중 배경 폴리곤(Polygon, 배치 이미지 없음, non-None fill)을
            // 그룹 PNG 내보내기 전 임시 숨김. 배경 폴리곤을 이미지와 합성하면
            // S자 밴드 등 선명한 경계가 이미지 콘텐츠에 묻혀 소실될 수 있음.
            // 이후 폴리곤을 개별 PNG로 내보내 BackgroundInjector에서 그룹 뒤에 배치.
            if (isGroupRender) {
                try {
                    var directChildren = renderTarget.pageItems;
                    for (var bpi = 0; bpi < directChildren.length; bpi++) {
                        var bgChild = directChildren[bpi];
                        if (bgChild.constructor.name !== "Polygon") continue;
                        var bgVisible = true;
                        try { bgVisible = bgChild.visible; } catch (e2) {}
                        if (!bgVisible) continue;
                        var bgHasPlaced = false;
                        try { bgHasPlaced = bgChild.images && bgChild.images.length > 0; } catch (e2) {}
                        if (!bgHasPlaced) try { bgHasPlaced = bgChild.pdfs && bgChild.pdfs.length > 0; } catch (e2) {}
                        if (bgHasPlaced) continue;
                        var bgHasFill = false;
                        try {
                            var bgFc = bgChild.fillColor;
                            bgHasFill = bgFc && bgFc.name && bgFc.name !== "None";
                        } catch (e2) {}
                        if (!bgHasFill) continue;
                        _imgBgPolygons.push(bgChild);
                        try { bgChild.visible = false; } catch (e2) {}
                    }
                } catch (e) {}
            }

            _marker(outputDir, "08_img_" + domId + "_export");
            try {
                renderTarget.exportFile(ExportFormat.PNG_FORMAT, outFile);
                _imgExportOk = true;
            } catch (eExp) {
                try { _imgExportError = String(eExp); } catch (eExpString) { _imgExportError = "export_exception"; }
                try { _imgExportOk = outFile.exists; } catch (e2) {}
            }
            _marker(outputDir, "08_img_" + domId + "_exportDone");

            // zOrder/childIds를 restore 전에 저장 — restore 예외가 push를 막지 않도록.
            if (_imgExportOk) {
                try { _imgZOrder = getItemZOrder(renderTarget); } catch (e) {}
                if (isGroupRender) {
                    _imgChildIds = [];
                    try {
                        var nested = renderTarget.allPageItems;
                        for (var ci = 0; ci < nested.length; ci++) {
                            _imgChildIds.push(nested[ci].id);
                        }
                    } catch (e) {}
                }
            }

            try { if (hiddenTFs.length > 0) restoreTextFrames(hiddenTFs); } catch (e) {}

            // 배경 폴리곤 가시성 복원
            for (var bri = 0; bri < _imgBgPolygons.length; bri++) {
                try { _imgBgPolygons[bri].visible = true; } catch (e2) {}
            }
            _marker(outputDir, "08_img_" + domId + "_restoreDone");

            try { $.gc(); } catch (gcErr) {}  // 아이템별 GC: 누적 메모리 해제
            _marker(outputDir, "08_img_" + domId + "_gcDone");
        } catch (e) {
            try { _imgExportError = String(e); } catch (eOuterString) { _imgExportError = "outer_export_exception"; }
            try { _imgExportOk = outFile.exists; } catch (e2) {}
            // outer catch: inner try-catch를 우회한 예외가 있어도 TF/bgPolygon 복원
            try { if (hiddenTFs && hiddenTFs.length > 0) restoreTextFrames(hiddenTFs); } catch (e3) {}
            for (var _brf = 0; _brf < _imgBgPolygons.length; _brf++) {
                try { _imgBgPolygons[_brf].visible = true; } catch (e3) {}
            }
        }
        if (_imgExportOk) {
            imageExportCache[_imgExportCacheKey] = {
                fileName: fileName,
                zOrder: _imgZOrder,
                childImageIds: _imgChildIds,
                bgPolygons: _imgBgPolygons,
                hiddenTextFrameIds: _imgHiddenTextFrameIds,
                tfInlineVisualIds: _imgTfInlineVisualIds
            };
            if (isGroupRender) {
                processedGroupIds[renderTarget.id] = true;
            }
        }
        }

        // push는 try 블록 밖에서 실행 — restore/gc 예외가 발생해도 반드시 등록.
        if (_imgExportOk) {
            var _imgRenderedCount = 0;
            var _imgActuallyHidText = isGroupRender && _imgHiddenTextFrameIds && _imgHiddenTextFrameIds.length > 0;
            // 그룹 항목을 배경 폴리곤보다 먼저 등록
            // BackgroundInjector.addBlockAtFront 특성상 나중 등록 항목이 XML 앞에 위치 →
            // 그룹(일러스트)이 폴리곤(S자 밴드) 위에 렌더링됨
            for (var _imgFi = 0; _imgFi < candidatePageFragments.length; _imgFi++) {
                var _imgFragment = candidatePageFragments[_imgFi];
                var _imgCandidateMatch = _imageCandidateMatchForFragment(
                        imagePlanPassId, renderTarget, imageSourceIds, imageIsSourceSetCandidate, _imgFragment);
                if (!_imgCandidateMatch) continue;
                _imgRenderedCount++;
                var _imgEntry = {
                    id: domId,
                    planPassId: imagePlanPassId,
                    file: "rendered_frames/" + fileName,
                    bounds: _imgFragment.bounds,
                    pageIndex: _imgFragment.pageIndex,
                    candidateId: _imgCandidateMatch.candidateId,
                    candidateMatchStrategy: _imgCandidateMatch.strategy,
                    childImageIds: _imgChildIds,
                    zOrder: _imgZOrder
                };
                if (_imgFragment.cropSourceBounds) _imgEntry.cropSourceBounds = _imgFragment.cropSourceBounds;
                renderedImageFrames.push(applyRenderOwnership(_imgEntry, renderTarget, {
                    sourceObjectIds: imageSourceIds,
                    textHiddenBeforeExport: _imgActuallyHidText,
                    hiddenTextFrameIds: _imgHiddenTextFrameIds,
                    tfInlineVisualIds: _imgTfInlineVisualIds,
                    reason: isGroupRender
                            ? (_imgActuallyHidText ? "image_group_text_hidden" : "image_group_visual")
                            : (hasPdf ? "pdf_export" : "image_export")
                }));
            }

            // 배경 폴리곤 개별 PNG 내보내기 및 등록
            for (var bei = 0; bei < _imgBgPolygons.length; bei++) {
                var bgPoly = _imgBgPolygons[bei];
                var bgPolyDomId = bgPoly.id;
                var bgPolyFileName = "shape_" + bgPolyDomId + ".png";
                var bgPolyCacheKey = _imgExportCacheKey + "|bg|" + String(bgPolyDomId);
                var bgPolyCachedExport = bgPolyExportCache[bgPolyCacheKey];
                try {
                    var bgPolySourceBounds = null;
                    var bgPolyZOrder = 0;
                    if (bgPolyCachedExport) {
                        bgPolyFileName = bgPolyCachedExport.fileName || bgPolyFileName;
                        bgPolySourceBounds = bgPolyCachedExport.sourceBounds || null;
                        bgPolyZOrder = bgPolyCachedExport.zOrder || 0;
                    } else {
                        var bgPolyOutFile = File(renderDir + "/" + bgPolyFileName);
                        bgPoly.exportFile(ExportFormat.PNG_FORMAT, bgPolyOutFile);
                        try { bgPolySourceBounds = arrCopy(bgPoly.visibleBounds); } catch (e2) {}
                        if (!bgPolySourceBounds) try { bgPolySourceBounds = arrCopy(bgPoly.geometricBounds); } catch (e2) {}
                        try { bgPolyZOrder = getItemZOrder(bgPoly); } catch (e2) {}
                        bgPolyExportCache[bgPolyCacheKey] = {
                            fileName: bgPolyFileName,
                            sourceBounds: bgPolySourceBounds,
                            zOrder: bgPolyZOrder
                        };
                    }
                    if (!bgPolySourceBounds) continue;
                    var bgPolyFragments = _pageLocalVisualFragmentsForBounds(doc, parentPage, bgPolySourceBounds, startPage, endPage);
                    var bgPolyCandidateFragments = _fragmentsForCandidatePage(bgPolyFragments, candidate);
                    for (var _bgFi = 0; bgPolyCandidateFragments && _bgFi < bgPolyCandidateFragments.length; _bgFi++) {
                        var _bgFragment = bgPolyCandidateFragments[_bgFi];
                        var _bgCandidateMatch = _imageCandidateMatchForFragment(
                                imagePlanPassId, renderTarget, imageSourceIds, imageIsSourceSetCandidate, _bgFragment);
                        if (!_bgCandidateMatch) continue;
                        var _bgEntry = {
                            id: bgPolyDomId,
                            planPassId: imagePlanPassId,
                            file: "rendered_frames/" + bgPolyFileName,
                            bounds: _bgFragment.bounds,
                            pageIndex: _bgFragment.pageIndex,
                            candidateId: _bgCandidateMatch.candidateId,
                            candidateMatchStrategy: _bgCandidateMatch.strategy,
                            childImageIds: null,
                            zOrder: bgPolyZOrder
                        };
                        if (_bgFragment.cropSourceBounds) _bgEntry.cropSourceBounds = _bgFragment.cropSourceBounds;
                        renderedImageFrames.push(applyRenderOwnership(_bgEntry, bgPoly, {
                            textOwner: "none",
                            reason: "image_group_background_polygon"
                        }));
                    }
                } catch (e2) {}
            }
            diag.result = _imgRenderedCount > 0 ? "rendered" : "skipped";
            diag.stage = "export_complete";
            diag.outputFile = fileName;
            diag.renderedFragmentCount = _imgRenderedCount;
            if (_imgExportError) diag.exportError = _imgExportError;
            if (_imgRenderedCount === 0) diag.reason = "export_ok_but_candidate_match_failed";
            imageRenderDiagnostics.push(diag);
        } else {
            diag.result = "skipped";
            diag.stage = "export_complete";
            diag.reason = "export_failed";
            diag.outputFile = fileName;
            if (_imgExportError) diag.exportError = _imgExportError;
            imageRenderDiagnostics.push(diag);
        }
    }

    try { writeJson(outputDir + "/render-image-candidate-diagnostics.json", imageRenderDiagnostics); } catch (eImageDiagWrite) {}
    return renderedImageFrames;
}

/**
 * 여러 비텍스트 visual source가 한 컨테이너 배경을 구성하는 경우를 원본 IDML에서 한 PNG로 export한다.
 *
 * 정책 의도:
 * - Java에서 이미 추출된 큰 PNG를 crop/합성하지 않는다.
 * - TextFrame은 포함하지 않는다.
 * - 배경 이미지/도형 조각을 source 단위로 다시 export하고, ObjectPlan이 같은 source의 개별 visual을 drop한다.
 *
 * 대표 케이스:
 * - 칠판/보드 배경 이미지 + 내부 Paper/흰 패널 + 상단 리본/장식.
 */
