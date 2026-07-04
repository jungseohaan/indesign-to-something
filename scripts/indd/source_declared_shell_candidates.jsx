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

    function pageIndexInCurrentExtraction(pageIndex) {
        if (pageIndex === null || pageIndex === undefined || pageIndex < 0) return false;
        if (ctx && ctx.rangePageCount !== undefined && pageIndex < ctx.rangePageCount) return true;
        return _candidatePageInRange(pageIndex, ctx);
    }

    function sourceKind(sourceId) {
        var src = sourceInfoById[String(sourceId)];
        return src ? String(src.kind || "") : "";
    }

    function sourceHasInlineAnchorAncestor(sourceId) {
        if (sourceId === null || sourceId === undefined) return false;
        var current = sourceInfoById[String(sourceId)];
        for (var depth = 0; depth < 64 && current; depth++) {
            if (sourceInfoIsInlineFlow(current)) return true;
            var parentId = current.parentId;
            if (parentId === null || parentId === undefined) return false;
            current = sourceInfoById[String(parentId)];
        }
        return false;
    }

    function sourceIsInlineOwnedDescendant(rootSourceId, sourceId) {
        if (sourceId === null || sourceId === undefined) return false;
        if (String(sourceId) === String(rootSourceId)) return false;
        return sourceHasInlineAnchorAncestor(sourceId);
    }

    function sourceHasEditableTextDescendant(sourceId) {
        var src = sourceInfoById[String(sourceId)];
        if (!src) return false;
        if (src.kind === "TextFrame"
                && src.textFrameClass === "editable"
                && src.hasText === true) {
            return true;
        }
        var children = childIdsByParentId[String(sourceId)] || [];
        for (var i = 0; i < children.length; i++) {
            if (sourceHasEditableTextDescendant(children[i])) return true;
        }
        return false;
    }

    function directEditableTextChildren(sourceId) {
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
        return _sortedNumericIds(ids);
    }

    function allEditableTextDescendants(sourceId) {
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
        return _sortedNumericIds(ids);
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
        var src = sourceInfoById[String(sourceId)];
        if (!src) return false;
        var kind = sourceKind(sourceId);
        if (isShellStructureSourceKind(kind)) return true;
        var children = childIdsByParentId[String(sourceId)] || [];
        for (var ci = 0; ci < children.length; ci++) {
            if (hasShellStructureMaterial(children[ci])) return true;
        }
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
        return _sortedNumericIds(ids);
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

    function collectTextShellVisualExportSourceIds(shellSourceId, editableTextIds) {
        var sourceIds = collectSubtreeSourceIds(shellSourceId);
        var exportIds = collectExportSourceIds(sourceIds, editableTextIds || [], false, shellSourceId);
        if (!exportIds || exportIds.length === 0) return [shellSourceId];
        return exportIds;
    }

    function sourceHasPlacedVisualSource(sourceId) {
        return _sourceHasPlacedVisualMetadataInIndex(sourceId, sourceInfoById, childIdsByParentId);
    }

    function sourceHasVisibleShellSource(sourceId) {
        var src = sourceInfoById[String(sourceId)];
        if (!src) return false;
        var kind = String(src.kind || "");
        if (kind === "TextFrame") return false;
        if (kind === "Group") {
            var children = childIdsByParentId[String(sourceId)] || [];
            for (var ci = 0; ci < children.length; ci++) {
                if (sourceHasVisibleShellSource(children[ci])) return true;
            }
            return false;
        }
        if (kind === "Image" || kind === "PDF") return true;
        if (kind === "Rectangle" || kind === "Oval" || kind === "Polygon" || kind === "GraphicLine") {
            return sourceHasVisiblePaint(src) || sourceHasPlacedVisualSource(sourceId);
        }
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
        var src = sourceInfoById[String(sourceId)];
        if (!src) return false;
        if (src.pageIndex !== pageIndex) return false;
        if (String(src.parentKind || "") !== "Group") return false;
        if (!isShellStructureSourceKind(src.kind)) return false;
        if (src.kind === "GraphicLine") return false;
        if (sourceHasInlineAnchorAncestor(sourceId)) return false;
        if (sourceHasEditableTextDescendant(sourceId)) return false;
        if (sourceHasPlacedVisualSource(sourceId)) return false;
        if (!sourceHasChildSources(sourceId)) return sourceHasVisibleFillSource(src);
        return sourceHasVisibleShellSource(sourceId);
    }

    function closedTextOwningShellInfo(sourceId) {
        var src = sourceInfoById[String(sourceId)];
        if (!src) return null;
        var kind = String(src.kind || "");
        if (kind !== "Group" && kind !== "Rectangle" && kind !== "Oval" && kind !== "Polygon") {
            return null;
        }
        if (sourceHasInlineAnchorAncestor(sourceId)) return null;

        var editableIds = allEditableTextDescendants(sourceId);
        if (!editableIds || editableIds.length === 0) return null;
        if (sourceHasPlacedVisualSource(sourceId) && editableIds.length !== 1) return null;
        if (!sourceHasVisibleShellSource(sourceId)) return null;

        var sourceIds = collectSubtreeSourceIds(sourceId);
        var exportIds = collectExportSourceIds(sourceIds, editableIds, false, sourceId);
        if (!exportIds || exportIds.length === 0) return null;
        return {
            sourceIds: sourceIds,
            exportIds: exportIds,
            editableIds: editableIds
        };
    }

    function hasMoreSpecificClosedTextOwningShell(sourceId, editableIds) {
        var children = childIdsByParentId[String(sourceId)] || [];
        var editableKey = _sourceSetKey(editableIds || []);
        for (var ci = 0; ci < children.length; ci++) {
            var childId = children[ci];
            var childInfo = closedTextOwningShellInfo(childId);
            if (childInfo && _sourceSetKey(childInfo.editableIds || []) === editableKey) {
                return true;
            }
            if (hasMoreSpecificClosedTextOwningShell(childId, editableIds)) return true;
        }
        return false;
    }

    function descendantClosedTextShellRoots(sourceId) {
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
        for (var ci = 0; candidates && ci < candidates.length; ci++) {
            var candidate = candidates[ci];
            if (!candidate || candidate.passId !== "pass.decoration_groups") continue;
            if (candidate.pageIndex !== attrs.pageIndex) continue;
            if (_sourceSetKey(candidate.sourceObjectIds || []) !== sourceKey) continue;
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
        var shellSource = sourceInfoById[String(shellSourceId)];
        if (!shellSource || shellSource.parentId === null || shellSource.parentId === undefined) {
            return [];
        }
        if (!sourceCanBeDirectSiblingTextShell(shellSourceId)) return [];
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
        return matchingTextFrameIds;
    }

    function matchingDescendantSiblingTextFrameIds(shellSourceId, pageIndex) {
        var shellSource = sourceInfoById[String(shellSourceId)];
        if (!shellSource || shellSource.parentId === null || shellSource.parentId === undefined) {
            return [];
        }
        if (!sourceCanBeDirectSiblingTextShell(shellSourceId)) return [];
        var siblings = childIdsByParentId[String(shellSource.parentId)] || [];
        var matchingTextFrameIds = [];
        var seen = {};
        for (var si = 0; si < siblings.length; si++) {
            var siblingId = siblings[si];
            if (String(siblingId) === String(shellSourceId)) continue;
            var editableIds = allEditableTextDescendants(siblingId);
            for (var ei = 0; editableIds && ei < editableIds.length; ei++) {
                var textFrameId = editableIds[ei];
                if (seen[String(textFrameId)]) continue;
                var textFrame = sourceInfoById[String(textFrameId)];
                if (!textFrame || textFrame.pageIndex !== pageIndex) continue;
                if (textFrameSourceHasOwnShellStyle(textFrame)) continue;
                if (matchingDirectSiblingShellSourceIds(textFrameId, pageIndex).length > 0) continue;
                if (!sourceTextFrameFitsShell(shellSourceId, textFrameId)) continue;
                seen[String(textFrameId)] = true;
                matchingTextFrameIds.push(textFrameId);
            }
        }
        return _sortedNumericIds(matchingTextFrameIds);
    }

    function matchingDirectSiblingShellSourceIds(textFrameSourceId, pageIndex) {
        var textFrame = sourceInfoById[String(textFrameSourceId)];
        if (!textFrame || textFrame.parentId === null || textFrame.parentId === undefined) return [];
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
        return _sortedNumericIds(shellIds);
    }

    function sourceCanBeDirectSiblingTextShell(shellSourceId) {
        var src = sourceInfoById[String(shellSourceId)];
        if (!src) return false;
        var kind = String(src.kind || "");
        if (kind !== "Rectangle" && kind !== "Oval" && kind !== "Polygon") return false;
        if (String(src.parentKind || "") !== "Group") return false;
        if (sourceHasEditableTextDescendant(shellSourceId)) return false;
        return sourceHasVisiblePaint(src);
    }

    function sourceCanBeNativeParentTextShell(shellSourceId) {
        var src = sourceInfoById[String(shellSourceId)];
        if (!src) return false;
        var kind = String(src.kind || "");
        if (kind !== "Rectangle" && kind !== "Oval" && kind !== "Polygon") return false;
        if (!sourceHasVisiblePaint(src)) return false;
        if (sourceHasPlacedVisualSource(shellSourceId)) return false;
        var directTextIds = directEditableTextChildren(shellSourceId);
        if (!directTextIds || directTextIds.length === 0) return false;
        var allTextIds = allEditableTextDescendants(shellSourceId);
        return sourceSetsEqual(directTextIds, allTextIds);
    }

    function sourceHasVisiblePaint(src) {
        return _sourceInfoHasVisiblePaintMetadata(src);
    }

    function sourceTreeHasVisibleStroke(sourceId) {
        var src = sourceInfoById[String(sourceId)];
        if (!src) return false;
        var strokeName = String(src.strokeColorName || src.strokeColor || "");
        var strokeWeight = Number(src.strokeWeight || 0);
        if (strokeName && strokeName !== "None" && strokeName !== "[None]" && strokeWeight > 0) {
            return true;
        }
        var children = childIdsByParentId[String(sourceId)] || [];
        for (var ci = 0; ci < children.length; ci++) {
            if (sourceTreeHasVisibleStroke(children[ci])) return true;
        }
        return false;
    }

    function sourceTreeHasNonPaperFill(sourceId) {
        var src = sourceInfoById[String(sourceId)];
        if (!src) return false;
        var fillName = String(src.fillColorName || src.fillColor || "");
        if (fillName && fillName !== "None" && fillName !== "[None]" && fillName !== "Paper" && fillName !== "[Paper]") {
            return true;
        }
        var children = childIdsByParentId[String(sourceId)] || [];
        for (var ci = 0; ci < children.length; ci++) {
            if (sourceTreeHasNonPaperFill(children[ci])) return true;
        }
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
        for (var ti = 0; sourceItems && ti < sourceItems.length; ti++) {
            var tfSource = sourceItems[ti];
            if (!tfSource || tfSource.kind !== "TextFrame") continue;
            if (tfSource.textFrameClass !== "editable" || tfSource.hasText !== true) continue;
            if (!pageIndexInCurrentExtraction(tfSource.pageIndex)) continue;
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
        for (var si = 0; sourceItems && si < sourceItems.length; si++) {
            var shellSource = sourceItems[si];
            if (!shellSource || shellSource.id === null || shellSource.id === undefined) continue;
            if (!pageIndexInCurrentExtraction(shellSource.pageIndex)) continue;
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

                var shellExportIds = collectTextShellVisualExportSourceIds(shellSource.id, [siblingId]);
                var sourceObjectIds = _sortedNumericIds(shellExportIds.concat([siblingId]));
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

    function appendNativeParentTextShellCandidates() {
        var generated = {};
        for (var si = 0; sourceItems && si < sourceItems.length; si++) {
            var shellSource = sourceItems[si];
            if (!shellSource || shellSource.id === null || shellSource.id === undefined) continue;
            if (!pageIndexInCurrentExtraction(shellSource.pageIndex)) continue;
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
        for (var si = 0; sourceItems && si < sourceItems.length; si++) {
            var shellSource = sourceItems[si];
            if (!shellSource || shellSource.id === null || shellSource.id === undefined) continue;
            if (!pageIndexInCurrentExtraction(shellSource.pageIndex)) continue;
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
        for (var si = 0; sourceItems && si < sourceItems.length; si++) {
            var parentSource = sourceItems[si];
            if (!parentSource || parentSource.id === null || parentSource.id === undefined) continue;
            if (!pageIndexInCurrentExtraction(parentSource.pageIndex)) continue;
            if (String(parentSource.kind || "") !== "Group") continue;
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
        var src = sourceInfoById[String(sourceId)];
        if (!src) return false;
        if (!isShellStructureSourceKind(src.kind)) return false;
        if (String(src.kind || "") === "GraphicLine") return false;
        if (sourceHasInlineAnchorAncestor(sourceId)) return false;
        if (sourceHasEditableTextDescendant(sourceId)) return false;
        if (sourceHasPlacedVisualSource(sourceId)) return false;
        if (!sourceHasChildSources(sourceId)) return sourceHasVisibleFillSource(src);
        return sourceHasVisibleShellSource(sourceId);
    }

    function appendSuppressedParentResidualSiblingShellCandidates() {
        var generated = {};
        for (var si = 0; sourceItems && si < sourceItems.length; si++) {
            var parentSource = sourceItems[si];
            if (!parentSource || parentSource.id === null || parentSource.id === undefined) continue;
            if (!pageIndexInCurrentExtraction(parentSource.pageIndex)) continue;
            var parentShellInfo = closedTextOwningShellInfo(parentSource.id);
            if (!parentShellInfo) continue;
            var hasMoreSpecific = hasMoreSpecificClosedTextOwningShell(parentSource.id, parentShellInfo.editableIds);
            if (!hasMoreSpecific) continue;

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
        for (var si = 0; sourceItems && si < sourceItems.length; si++) {
            var sourceEntry = sourceItems[si];
            if (!sourceEntry || sourceEntry.id === null || sourceEntry.id === undefined) continue;
            if (!pageIndexInCurrentExtraction(sourceEntry.pageIndex)) continue;

            var shellInfo = closedTextOwningShellInfo(sourceEntry.id);
            if (!shellInfo) continue;
            var hasMoreSpecificShell = hasMoreSpecificClosedTextOwningShell(
                    sourceEntry.id, shellInfo.editableIds);
            var descendantShellsPartitionText = descendantClosedTextShellsPartitionEditableText(
                    sourceEntry.id, shellInfo.editableIds);
            if ((hasMoreSpecificShell || descendantShellsPartitionText)
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
        for (var si = 0; sourceItems && si < sourceItems.length; si++) {
            var visualSource = sourceItems[si];
            if (!visualSource || visualSource.id === null || visualSource.id === undefined) continue;
            if (!pageIndexInCurrentExtraction(visualSource.pageIndex)) continue;
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
        var src = sourceInfoById[String(sourceId)];
        if (!src) return false;
        if (!sourceInfoIsInlineFlow(src)) return false;
        var kind = String(src.kind || "");
        if (kind === "TextFrame" || kind === "Story" || kind === "Character" || kind === "InsertionPoint") {
            return false;
        }
        if (src.parentId === null || src.parentId === undefined) return false;
        var parent = sourceInfoById[String(src.parentId)];
        if (!parent || String(parent.kind || "") !== "Group") return false;
        if (!sourceInfoIsInlineFlow(parent)) return false;
        if (sourceHasEditableTextDescendant(sourceId)) return false;
        if (sourceCanBeNativeParentTextShell(sourceId)) return false;
        if (sourceCanBeDirectSiblingTextShell(sourceId)
                && matchingDirectSiblingTextFrameIds(sourceId, src.pageIndex).length > 0) {
            return false;
        }
        return inlineParentHasEditableTextSibling(src.parentId, sourceId);
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
        var siblings = childIdsByParentId[String(parentId)] || [];
        for (var si = 0; si < siblings.length; si++) {
            var siblingId = siblings[si];
            if (String(siblingId) === String(sourceId)) continue;
            var sibling = sourceInfoById[String(siblingId)];
            if (!sibling) continue;
            if (sibling.kind === "TextFrame"
                    && sibling.textFrameClass === "editable"
                    && sibling.hasText === true) {
                return true;
            }
            var editableIds = allEditableTextDescendants(siblingId);
            if (editableIds && editableIds.length > 0) return true;
        }
        return false;
    }

    appendClosedTextOwningShellGroupCandidates();
    appendNativeParentTextShellCandidates();
    appendDirectSiblingTextShellCandidates();
    appendDescendantSiblingTextShellCandidates();
    appendInlineTextlessSiblingDecorationCandidates();
    appendUngroupedOutlineTextShellCandidates();
    appendTableSiblingShellCandidates();
    appendSuppressedParentResidualSiblingShellCandidates();

}
