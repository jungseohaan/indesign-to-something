// Read-only source metadata context for candidate normalization.
//
// This module owns source index caches used by candidate_normalization.jsx.
// It must not mutate candidates, choose ownership, placement, or layers.

function _createCandidateNormalizationSourceContext(sourceItems) {
    var sourceIndexes = _buildSourceItemIndexes(sourceItems);
    var sourceInfoById = sourceIndexes.sourceInfoById;
    var childIdsByParentId = sourceIndexes.childIdsByParentId;
    var editableDescendantBySourceId = {};
    var sourceRootObjectIdsForSourceSetCache = {};
    var completeSubtreeIdsByRootId = {};
    var fullSubtreeIdsByRootPageKey = {};
    var editableTextIdsInSourceSetCache = {};
    var textFrameIdsInSourceSetCache = {};
    var sourceContainsSourceIdCache = {};
    var sourceHasInlineAnchorAncestorCache = {};

    function sourceHasEditableTextDescendant(sourceId) {
        return _sourceHasEditableTextDescendantInIndex(
                sourceId, sourceInfoById, childIdsByParentId, editableDescendantBySourceId);
    }

    function sourceHasEditableTextAncestorCarrier(sourceId) {
        if (sourceId === null || sourceId === undefined) return false;
        var current = sourceInfoById[String(sourceId)];
        for (var depth = 0; depth < 32 && current; depth++) {
            var parentId = current.parentId;
            if (parentId === null || parentId === undefined) return false;
            if (sourceHasEditableTextDescendant(parentId)) return true;
            current = sourceInfoById[String(parentId)];
        }
        return false;
    }

    function collectSourceDescendantIds(sourceId, seen, out) {
        _collectSourceDescendantIdsInIndex(sourceId, childIdsByParentId, seen, out);
    }

    function sourceRootObjectIdsForSourceSet(sourceIds) {
        return _sourceRootObjectIdsForSourceSetInIndex(
                sourceIds, sourceInfoById, sourceRootObjectIdsForSourceSetCache);
    }

    function editableTextIdsInSourceSet(sourceIds, pageIndex) {
        var cacheKey = _sourceSetKey(sourceIds || []) + "|page:" + String(pageIndex);
        if (editableTextIdsInSourceSetCache.hasOwnProperty(cacheKey)) {
            return editableTextIdsInSourceSetCache[cacheKey].slice(0);
        }
        var ids = [];
        var seen = {};
        if (!sourceIds) {
            editableTextIdsInSourceSetCache[cacheKey] = ids.slice(0);
            return ids;
        }
        for (var i = 0; i < sourceIds.length; i++) {
            var src = sourceInfoById[String(sourceIds[i])];
            if (!src || src.kind !== "TextFrame") continue;
            if (src.textFrameClass !== "editable" || src.hasText !== true) continue;
            if (pageIndex !== null && pageIndex !== undefined && src.pageIndex !== pageIndex) continue;
            _pushUniqueId(ids, seen, sourceIds[i]);
        }
        ids = _sortedNumericIds(ids);
        editableTextIdsInSourceSetCache[cacheKey] = ids.slice(0);
        return ids;
    }

    function textFrameIdsInSourceSet(sourceIds, pageIndex) {
        var cacheKey = _sourceSetKey(sourceIds || []) + "|page:" + String(pageIndex);
        if (textFrameIdsInSourceSetCache.hasOwnProperty(cacheKey)) {
            return textFrameIdsInSourceSetCache[cacheKey].slice(0);
        }
        var ids = [];
        var seen = {};
        if (!sourceIds) {
            textFrameIdsInSourceSetCache[cacheKey] = ids.slice(0);
            return ids;
        }
        for (var i = 0; i < sourceIds.length; i++) {
            var src = sourceInfoById[String(sourceIds[i])];
            if (!src || src.kind !== "TextFrame") continue;
            if (src.textFrameClass !== "editable") continue;
            if (pageIndex !== null && pageIndex !== undefined && src.pageIndex !== pageIndex) continue;
            _pushUniqueId(ids, seen, sourceIds[i]);
        }
        ids = _sortedNumericIds(ids);
        textFrameIdsInSourceSetCache[cacheKey] = ids.slice(0);
        return ids;
    }

    function sourceIdsInFullSubtree(rootId, pageIndex) {
        return _sourceIdsInFullSubtreeInIndex(
                rootId, pageIndex, sourceInfoById, childIdsByParentId, fullSubtreeIdsByRootPageKey);
    }

    function sourceIdsInCompleteSubtree(rootId) {
        return _sourceIdsInCompleteSubtreeInIndex(
                rootId, sourceInfoById, childIdsByParentId, completeSubtreeIdsByRootId);
    }

    function sourceContainsSourceId(ancestorId, descendantId) {
        return _sourceContainsSourceIdInIndex(
                ancestorId, descendantId, sourceInfoById, sourceContainsSourceIdCache);
    }

    function sourceHasInlineAnchorAncestor(sourceId) {
        if (sourceId === null || sourceId === undefined) return false;
        var cacheKey = String(sourceId);
        if (sourceHasInlineAnchorAncestorCache.hasOwnProperty(cacheKey)) {
            return sourceHasInlineAnchorAncestorCache[cacheKey];
        }
        var current = sourceInfoById[String(sourceId)];
        for (var depth = 0; depth < 64 && current; depth++) {
            if (_isInlineFlowItemBySourceInfo(current)) {
                sourceHasInlineAnchorAncestorCache[cacheKey] = true;
                return true;
            }
            var parentId = current.parentId;
            if (parentId === null || parentId === undefined) {
                sourceHasInlineAnchorAncestorCache[cacheKey] = false;
                return false;
            }
            current = sourceInfoById[String(parentId)];
        }
        sourceHasInlineAnchorAncestorCache[cacheKey] = false;
        return false;
    }

    return {
        sourceIndexes: sourceIndexes,
        sourceInfoById: sourceInfoById,
        childIdsByParentId: childIdsByParentId,
        refreshCandidateMeta: function(meta) {
            return _refreshExtractionCandidateMeta(meta, sourceInfoById);
        },
        sourceHasEditableTextDescendant: sourceHasEditableTextDescendant,
        sourceHasEditableTextAncestorCarrier: sourceHasEditableTextAncestorCarrier,
        collectSourceDescendantIds: collectSourceDescendantIds,
        sourceRootObjectIdsForSourceSet: sourceRootObjectIdsForSourceSet,
        editableTextIdsInSourceSet: editableTextIdsInSourceSet,
        textFrameIdsInSourceSet: textFrameIdsInSourceSet,
        sourceIdsInFullSubtree: sourceIdsInFullSubtree,
        sourceIdsInCompleteSubtree: sourceIdsInCompleteSubtree,
        sourceContainsSourceId: sourceContainsSourceId,
        sourceHasInlineAnchorAncestor: sourceHasInlineAnchorAncestor
    };
}
