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

function exportSingleTextlessPagePlanes(doc, outputDir, startPage, endPage,
                                        allItems, itemById, inlineCandidates, opts) {
    opts = opts || {};
    var renderDir = Folder(outputDir + "/rendered_frames");
    renderDir.create();

    var savedRes = null;
    var savedTransparent = null;
    var savedQuality = null;
    var savedSpread = null;
    var savedPageString = null;
    var savedDisplayPerf = null;
    var hadPageString = false;
    var results = [];
    var diagnostics = [];
    var startedAt = (new Date()).getTime();

    function nowMs() {
        try { return (new Date()).getTime(); } catch (eNow) { return 0; }
    }

    function pageLocalBounds(page) {
        try {
            var pb = page.bounds;
            return [0, 0, Number(pb[2]) - Number(pb[0]), Number(pb[3]) - Number(pb[1])];
        } catch (eBounds) {}
        return null;
    }

    function inlineItemForCandidate(candidate) {
        if (!candidate || !itemById) return null;
        if (candidate.exportTargetObjectId !== null && candidate.exportTargetObjectId !== undefined) {
            var exportTarget = itemById[String(candidate.exportTargetObjectId)];
            if (exportTarget) return exportTarget;
        }
        if (candidate.primarySourceObjectId !== null && candidate.primarySourceObjectId !== undefined) {
            var primary = itemById[String(candidate.primarySourceObjectId)];
            if (primary) return primary;
        }
        var sourceIds = candidate.sourceObjectIds || [];
        for (var si = 0; si < sourceIds.length; si++) {
            var item = itemById[String(sourceIds[si])];
            if (item) return item;
        }
        return null;
    }

    function collectInlineItemsToHide() {
        var items = [];
        var seen = {};
        for (var i = 0; inlineCandidates && i < inlineCandidates.length; i++) {
            var c = inlineCandidates[i];
            if (!c || c.visualAction === "DROP_VISUAL") continue;
            if (c.materialization === "HWPX_TEXT" || c.materialization === "HWPX_TABLE_STYLE") continue;
            var item = inlineItemForCandidate(c);
            if (!item) continue;
            // Planned inline visuals must be removed from the page plane even
            // when the export target itself is a TextFrame shape.  The later
            // text-hiding pass preserves TextFrame fill/stroke by design, so
            // excluding TextFrames here bakes inline checkbox/marker shells
            // into page_textless_plane_p*.png.
            var id = null;
            try { id = item.id; } catch (eId) {}
            var key = id !== null && id !== undefined ? String(id) : String(items.length);
            if (seen[key]) continue;
            seen[key] = true;
            items.push(item);
        }
        if (opts.inlineFallbackAllItems === true) {
            var includeAllFallbackInlineItems = !inlineCandidates || inlineCandidates.length === 0;
            function addFallbackInlineItem(fallbackItem) {
                try {
                    if (includeAllFallbackInlineItems) {
                        if (!isPagePlaneInlineVisualItem(fallbackItem)) return;
                    } else if (!isPagePlaneInlineTextFrameItem(fallbackItem)) {
                        return;
                    }
                } catch (eInlineFallback) {
                    return;
                }
                var fallbackId = null;
                try { fallbackId = fallbackItem.id; } catch (eFallbackId) {}
                var fallbackKey = fallbackId !== null && fallbackId !== undefined
                        ? String(fallbackId)
                        : "fallback:" + String(items.length);
                if (seen[fallbackKey]) return;
                seen[fallbackKey] = true;
                items.push(fallbackItem);
            }
            for (var ai = 0; allItems && ai < allItems.length; ai++) {
                addFallbackInlineItem(allItems[ai]);
            }
            try {
                var textFrames = doc.textFrames.everyItem().getElements();
                for (var tf = 0; textFrames && tf < textFrames.length; tf++) {
                    addFallbackInlineItem(textFrames[tf]);
                }
            } catch (eFallbackDocTextFrames) {}
            try {
                var pageItems = doc.allPageItems;
                for (var dpi = 0; pageItems && dpi < pageItems.length; dpi++) {
                    addFallbackInlineItem(pageItems[dpi]);
                    try {
                        var nestedTextFrames = pageItems[dpi].textFrames.everyItem().getElements();
                        for (var ntf = 0; nestedTextFrames && ntf < nestedTextFrames.length; ntf++) {
                            addFallbackInlineItem(nestedTextFrames[ntf]);
                        }
                    } catch (eNestedTextFrames) {}
                }
            } catch (eFallbackDocPageItems) {}
        }
        return items;
    }

    function collectTableStyleSourceItemsToHide() {
        var items = [];
        var seen = {};
        var idsByPage = opts.hiddenTableStyleSourceObjectIdsByPage || {};
        for (var pageNumber = startPage; pageNumber <= endPage; pageNumber++) {
            var pageIndex = pageNumber - 1;
            var ids = idsByPage[String(pageIndex)] || [];
            for (var i = 0; i < ids.length; i++) {
                var id = ids[i];
                if (id === null || id === undefined) continue;
                var key = String(id);
                if (seen[key]) continue;
                var item = itemById ? itemById[key] : null;
                if (!item) continue;
                seen[key] = true;
                items.push(item);
            }
        }
        return items;
    }

    function isPagePlaneInlineTextFrameItem(item) {
        if (!item) return false;
        try {
            if (!item.constructor || item.constructor.name !== "TextFrame") return false;
            if (isInlineItem(item) || isStoryAnchoredInlineItem(item)) return true;
            try {
                var anchored = String(item.anchoredObjectSettings.anchoredPosition || "").toUpperCase();
                if (anchored === "INLINE_POSITION" || anchored === "INLINEPOSITION") return true;
            } catch (eAnchoredPosition) {}
        } catch (eTextFrameInlineItem) {}
        return false;
    }

    function isPagePlaneInlineVisualItem(item) {
        if (!item) return false;
        if (_isTopLevelInlineVisualItem(item)) return true;
        try {
            if (item.constructor && item.constructor.name === "TextFrame"
                    && (isInlineItem(item) || isStoryAnchoredInlineItem(item))) {
                return true;
            }
        } catch (eTextFrameInline) {}
        return false;
    }

    function isStoryAnchoredInlineItem(item) {
        try {
            var parent = item && item.parent;
            var parentName = parent && parent.constructor && parent.constructor.name
                    ? String(parent.constructor.name) : "";
            return parentName === "Character" || parentName === "InsertionPoint";
        } catch (eStoryAnchoredInline) {}
        return false;
    }

    function textItemKey(item) {
        try {
            if (item.id !== undefined && item.id !== null) {
                var ctor = item.constructor && item.constructor.name
                        ? item.constructor.name : "TextItem";
                return ctor + ":" + String(item.id);
            }
        } catch (eId) {}
        return null;
    }

    function isTextItem(item) {
        if (!item) return false;
        var name = null;
        try {
            name = item.constructor && item.constructor.name
                    ? item.constructor.name : null;
        } catch (eCtor) {}
        return name === "TextFrame" || name === "TextPath";
    }

    function collectDocumentTextItemsToHide() {
        var items = [];
        var seen = {};

        function add(item) {
            if (!isTextItem(item)) return;
            var key = textItemKey(item);
            if (key === null) key = "idx:" + String(items.length);
            if (seen[key]) return;
            seen[key] = true;
            items.push(item);
        }

        function addFromItems(items) {
            if (!items) return;
            for (var ii = 0; ii < items.length; ii++) add(items[ii]);
        }

        try { addFromItems(doc.textFrames.everyItem().getElements()); } catch (eDocTextFrames) {}
        try { addFromItems(doc.textPaths.everyItem().getElements()); } catch (eDocTextPaths) {}
        try { addFromItems(doc.allPageItems); } catch (eDocAllPageItems) {}
        try {
            var spreads = doc.spreads.everyItem().getElements();
            for (var si = 0; si < spreads.length; si++) {
                try { addFromItems(spreads[si].allPageItems); } catch (eSpreadItems) {}
            }
        } catch (eSpreads) {}
        try {
            var masters = doc.masterSpreads.everyItem().getElements();
            for (var mi = 0; mi < masters.length; mi++) {
                try { addFromItems(masters[mi].allPageItems); } catch (eMasterItems) {}
            }
        } catch (eMasters) {}
        try {
            var pageItems = doc.allPageItems;
            for (var pi = 0; pi < pageItems.length; pi++) {
                try { addFromItems(pageItems[pi].textPaths.everyItem().getElements()); } catch (eItemTextPaths) {}
            }
        } catch (eAllItemTextPaths) {}
        return items;
    }

    function collectRangeTextItemsToHide() {
        var items = [];
        var seen = {};

        function add(item) {
            if (!isTextItem(item)) return;
            var key = textItemKey(item);
            if (key === null) key = "idx:" + String(items.length);
            if (seen[key]) return;
            seen[key] = true;
            items.push(item);
        }

        function addFromItems(list) {
            if (!list) return;
            for (var ii = 0; ii < list.length; ii++) add(list[ii]);
        }

        function addNestedTextItems(root) {
            if (!root) return;
            add(root);
            try { addFromItems(root.allPageItems); } catch (eAllPageItems) {}
            try { addFromItems(root.textFrames.everyItem().getElements()); } catch (eTextFrames) {}
            try { addFromItems(root.textPaths.everyItem().getElements()); } catch (eTextPaths) {}
            try {
                var nested = root.allPageItems;
                for (var ni = 0; nested && ni < nested.length; ni++) {
                    try { addFromItems(nested[ni].textPaths.everyItem().getElements()); } catch (eNestedTextPaths) {}
                }
            } catch (eNested) {}
        }

        for (var ai = 0; allItems && ai < allItems.length; ai++) {
            addNestedTextItems(allItems[ai]);
        }

        for (var pageNumber = startPage; pageNumber <= endPage; pageNumber++) {
            var page = null;
            try { page = doc.pages[pageNumber - 1]; } catch (ePage) { page = null; }
            if (!page) continue;
            addNestedTextItems(page);

            // Applied master text can appear in page PNG export even when it is
            // not returned as an ordinary page item. Keep this scoped to the
            // selected page's master spread instead of scanning every page.
            try { addNestedTextItems(page.appliedMaster); } catch (eMaster) {}
        }

        return items;
    }

    function hideCollectedTextItems(items) {
        var saved = [];
        var seen = {};
        for (var fi = 0; items && fi < items.length; fi++) {
            var textItem = items[fi];
            var key = textItemKey(textItem);
            if (key === null) key = "idx:" + String(fi);
            if (seen[key]) continue;
            seen[key] = true;
            try {
                var itemSaved = hideOneTextFrameContent(textItem, {
                    preserveFrameVisual: true
                });
                if (itemSaved) saved.push(itemSaved);
            } catch (eHide) {}
        }
        return saved;
    }

    function exportPage(page, outFile) {
        try {
            page.exportFile(ExportFormat.PNG_FORMAT, outFile, false);
            return outFile.exists;
        } catch (ePageExport) {}
        try {
            hadPageString = true;
            savedPageString = app.pngExportPreferences.pageString;
        } catch (ePageStringRead) {
            hadPageString = false;
        }
        try {
            app.pngExportPreferences.pageString = String(page.name);
            doc.exportFile(ExportFormat.PNG_FORMAT, outFile, false);
            return outFile.exists;
        } catch (eDocExport) {
            diagnostics.push({
                accepted: false,
                reason: "single_textless_page_plane_export_failed",
                pageIndex: page && page.documentOffset !== undefined ? page.documentOffset : -1,
                error: String(eDocExport)
            });
        } finally {
            try {
                if (hadPageString) app.pngExportPreferences.pageString = savedPageString;
            } catch (ePageStringRestore) {}
        }
        return false;
    }

    var savedInlineItems = [];
    var savedTableStyleItems = [];
    var savedDocumentTextFrames = [];
    // SPEC-030 계측 변수 (예외 경로에서도 정의되도록 미리 선언)
    var _tCollectInline = 0, _tHideInline = 0, _tCollectText = 0, _tHideText = 0;
    var _tPrepTotal = 0, _tExportLoop = 0, _tRestore = 0;
    var _exportLoopStart = 0;
    if (opts.precomputedPagePlanesByPageIndex) {
        var _reuseStartedAt = nowMs();
        for (var reusePageNumber = startPage; reusePageNumber <= endPage; reusePageNumber++) {
            var reusePageIndex = reusePageNumber - 1;
            var reuseEntry = opts.precomputedPagePlanesByPageIndex[String(reusePageIndex)];
            var reusePage = null;
            try { reusePage = doc.pages[reusePageIndex]; } catch (eReusePage) {}
            var reuseBounds = reuseEntry && reuseEntry.bounds ? reuseEntry.bounds
                    : (reusePage ? pageLocalBounds(reusePage) : null);
            if (!reuseEntry || !reuseEntry.file) {
                diagnostics.push({
                    accepted: false,
                    reason: "single_textless_page_plane_precomputed_missing",
                    pageIndex: reusePageIndex,
                    file: null,
                    bounds: reuseBounds,
                    elapsedMs: 0
                });
                continue;
            }
            diagnostics.push({
                accepted: true,
                reason: "single_textless_page_plane_reused",
                pageIndex: reusePageIndex,
                file: reuseEntry.file,
                bounds: reuseBounds,
                elapsedMs: 0,
                globalRenderedFrame: true
            });
            var reuseSyntheticSourceId = typeof _canonicalPagePlaneSyntheticSourceId === "function"
                    ? _canonicalPagePlaneSyntheticSourceId(reusePageIndex)
                    : (-940000000 + reusePageIndex);
            var reuseCandidateId = typeof _canonicalPagePlaneCandidateId === "function"
                    ? _canonicalPagePlaneCandidateId(reusePageIndex)
                    : ("cand.pass.page_textless_graphic_groups.page."
                        + String(reusePageIndex)
                        + ".single_textless_page_plane");
            results.push({
                id: reuseSyntheticSourceId,
                type: "page_textless_plane",
                candidateId: reuseCandidateId,
                candidateMatchStrategy: "page_plane_direct",
                file: reuseEntry.file,
                bounds: reuseBounds,
                pageIndex: reusePageIndex,
                zOrder: -900000,
                zOrderKnown: true,
                visualLayer: "PAGE_BACKGROUND",
                policyLayer: "BACKGROUND",
                visualOwner: "indesign_png",
                textOwner: "none",
                placement: "FLOATING",
                coordinateSpace: "PAGE",
                materialization: "PAGE_PLANE_PNG",
                slotRole: "page_textless_plane",
                ownershipSlot: "CONTENT_VISUAL_SLOT",
                reason: "canonical_single_textless_page_plane_reused",
                sourceObjectIds: [reuseSyntheticSourceId],
                visualSourceObjectIds: [reuseSyntheticSourceId],
                exportSourceObjectIds: [reuseSyntheticSourceId],
                hiddenTextFrameIds: [],
                globalRenderedFrame: true,
                exportSanity: {
                    singleTextlessPagePlane: true,
                    globalPreExportReused: true,
                    fileBytes: reuseEntry.fileBytes || 0,
                    pageRelativeBounds: reuseBounds
                }
            });
        }
        try {
            writeJson(outputDir + "/single-textless-page-plane-export.json", {
                schemaVersion: 1,
                mode: "single-textless-plane-reuse",
                elapsedMs: nowMs() - _reuseStartedAt,
                pageCount: results.length,
                hiddenInlineItemCount: 0,
                perfBreakdown: {
                    collectInlineMs: 0,
                    hideInlineMs: 0,
                    collectTextMs: 0,
                    hideTextMs: 0,
                    prepTotalMs: 0,
                    exportLoopMs: 0,
                    restoreMs: 0,
                    hiddenTextItemCount: 0,
                    hiddenInlineItemCount: 0,
                    reusedPagePlaneCount: results.length
                },
                diagnostics: diagnostics
            });
        } catch (eWriteSinglePlaneReuseDiag) {}
        return {
            frames: results,
            textlessShellDiagnostics: diagnostics,
            childIds: {}
        };
    }
    try {
        try { savedRes = app.pngExportPreferences.exportResolution; } catch (eRes) {}
        try { savedTransparent = app.pngExportPreferences.transparentBackground; } catch (eTrans) {}
        try { savedQuality = app.pngExportPreferences.pngQuality; } catch (eQuality) {}
        try { savedSpread = app.pngExportPreferences.exportingSpread; } catch (eSpread) {}
        try { savedDisplayPerf = doc.viewPreferences.displayPerformance; } catch (eDisplay) {}
        try { app.pngExportPreferences.exportResolution = CONFIG.rendering.pngExportResolution || 220; } catch (eSetRes) {}
        try { app.pngExportPreferences.antiAlias = true; } catch (eAntiAlias) {}
        try { app.pngExportPreferences.transparentBackground = false; } catch (eSetTrans) {}
        try { app.pngExportPreferences.pngQuality = PNGQualityEnum.MAXIMUM; } catch (eSetQuality) {}
        try { app.pngExportPreferences.exportingSpread = false; } catch (eSetSpread) {}
        try { doc.viewPreferences.displayPerformance = ViewDisplaySettings.HIGH_QUALITY; } catch (eSetDisplay) {}

        // SPEC-030 계측: 06b1 구간(157s)이 "준비(hide/collect)" 와 "PNG export" 중
        // 어디서 시간을 쓰는지 분리한다. 지금까지 이 구간은 마커가 없어 블랙박스였다.
        var _perfPrepStart = nowMs();
        var _inlineToHide = collectInlineItemsToHide();
        _tCollectInline = nowMs() - _perfPrepStart;

        var _t0 = nowMs();
        savedInlineItems = _hideItemsForExport(_inlineToHide);
        _tHideInline = nowMs() - _t0;

        savedTableStyleItems = _hideItemsForExport(collectTableStyleSourceItemsToHide());

        _t0 = nowMs();
        var _textHideScope = opts.documentWideTextHide === true ? "document" : "range";
        var _textToHide = opts.documentWideTextHide === true
                ? collectDocumentTextItemsToHide()
                : collectRangeTextItemsToHide();
        if ((!_textToHide || _textToHide.length === 0) && opts.documentWideTextHide !== true) {
            _textHideScope = "document-fallback";
            _textToHide = collectDocumentTextItemsToHide();
        }
        _tCollectText = nowMs() - _t0;

        _t0 = nowMs();
        savedDocumentTextFrames = hideCollectedTextItems(_textToHide);
        _tHideText = nowMs() - _t0;

        _tPrepTotal = nowMs() - _perfPrepStart;
        _exportLoopStart = nowMs();

        for (var pageNumber = startPage; pageNumber <= endPage; pageNumber++) {
            var pageIndex = pageNumber - 1;
            var page = null;
            try { page = doc.pages[pageIndex]; } catch (ePage) {}
            if (!page) continue;
            var pageStart = nowMs();
            var fileName = "page_textless_plane_p" + String(pageIndex + 1) + ".png";
            var outFile = File(renderDir + "/" + fileName);
            var ok = exportPage(page, outFile);
            var bounds = pageLocalBounds(page);
            diagnostics.push({
                accepted: ok === true,
                reason: ok ? "single_textless_page_plane_exported"
                        : "single_textless_page_plane_missing_file",
                pageIndex: page.documentOffset,
                file: ok ? "rendered_frames/" + fileName : null,
                bounds: bounds,
                elapsedMs: nowMs() - pageStart
            });
            if (!ok) continue;
            var syntheticSourceId = typeof _canonicalPagePlaneSyntheticSourceId === "function"
                    ? _canonicalPagePlaneSyntheticSourceId(page.documentOffset)
                    : (-940000000 + pageIndex);
            var candidateId = typeof _canonicalPagePlaneCandidateId === "function"
                    ? _canonicalPagePlaneCandidateId(page.documentOffset)
                    : ("cand.pass.page_textless_graphic_groups.page."
                        + String(page.documentOffset)
                        + ".single_textless_page_plane");
            results.push({
                id: syntheticSourceId,
                type: "page_textless_plane",
                candidateId: candidateId,
                candidateMatchStrategy: "page_plane_direct",
                file: "rendered_frames/" + fileName,
                bounds: bounds,
                pageIndex: page.documentOffset,
                zOrder: -900000,
                zOrderKnown: true,
                visualLayer: "PAGE_BACKGROUND",
                policyLayer: "BACKGROUND",
                visualOwner: "indesign_png",
                textOwner: "none",
                placement: "FLOATING",
                coordinateSpace: "PAGE",
                materialization: "PAGE_PLANE_PNG",
                slotRole: "page_textless_plane",
                ownershipSlot: "CONTENT_VISUAL_SLOT",
                reason: "canonical_single_textless_page_plane_export",
                sourceObjectIds: [syntheticSourceId],
                visualSourceObjectIds: [syntheticSourceId],
                exportSourceObjectIds: [syntheticSourceId],
                hiddenTextFrameIds: [],
                globalRenderedFrame: opts.globalPreExport === true,
                exportSanity: {
                    singleTextlessPagePlane: true,
                    fileBytes: outFile.exists ? outFile.length : 0,
                    pageRelativeBounds: bounds,
                    hiddenInlineItemCount: savedInlineItems.length,
                    hiddenTextFrameCount: savedDocumentTextFrames.length,
                    hiddenTableStyleItemCount: savedTableStyleItems.length,
                    globalPreExport: opts.globalPreExport === true
                }
            });
        }
        _tExportLoop = nowMs() - _exportLoopStart;
    } finally {
        var _restoreStart = nowMs();
        try { restoreTextFrames(savedDocumentTextFrames); } catch (eRestoreTextFrames) {}
        try { _restoreItemsForExport(savedTableStyleItems); } catch (eRestoreTableStyle) {}
        try { _restoreItemsForExport(savedInlineItems); } catch (eRestoreInline) {}
        try { if (savedRes !== null) app.pngExportPreferences.exportResolution = savedRes; } catch (eRestoreRes) {}
        try { if (savedTransparent !== null) app.pngExportPreferences.transparentBackground = savedTransparent; } catch (eRestoreTrans) {}
        try { if (savedQuality !== null) app.pngExportPreferences.pngQuality = savedQuality; } catch (eRestoreQuality) {}
        try { if (savedSpread !== null) app.pngExportPreferences.exportingSpread = savedSpread; } catch (eRestoreSpread) {}
        try { if (savedDisplayPerf !== null) doc.viewPreferences.displayPerformance = savedDisplayPerf; } catch (eRestoreDisplay) {}
        _tRestore = nowMs() - _restoreStart;
    }

    try {
        // SPEC-030: 06b1 구간 비용 분해 — export vs 준비(hide/collect) vs 복원
        var _perfBreakdown = {
            collectInlineMs: _tCollectInline,
            hideInlineMs: _tHideInline,
            collectTextMs: _tCollectText,
            hideTextMs: _tHideText,
            prepTotalMs: _tPrepTotal,
            exportLoopMs: _tExportLoop,
            restoreMs: _tRestore,
            hiddenTextItemCount: savedDocumentTextFrames.length,
            hiddenInlineItemCount: savedInlineItems.length,
            hiddenTableStyleItemCount: savedTableStyleItems.length,
            textHideScope: _textHideScope
        };
        writeJson(outputDir + "/single-textless-page-plane-export.json", {
            schemaVersion: 1,
            mode: "single-textless-plane",
            elapsedMs: nowMs() - startedAt,
            pageCount: results.length,
            hiddenInlineItemCount: savedInlineItems.length,
            perfBreakdown: _perfBreakdown,
            diagnostics: diagnostics
        });
    } catch (eWriteSinglePlaneDiag) {}

    return {
        frames: results,
        textlessShellDiagnostics: diagnostics,
        childIds: {}
    };
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

    function _plannedCandidateRenderedBounds(candidate, fallbackBounds) {
        if (!candidate) return fallbackBounds;
        var plannedPageVisual = false;
        try {
            plannedPageVisual = String(candidate.placement || "") === "FLOATING"
                    || String(candidate.coordinateSpace || "") === "PAGE";
        } catch (ePlannedPageVisual) {}
        if (!plannedPageVisual) return fallbackBounds;
        if (candidate.bounds && candidate.bounds.length >= 4) {
            return arrCopy(candidate.bounds);
        }
        if (candidate.pageRelativeBounds && candidate.pageRelativeBounds.length >= 4) {
            return arrCopy(candidate.pageRelativeBounds);
        }
        return fallbackBounds;
    }

    function _duplicateNestedCompletePngForExport(item, candidate, doc) {
        if (!item || !candidate || !doc) return null;
        if (candidate.materialization !== "COMPLETE_PNG"
                || candidate.placement !== "INLINE"
                || candidate.coordinateSpace !== "STORY_FLOW") {
            return null;
        }
        var copies = [];
        try {
            var page = _resolveParentPage(item, doc);
            if (!page && candidate.pageIndex !== null && candidate.pageIndex !== undefined
                    && candidate.pageIndex >= 0 && candidate.pageIndex < doc.pages.length) {
                page = doc.pages[candidate.pageIndex];
            }
            var spread = page && page.parent ? page.parent : null;
            if (!spread) return null;
            var sourceIds = candidate.exportSourceObjectIds || [];
            for (var si = 0; si < sourceIds.length; si++) {
                if (String(sourceIds[si]) === String(item.id)) continue;
                var source = itemById ? itemById[String(sourceIds[si])] : null;
                if (!source) continue;
                try {
                    var copy = source.duplicate(spread);
                    try {
                        if (copy && copy.constructor && copy.constructor.name === "TextFrame") {
                            for (var ci = copy.characters.length - 1; ci >= 0; ci--) {
                                var ch = copy.characters[ci];
                                var anchoredCharacter = false;
                                try { anchoredCharacter = String(ch.contents) === "\uFFFC"; } catch (eCharContents) {}
                                try {
                                    anchoredCharacter = anchoredCharacter
                                            || ch.contents === SpecialCharacters.ANCHORED_OBJECT;
                                } catch (eAnchoredSpecialCharacter) {}
                                if (anchoredCharacter) ch.remove();
                            }
                        }
                    } catch (eRemoveCopiedAnchor) {}
                    copies.push(copy);
                } catch (eDuplicateChild) {}
            }
            if (copies.length === 1) return copies[0];
            if (copies.length > 1) return spread.groups.add(copies);
            return item.duplicate(spread) || null;
        } catch (eDuplicateNestedCompletePng) {
            for (var ci = copies.length - 1; ci >= 0; ci--) {
                try { copies[ci].remove(); } catch (eRemoveFailedCopy) {}
            }
        }
        return null;
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
                            || inlineCandidate.compositeRole === "direct_child_shell_slot"
                            || inlineCandidate.slotRole === "inline_text_frame_shell_slot"
                            || inlineCandidate.compositeRole === "inline_visible_text_frame_shell"
                            || inlineCandidate.slotRole === "inline_editable_text_shell_composite"
                            || inlineCandidate.compositeRole === "inline_editable_text_shell_composite"
                            || inlineCandidate.slotRole === "table_textless_shell_slot"
                            || inlineCandidate.compositeRole === "table_carrier_textless_shell"
                            || inlineCandidate.slotRole === "table_cell_shell_slot"
                            || inlineCandidate.compositeRole === "table_carrier_sibling_decoration"
                            || inlineCandidate.slotRole === "source_bundle_text_range_shell_slot"
                            || inlineCandidate.compositeRole === "source_bundle_text_range_shell")
                        && (inlineCandidate.exportSourceObjectIds && inlineCandidate.exportSourceObjectIds.length > 0);
                var plannedInlineTextFrameVisual = inlineCandidate.visualAction === "PLACE_INLINE_PNG"
                        && inlineCandidate.placement === "INLINE"
                        && inlineCandidate.compositeRole === "empty_inline_textframe_visual"
                        && (inlineCandidate.exportSourceObjectIds && inlineCandidate.exportSourceObjectIds.length > 0);
                // Stage 1 may assign a closed nested source group to one inline
                // COMPLETE_PNG slot.  The source group itself is not anchored;
                // its ancestor carries the story anchor.  Execute that explicit
                // plan instead of reclassifying the nested group as non-inline.
                var plannedInlineCompletePng = inlineCandidate.visualAction === "PLACE_INLINE_PNG"
                        && inlineCandidate.placement === "INLINE"
                        && inlineCandidate.coordinateSpace === "STORY_FLOW"
                        && inlineCandidate.materialization === "COMPLETE_PNG"
                        && inlineCandidate.textAction === "OWNED_BY_PNG"
                        && inlineCandidate.completePngTextAllowed === true;
                if (inItem.constructor.name === "TextFrame" && !plannedTextFrameShell && !plannedInlineTextFrameVisual) {
                    inlineStats.textFrameSkipped++;
                    continue;
                }
                var plannedDirectChildShellSlot = inlineCandidate.slotRole === "direct_child_shell_slot";
                var plannedPageVisual = inlineCandidate.placement === "FLOATING"
                        && inlineCandidate.visualAction === "PLACE_FLOATING_PNG";
                if (!isInlineItem(inItem) && !plannedDirectChildShellSlot && !plannedPageVisual
                        && !plannedInlineCompletePng
                        && !plannedTextFrameShell) {
                    inlineStats.notInlineSkipped++;
                    continue;
                }
                try {
                    var inParent = inItem.parent;
                    if (!plannedDirectChildShellSlot && !plannedPageVisual && !plannedTextFrameShell
                            && !plannedInlineCompletePng
                            && inParent && inParent.constructor.name === "Group" && isInlineItem(inParent)) {
                        inlineStats.parentInlineSkipped++;
                        continue;
                    }
                    if (!plannedDirectChildShellSlot && !plannedPageVisual && !plannedTextFrameShell
                            && !plannedInlineCompletePng
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
                            savedInlineTextFrames = hideTextFrames(inItem, {
                                textFrameIds: inlineHiddenTextFrameIds
                            });
                        }
                    }
                } catch (eWalk) {}
                var _inlineExportOk = false;
                var _inlineExportDuplicate = null;
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
                        _inlineExportDuplicate = _duplicateNestedCompletePngForExport(
                                inItem, inlineCandidate, doc);
                        var _inlineExportTarget = _inlineExportDuplicate || inItem;
                        _inlineExportTarget.exportFile(ExportFormat.PNG_FORMAT, inOutFile);
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
                        var _inlineOwnsCompletePngText = plannedInlineCompletePng;
                        var _inlineRenderedBounds = _plannedCandidateRenderedBounds(inlineCandidate, inBounds);
                        var _inlineCandidatePassId = inlineCandidate.passId || "pass.inline_objects";
                        var _inlinePlannedPageVisual = inlineCandidate.placement === "FLOATING"
                                || inlineCandidate.coordinateSpace === "PAGE";
                        var _inlineBaseEntry = {
                            id: inId,
                            planPassId: _inlineCandidatePassId,
                            candidateId: _inlineCandidateMatch.candidateId,
                            candidateMatchStrategy: _inlineCandidateMatch.strategy,
                            file: "rendered_frames/" + inFileName,
                            parentStoryId: parentStoryId,
                            bounds: _inlineRenderedBounds,
                            renderSourceBounds: inlineCandidate.renderSourceBounds || _inlineRenderedBounds,
                            cropSourceBounds: inlineCandidate.cropSourceBounds || null,
                            pageIndex: inPageIdx,
                            type: _inlinePlannedPageVisual ? "page_object" : "inline_object",
                            placementRole: _inlinePlannedPageVisual ? "page_object" : "inline_object",
                            placement: inlineCandidate.placement || null,
                            coordinateSpace: inlineCandidate.coordinateSpace || null,
                            visualAction: inlineCandidate.visualAction || null,
                            visualLayer: inlineCandidate.visualLayer || null,
                            policyLayer: inlineCandidate.policyLayer || null,
                            materialization: inlineCandidate.materialization || null,
                            slotRole: inlineCandidate.slotRole || null,
                            textWrapMode: inlineCandidate.textWrapMode || null,
                            textWrapSide: inlineCandidate.textWrapSide || null,
                            textWrapTop: inlineCandidate.textWrapTop || 0,
                            textWrapLeft: inlineCandidate.textWrapLeft || 0,
                            textWrapBottom: inlineCandidate.textWrapBottom || 0,
                            textWrapRight: inlineCandidate.textWrapRight || 0,
                            textWrapSourceObjectId: inlineCandidate.textWrapSourceObjectId || null,
                            visualOwner: "indesign_png",
                            textOwner: _inlineOwnsCompletePngText ? "indesign_png"
                                    : (_inlineHasHiddenText ? "hwpx_tf" : "none"),
                            containsText: _inlineOwnsCompletePngText,
                            containsEditableText: _inlineOwnsCompletePngText,
                            placementAllowed: true,
                            reason: _inlineOwnsCompletePngText ? "inline_graphic_only"
                                    : (_inlineHasHiddenText ? "inline_text_hidden"
                                    : (_inlinePlannedPageVisual ? "planned_page_content_visual" : "inline_graphic_only")),
                            sourceObjectIds: _inlineEntrySourceIds,
                            childIds: inlineHiddenTextFrameIds,
                            exportSanity: {
                                fileBytes: _inlineWrittenFile.exists ? _inlineWrittenFile.length : 0,
                                exportOk: _inlineExportOk ? true : false,
                                guarded: false,
                                duplicatedForExport: _inlineExportDuplicate !== null,
                                textHiddenBeforeExport: _inlineHasHiddenText,
                                exportTargetType: inItem && inItem.constructor ? inItem.constructor.name : null,
                                sourceBounds: _inlineSourceBounds,
                                pageRelativeBounds: _inlineRenderedBounds,
                                renderSourceBounds: inlineCandidate.renderSourceBounds || _inlineRenderedBounds,
                                cropSourceBounds: inlineCandidate.cropSourceBounds || null
                            }
                        };
                        try {
                            inlineObjects.push(applyRenderOwnership(_inlineBaseEntry, inItem, {
                                textHiddenBeforeExport: _inlineHasHiddenText,
                                textOwner: _inlineBaseEntry.textOwner,
                                containsText: _inlineBaseEntry.containsText,
                                containsEditableText: _inlineBaseEntry.containsEditableText,
                                placementAllowed: true,
                                editableTextFrameIds: _inlineOwnsCompletePngText
                                        ? (inlineCandidate.editableTextFrameIds || [])
                                        : inlineHiddenTextFrameIds,
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
                    if (_inlineExportDuplicate) _inlineExportDuplicate.remove();
                } catch (eRemoveInlineExportDuplicate) {}
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
