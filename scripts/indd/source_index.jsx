/*
 * SourceIndex for extract_indd.jsx.
 *
 * Stage 0 owns InDesign DOM reads. Planner code should query this index
 * instead of repeatedly walking item.allPageItems or page/spread DOM.
 */

function _itemKind(item) {
    try { return item && item.constructor ? item.constructor.name : null; } catch (e) { return null; }
}

function _itemId(item) {
    try { return item && item.id !== undefined ? item.id : null; } catch (e) { return null; }
}

function _itemParentId(item) {
    try {
        if (item && item.parent && item.parent.id !== undefined) return item.parent.id;
    } catch (e) {}
    return null;
}

function _itemParentKind(item) {
    try { return item && item.parent && item.parent.constructor ? item.parent.constructor.name : null; } catch (e) { return null; }
}

function isInlineItem(item) {
    try {
        var cur = item.parent;
        while (cur) {
            var pn = cur.constructor.name;
            if (pn === "TextFrame" || pn === "Character"
                || pn === "InsertionPoint" || pn === "Cell"
                || pn === "Story") return true;
            if (pn === "Spread" || pn === "Page" || pn === "Document") return false;
            try { cur = cur.parent; } catch (e) { break; }
        }
    } catch (e) {}
    return false;
}

/**
 * resolved 수집에서 editable TextFrame 복원/수집 기준으로 사용하는 id map.
 */

function _itemAnchoredPosition(item) {
    if (!item) return null;
    var settings = null;
    try {
        settings = item.anchoredObjectSettings;
    } catch (eSettings) {
        return null;
    }
    if (!settings) return null;
    try {
        var pos = settings.anchoredPosition;
        if (pos === null || pos === undefined) return null;
        return String(pos).replace(/^AnchorPosition\\./, "");
    } catch (ePosition) {
        return null;
    }
}

function _storyTextContainerForItem(doc, item, parentStory) {
    try {
        var story = parentStory || null;
        if (!story && item) {
            try { story = item.parentStory; } catch (eItemStory) {}
        }
        if (!story) return null;
        try {
            if (story.textContainers && story.textContainers.length > 0) {
                return story.textContainers[0];
            }
        } catch (eContainers) {}
    } catch (e) {}
    return null;
}

function _textFrameContentBounds(tf) {
    try {
        if (!tf || !tf.geometricBounds) return null;
        var gb = tf.geometricBounds;
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
            Number(gb[0]) + inset[0],
            Number(gb[1]) + inset[1],
            Number(gb[2]) - inset[2],
            Number(gb[3]) - inset[3]
        ];
    } catch (e) {
        return null;
    }
}

function _storyAnchorPlacementForItem(doc, item, parentStory) {
    if (!item) return null;
    if (!isInlineItem(item)) return "PAGE";
    var anchoredPosition = _itemAnchoredPosition(item);
    if (anchoredPosition && String(anchoredPosition).toUpperCase() === "ANCHORED") {
        return "FLOATING_ANCHORED";
    }
    var parentKind = _itemParentKind(item);
    if (parentKind !== "Character" && parentKind !== "InsertionPoint") {
        return "INLINE";
    }

    var itemBounds = _itemBounds(item);
    var carrier = _storyTextContainerForItem(doc, item, parentStory);
    var contentBounds = _textFrameContentBounds(carrier);
    if (!itemBounds || !contentBounds) return "INLINE";

    // Some InDesign objects are anchored to a story character only as a stable
    // source reference, while their resolved page bounds live outside the text
    // container. Those are page-space anchored visuals, not text-flow inline
    // objects. Decide this at source metadata time so later stages execute only
    // the declared placement.
    var eps = 0.5;
    if (itemBounds[3] <= contentBounds[1] - eps
            || itemBounds[1] >= contentBounds[3] + eps
            || itemBounds[2] <= contentBounds[0] - eps
            || itemBounds[0] >= contentBounds[2] + eps) {
        return "FLOATING_ANCHORED";
    }
    return "INLINE";
}

function _isInlineFlowItemBySourceInfo(sourceInfo) {
    if (!sourceInfo) return false;
    var placement = String(sourceInfo.storyAnchorPlacement || "");
    if (placement !== "INLINE") return false;
    return String(sourceInfo.parentKind || "") === "Character"
            || String(sourceInfo.parentKind || "") === "InsertionPoint";
}

function _isClipCarryingShapeKind(kind) {
    return kind === "Oval" || kind === "Rectangle" || kind === "Polygon";
}

