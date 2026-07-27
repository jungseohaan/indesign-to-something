// Source-declared shell candidate generation.
//
// This module intentionally keeps the legacy candidate-shaped output contract
// while moving source-declared shell discovery out of extraction_plan_builder.jsx.
// Ownership decisions still come from source metadata and ObjectPlan policy; this
// file must not inspect rendered pixels or page-specific symptoms.

function _appendSourceDeclaredTextOwningShellGroupCandidates(ctx, sourceItems, allItems, candidates, seen, planCache) {
    var indexes = _buildSourceItemIndexes(sourceItems);
    var sourceInfoById = indexes.sourceInfoById;
    var childIdsByParentId = indexes.childIdsByParentId;
    var sourceHasInlineAnchorAncestorCache = {};
    var sourceHasEditableTextDescendantCache = {};
    var directEditableTextChildrenCache = {};
    var allEditableTextDescendantsCache = {};
    var hasShellStructureMaterialCache = {};
    var collectSubtreeSourceIdsCache = {};
    var sourceHasPlacedVisualSourceCache = {};
    var sourceHasVisibleShellSourceCache = {};
    var sourceTreeHasVisibleStrokeCache = {};
    var sourceTreeHasNonPaperFillCache = {};
    var closedTextOwningShellInfoCache = {};
    var hasMoreSpecificClosedTextOwningShellCache = {};
    var descendantClosedTextShellRootsCache = {};
    var sourceCanBeDirectSiblingTextShellCache = {};
    var sourceCanBePageSiblingTextShellCache = {};
    var sourceCanBeNativeParentTextShellCache = {};
    var matchingDirectSiblingTextFrameIdsCache = {};
    var matchingPageSiblingTextFrameIdsCache = {};
    var matchingDescendantSiblingTextFrameIdsCache = {};
    var matchingDirectSiblingShellSourceIdsCache = {};
    var parentEditableTextDescendantsCache = {};
    var sourceCanBeTableSiblingShellCache = {};
    var sourceCanBeResidualSiblingShellCache = {};
    var sourceCanBeInlineTextlessSiblingDecorationCache = {};
    var inlineParentHasEditableTextSiblingCache = {};
    var upsertDecorationCandidateIndex = null;
    var sourceBuckets = buildSourceDeclaredShellBuckets();

    function markSourceDeclaredShellStep(tag) {
        try {
            if (ctx && ctx.outputDir && typeof _marker === "function") {
                _marker(ctx.outputDir, tag);
            }
        } catch (eMarkSourceDeclaredShellStep) {}
    }

    function pageIndexInCurrentExtraction(pageIndex) {
        if (pageIndex === null || pageIndex === undefined || pageIndex < 0) return false;
        if (ctx && ctx.rangePageCount !== undefined && pageIndex < ctx.rangePageCount) return true;
        return _candidatePageInRange(pageIndex, ctx);
    }

    function buildSourceDeclaredShellBuckets() {
        var buckets = {
            current: [],
            shellRoots: [],
            shellShapes: [],
            groups: [],
            editableTextFrames: [],
            inlineVisuals: []
        };
        for (var i = 0; sourceItems && i < sourceItems.length; i++) {
            var src = sourceItems[i];
            if (!src || src.id === null || src.id === undefined) continue;
            if (!pageIndexInCurrentExtraction(src.pageIndex)) continue;
            buckets.current.push(src);
            var kind = String(src.kind || "");
            if (kind === "TextFrame") {
                if (src.textFrameClass === "editable" && src.hasText === true) {
                    buckets.editableTextFrames.push(src);
                }
                continue;
            }
            if (kind === "Group") {
                buckets.groups.push(src);
                buckets.shellRoots.push(src);
            } else if (kind === "Rectangle" || kind === "Oval" || kind === "Polygon") {
                buckets.shellShapes.push(src);
                buckets.shellRoots.push(src);
            } else if (kind === "GraphicLine") {
                buckets.shellRoots.push(src);
            }
            if (kind !== "Story" && kind !== "Character" && kind !== "InsertionPoint"
                    && sourceInfoIsInlineFlow(src)) {
                buckets.inlineVisuals.push(src);
            }
        }
        return buckets;
    }

    function sourceKind(sourceId) {
        var src = sourceInfoById[String(sourceId)];
        return src ? String(src.kind || "") : "";
    }

    function sourceHasInlineAnchorAncestor(sourceId) {
        if (sourceId === null || sourceId === undefined) return false;
        var cacheKey = String(sourceId);
        if (sourceHasInlineAnchorAncestorCache.hasOwnProperty(cacheKey)) {
            return sourceHasInlineAnchorAncestorCache[cacheKey];
        }
        var current = sourceInfoById[String(sourceId)];
        for (var depth = 0; depth < 64 && current; depth++) {
            if (sourceInfoIsInlineFlow(current)) {
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

    function sourceIsInlineOwnedDescendant(rootSourceId, sourceId) {
        if (sourceId === null || sourceId === undefined) return false;
        if (String(sourceId) === String(rootSourceId)) return false;
        return sourceHasInlineAnchorAncestor(sourceId);
    }

    function sourceHasEditableTextDescendant(sourceId) {
        var cacheKey = String(sourceId);
        if (sourceHasEditableTextDescendantCache.hasOwnProperty(cacheKey)) {
            return sourceHasEditableTextDescendantCache[cacheKey];
        }
        var src = sourceInfoById[String(sourceId)];
        if (!src) {
            sourceHasEditableTextDescendantCache[cacheKey] = false;
            return false;
        }
        if (src.kind === "TextFrame"
                && src.textFrameClass === "editable"
                && src.hasText === true) {
            sourceHasEditableTextDescendantCache[cacheKey] = true;
            return true;
        }
        var children = childIdsByParentId[String(sourceId)] || [];
        for (var i = 0; i < children.length; i++) {
            if (sourceHasEditableTextDescendant(children[i])) {
                sourceHasEditableTextDescendantCache[cacheKey] = true;
                return true;
            }
        }
        sourceHasEditableTextDescendantCache[cacheKey] = false;
        return false;
    }

    function directEditableTextChildren(sourceId) {
        var cacheKey = String(sourceId);
        if (directEditableTextChildrenCache.hasOwnProperty(cacheKey)) {
            return directEditableTextChildrenCache[cacheKey];
        }
        var children = childIdsByParentId[String(sourceId)] || [];
        var ids = [];
        var seenIds = {};
        for (var ci = 0; ci < children.length; ci++) {
            var child = sourceInfoById[String(children[ci])];
            if (!child) continue;
            if (child.kind !== "TextFrame") continue;
            if (child.textFrameClass !== "editable") continue;
            if (child.hasText !== true) continue;
            _pushUniqueId(ids, seenIds, child.id);
        }
        directEditableTextChildrenCache[cacheKey] = _sortedNumericIds(ids);
        return directEditableTextChildrenCache[cacheKey];
    }

    function allEditableTextDescendants(sourceId) {
        var cacheKey = String(sourceId);
        if (allEditableTextDescendantsCache.hasOwnProperty(cacheKey)) {
            return allEditableTextDescendantsCache[cacheKey];
        }
        var ids = [];
        var seenIds = {};
        function visit(id) {
            if (sourceIsInlineOwnedDescendant(sourceId, id)) return;
            var src = sourceInfoById[String(id)];
            if (!src) return;
            if (src.kind === "TextFrame"
                    && src.textFrameClass === "editable"
                    && src.hasText === true) {
                _pushUniqueId(ids, seenIds, src.id);
            }
            var children = childIdsByParentId[String(id)] || [];
            for (var ci = 0; ci < children.length; ci++) visit(children[ci]);
        }
        visit(sourceId);
        allEditableTextDescendantsCache[cacheKey] = _sortedNumericIds(ids);
        return allEditableTextDescendantsCache[cacheKey];
    }

    function visualShellChildCount(sourceId) {
        var children = childIdsByParentId[String(sourceId)] || [];
        var count = 0;
        for (var ci = 0; ci < children.length; ci++) {
            var childId = children[ci];
            var kind = sourceKind(childId);
            if (kind === "TextFrame") continue;
            if (kind !== "Group" && kind !== "Rectangle" && kind !== "Oval"
                    && kind !== "Polygon" && kind !== "GraphicLine") {
                continue;
            }
            if (sourceHasEditableTextDescendant(childId)) continue;
            count++;
        }
        return count;
    }

    function isShellStructureSourceKind(kind) {
        kind = String(kind || "");
        return kind === "Group"
                || kind === "Rectangle"
                || kind === "Oval"
                || kind === "Polygon"
                || kind === "GraphicLine";
    }

    function isDirectTextOwningShellRoot(sourceId) {
        var kind = sourceKind(sourceId);
        if (!isShellStructureSourceKind(kind) || kind === "GraphicLine") return false;
        var textIds = directEditableTextChildren(sourceId);
        if (!textIds || textIds.length === 0) return false;
        var allTextIds = allEditableTextDescendants(sourceId);
        return sourceSetsEqual(textIds, allTextIds);
    }

    function hasShellStructureMaterial(sourceId) {
        var cacheKey = String(sourceId);
        if (hasShellStructureMaterialCache.hasOwnProperty(cacheKey)) {
            return hasShellStructureMaterialCache[cacheKey];
        }
        var src = sourceInfoById[String(sourceId)];
        if (!src) {
            hasShellStructureMaterialCache[cacheKey] = false;
            return false;
        }
        var kind = sourceKind(sourceId);
        if (isShellStructureSourceKind(kind)) {
            hasShellStructureMaterialCache[cacheKey] = true;
            return true;
        }
        var children = childIdsByParentId[String(sourceId)] || [];
        for (var ci = 0; ci < children.length; ci++) {
            if (hasShellStructureMaterial(children[ci])) {
                hasShellStructureMaterialCache[cacheKey] = true;
                return true;
            }
        }
        hasShellStructureMaterialCache[cacheKey] = false;
        return false;
    }

    function parentHasResidualShellStructureOutsideChild(sourceId) {
        var sourceEntry = sourceInfoById[String(sourceId)];
        if (!sourceEntry) return false;
        if (_isInlineFlowItemBySourceInfo(sourceEntry)) return false;

        var parentId = sourceEntry.parentId;
        if (parentId === null || parentId === undefined) return false;
        var parent = sourceInfoById[String(parentId)];
        if (!parent) return false;
        if (!isShellStructureSourceKind(parent.kind)) return false;

        var siblings = childIdsByParentId[String(parentId)] || [];
        var hasDirectResidualText = false;
        var hasDirectResidualShell = false;
        for (var si = 0; si < siblings.length; si++) {
            var siblingId = siblings[si];
            if (String(siblingId) === String(sourceId)) continue;
            var sibling = sourceInfoById[String(siblingId)];
            if (!sibling) continue;
            if (String(sibling.kind || "") === "TextFrame") {
                if (sibling.textFrameClass === "editable" && sibling.hasText === true) {
                    hasDirectResidualText = true;
                }
                continue;
            }
            if (isDirectTextOwningShellRoot(siblingId)) continue;
            if (hasShellStructureMaterial(siblingId)) hasDirectResidualShell = true;
        }
        if (hasDirectResidualText && hasDirectResidualShell) {
            return false;
        }
        for (var ri = 0; ri < siblings.length; ri++) {
            var residualSiblingId = siblings[ri];
            if (String(residualSiblingId) === String(sourceId)) continue;
            var residualSibling = sourceInfoById[String(residualSiblingId)];
            if (!residualSibling) continue;
            if (String(residualSibling.kind || "") === "TextFrame") continue;
            if (isDirectTextOwningShellRoot(residualSiblingId)) continue;
            if (hasShellStructureMaterial(residualSiblingId)) return true;
        }
        return false;
    }

    function collectSubtreeSourceIds(sourceId) {
        var cacheKey = String(sourceId);
        if (collectSubtreeSourceIdsCache.hasOwnProperty(cacheKey)) {
            return collectSubtreeSourceIdsCache[cacheKey];
        }
        var ids = [];
        var seenIds = {};
        function visit(id) {
            if (sourceIsInlineOwnedDescendant(sourceId, id)) return;
            var src = sourceInfoById[String(id)];
            if (!src) return;
            _pushUniqueId(ids, seenIds, src.id);
            var children = childIdsByParentId[String(id)] || [];
            for (var ci = 0; ci < children.length; ci++) visit(children[ci]);
        }
        visit(sourceId);
        collectSubtreeSourceIdsCache[cacheKey] = _sortedNumericIds(ids);
        return collectSubtreeSourceIdsCache[cacheKey];
    }

    function sourceHasTextFrameShellStyleInSourceIndex(sourceId) {
        return _sourceHasTextFrameShellStyleMetadataInIndex(sourceId, sourceInfoById);
    }

    function collectExportSourceIds(sourceIds, editableTextIds, allowInlineOwnedSources, shellRootId) {
        var editableSet = _sourceIdSet(editableTextIds || []);
        var ids = [];
        var seenIds = {};
        for (var i = 0; sourceIds && i < sourceIds.length; i++) {
            var sourceId = sourceIds[i];
            if (allowInlineOwnedSources !== true && sourceHasInlineAnchorAncestor(sourceId)) continue;
            if (sourceIsInsidePlacedVisualBranch(sourceId, shellRootId)) {
                continue;
            }
            var kind = sourceKind(sourceId);
            if (editableSet[String(sourceId)]) {
                if (kind === "TextFrame" && sourceHasTextFrameShellStyleInSourceIndex(sourceId)) {
                    _pushUniqueId(ids, seenIds, sourceId);
                }
                continue;
            }
            if (kind === "TextFrame") continue;
            if (kind !== "Group" && kind !== "Rectangle" && kind !== "Oval"
                    && kind !== "Polygon" && kind !== "GraphicLine") {
                continue;
            }
            _pushUniqueId(ids, seenIds, sourceId);
        }
        return _sortedNumericIds(ids);
    }

    function sourceIsInsidePlacedVisualBranch(sourceId, shellRootId) {
        if (sourceId === null || sourceId === undefined
                || shellRootId === null || shellRootId === undefined) {
            return false;
        }
        var current = sourceInfoById[String(sourceId)];
        for (var depth = 0; depth < 64 && current; depth++) {
            if (String(current.id) === String(shellRootId)) return false;
            if (sourceHasPlacedVisualSource(current.id)) return true;
            if (current.parentId === null || current.parentId === undefined) return false;
            current = sourceInfoById[String(current.parentId)];
        }
        return false;
    }

    function collectTextShellVisualExportSourceIds(shellSourceId, editableTextIds, includePlacedVisualBranches) {
        var sourceIds = collectSubtreeSourceIds(shellSourceId);
        var exportIds = collectExportSourceIds(
                sourceIds,
                editableTextIds || [],
                false,
                includePlacedVisualBranches === true ? null : shellSourceId);
        if (!exportIds || exportIds.length === 0) return [shellSourceId];
        return exportIds;
    }

    function sourceHasPlacedVisualSource(sourceId) {
        var cacheKey = String(sourceId);
        if (sourceHasPlacedVisualSourceCache.hasOwnProperty(cacheKey)) {
            return sourceHasPlacedVisualSourceCache[cacheKey];
        }
        sourceHasPlacedVisualSourceCache[cacheKey] = _sourceHasPlacedVisualMetadataInIndex(
                sourceId, sourceInfoById, childIdsByParentId);
        return sourceHasPlacedVisualSourceCache[cacheKey];
    }

    function sourceHasVisibleShellSource(sourceId) {
        var cacheKey = String(sourceId);
        if (sourceHasVisibleShellSourceCache.hasOwnProperty(cacheKey)) {
            return sourceHasVisibleShellSourceCache[cacheKey];
        }
        var src = sourceInfoById[String(sourceId)];
        if (!src) {
            sourceHasVisibleShellSourceCache[cacheKey] = false;
            return false;
        }
        var kind = String(src.kind || "");
        if (kind === "TextFrame") {
            sourceHasVisibleShellSourceCache[cacheKey] = false;
            return false;
        }
        if (kind === "Group") {
            var children = childIdsByParentId[String(sourceId)] || [];
            for (var ci = 0; ci < children.length; ci++) {
                if (sourceHasVisibleShellSource(children[ci])) {
                    sourceHasVisibleShellSourceCache[cacheKey] = true;
                    return true;
                }
            }
            sourceHasVisibleShellSourceCache[cacheKey] = false;
            return false;
        }
        if (kind === "Image" || kind === "PDF") {
            sourceHasVisibleShellSourceCache[cacheKey] = true;
            return true;
        }
        if (kind === "Rectangle" || kind === "Oval" || kind === "Polygon" || kind === "GraphicLine") {
            sourceHasVisibleShellSourceCache[cacheKey] = sourceHasVisiblePaint(src) || sourceHasPlacedVisualSource(sourceId);
            return sourceHasVisibleShellSourceCache[cacheKey];
        }
        sourceHasVisibleShellSourceCache[cacheKey] = false;
        return false;
    }

    function sourceHasChildSources(sourceId) {
        var children = childIdsByParentId[String(sourceId)] || [];
        return children.length > 0;
    }

    function sourceHasVisibleFillSource(src) {
        if (!src) return false;
        var fillName = String(src.fillColorName || src.fillColor || "");
        return fillName !== "" && fillName !== "None" && fillName !== "[None]";
    }

    function storyTableCount(story) {
        try {
            if (!story || !story.tables) return 0;
            if (story.tables.length !== undefined && story.tables.length !== null) {
                return Number(story.tables.length || 0);
            }
            if (story.tables.count) return Number(story.tables.count() || 0);
        } catch (eStoryTableCount) {}
        return 0;
    }

    function isTableOnlyCarrierSource(sourceId) {
        var src = sourceInfoById[String(sourceId)];
        if (!src || src.kind !== "TextFrame") return false;
        if (src.hasTablesInStory === true) {
            return src.textFrameClass === "editable"
                    && src.hasText === false
                    && Number(src.textLength || 0) === 0;
        }
        if (src.markerOnlyContents === true) {
            return src.textFrameClass === "editable"
                    && src.hasText === false
                    && Number(src.textLength || 0) === 0;
        }
        var tf = planCache && planCache.domItem ? planCache.domItem(sourceId) : null;
        try {
            if (!tf || tf.constructor.name !== "TextFrame") return false;
            if (storyTableCount(tf.parentStory) <= 0) return false;
            var text = "";
            try { text = String(tf.contents || ""); } catch (eContents) {}
            text = text.replace(/[\s\r\n\t\u0016\u0018\u0003\uFFFC\uFEFF]/g, "");
            return text.length === 0;
        } catch (eTableOnlyCarrier) {}
        return false;
    }

    function isEmptyCarrierTextFrameSource(sourceId) {
        var src = sourceInfoById[String(sourceId)];
        if (!src || src.kind !== "TextFrame") return false;
        return src.textFrameClass === "editable"
                && src.hasText === false
                && Number(src.textLength || 0) === 0
                && String(src.parentKind || "") === "Group";
    }

    function hasDirectTableOnlyCarrierChild(sourceId, pageIndex) {
        var children = childIdsByParentId[String(sourceId)] || [];
        for (var ci = 0; ci < children.length; ci++) {
            var child = sourceInfoById[String(children[ci])];
            if (!child || child.pageIndex !== pageIndex) continue;
            if (isTableOnlyCarrierSource(child.id) || isEmptyCarrierTextFrameSource(child.id)) return true;
        }
        return false;
    }

    function sourceCanBeTableSiblingShell(sourceId, pageIndex) {
        var cacheKey = String(sourceId) + "|" + String(pageIndex);
        if (sourceCanBeTableSiblingShellCache.hasOwnProperty(cacheKey)) {
            return sourceCanBeTableSiblingShellCache[cacheKey];
        }
        var src = sourceInfoById[String(sourceId)];
        if (!src) {
            sourceCanBeTableSiblingShellCache[cacheKey] = false;
            return false;
        }
        if (src.pageIndex !== pageIndex
                || String(src.parentKind || "") !== "Group"
                || !isShellStructureSourceKind(src.kind)
                || src.kind === "GraphicLine"
                || sourceHasInlineAnchorAncestor(sourceId)
                || sourceHasEditableTextDescendant(sourceId)
                || sourceHasPlacedVisualSource(sourceId)) {
            sourceCanBeTableSiblingShellCache[cacheKey] = false;
            return false;
        }
        sourceCanBeTableSiblingShellCache[cacheKey] = !sourceHasChildSources(sourceId)
                ? sourceHasVisibleFillSource(src)
                : sourceHasVisibleShellSource(sourceId);
        return sourceCanBeTableSiblingShellCache[cacheKey];
    }

    function closedTextOwningShellInfo(sourceId) {
        var cacheKey = String(sourceId);
        if (closedTextOwningShellInfoCache.hasOwnProperty(cacheKey)) {
            return closedTextOwningShellInfoCache[cacheKey] || null;
        }
        var src = sourceInfoById[String(sourceId)];
        if (!src) {
            closedTextOwningShellInfoCache[cacheKey] = false;
            return null;
        }
        var kind = String(src.kind || "");
        if (kind !== "Group" && kind !== "Rectangle" && kind !== "Oval" && kind !== "Polygon") {
            closedTextOwningShellInfoCache[cacheKey] = false;
            return null;
        }
        if (sourceHasInlineAnchorAncestor(sourceId)) {
            closedTextOwningShellInfoCache[cacheKey] = false;
            return null;
        }

        var editableIds = allEditableTextDescendants(sourceId);
        if (!editableIds || editableIds.length === 0) {
            closedTextOwningShellInfoCache[cacheKey] = false;
            return null;
        }
        if (sourceHasPlacedVisualSource(sourceId) && editableIds.length !== 1) {
            closedTextOwningShellInfoCache[cacheKey] = false;
            return null;
        }
        if (!sourceHasVisibleShellSource(sourceId)) {
            closedTextOwningShellInfoCache[cacheKey] = false;
            return null;
        }
        if (directEditableTextChildren(sourceId).length > 0
                && descendantClosedTextShellRoots(sourceId).length > 0) {
            closedTextOwningShellInfoCache[cacheKey] = false;
            return null;
        }

        var sourceIds = collectSubtreeSourceIds(sourceId);
        var exportIds = collectExportSourceIds(sourceIds, editableIds, false, sourceId);
        if (!exportIds || exportIds.length === 0) {
            closedTextOwningShellInfoCache[cacheKey] = false;
            return null;
        }
        closedTextOwningShellInfoCache[cacheKey] = {
            sourceIds: sourceIds,
            exportIds: exportIds,
            editableIds: editableIds
        };
        return closedTextOwningShellInfoCache[cacheKey];
    }

    function hasMoreSpecificClosedTextOwningShell(sourceId, editableIds) {
        var editableKey = _sourceSetKey(editableIds || []);
        var cacheKey = String(sourceId) + "|" + editableKey;
        if (hasMoreSpecificClosedTextOwningShellCache.hasOwnProperty(cacheKey)) {
            return hasMoreSpecificClosedTextOwningShellCache[cacheKey];
        }
        var children = childIdsByParentId[String(sourceId)] || [];
        for (var ci = 0; ci < children.length; ci++) {
            var childId = children[ci];
            var childInfo = closedTextOwningShellInfo(childId);
            if (childInfo && _sourceSetKey(childInfo.editableIds || []) === editableKey) {
                hasMoreSpecificClosedTextOwningShellCache[cacheKey] = true;
                return true;
            }
            if (hasMoreSpecificClosedTextOwningShell(childId, editableIds)) {
                hasMoreSpecificClosedTextOwningShellCache[cacheKey] = true;
                return true;
            }
        }
        hasMoreSpecificClosedTextOwningShellCache[cacheKey] = false;
        return false;
    }

    function sourceIsDescendantOf(sourceId, ancestorId) {
        var current = sourceInfoById[String(sourceId)];
        for (var depth = 0; depth < 64 && current; depth++) {
            if (String(current.id) === String(ancestorId)) return true;
            if (current.parentId === null || current.parentId === undefined) return false;
            current = sourceInfoById[String(current.parentId)];
        }
        return false;
    }

    function directSiblingTextShellsPartitionEditableText(sourceId, editableIds) {
        if (!editableIds || editableIds.length === 0) return false;
        for (var ti = 0; ti < editableIds.length; ti++) {
            var textFrameId = editableIds[ti];
            var textFrame = sourceInfoById[String(textFrameId)];
            if (!textFrame || !sourceIsDescendantOf(textFrameId, sourceId)) return false;
            var shellIds = matchingDirectSiblingShellSourceIds(textFrameId, textFrame.pageIndex);
            if (!shellIds || shellIds.length !== 1) return false;
            var shellId = shellIds[0];
            if (!sourceIsDescendantOf(shellId, sourceId)) return false;
            var shellTextIds = matchingDirectSiblingTextFrameIds(shellId, textFrame.pageIndex);
            if (!shellTextIds || shellTextIds.length !== 1
                    || String(shellTextIds[0]) !== String(textFrameId)) {
                return false;
            }
        }
        return true;
    }

    function descendantClosedTextShellRoots(sourceId) {
        var cacheKey = String(sourceId);
        if (descendantClosedTextShellRootsCache.hasOwnProperty(cacheKey)) {
            return descendantClosedTextShellRootsCache[cacheKey];
        }
        var roots = [];
        function visit(nodeId) {
            var children = childIdsByParentId[String(nodeId)] || [];
            for (var ci = 0; ci < children.length; ci++) {
                var childId = children[ci];
                var childInfo = closedTextOwningShellInfo(childId);
                if (childInfo) {
                    roots.push({
                        sourceId: childId,
                        editableIds: childInfo.editableIds || [],
                        sourceIds: childInfo.sourceIds || []
                    });
                    continue;
                }
                visit(childId);
            }
        }
        visit(sourceId);
        descendantClosedTextShellRootsCache[cacheKey] = roots;
        return roots;
    }

    function descendantClosedTextShellsPartitionEditableText(sourceId, editableIds) {
        if (!editableIds || editableIds.length === 0) return false;
        var roots = descendantClosedTextShellRoots(sourceId);
        if (!roots || roots.length < 2) return false;
        var editableSeen = {};
        for (var ri = 0; ri < roots.length; ri++) {
            var ids = roots[ri].editableIds || [];
            for (var ei = 0; ei < ids.length; ei++) {
                editableSeen[String(ids[ei])] = true;
            }
        }
        for (var ti = 0; ti < editableIds.length; ti++) {
            if (!editableSeen[String(editableIds[ti])]) return false;
        }
        return true;
    }

    function closedTextShellDirectVisualBranchesOverlap(sourceId) {
        var children = childIdsByParentId[String(sourceId)] || [];
        if (!children || children.length < 2) return false;
        var branches = [];
        for (var ci = 0; ci < children.length; ci++) {
            var childId = children[ci];
            var child = sourceInfoById[String(childId)];
            if (!child) continue;
            if (String(child.kind || "") === "TextFrame") continue;
            if (!sourceHasVisibleShellSource(childId)) continue;
            var b = sourceBounds(childId);
            if (!b || boundsArea(b) <= 0) continue;
            branches.push({ id: childId, bounds: b });
        }
        for (var i = 0; i < branches.length; i++) {
            for (var j = i + 1; j < branches.length; j++) {
                if (boundsOverlapArea(branches[i].bounds, branches[j].bounds) > 0.01) {
                    return true;
                }
            }
        }
        return false;
    }

    function hasClosedTextOwningShellAncestorForEditableSet(sourceId, editableIds) {
        var src = sourceInfoById[String(sourceId)];
        if (!src) return false;
        var editableKey = _sourceSetKey(editableIds || []);
        var parentId = src.parentId;
        while (parentId !== null && parentId !== undefined) {
            var parentInfo = closedTextOwningShellInfo(parentId);
            if (parentInfo && _sourceSetKey(parentInfo.editableIds || []) === editableKey) {
                if (directSiblingTextShellsPartitionEditableText(parentId, parentInfo.editableIds || [])) {
                    return false;
                }
                return true;
            }
            var parent = sourceInfoById[String(parentId)];
            if (!parent) break;
            parentId = parent.parentId;
        }
        return false;
    }

    function sourceSetsEqual(a, b) {
        return _sourceSetKey(a || []) === _sourceSetKey(b || []);
    }

    function sourceIdsMinusInDeclaredShellScope(sourceIds, removedIds) {
        var removed = _sourceIdSet(removedIds || []);
        var ids = [];
        var seen = {};
        for (var i = 0; sourceIds && i < sourceIds.length; i++) {
            if (removed[String(sourceIds[i])]) continue;
            _pushUniqueId(ids, seen, sourceIds[i]);
        }
        return _sortedNumericIds(ids);
    }

    function upsertDecorationShellCandidate(shellItem, attrs) {
        var sourceKey = _sourceSetKey(attrs.sourceObjectIds || []);
        if (upsertDecorationCandidateIndex === null) {
            upsertDecorationCandidateIndex = {};
            for (var ci = 0; candidates && ci < candidates.length; ci++) {
                var indexedCandidate = candidates[ci];
                if (!indexedCandidate || indexedCandidate.passId !== "pass.decoration_groups") continue;
                var indexedKey = String(indexedCandidate.pageIndex) + "|" + _sourceSetKey(indexedCandidate.sourceObjectIds || []);
                upsertDecorationCandidateIndex[indexedKey] = indexedCandidate;
            }
        }
        var candidateKey = String(attrs.pageIndex) + "|" + sourceKey;
        var candidate = upsertDecorationCandidateIndex[candidateKey] || null;
        if (candidate) {
            candidate.unit = "GROUP_OR_ITEM";
            candidate.mode = "TEXTLESS_CANDIDATE";
            candidate.candidatePurpose = "SHELL_CANDIDATE";
            candidate.compositeRole = attrs.compositeRole || candidate.compositeRole || "direct_child_shell_slot";
            candidate.slotRole = attrs.slotRole || candidate.slotRole || "direct_child_shell_slot";
            candidate.exportSourceObjectIds = attrs.exportSourceObjectIds;
            candidate.exportTargetObjectId = attrs.exportTargetObjectId;
            candidate.hiddenVisualSourceObjectIds = attrs.hiddenVisualSourceObjectIds || [];
            candidate.visualSourceObjectIds = attrs.visualSourceObjectIds || candidate.visualSourceObjectIds || [];
            candidate.styleSourceObjectIds = attrs.styleSourceObjectIds || candidate.styleSourceObjectIds || [];
            candidate.editableTextFrameIds = attrs.editableTextFrameIds;
            candidate.hiddenTextFrameIds = attrs.hiddenTextFrameIds;
            candidate.requiresTextHidden = true;
            candidate.textOwner = "hwpx_tf";
            candidate.containsEditableText = false;
            candidate.completePngTextAllowed = false;
            candidate.primarySourceObjectId = attrs.primarySourceObjectId;
            candidate.bounds = attrs.bounds || candidate.bounds;
            candidate.parentId = attrs.parentId;
            candidate.parentKind = attrs.parentKind;
            candidate.zOrder = attrs.zOrder;
            candidate.composite = true;
            if (attrs.sourceDeclaredClosedTextShell === true) {
                candidate.sourceDeclaredClosedTextShell = true;
            }
            if (attrs.directSiblingTextShellSlot === true) {
                candidate.directSiblingTextShellSlot = true;
            }
            if (attrs.ungroupedOutlineTextShellSlot === true) {
                candidate.ungroupedOutlineTextShellSlot = true;
            }
            if (attrs.tableSiblingShellSlot === true) {
                candidate.tableSiblingShellSlot = true;
            }
            candidate.candidateId = _candidateCompositeId(
                    candidate.passId, candidate.pageIndex, candidate.sourceObjectIds, candidate.slotRole);
            return;
        }
        _pushExtractionCandidate(candidates, seen, "pass.decoration_groups", shellItem, attrs);
        if (candidates && candidates.length > 0) {
            upsertDecorationCandidateIndex[candidateKey] = candidates[candidates.length - 1];
        }
    }

    function sourceBounds(sourceId) {
        var src = sourceInfoById[String(sourceId)];
        return src ? src.bounds : null;
    }

    function sourceTextFrameFitsShell(shellSourceId, textFrameSourceId) {
        var shellBounds = sourceBounds(shellSourceId);
        var tfBounds = sourceBounds(textFrameSourceId);
        var tfArea = boundsArea(tfBounds);
        if (!shellBounds || !tfBounds || tfArea <= 0) return false;
        return boundsOverlapArea(shellBounds, tfBounds) / tfArea >= 0.75;
    }

    function matchingDirectSiblingTextFrameIds(shellSourceId, pageIndex) {
        var cacheKey = String(shellSourceId) + "|" + String(pageIndex);
        if (matchingDirectSiblingTextFrameIdsCache.hasOwnProperty(cacheKey)) {
            return matchingDirectSiblingTextFrameIdsCache[cacheKey];
        }
        var shellSource = sourceInfoById[String(shellSourceId)];
        if (!shellSource || shellSource.parentId === null || shellSource.parentId === undefined) {
            matchingDirectSiblingTextFrameIdsCache[cacheKey] = [];
            return [];
        }
        if (!sourceCanBeDirectSiblingTextShell(shellSourceId)) {
            matchingDirectSiblingTextFrameIdsCache[cacheKey] = [];
            return [];
        }
        var siblings = childIdsByParentId[String(shellSource.parentId)] || [];
        var matchingTextFrameIds = [];
        for (var mi = 0; mi < siblings.length; mi++) {
            var matchSiblingId = siblings[mi];
            if (String(matchSiblingId) === String(shellSourceId)) continue;
            var matchSibling = sourceInfoById[String(matchSiblingId)];
            if (!matchSibling || matchSibling.kind !== "TextFrame") continue;
            if (matchSibling.textFrameClass !== "editable" || matchSibling.hasText !== true) continue;
            if (matchSibling.pageIndex !== pageIndex) continue;
            if (!sourceTextFrameFitsShell(shellSourceId, matchSiblingId)) continue;
            matchingTextFrameIds.push(matchSiblingId);
        }
        matchingDirectSiblingTextFrameIdsCache[cacheKey] = matchingTextFrameIds;
        return matchingDirectSiblingTextFrameIdsCache[cacheKey];
    }

    function matchingDescendantSiblingTextFrameIds(shellSourceId, pageIndex) {
        var cacheKey = String(shellSourceId) + "|" + String(pageIndex);
        if (matchingDescendantSiblingTextFrameIdsCache.hasOwnProperty(cacheKey)) {
            return matchingDescendantSiblingTextFrameIdsCache[cacheKey];
        }
        var shellSource = sourceInfoById[String(shellSourceId)];
        if (!shellSource || shellSource.parentId === null || shellSource.parentId === undefined) {
            matchingDescendantSiblingTextFrameIdsCache[cacheKey] = [];
            return [];
        }
        if (!sourceCanBeDirectSiblingTextShell(shellSourceId)) {
            matchingDescendantSiblingTextFrameIdsCache[cacheKey] = [];
            return [];
        }
        var parentEditableTextIds = editableTextDescendantsUnderParent(shellSource.parentId, pageIndex);
        var matchingTextFrameIds = [];
        var seen = {};
        for (var ei = 0; parentEditableTextIds && ei < parentEditableTextIds.length; ei++) {
            var textFrameId = parentEditableTextIds[ei];
            if (seen[String(textFrameId)]) continue;
            var textFrame = sourceInfoById[String(textFrameId)];
            if (!textFrame) continue;
            if (textFrameSourceHasOwnShellStyle(textFrame)) continue;
            if (matchingDirectSiblingShellSourceIds(textFrameId, pageIndex).length > 0) continue;
            if (!sourceTextFrameFitsShell(shellSourceId, textFrameId)) continue;
            seen[String(textFrameId)] = true;
            matchingTextFrameIds.push(textFrameId);
        }
        matchingDescendantSiblingTextFrameIdsCache[cacheKey] = _sortedNumericIds(matchingTextFrameIds);
        return matchingDescendantSiblingTextFrameIdsCache[cacheKey];
    }

    function sourceCanBePageSiblingTextShell(shellSourceId) {
        var cacheKey = String(shellSourceId);
        if (sourceCanBePageSiblingTextShellCache.hasOwnProperty(cacheKey)) {
            return sourceCanBePageSiblingTextShellCache[cacheKey];
        }
        var src = sourceInfoById[String(shellSourceId)];
        if (!src) {
            sourceCanBePageSiblingTextShellCache[cacheKey] = false;
            return false;
        }
        var kind = String(src.kind || "");
        if (kind !== "Rectangle" && kind !== "Oval" && kind !== "Polygon") {
            sourceCanBePageSiblingTextShellCache[cacheKey] = false;
            return false;
        }
        if (sourceInfoIsInlineFlow(src)
                || sourceHasInlineAnchorAncestor(shellSourceId)
                || sourceHasEditableTextDescendant(shellSourceId)
                || sourceHasPlacedVisualSource(shellSourceId)
                || !sourceHasVisiblePaint(src)) {
            sourceCanBePageSiblingTextShellCache[cacheKey] = false;
            return false;
        }
        if (src.parentId !== null && src.parentId !== undefined) {
            sourceCanBePageSiblingTextShellCache[cacheKey] = false;
            return false;
        }
        sourceCanBePageSiblingTextShellCache[cacheKey] = true;
        return true;
    }

    function pageSiblingTextFrameFitScore(shellSourceId, textFrameSourceId) {
        var shellBounds = sourceBounds(shellSourceId);
        var tfBounds = sourceBounds(textFrameSourceId);
        var tfArea = boundsArea(tfBounds);
        var shellArea = boundsArea(shellBounds);
        if (!shellBounds || !tfBounds || tfArea <= 0 || shellArea <= 0) return 0;
        var intersection = boundsOverlapArea(shellBounds, tfBounds);
        if (intersection <= 0) return 0;
        var tfCover = intersection / tfArea;
        var shellCover = intersection / shellArea;
        if (tfCover < 0.75) return 0;
        if (shellCover < 0.45) return 0;
        if (shellArea > tfArea * 6.0) return 0;
        return Math.min(tfCover, shellCover);
    }

    function matchingPageSiblingTextFrameIds(shellSourceId, pageIndex) {
        var cacheKey = String(shellSourceId) + "|" + String(pageIndex);
        if (matchingPageSiblingTextFrameIdsCache.hasOwnProperty(cacheKey)) {
            return matchingPageSiblingTextFrameIdsCache[cacheKey];
        }
        var shellSource = sourceInfoById[String(shellSourceId)];
        if (!shellSource || !sourceCanBePageSiblingTextShell(shellSourceId)) {
            matchingPageSiblingTextFrameIdsCache[cacheKey] = [];
            return [];
        }
        var shellZ = shellSource.zOrder !== null && shellSource.zOrder !== undefined
                ? Number(shellSource.zOrder)
                : null;
        var matchingTextFrameIds = [];
        for (var ti = 0; ti < sourceBuckets.editableTextFrames.length; ti++) {
            var textFrame = sourceBuckets.editableTextFrames[ti];
            if (!textFrame || textFrame.pageIndex !== pageIndex) continue;
            if (String(textFrame.layerName || "") !== String(shellSource.layerName || "")) continue;
            var tfZ = textFrame.zOrder !== null && textFrame.zOrder !== undefined
                    ? Number(textFrame.zOrder)
                    : null;
            if (shellZ !== null && tfZ !== null) {
                if (!(shellZ < tfZ && (tfZ - shellZ) <= 16)) continue;
            }
            if (pageSiblingTextFrameFitScore(shellSourceId, textFrame.id) <= 0) continue;
            matchingTextFrameIds.push(textFrame.id);
        }
        matchingPageSiblingTextFrameIdsCache[cacheKey] = _sortedNumericIds(matchingTextFrameIds);
        return matchingPageSiblingTextFrameIdsCache[cacheKey];
    }

    function editableTextDescendantsUnderParent(parentId, pageIndex) {
        var cacheKey = String(parentId) + "|" + String(pageIndex);
        if (parentEditableTextDescendantsCache.hasOwnProperty(cacheKey)) {
            return parentEditableTextDescendantsCache[cacheKey];
        }
        var ids = [];
        var seenIds = {};
        var children = childIdsByParentId[String(parentId)] || [];
        for (var ci = 0; ci < children.length; ci++) {
            var childId = children[ci];
            var editableIds = allEditableTextDescendants(childId);
            for (var ei = 0; editableIds && ei < editableIds.length; ei++) {
                var textFrameId = editableIds[ei];
                var textFrame = sourceInfoById[String(textFrameId)];
                if (!textFrame || textFrame.pageIndex !== pageIndex) continue;
                _pushUniqueId(ids, seenIds, textFrameId);
            }
        }
        parentEditableTextDescendantsCache[cacheKey] = _sortedNumericIds(ids);
        return parentEditableTextDescendantsCache[cacheKey];
    }

    function matchingDirectSiblingShellSourceIds(textFrameSourceId, pageIndex) {
        var cacheKey = String(textFrameSourceId) + "|" + String(pageIndex);
        if (matchingDirectSiblingShellSourceIdsCache.hasOwnProperty(cacheKey)) {
            return matchingDirectSiblingShellSourceIdsCache[cacheKey];
        }
        var textFrame = sourceInfoById[String(textFrameSourceId)];
        if (!textFrame || textFrame.parentId === null || textFrame.parentId === undefined) {
            matchingDirectSiblingShellSourceIdsCache[cacheKey] = [];
            return [];
        }
        var siblings = childIdsByParentId[String(textFrame.parentId)] || [];
        var shellIds = [];
        var seen = {};
        for (var si = 0; si < siblings.length; si++) {
            var siblingId = siblings[si];
            if (String(siblingId) === String(textFrameSourceId)) continue;
            var sibling = sourceInfoById[String(siblingId)];
            if (!sibling || sibling.pageIndex !== pageIndex) continue;
            if (!sourceCanBeDirectSiblingTextShell(siblingId)) continue;
            if (!sourceTextFrameFitsShell(siblingId, textFrameSourceId)) continue;
            _pushUniqueId(shellIds, seen, siblingId);
        }
        matchingDirectSiblingShellSourceIdsCache[cacheKey] = _sortedNumericIds(shellIds);
        return matchingDirectSiblingShellSourceIdsCache[cacheKey];
    }

    function sourceCanBeDirectSiblingTextShell(shellSourceId) {
        var cacheKey = String(shellSourceId);
        if (sourceCanBeDirectSiblingTextShellCache.hasOwnProperty(cacheKey)) {
            return sourceCanBeDirectSiblingTextShellCache[cacheKey];
        }
        var src = sourceInfoById[String(shellSourceId)];
        if (!src) {
            sourceCanBeDirectSiblingTextShellCache[cacheKey] = false;
            return false;
        }
        var kind = String(src.kind || "");
        sourceCanBeDirectSiblingTextShellCache[cacheKey] = kind === "Rectangle" || kind === "Oval" || kind === "Polygon";
        if (!sourceCanBeDirectSiblingTextShellCache[cacheKey]) return false;
        if (!sourceParentCanHostSiblingTextShell(src)) {
            sourceCanBeDirectSiblingTextShellCache[cacheKey] = false;
            return false;
        }
        if (!parentHasEditableTextDescendantOnPage(src.parentId, src.pageIndex)) {
            sourceCanBeDirectSiblingTextShellCache[cacheKey] = false;
            return false;
        }
        if (sourceHasEditableTextDescendant(shellSourceId)) {
            sourceCanBeDirectSiblingTextShellCache[cacheKey] = false;
            return false;
        }
        sourceCanBeDirectSiblingTextShellCache[cacheKey] = !sourceHasChildSources(shellSourceId)
                ? sourceHasVisiblePaint(src)
                : sourceHasVisibleShellSource(shellSourceId);
        return sourceCanBeDirectSiblingTextShellCache[cacheKey];
    }

    function sourceParentCanHostSiblingTextShell(src) {
        if (!src || src.parentId === null || src.parentId === undefined) return false;
        if (String(src.parentKind || "") === "Group") return true;
        var parent = sourceInfoById[String(src.parentId)];
        var parentKind = String(parent && (parent.kind || parent.type || parent.itemType) || "");
        return parentKind === "Group" || parentKind === "page_object";
    }

    function parentHasEditableTextDescendantOnPage(parentId, pageIndex) {
        if (parentId === null || parentId === undefined) return false;
        return editableTextDescendantsUnderParent(parentId, pageIndex).length > 0;
    }

    function sourceCanBeNativeParentTextShell(shellSourceId) {
        var cacheKey = String(shellSourceId);
        if (sourceCanBeNativeParentTextShellCache.hasOwnProperty(cacheKey)) {
            return sourceCanBeNativeParentTextShellCache[cacheKey];
        }
        var src = sourceInfoById[String(shellSourceId)];
        if (!src) {
            sourceCanBeNativeParentTextShellCache[cacheKey] = false;
            return false;
        }
        var kind = String(src.kind || "");
        var nativeShape = kind === "Rectangle" || kind === "Oval" || kind === "Polygon";
        var groupedShell = kind === "Group";
        if (!nativeShape && !groupedShell) {
            sourceCanBeNativeParentTextShellCache[cacheKey] = false;
            return false;
        }
        if (nativeShape && !sourceHasVisiblePaint(src)) {
            sourceCanBeNativeParentTextShellCache[cacheKey] = false;
            return false;
        }
        if (groupedShell && !sourceHasVisibleShellSource(shellSourceId)) {
            sourceCanBeNativeParentTextShellCache[cacheKey] = false;
            return false;
        }
        if (sourceHasPlacedVisualSource(shellSourceId)) {
            sourceCanBeNativeParentTextShellCache[cacheKey] = false;
            return false;
        }
        var directTextIds = directEditableTextChildren(shellSourceId);
        if (!directTextIds || directTextIds.length === 0) {
            sourceCanBeNativeParentTextShellCache[cacheKey] = false;
            return false;
        }
        var allTextIds = allEditableTextDescendants(shellSourceId);
        if (!sourceSetsEqual(directTextIds, allTextIds)) {
            sourceCanBeNativeParentTextShellCache[cacheKey] = false;
            return false;
        }
        if (groupedShell && !sourceGroupChildrenAreTextOrShellMaterial(shellSourceId, directTextIds)) {
            sourceCanBeNativeParentTextShellCache[cacheKey] = false;
            return false;
        }
        sourceCanBeNativeParentTextShellCache[cacheKey] = true;
        return sourceCanBeNativeParentTextShellCache[cacheKey];
    }

    function sourceGroupChildrenAreTextOrShellMaterial(groupSourceId, directTextIds) {
        var directTextSet = _sourceIdSet(directTextIds || []);
        var children = childIdsByParentId[String(groupSourceId)] || [];
        var hasVisualShellChild = false;
        for (var ci = 0; ci < children.length; ci++) {
            var childId = children[ci];
            var child = sourceInfoById[String(childId)];
            if (!child) return false;
            var kind = String(child.kind || "");
            if (directTextSet[String(childId)]) continue;
            if (kind === "TextFrame") return false;
            if (!isShellStructureSourceKind(kind)) return false;
            if (sourceHasEditableTextDescendant(childId)) return false;
            if (sourceHasPlacedVisualSource(childId)) return false;
            if (sourceHasVisibleShellSource(childId)) {
                hasVisualShellChild = true;
                continue;
            }
            if (kind !== "Group") return false;
        }
        return hasVisualShellChild;
    }

    function sourceHasVisiblePaint(src) {
        return _sourceInfoHasVisiblePaintMetadata(src);
    }

    function sourceTreeHasVisibleStroke(sourceId) {
        var cacheKey = String(sourceId);
        if (sourceTreeHasVisibleStrokeCache.hasOwnProperty(cacheKey)) {
            return sourceTreeHasVisibleStrokeCache[cacheKey];
        }
        var src = sourceInfoById[String(sourceId)];
        if (!src) {
            sourceTreeHasVisibleStrokeCache[cacheKey] = false;
            return false;
        }
        var strokeName = String(src.strokeColorName || src.strokeColor || "");
        var strokeWeight = Number(src.strokeWeight || 0);
        if (strokeName && strokeName !== "None" && strokeName !== "[None]" && strokeWeight > 0) {
            sourceTreeHasVisibleStrokeCache[cacheKey] = true;
            return true;
        }
        var children = childIdsByParentId[String(sourceId)] || [];
        for (var ci = 0; ci < children.length; ci++) {
            if (sourceTreeHasVisibleStroke(children[ci])) {
                sourceTreeHasVisibleStrokeCache[cacheKey] = true;
                return true;
            }
        }
        sourceTreeHasVisibleStrokeCache[cacheKey] = false;
        return false;
    }

    function sourceTreeHasNonPaperFill(sourceId) {
        var cacheKey = String(sourceId);
        if (sourceTreeHasNonPaperFillCache.hasOwnProperty(cacheKey)) {
            return sourceTreeHasNonPaperFillCache[cacheKey];
        }
        var src = sourceInfoById[String(sourceId)];
        if (!src) {
            sourceTreeHasNonPaperFillCache[cacheKey] = false;
            return false;
        }
        var fillName = String(src.fillColorName || src.fillColor || "");
        if (fillName && fillName !== "None" && fillName !== "[None]" && fillName !== "Paper" && fillName !== "[Paper]") {
            sourceTreeHasNonPaperFillCache[cacheKey] = true;
            return true;
        }
        var children = childIdsByParentId[String(sourceId)] || [];
        for (var ci = 0; ci < children.length; ci++) {
            if (sourceTreeHasNonPaperFill(children[ci])) {
                sourceTreeHasNonPaperFillCache[cacheKey] = true;
                return true;
            }
        }
        sourceTreeHasNonPaperFillCache[cacheKey] = false;
        return false;
    }

    function boundsUnionOfSourceIds(sourceIds) {
        var u = null;
        for (var i = 0; sourceIds && i < sourceIds.length; i++) {
            var b = sourceBounds(sourceIds[i]);
            if (!b || b.length < 4) continue;
            if (!u) {
                u = [b[0], b[1], b[2], b[3]];
            } else {
                u[0] = Math.min(u[0], b[0]);
                u[1] = Math.min(u[1], b[1]);
                u[2] = Math.max(u[2], b[2]);
                u[3] = Math.max(u[3], b[3]);
            }
        }
        return u;
    }

    function boundsIntersectsWithPad(a, b, pad) {
        if (!a || !b || a.length < 4 || b.length < 4) return false;
        pad = Number(pad || 0);
        return !(a[2] < b[0] - pad || a[0] > b[2] + pad
                || a[3] < b[1] - pad || a[1] > b[3] + pad);
    }

    function boundsContainsWithPad(outer, inner, pad) {
        if (!outer || !inner || outer.length < 4 || inner.length < 4) return false;
        pad = Number(pad || 0);
        return outer[0] <= inner[0] + pad
                && outer[1] <= inner[1] + pad
                && outer[2] >= inner[2] - pad
                && outer[3] >= inner[3] - pad;
    }

    function sourceCanBeUngroupedOutlineShellFragment(sourceId, textFrameSource) {
        var src = sourceInfoById[String(sourceId)];
        if (!src || !textFrameSource) return false;
        if (String(src.kind || "") === "TextFrame") return false;
        if (!isShellStructureSourceKind(src.kind)) return false;
        if (src.pageIndex !== textFrameSource.pageIndex) return false;
        if (String(src.parentId) !== String(textFrameSource.parentId)) return false;
        if (String(src.layerName || "") !== String(textFrameSource.layerName || "")) return false;
        if (sourceHasInlineAnchorAncestor(sourceId)) return false;
        if (sourceHasEditableTextDescendant(sourceId)) return false;
        if (sourceHasPlacedVisualSource(sourceId)) return false;
        if (!sourceTreeHasVisibleStroke(sourceId)) return false;
        if (sourceTreeHasNonPaperFill(sourceId)) return false;
        if (!boundsIntersectsWithPad(src.bounds, textFrameSource.bounds, 4)) return false;
        return true;
    }

    function textFrameSourceHasOwnShellStyle(tfSource) {
        if (!tfSource || String(tfSource.kind || "") !== "TextFrame") return false;
        var fill = String(tfSource.fillColorName || tfSource.fillColor || "");
        if (fill && fill !== "None" && fill !== "[None]") return true;
        var stroke = String(tfSource.strokeColorName || tfSource.strokeColor || "");
        var weight = tfSource.strokeWeight !== null && tfSource.strokeWeight !== undefined
                ? Number(tfSource.strokeWeight)
                : 0;
        return stroke && stroke !== "None" && stroke !== "[None]" && !isNaN(weight) && weight > 0.01;
    }

    function appendUngroupedOutlineTextShellCandidates() {
        var generated = {};
        var textFrameSources = sourceBuckets.editableTextFrames || [];
        for (var ti = 0; ti < textFrameSources.length; ti++) {
            var tfSource = textFrameSources[ti];
            if (tfSource.parentId === null || tfSource.parentId === undefined) continue;
            if (sourceHasInlineAnchorAncestor(tfSource.id)) continue;
            if (textFrameSourceHasOwnShellStyle(tfSource)) continue;

            var visualRoots = [];
            var visualSeen = {};
            var siblings = childIdsByParentId[String(tfSource.parentId)] || [];
            for (var si = 0; si < siblings.length; si++) {
                var siblingId = siblings[si];
                if (String(siblingId) === String(tfSource.id)) continue;
                if (!sourceCanBeUngroupedOutlineShellFragment(siblingId, tfSource)) continue;
                _pushUniqueId(visualRoots, visualSeen, siblingId);
            }
            if (visualRoots.length < 2) continue;
            var unionBounds = boundsUnionOfSourceIds(visualRoots);
            if (!boundsContainsWithPad(unionBounds, tfSource.bounds, 4)) continue;

            var exportIds = [];
            var exportSeen = {};
            for (var vi = 0; vi < visualRoots.length; vi++) {
                var subtreeIds = collectSubtreeSourceIds(visualRoots[vi]);
                var subtreeExportIds = collectExportSourceIds(subtreeIds, []);
                for (var ei = 0; ei < subtreeExportIds.length; ei++) {
                    _pushUniqueId(exportIds, exportSeen, subtreeExportIds[ei]);
                }
            }
            exportIds = _sortedNumericIds(exportIds);
            if (!exportIds || exportIds.length === 0) continue;

            var sourceIds = _sortedNumericIds(visualRoots.concat([tfSource.id]));
            var sourceKey = _sourceSetKey(sourceIds);
            if (generated[sourceKey]) continue;
            generated[sourceKey] = true;

            var primarySourceId = visualRoots[0];
            var primarySource = sourceInfoById[String(primarySourceId)] || tfSource;
            var shellItem = planCache && planCache.domItem ? planCache.domItem(primarySourceId) : null;
            if (!shellItem) shellItem = { id: primarySourceId };
            upsertDecorationShellCandidate(shellItem, {
                sourceObjectIds: sourceIds,
                pageIndex: tfSource.pageIndex,
                kind: primarySource.kind,
                unit: "GROUP_OR_ITEM",
                mode: "TEXTLESS_CANDIDATE",
                candidatePurpose: "SHELL_CANDIDATE",
                primarySourceObjectId: primarySourceId,
                bounds: unionBounds,
                parentId: tfSource.parentId,
                parentKind: tfSource.parentKind,
                composite: true,
                compositeRole: "ungrouped_outline_text_shell_slot",
                slotRole: "direct_child_shell_slot",
                exportSourceObjectIds: exportIds,
                exportTargetObjectId: null,
                hiddenVisualSourceObjectIds: [],
                visualSourceObjectIds: exportIds.slice(0),
                editableTextFrameIds: [tfSource.id],
                hiddenTextFrameIds: [tfSource.id],
                requiresTextHidden: true,
                textOwner: "hwpx_tf",
                containsEditableText: false,
                completePngTextAllowed: false,
                ungroupedOutlineTextShellSlot: true,
                zOrder: primarySource.zOrder,
                suffix: "ungrouped_outline_text_shell_slot"
            });
        }
    }

    function appendDirectSiblingTextShellCandidates() {
        var generated = {};
        var shellSources = sourceBuckets.shellShapes || [];
        for (var si = 0; si < shellSources.length; si++) {
            var shellSource = shellSources[si];
            if (!sourceCanBeDirectSiblingTextShell(shellSource.id)) continue;
            var parentId = shellSource.parentId;
            if (parentId === null || parentId === undefined) continue;
            var shellItem = planCache && planCache.domItem ? planCache.domItem(shellSource.id) : null;
            if (!shellItem) shellItem = { id: shellSource.id };

            var matchingTextFrameIds = matchingDirectSiblingTextFrameIds(shellSource.id, shellSource.pageIndex);
            if (matchingTextFrameIds.length !== 1) continue;

            for (var ti = 0; ti < matchingTextFrameIds.length; ti++) {
                var siblingId = matchingTextFrameIds[ti];
                if (hasClosedTextOwningShellAncestorForEditableSet(shellSource.id, [siblingId])) {
                    continue;
                }

                var shellSourceIds = collectSubtreeSourceIds(shellSource.id);
                var shellExportIds = collectTextShellVisualExportSourceIds(shellSource.id, [siblingId], true);
                var sourceObjectIds = _sortedNumericIds(shellSourceIds.concat([siblingId]));
                var sourceKey = _sourceSetKey(sourceObjectIds);
                if (generated[sourceKey]) continue;
                generated[sourceKey] = true;

                upsertDecorationShellCandidate(shellItem, {
                    sourceObjectIds: sourceObjectIds,
                    pageIndex: shellSource.pageIndex,
                    kind: shellSource.kind,
                    unit: "GROUP_OR_ITEM",
                    mode: "TEXTLESS_CANDIDATE",
                    candidatePurpose: "SHELL_CANDIDATE",
                    primarySourceObjectId: shellSource.id,
                    bounds: shellSource.bounds,
                    parentId: shellSource.parentId,
                    parentKind: shellSource.parentKind,
                    composite: true,
                    compositeRole: "direct_child_shell_slot",
                    slotRole: "direct_child_shell_slot",
                    exportSourceObjectIds: shellExportIds,
                    exportTargetObjectId: shellSource.id,
                    hiddenVisualSourceObjectIds: [],
                    visualSourceObjectIds: shellExportIds.slice(0),
                    editableTextFrameIds: [siblingId],
                    hiddenTextFrameIds: [siblingId],
                    requiresTextHidden: true,
                    textOwner: "hwpx_tf",
                    containsEditableText: false,
                    completePngTextAllowed: false,
                    directSiblingTextShellSlot: true,
                    zOrder: shellSource.zOrder,
                    suffix: "direct_sibling_text_shell_slot"
                });
            }
        }
    }

    function appendPageSiblingTextShellCandidates() {
        var generated = {};
        var shellSources = sourceBuckets.shellShapes || [];
        for (var si = 0; si < shellSources.length; si++) {
            var shellSource = shellSources[si];
            if (!sourceCanBePageSiblingTextShell(shellSource.id)) continue;
            var matchingTextFrameIds = matchingPageSiblingTextFrameIds(shellSource.id, shellSource.pageIndex);
            if (matchingTextFrameIds.length !== 1) continue;

            var siblingId = matchingTextFrameIds[0];
            var shellExportIds = collectTextShellVisualExportSourceIds(shellSource.id, [siblingId]);
            if (!shellExportIds || shellExportIds.length === 0) continue;
            var sourceObjectIds = _sortedNumericIds(shellExportIds.concat([siblingId]));
            var sourceKey = _sourceSetKey(sourceObjectIds);
            if (generated[sourceKey]) continue;
            generated[sourceKey] = true;

            var shellItem = planCache && planCache.domItem ? planCache.domItem(shellSource.id) : null;
            if (!shellItem) shellItem = { id: shellSource.id };
            upsertDecorationShellCandidate(shellItem, {
                sourceObjectIds: sourceObjectIds,
                pageIndex: shellSource.pageIndex,
                kind: shellSource.kind,
                unit: "GROUP_OR_ITEM",
                mode: "TEXTLESS_CANDIDATE",
                candidatePurpose: "SHELL_CANDIDATE",
                primarySourceObjectId: shellSource.id,
                bounds: shellSource.bounds,
                parentId: shellSource.parentId,
                parentKind: shellSource.parentKind,
                composite: true,
                compositeRole: "page_sibling_text_shell_slot",
                slotRole: "direct_child_shell_slot",
                exportSourceObjectIds: shellExportIds,
                exportTargetObjectId: shellSource.id,
                hiddenVisualSourceObjectIds: [],
                visualSourceObjectIds: shellExportIds.slice(0),
                editableTextFrameIds: [siblingId],
                hiddenTextFrameIds: [siblingId],
                requiresTextHidden: true,
                textOwner: "hwpx_tf",
                containsEditableText: false,
                completePngTextAllowed: false,
                pageSiblingTextShellSlot: true,
                zOrder: shellSource.zOrder,
                suffix: "page_sibling_text_shell_slot"
            });
        }
    }

    function appendNativeParentTextShellCandidates() {
        var generated = {};
        var shellSources = (sourceBuckets.shellShapes || []).concat(sourceBuckets.groups || []);
        for (var si = 0; si < shellSources.length; si++) {
            var shellSource = shellSources[si];
            if (!sourceHasChildSources(shellSource.id)) continue;
            if (!sourceCanBeNativeParentTextShell(shellSource.id)) continue;

            var editableIds = directEditableTextChildren(shellSource.id);
            var shellExportIds = collectTextShellVisualExportSourceIds(shellSource.id, editableIds);
            var sourceObjectIds = _sortedNumericIds(shellExportIds.concat(editableIds));
            var sourceKey = _sourceSetKey(sourceObjectIds);
            if (generated[sourceKey]) continue;
            generated[sourceKey] = true;

            var shellItem = planCache && planCache.domItem ? planCache.domItem(shellSource.id) : null;
            if (!shellItem) shellItem = { id: shellSource.id };
            upsertDecorationShellCandidate(shellItem, {
                sourceObjectIds: sourceObjectIds,
                pageIndex: shellSource.pageIndex,
                kind: shellSource.kind,
                unit: "GROUP_OR_ITEM",
                mode: "TEXTLESS_CANDIDATE",
                candidatePurpose: "SHELL_CANDIDATE",
                primarySourceObjectId: shellSource.id,
                bounds: shellSource.bounds,
                parentId: shellSource.parentId,
                parentKind: shellSource.parentKind,
                composite: true,
                compositeRole: "native_parent_text_shell_slot",
                slotRole: "direct_child_shell_slot",
                exportSourceObjectIds: shellExportIds,
                exportTargetObjectId: shellSource.id,
                hiddenVisualSourceObjectIds: [],
                visualSourceObjectIds: shellExportIds.slice(0),
                editableTextFrameIds: editableIds,
                hiddenTextFrameIds: editableIds,
                requiresTextHidden: true,
                textOwner: "hwpx_tf",
                containsEditableText: false,
                completePngTextAllowed: false,
                nativeParentTextShellSlot: true,
                zOrder: shellSource.zOrder,
                suffix: "native_parent_text_shell_slot"
            });
        }
    }

    function appendDescendantSiblingTextShellCandidates() {
        var generated = {};
        var shellSources = sourceBuckets.shellShapes || [];
        for (var si = 0; si < shellSources.length; si++) {
            var shellSource = shellSources[si];
            if (!sourceCanBeDirectSiblingTextShell(shellSource.id)) {
                continue;
            }
            var matchingTextFrameIds = matchingDescendantSiblingTextFrameIds(
                    shellSource.id, shellSource.pageIndex);
            if (!matchingTextFrameIds || matchingTextFrameIds.length === 0) continue;
            var directIds = matchingDirectSiblingTextFrameIds(shellSource.id, shellSource.pageIndex);
            if (directIds && directIds.length === matchingTextFrameIds.length
                    && _sourceSetKey(directIds) === _sourceSetKey(matchingTextFrameIds)) {
                continue;
            }
            var shellItem = planCache && planCache.domItem ? planCache.domItem(shellSource.id) : null;
            if (!shellItem) shellItem = { id: shellSource.id };
            var sourceObjectIds = [shellSource.id];
            var sourceKey = _sourceSetKey(sourceObjectIds);
            if (generated[sourceKey]) continue;
            generated[sourceKey] = true;
            _pushExtractionCandidate(candidates, seen, "pass.decoration_groups", shellItem, {
                sourceObjectIds: sourceObjectIds,
                pageIndex: shellSource.pageIndex,
                kind: shellSource.kind,
                unit: "GROUP_OR_ITEM",
                mode: "TEXTLESS_CANDIDATE",
                candidatePurpose: "SHELL_CANDIDATE",
                primarySourceObjectId: shellSource.id,
                bounds: shellSource.bounds,
                parentId: shellSource.parentId,
                parentKind: shellSource.parentKind,
                composite: true,
                compositeRole: "descendant_sibling_text_shell_slot",
                slotRole: "direct_child_shell_slot",
                exportSourceObjectIds: [shellSource.id],
                exportTargetObjectId: shellSource.id,
                hiddenVisualSourceObjectIds: [],
                visualSourceObjectIds: [shellSource.id],
                editableTextFrameIds: [],
                hiddenTextFrameIds: [],
                requiresTextHidden: false,
                textOwner: "none",
                containsEditableText: false,
                completePngTextAllowed: false,
                descendantSiblingTextShellSlot: true,
                zOrder: shellSource.zOrder,
                suffix: "descendant_sibling_text_shell_slot"
            });
        }
    }

    function appendTableSiblingShellCandidates() {
        var generated = {};
        var parentSources = sourceBuckets.groups || [];
        for (var si = 0; si < parentSources.length; si++) {
            var parentSource = parentSources[si];
            if (!hasDirectTableOnlyCarrierChild(parentSource.id, parentSource.pageIndex)) continue;

            var siblings = childIdsByParentId[String(parentSource.id)] || [];
            for (var s = 0; s < siblings.length; s++) {
                var siblingId = siblings[s];
                var sibling = sourceInfoById[String(siblingId)];
                if (!sibling) continue;
                if (sibling.kind === "TextFrame") continue;
                if (!sourceCanBeTableSiblingShell(siblingId, parentSource.pageIndex)) continue;
                if (matchingDirectSiblingTextFrameIds(siblingId, parentSource.pageIndex).length === 1) continue;

                var sourceIds = collectSubtreeSourceIds(siblingId);
                var exportIds = collectExportSourceIds(sourceIds, []);
                if (!exportIds || exportIds.length === 0) continue;
                var sourceKey = _sourceSetKey(sourceIds);
                if (generated[sourceKey]) continue;
                generated[sourceKey] = true;

                var shellItem = planCache && planCache.domItem ? planCache.domItem(siblingId) : null;
                if (!shellItem) shellItem = { id: siblingId };
                _pushExtractionCandidate(candidates, seen, "pass.decoration_groups", shellItem, {
                    sourceObjectIds: sourceIds,
                    pageIndex: sibling.pageIndex,
                    kind: sibling.kind,
                    unit: "GROUP_OR_ITEM",
                    mode: "TEXTLESS_CANDIDATE",
                    candidatePurpose: "SHELL_CANDIDATE",
                    primarySourceObjectId: siblingId,
                    bounds: sibling.bounds,
                    parentId: sibling.parentId,
                    parentKind: sibling.parentKind,
                    composite: sourceIds.length > 1,
                    compositeRole: "table_sibling_shell_slot",
                    slotRole: "shell_slot_only",
                    exportSourceObjectIds: exportIds,
                    exportTargetObjectId: siblingId,
                    hiddenVisualSourceObjectIds: [],
                    editableTextFrameIds: [],
                    hiddenTextFrameIds: [],
                    requiresTextHidden: false,
                    textOwner: "none",
                    containsEditableText: false,
                    completePngTextAllowed: false,
                    tableSiblingShellSlot: true,
                    zOrder: sibling.zOrder,
                    suffix: "table_sibling_shell_slot"
                });
            }
        }
    }

    function sourceCanBeResidualSiblingShell(sourceId) {
        var cacheKey = String(sourceId);
        if (sourceCanBeResidualSiblingShellCache.hasOwnProperty(cacheKey)) {
            return sourceCanBeResidualSiblingShellCache[cacheKey];
        }
        var src = sourceInfoById[String(sourceId)];
        if (!src
                || !isShellStructureSourceKind(src.kind)
                || String(src.kind || "") === "GraphicLine"
                || sourceHasInlineAnchorAncestor(sourceId)
                || sourceHasEditableTextDescendant(sourceId)
                || sourceHasPlacedVisualSource(sourceId)) {
            sourceCanBeResidualSiblingShellCache[cacheKey] = false;
            return false;
        }
        sourceCanBeResidualSiblingShellCache[cacheKey] = !sourceHasChildSources(sourceId)
                ? sourceHasVisibleFillSource(src)
                : sourceHasVisibleShellSource(sourceId);
        return sourceCanBeResidualSiblingShellCache[cacheKey];
    }

    function appendSuppressedParentResidualSiblingShellCandidates() {
        var generated = {};
        var parentSources = sourceBuckets.shellRoots || [];
        for (var si = 0; si < parentSources.length; si++) {
            var parentSource = parentSources[si];
            var parentShellInfo = closedTextOwningShellInfo(parentSource.id);
            if (!parentShellInfo) continue;
            var hasMoreSpecific = hasMoreSpecificClosedTextOwningShell(parentSource.id, parentShellInfo.editableIds);
            var directSiblingShellsPartitionText = directSiblingTextShellsPartitionEditableText(
                    parentSource.id, parentShellInfo.editableIds);
            if (!hasMoreSpecific && !directSiblingShellsPartitionText) continue;

            var children = childIdsByParentId[String(parentSource.id)] || [];
            for (var ci = 0; ci < children.length; ci++) {
                var childId = children[ci];
                var child = sourceInfoById[String(childId)];
                if (!child || child.pageIndex !== parentSource.pageIndex) continue;
                if (String(child.kind || "") === "TextFrame") continue;
                if (closedTextOwningShellInfo(childId)) continue;
                if (!sourceCanBeResidualSiblingShell(childId)) continue;

                var sourceIds = collectSubtreeSourceIds(childId);
                var exportIds = collectExportSourceIds(sourceIds, []);
                if (!exportIds || exportIds.length === 0) continue;
                var sourceKey = _sourceSetKey(sourceIds);
                if (generated[sourceKey]) continue;
                generated[sourceKey] = true;

                var shellItem = planCache && planCache.domItem ? planCache.domItem(childId) : null;
                if (!shellItem) shellItem = { id: childId };
                _pushExtractionCandidate(candidates, seen, "pass.decoration_groups", shellItem, {
                    sourceObjectIds: sourceIds,
                    pageIndex: child.pageIndex,
                    kind: child.kind,
                    unit: "GROUP_OR_ITEM",
                    mode: "TEXTLESS_CANDIDATE",
                    candidatePurpose: "SHELL_CANDIDATE",
                    primarySourceObjectId: childId,
                    bounds: child.bounds,
                    parentId: child.parentId,
                    parentKind: child.parentKind,
                    composite: sourceIds.length > 1,
                    compositeRole: "residual_sibling_shell_slot",
                    slotRole: "shell_slot_only",
                    exportSourceObjectIds: exportIds,
                    exportTargetObjectId: childId,
                    hiddenVisualSourceObjectIds: [],
                    editableTextFrameIds: [],
                    hiddenTextFrameIds: [],
                    requiresTextHidden: false,
                    textOwner: "none",
                    containsEditableText: false,
                    completePngTextAllowed: false,
                    residualSiblingShellSlot: true,
                    zOrder: child.zOrder,
                    suffix: "residual_sibling_shell_slot"
                });
            }
        }
    }

    function appendClosedTextOwningShellGroupCandidates() {
        var generated = {};
        var shellSources = sourceBuckets.shellRoots || [];
        for (var si = 0; si < shellSources.length; si++) {
            var sourceEntry = shellSources[si];
            if (!sourceHasChildSources(sourceEntry.id)) continue;

            var shellInfo = closedTextOwningShellInfo(sourceEntry.id);
            if (!shellInfo) continue;
            var hasMoreSpecificShell = hasMoreSpecificClosedTextOwningShell(
                    sourceEntry.id, shellInfo.editableIds);
            var descendantShellsPartitionText = descendantClosedTextShellsPartitionEditableText(
                    sourceEntry.id, shellInfo.editableIds);
            var directSiblingShellsPartitionText = directSiblingTextShellsPartitionEditableText(
                    sourceEntry.id, shellInfo.editableIds);
            if ((hasMoreSpecificShell || descendantShellsPartitionText || directSiblingShellsPartitionText)
                    && !closedTextShellDirectVisualBranchesOverlap(sourceEntry.id)
                    && !parentHasResidualShellStructureOutsideChild(sourceEntry.id)) {
                continue;
            }

            var sourceKey = _sourceSetKey(shellInfo.sourceIds || []);
            if (generated[sourceKey]) continue;
            generated[sourceKey] = true;

            var shellItem = planCache && planCache.domItem ? planCache.domItem(sourceEntry.id) : null;
            if (!shellItem) shellItem = { id: sourceEntry.id };
            upsertDecorationShellCandidate(shellItem, {
                sourceObjectIds: shellInfo.sourceIds,
                pageIndex: sourceEntry.pageIndex,
                kind: sourceEntry.kind,
                unit: "GROUP_OR_ITEM",
                mode: "TEXTLESS_CANDIDATE",
                candidatePurpose: "SHELL_CANDIDATE",
                primarySourceObjectId: sourceEntry.id,
                bounds: sourceEntry.bounds,
                parentId: sourceEntry.parentId,
                parentKind: sourceEntry.parentKind,
                composite: true,
                compositeRole: "source_declared_closed_text_shell",
                slotRole: "shell_slot_only",
                exportSourceObjectIds: shellInfo.exportIds,
                exportTargetObjectId: sourceEntry.id,
                hiddenVisualSourceObjectIds: sourceIdsMinusInDeclaredShellScope(shellInfo.sourceIds, shellInfo.exportIds),
                editableTextFrameIds: shellInfo.editableIds,
                hiddenTextFrameIds: shellInfo.editableIds,
                requiresTextHidden: true,
                textOwner: "hwpx_tf",
                containsEditableText: false,
                completePngTextAllowed: false,
                sourceDeclaredClosedTextShell: true,
                zOrder: sourceEntry.zOrder,
                suffix: "source_declared_closed_text_shell"
            });
        }
    }

    function appendInlineTextlessSiblingDecorationCandidates() {
        var generated = {};
        var visualSources = sourceBuckets.inlineVisuals || [];
        for (var si = 0; si < visualSources.length; si++) {
            var visualSource = visualSources[si];
            if (!sourceCanBeInlineTextlessSiblingDecoration(visualSource.id)) continue;

            var subtreeIds = collectSubtreeSourceIds(visualSource.id);
            var exportIds = collectExportSourceIds(subtreeIds, [], true);
            if (!exportIds || exportIds.length === 0) continue;

            var sourceKey = _sourceSetKey(exportIds);
            if (generated[sourceKey]) continue;
            generated[sourceKey] = true;

            var visualItem = planCache && planCache.domItem ? planCache.domItem(visualSource.id) : null;
            if (!visualItem) visualItem = { id: visualSource.id };
            _pushExtractionCandidate(candidates, seen, "pass.inline_objects", visualItem, {
                sourceObjectIds: exportIds,
                pageIndex: visualSource.pageIndex,
                kind: visualSource.kind,
                unit: "INLINE_OBJECT",
                mode: "TEXTLESS_CANDIDATE",
                candidatePurpose: "INLINE_CANDIDATE",
                primarySourceObjectId: visualSource.id,
                bounds: boundsUnionOfSourceIds(exportIds) || visualSource.bounds,
                parentId: visualSource.parentId,
                parentKind: visualSource.parentKind,
                composite: exportIds.length > 1,
                compositeRole: "inline_textless_sibling_decoration_slot",
                slotRole: "inline_textless_sibling_decoration_slot",
                exportSourceObjectIds: exportIds,
                exportTargetObjectId: visualSource.id,
                hiddenVisualSourceObjectIds: [],
                visualSourceObjectIds: exportIds.slice(0),
                editableTextFrameIds: [],
                hiddenTextFrameIds: [],
                requiresTextHidden: false,
                textOwner: "none",
                containsEditableText: false,
                completePngTextAllowed: false,
                inlineTextlessSiblingDecorationSlot: true,
                zOrder: visualSource.zOrder,
                suffix: "inline_textless_sibling_decoration_slot"
            });
        }
    }

    function sourceCanBeInlineTextlessSiblingDecoration(sourceId) {
        var cacheKey = String(sourceId);
        if (sourceCanBeInlineTextlessSiblingDecorationCache.hasOwnProperty(cacheKey)) {
            return sourceCanBeInlineTextlessSiblingDecorationCache[cacheKey];
        }
        var src = sourceInfoById[String(sourceId)];
        if (!src || !sourceInfoIsInlineFlow(src)) {
            sourceCanBeInlineTextlessSiblingDecorationCache[cacheKey] = false;
            return false;
        }
        var kind = String(src.kind || "");
        if (kind === "TextFrame" || kind === "Story" || kind === "Character" || kind === "InsertionPoint") {
            sourceCanBeInlineTextlessSiblingDecorationCache[cacheKey] = false;
            return false;
        }
        if (src.parentId === null || src.parentId === undefined) {
            sourceCanBeInlineTextlessSiblingDecorationCache[cacheKey] = false;
            return false;
        }
        var parent = sourceInfoById[String(src.parentId)];
        if (!parent || String(parent.kind || "") !== "Group"
                || !sourceInfoIsInlineFlow(parent)
                || sourceHasEditableTextDescendant(sourceId)
                || sourceCanBeNativeParentTextShell(sourceId)) {
            sourceCanBeInlineTextlessSiblingDecorationCache[cacheKey] = false;
            return false;
        }
        if (sourceCanBeDirectSiblingTextShell(sourceId)
                && matchingDirectSiblingTextFrameIds(sourceId, src.pageIndex).length > 0) {
            sourceCanBeInlineTextlessSiblingDecorationCache[cacheKey] = false;
            return false;
        }
        sourceCanBeInlineTextlessSiblingDecorationCache[cacheKey] =
                inlineParentHasEditableTextSibling(src.parentId, sourceId);
        return sourceCanBeInlineTextlessSiblingDecorationCache[cacheKey];
    }

    function sourceInfoIsInlineFlow(src) {
        if (!src) return false;
        if (typeof _isInlineFlowItemBySourceInfo === "function"
                && _isInlineFlowItemBySourceInfo(src)) {
            return true;
        }
        return src.isInline === true
                || src.inline === true
                || String(src.storyAnchorPlacement || "").toUpperCase() === "INLINE"
                || String(src.anchoredPosition || "").toUpperCase() === "INLINE_POSITION";
    }

    function inlineParentHasEditableTextSibling(parentId, sourceId) {
        var cacheKey = String(parentId) + "|" + String(sourceId);
        if (inlineParentHasEditableTextSiblingCache.hasOwnProperty(cacheKey)) {
            return inlineParentHasEditableTextSiblingCache[cacheKey];
        }
        var siblings = childIdsByParentId[String(parentId)] || [];
        for (var si = 0; si < siblings.length; si++) {
            var siblingId = siblings[si];
            if (String(siblingId) === String(sourceId)) continue;
            var sibling = sourceInfoById[String(siblingId)];
            if (!sibling) continue;
            if (sibling.kind === "TextFrame"
                    && sibling.textFrameClass === "editable"
                    && sibling.hasText === true) {
                inlineParentHasEditableTextSiblingCache[cacheKey] = true;
                return true;
            }
            var editableIds = allEditableTextDescendants(siblingId);
            if (editableIds && editableIds.length > 0) {
                inlineParentHasEditableTextSiblingCache[cacheKey] = true;
                return true;
            }
        }
        inlineParentHasEditableTextSiblingCache[cacheKey] = false;
        return false;
    }

    appendClosedTextOwningShellGroupCandidates();
    markSourceDeclaredShellStep("03d08a_sourceDeclared_closedTextShells");
    appendNativeParentTextShellCandidates();
    markSourceDeclaredShellStep("03d08b_sourceDeclared_nativeParentTextShells");
    appendDirectSiblingTextShellCandidates();
    markSourceDeclaredShellStep("03d08c_sourceDeclared_directSiblingTextShells");
    appendPageSiblingTextShellCandidates();
    markSourceDeclaredShellStep("03d08c1_sourceDeclared_pageSiblingTextShells");
    appendDescendantSiblingTextShellCandidates();
    markSourceDeclaredShellStep("03d08d_sourceDeclared_descendantSiblingTextShells");
    appendInlineTextlessSiblingDecorationCandidates();
    markSourceDeclaredShellStep("03d08e_sourceDeclared_inlineTextlessSiblingDecorations");
    appendUngroupedOutlineTextShellCandidates();
    markSourceDeclaredShellStep("03d08f_sourceDeclared_ungroupedOutlineTextShells");
    appendTableSiblingShellCandidates();
    markSourceDeclaredShellStep("03d08g_sourceDeclared_tableSiblingShells");
    appendSuppressedParentResidualSiblingShellCandidates();
    markSourceDeclaredShellStep("03d08h_sourceDeclared_residualSiblingShells");

}
