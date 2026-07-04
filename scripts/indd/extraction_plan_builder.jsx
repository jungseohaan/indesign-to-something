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
                    var inlineCompleteMarkerOwnsText = _inlineCompleteMarkerDecisionForOwnership(inItem, inlineEditableTextFrameIds);
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
                        _inlineCompleteMarkerDecisionForOwnership(nativeInlineShell, nativeInlineEditableTextFrameIds);
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
    var preObjectPlanTextlessShellSuppressionDiagnostics =
            _suppressChildExportsCoveredByTextlessGroupCandidates(candidates, sourceItems);
    candidates = preObjectPlanTextlessShellSuppressionDiagnostics.candidates;
    _marker(ctx.outputDir, "03d12a_plan_suppressTextlessGroupChildrenBeforeObjectPlans");
    var sourceClusterQueryDiagnostics = _buildSourceClusterQueryDiagnostics(sourceClusterIndex, candidates);
    _marker(ctx.outputDir, "03d13_plan_clusterQueries");
    var plannerBundleDiagnostics = _buildPlannerBundles(sourceItems, candidates);
    _marker(ctx.outputDir, "03d14_plan_plannerBundles");
    var objectPlanDiagnostics = _buildObjectPlanDiagnosticsFromPlannerBundles(plannerBundleDiagnostics);
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
    if (sourceSlotCanonicalizationDiagnostics.diagnostics
            && sourceSlotCanonicalizationDiagnostics.diagnostics.suppressedCount > 0
            || multiTextParentSuppressionDiagnostics.suppressedCount > 0) {
        plannerBundleDiagnostics = _buildPlannerBundles(sourceItems, executionCandidates);
        _marker(ctx.outputDir, "03d16c_plan_rebuildPlannerBundlesAfterSubsumed");
        objectPlanDiagnostics = _buildObjectPlanDiagnosticsFromPlannerBundles(plannerBundleDiagnostics);
        _marker(ctx.outputDir, "03d16d_plan_rebuildObjectPlansAfterSubsumed");
        executionCandidates = _buildExecutionCandidatesFromObjectPlans(executionCandidates, objectPlanDiagnostics);
        _marker(ctx.outputDir, "03d16e_plan_rebuildExecutionCandidatesAfterSubsumed");
        executionCandidates = _excludeDirectChildShellSourcesFromParentShellExports(executionCandidates, sourceItems);
        _marker(ctx.outputDir, "03d16f_plan_excludeChildShellSourcesFromParentExportsAfterRebuild");
        multiTextParentSuppressionDiagnostics = _suppressChildExportsCoveredByTextlessGroupCandidates(
                executionCandidates, sourceItems);
        executionCandidates = multiTextParentSuppressionDiagnostics.candidates;
        _marker(ctx.outputDir, "03d16g_plan_suppressTextlessGroupChildrenAfterRebuild");
    }
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
        preObjectPlanTextlessShellSuppressionSummary: {
            suppressedCount: preObjectPlanTextlessShellSuppressionDiagnostics.suppressedCount,
            suppressed: preObjectPlanTextlessShellSuppressionDiagnostics.suppressed
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
        preObjectPlanTextlessShellSuppressionDiagnostics:
                preObjectPlanTextlessShellSuppressionDiagnostics,
        sourceSlotCanonicalizationDiagnostics: sourceSlotCanonicalizationDiagnostics.diagnostics,
        executionCandidateContractDiagnostics: executionCandidateContractDiagnostics,
        legacyNormalizationFilterDiagnostics: _LEGACY_NORMALIZATION_FILTER_DIAGNOSTICS,
        exactShellSlotDuplicateDiagnostics: _EXACT_SHELL_SLOT_DUPLICATE_DIAGNOSTICS,
        sourceSlotRegistryDiagnostics: sourceSlotRegistryDiagnostics
    };
    return plan;
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
