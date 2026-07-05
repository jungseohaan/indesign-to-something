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

function _isSimpleMarkerLabelTextForSourceIndex(text) {
    text = String(text || "").replace(/[\s\r\n\t\u0016\u0018\u0003\uFFFC\uFEFF]/g, "");
    if (!text) return false;
    if (/^(가|나|다|라|마|바|ㄱ|ㄴ|ㄷ|ㄹ|ㅁ|ㅂ|ㅅ|ㅇ|ㅈ|ㅊ|ㅋ|ㅌ|ㅍ|ㅎ)$/.test(text)) return true;
    if (/^[0-9]{1,2}$/.test(text)) return true;
    if (text.length === 1) {
        var code = text.charCodeAt(0);
        if (code >= 0x2460 && code <= 0x2473) return true;
    }
    return false;
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
        var simpleMarkerLabelContents = null;
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
                simpleMarkerLabelContents = _isSimpleMarkerLabelTextForSourceIndex(
                        _plainTextOfTextFrameForOwnership(item));
            } catch (eSimpleMarkerLabel) {
                simpleMarkerLabelContents = null;
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
            simpleMarkerLabelContents: simpleMarkerLabelContents,
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
            if (hasVisibleFill(item)) {
                info.fillColor = item.fillColor.name;
                info.fillColorName = item.fillColor.name;
                try { info.fillTint = item.fillTint; } catch (eFillTint) {}
                info.hasVisibleFill = true;
            }
        } catch (eFill) {}
        try {
            if (hasVisibleStroke(item)) {
                info.strokeColor = item.strokeColor.name;
                info.strokeColorName = item.strokeColor.name;
                try { info.strokeTint = item.strokeTint; } catch (eStrokeTint) {}
                try { info.strokeWeight = item.strokeWeight || 0; } catch (eStrokeWeight) {}
                info.hasVisibleStroke = true;
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

function _buildSourceCoverageDiagnostics(sourceItems, candidates, objectPlanDiagnostics, options) {
    options = options || {};
    var fullDiagnostics = options.fullDiagnostics === true;
    var claimsBySourceId = {};
    var plans = objectPlanDiagnostics && objectPlanDiagnostics.objectPlans
            ? objectPlanDiagnostics.objectPlans
            : [];
    var summary = {
        sourceObjectCount: sourceItems ? sourceItems.length : 0,
        candidateCount: candidates ? candidates.length : 0,
        objectPlanCount: plans.length,
        statusCounts: {},
        claimKindCounts: {},
        unresolvedCount: 0,
        visibleMaterialUnresolvedCount: 0,
        droppedIntentionalCount: 0,
        provenanceOnlyCount: 0
    };

    function addClaim(sourceId, kind, owner) {
        if (sourceId === null || sourceId === undefined) return;
        var key = String(sourceId);
        if (!claimsBySourceId[key]) claimsBySourceId[key] = [];
        claimsBySourceId[key].push({
            kind: kind,
            ownerType: owner && owner.ownerType ? owner.ownerType : null,
            objectPlanId: owner && owner.objectPlanId ? owner.objectPlanId : null,
            bundleId: owner && owner.bundleId ? owner.bundleId : null,
            candidateId: owner && owner.candidateId ? owner.candidateId : null,
            passId: owner && owner.passId ? owner.passId : null,
            ownershipSlot: owner && owner.ownershipSlot ? owner.ownershipSlot : null,
            textAction: owner && owner.textAction ? owner.textAction : null,
            visualAction: owner && owner.visualAction ? owner.visualAction : null,
            materialization: owner && owner.materialization ? owner.materialization : null
        });
        _incrementSourceCoverageCount(summary.claimKindCounts, kind);
    }

    function addClaims(ids, kind, owner) {
        for (var i = 0; ids && i < ids.length; i++) addClaim(ids[i], kind, owner);
    }

    for (var p = 0; p < plans.length; p++) {
        var plan = plans[p];
        if (!plan) continue;
        var owner = {
            ownerType: "ObjectPlan",
            objectPlanId: plan.objectPlanId || null,
            bundleId: plan.bundleId || null,
            candidateId: plan.candidateId || null,
            passId: plan.passId || null,
            ownershipSlot: plan.ownershipSlot || null,
            textAction: plan.textAction || null,
            visualAction: plan.visualAction || null,
            materialization: plan.materialization || null
        };
        addClaims(plan.sourceObjectIds, "PROVENANCE", owner);
        if (_sourceCoveragePlanHasVisibleVisual(plan)) {
            addClaims(plan.visualSourceObjectIds, "VISIBLE_VISUAL", owner);
            addClaims(plan.exportSourceObjectIds, "VISIBLE_EXPORT", owner);
        }
        if (plan.visualAction === "PLACE_TABLE_STYLE") {
            addClaims(plan.styleSourceObjectIds, "STYLE_OWNER", owner);
            addClaims(plan.ownedTextFrameIds, "TEXT_OWNER", owner);
        } else {
            addClaims(plan.styleSourceObjectIds, "STYLE_OWNER", owner);
            if (plan.textAction === "OWNED_BY_HWPX_TEXT") {
                addClaims(plan.ownedTextFrameIds, "TEXT_OWNER", owner);
            } else if (plan.ownedTextFrameIds && plan.ownedTextFrameIds.length > 0) {
                addClaims(plan.ownedTextFrameIds, "TEXT_RELATION", owner);
            }
        }
        addClaims(plan.hiddenVisualSourceObjectIds, "HIDDEN_BY_OWNER", owner);
    }

    for (var c = 0; candidates && c < candidates.length; c++) {
        var candidate = candidates[c];
        if (!candidate) continue;
        var candidateOwner = {
            ownerType: "ExtractionCandidate",
            candidateId: candidate.candidateId || null,
            passId: candidate.passId || null,
            ownershipSlot: candidate.ownershipSlot || null,
            textAction: candidate.textAction || null,
            visualAction: candidate.visualAction || null,
            materialization: candidate.materialization || null
        };
        addClaims(candidate.sourceObjectIds, "CANDIDATE_PROVENANCE", candidateOwner);
        addClaims(candidate.hiddenVisualSourceObjectIds, "CANDIDATE_HIDDEN", candidateOwner);
    }

    var rows = [];
    var unresolvedRows = [];
    for (var s = 0; sourceItems && s < sourceItems.length; s++) {
        var src = sourceItems[s];
        if (!src || src.id === null || src.id === undefined) continue;
        var srcClaims = claimsBySourceId[String(src.id)] || [];
        var status = _sourceCoverageStatusForSource(src, srcClaims);
        var claimKinds = _sourceCoverageClaimKinds(srcClaims);
        var row = {
            sourceObjectId: src.id,
            coverageStatus: status,
            kind: src.kind || null,
            pageIndex: src.pageIndex,
            parentId: src.parentId !== undefined ? src.parentId : null,
            parentKind: src.parentKind || null,
            storyId: src.storyId !== undefined ? src.storyId : null,
            bounds: src.bounds || null,
            zOrder: src.zOrder !== undefined ? src.zOrder : null,
            layerName: src.layerName || null,
            visible: src.visible,
            hiddenLayer: src.hiddenLayer === true,
            nonprinting: src.nonprinting === true,
            textFrameClass: src.textFrameClass || null,
            hasText: src.hasText,
            hasChildren: src.hasChildren === true,
            hasPlacedVisual: src.hasPlacedVisual === true,
            hasCandidateVectorPaint: src.hasCandidateVectorPaint === true,
            hasVisibleFill: src.hasVisibleFill === true,
            hasVisibleStroke: src.hasVisibleStroke === true,
            storyAnchorPlacement: src.storyAnchorPlacement || null,
            coverageClaimKinds: claimKinds
        };
        if (fullDiagnostics) {
            row.claims = srcClaims;
            rows.push(row);
        }
        _incrementSourceCoverageCount(summary.statusCounts, status);
        if (status === "UNRESOLVED") {
            summary.unresolvedCount++;
            if (_sourceCoverageHasPotentialVisibleMaterial(src)) {
                summary.visibleMaterialUnresolvedCount++;
            }
            var unresolvedRow = row;
            if (!fullDiagnostics) {
                unresolvedRow = _sourceCoverageProblemRow(row, srcClaims);
                rows.push(unresolvedRow);
            }
            if (unresolvedRows.length < 200) unresolvedRows.push(unresolvedRow);
        } else if (status === "DROPPED_INTENTIONAL") {
            summary.droppedIntentionalCount++;
        } else if (status === "PROVENANCE_ONLY") {
            summary.provenanceOnlyCount++;
        }
    }

    return {
        schemaVersion: 1,
        policy: "POLICY-source-ownership",
        mode: fullDiagnostics
                ? "source-coverage-diagnostics"
                : "source-coverage-summary",
        summary: summary,
        fullDiagnostics: fullDiagnostics,
        fullDiagnosticsSkipped: !fullDiagnostics,
        unresolvedPreview: unresolvedRows,
        sourceObjects: rows
    };
}

function _sourceCoverageProblemRow(row, claims) {
    var out = {};
    for (var key in row) {
        if (!row.hasOwnProperty(key)) continue;
        out[key] = row[key];
    }
    out.claims = claims || [];
    return out;
}

function _sourceCoveragePlanHasVisibleVisual(plan) {
    if (!plan) return false;
    return plan.visualAction === "PLACE_INLINE_PNG"
            || plan.visualAction === "PLACE_FLOATING_PNG"
            || plan.visualAction === "PLACE_TEXT_SHELL"
            || plan.visualAction === "PLACE_TABLE_STYLE";
}

function _sourceCoverageStatusForSource(src, claims) {
    if (!src) return "UNRESOLVED";
    if (_sourceCoverageHasClaim(claims, "TEXT_OWNER")) return "TEXT_OWNED";
    if (_sourceCoverageHasClaim(claims, "STYLE_OWNER")) return "STYLE_OWNED";
    if (_sourceCoverageHasClaim(claims, "VISIBLE_VISUAL")
            || _sourceCoverageHasClaim(claims, "VISIBLE_EXPORT")) {
        return "VISIBLE_OWNED";
    }
    if (_sourceCoverageHasClaim(claims, "HIDDEN_BY_OWNER")
            || _sourceCoverageHasClaim(claims, "CANDIDATE_HIDDEN")) {
        return "HIDDEN_BY_OWNER";
    }
    if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) {
        return "DROPPED_INTENTIONAL";
    }
    if (_sourceCoverageHasClaim(claims, "PROVENANCE")
            || _sourceCoverageHasClaim(claims, "CANDIDATE_PROVENANCE")) {
        return "PROVENANCE_ONLY";
    }
    if (_sourceCoverageHasPotentialVisibleMaterial(src)) return "UNRESOLVED";
    return "PROVENANCE_ONLY";
}

function _sourceCoverageHasPotentialVisibleMaterial(src) {
    if (!src) return false;
    if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) return false;
    var kind = String(src.kind || "");
    if (kind === "TextFrame" && src.hasText === true) return true;
    if (kind === "Image" || kind === "PDF" || kind === "EPS") return true;
    if (src.hasPlacedVisual === true) return true;
    if (src.hasCandidateVectorPaint === true) return true;
    if (src.hasVisibleFill === true || src.hasVisibleStroke === true) return true;
    return false;
}

