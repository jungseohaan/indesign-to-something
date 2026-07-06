/**
 * Extraction candidate and plan builder.
 *
 * This module assembles Stage 1 legacy candidates and extraction-plan.json.
 * It must not render PNGs or reinterpret placement during execution.
 */

// =============================================================================
// SECTION 3.5: EXTRACTION PLANNING
// E0/E1: DOM metadata scan -> extraction-plan.json.
// The plan describes candidate production only. It is not HWPX ownership.
// =============================================================================



function _hasPlacedVisual(item) {
    try { if (item.images && item.images.length > 0) return true; } catch (eImage) {}
    try { if (item.pdfs && item.pdfs.length > 0) return true; } catch (ePdf) {}
    try { if (item.epss && item.epss.length > 0) return true; } catch (eEps) {}
    return false;
}

function _hasCandidateVectorPaint(item) {
    try { if (hasVisibleFill(item)) return true; } catch (eFill) {}
    try { if (hasVisibleStroke(item)) return true; } catch (eStroke) {}
    return false;
}

function _appendMasterCompositeExtractionCandidates(doc, ctx, candidates, seen, planCache) {
    var masterCompositeClusterCache = {};

    function boundsIntersectForPlan(a, b, pad) {
        pad = pad || 0;
        try {
            return a[2] >= b[0] - pad && a[0] <= b[2] + pad &&
                   a[3] >= b[1] - pad && a[1] <= b[3] + pad;
        } catch (e) {}
        return false;
    }

    function isTopLevelMasterItemForPlan(item) {
        try {
            if (!item) return false;
            if (isOnHiddenLayer(item)) return false;
            try { if (item.visible === false) return false; } catch (eVis) {}
            try { if (item.nonprinting) return false; } catch (eNp) {}
            var p = item.parent;
            while (p) {
                var pn = "";
                try { pn = p.constructor.name; } catch (eName) { break; }
                if (pn === "Group") return false;
                if (pn === "Page" || pn === "Spread" || pn === "MasterSpread" || pn === "Document") return true;
                try { p = p.parent; } catch (eParent) { break; }
            }
        } catch (e) {}
        return false;
    }

    function clusterPlanMasterEntries(entries) {
        var clusters = [];
        var used = [];
        var pad = 2.0;
        for (var i = 0; i < entries.length; i++) {
            if (used[i]) continue;
            var cluster = [];
            var queue = [i];
            used[i] = true;
            while (queue.length > 0) {
                var idx = queue.shift();
                cluster.push(entries[idx]);
                for (var j = 0; j < entries.length; j++) {
                    if (used[j]) continue;
                    var touches = false;
                    for (var k = 0; k < cluster.length; k++) {
                        if (boundsIntersectForPlan(cluster[k].bounds, entries[j].bounds, pad)) {
                            touches = true;
                            break;
                        }
                    }
                    if (touches) {
                        used[j] = true;
                        queue.push(j);
                    }
                }
            }
            clusters.push(cluster);
        }
        return clusters;
    }

    function unionPlanEntryBounds(entries) {
        var union = null;
        for (var i = 0; i < entries.length; i++) {
            try {
                var b = entries[i].bounds;
                if (!b || b.length < 4) continue;
                if (!union) {
                    union = [b[0], b[1], b[2], b[3]];
                } else {
                    union[0] = Math.min(union[0], b[0]);
                    union[1] = Math.min(union[1], b[1]);
                    union[2] = Math.max(union[2], b[2]);
                    union[3] = Math.max(union[3], b[3]);
                }
            } catch (e) {}
        }
        return union;
    }

    function collectPlanEntrySourceIds(entries) {
        var sourceIds = [], seenIds = {};
        for (var i = 0; i < entries.length; i++) {
            try {
                // Master composites are page-applied source clusters, not page-local
                // source-index bundles. Use the full InDesign source tree here so
                // Stage 1 candidate ownership and the export executor share exactly
                // the same slot source set.
                var ids = _collectSourceObjectIds(entries[i].item);
                for (var si = 0; si < ids.length; si++) _pushUniqueId(sourceIds, seenIds, ids[si]);
            } catch (e) {}
        }
        sourceIds.sort(function(a, b) { return Number(a) - Number(b); });
        return sourceIds;
    }

    try {
        for (var pp = ctx.startPage - 1; pp < ctx.endPage && pp < doc.pages.length; pp++) {
            var page = doc.pages[pp];
            var master = null;
            try { master = page.appliedMaster; } catch (eMaster) {}
            if (!master) continue;
            var side = 0;
            try {
                var spreadPages = page.parent.pages;
                for (var spi = 0; spi < spreadPages.length; spi++) {
                    if (spreadPages[spi].id === page.id) { side = spi; break; }
                }
            } catch (eSide) {}
            var masterPageBounds = null;
            try { masterPageBounds = master.pages[side].bounds; } catch (eBounds) {}
            if (!masterPageBounds) continue;
            var cacheKey = String(master.id) + "|" + String(side);
            var cachedClusters = masterCompositeClusterCache[cacheKey];
            if (!cachedClusters) {
                var entries = [];
                var items = [];
                try { items = master.allPageItems; } catch (eItems) {}
                for (var ii = 0; ii < items.length; ii++) {
                    try {
                        var item = items[ii];
                        if (_itemKind(item) === "TextFrame") continue;
                        if (!isTopLevelMasterItemForPlan(item)) continue;
                        var b = _itemBounds(item);
                        if (!b || !boundsIntersectForPlan(b, masterPageBounds, 5)) continue;
                        entries.push({ item: item, bounds: b });
                    } catch (eItem) {}
                }
                cachedClusters = [];
                if (entries.length > 0) {
                    var clusters = clusterPlanMasterEntries(entries);
                    for (var ci = 0; ci < clusters.length; ci++) {
                        var sourceIds = collectPlanEntrySourceIds(clusters[ci]);
                        if (sourceIds.length === 0) continue;
                        cachedClusters.push({
                            sourceIds: sourceIds,
                            bounds: unionPlanEntryBounds(clusters[ci]) || masterPageBounds,
                            clusterIndex: ci
                        });
                    }
                }
                masterCompositeClusterCache[cacheKey] = cachedClusters;
            }
            for (var ci2 = 0; ci2 < cachedClusters.length; ci2++) {
                var cached = cachedClusters[ci2];
                if (!cached || !cached.sourceIds || cached.sourceIds.length === 0) continue;
                _pushExtractionCandidate(candidates, seen, "pass.master_page_graphics", null, {
                    sourceId: null,
                    sourceObjectIds: cached.sourceIds,
                    pageIndex: pp,
                    kind: "MasterPageComposite",
                    unit: "MASTER_ITEM",
                    mode: "TEXTLESS_CANDIDATE",
                    candidatePurpose: "MASTER_CANDIDATE",
                    bounds: cached.bounds,
                    composite: true,
                    compositeRole: "applied_master_page_cluster",
                    suffix: "master_" + master.id + "_side_" + side + "_cluster_" + cached.clusterIndex
                });
            }
        }
    } catch (eMasterCompositeCandidates) {}
}

function _appendInlineObjectExtractionCandidates(doc, ctx, allItems, candidates, seen, planCache) {
    var inlineScanFrames = [];
    var inlineScanFrameIds = {};
    var inlineItemById = {};
    try {
        for (var mapIdx = 0; mapIdx < allItems.length; mapIdx++) {
            try {
                if (allItems[mapIdx] && allItems[mapIdx].id !== undefined && allItems[mapIdx].id !== null) {
                    inlineItemById[String(allItems[mapIdx].id)] = allItems[mapIdx];
                }
            } catch (eMapItem) {}
        }
    } catch (eInlineMap) {}
    function isOffCanvasInlineStoryCarrier(tf) {
        try {
            if (!tf || tf.constructor.name !== "TextFrame") return false;
        } catch (eType) { return false; }
        try {
            var parentPage = tf.parentPage;
            if (parentPage) return false;
        } catch (ePage) {}
        try {
            if (isInlineItem(tf)) return false;
        } catch (eInlineTf) {}
        try {
            var cur = tf.parent;
            var hop = 0;
            while (cur && hop < 8) {
                var name = "";
                try { name = cur.constructor.name; } catch (eName) {}
                if (name === "Cell") return false;
                if (name === "Spread") break;
                if (name === "MasterSpread") return false;
                try { cur = cur.parent; } catch (eParent) { break; }
                hop++;
            }
            if (!cur || cur.constructor.name !== "Spread") return false;
        } catch (eParentChain) {
            return false;
        }
        try {
            var text = String(tf.contents || "").replace(/[\s﻿\r\n\u0016\u0018\uFFFC]/g, "");
            if (text.length === 0) return false;
        } catch (eText) {
            return false;
        }
        try {
            var storyItems = tf.parentStory.allPageItems;
            for (var si = 0; si < storyItems.length; si++) {
                if (storyItems[si] && storyItems[si].id !== tf.id) return true;
            }
        } catch (eStoryItems) {}
        return false;
    }
    try {
        for (var ti = 0; ti < allItems.length; ti++) {
            var tf = allItems[ti];
            try {
                if (classifyTextFrameCached(tf) !== "editable"
                        && !isOffCanvasInlineStoryCarrier(tf)) continue;
                inlineScanFrames.push(tf);
                inlineScanFrameIds[tf.id] = true;
            } catch (eTf) {}
        }
    } catch (eAllTf) {}
    try {
        var masterSpreads = doc.masterSpreads.everyItem().getElements();
        for (var msi = 0; msi < masterSpreads.length; msi++) {
            var masterItems = [];
            try { masterItems = masterSpreads[msi].allPageItems; } catch (eMsItems) {}
            for (var mii = 0; mii < masterItems.length; mii++) {
                var masterTf = masterItems[mii];
                try {
                    if (masterTf.constructor.name !== "TextFrame") continue;
                    if (classifyTextFrameCached(masterTf) !== "editable") continue;
                    if (inlineScanFrameIds[masterTf.id]) continue;
                    inlineScanFrames.push(masterTf);
                    inlineScanFrameIds[masterTf.id] = true;
                } catch (eMasterTf) {}
            }
        }
    } catch (eMasterScan) {}

    var processedStoryIds = {};
    for (var ei = 0; ei < inlineScanFrames.length; ei++) {
        var eTf = inlineScanFrames[ei];
        try {
            var story = eTf.parentStory;
            var storyKey = story.id.toString();
            if (processedStoryIds[storyKey]) continue;
            processedStoryIds[storyKey] = true;
            var pageIndex = -1;
            try {
                var tfPage = eTf.parentPage;
                if (tfPage) pageIndex = tfPage.documentOffset;
            } catch (eTfPage) {}
            if (pageIndex < 0) pageIndex = _pageIndexBySpreadBounds(doc, eTf, ctx);
            var storyItems = story.allPageItems;
            for (var si = 0; si < storyItems.length; si++) {
                var inItem = storyItems[si];
                try {
	                    if (inItem.constructor.name === "TextFrame") continue;
	                    if (!isInlineItem(inItem)) continue;
	                    var inItemInfo = planCache ? planCache.itemInfo(inItem) : null;
	                    if (inItemInfo && !_isInlineFlowItemBySourceInfo(inItemInfo)) continue;
	                    var inlinePageIndex = _pageIndexOfItem(doc, inItem);
                    if (inlinePageIndex === null || inlinePageIndex === undefined || inlinePageIndex < 0) {
                        inlinePageIndex = pageIndex;
                    }
                    if (inlinePageIndex === null || inlinePageIndex === undefined || inlinePageIndex < 0) {
                        inlinePageIndex = _pageIndexBySpreadBounds(doc, inItem, ctx);
                    }
                    if (inlinePageIndex >= 0 && !_candidatePageInRange(inlinePageIndex, ctx)) continue;
                    var inParent = inItem.parent;
                    if (inParent && inParent.constructor.name === "Group" && isInlineItem(inParent)) continue;
                    if (inParent && inParent.constructor.name === "Rectangle" && isInlineItem(inParent)) continue;
                    var inlineSourceIds = null;
                    try {
                        inlineSourceIds = planCache ? planCache.sourceObjectIds(inItem) : _collectSourceObjectIds(inItem);
                    } catch (eInlineSourceIds) {}
                    var inlineEditableTextFrameIds = [];
                    try {
                        inlineEditableTextFrameIds = planCache ? planCache.textFrameIds(inItem, true, true) : _collectTextFrameIds(inItem, true, true);
                    } catch (eInlineEditableIds) {}
                    var inlineCompleteMarkerOwnsText = _inlineCompleteMarkerDecisionForOwnership(
                            inItem, inlineEditableTextFrameIds, inlineItemById);
                    var inlineRequiresTextHidden = inlineEditableTextFrameIds.length > 0 && !inlineCompleteMarkerOwnsText;
                    _pushExtractionCandidate(candidates, seen, "pass.inline_objects", inItem, {
                        sourceObjectIds: inlineSourceIds,
                        pageIndex: inlinePageIndex,
                        unit: "INLINE_OBJECT",
                        mode: "TEXTLESS_CANDIDATE",
                        candidatePurpose: "INLINE_CANDIDATE",
                        editableTextFrameIds: inlineEditableTextFrameIds,
                        hiddenTextFrameIds: inlineRequiresTextHidden ? inlineEditableTextFrameIds : [],
                        requiresTextHidden: inlineRequiresTextHidden,
                        textOwner: inlineCompleteMarkerOwnsText ? "indesign_png" : (inlineRequiresTextHidden ? "hwpx_tf" : "none"),
                        containsEditableText: inlineCompleteMarkerOwnsText,
                        completePngTextAllowed: inlineCompleteMarkerOwnsText
                    });
                } catch (eInlineCandidate) {}
            }
        } catch (eStory) {}
    }

    try {
        for (var ni = 0; ni < allItems.length; ni++) {
            var nativeInlineShell = allItems[ni];
            try {
                var nativeKind = nativeInlineShell.constructor.name;
                if (nativeKind !== "Rectangle" && nativeKind !== "Oval" && nativeKind !== "Polygon") continue;
                var nativeInlineInfo = planCache ? planCache.itemInfo(nativeInlineShell) : null;
                var nativeInlineParentKind = nativeInlineInfo ? String(nativeInlineInfo.parentKind || "") : "";
	                if (!isInlineItem(nativeInlineShell) && !_isInlineFlowItemBySourceInfo(nativeInlineInfo)) continue;
	                if (nativeInlineInfo && !_isInlineFlowItemBySourceInfo(nativeInlineInfo)) continue;
                var nativeParent = nativeInlineShell.parent;
                if (nativeParent && nativeParent.constructor.name === "Group" && isInlineItem(nativeParent)) continue;
                if (nativeParent && nativeParent.constructor.name === "Rectangle" && isInlineItem(nativeParent)) continue;

                var nativeInlinePageIndex = _pageIndexOfItem(doc, nativeInlineShell);
                if (nativeInlinePageIndex === null || nativeInlinePageIndex === undefined || nativeInlinePageIndex < 0) {
                    nativeInlinePageIndex = _pageIndexBySpreadBounds(doc, nativeInlineShell, ctx);
                }
                if (nativeInlineInfo && nativeInlineInfo.pageIndex !== null
                        && nativeInlineInfo.pageIndex !== undefined
                        && nativeInlineInfo.pageIndex >= 0) {
                    nativeInlinePageIndex = nativeInlineInfo.pageIndex;
                }
                if (nativeInlinePageIndex >= 0
                        && !_candidatePageInRange(nativeInlinePageIndex, ctx)
                        && !(nativeInlinePageIndex < ctx.rangePageCount)) {
                    continue;
                }

                var nativeInlineEditableTextFrameIds = [];
                try {
                    nativeInlineEditableTextFrameIds = planCache
                            ? planCache.textFrameIds(nativeInlineShell, true, true)
                            : _collectTextFrameIds(nativeInlineShell, true, true);
                } catch (eNativeInlineEditable) {}
                if (!nativeInlineEditableTextFrameIds || nativeInlineEditableTextFrameIds.length === 0) continue;

                var nativeInlineSourceIds = null;
                try {
                    nativeInlineSourceIds = planCache
                            ? planCache.sourceObjectIds(nativeInlineShell)
                            : _collectSourceObjectIds(nativeInlineShell);
                } catch (eNativeInlineSourceIds) {}
                var nativeInlineCompleteMarkerOwnsText =
                        _inlineCompleteMarkerDecisionForOwnership(
                                nativeInlineShell, nativeInlineEditableTextFrameIds, inlineItemById);
                var nativeInlineRequiresTextHidden =
                        nativeInlineEditableTextFrameIds.length > 0 && !nativeInlineCompleteMarkerOwnsText;
                _pushExtractionCandidate(candidates, seen, "pass.inline_objects", nativeInlineShell, {
                    sourceObjectIds: nativeInlineSourceIds,
                    pageIndex: nativeInlinePageIndex,
                    unit: "INLINE_OBJECT",
                    mode: "TEXTLESS_CANDIDATE",
                    candidatePurpose: "INLINE_CANDIDATE",
                    editableTextFrameIds: nativeInlineEditableTextFrameIds,
                    hiddenTextFrameIds: nativeInlineRequiresTextHidden ? nativeInlineEditableTextFrameIds : [],
                    requiresTextHidden: nativeInlineRequiresTextHidden,
                    textOwner: nativeInlineCompleteMarkerOwnsText
                            ? "indesign_png"
                            : (nativeInlineRequiresTextHidden ? "hwpx_tf" : "none"),
                    containsEditableText: nativeInlineCompleteMarkerOwnsText,
                    completePngTextAllowed: nativeInlineCompleteMarkerOwnsText
                });
            } catch (eNativeInlineCandidate) {}
        }
    } catch (eNativeInlineScan) {}
}

