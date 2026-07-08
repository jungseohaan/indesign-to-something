// Candidate ownership slot normalization.
//
// This module preserves the existing policy-sensitive normalization contract
// while moving it out of extraction_plan_builder.jsx. Future work should split
// read-only detectors from mutation blocks inside this file, then replace those
// mutations with ObjectPlan/source-slot registry decisions.

function _normalizeExtractionCandidateOwnershipSlots(candidates, sourceItems) {
    if (!candidates || candidates.length === 0) return candidates || [];

    var sourceContext = _createCandidateNormalizationSourceContext(sourceItems);
    var sourceIndexes = sourceContext.sourceIndexes;
    var sourceInfoById = sourceContext.sourceInfoById;
    var childIdsByParentId = sourceContext.childIdsByParentId;
    var closedSubtreeIdsByRootAndSourceSet = {};
    var closedTextlessGroupSourceClosureByRootPage = {};
    var hiddenInlineSourceIdsByPage = null;
    var inlineCandidateSourceIdSet = {};
    var inlineOwnedSourceSet = {};
    var inlineOwnedTextFrameSet = {};
    var independentTextFrameStyleShellIds = {};
    for (var inlineCandidateIdx = 0; candidates && inlineCandidateIdx < candidates.length; inlineCandidateIdx++) {
        var inlineCandidateForSet = candidates[inlineCandidateIdx];
        if (!inlineCandidateForSet || inlineCandidateForSet.passId !== "pass.inline_objects") continue;
        for (var inlineSourceIdx = 0; inlineCandidateForSet.sourceObjectIds
                && inlineSourceIdx < inlineCandidateForSet.sourceObjectIds.length; inlineSourceIdx++) {
            inlineCandidateSourceIdSet[String(inlineCandidateForSet.sourceObjectIds[inlineSourceIdx])] = true;
        }
    }

    function refreshCandidateMeta(meta) {
        return sourceContext.refreshCandidateMeta(meta);
    }

    function sourceHasEditableTextDescendant(sourceId) {
        return sourceContext.sourceHasEditableTextDescendant(sourceId);
    }

    function sourceHasEditableTextAncestorCarrier(sourceId) {
        return sourceContext.sourceHasEditableTextAncestorCarrier(sourceId);
    }

    function collectSourceDescendantIds(sourceId, seen, out) {
        sourceContext.collectSourceDescendantIds(sourceId, seen, out);
    }

    function sourceRootObjectIdsForSourceSet(sourceIds) {
        return sourceContext.sourceRootObjectIdsForSourceSet(sourceIds);
    }

    function closedClusterSourceIdsForCandidate(candidate) {
        var roots = sourceRootObjectIdsForSourceSet(candidate ? candidate.sourceObjectIds : []);
        var ids = [];
        var seen = {};
        for (var i = 0; i < roots.length; i++) {
            collectSourceDescendantIds(roots[i], seen, ids);
        }
        return _sortedNumericIds(ids);
    }

    function editableTextIdsInSourceSet(sourceIds, pageIndex) {
        return sourceContext.editableTextIdsInSourceSet(sourceIds, pageIndex);
    }

    function textFrameIdsInSourceSet(sourceIds, pageIndex) {
        return sourceContext.textFrameIdsInSourceSet(sourceIds, pageIndex);
    }

    function allTextFramesAreSimpleMarkers(textFrameIds) {
        if (!textFrameIds || textFrameIds.length === 0) return false;
        for (var i = 0; i < textFrameIds.length; i++) {
            var src = sourceInfoById[String(textFrameIds[i])];
            if (!src || String(src.kind || "") !== "TextFrame") return false;
            if (src.simpleMarkerLabelContents !== true) return false;
        }
        return true;
    }

    function textBearingEditableSourceIds(sourceIds, pageIndex) {
        var ids = [];
        var seen = {};
        for (var i = 0; sourceIds && i < sourceIds.length; i++) {
            var sourceId = sourceIds[i];
            var src = sourceInfoById[String(sourceId)];
            if (!src || String(src.kind || "") !== "TextFrame") continue;
            if (src.textFrameClass !== "editable" || src.hasText !== true) continue;
            if (pageIndex !== null && pageIndex !== undefined && src.pageIndex !== pageIndex) continue;
            _pushUniqueId(ids, seen, sourceId);
        }
        return _sortedNumericIds(ids);
    }

    function sourceIsTextlessTextFrameShellMaterial(sourceId) {
        var src = sourceInfoById[String(sourceId)];
        if (!src || String(src.kind || "") !== "TextFrame") return false;
        if (src.hasText === true) return false;
        return sourceHasTextFrameShellStyle(sourceId);
    }

    function textlessShellExportSourceIds(sourceIds, editableTextIds) {
        var editableSet = _sourceIdSet(editableTextIds || []);
        var ids = [];
        var seen = {};
        for (var i = 0; sourceIds && i < sourceIds.length; i++) {
            var sourceId = sourceIds[i];
            var src = sourceInfoById[String(sourceId)];
            if (!src) continue;
            var kind = String(src.kind || "");
            if (editableSet[String(sourceId)]) {
                if (kind === "TextFrame" && sourceHasTextFrameShellStyle(sourceId)) {
                    _pushUniqueId(ids, seen, sourceId);
                }
                continue;
            }
            if (kind === "TextFrame") {
                if (sourceHasTextFrameShellStyle(sourceId)) {
                    _pushUniqueId(ids, seen, sourceId);
                }
                continue;
            }
            if (kind === "Group" || kind === "Rectangle" || kind === "Oval"
                    || kind === "Polygon" || kind === "GraphicLine") {
                _pushUniqueId(ids, seen, sourceId);
            }
        }
        return _sortedNumericIds(ids);
    }

    function sourceIdsInFullSubtree(rootId, pageIndex) {
        return sourceContext.sourceIdsInFullSubtree(rootId, pageIndex);
    }

    function sourceIdsInCompleteSubtree(rootId) {
        return sourceContext.sourceIdsInCompleteSubtree(rootId);
    }

    function textlessShellExportSourceIdsForTarget(rootId, pageIndex) {
        var subtreeIds = sourceIdsInCompleteSubtree(rootId);
        var ids = [];
        var seen = {};
        for (var i = 0; i < subtreeIds.length; i++) {
            var sourceId = subtreeIds[i];
            var src = sourceInfoById[String(sourceId)];
            if (!src) continue;
            if (src.kind === "TextFrame" && !sourceHasTextFrameShellStyle(sourceId)) continue;
            if (sourceHasInlineAnchorAncestor(sourceId)) continue;
            _pushUniqueId(ids, seen, sourceId);
        }
        return _sortedNumericIds(ids);
    }

    function isClosedTextlessGroupVisualSlot(candidate) {
        if (!candidate) return false;
        var pageTextlessGroup = candidate.passId === "pass.page_textless_graphic_groups"
                && (candidate.compositeRole === "page_textless_graphic_group"
                || candidate.slotRole === "page_textless_graphic_group");
        if (!pageTextlessGroup) {
            if (candidate.passId !== "pass.decoration_groups") return false;
            if (candidate.candidatePurpose !== "SHELL_CANDIDATE") return false;
            if (candidate.compositeRole !== "textless_group_visual_slot"
                    && candidate.slotRole !== "textless_group_visual_slot") return false;
        }
        if (candidate.primarySourceObjectId === null || candidate.primarySourceObjectId === undefined) return false;
        if (!pageTextlessGroup && candidate.textOwner && candidate.textOwner !== "hwpx_tf") return false;
        return true;
    }

    function sourceIsTextlessGroupVisibleMaterial(sourceId) {
        var src = sourceInfoById[String(sourceId)];
        if (!src) return false;
        if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) return false;
        if (sourceHasInlineAnchorAncestor(sourceId)) return false;
        var kind = String(src.kind || "");
        if (kind === "TextFrame") return sourceIsTextlessTextFrameShellMaterial(sourceId);
        if (kind === "Image" || kind === "PDF" || kind === "EPS") return true;
        if (kind === "Group") return true;
        return src.hasPlacedVisual === true
                || src.hasVisibleFill === true
                || src.hasVisibleStroke === true
                || src.hasCandidateVectorPaint === true;
    }

    function sourceIsDescendantOfRootByParentChain(sourceId, rootId, rootPageIndex) {
        if (sourceId === null || sourceId === undefined || rootId === null || rootId === undefined) return false;
        var src = sourceInfoById[String(sourceId)];
        if (!src) return false;
        if (rootPageIndex !== null && rootPageIndex !== undefined
                && src.pageIndex !== null && src.pageIndex !== undefined
                && String(src.pageIndex) !== String(rootPageIndex)) {
            return false;
        }
        var guard = 0;
        while (src && guard < 256) {
            if (String(src.id) === String(rootId)) return true;
            if (src.parentId === null || src.parentId === undefined) return false;
            src = sourceInfoById[String(src.parentId)];
            guard++;
        }
        return false;
    }

    function closedTextlessGroupSourceClosure(rootId, rootPageIndex) {
        var cacheKey = String(rootId) + "|" + String(rootPageIndex);
        if (closedTextlessGroupSourceClosureByRootPage.hasOwnProperty(cacheKey)) {
            return closedTextlessGroupSourceClosureByRootPage[cacheKey].slice(0);
        }
        var ids = sourceIdsInCompleteSubtree(rootId);
        var seen = _sourceIdSet(ids || []);
        for (var key in sourceInfoById) {
            if (!sourceInfoById.hasOwnProperty(key)) continue;
            var src = sourceInfoById[key];
            if (!src || src.id === null || src.id === undefined) continue;
            if (seen[String(src.id)]) continue;
            if (!sourceIsDescendantOfRootByParentChain(src.id, rootId, rootPageIndex)) continue;
            _pushUniqueId(ids, seen, src.id);
        }
        ids = _sortedNumericIds(ids || []);
        closedTextlessGroupSourceClosureByRootPage[cacheKey] = ids.slice(0);
        return ids;
    }

    function completeClosedTextlessGroupVisualSlot(candidate) {
        if (!isClosedTextlessGroupVisualSlot(candidate)) return false;
        var rootId = candidate.primarySourceObjectId;
        var fullSourceIds = closedTextlessGroupSourceClosure(rootId, candidate.pageIndex);
        if (!fullSourceIds || fullSourceIds.length === 0) return false;

        var editableIds = _sourceIdsUnion(
                candidate.editableTextFrameIds || [],
                candidate.hiddenTextFrameIds || []);
        editableIds = textBearingEditableSourceIds(editableIds, candidate.pageIndex);
        editableIds = _sourceIdsUnion(
                editableIds,
                editableTextIdsInSourceSet(fullSourceIds, candidate.pageIndex));
        if (!editableIds || editableIds.length === 0) return false;

        var visualIds = [];
        var visualSeen = {};
        for (var i = 0; i < fullSourceIds.length; i++) {
            if (sourceIsTextlessGroupVisibleMaterial(fullSourceIds[i])) {
                _pushUniqueId(visualIds, visualSeen, fullSourceIds[i]);
            }
        }
        visualIds = _sortedNumericIds(visualIds);
        if (!visualIds || visualIds.length === 0) return false;

        var exportIds = candidate.exportTargetObjectId !== null
                && candidate.exportTargetObjectId !== undefined
                ? [candidate.exportTargetObjectId]
                : (candidate.exportSourceObjectIds && candidate.exportSourceObjectIds.length > 0
                        ? candidate.exportSourceObjectIds
                        : [rootId]);
        exportIds = _sourceIdsUnion(exportIds, visualIds);
        var hiddenSourceIds = _sourceIdsMinus(fullSourceIds, exportIds);

        var beforeSource = _sourceSetKey(candidate.sourceObjectIds || []);
        var beforeVisual = _sourceSetKey(candidate.visualSourceObjectIds || []);
        var beforeExport = _sourceSetKey(candidate.exportSourceObjectIds || []);
        var beforeHidden = _sourceSetKey(candidate.hiddenVisualSourceObjectIds || []);
        var beforeEditable = _sourceSetKey(candidate.editableTextFrameIds || []);
        var beforeHiddenText = _sourceSetKey(candidate.hiddenTextFrameIds || []);

        candidate.sourceObjectIds = _sourceIdsUnion(candidate.sourceObjectIds || [], fullSourceIds);
        candidate.visualSourceObjectIds = _sourceIdsUnion(candidate.visualSourceObjectIds || [], visualIds);
        candidate.exportSourceObjectIds = _sortedNumericIds(exportIds);
        candidate.exportTargetObjectId = rootId;
        candidate.hiddenVisualSourceObjectIds = _sortedNumericIds(hiddenSourceIds);
        candidate.editableTextFrameIds = _sortedNumericIds(editableIds);
        candidate.hiddenTextFrameIds = _sortedNumericIds(editableIds);
        candidate.ownedTextFrameIds = _sourceIdsUnion(candidate.ownedTextFrameIds || [], editableIds);
        candidate.requiresTextHidden = true;
        candidate.textOwner = "hwpx_tf";
        candidate.containsEditableText = false;
        candidate.completePngTextAllowed = false;
        candidate.materialization = candidate.materialization || "EXTRACTED_PNG_VECTOR";
        candidate.textAction = candidate.textAction || "OWNED_BY_HWPX_TEXT";
        candidate.visualAction = candidate.visualAction || "PLACE_TEXT_SHELL";
        candidate.ownershipSlot = candidate.ownershipSlot || "SHELL_SLOT";

        var changed = beforeSource !== _sourceSetKey(candidate.sourceObjectIds || [])
                || beforeVisual !== _sourceSetKey(candidate.visualSourceObjectIds || [])
                || beforeExport !== _sourceSetKey(candidate.exportSourceObjectIds || [])
                || beforeHidden !== _sourceSetKey(candidate.hiddenVisualSourceObjectIds || [])
                || beforeEditable !== _sourceSetKey(candidate.editableTextFrameIds || [])
                || beforeHiddenText !== _sourceSetKey(candidate.hiddenTextFrameIds || []);
        if (changed) refreshCandidateIdentity(candidate);
        return changed;
    }

    function applyClosedTextOwningShellContract(candidate) {
        if (!_isExtractionShellCandidate(candidate)) return false;
        if (candidate.compositeRole === "source_declared_closed_text_shell") return false;
        if (!candidate.sourceObjectIds || candidate.sourceObjectIds.length === 0) return false;

        var exportSourceIds = _sortedNumericIds(candidate.sourceObjectIds);
        var clusterSourceIds = closedClusterSourceIdsForCandidate(candidate);
        if (!clusterSourceIds || clusterSourceIds.length === 0) return false;
        if (_sourceSetKey(clusterSourceIds) === _sourceSetKey(exportSourceIds)) return false;

        var hiddenSourceIds = _sourceIdsMinus(clusterSourceIds, exportSourceIds);
        var hiddenEditableTextIds = editableTextIdsInSourceSet(hiddenSourceIds, candidate.pageIndex);
        if (!hiddenEditableTextIds || hiddenEditableTextIds.length === 0) return false;

        exportSourceIds = _sourceIdsUnion(
                exportSourceIds, textlessShellExportSourceIds(clusterSourceIds, hiddenEditableTextIds));
        hiddenSourceIds = _sourceIdsMinus(clusterSourceIds, exportSourceIds);

        candidate.sourceObjectIds = clusterSourceIds;
        candidate.exportSourceObjectIds = exportSourceIds;
        candidate.hiddenVisualSourceObjectIds = hiddenSourceIds;
        candidate.slotRole = "shell_slot_only";
        candidate.mode = "SLOT_ONLY";
        return true;
    }

    function applyClosedTextlessShellFragmentContract(candidate) {
        if (!_isExtractionShellCandidate(candidate)) return false;
        if (!candidate || candidate.passId !== "pass.decoration_groups") return false;
        if (!candidate.sourceObjectIds || candidate.sourceObjectIds.length === 0) return false;
        if (candidate.editableTextFrameIds && candidate.editableTextFrameIds.length > 0) return false;
        if (candidate.hiddenTextFrameIds && candidate.hiddenTextFrameIds.length > 0) return false;

        var exportSourceIds = _sortedNumericIds(candidate.sourceObjectIds);
        var clusterSourceIds = closedClusterSourceIdsForCandidate(candidate);
        if (!clusterSourceIds || clusterSourceIds.length === 0) return false;
        if (_sourceSetKey(clusterSourceIds) === _sourceSetKey(exportSourceIds)) return false;

        if (editableTextIdsInSourceSet(clusterSourceIds, candidate.pageIndex).length > 0) return false;
        var hiddenSourceIds = _sourceIdsMinus(clusterSourceIds, exportSourceIds);
        if (!hiddenSourceIds || hiddenSourceIds.length === 0) return false;

        candidate.sourceObjectIds = clusterSourceIds;
        candidate.exportSourceObjectIds = exportSourceIds;
        candidate.exportTargetObjectId = exportSourceIds.length === 1 ? exportSourceIds[0] : null;
        candidate.hiddenVisualSourceObjectIds = hiddenSourceIds;
        candidate.slotRole = "shell_slot_only";
        candidate.mode = "SLOT_ONLY";
        return true;
    }

    function completeTextOwningShellExportContract(candidate, editableIds, exportSourceIds, exportTargetId) {
        if (!_isExtractionShellCandidate(candidate)) return false;
        if (!candidate || candidate.passId !== "pass.decoration_groups") return false;
        if (_isPlannerDeclaredDirectChildShellSlot(candidate)) return false;
        if ((candidate.sourceDeclaredClosedTextShell === true
                    || candidate.compositeRole === "source_declared_closed_text_shell")
                && candidate.exportSourceObjectIds
                && candidate.exportSourceObjectIds.length > 0
                && candidate.hiddenVisualSourceObjectIds
                && candidate.hiddenVisualSourceObjectIds.length > 0) {
            return false;
        }
        if (!editableIds || editableIds.length === 0) return false;
        if (!candidate.sourceObjectIds || candidate.sourceObjectIds.length === 0) return false;

        if (candidate.directChildShellSplitBlocked === true
                && candidate.primarySourceObjectId !== null
                && candidate.primarySourceObjectId !== undefined) {
            var blockedRootExportIds = textlessShellExportSourceIdsForTarget(
                    candidate.primarySourceObjectId, candidate.pageIndex);
            if (blockedRootExportIds && blockedRootExportIds.length > 0) {
                exportSourceIds = blockedRootExportIds;
                exportTargetId = candidate.primarySourceObjectId;
                candidate.sourceObjectIds = _sourceIdsUnion(candidate.sourceObjectIds,
                        sourceIdsInCompleteSubtree(candidate.primarySourceObjectId));
            }
        }

        var resolvedExportTargetId = exportTargetId !== undefined && exportTargetId !== null
                ? exportTargetId
                : (candidate.exportTargetObjectId !== undefined && candidate.exportTargetObjectId !== null
                        ? candidate.exportTargetObjectId
                        : bestShellExportTargetId(candidate, editableIds));
        if (resolvedExportTargetId !== null && resolvedExportTargetId !== undefined) {
            var targetExportIds = textlessShellExportSourceIdsForTarget(
                    resolvedExportTargetId, candidate.pageIndex);
            if (targetExportIds && targetExportIds.length > 0) {
                exportSourceIds = targetExportIds;
                candidate.sourceObjectIds = _sourceIdsUnion(candidate.sourceObjectIds, sourceIdsInCompleteSubtree(
                        resolvedExportTargetId));
            }
        }

        if (!exportSourceIds || exportSourceIds.length === 0) {
            exportSourceIds = candidate.exportSourceObjectIds && candidate.exportSourceObjectIds.length > 0
                    ? _sortedNumericIds(candidate.exportSourceObjectIds)
                    : concreteShellExportSourceIds(candidate);
        } else {
            exportSourceIds = _sortedNumericIds(exportSourceIds);
        }
        if (!exportSourceIds || exportSourceIds.length === 0) return false;
        var hiddenSourceIds = _sourceIdsMinus(candidate.sourceObjectIds, exportSourceIds);
        if (!hiddenSourceIds || hiddenSourceIds.length === 0) return false;
        var hiddenEditableTextIds = editableTextIdsInSourceSet(hiddenSourceIds, candidate.pageIndex);
        if (!hiddenEditableTextIds || hiddenEditableTextIds.length === 0) return false;

        var hiddenTextSourceIds = [];
        var hiddenTextSeen = {};
        for (var hti = 0; hti < hiddenEditableTextIds.length; hti++) {
            var hiddenTextSubtreeIds = sourceIdsInCompleteSubtree(hiddenEditableTextIds[hti]);
            for (var htsi = 0; htsi < hiddenTextSubtreeIds.length; htsi++) {
                _pushUniqueId(hiddenTextSourceIds, hiddenTextSeen, hiddenTextSubtreeIds[htsi]);
            }
        }
        if (hiddenTextSourceIds.length > 0) {
            candidate.sourceObjectIds = _sourceIdsUnion(candidate.sourceObjectIds, hiddenTextSourceIds);
            hiddenSourceIds = _sourceIdsUnion(hiddenSourceIds, hiddenTextSourceIds);
            hiddenEditableTextIds = _sourceIdsUnion(hiddenEditableTextIds,
                    editableTextIdsInSourceSet(hiddenTextSourceIds, candidate.pageIndex));
        }

        candidate.exportSourceObjectIds = exportSourceIds;
        candidate.hiddenVisualSourceObjectIds = hiddenSourceIds;
        candidate.hiddenTextFrameIds = _sourceIdsUnion(candidate.hiddenTextFrameIds || [], hiddenEditableTextIds);
        candidate.exportTargetObjectId = resolvedExportTargetId !== null && resolvedExportTargetId !== undefined
                ? resolvedExportTargetId
                : (exportSourceIds.length === 1 ? exportSourceIds[0] : null);
        candidate.slotRole = "shell_slot_only";
        candidate.mode = "SLOT_ONLY";
        return true;
    }

    function annotateShellSuppression(candidate, reason, sourceIds, childSlots) {
        if (!candidate) return;
        candidate.suppressedByDirectChildShellSlotsReason = reason || "SUPPRESSED_BY_DIRECT_CHILD_SHELL_SLOTS";
        candidate.suppressedByDirectChildShellSlotSourceIds = _sortedNumericIds(sourceIds || []);
        var childCandidateIds = [];
        var childCandidateSeen = {};
        for (var i = 0; childSlots && i < childSlots.length; i++) {
            var child = childSlots[i];
            if (!child || !child.candidateId) continue;
            _pushUniqueId(childCandidateIds, childCandidateSeen, child.candidateId);
        }
        candidate.suppressedByDirectChildShellSlotCandidateIds = childCandidateIds;
    }

    function removeInlineAnchorSourcesFromFloatingShellExport(candidate) {
        if (!candidate || candidate.passId !== "pass.decoration_groups") return false;
        if (candidate.candidatePurpose !== "SHELL_CANDIDATE") return false;
        if (candidate.slotRole !== "shell_slot_only" && candidate.mode !== "SLOT_ONLY") return false;
        if ((!candidate.exportSourceObjectIds || candidate.exportSourceObjectIds.length === 0)
                && (!candidate.styleSourceObjectIds || candidate.styleSourceObjectIds.length === 0)
                && (!candidate.visualSourceObjectIds || candidate.visualSourceObjectIds.length === 0)) {
            return false;
        }

        function isInlineOwnedSource(sourceId) {
            return sourceHasInlineAnchorAncestor(sourceId)
                    || inlineCandidateSourceIdSet[String(sourceId)] === true;
        }

        var kept = [];
        var keptSeen = {};
        var removed = [];
        var removedSeen = {};
        var originalExportIds = candidate.exportSourceObjectIds || [];
        for (var esi = 0; esi < originalExportIds.length; esi++) {
            var exportSourceId = originalExportIds[esi];
            if (isInlineOwnedSource(exportSourceId)) {
                _pushUniqueId(removed, removedSeen, exportSourceId);
            } else {
                _pushUniqueId(kept, keptSeen, exportSourceId);
            }
        }

        var keptStyle = [];
        var keptStyleSeen = {};
        var originalStyleIds = candidate.styleSourceObjectIds || [];
        for (var ssi = 0; ssi < originalStyleIds.length; ssi++) {
            var styleSourceId = originalStyleIds[ssi];
            if (isInlineOwnedSource(styleSourceId)) {
                _pushUniqueId(removed, removedSeen, styleSourceId);
            } else {
                _pushUniqueId(keptStyle, keptStyleSeen, styleSourceId);
            }
        }

        var keptVisual = [];
        var keptVisualSeen = {};
        var originalVisualIds = candidate.visualSourceObjectIds || [];
        for (var vsi = 0; vsi < originalVisualIds.length; vsi++) {
            var visualSourceId = originalVisualIds[vsi];
            if (isInlineOwnedSource(visualSourceId)) {
                _pushUniqueId(removed, removedSeen, visualSourceId);
            } else {
                _pushUniqueId(keptVisual, keptVisualSeen, visualSourceId);
            }
        }

        if (removed.length === 0) return false;
        candidate.hiddenVisualSourceObjectIds = _sourceIdsUnion(
                candidate.hiddenVisualSourceObjectIds || [], removed);
        candidate.exportSourceObjectIds = _sortedNumericIds(kept);
        candidate.styleSourceObjectIds = _sortedNumericIds(keptStyle);
        candidate.visualSourceObjectIds = _sortedNumericIds(keptVisual);
        if (candidate.exportSourceObjectIds.length === 0) {
            candidate.suppressedByDirectChildShellSlots = true;
            annotateShellSuppression(candidate, "INLINE_OWNED_SOURCES_REMOVED", removed, null);
            candidate.exportTargetObjectId = null;
        } else if (candidate.exportTargetObjectId !== null && candidate.exportTargetObjectId !== undefined
                && isInlineOwnedSource(candidate.exportTargetObjectId)) {
            candidate.exportTargetObjectId = candidate.exportSourceObjectIds.length === 1
                    ? candidate.exportSourceObjectIds[0]
                    : null;
        }
        return true;
    }

    function hiddenInlineSourceIdsFromSourceMetadata(candidate) {
        var out = [];
        var seen = {};
        var hiddenIds = candidate ? (candidate.hiddenTextFrameIds || candidate.editableTextFrameIds || []) : [];
        if (!hiddenIds || hiddenIds.length === 0) return out;
        if (!hiddenInlineSourceIdsByPage) {
            hiddenInlineSourceIdsByPage = {};
            for (var isi = 0; sourceItems && isi < sourceItems.length; isi++) {
                var inlineSrc = sourceItems[isi];
                if (!inlineSrc || inlineSrc.id === null || inlineSrc.id === undefined) continue;
                if (!_isInlineFlowItemBySourceInfo(inlineSrc)) continue;
                if (!inlineSrc.bounds) continue;
                var pageKey = String(inlineSrc.pageIndex);
                if (!hiddenInlineSourceIdsByPage[pageKey]) hiddenInlineSourceIdsByPage[pageKey] = [];
                hiddenInlineSourceIdsByPage[pageKey].push(inlineSrc);
            }
        }
        for (var hi = 0; hi < hiddenIds.length; hi++) {
            var hidden = sourceInfoById[String(hiddenIds[hi])];
            if (!hidden || !hidden.bounds) continue;
            var pageSources = hiddenInlineSourceIdsByPage[String(hidden.pageIndex)] || [];
            for (var si = 0; si < pageSources.length; si++) {
                var src = pageSources[si];
                if (!src || src.id === null || src.id === undefined) continue;
                if (String(src.id) === String(hidden.id)) continue;
                if (!src.bounds || !_boundsOverlap(hidden.bounds, src.bounds)) continue;
                collectSourceDescendantIds(src.id, seen, out);
            }
        }
        return _sortedNumericIds(out);
    }

    function includeHiddenInlineSourceProvenance(candidate) {
        if (!candidate || candidate.passId !== "pass.decoration_groups") return false;
        if (_isPlannerDeclaredDirectChildShellSlot(candidate)) return false;
        if (candidate.candidatePurpose !== "SHELL_CANDIDATE") return false;
        if (candidate.slotRole !== "shell_slot_only"
                && candidate.slotRole !== "direct_child_shell_slot"
                && candidate.mode !== "SLOT_ONLY") return false;
        if (!candidate.hiddenTextFrameIds || candidate.hiddenTextFrameIds.length === 0) return false;
        var inlineSourceIds = hiddenInlineSourceIdsFromSourceMetadata(candidate);
        if (!inlineSourceIds || inlineSourceIds.length === 0) return false;
        candidate.sourceObjectIds = _sourceIdsUnion(candidate.sourceObjectIds || [], inlineSourceIds);
        candidate.hiddenTextFrameIds = _sourceIdsUnion(candidate.hiddenTextFrameIds || [],
                textFrameIdsInSourceSet(inlineSourceIds, candidate.pageIndex));
        candidate.exportSourceObjectIds = _sourceIdsMinus(candidate.exportSourceObjectIds || [], inlineSourceIds);
        return true;
    }

    function normalizeInlineTextOwningShellContract(candidate) {
        if (!candidate || candidate.passId !== "pass.inline_objects") return false;
        if (candidate.completePngTextAllowed === true || candidate.textOwner === "indesign_png") return false;
        if (!candidate.sourceObjectIds || candidate.sourceObjectIds.length === 0) return false;

        var inlineRootId = candidate.primarySourceObjectId !== undefined
                && candidate.primarySourceObjectId !== null
                ? candidate.primarySourceObjectId
                : null;
        var inlineRootSubtreeIds = inlineRootId !== null
                ? sourceIdsInCompleteSubtree(inlineRootId)
                : [];
        var ownershipSourceIds = inlineRootSubtreeIds && inlineRootSubtreeIds.length > 0
                ? _sourceIdsUnion(candidate.sourceObjectIds || [], inlineRootSubtreeIds)
                : _sortedNumericIds(candidate.sourceObjectIds || []);
        var editableIds = _sourceIdsUnion(
                candidate.editableTextFrameIds || [],
                candidate.hiddenTextFrameIds || []);
        editableIds = _sourceIdsUnion(
                editableIds,
                editableTextIdsInSourceSet(ownershipSourceIds, candidate.pageIndex));
        if (!editableIds || editableIds.length === 0) {
            editableIds = textFrameIdsInSourceSet(ownershipSourceIds, candidate.pageIndex);
        }
        if (!editableIds || editableIds.length === 0) return false;
        var isExplicitShellSlot = candidate.slotRole === "direct_child_shell_slot"
                || candidate.compositeRole === "direct_child_shell_slot";
        if (!isExplicitShellSlot && allTextFramesAreSimpleMarkers(editableIds)) {
            var beforeMarkerOwner = String(candidate.textOwner || "");
            var beforeMarkerHidden = _sourceSetKey(candidate.hiddenTextFrameIds || []);
            candidate.sourceObjectIds = ownershipSourceIds;
            candidate.exportSourceObjectIds = [];
            candidate.exportTargetObjectId = inlineRootId;
            candidate.visualSourceObjectIds = ownershipSourceIds;
            candidate.hiddenVisualSourceObjectIds = [];
            candidate.editableTextFrameIds = _sourceIdsUnion(
                    candidate.editableTextFrameIds || [], editableIds);
            candidate.hiddenTextFrameIds = [];
            candidate.requiresTextHidden = false;
            candidate.textOwner = "indesign_png";
            candidate.containsEditableText = true;
            candidate.completePngTextAllowed = true;
            candidate.materialization = candidate.materialization || "COMPLETE_PNG";
            candidate.textAction = "OWNED_BY_PNG";
            candidate.visualAction = candidate.visualAction || "PLACE_INLINE_PNG";
            candidate.ownershipSlot = candidate.ownershipSlot || "CONTENT_VISUAL_SLOT";
            candidate.mode = candidate.mode || "TEXTLESS_CANDIDATE";
            var markerChanged = beforeMarkerOwner !== String(candidate.textOwner || "")
                    || beforeMarkerHidden !== _sourceSetKey(candidate.hiddenTextFrameIds || []);
            if (markerChanged) refreshCandidateIdentity(candidate);
            return markerChanged;
        }

        // Inline TF+shell groups are native inline carriers.  Preserve the
        // carrier as the shell export target and hide editable child TFs during
        // export; reducing the contract to leaf shapes drops clipping/effects
        // and makes the native shell appear as a synthetic box.
        var visibleExportIds = inlineRootId !== null
                ? [inlineRootId]
                : textlessShellExportSourceIds(ownershipSourceIds, editableIds);
        if (!visibleExportIds || visibleExportIds.length === 0) return false;

        var beforeExport = _sourceSetKey(candidate.exportSourceObjectIds || []);
        var beforeVisual = _sourceSetKey(candidate.visualSourceObjectIds || []);
        var beforeHidden = _sourceSetKey(candidate.hiddenVisualSourceObjectIds || []);
        var beforeHiddenText = _sourceSetKey(candidate.hiddenTextFrameIds || []);
        var beforeEditable = _sourceSetKey(candidate.editableTextFrameIds || []);
        var beforeTextOwner = String(candidate.textOwner || "");
        var beforeRequiresHidden = candidate.requiresTextHidden === true;

        candidate.sourceObjectIds = ownershipSourceIds;
        var visibleTraceIds = _sourceIdsUnion(candidate.visualSourceObjectIds || [], visibleExportIds);
        visibleTraceIds = _sourceIdsMinus(visibleTraceIds, editableIds);
        candidate.exportSourceObjectIds = visibleExportIds;
        candidate.exportTargetObjectId = inlineRootId;
        candidate.visualSourceObjectIds = visibleTraceIds;
        candidate.hiddenVisualSourceObjectIds = [];
        candidate.editableTextFrameIds = _sourceIdsUnion(
                candidate.editableTextFrameIds || [], editableIds);
        candidate.hiddenTextFrameIds = _sourceIdsUnion(
                candidate.hiddenTextFrameIds || [], editableIds);
        candidate.requiresTextHidden = true;
        candidate.textOwner = "hwpx_tf";
        candidate.containsEditableText = true;
        candidate.mode = candidate.mode || "TEXTLESS_CANDIDATE";
        candidate.exportTargetObjectId = visibleExportIds.length === 1 ? visibleExportIds[0] : null;

        var changed = beforeExport !== _sourceSetKey(candidate.exportSourceObjectIds || [])
                || beforeVisual !== _sourceSetKey(candidate.visualSourceObjectIds || [])
                || beforeHidden !== _sourceSetKey(candidate.hiddenVisualSourceObjectIds || [])
                || beforeHiddenText !== _sourceSetKey(candidate.hiddenTextFrameIds || [])
                || beforeEditable !== _sourceSetKey(candidate.editableTextFrameIds || [])
                || beforeTextOwner !== String(candidate.textOwner || "")
                || beforeRequiresHidden !== (candidate.requiresTextHidden === true);
        if (changed) refreshCandidateIdentity(candidate);
        return changed;
    }

    function applyExactTextOwningShellExportContract(candidate, editableIds) {
        if (candidate && candidate.exportSourceObjectIds && candidate.exportSourceObjectIds.length > 0) return false;
        return completeTextOwningShellExportContract(candidate, editableIds, null, undefined);
    }

    function isLeafOutlineGlyphVectorCandidate(candidateMeta) {
        var candidate = candidateMeta ? candidateMeta.candidate : null;
        if (!candidate || candidate.passId !== "pass.vector_shape_frames") return false;
        if (candidate.candidatePurpose !== "VECTOR_CANDIDATE") return false;
        if (!candidateMeta.sourceIds || candidateMeta.sourceIds.length !== 1) return false;
        var sourceId = candidateMeta.sourceIds[0];
        var src = sourceInfoById[String(sourceId)];
        if (!src || src.kind !== "Polygon") return false;
        if (src.hasText === true || src.hasChildren === true) return false;
        if (src.parentId === null || src.parentId === undefined) return false;
        return sourceHasEditableTextAncestorCarrier(sourceId);
    }

    function refreshCandidateIdentity(candidate) {
        if (!candidate) return candidate;
        var previousPrimary = candidate.primarySourceObjectId;
        candidate.sourceObjectIds = _sortedNumericIds(candidate.sourceObjectIds);
        candidate.primarySourceObjectId = _sourceIdsContain(candidate.sourceObjectIds, previousPrimary)
                ? previousPrimary
                : _chooseFallbackPrimarySourceId(candidate.sourceObjectIds, sourceInfoById);
        candidate.composite = candidate.sourceObjectIds.length > 1 || candidate.composite === true;
        candidate.candidateId = candidate.composite
                ? _candidateCompositeId(candidate.passId, candidate.pageIndex, candidate.sourceObjectIds, candidate.slotRole || candidate.suffix)
                : _candidateId(candidate.passId, candidate.primarySourceObjectId, candidate.pageIndex);
        return candidate;
    }

    function isExportableShellVisualSource(sourceId) {
        var src = sourceInfoById[String(sourceId)];
        if (!src) return false;
        var kind = String(src.kind || "");
        if (kind !== "Rectangle" && kind !== "Oval" && kind !== "Polygon" && kind !== "GraphicLine") {
            return false;
        }
        if (src.hasText === true) return false;
        return sourceHasVisiblePaint(src) || sourceHasPlacedVisualSource(sourceId);
    }

    function sourceHasTextFrameShellStyle(sourceId) {
        return _sourceHasTextFrameShellStyleMetadataInIndex(sourceId, sourceInfoById);
    }

    function isExportableTextFrameShellStyleSource(sourceId) {
        if (!sourceHasTextFrameShellStyle(sourceId)) return false;
        return true;
    }

    function _normalizationPageSourceKey(pageIndex, sourceId) {
        return String(pageIndex) + "|" + String(sourceId);
    }

    function buildDirectChildShellSourceIndex() {
        if (directChildShellSourceIndex !== null) return directChildShellSourceIndex;
        directChildShellSourceIndex = {};
        for (var ci = 0; candidates && ci < candidates.length; ci++) {
            var child = candidates[ci];
            if (!child || !_isPlannerDeclaredDirectChildShellSlot(child)) continue;
            var passKey = String(child.passId || "");
            var pageKey = String(child.pageIndex);
            var sourceIds = child.sourceObjectIds || [];
            for (var si = 0; si < sourceIds.length; si++) {
                var key = passKey + "|" + pageKey + "|" + String(sourceIds[si]);
                if (!directChildShellSourceIndex[key]) directChildShellSourceIndex[key] = [];
                directChildShellSourceIndex[key].push(child);
            }
        }
        return directChildShellSourceIndex;
    }

    function isExecutableResidualShellExportSource(candidate, sourceId) {
        if (textFrameStyleSourceBelongsToDirectChildShellSlot(candidate, sourceId)) return false;
        return isExportableShellVisualSource(sourceId)
                || isExportableTextFrameShellStyleSource(sourceId);
    }

    function textFrameStyleSourceBelongsToDirectChildShellSlot(parentCandidate, sourceId) {
        if (!parentCandidate || sourceId === null || sourceId === undefined) return false;
        if (!parentCandidate.sourceObjectIds || !_sourceIdsContain(parentCandidate.sourceObjectIds, sourceId)) return false;
        if (!parentCandidate.hiddenVisualSourceObjectIds
                || !_sourceIdsContain(parentCandidate.hiddenVisualSourceObjectIds, sourceId)) {
            return false;
        }
        var index = buildDirectChildShellSourceIndex();
        var key = String(parentCandidate.passId || "") + "|" + String(parentCandidate.pageIndex)
                + "|" + String(sourceId);
        var matches = index[key] || [];
        for (var ci = 0; ci < matches.length; ci++) {
            var child = matches[ci];
            if (!child || child === parentCandidate) continue;
            if (!_candidateSourceIdArrayContainsAll(parentCandidate.sourceObjectIds, child.sourceObjectIds)) continue;
            return true;
        }
        return false;
    }

    function concreteShellExportSourceIds(candidate) {
        var ids = [], seen = {};
        if (!candidate || !candidate.sourceObjectIds) return ids;
        var hiddenSet = _sourceIdSet(candidate.hiddenVisualSourceObjectIds || []);
        for (var i = 0; i < candidate.sourceObjectIds.length; i++) {
            var sourceId = candidate.sourceObjectIds[i];
            if (hiddenSet[String(sourceId)] && !isExportableTextFrameShellStyleSource(sourceId)) continue;
            if (sourceHasInlineAnchorAncestor(sourceId)
                    && !isSelfInlineTextFrameShellSlotSource(candidate, sourceId)) {
                continue;
            }
            if (isExecutableResidualShellExportSource(candidate, sourceId)) {
                _pushUniqueId(ids, seen, sourceId);
            }
        }
        return _sortedNumericIds(ids);
    }

    function executableResidualShellExportSourceIds(candidate, sourceIds) {
        var ids = [];
        var seen = {};
        for (var i = 0; sourceIds && i < sourceIds.length; i++) {
            var sourceId = sourceIds[i];
            if (!isExecutableResidualShellExportSource(candidate, sourceId)) continue;
            _pushUniqueId(ids, seen, sourceId);
        }
        return _sortedNumericIds(ids);
    }

    function residualShellExportSourceIdsAfterDirectChildSlots(parentCandidate, parentExportIds, childSourceIds) {
        var residualIds = executableResidualShellExportSourceIds(
                parentCandidate, _sourceIdsMinus(parentExportIds || [], childSourceIds || []));
        if (residualIds && residualIds.length > 0) return residualIds;

        // A composite/root export can represent several visible descendants.
        // When direct child shell slots take some descendants, the root id by
        // itself may no longer be executable even though uncovered leaf
        // material remains. Preserve that residual ownership from the trace
        // sources instead of suppressing the parent slot.
        var traceIds = _sourceIdsUnion(
                parentCandidate ? parentCandidate.visualSourceObjectIds || [] : [],
                parentCandidate ? parentCandidate.sourceObjectIds || [] : []);
        traceIds = _sourceIdsMinus(traceIds, childSourceIds || []);
        return executableResidualShellExportSourceIds(parentCandidate, traceIds);
    }

    function textFrameShellStyleSourceIdsInCandidate(candidate) {
        var ids = [], seen = {};
        if (!_isExtractionShellCandidate(candidate) || !candidate.sourceObjectIds) return ids;
        var hiddenSet = _sourceIdSet(candidate.hiddenVisualSourceObjectIds || []);
        function isInsideHiddenVisualSubtree(sourceId) {
            for (var hi = 0; candidate.hiddenVisualSourceObjectIds
                    && hi < candidate.hiddenVisualSourceObjectIds.length; hi++) {
                var hiddenId = candidate.hiddenVisualSourceObjectIds[hi];
                if (String(hiddenId) === String(sourceId)) continue;
                var hidden = sourceInfoById[String(hiddenId)];
                if (!hidden || String(hidden.kind || "") === "TextFrame") continue;
                if (sourceContainsSourceId(hiddenId, sourceId)) return true;
            }
            return false;
        }
        for (var i = 0; i < candidate.sourceObjectIds.length; i++) {
            var sourceId = candidate.sourceObjectIds[i];
            var src = sourceInfoById[String(sourceId)];
            if (!src || String(src.kind || "") !== "TextFrame") continue;
            var allowSelfInlineShell = isSelfInlineTextFrameShellSlotSource(candidate, sourceId);
            if (candidate.passId !== "pass.editable_textframe_visual_shells"
                    && independentTextFrameStyleShellIds[String(sourceId)]) {
                continue;
            }
            if (sourceHasInlineAnchorAncestor(sourceId) && !allowSelfInlineShell) continue;
            if (inlineCandidateSourceIdSet[String(sourceId)] && !allowSelfInlineShell) continue;
            if (isInsideHiddenVisualSubtree(sourceId)) continue;
            if (textFrameStyleSourceBelongsToDirectChildShellSlot(candidate, sourceId)) continue;
            if (!hiddenSet[String(sourceId)] && !sourceHasTextFrameShellStyle(sourceId)) continue;
            _pushUniqueId(ids, seen, sourceId);
        }
        return _sortedNumericIds(ids);
    }

    function normalizeTextFrameShellStyleExports(meta) {
        var candidate = meta ? meta.candidate : null;
        if (!_isExtractionShellCandidate(candidate)) return false;
        if (candidate.slotRole !== "shell_slot_only" && candidate.mode !== "SLOT_ONLY") return false;
        var styleIds = textFrameShellStyleSourceIdsInCandidate(candidate);
        if (!styleIds || styleIds.length === 0) return false;
        var beforeExport = _sourceSetKey(candidate.exportSourceObjectIds || []);
        var beforeHidden = _sourceSetKey(candidate.hiddenVisualSourceObjectIds || []);
        candidate.exportSourceObjectIds = _sourceIdsUnion(candidate.exportSourceObjectIds || [], styleIds);
        candidate.hiddenVisualSourceObjectIds = _sourceIdsMinus(candidate.hiddenVisualSourceObjectIds || [], styleIds);
        var hiddenTextIds = [];
        var hiddenTextSeen = {};
        for (var i = 0; i < styleIds.length; i++) {
            var src = sourceInfoById[String(styleIds[i])];
            if (src && src.kind === "TextFrame" && src.textFrameClass === "editable" && src.hasText === true) {
                _pushUniqueId(hiddenTextIds, hiddenTextSeen, styleIds[i]);
            }
        }
        if (hiddenTextIds.length > 0) {
            candidate.hiddenTextFrameIds = _sourceIdsUnion(candidate.hiddenTextFrameIds || [], hiddenTextIds);
            candidate.requiresTextHidden = true;
        }
        if (candidate.sourceDeclaredClosedTextShell !== true
                && candidate.exportTargetObjectId !== null && candidate.exportTargetObjectId !== undefined
                && candidate.exportSourceObjectIds.length !== 1) {
            candidate.exportTargetObjectId = null;
        }
        var changed = beforeExport !== _sourceSetKey(candidate.exportSourceObjectIds || [])
                || beforeHidden !== _sourceSetKey(candidate.hiddenVisualSourceObjectIds || []);
        if (changed) {
            refreshCandidateIdentity(candidate);
            refreshCandidateMeta(meta);
        }
        return changed;
    }

    function completeSlotOnlyShellExportSources(meta) {
        var candidate = meta ? meta.candidate : null;
        if (!_isSlotOnlyShellWithHiddenChildren(candidate)) return true;
        if (candidate.exportSourceObjectIds && candidate.exportSourceObjectIds.length > 0) return true;
        var exportIds = concreteShellExportSourceIds(candidate);
        if (!exportIds || exportIds.length === 0) return false;
        candidate.exportSourceObjectIds = exportIds;
        candidate.exportTargetObjectId = exportIds.length === 1 ? exportIds[0] : null;
        refreshCandidateIdentity(candidate);
        refreshCandidateMeta(meta);
        return true;
    }

    function completeExportSourceProvenance(candidate) {
        if (!_isExtractionShellCandidate(candidate)) return false;
        if (!candidate.exportSourceObjectIds || candidate.exportSourceObjectIds.length === 0) return false;
        var beforeSource = _sourceSetKey(candidate.sourceObjectIds || []);
        var beforeVisual = _sourceSetKey(candidate.visualSourceObjectIds || []);
        var exportIds = _sortedNumericIds(candidate.exportSourceObjectIds || []);
        candidate.sourceObjectIds = _sourceIdsUnion(candidate.sourceObjectIds || [], exportIds);

        var hiddenSet = _sourceIdSet(candidate.hiddenVisualSourceObjectIds || []);
        var visibleExportIds = [];
        var visibleSeen = {};
        for (var i = 0; i < exportIds.length; i++) {
            var exportId = exportIds[i];
            if (hiddenSet[String(exportId)]) continue;
            _pushUniqueId(visibleExportIds, visibleSeen, exportId);
        }
        if (visibleExportIds.length > 0) {
            candidate.visualSourceObjectIds = _sourceIdsUnion(
                    candidate.visualSourceObjectIds || [], visibleExportIds);
        }

        var changed = beforeSource !== _sourceSetKey(candidate.sourceObjectIds || [])
                || beforeVisual !== _sourceSetKey(candidate.visualSourceObjectIds || []);
        if (changed) refreshCandidateIdentity(candidate);
        return changed;
    }

    function isIndependentVisualChildCandidate(childMeta, parentMeta) {
        var candidate = childMeta ? childMeta.candidate : null;
        var parent = parentMeta ? parentMeta.candidate : null;
        if (!candidate || !parent || candidate === parent) return false;
        if (childMeta.pageKey !== parentMeta.pageKey) return false;
        if (!childMeta.sourceIds || childMeta.sourceIds.length === 0) return false;
        if (!_candidateMetaSourceContainsAll(parentMeta, childMeta)) return false;
        if (parentMeta.sourceKey === childMeta.sourceKey) return false;
        if (candidate.passId === "pass.vector_shape_frames") {
            return isLeafOutlineGlyphVectorCandidate(childMeta);
        }
        if (candidate.passId === "pass.decoration_groups" && childMeta.sourceIds.length <= 1) return false;
        if (candidate.candidatePurpose !== "CONTENT_CANDIDATE"
                && candidate.passId !== "pass.decoration_groups"
                && candidate.passId !== "pass.image_textless_groups"
                && candidate.passId !== "pass.image_placed_frames"
                && candidate.passId !== "pass.complex_graphic_frames") {
            return false;
        }
        return !childMeta.hasEditableText;
    }

    function isRedundantChildTextShell(childMeta, parentMeta) {
        var child = childMeta ? childMeta.candidate : null;
        var parent = parentMeta ? parentMeta.candidate : null;
        if (!_isExtractionShellCandidate(child) || !_isExtractionShellCandidate(parent) || child === parent) return false;
        if (_isDirectChildShellSlotCandidate(child)) return false;
        if (childMeta.pageKey !== parentMeta.pageKey) return false;
        if (!_candidateMetaSourceContainsAll(parentMeta, childMeta)) return false;
        if (parentMeta.sourceKey === childMeta.sourceKey) return false;
        return _candidateEditableTextIntersects(childMeta, parentMeta);
    }

    function sourceHasAncestor(sourceId, ancestorId) {
        return _sourceHasAncestorInIndex(sourceId, ancestorId, sourceInfoById, 32);
    }

    function isClipParentShellCandidate(candidate) {
        if (!_isExtractionShellCandidate(candidate)) return false;
        if (!_isClipCarryingShapeKind(candidate.kind)) return false;
        if (!candidate.sourceObjectIds || candidate.sourceObjectIds.length <= 1) return false;
        return true;
    }

    function isChildOnlyVisualOfClipParent(childMeta, clipParentMeta) {
        var candidate = childMeta ? childMeta.candidate : null;
        var clipParent = clipParentMeta ? clipParentMeta.candidate : null;
        if (!candidate || !clipParent || candidate === clipParent) return false;
        if (childMeta.pageKey !== clipParentMeta.pageKey) return false;
        if (!childMeta.sourceIds || childMeta.sourceIds.length === 0) return false;
        if (!_candidateMetaSourceContainsAll(clipParentMeta, childMeta)) return false;
        if (childMeta.hasEditableText) return false;
        if (candidate.passId !== "pass.decoration_groups"
                && candidate.passId !== "pass.vector_shape_frames"
                && candidate.passId !== "pass.complex_graphic_frames"
                && candidate.passId !== "pass.image_textless_groups"
                && candidate.passId !== "pass.image_placed_frames") {
            return false;
        }
        var clipParentId = clipParent.primarySourceObjectId;
        for (var i = 0; i < candidate.sourceObjectIds.length; i++) {
            if (sourceHasAncestor(candidate.sourceObjectIds[i], clipParentId)) return true;
        }
        return false;
    }

    function sourceContainsSourceId(ancestorId, descendantId) {
        return sourceContext.sourceContainsSourceId(ancestorId, descendantId);
    }

    function visualChannelContainsSourceId(candidate, sourceId) {
        if (!candidate || sourceId === null || sourceId === undefined) return false;
        var hiddenIds = candidate.hiddenVisualSourceObjectIds || [];
        if (_sourceIdsContain(hiddenIds, sourceId)) return false;
        var exportIds = _candidateEffectiveVisualSourceIds(candidate);
        if (!exportIds || exportIds.length === 0) return false;
        for (var i = 0; i < exportIds.length; i++) {
            if (sourceContainsSourceId(exportIds[i], sourceId)) return true;
        }
        return false;
    }

    function visualChannelContainsAllSources(containerCandidate, memberSourceIds) {
        if (!memberSourceIds || memberSourceIds.length === 0) return false;
        for (var i = 0; i < memberSourceIds.length; i++) {
            if (!visualChannelContainsSourceId(containerCandidate, memberSourceIds[i])) return false;
        }
        return true;
    }

    function sourceHasInlineAnchorAncestor(sourceId) {
        return sourceContext.sourceHasInlineAnchorAncestor(sourceId);
    }

    function sourceHasInlineAnchorAncestorExcludingSelf(sourceId) {
        var src = sourceInfoById[String(sourceId)];
        if (!src) return false;
        var parentId = src.parentId;
        if (parentId === null || parentId === undefined) return false;
        return sourceHasInlineAnchorAncestor(parentId);
    }

    function isSelfInlineTextFrameShellSlotSource(candidate, sourceId) {
        if (!candidate || candidate.passId !== "pass.editable_textframe_visual_shells") return false;
        if (!candidate.sourceObjectIds || candidate.sourceObjectIds.length !== 1) return false;
        return String(candidate.sourceObjectIds[0]) === String(sourceId);
    }

    function sourceBoundsArea(sourceId) {
        var src = sourceInfoById[String(sourceId)];
        var b = src ? src.bounds : null;
        if (!b || b.length < 4) return Number.MAX_VALUE;
        var w = Math.max(0, Number(b[3]) - Number(b[1]));
        var h = Math.max(0, Number(b[2]) - Number(b[0]));
        return w * h;
    }

    function sourceHasNonTextVisualDescendant(sourceId, candidateSourceSet) {
        if (sourceId === null || sourceId === undefined) return false;
        var children = childIdsByParentId[String(sourceId)] || [];
        for (var i = 0; i < children.length; i++) {
            var childId = children[i];
            if (candidateSourceSet && !candidateSourceSet[String(childId)]) continue;
            var child = sourceInfoById[String(childId)];
            if (!child) continue;
            if (child.kind !== "TextFrame") return true;
            if (sourceHasNonTextVisualDescendant(childId, candidateSourceSet)) return true;
        }
        return false;
    }

    function sourceCanBeShellExportTarget(sourceId, candidateSourceSet) {
        var src = sourceInfoById[String(sourceId)];
        if (!src) return false;
        var kind = String(src.kind || "");
        if (kind === "TextFrame" || kind === "PDF" || kind === "Image") return false;
        if (kind !== "Group" && kind !== "Rectangle" && kind !== "Oval"
                && kind !== "Polygon" && kind !== "GraphicLine") {
            return false;
        }
        if (kind === "GraphicLine") return false;
        if (kind === "Group") return sourceHasNonTextVisualDescendant(sourceId, candidateSourceSet);
        return true;
    }

    function sourceCanBeInlineTextShellSource(sourceId, candidateSourceSet) {
        return sourceCanBeShellExportTarget(sourceId, candidateSourceSet)
                || isExportableTextFrameShellStyleSource(sourceId);
    }

    function sourceBounds(sourceId) {
        var src = sourceInfoById[String(sourceId)];
        if (!src) return null;
        return src.bounds || src.geometricBounds || src.visibleBounds || null;
    }

    function sourceHasVisiblePaint(src) {
        return _sourceInfoHasVisiblePaintMetadata(src);
    }

    function sourceTextFrameFitsShell(shellSourceId, textFrameSourceId) {
        var shellBounds = sourceBounds(shellSourceId);
        var tfBounds = sourceBounds(textFrameSourceId);
        var tfArea = boundsArea(tfBounds);
        if (!shellBounds || !tfBounds || tfArea <= 0) return false;
        return boundsOverlapArea(shellBounds, tfBounds) / tfArea >= 0.75;
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

    function matchingInlineCompositeTextFrameIds(shellSourceId, candidateSourceIds, pageIndex) {
        if (!candidateSourceIds || candidateSourceIds.length === 0) return [];
        var shellSource = sourceInfoById[String(shellSourceId)];
        if (!shellSource) return [];
        var shellKind = String(shellSource.kind || "");
        if (shellKind !== "Rectangle" && shellKind !== "Oval" && shellKind !== "Polygon"
                && shellKind !== "TextFrame") return [];
        if (!sourceCanBeInlineTextShellSource(shellSourceId, _sourceIdSet(candidateSourceIds))) return [];
        var editableIds = editableTextIdsInSourceSet(candidateSourceIds, pageIndex);
        var matching = [];
        var seen = {};
        for (var i = 0; editableIds && i < editableIds.length; i++) {
            var textId = editableIds[i];
            var textSource = sourceInfoById[String(textId)];
            if (!textSource || textSource.pageIndex !== pageIndex) continue;
            if (!sourceTextFrameFitsShell(shellSourceId, textId)) continue;
            _pushUniqueId(matching, seen, textId);
        }
        return _sortedNumericIds(matching);
    }

    function directTextOwningShellChildCount(sourceId, candidateSourceSet) {
        var children = childIdsByParentId[String(sourceId)] || [];
        var count = 0;
        for (var i = 0; i < children.length; i++) {
            var childId = children[i];
            if (candidateSourceSet && !candidateSourceSet[String(childId)]) continue;
            if (!sourceHasEditableTextDescendant(childId)) continue;
            if (!sourceCanBeShellExportTarget(childId, candidateSourceSet)
                    && !sourceCanBeShellExportTarget(childId, null)) {
                continue;
            }
            count++;
            if (count >= 2) return count;
        }
        return count;
    }

    function directEditableTextFrameChildIds(sourceId, candidateSourceSet, pageIndex) {
        var children = childIdsByParentId[String(sourceId)] || [];
        var out = [], seen = {};
        for (var i = 0; i < children.length; i++) {
            var childId = children[i];
            if (candidateSourceSet && !candidateSourceSet[String(childId)]) continue;
            var child = sourceInfoById[String(childId)];
            if (!child || String(child.kind || "") !== "TextFrame") continue;
            var editableIds = editableTextIdsInSourceSet([childId], pageIndex);
            if (!editableIds || editableIds.length === 0) continue;
            for (var ei = 0; ei < editableIds.length; ei++) {
                _pushUniqueId(out, seen, editableIds[ei]);
            }
        }
        return _sortedNumericIds(out);
    }

    function shouldTreatGroupAsDirectChildCarrier(sourceId, candidateSourceSet) {
        var src = sourceInfoById[String(sourceId)];
        if (!src || String(src.kind || "") !== "Group") return false;
        return directTextOwningShellChildCount(sourceId, candidateSourceSet) >= 2;
    }

    function isEmptyCarrierTextFrameSource(sourceId) {
        var src = sourceInfoById[String(sourceId)];
        if (!src || String(src.kind || "") !== "TextFrame") return false;
        return src.textFrameClass === "editable"
                && src.hasText === false
                && Number(src.textLength || 0) === 0
                && String(src.parentKind || "") === "Group";
    }

    function sourceHasPlacedVisualSource(sourceId) {
        return _sourceHasPlacedVisualMetadataInIndex(sourceId, sourceInfoById, childIdsByParentId);
    }

    function hasDirectEmptyTableCarrierChild(sourceId, pageIndex, candidateSourceSet) {
        var children = childIdsByParentId[String(sourceId)] || [];
        for (var ci = 0; ci < children.length; ci++) {
            var childId = children[ci];
            if (candidateSourceSet && !candidateSourceSet[String(childId)]) continue;
            var child = sourceInfoById[String(childId)];
            if (!child || child.pageIndex !== pageIndex) continue;
            if (isEmptyCarrierTextFrameSource(childId)) return true;
        }
        return false;
    }

    function sourceCanBeTableSiblingShellSlot(sourceId, pageIndex, candidateSourceSet) {
        var src = sourceInfoById[String(sourceId)];
        if (!src) return false;
        if (candidateSourceSet && !candidateSourceSet[String(sourceId)]) return false;
        if (src.pageIndex !== pageIndex) return false;
        if (String(src.parentKind || "") !== "Group") return false;
        var kind = String(src.kind || "");
        if (kind === "TextFrame" || kind === "GraphicLine" || kind === "Image" || kind === "PDF") return false;
        if (!sourceCanBeShellExportTarget(sourceId, candidateSourceSet)
                && !sourceCanBeShellExportTarget(sourceId, null)) {
            return false;
        }
        if (sourceHasEditableTextDescendant(sourceId)) return false;
        if (sourceHasInlineAnchorAncestor(sourceId)) return false;
        if (sourceHasPlacedVisualSource(sourceId)) return false;
        return true;
    }

    function directChildSourceIdSet(childSlots) {
        var ids = [], seen = {};
        for (var i = 0; childSlots && i < childSlots.length; i++) {
            var child = childSlots[i];
            for (var si = 0; child.sourceObjectIds && si < child.sourceObjectIds.length; si++) {
                _pushUniqueId(ids, seen, child.sourceObjectIds[si]);
            }
        }
        return _sourceIdSet(ids);
    }

    function parentHasResidualShellMaterialOutsideDirectChildren(parentCandidate, childSlots) {
        if (!parentCandidate || !childSlots || childSlots.length === 0) return false;
        var childSet = directChildSourceIdSet(childSlots);
        var visualIds = concreteShellExportSourceIds(parentCandidate);
        for (var i = 0; visualIds && i < visualIds.length; i++) {
            if (!childSet[String(visualIds[i])]) return true;
        }
        return false;
    }

    function parentResidualFormsSiblingTextShellSlot(parentCandidate, childSlots) {
        if (!parentCandidate || !childSlots || childSlots.length === 0) return false;
        if (parentCandidate.primarySourceObjectId === null
                || parentCandidate.primarySourceObjectId === undefined) {
            return false;
        }
        var rootId = parentCandidate.primarySourceObjectId;
        var root = sourceInfoById[String(rootId)];
        if (!root || String(root.kind || "") !== "Group") return false;

        var candidateSet = _sourceIdSet(parentCandidate.sourceObjectIds || []);
        var childSet = directChildSourceIdSet(childSlots);
        var hasDirectResidualText = false;
        var hasDirectResidualShell = false;
        var children = childIdsByParentId[String(rootId)] || [];
        for (var i = 0; i < children.length; i++) {
            var childId = children[i];
            if (childSet[String(childId)]) continue;
            if (candidateSet && !candidateSet[String(childId)]) continue;
            var child = sourceInfoById[String(childId)];
            if (!child) continue;
            if (String(child.kind || "") === "TextFrame") {
                var editableIds = editableTextIdsInSourceSet([childId], parentCandidate.pageIndex);
                if (editableIds && editableIds.length > 0) hasDirectResidualText = true;
                continue;
            }
            if (sourceCanBeShellExportTarget(childId, candidateSet)
                    || sourceCanBeShellExportTarget(childId, null)) {
                hasDirectResidualShell = true;
            }
        }
        return hasDirectResidualText && hasDirectResidualShell;
    }

    function canSplitParentIntoDirectChildShellSlots(parentCandidate, childSlots) {
        if (!parentCandidate || !childSlots || childSlots.length === 0) return false;
        if (parentCandidate.passId === "pass.inline_objects") return true;
        if (parentCandidate.passId !== "pass.decoration_groups") return true;

        // A page/floating decoration group can split only when the parent is a
        // pure carrier for independent child shell clusters. If any executable
        // shell material remains outside those children, the residual is a
        // container/chrome fragment and the parent must own one textless shell.
        var hasResidual = parentHasResidualShellMaterialOutsideDirectChildren(parentCandidate, childSlots);
        var canSplit = !hasResidual || parentResidualFormsSiblingTextShellSlot(parentCandidate, childSlots);
        if (!canSplit) {
            parentCandidate.directChildShellSplitBlocked = true;
            parentCandidate.directChildShellSplitBlockedReason = "parent_container_chrome_not_independent";
        } else if (hasResidual) {
            parentCandidate.directChildShellSplitAllowedReason = "parent_residual_sibling_text_shell_slot";
        }
        return canSplit;
    }

    function bestShellExportTargetId(candidate, editableIds) {
        if (!candidate || !candidate.sourceObjectIds || !editableIds || editableIds.length === 0) {
            return null;
        }
        var candidateSourceSet = _sourceIdSet(candidate.sourceObjectIds);
        var primaryTargetId = primaryNonGroupShellExportTargetId(candidate, editableIds, candidateSourceSet);
        if (primaryTargetId !== null && primaryTargetId !== undefined) return primaryTargetId;
        var best = null;
        var bestArea = Number.MAX_VALUE;
        for (var i = 0; i < candidate.sourceObjectIds.length; i++) {
            var sourceId = candidate.sourceObjectIds[i];
            if (!sourceCanBeShellExportTarget(sourceId, candidateSourceSet)) continue;
            var containsAllText = true;
            for (var ti = 0; ti < editableIds.length; ti++) {
                if (!sourceContainsSourceId(sourceId, editableIds[ti])) {
                    containsAllText = false;
                    break;
                }
            }
            if (!containsAllText) continue;
            var area = sourceBoundsArea(sourceId);
            if (area < bestArea) {
                best = sourceId;
                bestArea = area;
            }
        }
        return best;
    }

    function primaryNonGroupShellExportTargetId(candidate, editableIds, candidateSourceSet) {
        var primaryId = candidate ? candidate.primarySourceObjectId : null;
        if (primaryId === null || primaryId === undefined) return null;
        if (!candidateSourceSet || !candidateSourceSet[String(primaryId)]) return null;
        if (!sourceCanBeShellExportTarget(primaryId, candidateSourceSet)) return null;
        var primarySource = sourceInfoById[String(primaryId)];
        var kind = primarySource ? String(primarySource.kind || "") : "";
        if (kind !== "Rectangle" && kind !== "Oval" && kind !== "Polygon") return null;
        for (var ti = 0; ti < editableIds.length; ti++) {
            if (!sourceContainsSourceId(primaryId, editableIds[ti])) return null;
        }
        return primaryId;
    }

    function sourceIdsInSubtree(candidate, rootId) {
        var ids = [], seen = {};
        if (!candidate || !candidate.sourceObjectIds) return ids;
        var rootIsInlineAnchor = sourceHasInlineAnchorAncestor(rootId);
        for (var i = 0; i < candidate.sourceObjectIds.length; i++) {
            var sourceId = candidate.sourceObjectIds[i];
            var src = sourceInfoById[String(sourceId)];
            if (src && src.kind === "TextFrame" && !sourceHasTextFrameShellStyle(sourceId)) continue;
            if (!rootIsInlineAnchor && sourceHasInlineAnchorAncestor(sourceId)) continue;
            if (sourceContainsSourceId(rootId, sourceId)) {
                _pushUniqueId(ids, seen, sourceId);
            }
        }
        return _sortedNumericIds(ids);
    }

    function sourceIdsInClosedSubtree(candidate, rootId) {
        var cacheKey = String(rootId) + "|" + _sourceSetKey(candidate && candidate.sourceObjectIds ? candidate.sourceObjectIds : []);
        if (closedSubtreeIdsByRootAndSourceSet.hasOwnProperty(cacheKey)) {
            return closedSubtreeIdsByRootAndSourceSet[cacheKey].slice(0);
        }
        var ids = [], seen = {};
        if (!candidate || !candidate.sourceObjectIds) return ids;
        var candidateSet = _sourceIdSet(candidate.sourceObjectIds);

        function visit(sourceId) {
            if (sourceId === null || sourceId === undefined) return;
            if (!candidateSet[String(sourceId)]) return;
            _pushUniqueId(ids, seen, sourceId);
            var children = childIdsByParentId[String(sourceId)] || [];
            for (var i = 0; i < children.length; i++) visit(children[i]);
        }

        visit(rootId);
        ids = _sortedNumericIds(ids);
        closedSubtreeIdsByRootAndSourceSet[cacheKey] = ids.slice(0);
        return ids;
    }

    function makeDirectChildShellSlotCandidate(parentCandidate, rootId, sourceIds, editableIds) {
        var root = sourceInfoById[String(rootId)];
        if (!root || !sourceIds || sourceIds.length === 0 || !editableIds || editableIds.length === 0) return null;
        var exportIds = textlessShellExportSourceIds(sourceIds, editableIds);
        if (!exportIds || exportIds.length === 0) exportIds = [rootId];
        exportIds = _sortedNumericIds(exportIds);
        sourceIds = _sortedNumericIds(sourceIds);
        return {
            candidateId: _candidateCompositeId(parentCandidate.passId, parentCandidate.pageIndex, sourceIds, "direct_child_shell_slot"),
            passId: parentCandidate.passId,
            sourceObjectIds: sourceIds,
            primarySourceObjectId: rootId,
            pageIndex: parentCandidate.pageIndex,
            kind: root.kind || null,
            unit: parentCandidate.unit || "INLINE_OBJECT",
            mode: "TEXTLESS_CANDIDATE",
            candidatePurpose: parentCandidate.candidatePurpose || "INLINE_CANDIDATE",
            bounds: root.bounds || null,
            parentId: root.parentId,
            parentKind: root.parentKind || null,
            composite: sourceIds.length > 1,
            compositeRole: "direct_child_shell_slot",
            slotRole: "direct_child_shell_slot",
            exportSourceObjectIds: exportIds,
            exportTargetObjectId: rootId,
            hiddenVisualSourceObjectIds: [],
            ownedTextFrameIds: editableIds.slice(0),
            editableTextFrameIds: editableIds.slice(0),
            hiddenTextFrameIds: editableIds.slice(0),
            requiresTextHidden: true,
            textOwner: "hwpx_tf",
            containsEditableText: false,
            completePngTextAllowed: false,
            placement: parentCandidate.passId === "pass.inline_objects" ? "INLINE" : parentCandidate.placement,
            coordinateSpace: parentCandidate.passId === "pass.inline_objects" ? "STORY_FLOW" : parentCandidate.coordinateSpace,
            zOrder: root.zOrder !== undefined ? root.zOrder : parentCandidate.zOrder,
            required: parentCandidate.required === true
        };
    }

    function makeTextFrameStyleShellSlotCandidate(parentCandidate, textFrameId, options) {
        options = options || {};
        var src = sourceInfoById[String(textFrameId)];
        if (!src || String(src.kind || "") !== "TextFrame") return null;
        if (src.textFrameClass !== "editable") return null;
        if (!sourceHasTextFrameShellStyle(textFrameId)) return null;
        if (sourceHasInlineAnchorAncestorExcludingSelf(textFrameId)) return null;
        if (textFrameStyleShellCoveredByInlineDirectChildShell(textFrameId, parentCandidate.pageIndex)) return null;
        if (options.skipCoveredByExistingCandidate !== false
                && textFrameStyleShellCoveredByExistingCandidate(textFrameId, parentCandidate.pageIndex)) {
            return null;
        }
        return {
            candidateId: _candidateId("pass.editable_textframe_visual_shells", textFrameId, parentCandidate.pageIndex),
            passId: "pass.editable_textframe_visual_shells",
            sourceObjectIds: [textFrameId],
            primarySourceObjectId: textFrameId,
            pageIndex: parentCandidate.pageIndex,
            kind: "TextFrame",
            unit: "TEXT_FRAME",
            mode: "TEXTLESS_CANDIDATE",
            candidatePurpose: "SHELL_CANDIDATE",
            bounds: src.bounds || null,
            parentId: src.parentId,
            parentKind: src.parentKind || null,
            composite: false,
            compositeRole: "textframe_style_shell_slot",
            slotRole: "direct_child_shell_slot",
            exportSourceObjectIds: [textFrameId],
            exportTargetObjectId: textFrameId,
            hiddenVisualSourceObjectIds: [],
            visualSourceObjectIds: [textFrameId],
            styleSourceObjectIds: [textFrameId],
            editableTextFrameIds: [textFrameId],
            hiddenTextFrameIds: [textFrameId],
            requiresTextHidden: true,
            textOwner: "hwpx_tf",
            containsEditableText: false,
            completePngTextAllowed: false,
            zOrder: src.zOrder !== undefined ? src.zOrder : parentCandidate.zOrder,
            required: parentCandidate.required === true
        };
    }

    function buildTextFrameStyleCoverageIndex() {
        if (textFrameStyleCoverageIndex !== null) return textFrameStyleCoverageIndex;
        textFrameStyleCoverageIndex = {};
        for (var ci = 0; candidates && ci < candidates.length; ci++) {
            var candidate = candidates[ci];
            if (!candidate || candidate.passId !== "pass.decoration_groups") continue;
            if (!candidate.exportSourceObjectIds || candidate.exportSourceObjectIds.length === 0) continue;
            var ownedIds = candidate.editableTextFrameIds && candidate.editableTextFrameIds.length > 0
                    ? candidate.editableTextFrameIds
                    : candidate.hiddenTextFrameIds;
            if (!ownedIds || ownedIds.length === 0) continue;
            var ownedSet = _sourceIdSet(ownedIds);
            for (var ei = 0; ei < candidate.exportSourceObjectIds.length; ei++) {
                var sourceId = candidate.exportSourceObjectIds[ei];
                if (!ownedSet[String(sourceId)]) continue;
                textFrameStyleCoverageIndex[_normalizationPageSourceKey(candidate.pageIndex, sourceId)] = true;
            }
        }
        return textFrameStyleCoverageIndex;
    }

    function buildInlineDirectChildShellCoverageIndex() {
        if (inlineDirectChildShellCoverageIndex !== null) return inlineDirectChildShellCoverageIndex;
        inlineDirectChildShellCoverageIndex = {};
        for (var ci = 0; candidates && ci < candidates.length; ci++) {
            var candidate = candidates[ci];
            if (!candidate || candidate.passId !== "pass.inline_objects") continue;
            if (candidate.slotRole !== "direct_child_shell_slot"
                    && candidate.compositeRole !== "direct_child_shell_slot") continue;
            var ids = _sourceIdsUnion(
                    candidate.sourceObjectIds || [],
                    _sourceIdsUnion(candidate.exportSourceObjectIds || [], candidate.visualSourceObjectIds || []));
            for (var ii = 0; ii < ids.length; ii++) {
                inlineDirectChildShellCoverageIndex[_normalizationPageSourceKey(candidate.pageIndex, ids[ii])] = true;
            }
        }
        return inlineDirectChildShellCoverageIndex;
    }

    function textFrameStyleShellCoveredByExistingCandidate(textFrameId, pageIndex) {
        if (!sourceHasTextFrameShellStyle(textFrameId)) return false;
        return buildTextFrameStyleCoverageIndex()[_normalizationPageSourceKey(pageIndex, textFrameId)] === true;
    }

    function textFrameStyleShellCoveredByInlineDirectChildShell(textFrameId, pageIndex) {
        var src = sourceInfoById[String(textFrameId)];
        if (!src || src.hasText === true) return false;
        return buildInlineDirectChildShellCoverageIndex()[_normalizationPageSourceKey(pageIndex, textFrameId)] === true;
    }

    function makeTableSiblingShellSlotCandidate(parentCandidate, rootId, sourceIds) {
        var root = sourceInfoById[String(rootId)];
        if (!root || !sourceIds || sourceIds.length === 0) return null;
        var exportIds = _sortedNumericIds(sourceIds);
        sourceIds = _sortedNumericIds(sourceIds);
        return {
            candidateId: _candidateCompositeId(parentCandidate.passId, parentCandidate.pageIndex, sourceIds, "table_sibling_shell_slot"),
            passId: parentCandidate.passId,
            sourceObjectIds: sourceIds,
            primarySourceObjectId: rootId,
            pageIndex: parentCandidate.pageIndex,
            kind: root.kind || null,
            unit: parentCandidate.unit || "GROUP_OR_ITEM",
            mode: "TEXTLESS_CANDIDATE",
            candidatePurpose: "SHELL_CANDIDATE",
            bounds: root.bounds || null,
            parentId: root.parentId,
            parentKind: root.parentKind || null,
            composite: sourceIds.length > 1,
            compositeRole: "table_sibling_shell_slot",
            slotRole: "direct_child_shell_slot",
            exportSourceObjectIds: exportIds,
            exportTargetObjectId: rootId,
            hiddenVisualSourceObjectIds: [],
            editableTextFrameIds: [],
            hiddenTextFrameIds: [],
            requiresTextHidden: false,
            textOwner: "none",
            containsEditableText: false,
            completePngTextAllowed: false,
            tableSiblingShellSlot: true,
            zOrder: root.zOrder !== undefined ? root.zOrder : parentCandidate.zOrder,
            required: parentCandidate.required === true
        };
    }

    function canHaveDirectChildShellSlots(candidate) {
        if (!candidate) return false;
        if (candidate.sourceDeclaredClosedTextShell === true) return false;
        if (_isDirectChildShellSlotCandidate(candidate)) {
            if (candidate.passId === "pass.decoration_groups"
                    && candidate.candidatePurpose === "SHELL_CANDIDATE"
                    && hasDirectEmptyTableCarrierChild(
                            candidate.primarySourceObjectId,
                            candidate.pageIndex,
                            _sourceIdSet(candidate.sourceObjectIds || []))) {
                return true;
            }
            return false;
        }
        if (candidate.passId === "pass.inline_objects") return true;
        if (candidate.passId === "pass.decoration_groups"
                && candidate.candidatePurpose === "SHELL_CANDIDATE"
                && candidate.textOwner === "hwpx_tf") {
            return true;
        }
        return false;
    }

    function excludeDirectChildShellSlotsFromParent(parentCandidate, childSlots) {
        if (!parentCandidate || !childSlots || childSlots.length === 0) return false;

        var childSourceIds = [];
        var childSourceSeen = {};
        var childExportIds = [];
        var childExportSeen = {};
        for (var i = 0; i < childSlots.length; i++) {
            var child = childSlots[i];
            for (var si = 0; child.sourceObjectIds && si < child.sourceObjectIds.length; si++) {
                var childSourceId = child.sourceObjectIds[si];
                if (parentShouldRetainChildTextFrameStyleSource(parentCandidate, child, childSourceId)) {
                    continue;
                }
                _pushUniqueId(childSourceIds, childSourceSeen, childSourceId);
            }
            for (var ei = 0; child.exportSourceObjectIds && ei < child.exportSourceObjectIds.length; ei++) {
                var childExportId = child.exportSourceObjectIds[ei];
                if (parentShouldRetainChildTextFrameStyleSource(parentCandidate, child, childExportId)) {
                    continue;
                }
                _pushUniqueId(childExportIds, childExportSeen, childExportId);
            }
        }
        childSourceIds = _sortedNumericIds(childSourceIds);
        childExportIds = _sortedNumericIds(childExportIds);
        if (childExportIds.length === 0) return false;

        var parentExportIds = parentCandidate.exportSourceObjectIds && parentCandidate.exportSourceObjectIds.length > 0
                ? _sortedNumericIds(parentCandidate.exportSourceObjectIds)
                : concreteShellExportSourceIds(parentCandidate);
        parentExportIds = residualShellExportSourceIdsAfterDirectChildSlots(
                parentCandidate, parentExportIds, childSourceIds);
        if (parentExportIds.length === 0) {
            parentCandidate.hiddenVisualSourceObjectIds = _sourceIdsUnion(
                    parentCandidate.hiddenVisualSourceObjectIds || [], childSourceIds);
            parentCandidate.slotRole = "shell_slot_only";
            parentCandidate.mode = "SLOT_ONLY";
            parentCandidate.textOwner = "hwpx_tf";
            parentCandidate.containsText = false;
            parentCandidate.containsEditableText = false;
            parentCandidate.suppressedByDirectChildShellSlots = true;
            annotateShellSuppression(parentCandidate, "DIRECT_CHILD_SHELL_SLOTS", childSourceIds, childSlots);
            return true;
        }

        parentCandidate.exportSourceObjectIds = parentExportIds;
        parentCandidate.hiddenVisualSourceObjectIds = _sourceIdsWithout(
                _sourceIdsUnion(parentCandidate.hiddenVisualSourceObjectIds || [], childSourceIds),
                parentExportIds);
        parentCandidate.slotRole = "shell_slot_only";
        parentCandidate.mode = "SLOT_ONLY";
        parentCandidate.textOwner = "hwpx_tf";
        parentCandidate.containsText = false;
        parentCandidate.containsEditableText = false;
        refreshCandidateIdentity(parentCandidate);
        return true;
    }

    function parentShouldRetainChildTextFrameStyleSource(parentCandidate, childCandidate, sourceId) {
        if (!parentCandidate || !childCandidate) return false;
        if (!_isExtractionShellCandidate(parentCandidate)) return false;
        if (parentCandidate.candidatePurpose !== "SHELL_CANDIDATE") return false;
        if (parentCandidate.textOwner !== "hwpx_tf") return false;
        if (!sourceHasTextFrameShellStyle(sourceId)) return false;
        var src = sourceInfoById[String(sourceId)];
        if (!src || String(src.kind || "") !== "TextFrame") return false;
        if (!_sourceIdsContain(parentCandidate.sourceObjectIds || [], sourceId)) return false;
        return childCandidate.passId === "pass.editable_textframe_visual_shells"
                || childCandidate.candidatePurpose === "SHELL_CANDIDATE";
    }

    function directChildShellSlotCandidatesForCompositeShell(candidate) {
        var out = [];
        if (!canHaveDirectChildShellSlots(candidate)) return out;
        var editableIds = editableTextIdsInSourceSet(candidate.sourceObjectIds, candidate.pageIndex);
        if (!editableIds) editableIds = [];
        if (candidate.passId === "pass.inline_objects"
                && editableIds.length > 0
                && allTextFramesAreSimpleMarkers(editableIds)) {
            return out;
        }

        var roots = sourceRootObjectIdsForSourceSet(candidate.sourceObjectIds);
        var candidateSet = _sourceIdSet(candidate.sourceObjectIds);
        var coveredEditable = {};
        var generatedSourceKeys = {};
        if (candidate.passId === "pass.inline_objects") {
            for (var ipsi = 0; ipsi < candidate.sourceObjectIds.length; ipsi++) {
                var inlineShellId = candidate.sourceObjectIds[ipsi];
                if (String(inlineShellId) === String(candidate.primarySourceObjectId)) continue;
                if (!sourceCanBeShellExportTarget(inlineShellId, candidateSet)) continue;
                var inlineShellTextIds = matchingInlineCompositeTextFrameIds(
                        inlineShellId, candidate.sourceObjectIds, candidate.pageIndex);
                if (!inlineShellTextIds || inlineShellTextIds.length !== 1) continue;
                var inlineSourceIds = _sourceIdsUnion([inlineShellId], inlineShellTextIds);
                var inlineSourceKey = _sourceSetKey(inlineSourceIds);
                if (generatedSourceKeys[inlineSourceKey]) continue;
                var inlineSlotCandidate = makeDirectChildShellSlotCandidate(
                        candidate, inlineShellId, inlineSourceIds, inlineShellTextIds);
                if (!inlineSlotCandidate) continue;
                inlineSlotCandidate.directSiblingTextShellSlot = true;
                generatedSourceKeys[inlineSourceKey] = true;
                out.push(inlineSlotCandidate);
                coveredEditable[String(inlineShellTextIds[0])] = true;
            }
        }
        for (var npsi = 0; npsi < candidate.sourceObjectIds.length; npsi++) {
            var nativeShellRootId = candidate.sourceObjectIds[npsi];
            var nativeShellRoot = sourceInfoById[String(nativeShellRootId)];
            if (!nativeShellRoot) continue;
            var nativeShellRootKind = String(nativeShellRoot.kind || "");
            if (nativeShellRootKind !== "Rectangle"
                    && nativeShellRootKind !== "Oval"
                    && nativeShellRootKind !== "Polygon") {
                continue;
            }
            if (String(nativeShellRootId) === String(candidate.primarySourceObjectId)) continue;
            if (!sourceCanBeShellExportTarget(nativeShellRootId, candidateSet)) continue;
            if (candidate.passId !== "pass.inline_objects"
                    && sourceHasInlineAnchorAncestor(nativeShellRootId)) {
                continue;
            }
            if (candidate.passId !== "pass.inline_objects"
                    && shouldTreatGroupAsDirectChildCarrier(nativeShellRootId, candidateSet)) {
                continue;
            }
            var nativeEditableIds = directEditableTextFrameChildIds(
                    nativeShellRootId, candidateSet, candidate.pageIndex);
            if (!nativeEditableIds || nativeEditableIds.length === 0) continue;
            var nativeSourceIds = sourceIdsInClosedSubtree(candidate, nativeShellRootId);
            var completeNativeSourceIds = sourceIdsInCompleteSubtree(nativeShellRootId);
            if (completeNativeSourceIds && completeNativeSourceIds.length > nativeSourceIds.length) {
                nativeSourceIds = completeNativeSourceIds;
            }
            if (!nativeSourceIds || nativeSourceIds.length === 0) continue;
            var nativeSourceKey = _sourceSetKey(nativeSourceIds);
            if (generatedSourceKeys[nativeSourceKey]) continue;
            var nativeSlotCandidate = makeDirectChildShellSlotCandidate(
                    candidate, nativeShellRootId, nativeSourceIds, nativeEditableIds);
            if (!nativeSlotCandidate) continue;
            generatedSourceKeys[nativeSourceKey] = true;
            out.push(nativeSlotCandidate);
            for (var nei = 0; nei < nativeEditableIds.length; nei++) {
                coveredEditable[String(nativeEditableIds[nei])] = true;
            }
        }
        for (var ri = 0; ri < roots.length; ri++) {
            var rootId = roots[ri];
            var root = sourceInfoById[String(rootId)];
            if (!root) continue;

            var probeIds = [];
            if (String(rootId) !== String(candidate.primarySourceObjectId)
                    && sourceCanBeShellExportTarget(rootId, candidateSet)
                    && sourceHasEditableTextDescendant(rootId)) {
                probeIds.push(rootId);
            }
            var children = childIdsByParentId[String(rootId)] || [];
            for (var ci = 0; ci < children.length; ci++) {
                var childId = children[ci];
                if (!sourceCanBeShellExportTarget(childId, candidateSet)) continue;
                if (!sourceHasEditableTextDescendant(childId)) continue;
                probeIds.push(childId);
            }

            var probeSeen = {};
            for (var pi = 0; pi < probeIds.length; pi++) {
                var shellRootId = probeIds[pi];
                if (probeSeen[String(shellRootId)]) continue;
                probeSeen[String(shellRootId)] = true;
                if (candidate.passId !== "pass.inline_objects"
                        && sourceHasInlineAnchorAncestor(shellRootId)) {
                    continue;
                }
                if (candidate.passId !== "pass.inline_objects"
                        && shouldTreatGroupAsDirectChildCarrier(shellRootId, candidateSet)) {
                    continue;
                }
                var sourceIds = sourceIdsInClosedSubtree(candidate, shellRootId);
                var completeSourceIds = sourceIdsInCompleteSubtree(shellRootId);
                if (completeSourceIds && completeSourceIds.length > sourceIds.length) {
                    sourceIds = completeSourceIds;
                }
                var childEditableIds = editableTextIdsInSourceSet(sourceIds, candidate.pageIndex);
                if (!childEditableIds || childEditableIds.length === 0) continue;
                if (childEditableIds.length === editableIds.length
                        && _sourceSetKey(sourceIds) === _sourceSetKey(candidate.sourceObjectIds)) {
                    continue;
                }
                var sourceKey = _sourceSetKey(sourceIds);
                if (generatedSourceKeys[sourceKey]) continue;
                var slotCandidate = makeDirectChildShellSlotCandidate(candidate, shellRootId, sourceIds, childEditableIds);
                if (!slotCandidate) continue;
                generatedSourceKeys[sourceKey] = true;
                out.push(slotCandidate);
                for (var ei = 0; ei < childEditableIds.length; ei++) {
                    coveredEditable[String(childEditableIds[ei])] = true;
                }
            }
        }

        if (candidate.hiddenVisualSourceObjectIds && candidate.hiddenVisualSourceObjectIds.length > 0) {
            var hiddenSourceSet = _sourceIdSet(candidate.hiddenVisualSourceObjectIds);
            for (var hi = 0; hi < candidate.sourceObjectIds.length; hi++) {
                var hiddenRootId = candidate.sourceObjectIds[hi];
                if (!hiddenSourceSet[String(hiddenRootId)]) continue;
                if (String(hiddenRootId) === String(candidate.primarySourceObjectId)) continue;
                if (!sourceCanBeShellExportTarget(hiddenRootId, candidateSet)) continue;
                if (!sourceHasEditableTextDescendant(hiddenRootId)) continue;
                if (candidate.passId !== "pass.inline_objects"
                        && sourceHasInlineAnchorAncestor(hiddenRootId)) {
                    continue;
                }
                if (candidate.passId !== "pass.inline_objects"
                        && shouldTreatGroupAsDirectChildCarrier(hiddenRootId, candidateSet)) {
                    continue;
                }
                var hiddenSourceIds = sourceIdsInClosedSubtree(candidate, hiddenRootId);
                var completeHiddenSourceIds = sourceIdsInCompleteSubtree(hiddenRootId);
                if (completeHiddenSourceIds && completeHiddenSourceIds.length > hiddenSourceIds.length) {
                    hiddenSourceIds = completeHiddenSourceIds;
                }
                var hiddenEditableIds = editableTextIdsInSourceSet(hiddenSourceIds, candidate.pageIndex);
                if (!hiddenEditableIds || hiddenEditableIds.length === 0) continue;
                if (hiddenEditableIds.length === editableIds.length
                        && _sourceSetKey(hiddenSourceIds) === _sourceSetKey(candidate.sourceObjectIds)) {
                    continue;
                }
                var hiddenSourceKey = _sourceSetKey(hiddenSourceIds);
                if (generatedSourceKeys[hiddenSourceKey]) continue;
                var hiddenSlotCandidate = makeDirectChildShellSlotCandidate(
                        candidate, hiddenRootId, hiddenSourceIds, hiddenEditableIds);
                if (!hiddenSlotCandidate) continue;
                generatedSourceKeys[hiddenSourceKey] = true;
                out.push(hiddenSlotCandidate);
                for (var hei = 0; hei < hiddenEditableIds.length; hei++) {
                    coveredEditable[String(hiddenEditableIds[hei])] = true;
                }
            }
        }

        var coveredCount = 0;
        if (editableIds.length > 0) {
            for (var cti = 0; cti < editableIds.length; cti++) {
                if (coveredEditable[String(editableIds[cti])]) coveredCount++;
            }
        } else {
            for (var coveredKey in coveredEditable) {
                if (coveredEditable.hasOwnProperty(coveredKey)) coveredCount++;
            }
        }
        appendTableSiblingShellSlotsForComposite(candidate, out, generatedSourceKeys);
        if (coveredCount < 2 && !hasSingleDirectSiblingTextShellSlot(out) && !hasTableSiblingShellSlot(out)) return [];
        if (!canSplitParentIntoDirectChildShellSlots(candidate, out)) return [];
        return out;
    }

    function hasSingleDirectSiblingTextShellSlot(slots) {
        if (!slots || slots.length !== 1) return false;
        var slot = slots[0];
        return slot && slot.slotRole === "direct_child_shell_slot"
                && slot.directSiblingTextShellSlot === true
                && slot.exportSourceObjectIds && slot.exportSourceObjectIds.length > 0
                && slot.editableTextFrameIds && slot.editableTextFrameIds.length === 1;
    }

    function hasTableSiblingShellSlot(slots) {
        if (!slots || slots.length === 0) return false;
        for (var i = 0; i < slots.length; i++) {
            if (slots[i] && slots[i].tableSiblingShellSlot === true) return true;
        }
        return false;
    }

    function appendTableSiblingShellSlotsForComposite(candidate, out, generatedSourceKeys) {
        if (!candidate || !candidate.sourceObjectIds || candidate.sourceObjectIds.length === 0) return;
        if (candidate.passId !== "pass.decoration_groups") return;
        var rootId = candidate.primarySourceObjectId;
        var root = sourceInfoById[String(rootId)];
        if (!root || String(root.kind || "") !== "Group") return;
        var candidateSet = _sourceIdSet(candidate.sourceObjectIds);
        if (!hasDirectEmptyTableCarrierChild(rootId, candidate.pageIndex, candidateSet)) return;
        var children = childIdsByParentId[String(rootId)] || [];
        for (var ci = 0; ci < children.length; ci++) {
            var childId = children[ci];
            var child = sourceInfoById[String(childId)];
            if (!child || String(child.kind || "") === "TextFrame") continue;
            if (!sourceCanBeTableSiblingShellSlot(childId, candidate.pageIndex, candidateSet)) continue;
            var sourceIds = sourceIdsInClosedSubtree(candidate, childId);
            if (!sourceIds || sourceIds.length === 0) continue;
            var sourceKey = _sourceSetKey(sourceIds);
            if (generatedSourceKeys[sourceKey]) continue;
            var slotCandidate = makeTableSiblingShellSlotCandidate(candidate, childId, sourceIds);
            if (!slotCandidate) continue;
            generatedSourceKeys[sourceKey] = true;
            out.push(slotCandidate);
        }
    }

    function directChildShellSlotCandidatesFromHiddenSources(candidate) {
        var out = [];
        if (!candidate || candidate.passId !== "pass.decoration_groups") return out;
        if (candidate.candidatePurpose !== "SHELL_CANDIDATE") return out;
        if (!candidate.sourceObjectIds || candidate.sourceObjectIds.length === 0) return out;
        if (!candidate.hiddenVisualSourceObjectIds || candidate.hiddenVisualSourceObjectIds.length === 0) return out;

        var candidateSet = _sourceIdSet(candidate.sourceObjectIds);
        var hiddenSet = _sourceIdSet(candidate.hiddenVisualSourceObjectIds);
        var generatedSourceKeys = {};
        var coveredEditable = {};
        for (var hi = 0; hi < candidate.hiddenVisualSourceObjectIds.length; hi++) {
            var rootId = candidate.hiddenVisualSourceObjectIds[hi];
            if (!candidateSet[String(rootId)]) continue;
            if (String(rootId) === String(candidate.primarySourceObjectId)) continue;
            if (!sourceCanBeShellExportTarget(rootId, candidateSet)) continue;
            var siblingTextFrameIds = matchingDirectSiblingTextFrameIds(rootId, candidate.pageIndex);
            if (!sourceHasEditableTextDescendant(rootId)
                    && (!siblingTextFrameIds || siblingTextFrameIds.length !== 1)) {
                continue;
            }
            if (sourceHasInlineAnchorAncestor(rootId)) continue;
            if (shouldTreatGroupAsDirectChildCarrier(rootId, candidateSet)) continue;

            var sourceIds;
            var editableIds;
            var directSiblingSlot = siblingTextFrameIds && siblingTextFrameIds.length === 1
                    && !sourceHasEditableTextDescendant(rootId);
            if (directSiblingSlot) {
                sourceIds = _sortedNumericIds([rootId, siblingTextFrameIds[0]]);
                editableIds = siblingTextFrameIds.slice(0);
            } else {
                sourceIds = sourceIdsInClosedSubtree(candidate, rootId);
                var completeSourceIds = sourceIdsInCompleteSubtree(rootId);
                if (completeSourceIds && completeSourceIds.length > sourceIds.length) {
                    sourceIds = completeSourceIds;
                }
                editableIds = editableTextIdsInSourceSet(sourceIds, candidate.pageIndex);
            }
            if (!sourceIds || sourceIds.length === 0) continue;
            if (!editableIds || editableIds.length === 0) continue;

            var parentId = sourceInfoById[String(rootId)] ? sourceInfoById[String(rootId)].parentId : null;
            if (parentId !== null && parentId !== undefined
                    && hiddenSet[String(parentId)]
                    && String(parentId) !== String(candidate.primarySourceObjectId)
                    && sourceCanBeShellExportTarget(parentId, candidateSet)
                    && sourceHasEditableTextDescendant(parentId)
                    && !shouldTreatGroupAsDirectChildCarrier(parentId, candidateSet)) {
                continue;
            }

            var sourceKey = _sourceSetKey(sourceIds);
            if (generatedSourceKeys[sourceKey]) continue;
            var slotCandidate = makeDirectChildShellSlotCandidate(candidate, rootId, sourceIds, editableIds);
            if (!slotCandidate) continue;
            if (directSiblingSlot) {
                slotCandidate.directSiblingTextShellSlot = true;
                slotCandidate.exportSourceObjectIds = [rootId];
                slotCandidate.exportTargetObjectId = rootId;
            }
            generatedSourceKeys[sourceKey] = true;
            out.push(slotCandidate);
            for (var ei = 0; ei < editableIds.length; ei++) coveredEditable[String(editableIds[ei])] = true;
        }

        var coveredCount = 0;
        for (var coveredKey in coveredEditable) {
            if (coveredEditable.hasOwnProperty(coveredKey)) coveredCount++;
        }
        appendTableSiblingShellSlotsForComposite(candidate, out, generatedSourceKeys);
        if (coveredCount < 2 && !hasSingleDirectSiblingTextShellSlot(out) && !hasTableSiblingShellSlot(out)) return [];
        if (!canSplitParentIntoDirectChildShellSlots(candidate, out)) return [];
        return out;
    }

    function appendCompositeChildShellSlots() {
        var createdAny = false;
        var generatedSlots = [];
        var existingByKey = {};
        for (var ei = 0; ei < candidates.length; ei++) {
            var existingCandidate = candidates[ei];
            existingByKey[existingCandidate.passId + "|" + existingCandidate.pageIndex + "|" + _sourceSetKey(existingCandidate.sourceObjectIds)] = existingCandidate;
        }
        var originalCandidateCount = candidates.length;
        for (var ci = 0; ci < originalCandidateCount; ci++) {
            var parentCandidate = candidates[ci];
            var childSlots = directChildShellSlotCandidatesForCompositeShell(parentCandidate);
            if (!childSlots || childSlots.length === 0) {
                childSlots = directChildShellSlotCandidatesFromHiddenSources(parentCandidate);
            }
            if (!childSlots || childSlots.length === 0) continue;
            var effectiveSlots = [];
            for (var si = 0; si < childSlots.length; si++) {
                var childCandidate = childSlots[si];
                var key = childCandidate.passId + "|" + childCandidate.pageIndex + "|" + _sourceSetKey(childCandidate.sourceObjectIds);
                if (existingByKey[key]) {
                    effectiveSlots.push(existingByKey[key]);
                    generatedSlots.push(existingByKey[key]);
                } else {
                    existingByKey[key] = childCandidate;
                    candidates.push(childCandidate);
                    effectiveSlots.push(childCandidate);
                    generatedSlots.push(childCandidate);
                    createdAny = true;
                }
            }
            if (effectiveSlots.length > 0) {
                excludeDirectChildShellSlotsFromParent(parentCandidate, effectiveSlots);
            }
        }
        return pruneExistingSubsetCandidatesForGeneratedDirectChildShellSlots(generatedSlots) || createdAny;
    }

    function splitInlineSiblingShellTextSlots() {
        var createdAny = false;
        var generatedSlots = [];
        var existingByKey = {};
        for (var ei = 0; ei < candidates.length; ei++) {
            var existingCandidate = candidates[ei];
            if (!existingCandidate) continue;
            existingByKey[existingCandidate.passId + "|" + existingCandidate.pageIndex + "|"
                    + _sourceSetKey(existingCandidate.sourceObjectIds)] = existingCandidate;
        }
        var originalCandidateCount = candidates.length;
        function isResidualInlineVisualMaterialSource(sourceId) {
            var src = sourceInfoById[String(sourceId)];
            if (!src) return false;
            if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) return false;
            var kind = String(src.kind || "");
            if (kind === "TextFrame" || kind === "Story" || kind === "Character"
                    || kind === "InsertionPoint" || kind === "Cell") {
                return false;
            }
            return kind === "Image" || kind === "PDF" || kind === "EPS"
                    || src.hasPlacedVisual === true
                    || src.hasVisibleFill === true
                    || src.hasVisibleStroke === true
                    || src.hasCandidateVectorPaint === true;
        }
        for (var ci = 0; ci < originalCandidateCount; ci++) {
            var parentCandidate = candidates[ci];
            if (!parentCandidate || parentCandidate.passId !== "pass.inline_objects") continue;
            if (parentCandidate.suppressedByDirectChildShellSlots === true) continue;
            var visualIds = parentCandidate.visualSourceObjectIds || parentCandidate.exportSourceObjectIds || [];
            visualIds = _sourceIdsUnion(visualIds, parentCandidate.styleSourceObjectIds || []);
            visualIds = _sourceIdsUnion(visualIds, parentCandidate.exportSourceObjectIds || []);
            var editableIds = parentCandidate.editableTextFrameIds || parentCandidate.hiddenTextFrameIds || [];
            if (!visualIds || visualIds.length < 2 || !editableIds || editableIds.length < 2) continue;
            var parentSourceSet = _sourceIdSet(parentCandidate.sourceObjectIds || []);
            var coveredEditable = {};
            var childSlots = [];
            var localKeys = {};
            for (var vi = 0; vi < visualIds.length; vi++) {
                var visualId = visualIds[vi];
                if (!parentSourceSet[String(visualId)]) continue;
                if (!sourceCanBeInlineTextShellSource(visualId, parentSourceSet)) continue;
                var matchingIds = matchingInlineCompositeTextFrameIds(
                        visualId, parentCandidate.sourceObjectIds, parentCandidate.pageIndex);
                var unmatched = [];
                for (var mi = 0; matchingIds && mi < matchingIds.length; mi++) {
                    if (!coveredEditable[String(matchingIds[mi])]) unmatched.push(matchingIds[mi]);
                }
                if (unmatched.length !== 1) continue;
                var childSourceIds = _sourceIdsUnion([visualId], unmatched);
                var childKey = _sourceSetKey(childSourceIds);
                if (localKeys[childKey]) continue;
                var childCandidate = makeDirectChildShellSlotCandidate(
                        parentCandidate, visualId, childSourceIds, unmatched);
                if (!childCandidate) continue;
                localKeys[childKey] = true;
                childSlots.push(childCandidate);
                coveredEditable[String(unmatched[0])] = true;
            }
            var coveredCount = 0;
            for (var ciText = 0; ciText < editableIds.length; ciText++) {
                if (coveredEditable[String(editableIds[ciText])]) coveredCount++;
            }
            if (coveredCount < 2 || coveredCount !== editableIds.length) continue;
            var coveredVisual = {};
            for (var cvi = 0; cvi < childSlots.length; cvi++) {
                var childVisibleIds = generatedDirectChildShellSlotVisibleSourceIds(childSlots[cvi]);
                for (var cvii = 0; childVisibleIds && cvii < childVisibleIds.length; cvii++) {
                    coveredVisual[String(childVisibleIds[cvii])] = true;
                }
            }
            var hasUncoveredResidualVisual = false;
            for (var rvi = 0; rvi < visualIds.length; rvi++) {
                var residualVisualId = visualIds[rvi];
                if (!parentSourceSet[String(residualVisualId)]) continue;
                if (coveredVisual[String(residualVisualId)]) continue;
                if (isResidualInlineVisualMaterialSource(residualVisualId)) {
                    hasUncoveredResidualVisual = true;
                    break;
                }
            }
            if (hasUncoveredResidualVisual) continue;
            for (var si = 0; si < childSlots.length; si++) {
                var child = childSlots[si];
                var key = child.passId + "|" + child.pageIndex + "|" + _sourceSetKey(child.sourceObjectIds);
                if (existingByKey[key]) {
                    generatedSlots.push(existingByKey[key]);
                    continue;
                }
                existingByKey[key] = child;
                candidates.push(child);
                generatedSlots.push(child);
                createdAny = true;
            }
            parentCandidate.suppressedByDirectChildShellSlots = true;
            annotateShellSuppression(parentCandidate, "INLINE_SIBLING_TEXT_SHELL_SLOTS",
                    parentCandidate.sourceObjectIds || [], childSlots);
        }
        return pruneExistingSubsetCandidatesForGeneratedDirectChildShellSlots(generatedSlots) || createdAny;
    }

    function generatedDirectChildShellSlotVisibleSourceIds(candidate) {
        if (!_isPlannerDeclaredDirectChildShellSlot(candidate)) return [];
        var ids = candidate.exportSourceObjectIds && candidate.exportSourceObjectIds.length > 0
                ? candidate.exportSourceObjectIds
                : candidate.visualSourceObjectIds;
        if ((!ids || ids.length === 0) && candidate.styleSourceObjectIds && candidate.styleSourceObjectIds.length > 0) {
            ids = candidate.styleSourceObjectIds;
        }
        if (!ids || ids.length === 0) ids = candidate.sourceObjectIds || [];
        return _sortedNumericIds(ids || []);
    }

    function canPruneByGeneratedDirectChildShellSlot(candidate) {
        if (!candidate) return false;
        if (_isPlannerDeclaredDirectChildShellSlot(candidate)) return false;
        var passId = String(candidate.passId || "");
        if (passId !== "pass.decoration_groups"
                && passId !== "pass.vector_shape_frames"
                && passId !== "pass.complex_graphic_frames") {
            return false;
        }
        if (candidate.editableTextFrameIds && candidate.editableTextFrameIds.length > 0) return false;
        if (candidate.hiddenTextFrameIds && candidate.hiddenTextFrameIds.length > 0) return false;
        return candidate.sourceObjectIds && candidate.sourceObjectIds.length > 0;
    }

    function pruneExistingSubsetCandidatesForGeneratedDirectChildShellSlots(generatedSlots) {
        if (!generatedSlots || generatedSlots.length === 0) return false;
        var slotInfos = [];
        for (var si = 0; si < generatedSlots.length; si++) {
            var slot = generatedSlots[si];
            if (!_isPlannerDeclaredDirectChildShellSlot(slot)) continue;
            var visibleIds = generatedDirectChildShellSlotVisibleSourceIds(slot);
            if (!visibleIds || visibleIds.length === 0) continue;
            slotInfos.push({
                candidate: slot,
                pageIndex: slot.pageIndex,
                passId: slot.passId,
                visibleIds: visibleIds
            });
        }
        if (slotInfos.length === 0) return false;

        var pruned = false;
        var kept = [];
        for (var ci = 0; ci < candidates.length; ci++) {
            var candidate = candidates[ci];
            if (!canPruneByGeneratedDirectChildShellSlot(candidate)) {
                kept.push(candidate);
                continue;
            }
            var candidateSourceIds = _sortedNumericIds(candidate.sourceObjectIds || []);
            var prune = false;
            for (var pi = 0; pi < slotInfos.length; pi++) {
                var slotInfo = slotInfos[pi];
                if (candidate === slotInfo.candidate) continue;
                if (candidate.pageIndex !== slotInfo.pageIndex) continue;
                if (candidate.passId !== slotInfo.passId) continue;
                if (candidateSourceIds.length >= slotInfo.visibleIds.length) continue;
                if (!_candidateSourceIdArrayContainsAll(slotInfo.visibleIds, candidateSourceIds)) continue;
                prune = true;
                break;
            }
            if (prune) {
                pruned = true;
                continue;
            }
            kept.push(candidate);
        }
        if (!pruned) return false;
        candidates.length = 0;
        for (var ki = 0; ki < kept.length; ki++) candidates.push(kept[ki]);
        return true;
    }

    function appendTextFrameStyleShellSlotsForCompositeCarriers() {
        var createdAny = false;
        var existingByKey = {};
        for (var ei = 0; ei < candidates.length; ei++) {
            var existingCandidate = candidates[ei];
            if (!existingCandidate || !existingCandidate.sourceObjectIds) continue;
            existingByKey[existingCandidate.passId + "|" + existingCandidate.pageIndex
                    + "|" + _sourceSetKey(existingCandidate.sourceObjectIds)] = true;
        }
        for (var ci = 0; ci < candidates.length; ci++) {
            var parentCandidate = candidates[ci];
            if (!parentCandidate || parentCandidate.passId !== "pass.decoration_groups") continue;
            if (parentCandidate.candidatePurpose !== "SHELL_CANDIDATE") continue;
            if (!parentCandidate.sourceObjectIds || parentCandidate.sourceObjectIds.length === 0) continue;
            var parentSourceSet = _sourceIdSet(parentCandidate.sourceObjectIds);
            var editableIds = editableTextIdsInSourceSet(parentCandidate.sourceObjectIds, parentCandidate.pageIndex);
            for (var ti = 0; editableIds && ti < editableIds.length; ti++) {
                var textFrameId = editableIds[ti];
                if (!parentSourceSet[String(textFrameId)]) continue;
                var key = "pass.editable_textframe_visual_shells|" + parentCandidate.pageIndex
                        + "|" + _sourceSetKey([textFrameId]);
                if (existingByKey[key]) continue;
                var tfShell = makeTextFrameStyleShellSlotCandidate(parentCandidate, textFrameId);
                if (!tfShell) continue;
                existingByKey[key] = true;
                candidates.push(tfShell);
                createdAny = true;
            }
        }
        return createdAny;
    }

    function appendIndependentTextFrameStyleShellSlots() {
        var createdAny = false;
        var existingByKey = {};
        for (var ei = 0; ei < candidates.length; ei++) {
            var existingCandidate = candidates[ei];
            if (!existingCandidate || !existingCandidate.sourceObjectIds) continue;
            existingByKey[existingCandidate.passId + "|" + existingCandidate.pageIndex
                    + "|" + _sourceSetKey(existingCandidate.sourceObjectIds)] = true;
        }

        for (var sourceId in sourceInfoById) {
            if (!sourceInfoById.hasOwnProperty(sourceId)) continue;
            var src = sourceInfoById[sourceId];
            if (!src || String(src.kind || "") !== "TextFrame") continue;
            var textFrameId = Number(sourceId);
            if (isNaN(textFrameId)) continue;
            if (!sourceHasTextFrameShellStyle(textFrameId)) continue;
            var pageIndex = src.pageIndex;
            if (pageIndex === null || pageIndex === undefined) continue;
            var key = "pass.editable_textframe_visual_shells|" + pageIndex
                    + "|" + _sourceSetKey([textFrameId]);
            if (existingByKey[key]) continue;

            var tfShell = makeTextFrameStyleShellSlotCandidate({
                pageIndex: pageIndex,
                required: false
            }, textFrameId, {
                skipCoveredByExistingCandidate: false
            });
            if (!tfShell) continue;
            existingByKey[key] = true;
            candidates.push(tfShell);
            createdAny = true;
        }
        return createdAny;
    }

    function rebuildIndependentTextFrameStyleShellIds() {
        independentTextFrameStyleShellIds = {};
        for (var i = 0; i < candidates.length; i++) {
            var candidate = candidates[i];
            if (!candidate || candidate.passId !== "pass.editable_textframe_visual_shells") continue;
            if (!candidate.sourceObjectIds || candidate.sourceObjectIds.length !== 1) continue;
            independentTextFrameStyleShellIds[String(candidate.sourceObjectIds[0])] = true;
        }
    }

    function excludeDeclaredChildShellSlotsFromParentExports() {
        var changed = false;
        var directChildSlotsByPagePass = {};
        for (var di = 0; di < candidates.length; di++) {
            var directCandidate = candidates[di];
            if (!_isPlannerDeclaredDirectChildShellSlot(directCandidate)) continue;
            if (!directCandidate || !directCandidate.sourceObjectIds || directCandidate.sourceObjectIds.length === 0) continue;
            var directKey = String(directCandidate.passId) + "|" + String(directCandidate.pageIndex);
            if (!directChildSlotsByPagePass[directKey]) directChildSlotsByPagePass[directKey] = [];
            directChildSlotsByPagePass[directKey].push({
                candidate: directCandidate,
                sourceKey: _sourceSetKey(directCandidate.sourceObjectIds || [])
            });
        }
        for (var pi = 0; pi < candidates.length; pi++) {
            var parentCandidate = candidates[pi];
            if (!parentCandidate) continue;
            if (parentCandidate.tableSiblingShellSlot === true) continue;
            if (parentCandidate.passId !== "pass.decoration_groups") continue;
            if (!_isExtractionShellCandidate(parentCandidate)) continue;
            var parentSourceSet = _sourceIdSet(parentCandidate.sourceObjectIds || []);
            var parentSourceKey = _sourceSetKey(parentCandidate.sourceObjectIds || []);
            var directSlots = directChildSlotsByPagePass[
                    String(parentCandidate.passId) + "|" + String(parentCandidate.pageIndex)] || [];
            var childSlots = [];
            for (var ci = 0; ci < directSlots.length; ci++) {
                var childCandidate = directSlots[ci].candidate;
                if (directSlots[ci].sourceKey === parentSourceKey) {
                    continue;
                }
                var allChildSourcesInParent = true;
                for (var si = 0; childCandidate.sourceObjectIds
                        && si < childCandidate.sourceObjectIds.length; si++) {
                    if (!parentSourceSet[String(childCandidate.sourceObjectIds[si])]) {
                        allChildSourcesInParent = false;
                        break;
                    }
                }
                if (!allChildSourcesInParent) continue;
                childSlots.push(childCandidate);
            }
            if (childSlots.length === 0) continue;
            if (excludeDirectChildShellSlotsFromParent(parentCandidate, childSlots)) {
                changed = true;
            }
        }
        return changed;
    }

    function pruneRedundantChildTextShellCandidatesBeforeDiagnostics() {
        var pruneByIndex = {};
        var prunedAny = false;
        for (var parentIdx = 0; parentIdx < metas.length; parentIdx++) {
            if (pruneByIndex[String(parentIdx)]) continue;
            var parentMeta = metas[parentIdx];
            if (!parentMeta || !parentMeta.isShell) continue;
            var samePageShells = shellIndexesByPage[parentMeta.pageKey] || [];
            for (var shellPageCursor = 0; shellPageCursor < samePageShells.length; shellPageCursor++) {
                var childIdx = samePageShells[shellPageCursor];
                if (parentIdx === childIdx) continue;
                if (pruneByIndex[String(childIdx)]) continue;
                if (isRedundantChildTextShell(metas[childIdx], parentMeta)) {
                    pruneByIndex[String(childIdx)] = true;
                    prunedAny = true;
                }
            }
        }
        if (!prunedAny) return false;
        var kept = [];
        for (var ci = 0; ci < candidates.length; ci++) {
            if (pruneByIndex[String(ci)]) continue;
            kept.push(candidates[ci]);
        }
        candidates.length = 0;
        for (var ki = 0; ki < kept.length; ki++) candidates.push(kept[ki]);
        return true;
    }

    function pruneSuppressedDirectChildShellParentCandidatesBeforeDiagnostics() {
        var prunedAny = false;
        var kept = [];
        for (var ci = 0; ci < candidates.length; ci++) {
            var candidate = candidates[ci];
            if (candidate && candidate.suppressedByDirectChildShellSlots === true) {
                prunedAny = true;
                continue;
            }
            kept.push(candidate);
        }
        if (!prunedAny) return false;
        candidates.length = 0;
        for (var ki = 0; ki < kept.length; ki++) candidates.push(kept[ki]);
        return true;
    }

    function rebuildSamePageShellSourceSetsForPruning() {
        shellSourceSetsByPage = {};
        shellVisibleSourceIndexByPage = {};
        for (var i = 0; i < metas.length; i++) {
            var candidate = metas[i].candidate;
            if (!candidate || candidate.passId !== "pass.decoration_groups") continue;
            if (candidate.candidatePurpose !== "SHELL_CANDIDATE") continue;
            if (candidate.suppressedByDirectChildShellSlots === true) continue;
            if (!candidate.sourceObjectIds || candidate.sourceObjectIds.length === 0) continue;
            var pageKey = metas[i].pageKey;
            if (!shellSourceSetsByPage[pageKey]) shellSourceSetsByPage[pageKey] = [];
            shellSourceSetsByPage[pageKey].push(metas[i]);
        }
    }

    function pruneSamePageShellSubsumedCandidatesBeforeDiagnostics() {
        rebuildSamePageShellSourceSetsForPruning();
        var pruneByIndex = {};
        var prunedAny = false;
        for (var ci = 0; ci < metas.length; ci++) {
            if (pruneByIndex[String(ci)]) continue;
            if (findSamePageShellSubsumingCandidateIndex(metas[ci]) !== null) {
                pruneByIndex[String(ci)] = true;
                prunedAny = true;
            }
        }
        if (!prunedAny) return false;
        var kept = [];
        for (var ki = 0; ki < candidates.length; ki++) {
            if (pruneByIndex[String(ki)]) continue;
            kept.push(candidates[ki]);
        }
        candidates.length = 0;
        for (var oi = 0; oi < kept.length; oi++) candidates.push(kept[oi]);
        return true;
    }

    function applyPlannedShellExportTarget(candidate, editableIds) {
        if (!_isExtractionShellCandidate(candidate)) return false;
        if (!editableIds || editableIds.length === 0) return false;
        var targetId = bestShellExportTargetId(candidate, editableIds);
        if (targetId === null || targetId === undefined) return false;
        var exportIds = sourceIdsInSubtree(candidate, targetId);
        if (exportIds.length === 0) return false;
        return completeTextOwningShellExportContract(candidate, editableIds, exportIds, targetId);
    }

    function editableTextIdsOwnedByDeclaredShell(candidate, editableIds) {
        var ids = [];
        var seen = {};
        for (var i = 0; editableIds && i < editableIds.length; i++) {
            var textId = editableIds[i];
            var textSource = sourceInfoById[String(textId)];
            if (inlineCandidateSourceIdSet[String(textId)]) continue;
            if (inlineOwnedTextFrameSet[String(textId)]) continue;
            if (textSource && String(textSource.storyAnchorPlacement || "").toUpperCase() === "INLINE") continue;
            if (sourceHasInlineAnchorAncestor(textId)) continue;
            _pushUniqueId(ids, seen, textId);
        }
        return _sortedNumericIds(ids);
    }

    function sourceIdsNotOwnedByInlineObjects(sourceIds) {
        var ids = [];
        var seen = {};
        for (var i = 0; sourceIds && i < sourceIds.length; i++) {
            var sourceId = sourceIds[i];
            if (inlineOwnedSourceSet[String(sourceId)]) continue;
            _pushUniqueId(ids, seen, sourceId);
        }
        return _sortedNumericIds(ids);
    }

    function sourceIdsWithinCandidateBoundary(candidate, sourceIds) {
        if (!candidate || !sourceIds || sourceIds.length === 0) return [];
        var boundary = _sourceIdSet(candidate.sourceObjectIds || []);
        var ids = [];
        var seen = {};
        for (var i = 0; i < sourceIds.length; i++) {
            var sourceId = sourceIds[i];
            if (!boundary[String(sourceId)]) continue;
            _pushUniqueId(ids, seen, sourceId);
        }
        return _sortedNumericIds(ids);
    }

    var metas = [];
    var indexesByPage = {};
    var shellIndexesByPage = {};
    var shellSourceSetsByPage = {};
    var shellVisibleSourceIndexByPage = {};
    var directChildShellSourceIndex = null;
    var textFrameStyleCoverageIndex = null;
    var inlineDirectChildShellCoverageIndex = null;

    function rebuildCandidateMetaIndexes() {
        var indexes = _buildExtractionCandidateMetaIndexes(candidates, sourceInfoById);
        metas = indexes.metas;
        indexesByPage = indexes.indexesByPage;
        shellIndexesByPage = indexes.shellIndexesByPage;
        shellVisibleSourceIndexByPage = {};
        directChildShellSourceIndex = null;
        textFrameStyleCoverageIndex = null;
        inlineDirectChildShellCoverageIndex = null;
    }

    rebuildCandidateMetaIndexes();

    for (var closedShellIdx = 0; closedShellIdx < metas.length; closedShellIdx++) {
        var closedShellMeta = metas[closedShellIdx];
        if (!closedShellMeta || !closedShellMeta.isShell) continue;
        if (!applyClosedTextOwningShellContract(closedShellMeta.candidate)
                && !applyClosedTextlessShellFragmentContract(closedShellMeta.candidate)) continue;
        refreshCandidateIdentity(closedShellMeta.candidate);
        refreshCandidateMeta(closedShellMeta);
    }

    for (var slotIdx = 0; slotIdx < metas.length; slotIdx++) {
        var shellMeta = metas[slotIdx];
        var shellCandidate = shellMeta.candidate;
        if (!shellMeta.isShell) continue;
        if (_isPlannerDeclaredDirectChildShellSlot(shellCandidate)) continue;
        if (!shellMeta.hasEditableText) continue;
        if (shellCandidate.slotRole === "shell_slot_only"
                && shellCandidate.exportSourceObjectIds
                && shellCandidate.exportSourceObjectIds.length > 0
                && shellCandidate.hiddenVisualSourceObjectIds
                && shellCandidate.hiddenVisualSourceObjectIds.length > 0) {
            continue;
        }

        var excludedVisualIds = [];
        var excludedSeen = {};
        var pageIndexes = indexesByPage[shellMeta.pageKey] || [];
        for (var childPageIdx = 0; childPageIdx < pageIndexes.length; childPageIdx++) {
            var childIdx = pageIndexes[childPageIdx];
            var childMeta = metas[childIdx];
            if (!isIndependentVisualChildCandidate(childMeta, shellMeta)) continue;
            for (var ci = 0; ci < childMeta.sourceIds.length; ci++) {
                _pushUniqueId(excludedVisualIds, excludedSeen, childMeta.sourceIds[ci]);
            }
        }
        if (excludedVisualIds.length === 0) continue;
        shellCandidate.hiddenVisualSourceObjectIds = _sortedNumericIds(excludedVisualIds);
        shellCandidate.slotRole = "shell_slot_only";
        shellCandidate.mode = "SLOT_ONLY";
        applyPlannedShellExportTarget(shellCandidate, shellMeta.editableTextIds);
        if (!shellCandidate.exportSourceObjectIds || shellCandidate.exportSourceObjectIds.length === 0) {
            var exportSourceIds = concreteShellExportSourceIds(shellCandidate);
            if (exportSourceIds.length > 0) {
                shellCandidate.exportSourceObjectIds = exportSourceIds;
                shellCandidate.exportTargetObjectId = exportSourceIds.length === 1 ? exportSourceIds[0] : null;
            }
        }
        refreshCandidateIdentity(shellCandidate);
        refreshCandidateMeta(shellMeta);
    }

    for (var shellExportIdx = 0; shellExportIdx < metas.length; shellExportIdx++) {
        var shellExportMeta = metas[shellExportIdx];
        if (!shellExportMeta.isShell || !shellExportMeta.hasEditableText) continue;
        if (_isPlannerDeclaredDirectChildShellSlot(shellExportMeta.candidate)) continue;
        if (shellExportMeta.candidate
                && shellExportMeta.candidate.exportSourceObjectIds
                && shellExportMeta.candidate.exportSourceObjectIds.length > 0) {
            if (completeTextOwningShellExportContract(
                    shellExportMeta.candidate, shellExportMeta.editableTextIds, null, undefined)) {
                refreshCandidateIdentity(shellExportMeta.candidate);
                refreshCandidateMeta(shellExportMeta);
            }
            continue;
        }
        var plannedShellContractApplied = applyPlannedShellExportTarget(
                shellExportMeta.candidate, shellExportMeta.editableTextIds);
        if (plannedShellContractApplied
                || applyExactTextOwningShellExportContract(
                shellExportMeta.candidate, shellExportMeta.editableTextIds)) {
            refreshCandidateIdentity(shellExportMeta.candidate);
            refreshCandidateMeta(shellExportMeta);
        }
    }

    var appendedCompositeChildShellSlots = appendCompositeChildShellSlots();
    if (appendedCompositeChildShellSlots) {
        rebuildCandidateMetaIndexes();
    }
    if (appendTextFrameStyleShellSlotsForCompositeCarriers()) {
        rebuildCandidateMetaIndexes();
    }
    if (appendIndependentTextFrameStyleShellSlots()) {
        rebuildCandidateMetaIndexes();
    }
    rebuildIndependentTextFrameStyleShellIds();
    if (excludeDeclaredChildShellSlotsFromParentExports()) {
        rebuildCandidateMetaIndexes();
        rebuildIndependentTextFrameStyleShellIds();
    }

    var normalizedBlockedParentShellExports = false;
    for (var blockedShellIdx = 0; blockedShellIdx < metas.length; blockedShellIdx++) {
        var blockedShellMeta = metas[blockedShellIdx];
        var blockedShellCandidate = blockedShellMeta ? blockedShellMeta.candidate : null;
        if (!blockedShellCandidate || blockedShellCandidate.directChildShellSplitBlocked !== true) continue;
        if (!blockedShellMeta.isShell || !blockedShellMeta.hasEditableText) continue;
        if (completeTextOwningShellExportContract(
                blockedShellCandidate, blockedShellMeta.editableTextIds, null, undefined)) {
            normalizedBlockedParentShellExports = true;
            refreshCandidateIdentity(blockedShellCandidate);
            refreshCandidateMeta(blockedShellMeta);
        }
    }
    if (normalizedBlockedParentShellExports) {
        rebuildCandidateMetaIndexes();
    }

    var includedHiddenInlineProvenance = false;
    for (var hiddenInlineIdx = 0; hiddenInlineIdx < metas.length; hiddenInlineIdx++) {
        if (includeHiddenInlineSourceProvenance(metas[hiddenInlineIdx].candidate)) {
            includedHiddenInlineProvenance = true;
            refreshCandidateIdentity(metas[hiddenInlineIdx].candidate);
            refreshCandidateMeta(metas[hiddenInlineIdx]);
        }
    }
    if (includedHiddenInlineProvenance) {
        rebuildCandidateMetaIndexes();
    }

    var normalizedInlineTextShellContracts = false;
    for (var inlineTextShellIdx = 0; inlineTextShellIdx < metas.length; inlineTextShellIdx++) {
        if (normalizeInlineTextOwningShellContract(metas[inlineTextShellIdx].candidate)) {
            normalizedInlineTextShellContracts = true;
            refreshCandidateMeta(metas[inlineTextShellIdx]);
        }
    }
    if (normalizedInlineTextShellContracts) {
        rebuildCandidateMetaIndexes();
        if (appendCompositeChildShellSlots()) {
            rebuildCandidateMetaIndexes();
        }
        if (splitInlineSiblingShellTextSlots()) {
            rebuildCandidateMetaIndexes();
        }
    }

    var removedInlineAnchoredExports = false;
    for (var inlineAnchoredExportIdx = 0; inlineAnchoredExportIdx < metas.length; inlineAnchoredExportIdx++) {
        if (removeInlineAnchorSourcesFromFloatingShellExport(metas[inlineAnchoredExportIdx].candidate)) {
            removedInlineAnchoredExports = true;
            refreshCandidateIdentity(metas[inlineAnchoredExportIdx].candidate);
            refreshCandidateMeta(metas[inlineAnchoredExportIdx]);
        }
    }
    if (removedInlineAnchoredExports) {
        rebuildCandidateMetaIndexes();
    }

    var normalizedTextFrameShellStyles = false;
    for (var styleShellIdx = 0; styleShellIdx < metas.length; styleShellIdx++) {
        if (normalizeTextFrameShellStyleExports(metas[styleShellIdx])) {
            normalizedTextFrameShellStyles = true;
        }
    }
    if (normalizedTextFrameShellStyles) {
        rebuildCandidateMetaIndexes();
    }

    if (pruneRedundantChildTextShellCandidatesBeforeDiagnostics()) {
        rebuildCandidateMetaIndexes();
    }
    if (pruneSuppressedDirectChildShellParentCandidatesBeforeDiagnostics()) {
        rebuildCandidateMetaIndexes();
    }
    if (pruneSamePageShellSubsumedCandidatesBeforeDiagnostics()) {
        rebuildCandidateMetaIndexes();
    }

    var completedExportSourceProvenance = false;
    for (var provenanceIdx = 0; provenanceIdx < metas.length; provenanceIdx++) {
        if (completeExportSourceProvenance(metas[provenanceIdx].candidate)) {
            completedExportSourceProvenance = true;
            refreshCandidateMeta(metas[provenanceIdx]);
        }
    }
    if (completedExportSourceProvenance) {
        rebuildCandidateMetaIndexes();
        if (pruneSamePageShellSubsumedCandidatesBeforeDiagnostics()) {
            rebuildCandidateMetaIndexes();
        }
    }

    var legacyNormalizationFilterDiagnostics = {
        schemaVersion: 1,
        policy: "POLICY-source-ownership",
        mode: "legacy-normalization-filter-diagnostics",
        summary: {
            filteredCount: 0,
            reasonCounts: {}
        },
        filteredCandidates: []
    };
    var legacyFilterReasonByIndex = {};
    var legacyFilterOwnerByIndex = {};
    var legacyFilterRecordedByIndex = {};

    function markLegacyNormalizationFilter(index, reason, ownerIndex) {
        if (index === null || index === undefined) return;
        var key = String(index);
        if (!legacyFilterReasonByIndex[key]) {
            legacyFilterReasonByIndex[key] = reason || "UNKNOWN";
            if (ownerIndex !== null && ownerIndex !== undefined) {
                legacyFilterOwnerByIndex[key] = ownerIndex;
            }
        }
    }

    function recordLegacyNormalizationFilter(index, reason, ownerIndex) {
        if (index === null || index === undefined) return;
        var key = String(index);
        if (legacyFilterRecordedByIndex[key]) return;
        var candidate = metas[index] ? metas[index].candidate : null;
        if (!candidate) return;
        reason = reason || legacyFilterReasonByIndex[key] || "UNKNOWN";
        if (ownerIndex === null || ownerIndex === undefined) ownerIndex = legacyFilterOwnerByIndex[key];
        var ownerCandidate = ownerIndex !== null && ownerIndex !== undefined && metas[ownerIndex]
                ? metas[ownerIndex].candidate
                : null;
        legacyFilterRecordedByIndex[key] = true;
        legacyNormalizationFilterDiagnostics.summary.filteredCount++;
        if (!legacyNormalizationFilterDiagnostics.summary.reasonCounts[reason]) {
            legacyNormalizationFilterDiagnostics.summary.reasonCounts[reason] = 0;
        }
        legacyNormalizationFilterDiagnostics.summary.reasonCounts[reason]++;
        legacyNormalizationFilterDiagnostics.filteredCandidates.push({
            candidateId: candidate.candidateId || null,
            passId: candidate.passId || null,
            pageIndex: candidate.pageIndex,
            reason: reason,
            sourceObjectIds: _sortedNumericIds(candidate.sourceObjectIds || []),
            visualSourceObjectIds: _sortedNumericIds(candidate.visualSourceObjectIds || []),
            exportSourceObjectIds: _sortedNumericIds(candidate.exportSourceObjectIds || []),
            styleSourceObjectIds: _sortedNumericIds(candidate.styleSourceObjectIds || []),
            suppressionReason: candidate.suppressedByDirectChildShellSlotsReason || null,
            suppressionSourceObjectIds: _sortedNumericIds(candidate.suppressedByDirectChildShellSlotSourceIds || []),
            suppressionCandidateIds: candidate.suppressedByDirectChildShellSlotCandidateIds || [],
            clipParentShellOwnerSourceId: candidate.clipParentShellOwnerSourceId || null,
            clipParentShellOwnerSourceObjectIds: _sortedNumericIds(candidate.clipParentShellOwnerSourceObjectIds || []),
            ownerCandidateId: ownerCandidate ? ownerCandidate.candidateId || null : null,
            ownerPassId: ownerCandidate ? ownerCandidate.passId || null : null,
            ownerSourceObjectIds: ownerCandidate ? _sortedNumericIds(ownerCandidate.sourceObjectIds || []) : []
        });
    }

    var invalidSlotOnlyCandidateIndexes = {};
    for (var slotOnlyIdx = 0; slotOnlyIdx < metas.length; slotOnlyIdx++) {
        var slotOnlyMeta = metas[slotOnlyIdx];
        if (!slotOnlyMeta || !slotOnlyMeta.isShell) continue;
        if (!_isSlotOnlyShellWithHiddenChildren(slotOnlyMeta.candidate)) continue;
        if (completeSlotOnlyShellExportSources(slotOnlyMeta)) continue;
        invalidSlotOnlyCandidateIndexes[String(slotOnlyIdx)] = true;
        markLegacyNormalizationFilter(slotOnlyIdx, "INVALID_SLOT_ONLY_HIDDEN_CHILDREN", null);
    }

    var dropCandidateIndexes = {};
    for (var parentIdx = 0; parentIdx < metas.length; parentIdx++) {
        var parentMeta = metas[parentIdx];
        if (invalidSlotOnlyCandidateIndexes[String(parentIdx)]) continue;
        if (!parentMeta.isShell) continue;
        var samePageShells = shellIndexesByPage[parentMeta.pageKey] || [];
        for (var shellPageCursor = 0; shellPageCursor < samePageShells.length; shellPageCursor++) {
            var redundantIdx = samePageShells[shellPageCursor];
            if (parentIdx === redundantIdx) continue;
            if (invalidSlotOnlyCandidateIndexes[String(redundantIdx)]) continue;
            if (isRedundantChildTextShell(metas[redundantIdx], parentMeta)) {
                dropCandidateIndexes[String(redundantIdx)] = true;
                markLegacyNormalizationFilter(redundantIdx, "REDUNDANT_CHILD_TEXT_SHELL", parentIdx);
            }
        }
    }

    for (var clipIdx = 0; clipIdx < metas.length; clipIdx++) {
        var clipParentMeta = metas[clipIdx];
        var clipParentCandidate = clipParentMeta.candidate;
        if (invalidSlotOnlyCandidateIndexes[String(clipIdx)]) continue;
        if (dropCandidateIndexes[String(clipIdx)]) continue;
        if (!isClipParentShellCandidate(clipParentCandidate)) continue;
        var clipPageIndexes = indexesByPage[clipParentMeta.pageKey] || [];
        for (var childVisualCursor = 0; childVisualCursor < clipPageIndexes.length; childVisualCursor++) {
            var childVisualIdx = clipPageIndexes[childVisualCursor];
            if (clipIdx === childVisualIdx) continue;
            if (dropCandidateIndexes[String(childVisualIdx)]) continue;
            if (isChildOnlyVisualOfClipParent(metas[childVisualIdx], clipParentMeta)) {
                dropCandidateIndexes[String(childVisualIdx)] = true;
                var clipFilterReason = metas[childVisualIdx].sourceKey === clipParentMeta.sourceKey
                        ? "CLIP_PARENT_SHELL_ALTERNATE_PASS"
                        : "CHILD_ONLY_VISUAL_OF_CLIP_PARENT";
                markLegacyNormalizationFilter(childVisualIdx, clipFilterReason, clipIdx);
            }
        }
    }

    shellSourceSetsByPage = {};
    for (var i = 0; i < metas.length; i++) {
        var candidate = metas[i].candidate;
        if (invalidSlotOnlyCandidateIndexes[String(i)]) continue;
        if (dropCandidateIndexes[String(i)]) continue;
        if (!candidate || candidate.passId !== "pass.decoration_groups") continue;
        if (candidate.candidatePurpose !== "SHELL_CANDIDATE") continue;
        if (candidate.suppressedByDirectChildShellSlots === true) continue;
        if (!candidate.sourceObjectIds || candidate.sourceObjectIds.length === 0) continue;
        var pageKey = metas[i].pageKey;
        if (!shellSourceSetsByPage[pageKey]) shellSourceSetsByPage[pageKey] = [];
        shellSourceSetsByPage[pageKey].push(metas[i]);
    }

    function shellMetasContainingVisibleSource(pageKey, sourceId) {
        var pageIndex = shellVisibleSourceIndexByPage[pageKey];
        if (!pageIndex) {
            pageIndex = {};
            var pageShells = shellSourceSetsByPage[pageKey] || [];
            for (var psi = 0; psi < pageShells.length; psi++) {
                var shellMeta = pageShells[psi];
                var shellCandidate = shellMeta ? shellMeta.candidate : null;
                if (!shellCandidate || shellCandidate.suppressedByDirectChildShellSlots === true) continue;
                var visibleIds = visibleShellSlotSourceIds(shellCandidate);
                for (var vsi = 0; visibleIds && vsi < visibleIds.length; vsi++) {
                    var key = String(visibleIds[vsi]);
                    if (!pageIndex[key]) pageIndex[key] = [];
                    pageIndex[key].push(shellMeta);
                }
            }
            shellVisibleSourceIndexByPage[pageKey] = pageIndex;
        }
        return pageIndex[String(sourceId)] || [];
    }

    function isClosedGraphicOnlyDecorationShell(candidateMeta) {
        var candidate = candidateMeta ? candidateMeta.candidate : null;
        if (!candidate || candidate.passId !== "pass.decoration_groups") return false;
        if (candidate.candidatePurpose !== "SHELL_CANDIDATE") return false;
        if (!candidate.sourceObjectIds || candidate.sourceObjectIds.length <= 1) return false;
        if (candidateMeta.hasEditableText) return false;
        if (candidate.editableTextFrameIds && candidate.editableTextFrameIds.length > 0) return false;
        if (candidate.hiddenTextFrameIds && candidate.hiddenTextFrameIds.length > 0) return false;
        if (candidate.hiddenVisualSourceObjectIds && candidate.hiddenVisualSourceObjectIds.length > 0) return false;
        if (candidate.exportSourceObjectIds && candidate.exportSourceObjectIds.length > 0) return false;
        return true;
    }

    function findSamePageShellSubsumingCandidateIndex(candidateMeta) {
        var candidate = candidateMeta ? candidateMeta.candidate : null;
        if (!candidate || !candidate.sourceObjectIds || candidate.sourceObjectIds.length === 0) return null;
        var passId = candidate.passId;
        if (passId !== "pass.vector_shape_frames"
                && passId !== "pass.complex_graphic_frames"
                && passId !== "pass.decoration_groups") return null;
        var candidateVisibleIds = _isExtractionShellCandidate(candidate)
                ? visibleShellSlotSourceIds(candidate)
                : _sortedNumericIds(candidate.sourceObjectIds || []);
        if (!candidateVisibleIds || candidateVisibleIds.length === 0) return null;
        var shells = shellMetasContainingVisibleSource(candidateMeta.pageKey, candidateVisibleIds[0]);
        for (var s = 0; s < shells.length; s++) {
            var shellMeta = shells[s];
            if (!shellMeta || shellMeta.index === candidateMeta.index) continue;
            var shellCandidate = shellMeta.candidate;
            if (!shellCandidate || shellCandidate.suppressedByDirectChildShellSlots === true) continue;
            var shellVisibleIds = visibleShellSlotSourceIds(shellCandidate);
            if (!_candidateSourceIdArrayContainsAll(shellVisibleIds, candidateVisibleIds)) continue;
            if (passId === "pass.decoration_groups") {
                if ((shellMeta.candidate.slotRole === "shell_slot_only" || shellMeta.candidate.mode === "SLOT_ONLY")
                        && _candidateSourceIdArrayContainsAll(shellMeta.candidate.hiddenVisualSourceObjectIds || [],
                                candidateVisibleIds)) {
                    return shellMeta.index;
                }
                if (isClosedGraphicOnlyDecorationShell(shellMeta)) return shellMeta.index;
                if (!_candidateSourceIdArrayContainsAll(
                        shellMeta.candidate ? shellMeta.candidate.exportSourceObjectIds : null,
                        candidateVisibleIds)) {
                    continue;
                }
            }
            return shellMeta.index;
        }
        return null;
    }

    function isSubsumedBySamePageShell(candidateMeta) {
        return findSamePageShellSubsumingCandidateIndex(candidateMeta) !== null;
    }

    var inlineSourceSetsByPage = {};
    for (var inlineIdx = 0; inlineIdx < metas.length; inlineIdx++) {
        var inlineCandidate = metas[inlineIdx].candidate;
        if (invalidSlotOnlyCandidateIndexes[String(inlineIdx)]) continue;
        if (!inlineCandidate || inlineCandidate.passId !== "pass.inline_objects") continue;
        if (!inlineCandidate.sourceObjectIds || inlineCandidate.sourceObjectIds.length === 0) continue;
        var inlinePageKey = metas[inlineIdx].pageKey;
        if (!inlineSourceSetsByPage[inlinePageKey]) inlineSourceSetsByPage[inlinePageKey] = {};
        inlineSourceSetsByPage[inlinePageKey][metas[inlineIdx].sourceKey] = true;
    }

    function isDuplicateFloatingShellOfInline(candidateMeta) {
        var candidate = candidateMeta ? candidateMeta.candidate : null;
        if (!candidate || candidate.passId !== "pass.decoration_groups") return false;
        if (candidate.candidatePurpose !== "SHELL_CANDIDATE") return false;
        if (!candidate.sourceObjectIds || candidate.sourceObjectIds.length === 0) return false;
        var inlineSets = inlineSourceSetsByPage[candidateMeta.pageKey];
        return inlineSets && inlineSets[candidateMeta.sourceKey] === true;
    }

    function isTextFrameStyleShellSubsumedByCompositeShell(candidateMeta) {
        var candidate = candidateMeta ? candidateMeta.candidate : null;
        if (!candidate || candidate.passId !== "pass.editable_textframe_visual_shells") return false;
        if (!candidate.sourceObjectIds || candidate.sourceObjectIds.length !== 1) return false;
        var textFrameId = candidate.sourceObjectIds[0];
        if (!sourceHasTextFrameShellStyle(textFrameId)
                && !_sourceIdsContain(candidate.styleSourceObjectIds, textFrameId)
                && !_sourceIdsContain(candidate.exportSourceObjectIds, textFrameId)) {
            return false;
        }
        var shells = shellSourceSetsByPage[candidateMeta.pageKey] || [];
        for (var s = 0; s < shells.length; s++) {
            var shellMeta = shells[s];
            var shellCandidate = shellMeta ? shellMeta.candidate : null;
            if (!shellCandidate || shellCandidate.passId !== "pass.decoration_groups") continue;
            if (shellCandidate === candidate) continue;
            if (!_sourceIdsContain(shellCandidate.exportSourceObjectIds, textFrameId)
                    && !_sourceIdsContain(shellCandidate.styleSourceObjectIds, textFrameId)) {
                continue;
            }
            var ownedIds = shellCandidate.editableTextFrameIds && shellCandidate.editableTextFrameIds.length > 0
                    ? shellCandidate.editableTextFrameIds
                    : shellCandidate.hiddenTextFrameIds;
            if (!_sourceIdsContain(ownedIds, textFrameId)
                    && !_sourceIdsContain(shellCandidate.styleSourceObjectIds, textFrameId)) {
                continue;
            }
            return true;
        }
        return false;
    }

    function visibleShellSlotSourceIds(candidate) {
        if (!candidate) return [];
        var ids = [];
        if (candidate.exportSourceObjectIds && candidate.exportSourceObjectIds.length > 0) {
            ids = _sortedNumericIds(candidate.exportSourceObjectIds);
            if ((candidate.candidatePurpose === "SHELL_CANDIDATE"
                        || candidate.slotRole === "direct_child_shell_slot"
                        || candidate.compositeRole === "direct_child_shell_slot")
                    && ids.length > 1) {
                var textOwned = _sourceIdSet(candidate.hiddenTextFrameIds || candidate.editableTextFrameIds || []);
                var visibleOnly = [];
                var seenVisibleOnly = {};
                for (var ei = 0; ei < ids.length; ei++) {
                    var id = ids[ei];
                    if (textOwned[String(id)]) continue;
                    var src = sourceInfoById[String(id)];
                    if (src && src.kind === "TextFrame" && src.hasText === true) continue;
                    _pushUniqueId(visibleOnly, seenVisibleOnly, id);
                }
                if (visibleOnly.length > 0) return _sortedNumericIds(visibleOnly);
            }
            return ids;
        }
        if (candidate.styleSourceObjectIds && candidate.styleSourceObjectIds.length > 0) {
            return _sortedNumericIds(candidate.styleSourceObjectIds);
        }
        return _sortedNumericIds(candidate.sourceObjectIds || []);
    }

    function exactShellSlotDuplicateKey(candidateMeta) {
        var candidate = candidateMeta ? candidateMeta.candidate : null;
        if (!isExactShellSlotCompetitor(candidate)) return null;
        var visibleIds = visibleShellSlotSourceIds(candidate);
        if (!visibleIds || visibleIds.length === 0) return null;
        return candidateMeta.pageKey + "|SHELL_SLOT|" + _sourceSetKey(visibleIds);
    }

    function isExactShellSlotCompetitor(candidate) {
        if (_isExtractionShellCandidate(candidate)) return true;
        if (!candidate || candidate.passId !== "pass.inline_objects") return false;
        if (candidate.slotRole !== "direct_child_shell_slot"
                && candidate.compositeRole !== "direct_child_shell_slot") return false;
        return (candidate.visualSourceObjectIds && candidate.visualSourceObjectIds.length > 0)
                || (candidate.exportSourceObjectIds && candidate.exportSourceObjectIds.length > 0)
                || (candidate.sourceObjectIds && candidate.sourceObjectIds.length > 0);
    }

    function exactShellSlotCandidatePriority(candidate) {
        if (!candidate) return 0;
        var score = 10;
        if (candidate.slotRole === "direct_child_shell_slot") score += 40;
        if (candidate.compositeRole === "direct_child_shell_slot") score += 35;
        if (candidate.slotRole === "shell_slot_only" || candidate.mode === "SLOT_ONLY") score += 30;
        if (candidate.passId === "pass.inline_objects") score += 30;
        if (candidate.passId === "pass.decoration_groups") score += 20;
        if (candidate.passId === "pass.editable_textframe_visual_shells") score += 10;
        if (candidate.textOwner === "hwpx_tf"
                || (candidate.ownedTextFrameIds && candidate.ownedTextFrameIds.length > 0)
                || (candidate.hiddenTextFrameIds && candidate.hiddenTextFrameIds.length > 0)
                || (candidate.editableTextFrameIds && candidate.editableTextFrameIds.length > 0)) {
            score += 20;
        }
        return score;
    }

    var exactShellSlotDuplicateDiagnostics = {
        schemaVersion: 1,
        policy: "POLICY-source-ownership",
        mode: "exact-shell-slot-duplicate-diagnostics",
        summary: {
            preNormalizeCompetitionCount: 0,
            finalFilterCompetitionCount: 0,
            suppressedCount: 0
        },
        preNormalizeCompetitions: [],
        finalFilterCompetitions: []
    };

    function exactShellSlotDiagnosticCandidate(candidate, key) {
        var visibleIds = visibleShellSlotSourceIds(candidate);
        return {
            candidateId: candidate ? candidate.candidateId || null : null,
            passId: candidate ? candidate.passId || null : null,
            pageIndex: candidate ? candidate.pageIndex : null,
            key: key || null,
            priority: exactShellSlotCandidatePriority(candidate),
            sourceObjectIds: candidate ? _sortedNumericIds(candidate.sourceObjectIds || []) : [],
            visualSourceObjectIds: candidate ? _sortedNumericIds(candidate.visualSourceObjectIds || []) : [],
            exportSourceObjectIds: candidate ? _sortedNumericIds(candidate.exportSourceObjectIds || []) : [],
            styleSourceObjectIds: candidate ? _sortedNumericIds(candidate.styleSourceObjectIds || []) : [],
            visibleShellSlotSourceIds: _sortedNumericIds(visibleIds || []),
            slotRole: candidate ? candidate.slotRole || null : null,
            compositeRole: candidate ? candidate.compositeRole || null : null,
            mode: candidate ? candidate.mode || null : null,
            materialization: candidate ? candidate.materialization || null : null,
            visualAction: candidate ? candidate.visualAction || null : null
        };
    }

    function recordExactShellSlotDuplicate(stage, key, keptCandidate, suppressedCandidate, replaced) {
        if (!key || !suppressedCandidate) return;
        var entry = {
            stage: stage,
            key: key,
            replacedPreviousWinner: replaced === true,
            kept: exactShellSlotDiagnosticCandidate(keptCandidate, key),
            suppressed: exactShellSlotDiagnosticCandidate(suppressedCandidate, key)
        };
        if (stage === "pre_normalize") {
            exactShellSlotDuplicateDiagnostics.preNormalizeCompetitions.push(entry);
            exactShellSlotDuplicateDiagnostics.summary.preNormalizeCompetitionCount++;
        } else {
            exactShellSlotDuplicateDiagnostics.finalFilterCompetitions.push(entry);
            exactShellSlotDuplicateDiagnostics.summary.finalFilterCompetitionCount++;
        }
        exactShellSlotDuplicateDiagnostics.summary.suppressedCount++;
    }

	function assertNoExactShellSlotDuplicates(candidateList) {
		if (!candidateList || candidateList.length === 0) return candidateList || [];
		var winners = {};
		for (var fi = 0; fi < candidateList.length; fi++) {
			var candidate = candidateList[fi];
			if (!isExactShellSlotCompetitor(candidate)) continue;
			var key = String(candidate.pageIndex) + "|SHELL_SLOT|"
					+ _sourceSetKey(visibleShellSlotSourceIds(candidate));
			var current = winners[key];
			if (!current) {
				winners[key] = { candidate: candidate };
				continue;
			}
			var currentPriority = exactShellSlotCandidatePriority(current.candidate);
			var candidatePriority = exactShellSlotCandidatePriority(candidate);
			if (candidatePriority > currentPriority) {
				recordExactShellSlotDuplicate("final_filter", key, candidate, current.candidate, true);
				winners[key] = { candidate: candidate };
			} else {
				recordExactShellSlotDuplicate("final_filter", key, current.candidate, candidate, false);
			}
		}
		var out = [];
		for (var oi = 0; oi < candidateList.length; oi++) {
			var outCandidate = candidateList[oi];
			if (!isExactShellSlotCompetitor(outCandidate)) {
				out.push(outCandidate);
				continue;
			}
			var outKey = String(outCandidate.pageIndex) + "|SHELL_SLOT|"
					+ _sourceSetKey(visibleShellSlotSourceIds(outCandidate));
			var winner = winners[outKey];
			if (winner && winner.candidate === outCandidate) out.push(outCandidate);
		}
		return out;
	}

    function shellCandidateHasExecutableVisualMaterial(candidate) {
        return _candidateHasExecutableShellMaterial(candidate, sourceInfoById, childIdsByParentId);
    }

    function assertExecutableShellCandidates(candidateList) {
        for (var mi = 0; candidateList && mi < candidateList.length; mi++) {
            var candidate = candidateList[mi];
            if (!candidate || shellCandidateHasExecutableVisualMaterial(candidate)) continue;
            throw new Error("Non-executable shell candidate created"
                    + " candidateId=" + String(candidate.candidateId || "")
                    + " passId=" + String(candidate.passId || "")
                    + " pageIndex=" + String(candidate.pageIndex)
                    + " sourceObjectIds=" + String((candidate.sourceObjectIds || []).join(",")));
        }
        return candidateList || [];
    }

    var exactShellSlotWinnerByKey = {};
    for (var exactIdx = 0; exactIdx < metas.length; exactIdx++) {
        if (invalidSlotOnlyCandidateIndexes[String(exactIdx)]) continue;
        if (dropCandidateIndexes[String(exactIdx)]) continue;
        var exactMeta = metas[exactIdx];
        if (exactMeta && exactMeta.candidate
                && !shellCandidateHasExecutableVisualMaterial(exactMeta.candidate)) {
            continue;
        }
        var exactKey = exactShellSlotDuplicateKey(exactMeta);
        if (!exactKey) continue;
        var previousWinnerIdx = exactShellSlotWinnerByKey[exactKey];
        if (previousWinnerIdx === undefined || previousWinnerIdx === null) {
            exactShellSlotWinnerByKey[exactKey] = exactIdx;
            continue;
        }
        var previousCandidate = metas[previousWinnerIdx] ? metas[previousWinnerIdx].candidate : null;
        var currentCandidate = exactMeta.candidate;
        var previousPriority = exactShellSlotCandidatePriority(previousCandidate);
        var currentPriority = exactShellSlotCandidatePriority(currentCandidate);
        if (currentPriority > previousPriority) {
            recordExactShellSlotDuplicate("pre_normalize", exactKey, currentCandidate, previousCandidate, true);
            exactShellSlotWinnerByKey[exactKey] = exactIdx;
        } else {
            recordExactShellSlotDuplicate("pre_normalize", exactKey, previousCandidate, currentCandidate, false);
        }
    }

    var normalized = [];
    for (var j = 0; j < candidates.length; j++) {
        if (candidates[j] && candidates[j].suppressedByDirectChildShellSlots === true) {
            recordLegacyNormalizationFilter(
                    j,
                    candidates[j].suppressedByDirectChildShellSlotsReason
                            || "SUPPRESSED_BY_DIRECT_CHILD_SHELL_SLOTS",
                    null);
            continue;
        }
        if (invalidSlotOnlyCandidateIndexes[String(j)]) {
            recordLegacyNormalizationFilter(j, null, null);
            continue;
        }
        if (dropCandidateIndexes[String(j)]) {
            recordLegacyNormalizationFilter(j, null, null);
            continue;
        }
        if (_isExtractionShellCandidate(candidates[j])
                && !shellCandidateHasExecutableVisualMaterial(candidates[j])) {
            recordLegacyNormalizationFilter(j, "NON_EXECUTABLE_SHELL_CANDIDATE", null);
            continue;
        }
        if (isDuplicateFloatingShellOfInline(metas[j])) {
            recordLegacyNormalizationFilter(j, "DUPLICATE_FLOATING_SHELL_OF_INLINE", null);
            continue;
        }
        var samePageShellOwnerIndex = findSamePageShellSubsumingCandidateIndex(metas[j]);
        if (samePageShellOwnerIndex !== null) {
            recordLegacyNormalizationFilter(j, "SUBSUMED_BY_SAME_PAGE_SHELL", samePageShellOwnerIndex);
            continue;
        }
        if (isTextFrameStyleShellSubsumedByCompositeShell(metas[j])) {
            recordLegacyNormalizationFilter(j, "TEXTFRAME_STYLE_SHELL_SUBSUMED_BY_COMPOSITE", null);
            continue;
        }
        var exactDuplicateKey = exactShellSlotDuplicateKey(metas[j]);
        if (exactDuplicateKey) {
            var exactWinnerIndex = exactShellSlotWinnerByKey[exactDuplicateKey];
            if (exactWinnerIndex !== undefined && exactWinnerIndex !== null
                    && Number(exactWinnerIndex) !== j) {
                recordLegacyNormalizationFilter(j, "EXACT_SHELL_SLOT_DUPLICATE_LOSER", exactWinnerIndex);
                continue;
            }
        }
        normalized.push(candidates[j]);
    }
	var normalizedByKey = {};
	for (var nk = 0; nk < normalized.length; nk++) {
		if (!normalized[nk]) continue;
		normalizedByKey[normalized[nk].passId + "|" + normalized[nk].pageIndex + "|"
				+ _sourceSetKey(normalized[nk].sourceObjectIds)] = true;
	}
	var normalizedExactShellOwnerIndexByKey = {};
	function exactShellSlotKeyForCandidate(candidate) {
		if (!_isExtractionShellCandidate(candidate)) return null;
		var visibleIds = visibleShellSlotSourceIds(candidate);
		if (!visibleIds || visibleIds.length === 0) return null;
		return String(candidate.pageIndex) + "|SHELL_SLOT|" + _sourceSetKey(visibleIds);
	}
	for (var nek = 0; nek < normalized.length; nek++) {
		if (!normalized[nek]) continue;
		var normalizedExactKey = exactShellSlotKeyForCandidate(normalized[nek]);
		if (normalizedExactKey) normalizedExactShellOwnerIndexByKey[normalizedExactKey] = nek;
	}
	function appendPostNormalizedCandidate(candidate) {
		if (!candidate) return false;
		completeExportSourceProvenance(candidate);
		if (_isExtractionShellCandidate(candidate)
				&& !shellCandidateHasExecutableVisualMaterial(candidate)) {
			return false;
		}
		var sourceKey = candidate.passId + "|" + candidate.pageIndex + "|"
				+ _sourceSetKey(candidate.sourceObjectIds);
		if (normalizedByKey[sourceKey]) return false;
		var exactKey = exactShellSlotKeyForCandidate(candidate);
		if (exactKey) {
			var existingIndex = normalizedExactShellOwnerIndexByKey[exactKey];
			if (existingIndex !== undefined && existingIndex !== null && normalized[existingIndex]) {
				var existingCandidate = normalized[existingIndex];
				var existingPriority = exactShellSlotCandidatePriority(existingCandidate);
				var newPriority = exactShellSlotCandidatePriority(candidate);
				if (newPriority > existingPriority) {
					recordExactShellSlotDuplicate("post_normalize", exactKey, candidate, existingCandidate, true);
					normalized[existingIndex] = candidate;
					normalizedByKey[sourceKey] = true;
					return true;
				}
				recordExactShellSlotDuplicate("post_normalize", exactKey, existingCandidate, candidate, false);
				return false;
			}
			normalizedExactShellOwnerIndexByKey[exactKey] = normalized.length;
		}
		normalizedByKey[sourceKey] = true;
		normalized.push(candidate);
		return true;
	}
	for (var parentSlotIdx = 0; parentSlotIdx < normalized.length; parentSlotIdx++) {
		var residualParent = normalized[parentSlotIdx];
		var residualChildSlots = directChildShellSlotCandidatesFromHiddenSources(residualParent);
		for (var rci = 0; residualChildSlots && rci < residualChildSlots.length; rci++) {
			var residualChild = residualChildSlots[rci];
			appendPostNormalizedCandidate(residualChild);
		}
	}
	for (var styleParentIdx = 0; styleParentIdx < normalized.length; styleParentIdx++) {
		var styleParent = normalized[styleParentIdx];
        if (!styleParent || styleParent.passId !== "pass.decoration_groups") continue;
        if (styleParent.candidatePurpose !== "SHELL_CANDIDATE") continue;
        if (!styleParent.sourceObjectIds || styleParent.sourceObjectIds.length === 0) continue;
        var styleEditableIds = editableTextIdsInSourceSet(styleParent.sourceObjectIds, styleParent.pageIndex);
        for (var stei = 0; styleEditableIds && stei < styleEditableIds.length; stei++) {
			var styleTextFrameId = styleEditableIds[stei];
			var styleChild = makeTextFrameStyleShellSlotCandidate(styleParent, styleTextFrameId);
			if (!styleChild) continue;
			appendPostNormalizedCandidate(styleChild);
		}
	}
    inlineOwnedSourceSet = {};
    inlineOwnedTextFrameSet = {};
    for (var inlineOwnerIdx = 0; inlineOwnerIdx < normalized.length; inlineOwnerIdx++) {
        var inlineOwner = normalized[inlineOwnerIdx];
        if (!inlineOwner || inlineOwner.passId !== "pass.inline_objects") continue;
        var inlineOwnedIds = _sourceIdsUnion(
                inlineOwner.editableTextFrameIds || [], inlineOwner.hiddenTextFrameIds || []);
        inlineOwnedIds = _sourceIdsUnion(inlineOwnedIds, inlineOwner.sourceObjectIds || []);
        for (var ioti = 0; ioti < inlineOwnedIds.length; ioti++) {
            inlineOwnedSourceSet[String(inlineOwnedIds[ioti])] = true;
            var inlineOwnedSource = sourceInfoById[String(inlineOwnedIds[ioti])];
            if (inlineOwnedSource && String(inlineOwnedSource.kind || "") === "TextFrame") {
                inlineOwnedTextFrameSet[String(inlineOwnedIds[ioti])] = true;
            }
        }
    }
    for (var declaredClosedIdx = 0; declaredClosedIdx < normalized.length; declaredClosedIdx++) {
        var declaredClosed = normalized[declaredClosedIdx];
        if (!declaredClosed || declaredClosed.compositeRole !== "source_declared_closed_text_shell") continue;
        if (!_isExtractionShellCandidate(declaredClosed)) continue;
        var declaredEditableIds = declaredClosed.editableTextFrameIds
                && declaredClosed.editableTextFrameIds.length > 0
                ? declaredClosed.editableTextFrameIds
                : editableTextIdsInSourceSet(declaredClosed.sourceObjectIds, declaredClosed.pageIndex);
        declaredEditableIds = editableTextIdsOwnedByDeclaredShell(
                declaredClosed, declaredEditableIds);
        var declaredShellExportIds = textlessShellExportSourceIds(
                declaredClosed.sourceObjectIds, declaredEditableIds);
        declaredShellExportIds = sourceIdsNotOwnedByInlineObjects(declaredShellExportIds);
        if (!declaredShellExportIds || declaredShellExportIds.length === 0) continue;
        declaredClosed.editableTextFrameIds = declaredEditableIds;
        declaredClosed.hiddenTextFrameIds = declaredEditableIds;
        declaredClosed.exportSourceObjectIds = _sourceIdsUnion(
                declaredClosed.exportSourceObjectIds || [], declaredShellExportIds);
        declaredClosed.exportSourceObjectIds = sourceIdsNotOwnedByInlineObjects(
                declaredClosed.exportSourceObjectIds);
        declaredClosed.sourceObjectIds = sourceIdsNotOwnedByInlineObjects(
                declaredClosed.sourceObjectIds || []);
        declaredClosed.exportSourceObjectIds = sourceIdsWithinCandidateBoundary(
                declaredClosed, declaredClosed.exportSourceObjectIds);
        declaredClosed.hiddenVisualSourceObjectIds = _sourceIdsMinus(
                declaredClosed.hiddenVisualSourceObjectIds || [], declaredClosed.exportSourceObjectIds);
        declaredClosed.hiddenVisualSourceObjectIds = sourceIdsNotOwnedByInlineObjects(
                declaredClosed.hiddenVisualSourceObjectIds || []);
        declaredClosed.hiddenVisualSourceObjectIds = sourceIdsWithinCandidateBoundary(
                declaredClosed, declaredClosed.hiddenVisualSourceObjectIds);
        completeExportSourceProvenance(declaredClosed);
        refreshCandidateIdentity(declaredClosed);
    }
    var completedClosedTextlessGroupSlots = false;
    for (var textlessGroupIdx = 0; textlessGroupIdx < normalized.length; textlessGroupIdx++) {
        if (completeClosedTextlessGroupVisualSlot(normalized[textlessGroupIdx])) {
            completedClosedTextlessGroupSlots = true;
        }
    }
    if (completedClosedTextlessGroupSlots) {
        normalizedByKey = {};
        normalizedExactShellOwnerIndexByKey = {};
        for (var rebuiltIdx = 0; rebuiltIdx < normalized.length; rebuiltIdx++) {
            if (!normalized[rebuiltIdx]) continue;
            normalizedByKey[normalized[rebuiltIdx].passId + "|" + normalized[rebuiltIdx].pageIndex + "|"
                    + _sourceSetKey(normalized[rebuiltIdx].sourceObjectIds)] = true;
            var rebuiltExactKey = exactShellSlotKeyForCandidate(normalized[rebuiltIdx]);
            if (rebuiltExactKey) normalizedExactShellOwnerIndexByKey[rebuiltExactKey] = rebuiltIdx;
        }
    }
    var exactValidated = assertNoExactShellSlotDuplicates(normalized);
    _LEGACY_NORMALIZATION_FILTER_DIAGNOSTICS = legacyNormalizationFilterDiagnostics;
    try {
        if (typeof $ !== "undefined" && $.global) {
            $.global._LEGACY_NORMALIZATION_FILTER_DIAGNOSTICS = legacyNormalizationFilterDiagnostics;
        }
    } catch (eLegacyNormalizationDiagnostics) {}
    _EXACT_SHELL_SLOT_DUPLICATE_DIAGNOSTICS = exactShellSlotDuplicateDiagnostics;
    try {
        if (typeof $ !== "undefined" && $.global) {
            $.global._EXACT_SHELL_SLOT_DUPLICATE_DIAGNOSTICS = exactShellSlotDuplicateDiagnostics;
        }
    } catch (eExactShellSlotDiagnostics) {}
	return assertExecutableShellCandidates(exactValidated);
}