function _sourceCoverageHasClaim(claims, kind) {
    for (var i = 0; claims && i < claims.length; i++) {
        if (claims[i] && claims[i].kind === kind) return true;
    }
    return false;
}

function _sourceCoverageClaimKinds(claims) {
    var out = [];
    var seen = {};
    for (var i = 0; claims && i < claims.length; i++) {
        var kind = claims[i] && claims[i].kind ? claims[i].kind : "UNKNOWN";
        if (seen[kind]) continue;
        seen[kind] = true;
        out.push(kind);
    }
    return out;
}

function _incrementSourceCoverageCount(map, key) {
    key = key || "UNKNOWN";
    if (!map[key]) map[key] = 0;
    map[key]++;
}

function _buildSourceOwnershipModelDiagnostics(sourceItems, candidates, objectPlanDiagnostics, options) {
    options = options || {};
    var fullDiagnostics = options.fullDiagnostics === true;
    var plans = objectPlanDiagnostics && objectPlanDiagnostics.objectPlans
            ? objectPlanDiagnostics.objectPlans
            : [];
    var bundleRows = [];
    var slotRows = [];
    var ownerRows = [];
    var renderUnitRows = [];
    var seenBundleIds = {};
    var ownerCountBySlotKey = {};
    var summary = {
        sourceObjectCount: sourceItems ? sourceItems.length : 0,
        candidateCount: candidates ? candidates.length : 0,
        objectPlanCount: plans.length,
        bundleCount: 0,
        slotCount: 0,
        slotOwnerCount: 0,
        renderUnitCount: 0,
        bundleTypeCounts: {},
        slotCounts: {},
        ownerCounts: {},
        materializationCounts: {},
        duplicateSlotOwnerCount: 0,
        duplicateSlotKeys: []
    };

    for (var p = 0; p < plans.length; p++) {
        var plan = plans[p];
        if (!plan) continue;
        var bundle = _sourceModelBundleFromObjectPlan(plan);
        if (!seenBundleIds[bundle.bundleId]) {
            seenBundleIds[bundle.bundleId] = true;
            if (fullDiagnostics) bundleRows.push(bundle);
            _incrementSourceCoverageCount(summary.bundleTypeCounts, bundle.bundleType);
        }
        var slots = _sourceModelSlotsFromObjectPlan(plan);
        for (var s = 0; s < slots.length; s++) {
            var slot = slots[s];
            if (fullDiagnostics) slotRows.push(slot);
            _incrementSourceCoverageCount(summary.slotCounts, slot.ownershipSlot);
            var owner = _sourceModelOwnerFromSlot(slot, plan);
            if (fullDiagnostics) ownerRows.push(owner);
            _incrementSourceCoverageCount(summary.ownerCounts, owner.owner);
            _incrementSourceCoverageCount(summary.materializationCounts, owner.materialization);
            if (!ownerCountBySlotKey[slot.slotIdentityKey]) ownerCountBySlotKey[slot.slotIdentityKey] = 0;
            ownerCountBySlotKey[slot.slotIdentityKey]++;
            var renderUnit = _sourceModelRenderUnitFromSlot(slot, plan, owner);
            if (renderUnit) renderUnitRows.push(renderUnit);
        }
    }

    for (var key in ownerCountBySlotKey) {
        if (!ownerCountBySlotKey.hasOwnProperty(key)) continue;
        if (ownerCountBySlotKey[key] > 1) {
            summary.duplicateSlotOwnerCount++;
            if (summary.duplicateSlotKeys.length < 100) {
                summary.duplicateSlotKeys.push({
                    slotIdentityKey: key,
                    ownerCount: ownerCountBySlotKey[key]
                });
            }
        }
    }

    summary.bundleCount = _sourceModelObjectKeyCount(seenBundleIds);
    summary.slotCount = _sourceModelCountMapTotal(summary.slotCounts);
    summary.slotOwnerCount = _sourceModelCountMapTotal(summary.ownerCounts);
    summary.renderUnitCount = renderUnitRows.length;

    return {
        sourceBundles: {
            schemaVersion: 1,
            policy: "POLICY-source-ownership",
            mode: fullDiagnostics
                    ? "source-bundle-diagnostics"
                    : "source-bundle-summary",
            summary: {
                bundleCount: summary.bundleCount,
                bundleTypeCounts: summary.bundleTypeCounts
            },
            fullDiagnostics: fullDiagnostics,
            fullDiagnosticsSkipped: !fullDiagnostics,
            bundles: bundleRows
        },
        ownershipSlots: {
            schemaVersion: 1,
            policy: "POLICY-source-ownership",
            mode: fullDiagnostics
                    ? "ownership-slot-diagnostics"
                    : "ownership-slot-summary",
            summary: {
                slotCount: summary.slotCount,
                slotCounts: summary.slotCounts,
                duplicateSlotOwnerCount: summary.duplicateSlotOwnerCount,
                duplicateSlotKeys: summary.duplicateSlotKeys
            },
            fullDiagnostics: fullDiagnostics,
            fullDiagnosticsSkipped: !fullDiagnostics,
            slots: slotRows
        },
        slotOwners: {
            schemaVersion: 1,
            policy: "POLICY-source-ownership",
            mode: fullDiagnostics
                    ? "slot-owner-diagnostics"
                    : "slot-owner-summary",
            summary: {
                slotOwnerCount: summary.slotOwnerCount,
                ownerCounts: summary.ownerCounts,
                materializationCounts: summary.materializationCounts
            },
            fullDiagnostics: fullDiagnostics,
            fullDiagnosticsSkipped: !fullDiagnostics,
            owners: ownerRows
        },
        renderUnits: {
            schemaVersion: 1,
            policy: "POLICY-source-ownership",
            mode: "render-unit-diagnostics",
            summary: {
                renderUnitCount: renderUnitRows.length
            },
            renderUnits: renderUnitRows
        },
        summary: summary
    };
}