function _includeOwnedInlineVisualsInTextlessShellCandidates(candidates, allItems, planCache, sourceItems) {
    if (!candidates || candidates.length === 0) return candidates || [];
    var inlineVisualDetector = _createInlineVisualSourceDetector(allItems, planCache);
    var sourceIndexes = _buildSourceItemIndexes(sourceItems || []);
    var sourceInfoById = sourceIndexes.sourceInfoById || {};
    var childIdsByParentId = sourceIndexes.childIdsByParentId || {};

    function mergeIds(base, extra) {
        var out = [];
        var seen = {};
        for (var bi = 0; base && bi < base.length; bi++) _pushUniqueId(out, seen, base[bi]);
        for (var ei = 0; extra && ei < extra.length; ei++) _pushUniqueId(out, seen, extra[ei]);
        return _sortedNumericIds(out);
    }

    function removeIds(base, removed) {
        return _removeSourceIds(base || [], removed || []);
    }

    function shouldInspectCandidate(candidate) {
        if (!candidate || candidate.passId !== "pass.decoration_groups") return false;
        if (candidate.candidatePurpose !== "SHELL_CANDIDATE") return false;
        if (candidate.textOwner !== "hwpx_tf") return false;
        if (candidate.slotRole !== "shell_slot_only") return false;
        if (!candidate.hiddenTextFrameIds || candidate.hiddenTextFrameIds.length === 0) return false;
        return true;
    }

    function filterTextFrameIds(ids) {
        var out = [];
        var seen = {};
        for (var i = 0; ids && i < ids.length; i++) {
            var item = planCache && planCache.domItem ? planCache.domItem(ids[i]) : null;
            try {
                if (item && item.constructor.name === "TextFrame") {
                    _pushUniqueId(out, seen, ids[i]);
                }
            } catch (e) {}
        }
        return _sortedNumericIds(out);
    }

    function assertExecutableShellCandidate(candidate) {
        if (!candidate || _candidateHasExecutableShellMaterial(candidate, sourceInfoById, childIdsByParentId)) return;
        throw new Error("Non-executable shell candidate after inline visual ownership merge"
                + " candidateId=" + String(candidate.candidateId || "")
                + " passId=" + String(candidate.passId || "")
                + " pageIndex=" + String(candidate.pageIndex)
                + " sourceObjectIds=" + String((candidate.sourceObjectIds || []).join(",")));
    }

    for (var ci = 0; ci < candidates.length; ci++) {
        var candidate = candidates[ci];
        if (!shouldInspectCandidate(candidate)) continue;
        var targetId = candidate.exportTargetObjectId !== undefined && candidate.exportTargetObjectId !== null
                ? candidate.exportTargetObjectId
                : candidate.primarySourceObjectId;
        var targetItem = planCache && planCache.domItem ? planCache.domItem(targetId) : null;
        if (!targetItem) continue;
        var inlineVisualIds = [];
        try { inlineVisualIds = collectTfInlineVisualIds(targetItem); } catch (eInlineVisuals) { inlineVisualIds = []; }
        if (inlineVisualIds && inlineVisualIds.length > 0) {
            inlineVisualIds = inlineVisualDetector.expandInlineVisualSourceIds(inlineVisualIds);
            candidate.sourceObjectIds = mergeIds(candidate.sourceObjectIds || [], inlineVisualIds);
            candidate.hiddenVisualSourceObjectIds = mergeIds(candidate.hiddenVisualSourceObjectIds || [], inlineVisualIds);
            candidate.exportSourceObjectIds = removeIds(candidate.exportSourceObjectIds || [], inlineVisualIds);
        }
        var hiddenInlineSourceIds = inlineVisualDetector.collectHiddenTextFrameInlineSourceIds(candidate.hiddenTextFrameIds || []);
        if (hiddenInlineSourceIds && hiddenInlineSourceIds.length > 0) {
            if (candidate.compositeRole !== "source_declared_closed_text_shell"
                    && candidate.sourceDeclaredClosedTextShell !== true) {
                candidate.sourceObjectIds = mergeIds(candidate.sourceObjectIds || [], hiddenInlineSourceIds);
                candidate.hiddenTextFrameIds = mergeIds(candidate.hiddenTextFrameIds || [],
                        filterTextFrameIds(hiddenInlineSourceIds));
                candidate.exportSourceObjectIds = removeIds(candidate.exportSourceObjectIds || [], hiddenInlineSourceIds);
            }
        }
        candidate.composite = candidate.sourceObjectIds.length > 1 || candidate.composite === true;
        candidate.candidateId = candidate.composite
                ? _candidateCompositeId(candidate.passId, candidate.pageIndex, candidate.sourceObjectIds, candidate.slotRole || candidate.suffix)
                : _candidateId(candidate.passId, candidate.primarySourceObjectId, candidate.pageIndex);
        assertExecutableShellCandidate(candidate);
    }
    return candidates;
}

function _appendMultiTextParentGroupExportCandidatesFromSourceItems(sourceItems, candidates, candidateSeen) {
    if (!sourceItems || !candidates) return candidates || [];
    var indexes = null;
    try { indexes = _buildSourceItemIndexes(sourceItems || []); } catch (eIndex) { indexes = null; }
    if (!indexes) return candidates;
    var sourceInfoById = indexes.sourceInfoById || {};
    var childIdsByParentId = indexes.childIdsByParentId || {};

    function info(id) {
        return sourceInfoById ? sourceInfoById[String(id)] : null;
    }

    function hasInlineAnchorMetadata(src) {
        if (!src) return false;
        var ap = String(src.anchoredPosition || "");
        var sp = String(src.storyAnchorPlacement || "");
        if (ap.length > 0 && ap !== "PAGE") return true;
        if (sp.length > 0 && sp !== "PAGE") return true;
        return false;
    }

    function collectSubtree(rootId) {
        var ids = [], seen = {};
        function visit(id) {
            if (id === null || id === undefined || seen[String(id)]) return;
            seen[String(id)] = true;
            ids.push(id);
            var children = childIdsByParentId[String(id)] || [];
            for (var ci = 0; ci < children.length; ci++) visit(children[ci]);
        }
        visit(rootId);
        return _sortedNumericIds(ids);
    }

    function sourceHasVisibleVisual(src) {
        if (!src) return false;
        if (String(src.kind || "") === "TextFrame") return false;
        if (src.visible === false || src.hiddenLayer === true) return false;
        return src.hasPlacedVisual === true
                || src.hasVisibleFill === true
                || src.hasVisibleStroke === true
                || src.hasCandidateVectorPaint === true
                || String(src.kind || "") === "Group";
    }

    function pushCandidate(candidate) {
        if (!candidate || !candidate.candidateId) return;
        var key = String(candidate.candidateId);
        if (candidateSeen && candidateSeen[key]) return;
        if (candidateSeen) candidateSeen[key] = true;
        candidates.push(candidate);
    }

    for (var i = 0; i < sourceItems.length; i++) {
        var root = sourceItems[i];
        if (!root || String(root.kind || "") !== "Group") continue;
        if (root.visible === false || root.hiddenLayer === true) continue;
        if (hasInlineAnchorMetadata(root)) continue;
        var subtree = collectSubtree(root.id);
        if (subtree.length <= 1) continue;
        var editableIds = [], editableSeen = {};
        var visualIds = [], visualSeen = {};
        var hasTableText = false;
        var hasNestedInline = false;
        for (var si = 0; si < subtree.length; si++) {
            var src = info(subtree[si]);
            if (!src) continue;
            if (src.id !== root.id && hasInlineAnchorMetadata(src)) {
                hasNestedInline = true;
                break;
            }
            if (String(src.kind || "") === "TextFrame") {
                if (src.hasTablesInStory === true || Number(src.tableCountInStory || 0) > 0) {
                    hasTableText = true;
                    break;
                }
                if (src.textFrameClass === "editable" && src.hasText === true) {
                    _pushUniqueId(editableIds, editableSeen, src.id);
                }
                continue;
            }
            if (sourceHasVisibleVisual(src)) {
                _pushUniqueId(visualIds, visualSeen, src.id);
            }
        }
        if (hasNestedInline || hasTableText) continue;
        if (editableIds.length < 2 || visualIds.length === 0) continue;
        editableIds = _sortedNumericIds(editableIds);
        visualIds = _sortedNumericIds(visualIds);
        subtree = _sortedNumericIds(subtree);
        var candidateId = _candidateCompositeId(
                "pass.decoration_groups",
                root.pageIndex,
                subtree,
                "textless_group_visual_slot");
        pushCandidate({
            candidateId: candidateId,
            passId: "pass.decoration_groups",
            sourceObjectIds: subtree,
            executionSourceObjectIds: subtree.slice(0),
            primarySourceObjectId: root.id,
            pageIndex: root.pageIndex,
            kind: root.kind || "Group",
            unit: "GROUP_OR_ITEM",
            mode: "TEXTLESS_CANDIDATE",
            candidatePurpose: "SHELL_CANDIDATE",
            bounds: root.bounds || null,
            parentId: root.parentId,
            parentKind: root.parentKind || null,
            composite: true,
            compositeRole: "textless_group_visual_slot",
            slotRole: "textless_group_visual_slot",
            exportSourceObjectIds: visualIds,
            exportTargetObjectId: root.id,
            hiddenVisualSourceObjectIds: editableIds.slice(0),
            visualSourceObjectIds: visualIds.slice(0),
            ownedTextFrameIds: editableIds.slice(0),
            editableTextFrameIds: editableIds.slice(0),
            hiddenTextFrameIds: editableIds.slice(0),
            requiresTextHidden: true,
            textOwner: "hwpx_tf",
            containsEditableText: false,
            completePngTextAllowed: false,
            materialization: "EXTRACTED_PNG_VECTOR",
            textAction: "OWNED_BY_HWPX_TEXT",
            visualAction: "PLACE_TEXT_SHELL",
            visualLayer: "LABEL_BACKDROP",
            placement: "FLOATING",
            coordinateSpace: "PAGE",
            ownershipSlot: "TEXTLESS_GROUP_VISUAL_SLOT",
            reason: "multi_text_parent_group_textless_visual",
            zOrder: root.zOrder !== undefined ? root.zOrder : 0,
            required: false
        });
    }
    return candidates;
}

function _absorbInlineDecorationDescendantsIntoTextShellCandidates(candidates, sourceItems) {
    if (!candidates || candidates.length === 0) return candidates || [];
    var indexes = null;
    try { indexes = _buildSourceItemIndexes(sourceItems || []); } catch (eIndex) { indexes = null; }
    if (!indexes) return candidates;
    var sourceInfoById = indexes.sourceInfoById || {};
    var childIdsByParentId = indexes.childIdsByParentId || {};

    function info(id) {
        return sourceInfoById ? sourceInfoById[String(id)] : null;
    }

    function idSet(ids) {
        var out = {};
        for (var i = 0; ids && i < ids.length; i++) out[String(ids[i])] = true;
        return out;
    }

    function addId(ids, seen, id) {
        if (id === null || id === undefined) return false;
        var key = String(id);
        if (seen[key]) return false;
        seen[key] = true;
        ids.push(Number(id));
        return true;
    }

    function mergeSorted(base, extra) {
        var ids = [];
        var seen = {};
        for (var i = 0; base && i < base.length; i++) addId(ids, seen, base[i]);
        for (var j = 0; extra && j < extra.length; j++) addId(ids, seen, extra[j]);
        return _sortedNumericIds(ids);
    }

    function isInlineRoot(src) {
        if (!src) return false;
        var parentKind = String(src.parentKind || "");
        if (parentKind === "Character" || parentKind === "InsertionPoint") return true;
        if (String(src.anchoredPosition || "").toUpperCase() === "INLINE_POSITION") return true;
        return String(src.storyAnchorPlacement || "").toUpperCase() === "INLINE";
    }

    function isDecorationKind(kind) {
        kind = String(kind || "");
        return kind === "Group"
                || kind === "Rectangle"
                || kind === "Oval"
                || kind === "Polygon"
                || kind === "GraphicLine";
    }

    function sourceHasPlacedContent(id) {
        var src = info(id);
        if (!src) return false;
        var kind = String(src.kind || "");
        if (kind === "Image" || kind === "PDF" || kind === "EPS") return true;
        if (src.hasPlacedVisual === true) return true;
        var children = childIdsByParentId[String(id)] || [];
        for (var ci = 0; ci < children.length; ci++) {
            if (sourceHasPlacedContent(children[ci])) return true;
        }
        return false;
    }

    function isEditableTextFrame(src) {
        return src
                && String(src.kind || "") === "TextFrame"
                && src.textFrameClass === "editable"
                && src.hasText === true
                && src.hiddenLayer !== true
                && src.visible !== false;
    }

    function isInlineTextShellCandidate(candidate) {
        if (!candidate || candidate.disabled === true) return false;
        if (candidate.passId !== "pass.inline_objects") return false;
        if (candidate.visualAction !== "PLACE_TEXT_SHELL" && candidate.textOwner !== "hwpx_tf") return false;
        var owned = candidate.ownedTextFrameIds || candidate.editableTextFrameIds || candidate.hiddenTextFrameIds || [];
        if (!owned || owned.length === 0) return false;
        var rootId = candidate.primarySourceObjectId !== undefined && candidate.primarySourceObjectId !== null
                ? candidate.primarySourceObjectId
                : (candidate.sourceObjectIds && candidate.sourceObjectIds.length > 0 ? candidate.sourceObjectIds[0] : null);
        var root = info(rootId);
        if (!root || String(root.kind || "") !== "Group") return false;
        return isInlineRoot(root);
    }

    function collectInlineShellSlot(rootId, candidate) {
        var sourceIds = [];
        var visualIds = [];
        var textIds = [];
        var sourceSeen = idSet(candidate.sourceObjectIds || []);
        var visualSeen = idSet(candidate.visualSourceObjectIds || candidate.exportSourceObjectIds || []);
        var textSeen = idSet(candidate.ownedTextFrameIds || candidate.editableTextFrameIds || candidate.hiddenTextFrameIds || []);
        var addedVisualCount = 0;

        function visit(id) {
            var src = info(id);
            if (!src || src.hiddenLayer === true || src.visible === false) return;
            var kind = String(src.kind || "");
            if (isEditableTextFrame(src)) {
                if (addId(sourceIds, sourceSeen, src.id)) {}
                addId(textIds, textSeen, src.id);
                return;
            }
            if (kind === "TextFrame") return;
            if (String(src.id) !== String(rootId) && sourceHasPlacedContent(src.id)) return;
            if (isDecorationKind(kind)) {
                if (addId(sourceIds, sourceSeen, src.id)) {}
                if (addId(visualIds, visualSeen, src.id)) addedVisualCount++;
            } else if (String(src.id) !== String(rootId)) {
                return;
            }
            var children = childIdsByParentId[String(src.id)] || [];
            for (var ci = 0; ci < children.length; ci++) visit(children[ci]);
        }

        visit(rootId);
        return {
            sourceIds: _sortedNumericIds(sourceIds),
            visualIds: _sortedNumericIds(visualIds),
            textIds: _sortedNumericIds(textIds),
            addedVisualCount: addedVisualCount
        };
    }

    for (var ci = 0; ci < candidates.length; ci++) {
        var candidate = candidates[ci];
        if (!isInlineTextShellCandidate(candidate)) continue;
        var rootId = candidate.primarySourceObjectId !== undefined && candidate.primarySourceObjectId !== null
                ? candidate.primarySourceObjectId
                : candidate.sourceObjectIds[0];
        var slot = collectInlineShellSlot(rootId, candidate);
        if (!slot || slot.addedVisualCount <= 0) continue;
        candidate.sourceObjectIds = mergeSorted(candidate.sourceObjectIds || [], slot.sourceIds);
        candidate.executionSourceObjectIds = mergeSorted(candidate.executionSourceObjectIds || candidate.sourceObjectIds || [], slot.sourceIds);
        candidate.exportSourceObjectIds = mergeSorted(candidate.exportSourceObjectIds || [], slot.visualIds);
        candidate.visualSourceObjectIds = mergeSorted(candidate.visualSourceObjectIds || [], slot.visualIds);
        candidate.ownedTextFrameIds = mergeSorted(candidate.ownedTextFrameIds || [], slot.textIds);
        candidate.editableTextFrameIds = mergeSorted(candidate.editableTextFrameIds || [], slot.textIds);
        candidate.hiddenTextFrameIds = mergeSorted(candidate.hiddenTextFrameIds || [], slot.textIds);
        candidate.hiddenVisualSourceObjectIds = mergeSorted(candidate.hiddenVisualSourceObjectIds || [], slot.textIds);
        candidate.inlineTextShellAbsorbedDecorationDescendants = true;
        candidate.composite = true;
        candidate.candidateId = _candidateCompositeId(
                candidate.passId, candidate.pageIndex, candidate.sourceObjectIds, candidate.slotRole || candidate.suffix);
    }
    return candidates;
}

