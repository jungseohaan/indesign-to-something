/*
 * Read-only inline visual source detector.
 *
 * This module only discovers inline visual source ids and provenance. It must
 * not mutate extraction candidates, ownership slots, placement, or layer.
 */

function _createInlineVisualSourceDetector(allItems, planCache) {
    var nestedSourceIdsById = {};
    var topLevelInlineItemsIndex = null;

    function nestedSourceIdsForItem(item) {
        var itemId = null;
        try { itemId = item ? item.id : null; } catch (eItemId) { itemId = null; }
        var key = itemId !== null && itemId !== undefined ? String(itemId) : null;
        if (key && nestedSourceIdsById.hasOwnProperty(key)) return nestedSourceIdsById[key].slice(0);
        if (planCache && planCache.sourceObjectIds) {
            try {
                var cachedIds = planCache.sourceObjectIds(item);
                if (cachedIds && cachedIds.length > 0) {
                    cachedIds = _sortedNumericIds(cachedIds);
                    if (key) nestedSourceIdsById[key] = cachedIds.slice(0);
                    return cachedIds;
                }
            } catch (ePlanCacheSourceIds) {}
        }
        var out = [];
        var seen = {};
        if (itemId !== null && itemId !== undefined) _pushUniqueId(out, seen, itemId);
        try {
            var nested = item.allPageItems;
            for (var ni = 0; nested && ni < nested.length; ni++) {
                try { _pushUniqueId(out, seen, nested[ni].id); } catch (eNestedId) {}
            }
        } catch (eNested) {}
        out = _sortedNumericIds(out);
        if (key) nestedSourceIdsById[key] = out.slice(0);
        return out;
    }

    function parentStoryKeyForItem(item) {
        try {
            if (item && item.parentStory && item.parentStory.id !== undefined) {
                return String(item.parentStory.id);
            }
        } catch (eSelfStory) {}
        try {
            var cur = item ? item.parent : null;
            while (cur) {
                try {
                    if (cur.parentStory && cur.parentStory.id !== undefined) {
                        return String(cur.parentStory.id);
                    }
                } catch (eCurStory) {}
                try {
                    if (cur.constructor && cur.constructor.name === "Story" && cur.id !== undefined) {
                        return String(cur.id);
                    }
                } catch (eStoryCtor) {}
                var pn = "";
                try { pn = cur.constructor.name; } catch (ePn) {}
                if (pn === "Spread" || pn === "Page" || pn === "Document") break;
                try { cur = cur.parent; } catch (eParent) { break; }
            }
        } catch (eParentStory) {}
        return "__unknown";
    }

    function expandInlineVisualSourceIds(ids) {
        var out = [];
        var seen = {};
        for (var ii = 0; ids && ii < ids.length; ii++) {
            var id = ids[ii];
            var item = planCache && planCache.domItem ? planCache.domItem(id) : null;
            if (!item) {
                _pushUniqueId(out, seen, id);
                continue;
            }
            var nestedIds = nestedSourceIdsForItem(item);
            for (var ni = 0; ni < nestedIds.length; ni++) _pushUniqueId(out, seen, nestedIds[ni]);
        }
        return _sortedNumericIds(out);
    }

    function isTopLevelInlineStoryItem(item) {
        try {
            if (!item || !isInlineItem(item)) return false;
            var p = item.parent;
            if (p && (p.constructor.name === "Group"
                    || p.constructor.name === "Rectangle"
                    || p.constructor.name === "Polygon"
                    || p.constructor.name === "Oval"
                    || p.constructor.name === "TextFrame")
                    && isInlineItem(p)) {
                return false;
            }
            return true;
        } catch (e) {}
        return false;
    }

    function topLevelInlineItemsByStory() {
        if (topLevelInlineItemsIndex) return topLevelInlineItemsIndex;
        var byStory = {};
        var all = [];
        for (var ai = 0; allItems && ai < allItems.length; ai++) {
            var item = allItems[ai];
            if (!isTopLevelInlineStoryItem(item)) continue;
            var itemId = null;
            try { itemId = item.id; } catch (eItemId) { itemId = null; }
            if (itemId === null || itemId === undefined) continue;
            var bounds = null;
            try { bounds = arrCopy(item.geometricBounds); } catch (eBounds) {}
            var entry = {
                item: item,
                id: itemId,
                storyKey: parentStoryKeyForItem(item),
                bounds: bounds
            };
            all.push(entry);
            if (!byStory[entry.storyKey]) byStory[entry.storyKey] = [];
            byStory[entry.storyKey].push(entry);
        }
        topLevelInlineItemsIndex = {
            byStory: byStory,
            all: all
        };
        return topLevelInlineItemsIndex;
    }

    function collectHiddenTextFrameInlineSourceIds(hiddenTextFrameIds) {
        var out = [];
        var seen = {};
        var inlineIndex = null;
        for (var hi = 0; hiddenTextFrameIds && hi < hiddenTextFrameIds.length; hi++) {
            var hiddenId = hiddenTextFrameIds[hi];
            var tf = planCache && planCache.domItem ? planCache.domItem(hiddenId) : null;
            if (!tf) continue;
            var tfBounds = null;
            try { tfBounds = arrCopy(tf.geometricBounds); } catch (eTfBounds) {}
            if (!inlineIndex) inlineIndex = topLevelInlineItemsByStory();
            var storyKey = parentStoryKeyForItem(tf);
            var storyEntries = inlineIndex.byStory[storyKey] || [];
            for (var si = 0; si < storyEntries.length; si++) {
                var storyEntry = storyEntries[si];
                if (String(storyEntry.id) === String(hiddenId) || seen[String(storyEntry.id)]) continue;
                if (tfBounds && storyEntry.bounds && !_boundsOverlap(tfBounds, storyEntry.bounds)) continue;
                var nestedIds = nestedSourceIdsForItem(storyEntry.item);
                for (var ni = 0; ni < nestedIds.length; ni++) _pushUniqueId(out, seen, nestedIds[ni]);
            }
        }
        return _sortedNumericIds(out);
    }

    return {
        expandInlineVisualSourceIds: expandInlineVisualSourceIds,
        collectHiddenTextFrameInlineSourceIds: collectHiddenTextFrameInlineSourceIds
    };
}
