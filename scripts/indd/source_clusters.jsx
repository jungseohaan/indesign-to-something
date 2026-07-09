/*
 * Source-cluster diagnostics for extract_indd.jsx.
 *
 * This module normalizes sourceItems into hierarchy clusters. It is diagnostic
 * for now: it does not decide extraction ownership or placement.
 */

function _buildSourceClusters(sourceItems) {
    var indexes = _buildSourceItemIndexes(sourceItems);
    var sourceInfoById = indexes.sourceInfoById;
    var childIdsByParentId = indexes.childIdsByParentId;
    var clusters = [];
    var seenRoot = {};

    if (!sourceItems) {
        return _sourceClusterDocument([], {
            sourceItemCount: 0,
            rootCount: 0
        });
    }

    for (var i = 0; i < sourceItems.length; i++) {
        var item = sourceItems[i];
        if (!item || item.id === null || item.id === undefined) continue;
        var root = _sourceClusterRootForItem(item, sourceInfoById);
        if (!root || root.id === null || root.id === undefined) continue;
        var rootKey = String(root.id);
        if (seenRoot[rootKey]) continue;
        seenRoot[rootKey] = true;
        clusters.push(_buildSourceCluster(root, sourceInfoById, childIdsByParentId));
    }

    clusters.sort(function(a, b) {
        var ap = a.pageIndex === null || a.pageIndex === undefined ? 999999 : a.pageIndex;
        var bp = b.pageIndex === null || b.pageIndex === undefined ? 999999 : b.pageIndex;
        if (ap !== bp) return ap - bp;
        return Number(a.rootSourceObjectId || 0) - Number(b.rootSourceObjectId || 0);
    });

    return _sourceClusterDocument(clusters, {
        sourceItemCount: sourceItems.length,
        rootCount: clusters.length
    });
}

function _createSourceClusterIndex(sourceItems, sourceClusterDocument) {
    var indexes = _buildSourceItemIndexes(sourceItems);
    var sourceInfoById = indexes.sourceInfoById;
    var childIdsByParentId = indexes.childIdsByParentId;
    var diagnostics = sourceClusterDocument || _buildSourceClusters(sourceItems);
    var clusterBySourceId = {};
    var descendantSourceIdsById = {};
    var textFrameIdsByKey = {};
    var editableTextOutsideByKey = {};

    if (diagnostics && diagnostics.clusters) {
        for (var ci = 0; ci < diagnostics.clusters.length; ci++) {
            var cluster = diagnostics.clusters[ci];
            if (!cluster || !cluster.sourceObjectIds) continue;
            for (var si = 0; si < cluster.sourceObjectIds.length; si++) {
                clusterBySourceId[String(cluster.sourceObjectIds[si])] = cluster;
            }
        }
    }

    function sourceInfo(sourceId) {
        if (sourceId === null || sourceId === undefined) return null;
        return sourceInfoById[String(sourceId)] || null;
    }

    function descendantSourceObjectIds(sourceId) {
        if (sourceId === null || sourceId === undefined) return [];
        var key = String(sourceId);
        if (descendantSourceIdsById[key]) return descendantSourceIdsById[key];
        var out = [];
        _collectSourceClusterIds(sourceId, childIdsByParentId, {}, out);
        out = _sortedNumericIds(out);
        descendantSourceIdsById[key] = out;
        return out;
    }

    function pageLocalSourceObjectIds(sourceId, pageIndex, pageBoundsFn, sameSpreadFn) {
        var descendants = descendantSourceObjectIds(sourceId);
        if (!descendants || descendants.length === 0) return [];
        var ids = [];
        var pb = pageBoundsFn ? pageBoundsFn(pageIndex) : null;

        for (var i = 0; i < descendants.length; i++) {
            var src = sourceInfo(descendants[i]);
            if (!src) continue;
            if (_sourceClusterInfoVisibleOnPage(src, pageIndex, pb, sameSpreadFn)) {
                ids.push(src.id);
            }
        }

        return ids.length > 0 ? _sortedNumericIds(ids) : descendants;
    }

    function textFrameIds(sourceId, editableOnly, requireContent) {
        var key = String(sourceId) + "|" + (editableOnly ? "e" : "a") + "|" + (requireContent ? "c" : "n");
        if (textFrameIdsByKey[key]) return textFrameIdsByKey[key];
        var descendants = descendantSourceObjectIds(sourceId);
        var ids = [];
        for (var i = 0; i < descendants.length; i++) {
            var src = sourceInfo(descendants[i]);
            if (!src || src.kind !== "TextFrame") continue;
            if (editableOnly && src.textFrameClass !== "editable") continue;
            if (requireContent && src.hasText !== true) continue;
            ids.push(src.id);
        }
        ids = _sortedNumericIds(ids);
        textFrameIdsByKey[key] = ids;
        return ids;
    }

    function hasEditableTextDescendantOutsideSubtree(rootId, subtreeRootId) {
        var key = String(rootId) + "|" + String(subtreeRootId);
        if (editableTextOutsideByKey[key] !== undefined) return editableTextOutsideByKey[key];
        var subtreeIds = _idSetForArray(descendantSourceObjectIds(subtreeRootId));
        var rootTextIds = textFrameIds(rootId, true, true);
        for (var i = 0; i < rootTextIds.length; i++) {
            if (!subtreeIds[String(rootTextIds[i])]) {
                editableTextOutsideByKey[key] = true;
                return true;
            }
        }
        editableTextOutsideByKey[key] = false;
        return false;
    }

    function clusterForSource(sourceId) {
        if (sourceId === null || sourceId === undefined) return null;
        return clusterBySourceId[String(sourceId)] || null;
    }

    return {
        diagnostics: diagnostics,
        sourceInfoById: sourceInfoById,
        childIdsByParentId: childIdsByParentId,
        sourceInfo: sourceInfo,
        clusterForSource: clusterForSource,
        descendantSourceObjectIds: descendantSourceObjectIds,
        pageLocalSourceObjectIds: pageLocalSourceObjectIds,
        textFrameIds: textFrameIds,
        hasEditableTextDescendantOutsideSubtree: hasEditableTextDescendantOutsideSubtree
    };
}