function _suppressChildExportsCoveredByTextlessGroupCandidates(candidates, sourceItems) {
    if (!candidates || candidates.length === 0) {
        return { candidates: candidates || [], suppressedCount: 0, suppressed: [] };
    }
    var indexes = null;
    try { indexes = _buildSourceItemIndexes(sourceItems || []); } catch (eIndex) { indexes = null; }
    var sourceInfoById = indexes ? indexes.sourceInfoById || {} : {};

    function info(id) {
        return sourceInfoById ? sourceInfoById[String(id)] : null;
    }

    function parentIdOf(id) {
        var src = info(id);
        if (!src) return null;
        return src.parentId !== undefined && src.parentId !== null ? src.parentId : null;
    }

    function isDescendantOf(childId, parentId) {
        if (childId === null || childId === undefined || parentId === null || parentId === undefined) return false;
        if (String(childId) === String(parentId)) return true;
        var cur = parentIdOf(childId);
        var guard = 0;
        while (cur !== null && cur !== undefined && guard++ < 100) {
            if (String(cur) === String(parentId)) return true;
            cur = parentIdOf(cur);
        }
        return false;
    }

    function ids(candidate, field) {
        return _sortedNumericIds(candidate && candidate[field] || []);
    }

    function textFrameIds(candidate) {
        var merged = [];
        var seen = {};
        function add(values) {
            for (var i = 0; values && i < values.length; i++) {
                var id = values[i];
                var key = String(id);
                if (seen[key]) continue;
                seen[key] = true;
                merged.push(id);
            }
        }
        add(candidate && candidate.ownedTextFrameIds);
        add(candidate && candidate.editableTextFrameIds);
        add(candidate && candidate.hiddenTextFrameIds);
        return _sortedNumericIds(merged);
    }

    function containsAll(ownerIds, childIds) {
        if (!childIds || childIds.length === 0) return false;
        var set = {};
        for (var i = 0; ownerIds && i < ownerIds.length; i++) set[String(ownerIds[i])] = true;
        for (var ci = 0; ci < childIds.length; ci++) {
            if (!set[String(childIds[ci])]) return false;
        }
        return true;
    }

    function primaryId(candidate) {
        if (!candidate) return null;
        if (candidate.primarySourceObjectId !== undefined && candidate.primarySourceObjectId !== null) {
            return candidate.primarySourceObjectId;
        }
        var sourceIds = ids(candidate, "sourceObjectIds");
        return sourceIds.length > 0 ? sourceIds[0] : null;
    }

    function isTextlessParentGroup(candidate) {
        return candidate
                && candidate.passId === "pass.decoration_groups"
                && candidate.slotRole === "textless_group_visual_slot"
                && candidate.textOwner === "hwpx_tf";
    }

    function isInlineTextlessShellCarrier(candidate) {
        var textIds = textFrameIds(candidate);
        return candidate
                && candidate.passId === "pass.inline_objects"
                && (candidate.textOwner === "hwpx_tf" || textIds.length > 0)
                && candidate.ownershipSlot !== "CONTENT_VISUAL_SLOT"
                && candidate.visualAction !== "PLACE_INLINE_PNG"
                && candidate.visualAction !== "PLACE_FLOATING_PNG";
    }

    function isTextlessShellCarrier(candidate) {
        return isTextlessParentGroup(candidate) || isInlineTextlessShellCarrier(candidate);
    }

    function isSuppressibleChild(candidate) {
        if (!candidate || candidate.disabled === true) return false;
        if (isTextlessParentGroup(candidate)) return true;
        if (candidate.passId === "pass.inline_objects"
                && candidate.slotRole === "inline_textless_sibling_decoration_slot"
                && (!candidate.ownedTextFrameIds || candidate.ownedTextFrameIds.length === 0)) {
            return true;
        }
        if (candidate.visualAction === "DROP_VISUAL") return false;
        if (candidate.materialization === "HWPX_TEXT"
                || candidate.materialization === "HWPX_TABLE_STYLE"
                || candidate.materialization === "NATIVE_SOURCE_SHAPE") {
            return false;
        }
        return candidate.passId === "pass.decoration_groups"
                || candidate.passId === "pass.image_textless_groups"
                || candidate.passId === "pass.image_placed_frames"
                || candidate.passId === "pass.vector_shape_frames"
                || candidate.passId === "pass.complex_graphic_frames";
    }

    var parents = [];
    for (var pi = 0; pi < candidates.length; pi++) {
        if (isTextlessShellCarrier(candidates[pi])) {
            parents.push({
                candidate: candidates[pi],
                primaryId: primaryId(candidates[pi]),
                editableIds: textFrameIds(candidates[pi])
            });
        }
    }
    if (parents.length === 0) {
        return { candidates: candidates, suppressedCount: 0, suppressed: [] };
    }

    var out = [];
    var suppressed = [];
    for (var ci = 0; ci < candidates.length; ci++) {
        var candidate = candidates[ci];
        if (!isSuppressibleChild(candidate)) {
            out.push(candidate);
            continue;
        }
        var candidatePrimary = primaryId(candidate);
        var coveredBy = null;
        for (var pp = 0; pp < parents.length; pp++) {
            var parent = parents[pp];
            if (String(candidate.pageIndex) !== String(parent.candidate.pageIndex)) continue;
            if (String(candidate.candidateId || "") === String(parent.candidate.candidateId || "")) continue;
            var candidateTextIds = textFrameIds(candidate);
            if ((isTextlessParentGroup(candidate) || candidateTextIds.length > 0)
                    && !containsAll(parent.editableIds || [], candidateTextIds)) {
                continue;
            }
            if (!isDescendantOf(candidatePrimary, parent.primaryId)) continue;
            coveredBy = parent.candidate;
            break;
        }
        if (!coveredBy) {
            out.push(candidate);
            continue;
        }
        suppressed.push({
            candidateId: candidate.candidateId || null,
            suppressedByCandidateId: coveredBy.candidateId || null,
            pageIndex: candidate.pageIndex,
            primarySourceObjectId: candidatePrimary,
            reason: "covered_by_textless_parent_group_export"
        });
    }
    return {
        candidates: out,
        suppressedCount: suppressed.length,
        suppressed: suppressed
    };
}