function _sourceModelObjectKeyCount(map) {
    var count = 0;
    for (var key in map) {
        if (map.hasOwnProperty(key)) count++;
    }
    return count;
}

function _sourceModelCountMapTotal(map) {
    var total = 0;
    for (var key in map) {
        if (!map.hasOwnProperty(key)) continue;
        total += Number(map[key] || 0);
    }
    return total;
}

function _sourceModelBundleFromObjectPlan(plan) {
    var bundleId = plan.bundleId || plan.objectPlanId || ("bundle.page." + plan.pageIndex + ".dom." + plan.primarySourceObjectId);
    return {
        bundleId: bundleId,
        objectPlanId: plan.objectPlanId || null,
        candidateId: plan.candidateId || null,
        passId: plan.passId || null,
        pageIndex: plan.pageIndex,
        bundleType: _sourceModelBundleType(plan),
        sourceObjectIds: _sortedNumericIds(plan.sourceObjectIds || []),
        sourceRootObjectIds: _sortedNumericIds(plan.sourceRootObjectIds || []),
        clusterSourceObjectIds: _sortedNumericIds(plan.clusterSourceObjectIds || []),
        primarySourceObjectId: plan.primarySourceObjectId !== undefined ? plan.primarySourceObjectId : null,
        clusterRelation: plan.clusterRelation || null,
        placement: plan.placement || null,
        coordinateSpace: plan.coordinateSpace || null,
        visualLayer: plan.visualLayer || null,
        zOrder: plan.zOrder !== undefined ? plan.zOrder : null,
        bounds: plan.bounds || null
    };
}

