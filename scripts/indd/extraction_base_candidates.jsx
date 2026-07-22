/*
 * Base extraction candidate planner for extract_indd.jsx.
 *
 * This module owns the first pass over sourceItems and emits the initial legacy
 * candidates. It must only describe source ownership candidates; later stages
 * decide canonical ObjectPlan ownership and executors only execute that plan.
 */

function _appendBaseExtractionCandidates(ctx, sourceItems, sourceIndex, sourceClusterIndex, candidates, candidateSeen) {
    var basePerfStartedAt = (new Date()).getTime();
    var basePerfDetailedTiming = ctx && ctx.enableBaseCandidateDetailedTiming === true;
    var basePerfStats = {
        stage: "03d05_plan_baseCandidates",
        sourceItemCount: sourceItems ? sourceItems.length : 0,
        predeclareItemCount: 0,
        predeclarePageCount: 0,
        mainItemCount: 0,
        mainPageCount: 0,
        candidateCountAtStart: candidates ? candidates.length : 0,
        candidateCountAtEnd: 0,
        helperCalls: {},
        helperMs: {},
        mainBranchMs: {},
        mainBranchCounts: {},
        cacheHits: {},
        cacheMisses: {},
        emittedByRole: {},
        duplicateSkippedByRole: {},
        consideredByRole: {},
        fallbackMs: {},
        mainLoopMs: 0,
        mainPageLoopMs: 0,
        broaderDecorationScannedSetCount: 0,
        broaderDecorationIndexedSetCount: 0,
        lastLabel: "start",
        lastItemIndex: -1,
        elapsedMs: 0,
        suppressedPreview: []
    };

    function basePerfWallNow() {
        return (new Date()).getTime();
    }

    function basePerfNow() {
        return basePerfDetailedTiming ? basePerfWallNow() : 0;
    }

    function basePerfAdd(mapName, key, value) {
        var map = basePerfStats[mapName];
        if (!map) return;
        if (!map[key]) map[key] = 0;
        map[key] += value;
    }

    function basePerfCall(name, startedAt) {
        basePerfAdd("helperCalls", name, 1);
        if (basePerfDetailedTiming) {
            basePerfAdd("helperMs", name, Math.max(0, basePerfWallNow() - startedAt));
        }
    }

    function basePerfHotCall(name, startedAt) {
        basePerfAdd("helperCalls", name, 1);
        if (basePerfDetailedTiming && startedAt) {
            basePerfAdd("helperMs", name, Math.max(0, basePerfWallNow() - startedAt));
        }
    }

    function basePerfBranchStart(name) {
        basePerfAdd("mainBranchCounts", name, 1);
        return basePerfNow();
    }

    function basePerfBranchEnd(name, startedAt) {
        if (basePerfDetailedTiming && startedAt) {
            basePerfAdd("mainBranchMs", name, Math.max(0, basePerfWallNow() - startedAt));
        }
    }

    function basePerfCache(name, hit) {
        basePerfAdd(hit ? "cacheHits" : "cacheMisses", name, 1);
    }

    function basePerfRole(mapName, role, value) {
        basePerfAdd(mapName, role || "unknown", value || 1);
    }

    function pushBaseExtractionCandidate(passId, item, attrs, role) {
        role = role || (attrs && attrs.compositeRole) || passId || "unknown";
        basePerfRole("consideredByRole", role, 1);
        var before = candidates ? candidates.length : 0;
        var startedAt = basePerfNow();
        _pushExtractionCandidate(candidates, candidateSeen, passId, item, cloneBaseCandidateAttrs(attrs));
        basePerfHotCall("pushBaseExtractionCandidate", startedAt);
        var after = candidates ? candidates.length : 0;
        if (after > before) {
            basePerfRole("emittedByRole", role, after - before);
        } else {
            basePerfRole("duplicateSkippedByRole", role, 1);
        }
    }

    function cloneBaseCandidateAttrs(attrs) {
        if (!attrs) return attrs;
        var out = {};
        for (var key in attrs) {
            if (!attrs.hasOwnProperty(key)) continue;
            var value = attrs[key];
            if (value && typeof value !== "string"
                    && value.length !== undefined
                    && value.slice) {
                out[key] = value.slice(0);
            } else {
                out[key] = value;
            }
        }
        return out;
    }

    function basePerfMarker(name) {
        try { _marker(ctx.outputDir, name); } catch (eMarker) {}
    }

    function basePerfWrite(label, itemIndex) {
        try {
            basePerfStats.lastLabel = label;
            basePerfStats.lastItemIndex = itemIndex;
            basePerfStats.candidateCountAtEnd = candidates ? candidates.length : 0;
            basePerfStats.elapsedMs = Math.max(0, basePerfWallNow() - basePerfStartedAt);
            writeJson(ctx.outputDir + "/_base_candidate_perf.json", basePerfStats);
        } catch (eBasePerfWrite) {}
    }

    function baseSuppressedPreview(reason, itemInfo, extra) {
        try {
            if (!itemInfo) return;
            if (basePerfStats.suppressedPreview.length >= 200) return;
            var row = {
                reason: reason || "unknown",
                id: itemInfo.id,
                kind: itemInfo.kind,
                pageIndex: itemInfo.pageIndex,
                parentId: itemInfo.parentId,
                hasChildren: itemInfo.hasChildren === true,
                hasPlacedVisual: itemInfo.hasPlacedVisual === true,
                hasVisibleFill: itemInfo.hasVisibleFill === true,
                hasVisibleStroke: itemInfo.hasVisibleStroke === true
            };
            if (extra) {
                for (var key in extra) {
                    if (!extra.hasOwnProperty(key)) continue;
                    row[key] = extra[key];
                }
            }
            basePerfStats.suppressedPreview.push(row);
        } catch (eBaseSuppressedPreview) {}
    }

    basePerfMarker("03d05a_base_enter");

    var sourceHasStoryFlowAnchorById = {};

    function sourceInfoHasStoryFlowAnchorForBase(info) {
        if (!info) return false;
        var parentKind = String(info.parentKind || "");
        if (parentKind === "Cell" || parentKind === "Story") {
            return true;
        }
        return _isInlineFlowItemBySourceInfo(info);
    }

    function sourceHasStoryFlowAnchor(sourceId) {
        if (sourceId === null || sourceId === undefined) return false;
        var startedAt = basePerfNow();
        var sourceKey = String(sourceId);
        if (sourceHasStoryFlowAnchorById.hasOwnProperty(sourceKey)) {
            basePerfCache("sourceHasStoryFlowAnchor", true);
            basePerfHotCall("sourceHasStoryFlowAnchor", startedAt);
            return sourceHasStoryFlowAnchorById[sourceKey];
        }
        basePerfCache("sourceHasStoryFlowAnchor", false);
        var declaredInfo = sourceInfoForBase(sourceId);
        if (declaredInfo) {
            sourceHasStoryFlowAnchorById[sourceKey] = sourceInfoHasStoryFlowAnchorForBase(declaredInfo);
            basePerfHotCall("sourceHasStoryFlowAnchor", startedAt);
            return sourceHasStoryFlowAnchorById[sourceKey];
        }
        var current = sourceClusterIndex && sourceClusterIndex.sourceInfo
                ? sourceClusterIndex.sourceInfo(sourceId)
                : null;
        for (var depth = 0; depth < 64 && current; depth++) {
            var parentKind = String(current.parentKind || "");
            if (parentKind === "Character" || parentKind === "InsertionPoint") {
                sourceHasStoryFlowAnchorById[sourceKey] = _isInlineFlowItemBySourceInfo(current);
                basePerfHotCall("sourceHasStoryFlowAnchor", startedAt);
                return sourceHasStoryFlowAnchorById[sourceKey];
            }
            if (parentKind === "Cell" || parentKind === "Story") {
                sourceHasStoryFlowAnchorById[sourceKey] = true;
                basePerfHotCall("sourceHasStoryFlowAnchor", startedAt);
                return true;
            }
            var parentId = current.parentId;
            if (parentId === null || parentId === undefined) {
                sourceHasStoryFlowAnchorById[sourceKey] = false;
                basePerfHotCall("sourceHasStoryFlowAnchor", startedAt);
                return false;
            }
            current = sourceClusterIndex.sourceInfo(parentId);
        }
        sourceHasStoryFlowAnchorById[sourceKey] = false;
        basePerfHotCall("sourceHasStoryFlowAnchor", startedAt);
        return false;
    }

    function sourceKindCanEmitBaseCandidate(kind) {
        kind = String(kind || "");
        return kind === "TextFrame" || kind === "Group" || kind === "Rectangle"
                || kind === "Oval" || kind === "Polygon" || kind === "GraphicLine";
    }

    function sourceItemHasChildren(sourceId) {
        if (sourceId === null || sourceId === undefined) return false;
        var startedAt = basePerfNow();
        var info = sourceInfoForBase(sourceId);
        basePerfHotCall("sourceItemHasChildren", startedAt);
        return !!(info && info.hasChildren);
    }

    var candidatePageIndexesForBaseById = {};
    var pageLocalSourceObjectIdsForBaseByKey = {};
    var hasPlacedVisualInSubtreeForBaseById = {};
    var hasCandidateVectorPaintForBaseById = {};
    var clipCarryingParentIdForBaseById = {};
    var pageBoundsForBaseByPage = {};

    function pageIndexInBaseRange(pageIndex) {
        pageIndex = Number(pageIndex);
        if (!(pageIndex >= 0)) return false;
        return pageIndex >= Number(ctx.startPage || 1) - 1
                && pageIndex <= Number(ctx.endPage || ctx.startPage || 1) - 1;
    }

    function pageBoundsForBase(pageIndex) {
        var key = String(pageIndex);
        if (pageBoundsForBaseByPage.hasOwnProperty(key)) {
            return pageBoundsForBaseByPage[key];
        }
        var bounds = null;
        try { bounds = sourceIndex.pageBounds(Number(pageIndex)); } catch (ePageBoundsForBase) { bounds = null; }
        pageBoundsForBaseByPage[key] = bounds;
        return bounds;
    }

    function sourceBoundsContainedInPageForBase(sourceBounds, pageBounds) {
        if (!sourceBounds || !pageBounds || sourceBounds.length < 4 || pageBounds.length < 4) return false;
        var eps = 0.001;
        return Number(sourceBounds[0]) >= Number(pageBounds[0]) - eps
                && Number(sourceBounds[1]) >= Number(pageBounds[1]) - eps
                && Number(sourceBounds[2]) <= Number(pageBounds[2]) + eps
                && Number(sourceBounds[3]) <= Number(pageBounds[3]) + eps;
    }

    function sourceBoundsHasPageAreaForBase(sourceBounds, pageBounds) {
        if (!sourceBounds || !pageBounds || sourceBounds.length < 4 || pageBounds.length < 4) return true;
        var eps = 0.001;
        var top = Math.max(Number(sourceBounds[0]), Number(pageBounds[0]));
        var left = Math.max(Number(sourceBounds[1]), Number(pageBounds[1]));
        var bottom = Math.min(Number(sourceBounds[2]), Number(pageBounds[2]));
        var right = Math.min(Number(sourceBounds[3]), Number(pageBounds[3]));
        return bottom - top > eps && right - left > eps;
    }

    function filterCandidatePageIndexesWithAreaForBase(sourceInfo, indexes) {
        if (!sourceInfo || !sourceInfo.bounds || sourceInfo.bounds.length < 4) {
            return indexes ? indexes.slice(0) : [];
        }
        var out = [];
        for (var i = 0; indexes && i < indexes.length; i++) {
            var pageIndex = indexes[i];
            if (sourceBoundsHasPageAreaForBase(sourceInfo.bounds, pageBoundsForBase(pageIndex))) {
                out.push(pageIndex);
            }
        }
        return out;
    }

    function candidatePageIndexesForBase(sourceId) {
        var startedAt = basePerfNow();
        var key = String(sourceId);
        if (candidatePageIndexesForBaseById.hasOwnProperty(key)) {
            basePerfCache("candidatePageIndexesForBase", true);
            basePerfHotCall("candidatePageIndexesForBase", startedAt);
            return candidatePageIndexesForBaseById[key].slice(0);
        }
        basePerfCache("candidatePageIndexesForBase", false);
        var sourceInfo = baseCandidateSourceInfoById[String(sourceId)];
        var sourcePageIndex = sourceInfo ? Number(sourceInfo.pageIndex) : -1;
        if (pageIndexInBaseRange(sourcePageIndex)) {
            if (!sourceInfo.bounds || sourceInfo.bounds.length < 4
                    || sourceBoundsContainedInPageForBase(sourceInfo.bounds, pageBoundsForBase(sourcePageIndex))) {
                candidatePageIndexesForBaseById[key] = [sourcePageIndex];
                basePerfCache("candidatePageIndexesForBase.fastPageLocal", true);
                basePerfHotCall("candidatePageIndexesForBase", startedAt);
                return [sourcePageIndex];
            }
        }
        basePerfCache("candidatePageIndexesForBase.fastPageLocal", false);
        var indexes = filterCandidatePageIndexesWithAreaForBase(
                sourceInfo, sourceIndex.candidatePageIndexes(sourceId) || []);
        candidatePageIndexesForBaseById[key] = indexes.slice(0);
        basePerfHotCall("candidatePageIndexesForBase", startedAt);
        return indexes;
    }

    function pageLocalSourceObjectIdsForBase(sourceId, pageIndex) {
        var key = String(sourceId) + "|" + String(pageIndex);
        if (pageLocalSourceObjectIdsForBaseByKey.hasOwnProperty(key)) {
            basePerfCache("pageLocalSourceObjectIdsForBase", true);
            return pageLocalSourceObjectIdsForBaseByKey[key];
        }
        basePerfCache("pageLocalSourceObjectIdsForBase", false);
        var startedAt = basePerfNow();
        var ids = null;
        try {
            ids = sourceIndex.pageLocalSourceObjectIds(sourceId, pageIndex);
        } catch (ePageLocalSourceObjectIdsForBase) {
            ids = null;
        }
        basePerfHotCall("pageLocalSourceObjectIdsForBase", startedAt);
        ids = ids || [sourceId];
        pageLocalSourceObjectIdsForBaseByKey[key] = ids;
        return ids;
    }

    function hasPlacedVisualInSubtreeForBase(sourceId) {
        var key = String(sourceId);
        if (hasPlacedVisualInSubtreeForBaseById.hasOwnProperty(key)) {
            basePerfCache("hasPlacedVisualInSubtreeForBase", true);
            return hasPlacedVisualInSubtreeForBaseById[key];
        }
        basePerfCache("hasPlacedVisualInSubtreeForBase", false);
        var startedAt = basePerfNow();
        var result = sourceIndex.hasPlacedVisualInSubtree(sourceId);
        basePerfHotCall("hasPlacedVisualInSubtreeForBase", startedAt);
        hasPlacedVisualInSubtreeForBaseById[key] = result;
        return result;
    }

    function hasCandidateVectorPaintForBase(sourceId) {
        var key = String(sourceId);
        if (hasCandidateVectorPaintForBaseById.hasOwnProperty(key)) {
            basePerfCache("hasCandidateVectorPaintForBase", true);
            return hasCandidateVectorPaintForBaseById[key];
        }
        basePerfCache("hasCandidateVectorPaintForBase", false);
        var startedAt = basePerfNow();
        var result = sourceIndex.hasCandidateVectorPaint(sourceId) === true;
        basePerfHotCall("hasCandidateVectorPaintForBase", startedAt);
        hasCandidateVectorPaintForBaseById[key] = result;
        return result;
    }

    function clipCarryingParentIdForBase(sourceId) {
        var key = String(sourceId);
        if (clipCarryingParentIdForBaseById.hasOwnProperty(key)) {
            basePerfCache("clipCarryingParentIdForBase", true);
            return clipCarryingParentIdForBaseById[key];
        }
        basePerfCache("clipCarryingParentIdForBase", false);
        var startedAt = basePerfNow();
        var result = null;
        try {
            result = sourceIndex.clipCarryingParentIdOfSource(sourceId);
        } catch (eClipCarryingParentIdForBase) {
            result = null;
        }
        basePerfHotCall("clipCarryingParentIdForBase", startedAt);
        clipCarryingParentIdForBaseById[key] = result;
        return result;
    }

    function domItemForBase(sourceId) {
        var startedAt = basePerfNow();
        var item = null;
        try { item = sourceIndex.domItem(sourceId); } catch (eDomItemForBase) { item = null; }
        basePerfHotCall("domItemForBase", startedAt);
        return item;
    }

    var sourceIsInPagePlaneForBaseByKey = {};

    function sourceIsInPagePlaneForBase(sourceId, pageIndex) {
        if (!sourceIndex.sourceIsInPagePlane) return true;
        var cacheKey = String(sourceId) + "|" + String(pageIndex);
        if (sourceIsInPagePlaneForBaseByKey.hasOwnProperty(cacheKey)) {
            basePerfCache("sourceIsInPagePlaneForBase", true);
            return sourceIsInPagePlaneForBaseByKey[cacheKey];
        }
        basePerfCache("sourceIsInPagePlaneForBase", false);
        var startedAt = basePerfNow();
        var result = false;
        try { result = sourceIndex.sourceIsInPagePlane(sourceId, pageIndex) === true; } catch (eSourceIsInPagePlane) {}
        sourceIsInPagePlaneForBaseByKey[cacheKey] = result;
        basePerfHotCall("sourceIsInPagePlaneForBase", startedAt);
        return result;
    }

    function shellExportSourceIdsForSourceSet(rootSourceId, sourceIds) {
        var ids = [];
        var seen = {};
        for (var si = 0; sourceIds && si < sourceIds.length; si++) {
            var sourceId = sourceIds[si];
            if (sourceIsInsidePlacedVisualBranch(sourceId, rootSourceId)) {
                continue;
            }
            _pushUniqueId(ids, seen, sourceId);
        }
        return _sortedNumericIds(ids);
    }

    function sourceIsInsidePlacedVisualBranch(sourceId, rootSourceId) {
        if (sourceId === null || sourceId === undefined
                || rootSourceId === null || rootSourceId === undefined) {
            return false;
        }
        var current = sourceInfoForBase(sourceId);
        for (var depth = 0; depth < 64 && current; depth++) {
            if (String(current.id) === String(rootSourceId)) return false;
            if (hasPlacedVisualInSubtreeForBase(current.id)) return true;
            if (current.parentId === null || current.parentId === undefined) return false;
            current = sourceInfoForBase(current.parentId);
        }
        return false;
    }

    function candidateAttrsForInfo(itemInfo, attrs) {
        attrs = attrs || {};
        if (attrs.sourceObjectIds === undefined && itemInfo && itemInfo.id !== null && itemInfo.id !== undefined) {
            attrs.sourceObjectIds = [itemInfo.id];
        }
        if (attrs.kind === undefined) attrs.kind = itemInfo.kind;
        if (attrs.bounds === undefined) attrs.bounds = itemInfo.bounds;
        if (attrs.parentId === undefined) attrs.parentId = itemInfo.parentId;
        if (attrs.parentKind === undefined) attrs.parentKind = itemInfo.parentKind;
        if (attrs.anchoredPosition === undefined) attrs.anchoredPosition = itemInfo.anchoredPosition;
        if (attrs.storyAnchorPlacement === undefined) attrs.storyAnchorPlacement = itemInfo.storyAnchorPlacement;
        if (attrs.zOrder === undefined) attrs.zOrder = itemInfo.zOrder;
        return attrs;
    }

    function shouldEmitDecorationSourceCandidate(itemInfo) {
        if (!itemInfo) return false;
        var kind = itemInfo.kind || itemInfo.type || itemInfo.itemType;
        if (kind === "Group") return itemInfo.hasChildren === true;
        if (kind === "Rectangle" || kind === "Oval"
                || kind === "Polygon" || kind === "GraphicLine") {
            if (itemInfo.hasChildren === true) return true;
            return false;
        }
        return false;
    }

    function textFrameMayHaveStyleShellForBase(itemInfo) {
        var kindName = String(itemInfo && (itemInfo.kind || itemInfo.type || itemInfo.itemType) || "");
        if (!itemInfo || kindName !== "TextFrame") return false;
        return itemInfo.hasVisibleFill === true
                || itemInfo.hasVisibleStroke === true
                || _sourceHasTextFrameShellStyleMetadataInIndex(
                        itemInfo.id, sourceIndex.sourceInfoById);
    }

    function sourceInfoIsInlineFlowForBase(itemInfo) {
        if (!itemInfo) return false;
        var placement = String(itemInfo.storyAnchorPlacement || "").toUpperCase();
        var anchoredPosition = String(itemInfo.anchoredPosition || "").toUpperCase();
        if (placement === "FLOATING_ANCHORED" || anchoredPosition === "ANCHORED") return false;
        if (itemInfo.storyTextInlineSlot === true || itemInfo.isInline === true) return true;
        return placement === "INLINE"
                || anchoredPosition === "INLINE_POSITION"
                || anchoredPosition === "INLINEPOSITION";
    }

    function sourceMayBeBackgroundVectorCandidateFast(itemInfo, pageIndex) {
        if (!itemInfo) return false;
        var kindName = String(itemInfo.kind || "");
        if (kindName !== "Rectangle" && kindName !== "Oval" && kindName !== "Polygon") return false;
        if (itemInfo.visible === false || itemInfo.hiddenLayer === true || itemInfo.nonprinting === true) return false;
        if (!itemInfo.bounds || itemInfo.bounds.length < 4) return false;
        if (itemInfo.hasChildren === true || itemInfo.hasPlacedVisual === true) return false;
        if (itemInfo.hasVisibleFill !== true || itemInfo.hasVisibleStroke === true) return false;
        var pb = pageBoundsForBase(Number(pageIndex));
        if (!pb || pb.length < 4 || !sourceBoundsIntersects(itemInfo.bounds, pb)) return false;
        var pageWidth = Math.max(0, Number(pb[3]) - Number(pb[1]));
        var pageHeight = Math.max(0, Number(pb[2]) - Number(pb[0]));
        if (pageWidth <= 0 || pageHeight <= 0) return false;
        var width = Math.max(0, Number(itemInfo.bounds[3]) - Number(itemInfo.bounds[1]));
        var height = Math.max(0, Number(itemInfo.bounds[2]) - Number(itemInfo.bounds[0]));
        var touchesLeft = Number(itemInfo.bounds[1]) <= Number(pb[1]) + 1.0;
        var touchesRight = Number(itemInfo.bounds[3]) >= Number(pb[3]) - 1.0;
        var touchesTop = Number(itemInfo.bounds[0]) <= Number(pb[0]) + 1.0;
        var touchesBottom = Number(itemInfo.bounds[2]) >= Number(pb[2]) - 1.0;
        var spansWidth = width >= pageWidth * 0.85 && touchesLeft && touchesRight;
        var spansHeight = height >= pageHeight * 0.85 && touchesTop && touchesBottom;
        return spansWidth || spansHeight;
    }

    function sourceMayNeedDecorationCompositeBranch(itemInfo) {
        if (!itemInfo) return false;
        var kindName = String(itemInfo.kind || "");
        if (kindName === "Group") return true;
        if (kindName !== "Rectangle" && kindName !== "Oval"
                && kindName !== "Polygon" && kindName !== "GraphicLine") {
            return false;
        }
        return itemInfo.hasChildren === true
                || clipCarryingParentIdForBase(itemInfo.id) !== null;
    }

    function sourceMayEmitClipParentShellCandidate(itemInfo, pageIndex) {
        if (!itemInfo) return false;
        var kindName = String(itemInfo.kind || "");
        if (!sourceKindCanCarryClipForBase(kindName)) return false;
        if (itemInfo.hasChildren !== true) return false;
        if (sourceIndex.hasPlacedVisual(itemInfo.id) === true) return false;
        if (sourceHasClipParentShellOwner(itemInfo.id, pageIndex)) return false;
        if (!shouldEmitDecorationSourceCandidate(itemInfo)) return false;

        var sourceIds = pageLocalSourceObjectIdsForBase(itemInfo.id, pageIndex);
        if (!sourceIds || sourceIds.length === 0) return false;
        var exportIds = shellExportSourceIdsForSourceSet(itemInfo.id, sourceIds);
        if (!exportIds || exportIds.length === 0) return false;
        return sourceSetHasExecutableShellMaterial(exportIds);
    }

    function shouldRasterizeLeafVectorShell(itemInfo) {
        if (!itemInfo) return false;
        var kind = String(itemInfo.kind || "");
        if (kind !== "Polygon" && !isUnsafeNativeGraphicLineShell(itemInfo)) return false;
        if (itemInfo.hasChildren === true) return false;
        if (itemInfo.hasPlacedVisual === true) return false;
        if (hasPlacedVisualInSubtreeForBase(itemInfo.id)) return false;
        return hasCandidateVectorPaintForBase(itemInfo.id) === true;
    }

    function sourceLooksLikeVisibleVectorMaterial(itemInfo) {
        if (!itemInfo) return false;
        if (itemInfo.visible === false || itemInfo.hiddenLayer === true || itemInfo.nonprinting === true) return false;
        var kind = String(itemInfo.kind || "");
        if (kind !== "Group" && kind !== "Rectangle" && kind !== "Oval"
                && kind !== "Polygon" && kind !== "GraphicLine") {
            return false;
        }
        if (hasPlacedVisualInSubtreeForBase(itemInfo.id)) return false;
        if (kind === "Group") {
            return itemInfo.hasChildren === true
                    && (itemInfo.hasVisibleFill === true || itemInfo.hasVisibleStroke === true);
        }
        return itemInfo.hasVisibleFill === true
                || itemInfo.hasVisibleStroke === true
                || hasCandidateVectorPaintForBase(itemInfo.id) === true;
    }

    function markCandidateSourceClaims(candidate, claimed) {
        if (!candidate || !claimed) return;
        function mark(ids) {
            for (var i = 0; ids && i < ids.length; i++) {
                if (ids[i] === null || ids[i] === undefined) continue;
                claimed[String(ids[i])] = true;
            }
        }
        mark(candidate.sourceObjectIds);
        mark(candidate.visualSourceObjectIds);
        mark(candidate.exportSourceObjectIds);
    }

    function appendUnclaimedVisibleVectorSourceCandidates() {
        var claimed = {};
        for (var ci = 0; candidates && ci < candidates.length; ci++) {
            markCandidateSourceClaims(candidates[ci], claimed);
        }
        var appended = 0;
        for (var si = 0; sourceItems && si < sourceItems.length; si++) {
            var itemInfo = sourceItems[si];
            if (!itemInfo || itemInfo.id === null || itemInfo.id === undefined) continue;
            if (claimed[String(itemInfo.id)] === true) continue;
            if (!sourceLooksLikeVisibleVectorMaterial(itemInfo)) continue;
            if (sourceHasStoryFlowAnchor(itemInfo.id)) continue;
            if (sourceHasEditableTextDescendantForBase(itemInfo.id, itemInfo.pageIndex)) continue;

            var sourceIds = null;
            sourceIds = pageLocalSourceObjectIdsForBase(itemInfo.id, itemInfo.pageIndex);
            sourceIds = _sortedNumericIds(sourceIds);
            if (!sourceSetHasExecutableShellMaterial(sourceIds)) continue;

            var exportIds = shellExportSourceIdsForSourceSet(itemInfo.id, sourceIds);
            if (!exportIds || exportIds.length === 0) exportIds = sourceIds;
            if (!sourceSetHasExecutableShellMaterial(exportIds)) continue;

            var item = null;
            item = domItemForBase(itemInfo.id);
            pushBaseExtractionCandidate("pass.decoration_groups", item, candidateAttrsForInfo(itemInfo, {
                sourceObjectIds: sourceIds,
                exportSourceObjectIds: exportIds,
                exportTargetObjectId: itemInfo.id,
                visualSourceObjectIds: exportIds,
                pageIndex: itemInfo.pageIndex,
                unit: "GROUP_OR_ITEM",
                mode: "TEXTLESS_CANDIDATE",
                candidatePurpose: "SHELL_CANDIDATE",
                compositeRole: sourceIds.length > 1
                        ? "unclaimed_visible_vector_source_set"
                        : "unclaimed_visible_vector_source",
                slotRole: "shell_slot_only",
                renderMode: "SLOT_ONLY",
                hiddenVisualSourceObjectIds: [],
                containsEditableText: false,
                textOwner: "none",
                required: false
            }), "unclaimed_visible_vector_fallback");
            recordDecorationSourceSet(itemInfo.pageIndex, sourceIds);
            for (var mi = 0; mi < sourceIds.length; mi++) claimed[String(sourceIds[mi])] = true;
            for (var ei = 0; ei < exportIds.length; ei++) claimed[String(exportIds[ei])] = true;
            appended++;
        }
        basePerfStats.unclaimedVisibleVectorFallbackCount = appended;
    }

    function isUnsafeNativeGraphicLineShell(itemInfo) {
        if (!itemInfo || String(itemInfo.kind || "") !== "GraphicLine") return false;
        var strokeName = String(itemInfo.strokeColorName || itemInfo.strokeColor || "");
        var strokeWeight = Number(itemInfo.strokeWeight || 0);
        if (!(strokeWeight > 0)) return false;
        if (!isPaperLikeStrokeColorName(strokeName)) return false;
        var b = itemInfo.bounds || [];
        if (!b || b.length < 4) return true;
        var w = Math.abs(Number(b[3]) - Number(b[1]));
        var h = Math.abs(Number(b[2]) - Number(b[0]));
        var longAxis = Math.max(w, h);
        if (!(longAxis > 0)) return true;
        // HWPX native rectangles cannot faithfully represent very thick Paper
        // GraphicLine masks.  InDesign PNG export preserves the actual stroke
        // geometry/opacity; Java native shape conversion turns it into an
        // oversized white box.
        return strokeWeight > longAxis * 0.25;
    }

    function isPaperLikeStrokeColorName(name) {
        var n = String(name || "").toLowerCase();
        return n === "paper" || n === "white" || n === "c=0 m=0 y=0 k=0"
                || n === "[paper]" || n === "[white]";
    }

    var baseCandidateSourceInfoById = {};
    var baseCandidateChildIdsByParentId = {};
    var parentHasEditableTextFrameChildByPage = {};
    var parentHasEmptyCarrierTextFrameChildByPage = {};
    var predeclareCandidateInfos = [];
    for (var sourceInfoIndex = 0; sourceInfoIndex < sourceItems.length; sourceInfoIndex++) {
        var sourceInfoEntry = sourceItems[sourceInfoIndex];
        if (!sourceInfoEntry || sourceInfoEntry.id === null || sourceInfoEntry.id === undefined) continue;
        baseCandidateSourceInfoById[String(sourceInfoEntry.id)] = sourceInfoEntry;
        if (sourceInfoEntry.parentId !== null && sourceInfoEntry.parentId !== undefined) {
            var parentKeyForBaseCandidate = String(sourceInfoEntry.parentId);
            if (!baseCandidateChildIdsByParentId[parentKeyForBaseCandidate]) {
                baseCandidateChildIdsByParentId[parentKeyForBaseCandidate] = [];
            }
            baseCandidateChildIdsByParentId[parentKeyForBaseCandidate].push(sourceInfoEntry.id);
        }
    }

    function sourceInfoForBase(sourceId) {
        if (sourceId === null || sourceId === undefined) return null;
        var info = baseCandidateSourceInfoById[String(sourceId)];
        if (info) return info;
        try { return sourceIndex.sourceInfo(sourceId); } catch (eSourceInfoForBase) {}
        return null;
    }
    for (var editableSiblingIndex = 0; editableSiblingIndex < sourceItems.length; editableSiblingIndex++) {
        var editableSiblingInfo = sourceItems[editableSiblingIndex];
        if (editableSiblingInfo && editableSiblingInfo.hiddenLayer !== true
                && shouldEmitDecorationSourceCandidate(editableSiblingInfo)
                && !hasPlacedVisualInSubtreeForBase(editableSiblingInfo.id)) {
            predeclareCandidateInfos.push(editableSiblingInfo);
        }
        if (!editableSiblingInfo || editableSiblingInfo.parentId === null
                || editableSiblingInfo.parentId === undefined) continue;
        if (String(editableSiblingInfo.parentKind || "") !== "Group") continue;
        if (String(editableSiblingInfo.kind || "") !== "TextFrame") continue;
        if (editableSiblingInfo.hasText !== true && Number(editableSiblingInfo.textLength || 0) <= 0) {
            parentHasEmptyCarrierTextFrameChildByPage[
                    String(editableSiblingInfo.parentId) + "|" + String(editableSiblingInfo.pageIndex)] = true;
        }
        if (editableSiblingInfo.textFrameClass !== "editable") continue;
        if (editableSiblingInfo.hasText !== true) continue;
        parentHasEditableTextFrameChildByPage[
                String(editableSiblingInfo.parentId) + "|" + String(editableSiblingInfo.pageIndex)] = true;
    }

    var executableShellMaterialBySourceSetKey = {};
    var executableShellMaterialBySourceId = {};

    function sourceHasExecutableShellMaterialForBase(sourceId) {
        var key = String(sourceId);
        if (executableShellMaterialBySourceId.hasOwnProperty(key)) {
            return executableShellMaterialBySourceId[key];
        }
        var result = _sourceHasExecutableShellMaterialMetadataInIndex(
                sourceId, baseCandidateSourceInfoById, baseCandidateChildIdsByParentId) === true;
        executableShellMaterialBySourceId[key] = result;
        return result;
    }

    function sourceSetHasExecutableShellMaterial(sourceIds) {
        var cacheKey = sourceSetKeyInGivenOrder(sourceIds || []);
        if (cacheKey && executableShellMaterialBySourceSetKey.hasOwnProperty(cacheKey)) {
            basePerfCache("sourceSetHasExecutableShellMaterial", true);
            return executableShellMaterialBySourceSetKey[cacheKey];
        }
        basePerfCache("sourceSetHasExecutableShellMaterial", false);
        var startedAt = basePerfNow();
        var result = false;
        for (var i = 0; sourceIds && i < sourceIds.length; i++) {
            if (sourceHasExecutableShellMaterialForBase(sourceIds[i])) {
                result = true;
                break;
            }
        }
        if (cacheKey) executableShellMaterialBySourceSetKey[cacheKey] = result;
        basePerfCall("sourceSetHasExecutableShellMaterial", startedAt);
        return result;
    }

    var sourceSetContainsAllByKey = {};

    function sourceSetKeyInGivenOrder(ids) {
        if (!ids || ids.length === 0) return "";
        if (ids._baseSourceSetKey !== undefined) return ids._baseSourceSetKey;
        var out = [];
        for (var i = 0; i < ids.length; i++) out.push(String(Number(ids[i])));
        var key = out.join(",");
        try {
            Object.defineProperty(ids, "_baseSourceSetKey", {
                value: key,
                enumerable: false
            });
        } catch (eBaseSourceSetKey) {
            ids._baseSourceSetKey = key;
        }
        return key;
    }

    function cachedSourceSetContainsAll(ownerIds, memberIds) {
        var key = sourceSetKeyInGivenOrder(ownerIds || []) + ">" + sourceSetKeyInGivenOrder(memberIds || []);
        if (sourceSetContainsAllByKey.hasOwnProperty(key)) return sourceSetContainsAllByKey[key];
        var result = _sourceSetContainsAll(ownerIds, memberIds);
        sourceSetContainsAllByKey[key] = result;
        return result;
    }

    var decorationSourceSetKeysByPage = {};
    var decorationSourceSetsByPage = {};
    var predeclaredDecorationSourceSetsByPage = {};
    var predeclaredDecorationSourceSetKeysByPage = {};
    var broaderDecorationSourceSetsByPageMember = {};
    var broaderDecorationSourceSetByKey = {};
    var decorationSourceSetVersionByPage = {};

    function bumpDecorationSourceSetVersion(pageIndex) {
        var pageKey = String(pageIndex);
        decorationSourceSetVersionByPage[pageKey] = Number(decorationSourceSetVersionByPage[pageKey] || 0) + 1;
    }

    function indexBroaderDecorationSourceSet(pageIndex, sourceIds) {
        if (!sourceIds || sourceIds.length === 0) return;
        var pageKey = String(pageIndex);
        for (var i = 0; i < sourceIds.length; i++) {
            var memberKey = pageKey + "|" + String(sourceIds[i]);
            if (!broaderDecorationSourceSetsByPageMember[memberKey]) {
                broaderDecorationSourceSetsByPageMember[memberKey] = [];
            }
            broaderDecorationSourceSetsByPageMember[memberKey].push(sourceIds);
        }
        basePerfStats.broaderDecorationIndexedSetCount++;
    }

    function recordDecorationSourceSet(pageIndex, sourceIds) {
        var key = _sourceSetKey(sourceIds || []);
        if (!key) return;
        var pageKey = String(pageIndex);
        if (!decorationSourceSetKeysByPage[pageKey]) decorationSourceSetKeysByPage[pageKey] = {};
        if (!decorationSourceSetsByPage[pageKey]) decorationSourceSetsByPage[pageKey] = [];
        if (decorationSourceSetKeysByPage[pageKey][key] === true) return;
        var sortedSourceIds = _sortedNumericIds(sourceIds || []);
        decorationSourceSetKeysByPage[pageKey][key] = true;
        decorationSourceSetsByPage[pageKey].push(sortedSourceIds);
        indexBroaderDecorationSourceSet(pageIndex, sortedSourceIds);
        bumpDecorationSourceSetVersion(pageIndex);
    }

    function hasDecorationSourceSet(pageIndex, sourceIds) {
        var key = _sourceSetKey(sourceIds || []);
        if (!key) return false;
        var pageKeys = decorationSourceSetKeysByPage[String(pageIndex)];
        return !!(pageKeys && pageKeys[key] === true);
    }

    function hasBroaderDecorationSourceSet(pageIndex, sourceIds) {
        if (!sourceIds || sourceIds.length === 0) return false;
        var startedAt = basePerfNow();
        var pageKey = String(pageIndex);
        var lookupSourceIds = sourceIds;
        var sourceKey = sourceSetKeyInGivenOrder(lookupSourceIds);
        var version = Number(decorationSourceSetVersionByPage[pageKey] || 0);
        var cacheKey = pageKey + "|" + version + "|" + sourceKey;
        if (broaderDecorationSourceSetByKey.hasOwnProperty(cacheKey)) {
            basePerfCache("hasBroaderDecorationSourceSet", true);
            basePerfCall("hasBroaderDecorationSourceSet", startedAt);
            return broaderDecorationSourceSetByKey[cacheKey];
        }
        basePerfCache("hasBroaderDecorationSourceSet", false);
        var memberKey = pageKey + "|" + String(Number(lookupSourceIds[0]));
        var pageSets = broaderDecorationSourceSetsByPageMember[memberKey] || [];
        basePerfStats.broaderDecorationScannedSetCount += pageSets.length;
        for (var i = 0; i < pageSets.length; i++) {
            var ownerIds = pageSets[i];
            if (!ownerIds || ownerIds.length <= lookupSourceIds.length) continue;
            if (sourceSetKeyInGivenOrder(ownerIds) === sourceKey) continue;
            if (cachedSourceSetContainsAll(ownerIds, lookupSourceIds)) {
                broaderDecorationSourceSetByKey[cacheKey] = true;
                basePerfCall("hasBroaderDecorationSourceSet", startedAt);
                return true;
            }
        }
        broaderDecorationSourceSetByKey[cacheKey] = false;
        basePerfCall("hasBroaderDecorationSourceSet", startedAt);
        return false;
    }

    function sourceCoveredByBroaderDecorationSourceSet(pageIndex, sourceId) {
        if (sourceId === null || sourceId === undefined) return false;
        var pageKey = String(pageIndex);
        var memberKey = pageKey + "|" + String(sourceId);
        var pageSets = broaderDecorationSourceSetsByPageMember[memberKey] || [];
        for (var i = 0; i < pageSets.length; i++) {
            var ownerIds = pageSets[i];
            if (ownerIds && ownerIds.length > 1) return true;
        }
        return false;
    }

    function predeclareDecorationSourceSet(pageIndex, sourceIds) {
        if (!sourceIds || sourceIds.length <= 1) return;
        var key = _sourceSetKey(sourceIds || []);
        if (!key) return;
        var pageKey = String(pageIndex);
        if (!predeclaredDecorationSourceSetsByPage[pageKey]) {
            predeclaredDecorationSourceSetsByPage[pageKey] = [];
        }
        if (!predeclaredDecorationSourceSetKeysByPage[pageKey]) {
            predeclaredDecorationSourceSetKeysByPage[pageKey] = {};
        }
        if (predeclaredDecorationSourceSetKeysByPage[pageKey][key] === true) return;
        var sortedSourceIds = _sortedNumericIds(sourceIds);
        predeclaredDecorationSourceSetKeysByPage[pageKey][key] = true;
        predeclaredDecorationSourceSetsByPage[pageKey].push(sortedSourceIds);
        indexBroaderDecorationSourceSet(pageIndex, sortedSourceIds);
        bumpDecorationSourceSetVersion(pageIndex);
    }

    function sourceIsEmptyCarrierTextFrameForPage(sourceInfo, pageIndex) {
        if (!sourceInfo) return false;
        if (sourceInfo.pageIndex !== pageIndex) return false;
        if (String(sourceInfo.kind || "") !== "TextFrame") return false;
        if (sourceInfo.hasText === true) return false;
        if (Number(sourceInfo.textLength || 0) > 0) return false;
        return String(sourceInfo.parentKind || "") === "Group";
    }

    function sourceOrAncestorHasDirectEmptyCarrierTextFrameSiblingForPage(sourceId, pageIndex) {
        var currentId = sourceId;
        for (var depth = 0; depth < 16 && currentId !== null && currentId !== undefined; depth++) {
            var src = baseCandidateSourceInfoById[String(currentId)];
            if (!src || src.parentId === null || src.parentId === undefined) return false;
            if (!sourceIsInPagePlaneForBase(src.id, pageIndex)) return false;
            if (parentHasEmptyCarrierTextFrameChildByPage[
                    String(src.parentId) + "|" + String(pageIndex)] === true) {
                var siblings = baseCandidateChildIdsByParentId[String(src.parentId)] || [];
                for (var si = 0; si < siblings.length; si++) {
                    if (String(siblings[si]) === String(currentId)) continue;
                    var sibling = baseCandidateSourceInfoById[String(siblings[si])];
                    if (sibling && !sourceIsInPagePlaneForBase(sibling.id, pageIndex)) continue;
                    if (sourceIsEmptyCarrierTextFrameForPage(sibling, pageIndex)) return true;
                }
            }
            currentId = src.parentId;
        }
        return false;
    }

    var clippedPlacedCarrierSiblingShellByKey = {};
    var mayBeClippedPlacedCarrierSiblingShellByKey = {};

    function sourceMayBeClippedPlacedCarrierSiblingShell(itemInfo, pageIndex) {
        if (!itemInfo || itemInfo.id === null || itemInfo.id === undefined) return false;
        var cacheKey = String(itemInfo.id) + "|" + String(pageIndex);
        if (mayBeClippedPlacedCarrierSiblingShellByKey.hasOwnProperty(cacheKey)) {
            return mayBeClippedPlacedCarrierSiblingShellByKey[cacheKey];
        }
        var kindName = String(itemInfo.kind || "");
        var result = kindName === "Rectangle" || kindName === "Oval" || kindName === "Polygon";
        if (result && itemInfo.hasPlacedVisual === true) result = false;
        if (result && itemInfo.hasChildren !== true) result = false;
        if (result && hasPlacedVisualInSubtreeForBase(itemInfo.id) !== true) result = false;
        if (result) {
            result = sourceOrAncestorHasDirectEmptyCarrierTextFrameSiblingForPage(itemInfo.id, pageIndex);
        }
        mayBeClippedPlacedCarrierSiblingShellByKey[cacheKey] = result;
        return result;
    }

    function sourceIsClippedPlacedCarrierSiblingShell(itemInfo, pageIndex) {
        if (!itemInfo || itemInfo.id === null || itemInfo.id === undefined) return false;
        var cacheKey = String(itemInfo.id) + "|" + String(pageIndex);
        if (clippedPlacedCarrierSiblingShellByKey.hasOwnProperty(cacheKey)) {
            basePerfCache("sourceIsClippedPlacedCarrierSiblingShell", true);
            return clippedPlacedCarrierSiblingShellByKey[cacheKey];
        }
        basePerfCache("sourceIsClippedPlacedCarrierSiblingShell", false);
        var startedAt = basePerfNow();
        var kindName = String(itemInfo.kind || "");
        if (kindName !== "Rectangle" && kindName !== "Oval" && kindName !== "Polygon") {
            clippedPlacedCarrierSiblingShellByKey[cacheKey] = false;
            basePerfCall("sourceIsClippedPlacedCarrierSiblingShell", startedAt);
            return false;
        }
        if (itemInfo.hasPlacedVisual === true) {
            clippedPlacedCarrierSiblingShellByKey[cacheKey] = false;
            basePerfCall("sourceIsClippedPlacedCarrierSiblingShell", startedAt);
            return false;
        }
        if (!sourceMayBeClippedPlacedCarrierSiblingShell(itemInfo, pageIndex)) {
            clippedPlacedCarrierSiblingShellByKey[cacheKey] = false;
            basePerfCall("sourceIsClippedPlacedCarrierSiblingShell", startedAt);
            return false;
        }
        var editableTextIds = [];
        try {
            editableTextIds = sourceIndex.textFrameIdsInSubtree(itemInfo.id, true, true);
        } catch (eEditableTextIds) {
            editableTextIds = [];
        }
        if (editableTextIds && editableTextIds.length > 0) {
            clippedPlacedCarrierSiblingShellByKey[cacheKey] = false;
            basePerfCall("sourceIsClippedPlacedCarrierSiblingShell", startedAt);
            return false;
        }
        clippedPlacedCarrierSiblingShellByKey[cacheKey] = true;
        basePerfCall("sourceIsClippedPlacedCarrierSiblingShell", startedAt);
        return true;
    }

    var insideClippedPlacedCarrierSiblingShellByKey = {};

    function sourceIsInsideClippedPlacedCarrierSiblingShell(sourceId, pageIndex) {
        var cacheKey = String(sourceId) + "|" + String(pageIndex);
        if (insideClippedPlacedCarrierSiblingShellByKey.hasOwnProperty(cacheKey)) {
            basePerfCache("sourceIsInsideClippedPlacedCarrierSiblingShell", true);
            return insideClippedPlacedCarrierSiblingShellByKey[cacheKey];
        }
        basePerfCache("sourceIsInsideClippedPlacedCarrierSiblingShell", false);
        var startedAt = basePerfNow();
        var src = baseCandidateSourceInfoById[String(sourceId)];
        var guard = 0;
        while (src && src.parentId !== null && src.parentId !== undefined && guard < 64) {
            var parent = baseCandidateSourceInfoById[String(src.parentId)];
            if (!parent) {
                insideClippedPlacedCarrierSiblingShellByKey[cacheKey] = false;
                basePerfCall("sourceIsInsideClippedPlacedCarrierSiblingShell", startedAt);
                return false;
            }
            if (sourceIsClippedPlacedCarrierSiblingShell(parent, pageIndex)) {
                insideClippedPlacedCarrierSiblingShellByKey[cacheKey] = true;
                basePerfCall("sourceIsInsideClippedPlacedCarrierSiblingShell", startedAt);
                return true;
            }
            src = parent;
            guard++;
        }
        insideClippedPlacedCarrierSiblingShellByKey[cacheKey] = false;
        basePerfCall("sourceIsInsideClippedPlacedCarrierSiblingShell", startedAt);
        return false;
    }

    function sourceLayerNameIsBackground(layerName) {
        if (!layerName) return false;
        var lower = String(layerName).toLowerCase();
        return lower.indexOf("\uBC30\uACBD") >= 0
                || lower.indexOf("\uBC14\uD0D5") >= 0
                || lower.indexOf("background") >= 0
                || lower === "bg"
                || lower.indexOf("backdrop") >= 0;
    }

    var pageWideSingleColorMinZByPage = {};

    function sourceBoundsIntersects(a, b) {
        if (!a || !b || a.length < 4 || b.length < 4) return false;
        return Number(a[2]) > Number(b[0]) && Number(a[0]) < Number(b[2])
                && Number(a[3]) > Number(b[1]) && Number(a[1]) < Number(b[3]);
    }

    function sourceLooksLikePageWideSingleColorFill(info, pageIndex) {
        if (!info) return false;
        var kindName = String(info.kind || "");
        if (kindName !== "Rectangle" && kindName !== "Oval" && kindName !== "Polygon") return false;
        if (!info.bounds || info.bounds.length < 4) return false;
        if (info.hasChildren === true || info.hasPlacedVisual === true) return false;
        if (hasPlacedVisualInSubtreeForBase(info.id) === true) return false;
        if (info.hasVisibleFill !== true || info.hasVisibleStroke === true) return false;
        if (info.visible === false || info.hiddenLayer === true || info.nonprinting === true) return false;
        var pb = null;
        try { pb = sourceIndex.pageBounds(Number(pageIndex)); } catch (ePageBounds) { pb = null; }
        if (!pb || pb.length < 4 || !sourceBoundsIntersects(info.bounds, pb)) return false;
        var pageWidth = Math.max(0, Number(pb[3]) - Number(pb[1]));
        var pageHeight = Math.max(0, Number(pb[2]) - Number(pb[0]));
        if (pageWidth <= 0 || pageHeight <= 0) return false;
        var width = Math.max(0, Number(info.bounds[3]) - Number(info.bounds[1]));
        var height = Math.max(0, Number(info.bounds[2]) - Number(info.bounds[0]));
        var touchesLeft = Number(info.bounds[1]) <= Number(pb[1]) + 1.0;
        var touchesRight = Number(info.bounds[3]) >= Number(pb[3]) - 1.0;
        var touchesTop = Number(info.bounds[0]) <= Number(pb[0]) + 1.0;
        var touchesBottom = Number(info.bounds[2]) >= Number(pb[2]) - 1.0;
        var spansWidth = width >= pageWidth * 0.85 && touchesLeft && touchesRight;
        var spansHeight = height >= pageHeight * 0.85 && touchesTop && touchesBottom;
        return spansWidth || spansHeight;
    }

    function minPageWideSingleColorSourceZ(pageIndex) {
        var key = String(pageIndex);
        if (pageWideSingleColorMinZByPage.hasOwnProperty(key)) {
            return pageWideSingleColorMinZByPage[key];
        }
        var minZ = null;
        var pb = null;
        try { pb = sourceIndex.pageBounds(Number(pageIndex)); } catch (ePageBounds) { pb = null; }
        for (var i = 0; sourceItems && i < sourceItems.length; i++) {
            var src = sourceItems[i];
            if (!src || !src.bounds || src.bounds.length < 4) continue;
            if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) continue;
            if (src.isInline === true) continue;
            if (!pb || pb.length < 4 || !sourceBoundsIntersects(src.bounds, pb)) continue;
            var z = Number(src.zOrder || 0);
            if (minZ === null || z < minZ) minZ = z;
        }
        pageWideSingleColorMinZByPage[key] = minZ;
        return minZ;
    }

    function sourceIsBackgroundVectorCandidate(info, sourceId, pageIndex) {
        var startedAt = basePerfNow();
        function finishBackgroundVectorCandidate(result) {
            basePerfCall("sourceIsBackgroundVectorCandidate", startedAt);
            return result;
        }
        if (!sourceLooksLikePageWideSingleColorFill(info, pageIndex)) return finishBackgroundVectorCandidate(false);
        if (info.hasChildren === true || info.hasPlacedVisual === true) return finishBackgroundVectorCandidate(false);
        if (hasPlacedVisualInSubtreeForBase(sourceId)) return finishBackgroundVectorCandidate(false);
        if (hasCandidateVectorPaintForBase(sourceId) !== true) return finishBackgroundVectorCandidate(false);
        var minZ = minPageWideSingleColorSourceZ(pageIndex);
        if (minZ === null || minZ === undefined) return finishBackgroundVectorCandidate(false);
        var result = Number(info.zOrder || 0) <= Number(minZ) + 0.001;
        return finishBackgroundVectorCandidate(result);
    }

    function sourceBoundsAreaForBase(sourceId) {
        var src = baseCandidateSourceInfoById[String(sourceId)];
        var b = src ? src.bounds : null;
        if (!b || b.length < 4) return 0;
        return Math.max(0, Number(b[3]) - Number(b[1]))
                * Math.max(0, Number(b[2]) - Number(b[0]));
    }

    function sourceTextFrameFitsShellForBase(shellSourceId, textFrameSourceId) {
        var shell = baseCandidateSourceInfoById[String(shellSourceId)];
        var textFrame = baseCandidateSourceInfoById[String(textFrameSourceId)];
        var shellBounds = shell ? shell.bounds : null;
        var textBounds = textFrame ? textFrame.bounds : null;
        var textArea = sourceBoundsAreaForBase(textFrameSourceId);
        if (!shellBounds || !textBounds || textArea <= 0) return false;
        return boundsOverlapArea(shellBounds, textBounds) / textArea >= 0.75;
    }

    var editableTextDescendantForBaseByKey = {};

    function sourceHasEditableTextDescendantForBase(sourceId, pageIndex) {
        var cacheKey = String(sourceId) + "|" + String(pageIndex);
        if (editableTextDescendantForBaseByKey.hasOwnProperty(cacheKey)) {
            basePerfCache("sourceHasEditableTextDescendantForBase", true);
            return editableTextDescendantForBaseByKey[cacheKey];
        }
        basePerfCache("sourceHasEditableTextDescendantForBase", false);
        var startedAt = basePerfNow();
        try {
            var ids = sourceIndex.textFrameIdsInSubtree(sourceId, true, true);
            for (var i = 0; ids && i < ids.length; i++) {
                var textInfo = baseCandidateSourceInfoById[String(ids[i])];
                if (!textInfo) continue;
                if (textInfo.textFrameClass !== "editable") continue;
                if (pageIndex !== null && pageIndex !== undefined && textInfo.pageIndex !== pageIndex) continue;
                editableTextDescendantForBaseByKey[cacheKey] = true;
                basePerfCall("sourceHasEditableTextDescendantForBase", startedAt);
                return true;
            }
        } catch (eEditableDescendantForBase) {}
        editableTextDescendantForBaseByKey[cacheKey] = false;
        basePerfCall("sourceHasEditableTextDescendantForBase", startedAt);
        return false;
    }

    var directSiblingTextShellOwnerByKey = {};

    function sourceMayHaveDirectSiblingTextShellOwner(sourceId, pageIndex) {
        var src = baseCandidateSourceInfoById[String(sourceId)];
        if (!src) return false;
        var kindName = String(src.kind || "");
        if (kindName !== "Rectangle" && kindName !== "Oval" && kindName !== "Polygon") return false;
        if (String(src.parentKind || "") !== "Group") return false;
        if (src.parentId === null || src.parentId === undefined) return false;
        return parentHasEditableTextFrameChildByPage[
                String(src.parentId) + "|" + String(pageIndex)] === true;
    }

    function sourceHasDirectSiblingTextShellOwner(sourceId, pageIndex) {
        var cacheKey = String(sourceId) + "|" + String(pageIndex);
        if (directSiblingTextShellOwnerByKey.hasOwnProperty(cacheKey)) {
            basePerfCache("sourceHasDirectSiblingTextShellOwner", true);
            return directSiblingTextShellOwnerByKey[cacheKey];
        }
        basePerfCache("sourceHasDirectSiblingTextShellOwner", false);
        var startedAt = basePerfNow();
        var src = baseCandidateSourceInfoById[String(sourceId)];
        if (!src) {
            directSiblingTextShellOwnerByKey[cacheKey] = false;
            basePerfCall("sourceHasDirectSiblingTextShellOwner", startedAt);
            return false;
        }
        if (!sourceMayHaveDirectSiblingTextShellOwner(sourceId, pageIndex)) {
            directSiblingTextShellOwnerByKey[cacheKey] = false;
            basePerfCall("sourceHasDirectSiblingTextShellOwner", startedAt);
            return false;
        }
        var kindName = String(src.kind || "");
        if (kindName !== "Rectangle" && kindName !== "Oval" && kindName !== "Polygon") {
            directSiblingTextShellOwnerByKey[cacheKey] = false;
            basePerfCall("sourceHasDirectSiblingTextShellOwner", startedAt);
            return false;
        }
        if (String(src.parentKind || "") !== "Group") {
            directSiblingTextShellOwnerByKey[cacheKey] = false;
            basePerfCall("sourceHasDirectSiblingTextShellOwner", startedAt);
            return false;
        }
        if (sourceHasEditableTextDescendantForBase(sourceId, pageIndex)) {
            directSiblingTextShellOwnerByKey[cacheKey] = false;
            basePerfCall("sourceHasDirectSiblingTextShellOwner", startedAt);
            return false;
        }
        if (hasCandidateVectorPaintForBase(sourceId) !== true) {
            directSiblingTextShellOwnerByKey[cacheKey] = false;
            basePerfCall("sourceHasDirectSiblingTextShellOwner", startedAt);
            return false;
        }
        if (src.parentId === null || src.parentId === undefined) {
            directSiblingTextShellOwnerByKey[cacheKey] = false;
            basePerfCall("sourceHasDirectSiblingTextShellOwner", startedAt);
            return false;
        }
        if (!sourceIsInPagePlaneForBase(sourceId, pageIndex)) {
            directSiblingTextShellOwnerByKey[cacheKey] = false;
            basePerfCall("sourceHasDirectSiblingTextShellOwner", startedAt);
            return false;
        }
        var siblings = baseCandidateChildIdsByParentId[String(src.parentId)] || [];
        var matchingTextFrameCount = 0;
        for (var si = 0; si < siblings.length; si++) {
            var siblingId = siblings[si];
            if (String(siblingId) === String(sourceId)) continue;
            var sibling = baseCandidateSourceInfoById[String(siblingId)];
            if (!sibling || sibling.kind !== "TextFrame") continue;
            if (!sourceIsInPagePlaneForBase(siblingId, pageIndex)) continue;
            if (sibling.textFrameClass !== "editable" || sibling.hasText !== true) continue;
            if (sibling.pageIndex !== pageIndex) continue;
            if (!sourceTextFrameFitsShellForBase(sourceId, siblingId)) continue;
            matchingTextFrameCount++;
        }
        var result = matchingTextFrameCount === 1;
        directSiblingTextShellOwnerByKey[cacheKey] = result;
        basePerfCall("sourceHasDirectSiblingTextShellOwner", startedAt);
        return result;
    }

    var clipParentShellOwnerForBaseByKey = {};
    var sourceMayHaveClipParentShellOwnerForBaseById = {};

    function sourceKindCanCarryClipForBase(kind) {
        kind = String(kind || "");
        return kind === "Oval" || kind === "Rectangle" || kind === "Polygon";
    }

    function sourceKindCanHaveShellAncestorForBase(kind) {
        kind = String(kind || "");
        return kind === "Group" || kind === "Rectangle" || kind === "Oval" || kind === "Polygon";
    }

    function sourceMayHaveClipParentShellOwner(sourceId) {
        var sourceKey = String(sourceId);
        if (sourceMayHaveClipParentShellOwnerForBaseById.hasOwnProperty(sourceKey)) {
            return sourceMayHaveClipParentShellOwnerForBaseById[sourceKey];
        }
        var src = baseCandidateSourceInfoById[sourceKey];
        if (!src || src.parentId === null || src.parentId === undefined) {
            sourceMayHaveClipParentShellOwnerForBaseById[sourceKey] = false;
            return false;
        }
        if (clipCarryingParentIdForBase(sourceId) !== null) {
            sourceMayHaveClipParentShellOwnerForBaseById[sourceKey] = true;
            return true;
        }
        var parentId = src.parentId;
        for (var depth = 0; depth < 32 && parentId !== null && parentId !== undefined; depth++) {
            var parentInfo = baseCandidateSourceInfoById[String(parentId)];
            if (!parentInfo) {
                sourceMayHaveClipParentShellOwnerForBaseById[sourceKey] = false;
                return false;
            }
            if (shouldEmitDecorationSourceCandidate(parentInfo)
                    && !hasPlacedVisualInSubtreeForBase(parentInfo.id)) {
                sourceMayHaveClipParentShellOwnerForBaseById[sourceKey] = true;
                return true;
            }
            if (!sourceKindCanHaveShellAncestorForBase(parentInfo.kind)) {
                sourceMayHaveClipParentShellOwnerForBaseById[sourceKey] = false;
                return false;
            }
            if (sourceKindCanCarryClipForBase(parentInfo.kind) && parentInfo.hasChildren === true) {
                sourceMayHaveClipParentShellOwnerForBaseById[sourceKey] = true;
                return true;
            }
            parentId = parentInfo.parentId;
        }
        sourceMayHaveClipParentShellOwnerForBaseById[sourceKey] = false;
        return false;
    }

    function clipParentShellOwnerForBase(sourceId, pageIndex) {
        var cacheKey = String(sourceId) + "|" + String(pageIndex);
        if (clipParentShellOwnerForBaseByKey.hasOwnProperty(cacheKey)) {
            basePerfCache("clipParentShellOwnerForBase", true);
            return clipParentShellOwnerForBaseByKey[cacheKey] || null;
        }
        basePerfCache("clipParentShellOwnerForBase", false);
        var startedAt = basePerfNow();
        var clipParentId = clipCarryingParentIdForBase(sourceId);
        if (clipParentId === null || clipParentId === undefined) {
            clipParentShellOwnerForBaseByKey[cacheKey] = false;
            basePerfCall("clipParentShellOwnerForBase", startedAt);
            return null;
        }
        if (String(clipParentId) === String(sourceId)) {
            clipParentShellOwnerForBaseByKey[cacheKey] = false;
            basePerfCall("clipParentShellOwnerForBase", startedAt);
            return null;
        }
        var clipParentInfo = sourceInfoForBase(clipParentId);
        if (!clipParentInfo || clipParentInfo.hiddenLayer === true) {
            clipParentShellOwnerForBaseByKey[cacheKey] = false;
            basePerfCall("clipParentShellOwnerForBase", startedAt);
            return null;
        }
        if (!sourceIsInPagePlaneForBase(clipParentId, pageIndex)) {
            clipParentShellOwnerForBaseByKey[cacheKey] = false;
            basePerfCall("clipParentShellOwnerForBase", startedAt);
            return null;
        }
        if (!shouldEmitDecorationSourceCandidate(clipParentInfo)) {
            clipParentShellOwnerForBaseByKey[cacheKey] = false;
            basePerfCall("clipParentShellOwnerForBase", startedAt);
            return null;
        }
        var ownerSourceIds = pageLocalSourceObjectIdsForBase(clipParentId, pageIndex);
        if (!sourceSetHasExecutableShellMaterial(ownerSourceIds)) {
            clipParentShellOwnerForBaseByKey[cacheKey] = false;
            basePerfCall("clipParentShellOwnerForBase", startedAt);
            return null;
        }
        var result = {
            sourceObjectId: clipParentId,
            sourceObjectIds: ownerSourceIds
        };
        clipParentShellOwnerForBaseByKey[cacheKey] = result;
        basePerfCall("clipParentShellOwnerForBase", startedAt);
        return result;
    }

    var ancestorShellOwnerForBaseByKey = {};

    function ancestorShellOwnerForBase(sourceId, pageIndex) {
        var cacheKey = String(sourceId) + "|" + String(pageIndex);
        if (ancestorShellOwnerForBaseByKey.hasOwnProperty(cacheKey)) {
            basePerfCache("ancestorShellOwnerForBase", true);
            return ancestorShellOwnerForBaseByKey[cacheKey] || null;
        }
        basePerfCache("ancestorShellOwnerForBase", false);
        var startedAt = basePerfNow();
        var src = sourceInfoForBase(sourceId);
        var parentId = src ? src.parentId : null;
        var bestOwner = null;
        for (var depth = 0; depth < 32 && parentId !== null && parentId !== undefined; depth++) {
            var parentInfo = sourceInfoForBase(parentId);
            if (!parentInfo) {
                ancestorShellOwnerForBaseByKey[cacheKey] = false;
                basePerfCall("ancestorShellOwnerForBase", startedAt);
                return null;
            }
            if (parentInfo.hiddenLayer === true) {
                ancestorShellOwnerForBaseByKey[cacheKey] = false;
                basePerfCall("ancestorShellOwnerForBase", startedAt);
                return null;
            }
            if (!sourceIsInPagePlaneForBase(parentInfo.id, pageIndex)) {
                ancestorShellOwnerForBaseByKey[cacheKey] = false;
                basePerfCall("ancestorShellOwnerForBase", startedAt);
                return null;
            }
            if (shouldEmitDecorationSourceCandidate(parentInfo)
                    && !hasPlacedVisualInSubtreeForBase(parentInfo.id)) {
                var ownerSourceIds = pageLocalSourceObjectIdsForBase(parentInfo.id, pageIndex);
                if (_sourceIdsContain(ownerSourceIds, sourceId)
                        && sourceSetHasExecutableShellMaterial(ownerSourceIds)) {
                    bestOwner = {
                        sourceObjectId: parentInfo.id,
                        sourceObjectIds: ownerSourceIds
                    };
                }
            }
            if (String(parentInfo.kind || "") !== "Group"
                    && String(parentInfo.kind || "") !== "Rectangle"
                    && String(parentInfo.kind || "") !== "Oval"
                    && String(parentInfo.kind || "") !== "Polygon") {
                ancestorShellOwnerForBaseByKey[cacheKey] = false;
                basePerfCall("ancestorShellOwnerForBase", startedAt);
                return null;
            }
            parentId = parentInfo.parentId;
        }
        ancestorShellOwnerForBaseByKey[cacheKey] = bestOwner || false;
        basePerfCall("ancestorShellOwnerForBase", startedAt);
        return bestOwner;
    }

    function markClipParentShellOwner(attrs, sourceId, pageIndex) {
        if (!sourceMayHaveClipParentShellOwner(sourceId)) return attrs;
        var owner = clipParentShellOwnerForBase(sourceId, pageIndex);
        if (!owner) owner = ancestorShellOwnerForBase(sourceId, pageIndex);
        if (!owner) return attrs;
        attrs.clipParentShellOwnerSourceId = owner.sourceObjectId;
        attrs.clipParentShellOwnerSourceObjectIds = owner.sourceObjectIds;
        return attrs;
    }

    var sourceHasClipParentShellOwnerForBaseByKey = {};

    function sourceHasClipParentShellOwner(sourceId, pageIndex) {
        var cacheKey = String(sourceId) + "|" + String(pageIndex);
        if (sourceHasClipParentShellOwnerForBaseByKey.hasOwnProperty(cacheKey)) {
            basePerfCache("sourceHasClipParentShellOwner", true);
            return sourceHasClipParentShellOwnerForBaseByKey[cacheKey];
        }
        basePerfCache("sourceHasClipParentShellOwner", false);
        var startedAt = basePerfNow();
        var result = false;
        if (sourceMayHaveClipParentShellOwner(sourceId)) {
            result = clipParentShellOwnerForBase(sourceId, pageIndex) !== null
                    || ancestorShellOwnerForBase(sourceId, pageIndex) !== null;
        }
        sourceHasClipParentShellOwnerForBaseByKey[cacheKey] = result;
        basePerfCall("sourceHasClipParentShellOwner", startedAt);
        return result;
    }

    function sourceSubtreeHasEditableText(sourceId) {
        try {
            var ids = sourceIndex.textFrameIdsInSubtree(sourceId, true, true);
            return ids && ids.length > 0;
        } catch (eTextIds) {
            return false;
        }
    }

    function topVisualOnlyCompositeRoot(sourceId, pageIndex) {
        var currentId = sourceId;
        var current = sourceInfoForBase(currentId);
        var guard = 0;
        while (current && current.parentId !== null && current.parentId !== undefined && guard < 32) {
            var parent = sourceInfoForBase(current.parentId);
            if (!parent || String(parent.kind || "") !== "Group") break;
            if (parent.hiddenLayer === true) break;
            if (!sourceIndex.hasPlacedVisualInSubtree(parent.id)) break;
            if (sourceSubtreeHasEditableText(parent.id)) break;
            if (parent.pageIndex >= 0 && pageIndex !== null && pageIndex !== undefined
                    && parent.pageIndex !== pageIndex
                    && !sourceIndex.sameSpread(parent.pageIndex, pageIndex)) {
                break;
            }
            currentId = parent.id;
            current = parent;
            guard++;
        }
        return currentId;
    }

    basePerfMarker("03d05b_base_indexes_ready");
    basePerfWrite("indexes_ready", -1);

    // Legacy pre-declaration of broad decoration source sets used to scan
    // candidate page-local descendants before ObjectPlan ownership existed.
    // With page-root textless planes, this creates a second hidden owner for
    // the same visual material and can force expensive DOM subtree queries on
    // large textbook files. Stage 1 now emits the canonical page/textless
    // visual candidates, so keep this pass closed and let later planning use
    // only source-index metadata.
    basePerfStats.predeclareItemCount = predeclareCandidateInfos.length;
    basePerfStats.predeclarePageCount = 0;

    basePerfMarker("03d05c_base_predeclare_done");
    basePerfWrite("predeclare_done", sourceItems.length);

    var mainLoopStartedAt = basePerfWallNow();
    for (var i = 0; i < sourceItems.length; i++) {
        basePerfStats.mainItemCount++;
        if (i > 0 && i % 1000 === 0) {
            basePerfWrite("main_loop", i);
        }
        var itemInfo = sourceItems[i];
        var kind = itemInfo.kind;
        var id = itemInfo.id;
        if (id === null) continue;
        var item = null;

        if (itemInfo.hiddenLayer === true) {
            baseSuppressedPreview("hidden_layer", itemInfo);
            continue;
        }
        if (!sourceKindCanEmitBaseCandidate(kind)) {
            baseSuppressedPreview("source_kind_cannot_emit_base_candidate", itemInfo);
            continue;
        }
        if (kind !== "TextFrame"
                && itemInfo.pageIndex !== null && itemInfo.pageIndex !== undefined
                && pageIndexInBaseRange(itemInfo.pageIndex)
                && sourceCoveredByBroaderDecorationSourceSet(itemInfo.pageIndex, id)) {
            baseSuppressedPreview("covered_by_broader_decoration_source_set", itemInfo);
            continue;
        }
        if (kind !== "TextFrame" && sourceHasStoryFlowAnchor(id)) {
            baseSuppressedPreview("story_flow_anchor", itemInfo);
            continue;
        }
        if (kind === "TextFrame" && !textFrameMayHaveStyleShellForBase(itemInfo)) {
            baseSuppressedPreview("text_frame_without_style_shell", itemInfo);
            continue;
        }
        var extractionPageIndexes = candidatePageIndexesForBase(id);
        if (!extractionPageIndexes || extractionPageIndexes.length === 0) {
            baseSuppressedPreview("no_candidate_page_indexes", itemInfo);
            continue;
        }

        var mainPageLoopStartedAt = basePerfWallNow();
        for (var extractionPageCursor = 0; extractionPageCursor < extractionPageIndexes.length; extractionPageCursor++) {
            basePerfStats.mainPageCount++;
            var extractionPageIndex = extractionPageIndexes[extractionPageCursor];

            if (kind === "Rectangle" || kind === "Oval" || kind === "Polygon") {
                var rectBranchStartedAt = basePerfBranchStart("rectangle_oval_polygon");
                var rectDirectSiblingTextShellOwned = null;
                var rectOwnedByClipParentShell = null;

                function directSiblingTextShellOwnedForRect() {
                    if (rectDirectSiblingTextShellOwned !== null) return rectDirectSiblingTextShellOwned;
                    rectDirectSiblingTextShellOwned = sourceMayHaveDirectSiblingTextShellOwner(id, extractionPageIndex)
                            ? sourceHasDirectSiblingTextShellOwner(id, extractionPageIndex)
                            : false;
                    return rectDirectSiblingTextShellOwned;
                }

                function ownedByClipParentShellForRect() {
                    if (rectOwnedByClipParentShell !== null) return rectOwnedByClipParentShell;
                    rectOwnedByClipParentShell = sourceHasClipParentShellOwner(id, extractionPageIndex);
                    return rectOwnedByClipParentShell;
                }

                if (sourceMayBeBackgroundVectorCandidateFast(itemInfo, extractionPageIndex)
                        && sourceIsBackgroundVectorCandidate(itemInfo, id, extractionPageIndex)
                        && !directSiblingTextShellOwnedForRect()
                        && !ownedByClipParentShellForRect()) {
                    var backgroundVectorSourceIds = pageLocalSourceObjectIdsForBase(id, extractionPageIndex);
                    if (hasBroaderDecorationSourceSet(extractionPageIndex, backgroundVectorSourceIds || [id])) continue;
                    item = item || domItemForBase(id);
                    if (!item) continue;
                    pushBaseExtractionCandidate("pass.decoration_groups", item, candidateAttrsForInfo(itemInfo, {
                        sourceObjectIds: backgroundVectorSourceIds,
                        pageIndex: extractionPageIndex,
                        unit: "GROUP_OR_ITEM",
                        mode: "TEXTLESS_CANDIDATE",
                        candidatePurpose: "SHELL_CANDIDATE",
                        compositeRole: "background_vector_source",
                        slotRole: "background_shell_slot",
                        exportSourceObjectIds: backgroundVectorSourceIds || [id],
                        exportTargetObjectId: id,
                        hiddenVisualSourceObjectIds: [],
                        containsEditableText: false,
                        textOwner: "none"
                    }), "background_vector_source");
                    recordDecorationSourceSet(extractionPageIndex, backgroundVectorSourceIds || [id]);
                    continue;
                }
                if (sourceIndex.hasPlacedVisual(id) && !ownedByClipParentShellForRect()) {
                    item = item || domItemForBase(id);
                    if (!item) continue;
                    var placedFrameSourceIds = pageLocalSourceObjectIdsForBase(id, extractionPageIndex);
                    pushBaseExtractionCandidate("pass.image_placed_frames", item, markClipParentShellOwner(candidateAttrsForInfo(itemInfo, {
                        sourceObjectIds: placedFrameSourceIds,
                        visualSourceObjectIds: placedFrameSourceIds,
                        exportSourceObjectIds: placedFrameSourceIds,
                        exportTargetObjectId: id,
                        pageIndex: extractionPageIndex,
                        unit: "ITEM",
                        mode: "ORIGINAL_VISUAL",
                        candidatePurpose: "CONTENT_CANDIDATE"
                    }), id, extractionPageIndex), "placed_image_frame");
                    try {
                        if (itemInfo.parentKind === "Group") {
                            var imageParentId = topVisualOnlyCompositeRoot(itemInfo.parentId, extractionPageIndex);
                            var imageParentItem = domItemForBase(imageParentId);
                            if (imageParentItem
                                    && !sourceIsInsideClippedPlacedCarrierSiblingShell(imageParentId, extractionPageIndex)
                                    && !sourceIndex.hasEditableTextDescendantOutsideSubtree(imageParentId, id)) {
                                var imageGroupSourceIds = pageLocalSourceObjectIdsForBase(
                                        imageParentId, extractionPageIndex);
                            var imageParentInfo = sourceInfoForBase(imageParentId);
                                pushBaseExtractionCandidate("pass.image_textless_groups", imageParentItem, markClipParentShellOwner(candidateAttrsForInfo(imageParentInfo, {
                                    sourceObjectIds: imageGroupSourceIds,
                                    pageIndex: extractionPageIndex,
                                    unit: "GROUP",
                                    mode: "TEXTLESS_CANDIDATE",
                                    candidatePurpose: "CONTENT_CANDIDATE",
                                    compositeRole: imageGroupSourceIds && imageGroupSourceIds.length > 1 ? "image_group_textless_source_set" : null
                                }), imageParentId, extractionPageIndex), "image_group_textless_source_set");
                            }
                        }
                    } catch (eParentImageCandidate) {}
                }
                if (sourceMayBeClippedPlacedCarrierSiblingShell(itemInfo, extractionPageIndex)
                        && !directSiblingTextShellOwnedForRect()
                        && !ownedByClipParentShellForRect()
                        && sourceIsClippedPlacedCarrierSiblingShell(itemInfo, extractionPageIndex)) {
                    var clippedCarrierShellSourceIds = pageLocalSourceObjectIdsForBase(id, extractionPageIndex);
                    if (hasBroaderDecorationSourceSet(extractionPageIndex, clippedCarrierShellSourceIds || [id])) continue;
                    item = item || domItemForBase(id);
                    if (!item) continue;
                    pushBaseExtractionCandidate("pass.decoration_groups", item, candidateAttrsForInfo(itemInfo, {
                        sourceObjectIds: clippedCarrierShellSourceIds,
                        pageIndex: extractionPageIndex,
                        unit: "GROUP_OR_ITEM",
                        mode: "TEXTLESS_CANDIDATE",
                        candidatePurpose: "SHELL_CANDIDATE",
                        compositeRole: "clipped_placed_carrier_sibling_shell",
                        slotRole: "shell_slot_only",
                        renderMode: "SLOT_ONLY",
                        exportSourceObjectIds: clippedCarrierShellSourceIds || [id],
                        exportTargetObjectId: id,
                        hiddenVisualSourceObjectIds: [],
                        visualOnlyChildIds: clippedCarrierShellSourceIds || [id],
                        containsEditableText: false,
                        textOwner: "none"
                    }), "clipped_placed_carrier_sibling_shell");
                    recordDecorationSourceSet(extractionPageIndex, clippedCarrierShellSourceIds || [id]);
                }

                if (shouldRasterizeLeafVectorShell(itemInfo)
                        && !directSiblingTextShellOwnedForRect()
                        && !ownedByClipParentShellForRect()) {
                    if (hasBroaderDecorationSourceSet(extractionPageIndex, [id])) continue;
                    item = item || domItemForBase(id);
                    if (!item) continue;
                    pushBaseExtractionCandidate("pass.decoration_groups", item, candidateAttrsForInfo(itemInfo, {
                        sourceObjectIds: [id],
                        pageIndex: extractionPageIndex,
                        unit: "GROUP_OR_ITEM",
                        mode: "TEXTLESS_CANDIDATE",
                        candidatePurpose: "SHELL_CANDIDATE",
                        compositeRole: "leaf_vector_shell_source",
                        slotRole: "shell_slot_only",
                        renderMode: "SLOT_ONLY",
                        exportSourceObjectIds: [id],
                        exportTargetObjectId: id,
                        hiddenVisualSourceObjectIds: [],
                        containsEditableText: false,
                        textOwner: "none"
                    }), "leaf_vector_shell_source");
                    recordDecorationSourceSet(extractionPageIndex, [id]);
                    continue;
                } else if (hasCandidateVectorPaintForBase(id) === true
                        && !hasPlacedVisualInSubtreeForBase(id)
                        && itemInfo.hasChildren !== true
                        && !ownedByClipParentShellForRect()) {
                    if (hasBroaderDecorationSourceSet(extractionPageIndex, [id])) continue;
                    item = item || domItemForBase(id);
                    if (!item) continue;
                    pushBaseExtractionCandidate("pass.vector_shape_frames", item, markClipParentShellOwner(candidateAttrsForInfo(itemInfo, {
                        sourceObjectIds: [id],
                        pageIndex: extractionPageIndex,
                        unit: "ITEM",
                        mode: "TEXTLESS_CANDIDATE",
                        candidatePurpose: "VECTOR_CANDIDATE",
                        renderMode: "SLOT_ONLY",
                        exportSourceObjectIds: [id],
                        exportTargetObjectId: id,
                        hiddenVisualSourceObjectIds: [],
                        containsEditableText: false,
                        textOwner: "none"
                    }), id, extractionPageIndex), "leaf_vector_shape_frame");
                }
                basePerfBranchEnd("rectangle_oval_polygon", rectBranchStartedAt);
            }

            if (kind === "GraphicLine") {
                var graphicLineBranchStartedAt = basePerfBranchStart("graphic_line");
                if (hasCandidateVectorPaintForBase(id) === true
                        && !sourceHasClipParentShellOwner(id, extractionPageIndex)) {
                    if (shouldRasterizeLeafVectorShell(itemInfo)) {
                        if (hasBroaderDecorationSourceSet(extractionPageIndex, [id])) {
                            basePerfBranchEnd("graphic_line", graphicLineBranchStartedAt);
                            continue;
                        }
                        item = item || domItemForBase(id);
                        if (!item) {
                            basePerfBranchEnd("graphic_line", graphicLineBranchStartedAt);
                            continue;
                        }
                        pushBaseExtractionCandidate("pass.decoration_groups", item, candidateAttrsForInfo(itemInfo, {
                            sourceObjectIds: [id],
                            pageIndex: extractionPageIndex,
                            unit: "GROUP_OR_ITEM",
                            mode: "TEXTLESS_CANDIDATE",
                            candidatePurpose: "SHELL_CANDIDATE",
                            compositeRole: "leaf_vector_shell_source",
                            slotRole: "shell_slot_only",
                            renderMode: "SLOT_ONLY",
                            exportSourceObjectIds: [id],
                            exportTargetObjectId: id,
                            hiddenVisualSourceObjectIds: [],
                            containsEditableText: false,
                            textOwner: "none"
                        }), "graphic_line_leaf_vector_shell_source");
                        recordDecorationSourceSet(extractionPageIndex, [id]);
                        basePerfBranchEnd("graphic_line", graphicLineBranchStartedAt);
                        continue;
                    }
                    if (hasBroaderDecorationSourceSet(extractionPageIndex, [id])) {
                        basePerfBranchEnd("graphic_line", graphicLineBranchStartedAt);
                        continue;
                    }
                    item = item || domItemForBase(id);
                    if (!item) {
                        basePerfBranchEnd("graphic_line", graphicLineBranchStartedAt);
                        continue;
                    }
                    pushBaseExtractionCandidate("pass.vector_shape_frames", item, markClipParentShellOwner(candidateAttrsForInfo(itemInfo, {
                        sourceObjectIds: [id],
                        pageIndex: extractionPageIndex,
                        unit: "ITEM",
                        mode: "TEXTLESS_CANDIDATE",
                        candidatePurpose: "VECTOR_CANDIDATE",
                        renderMode: "SLOT_ONLY",
                        exportSourceObjectIds: [id],
                        exportTargetObjectId: id,
                        hiddenVisualSourceObjectIds: [],
                        containsEditableText: false,
                        textOwner: "none"
                    }), id, extractionPageIndex), "graphic_line_vector_shape_frame");
                }
                basePerfBranchEnd("graphic_line", graphicLineBranchStartedAt);
            }

            if (sourceMayNeedDecorationCompositeBranch(itemInfo)) {
                var decorationBranchStartedAt = basePerfBranchStart("decoration_composite");
                var allowPlacedVisualClipParentShell = hasPlacedVisualInSubtreeForBase(id)
                        && sourceMayEmitClipParentShellCandidate(itemInfo, extractionPageIndex);
                if (!hasPlacedVisualInSubtreeForBase(id) || allowPlacedVisualClipParentShell) {
                    var clipParentForDecoId = clipCarryingParentIdForBase(id);
                    var clipParentForDeco = clipParentForDecoId !== null ? domItemForBase(clipParentForDecoId) : null;
                    var clipParentInfo = clipParentForDecoId !== null ? sourceInfoForBase(clipParentForDecoId) : null;
                    if (clipParentForDeco && clipParentInfo && clipParentInfo.hiddenLayer !== true
                            && !sourceHasClipParentShellOwner(clipParentForDecoId, extractionPageIndex)
                            && !hasPlacedVisualInSubtreeForBase(clipParentForDecoId)
                            && shouldEmitDecorationSourceCandidate(clipParentInfo)) {
                        var clipParentSourceIds = pageLocalSourceObjectIdsForBase(
                                clipParentForDecoId, extractionPageIndex);
                        if (hasDecorationSourceSet(extractionPageIndex, clipParentSourceIds)) continue;
                        if (hasBroaderDecorationSourceSet(extractionPageIndex, clipParentSourceIds)) continue;
                        if (!sourceSetHasExecutableShellMaterial(clipParentSourceIds)) {
                            baseSuppressedPreview("clip_parent_source_set_without_executable_shell_material", clipParentInfo, {
                                sourceObjectIds: clipParentSourceIds
                            });
                            continue;
                        }
                        pushBaseExtractionCandidate("pass.decoration_groups", clipParentForDeco, candidateAttrsForInfo(clipParentInfo, {
                            sourceObjectIds: clipParentSourceIds,
                            pageIndex: extractionPageIndex,
                            unit: "GROUP_OR_ITEM",
                            mode: "TEXTLESS_CANDIDATE",
                            candidatePurpose: "SHELL_CANDIDATE",
                            compositeRole: clipParentSourceIds && clipParentSourceIds.length > 1 ? "clip_parent_source_set" : null,
                            suffix: "clip_parent"
                        }), "clip_parent_source_set");
                        recordDecorationSourceSet(extractionPageIndex, clipParentSourceIds);
                    } else if (!sourceHasClipParentShellOwner(id, extractionPageIndex)
                            && shouldEmitDecorationSourceCandidate(itemInfo)) {
                        var decoSourceIds = pageLocalSourceObjectIdsForBase(id, extractionPageIndex);
                        if (hasBroaderDecorationSourceSet(extractionPageIndex, decoSourceIds)) continue;
                        var decoExportSourceIds = shellExportSourceIdsForSourceSet(id, decoSourceIds);
                        if (!sourceSetHasExecutableShellMaterial(decoExportSourceIds)) {
                            baseSuppressedPreview("decoration_group_without_executable_shell_material", itemInfo, {
                                sourceObjectIds: decoSourceIds,
                                exportSourceObjectIds: decoExportSourceIds
                            });
                            continue;
                        }
                        item = item || domItemForBase(id);
                        if (!item) {
                            baseSuppressedPreview("decoration_group_missing_dom_item", itemInfo, {
                                sourceObjectIds: decoSourceIds,
                                exportSourceObjectIds: decoExportSourceIds
                            });
                            continue;
                        }
                        pushBaseExtractionCandidate("pass.decoration_groups", item, candidateAttrsForInfo(itemInfo, {
                            sourceObjectIds: decoSourceIds,
                            exportSourceObjectIds: decoExportSourceIds,
                            visualSourceObjectIds: decoExportSourceIds,
                            pageIndex: extractionPageIndex,
                            unit: "GROUP_OR_ITEM",
                            mode: "TEXTLESS_CANDIDATE",
                            candidatePurpose: "SHELL_CANDIDATE",
                            compositeRole: decoSourceIds && decoSourceIds.length > 1 ? "decoration_group_source_set" : null
                        }), "decoration_group_source_set");
                        recordDecorationSourceSet(extractionPageIndex, decoSourceIds);
                    }
                } else {
                    baseSuppressedPreview("decoration_group_skipped_by_placed_visual_subtree", itemInfo, {
                        allowPlacedVisualClipParentShell: allowPlacedVisualClipParentShell
                    });
                }
                basePerfBranchEnd("decoration_composite", decorationBranchStartedAt);
            }

            if (kind === "Rectangle" || kind === "Oval" || kind === "Polygon") {
                var complexBranchStartedAt = basePerfBranchStart("complex_graphic");
                try {
                    if (itemInfo.hasChildren === true
                            && !sourceIndex.hasPlacedVisual(id)
                            && !sourceHasClipParentShellOwner(id, extractionPageIndex)
                            && !(sourceMayBeClippedPlacedCarrierSiblingShell(itemInfo, extractionPageIndex)
                                    && sourceIsClippedPlacedCarrierSiblingShell(itemInfo, extractionPageIndex))) {
                        var complexEditableTextIds = [];
                        try { complexEditableTextIds = sourceIndex.textFrameIdsInSubtree(id, true, true); } catch (eComplexTextIds) {}
                        if (complexEditableTextIds && complexEditableTextIds.length > 0) {
                            continue;
                        }
                        var complexSourceIds = pageLocalSourceObjectIdsForBase(id, extractionPageIndex);
                        if (hasDecorationSourceSet(extractionPageIndex, complexSourceIds || [id])) continue;
                        if (hasBroaderDecorationSourceSet(extractionPageIndex, complexSourceIds || [id])) continue;
                        var complexVisualSourceIds = [];
                        try { complexVisualSourceIds = sourceIndex.placedVisualSourceObjectIdsInSubtree(id); } catch (eComplexVisualSourceIds) {}
                        var complexExportSourceIds = complexSourceIds && complexSourceIds.length > 0
                                ? complexSourceIds
                                : (complexVisualSourceIds && complexVisualSourceIds.length > 0 ? complexVisualSourceIds : [id]);
                        item = item || domItemForBase(id);
                        if (!item) continue;
                        pushBaseExtractionCandidate("pass.complex_graphic_frames", item, markClipParentShellOwner(candidateAttrsForInfo(itemInfo, {
                            sourceObjectIds: complexSourceIds,
                            exportSourceObjectIds: complexExportSourceIds,
                            exportTargetObjectId: id,
                            visualSourceObjectIds: complexExportSourceIds,
                            pageIndex: extractionPageIndex,
                            unit: "GROUP",
                            mode: "TEXTLESS_CANDIDATE",
                            candidatePurpose: "CONTENT_CANDIDATE",
                            compositeRole: complexSourceIds && complexSourceIds.length > 1 ? "complex_graphic_source_set" : null
                        }), id, extractionPageIndex), "complex_graphic_source_set");
                    }
                } catch (eComplexCandidate) {}
                basePerfBranchEnd("complex_graphic", complexBranchStartedAt);
            }

            if (kind === "TextFrame") {
                var textFrameBranchStartedAt = basePerfBranchStart("textframe_style_shell");
                var tfShellSourceIds = [id];
                item = item || domItemForBase(id);
                if (!item) continue;
                var textFrameInlineFlow = sourceInfoIsInlineFlowForBase(itemInfo);
                var textFrameShellPassId = textFrameInlineFlow
                        ? "pass.inline_objects"
                        : "pass.editable_textframe_visual_shells";
                pushBaseExtractionCandidate(textFrameShellPassId, item, candidateAttrsForInfo(itemInfo, {
                    sourceObjectIds: tfShellSourceIds,
                    executionSourceObjectIds: tfShellSourceIds,
                    exportSourceObjectIds: tfShellSourceIds,
                    visualSourceObjectIds: tfShellSourceIds,
                    styleSourceObjectIds: tfShellSourceIds,
                    editableTextFrameIds: tfShellSourceIds,
                    hiddenTextFrameIds: tfShellSourceIds,
                    requiresTextHidden: true,
                    textOwner: "hwpx_tf",
                    pageIndex: extractionPageIndex,
                    unit: textFrameInlineFlow ? "INLINE_OBJECT" : "TEXT_FRAME",
                    mode: "TEXTLESS_CANDIDATE",
                    candidatePurpose: textFrameInlineFlow ? "INLINE_CANDIDATE" : "SHELL_CANDIDATE",
                    compositeRole: textFrameInlineFlow
                            ? "inline_visible_text_frame_shell"
                            : "textframe_style_shell_slot",
                    slotRole: textFrameInlineFlow
                            ? "inline_text_frame_shell_slot"
                            : "direct_child_shell_slot",
                    inlineAnchorSourceObjectId: textFrameInlineFlow ? id : null,
                    inlineSourceTreeClosed: textFrameInlineFlow,
                    inlineFlowSourceObjectIds: textFrameInlineFlow ? tfShellSourceIds : [],
                    ownershipSlot: "SHELL_SLOT",
                    materialization: "EXTRACTED_PNG_VECTOR",
                    textAction: "DROP_TEXT",
                    visualAction: "PLACE_TEXT_SHELL",
                    visualLayer: "LABEL_BACKDROP",
                    placement: textFrameInlineFlow ? "INLINE" : "FLOATING",
                    coordinateSpace: textFrameInlineFlow ? "STORY_FLOW" : "PAGE"
                }), textFrameInlineFlow
                        ? "inline_visible_text_frame_shell"
                        : "textframe_style_shell_slot");
                basePerfBranchEnd("textframe_style_shell", textFrameBranchStartedAt);
            }
        }
        basePerfStats.mainPageLoopMs += Math.max(0, basePerfWallNow() - mainPageLoopStartedAt);
    }
    basePerfStats.mainLoopMs = Math.max(0, basePerfWallNow() - mainLoopStartedAt);
    var unclaimedFallbackStartedAt = basePerfWallNow();
    basePerfMarker("03d05d0_base_unclaimedVisibleVectorFallback_start");
    if (ctx && ctx.enableLegacyUnclaimedVisibleVectorFallback === true) {
        appendUnclaimedVisibleVectorSourceCandidates();
        basePerfStats.unclaimedVisibleVectorFallbackSkipped = false;
    } else {
        basePerfStats.unclaimedVisibleVectorFallbackCount = 0;
        basePerfStats.unclaimedVisibleVectorFallbackSkipped = true;
    }
    basePerfStats.fallbackMs.unclaimedVisibleVectorFallback = Math.max(
            0, basePerfWallNow() - unclaimedFallbackStartedAt);
    basePerfMarker("03d05d1_base_unclaimedVisibleVectorFallback_done");
    basePerfMarker("03d05d_base_main_done");
    basePerfWrite("done", sourceItems.length);
}

