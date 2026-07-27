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
var _SOURCE_SET_SORTED_CACHE = {};
var _SOURCE_SET_SORTED_CACHE_COUNT = 0;
var _SOURCE_SET_INTERN_CACHE = {};
var _SOURCE_SET_INTERN_CACHE_COUNT = 0;
var _SOURCE_SET_CACHE_LIMIT = 12000;

function _sourceSetDefineHidden(target, key, value) {
    if (!target) return;
    try {
        Object.defineProperty(target, key, {
            value: value,
            enumerable: false
        });
    } catch (eDefineHidden) {
        try { target[key] = value; } catch (eAssignHidden) {}
    }
}

function _sourceSetRawKey(ids) {
    if (!ids || ids.length === 0) return "";
    var out = [];
    for (var i = 0; i < ids.length; i++) out.push(String(ids[i]));
    return String(ids.length) + "|" + out.join(",");
}

function _sourceSetKey(ids) {
    if (!ids || ids.length === 0) return "";
    try {
        if (ids._sourceSetKeyCache !== undefined) return ids._sourceSetKeyCache;
    } catch (eCachedKey) {}
    var rawKey = _sourceSetRawKey(ids);
    if (_SOURCE_SET_KEY_CACHE.hasOwnProperty(rawKey)) {
        var cachedKey = _SOURCE_SET_KEY_CACHE[rawKey];
        _sourceSetDefineHidden(ids, "_sourceSetKeyCache", cachedKey);
        if (_SOURCE_SET_SORTED_CACHE.hasOwnProperty(rawKey)) {
            _sourceSetDefineHidden(ids, "_sourceSetSortedIdsCache", _SOURCE_SET_SORTED_CACHE[rawKey]);
        }
        return cachedKey;
    }
    var copy = _sourceSetSortedIdsForRawKey(ids, rawKey);
    var out = [];
    for (var j = 0; j < copy.length; j++) out.push(String(copy[j]));
    var key = out.join(",");
    if (_SOURCE_SET_KEY_CACHE_COUNT > _SOURCE_SET_CACHE_LIMIT) {
        _SOURCE_SET_KEY_CACHE = {};
        _SOURCE_SET_KEY_CACHE_COUNT = 0;
    }
    _SOURCE_SET_KEY_CACHE[rawKey] = key;
    _SOURCE_SET_KEY_CACHE_COUNT++;
    _sourceSetDefineHidden(ids, "_sourceSetKeyCache", key);
    _sourceSetDefineHidden(ids, "_sourceSetSortedIdsCache", copy);
    return key;
}

function _sourceSetSortedIdsForRawKey(ids, rawKey) {
    if (!ids) return [];
    try {
        if (ids._sourceSetSortedIdsCache) return ids._sourceSetSortedIdsCache;
    } catch (eCachedSorted) {}
    rawKey = rawKey || _sourceSetRawKey(ids);
    if (_SOURCE_SET_SORTED_CACHE.hasOwnProperty(rawKey)) {
        var cached = _SOURCE_SET_SORTED_CACHE[rawKey];
        _sourceSetDefineHidden(ids, "_sourceSetSortedIdsCache", cached);
        return cached;
    }
    var sorted = [];
    for (var i = 0; i < ids.length; i++) sorted.push(Number(ids[i]));
    sorted.sort(function(a, b) { return a - b; });
    var copy = [];
    for (var j = 0; j < sorted.length; j++) {
        if (j === 0 || sorted[j] !== sorted[j - 1]) copy.push(sorted[j]);
    }
    if (_SOURCE_SET_SORTED_CACHE_COUNT > _SOURCE_SET_CACHE_LIMIT) {
        _SOURCE_SET_SORTED_CACHE = {};
        _SOURCE_SET_SORTED_CACHE_COUNT = 0;
    }
    _SOURCE_SET_SORTED_CACHE[rawKey] = copy;
    _SOURCE_SET_SORTED_CACHE_COUNT++;
    _sourceSetDefineHidden(ids, "_sourceSetSortedIdsCache", copy);
    return copy;
}

function _internSourceSetIds(ids) {
    if (!ids || ids.length === 0) return [];
    var sorted = _sourceSetSortedIdsForRawKey(ids);
    var key = _sourceSetKey(sorted);
    if (_SOURCE_SET_INTERN_CACHE.hasOwnProperty(key)) return _SOURCE_SET_INTERN_CACHE[key];
    if (_SOURCE_SET_INTERN_CACHE_COUNT > _SOURCE_SET_CACHE_LIMIT) {
        _SOURCE_SET_INTERN_CACHE = {};
        _SOURCE_SET_INTERN_CACHE_COUNT = 0;
    }
    _sourceSetDefineHidden(sorted, "_sourceSetKeyCache", key);
    _sourceSetDefineHidden(sorted, "_sourceSetSortedIdsCache", sorted);
    _sourceSetDefineHidden(sorted, "_sourceSetInterned", true);
    _SOURCE_SET_INTERN_CACHE[key] = sorted;
    _SOURCE_SET_INTERN_CACHE_COUNT++;
    return sorted;
}

function _sourceSetId(ids) {
    var key = _sourceSetKey(ids || []);
    return key ? "ss." + _sourceSetStableHash(key) : "";
}

function _sourceSetStableHash(value) {
    var h = 5381;
    var s = String(value || "");
    for (var i = 0; i < s.length; i++) {
        h = ((h << 5) + h) + s.charCodeAt(i);
        h = h & 0x7fffffff;
    }
    var out = h.toString(36);
    return out + "." + String(s.length);
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
    if (!ids) return [];
    var sorted = _sourceSetSortedIdsForRawKey(ids);
    return sorted.slice(0);
}

function _sourceIdsContain(ids, sourceId) {
    if (!ids || sourceId === null || sourceId === undefined) return false;
    for (var i = 0; i < ids.length; i++) {
        if (String(ids[i]) === String(sourceId)) return true;
    }
    return false;
}