function _appendPageTextlessGraphicGroupCandidates(candidates, sourceItems, candidateSeen, sourceIndex) {
    if (!candidates || candidates.length === 0) {
        return { appendedCount: 0, componentCount: 0, components: [] };
    }
    var indexes = null;
    try { indexes = _buildSourceItemIndexes(sourceItems || []); } catch (eIndex) { indexes = null; }
    var sourceInfoById = indexes ? indexes.sourceInfoById || {} : {};
    var childIdsByParentId = indexes ? indexes.childIdsByParentId || {} : {};
    var pageWideBackgroundMinZByPage = {};

    function ids(candidate, field) {
        return _sortedNumericIds(candidate && candidate[field] || []);
    }

    function mergeIds(target, seen, values) {
        for (var i = 0; values && i < values.length; i++) {
            _pushUniqueId(target, seen, values[i]);
        }
    }

    function visibleExportIds(candidate) {
        var exportIds = ids(candidate, "exportSourceObjectIds");
        if (exportIds.length > 0) return exportIds;
        var visualIds = ids(candidate, "visualSourceObjectIds");
        if (visualIds.length > 0) return visualIds;
        return ids(candidate, "sourceObjectIds");
    }

    function sourceIds(candidate) {
        return ids(candidate, "sourceObjectIds");
    }

    function info(id) {
        return sourceInfoById ? sourceInfoById[String(id)] : null;
    }

    function sourceIsTextLike(id) {
        var src = info(id);
        if (!src) return false;
        var kind = String(src.kind || "");
        return kind === "TextFrame" || kind === "Story" || kind === "Character"
                || kind === "InsertionPoint" || kind === "Cell";
    }

    function sourceBoundsArea(b) {
        if (!b || b.length < 4) return 0;
        return Math.max(0, Number(b[2]) - Number(b[0]))
                * Math.max(0, Number(b[3]) - Number(b[1]));
    }

    function pageBounds(pageIndex) {
        try {
            if (sourceIndex && sourceIndex.pageBounds) return sourceIndex.pageBounds(Number(pageIndex));
        } catch (ePageBounds) {}
        return null;
    }

    function boundsIntersects(a, b) {
        if (!a || !b || a.length < 4 || b.length < 4) return false;
        return a[2] > b[0] && a[0] < b[2] && a[3] > b[1] && a[1] < b[3];
    }

    function sourceLooksLikePageWideSingleColorFill(src, pageIndex) {
        if (!src || !src.bounds || src.bounds.length < 4) return false;
        if (src.hasPlacedVisual === true || src.hasPlacedVisualInSubtree === true) return false;
        if (src.hasVisibleFill !== true || src.hasVisibleStroke === true) return false;
        if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) return false;
        var kind = String(src.kind || "");
        if (kind !== "Rectangle" && kind !== "Polygon" && kind !== "Oval") return false;
        var pb = pageBounds(pageIndex);
        if (!pb || pb.length < 4 || !boundsIntersects(src.bounds, pb)) return false;
        var pageWidth = Math.max(0, Number(pb[3]) - Number(pb[1]));
        var pageHeight = Math.max(0, Number(pb[2]) - Number(pb[0]));
        if (pageWidth <= 0 || pageHeight <= 0) return false;
        var b = src.bounds;
        var width = Math.max(0, Number(b[3]) - Number(b[1]));
        var height = Math.max(0, Number(b[2]) - Number(b[0]));
        var touchesLeft = Number(b[1]) <= Number(pb[1]) + 1.0;
        var touchesRight = Number(b[3]) >= Number(pb[3]) - 1.0;
        var touchesTop = Number(b[0]) <= Number(pb[0]) + 1.0;
        var touchesBottom = Number(b[2]) >= Number(pb[2]) - 1.0;
        var spansWidth = width >= pageWidth * 0.85 && touchesLeft && touchesRight;
        var spansHeight = height >= pageHeight * 0.85 && touchesTop && touchesBottom;
        return spansWidth || spansHeight;
    }

    function minPageWideBackgroundZ(pageIndex) {
        var key = String(pageIndex);
        if (pageWideBackgroundMinZByPage.hasOwnProperty(key)) {
            return pageWideBackgroundMinZByPage[key];
        }
        var minZ = null;
        var pb = pageBounds(pageIndex);
        for (var i = 0; sourceItems && i < sourceItems.length; i++) {
            var src = sourceItems[i];
            if (!src || !src.bounds || src.bounds.length < 4) continue;
            if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) continue;
            if (src.isInline === true) continue;
            if (!pb || pb.length < 4 || !boundsIntersects(src.bounds, pb)) continue;
            var z = Number(src.zOrder || 0);
            if (minZ === null || z < minZ) minZ = z;
        }
        pageWideBackgroundMinZByPage[key] = minZ;
        return minZ;
    }

    function descendantIds(sourceId) {
        var out = [];
        var seen = {};
        function visit(id) {
            if (id === null || id === undefined || seen[String(id)]) return;
            seen[String(id)] = true;
            out.push(id);
            var children = childIdsByParentId[String(id)] || [];
            for (var ci = 0; ci < children.length; ci++) visit(children[ci]);
        }
        visit(sourceId);
        return _sortedNumericIds(out);
    }

    function clipCarryingParentId(id) {
        try {
            if (sourceIndex && sourceIndex.clipCarryingParentIdOfSource) {
                return sourceIndex.clipCarryingParentIdOfSource(id);
            }
        } catch (eClipParent) {}
        return null;
    }

    function expandSourceIds(sourceIds, includeTextLike, includeInlineFlow) {
        var out = [];
        var seen = {};
        var expanded = [];
        var expandedSet = {};
        for (var i = 0; sourceIds && i < sourceIds.length; i++) {
            var descendants = descendantIds(sourceIds[i]);
            for (var di = 0; di < descendants.length; di++) {
                _pushUniqueId(expanded, expandedSet, descendants[di]);
            }
        }
        for (var ei = 0; ei < expanded.length; ei++) {
            var id = expanded[ei];
            var clipParentId = clipCarryingParentId(id);
            if (clipParentId !== null && clipParentId !== undefined
                    && !expandedSet[String(clipParentId)]) {
                continue;
            }
            if (!includeTextLike && sourceIsTextLike(id)) continue;
            if (includeInlineFlow !== true && sourceIsInlineFlow(id)) continue;
            if (sourceIsStoryAnchoredMaterial(id)) continue;
            _pushUniqueId(out, seen, id);
        }
        return _sortedNumericIds(out);
    }

    function sourceIsInlineFlow(id) {
        var src = info(id);
        if (!src) return false;
        if (typeof _isInlineFlowItemBySourceInfo === "function"
                && _isInlineFlowItemBySourceInfo(src)) {
            return true;
        }
        var cur = src;
        var guard = 0;
        while (cur && guard++ < 64) {
            var kind = String(cur.kind || "");
            var parentKind = String(cur.parentKind || "");
            if (parentKind === "Character" || parentKind === "InsertionPoint") {
                return true;
            }
            if (kind === "Character" || kind === "InsertionPoint" || kind === "Cell"
                    || kind === "Story") {
                return true;
            }
            if (cur.parentId === null || cur.parentId === undefined) break;
            cur = info(cur.parentId);
        }
        return false;
    }

    function sourceIsStoryAnchoredMaterial(id) {
        var src = info(id);
        if (!src) return false;
        var anchoredPosition = String(src.anchoredPosition || "").toUpperCase();
        var storyAnchorPlacement = String(src.storyAnchorPlacement || "").toUpperCase();
        return anchoredPosition === "ANCHORED"
                || storyAnchorPlacement === "FLOATING_ANCHORED";
    }

    function sourcePageIndex(id) {
        var src = info(id);
        return src && src.pageIndex !== undefined && src.pageIndex !== null
                ? Number(src.pageIndex)
                : null;
    }

    function sourceHasCrossPageClipParent(id, pageIndex) {
        var clipParentId = clipCarryingParentId(id);
        if (clipParentId === null || clipParentId === undefined) return false;
        var clipPageIndex = sourcePageIndex(clipParentId);
        return clipPageIndex !== null
                && pageIndex !== null
                && pageIndex !== undefined
                && Number(clipPageIndex) !== Number(pageIndex);
    }

    function candidateHasCrossPageClipParentContext(candidate, pageIndex) {
        var values = visibleExportIds(candidate)
                .concat(sourceIds(candidate))
                .concat(candidate && candidate.visualSourceObjectIds || []);
        var seen = {};
        for (var i = 0; i < values.length; i++) {
            var id = values[i];
            if (id === null || id === undefined || seen[String(id)]) continue;
            seen[String(id)] = true;
            if (sourceHasCrossPageClipParent(id, pageIndex)) return true;
        }
        return false;
    }

    function sourceHasPageWideBackgroundShapeOnPage(src, rolePageIndex) {
        if (!sourceLooksLikePageWideSingleColorFill(src, rolePageIndex)) return false;
        if (_isBackgroundLayerName(src.layerName)) return true;
        return true;
    }

    function sourceHasPageWideBackgroundShape(id, pageIndex) {
        var src = info(id);
        if (!src) return false;
        if (sourceHasPageWideBackgroundShapeOnPage(src, pageIndex)) return true;
        if (src.pageIndex !== null && src.pageIndex !== undefined
                && String(src.pageIndex) !== String(pageIndex)) {
            return sourceHasPageWideBackgroundShapeOnPage(src, src.pageIndex);
        }
        return false;
    }

    function candidateHasBackgroundRole(candidate) {
        if (!candidate) return false;
        if (candidate.passId === "pass.page_backgrounds") return true;
        if (candidate.compositeRole === "background_vector_source") return true;
        if (candidate.slotRole === "background_shell_slot") return true;
        if (candidate.visualLayer === "PAGE_BACKGROUND") return true;
        var src = sourceIds(candidate);
        for (var i = 0; i < src.length; i++) {
            if (sourceHasPageWideBackgroundShape(src[i], candidate.pageIndex)) return true;
        }
        return false;
    }

    function candidateHasEditableTextSignal(candidate) {
        if (!candidate) return false;
        if (candidate.containsEditableText === true) return true;
        if (candidate.ownedTextFrameIds && candidate.ownedTextFrameIds.length > 0) return true;
        if (candidate.editableTextFrameIds && candidate.editableTextFrameIds.length > 0) return true;
        if (candidate.hiddenTextFrameIds && candidate.hiddenTextFrameIds.length > 0) return true;
        if (candidate.hiddenVisualSourceObjectIds) {
            for (var i = 0; i < candidate.hiddenVisualSourceObjectIds.length; i++) {
                var src = info(candidate.hiddenVisualSourceObjectIds[i]);
                if (src && String(src.kind || "") === "TextFrame"
                        && src.textFrameClass === "editable") {
                    return true;
                }
            }
        }
        return false;
    }

    function markBackgroundCandidate(candidate) {
        if (!candidate || candidate.passId === "pass.page_backgrounds") return false;
        if (!candidateHasBackgroundRole(candidate)) return false;
        if (candidateHasEditableTextSignal(candidate)) return false;
        candidate.disabled = false;
        candidate.compositeRole = "background_vector_source";
        candidate.slotRole = "background_shell_slot";
        candidate.materialization = "EXTRACTED_PNG_VECTOR";
        candidate.textAction = "DROP_TEXT";
        candidate.visualAction = "PLACE_FLOATING_PNG";
        candidate.visualLayer = "PAGE_BACKGROUND";
        candidate.placement = "FLOATING";
        candidate.coordinateSpace = "PAGE";
        candidate.ownershipSlot = "SHELL_SLOT";
        candidate.backgroundDeferred = false;
        candidate.backgroundReason = "page_wide_single_color_fill_excluded_from_overlap_group";
        return true;
    }

    function bounds(candidate) {
        var b = candidate && candidate.bounds;
        if (!b || b.length < 4) return null;
        return [Number(b[0]), Number(b[1]), Number(b[2]), Number(b[3])];
    }

    function sourceBounds(id) {
        var src = info(id);
        if (!src || !src.bounds || src.bounds.length < 4) return null;
        return [
            Number(src.bounds[0]),
            Number(src.bounds[1]),
            Number(src.bounds[2]),
            Number(src.bounds[3])
        ];
    }

    function boundsHasArea(b) {
        return b && (b[2] - b[0]) > 0.1 && (b[3] - b[1]) > 0.1;
    }

    function boundsOverlapWithPad(a, b, pad) {
        if (!a || !b) return false;
        return a[2] >= b[0] - pad && a[0] <= b[2] + pad
                && a[3] >= b[1] - pad && a[1] <= b[3] + pad;
    }

    function unionBounds(a, b) {
        if (!a) return b ? b.slice(0) : null;
        if (!b) return a.slice(0);
        return [
            Math.min(a[0], b[0]),
            Math.min(a[1], b[1]),
            Math.max(a[2], b[2]),
            Math.max(a[3], b[3])
        ];
    }

    function zOrderOfCandidate(candidate) {
        if (candidate && candidate.zOrder !== undefined && candidate.zOrder !== null) {
            var z = Number(candidate.zOrder);
            if (!isNaN(z)) return z;
        }
        return 0;
    }

    function sourceHasAncestorInSet(id, sourceSet) {
        var cur = info(id);
        var guard = 0;
        while (cur && guard++ < 64) {
            var parentId = cur.parentId;
            if (parentId === null || parentId === undefined) return false;
            if (sourceSet[String(parentId)]) return true;
            cur = info(parentId);
        }
        return false;
    }

    function nonPageAncestorIds(id) {
        var out = [];
        var seen = {};
        var cur = info(id);
        var guard = 0;
        while (cur && guard++ < 64) {
            var kind = String(cur.kind || "");
            if (kind === "Page" || kind === "Spread" || kind === "Document"
                    || kind === "Story" || kind === "Character"
                    || kind === "InsertionPoint" || kind === "Cell") {
                break;
            }
            if (cur.id !== null && cur.id !== undefined) {
                _pushUniqueId(out, seen, cur.id);
            }
            if (cur.parentId === null || cur.parentId === undefined) break;
            cur = info(cur.parentId);
        }
        return out;
    }

    function sourceIdsHaveAncestorRelation(aIds, bIds) {
        var bSet = {};
        for (var bi = 0; bIds && bi < bIds.length; bi++) bSet[String(bIds[bi])] = true;
        for (var ai = 0; aIds && ai < aIds.length; ai++) {
            if (bSet[String(aIds[ai])]) return true;
            if (sourceHasAncestorInSet(aIds[ai], bSet)) return true;
        }
        var aSet = {};
        for (var aj = 0; aIds && aj < aIds.length; aj++) aSet[String(aIds[aj])] = true;
        for (var bj = 0; bIds && bj < bIds.length; bj++) {
            if (sourceHasAncestorInSet(bIds[bj], aSet)) return true;
        }
        return false;
    }

    function entriesShareStructuralRoot(a, b) {
        if (!a || !b) return false;
        var aIds = (a.sourceIds || []).concat(a.exportIds || []);
        var bIds = (b.sourceIds || []).concat(b.exportIds || []);
        if (sourceIdsHaveAncestorRelation(aIds, bIds)) return true;

        var aAncestors = {};
        for (var ai = 0; ai < aIds.length; ai++) {
            var aa = nonPageAncestorIds(aIds[ai]);
            for (var aai = 0; aai < aa.length; aai++) aAncestors[String(aa[aai])] = true;
        }
        for (var bi = 0; bi < bIds.length; bi++) {
            var ba = nonPageAncestorIds(bIds[bi]);
            for (var bai = 0; bai < ba.length; bai++) {
                if (aAncestors[String(ba[bai])]) return true;
            }
        }
        return false;
    }

    function visibleBoundsSourceId(id, sourceSet) {
        var src = info(id);
        if (!src) return id;
        if (String(src.kind || "") !== "Image") return id;
        var parentId = src.parentId;
        var parent = parentId !== null && parentId !== undefined ? info(parentId) : null;
        if (!parent || !parent.bounds) return id;
        var parentKind = String(parent.kind || "");
        if (parentKind !== "Rectangle" && parentKind !== "Oval" && parentKind !== "Polygon") return id;
        return parentId;
    }

    function effectiveCandidateBounds(candidate) {
        var candidateBounds = bounds(candidate);
        var idsForBounds = visibleExportIds(candidate);
        if (!idsForBounds || idsForBounds.length === 0) idsForBounds = sourceIds(candidate);
        if (!idsForBounds || idsForBounds.length === 0) return candidateBounds;

        var sourceSet = {};
        for (var i = 0; i < idsForBounds.length; i++) sourceSet[String(idsForBounds[i])] = true;

        var b = null;
        var used = {};
        for (var si = 0; si < idsForBounds.length; si++) {
            var id = idsForBounds[si];
            if (sourceHasAncestorInSet(id, sourceSet)) continue;
            var boundsId = visibleBoundsSourceId(id, sourceSet);
            if (used[String(boundsId)]) continue;
            used[String(boundsId)] = true;
            var sb = sourceBounds(boundsId);
            if (!boundsHasArea(sb)) continue;
            b = unionBounds(b, sb);
        }
        return boundsHasArea(b) ? b : candidateBounds;
    }

    function isEligible(candidate) {
        if (!candidate || candidate.disabled === true) return false;
        if (candidate.passId === "pass.page_textless_graphic_groups") return false;
        if (candidate.passId !== "pass.decoration_groups"
                && candidate.passId !== "pass.image_textless_groups"
                && candidate.passId !== "pass.image_placed_frames"
                && candidate.passId !== "pass.vector_shape_frames"
                && candidate.passId !== "pass.complex_graphic_frames") {
            return false;
        }
        if (candidateHasBackgroundRole(candidate)) return false;
        if (candidate.placement === "INLINE") return false;
        if (candidate.visualAction === "DROP_VISUAL") return false;
        if (candidate.materialization === "HWPX_TEXT"
                || candidate.materialization === "HWPX_TABLE_STYLE"
                || candidate.materialization === "NATIVE_SOURCE_SHAPE") {
            return false;
        }
        var pageIndex = candidate.pageIndex;
        if (pageIndex === null || pageIndex === undefined || Number(pageIndex) < 0) return false;
        if (candidateHasCrossPageClipParentContext(candidate, pageIndex)) return false;
        var b = bounds(candidate);
        if (!boundsHasArea(b)) return false;
        var exportIds = visibleExportIds(candidate);
        if (exportIds.length === 0) return false;
        var hasNonTextVisual = false;
        for (var i = 0; i < exportIds.length; i++) {
            if (sourceIsInlineFlow(exportIds[i])) return false;
            if (sourceIsStoryAnchoredMaterial(exportIds[i])) return false;
            if (!sourceIsTextLike(exportIds[i])) hasNonTextVisual = true;
        }
        return hasNonTextVisual;
    }

    function candidateEntry(candidate, index) {
        return {
            candidate: candidate,
            index: index,
            pageIndex: Number(candidate.pageIndex),
            bounds: effectiveCandidateBounds(candidate),
            sourceIds: sourceIds(candidate),
            exportIds: visibleExportIds(candidate),
            zOrder: zOrderOfCandidate(candidate)
        };
    }

    for (var bi = 0; bi < candidates.length; bi++) {
        markBackgroundCandidate(candidates[bi]);
    }

    var byPage = {};
    for (var ci = 0; ci < candidates.length; ci++) {
        var candidate = candidates[ci];
        if (!isEligible(candidate)) continue;
        var entry = candidateEntry(candidate, ci);
        var pageKey = String(entry.pageIndex);
        if (!byPage[pageKey]) byPage[pageKey] = [];
        byPage[pageKey].push(entry);
    }

    function buildComponents(entries) {
        var components = [];
        var used = {};
        var pad = 0.5;
        for (var i = 0; i < entries.length; i++) {
            if (used[i]) continue;
            var component = [];
            var queue = [i];
            used[i] = true;
            while (queue.length > 0) {
                var idx = queue.shift();
                component.push(entries[idx]);
                for (var j = 0; j < entries.length; j++) {
                    if (used[j]) continue;
                    var touches = false;
                    for (var k = 0; k < component.length; k++) {
                        if (boundsOverlapWithPad(component[k].bounds, entries[j].bounds, pad)) {
                            touches = true;
                            break;
                        }
                    }
                    if (!touches) continue;
                    used[j] = true;
                    queue.push(j);
                }
            }
            if (component.length > 1) components.push(component);
        }
        return components;
    }

    function textFrameIdsFromSources(sourceIds) {
        var out = [];
        var seen = {};
        for (var i = 0; i < sourceIds.length; i++) {
            var src = info(sourceIds[i]);
            if (!src || String(src.kind || "") !== "TextFrame") continue;
            if (!isEditableTextFrameSource(src)) continue;
            _pushUniqueId(out, seen, sourceIds[i]);
        }
        return _sortedNumericIds(out);
    }

    function isEditableTextFrameSource(src) {
        if (!src || String(src.kind || "") !== "TextFrame") return false;
        if (src.textFrameClass === "editable") return true;
        if (src.hasText === true) return true;
        return Number(src.textLength || 0) > 0;
    }

    function isInlineTextFrameSource(src) {
        if (!src) return false;
        var placement = String(src.storyAnchorPlacement || src.anchoredPosition || "");
        return placement.indexOf("INLINE") >= 0 || String(src.parentKind || "") === "Character";
    }

    function splitTextFrameIdsForPageTextlessGroup(sourceIds) {
        var hidden = [];
        var hiddenSeen = {};
        var pngOwned = [];
        var pngOwnedSeen = {};
        for (var i = 0; i < sourceIds.length; i++) {
            var src = info(sourceIds[i]);
            if (!src || String(src.kind || "") !== "TextFrame") continue;
            if (!isEditableTextFrameSource(src)) continue;
            if (src.simpleMarkerLabelContents === true && !isInlineTextFrameSource(src)) {
                _pushUniqueId(pngOwned, pngOwnedSeen, sourceIds[i]);
            } else {
                _pushUniqueId(hidden, hiddenSeen, sourceIds[i]);
            }
        }
        return {
            hiddenTextFrameIds: _sortedNumericIds(hidden),
            pngOwnedTextFrameIds: _sortedNumericIds(pngOwned)
        };
    }

    function unionSourceIds(a, b) {
        var out = [];
        var seen = {};
        mergeIds(out, seen, a || []);
        mergeIds(out, seen, b || []);
        return _sortedNumericIds(out);
    }

    function subtractSourceIds(ids, removeIds) {
        var remove = {};
        for (var i = 0; removeIds && i < removeIds.length; i++) {
            remove[String(removeIds[i])] = true;
        }
        var out = [];
        var seen = {};
        for (var j = 0; ids && j < ids.length; j++) {
            if (remove[String(ids[j])]) continue;
            _pushUniqueId(out, seen, ids[j]);
        }
        return _sortedNumericIds(out);
    }

    function sourceHasVisiblePaint(id) {
        var src = info(id);
        if (!src) return false;
        if (sourceIsTextLike(id)) return false;
        return src.hasCandidateVectorPaint === true
                || src.hasVisibleFill === true
                || src.hasVisibleStroke === true
                || src.hasPlacedVisual === true;
    }

    function visiblePaintSourceIds(sourceIds, pageIndex) {
        var out = [];
        var seen = {};
        for (var i = 0; i < sourceIds.length; i++) {
            var id = sourceIds[i];
            if (!sourceBelongsToPageTextlessGroupPage(id, pageIndex)) continue;
            if (!sourceHasVisiblePaint(id)) continue;
            if (sourceIsInlineFlow(id)) continue;
            if (sourceIsStoryAnchoredMaterial(id)) continue;
            if (sourceHasCrossPageClipParent(id, pageIndex)) continue;
            _pushUniqueId(out, seen, id);
        }
        return _sortedNumericIds(out);
    }

    function filterPageTextlessGroupSourceIds(sourceIds, pageIndex) {
        var out = [];
        var seen = {};
        for (var i = 0; sourceIds && i < sourceIds.length; i++) {
            var id = sourceIds[i];
            if (!sourceBelongsToPageTextlessGroupPage(id, pageIndex)) continue;
            _pushUniqueId(out, seen, id);
        }
        return _sortedNumericIds(out);
    }

    function sourceBelongsToPageTextlessGroupPage(id, pageIndex) {
        var pi = sourcePageIndex(id);
        if (pi === null || pi === undefined || pageIndex === null || pageIndex === undefined) return true;
        if (Number(pi) === Number(pageIndex)) return true;
        return sourceHasCrossPageClipParent(id, pageIndex);
    }

    var appended = 0;
    var diagnostics = [];
    for (var pageKey in byPage) {
        if (!byPage.hasOwnProperty(pageKey)) continue;
        var components = buildComponents(byPage[pageKey]);
        for (var cc = 0; cc < components.length; cc++) {
            var component = components[cc];
            var allSourceIds = [], allSourceSeen = {};
            var exportIds = [], exportSeen = {};
            var coveredCandidateIds = [];
            var b = null;
            var minZ = null;
            for (var ei = 0; ei < component.length; ei++) {
                var entry = component[ei];
                mergeIds(allSourceIds, allSourceSeen, expandSourceIds(entry.sourceIds, true, false));
                mergeIds(exportIds, exportSeen, expandSourceIds(entry.exportIds, false, false));
                b = unionBounds(b, entry.bounds);
                minZ = minZ === null ? entry.zOrder : Math.min(minZ, entry.zOrder);
                coveredCandidateIds.push(entry.candidate.candidateId || null);
            }
            allSourceIds = _sortedNumericIds(allSourceIds);
            exportIds = _sortedNumericIds(exportIds);
            allSourceIds = filterPageTextlessGroupSourceIds(allSourceIds, Number(pageKey));
            exportIds = filterPageTextlessGroupSourceIds(exportIds, Number(pageKey));
            var textFrameSplit = splitTextFrameIdsForPageTextlessGroup(allSourceIds);
            var hiddenTextFrameIds = textFrameSplit.hiddenTextFrameIds;
            var pngOwnedTextFrameIds = textFrameSplit.pngOwnedTextFrameIds;
            exportIds = subtractSourceIds(exportIds, hiddenTextFrameIds);
            exportIds = unionSourceIds(exportIds, visiblePaintSourceIds(allSourceIds, Number(pageKey)));
            var visualSourceIds = exportIds.slice(0);
            exportIds = unionSourceIds(exportIds, pngOwnedTextFrameIds);
            var executionSourceIds = subtractSourceIds(allSourceIds, hiddenTextFrameIds);
            executionSourceIds = unionSourceIds(executionSourceIds, pngOwnedTextFrameIds);
            if (exportIds.length < 2 || allSourceIds.length < 2) continue;
            var candidateId = _candidateCompositeId(
                    "pass.page_textless_graphic_groups",
                    Number(pageKey),
                    allSourceIds,
                    "overlap");
            var seenKey = "pass.page_textless_graphic_groups|page:" + pageKey
                    + "|src:" + _sourceSetKey(allSourceIds);
            if (candidateSeen && candidateSeen[seenKey]) continue;
            if (candidateSeen) candidateSeen[seenKey] = true;
            candidates.push({
                candidateId: candidateId,
                passId: "pass.page_textless_graphic_groups",
                sourceObjectIds: allSourceIds,
                executionSourceObjectIds: executionSourceIds,
                primarySourceObjectId: allSourceIds.length > 0 ? allSourceIds[0] : null,
                pageIndex: Number(pageKey),
                kind: "PageTextlessGraphicGroup",
                unit: "PAGE_GRAPHIC_GROUP",
                mode: "TEXTLESS_CANDIDATE",
                candidatePurpose: "CONTENT_CANDIDATE",
                bounds: b,
                parentId: null,
                parentKind: "Page",
                anchoredPosition: null,
                storyAnchorPlacement: null,
                composite: true,
                compositeRole: "page_textless_graphic_group",
                slotRole: "page_textless_graphic_group",
                exportSourceObjectIds: exportIds,
                exportTargetObjectId: null,
                hiddenVisualSourceObjectIds: hiddenTextFrameIds,
                visualSourceObjectIds: visualSourceIds,
                styleSourceObjectIds: [],
                ownedTextFrameIds: pngOwnedTextFrameIds,
                editableTextFrameIds: hiddenTextFrameIds,
                hiddenTextFrameIds: hiddenTextFrameIds,
                requiresTextHidden: hiddenTextFrameIds.length > 0,
                textOwner: pngOwnedTextFrameIds.length > 0 ? "indesign_png" : "none",
                containsEditableText: pngOwnedTextFrameIds.length > 0,
                completePngTextAllowed: pngOwnedTextFrameIds.length > 0,
                ownershipSlot: "CONTENT_VISUAL_SLOT",
                materialization: "EXTRACTED_PNG_VECTOR",
                textAction: "DROP_TEXT",
                visualAction: "PLACE_FLOATING_PNG",
                visualLayer: "CONTENT_VISUAL",
                placement: "FLOATING",
                coordinateSpace: "PAGE",
                zOrder: minZ !== null ? minZ : 0,
                coveredCandidateIds: coveredCandidateIds,
                required: false,
                reason: "page_textless_graphic_group_from_overlapping_source_candidates"
            });
            diagnostics.push({
                candidateId: candidateId,
                pageIndex: Number(pageKey),
                coveredCandidateIds: coveredCandidateIds,
                coveredCandidateCount: coveredCandidateIds.length,
                sourceObjectCount: allSourceIds.length,
                exportSourceObjectCount: exportIds.length,
                hiddenTextFrameCount: hiddenTextFrameIds.length,
                pngOwnedTextFrameCount: pngOwnedTextFrameIds.length,
                bounds: b
            });
            appended++;
        }
    }
    return {
        appendedCount: appended,
        componentCount: diagnostics.length,
        components: diagnostics
    };
}