function _buildSourceClusterQueryDiagnostics(sourceClusterIndex, candidates) {
    var summary = {
        candidateCount: 0,
        comparableCandidateCount: 0,
        exactSourceSetCount: 0,
        candidateBroaderThanClusterCount: 0,
        clusterBroaderThanCandidateCount: 0,
        divergentSourceSetCount: 0
    };
    var samples = [];

    if (!sourceClusterIndex || !candidates) {
        return { summary: summary, samples: samples };
    }

    for (var i = 0; i < candidates.length; i++) {
        var candidate = candidates[i];
        if (!candidate) continue;
        summary.candidateCount++;
        if (candidate.primarySourceObjectId === null || candidate.primarySourceObjectId === undefined) continue;
        if (!candidate.sourceObjectIds || candidate.sourceObjectIds.length === 0) continue;

        var clusterIds = sourceClusterIndex.descendantSourceObjectIds(candidate.primarySourceObjectId);
        if (!clusterIds || clusterIds.length === 0) continue;

        summary.comparableCandidateCount++;
        var candidateIds = _sortedNumericIds(candidate.sourceObjectIds);
        var candidateKey = _sourceSetKey(candidateIds);
        var clusterKey = _sourceSetKey(clusterIds);
        var relation = null;

        if (candidateKey === clusterKey) {
            summary.exactSourceSetCount++;
            continue;
        } else if (_sourceSetContainsAll(candidateIds, clusterIds)) {
            relation = "candidate_broader_than_cluster";
            summary.candidateBroaderThanClusterCount++;
        } else if (_sourceSetContainsAll(clusterIds, candidateIds)) {
            relation = "cluster_broader_than_candidate";
            summary.clusterBroaderThanCandidateCount++;
        } else {
            relation = "divergent_source_set";
            summary.divergentSourceSetCount++;
        }

        if (samples.length < 100) {
            samples.push({
                candidateId: candidate.candidateId || null,
                passId: candidate.passId || null,
                pageIndex: candidate.pageIndex,
                primarySourceObjectId: candidate.primarySourceObjectId,
                relation: relation,
                candidateSourceObjectIds: candidateIds,
                clusterDescendantSourceObjectIds: clusterIds
            });
        }
    }

    return {
        summary: summary,
        samples: samples
    };
}

function _sourceClusterDocumentWithQueryDiagnostics(sourceItems, candidates) {
    var doc = _buildSourceClusters(sourceItems);
    var index = _createSourceClusterIndex(sourceItems, doc);
    doc.queryDiagnostics = _buildSourceClusterQueryDiagnostics(index, candidates);
    return doc;
}

function _sourceClusterDocument(clusters, baseSummary) {
    var summary = baseSummary || {};
    summary.clusterCount = clusters ? clusters.length : 0;
    summary.textOwningShellLikeCount = 0;
    summary.visualOnlyCount = 0;
    summary.textOnlyCount = 0;
    summary.mixedPageCount = 0;

    if (clusters) {
        for (var i = 0; i < clusters.length; i++) {
            var c = clusters[i];
            if (!c) continue;
            if (c.clusterRole === "TEXT_OWNING_SHELL_LIKE") summary.textOwningShellLikeCount++;
            else if (c.clusterRole === "VISUAL_ONLY") summary.visualOnlyCount++;
            else if (c.clusterRole === "TEXT_ONLY") summary.textOnlyCount++;
            if (c.pageIndexes && c.pageIndexes.length > 1) summary.mixedPageCount++;
        }
    }

    return {
        schemaVersion: 1,
        policy: "POLICY-source-ownership",
        mode: "source-cluster-diagnostics",
        summary: summary,
        clusters: clusters || []
    };
}

function _sourceClusterRootForItem(item, sourceInfoById) {
    var current = item;
    var guard = 0;
    while (current && current.parentId !== null && current.parentId !== undefined && guard < 200) {
        var parent = sourceInfoById[String(current.parentId)];
        if (!parent) break;
        current = parent;
        guard++;
    }
    return current;
}