function _sourceModelBundleType(plan) {
    if (!plan) return "UNKNOWN_BUNDLE";
    if (plan.passId === "pass.master_page_graphics") return "APPLIED_MASTER_BUNDLE";
    if (plan.visualAction === "PLACE_TABLE_STYLE"
            || plan.ownershipSlot === "TABLE_STYLE_SLOT") return "TABLE_STYLE_BUNDLE";
    if (plan.placement === "INLINE") return "INLINE_ANCHOR_BUNDLE";
    if (plan.visualAction === "PLACE_TEXT_SHELL"
            || plan.ownershipSlot === "SHELL_SLOT") return "SHELL_BUNDLE";
    if (plan.ownershipSlot === "CONTENT_VISUAL_SLOT") return "CONTENT_VISUAL_BUNDLE";
    if (plan.textAction === "OWNED_BY_HWPX_TEXT") return "TEXT_BUNDLE";
    return "PROVENANCE_BUNDLE";
}

function _sourceModelSlotsFromObjectPlan(plan) {
    var slots = [];
    if (!plan) return slots;
    if (plan.textAction === "OWNED_BY_HWPX_TEXT"
            && plan.ownedTextFrameIds
            && plan.ownedTextFrameIds.length > 0) {
        slots.push(_sourceModelSlotFromObjectPlan(plan, "TEXT_SLOT",
                "HWPX_TEXT", plan.ownedTextFrameIds));
    }
    if (plan.visualAction === "PLACE_TABLE_STYLE") {
        slots.push(_sourceModelSlotFromObjectPlan(plan, "TABLE_STYLE_SLOT",
                "HWPX_TABLE_STYLE",
                _sourceIdsUnion(plan.styleSourceObjectIds || [], plan.ownedTextFrameIds || [])));
    }
    if (_sourceCoveragePlanHasVisibleVisual(plan)
            && plan.visualAction !== "PLACE_TABLE_STYLE") {
        var slot = plan.ownershipSlot || _sourceModelVisualSlotFromPlan(plan);
        slots.push(_sourceModelSlotFromObjectPlan(plan, slot,
                _sourceModelVisualOwner(plan),
                _sourceModelVisualSlotSourceIds(plan)));
    }
    return slots;
}