function _mergeOverlappingPageTextlessGraphicGroupCandidates(candidates, sourceItems) {
    if (!candidates || candidates.length === 0) {
        return { candidates: candidates || [], mergedCount: 0, merged: [] };
    }
    var indexes = null;
    try { indexes = _buildSourceItemIndexes(sourceItems || []); } catch (eIndex) { indexes = null; }
    var sourceInfoById = indexes ? indexes.sourceInfoById || {} : {};

    function isPageGroup(candidate) {
        return candidate && candidate.passId === "pass.page_textless_graphic_groups";
    }

    function candidateBounds(candidate) {
        var b = candidate && candidate.bounds;
        if (!b || b.length < 4) return null;
        return [Number(b[0]), Number(b[1]), Number(b[2]), Number(b[3])];
    }

    function boundsHasArea(b) {
        return b && (b[2] - b[0]) > 0.1 && (b[3] - b[1]) > 0.1;
    }

    function boundsOverlapWithPad(a, b, pad) {
        if (!a || !b) return false;
        return a[2] >= b[0] - pad && a[0] <= b[2] + pad
                && a[3] >= b[1] - pad && a[1] <= b[3] + pad;
    }

    function unionBounds(a, b) {
        if (!a) return b ? b.slice(0) : null;
        if (!b) return a.slice(0);
        return [
            Math.min(a[0], b[0]),
            Math.min(a[1], b[1]),
            Math.max(a[2], b[2]),
            Math.max(a[3], b[3])
        ];
    }

    function copyCandidate(candidate) {
        var out = {};
        for (var key in candidate) {
            if (!candidate.hasOwnProperty(key)) continue;
            var value = candidate[key];
            out[key] = value && value.slice ? value.slice(0) : value;
        }
        return out;
    }

    function mergeIds(target, seen, values) {
        for (var i = 0; values && i < values.length; i++) {
            _pushUniqueId(target, seen, values[i]);
        }
    }

    function info(id) {
        return sourceInfoById ? sourceInfoById[String(id)] : null;
    }

    function sourcePageIndex(id) {
        var src = info(id);
        return src && src.pageIndex !== undefined && src.pageIndex !== null
                ? Number(src.pageIndex)
                : null;
    }

    function clipCarryingParentId(id) {
        var src = info(id);
        if (!src || src.parentId === null || src.parentId === undefined) return null;
        var parent = info(src.parentId);
        for (var depth = 0; depth < 16 && parent; depth++) {
            var kind = String(parent.kind || "");
            if (kind === "Rectangle" || kind === "Oval" || kind === "Polygon") {
                return parent.hasChildren ? parent.id : null;
            }
            if (kind !== "Group") return null;
            if (parent.parentId === null || parent.parentId === undefined) return null;
            parent = info(parent.parentId);
        }
        return null;
    }

    function sourceHasCrossPageClipParent(id, pageIndex) {
        var clipParentId = clipCarryingParentId(id);
        if (clipParentId === null || clipParentId === undefined) return false;
        var clipPageIndex = sourcePageIndex(clipParentId);
        return clipPageIndex !== null
                && pageIndex !== null
                && pageIndex !== undefined
                && Number(clipPageIndex) !== Number(pageIndex);
    }

    function sourceBelongsToPageTextlessGroupPage(id, pageIndex) {
        var pi = sourcePageIndex(id);
        if (pi === null || pi === undefined || pageIndex === null || pageIndex === undefined) return true;
        if (Number(pi) === Number(pageIndex)) return true;
        return sourceHasCrossPageClipParent(id, pageIndex);
    }

    function filterPageTextlessGroupSourceIds(sourceIds, pageIndex) {
        var out = [];
        var seen = {};
        for (var i = 0; sourceIds && i < sourceIds.length; i++) {
            var id = sourceIds[i];
            if (!sourceBelongsToPageTextlessGroupPage(id, pageIndex)) continue;
            _pushUniqueId(out, seen, id);
        }
        return _sortedNumericIds(out);
    }

    function sourceHasAncestorInSet(id, sourceSet) {
        var cur = info(id);
        var guard = 0;
        while (cur && guard++ < 64) {
            var parentId = cur.parentId;
            if (parentId === null || parentId === undefined) return false;
            if (sourceSet[String(parentId)]) return true;
            cur = info(parentId);
        }
        return false;
    }

    function nonPageAncestorIds(id) {
        var out = [];
        var seen = {};
        var cur = info(id);
        var guard = 0;
        while (cur && guard++ < 64) {
            var kind = String(cur.kind || "");
            if (kind === "Page" || kind === "Spread" || kind === "Document"
                    || kind === "Story" || kind === "Character"
                    || kind === "InsertionPoint" || kind === "Cell") {
                break;
            }
            if (cur.id !== null && cur.id !== undefined) {
                _pushUniqueId(out, seen, cur.id);
            }
            if (cur.parentId === null || cur.parentId === undefined) break;
            cur = info(cur.parentId);
        }
        return out;
    }

    function sourceIdsHaveAncestorRelation(aIds, bIds) {
        var bSet = {};
        for (var bi = 0; bIds && bi < bIds.length; bi++) bSet[String(bIds[bi])] = true;
        for (var ai = 0; aIds && ai < aIds.length; ai++) {
            if (bSet[String(aIds[ai])]) return true;
            if (sourceHasAncestorInSet(aIds[ai], bSet)) return true;
        }
        var aSet = {};
        for (var aj = 0; aIds && aj < aIds.length; aj++) aSet[String(aIds[aj])] = true;
        for (var bj = 0; bIds && bj < bIds.length; bj++) {
            if (sourceHasAncestorInSet(bIds[bj], aSet)) return true;
        }
        return false;
    }

    function entriesShareStructuralRoot(a, b) {
        if (!a || !b) return false;
        var aIds = (a.candidate.sourceObjectIds || []).concat(a.candidate.exportSourceObjectIds || []);
        var bIds = (b.candidate.sourceObjectIds || []).concat(b.candidate.exportSourceObjectIds || []);
        if (sourceIdsHaveAncestorRelation(aIds, bIds)) return true;
        var aAncestors = {};
        for (var ai = 0; ai < aIds.length; ai++) {
            var aa = nonPageAncestorIds(aIds[ai]);
            for (var aai = 0; aai < aa.length; aai++) aAncestors[String(aa[aai])] = true;
        }
        for (var bi = 0; bi < bIds.length; bi++) {
            var ba = nonPageAncestorIds(bIds[bi]);
            for (var bai = 0; bai < ba.length; bai++) {
                if (aAncestors[String(ba[bai])]) return true;
            }
        }
        return false;
    }

    function mergedIds(component, field) {
        var out = [];
        var seen = {};
        for (var i = 0; i < component.length; i++) {
            mergeIds(out, seen, component[i].candidate[field] || []);
        }
        return _sortedNumericIds(out);
    }

    function splitTextFrameIdsForPageTextlessGroup(sourceIds) {
        var hidden = [];
        var hiddenSeen = {};
        var pngOwned = [];
        var pngOwnedSeen = {};
        for (var i = 0; sourceIds && i < sourceIds.length; i++) {
            var src = info(sourceIds[i]);
            if (!src || String(src.kind || "") !== "TextFrame") continue;
            if (!isEditableTextFrameSource(src)) continue;
            if (src.simpleMarkerLabelContents === true && !isInlineTextFrameSource(src)) {
                _pushUniqueId(pngOwned, pngOwnedSeen, sourceIds[i]);
            } else {
                _pushUniqueId(hidden, hiddenSeen, sourceIds[i]);
            }
        }
        return {
            hiddenTextFrameIds: _sortedNumericIds(hidden),
            pngOwnedTextFrameIds: _sortedNumericIds(pngOwned)
        };
    }

    function isEditableTextFrameSource(src) {
        if (!src || String(src.kind || "") !== "TextFrame") return false;
        if (src.textFrameClass === "editable") return true;
        if (src.hasText === true) return true;
        return Number(src.textLength || 0) > 0;
    }

    function isInlineTextFrameSource(src) {
        if (!src) return false;
        var placement = String(src.storyAnchorPlacement || src.anchoredPosition || "");
        return placement.indexOf("INLINE") >= 0 || String(src.parentKind || "") === "Character";
    }

    function unionSourceIds(a, b) {
        var out = [];
        var seen = {};
        mergeIds(out, seen, a || []);
        mergeIds(out, seen, b || []);
        return _sortedNumericIds(out);
    }

    function subtractSourceIds(ids, removeIds) {
        var remove = {};
        for (var i = 0; removeIds && i < removeIds.length; i++) {
            remove[String(removeIds[i])] = true;
        }
        var out = [];
        var seen = {};
        for (var j = 0; ids && j < ids.length; j++) {
            if (remove[String(ids[j])]) continue;
            _pushUniqueId(out, seen, ids[j]);
        }
        return _sortedNumericIds(out);
    }

    function minZ(component) {
        var out = null;
        for (var i = 0; i < component.length; i++) {
            var z = Number(component[i].candidate.zOrder || 0);
            if (out === null || z < out) out = z;
        }
        return out === null ? 0 : out;
    }

    var byPage = {};
    for (var ci = 0; ci < candidates.length; ci++) {
        var candidate = candidates[ci];
        if (!isPageGroup(candidate)) continue;
        var b = candidateBounds(candidate);
        if (!boundsHasArea(b)) continue;
        var pageKey = String(candidate.pageIndex);
        if (!byPage[pageKey]) byPage[pageKey] = [];
        byPage[pageKey].push({ index: ci, candidate: candidate, bounds: b });
    }

    var replacementByIndex = {};
    var suppressedIndex = {};
    var diagnostics = [];
    var pad = 0.5;
    for (var pageKey in byPage) {
        if (!byPage.hasOwnProperty(pageKey)) continue;
        var entries = byPage[pageKey];
        var used = {};
        for (var i = 0; i < entries.length; i++) {
            if (used[i]) continue;
            var component = [];
            var queue = [i];
            used[i] = true;
            while (queue.length > 0) {
                var idx = queue.shift();
                component.push(entries[idx]);
                for (var j = 0; j < entries.length; j++) {
                    if (used[j]) continue;
                    var touches = false;
                    for (var k = 0; k < component.length; k++) {
                        if (boundsOverlapWithPad(component[k].bounds, entries[j].bounds, pad)) {
                            touches = true;
                            break;
                        }
                    }
                    if (!touches) continue;
                    used[j] = true;
                    queue.push(j);
                }
            }
            if (component.length < 2) continue;

            var merged = copyCandidate(component[0].candidate);
            merged.sourceObjectIds = mergedIds(component, "sourceObjectIds");
            merged.visualSourceObjectIds = mergedIds(component, "visualSourceObjectIds");
            merged.exportSourceObjectIds = mergedIds(component, "exportSourceObjectIds");
            merged.hiddenVisualSourceObjectIds = mergedIds(component, "hiddenVisualSourceObjectIds");
            merged.editableTextFrameIds = mergedIds(component, "editableTextFrameIds");
            merged.hiddenTextFrameIds = mergedIds(component, "hiddenTextFrameIds");
            merged.ownedTextFrameIds = mergedIds(component, "ownedTextFrameIds");
            merged.styleSourceObjectIds = mergedIds(component, "styleSourceObjectIds");
            merged.sourceObjectIds = filterPageTextlessGroupSourceIds(
                    merged.sourceObjectIds, Number(pageKey));
            merged.visualSourceObjectIds = filterPageTextlessGroupSourceIds(
                    merged.visualSourceObjectIds, Number(pageKey));
            merged.exportSourceObjectIds = filterPageTextlessGroupSourceIds(
                    merged.exportSourceObjectIds, Number(pageKey));
            merged.hiddenVisualSourceObjectIds = filterPageTextlessGroupSourceIds(
                    merged.hiddenVisualSourceObjectIds, Number(pageKey));
            merged.editableTextFrameIds = filterPageTextlessGroupSourceIds(
                    merged.editableTextFrameIds, Number(pageKey));
            merged.hiddenTextFrameIds = filterPageTextlessGroupSourceIds(
                    merged.hiddenTextFrameIds, Number(pageKey));
            merged.ownedTextFrameIds = filterPageTextlessGroupSourceIds(
                    merged.ownedTextFrameIds, Number(pageKey));
            merged.styleSourceObjectIds = filterPageTextlessGroupSourceIds(
                    merged.styleSourceObjectIds, Number(pageKey));
            var textFrameSplit = splitTextFrameIdsForPageTextlessGroup(merged.sourceObjectIds);
            merged.hiddenTextFrameIds = textFrameSplit.hiddenTextFrameIds;
            merged.editableTextFrameIds = textFrameSplit.hiddenTextFrameIds;
            merged.hiddenVisualSourceObjectIds = textFrameSplit.hiddenTextFrameIds;
            merged.ownedTextFrameIds = textFrameSplit.pngOwnedTextFrameIds;
            merged.visualSourceObjectIds = subtractSourceIds(
                    merged.visualSourceObjectIds || [], merged.hiddenTextFrameIds || []);
            merged.exportSourceObjectIds = subtractSourceIds(
                    merged.exportSourceObjectIds || [], merged.hiddenTextFrameIds || []);
            merged.exportSourceObjectIds = unionSourceIds(
                    merged.exportSourceObjectIds || [], merged.ownedTextFrameIds || []);
            merged.executionSourceObjectIds = subtractSourceIds(
                    merged.sourceObjectIds || [], merged.hiddenTextFrameIds || []);
            merged.executionSourceObjectIds = unionSourceIds(
                    merged.executionSourceObjectIds || [], merged.ownedTextFrameIds || []);
            merged.bounds = null;
            for (var bi = 0; bi < component.length; bi++) {
                merged.bounds = unionBounds(merged.bounds, component[bi].bounds);
            }
            merged.zOrder = minZ(component);
            merged.primarySourceObjectId = merged.sourceObjectIds.length > 0 ? merged.sourceObjectIds[0] : null;
            merged.requiresTextHidden = merged.hiddenTextFrameIds.length > 0
                    || merged.editableTextFrameIds.length > 0;
            merged.textOwner = merged.ownedTextFrameIds.length > 0 ? "indesign_png" : "none";
            merged.containsEditableText = merged.ownedTextFrameIds.length > 0;
            merged.completePngTextAllowed = merged.ownedTextFrameIds.length > 0;
            merged.reason = "merged_overlapping_page_textless_graphic_group_candidates";
            merged.candidateId = _candidateCompositeId(
                    "pass.page_textless_graphic_groups",
                    Number(pageKey),
                    merged.sourceObjectIds,
                    "merged_overlap");

            replacementByIndex[String(component[0].index)] = merged;
            var mergedCandidateIds = [];
            for (var ci2 = 0; ci2 < component.length; ci2++) {
                mergedCandidateIds.push(component[ci2].candidate.candidateId || null);
                if (ci2 > 0) suppressedIndex[String(component[ci2].index)] = true;
            }
            diagnostics.push({
                candidateId: merged.candidateId,
                pageIndex: Number(pageKey),
                mergedCandidateIds: mergedCandidateIds,
                sourceObjectCount: merged.sourceObjectIds.length,
                exportSourceObjectCount: merged.exportSourceObjectIds.length,
                hiddenTextFrameCount: merged.hiddenTextFrameIds.length,
                pngOwnedTextFrameCount: merged.ownedTextFrameIds.length,
                bounds: merged.bounds
            });
        }
    }

    if (diagnostics.length === 0) {
        return { candidates: candidates, mergedCount: 0, merged: [] };
    }

    var out = [];
    for (var oi = 0; oi < candidates.length; oi++) {
        if (suppressedIndex[String(oi)]) continue;
        var replacement = replacementByIndex[String(oi)];
        out.push(replacement || candidates[oi]);
    }
    return {
        candidates: out,
        mergedCount: diagnostics.length,
        merged: diagnostics
    };
}