function _itemBounds(item) {
    try {
        var b = item.visibleBounds || item.geometricBounds;
        if (!b || b.length < 4) return null;
        return [Number(b[0]), Number(b[1]), Number(b[2]), Number(b[3])];
    } catch (e) {
        return null;
    }
}

function _itemLayerName(item) {
    try { return item && item.itemLayer ? String(item.itemLayer.name) : null; } catch (e) { return null; }
}

function _itemVisible(item) {
    try { return item && item.visible !== false; } catch (e) { return null; }
}

function _pageIndexOfItem(doc, item) {
    try {
        var pg = _resolveParentPage(item, doc);
        if (pg) return pg.documentOffset;
    } catch (e) {}
    return -1;
}

function _pageIndexBySpreadBounds(doc, item, ctx) {
    var itemBounds = _itemBounds(item);
    if (!itemBounds) return -1;
    var itemSpreadId = null;
    try {
        var cur = item.parent;
        var hop = 0;
        while (cur && hop < 10) {
            var kind = "";
            try { kind = cur.constructor.name; } catch (eKind) {}
            if (kind === "Spread") {
                itemSpreadId = cur.id;
                break;
            }
            if (kind === "MasterSpread" || kind === "Document") break;
            try { cur = cur.parent; } catch (eParent) { break; }
            hop++;
        }
    } catch (eSpread) {}
    var start = ctx && ctx.startPage ? ctx.startPage - 1 : 0;
    var end = ctx && ctx.endPage ? ctx.endPage - 1 : (doc.pages.length - 1);
    for (var pageIndex = start; pageIndex <= end && pageIndex < doc.pages.length; pageIndex++) {
        if (itemSpreadId !== null) {
            var pageSpreadId = _pageSpreadIdForIndex(doc, pageIndex);
            if (pageSpreadId !== itemSpreadId) continue;
        }
        var pageBounds = _pageBoundsForIndex(doc, pageIndex);
        if (_boundsOverlap(pageBounds, itemBounds)) return pageIndex;
    }
    return -1;
}

function _textLengthOfItem(item) {
    try {
        if (!item || _itemKind(item) !== "TextFrame") return null;
        var s = String(item.contents || "");
        return s.replace(/\uFFFC/g, "").replace(/\u0016/g, "").replace(/\u0018/g, "").length;
    } catch (e) {
        return null;
    }
}

function _plainTextOfTextFrameForOwnership(item) {
    var text = "";
    try { text = item && item.contents ? item.contents : ""; } catch (eContents) {}
    if (!text) {
        try {
            if (item && item.parentStory) text = item.parentStory.contents || "";
        } catch (eStoryContents) {}
    }
    return String(text || "").replace(/[\s\r\n\t\u0016\u0018\u0003\uFFFC]/g, "");
}

function _storyTableSourceObjectIds(story) {
    var ids = [];
    try {
        if (!story || !story.tables) return ids;
        var tables = story.tables.everyItem().getElements();
        for (var i = 0; tables && i < tables.length; i++) {
            try {
                if (tables[i] && tables[i].id !== undefined && tables[i].id !== null) {
                    ids.push(Number(tables[i].id));
                }
            } catch (eTableId) {}
        }
    } catch (eEveryTable) {
        try {
            for (var t = 0; story && story.tables && t < story.tables.length; t++) {
                try {
                    if (story.tables[t] && story.tables[t].id !== undefined
                            && story.tables[t].id !== null) {
                        ids.push(Number(story.tables[t].id));
                    }
                } catch (eTableIndexId) {}
            }
        } catch (eTableLoop) {}
    }
    return ids;
}

function _storyHasVisibleTableCellText(story) {
    try {
        if (!story || !story.tables) return false;
        var tables = story.tables.everyItem().getElements();
        for (var ti = 0; tables && ti < tables.length; ti++) {
            var cells = null;
            try { cells = tables[ti].cells.everyItem().getElements(); } catch (eCells) {}
            for (var ci = 0; cells && ci < cells.length; ci++) {
                var text = "";
                try { text = String(cells[ci].texts[0].contents || ""); } catch (eText0) {}
                if (!text) {
                    try { text = String(cells[ci].contents || ""); } catch (eCellContents) {}
                }
                if (text.replace(/[\s\r\n\t\u0016\u0018\u0003\uFFFC\uFEFF]/g, "").length > 0) {
                    return true;
                }
            }
        }
    } catch (eTableCellText) {}
    return false;
}