function _sourceModelSlotFromObjectPlan(plan, ownershipSlot, owner, slotSourceObjectIds) {
    var ids = _sortedNumericIds(slotSourceObjectIds || []);
    var slotIdentityKey = String(plan.pageIndex) + "|"
            + String(plan.placement || "NONE") + "|"
            + ownershipSlot + "|"
            + _sourceSetKey(ids);
    var slotId = "slot." + slotIdentityKey.replace(/[^A-Za-z0-9_.-]/g, "_")
            + "." + String(plan.objectPlanId || plan.bundleId || "plan").replace(/[^A-Za-z0-9_.-]/g, "_");
    return {
        slotId: slotId,
        slotIdentityKey: slotIdentityKey,
        bundleId: plan.bundleId || plan.objectPlanId || null,
        objectPlanId: plan.objectPlanId || null,
        candidateId: plan.candidateId || null,
        passId: plan.passId || null,
        pageIndex: plan.pageIndex,
        ownershipSlot: ownershipSlot,
        owner: owner,
        slotSourceObjectIds: ids,
        sourceObjectIds: _sortedNumericIds(plan.sourceObjectIds || []),
        visualSourceObjectIds: _sortedNumericIds(plan.visualSourceObjectIds || []),
        styleSourceObjectIds: _sortedNumericIds(plan.styleSourceObjectIds || []),
        ownedTextFrameIds: _sortedNumericIds(plan.ownedTextFrameIds || []),
        exportSourceObjectIds: _sortedNumericIds(plan.exportSourceObjectIds || []),
        hiddenVisualSourceObjectIds: _sortedNumericIds(plan.hiddenVisualSourceObjectIds || []),
        placement: plan.placement || null,
        coordinateSpace: plan.coordinateSpace || null,
        visualLayer: plan.visualLayer || null,
        policyLayer: plan.policyLayer || null,
        zOrder: plan.zOrder !== undefined ? plan.zOrder : null,
        materialization: plan.materialization || null,
        textAction: plan.textAction || null,
        visualAction: plan.visualAction || null,
        bounds: plan.bounds || null
    };
}