function _appendEditableTextFrameStyleShellCandidatesFromSourceItems(sourceItems, candidates, candidateSeen) {
    function itemInfoKind(itemInfo) {
        return String(itemInfo && (itemInfo.kind || itemInfo.type || itemInfo.itemType) || "");
    }
    function itemInfoHasTextFrameShellStyle(itemInfo) {
        if (!itemInfo || itemInfoKind(itemInfo) !== "TextFrame") return false;
        if (itemInfo.hasVisibleFill === true || itemInfo.hasVisibleStroke === true) return true;
        var fillName = String(itemInfo.fillColorName || itemInfo.fillColor || "");
        if (fillName && fillName !== "None" && fillName !== "[None]") return true;
        var strokeName = String(itemInfo.strokeColorName || itemInfo.strokeColor || "");
        var strokeWeight = Number(itemInfo.strokeWeight || 0);
        return strokeName && strokeName !== "None" && strokeName !== "[None]" && strokeWeight > 0;
    }
    function itemInfoIsInlineFlow(itemInfo) {
        if (!itemInfo) return false;
        var placement = String(itemInfo.storyAnchorPlacement || "").toUpperCase();
        var anchoredPosition = String(itemInfo.anchoredPosition || "").toUpperCase();
        if (placement === "FLOATING_ANCHORED" || anchoredPosition === "ANCHORED") return false;
        if (itemInfo.storyTextInlineSlot === true || itemInfo.isInline === true) return true;
        return placement === "INLINE"
                || anchoredPosition === "INLINE_POSITION"
                || anchoredPosition === "INLINEPOSITION";
    }
    function hasCandidate(passId, pageIndex, sourceIds) {
        var sourceKey = _sourceSetKey(sourceIds || []);
        for (var ci = 0; candidates && ci < candidates.length; ci++) {
            var candidate = candidates[ci];
            if (!candidate || candidate.passId !== passId) continue;
            if (String(candidate.pageIndex) !== String(pageIndex)) continue;
            if (_sourceSetKey(candidate.sourceObjectIds || []) === sourceKey) return true;
        }
        return false;
    }
    function hasInlineDirectChildShellOwner(pageIndex, itemInfo) {
        var sourceId = itemInfo ? itemInfo.id : null;
        if (sourceId === null || sourceId === undefined) return false;
        for (var ci = 0; candidates && ci < candidates.length; ci++) {
            var candidate = candidates[ci];
            if (!candidate || candidate.passId !== "pass.inline_objects") continue;
            if (String(candidate.pageIndex) !== String(pageIndex)) continue;
            if (candidate.slotRole !== "direct_child_shell_slot"
                    && candidate.compositeRole !== "direct_child_shell_slot") continue;
            if (candidate.visualSourceObjectIds && candidate.visualSourceObjectIds.length > 0
                    && !_sourceIdsContain(candidate.visualSourceObjectIds || [], sourceId)) {
                continue;
            }
            if ((!candidate.visualSourceObjectIds || candidate.visualSourceObjectIds.length === 0)
                    && itemInfo.hasText === true) {
                continue;
            }
            if (!_sourceIdsContain(candidate.sourceObjectIds || [], sourceId)
                    && !_sourceIdsContain(candidate.exportSourceObjectIds || [], sourceId)) {
                continue;
            }
            return true;
        }
        return false;
    }
    for (var i = 0; sourceItems && i < sourceItems.length; i++) {
        var itemInfo = sourceItems[i];
        if (!itemInfo || itemInfoKind(itemInfo) !== "TextFrame") continue;
        if (itemInfo.textFrameClass !== "editable") continue;
        if (itemInfo.id === null || itemInfo.id === undefined) continue;
        var id = itemInfo.id;
        if (!itemInfoHasTextFrameShellStyle(itemInfo)) continue;
        var sourceIds = [id];
        var inlineFlow = itemInfoIsInlineFlow(itemInfo);
        var passId = inlineFlow ? "pass.inline_objects" : "pass.editable_textframe_visual_shells";
        if (hasCandidate(passId, itemInfo.pageIndex, sourceIds)) continue;
        if (hasInlineDirectChildShellOwner(itemInfo.pageIndex, itemInfo)) continue;
        var candidateId = _candidateId(passId, id, itemInfo.pageIndex)
                + (inlineFlow ? ".inline_textframe_shell" : "");
        candidates.push({
            candidateId: candidateId,
            passId: passId,
            sourceObjectIds: sourceIds,
            executionSourceObjectIds: sourceIds,
            primarySourceObjectId: id,
            pageIndex: itemInfo.pageIndex,
            kind: "TextFrame",
            unit: inlineFlow ? "INLINE_OBJECT" : "TEXT_FRAME",
            mode: "TEXTLESS_CANDIDATE",
            candidatePurpose: inlineFlow ? "INLINE_CANDIDATE" : "SHELL_CANDIDATE",
            bounds: itemInfo.bounds || null,
            parentId: itemInfo.parentId,
            parentKind: itemInfo.parentKind,
            anchoredPosition: itemInfo.anchoredPosition,
            storyAnchorPlacement: itemInfo.storyAnchorPlacement,
            composite: false,
            compositeRole: inlineFlow
                    ? "inline_visible_text_frame_shell"
                    : "textframe_style_shell_slot",
            slotRole: inlineFlow
                    ? "inline_text_frame_shell_slot"
                    : "direct_child_shell_slot",
            exportSourceObjectIds: sourceIds,
            exportTargetObjectId: id,
            inlineAnchorSourceObjectId: inlineFlow ? id : null,
            inlineSourceTreeClosed: inlineFlow,
            inlineFlowSourceObjectIds: inlineFlow ? sourceIds : [],
            hiddenVisualSourceObjectIds: [],
            visualSourceObjectIds: sourceIds,
            styleSourceObjectIds: sourceIds,
            editableTextFrameIds: sourceIds,
            hiddenTextFrameIds: sourceIds,
            requiresTextHidden: true,
            textOwner: "hwpx_tf",
            containsEditableText: false,
            completePngTextAllowed: false,
            ownershipSlot: "SHELL_SLOT",
            materialization: "EXTRACTED_PNG_VECTOR",
            textAction: "DROP_TEXT",
            visualAction: "PLACE_TEXT_SHELL",
            visualLayer: "LABEL_BACKDROP",
            placement: inlineFlow ? "INLINE" : "FLOATING",
            coordinateSpace: inlineFlow ? "STORY_FLOW" : "PAGE",
            zOrder: itemInfo.zOrder,
            required: false,
            reason: inlineFlow
                    ? "inline_visible_text_frame_shell_from_source_style"
                    : "textframe_style_shell_from_source_style"
        });
        if (candidateSeen) {
            candidateSeen[passId + "|page:" + itemInfo.pageIndex
                    + "|src:" + _sourceSetKey(sourceIds)] = true;
        }
    }
}