function _buildSourceIndexFromAllItems(doc, ctx, allItems) {
    var sourceItems = [];
    var sourceInfoById = {};
    var domById = {};
    var childIdsByParentId = {};
    var pageBoundsByIndex = {};
    var pageSpreadByIndex = {};
    var sameSpreadByKey = {};
    var descendantIdsById = {};
    var pageLocalSourceIdsByKey = {};
    var placedVisualSubtreeById = {};
    var placedVisualSourceIdsById = {};
    var candidatePageIndexesById = {};
    var textFrameIdsByKey = {};
    var editableTextOutsideByKey = {};
    var candidateVectorPaintById = {};
    var stats = {
        itemCount: allItems ? allItems.length : 0,
        kindCounts: {}
    };

    function itemId(item) {
        try { return item && item.id !== undefined ? item.id : null; } catch (e) {}
        return null;
    }

    function parentStoryOfItem(item) {
        try {
            if (item && item.parentStory) return item.parentStory;
        } catch (eSelfStory) {}
        try {
            var cur = item ? item.parent : null;
            while (cur) {
                try {
                    if (cur.parentStory) return cur.parentStory;
                } catch (eCurStory) {}
                try {
                    if (cur.constructor && cur.constructor.name === "Story") return cur;
                } catch (eStoryCtor) {}
                var parentName = "";
                try { parentName = cur.constructor.name; } catch (eParentName) {}
                if (parentName === "Spread" || parentName === "Page" || parentName === "Document") break;
                try { cur = cur.parent; } catch (eParent) { break; }
            }
        } catch (eParentStory) {}
        return null;
    }

    function readItemInfo(item) {
        var id = itemId(item);
        if (id === null || id === undefined) return null;
        var key = String(id);
        if (sourceInfoById[key]) return sourceInfoById[key];

        var kind = _itemKind(item);
        stats.kindCounts[kind || "Unknown"] = (stats.kindCounts[kind || "Unknown"] || 0) + 1;
        var textFrameClass = null;
        var textLength = null;
        var rawContents = null;
        var markerOnlyContents = null;
        var storyId = null;
        var tableCountInStory = null;
        var tableSourceObjectIds = [];
        var storyHasVisibleTableCellText = null;
        var parentStory = null;
        try { parentStory = parentStoryOfItem(item); } catch (eParentStoryLookup) {}
        if (parentStory) {
            try { if (parentStory.id !== undefined) storyId = parentStory.id; } catch (eStoryId) {}
        }
        if (kind === "TextFrame") {
            try { textFrameClass = classifyTextFrameCached(item); } catch (eClass) { textFrameClass = null; }
            textLength = _textLengthOfItem(item);
            try {
                rawContents = String(item.contents || "");
                markerOnlyContents = rawContents.replace(/[\s\r\n\t\u0016\u0018\u0003\uFFFC\uFEFF]/g, "").length === 0
                        && /[\u0016\u0018\u0003\uFFFC\uFEFF]/.test(rawContents);
            } catch (eRawContents) {
                rawContents = null;
                markerOnlyContents = null;
            }
            try {
                if (parentStory && parentStory.tables) {
                    if (parentStory.tables.length !== undefined && parentStory.tables.length !== null) {
                        tableCountInStory = Number(parentStory.tables.length || 0);
                    } else if (parentStory.tables.count) {
                        tableCountInStory = Number(parentStory.tables.count() || 0);
                    }
                }
                tableSourceObjectIds = _storyTableSourceObjectIds(parentStory);
                storyHasVisibleTableCellText = _storyHasVisibleTableCellText(parentStory);
            } catch (eStoryMeta) {
                tableCountInStory = tableCountInStory === null ? 0 : tableCountInStory;
                storyHasVisibleTableCellText = storyHasVisibleTableCellText === null
                        ? false
                        : storyHasVisibleTableCellText;
            }
        }

        var info = {
            id: id,
            pageIndex: _pageIndexOfItem(doc, item),
            kind: kind,
            parentId: _itemParentId(item),
            parentKind: _itemParentKind(item),
            bounds: _itemBounds(item),
            sourceOrder: sourceItems.length,
            zOrder: sourceItems.length,
            layerName: _itemLayerName(item),
            visible: _itemVisible(item),
            hiddenLayer: false,
            nonprinting: false,
            textFrameClass: textFrameClass,
            textLength: textLength,
            hasText: textLength !== null ? textLength > 0 : null,
            markerOnlyContents: markerOnlyContents,
            storyId: storyId,
            tableCountInStory: tableCountInStory,
            hasTablesInStory: tableCountInStory !== null ? tableCountInStory > 0 : null,
            tableSourceObjectIds: tableSourceObjectIds,
            storyHasVisibleTableCellText: storyHasVisibleTableCellText,
            hasChildren: false,
            hasPlacedVisual: false,
            hasCandidateVectorPaint: null,
            hasVisibleFill: false,
            hasVisibleStroke: false,
            fillColor: null,
            fillColorName: null,
            fillTint: null,
            strokeColor: null,
            strokeColorName: null,
            strokeTint: null,
            strokeWeight: null,
            cornerRadius: null,
            anchoredPosition: _itemAnchoredPosition(item),
            storyAnchorPlacement: _storyAnchorPlacementForItem(doc, item, parentStory)
        };

        try { info.hiddenLayer = isOnHiddenLayer(item); } catch (eHidden) {}
        try { info.nonprinting = !!item.nonprinting; } catch (eNonprinting) {}
        if (kind === "Rectangle" || kind === "Oval" || kind === "Polygon") {
            try { info.hasPlacedVisual = _hasPlacedVisual(item); } catch (ePlaced) {}
        }
        try {
            if (item.fillColor && item.fillColor.name !== "None" && item.fillColor.name !== "[None]") {
                info.fillColor = item.fillColor.name;
                info.fillColorName = item.fillColor.name;
                try { info.fillTint = item.fillTint; } catch (eFillTint) {}
                info.hasVisibleFill = true;
            }
        } catch (eFill) {}
        try {
            if (item.strokeColor && item.strokeColor.name !== "None" && item.strokeColor.name !== "[None]") {
                info.strokeColor = item.strokeColor.name;
                info.strokeColorName = item.strokeColor.name;
                try { info.strokeTint = item.strokeTint; } catch (eStrokeTint) {}
                try { info.strokeWeight = item.strokeWeight || 0; } catch (eStrokeWeight) {}
                info.hasVisibleStroke = Number(info.strokeWeight || 0) > 0;
            }
        } catch (eStroke) {}
        try {
            if (item.cornerRadius > 0) info.cornerRadius = item.cornerRadius;
        } catch (eCorner) {}

        sourceInfoById[key] = info;
        domById[key] = item;
        sourceItems.push(info);
        return info;
    }

    for (var i = 0; allItems && i < allItems.length; i++) {
        try { readItemInfo(allItems[i]); } catch (eReadInfo) {}
    }
    normalizeSourceItemZOrder(sourceItems, ctx);
    stats.sourceZOrderSummary = ctx && ctx.sourceZOrderSummary
            ? ctx.sourceZOrderSummary
            : {
                sourceItemCount: sourceItems.length,
                idmlZOrderAvailable: false,
                idmlZOrderAppliedCount: 0,
                idmlZOrderMissingCount: sourceItems.length
            };

    for (var si = 0; si < sourceItems.length; si++) {
        var src = sourceItems[si];
        if (!src || src.parentId === null || src.parentId === undefined) continue;
        var parentKey = String(src.parentId);
        if (!childIdsByParentId[parentKey]) childIdsByParentId[parentKey] = [];
        childIdsByParentId[parentKey].push(src.id);
    }
    for (var hi = 0; hi < sourceItems.length; hi++) {
        var hasChildInfo = sourceItems[hi];
        hasChildInfo.hasChildren = !!childIdsByParentId[String(hasChildInfo.id)];
    }

    function sourceInfo(sourceId) {
        if (sourceId === null || sourceId === undefined) return null;
        return sourceInfoById[String(sourceId)] || null;
    }

    function domItem(sourceId) {
        if (sourceId === null || sourceId === undefined) return null;
        return domById[String(sourceId)] || null;
    }

    function childIds(sourceId) {
        if (sourceId === null || sourceId === undefined) return [];
        var ids = childIdsByParentId[String(sourceId)] || [];
        return ids.slice(0);
    }

    function pageBounds(pageIndex) {
        var key = String(pageIndex);
        if (pageBoundsByIndex[key]) return pageBoundsByIndex[key];
        var b = _pageBoundsForIndex(doc, pageIndex);
        pageBoundsByIndex[key] = b;
        return b;
    }

    function pageSpreadId(pageIndex) {
        var key = String(pageIndex);
        if (pageSpreadByIndex[key] !== undefined) return pageSpreadByIndex[key];
        var spreadId = _pageSpreadIdForIndex(doc, pageIndex);
        pageSpreadByIndex[key] = spreadId;
        return spreadId;
    }

    function sameSpread(a, b) {
        if (a === null || a === undefined || b === null || b === undefined) return false;
        if (a < 0 || b < 0) return false;
        var key = String(a) + "|" + String(b);
        if (sameSpreadByKey[key] !== undefined) return sameSpreadByKey[key];
        var spreadA = pageSpreadId(a);
        var spreadB = pageSpreadId(b);
        var same = spreadA !== null && spreadB !== null && spreadA === spreadB;
        sameSpreadByKey[key] = same;
        sameSpreadByKey[String(b) + "|" + String(a)] = same;
        return same;
    }

    function descendantSourceObjectIds(sourceId) {
        if (sourceId === null || sourceId === undefined) return [];
        var key = String(sourceId);
        if (descendantIdsById[key]) return descendantIdsById[key].slice(0);
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
        out = _sortedNumericIds(out);
        descendantIdsById[key] = out.slice(0);
        return out;
    }

    function sourceObjectIds(sourceId) {
        return descendantSourceObjectIds(sourceId);
    }

    function includeSourceOnPage(info, pageIndex) {
        if (!info) return false;
        if (info.pageIndex < 0) return true;
        if (info.pageIndex === pageIndex) return true;
        if (sameSpread(info.pageIndex, pageIndex) && _boundsOverlap(pageBounds(pageIndex), info.bounds)) return true;
        return false;
    }

    function sourceIsInPagePlane(sourceId, pageIndex) {
        return includeSourceOnPage(sourceInfo(sourceId), pageIndex);
    }

    function pageLocalSourceObjectIds(sourceId, pageIndex) {
        var key = String(sourceId) + "|" + String(pageIndex);
        if (pageLocalSourceIdsByKey[key]) return pageLocalSourceIdsByKey[key].slice(0);
        var ids = [];
        var seen = {};
        var subtree = descendantSourceObjectIds(sourceId);
        for (var i = 0; i < subtree.length; i++) {
            var info = sourceInfo(subtree[i]);
            if (includeSourceOnPage(info, pageIndex)) _pushUniqueId(ids, seen, subtree[i]);
        }
        if (ids.length === 0) ids = subtree.slice(0);
        ids = _sortedNumericIds(ids);
        pageLocalSourceIdsByKey[key] = ids.slice(0);
        return ids;
    }

    function hasPlacedVisual(sourceId) {
        var info = sourceInfo(sourceId);
        return !!(info && info.hasPlacedVisual);
    }

    function hasCandidateVectorPaint(sourceId) {
        var info = sourceInfo(sourceId);
        if (!info) return false;
        var kind = info.kind;
        if (!(kind === "Rectangle" || kind === "Oval"
                || kind === "Polygon" || kind === "GraphicLine")) {
            return false;
        }
        var key = String(sourceId);
        if (candidateVectorPaintById[key] !== undefined) return candidateVectorPaintById[key];
        var result = false;
        try {
            var item = domItem(sourceId);
            result = item ? _hasCandidateVectorPaint(item) === true : false;
        } catch (eVector) {
            result = false;
        }
        candidateVectorPaintById[key] = result;
        info.hasCandidateVectorPaint = result;
        return result;
    }

    function hasPlacedVisualInSubtree(sourceId) {
        if (sourceId === null || sourceId === undefined) return false;
        var key = String(sourceId);
        if (placedVisualSubtreeById[key] !== undefined) return placedVisualSubtreeById[key];
        var info = sourceInfo(sourceId);
        var kind = info ? String(info.kind || "") : "";
        if (kind === "Image" || kind === "PDF") {
            placedVisualSubtreeById[key] = true;
            return true;
        }
        if (hasPlacedVisual(sourceId)) {
            placedVisualSubtreeById[key] = true;
            return true;
        }
        var children = childIdsByParentId[key] || [];
        for (var i = 0; i < children.length; i++) {
            if (hasPlacedVisualInSubtree(children[i])) {
                placedVisualSubtreeById[key] = true;
                return true;
            }
        }
        placedVisualSubtreeById[key] = false;
        return false;
    }

    function placedVisualSourceObjectIdsInSubtree(sourceId) {
        if (sourceId === null || sourceId === undefined) return [];
        var key = String(sourceId);
        if (placedVisualSourceIdsById[key]) return placedVisualSourceIdsById[key].slice(0);
        var ids = [];
        var seen = {};

        function appendSubtree(id) {
            if (id === null || id === undefined || seen[String(id)]) return;
            seen[String(id)] = true;
            ids.push(id);
            var children = childIdsByParentId[String(id)] || [];
            for (var ci = 0; ci < children.length; ci++) appendSubtree(children[ci]);
        }

        function visit(id) {
            var info = sourceInfo(id);
            if (!info) return;
            var kind = String(info.kind || "");
            if (kind === "Image" || kind === "PDF" || hasPlacedVisual(id)) {
                appendSubtree(id);
                return;
            }
            var children = childIdsByParentId[String(id)] || [];
            for (var ci = 0; ci < children.length; ci++) visit(children[ci]);
        }

        visit(sourceId);
        ids = _sortedNumericIds(ids);
        placedVisualSourceIdsById[key] = ids.slice(0);
        return ids;
    }

    function textFrameIdsInSubtree(sourceId, editableOnly, requireContent) {
        var key = String(sourceId) + "|" + (editableOnly ? "e" : "a") + "|" + (requireContent ? "c" : "n");
        if (textFrameIdsByKey[key]) return textFrameIdsByKey[key].slice(0);
        var ids = [];
        var seen = {};
        function visit(id) {
            var info = sourceInfo(id);
            if (info && info.kind === "TextFrame"
                    && !(requireContent && info.hasText !== true)
                    && !(editableOnly && info.textFrameClass !== "editable")) {
                _pushUniqueId(ids, seen, info.id);
            }
            var children = childIdsByParentId[String(id)] || [];
            for (var ci = 0; ci < children.length; ci++) visit(children[ci]);
        }
        visit(sourceId);
        ids = _sortedNumericIds(ids);
        textFrameIdsByKey[key] = ids.slice(0);
        return ids;
    }

    function hasEditableTextDescendantOutsideSubtree(rootId, subtreeRootId) {
        var key = String(rootId) + "|" + String(subtreeRootId);
        if (editableTextOutsideByKey[key] !== undefined) return editableTextOutsideByKey[key];
        var subtreeSet = _idSetForArray(descendantSourceObjectIds(subtreeRootId));
        var rootTextIds = textFrameIdsInSubtree(rootId, true, true);
        var result = false;
        for (var i = 0; i < rootTextIds.length; i++) {
            if (!subtreeSet[String(rootTextIds[i])]) {
                result = true;
                break;
            }
        }
        editableTextOutsideByKey[key] = result;
        return result;
    }

    function candidatePageIndexes(sourceId) {
        var candidateKey = String(sourceId);
        if (candidatePageIndexesById[candidateKey]) return candidatePageIndexesById[candidateKey].slice(0);
        var info = sourceInfo(sourceId);
        if (!info) return [];
        var sourcePageIndex = info.pageIndex;
        if (info.kind === "TextFrame") {
            var textPages = _candidatePageInRange(sourcePageIndex, ctx) ? [sourcePageIndex] : [];
            candidatePageIndexesById[candidateKey] = textPages.slice(0);
            return textPages;
        }
        if (!info.bounds) {
            var noBoundsPages = _candidatePageInRange(sourcePageIndex, ctx) ? [sourcePageIndex] : [];
            candidatePageIndexesById[candidateKey] = noBoundsPages.slice(0);
            return noBoundsPages;
        }
        var pages = [];
        var seen = {};
        for (var pageIndex = ctx.startPage - 1; pageIndex <= ctx.endPage - 1; pageIndex++) {
            if (sourcePageIndex >= 0 && !sameSpread(sourcePageIndex, pageIndex)) continue;
            if (_boundsOverlap(pageBounds(pageIndex), info.bounds) && !seen[pageIndex]) {
                pages.push(pageIndex);
                seen[pageIndex] = true;
            }
        }
        if (pages.length === 0 && _candidatePageInRange(sourcePageIndex, ctx)) pages.push(sourcePageIndex);
        candidatePageIndexesById[candidateKey] = pages.slice(0);
        return pages;
    }

    function clipCarryingParentIdOfSource(sourceId) {
        var info = sourceInfo(sourceId);
        if (!info || info.parentId === null || info.parentId === undefined) return null;
        var parent = sourceInfo(info.parentId);
        for (var depth = 0; depth < 16 && parent; depth++) {
            if (_isClipCarryingShapeKind(parent.kind)) {
                return parent.hasChildren ? parent.id : null;
            }
            if (String(parent.kind || "") !== "Group") return null;
            if (parent.parentId === null || parent.parentId === undefined) return null;
            parent = sourceInfo(parent.parentId);
        }
        return null;
    }

    return {
        sourceItems: sourceItems,
        sourceInfoById: sourceInfoById,
        childIdsByParentId: childIdsByParentId,
        domById: domById,
        sourceInfo: sourceInfo,
        domItem: domItem,
        childIds: childIds,
        descendantSourceObjectIds: descendantSourceObjectIds,
        sourceObjectIds: sourceObjectIds,
        sourceIsInPagePlane: sourceIsInPagePlane,
        pageLocalSourceObjectIds: pageLocalSourceObjectIds,
        hasPlacedVisual: hasPlacedVisual,
        hasCandidateVectorPaint: hasCandidateVectorPaint,
        hasPlacedVisualInSubtree: hasPlacedVisualInSubtree,
        placedVisualSourceObjectIdsInSubtree: placedVisualSourceObjectIdsInSubtree,
        textFrameIdsInSubtree: textFrameIdsInSubtree,
        hasEditableTextDescendantOutsideSubtree: hasEditableTextDescendantOutsideSubtree,
        candidatePageIndexes: candidatePageIndexes,
        clipCarryingParentIdOfSource: clipCarryingParentIdOfSource,
        sameSpread: sameSpread,
        pageBounds: pageBounds,
        stats: stats
    };
}

