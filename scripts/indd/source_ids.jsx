/*
 * Source id/set helpers for extract_indd.jsx.
 *
 * Keep this file free of InDesign DOM access. It is loaded by
 * extract_indd.jsx and used by planning/validation code.
 */

function _sourceSetKey(ids) {
    if (!ids || ids.length === 0) return "";
    var copy = [];
    for (var i = 0; i < ids.length; i++) copy.push(Number(ids[i]));
    copy.sort(function(a, b) { return a - b; });
    var out = [];
    for (var j = 0; j < copy.length; j++) out.push(String(copy[j]));
    return out.join(",");
}

function _sourceSetContainsAll(containerIds, memberIds) {
    if (!containerIds || !memberIds || memberIds.length === 0) return false;
    var seen = {};
    for (var i = 0; i < containerIds.length; i++) seen[String(containerIds[i])] = true;
    for (var j = 0; j < memberIds.length; j++) {
        if (!seen[String(memberIds[j])]) return false;
    }
    return true;
}

function _sourceSetsIntersect(a, b) {
    if (!a || !b || a.length === 0 || b.length === 0) return false;
    var small = a.length <= b.length ? a : b;
    var large = small === a ? b : a;
    var seen = {};
    for (var i = 0; i < large.length; i++) seen[String(large[i])] = true;
    for (var j = 0; j < small.length; j++) {
        if (seen[String(small[j])]) return true;
    }
    return false;
}

function _idSetForArray(ids) {
    var out = {};
    if (!ids) return out;
    for (var i = 0; i < ids.length; i++) out[String(ids[i])] = true;
    return out;
}

function _buildSourceItemIndexes(sourceItems) {
    var sourceInfoById = {};
    var childIdsByParentId = {};
    if (!sourceItems) {
        return {
            sourceInfoById: sourceInfoById,
            childIdsByParentId: childIdsByParentId
        };
    }

    for (var i = 0; i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!src || src.id === null || src.id === undefined) continue;
        sourceInfoById[String(src.id)] = src;
        if (src.parentId === null || src.parentId === undefined) continue;
        var parentKey = String(src.parentId);
        if (!childIdsByParentId[parentKey]) childIdsByParentId[parentKey] = [];
        childIdsByParentId[parentKey].push(src.id);
    }

    return {
        sourceInfoById: sourceInfoById,
        childIdsByParentId: childIdsByParentId
    };
}

function _sourceIdSet(ids) {
    var out = {};
    if (!ids) return out;
    for (var i = 0; i < ids.length; i++) out[String(ids[i])] = true;
    return out;
}

function _removeSourceIds(sourceIds, removedIds) {
    if (!sourceIds || sourceIds.length === 0 || !removedIds || removedIds.length === 0) {
        return sourceIds || [];
    }
    var remove = _sourceIdSet(removedIds);
    var out = [], seen = {};
    for (var i = 0; i < sourceIds.length; i++) {
        var id = sourceIds[i];
        if (remove[String(id)]) continue;
        _pushUniqueId(out, seen, id);
    }
    return out;
}

function _sourceIdsMinus(sourceIds, removedIds) {
    var removed = _sourceIdSet(removedIds || []);
    var ids = [];
    var seen = {};
    for (var i = 0; sourceIds && i < sourceIds.length; i++) {
        if (removed[String(sourceIds[i])]) continue;
        _pushUniqueId(ids, seen, sourceIds[i]);
    }
    return _sortedNumericIds(ids);
}

function _sourceIdsUnion(a, b) {
    var ids = [];
    var seen = {};
    for (var ai = 0; a && ai < a.length; ai++) _pushUniqueId(ids, seen, a[ai]);
    for (var bi = 0; b && bi < b.length; bi++) _pushUniqueId(ids, seen, b[bi]);
    return _sortedNumericIds(ids);
}

function _sourceIdsWithout(sourceIds, removedIds) {
    return _sourceIdsMinus(sourceIds, removedIds);
}

function _sortedNumericIds(ids) {
    var out = [];
    if (!ids) return out;
    for (var i = 0; i < ids.length; i++) out.push(Number(ids[i]));
    out.sort(function(a, b) { return a - b; });
    return out;
}

function _sourceIdsContain(ids, sourceId) {
    if (!ids || sourceId === null || sourceId === undefined) return false;
    for (var i = 0; i < ids.length; i++) {
        if (String(ids[i]) === String(sourceId)) return true;
    }
    return false;
}
