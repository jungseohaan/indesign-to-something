/*
 * Render matching helpers for extract_indd.jsx.
 *
 * This module may look up planned extraction candidates, filter candidates by
 * pass/materialization, and add execution metadata to already-rendered rows.
 * It must not create ownership, change placement, or reinterpret source slots.
 */

function _buildExtractionCandidateLookup(plan) {
    var lookup = {
        byPassSource: {},
        byPassSourcePage: {},
        byPassSourceSet: {},
        byPassSourceSetPage: {},
        byPassPage: {},
        byId: {},
        compositesByPassPage: {}
    };
    if (!plan || !plan.candidates) return lookup;
    for (var i = 0; i < plan.candidates.length; i++) {
        var c = plan.candidates[i];
        if (!c || !c.candidateId || !c.passId) continue;
        lookup.byId[c.candidateId] = c;
        if (c.sourceObjectIds && c.sourceObjectIds.length > 0) {
            lookup.byPassSourceSet[c.passId + "|" + _sourceSetKey(c.sourceObjectIds)] = c;
            if (c.pageIndex !== null && c.pageIndex !== undefined) {
                lookup.byPassSourceSetPage[c.passId + "|" + c.pageIndex + "|" + _sourceSetKey(c.sourceObjectIds)] = c;
            }
        }
        if (c.composite && c.sourceObjectIds && c.sourceObjectIds.length > 0
                && c.pageIndex !== null && c.pageIndex !== undefined) {
            var ck = c.passId + "|" + c.pageIndex;
            if (!lookup.compositesByPassPage[ck]) lookup.compositesByPassPage[ck] = [];
            lookup.compositesByPassPage[ck].push(c);
        }
        if (c.sourceObjectIds && c.sourceObjectIds.length === 1) {
            for (var si = 0; si < c.sourceObjectIds.length; si++) {
                lookup.byPassSource[c.passId + "|" + c.sourceObjectIds[si]] = c;
                if (c.pageIndex !== null && c.pageIndex !== undefined) {
                    lookup.byPassSourcePage[c.passId + "|" + c.pageIndex + "|" + c.sourceObjectIds[si]] = c;
                }
            }
        } else if (c.pageIndex !== null && c.pageIndex !== undefined) {
            lookup.byPassPage[c.passId + "|" + c.pageIndex] = c;
        }
    }
    return lookup;
}

function _extractionCandidatesForPass(plan, passId) {
    var out = [];
    if (!plan || !plan.candidates) return out;
    for (var i = 0; i < plan.candidates.length; i++) {
        var c = plan.candidates[i];
        if (c && c.passId === passId) out.push(c);
    }
    return out;
}

function _pngExtractionCandidatesForPass(plan, passId) {
    var all = _extractionCandidatesForPass(plan, passId);
    var out = [];
    for (var i = 0; i < all.length; i++) {
        var c = all[i];
        if (c && c.disabled === true) continue;
        if (c && c.visualAction === "DROP_VISUAL") continue;
        if (c && c.materialization === "NATIVE_SOURCE_SHAPE") continue;
        if (c && c.materialization === "HWPX_TABLE_STYLE") continue;
        if (c && c.materialization === "HWPX_TEXT") continue;
        out.push(c);
    }
    return out;
}

function _nonPngExtractionCandidatesForPass(plan, passId) {
    var all = _extractionCandidatesForPass(plan, passId);
    var out = [];
    for (var i = 0; i < all.length; i++) {
        var c = all[i];
        if (c && (c.materialization === "NATIVE_SOURCE_SHAPE"
                || c.materialization === "HWPX_TABLE_STYLE"
                || c.materialization === "HWPX_TEXT")) {
            out.push(c);
        }
    }
    return out;
}

function _candidateMatch(candidate, strategy) {
    return candidate ? { candidate: candidate, candidateId: candidate.candidateId, strategy: strategy } : null;
}

function _findPlannedExtractionCandidate(passId, item, pageIndex) {
    if (!_extractionCandidateLookup) return _candidateMatch({ candidateId: null, passId: passId }, "lookup_unavailable");
    var id = _itemId(item);
    var hasPage = pageIndex !== null && pageIndex !== undefined && pageIndex >= 0;
    if (id !== null && id !== undefined) {
        if (hasPage) {
            var sourceByItemPage = _extractionCandidateLookup.byPassSourcePage[passId + "|" + pageIndex + "|" + id];
            if (sourceByItemPage) return _candidateMatch(sourceByItemPage, "single_source_page");
            return null;
        }
        var sourceByItem = _extractionCandidateLookup.byPassSource[passId + "|" + id];
        if (sourceByItem) return _candidateMatch(sourceByItem, "single_source");
    }
    return null;
}