function normalizeSourceItemZOrder(sourceItems, ctx) {
    if (!sourceItems || sourceItems.length === 0) return;
    var idmlZOrderById = ctx && ctx.idmlZOrderBySourceObjectId
            ? ctx.idmlZOrderBySourceObjectId
            : null;
    var applied = 0;
    var missing = 0;
    for (var i = 0; i < sourceItems.length; i++) {
        var item = sourceItems[i];
        if (!item) continue;
        item.rawZOrder = item.zOrder;
        var idmlZ = idmlZOrderById ? idmlZOrderById[String(item.id)] : null;
        if (idmlZ !== null && idmlZ !== undefined) {
            item.zOrder = Number(idmlZ);
            item.zOrderSource = "idml_spread";
            applied++;
        } else {
            item.zOrder = Number(item.rawZOrder || 0);
            item.zOrderSource = "dom_allPageItems";
            if (idmlZOrderById) missing++;
        }
    }
    if (ctx) {
        ctx.sourceZOrderSummary = {
            sourceItemCount: sourceItems.length,
            idmlZOrderAvailable: !!idmlZOrderById,
            idmlZOrderAppliedCount: applied,
            idmlZOrderMissingCount: missing
        };
    }
}

function _sourceHasEditableTextDescendantInIndex(sourceId, sourceInfoById, childIdsByParentId, cache) {
    if (sourceId === null || sourceId === undefined) return false;
    var key = String(sourceId);
    cache = cache || {};
    if (cache.hasOwnProperty(key)) return cache[key];
    var src = sourceInfoById ? sourceInfoById[key] : null;
    if (!src) {
        cache[key] = false;
        return false;
    }
    if (src.kind === "TextFrame"
            && src.textFrameClass === "editable"
            && src.hasText === true) {
        cache[key] = true;
        return true;
    }
    var children = childIdsByParentId ? (childIdsByParentId[key] || []) : [];
    for (var i = 0; i < children.length; i++) {
        if (_sourceHasEditableTextDescendantInIndex(children[i], sourceInfoById, childIdsByParentId, cache)) {
            cache[key] = true;
            return true;
        }
    }
    cache[key] = false;
    return false;
}

