/**
 * Planned decoration/text-shell render executor.
 *
 * This module moves the existing executor unchanged: ownership and slot
 * decisions must already be present in Stage 1 candidates/ObjectPlan data.
 */

var _decoGlobalAllPageItemsCache = {};

function _decoCacheKey(item) {
    try {
        if (item && item.id !== null && item.id !== undefined) return String(item.id);
    } catch (e) {}
    return null;
}

function _decoAllPageItems(item) {
    var key = _decoCacheKey(item);
    if (key !== null && _decoGlobalAllPageItemsCache.hasOwnProperty(key)) {
        return _decoGlobalAllPageItemsCache[key];
    }
    var nested = [];
    try {
        var raw = item.allPageItems;
        try {
            nested = raw.everyItem().getElements();
        } catch (eGetElements) {
            nested = raw;
        }
    } catch (eNested) {
        nested = [];
    }
    if (key !== null) _decoGlobalAllPageItemsCache[key] = nested;
    return nested;
}

function _buildDecorationCandidateIndexes(decorationCandidates, itemById) {
    var plannedItems = [];
    var plannedSeen = {};
    var plannedPagesBySource = {};
    var candidateByPrimaryPage = {};
    var decorationCandidatesByPage = {};

    for (var i = 0; decorationCandidates && i < decorationCandidates.length; i++) {
        var candidate = decorationCandidates[i];
        if (!candidate) continue;
        if (candidate.pageIndex !== null && candidate.pageIndex !== undefined) {
            var pageKey = String(candidate.pageIndex);
            if (!decorationCandidatesByPage[pageKey]) decorationCandidatesByPage[pageKey] = [];
            decorationCandidatesByPage[pageKey].push(candidate);
        }
        var sourceId = candidate.primarySourceObjectId;
        if ((candidate.slotRole === "direct_child_shell_slot"
                    || candidate.slotRole === "shell_slot_only")
                && candidate.exportTargetObjectId !== null
                && candidate.exportTargetObjectId !== undefined) {
            sourceId = candidate.exportTargetObjectId;
        } else if ((candidate.slotRole === "direct_child_shell_slot"
                    || candidate.slotRole === "shell_slot_only")
                && candidate.exportSourceObjectIds
                && candidate.exportSourceObjectIds.length === 1) {
            sourceId = candidate.exportSourceObjectIds[0];
        }
        if ((sourceId === null || sourceId === undefined)
                && candidate.sourceObjectIds && candidate.sourceObjectIds.length > 0) {
            sourceId = candidate.sourceObjectIds[0];
        }
        if (sourceId === null || sourceId === undefined || !itemById) continue;
        var item = null;
        if ((candidate.slotRole === "direct_child_shell_slot"
                    || candidate.slotRole === "shell_slot_only")
                && candidate.exportSourceObjectIds
                && candidate.exportSourceObjectIds.length > 1) {
            var primaryItem = itemById[String(sourceId)];
            if (_pageItemContainsAllIds(primaryItem, candidate.exportSourceObjectIds)) {
                item = primaryItem;
            }
            if (!item) item = _commonPageItemParentForIds(candidate.exportSourceObjectIds, itemById);
        }
        if (!item) item = itemById[String(sourceId)];
        if (!item
                && candidate.exportTargetObjectId !== null
                && candidate.exportTargetObjectId !== undefined) {
            item = itemById[String(candidate.exportTargetObjectId)];
        }
        if (!item && candidate.exportSourceObjectIds && candidate.exportSourceObjectIds.length > 0) {
            for (var esi = 0; esi < candidate.exportSourceObjectIds.length; esi++) {
                item = itemById[String(candidate.exportSourceObjectIds[esi])];
                if (item) break;
            }
        }
        if (!item && candidate.sourceObjectIds && candidate.sourceObjectIds.length > 0) {
            for (var csi = 0; csi < candidate.sourceObjectIds.length; csi++) {
                item = itemById[String(candidate.sourceObjectIds[csi])];
                if (item) break;
            }
        }
        if (!item) continue;
        var candidatePageKey = candidate.pageIndex !== null && candidate.pageIndex !== undefined
                ? String(candidate.pageIndex)
                : "";
        var key = candidatePageKey + "|" + String(sourceId);
        if ((candidate.slotRole === "direct_child_shell_slot" || candidate.slotRole === "shell_slot_only")
                && candidate.candidateId) {
            key += "|" + String(candidate.candidateId);
        }
        candidateByPrimaryPage[key] = candidate;
        if (candidatePageKey !== "") {
            var sourceKey = String(sourceId);
            if (!plannedPagesBySource[sourceKey]) plannedPagesBySource[sourceKey] = {};
            plannedPagesBySource[sourceKey][candidatePageKey] = true;
        }
        if (plannedSeen[key]) continue;
        plannedSeen[key] = true;
        plannedItems.push({ item: item, candidate: candidate });
    }

    return {
        plannedItems: plannedItems,
        plannedPagesBySource: plannedPagesBySource,
        candidateByPrimaryPage: candidateByPrimaryPage,
        decorationCandidatesByPage: decorationCandidatesByPage
    };
}

/**
 * 도형만으로 구성된 장식 그룹(클리핑 Oval/Rect 포함)을 그룹 단위로 PNG 렌더링한다.
 * NATIVE_SOURCE_SHAPE 후보와 같은 source slot을 공유하지 않도록 Stage 1
 * 후보 생성에서 먼저 분리한다.
 *
 * 대상:
 * - Group의 allPageItems가 모두 도형(Rect/Polygon/Oval/GraphicLine/Group)
 * - Oval/Rectangle 클리핑 컨테이너 내부에 도형 그룹이 있는 경우
 *
 * @return {{ frames: Array, childIds: Object }}
 */