function _sourceModelOwnerFromSlot(slot, plan) {
    return {
        slotOwnerId: "owner." + slot.slotId,
        slotId: slot.slotId,
        slotIdentityKey: slot.slotIdentityKey,
        bundleId: slot.bundleId,
        objectPlanId: slot.objectPlanId,
        candidateId: slot.candidateId,
        passId: slot.passId,
        pageIndex: slot.pageIndex,
        ownershipSlot: slot.ownershipSlot,
        owner: slot.owner,
        materialization: slot.materialization,
        textAction: slot.textAction,
        visualAction: slot.visualAction,
        sourceObjectIds: slot.sourceObjectIds,
        slotSourceObjectIds: slot.slotSourceObjectIds,
        reason: plan && plan.reason ? plan.reason : null
    };
}

function _sourceModelRenderUnitFromSlot(slot, plan, owner) {
    if (!slot || !plan) return null;
    if (!(slot.owner === "TEXTLESS_PNG"
            || slot.owner === "CONTENT_PNG"
            || slot.owner === "COMPLETE_PNG")) {
        return null;
    }
    return {
        renderUnitId: "renderUnit." + slot.slotId.replace(/^slot\./, ""),
        slotId: slot.slotId,
        slotIdentityKey: slot.slotIdentityKey,
        bundleId: slot.bundleId,
        objectPlanId: slot.objectPlanId,
        candidateId: slot.candidateId,
        passId: slot.passId,
        pageIndex: slot.pageIndex,
        ownershipSlot: slot.ownershipSlot,
        owner: owner ? owner.owner : slot.owner,
        sourceObjectIds: slot.sourceObjectIds,
        visualSourceObjectIds: slot.visualSourceObjectIds,
        exportSourceObjectIds: slot.exportSourceObjectIds,
        hiddenTextFrameIds: slot.ownershipSlot === "SHELL_SLOT" ? slot.ownedTextFrameIds : [],
        hiddenVisualSourceObjectIds: slot.hiddenVisualSourceObjectIds,
        placement: slot.placement,
        coordinateSpace: slot.coordinateSpace,
        visualLayer: slot.visualLayer,
        zOrder: slot.zOrder,
        bounds: slot.bounds,
        cropSourceBounds: plan.cropSourceBounds || null,
        materialization: slot.materialization,
        visualAction: slot.visualAction
    };
}