function _collectSourceDescendantIdsInIndex(sourceId, childIdsByParentId, seen, out) {
    if (sourceId === null || sourceId === undefined) return;
    _pushUniqueId(out, seen, sourceId);
    var children = childIdsByParentId ? (childIdsByParentId[String(sourceId)] || []) : [];
    for (var i = 0; i < children.length; i++) {
        _collectSourceDescendantIdsInIndex(children[i], childIdsByParentId, seen, out);
    }
}

function _sourceRootObjectIdsForSourceSetInIndex(sourceIds, sourceInfoById, cache) {
    if (!sourceIds || sourceIds.length === 0) return [];
    cache = cache || {};
    var cacheKey = _sourceSetKey(sourceIds);
    if (cache.hasOwnProperty(cacheKey)) return cache[cacheKey].slice(0);
    var sourceSet = _sourceIdSet(sourceIds);
    var roots = [];
    var seen = {};
    for (var i = 0; i < sourceIds.length; i++) {
        var sourceId = sourceIds[i];
        var src = sourceInfoById ? sourceInfoById[String(sourceId)] : null;
        var parentInSet = false;
        var guard = 0;
        while (src && src.parentId !== null && src.parentId !== undefined && guard < 200) {
            if (sourceSet[String(src.parentId)]) {
                parentInSet = true;
                break;
            }
            src = sourceInfoById ? sourceInfoById[String(src.parentId)] : null;
            guard++;
        }
        if (!parentInSet) _pushUniqueId(roots, seen, sourceId);
    }
    roots = _sortedNumericIds(roots);
    cache[cacheKey] = roots.slice(0);
    return roots;
}