function _findPlannedExtractionSourceSet(passId, sourceObjectIds, pageIndex) {
    if (!_extractionCandidateLookup) return _candidateMatch({ candidateId: null, passId: passId }, "lookup_unavailable");
    if (!sourceObjectIds || sourceObjectIds.length === 0) return null;
    var hasPage = pageIndex !== null && pageIndex !== undefined && pageIndex >= 0;
    var sourceSetKey = _sourceSetKey(sourceObjectIds);
    if (hasPage) {
        var exactSourceSetPage = _extractionCandidateLookup.byPassSourceSetPage[passId + "|" + pageIndex + "|" + sourceSetKey];
        if (exactSourceSetPage) return _candidateMatch(exactSourceSetPage, "exact_source_set_page");
        return null;
    }
    var exactSourceSet = _extractionCandidateLookup.byPassSourceSet[passId + "|" + sourceSetKey];
    if (exactSourceSet) return _candidateMatch(exactSourceSet, "exact_source_set");
    return null;
}

function _findExtractionPass(plan, passId) {
    if (!plan || !plan.exportPasses) return null;
    for (var i = 0; i < plan.exportPasses.length; i++) {
        var p = plan.exportPasses[i];
        if (p && p.id === passId) return p;
    }
    return null;
}

function _isExtractionPassEnabled(plan, passId) {
    var p = _findExtractionPass(plan, passId);
    return !!(p && !p.disabled);
}

function _requireExtractionPass(ctx, passId) {
    var p = _findExtractionPass(ctx ? ctx.extractionPlan : null, passId);
    if (!p) throw new Error("ExtractionPlan missing required pass: " + passId);
    if (p.disabled) throw new Error("ExtractionPlan disabled required pass: " + passId);
    return p;
}

function _hiddenTextFrameIdsFromSaved(saved) {
    var ids = [], seen = {};
    if (!saved) return ids;
    for (var i = 0; i < saved.length; i++) {
        try {
            var tf = saved[i].tf;
            if (tf && tf.constructor && tf.constructor.name === "TextFrame") {
                _pushUniqueId(ids, seen, tf.id);
            }
        } catch (e) {}
    }
    return ids;
}

function _addRenderMeta(arr, type, planPassId) {
    if (!arr) return;
    for (var i = 0; i < arr.length; i++) {
        if (type !== null && type !== undefined) arr[i].type = type;
        if (!arr[i].planPassId) arr[i].planPassId = planPassId;
    }
}

function _buildItemById(allItems) {
    var itemById = {};
    if (!allItems) return itemById;
    function canIndexParent(item) {
        var kind = "";
        try { kind = item && item.constructor ? String(item.constructor.name || "") : ""; } catch (eKind) {}
        return kind === "Group"
                || kind === "Rectangle"
                || kind === "Oval"
                || kind === "Polygon"
                || kind === "GraphicLine"
                || kind === "TextFrame";
    }
    function putItem(item) {
        try {
            if (item && item.id !== undefined) {
                itemById[String(item.id)] = item;
                return true;
            }
        } catch (e) {}
        return false;
    }
    for (var i = 0; i < allItems.length; i++) {
        putItem(allItems[i]);
    }
    for (var pass = 0; pass < 8; pass++) {
        var appended = false;
        var keys = [];
        for (var key in itemById) {
            if (itemById.hasOwnProperty(key)) keys.push(key);
        }
        for (var ki = 0; ki < keys.length; ki++) {
            var item = itemById[keys[ki]];
            var parent = null;
            try { parent = item ? item.parent : null; } catch (eParent) {}
            if (!parent || !canIndexParent(parent)) continue;
            var parentId = null;
            try { parentId = parent.id; } catch (eParentId) {}
            if (parentId === null || parentId === undefined) continue;
            if (itemById[String(parentId)]) continue;
            if (putItem(parent)) appended = true;
        }
        if (!appended) break;
    }
    return itemById;
}