function _sourceModelVisualSlotFromPlan(plan) {
    if (!plan) return "CONTENT_VISUAL_SLOT";
    if (plan.visualAction === "PLACE_TEXT_SHELL") return "SHELL_SLOT";
    if (plan.visualAction === "PLACE_TABLE_STYLE") return "TABLE_STYLE_SLOT";
    return "CONTENT_VISUAL_SLOT";
}

function _sourceModelVisualOwner(plan) {
    if (!plan) return "CONTENT_PNG";
    if (plan.materialization === "COMPLETE_PNG") return "COMPLETE_PNG";
    if (plan.materialization === "NATIVE_SOURCE_SHAPE") return "NATIVE_SOURCE_SHAPE";
    if (plan.visualAction === "PLACE_TEXT_SHELL") return "TEXTLESS_PNG";
    return "CONTENT_PNG";
}

function _sourceModelVisualSlotSourceIds(plan) {
    if (!plan) return [];
    if (plan.visualSourceObjectIds && plan.visualSourceObjectIds.length > 0) {
        return plan.visualSourceObjectIds;
    }
    if (plan.exportSourceObjectIds && plan.exportSourceObjectIds.length > 0) {
        return plan.exportSourceObjectIds;
    }
    if (plan.styleSourceObjectIds && plan.styleSourceObjectIds.length > 0) {
        return plan.styleSourceObjectIds;
    }
    return plan.sourceObjectIds || [];
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