function _sourceIdsInCompleteSubtreeInIndex(rootId, sourceInfoById, childIdsByParentId, cache) {
    var cacheKey = String(rootId);
    cache = cache || {};
    if (cache.hasOwnProperty(cacheKey)) return cache[cacheKey].slice(0);
    var ids = [];
    var seen = {};
    function visit(sourceId) {
        if (sourceId === null || sourceId === undefined) return;
        var src = sourceInfoById ? sourceInfoById[String(sourceId)] : null;
        if (!src) return;
        _pushUniqueId(ids, seen, sourceId);
        var children = childIdsByParentId ? (childIdsByParentId[String(sourceId)] || []) : [];
        for (var ci = 0; ci < children.length; ci++) visit(children[ci]);
    }
    visit(rootId);
    ids = _sortedNumericIds(ids);
    cache[cacheKey] = ids.slice(0);
    return ids;
}

function _sourceIdsInFullSubtreeInIndex(rootId, pageIndex, sourceInfoById, childIdsByParentId, cache) {
    var cacheKey = String(rootId) + "|page:" + String(pageIndex);
    cache = cache || {};
    if (cache.hasOwnProperty(cacheKey)) return cache[cacheKey].slice(0);
    var ids = [];
    var seen = {};
    function visit(sourceId) {
        if (sourceId === null || sourceId === undefined) return;
        var src = sourceInfoById ? sourceInfoById[String(sourceId)] : null;
        if (!src) return;
        if (pageIndex !== null && pageIndex !== undefined
                && src.pageIndex !== null && src.pageIndex !== undefined
                && src.pageIndex !== pageIndex) {
            return;
        }
        _pushUniqueId(ids, seen, sourceId);
        var children = childIdsByParentId ? (childIdsByParentId[String(sourceId)] || []) : [];
        for (var ci = 0; ci < children.length; ci++) visit(children[ci]);
    }
    visit(rootId);
    ids = _sortedNumericIds(ids);
    cache[cacheKey] = ids.slice(0);
    return ids;
}

