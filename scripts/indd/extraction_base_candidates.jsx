/*
 * Base extraction candidate planner for extract_indd.jsx.
 *
 * This module owns the first pass over sourceItems and emits the initial legacy
 * candidates. It must only describe source ownership candidates; later stages
 * decide canonical ObjectPlan ownership and executors only execute that plan.
 */

function _appendBaseExtractionCandidates(ctx, sourceItems, sourceIndex, sourceClusterIndex, candidates, candidateSeen) {
    var basePerfStartedAt = (new Date()).getTime();
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
        cacheHits: {},
        cacheMisses: {},
        broaderDecorationScannedSetCount: 0,
        broaderDecorationIndexedSetCount: 0,
        lastLabel: "start",
        lastItemIndex: -1,
        elapsedMs: 0
    };

    function basePerfNow() {
        return (new Date()).getTime();
    }

    function basePerfAdd(mapName, key, value) {
        var map = basePerfStats[mapName];
        if (!map) return;
        if (!map[key]) map[key] = 0;
        map[key] += value;
    }

    function basePerfCall(name, startedAt) {
        basePerfAdd("helperCalls", name, 1);
        basePerfAdd("helperMs", name, Math.max(0, basePerfNow() - startedAt));
    }

    function basePerfCache(name, hit) {
        basePerfAdd(hit ? "cacheHits" : "cacheMisses", name, 1);
    }

    function basePerfMarker(name) {
        try { _marker(ctx.outputDir, name); } catch (eMarker) {}
    }

    function basePerfWrite(label, itemIndex) {
        try {
            basePerfStats.lastLabel = label;
            basePerfStats.lastItemIndex = itemIndex;
            basePerfStats.candidateCountAtEnd = candidates ? candidates.length : 0;
            basePerfStats.elapsedMs = Math.max(0, basePerfNow() - basePerfStartedAt);
            writeJson(ctx.outputDir + "/_base_candidate_perf.json", basePerfStats);
        } catch (eBasePerfWrite) {}
    }

    basePerfMarker("03d05a_base_enter");

    function sourceHasStoryFlowAnchor(sourceId) {
        if (sourceId === null || sourceId === undefined) return false;
        var current = sourceClusterIndex && sourceClusterIndex.sourceInfo
                ? sourceClusterIndex.sourceInfo(sourceId)
                : null;
        for (var depth = 0; depth < 64 && current; depth++) {
            var parentKind = String(current.parentKind || "");
            if (parentKind === "Character" || parentKind === "InsertionPoint") {
                return _isInlineFlowItemBySourceInfo(current);
            }
            if (parentKind === "Cell" || parentKind === "Story") {
                return true;
            }
            var parentId = current.parentId;
            if (parentId === null || parentId === undefined) return false;
            current = sourceClusterIndex.sourceInfo(parentId);
        }
        return false;
    }

    function sourceItemHasChildren(sourceId) {
        if (sourceId === null || sourceId === undefined) return false;
        var info = sourceIndex.sourceInfo(sourceId);
        return !!(info && info.hasChildren);
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
        var current = sourceIndex.sourceInfo(sourceId);
        for (var depth = 0; depth < 64 && current; depth++) {
            if (String(current.id) === String(rootSourceId)) return false;
            if (sourceIndex.hasPlacedVisualInSubtree(current.id)) return true;
            if (current.parentId === null || current.parentId === undefined) return false;
            current = sourceIndex.sourceInfo(current.parentId);
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
        var kind = itemInfo.kind;
        if (kind === "Group") return sourceItemHasChildren(itemInfo.id);
        if (kind === "Rectangle" || kind === "Oval"
                || kind === "Polygon" || kind === "GraphicLine") {
            if (sourceItemHasChildren(itemInfo.id)) return true;
            return false;
        }
        return false;
    }

    function shouldRasterizeLeafVectorShell(itemInfo) {
        if (!itemInfo) return false;
        var kind = String(itemInfo.kind || "");
        if (kind !== "Polygon" && !isUnsafeNativeGraphicLineShell(itemInfo)) return false;
        if (sourceItemHasChildren(itemInfo.id)) return false;
        if (itemInfo.hasPlacedVisual === true) return false;
        if (sourceIndex.hasPlacedVisualInSubtree(itemInfo.id)) return false;
        return sourceIndex.hasCandidateVectorPaint(itemInfo.id) === true;
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

    var executableShellMaterialBySourceSetKey = {};

    function sourceSetHasExecutableShellMaterial(sourceIds) {
        var startedAt = basePerfNow();
        var cacheKey = sourceSetKeyInGivenOrder(sourceIds || []);
        if (cacheKey && executableShellMaterialBySourceSetKey.hasOwnProperty(cacheKey)) {
            basePerfCache("sourceSetHasExecutableShellMaterial", true);
            basePerfCall("sourceSetHasExecutableShellMaterial", startedAt);
            return executableShellMaterialBySourceSetKey[cacheKey];
        }
        basePerfCache("sourceSetHasExecutableShellMaterial", false);
        var result = _candidateHasExecutableShellMaterial({
            candidatePurpose: "SHELL_CANDIDATE",
            sourceObjectIds: sourceIds || [],
            exportSourceObjectIds: sourceIds || []
        }, baseCandidateSourceInfoById, baseCandidateChildIdsByParentId);
        if (cacheKey) executableShellMaterialBySourceSetKey[cacheKey] = result;
        basePerfCall("sourceSetHasExecutableShellMaterial", startedAt);
        return result;
    }

    var sourceSetContainsAllByKey = {};

    function sourceSetKeyInGivenOrder(ids) {
        if (!ids || ids.length === 0) return "";
        var out = [];
        for (var i = 0; i < ids.length; i++) out.push(String(Number(ids[i])));
        return out.join(",");
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
            if (sourceIndex.sourceIsInPagePlane
                    && !sourceIndex.sourceIsInPagePlane(src.id, pageIndex)) return false;
            var siblings = baseCandidateChildIdsByParentId[String(src.parentId)] || [];
            for (var si = 0; si < siblings.length; si++) {
                if (String(siblings[si]) === String(currentId)) continue;
                var sibling = baseCandidateSourceInfoById[String(siblings[si])];
                if (sibling && sourceIndex.sourceIsInPagePlane
                        && !sourceIndex.sourceIsInPagePlane(sibling.id, pageIndex)) continue;
                if (sourceIsEmptyCarrierTextFrameForPage(sibling, pageIndex)) return true;
            }
            currentId = src.parentId;
        }
        return false;
    }

    var clippedPlacedCarrierSiblingShellByKey = {};

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
        if (!sourceIndex.hasPlacedVisualInSubtree(itemInfo.id)) {
            clippedPlacedCarrierSiblingShellByKey[cacheKey] = false;
            basePerfCall("sourceIsClippedPlacedCarrierSiblingShell", startedAt);
            return false;
        }
        if (!sourceItemHasChildren(itemInfo.id)) {
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
        var result = sourceOrAncestorHasDirectEmptyCarrierTextFrameSiblingForPage(itemInfo.id, pageIndex);
        clippedPlacedCarrierSiblingShellByKey[cacheKey] = result;
        basePerfCall("sourceIsClippedPlacedCarrierSiblingShell", startedAt);
        return result;
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

    function sourceIsBackgroundVectorCandidate(info, sourceId) {
        if (!info) return false;
        var kindName = String(info.kind || "");
        if (kindName !== "Rectangle" && kindName !== "Oval"
                && kindName !== "Polygon" && kindName !== "GraphicLine") {
            return false;
        }
        if (info.hasChildren === true || info.hasPlacedVisual === true) return false;
        if (sourceIndex.hasPlacedVisualInSubtree(sourceId)) return false;
        if (sourceIndex.hasCandidateVectorPaint(sourceId) !== true) return false;
        return sourceLayerNameIsBackground(info.layerName);
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
        if (sourceIndex.hasCandidateVectorPaint(sourceId) !== true) {
            directSiblingTextShellOwnerByKey[cacheKey] = false;
            basePerfCall("sourceHasDirectSiblingTextShellOwner", startedAt);
            return false;
        }
        if (src.parentId === null || src.parentId === undefined) {
            directSiblingTextShellOwnerByKey[cacheKey] = false;
            basePerfCall("sourceHasDirectSiblingTextShellOwner", startedAt);
            return false;
        }
        if (sourceIndex.sourceIsInPagePlane
                && !sourceIndex.sourceIsInPagePlane(sourceId, pageIndex)) {
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
            if (sourceIndex.sourceIsInPagePlane
                    && !sourceIndex.sourceIsInPagePlane(siblingId, pageIndex)) continue;
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

    function clipParentShellOwnerForBase(sourceId, pageIndex) {
        var cacheKey = String(sourceId) + "|" + String(pageIndex);
        if (clipParentShellOwnerForBaseByKey.hasOwnProperty(cacheKey)) {
            basePerfCache("clipParentShellOwnerForBase", true);
            return clipParentShellOwnerForBaseByKey[cacheKey] || null;
        }
        basePerfCache("clipParentShellOwnerForBase", false);
        var startedAt = basePerfNow();
        var clipParentId = null;
        try {
            clipParentId = sourceIndex.clipCarryingParentIdOfSource(sourceId);
        } catch (eClipOwnerId) {
            clipParentId = null;
        }
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
        var clipParentInfo = sourceIndex.sourceInfo(clipParentId);
        if (!clipParentInfo || clipParentInfo.hiddenLayer === true) {
            clipParentShellOwnerForBaseByKey[cacheKey] = false;
            basePerfCall("clipParentShellOwnerForBase", startedAt);
            return null;
        }
        if (sourceIndex.sourceIsInPagePlane
                && !sourceIndex.sourceIsInPagePlane(clipParentId, pageIndex)) {
            clipParentShellOwnerForBaseByKey[cacheKey] = false;
            basePerfCall("clipParentShellOwnerForBase", startedAt);
            return null;
        }
        if (!shouldEmitDecorationSourceCandidate(clipParentInfo)) {
            clipParentShellOwnerForBaseByKey[cacheKey] = false;
            basePerfCall("clipParentShellOwnerForBase", startedAt);
            return null;
        }
        var ownerSourceIds = null;
        try {
            ownerSourceIds = sourceIndex.pageLocalSourceObjectIds(clipParentId, pageIndex);
        } catch (eClipOwnerSourceIds) {
            ownerSourceIds = null;
        }
        ownerSourceIds = ownerSourceIds || [clipParentId];
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
        var src = sourceIndex.sourceInfo(sourceId);
        var parentId = src ? src.parentId : null;
        var bestOwner = null;
        for (var depth = 0; depth < 32 && parentId !== null && parentId !== undefined; depth++) {
            var parentInfo = sourceIndex.sourceInfo(parentId);
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
            if (sourceIndex.sourceIsInPagePlane
                    && !sourceIndex.sourceIsInPagePlane(parentInfo.id, pageIndex)) {
                ancestorShellOwnerForBaseByKey[cacheKey] = false;
                basePerfCall("ancestorShellOwnerForBase", startedAt);
                return null;
            }
            if (shouldEmitDecorationSourceCandidate(parentInfo)
                    && !sourceIndex.hasPlacedVisualInSubtree(parentInfo.id)) {
                var ownerSourceIds = null;
                try {
                    ownerSourceIds = sourceIndex.pageLocalSourceObjectIds(parentInfo.id, pageIndex);
                } catch (eAncestorOwnerSourceIds) {
                    ownerSourceIds = null;
                }
                ownerSourceIds = ownerSourceIds || [parentInfo.id];
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
        var owner = clipParentShellOwnerForBase(sourceId, pageIndex);
        if (!owner) owner = ancestorShellOwnerForBase(sourceId, pageIndex);
        if (!owner) return attrs;
        attrs.clipParentShellOwnerSourceId = owner.sourceObjectId;
        attrs.clipParentShellOwnerSourceObjectIds = owner.sourceObjectIds;
        return attrs;
    }

    function sourceHasClipParentShellOwner(sourceId, pageIndex) {
        var startedAt = basePerfNow();
        var result = clipParentShellOwnerForBase(sourceId, pageIndex) !== null
                || ancestorShellOwnerForBase(sourceId, pageIndex) !== null;
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
        var current = sourceIndex.sourceInfo(currentId);
        var guard = 0;
        while (current && current.parentId !== null && current.parentId !== undefined && guard < 32) {
            var parent = sourceIndex.sourceInfo(current.parentId);
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

    for (var predeclareIndex = 0; predeclareIndex < sourceItems.length; predeclareIndex++) {
        basePerfStats.predeclareItemCount++;
        if (predeclareIndex > 0 && predeclareIndex % 250 === 0) {
            basePerfWrite("predeclare", predeclareIndex);
        }
        var predeclareInfo = sourceItems[predeclareIndex];
        if (!predeclareInfo || predeclareInfo.id === null || predeclareInfo.id === undefined) continue;
        if (predeclareInfo.hiddenLayer === true) continue;
        if (!shouldEmitDecorationSourceCandidate(predeclareInfo)) continue;
        if (sourceIndex.hasPlacedVisualInSubtree(predeclareInfo.id)) continue;
        var predeclarePageIndexes = sourceIndex.candidatePageIndexes(predeclareInfo.id);
        if (!predeclarePageIndexes || predeclarePageIndexes.length === 0) continue;
        for (var predeclarePageCursor = 0; predeclarePageCursor < predeclarePageIndexes.length; predeclarePageCursor++) {
            basePerfStats.predeclarePageCount++;
            var predeclarePageIndex = predeclarePageIndexes[predeclarePageCursor];
            var predeclareSourceIds = null;
            try {
                predeclareSourceIds = sourceIndex.pageLocalSourceObjectIds(predeclareInfo.id, predeclarePageIndex);
            } catch (ePredeclareSourceIds) {
                predeclareSourceIds = null;
            }
            predeclareSourceIds = predeclareSourceIds || [predeclareInfo.id];
            if (!sourceSetHasExecutableShellMaterial(predeclareSourceIds)) continue;
            predeclareDecorationSourceSet(predeclarePageIndex, predeclareSourceIds);
        }
    }

    basePerfMarker("03d05c_base_predeclare_done");
    basePerfWrite("predeclare_done", sourceItems.length);

    for (var i = 0; i < sourceItems.length; i++) {
        basePerfStats.mainItemCount++;
        if (i > 0 && i % 100 === 0) {
            basePerfWrite("main_loop", i);
        }
        var itemInfo = sourceItems[i];
        var kind = itemInfo.kind;
        var id = itemInfo.id;
        if (id === null) continue;
        var item = sourceIndex.domItem(id);
        if (!item) continue;

        var extractionPageIndexes = sourceIndex.candidatePageIndexes(id);
        if (!extractionPageIndexes || extractionPageIndexes.length === 0) continue;
        if (itemInfo.hiddenLayer === true) continue;
        if (kind !== "TextFrame" && sourceHasStoryFlowAnchor(id)) continue;

        for (var extractionPageCursor = 0; extractionPageCursor < extractionPageIndexes.length; extractionPageCursor++) {
            basePerfStats.mainPageCount++;
            var extractionPageIndex = extractionPageIndexes[extractionPageCursor];

            if (kind === "Rectangle" || kind === "Oval" || kind === "Polygon") {
                var directSiblingTextShellOwned = sourceHasDirectSiblingTextShellOwner(id, extractionPageIndex);
                var ownedByClipParentShell = sourceHasClipParentShellOwner(id, extractionPageIndex);
                if (!directSiblingTextShellOwned
                        && !ownedByClipParentShell
                        && sourceIsBackgroundVectorCandidate(itemInfo, id)) {
                    var backgroundVectorSourceIds = null;
                    try {
                        backgroundVectorSourceIds = sourceIndex.pageLocalSourceObjectIds(id, extractionPageIndex);
                    } catch (eBackgroundVectorSourceIds) {
                        backgroundVectorSourceIds = [id];
                    }
                    if (hasBroaderDecorationSourceSet(extractionPageIndex, backgroundVectorSourceIds || [id])) continue;
                    _pushExtractionCandidate(candidates, candidateSeen, "pass.decoration_groups", item, candidateAttrsForInfo(itemInfo, {
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
                    }));
                    recordDecorationSourceSet(extractionPageIndex, backgroundVectorSourceIds || [id]);
                    continue;
                }
                if (sourceIndex.hasPlacedVisual(id) && !ownedByClipParentShell) {
                    _pushExtractionCandidate(candidates, candidateSeen, "pass.image_placed_frames", item, markClipParentShellOwner(candidateAttrsForInfo(itemInfo, {
                        pageIndex: extractionPageIndex,
                        unit: "ITEM",
                        mode: "ORIGINAL_VISUAL",
                        candidatePurpose: "CONTENT_CANDIDATE"
                    }), id, extractionPageIndex));
                    try {
                        if (itemInfo.parentKind === "Group") {
                            var imageParentId = topVisualOnlyCompositeRoot(itemInfo.parentId, extractionPageIndex);
                            var imageParentItem = sourceIndex.domItem(imageParentId);
                            if (imageParentItem
                                    && !sourceIsInsideClippedPlacedCarrierSiblingShell(imageParentId, extractionPageIndex)
                                    && !sourceIndex.hasEditableTextDescendantOutsideSubtree(imageParentId, id)) {
                                var imageGroupSourceIds = null;
                                try { imageGroupSourceIds = sourceIndex.pageLocalSourceObjectIds(imageParentId, extractionPageIndex); } catch (eImageGroupSourceIds) {}
                                var imageParentInfo = sourceIndex.sourceInfo(imageParentId);
                                _pushExtractionCandidate(candidates, candidateSeen, "pass.image_textless_groups", imageParentItem, markClipParentShellOwner(candidateAttrsForInfo(imageParentInfo, {
                                    sourceObjectIds: imageGroupSourceIds,
                                    pageIndex: extractionPageIndex,
                                    unit: "GROUP",
                                    mode: "TEXTLESS_CANDIDATE",
                                    candidatePurpose: "CONTENT_CANDIDATE",
                                    compositeRole: imageGroupSourceIds && imageGroupSourceIds.length > 1 ? "image_group_textless_source_set" : null
                                }), imageParentId, extractionPageIndex));
                            }
                        }
                    } catch (eParentImageCandidate) {}
                }
                if (!directSiblingTextShellOwned
                        && !ownedByClipParentShell
                        && sourceIsClippedPlacedCarrierSiblingShell(itemInfo, extractionPageIndex)) {
                    var clippedCarrierShellSourceIds = null;
                    try {
                        clippedCarrierShellSourceIds = sourceIndex.pageLocalSourceObjectIds(id, extractionPageIndex);
                    } catch (eClippedCarrierShellSourceIds) {
                        clippedCarrierShellSourceIds = [id];
                    }
                    if (hasBroaderDecorationSourceSet(extractionPageIndex, clippedCarrierShellSourceIds || [id])) continue;
                    _pushExtractionCandidate(candidates, candidateSeen, "pass.decoration_groups", item, candidateAttrsForInfo(itemInfo, {
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
                    }));
                    recordDecorationSourceSet(extractionPageIndex, clippedCarrierShellSourceIds || [id]);
                }

                if (!directSiblingTextShellOwned && !ownedByClipParentShell && shouldRasterizeLeafVectorShell(itemInfo)) {
                    if (hasBroaderDecorationSourceSet(extractionPageIndex, [id])) continue;
                    _pushExtractionCandidate(candidates, candidateSeen, "pass.decoration_groups", item, candidateAttrsForInfo(itemInfo, {
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
                    }));
                    recordDecorationSourceSet(extractionPageIndex, [id]);
                    continue;
                } else if (!ownedByClipParentShell
                        && sourceIndex.hasCandidateVectorPaint(id) === true
                        && !sourceIndex.hasPlacedVisualInSubtree(id)
                        && !sourceItemHasChildren(id)) {
                    if (hasBroaderDecorationSourceSet(extractionPageIndex, [id])) continue;
                    _pushExtractionCandidate(candidates, candidateSeen, "pass.vector_shape_frames", item, markClipParentShellOwner(candidateAttrsForInfo(itemInfo, {
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
                    }), id, extractionPageIndex));
                }
            }

            if (kind === "GraphicLine"
                    && !sourceHasClipParentShellOwner(id, extractionPageIndex)
                    && sourceIndex.hasCandidateVectorPaint(id) === true) {
                if (shouldRasterizeLeafVectorShell(itemInfo)) {
                    if (hasBroaderDecorationSourceSet(extractionPageIndex, [id])) continue;
                    _pushExtractionCandidate(candidates, candidateSeen, "pass.decoration_groups", item, candidateAttrsForInfo(itemInfo, {
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
                    }));
                    recordDecorationSourceSet(extractionPageIndex, [id]);
                    continue;
                }
                if (hasBroaderDecorationSourceSet(extractionPageIndex, [id])) continue;
                _pushExtractionCandidate(candidates, candidateSeen, "pass.vector_shape_frames", item, markClipParentShellOwner(candidateAttrsForInfo(itemInfo, {
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
                }), id, extractionPageIndex));
            }

            if (kind === "Group" || kind === "Rectangle" || kind === "Oval"
                    || kind === "Polygon" || kind === "GraphicLine") {
                if (!sourceIndex.hasPlacedVisualInSubtree(id)) {
                    var clipParentForDecoId = sourceIndex.clipCarryingParentIdOfSource(id);
                    var clipParentForDeco = clipParentForDecoId !== null ? sourceIndex.domItem(clipParentForDecoId) : null;
                    var clipParentInfo = clipParentForDecoId !== null ? sourceIndex.sourceInfo(clipParentForDecoId) : null;
                    if (clipParentForDeco && clipParentInfo && clipParentInfo.hiddenLayer !== true
                            && !sourceHasClipParentShellOwner(clipParentForDecoId, extractionPageIndex)
                            && !sourceIndex.hasPlacedVisualInSubtree(clipParentForDecoId)
                            && shouldEmitDecorationSourceCandidate(clipParentInfo)) {
                        var clipParentSourceIds = null;
                        try {
                            clipParentSourceIds = sourceIndex.pageLocalSourceObjectIds(
                                    clipParentForDecoId, extractionPageIndex);
                        } catch (eClipParentSourceIds) {}
                        clipParentSourceIds = clipParentSourceIds || [clipParentForDecoId];
                        if (hasDecorationSourceSet(extractionPageIndex, clipParentSourceIds)) continue;
                        if (hasBroaderDecorationSourceSet(extractionPageIndex, clipParentSourceIds)) continue;
                        if (!sourceSetHasExecutableShellMaterial(clipParentSourceIds)) continue;
                        _pushExtractionCandidate(candidates, candidateSeen, "pass.decoration_groups", clipParentForDeco, candidateAttrsForInfo(clipParentInfo, {
                            sourceObjectIds: clipParentSourceIds,
                            pageIndex: extractionPageIndex,
                            unit: "GROUP_OR_ITEM",
                            mode: "TEXTLESS_CANDIDATE",
                            candidatePurpose: "SHELL_CANDIDATE",
                            compositeRole: clipParentSourceIds && clipParentSourceIds.length > 1 ? "clip_parent_source_set" : null,
                            suffix: "clip_parent"
                        }));
                        recordDecorationSourceSet(extractionPageIndex, clipParentSourceIds);
                    } else if (!sourceHasClipParentShellOwner(id, extractionPageIndex)
                            && shouldEmitDecorationSourceCandidate(itemInfo)) {
                        var decoSourceIds = null;
                        try { decoSourceIds = sourceIndex.pageLocalSourceObjectIds(id, extractionPageIndex); } catch (eDecoSourceIds) {}
                        decoSourceIds = decoSourceIds || [id];
                        if (hasBroaderDecorationSourceSet(extractionPageIndex, decoSourceIds)) continue;
                        var decoExportSourceIds = shellExportSourceIdsForSourceSet(id, decoSourceIds);
                        if (!sourceSetHasExecutableShellMaterial(decoExportSourceIds)) continue;
                        _pushExtractionCandidate(candidates, candidateSeen, "pass.decoration_groups", item, candidateAttrsForInfo(itemInfo, {
                            sourceObjectIds: decoSourceIds,
                            exportSourceObjectIds: decoExportSourceIds,
                            visualSourceObjectIds: decoExportSourceIds,
                            pageIndex: extractionPageIndex,
                            unit: "GROUP_OR_ITEM",
                            mode: "TEXTLESS_CANDIDATE",
                            candidatePurpose: "SHELL_CANDIDATE",
                            compositeRole: decoSourceIds && decoSourceIds.length > 1 ? "decoration_group_source_set" : null
                        }));
                        recordDecorationSourceSet(extractionPageIndex, decoSourceIds);
                    }
                }
            }

            if (kind === "Rectangle" || kind === "Oval" || kind === "Polygon") {
                try {
                    if (sourceItemHasChildren(id)
                            && !sourceIndex.hasPlacedVisual(id)
                            && !sourceHasClipParentShellOwner(id, extractionPageIndex)
                            && !sourceIsClippedPlacedCarrierSiblingShell(itemInfo, extractionPageIndex)) {
                        var complexEditableTextIds = [];
                        try { complexEditableTextIds = sourceIndex.textFrameIdsInSubtree(id, true, true); } catch (eComplexTextIds) {}
                        if (complexEditableTextIds && complexEditableTextIds.length > 0) {
                            continue;
                        }
                        var complexSourceIds = null;
                        try { complexSourceIds = sourceIndex.pageLocalSourceObjectIds(id, extractionPageIndex); } catch (eComplexSourceIds) {}
                        if (hasDecorationSourceSet(extractionPageIndex, complexSourceIds || [id])) continue;
                        if (hasBroaderDecorationSourceSet(extractionPageIndex, complexSourceIds || [id])) continue;
                        var complexVisualSourceIds = [];
                        try { complexVisualSourceIds = sourceIndex.placedVisualSourceObjectIdsInSubtree(id); } catch (eComplexVisualSourceIds) {}
                        var complexExportSourceIds = complexSourceIds && complexSourceIds.length > 0
                                ? complexSourceIds
                                : (complexVisualSourceIds && complexVisualSourceIds.length > 0 ? complexVisualSourceIds : [id]);
                        _pushExtractionCandidate(candidates, candidateSeen, "pass.complex_graphic_frames", item, markClipParentShellOwner(candidateAttrsForInfo(itemInfo, {
                            sourceObjectIds: complexSourceIds,
                            exportSourceObjectIds: complexExportSourceIds,
                            exportTargetObjectId: id,
                            visualSourceObjectIds: complexExportSourceIds,
                            pageIndex: extractionPageIndex,
                            unit: "GROUP",
                            mode: "TEXTLESS_CANDIDATE",
                            candidatePurpose: "CONTENT_CANDIDATE",
                            compositeRole: complexSourceIds && complexSourceIds.length > 1 ? "complex_graphic_source_set" : null
                        }), id, extractionPageIndex));
                    }
                } catch (eComplexCandidate) {}
            }

            if (kind === "TextFrame") {
                var hasShellPaint = itemInfo.hasVisibleFill === true || itemInfo.hasVisibleStroke === true;
                if (hasShellPaint) {
                    var tfShellSourceIds = [id];
                    _pushExtractionCandidate(candidates, candidateSeen, "pass.editable_textframe_visual_shells", item, candidateAttrsForInfo(itemInfo, {
                        sourceObjectIds: tfShellSourceIds,
                        exportSourceObjectIds: tfShellSourceIds,
                        visualSourceObjectIds: tfShellSourceIds,
                        styleSourceObjectIds: tfShellSourceIds,
                        editableTextFrameIds: tfShellSourceIds,
                        hiddenTextFrameIds: tfShellSourceIds,
                        requiresTextHidden: true,
                        textOwner: "hwpx_tf",
                        pageIndex: extractionPageIndex,
                        unit: "TEXT_FRAME",
                        mode: "TEXTLESS_CANDIDATE",
                        candidatePurpose: "SHELL_CANDIDATE",
                        compositeRole: "textframe_style_shell_slot",
                        slotRole: "direct_child_shell_slot"
                    }));
                }
            }
        }
    }
    basePerfMarker("03d05d_base_main_done");
    basePerfWrite("done", sourceItems.length);
}

function _appendEditableTextFrameStyleShellCandidatesFromSourceItems(sourceItems, candidates, candidateSeen) {
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
    for (var i = 0; sourceItems && i < sourceItems.length; i++) {
        var itemInfo = sourceItems[i];
        if (!itemInfo || String(itemInfo.kind || "") !== "TextFrame") continue;
        if (itemInfo.textFrameClass !== "editable") continue;
        if (itemInfo.hasVisibleFill !== true && itemInfo.hasVisibleStroke !== true) continue;
        if (itemInfo.id === null || itemInfo.id === undefined) continue;
        var id = itemInfo.id;
        var sourceIds = [id];
        if (hasCandidate("pass.editable_textframe_visual_shells", itemInfo.pageIndex, sourceIds)) continue;
        var candidateId = _candidateId("pass.editable_textframe_visual_shells", id, itemInfo.pageIndex);
        candidates.push({
            candidateId: candidateId,
            passId: "pass.editable_textframe_visual_shells",
            sourceObjectIds: sourceIds,
            primarySourceObjectId: id,
            pageIndex: itemInfo.pageIndex,
            kind: "TextFrame",
            unit: "TEXT_FRAME",
            mode: "TEXTLESS_CANDIDATE",
            candidatePurpose: "SHELL_CANDIDATE",
            bounds: itemInfo.bounds || null,
            parentId: itemInfo.parentId,
            parentKind: itemInfo.parentKind,
            anchoredPosition: itemInfo.anchoredPosition,
            storyAnchorPlacement: itemInfo.storyAnchorPlacement,
            composite: false,
            compositeRole: "textframe_style_shell_slot",
            slotRole: "direct_child_shell_slot",
            exportSourceObjectIds: sourceIds,
            exportTargetObjectId: id,
            hiddenVisualSourceObjectIds: [],
            visualSourceObjectIds: sourceIds,
            styleSourceObjectIds: sourceIds,
            editableTextFrameIds: sourceIds,
            hiddenTextFrameIds: sourceIds,
            requiresTextHidden: true,
            textOwner: "hwpx_tf",
            containsEditableText: false,
            completePngTextAllowed: false,
            zOrder: itemInfo.zOrder,
            required: false
        });
        if (candidateSeen) {
            candidateSeen["pass.editable_textframe_visual_shells|page:" + itemInfo.pageIndex
                    + "|src:" + _sourceSetKey(sourceIds)] = true;
        }
    }
}