function exportDecorationGroups(doc, outputDir, startPage, endPage,
                                itemById, decorationCandidates, imgRenderedIds, sourceItems) {
    var renderDir = Folder(outputDir + "/rendered_frames");
    renderDir.create();

    var results = [];
    var decoChildIds = {};
    var renderedIds = {};
    var labelBackdropClaimedIds = {};
    var labelBackdropClaimedTextFrameIds = {};
    var atomicTextShellRootGroupIds = {};
    var atomicGraphicRootGroupIds = {};
    var textlessShellDiagnostics = [];
    var candidateIndexes = _buildDecorationCandidateIndexes(decorationCandidates, itemById);
    var plannedItems = candidateIndexes.plannedItems;
    var plannedPagesBySource = candidateIndexes.plannedPagesBySource;
    var candidateByPrimaryPage = candidateIndexes.candidateByPrimaryPage;
    var decorationCandidatesByPage = candidateIndexes.decorationCandidatesByPage;
    var decoAllPageItemsCache = {};
    var decoTextFrameIdsCache = {};
    var decoBoundsCache = {};
    var decoDirectGroupItemsCache = {};
    var decoPlannedCandidateMatchCache = {};
    var renderedPageKeys = {};
    var sourceItemIndexes = null;
    try { sourceItemIndexes = _buildSourceItemIndexes(sourceItems || []); } catch (eSourceIndexes) {}
    var sourceInfoById = sourceItemIndexes ? sourceItemIndexes.sourceInfoById || {} : {};
    var decoPerfStats = {
        passId: decorationCandidates && decorationCandidates.length > 0
                ? String(decorationCandidates[0].passId || "unknown")
                : "unknown",
        candidateCount: decorationCandidates ? decorationCandidates.length : 0,
        plannedItemCount: plannedItems ? plannedItems.length : 0,
        phaseMs: {},
        eventCounts: {},
        renderEvents: []
    };

    function _decoPerfNow() {
        try { return new Date().getTime(); } catch (e) {}
        return 0;
    }

    function _decoPerfAdd(map, key, value) {
        try {
            if (!map[key]) map[key] = 0;
            map[key] += value || 0;
        } catch (e) {}
    }

    function _decoPerfEndPhase(name, startedAt) {
        try { _decoPerfAdd(decoPerfStats.phaseMs, name, _decoPerfNow() - startedAt); } catch (e) {}
    }

    function _decoPerfPageIndex(page) {
        try { return page && page.documentOffset !== undefined ? page.documentOffset : -1; } catch (e) {}
        return -1;
    }

    function _decoPerfItemId(item) {
        try { return item && item.id !== undefined && item.id !== null ? item.id : null; } catch (e) {}
        return null;
    }

    function _decoPerfRecord(kind, item, page, ownershipOpts, startedAt, detail) {
        try {
            if (!decoPerfStats.eventCounts[kind]) decoPerfStats.eventCounts[kind] = 0;
            decoPerfStats.eventCounts[kind]++;
            if (decoPerfStats.renderEvents.length >= 500) return;
            var event = {
                kind: kind,
                ms: _decoPerfNow() - startedAt,
                itemId: _decoPerfItemId(item),
                pageIndex: _decoPerfPageIndex(page)
            };
            if (ownershipOpts) {
                event.candidateId = ownershipOpts.candidateId || null;
                event.slotRole = ownershipOpts.slotRole || null;
                event.reason = ownershipOpts.reason || null;
                event.exportSourceObjectCount = ownershipOpts.exportSourceObjectIds
                        ? ownershipOpts.exportSourceObjectIds.length : 0;
                event.sourceObjectCount = ownershipOpts.sourceObjectIds
                        ? ownershipOpts.sourceObjectIds.length : 0;
            }
            if (detail) {
                for (var dk in detail) {
                    if (detail.hasOwnProperty(dk)) event[dk] = detail[dk];
                }
            }
            decoPerfStats.renderEvents.push(event);
        } catch (e) {}
    }

    function _decoPerfSafePassId() {
        try { return String(decoPerfStats.passId || "unknown").replace(/[^A-Za-z0-9_-]+/g, "_"); } catch (e) {}
        return "unknown";
    }

    function _decoPerfWrite() {
        try {
            decoPerfStats.resultCount = results.length;
            decoPerfStats.textlessShellDiagnosticCount = textlessShellDiagnostics.length;
            writeJson(outputDir + "/_deco_group_perf_" + _decoPerfSafePassId() + ".json", decoPerfStats);
        } catch (e) {}
    }

    // ── 공통 헬퍼 ────────────────────────────────────────────────────────────

    function _decoSourceSetFileHash(ids) {
        var sorted = _sortedNumericIds(ids || []);
        var hash = 0;
        for (var i = 0; i < sorted.length; i++) {
            var n = Number(sorted[i]) || 0;
            hash = ((hash * 131) + n) % 1000000007;
        }
        return String(hash);
    }

    function _decoCacheKey(item) {
        try {
            if (item && item.id !== null && item.id !== undefined) return String(item.id);
        } catch (e) {}
        return null;
    }

    function _decoAllPageItems(item) {
        var key = _decoCacheKey(item);
        if (key !== null && decoAllPageItemsCache.hasOwnProperty(key)) return decoAllPageItemsCache[key];
        var nested = [];
        try {
            var raw = item.allPageItems;
            try {
                nested = raw.everyItem().getElements();
            } catch (eGetElements) {
                nested = raw;
            }
        } catch (eNested) {
            nested = [];
        }
        if (key !== null) decoAllPageItemsCache[key] = nested;
        return nested;
    }

    function _decoCollectTextFrameIds(item, editableOnly, requireContent) {
        var key = _decoCacheKey(item);
        var cacheKey = key !== null
                ? key + "|" + (editableOnly ? "1" : "0") + "|" + (requireContent ? "1" : "0")
                : null;
        if (cacheKey !== null && decoTextFrameIdsCache.hasOwnProperty(cacheKey)) {
            return decoTextFrameIdsCache[cacheKey].slice(0);
        }
        var ids = [], seen = {};
        function visit(tf) {
            try {
                if (requireContent && !_textFrameHasContent(tf)) return;
                if (editableOnly && classifyTextFrameCached(tf) !== "editable") return;
                _pushUniqueId(ids, seen, tf.id);
            } catch (e) {}
        }
        try { if (item.constructor.name === "TextFrame") visit(item); } catch (eSelf) {}
        var nested = _decoAllPageItems(item);
        for (var i = 0; nested && i < nested.length; i++) {
            try {
                if (nested[i].constructor.name === "TextFrame") visit(nested[i]);
            } catch (eChild) {}
        }
        if (cacheKey !== null) decoTextFrameIdsCache[cacheKey] = ids.slice(0);
        return ids;
    }

    function _decoHasPlaced(item) {
        try { if (item.images && item.images.length > 0) return true; } catch (e) {}
        try { if (item.pdfs   && item.pdfs.length   > 0) return true; } catch (e) {}
        try { if (item.epss   && item.epss.length   > 0) return true; } catch (e) {}
        return false;
    }

    function _renderPageKey(id, page) {
        var pageIndex = -1;
        try { pageIndex = page && page.documentOffset !== undefined ? page.documentOffset : -1; } catch (e) {}
        return String(id) + "@" + String(pageIndex);
    }

    function _isRenderedOnPage(id, page) {
        return renderedPageKeys[_renderPageKey(id, page)] === true;
    }

    function _plannedPageCountForSource(id) {
        var pages = plannedPagesBySource[String(id)];
        if (!pages) return 0;
        var count = 0;
        for (var k in pages) {
            if (pages.hasOwnProperty(k)) count++;
        }
        return count;
    }

    function _pageForPlannedItem(planned, fallbackPage) {
        try {
            var candidate = planned ? planned.candidate : null;
            if (candidate && candidate.pageIndex !== null && candidate.pageIndex !== undefined) {
                var idx = Number(candidate.pageIndex);
                if (idx >= 0 && idx < doc.pages.length) return doc.pages[idx];
            }
        } catch (eCandidatePage) {}
        return fallbackPage;
    }

    function _isPlannedPageLocalBackgroundShapeCandidate(candidate, item) {
        if (!candidate || !item) return false;
        if (candidate.passId !== "pass.decoration_groups") return false;
        if (candidate.candidatePurpose !== "SHELL_CANDIDATE") return false;
        if (!_isBackgroundLayerName(_itemLayerName(item))) return false;
        if (_plannedPageCountForSource(item.id) <= 1) return false;
        var kind = null;
        try { kind = item.constructor.name; } catch (eKind) {}
        if (!_isClipCarryingShapeKind(kind)) return false;
        try {
            var nested = _decoAllPageItems(item);
            if (!nested || nested.length === 0) return false;
        } catch (eNested) {
            return false;
        }
        if (_decoHasPlaced(item)) return false;
        return true;
    }

    function _isPlannedGraphicOnlyCompositeShellCandidate(candidate, item) {
        if (!candidate || !item) return false;
        if (candidate.passId !== "pass.decoration_groups") return false;
        if (candidate.candidatePurpose !== "SHELL_CANDIDATE") return false;
        if (candidate.compositeRole !== "decoration_group_source_set") return false;
        if (!candidate.sourceObjectIds || candidate.sourceObjectIds.length <= 1) return false;
        if (candidate.ownedTextFrameIds && candidate.ownedTextFrameIds.length > 0) return false;
        if (candidate.editableTextFrameIds && candidate.editableTextFrameIds.length > 0) return false;
        if (candidate.hiddenTextFrameIds && candidate.hiddenTextFrameIds.length > 0) return false;
        try { if (item.constructor.name !== "Group") return false; } catch (eKind) { return false; }
        try { if (isOnHiddenLayer(item)) return false; } catch (eHidden) { return false; }
        try { if (_decoHasPlaced(item)) return false; } catch (ePlaced) { return false; }
        return true;
    }

    function _isPlannedTextShellCompositeCandidate(candidate, item) {
        if (!candidate || !item) return false;
        if (candidate.passId !== "pass.decoration_groups") return false;
        if (candidate.candidatePurpose !== "SHELL_CANDIDATE") return false;
        if (candidate.visualAction !== "PLACE_TEXT_SHELL") return false;
        if (!candidate.exportSourceObjectIds || candidate.exportSourceObjectIds.length === 0) return false;
        if (!candidate.visualSourceObjectIds || candidate.visualSourceObjectIds.length === 0) return false;
        if ((candidate.ownedTextFrameIds && candidate.ownedTextFrameIds.length > 0)
                || (candidate.styleSourceObjectIds && candidate.styleSourceObjectIds.length > 0)
                || (candidate.editableTextFrameIds && candidate.editableTextFrameIds.length > 0)) {
            return true;
        }
        return false;
    }

    function _plannedSlotRenderObjectId(slotPlan) {
        if (!slotPlan) return null;
        var isExplicitSlotOnly = slotPlan.slotRole === "shell_slot_only"
                || slotPlan.slotRole === "direct_child_shell_slot";
        if (isExplicitSlotOnly
                && slotPlan.exportTargetObjectId !== undefined
                && slotPlan.exportTargetObjectId !== null) {
            return slotPlan.exportTargetObjectId;
        }
        if (isExplicitSlotOnly
                && slotPlan.exportSourceObjectIds
                && slotPlan.exportSourceObjectIds.length === 1) {
            return slotPlan.exportSourceObjectIds[0];
        }
        return slotPlan.primarySourceObjectId !== undefined ? slotPlan.primarySourceObjectId : null;
    }

    // Group용 가드: renderedIds / decoChildIds / badge / imgRendered / 부모 체크
    function _decoGroupSkip(id, item, page) {
        var hiddenFromSlotOnlyParent = _isHiddenVisualCandidateFromSlotOnlyParent(item, page);
        if (_isRenderedOnPage(id, page)) return true;
        if (renderedIds[id]) return true;
        if (decoChildIds[id] && !hiddenFromSlotOnlyParent) return true;
        if (imgRenderedIds && imgRenderedIds[id]) return true;
        try {
            var par = item.parent;
            if (par && (_isRenderedOnPage(par.id, page) || renderedIds[par.id])) return true;
            if (par && decoChildIds[par.id] && !hiddenFromSlotOnlyParent) return true;
        } catch (e) {}
        return false;
    }

    function _candidateSourceIds(candidate) {
        if (!candidate) return [];
        if (candidate.sourceObjectIds && candidate.sourceObjectIds.length > 0) return candidate.sourceObjectIds;
        if (candidate.primarySourceObjectId !== null && candidate.primarySourceObjectId !== undefined) {
            return [candidate.primarySourceObjectId];
        }
        return [];
    }

    function _decoSourceIdsUnion(a, b) {
        var ids = [];
        var seen = {};
        for (var ai = 0; a && ai < a.length; ai++) _pushUniqueId(ids, seen, a[ai]);
        for (var bi = 0; b && bi < b.length; bi++) _pushUniqueId(ids, seen, b[bi]);
        return _sortedNumericIds(ids);
    }

    function _isPlannedNonGroupCompositeShell(planned) {
        if (!planned || !planned.item || !planned.candidate) return false;
        var item = planned.item;
        var candidate = planned.candidate;
        var cn = "";
        try { cn = item.constructor.name; } catch (eCn) { return false; }
        if (cn !== "Oval" && cn !== "Rectangle" && cn !== "Polygon") return false;
        if (candidate.passId !== "pass.decoration_groups") return false;
        if (candidate.candidatePurpose !== "SHELL_CANDIDATE") return false;
        if (candidate.compositeRole === "background_vector_source") return true;
        if (candidate.composite !== true && String(candidate.composite) !== "true") return false;
        var sourceIds = _candidateSourceIds(candidate);
        if (sourceIds.length <= 1) return false;
        try {
            if (String(candidate.primarySourceObjectId) !== String(item.id)) return false;
        } catch (eId) {
            return false;
        }
        return true;
    }

    function _plannedCandidateForItemOnPage(item, page) {
        try {
            var direct = _directDecoCandidateMatch(item, page);
            if (direct && direct.candidate) return direct.candidate;
        } catch (eDirect) {}
        try {
            var found = _decoFindPlannedExtractionCandidate("pass.decoration_groups", item, page ? page.documentOffset : null);
            if (found && found.candidate) return found.candidate;
        } catch (eFound) {}
        return null;
    }

    function _isHiddenVisualCandidateFromSlotOnlyParent(item, page) {
        if (!item || !page) return false;
        var candidate = _plannedCandidateForItemOnPage(item, page);
        var sourceIds = _candidateSourceIds(candidate);
        if (!candidate || sourceIds.length === 0) return false;
        var pageCandidates = decorationCandidatesByPage[String(page.documentOffset)] || [];
        for (var i = 0; i < pageCandidates.length; i++) {
            var parentCandidate = pageCandidates[i];
            if (!parentCandidate || parentCandidate === candidate) continue;
            if (parentCandidate.slotRole !== "shell_slot_only") continue;
            if (!parentCandidate.hiddenVisualSourceObjectIds
                    || parentCandidate.hiddenVisualSourceObjectIds.length === 0) continue;
            if (_sourceSetsIntersect(sourceIds, parentCandidate.hiddenVisualSourceObjectIds)) return true;
        }
        return false;
    }

    function _stripInDesignControlText(text) {
        var src = "";
        try { src = String(text || ""); } catch (eString) { return ""; }
        var out = "";
        try {
            for (var i = 0; i < src.length; i++) {
                var code = src.charCodeAt(i);
                if (code <= 32) continue;
                if (code === 0xFFFC || code === 0xFEFF) continue;
                out += String.fromCharCode(code);
            }
        } catch (eLoop) {
            return src;
        }
        return out;
    }

    function _storyTableCount(story) {
        if (!story || !story.tables) return 0;
        try { return Number(story.tables.everyItem().getElements().length || 0); } catch (eEveryTableCount) {}
        try {
            if (story.tables.length !== undefined && story.tables.length !== null) {
                return Number(story.tables.length || 0);
            }
        } catch (eLengthTableCount) {}
        try {
            if (story.tables.count) return Number(story.tables.count() || 0);
        } catch (eCountTableCount) {}
        return 0;
    }

    function _storyVisibleTextWithoutTableMarkers(story) {
        var text = "";
        try { text = String(story.contents || ""); } catch (eContents) {}
        return _stripInDesignControlText(text);
    }

    function _isMarkerOnlyTextFrame(tf) {
        var text = "";
        try { text = String(tf.contents || ""); } catch (eContents) {}
        return _stripInDesignControlText(text).length === 0;
    }

    function _isTableOnlyCarrierStory(story) {
        if (_storyTableCount(story) <= 0) return false;
        return _storyVisibleTextWithoutTableMarkers(story).length === 0;
    }

    function _isTableOnlyCarrierTextFrame(tf) {
        try {
            if (!tf || tf.constructor.name !== "TextFrame") return false;
            if (_storyTableCount(tf.parentStory) <= 0) return false;
            return _isMarkerOnlyTextFrame(tf);
        } catch (e) {}
        return false;
    }

    function _isTableOnlyCarrierTextFrameForStory(tf, story) {
        try {
            if (!tf || tf.constructor.name !== "TextFrame") return false;
            if (_storyTableCount(story) <= 0) return false;
            return _isMarkerOnlyTextFrame(tf);
        } catch (e) {}
        return false;
    }

    function _textFrameContentBoundsPageRelative(tf) {
        try {
            if (!tf || tf.constructor.name !== "TextFrame") return null;
            var gb = null;
            try { gb = arrCopy(tf.geometricBounds); } catch (eGb) {}
            if (!gb || gb.length < 4) return null;
            var page = null;
            try { page = tf.parentPage; } catch (ePage) {}
            if (!page) return null;
            var pb = null;
            try { pb = arrCopy(page.bounds); } catch (ePb) {}
            if (!pb || pb.length < 4) return null;
            var inset = [0, 0, 0, 0];
            try {
                var rawInset = tf.textFramePreferences.insetSpacing;
                if (rawInset && rawInset.length >= 4) {
                    inset = [
                        Number(rawInset[0] || 0),
                        Number(rawInset[1] || 0),
                        Number(rawInset[2] || 0),
                        Number(rawInset[3] || 0)
                    ];
                } else if (rawInset !== undefined && rawInset !== null) {
                    var one = Number(rawInset || 0);
                    inset = [one, one, one, one];
                }
            } catch (eInset) {}
            return [
                gb[0] + inset[0] - pb[0],
                gb[1] + inset[1] - pb[1],
                gb[2] - inset[2] - pb[0],
                gb[3] - inset[3] - pb[1]
            ];
        } catch (e) {}
        return null;
    }

    function _firstTextFrameFromStory(story, cachedAllItems) {
        if (!story) return null;
        var storyId = null;
        try { storyId = String(story.id); } catch (eStoryId0) {}
        if (storyId && cachedAllItems) {
            try {
                for (var cai = 0; cai < cachedAllItems.length; cai++) {
                    var cachedItem = cachedAllItems[cai];
                    try {
                        if (!cachedItem || !cachedItem.constructor || cachedItem.constructor.name !== "TextFrame") continue;
                        if (!cachedItem.parentStory) continue;
                        if (String(cachedItem.parentStory.id) === storyId) return cachedItem;
                    } catch (eCachedStoryId) {}
                }
            } catch (eCachedItems) {}
        }
        try {
            if (story.textContainers) {
                var containers = null;
                try {
                    if (story.textContainers.everyItem) {
                        containers = story.textContainers.everyItem().getElements();
                    }
                } catch (eEveryContainer) {}
                if (!containers) {
                    try {
                        var count = story.textContainers.length;
                        containers = [];
                        for (var ci = 0; ci < count; ci++) containers.push(story.textContainers[ci]);
                    } catch (eIndexContainer) {}
                }
                if (containers) {
                    for (var i = 0; i < containers.length; i++) {
                        var item = containers[i];
                        if (item && item.constructor && item.constructor.name === "TextFrame") {
                            return item;
                        }
                    }
                }
            }
        } catch (eContainers) {}
        try {
            if (story.parentTextFrames && story.parentTextFrames.length > 0) {
                var tf = story.parentTextFrames[0];
                if (tf && tf.constructor && tf.constructor.name === "TextFrame") return tf;
            }
        } catch (eParentFrames) {}
        try {
            var doc = app && app.activeDocument ? app.activeDocument : null;
            if (doc && storyId) {
                var frames = null;
                try { frames = doc.textFrames.everyItem().getElements(); } catch (eEveryFrame) {}
                if (frames) {
                    for (var fi = 0; fi < frames.length; fi++) {
                        var frame = frames[fi];
                        if (!frame || !frame.parentStory) continue;
                        try {
                            if (String(frame.parentStory.id) === storyId) return frame;
                        } catch (eFrameStoryId) {}
                    }
                }
                var allItems = null;
                try { allItems = doc.allPageItems; } catch (eAllPageItems) {}
                if (allItems) {
                    for (var ai = 0; ai < allItems.length; ai++) {
                        var item = allItems[ai];
                        try {
                            if (!item || !item.constructor || item.constructor.name !== "TextFrame") continue;
                            if (!item.parentStory) continue;
                            if (String(item.parentStory.id) === storyId) return item;
                        } catch (eItemStoryId) {}
                    }
                }
            }
        } catch (eDocFrames) {}
        return null;
    }

    function _hasTableOnlyCarrierTextFrame(item) {
        try {
            if (item && item.constructor.name === "TextFrame" && _isTableOnlyCarrierTextFrame(item)) {
                return true;
            }
        } catch (eSelf) {}
        try {
            var nested = _decoAllPageItems(item);
            for (var i = 0; i < nested.length; i++) {
                if (_isTableOnlyCarrierTextFrame(nested[i])) return true;
            }
        } catch (eNested) {}
        return false;
    }

    function _clearTableOnlyCarrierTextFrames(item) {
        function clearOne(tf) {
            if (!_isTableOnlyCarrierTextFrame(tf)) return;
            try { hideOneTextFrameContent(tf); return; } catch (eContentPaint) {}
        }
        try {
            if (item && item.constructor.name === "TextFrame") clearOne(item);
        } catch (eSelf) {}
        try {
            var nested = _decoAllPageItems(item);
            for (var i = 0; i < nested.length; i++) {
                try { clearOne(nested[i]); } catch (eOne) {}
            }
        } catch (eNested) {}
    }

    // PNG 렌더 + results 등록 (자식 claim 포함)
    function _directDecoCandidateMatch(item, page) {
        try {
            var pageIndex = page ? page.documentOffset : null;
            var id = _itemId(item);
            if (id !== null && id !== undefined && pageIndex !== null && pageIndex !== undefined) {
                var direct = candidateByPrimaryPage[String(pageIndex) + "|" + String(id)];
                if (direct) {
                    var strategy = direct.sourceObjectIds && direct.sourceObjectIds.length > 1
                            ? "candidate_source_set_direct"
                            : "candidate_direct";
                    return _candidateMatch(direct, strategy);
                }
            }
        } catch (eDirectCandidate) {}
        return null;
    }

    function _decoSourceSetCandidateMatch(sourceIds, page) {
        if (!sourceIds || sourceIds.length === 0 || !page) return null;
        var list = decorationCandidatesByPage[String(page.documentOffset)] || [];
        var sourceKey = _sourceSetKey(sourceIds);
        for (var i = 0; i < list.length; i++) {
            var candidate = list[i];
            if (!candidate || !candidate.sourceObjectIds || candidate.sourceObjectIds.length === 0) continue;
            if (_sourceSetKey(candidate.sourceObjectIds) === sourceKey) {
                return _candidateMatch(candidate, "candidate_source_set_direct");
            }
        }
        return null;
    }

    function _decoFindPlannedExtractionCandidate(passId, item, pageIndex) {
        var id = _itemId(item);
        var key = String(passId || "") + "|" + String(id) + "|" + String(pageIndex);
        if (decoPlannedCandidateMatchCache.hasOwnProperty(key)) {
            return decoPlannedCandidateMatchCache[key] || null;
        }
        var match = _findPlannedExtractionCandidate(passId, item, pageIndex);
        decoPlannedCandidateMatchCache[key] = match || false;
        return match;
    }

    function _ownershipSourceAllowsChild(childId, ownershipOpts) {
        if (!ownershipOpts || !ownershipOpts.sourceObjectIds || ownershipOpts.sourceObjectIds.length === 0) {
            return true;
        }
        return _sourceObjectIdsAllowId(ownershipOpts.sourceObjectIds, childId);
    }

    function _collectOutOfScopeChildrenForOwnership(item, ownershipOpts) {
        if (!ownershipOpts || !ownershipOpts.sourceObjectIds || ownershipOpts.sourceObjectIds.length === 0) {
            return [];
        }
        return _collectOutOfScopeChildrenForSourceIds(item, ownershipOpts.sourceObjectIds);
    }

    function _decoBoundsIntersection(a, b) {
        try {
            if (!a || !b || a.length < 4 || b.length < 4) return null;
            var t = Math.max(a[0], b[0]);
            var l = Math.max(a[1], b[1]);
            var bt = Math.min(a[2], b[2]);
            var r = Math.min(a[3], b[3]);
            if ((bt - t) <= 0.01 || (r - l) <= 0.01) return null;
            return [t, l, bt, r];
        } catch (e) {}
        return null;
    }

    function _decoBoundsDiffer(a, b, eps) {
        eps = eps || 0.01;
        try {
            if (!a || !b || a.length < 4 || b.length < 4) return false;
            return Math.abs(a[0] - b[0]) > eps || Math.abs(a[1] - b[1]) > eps ||
                   Math.abs(a[2] - b[2]) > eps || Math.abs(a[3] - b[3]) > eps;
        } catch (e) {}
        return false;
    }

    function _decoRender(item, page, childIdMap, ownershipOpts) {
        var _perfRenderStartedAt = _decoPerfNow();
        var _perfCandidateStartedAt = 0;
        var _perfCandidateMs = 0;
        var _perfAutoHideMs = 0;
        var _perfExportMs = 0;
        var _perfChildIdsMs = 0;
        var domId = item.id;
        try {
            if (ownershipOpts
                    && ownershipOpts.renderObjectId !== undefined
                    && ownershipOpts.renderObjectId !== null) {
                domId = ownershipOpts.renderObjectId;
            }
        } catch (eRenderObjectId) {}
        var renderKey = _renderPageKey(domId, page);
        if (ownershipOpts && ownershipOpts.candidateId) {
            renderKey += "|candidate:" + String(ownershipOpts.candidateId);
        }
        if (renderedPageKeys[renderKey]) {
            if (ownershipOpts && ownershipOpts.candidateId) {
                _recordPlannedShellRenderDiagnostic(item, page, "planned_shell_render_skipped_already_rendered", {
                    candidateId: ownershipOpts.candidateId,
                    renderKey: renderKey
                });
            }
            _decoPerfRecord("direct_render", item, page, ownershipOpts, _perfRenderStartedAt, {
                result: "skipped_already_rendered"
            });
            return [];
        }
        var decoCandidateMatch = null;
        _perfCandidateStartedAt = _decoPerfNow();
        if (ownershipOpts && ownershipOpts.candidateId && _extractionCandidateLookup
                && _extractionCandidateLookup.byId
                && _extractionCandidateLookup.byId[String(ownershipOpts.candidateId)]) {
            decoCandidateMatch = _candidateMatch(
                    _extractionCandidateLookup.byId[String(ownershipOpts.candidateId)],
                    "explicit_ownership_candidate");
        }
        if (!decoCandidateMatch) {
            decoCandidateMatch = _directDecoCandidateMatch(item, page)
                    || _decoFindPlannedExtractionCandidate("pass.decoration_groups", item, page ? page.documentOffset : null);
        }
        _perfCandidateMs = _decoPerfNow() - _perfCandidateStartedAt;
        if (!decoCandidateMatch) {
            if (ownershipOpts && ownershipOpts.candidateId) {
                _recordPlannedShellRenderDiagnostic(item, page, "planned_shell_render_no_candidate_match", {
                    candidateId: ownershipOpts.candidateId
                });
            }
            _decoPerfRecord("direct_render", item, page, ownershipOpts, _perfRenderStartedAt, {
                result: "no_candidate_match",
                candidateMatchMs: _perfCandidateMs
            });
            return [];
        }
        var pageIndexForFile = -1;
        try { pageIndexForFile = page && page.documentOffset !== undefined ? page.documentOffset : -1; } catch (ePageFile) {}
        var fileName = _plannedPageCountForSource(domId) > 1 && pageIndexForFile >= 0
                ? "deco_" + domId + "_p" + (pageIndexForFile + 1) + ".png"
                : "deco_" + domId + ".png";
        var _outFile = File(renderDir + "/" + fileName);
        var resolvedOwnershipOpts = ownershipOpts || { reason: "decoration_group" };
        if (decoCandidateMatch && decoCandidateMatch.candidate
                && decoCandidateMatch.candidate.sourceObjectIds
                && decoCandidateMatch.candidate.sourceObjectIds.length > 0
                && (!resolvedOwnershipOpts.sourceObjectIds
                    || resolvedOwnershipOpts.sourceObjectIds.length === 0)) {
            var candidateOwnershipOpts = {};
            for (var candidateOptKey in resolvedOwnershipOpts) {
                if (resolvedOwnershipOpts.hasOwnProperty(candidateOptKey)) {
                    candidateOwnershipOpts[candidateOptKey] = resolvedOwnershipOpts[candidateOptKey];
                }
            }
            candidateOwnershipOpts.sourceObjectIds = decoCandidateMatch.candidate.sourceObjectIds;
            candidateOwnershipOpts.hiddenVisualSourceObjectIds =
                    decoCandidateMatch.candidate.hiddenVisualSourceObjectIds || [];
            candidateOwnershipOpts.exportSourceObjectIds =
                    decoCandidateMatch.candidate.exportSourceObjectIds || [];
            candidateOwnershipOpts.exportTargetObjectId =
                    decoCandidateMatch.candidate.exportTargetObjectId !== undefined
                            ? decoCandidateMatch.candidate.exportTargetObjectId
                            : null;
            candidateOwnershipOpts.slotRole = decoCandidateMatch.candidate.slotRole || null;
            candidateOwnershipOpts.renderMode = decoCandidateMatch.candidate.mode || null;
            resolvedOwnershipOpts = candidateOwnershipOpts;
        }
        var _exportTarget = item;
        var _exportTargetSource = item;
        var _plannedExportSourceIds = resolvedOwnershipOpts.exportSourceObjectIds || [];
        var _slotUsesSourceSetExport = (resolvedOwnershipOpts.slotRole === "direct_child_shell_slot"
                    || resolvedOwnershipOpts.slotRole === "shell_slot_only")
                && _plannedExportSourceIds.length > 1;
        var _plannedExportTargetId = !_slotUsesSourceSetExport
                && resolvedOwnershipOpts.exportTargetObjectId !== undefined
                && resolvedOwnershipOpts.exportTargetObjectId !== null
                ? resolvedOwnershipOpts.exportTargetObjectId
                : (_plannedExportSourceIds.length === 1 ? _plannedExportSourceIds[0] : null);
        if ((resolvedOwnershipOpts.slotRole === "direct_child_shell_slot"
                    || resolvedOwnershipOpts.slotRole === "shell_slot_only")
                && _plannedExportSourceIds.length > 0) {
            var _slotCoverageOpts = {};
            for (var _slotCoverageKey in resolvedOwnershipOpts) {
                if (resolvedOwnershipOpts.hasOwnProperty(_slotCoverageKey)) {
                    _slotCoverageOpts[_slotCoverageKey] = resolvedOwnershipOpts[_slotCoverageKey];
                }
            }
            _slotCoverageOpts.sourceObjectIds = _decoSourceIdsUnion(
                    resolvedOwnershipOpts.sourceObjectIds || [],
                    _plannedExportSourceIds);
            resolvedOwnershipOpts = _slotCoverageOpts;
        }
        if (_plannedExportTargetId !== null && _plannedExportTargetId !== undefined) {
            var _candidateExportTarget = null;
            try { _candidateExportTarget = _findNestedPageItemById(item, _plannedExportTargetId); } catch (eFindExportTarget) {}
            if (_candidateExportTarget) {
                _exportTarget = _candidateExportTarget;
                _exportTargetSource = _candidateExportTarget;
                resolvedOwnershipOpts.layerSource = _candidateExportTarget;
            }
        }
        var _autoHiddenTFs = null;
        var _perfAutoHideStartedAt = _decoPerfNow();
        try {
            // A decoration render target may contain editable TextFrames whose
            // visible content is a table/anchored story rather than plain
            // TextFrame.contents.  Ownership must follow the source object, not
            // a text-content heuristic: any editable TF inside the rendered
            // target belongs to HWPX text and must be hidden before PNG export.
            var _editableForDeco = _decoCollectTextFrameIds(_exportTargetSource, true, false);
            var _textOwnerForDeco = resolvedOwnershipOpts.textOwner;
            if (!_textOwnerForDeco && _editableForDeco.length > 0) _textOwnerForDeco = "hwpx_tf";
            if (_editableForDeco.length > 0
                    && _textOwnerForDeco !== "indesign_png") {
                _autoHiddenTFs = hideTextFramesAndOwnedInlineVisuals(_exportTargetSource);
                var _copiedOwnershipOpts = {};
                for (var _copyKey in resolvedOwnershipOpts) {
                    if (resolvedOwnershipOpts.hasOwnProperty(_copyKey)) {
                        _copiedOwnershipOpts[_copyKey] = resolvedOwnershipOpts[_copyKey];
                    }
                }
                _copiedOwnershipOpts.textHiddenBeforeExport = true;
                _copiedOwnershipOpts.textOwner = _textOwnerForDeco;
                _copiedOwnershipOpts.editableTextFrameIds = _copiedOwnershipOpts.editableTextFrameIds || _editableForDeco;
                _copiedOwnershipOpts.hiddenTextFrameIds = _hiddenTextFrameIdsFromSaved(_autoHiddenTFs);
                resolvedOwnershipOpts = _copiedOwnershipOpts;
            }
        } catch (eAutoHide) {}
        _perfAutoHideMs = _decoPerfNow() - _perfAutoHideStartedAt;
        // exportFile을 독립 try로 감싸 InDesign 2026 예외가 push를 건너뛰지 않도록 보호
        var _exportOk = false;
        var _exportDup = null;
        var _savedOutOfScopeChildren = null;
        var _clearTableOnlyCarrierForExport = false;
        try {
            _clearTableOnlyCarrierForExport = resolvedOwnershipOpts.textHiddenBeforeExport === true
                    && _hasTableOnlyCarrierTextFrame(_exportTarget);
        } catch (eClearCheck) {}
        var _exportError = null;
        var _perfExportStartedAt = _decoPerfNow();
        try {
            if (_plannedExportSourceIds.length > 0) {
                var _outOfScopeOwnershipOpts = resolvedOwnershipOpts;
                _outOfScopeOwnershipOpts = {};
                for (var _exportOptKey in resolvedOwnershipOpts) {
                    if (resolvedOwnershipOpts.hasOwnProperty(_exportOptKey)) {
                        _outOfScopeOwnershipOpts[_exportOptKey] = resolvedOwnershipOpts[_exportOptKey];
                    }
                }
                _outOfScopeOwnershipOpts.sourceObjectIds = _plannedExportSourceIds;
                var _outOfScopeChildren = _collectOutOfScopeChildrenForOwnership(_exportTargetSource, _outOfScopeOwnershipOpts);
                if (_outOfScopeChildren.length > 0) {
                    _savedOutOfScopeChildren = _hideItemsForExport(_outOfScopeChildren);
                }
            }
            if (_clearTableOnlyCarrierForExport) {
                _exportDup = _exportTarget.duplicate();
                if (_clearTableOnlyCarrierForExport && _exportDup) {
                    _clearTableOnlyCarrierTextFrames(_exportDup);
                }
            }
            if (_exportDup) _exportTarget = _exportDup;
            _exportTarget.exportFile(ExportFormat.PNG_FORMAT, _outFile);
            _exportOk = true;
        } catch (eExp) {
            try { _exportError = String(eExp); } catch (eExpString) { _exportError = "export_exception"; }
            try { _exportOk = _outFile.exists; } catch (e2) {}
        } finally {
            try { if (_exportDup) _exportDup.remove(); } catch (eRemoveDup) {}
            try { if (_savedOutOfScopeChildren && _savedOutOfScopeChildren.length > 0) _restoreItemsForExport(_savedOutOfScopeChildren); } catch (eRestoreOutOfScope) {}
            try { if (_autoHiddenTFs && _autoHiddenTFs.length > 0) restoreTextFrames(_autoHiddenTFs); } catch (eRestoreAutoHide) {}
        }
        _perfExportMs = _decoPerfNow() - _perfExportStartedAt;
        if (!_exportOk) {
            if (ownershipOpts && ownershipOpts.candidateId) {
                _recordPlannedShellRenderDiagnosticForCandidate(
                        item,
                        page,
                        decoCandidateMatch ? decoCandidateMatch.candidate : null,
                        "planned_shell_render_export_failed",
                        {
                            candidateId: ownershipOpts.candidateId,
                            exportError: _exportError,
                            fileName: fileName,
                            exportTargetObjectId: _plannedExportTargetId,
                            exportTargetType: _exportTarget && _exportTarget.constructor ? _exportTarget.constructor.name : null,
                            exportSourceObjectIds: _plannedExportSourceIds
                    });
            }
            _decoPerfRecord("direct_render", item, page, ownershipOpts, _perfRenderStartedAt, {
                result: "export_failed",
                candidateMatchMs: _perfCandidateMs,
                autoHideMs: _perfAutoHideMs,
                exportMs: _perfExportMs,
                exportError: _exportError
            });
            return [];
        }

        var bounds = null;
        try { bounds = arrCopy(_exportTargetSource.visibleBounds); } catch (e) {}
        if (!bounds) try { bounds = arrCopy(_exportTargetSource.geometricBounds); } catch (e) {}
        var cropSourceBounds = null;
        if (bounds) {
            var originalBounds = arrCopy(bounds);
            var pageIntersection = null;
            try { pageIntersection = _decoBoundsIntersection(originalBounds, page.bounds); } catch (eIntersect) {}
            if (pageIntersection && _decoBoundsDiffer(originalBounds, pageIntersection, 0.01)) {
                cropSourceBounds = arrCopy(originalBounds);
                bounds = pageIntersection;
            }
            if (cropSourceBounds) _toPageRelativeBounds(cropSourceBounds, page);
            _toPageRelativeBounds(bounds, page);
        }

        var childIds = [];
        var skippedClaimedLabelChild = false;
        var _perfChildIdsStartedAt = _decoPerfNow();
        try {
            var nested = _decoAllPageItems(_exportTargetSource);
            for (var i = 0; i < nested.length; i++) {
                var cid = nested[i].id;
                if (!_ownershipSourceAllowsChild(cid, resolvedOwnershipOpts)) continue;
                if (labelBackdropClaimedIds[cid] || labelBackdropClaimedTextFrameIds[cid]) {
                    skippedClaimedLabelChild = true;
                    continue;
                }
                decoChildIds[cid] = true;
                childIds.push(cid);
                if (childIdMap) childIdMap[cid] = true;
            }
        } catch (e) {}
        _perfChildIdsMs = _decoPerfNow() - _perfChildIdsStartedAt;

        var _z = 0;
        try { _z = getItemZOrder(item); } catch (e) {}
        var entry = {
            id: domId,
            file: "rendered_frames/" + fileName,
            bounds: bounds,
            pageIndex: page.documentOffset,
            candidateId: decoCandidateMatch.candidateId,
            candidateMatchStrategy: decoCandidateMatch.strategy,
            zOrder: _z
        };
        if (cropSourceBounds) entry.cropSourceBounds = cropSourceBounds;
        try {
            entry.exportSanity = {
                fileBytes: _outFile.exists ? _outFile.length : 0,
                guarded: false,
                duplicatedForExport: _exportDup ? true : false,
                textHiddenBeforeExport: resolvedOwnershipOpts.textHiddenBeforeExport === true,
                exportTargetType: _exportTarget && _exportTarget.constructor ? _exportTarget.constructor.name : null,
                exportTargetObjectId: _plannedExportTargetId,
                exportSourceObjectIds: _plannedExportSourceIds,
                sourceBounds: _boundsOfItem(_exportTargetSource),
                pageRelativeBounds: bounds
            };
        } catch (eSanity) {}
        if (childIds.length > 0) entry.childIds = childIds;
        if (skippedClaimedLabelChild && !resolvedOwnershipOpts.sourceObjectIds) {
            var sourceIds = [domId];
            for (var si = 0; si < childIds.length; si++) sourceIds.push(childIds[si]);
            var copiedOpts = {};
            for (var ok in resolvedOwnershipOpts) copiedOpts[ok] = resolvedOwnershipOpts[ok];
            copiedOpts.sourceObjectIds = sourceIds;
            resolvedOwnershipOpts = copiedOpts;
        }
        if (resolvedOwnershipOpts.textHiddenBeforeExport === true
                && (!resolvedOwnershipOpts.hiddenTextFrameIds || resolvedOwnershipOpts.hiddenTextFrameIds.length === 0)) {
            var copiedHiddenOpts = {};
            for (var hk in resolvedOwnershipOpts) copiedHiddenOpts[hk] = resolvedOwnershipOpts[hk];
            copiedHiddenOpts.hiddenTextFrameIds = _decoCollectTextFrameIds(_exportTargetSource, true, true);
            resolvedOwnershipOpts = copiedHiddenOpts;
        }
        results.push(applyRenderOwnership(entry, item, resolvedOwnershipOpts));
        renderedPageKeys[renderKey] = true;
        if (_plannedPageCountForSource(domId) <= 1) renderedIds[domId] = true;
        _decoPerfRecord("direct_render", item, page, resolvedOwnershipOpts, _perfRenderStartedAt, {
            result: "rendered",
            candidateMatchMs: _perfCandidateMs,
            autoHideMs: _perfAutoHideMs,
            exportMs: _perfExportMs,
            childIdsMs: _perfChildIdsMs,
            childIdCount: childIds.length,
            fileName: fileName
        });
        return childIds;
    }

    function _itemsForSourceSet(ids) {
        var out = [];
        var seen = {};
        var sourceSet = {};
        for (var si = 0; ids && si < ids.length; si++) {
            sourceSet[String(ids[si])] = true;
        }
        function hasSourceAncestor(item) {
            var cur = null;
            try { cur = item ? item.parent : null; } catch (eParent) { cur = null; }
            while (cur) {
                try {
                    if (cur.id !== undefined && cur.id !== null && sourceSet[String(cur.id)]) return true;
                } catch (eId) {}
                var name = "";
                try { name = cur.constructor ? cur.constructor.name : ""; } catch (eName) {}
                if (name === "Page" || name === "Spread" || name === "Document" || name === "Story") break;
                try { cur = cur.parent; } catch (eNext) { break; }
            }
            return false;
        }
        for (var i = 0; ids && i < ids.length; i++) {
            var id = ids[i];
            var key = String(id);
            if (seen[key]) continue;
            seen[key] = true;
            var item = itemById ? itemById[key] : null;
            if (hasSourceAncestor(item)) continue;
            if (item) out.push(item);
        }
        return out;
    }

    function _renderPlannedSourceSetCompositeShell(slotPlan, page, ownershipOpts) {
        var _perfCompositeStartedAt = _decoPerfNow();
        if (!slotPlan || !page || !ownershipOpts) return false;
        function fail(reason, detail) {
            var diagItem = null;
            try {
                var diagId = slotPlan.primarySourceObjectId;
                if ((diagId === null || diagId === undefined)
                        && slotPlan.exportSourceObjectIds
                        && slotPlan.exportSourceObjectIds.length > 0) {
                    diagId = slotPlan.exportSourceObjectIds[0];
                }
                if (diagId !== null && diagId !== undefined && itemById) {
                    diagItem = itemById[String(diagId)];
                }
            } catch (eDiagItem) {}
            var copiedDetail = detail || {};
            copiedDetail.materialization = slotPlan.materialization || null;
            copiedDetail.visualAction = slotPlan.visualAction || null;
            copiedDetail.passId = slotPlan.passId || null;
            copiedDetail.candidatePurpose = slotPlan.candidatePurpose || null;
            copiedDetail.exportSourceObjectCount = slotPlan.exportSourceObjectIds
                    ? slotPlan.exportSourceObjectIds.length : 0;
            if (diagItem) {
                _recordPlannedShellRenderDiagnosticForCandidate(
                        diagItem,
                        page,
                        slotPlan,
                        reason,
                        copiedDetail);
            } else if (textlessShellDiagnostics.length < 2000) {
                textlessShellDiagnostics.push({
                    id: null,
                    pageIndex: page && page.documentOffset !== undefined ? page.documentOffset : -1,
                    accepted: false,
                    reason: reason,
                    candidateId: slotPlan.candidateId || null,
                    slotRole: slotPlan.slotRole || null,
                    compositeRole: slotPlan.compositeRole || null,
                    exportSourceObjectIds: slotPlan.exportSourceObjectIds || [],
                    detail: copiedDetail
                });
            }
            _decoPerfRecord("source_set_composite", diagItem, page, ownershipOpts, _perfCompositeStartedAt, {
                result: "failed",
                failReason: reason,
                passId: slotPlan ? slotPlan.passId || null : null,
                candidatePurpose: slotPlan ? slotPlan.candidatePurpose || null : null,
                compositeRole: slotPlan ? slotPlan.compositeRole || null : null,
                exportSourceObjectCount: slotPlan && slotPlan.exportSourceObjectIds
                        ? slotPlan.exportSourceObjectIds.length : 0
            });
            return false;
        }
        if (slotPlan.materialization !== "EXTRACTED_PNG_VECTOR") {
            return fail("planned_source_set_render_not_png_vector", {});
        }
        var isPageTextlessGraphicGroup = slotPlan.passId === "pass.page_textless_graphic_groups";
        if (slotPlan.visualAction !== "PLACE_TEXT_SHELL"
                && !(isPageTextlessGraphicGroup
                    && (slotPlan.visualAction === "PLACE_FLOATING_PNG"
                        || slotPlan.visualAction === "PLACE_INLINE_PNG"))) {
            return fail("planned_source_set_render_visual_action_not_supported", {});
        }
        var exportIds = _sortedNumericIds(slotPlan.exportSourceObjectIds || []);
        if (exportIds.length < 2) return fail("planned_source_set_render_not_composite", {
            exportSourceObjectIds: exportIds
        });

        var sourceItems = _itemsForSourceSet(exportIds);
        if (sourceItems.length < 1) {
            return fail("planned_source_set_render_no_source_items", {
                exportSourceObjectIds: exportIds
            });
        }

        var filePrefix = isPageTextlessGraphicGroup ? "page_textless_sourceset_" : "deco_sourceset_";
        var fileName = filePrefix + String(slotPlan.primarySourceObjectId || exportIds[0])
                + "_n" + String(exportIds.length)
                + "_h" + _decoSourceSetFileHash(exportIds) + ".png";
        var outFile = File(renderDir + "/" + fileName);
        var renderKey = "sourceset|" + String(slotPlan.candidateId || fileName);
        if (renderedPageKeys[renderKey]) return true;

        function sourceOrderInfoForItem(item) {
            var src = null;
            try {
                if (item && item.id !== undefined && item.id !== null) {
                    src = sourceInfoById[String(item.id)];
                }
            } catch (eItemId) {}
            var z = null;
            var order = null;
            if (src) {
                if (src.zOrder !== undefined && src.zOrder !== null) z = Number(src.zOrder);
                if (src.sourceOrder !== undefined && src.sourceOrder !== null) order = Number(src.sourceOrder);
            }
            if (isNaN(z) || z === null) {
                try { z = Number(getItemZOrder(item)); } catch (eDomZ) { z = 0; }
            }
            if (isNaN(order) || order === null) {
                try { order = Number(getItemZOrder(item)); } catch (eDomOrder) { order = 0; }
            }
            return { z: z, order: order };
        }

        function sortSourceItemsByPlannedZOrder(items) {
            var copy = [];
            for (var si = 0; si < items.length; si++) copy.push(items[si]);
            copy.sort(function(a, b) {
                var ai = sourceOrderInfoForItem(a);
                var bi = sourceOrderInfoForItem(b);
                if (ai.z !== bi.z) return ai.z - bi.z;
                return ai.order - bi.order;
            });
            return copy;
        }

        var dups = [];
        var tempGroup = null;
        var savedOutOfScope = [];
        var ordered = sortSourceItemsByPlannedZOrder(sourceItems);
        var groupCreateErrors = [];
        var sourceItemDebug = [];
        var hiddenVisualIds = _sortedNumericIds(slotPlan.hiddenVisualSourceObjectIds || []);
        function itemParentKey(item) {
            try {
                var p = item ? item.parent : null;
                if (p && p.id !== undefined && p.id !== null) return String(p.id);
            } catch (eParentKey) {}
            return "";
        }
        function appendHiddenVisualChildrenForItem(item, out, seen) {
            if (!item || !hiddenVisualIds || hiddenVisualIds.length === 0) return;
            for (var hi = 0; hi < hiddenVisualIds.length; hi++) {
                var hiddenId = hiddenVisualIds[hi];
                var hiddenItem = null;
                try { hiddenItem = _findNestedPageItemById(item, hiddenId); } catch (eFindHidden) {}
                if (!hiddenItem) continue;
                try {
                    if (item.id !== undefined && item.id !== null
                            && String(item.id) === String(hiddenId)) {
                        continue;
                    }
                } catch (eSelfHidden) {}
                var key = String(hiddenId);
                if (seen[key]) continue;
                seen[key] = true;
                out.push(hiddenItem);
            }
        }
        function groupItemsInParent(items, parentItem) {
            if (!items || items.length === 0) return null;
            if (items.length === 1) return items[0];
            var group = null;
            try {
                if (parentItem && parentItem.groups) group = parentItem.groups.add(items);
            } catch (eParentGroup) {
                try { groupCreateErrors.push("parent:" + String(eParentGroup)); } catch (ePushParent) {}
            }
            if (!group) {
                try { group = page.groups.add(items); }
                catch (ePageGroup) {
                    try { groupCreateErrors.push("page:" + String(ePageGroup)); } catch (ePushPage) {}
                }
            }
            if (!group) {
                try { group = doc.groups.add(items); }
                catch (eDocGroup) {
                    try { groupCreateErrors.push("doc:" + String(eDocGroup)); } catch (ePushDoc) {}
                }
            }
            return group;
        }
        try {
            for (var i = 0; i < ordered.length; i++) {
                try {
                    sourceItemDebug.push({
                        id: ordered[i].id,
                        kind: ordered[i].constructor ? ordered[i].constructor.name : null,
                        parentId: ordered[i].parent && ordered[i].parent.id !== undefined ? ordered[i].parent.id : null,
                        parentKind: ordered[i].parent && ordered[i].parent.constructor ? ordered[i].parent.constructor.name : null
                    });
                } catch (eSourceItemDebug) {}
                var hiddenForItem = [];
                try { hiddenForItem = _collectOutOfScopeChildrenForSourceIds(ordered[i], exportIds); } catch (eOutOfScope) {}
                try {
                    var hiddenSeen = {};
                    for (var hfi = 0; hfi < hiddenForItem.length; hfi++) {
                        try { hiddenSeen[String(hiddenForItem[hfi].id)] = true; } catch (eSeenHidden) {}
                    }
                    appendHiddenVisualChildrenForItem(ordered[i], hiddenForItem, hiddenSeen);
                } catch (eHiddenVisualForItem) {}
                if (hiddenForItem && hiddenForItem.length > 0) {
                    var savedForItem = _hideItemsForExport(hiddenForItem);
                    for (var si = 0; savedForItem && si < savedForItem.length; si++) {
                        savedOutOfScope.push(savedForItem[si]);
                    }
                }
                var dup = ordered[i].duplicate();
                _clearTextFramesInRenderDuplicate(dup);
                dups.push(dup);
            }
            if (dups.length === 1) {
                tempGroup = dups[0];
            } else {
                var parentBuckets = {};
                var parentItems = {};
                for (var dbi = 0; dbi < dups.length; dbi++) {
                    var key = itemParentKey(dups[dbi]);
                    if (!parentBuckets[key]) parentBuckets[key] = [];
                    parentBuckets[key].push(dups[dbi]);
                    try { if (!parentItems[key]) parentItems[key] = dups[dbi].parent; } catch (eParentBucket) {}
                }
                var bucketGroups = [];
                for (var bucketKey in parentBuckets) {
                    if (parentBuckets.hasOwnProperty && !parentBuckets.hasOwnProperty(bucketKey)) continue;
                    var bucketGroup = groupItemsInParent(parentBuckets[bucketKey], parentItems[bucketKey]);
                    if (bucketGroup) bucketGroups.push(bucketGroup);
                }
                if (bucketGroups.length === 1) {
                    tempGroup = bucketGroups[0];
                } else if (bucketGroups.length > 1) {
                    var parentForTempGroup = null;
                    try { parentForTempGroup = bucketGroups[0] ? bucketGroups[0].parent : null; } catch (eParentForTempGroup) {}
                    try {
                        if (parentForTempGroup && parentForTempGroup.groups) {
                            tempGroup = parentForTempGroup.groups.add(bucketGroups);
                        }
                    } catch (eGroupParent) {
                        try { groupCreateErrors.push("bucketParent:" + String(eGroupParent)); } catch (ePushBucketParent) {}
                    }
                    if (!tempGroup) {
                        try {
                            tempGroup = page.groups.add(bucketGroups);
                        } catch (eGroupPage) {
                            try { groupCreateErrors.push("bucketPage:" + String(eGroupPage)); } catch (ePushBucketPage) {}
                            try { tempGroup = doc.groups.add(bucketGroups); }
                            catch (eGroupDoc) {
                                try { groupCreateErrors.push("bucketDoc:" + String(eGroupDoc)); } catch (ePushBucketDoc) {}
                            }
                        }
                    }
                }
            }
            if (!tempGroup) {
                return fail("planned_source_set_render_group_create_failed", {
                    exportSourceObjectIds: exportIds,
                    duplicateCount: dups.length,
                    groupCreateErrors: groupCreateErrors,
                    sourceItems: sourceItemDebug
                });
            }
            try { tempGroup.exportFile(ExportFormat.PNG_FORMAT, outFile); } catch (eExport) {}
            try {
                if (!outFile.exists || outFile.length < 512) {
                    return fail("planned_source_set_render_export_too_small", {
                        fileName: fileName,
                        fileExists: outFile.exists,
                        fileBytes: outFile.exists ? outFile.length : 0,
                        exportSourceObjectIds: exportIds,
                        sourceItemCount: sourceItems.length,
                        duplicateCount: dups.length
                    });
                }
            } catch (eSize) {
                return fail("planned_source_set_render_export_size_check_failed", {
                    fileName: fileName,
                    error: String(eSize),
                    exportSourceObjectIds: exportIds,
                    sourceItemCount: sourceItems.length,
                    duplicateCount: dups.length
                });
            }

            var exportSourceBounds = null;
            try { exportSourceBounds = arrCopy(tempGroup.visibleBounds); } catch (eBounds) {}
            if (!exportSourceBounds) try { exportSourceBounds = arrCopy(tempGroup.geometricBounds); } catch (eBounds2) {}

            var exportBounds = null;
            var exportFullBounds = null;
            var exportCropSourceBounds = null;
            if (exportSourceBounds) {
                exportFullBounds = arrCopy(exportSourceBounds);
                var visibleExportBounds = arrCopy(exportSourceBounds);
                var pageIntersection = null;
                try { pageIntersection = _decoBoundsIntersection(exportSourceBounds, page.bounds); } catch (eIntersect) {}
                if (pageIntersection && _decoBoundsDiffer(exportSourceBounds, pageIntersection, 0.01)) {
                    exportCropSourceBounds = arrCopy(exportSourceBounds);
                    visibleExportBounds = pageIntersection;
                }
                exportBounds = arrCopy(visibleExportBounds);
                _toPageRelativeBounds(exportBounds, page);
                _toPageRelativeBounds(exportFullBounds, page);
                if (exportCropSourceBounds) _toPageRelativeBounds(exportCropSourceBounds, page);
            }

            var boundsInfo = _pageRelativeSourceUnionBoundsInfo(sourceItems, page);
            // A source-set composite PNG must be placed with the same geometry used
            // by the actual InDesign export.  The source union can differ from the
            // duplicated tempGroup's visible bounds because grouping, strokes, masks,
            // and cleared child text frames affect the rendered canvas.
            var bounds = exportBounds || (boundsInfo ? boundsInfo.bounds : null);
            var cropSourceBounds = exportCropSourceBounds || (boundsInfo ? boundsInfo.cropSourceBounds : null);

            var z = 0;
            if (slotPlan.zOrder !== undefined && slotPlan.zOrder !== null) {
                z = slotPlan.zOrder;
            } else {
                try { z = getItemZOrder(sourceItems[0]); } catch (eZ0) {}
            }
            var opts = {};
            for (var key in ownershipOpts) {
                if (ownershipOpts.hasOwnProperty(key)) opts[key] = ownershipOpts[key];
            }
            opts.sourceObjectIds = slotPlan.sourceObjectIds || ownershipOpts.sourceObjectIds || exportIds;
            opts.exportSourceObjectIds = exportIds;
            opts.visualOnlyChildIds = exportIds;
            opts.layerSource = sourceItems[0];
            opts.reason = ownershipOpts.reason || (isPageTextlessGraphicGroup
                    ? "planned_page_textless_graphic_group"
                    : "planned_source_set_text_shell");

            var entryIdBase = isPageTextlessGraphicGroup ? -920000000 : -910000000;
            var entryId = entryIdBase + (Number(slotPlan.primarySourceObjectId || exportIds[0]) || 0);
            var entry = {
                id: entryId,
                file: "rendered_frames/" + fileName,
                bounds: bounds,
                pageIndex: page.documentOffset,
                candidateId: slotPlan.candidateId || null,
                candidateMatchStrategy: "planned_source_set_composite",
                zOrder: z,
                exportSanity: {
                    fileBytes: outFile.exists ? outFile.length : 0,
                    sourceSetComposite: true,
                    pageTextlessGraphicGroup: isPageTextlessGraphicGroup,
                    exportSourceObjectIds: exportIds,
                    pageRelativeBounds: bounds,
                    exportPageRelativeBounds: exportBounds,
                    exportFullPageRelativeBounds: exportFullBounds,
                    cropSourceBounds: cropSourceBounds
                }
            };
            if (cropSourceBounds) entry.cropSourceBounds = cropSourceBounds;
            results.push(applyRenderOwnership(entry, tempGroup, opts));
            renderedPageKeys[renderKey] = true;
            for (var mi = 0; mi < exportIds.length; mi++) {
                decoChildIds[exportIds[mi]] = true;
                renderedIds[exportIds[mi]] = true;
            }
            var renderedSourceIds = _sortedNumericIds(slotPlan.sourceObjectIds || []);
            for (var rsi = 0; rsi < renderedSourceIds.length; rsi++) {
                renderedIds[renderedSourceIds[rsi]] = true;
            }
            _decoPerfRecord("source_set_composite", tempGroup, page, ownershipOpts, _perfCompositeStartedAt, {
                result: "rendered",
                passId: slotPlan.passId || null,
                candidatePurpose: slotPlan.candidatePurpose || null,
                compositeRole: slotPlan.compositeRole || null,
                sourceItemCount: sourceItems.length,
                duplicateCount: dups.length,
                exportSourceObjectCount: exportIds.length,
                fileName: fileName
            });
            return true;
        } catch (e) {
            return fail("planned_source_set_render_exception", {
                error: String(e),
                exportSourceObjectIds: exportIds,
                sourceItemCount: sourceItems ? sourceItems.length : 0,
                duplicateCount: dups ? dups.length : 0
            });
        } finally {
            try {
                if (savedOutOfScope && savedOutOfScope.length > 0) {
                    _restoreItemsForExport(savedOutOfScope);
                }
            } catch (eRestoreOutOfScope) {}
            try {
                if (tempGroup) tempGroup.remove();
                else {
                    for (var di = 0; di < dups.length; di++) {
                        try { dups[di].remove(); } catch (eDup) {}
                    }
                }
            } catch (eCleanup) {}
        }
    }

    function _boundsOfItem(item) {
        var key = _decoCacheKey(item);
        if (key !== null && decoBoundsCache.hasOwnProperty(key)) {
            return decoBoundsCache[key] ? arrCopy(decoBoundsCache[key]) : null;
        }
        var b = null;
        try { b = arrCopy(item.visibleBounds); } catch (e) {}
        if (!b) try { b = arrCopy(item.geometricBounds); } catch (e2) {}
        if (key !== null) decoBoundsCache[key] = b ? arrCopy(b) : null;
        return b;
    }

    function _absoluteSourceUnionBounds(items) {
        var union = null;
        for (var i = 0; items && i < items.length; i++) {
            var b = _boundsOfItem(items[i]);
            if (!b || b.length < 4) continue;
            if (!union) union = arrCopy(b);
            else {
                union[0] = Math.min(union[0], b[0]);
                union[1] = Math.min(union[1], b[1]);
                union[2] = Math.max(union[2], b[2]);
                union[3] = Math.max(union[3], b[3]);
            }
        }
        return union;
    }

    function _pageRelativeSourceUnionBounds(items, page) {
        var info = _pageRelativeSourceUnionBoundsInfo(items, page);
        return info ? info.bounds : null;
    }

    function _pageRelativeSourceUnionBoundsInfo(items, page) {
        var union = _absoluteSourceUnionBounds(items);
        if (!union) return null;
        var original = arrCopy(union);
        var pageBounds = null;
        try { pageBounds = arrCopy(page.bounds); } catch (ePageBounds) {}
        var cropSourceBounds = null;
        if (pageBounds) {
            var intersection = _decoBoundsIntersection(union, pageBounds);
            if (intersection) union = intersection;
        }
        if (_decoBoundsDiffer(original, union, 0.01)) {
            cropSourceBounds = arrCopy(original);
            _toPageRelativeBounds(cropSourceBounds, page);
        }
        _toPageRelativeBounds(union, page);
        return {
            bounds: union,
            cropSourceBounds: cropSourceBounds
        };
    }

    function _textStatsOfGroup(grp) {
        var stats = { count: 0, length: 0, text: "", hasTable: false, titleLabelStyle: false };
        try {
            var nested = _decoAllPageItems(grp);
            for (var i = 0; i < nested.length; i++) {
                var item = nested[i];
                if (item.constructor.name !== "TextFrame") continue;
                stats.count++;
                try {
                    var tableCount = 0;
                    if (item.parentStory && item.parentStory.tables) {
                        if (item.parentStory.tables.length !== undefined && item.parentStory.tables.length !== null) {
                            tableCount = Number(item.parentStory.tables.length || 0);
                        } else if (item.parentStory.tables.count) {
                            tableCount = Number(item.parentStory.tables.count() || 0);
                        }
                    }
                    if (tableCount > 0) {
                        stats.hasTable = true;
                    }
                } catch (eTable) {}
                try {
                    var text = item.contents || "";
                    text = String(text).replace(/[\s\r\n\t\u0016\u0018\u0003\uFFFC]/g, "");
                    stats.length += text.length;
                    stats.text += text;
                } catch (eText) {}
                try {
                    var ps = item.parentStory && item.parentStory.paragraphs.length > 0
                            ? item.parentStory.paragraphs[0].appliedParagraphStyle
                            : null;
                    var styleName = ps ? String(ps.name || "") : "";
                    if (styleName.indexOf("#표제목") === 0) stats.titleLabelStyle = true;
                } catch (eStyle) {}
            }
        } catch (e) {}
        return stats;
    }

    function _plainTextOfTextFrame(tf) {
        var text = "";
        try { text = tf.contents || ""; } catch (eContents) {}
        if (!text) {
            try {
                if (tf.parentStory) text = tf.parentStory.contents || "";
            } catch (eStoryContents) {}
        }
        return String(text || "").replace(/[\s\r\n\t\u0016\u0018\u0003\uFFFC]/g, "");
    }

    function _isSimpleMarkerLabelText(text) {
        text = String(text || "").replace(/[\s\r\n\t\u0016\u0018\u0003\uFFFC]/g, "");
        if (!text) return false;
        if (/^(가|나|다|라|마|바|ㄱ|ㄴ|ㄷ|ㄹ|ㅁ|ㅂ|ㅅ|ㅇ|ㅈ|ㅊ|ㅋ|ㅌ|ㅍ|ㅎ)$/.test(text)) return true;
        if (/^[0-9]{1,2}$/.test(text)) return true;
        if (/^[①-⑳]$/.test(text)) return true;
        return false;
    }

    function _isVisualMarkerLabelGroup(grp) {
        var stats = _textStatsOfGroup(grp);
        if (stats.count !== 1) return false;
        var text = stats.text || "";
        if (text.length < 1 || text.length > 2) {
            try {
                    var nested = _decoAllPageItems(grp);
                for (var i = 0; i < nested.length; i++) {
                    if (nested[i].constructor.name !== "TextFrame") continue;
                    text = _plainTextOfTextFrame(nested[i]);
                    break;
                }
            } catch (eFallbackText) {}
        }
        // 선택지/번호/체크박스처럼 텍스트보다 마커 시각성이 우선인 짧은 버튼만
        // InDesign PNG가 텍스트까지 소유할 수 있다. "나는", "굴을" 같은 짧은
        // 의미 단어 라벨은 editable TF로 유지하고 배경만 shell PNG로 추출한다.
        return _isSimpleMarkerLabelText(text);
    }

    function _completePngMarkerDecision(grp, editableIds) {
        var ids = editableIds || [];
        var marker = false;
        try { marker = _isVisualMarkerLabelGroup(grp); } catch (eMarker) {}
        return {
            complete: marker || ids.length === 0,
            marker: marker,
            containsText: marker,
            containsEditableText: marker && ids.length > 0,
            editableTextFrameIds: ids
        };
    }

    function _renderEditableVisualLabelShell(grp, page, reason) {
        var editableIds = [];
        try { editableIds = _decoCollectTextFrameIds(grp, true, true); } catch (eIds) {}
        var savedTFs = null;
        try {
            savedTFs = hideTextFrames(grp);
            _decoRender(grp, page, null, {
                textHiddenBeforeExport: true,
                textOwner: "hwpx_tf",
                placementAllowed: true,
                editableTextFrameIds: editableIds,
                reason: reason || "visual_label_text_hidden_shell"
            });
        } catch (e) {
            // fall through to restore
        } finally {
            try { if (savedTFs && savedTFs.length > 0) restoreTextFrames(savedTFs); } catch (eRestore) {}
        }
    }

    // Planned shell/background exports are explicit E1 instructions. Render the
    // candidate's page-local source set instead of relying on global source-id
    // traversal. This covers both explicit slot-only shells and spread-spanning
    // background shape containers that materialize once per intersecting page.
    var _perfPhaseStartedAt = _decoPerfNow();
    for (var soi = 0; soi < plannedItems.length; soi++) {
        var slotPlan = plannedItems[soi].candidate;
        var slotItem = plannedItems[soi].item;
        if (!slotPlan || !slotItem) {
            if (slotPlan) {
                _recordPlannedShellRenderDiagnosticForCandidate(
                        slotItem,
                        null,
                        slotPlan,
                        "planned_shell_render_missing_item",
                        {});
            }
            continue;
        }
        var isExplicitSlotOnly = slotPlan.slotRole === "shell_slot_only"
                || slotPlan.slotRole === "direct_child_shell_slot";
        var isPageLocalBackgroundShape = _isPlannedPageLocalBackgroundShapeCandidate(slotPlan, slotItem);
        var isPlannedVectorShape = slotPlan.passId === "pass.vector_shape_frames"
                && slotPlan.candidatePurpose === "VECTOR_CANDIDATE";
        var isGraphicOnlyCompositeShell =
                _isPlannedGraphicOnlyCompositeShellCandidate(slotPlan, slotItem);
        var isTextShellComposite =
                _isPlannedTextShellCompositeCandidate(slotPlan, slotItem);
        var isPageTextlessGraphicGroup =
                slotPlan.passId === "pass.page_textless_graphic_groups"
                && slotPlan.candidatePurpose === "CONTENT_CANDIDATE";
        if (!isExplicitSlotOnly && !isPageLocalBackgroundShape
                && !isPlannedVectorShape
                && !isGraphicOnlyCompositeShell && !isTextShellComposite
                && !isPageTextlessGraphicGroup) continue;
        var slotPage = null;
        try { slotPage = slotItem.parentPage; } catch (eSlotPage) {}
        slotPage = _pageForPlannedItem(plannedItems[soi], slotPage);
        if (!slotPage) {
            _recordPlannedShellRenderDiagnosticForCandidate(
                    slotItem,
                    null,
                    slotPlan,
                    "planned_shell_render_missing_page",
                    {});
            continue;
        }
        if (slotPage.documentOffset + 1 < startPage || slotPage.documentOffset + 1 > endPage) continue;
        try {
            var slotOwnershipOpts = {
                candidateId: slotPlan.candidateId || null,
                sourceObjectIds: slotPlan.sourceObjectIds || [],
                hiddenVisualSourceObjectIds: slotPlan.hiddenVisualSourceObjectIds || [],
                exportSourceObjectIds: slotPlan.exportSourceObjectIds || [],
                exportTargetObjectId: slotPlan.exportTargetObjectId !== undefined ? slotPlan.exportTargetObjectId : null,
                renderObjectId: _plannedSlotRenderObjectId(slotPlan),
                slotRole: slotPlan.slotRole,
                renderMode: slotPlan.mode,
                placementRole: slotPlan.placement === "INLINE" ? "inline_object" : null,
                inlineAnchorSourceObjectId: slotPlan.inlineAnchorSourceObjectId || null,
                inlineSourceTreeClosed: slotPlan.inlineSourceTreeClosed === true,
                placement: slotPlan.placement || null,
                coordinateSpace: slotPlan.coordinateSpace || null,
                placementAllowed: true,
                reason: "decoration_group",
                textOwner: "none"
            };
            if (isGraphicOnlyCompositeShell) {
                slotOwnershipOpts.containsText = false;
                slotOwnershipOpts.containsEditableText = false;
                slotOwnershipOpts.reason = "graphic_ownership_root";
            }
            if (isPlannedVectorShape) {
                slotOwnershipOpts.containsText = false;
                slotOwnershipOpts.containsEditableText = false;
                slotOwnershipOpts.reason = "planned_vector_shape";
            }
            if (isTextShellComposite) {
                slotOwnershipOpts.textOwner = "hwpx_tf";
                slotOwnershipOpts.editableTextFrameIds = _decoCollectTextFrameIds(slotItem, true, true);
                slotOwnershipOpts.containsText = false;
                slotOwnershipOpts.containsEditableText = false;
                slotOwnershipOpts.reason = "planned_text_shell_composite";
            }
            if (isPageTextlessGraphicGroup) {
                slotOwnershipOpts.textOwner = "none";
                slotOwnershipOpts.containsText = false;
                slotOwnershipOpts.containsEditableText = false;
                slotOwnershipOpts.reason = "planned_page_textless_graphic_group";
            }
            if (isExplicitSlotOnly) {
                slotOwnershipOpts.textOwner = "hwpx_tf";
                slotOwnershipOpts.editableTextFrameIds = _decoCollectTextFrameIds(slotItem, true, true);
                slotOwnershipOpts.containsText = false;
                slotOwnershipOpts.containsEditableText = false;
                slotOwnershipOpts.reason = "slot_only_textless_shell";
            }
            if (_renderPlannedSourceSetCompositeShell(slotPlan, slotPage, slotOwnershipOpts)) {
                continue;
            }
            _decoRender(slotItem, slotPage, null, slotOwnershipOpts);
        } catch (eSlotOnlyRender) {
            _recordPlannedShellRenderDiagnosticForCandidate(
                    slotItem,
                    slotPage,
                    slotPlan,
                    "planned_shell_render_exception",
                    { error: String(eSlotOnlyRender) });
        }
    }
    _decoPerfEndPhase("planned_loop", _perfPhaseStartedAt);

    function _hasVisualShapeChild(grp) {
        try {
            var nested = _decoAllPageItems(grp);
            for (var i = 0; i < nested.length; i++) {
                var cn = nested[i].constructor.name;
                if (cn === "Rectangle" || cn === "Oval" || cn === "Polygon"
                        || cn === "GraphicLine" || cn === "Group") return true;
            }
        } catch (e) {}
        return false;
    }

    function _pushDirectGroupChild(items, seen, grp, item) {
        if (!items || !seen || !grp || !item) return;
        try {
            var p = item.parent;
            if (!p || p.id !== grp.id) return;
            var key = item.id !== undefined && item.id !== null ? item.id.toString() : "";
            if (!key || seen[key]) return;
            seen[key] = true;
            items.push(item);
        } catch (eParent) {}
    }

    function _collectTypedDirectGroupChildren(grp, items, seen, collectionName) {
        try {
            var coll = grp[collectionName];
            for (var i = 0; coll && i < coll.length; i++) {
                _pushDirectGroupChild(items, seen, grp, coll[i]);
            }
        } catch (eColl) {}
    }

    function _directPageItemsOfGroup(grp) {
        var groupKey = _decoCacheKey(grp);
        if (groupKey !== null && decoDirectGroupItemsCache.hasOwnProperty(groupKey)) {
            return decoDirectGroupItemsCache[groupKey].slice(0);
        }
        var items = [];
        var seen = {};
        if (!grp) return items;

        // InDesign sometimes exposes direct children from Group.pageItems/allPageItems
        // as generic PageItem wrappers. Prefer typed collections so shell
        // ownership sees TextFrame/Polygon/etc. from the original source object.
        _collectTypedDirectGroupChildren(grp, items, seen, "textFrames");
        _collectTypedDirectGroupChildren(grp, items, seen, "rectangles");
        _collectTypedDirectGroupChildren(grp, items, seen, "ovals");
        _collectTypedDirectGroupChildren(grp, items, seen, "polygons");
        _collectTypedDirectGroupChildren(grp, items, seen, "graphicLines");
        _collectTypedDirectGroupChildren(grp, items, seen, "groups");

        try {
            var direct = grp.pageItems;
            for (var i = 0; direct && i < direct.length; i++) {
                _pushDirectGroupChild(items, seen, grp, direct[i]);
            }
        } catch (eDirect) {}
        try {
            var nested = _decoAllPageItems(grp);
            for (var j = 0; nested && j < nested.length; j++) {
                _pushDirectGroupChild(items, seen, grp, nested[j]);
            }
        } catch (eNested) {}
        if (groupKey !== null) decoDirectGroupItemsCache[groupKey] = items.slice(0);
        return items;
    }

    function _isDirectVisualShapeItem(item) {
        if (!item) return false;
        var cn = "";
        try { cn = item.constructor.name; } catch (eCn) {}
        if (cn !== "Rectangle" && cn !== "Oval" && cn !== "Polygon" && cn !== "GraphicLine") return false;
        try {
            if (item.images && item.images.length > 0) return false;
            if (item.pdfs && item.pdfs.length > 0) return false;
            if (item.epss && item.epss.length > 0) return false;
        } catch (ePlaced) {}
        return hasVisibleFill(item) || hasVisibleStroke(item);
    }

    function _hasDirectVisualShapeChild(grp) {
        var direct = _directPageItemsOfGroup(grp);
        for (var i = 0; i < direct.length; i++) {
            if (_isDirectVisualShapeItem(direct[i])) return true;
        }
        return false;
    }

    function _collectShellVisualSourceIds(item, out, seen) {
        if (!item || !out || !seen) return;
        _appendSourceAndDescendantIds(item, out, seen);
    }

    function _isVisualOnlyShellSource(item) {
        if (!item) return false;
        try { if (isOnHiddenLayer(item)) return false; } catch (eHidden) { return false; }
        try {
            if (item.images && item.images.length > 0) return false;
            if (item.pdfs && item.pdfs.length > 0) return false;
            if (item.epss && item.epss.length > 0) return false;
        } catch (ePlaced) {}

        var cn = "";
        try { cn = item.constructor.name; } catch (eCn) {}
        if (cn === "TextFrame") {
            try { if (visibleTextLengthOfTextFrame(item) > 0) return false; } catch (eText) { return false; }
            return hasVisibleFill(item) || hasVisibleStroke(item);
        }
        if (cn === "Rectangle" || cn === "Oval" || cn === "Polygon" || cn === "GraphicLine") {
            return _isDirectVisualShapeItem(item);
        }
        if (cn !== "Group") return false;
        if (_decoHasPlaced(item)) return false;

        var hasVisual = false;
        try {
            var nested = item.allPageItems;
            for (var i = 0; nested && i < nested.length; i++) {
                var child = nested[i];
                if (!child) continue;
                var ccn = "";
                try { ccn = child.constructor.name; } catch (eChildCn) {}
                if (ccn === "TextFrame") {
                    try { if (visibleTextLengthOfTextFrame(child) > 0) return false; } catch (eChildText) { return false; }
                    if (hasVisibleFill(child) || hasVisibleStroke(child)) hasVisual = true;
                    continue;
                }
                if (ccn === "Group") {
                    if (!_isVisualOnlyShellSource(child)) return false;
                    hasVisual = true;
                    continue;
                }
                if (ccn === "Rectangle" || ccn === "Oval" || ccn === "Polygon" || ccn === "GraphicLine") {
                    if (_isDirectVisualShapeItem(child)) hasVisual = true;
                    continue;
                }
                return false;
            }
        } catch (eNested) {
            return false;
        }
        return hasVisual;
    }

    /**
     * Source-bundle classifier for textless shell extraction.
     *
     * The decision is structural: a group is a textless shell candidate when its
     * editable TextFrames and visual shell/content sources form a closed source
     * owner. Text length and visual size are intentionally not ownership
     * criteria. Some InDesign cards nest the editable TextFrame one level below
     * the visual shell, or include a placed image clipped by an Oval/Rectangle;
     * those still belong to the same source owner and must be exported once with
     * the text hidden.
     */
    function _textlessShellDiagnosticsRelevant(grp) {
        try { if (!grp || grp.constructor.name !== "Group") return false; } catch (e0) { return false; }
        try {
            var nested = _decoAllPageItems(grp);
            for (var i = 0; nested && i < nested.length; i++) {
                var item = nested[i];
                if (!item) continue;
                try {
                    if (item.constructor.name === "TextFrame" && visibleTextLengthOfTextFrame(item) > 0) return true;
                } catch (eText) {}
                try {
                    if (hasVisibleFill(item) || hasVisibleStroke(item)) return true;
                } catch (eVisual) {}
            }
        } catch (eNested) {}
        return false;
    }

    function _recordTextlessShellDiagnostic(grp, accepted, reason, detail) {
        if (!grp || textlessShellDiagnostics.length >= 2000) return;
        if (!accepted && !_textlessShellDiagnosticsRelevant(grp)) return;
        var entry = {
            id: null,
            pageIndex: -1,
            accepted: accepted ? true : false,
            reason: reason || (accepted ? "accepted" : "rejected")
        };
        try { entry.id = grp.id; } catch (eId) {}
        try {
            var page = _resolveGroupPage(grp);
            if (page) entry.pageIndex = page.documentOffset;
        } catch (ePage) {}
        try {
            var b = _boundsOfItem(grp);
            if (b) entry.bounds = arrCopy(b);
        } catch (eBounds) {}
        if (detail) {
            for (var k in detail) {
                if (detail.hasOwnProperty(k)) entry[k] = detail[k];
            }
        }
        textlessShellDiagnostics.push(entry);
    }

    function _recordPlannedShellRenderDiagnostic(item, page, reason, detail) {
        if (!item || textlessShellDiagnostics.length >= 2000) return;
        var entry = {
            id: null,
            pageIndex: -1,
            accepted: false,
            reason: reason || "planned_shell_render_failed"
        };
        try { entry.id = item.id; } catch (eId) {}
        try { if (page) entry.pageIndex = page.documentOffset; } catch (ePage) {}
        try {
            var b = _boundsOfItem(item);
            if (b) entry.bounds = arrCopy(b);
        } catch (eBounds) {}
        if (detail) {
            for (var k in detail) {
                if (detail.hasOwnProperty(k)) entry[k] = detail[k];
            }
        }
        textlessShellDiagnostics.push(entry);
    }

    function _recordPlannedShellRenderDiagnosticForCandidate(item, page, candidate, reason, detail) {
        var copied = detail || {};
        if (candidate) {
            copied.candidateId = candidate.candidateId || null;
            copied.slotRole = candidate.slotRole || null;
            copied.compositeRole = candidate.compositeRole || null;
            copied.exportSourceObjectIds = candidate.exportSourceObjectIds || [];
            copied.exportTargetObjectId = candidate.exportTargetObjectId !== undefined
                    ? candidate.exportTargetObjectId : null;
        }
        _recordPlannedShellRenderDiagnostic(item, page, reason, copied);
    }

    function _classifyTextlessShellCandidate(grp, collectDiagnostics) {
        function fail(reason, detail) {
            if (collectDiagnostics) _recordTextlessShellDiagnostic(grp, false, reason, detail);
            return null;
        }
        function accept(candidate) {
            if (collectDiagnostics) {
                _recordTextlessShellDiagnostic(grp, true, candidate.reason || "accepted", {
                    ownedTextFrameIds: candidate.ownedTextFrameIds,
                    visualSourceObjectIds: candidate.visualSourceObjectIds
                });
            }
            return candidate;
        }

        function hasPlacedContent(item) {
            try {
                if (item.images && item.images.length > 0) return true;
                if (item.pdfs && item.pdfs.length > 0) return true;
                if (item.epss && item.epss.length > 0) return true;
            } catch (ePlaced) {}
            return false;
        }

        function isTextShellVisualSource(item) {
            if (!item) return false;
            try { if (isOnHiddenLayer(item)) return false; } catch (eHiddenSource) { return false; }
            var cn = "";
            try { cn = item.constructor.name; } catch (eCnSource) {}
            if (cn === "TextFrame") {
                try { if (visibleTextLengthOfTextFrame(item) > 0) return false; } catch (eTextSource) { return false; }
                return hasVisibleFill(item) || hasVisibleStroke(item);
            }
            if (cn === "Rectangle" || cn === "Oval" || cn === "Polygon" || cn === "GraphicLine") {
                return _isDirectVisualShapeItem(item) || hasPlacedContent(item);
            }
            return false;
        }

        function collectClosedTextShellBranch(item, state) {
            if (!item || !state) return false;
            try { if (isOnHiddenLayer(item)) return false; } catch (eHiddenBranch) { return false; }
            var cn = "";
            try { cn = item.constructor.name; } catch (eCnBranch) {}

            if (cn === "TextFrame") {
                if (_isDirectEditableTextFrame(item)) {
                    _pushUniqueId(state.editableIds, state.editableSeen, item.id);
                    return true;
                }
                try {
                    if (visibleTextLengthOfTextFrame(item) > 0) {
                        state.failReason = "non_editable_visible_textframe";
                        state.failDetail = { childId: item.id };
                        return false;
                    }
                } catch (eTextBranch) {
                    state.failReason = "textframe_text_check_failed";
                    state.failDetail = { childId: item.id };
                    return false;
                }
                if (isTextShellVisualSource(item)) {
                    _collectShellVisualSourceIds(item, state.visualIds, state.visualSeen);
                    state.visualCount++;
                    return true;
                }
                state.failReason = "textframe_not_editable_or_shell";
                state.failDetail = { childId: item.id };
                return false;
            }

            if (isTextShellVisualSource(item)) {
                _collectShellVisualSourceIds(item, state.visualIds, state.visualSeen);
                state.visualCount++;
                return true;
            }

            if (cn !== "Group") {
                state.failReason = "child_not_text_shell_source";
                state.failDetail = { childId: item.id, childType: cn };
                return false;
            }

            if (_isVisualOnlyShellSource(item)) {
                _collectShellVisualSourceIds(item, state.visualIds, state.visualSeen);
                state.visualCount++;
                return true;
            }

            var nestedDirect = _directPageItemsOfGroup(item);
            if (!nestedDirect || nestedDirect.length === 0) {
                state.failReason = "empty_nested_group";
                state.failDetail = { childId: item.id };
                return false;
            }
            var beforeEditable = state.editableIds.length;
            var beforeVisual = state.visualCount;
            for (var ni = 0; ni < nestedDirect.length; ni++) {
                if (!collectClosedTextShellBranch(nestedDirect[ni], state)) return false;
            }
            if (state.editableIds.length === beforeEditable && state.visualCount === beforeVisual) {
                state.failReason = "nested_group_without_text_or_visual_shell";
                state.failDetail = { childId: item.id };
                return false;
            }
            return true;
        }

        try { if (!grp || grp.constructor.name !== "Group") return fail("not_group"); } catch (e0) { return fail("not_group"); }
        try { if (isOnHiddenLayer(grp)) return fail("hidden_layer"); } catch (eHidden) { return fail("hidden_layer_check_failed"); }

        var direct = _directPageItemsOfGroup(grp);
        if (!direct || direct.length < 2) return fail("not_enough_direct_children");

        var state = {
            editableIds: [],
            editableSeen: {},
            visualIds: [],
            visualSeen: {},
            visualCount: 0,
            failReason: null,
            failDetail: null
        };

        for (var i = 0; i < direct.length; i++) {
            if (!collectClosedTextShellBranch(direct[i], state)) {
                return fail(state.failReason || "direct_child_not_text_shell_source", state.failDetail);
            }
        }

        if (state.editableIds.length < 1) return fail("no_editable_textframe");
        if (state.visualCount < 1) return fail("no_visual_shell_source", { ownedTextFrameIds: state.editableIds });
        var allEditableIds = [];
        try { allEditableIds = _decoCollectTextFrameIds(grp, true, true); } catch (eAllEditable) {}
        if (allEditableIds.length !== state.editableIds.length) {
            return fail("editable_textframe_not_closed_bundle", {
                ownedTextFrameIds: state.editableIds,
                allEditableTextFrameIds: allEditableIds
            });
        }
        for (var ai = 0; ai < allEditableIds.length; ai++) {
            if (!_idArrayContains(state.editableIds, allEditableIds[ai])) {
                return fail("editable_textframe_not_in_closed_bundle", {
                    ownedTextFrameIds: state.editableIds,
                    allEditableTextFrameIds: allEditableIds
                });
            }
        }

        return accept({
            kind: "TEXTLESS_SHELL_WITH_TF",
            root: grp,
            ownedTextFrameIds: state.editableIds,
            visualSourceObjectIds: state.visualIds,
            reason: "atomic_ownership_root_text_hidden_shell"
        });
    }

    function _isGraphicOnlyOwnershipRootGroup(grp) {
        try { if (!grp || grp.constructor.name !== "Group") return false; } catch (e0) { return false; }
        if (isOnHiddenLayer(grp)) return false;
        if (_decoHasPlaced(grp)) return false;
        if (!isAllShapeChildren(grp)) return false;
        try {
            var nested = _decoAllPageItems(grp);
            if (!nested || nested.length < 2) return false;
            var hasVisibleVisual = false;
            for (var i = 0; i < nested.length; i++) {
                var item = nested[i];
                if (!item) continue;
                var cn = "";
                try { cn = item.constructor.name; } catch (eCn) {}
                if (cn === "Group") continue;
                if (_isDirectVisualShapeItem(item)) hasVisibleVisual = true;
            }
            return hasVisibleVisual;
        } catch (eNested) {
            return false;
        }
    }

    function _renderGraphicOnlyOwnershipRootGroup(grp, page) {
        try { atomicGraphicRootGroupIds[grp.id] = true; } catch (eMark) {}
        _decoRender(grp, page, null, {
            textOwner: "none",
            containsText: false,
            containsEditableText: false,
            placementAllowed: true,
            reason: "graphic_ownership_root"
        });
    }

    function _isDirectEditableTextFrame(item) {
        if (!item) return false;
        try { if (item.constructor.name !== "TextFrame") return false; } catch (e0) { return false; }
        try { if (classifyTextFrameCached(item) !== "editable") return false; } catch (eClass) { return false; }
        try { if (!_textFrameHasContent(item)) return false; } catch (eContent) { return false; }
        return true;
    }

    function _isAtomicOwnershipRootTextShellGroup(grp) {
        return _classifyTextlessShellCandidate(grp) !== null;
    }

    function _renderAtomicOwnershipRootTextShellGroups(grp, page) {
        var rendered = 0;
        try {
            var nested = _decoAllPageItems(grp);
            for (var i = 0; nested && i < nested.length; i++) {
                var child = nested[i];
                if (!child) continue;
                try { if (child.constructor.name !== "Group") continue; } catch (eCn) { continue; }
                try { if (child.id === grp.id || renderedIds[child.id] || decoChildIds[child.id]) continue; } catch (eSeen) {}
                if (!_isAtomicOwnershipRootTextShellGroup(child)) continue;
                _renderAtomicOwnershipRootTextShellGroup(child, page);
                rendered++;
            }
        } catch (e) {}
        return rendered;
    }

    function _renderAtomicOwnershipRootTextShellGroup(grp, page) {
        try { atomicTextShellRootGroupIds[grp.id] = true; } catch (eMark) {}
        _renderEditableVisualLabelShell(grp, page, "atomic_ownership_root_text_hidden_shell");
    }

    function _hasRenderedAtomicTextShellRootChild(grp) {
        try {
            var nested = _decoAllPageItems(grp);
            for (var i = 0; nested && i < nested.length; i++) {
                try {
                    if (nested[i].constructor.name === "Group" && atomicTextShellRootGroupIds[nested[i].id]) return true;
                } catch (eChild) {}
            }
        } catch (eNested) {}
        var direct = _directPageItemsOfGroup(grp);
        for (var j = 0; j < direct.length; j++) {
            try {
                if (direct[j].constructor.name === "Group" && atomicTextShellRootGroupIds[direct[j].id]) return true;
            } catch (e) {}
        }
        return false;
    }

    function _hasRenderedAtomicOwnershipRootChild(grp) {
        try {
            var nested = _decoAllPageItems(grp);
            for (var i = 0; nested && i < nested.length; i++) {
                try {
                    if (nested[i].constructor.name !== "Group") continue;
                    if (atomicTextShellRootGroupIds[nested[i].id]) return true;
                    if (atomicGraphicRootGroupIds[nested[i].id]) return true;
                } catch (eChild) {}
            }
        } catch (eNested) {}
        var direct = _directPageItemsOfGroup(grp);
        for (var j = 0; j < direct.length; j++) {
            try {
                if (direct[j].constructor.name !== "Group") continue;
                if (atomicTextShellRootGroupIds[direct[j].id]) return true;
                if (atomicGraphicRootGroupIds[direct[j].id]) return true;
            } catch (e) {}
        }
        return false;
    }

    function _isShortVisualLabelGroup(grp) {
        var stats = _textStatsOfGroup(grp);
        if (stats.count < 1 || stats.length < 1 || stats.length > 8 || stats.hasTable) return false;
        if (stats.titleLabelStyle) return false;
        if (!_hasVisualShapeChild(grp)) return false;
        var b = _boundsOfItem(grp);
        if (!b) return false;
        var h = Math.abs(b[2] - b[0]);
        var w = Math.abs(b[3] - b[1]);
        // 작은 배지/버튼/라벨만 InDesign PNG가 텍스트까지 소유한다.
        // 긴 제목 라벨이나 활동 지시문 컨테이너는 HWPX TF로 유지한다.
        return h <= 14 && w <= 55;
    }

    function _firstVisibleTextFrameOfGroup(grp) {
        try {
            var nested = _decoAllPageItems(grp);
            for (var i = 0; i < nested.length; i++) {
                var item = nested[i];
                if (!item || item.constructor.name !== "TextFrame") continue;
                if (visibleTextLengthOfTextFrame(item) <= 0) continue;
                return item;
            }
        } catch (e) {}
        return null;
    }

    function _groupHasVisibleTextFrame(grp) {
        try {
            if (!grp || grp.constructor.name !== "Group") return false;
            var nested = _decoAllPageItems(grp);
            for (var i = 0; i < nested.length; i++) {
                var item = nested[i];
                if (!item || item.constructor.name !== "TextFrame") continue;
                if (visibleTextLengthOfTextFrame(item) > 0) return true;
            }
        } catch (e) {}
        return false;
    }

    function _isEditableCompositeTextHiddenShellGroup(grp) {
        if (!_isShortVisualLabelGroup(grp)) return false;
        if (_isVisualMarkerLabelGroup(grp)) return false;

        var stats = _textStatsOfGroup(grp);
        if (stats.count !== 1 || stats.length < 2) return false;

        var tf = _firstVisibleTextFrameOfGroup(grp);
        if (!tf) return false;
        try { if (classifyTextFrameCached(tf) !== "editable") return false; } catch (eClass) {}

        var tfBounds = _boundsOfItem(tf);
        if (!tfBounds) return false;
        var tfArea = boundsArea(tfBounds);
        if (tfArea <= 0) return false;

        try {
            var nested = grp.allPageItems;
            for (var i = 0; i < nested.length; i++) {
                var item = nested[i];
                if (!item) continue;
                var cn = "";
                try { cn = item.constructor.name; } catch (eCn) {}
                if (cn === "TextFrame") continue;
                if (cn === "Group" && _groupHasVisibleTextFrame(item)) continue;
                if (cn !== "Group" && cn !== "Rectangle" && cn !== "Oval"
                        && cn !== "Polygon" && cn !== "GraphicLine") {
                    continue;
                }
                if (cn !== "Group" && !hasVisibleFill(item) && !hasVisibleStroke(item)) continue;
                var cb = _boundsOfItem(item);
                if (!cb) continue;
                var candArea = boundsArea(cb);
                if (candArea <= 0 || candArea < tfArea * 0.05) continue;
                var overlap = boundsOverlapArea(tfBounds, cb);
                var overlapRatio = overlap / tfArea;
                var cx = (cb[1] + cb[3]) / 2.0;
                var cy = (cb[0] + cb[2]) / 2.0;
                var detachedX = cx < tfBounds[1] || cx > tfBounds[3];
                var detachedY = cy < tfBounds[0] || cy > tfBounds[2];
                if (overlapRatio < 0.35 && (detachedX || detachedY)) {
                    return true;
                }
            }
        } catch (e) {}
        return false;
    }

    function _isLargeMixedParentGroup(grp) {
        var stats = _textStatsOfGroup(grp);
        if (stats.count < 4) return false;
        var b = _boundsOfItem(grp);
        if (!b) return false;
        var h = Math.abs(b[2] - b[0]);
        var w = Math.abs(b[3] - b[1]);
        return h >= 30 && w >= 80;
    }

    function _sortBackToFront(items) {
        var copy = [];
        for (var i = 0; i < items.length; i++) copy.push(items[i]);
        copy.sort(function(a, b) {
            var za = 0, zb = 0;
            try { za = getItemZOrder(a); } catch (eA) {}
            try { zb = getItemZOrder(b); } catch (eB) {}
            return zb - za;
        });
        return copy;
    }

    function _isEditableSiblingLabelTextFrame(tf) {
        try { if (!tf || tf.constructor.name !== "TextFrame") return false; } catch (e0) { return false; }
        try { if (classifyTextFrameCached(tf) !== "editable") return false; } catch (e1) { return false; }
        var text = _plainTextOfTextFrame(tf);
        if (!text || text.length < 1 || text.length > 12) return false;
        if (_isSimpleMarkerLabelText(text)) return false;
        try {
            var ps = tf.parentStory && tf.parentStory.paragraphs.length > 0
                    ? tf.parentStory.paragraphs[0].appliedParagraphStyle
                    : null;
            var styleName = ps ? String(ps.name || "") : "";
            if (styleName.indexOf("#표제목") === 0) return false;
        } catch (eStyle) {}
        return true;
    }

    function _isCompactStackedEditableLabelTextFrame(tf) {
        if (!_isEditableSiblingLabelTextFrame(tf)) return false;
        var raw = "";
        try { raw = String(tf.contents || ""); } catch (eContents) {}
        if (raw.indexOf("\r") < 0 && raw.indexOf("\n") < 0) return false;
        var text = _plainTextOfTextFrame(tf);
        if (!text || text.length < 2 || text.length > 8) return false;
        var b = _boundsOfItem(tf);
        if (!b) return false;
        var w = Math.abs(b[3] - b[1]);
        var h = Math.abs(b[2] - b[0]);
        return w <= 18.0 && h <= 24.0 && h >= w * 0.75;
    }

    function _isLabelBackdropCandidateItem(item) {
        try { if (!item || isOnHiddenLayer(item)) return false; } catch (e0) { return false; }
        try { if (renderedIds[item.id] || decoChildIds[item.id]) return false; } catch (eSeen) {}
        try {
            if (item.images && item.images.length > 0) return false;
            if (item.pdfs && item.pdfs.length > 0) return false;
            if (item.epss && item.epss.length > 0) return false;
        } catch (ePlaced) {}
        var cn = "";
        try { cn = item.constructor.name; } catch (eCn) {}
        if (cn === "TextFrame") {
            if (visibleTextLengthOfTextFrame(item) > 0) return false;
            return hasVisibleFill(item) || hasVisibleStroke(item);
        }
        if (cn === "Rectangle" || cn === "Oval" || cn === "Polygon") {
            return hasVisibleFill(item) || hasVisibleStroke(item);
        }
        return false;
    }

    function _isLabelBackdropResidualCandidateItem(item) {
        if (_isLabelBackdropCandidateItem(item)) return true;
        try { if (!item || isOnHiddenLayer(item)) return false; } catch (e0) { return false; }
        try { if (renderedIds[item.id] || decoChildIds[item.id]) return false; } catch (eSeen) {}
        try {
            if (item.images && item.images.length > 0) return false;
            if (item.pdfs && item.pdfs.length > 0) return false;
            if (item.epss && item.epss.length > 0) return false;
        } catch (ePlaced) {}
        var cn = "";
        try { cn = item.constructor.name; } catch (eCn) {}
        if (cn !== "Group") return false;

        var hasVisual = false;
        try {
            var nested = _decoAllPageItems(item);
            for (var i = 0; i < nested.length; i++) {
                var child = nested[i];
                if (!child) continue;
                var ccn = "";
                try { ccn = child.constructor.name; } catch (eChildCn) {}
                if (ccn === "TextFrame") {
                    if (visibleTextLengthOfTextFrame(child) > 0) return false;
                    if (hasVisibleFill(child) || hasVisibleStroke(child)) hasVisual = true;
                    continue;
                }
                if (ccn === "Rectangle" || ccn === "Oval" || ccn === "Polygon") {
                    if (hasVisibleFill(child) || hasVisibleStroke(child)) hasVisual = true;
                    continue;
                }
                if (ccn !== "Group") return false;
            }
        } catch (eNested) {
            return false;
        }
        return hasVisual;
    }

    function _candidateLooksLikeLabelShell(tfBounds, candidateBounds) {
        if (!tfBounds || !candidateBounds) return false;
        var tfArea = boundsArea(tfBounds);
        var candArea = boundsArea(candidateBounds);
        if (tfArea <= 0 || candArea <= 0) return false;
        var overlap = boundsOverlapArea(tfBounds, candidateBounds);
        if (overlap / tfArea < 0.55) return false;
        var tfW = Math.abs(tfBounds[3] - tfBounds[1]);
        var tfH = Math.abs(tfBounds[2] - tfBounds[0]);
        var candW = Math.abs(candidateBounds[3] - candidateBounds[1]);
        var candH = Math.abs(candidateBounds[2] - candidateBounds[0]);
        if (candW < tfW * 0.95 || candH < tfH * 0.95) return false;
        if (candW > Math.max(90, tfW * 5.0)) return false;
        if (candH > Math.max(35, tfH * 5.0)) return false;
        return true;
    }

    function _candidateLooksLikeLabelBackdropResidual(tfBounds, candidateBounds) {
        if (!tfBounds || !candidateBounds) return false;
        var tfArea = boundsArea(tfBounds);
        var candArea = boundsArea(candidateBounds);
        if (tfArea <= 0 || candArea <= 0) return false;

        var tfW = Math.abs(tfBounds[3] - tfBounds[1]);
        var tfH = Math.abs(tfBounds[2] - tfBounds[0]);
        var candW = Math.abs(candidateBounds[3] - candidateBounds[1]);
        var candH = Math.abs(candidateBounds[2] - candidateBounds[0]);
        if (candW > Math.max(90, tfW * 5.0)) return false;
        if (candH > Math.max(35, tfH * 5.0)) return false;

        var cx = (candidateBounds[1] + candidateBounds[3]) / 2.0;
        var cy = (candidateBounds[0] + candidateBounds[2]) / 2.0;
        var PAD = 4.0;
        if (cy < tfBounds[0] - PAD || cy > tfBounds[2] + PAD
                || cx < tfBounds[1] - PAD || cx > tfBounds[3] + PAD) {
            return false;
        }

        var overlap = boundsOverlapArea(tfBounds, candidateBounds);
        if (overlap > 0) return true;

        // Thin ruling/texture strokes can sit just outside the text glyph bounds
        // while visually belonging to the same button shell.
        if (candW >= tfW * 0.45 && candH <= Math.max(2.0, tfH * 0.35)) return true;
        if (candH >= tfH * 0.45 && candW <= Math.max(2.0, tfW * 0.35)) return true;
        return false;
    }

    function _claimLabelBackdropResidualItems(tfBounds, candidates, owned, claimed) {
        var ownedMap = {};
        for (var oi = 0; oi < owned.length; oi++) {
            try { ownedMap[owned[oi].id] = true; } catch (eOwned) {}
        }
        for (var ci = 0; ci < candidates.length; ci++) {
            var cand = candidates[ci];
            if (!cand || ownedMap[cand.id] || claimed[cand.id]) continue;
            var cb = _boundsOfItem(cand);
            if (!_candidateLooksLikeLabelBackdropResidual(tfBounds, cb)) continue;
            decoChildIds[cand.id] = true;
            renderedIds[cand.id] = true;
            labelBackdropClaimedIds[cand.id] = true;
            claimed[cand.id] = true;
        }
    }

    function _exportLabelBackdropGroup(tf, visualItems, page) {
        if (!tf || !visualItems || visualItems.length < 1 || !page) return false;
        if (visualItems.length < 2 && !_isCompactStackedEditableLabelTextFrame(tf)) return false;
        var tfId = tf.id;
        var fileName = "label_backdrop_group_" + tfId + ".png";
        var outFile = File(renderDir + "/" + fileName);
        var dups = [];
        var tempGroup = null;
        var ordered = _sortBackToFront(visualItems);
        try {
            for (var i = 0; i < ordered.length; i++) {
                var dup = ordered[i].duplicate();
                _clearTextFramesInRenderDuplicate(dup);
                dups.push(dup);
            }
            if (dups.length === 1) {
                tempGroup = dups[0];
            } else {
                try {
                    tempGroup = page.groups.add(dups);
                } catch (eGroupPage) {
                    try { tempGroup = doc.groups.add(dups); } catch (eGroupDoc) {}
                }
            }
            if (!tempGroup) return false;
            try { tempGroup.exportFile(ExportFormat.PNG_FORMAT, outFile); } catch (eExport) {}
            try { if (!outFile.exists || outFile.length < 512) return false; } catch (eSize) {}

            var bounds = null;
            try { bounds = arrCopy(tempGroup.visibleBounds); } catch (eBounds) {}
            if (!bounds) try { bounds = arrCopy(tempGroup.geometricBounds); } catch (eBounds2) {}
            if (bounds) _toPageRelativeBounds(bounds, page);
            var sourceIds = [];
            var seen = {};
            var z = 0;
            try { z = getItemZOrder(visualItems[0]); } catch (eZ0) {}
            for (var si = 0; si < visualItems.length; si++) {
                _appendSourceAndDescendantIds(visualItems[si], sourceIds, seen);
                var sid = visualItems[si].id;
                decoChildIds[sid] = true;
                renderedIds[sid] = true;
                labelBackdropClaimedIds[sid] = true;
                try {
                    var iz = getItemZOrder(visualItems[si]);
                    if (iz > z) z = iz;
                } catch (eZ) {}
            }
            sourceIds = _withoutSourceId(sourceIds, tfId);
            var labelBackdropCandidateMatch = _decoSourceSetCandidateMatch(sourceIds, page);
            if (!labelBackdropCandidateMatch) return false;
            labelBackdropClaimedTextFrameIds[tfId] = true;
            var entryId = -900000000 + Number(tfId);
            results.push(applyRenderOwnership({
                id: entryId,
                file: "rendered_frames/" + fileName,
                bounds: bounds,
                pageIndex: page.documentOffset,
                candidateId: labelBackdropCandidateMatch.candidateId,
                candidateMatchStrategy: labelBackdropCandidateMatch.strategy,
                zOrder: z
            }, null, {
                sourceObjectIds: sourceIds,
                visualOnlyChildIds: sourceIds,
                editableTextFrameIds: [tfId],
                textHiddenBeforeExport: true,
                hiddenTextFrameIds: [tfId],
                textOwner: "hwpx_tf",
                containsText: false,
                containsEditableText: false,
                placementAllowed: true,
                placementRole: "label_backdrop_group",
                reason: "label_backdrop_group"
            }));
            renderedIds[entryId] = true;
            return true;
        } catch (e) {
            return false;
        } finally {
            try {
                if (tempGroup) tempGroup.remove();
                else {
                    for (var di = 0; di < dups.length; di++) {
                        try { dups[di].remove(); } catch (eDup) {}
                    }
                }
            } catch (eCleanup) {}
        }
    }

    function _withoutSourceId(sourceIds, excludedId) {
        var out = [];
        var excluded = String(excludedId);
        for (var i = 0; sourceIds && i < sourceIds.length; i++) {
            if (String(sourceIds[i]) === excluded) continue;
            out.push(sourceIds[i]);
        }
        return out;
    }

    function _clearTextFramesInRenderDuplicate(item) {
        if (!item) return;
        try {
            if (item.constructor && item.constructor.name === "TextFrame") {
                item.contents = "";
            }
        } catch (eClearSelf) {}
        try {
            var descendants = _decoAllPageItems(item);
            if (!descendants) return;
            for (var i = 0; i < descendants.length; i++) {
                try {
                    if (descendants[i].constructor && descendants[i].constructor.name === "TextFrame") {
                        descendants[i].contents = "";
                    }
                } catch (eClearChild) {}
            }
        } catch (eDesc) {}
    }

    function _appendSourceAndDescendantIds(item, out, seen) {
        if (!item || !out || !seen) return;
        try {
            var id = item.id;
            if (!seen[id]) {
                out.push(id);
                seen[id] = true;
            }
        } catch (eSelf) {}
        try {
            var descendants = _decoAllPageItems(item);
            if (!descendants) return;
            for (var i = 0; i < descendants.length; i++) {
                try {
                    var childId = descendants[i].id;
                    if (!seen[childId]) {
                        out.push(childId);
                        seen[childId] = true;
                    }
                } catch (eChild) {}
            }
        } catch (eDesc) {}
    }

    function _renderSiblingLabelBackdropGroups(grp, page) {
        var rendered = 0;
        try {
            var nested = _decoAllPageItems(grp);
            var candidates = [];
            var compactCandidates = [];
            var residualCandidates = [];
            for (var i = 0; i < nested.length; i++) {
                if (_isLabelBackdropCandidateItem(nested[i])) candidates.push(nested[i]);
                if (_isLabelBackdropCandidateItemIgnoringClaims(nested[i])) compactCandidates.push(nested[i]);
                if (_isLabelBackdropResidualCandidateItem(nested[i])) residualCandidates.push(nested[i]);
            }
            var claimed = {};
            for (var ti = 0; ti < nested.length; ti++) {
                var tf = nested[ti];
                if (!_isEditableSiblingLabelTextFrame(tf)) continue;
                var tfBounds = _boundsOfItem(tf);
                if (!tfBounds) continue;
                var owned = [];
                if (_isCompactStackedEditableLabelTextFrame(tf)) {
                    var parentBackdrop = _parentBackdropOfCompactLabel(tf);
                    if (parentBackdrop && !claimed[parentBackdrop.id]) {
                        var pb = _boundsOfItem(parentBackdrop);
                        if (_candidateLooksLikeLabelShell(tfBounds, pb)) owned.push(parentBackdrop);
                    }
                    for (var cci = 0; cci < compactCandidates.length; cci++) {
                        var compactCand = compactCandidates[cci];
                        if (!compactCand || claimed[compactCand.id]) continue;
                        var ccb = _boundsOfItem(compactCand);
                        if (!_candidateLooksLikeLabelShell(tfBounds, ccb)) continue;
                        var alreadyOwned = false;
                        for (var oi0 = 0; oi0 < owned.length; oi0++) {
                            if (owned[oi0] && owned[oi0].id === compactCand.id) {
                                alreadyOwned = true;
                                break;
                            }
                        }
                        if (!alreadyOwned) owned.push(compactCand);
                    }
                }
                for (var ci = 0; ci < candidates.length; ci++) {
                    var cand = candidates[ci];
                    if (!cand || claimed[cand.id]) continue;
                    var cb = _boundsOfItem(cand);
                    if (!_candidateLooksLikeLabelShell(tfBounds, cb)) continue;
                    owned.push(cand);
                }
                if (owned.length < 2 && !_isCompactStackedEditableLabelTextFrame(tf)) continue;
                if (_exportLabelBackdropGroup(tf, owned, page)) {
                    for (var oi = 0; oi < owned.length; oi++) claimed[owned[oi].id] = true;
                    _claimLabelBackdropResidualItems(tfBounds, residualCandidates, owned, claimed);
                    rendered++;
                }
            }
        } catch (e) {}
        return rendered;
    }

    function _renderCompactStackedLabelBackdrops(grp, page) {
        var rendered = 0;
        try {
            if (!grp || !page) return 0;
            var nested = _decoAllPageItems(grp);
            var visualCandidates = [];
            for (var i = 0; i < nested.length; i++) {
                if (_isLabelBackdropCandidateItemIgnoringClaims(nested[i])) visualCandidates.push(nested[i]);
            }
            for (var ti = 0; ti < nested.length; ti++) {
                var tf = nested[ti];
                if (!_looksLikeCompactStackedLabelTextFrame(tf)) continue;
                try { if (labelBackdropClaimedTextFrameIds[tf.id]) continue; } catch (eClaimedTf) {}
                var tfBounds = _boundsOfItem(tf);
                if (!tfBounds) continue;
                var owned = [];
                var parentBackdrop = _parentBackdropOfCompactLabel(tf);
                if (parentBackdrop) {
                    var pb = _boundsOfItem(parentBackdrop);
                    if (_candidateLooksLikeLabelShell(tfBounds, pb)) owned.push(parentBackdrop);
                }
                for (var ci = 0; ci < visualCandidates.length; ci++) {
                    var cand = visualCandidates[ci];
                    if (!cand || (owned.length > 0 && cand.id === owned[0].id)) continue;
                    var cb = _boundsOfItem(cand);
                    if (_candidateLooksLikeLabelShell(tfBounds, cb)) {
                        owned.push(cand);
                        break;
                    }
                }
                if (owned.length < 1) continue;
                if (_exportLabelBackdropGroup(tf, owned, page)) {
                    for (var oi = 0; oi < owned.length; oi++) {
                        try { labelBackdropClaimedIds[owned[oi].id] = true; } catch (eOwned) {}
                    }
                    rendered++;
                }
            }
        } catch (e) {}
        return rendered;
    }

    function _looksLikeCompactStackedLabelTextFrame(tf) {
        try { if (!tf || tf.constructor.name !== "TextFrame") return false; } catch (e0) { return false; }
        try { if (visibleTextLengthOfTextFrame(tf) <= 0) return false; } catch (eVisible) { return false; }
        var raw = "";
        try { raw = String(tf.contents || ""); } catch (eContents) {}
        if (raw.indexOf("\r") < 0 && raw.indexOf("\n") < 0) return false;
        var text = _plainTextOfTextFrame(tf);
        if (!text || text.length < 2 || text.length > 8) return false;
        if (_isSimpleMarkerLabelText(text)) return false;
        var b = _boundsOfItem(tf);
        if (!b) return false;
        var w = Math.abs(b[3] - b[1]);
        var h = Math.abs(b[2] - b[0]);
        return w <= 18.0 && h <= 24.0 && h >= w * 0.75;
    }

    function _isLabelBackdropCandidateItemIgnoringClaims(item) {
        try { if (!item || isOnHiddenLayer(item)) return false; } catch (e0) { return false; }
        try {
            if (item.images && item.images.length > 0) return false;
            if (item.pdfs && item.pdfs.length > 0) return false;
            if (item.epss && item.epss.length > 0) return false;
        } catch (ePlaced) {}
        var cn = "";
        try { cn = item.constructor.name; } catch (eCn) {}
        if (cn === "TextFrame") {
            if (visibleTextLengthOfTextFrame(item) > 0) return false;
            return hasVisibleFill(item) || hasVisibleStroke(item);
        }
        if (cn === "Rectangle" || cn === "Oval" || cn === "Polygon") {
            return hasVisibleFill(item) || hasVisibleStroke(item);
        }
        return false;
    }

    function _parentBackdropOfCompactLabel(tf) {
        if (!tf) return null;
        var parent = null;
        try { parent = tf.parent; } catch (eParent) {}
        if (!parent || parent === tf) return null;
        try { if (isOnHiddenLayer(parent)) return null; } catch (eHidden) { return null; }
        try {
            if (parent.images && parent.images.length > 0) return null;
            if (parent.pdfs && parent.pdfs.length > 0) return null;
            if (parent.epss && parent.epss.length > 0) return null;
        } catch (ePlaced) {}
        var cn = "";
        try { cn = parent.constructor.name; } catch (eCn) {}
        if (cn !== "Rectangle" && cn !== "Oval" && cn !== "Polygon" && cn !== "TextFrame") return null;
        if (!hasVisibleFill(parent) && !hasVisibleStroke(parent)) return null;
        return parent;
    }

    function _isLabelBackdropOnlyGroup(grp) {
        var labelCount = 0;
        try {
            var nested = _decoAllPageItems(grp);
            for (var i = 0; i < nested.length; i++) {
                var item = nested[i];
                if (!item || item.constructor.name !== "TextFrame") continue;
                if (visibleTextLengthOfTextFrame(item) <= 0) continue;
                try { if (classifyTextFrameCached(item) !== "editable") continue; } catch (eClass) {}
                var text = _plainTextOfTextFrame(item);
                if (_isSimpleMarkerLabelText(text) || _isEditableSiblingLabelTextFrame(item)) {
                    labelCount++;
                    continue;
                }
                return false;
            }
        } catch (e) {
            return false;
        }
        return labelCount >= 2;
    }

    function _hideClaimedLabelBackdropItems(item) {
        var saved = [];
        try {
            var nested = _decoAllPageItems(item);
            for (var i = 0; i < nested.length; i++) {
                var child = nested[i];
                if (!child || !labelBackdropClaimedIds[child.id]) continue;
                try {
                    saved.push({ item: child, visible: child.visible });
                    child.visible = false;
                } catch (eVisible) {}
            }
        } catch (e) {}
        return saved;
    }

    function _restoreClaimedLabelBackdropItems(saved) {
        if (!saved) return;
        for (var i = 0; i < saved.length; i++) {
            try { saved[i].item.visible = saved[i].visible; } catch (e) {}
        }
    }

    function _hasClaimedLabelBackdropItems(item) {
        try {
            var nested = _decoAllPageItems(item);
            for (var i = 0; i < nested.length; i++) {
                if (nested[i] && labelBackdropClaimedIds[nested[i].id]) return true;
            }
        } catch (e) {}
        return false;
    }

    function _hasOnlyClaimedLabelTextFrames(item) {
        var count = 0;
        try {
            var nested = _decoAllPageItems(item);
            for (var i = 0; i < nested.length; i++) {
                var child = nested[i];
                if (!child || child.constructor.name !== "TextFrame") continue;
                if (visibleTextLengthOfTextFrame(child) <= 0) continue;
                try { if (classifyTextFrameCached(child) !== "editable") continue; } catch (eClass) {}
                count++;
                if (!labelBackdropClaimedTextFrameIds[child.id]) return false;
            }
        } catch (e) {
            return false;
        }
        return count > 0;
    }

    function _editableVisibleTextFrameCount(item) {
        var count = 0;
        try {
            var nested = _decoAllPageItems(item);
            for (var i = 0; i < nested.length; i++) {
                var child = nested[i];
                if (!child || child.constructor.name !== "TextFrame") continue;
                if (visibleTextLengthOfTextFrame(child) <= 0) continue;
                try { if (classifyTextFrameCached(child) !== "editable") continue; } catch (eClass) {}
                count++;
            }
        } catch (e) {}
        return count;
    }

    function _renderAtomicVisualClusters(grp, page) {
        var rendered = 0;
        try {
            var nested = _decoAllPageItems(grp);
            for (var i = 0; i < nested.length; i++) {
                var child = nested[i];
                if (!child || child.constructor.name !== "Group") continue;
                var childId = child.id;
                if (renderedIds[childId] || decoChildIds[childId]) continue;
                if (!_isShortVisualLabelGroup(child)) continue;
                var editableIds = [];
                try { editableIds = _decoCollectTextFrameIds(child, true, true); } catch (eIds) {}
                var completeDecision = _completePngMarkerDecision(child, editableIds);
                if (completeDecision.marker) {
                    _decoRender(child, page, null, {
                        textOwner: "indesign_png",
                        containsText: true,
                        containsEditableText: completeDecision.containsEditableText,
                        placementAllowed: true,
                        editableTextFrameIds: completeDecision.editableTextFrameIds,
                        reason: "visual_marker_label_indesign_png"
                    });
                } else {
                    var shellReason = _isEditableCompositeTextHiddenShellGroup(child)
                            ? "editable_composite_text_hidden_shell"
                            : "visual_label_text_hidden_shell";
                    _renderEditableVisualLabelShell(child, page, shellReason);
                }
                rendered++;
            }
        } catch (e) {}
        try { rendered += _renderSiblingLabelBackdropGroups(grp, page); } catch (eSibling) {}
        return rendered;
    }

    // Group의 parentPage 탐색 (3단계 폴백)
    function _resolveGroupPage(grp) {
        var page = null;
        try { page = grp.parentPage; } catch (e) {}
        if (!page) {
            try {
                var gb = grp.visibleBounds;
                var cy = (gb[0] + gb[2]) / 2, cx = (gb[1] + gb[3]) / 2;
                for (var pi = 0; pi < doc.pages.length; pi++) {
                    var pb = doc.pages[pi].bounds;
                    if (cy >= pb[0] && cy <= pb[2] && cx >= pb[1] && cx <= pb[3]) { page = doc.pages[pi]; break; }
                }
            } catch (e) {}
        }
        if (!page) {
            try {
                var items = grp.pageItems;
                for (var k = 0; k < items.length; k++) {
                    try { page = items[k].parentPage; } catch (e2) {}
                    if (page) break;
                }
            } catch (e) {}
        }
        return page;
    }

    // TF 하나에 특수효과(outline/shadow/skew/rotation)가 있는지 확인
    function _tfHasSpecialEffect(tf) {
        try { if (Math.abs(tf.rotationAngle) > 0.1) return true; } catch (e) {}
        try {
            var chars = tf.characters;
            var allSpecial = true;
            for (var i = 0; i < chars.length && i < 30; i++) {
                var ch = chars[i], c = ch.contents;
                if (c === " " || c === "\r" || c === "\n") continue;
                var special = false;
                try { var sc = ch.strokeColor; if (sc && sc.name !== "None") special = true; } catch (e2) {}
                if (!special) try { if (Math.abs(ch.skewAngle) > 0.1) special = true; } catch (e2) {}
                if (!special) try {
                    var ds = ch.dropShadowSettings;
                    if (ds && ds.mode && ds.mode.toString() !== "ShadowMode.NONE") special = true;
                } catch (e2) {}
                if (!special) { allSpecial = false; break; }
            }
            if (allSpecial) return true;
        } catch (e) {}
        try {
            var fds = tf.transparencySettings.dropShadowSettings;
            if (fds && fds.mode && fds.mode.toString() !== "ShadowMode.NONE") return true;
        } catch (e) {}
        return false;
    }

    // Group 분류: "pureShape" | "textComposite" | "hexGrid" | null
    function _classifyGroup(grp) {
        var nested;
        try { nested = _decoAllPageItems(grp); } catch (e) { return null; }

        // pureShape: 도형/그룹만 (자식 1개 이상)
        if (isAllShapeChildren(grp)) return "pureShape";

        if (nested.length < 2) return null;

        var nonRectCount = 0, hasTF = false;
        var containerShape = null, containerBounds = null, containerArea = 0;
        for (var i = 0; i < nested.length; i++) {
            var n = nested[i], cn = n.constructor.name;
            if (cn === "TextFrame") { hasTF = true; }
            if (cn === "Polygon" || cn === "Rectangle") {
                try { if (n.paths[0].pathPoints.length > 4) nonRectCount++; } catch (e) {}
            }
            if (cn === "Rectangle" || cn === "Oval") {
                try {
                    var sb = n.geometricBounds, area = (sb[2]-sb[0])*(sb[3]-sb[1]);
                    if (area > containerArea) { containerArea = area; containerShape = n; containerBounds = sb; }
                } catch (e) {}
            }
        }

        // hexGrid: 비사각형 Polygon 3개 이상, TF 없음
        if (!hasTF && nonRectCount >= 3) return "hexGrid";

        // textComposite: 컨테이너 도형 + 모든 TF에 특수효과
        if (hasTF && containerShape) {
            var TOL = 1.0, allInside = true;
            for (var j = 0; j < nested.length; j++) {
                if (nested[j] === containerShape) continue;
                try {
                    var bb = nested[j].geometricBounds;
                    if (bb[0] < containerBounds[0]-TOL || bb[1] < containerBounds[1]-TOL ||
                        bb[2] > containerBounds[2]+TOL || bb[3] > containerBounds[3]+TOL) { allInside = false; break; }
                } catch (e) {}
            }
            if (allInside) {
                var allSpecial = true;
                for (var k = 0; k < nested.length; k++) {
                    if (nested[k].constructor.name !== "TextFrame") continue;
                    try {
                        var content = nested[k].contents;
                        if (!content || content.replace(/[\s\r\n]/g, "").length === 0) continue;
                    } catch (e) { continue; }
                    if (!_tfHasSpecialEffect(nested[k])) { allSpecial = false; break; }
                }
                if (allSpecial) return "textComposite";

                // 컨테이너 배경색이 있고 TF가 모두 안쪽에 있으면 → textComposite (PNG 통 렌더)
                // mixedGroup 경로(TF 숨김 + 별도 Java 배치)는 TF가 badge-shift에 의해
                // 수평 이탈하는 버그가 있으므로, 배경 박스 레이블형 그룹은 여기서 차단.
                var containerHasFill = false;
                try {
                    var _cFill = containerShape.fillColor;
                    if (_cFill && _cFill.name !== "None" && _cFill.name !== "[None]") containerHasFill = true;
                } catch (e) {}
                if (containerHasFill) {
                    var noTableInTfs = true;
                    for (var kt = 0; kt < nested.length; kt++) {
                        if (nested[kt].constructor.name !== "TextFrame") continue;
                        try { if (nested[kt].parentStory.tables.length > 0) { noTableInTfs = false; break; } } catch (e) {}
                    }
                    if (noTableInTfs) return "textComposite";
                }
            }
        }

        // mixedGroup: 도형 + 일반 TF 혼합 (컨테이너 fill 없거나 TF가 컨테이너 밖으로 벗어남)
        // → TF 숨기고 도형만 PNG 렌더, TF 텍스트는 Java 파이프라인
        if (hasTF) {
            var hasShape = false;
            for (var m = 0; m < nested.length; m++) {
                var mn = nested[m].constructor.name;
                if (mn === "Rectangle" || mn === "Oval" || mn === "Polygon"
                        || mn === "GraphicLine" || mn === "Group") { hasShape = true; break; }
            }
            if (hasShape) return "mixedGroup";
        }

        return null;
    }

    /**
     * mixedGroup 내 "Color/Paper"(흰색) 획 도형을 검은색으로 임시 변환 후 개별 PNG 내보내기.
     * InDesign 투명배경 PNG 내보내기 시 흰색 획이 역마트 알고리즘으로 소실되는 문제 방지.
     * 내보낸 PNG는 검은 획으로 저장되며 Java에서 흰색으로 반전하여 배치.
     */
    function _exportPaperStrokeShapes(grp, grpPage) {
        try {
            var nested = _decoAllPageItems(grp);
            for (var _pi = 0; _pi < nested.length; _pi++) {
                var _sh = nested[_pi];
                var _cn;
                try { _cn = _sh.constructor.name; } catch(_e) { continue; }
                if (_cn !== "Polygon" && _cn !== "Oval" && _cn !== "GraphicLine"
                        && _cn !== "Rectangle" && _cn !== "TextFrame") continue;

                var _sName = "";
                try { _sName = _sh.strokeColor.name; } catch(_e) {}
                if (_sName !== "Paper") continue;

                var _sw = 0;
                try { _sw = _sh.strokeWeight; } catch(_e) {}
                if (_sw <= 0.01) continue;

                var _origStroke = null;
                try { _origStroke = _sh.strokeColor; } catch(_e) {}
                var _colored = false;
                try { _sh.strokeColor = doc.swatches.itemByName("Black"); _colored = true; } catch(_e) {}
                if (!_colored) {
                    try { _sh.strokeColor = doc.colors.itemByName("Black"); _colored = true; } catch(_e) {}
                }
                if (!_colored) continue;

                // TextFrame: 텍스트 컨텐츠를 숨겨 테두리(stroke)만 내보내기
                var _savedContentOp = null;
                if (_cn === "TextFrame") {
                    try {
                        _savedContentOp = _sh.contentTransparencySettings.blendingSettings.opacity;
                        _sh.contentTransparencySettings.blendingSettings.opacity = 0;
                    } catch(_e) { _savedContentOp = null; }
                }

                try {
                    var _wFile = "white_shape_" + _sh.id + ".png";
                    _sh.exportFile(ExportFormat.PNG_FORMAT, File(renderDir + "/" + _wFile));
                    var _wBounds = null;
                    try { _wBounds = arrCopy(_sh.visibleBounds); } catch(_e) {}
                    if (!_wBounds) try { _wBounds = arrCopy(_sh.geometricBounds); } catch(_e) {}
                    if (_wBounds) _toPageRelativeBounds(_wBounds, grpPage);
                    results.push(applyRenderOwnership({
                        id: _sh.id,
                        file: "rendered_frames/" + _wFile,
                        bounds: _wBounds,
                        pageIndex: grpPage.documentOffset,
                        whiteStroke: true,
                        zOrder: getItemZOrder(_sh)
                    }, _sh, {
                        textHiddenBeforeExport: _cn === "TextFrame",
                        textOwner: _cn === "TextFrame" ? "hwpx_tf" : "none",
                        reason: "paper_stroke_visual_only"
                    }));
                } catch(_e2) {}

                if (_origStroke) try { _sh.strokeColor = _origStroke; } catch(_e) {}
                // TextFrame: 컨텐츠 opacity 복원
                if (_savedContentOp !== null) {
                    try { _sh.contentTransparencySettings.blendingSettings.opacity = _savedContentOp; } catch(_e) {}
                }
            }
        } catch(_e) {}
    }

    // ── Pass 1: shape clipping containers ───────────────────────────────────
    // Non-Group clip carriers must be rendered before their child groups. If a
    // child group is exported first, InDesign's parent clip path is lost and the
    // child appears as an unclipped visual fragment.
    _perfPhaseStartedAt = _decoPerfNow();
    for (var i = 0; i < plannedItems.length; i++) {
        var planned = plannedItems[i];
        var item = planned.item;
        var cName = item.constructor.name;
        if (cName !== "Oval" && cName !== "Rectangle" && cName !== "Polygon") continue;
        if (isOnHiddenLayer(item)) continue;
        var isPlannedCompositeShell = _isPlannedNonGroupCompositeShell(planned);
        if (!isPlannedCompositeShell) continue;

        var domId = item.id;
        var p1Page = null;
        try { p1Page = item.parentPage; } catch (e) {}
        p1Page = _pageForPlannedItem(planned, p1Page);
        if (!p1Page) continue;
        if (_isRenderedOnPage(domId, p1Page) || renderedIds[domId]) continue;

        if (_decoHasPlaced(item)) continue;

        if (p1Page.documentOffset + 1 < startPage || p1Page.documentOffset + 1 > endPage) continue;

        try { _decoRender(item, p1Page, null); } catch (e) {}
    }
    _decoPerfEndPhase("pass1_clip_carriers", _perfPhaseStartedAt);

    // ── Pass 1.5: atomic ownership-root text shell group ─────────────────────
    // 같은 원본 시각 단위 안에 직접 도형과 editable TF가 함께 있으면, sibling
    // label backdrop으로 쪼개기 전에 ownership root 전체를 하나의 textless shell로 소유한다.
    _perfPhaseStartedAt = _decoPerfNow();
    for (var lgi = 0; lgi < plannedItems.length; lgi++) {
        var leafGrp = plannedItems[lgi].item;
        try { if (!leafGrp || leafGrp.constructor.name !== "Group") continue; } catch (eLeafCn) { continue; }
        var leafPage = _pageForPlannedItem(plannedItems[lgi], _resolveGroupPage(leafGrp));
        if (!leafPage) continue;
        if (_isRenderedOnPage(leafGrp.id, leafPage) || renderedIds[leafGrp.id] || decoChildIds[leafGrp.id]) continue;
        if (!_classifyTextlessShellCandidate(leafGrp, true)) continue;
        if (leafPage.documentOffset + 1 < startPage || leafPage.documentOffset + 1 > endPage) continue;
        try { _renderAtomicOwnershipRootTextShellGroup(leafGrp, leafPage); } catch (eLeafRender) {}
    }
    _decoPerfEndPhase("pass15_atomic_text_shell_root", _perfPhaseStartedAt);

    // ── Pass 1.6: graphic-only ownership-root group ──────────────────────────
    // leaf가 아니더라도 자식 도형/하위 그룹이 합쳐져 하나의 원본 시각 단위가 되는
    // graphic-only Group은 부모 mixed/container보다 먼저 추출한다.
    _perfPhaseStartedAt = _decoPerfNow();
    for (var gori = 0; gori < plannedItems.length; gori++) {
        var graphicRoot = plannedItems[gori].item;
        try { if (!graphicRoot || graphicRoot.constructor.name !== "Group") continue; } catch (eGraphicCn) { continue; }
        var graphicPage = _pageForPlannedItem(plannedItems[gori], _resolveGroupPage(graphicRoot));
        if (!graphicPage) continue;
        if (_isRenderedOnPage(graphicRoot.id, graphicPage) || renderedIds[graphicRoot.id] || decoChildIds[graphicRoot.id]) continue;
        if (!_isGraphicOnlyOwnershipRootGroup(graphicRoot)) continue;
        if (graphicPage.documentOffset + 1 < startPage || graphicPage.documentOffset + 1 > endPage) continue;
        try { _renderGraphicOnlyOwnershipRootGroup(graphicRoot, graphicPage); } catch (eGraphicRender) {}
    }
    _decoPerfEndPhase("pass16_graphic_root", _perfPhaseStartedAt);

    // ── Pass 2~4 통합: Group ─────────────────────────────────────────────────
    _perfPhaseStartedAt = _decoPerfNow();
    for (var gi = 0; gi < plannedItems.length; gi++) {
        var grp = plannedItems[gi].item;
        if (grp.constructor.name !== "Group") continue;
        if (isOnHiddenLayer(grp)) continue;

        var grpId = grp.id;
        var grpPage = _pageForPlannedItem(plannedItems[gi], _resolveGroupPage(grp));
        if (!grpPage) continue;
        if (_decoGroupSkip(grpId, grp, grpPage)) continue;

        var kind = _classifyGroup(grp);
        if (!kind) continue;

        // pureShape: 부모 승격 또는 형제 렌더 결정
        var renderSiblings = false;
        var origGi = gi;
        if (kind === "pureShape") {
            try {
                var par = grp.parent;
                if (par && par.constructor.name === "Group" && !_isRenderedOnPage(par.id, grpPage) && !renderedIds[par.id]) {
                    if (isAllShapeChildren(par)) {
                        grp = par; grpId = par.id;  // 부모로 승격
                    } else {
                        renderSiblings = true;  // 부모에 TF 있음 → 형제 개별 렌더
                    }
                }
            } catch (e) {}
            if (_isRenderedOnPage(grpId, grpPage) || renderedIds[grpId]) continue;
        }

        if (grpPage.documentOffset + 1 < startPage || grpPage.documentOffset + 1 > endPage) continue;

        var _savedTFsForCatch = null;
        var _savedCellBgsForCatch = null;
        var _savedLabelBackdropsForCatch = null;
        var _savedDecompForCatch = null;
        try {
            // hexGrid: 자신의 자식이 이전 Pass에서 개별 렌더된 경우 results에서 제거
            if (kind === "hexGrid") {

                var hexChildMap = {};
                try {
                    var hexNested = _decoAllPageItems(grp);
                    for (var hi = 0; hi < hexNested.length; hi++) hexChildMap[hexNested[hi].id] = true;
                } catch (e) {}
                var cleaned = [];
                for (var ri = 0; ri < results.length; ri++) {
                    if (!hexChildMap[results[ri].id]) cleaned.push(results[ri]);
                }
                results = cleaned;
            }

            var editableTfIdsForGroup = [];
            if (kind === "mixedGroup" || kind === "textComposite") {
                try { editableTfIdsForGroup = _decoCollectTextFrameIds(grp, true, true); } catch (e) {}
            }

            var _atomicTextShellRootGroup = (kind === "mixedGroup" || kind === "textComposite")
                    && _isAtomicOwnershipRootTextShellGroup(grp);
            var _hasAtomicTextShellRootChild = (kind === "mixedGroup" || kind === "textComposite")
                    && _hasRenderedAtomicTextShellRootChild(grp);
            var _hasAtomicOwnershipRootChild = (kind === "mixedGroup" || kind === "textComposite")
                    && _hasRenderedAtomicOwnershipRootChild(grp);
            var _nestedAtomicTextShellRootCount = 0;
            if (!_atomicTextShellRootGroup && !_hasAtomicTextShellRootChild && !_hasAtomicOwnershipRootChild
                    && (kind === "mixedGroup" || kind === "textComposite")) {
                _nestedAtomicTextShellRootCount = _renderAtomicOwnershipRootTextShellGroups(grp, grpPage);
            }

            var siblingLabelBackdropCount = 0;
            if (!_atomicTextShellRootGroup && !_hasAtomicTextShellRootChild && !_hasAtomicOwnershipRootChild
                    && _nestedAtomicTextShellRootCount === 0
                    && (kind === "mixedGroup" || kind === "textComposite")) {
                siblingLabelBackdropCount = _renderSiblingLabelBackdropGroups(grp, grpPage);
                siblingLabelBackdropCount += _renderCompactStackedLabelBackdrops(grp, grpPage);
            }

            if (_atomicTextShellRootGroup) {
                if (!_isRenderedOnPage(grp.id, grpPage) && !renderedIds[grp.id]) _renderAtomicOwnershipRootTextShellGroup(grp, grpPage);
            } else if ((_hasAtomicTextShellRootChild || _hasAtomicOwnershipRootChild || _nestedAtomicTextShellRootCount > 0)
                    && !_hasDirectVisualShapeChild(grp)) {
                _exportPaperStrokeShapes(grp, grpPage);
            } else if ((siblingLabelBackdropCount > 0
                        && (_isLabelBackdropOnlyGroup(grp) || _isShortVisualLabelGroup(grp)))
                    || (_hasClaimedLabelBackdropItems(grp)
                        && (_hasOnlyClaimedLabelTextFrames(grp) || _editableVisibleTextFrameCount(grp) === 0))) {
                // LABEL_BACKDROP_GROUP PNG가 라벨 배경을 소유한 경우 기존
                // visual_label_text_hidden_shell 경로를 다시 타면 같은 visual-only
                // 조각이 동일 위치에 중복 배치된다. 라벨 전용 parent/child group은
                // 새 label backdrop route만 남긴다.
                _exportPaperStrokeShapes(grp, grpPage);
            } else if ((kind === "mixedGroup" || kind === "textComposite") && _isShortVisualLabelGroup(grp)) {
                var _labelEditableIds = [];
                try { _labelEditableIds = _decoCollectTextFrameIds(grp, true, true); } catch (e) {}
                if (_isVisualMarkerLabelGroup(grp)) {
                    _decoRender(grp, grpPage, null, {
                        textOwner: "indesign_png",
                        containsText: true,
                        containsEditableText: true,
                        placementAllowed: true,
                        editableTextFrameIds: _labelEditableIds,
                        reason: "visual_marker_label_indesign_png"
                    });
                } else {
                    var _shellReason = _isEditableCompositeTextHiddenShellGroup(grp)
                            ? "editable_composite_text_hidden_shell"
                            : "visual_label_text_hidden_shell";
                    _renderEditableVisualLabelShell(grp, grpPage, _shellReason);
                }
            } else if (kind === "mixedGroup" && _isLargeMixedParentGroup(grp)) {
                _renderAtomicVisualClusters(grp, grpPage);
                // 큰 부모 mixedGroup은 통 이미지로 만들지 않는다. 자식 atomic cluster와
                // 이후 vector/shape 렌더가 각 시각 단위를 소유한다.
                _exportPaperStrokeShapes(grp, grpPage);
            } else if (kind === "mixedGroup" || (kind === "textComposite" && editableTfIdsForGroup.length > 0)) {
                // TF 텍스트만 숨기고 도형/anchored visual은 부모 PNG에 포함한다.
                // 말풍선 brace, 답안 밑줄처럼 TF story에 앵커되어도 시각 껍데기의 일부인 객체는
                // 부모 PNG의 visualOnlyChildIds로 소유시켜 Java inline 재배치를 막는다.
                var savedTFs = hideTextFrames(grp);
                var savedCellBgs = hideRepeatedCellBackgroundCandidates(grp);
                var savedLabelBackdrops = _hideClaimedLabelBackdropItems(grp);
                // 사이드박스 분해: 대형 솔리드 배경 + 인라인 불릿만 PNG에서 분리(네이티브 fill/인라인).
                // 제목 둥근사각형은 그대로 PNG에 굽고(추출 이미지) 제목 텍스트가 그 위에 결합되게 둔다.
                // (단일 대형 배경이 있을 때만 발동 → 배지/개념도/라벨 배경은 미발동)
                var _bgRect = (kind === "mixedGroup") ? _findLargeSolidFillBackgroundChild(grp) : null;
                var _inlineVisItems = _bgRect ? _findInlineAnchoredVisualItems(grp) : [];
                var _releasedIds = [];
                if (_bgRect) {
                    _releasedIds.push(_bgRect.id);
                    for (var _ii = 0; _ii < _inlineVisItems.length; _ii++) _releasedIds.push(_inlineVisItems[_ii].id);
                }
                var _decompItems = [];
                if (_bgRect) _decompItems.push(_bgRect);
                _decompItems = _decompItems.concat(_inlineVisItems);
                var savedDecomp = _hideItemsForExport(_decompItems);
                _savedTFsForCatch = savedTFs;
                _savedCellBgsForCatch = savedCellBgs;
                _savedLabelBackdropsForCatch = savedLabelBackdrops;
                _savedDecompForCatch = savedDecomp;
                _decoRender(grp, grpPage, null, {
                    textHiddenBeforeExport: true,
                    textOwner: "hwpx_tf",
                    editableTextFrameIds: editableTfIdsForGroup.length > 0 ? editableTfIdsForGroup : undefined,
                    tfInlineVisualIds: _releasedIds.length > 0 ? _releasedIds : undefined,
                    nativeFillChildIds: _bgRect ? [_bgRect.id] : undefined,
                    reason: kind === "textComposite" ? "text_composite_editable_text_hidden" : "mixed_group_text_hidden"
                });
                _restoreItemsForExport(savedDecomp);
                _restoreClaimedLabelBackdropItems(savedLabelBackdrops);
                restoreRepeatedCellBackgroundCandidates(savedCellBgs);
                restoreTextFrames(savedTFs);
                _savedDecompForCatch = null;
                _savedLabelBackdropsForCatch = null;
                _savedCellBgsForCatch = null;
                _savedTFsForCatch = null;
                // Color/Paper (흰색) 획 도형은 투명배경 PNG에서 소실 → 검은색으로 임시 변환 후 개별 추출
                _exportPaperStrokeShapes(grp, grpPage);
            } else {
                _decoRender(grp, grpPage, null, kind === "textComposite" ? {
                    textOwner: "indesign_png",
                    containsText: true,
                    placementAllowed: true,
                    reason: "text_composite_indesign_png"
                } : {
                    textOwner: "none",
                    reason: "pure_decoration_group"
                });
            }
        } catch (e) {
            // outer catch: 예외가 발생해도 숨겼던 TF/도형 복원
            try { if (_savedDecompForCatch && _savedDecompForCatch.length > 0) _restoreItemsForExport(_savedDecompForCatch); } catch (eD) {}
            try { if (_savedLabelBackdropsForCatch && _savedLabelBackdropsForCatch.length > 0) _restoreClaimedLabelBackdropItems(_savedLabelBackdropsForCatch); } catch (e0) {}
            try { if (_savedCellBgsForCatch && _savedCellBgsForCatch.length > 0) restoreRepeatedCellBackgroundCandidates(_savedCellBgsForCatch); } catch (e1) {}
            try { if (_savedTFsForCatch && _savedTFsForCatch.length > 0) restoreTextFrames(_savedTFsForCatch); } catch (e2) {}
        }

        // pureShape + renderSiblings: 부모 그룹의 형제 도형을 개별 렌더
        if (renderSiblings) {
            try {
                var origGrp = plannedItems[origGi].item;
                var parRef = origGrp.parent;
                var allKids = _decoAllPageItems(parRef);
                for (var fi = 0; fi < allKids.length; fi++) {
                    try {
                        var sib = allKids[fi];
                        if (sib.parent.id !== parRef.id) continue;
                        var sibId = sib.id;
                        if (sibId === grpId || _isRenderedOnPage(sibId, grpPage) || renderedIds[sibId] || decoChildIds[sibId]) continue;
                        var sibCn = sib.constructor.name;
                        if (sibCn !== "GraphicLine" && sibCn !== "Rectangle" &&
                                sibCn !== "Polygon" && sibCn !== "Oval" && sibCn !== "Group") continue;
                        if (sibCn === "Group" && !isAllShapeChildren(sib)) continue;
                        _decoRender(sib, grpPage, null);
                    } catch (e2) {}
                }
            } catch (e) {}
        }
    }
    _decoPerfEndPhase("pass2_4_group_loop", _perfPhaseStartedAt);

    _decoPerfWrite();
    return { frames: results, childIds: decoChildIds, textlessShellDiagnostics: textlessShellDiagnostics };
}

/**
 * 아이템의 모든 자식이 도형(Rect/Polygon/Oval/GraphicLine) 또는 Group인지 확인.
 * TextFrame, Image 등 비도형 자식이 하나라도 있으면 false.
 */
function isAllShapeChildren(item) {
    try {
        var nested = _decoAllPageItems(item);
        for (var i = 0; i < nested.length; i++) {
            var cn = nested[i].constructor.name;
            if (cn !== "Rectangle" && cn !== "Polygon"
                && cn !== "Oval" && cn !== "GraphicLine"
                && cn !== "Group") {
                return false;
            }
        }
        return nested.length > 0;
    } catch (e) {
        return false;
    }
}