function _buildSourceCluster(root, sourceInfoById, childIdsByParentId) {
    var ids = [];
    var visited = {};
    _collectSourceClusterIds(root.id, childIdsByParentId, visited, ids);
    ids = _sortedNumericIds(ids);

    var bounds = null;
    var pageSeen = {};
    var pageIndexes = [];
    var kindCounts = {};
    var layerSeen = {};
    var layers = [];
    var textFrameIds = [];
    var editableTextFrameIds = [];
    var visualSourceObjectIds = [];
    var textFrameSeen = {};
    var editableTextFrameSeen = {};
    var visualSourceSeen = {};
    var hasText = false;
    var hasEditableText = false;
    var hasVisual = false;

    for (var i = 0; i < ids.length; i++) {
        var src = sourceInfoById[String(ids[i])];
        if (!src) continue;
        if (src.bounds && src.bounds.length >= 4) bounds = _unionBounds(bounds, src.bounds);
        if (src.pageIndex !== null && src.pageIndex !== undefined && !pageSeen[String(src.pageIndex)]) {
            pageSeen[String(src.pageIndex)] = true;
            pageIndexes.push(src.pageIndex);
        }
        var kind = src.kind || "Unknown";
        kindCounts[kind] = (kindCounts[kind] || 0) + 1;
        if (src.layerName && !layerSeen[src.layerName]) {
            layerSeen[src.layerName] = true;
            layers.push(src.layerName);
        }
        if (kind === "TextFrame") {
            _pushClusterUniqueIdWithSeen(textFrameIds, textFrameSeen, src.id);
            if (src.textFrameClass === "editable") {
                _pushClusterUniqueIdWithSeen(editableTextFrameIds, editableTextFrameSeen, src.id);
                hasEditableText = true;
            }
        } else {
            _pushClusterUniqueIdWithSeen(visualSourceObjectIds, visualSourceSeen, src.id);
            hasVisual = true;
        }
        if (src.hasText || (src.textLength !== null && src.textLength !== undefined && Number(src.textLength) > 0)) {
            hasText = true;
        }
    }

    pageIndexes.sort(function(a, b) { return Number(a) - Number(b); });

    return {
        clusterId: "cluster.src." + root.id,
        rootSourceObjectId: root.id,
        pageIndex: root.pageIndex,
        pageIndexes: pageIndexes,
        kind: root.kind,
        parentId: root.parentId,
        parentKind: root.parentKind,
        bounds: bounds,
        sourceObjectIds: ids,
        visualSourceObjectIds: visualSourceObjectIds,
        textFrameIds: textFrameIds,
        editableTextFrameIds: editableTextFrameIds,
        hasText: hasText,
        hasEditableText: hasEditableText,
        hasVisual: hasVisual,
        kindCounts: kindCounts,
        layerNames: layers,
        clusterRole: _sourceClusterRole(hasEditableText, hasText, hasVisual)
    };
}

function _collectSourceClusterIds(rootId, childIdsByParentId, visited, out) {
    var key = String(rootId);
    if (visited[key]) return;
    visited[key] = true;
    out.push(rootId);
    var children = childIdsByParentId[key] || [];
    for (var i = 0; i < children.length; i++) {
        _collectSourceClusterIds(children[i], childIdsByParentId, visited, out);
    }
}

function _sourceClusterRole(hasEditableText, hasText, hasVisual) {
    if (hasEditableText && hasVisual) return "TEXT_OWNING_SHELL_LIKE";
    if (hasVisual && !hasText) return "VISUAL_ONLY";
    if (hasText && !hasVisual) return "TEXT_ONLY";
    if (hasText && hasVisual) return "MIXED_TEXT_VISUAL";
    return "EMPTY_OR_UNKNOWN";
}

function _sourceClusterInfoVisibleOnPage(src, pageIndex, pageBounds, sameSpreadFn) {
    if (!src) return false;
    if (src.pageIndex < 0) return true;
    if (src.pageIndex === pageIndex) return true;
    if (!sameSpreadFn || !pageBounds || !src.bounds) return false;
    return sameSpreadFn(src.pageIndex, pageIndex) && _boundsOverlap(pageBounds, src.bounds);
}

function _unionBounds(a, b) {
    if (!b || b.length < 4) return a;
    if (!a || a.length < 4) return [Number(b[0]), Number(b[1]), Number(b[2]), Number(b[3])];
    return [
        Math.min(Number(a[0]), Number(b[0])),
        Math.min(Number(a[1]), Number(b[1])),
        Math.max(Number(a[2]), Number(b[2])),
        Math.max(Number(a[3]), Number(b[3]))
    ];
}

function _pushClusterUniqueId(arr, id) {
    if (id === null || id === undefined) return;
    var key = String(id);
    for (var i = 0; i < arr.length; i++) {
        if (String(arr[i]) === key) return;
    }
    arr.push(id);
}

function _pushClusterUniqueIdWithSeen(arr, seen, id) {
    if (id === null || id === undefined) return;
    var key = String(id);
    if (seen[key]) return;
    seen[key] = true;
    arr.push(id);
}