function _sourceHasAncestorInIndex(sourceId, ancestorId, sourceInfoById, maxDepth) {
    if (sourceId === null || sourceId === undefined || ancestorId === null || ancestorId === undefined) {
        return false;
    }
    var current = sourceInfoById ? sourceInfoById[String(sourceId)] : null;
    var limit = maxDepth || 32;
    for (var depth = 0; depth < limit && current; depth++) {
        var parentId = current.parentId;
        if (parentId === null || parentId === undefined) return false;
        if (String(parentId) === String(ancestorId)) return true;
        current = sourceInfoById ? sourceInfoById[String(parentId)] : null;
    }
    return false;
}

function _sourceContainsSourceIdInIndex(ancestorId, descendantId, sourceInfoById, cache) {
    if (ancestorId === null || ancestorId === undefined
            || descendantId === null || descendantId === undefined) {
        return false;
    }
    cache = cache || {};
    var cacheKey = String(ancestorId) + "|" + String(descendantId);
    if (cache.hasOwnProperty(cacheKey)) return cache[cacheKey];
    if (String(ancestorId) === String(descendantId)) return true;
    var current = sourceInfoById ? sourceInfoById[String(descendantId)] : null;
    for (var depth = 0; depth < 64 && current; depth++) {
        var parentId = current.parentId;
        if (parentId === null || parentId === undefined) {
            cache[cacheKey] = false;
            return false;
        }
        if (String(parentId) === String(ancestorId)) {
            cache[cacheKey] = true;
            return true;
        }
        current = sourceInfoById ? sourceInfoById[String(parentId)] : null;
    }
    cache[cacheKey] = false;
    return false;
}