function _suppressCrossPageClipParentSourceSetDecorations(candidates, sourceItems) {
    if (!candidates || candidates.length === 0) {
        return { candidates: candidates || [], suppressedCount: 0, suppressed: [] };
    }
    var indexes = null;
    try { indexes = _buildSourceItemIndexes(sourceItems || []); } catch (eIndex) { indexes = null; }
    var sourceInfoById = indexes ? indexes.sourceInfoById || {} : {};

    function isClipParentSourceSetDecoration(candidate) {
        return candidate
                && candidate.passId === "pass.decoration_groups"
                && candidate.compositeRole === "clip_parent_source_set";
    }

    function isDecorationCandidate(candidate) {
        return candidate && candidate.passId === "pass.decoration_groups";
    }

    function primarySourceId(candidate) {
        if (!candidate) return null;
        if (candidate.primarySourceObjectId !== undefined && candidate.primarySourceObjectId !== null) {
            return candidate.primarySourceObjectId;
        }
        return candidate.sourceObjectIds && candidate.sourceObjectIds.length > 0
                ? candidate.sourceObjectIds[0]
                : null;
    }

    function sourcePageIndex(sourceId) {
        var src = sourceInfoById ? sourceInfoById[String(sourceId)] : null;
        return src && src.pageIndex !== undefined && src.pageIndex !== null
                ? Number(src.pageIndex)
                : null;
    }

    function clipCarryingParentId(id) {
        var src = sourceInfoById ? sourceInfoById[String(id)] : null;
        if (!src || src.parentId === null || src.parentId === undefined) return null;
        var parent = sourceInfoById[String(src.parentId)];
        for (var depth = 0; depth < 16 && parent; depth++) {
            var kind = String(parent.kind || "");
            if (kind === "Rectangle" || kind === "Oval" || kind === "Polygon") {
                return parent.hasChildren ? parent.id : null;
            }
            if (kind !== "Group") return null;
            if (parent.parentId === null || parent.parentId === undefined) return null;
            parent = sourceInfoById[String(parent.parentId)];
        }
        return null;
    }

    function sourceHasCrossPageClipParent(id, pageIndex) {
        var clipParentId = clipCarryingParentId(id);
        if (clipParentId === null || clipParentId === undefined) return false;
        var clipPageIndex = sourcePageIndex(clipParentId);
        return clipPageIndex !== null
                && pageIndex !== null
                && pageIndex !== undefined
                && Number(clipPageIndex) !== Number(pageIndex);
    }

    function candidateHasCrossPageClipParentContext(candidate, pageIndex) {
        var values = []
                .concat(candidate && candidate.exportSourceObjectIds || [])
                .concat(candidate && candidate.visualSourceObjectIds || [])
                .concat(candidate && candidate.sourceObjectIds || []);
        var seen = {};
        for (var i = 0; i < values.length; i++) {
            var id = values[i];
            if (id === null || id === undefined || seen[String(id)]) continue;
            seen[String(id)] = true;
            if (sourceHasCrossPageClipParent(id, pageIndex)) return true;
        }
        return false;
    }

    var out = [];
    var suppressed = [];
    for (var i = 0; i < candidates.length; i++) {
        var candidate = candidates[i];
        if (!isDecorationCandidate(candidate)) {
            out.push(candidate);
            continue;
        }
        var primaryId = primarySourceId(candidate);
        var primaryPageIndex = sourcePageIndex(primaryId);
        var candidatePageIndex = candidate.pageIndex !== undefined && candidate.pageIndex !== null
                ? Number(candidate.pageIndex)
                : null;
        var primaryCrossPage = isClipParentSourceSetDecoration(candidate)
                && primaryPageIndex !== null
                && candidatePageIndex !== null
                && primaryPageIndex !== candidatePageIndex;
        if (primaryCrossPage || candidateHasCrossPageClipParentContext(candidate, candidatePageIndex)) {
            suppressed.push({
                candidateId: candidate.candidateId || null,
                pageIndex: candidatePageIndex,
                primarySourceObjectId: primaryId,
                primarySourcePageIndex: primaryPageIndex,
                sourceObjectCount: candidate.sourceObjectIds ? candidate.sourceObjectIds.length : 0,
                reason: primaryCrossPage
                        ? "cross_page_clip_parent_source_set_without_parent_context"
                        : "cross_page_clip_parent_context_without_parent_context"
            });
            continue;
        }
        out.push(candidate);
    }
    return {
        candidates: out,
        suppressedCount: suppressed.length,
        suppressed: suppressed
    };
}

function _suppressChildExportsCoveredByPageTextlessGraphicGroups(candidates, sourceItems) {
    if (!candidates || candidates.length === 0) {
        return { candidates: candidates || [], suppressedCount: 0, suppressed: [] };
    }
    var indexes = null;
    try { indexes = _buildSourceItemIndexes(sourceItems || []); } catch (eIndex) { indexes = null; }
    var sourceInfoById = indexes ? indexes.sourceInfoById || {} : {};

    function ids(candidate, field) {
        return _sortedNumericIds(candidate && candidate[field] || []);
    }

    function info(id) {
        return sourceInfoById ? sourceInfoById[String(id)] : null;
    }

    function sourceIsStoryAnchoredMaterial(id) {
        var src = info(id);
        if (!src) return false;
        var anchoredPosition = String(src.anchoredPosition || "").toUpperCase();
        var storyAnchorPlacement = String(src.storyAnchorPlacement || "").toUpperCase();
        return anchoredPosition === "ANCHORED"
                || storyAnchorPlacement === "FLOATING_ANCHORED";
    }

    function candidateHasStoryAnchoredMaterial(candidate) {
        var values = ids(candidate, "exportSourceObjectIds")
                .concat(ids(candidate, "visualSourceObjectIds"))
                .concat(ids(candidate, "sourceObjectIds"));
        for (var i = 0; i < values.length; i++) {
            if (sourceIsStoryAnchoredMaterial(values[i])) return true;
        }
        return false;
    }

    function visibleExportIds(candidate) {
        var exportIds = ids(candidate, "exportSourceObjectIds");
        if (exportIds.length > 0) return exportIds;
        var visualIds = ids(candidate, "visualSourceObjectIds");
        if (visualIds.length > 0) return visualIds;
        return ids(candidate, "sourceObjectIds");
    }

    function containsAll(ownerIds, childIds) {
        if (!childIds || childIds.length === 0) return false;
        var set = {};
        for (var i = 0; ownerIds && i < ownerIds.length; i++) set[String(ownerIds[i])] = true;
        for (var ci = 0; ci < childIds.length; ci++) {
            if (!set[String(childIds[ci])]) return false;
        }
        return true;
    }

    function isPageGroup(candidate) {
        return candidate && candidate.passId === "pass.page_textless_graphic_groups";
    }

    function isSuppressibleChild(candidate) {
        if (!candidate || candidate.disabled === true || isPageGroup(candidate)) return false;
        if (candidate.passId !== "pass.decoration_groups"
                && candidate.passId !== "pass.editable_textframe_visual_shells"
                && candidate.passId !== "pass.image_textless_groups"
                && candidate.passId !== "pass.image_placed_frames"
                && candidate.passId !== "pass.vector_shape_frames"
                && candidate.passId !== "pass.complex_graphic_frames") {
            return false;
        }
        if (candidate.visualAction === "DROP_VISUAL") return false;
        if (candidate.materialization === "HWPX_TEXT"
                || candidate.materialization === "HWPX_TABLE_STYLE"
                || candidate.materialization === "NATIVE_SOURCE_SHAPE") {
            return false;
        }
        if (candidateHasStoryAnchoredMaterial(candidate)) return false;
        return true;
    }

    var groups = [];
    for (var gi = 0; gi < candidates.length; gi++) {
        var group = candidates[gi];
        if (!isPageGroup(group)) continue;
        groups.push({
            candidate: group,
            sourceIds: ids(group, "sourceObjectIds"),
            exportIds: visibleExportIds(group)
        });
    }
    if (groups.length === 0) return { candidates: candidates, suppressedCount: 0, suppressed: [] };

    var out = [];
    var suppressed = [];
    for (var ci = 0; ci < candidates.length; ci++) {
        var candidate = candidates[ci];
        if (!isSuppressibleChild(candidate)) {
            out.push(candidate);
            continue;
        }
        var childVisibleIds = visibleExportIds(candidate);
        var coveredBy = null;
        for (var gg = 0; gg < groups.length; gg++) {
            var owner = groups[gg];
            if (String(candidate.pageIndex) !== String(owner.candidate.pageIndex)) continue;
            if (!containsAll(owner.exportIds, childVisibleIds)
                    && !containsAll(owner.sourceIds, ids(candidate, "sourceObjectIds"))) {
                continue;
            }
            coveredBy = owner.candidate;
            break;
        }
        if (!coveredBy) {
            out.push(candidate);
            continue;
        }
        suppressed.push({
            candidateId: candidate.candidateId || null,
            suppressedByCandidateId: coveredBy.candidateId || null,
            pageIndex: candidate.pageIndex,
            reason: "covered_by_page_textless_graphic_group"
        });
    }
    return {
        candidates: out,
        suppressedCount: suppressed.length,
        suppressed: suppressed
    };
}

function _mergePageTextlessGraphicGroupDiagnostics(target, appended, suppression) {
    if (!target || !appended) return;
    target.appendedCount = Number(target.appendedCount || 0) + Number(appended.appendedCount || 0);
    target.componentCount = Number(target.componentCount || 0) + Number(appended.componentCount || 0);
    target.components = (target.components || []).concat(appended.components || []);
    if (suppression) {
        target.executionSuppressedCount = Number(target.executionSuppressedCount || 0)
                + Number(suppression.suppressedCount || 0);
        target.executionSuppressed = (target.executionSuppressed || []).concat(suppression.suppressed || []);
    }
}

function _normalizePageCoordinateCandidateBounds(candidates, sourceIndex) {
    if (!candidates || !sourceIndex || !sourceIndex.pageBounds) return candidates || [];
    var normalized = 0;

    function hasArea(b) {
        return b && b.length >= 4
                && Number(b[2]) > Number(b[0])
                && Number(b[3]) > Number(b[1]);
    }

    function intersects(a, b) {
        if (!hasArea(a) || !hasArea(b)) return false;
        return Number(a[2]) > Number(b[0]) && Number(a[0]) < Number(b[2])
                && Number(a[3]) > Number(b[1]) && Number(a[1]) < Number(b[3]);
    }

    function pageRelativeIntersection(bounds, pageBounds) {
        if (!intersects(bounds, pageBounds)) return null;
        var top = Math.max(Number(bounds[0]), Number(pageBounds[0]));
        var left = Math.max(Number(bounds[1]), Number(pageBounds[1]));
        var bottom = Math.min(Number(bounds[2]), Number(pageBounds[2]));
        var right = Math.min(Number(bounds[3]), Number(pageBounds[3]));
        if (bottom <= top || right <= left) return null;
        return [
            top - Number(pageBounds[0]),
            left - Number(pageBounds[1]),
            bottom - Number(pageBounds[0]),
            right - Number(pageBounds[1])
        ];
    }

    for (var i = 0; i < candidates.length; i++) {
        var candidate = candidates[i];
        if (!candidate || !hasArea(candidate.bounds)) continue;
        if (candidate.placement === "INLINE" || candidate.coordinateSpace === "STORY_FLOW") continue;
        if (candidate.pageIndex === null || candidate.pageIndex === undefined) continue;
        var pb = null;
        try { pb = sourceIndex.pageBounds(Number(candidate.pageIndex)); } catch (ePageBounds) { pb = null; }
        if (!hasArea(pb)) continue;
        var rel = pageRelativeIntersection(candidate.bounds, pb);
        if (!rel) continue;
        if (Math.abs(Number(candidate.bounds[0]) - rel[0]) < 0.001
                && Math.abs(Number(candidate.bounds[1]) - rel[1]) < 0.001
                && Math.abs(Number(candidate.bounds[2]) - rel[2]) < 0.001
                && Math.abs(Number(candidate.bounds[3]) - rel[3]) < 0.001) {
            continue;
        }
        candidate.sourceBounds = candidate.sourceBounds || candidate.bounds.slice(0);
        candidate.bounds = rel;
        normalized++;
    }
    try {
        if (normalized > 0 && sourceIndex.stats) {
            sourceIndex.stats.pageCoordinateCandidateBoundsNormalized = normalized;
        }
    } catch (eStats) {}
    return candidates;
}

function _appendUnclaimedVisibleVectorExecutionCandidates(candidates, sourceItems) {
    if (!candidates || !sourceItems || sourceItems.length === 0) {
        return { warningCount: 0, warnings: [] };
    }
    var infoById = {};
    var childIdsByParentId = {};
    for (var i = 0; i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!src || src.id === null || src.id === undefined) continue;
        infoById[String(src.id)] = src;
        if (src.parentId !== null && src.parentId !== undefined) {
            var parentKey = String(src.parentId);
            if (!childIdsByParentId[parentKey]) childIdsByParentId[parentKey] = [];
            childIdsByParentId[parentKey].push(src.id);
        }
    }

    function mark(ids, claimed) {
        for (var mi = 0; ids && mi < ids.length; mi++) {
            if (ids[mi] === null || ids[mi] === undefined) continue;
            claimed[String(ids[mi])] = true;
        }
    }

    function sourceHasPlacedVisualInSubtree(sourceId, visiting) {
        var src = infoById[String(sourceId)];
        if (!src) return false;
        var key = String(sourceId);
        visiting = visiting || {};
        if (visiting[key]) return false;
        visiting[key] = true;
        var kind = String(src.kind || "");
        if (kind === "Image" || kind === "PDF" || kind === "EPS") return true;
        if (src.hasPlacedVisual === true) return true;
        var children = childIdsByParentId[key] || [];
        for (var ci = 0; ci < children.length; ci++) {
            if (sourceHasPlacedVisualInSubtree(children[ci], visiting)) return true;
        }
        return false;
    }

    function sourceHasEditableTextDescendant(sourceId, pageIndex, visiting) {
        var src = infoById[String(sourceId)];
        if (!src) return false;
        var key = String(sourceId);
        visiting = visiting || {};
        if (visiting[key]) return false;
        visiting[key] = true;
        if (String(src.kind || "") === "TextFrame"
                && src.textFrameClass === "editable"
                && src.hasText === true
                && (pageIndex === null || pageIndex === undefined || src.pageIndex === pageIndex)) {
            return true;
        }
        var children = childIdsByParentId[key] || [];
        for (var ci = 0; ci < children.length; ci++) {
            if (sourceHasEditableTextDescendant(children[ci], pageIndex, visiting)) return true;
        }
        return false;
    }

    function sourceLooksLikeVisibleVectorMaterial(src) {
        if (!src) return false;
        if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) return false;
        var kind = String(src.kind || "");
        if (kind !== "Group" && kind !== "Rectangle" && kind !== "Oval"
                && kind !== "Polygon" && kind !== "GraphicLine") {
            return false;
        }
        if (sourceHasPlacedVisualInSubtree(src.id)) return false;
        if (kind === "Group") {
            return src.hasChildren === true
                    && (src.hasVisibleFill === true || src.hasVisibleStroke === true);
        }
        return src.hasVisibleFill === true
                || src.hasVisibleStroke === true
                || src.hasCandidateVectorPaint === true;
    }

    function collectSamePageSubtree(sourceId, pageIndex, seen, out) {
        var src = infoById[String(sourceId)];
        if (!src) return;
        if (pageIndex !== null && pageIndex !== undefined
                && src.pageIndex !== null && src.pageIndex !== undefined
                && src.pageIndex !== pageIndex) {
            return;
        }
        _pushUniqueId(out, seen, sourceId);
        var children = childIdsByParentId[String(sourceId)] || [];
        for (var ci = 0; ci < children.length; ci++) {
            collectSamePageSubtree(children[ci], pageIndex, seen, out);
        }
    }

    function sourceSetHasVisibleVectorMaterial(sourceIds) {
        for (var si = 0; sourceIds && si < sourceIds.length; si++) {
            if (sourceLooksLikeVisibleVectorMaterial(infoById[String(sourceIds[si])])) return true;
        }
        return false;
    }

    var claimed = {};
    for (var ci = 0; ci < candidates.length; ci++) {
        var candidate = candidates[ci];
        if (!candidate) continue;
        mark(candidate.sourceObjectIds, claimed);
        mark(candidate.visualSourceObjectIds, claimed);
        mark(candidate.exportSourceObjectIds, claimed);
        mark(candidate.hiddenVisualSourceObjectIds, claimed);
    }

    var warnings = [];
    for (var si = 0; si < sourceItems.length; si++) {
        var src = sourceItems[si];
        if (!src || src.id === null || src.id === undefined) continue;
        if (claimed[String(src.id)] === true) continue;
        if (!sourceLooksLikeVisibleVectorMaterial(src)) continue;
        if (sourceHasEditableTextDescendant(src.id, src.pageIndex)) continue;

        var sourceIds = [];
        var seen = {};
        collectSamePageSubtree(src.id, src.pageIndex, seen, sourceIds);
        sourceIds = _sortedNumericIds(sourceIds.length > 0 ? sourceIds : [src.id]);
        if (!sourceSetHasVisibleVectorMaterial(sourceIds)) continue;

        var candidateId = sourceIds.length > 1
                ? _candidateCompositeId("pass.decoration_groups", src.pageIndex, sourceIds,
                        "unclaimed_visible_vector_source")
                : _candidateId("pass.decoration_groups", src.id, src.pageIndex);
        warnings.push({
            code: "unclaimed_visible_vector_source",
            severity: "WARNING",
            candidateId: candidateId,
            passId: "pass.decoration_groups",
            sourceObjectIds: sourceIds,
            primarySourceObjectId: src.id,
            pageIndex: src.pageIndex,
            kind: src.kind || null,
            bounds: src.bounds || null,
            parentId: src.parentId,
            parentKind: src.parentKind || null,
            composite: sourceIds.length > 1,
            compositeRole: sourceIds.length > 1
                    ? "unclaimed_visible_vector_source_set"
                    : "unclaimed_visible_vector_source",
            slotRole: "shell_slot_only",
            ownershipSlot: "SHELL_SLOT",
            reason: "unclaimed_visible_vector_source",
            zOrder: src.zOrder !== undefined ? src.zOrder : 0
        });
        mark(sourceIds, claimed);
    }
    return { warningCount: warnings.length, warnings: warnings };
}

