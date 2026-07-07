/*
 * Source id/set helpers for extract_indd.jsx.
 *
 * Keep this file free of InDesign DOM access. It is loaded by
 * extract_indd.jsx and used by planning/validation code.
 */

var _SOURCE_SET_KEY_CACHE = {};
var _SOURCE_SET_KEY_CACHE_COUNT = 0;
var _SOURCE_SET_MEMBERSHIP_CACHE = {};
var _SOURCE_SET_MEMBERSHIP_CACHE_COUNT = 0;
var _SOURCE_SET_CACHE_LIMIT = 12000;

function _sourceSetRawKey(ids) {
    if (!ids || ids.length === 0) return "";
    var out = [];
    for (var i = 0; i < ids.length; i++) out.push(String(ids[i]));
    return String(ids.length) + "|" + out.join(",");
}

function _sourceSetKey(ids) {
    if (!ids || ids.length === 0) return "";
    var rawKey = _sourceSetRawKey(ids);
    if (_SOURCE_SET_KEY_CACHE.hasOwnProperty(rawKey)) return _SOURCE_SET_KEY_CACHE[rawKey];
    var copy = [];
    for (var i = 0; i < ids.length; i++) copy.push(Number(ids[i]));
    copy.sort(function(a, b) { return a - b; });
    var out = [];
    for (var j = 0; j < copy.length; j++) out.push(String(copy[j]));
    var key = out.join(",");
    if (_SOURCE_SET_KEY_CACHE_COUNT > _SOURCE_SET_CACHE_LIMIT) {
        _SOURCE_SET_KEY_CACHE = {};
        _SOURCE_SET_KEY_CACHE_COUNT = 0;
    }
    _SOURCE_SET_KEY_CACHE[rawKey] = key;
    _SOURCE_SET_KEY_CACHE_COUNT++;
    return key;
}

function _sourceSetMembership(ids) {
    var key = _sourceSetKey(ids || []);
    if (_SOURCE_SET_MEMBERSHIP_CACHE.hasOwnProperty(key)) return _SOURCE_SET_MEMBERSHIP_CACHE[key];
    var seen = {};
    for (var i = 0; ids && i < ids.length; i++) seen[String(ids[i])] = true;
    if (_SOURCE_SET_MEMBERSHIP_CACHE_COUNT > _SOURCE_SET_CACHE_LIMIT) {
        _SOURCE_SET_MEMBERSHIP_CACHE = {};
        _SOURCE_SET_MEMBERSHIP_CACHE_COUNT = 0;
    }
    _SOURCE_SET_MEMBERSHIP_CACHE[key] = seen;
    _SOURCE_SET_MEMBERSHIP_CACHE_COUNT++;
    return seen;
}

function _sourceSetContainsAll(containerIds, memberIds) {
    if (!containerIds || !memberIds || memberIds.length === 0) return false;
    var seen = _sourceSetMembership(containerIds);
    for (var j = 0; j < memberIds.length; j++) {
        if (!seen[String(memberIds[j])]) return false;
    }
    return true;
}

function _sourceSetsIntersect(a, b) {
    if (!a || !b || a.length === 0 || b.length === 0) return false;
    var small = a.length <= b.length ? a : b;
    var large = small === a ? b : a;
    var seen = _sourceSetMembership(large);
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
    if (sourceItems._sourceItemIndexesCache) return sourceItems._sourceItemIndexesCache;

    for (var i = 0; i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!src || src.id === null || src.id === undefined) continue;
        sourceInfoById[String(src.id)] = src;
        if (src.parentId === null || src.parentId === undefined) continue;
        var parentKey = String(src.parentId);
        if (!childIdsByParentId[parentKey]) childIdsByParentId[parentKey] = [];
        childIdsByParentId[parentKey].push(src.id);
    }

    var indexes = {
        sourceInfoById: sourceInfoById,
        childIdsByParentId: childIdsByParentId
    };
    try {
        Object.defineProperty(sourceItems, "_sourceItemIndexesCache", {
            value: indexes,
            enumerable: false
        });
    } catch (eSourceItemIndexesCache) {
        sourceItems._sourceItemIndexesCache = indexes;
    }
    return indexes;
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