function _appendUnresolvedVisibleVectorCoverageCandidates(candidates, sourceCoverageDiagnostics, sourceItems) {
    if (!candidates || !sourceCoverageDiagnostics || !sourceItems) {
        return { warningCount: 0, warnings: [] };
    }
    var infoById = {};
    for (var i = 0; i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!src || src.id === null || src.id === undefined) continue;
        infoById[String(src.id)] = src;
    }
    function isVisibleVectorSource(src) {
        if (!src) return false;
        if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) return false;
        var kind = String(src.kind || "");
        if (kind !== "Group" && kind !== "Rectangle" && kind !== "Oval"
                && kind !== "Polygon" && kind !== "GraphicLine") {
            return false;
        }
        return src.hasVisibleFill === true
                || src.hasVisibleStroke === true
                || src.hasCandidateVectorPaint === true;
    }
    var existing = {};
    for (var ci = 0; ci < candidates.length; ci++) {
        var candidate = candidates[ci];
        if (!candidate) continue;
        var key = String(candidate.passId || "") + "|" + String(candidate.pageIndex) + "|"
                + _sourceSetKey(candidate.sourceObjectIds || []);
        existing[key] = true;
    }
    var warnings = [];
    var rows = sourceCoverageDiagnostics.sourceObjects || [];
    for (var ri = 0; ri < rows.length; ri++) {
        var row = rows[ri];
        if (!row || row.coverageStatus !== "UNRESOLVED") continue;
        var src = infoById[String(row.sourceObjectId)];
        if (!isVisibleVectorSource(src)) continue;
        var sourceIds = [src.id];
        var sourceKey = "pass.decoration_groups|" + String(src.pageIndex) + "|" + _sourceSetKey(sourceIds);
        if (existing[sourceKey]) continue;
        warnings.push({
            code: "unresolved_visible_vector_source",
            severity: "WARNING",
            candidateId: _candidateId("pass.decoration_groups", src.id, src.pageIndex),
            passId: "pass.decoration_groups",
            sourceObjectIds: sourceIds,
            primarySourceObjectId: src.id,
            pageIndex: src.pageIndex,
            kind: src.kind || null,
            bounds: src.bounds || null,
            parentId: src.parentId,
            parentKind: src.parentKind || null,
            composite: false,
            compositeRole: "unresolved_visible_vector_source",
            slotRole: "shell_slot_only",
            ownershipSlot: "SHELL_SLOT",
            reason: "unresolved_visible_vector_source",
            zOrder: src.zOrder !== undefined ? src.zOrder : 0,
            coverageStatus: row.coverageStatus || null,
            coverageClaimKinds: row.coverageClaimKinds || []
        });
        existing[sourceKey] = true;
    }
    return { warningCount: warnings.length, warnings: warnings };
}

function _backfillVisibleCandidateVisualSources(candidates, sourceItems) {
    if (!candidates || candidates.length === 0) return candidates || [];
    var indexes = null;
    try { indexes = _buildSourceItemIndexes(sourceItems || []); } catch (eIndex) { indexes = null; }
    var sourceInfoById = indexes ? indexes.sourceInfoById || {} : {};
    var childIdsByParentId = indexes ? indexes.childIdsByParentId || {} : {};

    function isVisibleCandidate(candidate) {
        if (!candidate || candidate.disabled === true) return false;
        var action = String(candidate.visualAction || "");
        if (action === "DROP_VISUAL" || action === "") return false;
        var materialization = String(candidate.materialization || "");
        if (materialization === "HWPX_TEXT"
                || materialization === "HWPX_TABLE_STYLE"
                || materialization === "NATIVE_SOURCE_SHAPE") {
            return false;
        }
        return true;
    }

    function sourceIsHiddenByTextOwnership(candidate, sourceId) {
        var key = String(sourceId);
        var textIds = candidate.hiddenTextFrameIds || candidate.editableTextFrameIds || [];
        for (var ti = 0; ti < textIds.length; ti++) {
            if (String(textIds[ti]) === key) return true;
        }
        var hiddenIds = candidate.hiddenVisualSourceObjectIds || [];
        for (var hi = 0; hi < hiddenIds.length; hi++) {
            if (String(hiddenIds[hi]) === key) return true;
        }
        return false;
    }

    for (var ci = 0; ci < candidates.length; ci++) {
        var candidate = candidates[ci];
        if (!isVisibleCandidate(candidate)) continue;
        if (candidate.visualSourceObjectIds && candidate.visualSourceObjectIds.length > 0) continue;
        var exportIds = candidate.exportSourceObjectIds || [];
        if (!exportIds || exportIds.length === 0) continue;
        var visualIds = [];
        var seen = {};
        for (var ei = 0; ei < exportIds.length; ei++) {
            var sourceId = exportIds[ei];
            if (sourceIsHiddenByTextOwnership(candidate, sourceId)) continue;
            if (!_sourceHasExecutableShellMaterialMetadataInIndex(
                    sourceId, sourceInfoById, childIdsByParentId)) {
                continue;
            }
            _pushUniqueId(visualIds, seen, sourceId);
        }
        if (visualIds.length === 0) continue;
        candidate.visualSourceObjectIds = _sortedNumericIds(visualIds);
        candidate.visualSourceBackfilledFromExportSourceIds = true;
    }
    return candidates;
}

function _buildExtractionPlan(doc, ctx, allItems) {
    _marker(ctx.outputDir, "03d01_plan_init");
    var candidates = [];
    var candidateSeen = {};
    var sourceIndex = _buildSourceIndexFromAllItems(doc, ctx, allItems);
    var sourceItems = sourceIndex.sourceItems;
    try { writeJson(ctx.outputDir + "/_source_index_stats.json", sourceIndex.stats || {}); } catch (eSourceIndexStats) {}
    _marker(ctx.outputDir, "03d02_plan_sourceItems");

    _marker(ctx.outputDir, "03d03_plan_childIndex");

    var sourceClusterDiagnostics = _buildSourceClusters(sourceItems);
    var sourceClusterIndex = _createSourceClusterIndex(sourceItems, sourceClusterDiagnostics);
    _marker(ctx.outputDir, "03d04_plan_sourceClusters");
    _appendBaseExtractionCandidates(ctx, sourceItems, sourceIndex, sourceClusterIndex, candidates, candidateSeen);
    _marker(ctx.outputDir, "03d05_plan_baseCandidates");
    _appendEditableTextFrameStyleShellCandidatesFromSourceItems(sourceItems, candidates, candidateSeen);
    _marker(ctx.outputDir, "03d05a_plan_textFrameStyleShellCandidates");

    var planCache = _createExtractionPlanSourceIndexCache(doc, sourceIndex);
    _appendInlineObjectExtractionCandidates(doc, ctx, allItems, candidates, candidateSeen, planCache);
    _marker(ctx.outputDir, "03d06_plan_inlineCandidates");
    _appendSourceDeclaredInlineShellCandidates(ctx, sourceItems, allItems, candidates, candidateSeen, planCache);
    _marker(ctx.outputDir, "03d07_plan_declaredInlineShells");
    _appendSourceDeclaredTextOwningShellGroupCandidates(ctx, sourceItems, allItems, candidates, candidateSeen, planCache);
    _marker(ctx.outputDir, "03d08_plan_textOwningShellGroups");

    _appendMasterCompositeExtractionCandidates(doc, ctx, candidates, candidateSeen, planCache);
    _marker(ctx.outputDir, "03d09_plan_masterComposite");
    candidates = _normalizeExtractionCandidateOwnershipSlots(candidates, sourceItems);
    _marker(ctx.outputDir, "03d10_plan_normalizeSlots");
    _appendEditableTextFrameStyleShellCandidatesFromSourceItems(sourceItems, candidates, candidateSeen);
    _marker(ctx.outputDir, "03d10a_plan_restoreTextFrameStyleShellCandidates");
    candidates = _includeOwnedInlineVisualsInTextlessShellCandidates(candidates, allItems, planCache, sourceItems);
    _marker(ctx.outputDir, "03d11_plan_includeInlineVisuals");
    candidates = _absorbInlineDecorationDescendantsIntoTextShellCandidates(candidates, sourceItems);
    _marker(ctx.outputDir, "03d11a_plan_absorbInlineTextShellDecorationDescendants");
    _appendMultiTextParentGroupExportCandidatesFromSourceItems(sourceItems, candidates, candidateSeen);
    _marker(ctx.outputDir, "03d12_plan_multiTextParentGroups");
    var pageTextlessGraphicGroupDiagnostics =
            _appendPageTextlessGraphicGroupCandidates(candidates, sourceItems, candidateSeen, sourceIndex);
    _marker(ctx.outputDir, "03d12b_plan_pageTextlessGraphicGroups");
    var pageTextlessGraphicGroupMergeDiagnostics =
            _mergeOverlappingPageTextlessGraphicGroupCandidates(candidates, sourceItems);
    candidates = pageTextlessGraphicGroupMergeDiagnostics.candidates;
    _marker(ctx.outputDir, "03d12b1_plan_mergeOverlappingPageTextlessGraphicGroups");
    var crossPageClipParentDecorationSuppressionDiagnostics =
            _suppressCrossPageClipParentSourceSetDecorations(candidates, sourceItems);
    candidates = crossPageClipParentDecorationSuppressionDiagnostics.candidates;
    _marker(ctx.outputDir, "03d12b2_plan_suppressCrossPageClipParentDecorations");
    var pageTextlessGraphicGroupSuppressionDiagnostics =
            _suppressChildExportsCoveredByPageTextlessGraphicGroups(candidates, sourceItems);
    candidates = pageTextlessGraphicGroupSuppressionDiagnostics.candidates;
    _marker(ctx.outputDir, "03d12c_plan_suppressPageTextlessGraphicGroupChildren");
    var preObjectPlanTextlessShellSuppressionDiagnostics =
            _suppressChildExportsCoveredByTextlessGroupCandidates(candidates, sourceItems);
    candidates = preObjectPlanTextlessShellSuppressionDiagnostics.candidates;
    _marker(ctx.outputDir, "03d12a_plan_suppressTextlessGroupChildrenBeforeObjectPlans");
    var sourceClusterQueryDiagnostics = _buildSourceClusterQueryDiagnostics(sourceClusterIndex, candidates);
    _marker(ctx.outputDir, "03d13_plan_clusterQueries");
    candidates = _backfillVisibleCandidateVisualSources(candidates, sourceItems);
    _marker(ctx.outputDir, "03d13a_plan_backfillVisualSources");
    candidates = _normalizePageCoordinateCandidateBounds(candidates, sourceIndex);
    _marker(ctx.outputDir, "03d13b_plan_normalizePageCoordinateBounds");
    var plannerBundleDiagnostics = _buildPlannerBundles(sourceItems, candidates);
    _marker(ctx.outputDir, "03d14_plan_plannerBundles");
    var objectPlanDiagnostics = _buildObjectPlanDiagnosticsFromPlannerBundles(plannerBundleDiagnostics, sourceItems);
    _marker(ctx.outputDir, "03d15_plan_objectPlans");
    var executionCandidates = _buildExecutionCandidatesFromObjectPlans(candidates, objectPlanDiagnostics);
    _marker(ctx.outputDir, "03d16_plan_buildExecutionCandidates");
    executionCandidates = _excludeDirectChildShellSourcesFromParentShellExports(executionCandidates, sourceItems);
    _marker(ctx.outputDir, "03d16a_plan_excludeChildShellSourcesFromParentExports");
    var sourceSlotCanonicalizationDiagnostics = _canonicalizeSourceSlotSubsumedCandidatesWithDiagnostics(
            executionCandidates, sourceItems);
    executionCandidates = sourceSlotCanonicalizationDiagnostics.candidates;
    _marker(ctx.outputDir, "03d16b_plan_canonicalizeSourceSlots");
    var multiTextParentSuppressionDiagnostics = _suppressChildExportsCoveredByTextlessGroupCandidates(
            executionCandidates, sourceItems);
    executionCandidates = multiTextParentSuppressionDiagnostics.candidates;
    _marker(ctx.outputDir, "03d16b2_plan_suppressTextlessGroupChildren");
    var pageTextlessExecutionSuppressionDiagnostics =
            _suppressChildExportsCoveredByPageTextlessGraphicGroups(executionCandidates, sourceItems);
    executionCandidates = pageTextlessExecutionSuppressionDiagnostics.candidates;
    _marker(ctx.outputDir, "03d16b3_plan_suppressPageTextlessGraphicGroupChildren");
    if (sourceSlotCanonicalizationDiagnostics.diagnostics
            && sourceSlotCanonicalizationDiagnostics.diagnostics.suppressedCount > 0
            || multiTextParentSuppressionDiagnostics.suppressedCount > 0
            || pageTextlessExecutionSuppressionDiagnostics.suppressedCount > 0) {
        plannerBundleDiagnostics = _buildPlannerBundles(sourceItems, executionCandidates);
        _marker(ctx.outputDir, "03d16c_plan_rebuildPlannerBundlesAfterSubsumed");
        objectPlanDiagnostics = _buildObjectPlanDiagnosticsFromPlannerBundles(plannerBundleDiagnostics, sourceItems);
        _marker(ctx.outputDir, "03d16d_plan_rebuildObjectPlansAfterSubsumed");
        executionCandidates = _buildExecutionCandidatesFromObjectPlans(executionCandidates, objectPlanDiagnostics);
        _marker(ctx.outputDir, "03d16e_plan_rebuildExecutionCandidatesAfterSubsumed");
        executionCandidates = _excludeDirectChildShellSourcesFromParentShellExports(executionCandidates, sourceItems);
        _marker(ctx.outputDir, "03d16f_plan_excludeChildShellSourcesFromParentExportsAfterRebuild");
        multiTextParentSuppressionDiagnostics = _suppressChildExportsCoveredByTextlessGroupCandidates(
                executionCandidates, sourceItems);
        executionCandidates = multiTextParentSuppressionDiagnostics.candidates;
        _marker(ctx.outputDir, "03d16g_plan_suppressTextlessGroupChildrenAfterRebuild");
        pageTextlessExecutionSuppressionDiagnostics =
                _suppressChildExportsCoveredByPageTextlessGraphicGroups(executionCandidates, sourceItems);
        executionCandidates = pageTextlessExecutionSuppressionDiagnostics.candidates;
        _marker(ctx.outputDir, "03d16g0_plan_suppressPageTextlessGraphicGroupChildrenAfterRebuild");
    }
    executionCandidates = _backfillVisibleCandidateVisualSources(executionCandidates, sourceItems);
    _marker(ctx.outputDir, "03d16g0a_plan_backfillExecutionVisualSources");
    var unclaimedVisibleVectorFallbackDiagnostics =
            _appendUnclaimedVisibleVectorExecutionCandidates(executionCandidates, sourceItems);
    _marker(ctx.outputDir, "03d16g1_plan_warnUnclaimedVisibleVectors");
    var sourceCoverageOptions = {
        fullDiagnostics: ctx.writePlannerDiagnostics === true
    };
    var sourceCoverageDiagnostics = _buildSourceCoverageDiagnostics(
            sourceItems, executionCandidates, objectPlanDiagnostics, sourceCoverageOptions);
    var unresolvedVisibleVectorCoverageDiagnostics =
            _appendUnresolvedVisibleVectorCoverageCandidates(
                    executionCandidates, sourceCoverageDiagnostics, sourceItems);
    _marker(ctx.outputDir, "03d16g6_plan_warnUnresolvedVisibleVectors");
    _marker(ctx.outputDir, "03d16h0_plan_sourceCoverageBuild");
    try { writeJson(ctx.outputDir + "/source-coverage.json", sourceCoverageDiagnostics); } catch (eSourceCoverageWrite) {}
    _marker(ctx.outputDir, "03d16h_plan_sourceCoverage");
    var sourceOwnershipModelDiagnostics = _buildSourceOwnershipModelDiagnostics(
            sourceItems, executionCandidates, objectPlanDiagnostics, {
                fullDiagnostics: ctx.writePlannerDiagnostics === true
            });
    _marker(ctx.outputDir, "03d16i0_plan_sourceOwnershipModelBuild");
    if (ctx.writePlannerDiagnostics === true) {
        try { writeJson(ctx.outputDir + "/source-bundles.json", sourceOwnershipModelDiagnostics.sourceBundles); } catch (eSourceBundlesWrite) {}
        _marker(ctx.outputDir, "03d16i1_plan_writeSourceBundles");
        try { writeJson(ctx.outputDir + "/ownership-slots.json", sourceOwnershipModelDiagnostics.ownershipSlots); } catch (eOwnershipSlotsWrite) {}
        _marker(ctx.outputDir, "03d16i2_plan_writeOwnershipSlots");
        try { writeJson(ctx.outputDir + "/slot-owners.json", sourceOwnershipModelDiagnostics.slotOwners); } catch (eSlotOwnersWrite) {}
        _marker(ctx.outputDir, "03d16i3_plan_writeSlotOwners");
    } else {
        try {
            writeJson(ctx.outputDir + "/source-ownership-model-summary.json", {
                schemaVersion: 1,
                policy: "POLICY-source-ownership",
                mode: "source-ownership-model-summary",
                sourceBundles: sourceOwnershipModelDiagnostics.sourceBundles.summary,
                ownershipSlots: sourceOwnershipModelDiagnostics.ownershipSlots.summary,
                slotOwners: sourceOwnershipModelDiagnostics.slotOwners.summary,
                renderUnits: sourceOwnershipModelDiagnostics.renderUnits.summary,
                summary: sourceOwnershipModelDiagnostics.summary,
                fullDiagnosticsSkipped: true
            });
        } catch (eSourceOwnershipSummaryWrite) {}
        _marker(ctx.outputDir, "03d16i1_plan_writeSourceOwnershipSummary");
    }
    try { writeJson(ctx.outputDir + "/render-units.json", sourceOwnershipModelDiagnostics.renderUnits); } catch (eRenderUnitsWrite) {}
    _marker(ctx.outputDir, "03d16i4_plan_writeRenderUnits");
    _marker(ctx.outputDir, "03d16i_plan_sourceOwnershipModel");
    _attachRenderUnitsToExecutionCandidates(executionCandidates, sourceOwnershipModelDiagnostics);
    _marker(ctx.outputDir, "03d16j_plan_attachRenderUnits");
    var sourceSlotRegistryDiagnostics = _buildSourceSlotRegistryDiagnostics(
            plannerBundleDiagnostics, objectPlanDiagnostics, sourceItems);
    _marker(ctx.outputDir, "03d17_plan_sourceSlotRegistry");
    var executionCandidateContractDiagnostics =
            _buildExecutionCandidateContractDiagnostics(executionCandidates);
    _marker(ctx.outputDir, "03d18_plan_executionCandidateContract");

    var renderDpi = CONFIG && CONFIG.rendering ? CONFIG.rendering.pngExportResolution : null;
    var plan = {
        schemaVersion: 1,
        policy: "POLICY-extraction-planning",
        scriptVersion: EXTRACT_SCRIPT_VERSION,
        mode: "legacy-pass-candidate-plan",
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
            resolvedRangeMode: ctx.resolvedRangeMode,
            physicalRange: !!ctx.physicalRange,
            chunkMode: !!ctx.chunkMode
        },
        renderOptions: {
            perfMode: ctx.perfMode,
            pngExportResolution: renderDpi,
            spreadMode: !!ctx.spreadMode,
            skipPdf: !!ctx.skipPdf
        },
        sourceItems: sourceItems,
        sourceClusterSummary: sourceClusterDiagnostics.summary,
        sourceClusterQuerySummary: sourceClusterQueryDiagnostics.summary,
        plannerBundleSummary: plannerBundleDiagnostics.summary,
        objectPlanSummary: objectPlanDiagnostics.summary,
        sourceCoverageSummary: sourceCoverageDiagnostics.summary,
        sourceOwnershipModelSummary: sourceOwnershipModelDiagnostics.summary,
        renderUnits: sourceOwnershipModelDiagnostics.renderUnits
                ? sourceOwnershipModelDiagnostics.renderUnits.renderUnits || []
                : [],
        preObjectPlanTextlessShellSuppressionSummary: {
            suppressedCount: preObjectPlanTextlessShellSuppressionDiagnostics.suppressedCount,
            suppressed: preObjectPlanTextlessShellSuppressionDiagnostics.suppressed
        },
        crossPageClipParentDecorationSuppressionSummary: {
            suppressedCount: crossPageClipParentDecorationSuppressionDiagnostics.suppressedCount,
            suppressed: crossPageClipParentDecorationSuppressionDiagnostics.suppressed
        },
        pageTextlessGraphicGroupSummary: {
            appendedCount: pageTextlessGraphicGroupDiagnostics.appendedCount,
            componentCount: pageTextlessGraphicGroupDiagnostics.componentCount,
            components: pageTextlessGraphicGroupDiagnostics.components,
            mergedCount: pageTextlessGraphicGroupMergeDiagnostics.mergedCount,
            merged: pageTextlessGraphicGroupMergeDiagnostics.merged,
            preObjectPlanSuppressedCount: pageTextlessGraphicGroupSuppressionDiagnostics.suppressedCount,
            preObjectPlanSuppressed: pageTextlessGraphicGroupSuppressionDiagnostics.suppressed,
            executionSuppressedCount: pageTextlessExecutionSuppressionDiagnostics.suppressedCount,
            executionSuppressed: pageTextlessExecutionSuppressionDiagnostics.suppressed
        },
        visibleVectorOwnershipWarningSummary: {
            unclaimedWarningCount: unclaimedVisibleVectorFallbackDiagnostics.warningCount || 0,
            unclaimedWarnings: unclaimedVisibleVectorFallbackDiagnostics.warnings || [],
            unresolvedWarningCount: unresolvedVisibleVectorCoverageDiagnostics.warningCount || 0,
            unresolvedWarnings: unresolvedVisibleVectorCoverageDiagnostics.warnings || []
        },
        sourceSlotCanonicalizationSummary: sourceSlotCanonicalizationDiagnostics.diagnostics.summary,
        executionCandidateContractSummary: executionCandidateContractDiagnostics.summary,
        legacyNormalizationFilterSummary: _LEGACY_NORMALIZATION_FILTER_DIAGNOSTICS
                ? _LEGACY_NORMALIZATION_FILTER_DIAGNOSTICS.summary
                : null,
        exactShellSlotDuplicateSummary: _EXACT_SHELL_SLOT_DUPLICATE_DIAGNOSTICS
                ? _EXACT_SHELL_SLOT_DUPLICATE_DIAGNOSTICS.summary
                : null,
        sourceSlotRegistrySummary: sourceSlotRegistryDiagnostics.summary,
        candidates: executionCandidates,
        exportPasses: _buildExtractionPasses()
    };
    ctx._extractionPlanDiagnostics = {
        sourceClusterDiagnostics: sourceClusterDiagnostics,
        sourceClusterQueryDiagnostics: sourceClusterQueryDiagnostics,
        plannerBundleDiagnostics: plannerBundleDiagnostics,
        objectPlanDiagnostics: objectPlanDiagnostics,
        sourceCoverageDiagnostics: sourceCoverageDiagnostics,
        sourceOwnershipModelDiagnostics: sourceOwnershipModelDiagnostics,
        preObjectPlanTextlessShellSuppressionDiagnostics:
                preObjectPlanTextlessShellSuppressionDiagnostics,
        pageTextlessGraphicGroupDiagnostics: pageTextlessGraphicGroupDiagnostics,
        pageTextlessGraphicGroupSuppressionDiagnostics:
                pageTextlessGraphicGroupSuppressionDiagnostics,
        pageTextlessExecutionSuppressionDiagnostics:
                pageTextlessExecutionSuppressionDiagnostics,
        visibleVectorOwnershipWarningDiagnostics: {
            unclaimed: unclaimedVisibleVectorFallbackDiagnostics,
            unresolved: unresolvedVisibleVectorCoverageDiagnostics
        },
        sourceSlotCanonicalizationDiagnostics: sourceSlotCanonicalizationDiagnostics.diagnostics,
        executionCandidateContractDiagnostics: executionCandidateContractDiagnostics,
        legacyNormalizationFilterDiagnostics: _LEGACY_NORMALIZATION_FILTER_DIAGNOSTICS,
        exactShellSlotDuplicateDiagnostics: _EXACT_SHELL_SLOT_DUPLICATE_DIAGNOSTICS,
        sourceSlotRegistryDiagnostics: sourceSlotRegistryDiagnostics
    };
    return plan;
}

function _attachRenderUnitsToExecutionCandidates(executionCandidates, sourceOwnershipModelDiagnostics) {
    if (!executionCandidates || !sourceOwnershipModelDiagnostics
            || !sourceOwnershipModelDiagnostics.renderUnits) {
        return;
    }
    var renderUnits = sourceOwnershipModelDiagnostics.renderUnits.renderUnits || [];
    var byCandidateId = {};
    var bySlotIdentityKey = {};
    for (var ri = 0; ri < renderUnits.length; ri++) {
        var unit = renderUnits[ri];
        if (!unit) continue;
        if (unit.candidateId) byCandidateId[String(unit.candidateId)] = unit;
        if (unit.slotIdentityKey) bySlotIdentityKey[String(unit.slotIdentityKey)] = unit;
    }

    function candidateOwnershipSlot(candidate) {
        if (!candidate) return "CONTENT_VISUAL_SLOT";
        if (candidate.ownershipSlot) {
            if (candidate.ownershipSlot === "TEXTLESS_GROUP_VISUAL_SLOT") return "SHELL_SLOT";
            return candidate.ownershipSlot;
        }
        if (candidate.visualAction === "PLACE_TEXT_SHELL") return "SHELL_SLOT";
        if (candidate.visualAction === "PLACE_TABLE_STYLE") return "TABLE_STYLE_SLOT";
        if (candidate.passId === "pass.decoration_groups"
                || candidate.passId === "pass.editable_textframe_visual_shells"
                || candidate.slotRole === "shell_slot_only"
                || candidate.slotRole === "textless_group_visual_slot") {
            return "SHELL_SLOT";
        }
        return "CONTENT_VISUAL_SLOT";
    }

    function candidatePlacement(candidate) {
        if (candidate && candidate.placement) return candidate.placement;
        if (candidate && candidate.passId === "pass.inline_objects") return "INLINE";
        return "FLOATING";
    }

    function candidateSlotIdentityKey(candidate) {
        if (!candidate) return null;
        var ids = candidate.visualSourceObjectIds && candidate.visualSourceObjectIds.length > 0
                ? candidate.visualSourceObjectIds
                : (candidate.exportSourceObjectIds && candidate.exportSourceObjectIds.length > 0
                        ? candidate.exportSourceObjectIds
                        : candidate.sourceObjectIds || []);
        var sourceKey = _sourceSetKey(ids || []);
        if (!sourceKey) return null;
        return String(candidate.pageIndex) + "|"
                + candidatePlacement(candidate) + "|"
                + candidateOwnershipSlot(candidate) + "|"
                + sourceKey;
    }

    for (var ci = 0; ci < executionCandidates.length; ci++) {
        var candidate = executionCandidates[ci];
        if (!candidate || !candidate.candidateId) continue;
        var renderUnit = byCandidateId[String(candidate.candidateId)]
                || bySlotIdentityKey[String(candidateSlotIdentityKey(candidate))];
        if (!renderUnit) continue;
        candidate.renderUnitId = renderUnit.renderUnitId || null;
        candidate.renderUnitSlotIdentityKey = renderUnit.slotIdentityKey || null;
    }
}

function _excludeDirectChildShellSourcesFromParentShellExports(candidates, sourceItems) {
    if (!candidates || candidates.length === 0) return candidates || [];
    var indexes = null;
    try { indexes = _buildSourceItemIndexes(sourceItems || []); } catch (eIndex) { indexes = null; }
    var sourceInfoById = indexes ? indexes.sourceInfoById || {} : {};

    function info(id) {
        return sourceInfoById ? sourceInfoById[String(id)] : null;
    }

    function parentIdOf(id) {
        var src = info(id);
        if (!src) return null;
        return src.parentId !== undefined && src.parentId !== null ? src.parentId : null;
    }

    function isDescendantOf(childId, parentId) {
        if (childId === null || childId === undefined || parentId === null || parentId === undefined) return false;
        var cur = parentIdOf(childId);
        var guard = 0;
        while (cur !== null && cur !== undefined && guard++ < 100) {
            if (String(cur) === String(parentId)) return true;
            cur = parentIdOf(cur);
        }
        return false;
    }

    function ids(candidate, field) {
        return _sortedNumericIds(candidate && candidate[field] || []);
    }

    function visibleExportIds(candidate) {
        var exportIds = ids(candidate, "exportSourceObjectIds");
        if (exportIds.length > 0) return exportIds;
        var visualIds = ids(candidate, "visualSourceObjectIds");
        if (visualIds.length > 0) return visualIds;
        return ids(candidate, "sourceObjectIds");
    }

    function sourceIds(candidate) {
        return ids(candidate, "sourceObjectIds");
    }

    function primaryId(candidate) {
        if (!candidate) return null;
        if (candidate.primarySourceObjectId !== undefined && candidate.primarySourceObjectId !== null) {
            return candidate.primarySourceObjectId;
        }
        var srcIds = sourceIds(candidate);
        return srcIds.length > 0 ? srcIds[0] : null;
    }

    function idSet(values) {
        var set = {};
        for (var i = 0; values && i < values.length; i++) set[String(values[i])] = true;
        return set;
    }

    function containsAll(ownerIds, childIds) {
        if (!childIds || childIds.length === 0) return false;
        var set = idSet(ownerIds || []);
        for (var i = 0; i < childIds.length; i++) {
            if (!set[String(childIds[i])]) return false;
        }
        return true;
    }

    function isParentShell(candidate) {
        return candidate
                && candidate.passId === "pass.decoration_groups"
                && candidate.candidatePurpose === "SHELL_CANDIDATE"
                && candidate.slotRole === "shell_slot_only"
                && candidate.textOwner === "hwpx_tf";
    }

    function isDirectChildShell(candidate) {
        return candidate
                && candidate.passId === "pass.decoration_groups"
                && candidate.candidatePurpose === "SHELL_CANDIDATE"
                && (candidate.slotRole === "direct_child_shell_slot"
                    || candidate.compositeRole === "direct_child_shell_slot");
    }

    function copyCandidate(candidate) {
        var copy = {};
        for (var key in candidate) {
            if (!candidate.hasOwnProperty || !candidate.hasOwnProperty(key)) continue;
            var value = candidate[key];
            copy[key] = value && value.constructor === Array ? value.slice(0) : value;
        }
        return copy;
    }

    var childShellsByPage = {};
    for (var ci = 0; ci < candidates.length; ci++) {
        var child = candidates[ci];
        if (!isDirectChildShell(child)) continue;
        var pageKey = String(child.pageIndex);
        if (!childShellsByPage[pageKey]) childShellsByPage[pageKey] = [];
        childShellsByPage[pageKey].push({
            candidate: child,
            primaryId: primaryId(child),
            exportIds: visibleExportIds(child)
        });
    }

    var out = [];
    for (var pi = 0; pi < candidates.length; pi++) {
        var parent = candidates[pi];
        if (!isParentShell(parent)) {
            out.push(parent);
            continue;
        }
        var parentPrimaryId = primaryId(parent);
        var parentExportIds = visibleExportIds(parent);
        var childShells = childShellsByPage[String(parent.pageIndex)] || [];
        var removeIds = [];
        var removeSeen = {};
        for (var si = 0; si < childShells.length; si++) {
            var childEntry = childShells[si];
            if (String(childEntry.primaryId) === String(parentPrimaryId)) continue;
            if (!isDescendantOf(childEntry.primaryId, parentPrimaryId)) continue;
            if (!containsAll(parentExportIds, childEntry.exportIds)) continue;
            for (var ri = 0; ri < childEntry.exportIds.length; ri++) {
                _pushUniqueId(removeIds, removeSeen, childEntry.exportIds[ri]);
            }
        }
        if (removeIds.length === 0) {
            out.push(parent);
            continue;
        }

        var pruned = copyCandidate(parent);
        var nextExportIds = _removeSourceIds(pruned.exportSourceObjectIds || [], removeIds);
        if ((pruned.exportSourceObjectIds || []).length > 0 && nextExportIds.length === 0) {
            out.push(parent);
            continue;
        }
        pruned.exportSourceObjectIds = nextExportIds;
        pruned.visualSourceObjectIds = _removeSourceIds(pruned.visualSourceObjectIds || [], removeIds);
        pruned.styleSourceObjectIds = _removeSourceIds(pruned.styleSourceObjectIds || [], removeIds);
        pruned.reason = "parent_shell_export_excludes_child_shell_sources";
        out.push(pruned);
    }
    return out;
}
