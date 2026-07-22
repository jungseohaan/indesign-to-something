/**
 * Extraction candidate and plan builder.
 *
 * This module assembles Stage 1 legacy candidates and extraction-plan.json.
 * It must not render PNGs or reinterpret placement during execution.
 */

// =============================================================================
// SECTION 3.5: EXTRACTION PLANNING
// E0/E1: DOM metadata scan -> extraction-plan.json.
// The plan describes candidate production only. It is not HWPX ownership.
// =============================================================================



function _hasPlacedVisual(item) {
    try { if (item.images && item.images.length > 0) return true; } catch (eImage) {}
    try { if (item.pdfs && item.pdfs.length > 0) return true; } catch (ePdf) {}
    try { if (item.epss && item.epss.length > 0) return true; } catch (eEps) {}
    return false;
}

function _hasCandidateVectorPaint(item) {
    try { if (hasVisibleFill(item)) return true; } catch (eFill) {}
    try { if (hasVisibleStroke(item)) return true; } catch (eStroke) {}
    return false;
}

function _hasPlacedVisualOrPaintInItemTree(item) {
    if (!item) return false;
    try { if (_hasPlacedVisual(item) || _hasCandidateVectorPaint(item)) return true; } catch (eSelf) {}
    try {
        var children = item.allPageItems;
        for (var i = 0; children && i < children.length; i++) {
            try {
                if (_hasPlacedVisual(children[i]) || _hasCandidateVectorPaint(children[i])) return true;
            } catch (eChild) {}
        }
    } catch (eChildren) {}
    return false;
}

function _candidateCanClaimVisibleSourceSlot(candidate) {
    if (!candidate || candidate.disabled === true) return false;
    if (candidate.suppressedByDirectChildShellSlots === true) return false;
    if (candidate.visualAction === "DROP_VISUAL") return false;
    if (candidate.materialization === "HWPX_TEXT"
            || candidate.materialization === "HWPX_TABLE_STYLE"
            || candidate.materialization === "NATIVE_SOURCE_SHAPE") {
        return false;
    }
    return true;
}

function _inlineCarrierVisualSourceObjectIds(item, itemInfo, sourceObjectIds) {
    var itemId = itemInfo && itemInfo.id !== null && itemInfo.id !== undefined
            ? itemInfo.id
            : _itemId(item);
    if (itemId === null || itemId === undefined) return [];
    var sourceSet = {};
    for (var si = 0; sourceObjectIds && si < sourceObjectIds.length; si++) {
        sourceSet[String(sourceObjectIds[si])] = true;
    }
    if (sourceObjectIds && sourceObjectIds.length > 0 && !sourceSet[String(itemId)]) return [];
    if (itemInfo) {
        var kind = String(itemInfo.kind || "");
        if (kind === "TextFrame") return [];
        if (itemInfo.visible === false || itemInfo.hiddenLayer === true) return [];
        if (itemInfo.hasPlacedVisual === true
                || itemInfo.hasVisibleFill === true
                || itemInfo.hasVisibleStroke === true
                || itemInfo.hasCandidateVectorPaint === true) {
            return [itemId];
        }
        if (kind === "Group" && sourceObjectIds && sourceObjectIds.length > 1
                && _hasPlacedVisualOrPaintInItemTree(item)) {
            return [itemId];
        }
    }
    try {
        var itemKind = item && item.constructor ? String(item.constructor.name || "") : "";
        if (itemKind === "TextFrame") return [];
        if (_hasPlacedVisualOrPaintInItemTree(item)) return [itemId];
    } catch (eVisualSource) {}
    return [];
}

function _appendTableCarrierSiblingDecorationCandidates(sourceItems, candidates, candidateSeen) {
    var appended = 0;
    var sourceById = {};
    var childrenByParent = {};
    var pageIndexesBySourceId = {};
    function backgroundCandidateSeenKey(pageIndex, sourceId) {
        return "pass.decoration_groups|page:" + String(pageIndex)
                + "|background:" + String(sourceId);
    }
    function sortedIds(ids) {
        ids = ids || [];
        ids.sort(function(a, b) { return Number(a) - Number(b); });
        return ids;
    }
    function pushId(out, seen, id) {
        if (id === null || id === undefined) return;
        var key = String(id);
        if (seen[key]) return;
        seen[key] = true;
        out.push(id);
    }
    function unionBounds(a, b) {
        if (!b || b.length < 4) return a;
        if (!a) return [b[0], b[1], b[2], b[3]];
        a[0] = Math.min(a[0], b[0]);
        a[1] = Math.min(a[1], b[1]);
        a[2] = Math.max(a[2], b[2]);
        a[3] = Math.max(a[3], b[3]);
        return a;
    }
    function boundsOverlapOrTouch(a, b) {
        if (!a || !b || a.length < 4 || b.length < 4) return false;
        var tolerance = 1.0;
        return !(a[2] < b[0] - tolerance
                || b[2] < a[0] - tolerance
                || a[3] < b[1] - tolerance
                || b[3] < a[1] - tolerance);
    }
    function connectedVisualComponents(entries) {
        var components = [];
        var visited = {};
        for (var i = 0; i < entries.length; i++) {
            if (visited[String(i)]) continue;
            var queue = [i];
            visited[String(i)] = true;
            var component = [];
            for (var qi = 0; qi < queue.length; qi++) {
                var index = queue[qi];
                var entry = entries[index];
                component.push(entry);
                for (var ni = 0; ni < entries.length; ni++) {
                    if (visited[String(ni)]) continue;
                    if (!boundsOverlapOrTouch(entry.bounds, entries[ni].bounds)) continue;
                    visited[String(ni)] = true;
                    queue.push(ni);
                }
            }
            components.push(component);
        }
        return components;
    }
    function isTableCarrierTextFrame(src) {
        if (!src || String(src.kind || "") !== "TextFrame") return false;
        if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) return false;
        if (src.textFrameClass !== "editable") return false;
        if (src.hasText === true || Number(src.textLength || 0) > 0) return false;
        return src.markerOnlyContents === true;
    }
    function sourceKind(src) {
        return String(src && (src.kind || src.type) || "");
    }
    function hasPaint(src) {
        if (!src) return false;
        return src.hasVisibleFill === true
                || src.hasVisibleStroke === true
                || src.hasCandidateVectorPaint === true;
    }
    function isPlacedContentKind(kind) {
        return kind === "Image" || kind === "PDF" || kind === "EPS";
    }
    function sourceSubtreeIds(src, seen) {
        var out = [];
        if (!src || src.id === null || src.id === undefined) return out;
        seen = seen || {};
        function visit(id) {
            var key = String(id);
            if (seen[key]) return;
            seen[key] = true;
            out.push(Number(id));
            var childList = childrenByParent[key] || [];
            for (var ci = 0; ci < childList.length; ci++) {
                if (!childList[ci] || childList[ci].id === null || childList[ci].id === undefined) continue;
                visit(childList[ci].id);
            }
        }
        visit(src.id);
        return sortedIds(out);
    }
    function sourceHasPlacedContentInTree(src, visiting) {
        if (!src) return false;
        var key = String(src.id);
        visiting = visiting || {};
        if (visiting[key]) return false;
        visiting[key] = true;
        if (isPlacedContentKind(sourceKind(src))) return true;
        if (src.hasPlacedVisual === true || src.hasPlacedVisualInSubtree === true) return true;
        var children = childrenByParent[key] || [];
        for (var i = 0; i < children.length; i++) {
            if (sourceHasPlacedContentInTree(children[i], visiting)) return true;
        }
        return false;
    }
    function isPaintOnlyTextFrameShell(src) {
        if (!src || sourceKind(src) !== "TextFrame") return false;
        if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) return false;
        if (src.hasText === true || Number(src.textLength || 0) > 0) return false;
        return hasPaint(src);
    }
    function sourceHasPaintOnlyTextFrameShellInTree(src, visiting) {
        if (!src) return false;
        var key = String(src.id);
        visiting = visiting || {};
        if (visiting[key]) return false;
        visiting[key] = true;
        if (isPaintOnlyTextFrameShell(src)) return true;
        var children = childrenByParent[key] || [];
        for (var i = 0; i < children.length; i++) {
            if (sourceHasPaintOnlyTextFrameShellInTree(children[i], visiting)) return true;
        }
        return false;
    }
    function tableCarrierSiblingVisualRole(src) {
        if (!src) return false;
        if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) return false;
        var kind = sourceKind(src);
        if (sourceHasPlacedContentInTree(src)) return "CONTENT_VISUAL_SLOT";
        if (isPaintOnlyTextFrameShell(src) || sourceHasPaintOnlyTextFrameShellInTree(src)) {
            return "SHELL_SLOT";
        }
        if (kind === "GraphicLine") return null;
        if ((kind === "Rectangle" || kind === "Oval" || kind === "Polygon")
                && !childrenByParent[String(src.id)]
                && hasPaint(src)) {
            return null;
        }
        if (kind === "Group" && hasPaint(src)) return "SHELL_SLOT";
        return null;
    }
    function candidateExists(pageIndex, sourceIds) {
        var key = _sourceSetKey(sortedIds(sourceIds.slice(0)));
        for (var ci = 0; ci < candidates.length; ci++) {
            var candidate = candidates[ci];
            if (!candidate || String(candidate.pageIndex) !== String(pageIndex)) continue;
            if (_sourceSetKey(candidate.sourceObjectIds || []) === key) return true;
            if (_sourceSetKey(candidate.exportSourceObjectIds || []) === key) return true;
        }
        return false;
    }
    function sourceAppearsOnMultiplePages(src) {
        if (!src || src.id === null || src.id === undefined) return false;
        var pages = pageIndexesBySourceId[String(src.id)] || {};
        var count = 0;
        for (var key in pages) {
            if (!pages.hasOwnProperty(key)) continue;
            count++;
            if (count > 1) return true;
        }
        return false;
    }
    function isPageWideBackgroundDecorationSibling(src) {
        if (!src) return false;
        if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) return false;
        if (String(src.kind || "") === "TextFrame") return false;
        if (src.hasPlacedVisual === true || src.hasPlacedVisualInSubtree === true) return false;
        if (src.hasVisibleFill !== true || src.hasVisibleStroke === true) return false;
        if (typeof _isBackgroundLayerName === "function" && _isBackgroundLayerName(src.layerName)) {
            return true;
        }
        return sourceAppearsOnMultiplePages(src);
    }
    function appendPageWideBackgroundDecorationCandidate(src, pageIndex) {
        if (!src || src.id === null || src.id === undefined) return false;
        if (pageIndex === null || pageIndex === undefined || Number(pageIndex) < 0) return false;
        if (!isPageWideBackgroundDecorationSibling(src)) return false;
        var sourceId = Number(src.id);
        if (isNaN(sourceId)) return false;
        if (candidateExists(pageIndex, [sourceId])) return false;
        var seenKey = backgroundCandidateSeenKey(pageIndex, sourceId);
        if (candidateSeen && candidateSeen[seenKey]) return false;
        if (candidateSeen) candidateSeen[seenKey] = true;
        candidates.push({
            candidateId: _candidateId("pass.decoration_groups", sourceId, Number(pageIndex)),
            passId: "pass.decoration_groups",
            sourceObjectIds: [sourceId],
            primarySourceObjectId: sourceId,
            pageIndex: Number(pageIndex),
            kind: src.kind || "PageWideBackgroundDecoration",
            unit: "ITEM",
            mode: "TEXTLESS_CANDIDATE",
            candidatePurpose: "SHELL_CANDIDATE",
            bounds: src.bounds || null,
            parentId: src.parentId !== undefined ? src.parentId : null,
            parentKind: src.parentKind || null,
            composite: false,
            compositeRole: "background_vector_source",
            slotRole: "background_shell_slot",
            exportSourceObjectIds: [sourceId],
            exportTargetObjectId: sourceId,
            hiddenVisualSourceObjectIds: [],
            visualSourceObjectIds: [sourceId],
            styleSourceObjectIds: [],
            ownedTextFrameIds: [],
            editableTextFrameIds: [],
            hiddenTextFrameIds: [],
            requiresTextHidden: false,
            textOwner: "none",
            containsEditableText: false,
            completePngTextAllowed: false,
            ownershipSlot: "SHELL_SLOT",
            materialization: "EXTRACTED_PNG_VECTOR",
            textAction: "DROP_TEXT",
            visualAction: "PLACE_FLOATING_PNG",
            visualLayer: "CONTENT_VISUAL",
            placement: "FLOATING",
            coordinateSpace: "PAGE",
            zOrder: src.zOrder !== undefined && src.zOrder !== null ? Number(src.zOrder) : 0,
            protectFromPageTextlessGroup: true,
            required: false,
            reason: "page_wide_background_excluded_from_table_sibling_decoration"
        });
        appended++;
        return true;
    }
    for (var i = 0; i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!src || src.id === null || src.id === undefined) continue;
        sourceById[String(src.id)] = src;
        if (src.pageIndex !== null && src.pageIndex !== undefined && Number(src.pageIndex) >= 0) {
            var sourceKey = String(src.id);
            if (!pageIndexesBySourceId[sourceKey]) pageIndexesBySourceId[sourceKey] = {};
            pageIndexesBySourceId[sourceKey][String(src.pageIndex)] = true;
        }
        if (src.parentId !== null && src.parentId !== undefined) {
            var parentKey = String(src.parentId);
            if (!childrenByParent[parentKey]) childrenByParent[parentKey] = [];
            childrenByParent[parentKey].push(src);
        }
    }
    for (var parentId in childrenByParent) {
        if (!childrenByParent.hasOwnProperty(parentId)) continue;
        var children = childrenByParent[parentId];
        var tableTextFrameIds = [];
        var tableSeen = {};
        var visualIdsByRole = {
            CONTENT_VISUAL_SLOT: [],
            SHELL_SLOT: []
        };
        var pageIndex = null;
        var b = null;
        for (var ci2 = 0; ci2 < children.length; ci2++) {
            var child = children[ci2];
            if (pageIndex === null || pageIndex === undefined) pageIndex = child.pageIndex;
            if (isTableCarrierTextFrame(child)) {
                tableTextFrameIds.push(child);
                continue;
            }
            var visualRole = tableCarrierSiblingVisualRole(child);
            if (!visualRole) continue;
            if (isPageWideBackgroundDecorationSibling(child)) {
                appendPageWideBackgroundDecorationCandidate(child, Number(child.pageIndex));
                continue;
            }
            visualIdsByRole[visualRole].push({
                id: child.id,
                bounds: child.bounds || null,
                zOrder: Number(child.zOrder || 0),
                sourceObjectIds: sourceSubtreeIds(child, {})
            });
        }
        if (tableTextFrameIds.length === 0) continue;
        if (pageIndex === null || pageIndex === undefined || Number(pageIndex) < 0) continue;
        var roles = ["CONTENT_VISUAL_SLOT", "SHELL_SLOT"];
        for (var roleIndex = 0; roleIndex < roles.length; roleIndex++) {
            var role = roles[roleIndex];
            var visualIds = visualIdsByRole[role] || [];
            if (visualIds.length === 0) continue;
            var components = connectedVisualComponents(visualIds);
            for (var cc = 0; cc < components.length; cc++) {
                var component = components[cc];
            var componentBounds = null;
            var componentZ = null;
            var sourceIds = [];
            var exportSourceIds = [];
            var localSeen = {};
            var exportSeen = {};
            for (var vi = 0; vi < component.length; vi++) {
                pushId(exportSourceIds, exportSeen, component[vi].id);
                var treeIds = component[vi].sourceObjectIds || [component[vi].id];
                for (var tsi = 0; tsi < treeIds.length; tsi++) {
                    pushId(sourceIds, localSeen, treeIds[tsi]);
                }
                componentBounds = unionBounds(componentBounds, component[vi].bounds || null);
                componentZ = componentZ === null
                        ? component[vi].zOrder
                        : Math.min(componentZ, component[vi].zOrder);
            }
            sourceIds = sortedIds(sourceIds);
            exportSourceIds = sortedIds(exportSourceIds);
            var localTableTextFrameIds = [];
            var localTableSeen = {};
            for (var ti = 0; ti < tableTextFrameIds.length; ti++) {
                var tf = tableTextFrameIds[ti];
                if (!boundsOverlapOrTouch(componentBounds, tf.bounds || null)) continue;
                pushId(localTableTextFrameIds, localTableSeen, tf.id);
            }
            if (localTableTextFrameIds.length === 0) continue;
            localTableTextFrameIds = sortedIds(localTableTextFrameIds);
            var textRangeDecorationShell = role === "SHELL_SLOT";
            var visualAction = role === "CONTENT_VISUAL_SLOT" ? "PLACE_FLOATING_PNG" : "PLACE_TEXT_SHELL";
            var visualLayer = role === "CONTENT_VISUAL_SLOT" ? "CONTENT_VISUAL" : "LABEL_BACKDROP";
            var slotRole = role === "CONTENT_VISUAL_SLOT"
                    ? "table_cell_content_visual_slot"
                    : "text_range_decoration_shell_slot";
            var candidateId = sourceIds.length > 1
                    ? _candidateCompositeId("pass.decoration_groups", Number(pageIndex), sourceIds,
                            "table_carrier_sibling_decoration")
                    : _candidateId("pass.decoration_groups", sourceIds[0], Number(pageIndex));
            var seenKey = "pass.decoration_groups|page:" + String(pageIndex)
                    + "|src:" + _sourceSetKey(sourceIds);
            if (candidateSeen && candidateSeen[seenKey]) continue;
            if (candidateSeen) candidateSeen[seenKey] = true;
            candidates.push({
                candidateId: candidateId,
                passId: "pass.decoration_groups",
                sourceObjectIds: sourceIds,
                primarySourceObjectId: sourceIds[0],
                pageIndex: Number(pageIndex),
                kind: sourceIds.length > 1 ? "TableCarrierSiblingDecorationGroup" : "TableCarrierSiblingDecoration",
                unit: sourceIds.length > 1 ? "GROUP_OR_ITEM" : "ITEM",
                mode: "TEXTLESS_CANDIDATE",
                candidatePurpose: "SHELL_CANDIDATE",
                bounds: componentBounds,
                parentId: Number(parentId),
                parentKind: sourceById[String(parentId)] ? sourceById[String(parentId)].kind || null : null,
                composite: sourceIds.length > 1,
                compositeRole: "table_carrier_sibling_decoration",
                slotRole: slotRole,
                tableDecorationRole: "table_carrier_sibling_decoration",
                exportSourceObjectIds: exportSourceIds.slice(0),
                exportTargetObjectId: exportSourceIds.length === 1 ? exportSourceIds[0] : null,
                hiddenVisualSourceObjectIds: [],
                visualSourceObjectIds: sourceIds.slice(0),
                styleSourceObjectIds: textRangeDecorationShell ? sourceIds.slice(0) : [],
                ownedTextFrameIds: [],
                editableTextFrameIds: [],
                hiddenTextFrameIds: [],
                decoratedTextFrameIds: localTableTextFrameIds,
                textRangeDecorationShell: textRangeDecorationShell,
                requiresTextHidden: false,
                textOwner: "none",
                containsEditableText: false,
                completePngTextAllowed: false,
                ownershipSlot: role,
                materialization: "EXTRACTED_PNG_VECTOR",
                textAction: "DROP_TEXT",
                visualAction: visualAction,
                visualLayer: visualLayer,
                placement: "FLOATING",
                coordinateSpace: "PAGE",
                zOrder: componentZ !== null ? componentZ : 0,
                protectFromPageTextlessGroup: true,
                required: false,
                reason: "table_carrier_sibling_decoration"
            });
            appended++;
            }
        }
    }
    return { appendedCount: appended };
}

function _appendTableCarrierTextlessShellCandidates(sourceItems, candidates, candidateSeen) {
    var appended = 0;
    var sourceById = {};
    var childrenByParent = {};
    function sortedIds(ids) {
        ids = ids || [];
        ids.sort(function(a, b) { return Number(a) - Number(b); });
        return ids;
    }
    function isTableCarrierTextFrame(src) {
        if (!src || String(src.kind || "") !== "TextFrame") return false;
        if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) return false;
        if (src.textFrameClass !== "editable") return false;
        if (src.hasTablesInStory !== true && Number(src.tableCountInStory || 0) <= 0) return false;
        if (src.hasText === true || Number(src.textLength || 0) > 0) return false;
        return src.markerOnlyContents !== false;
    }
    function buildIndexes() {
        for (var si = 0; si < sourceItems.length; si++) {
            var source = sourceItems[si];
            if (!source || source.id === null || source.id === undefined) continue;
            sourceById[String(source.id)] = source;
            if (source.parentId === null || source.parentId === undefined) continue;
            var key = String(source.parentId);
            if (!childrenByParent[key]) childrenByParent[key] = [];
            childrenByParent[key].push(source);
        }
    }
    function tableCarrierExportContainer(src) {
        if (!src || src.parentId === null || src.parentId === undefined) return null;
        var parent = sourceById[String(src.parentId)];
        if (!parent) return null;
        var parentKind = String(parent.kind || "");
        if (parentKind !== "Group"
                && parentKind !== "Rectangle"
                && parentKind !== "Polygon"
                && parentKind !== "Oval") {
            return null;
        }
        var children = childrenByParent[String(src.parentId)] || [];
        if (children.length < 2) return null;
        for (var ci = 0; ci < children.length; ci++) {
            var child = children[ci];
            if (!child) return null;
            if (child.visible === false || child.hiddenLayer === true || child.nonprinting === true) continue;
            if (String(child.kind || "") !== "TextFrame") return null;
        }
        return parent;
    }
    function exportContainerHiddenTextFrameIds(src, container) {
        var ids = [];
        if (!container) return [Number(src.id)];
        var children = childrenByParent[String(container.id)] || [];
        for (var ci = 0; ci < children.length; ci++) {
            var child = children[ci];
            if (!child || String(child.kind || "") !== "TextFrame") continue;
            if (child.visible === false || child.hiddenLayer === true || child.nonprinting === true) continue;
            ids.push(Number(child.id));
        }
        if (ids.length === 0) ids.push(Number(src.id));
        return sortedIds(ids);
    }
    buildIndexes();
    for (var i = 0; i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!isTableCarrierTextFrame(src)) continue;
        var id = Number(src.id);
        var pageIndex = Number(src.pageIndex);
        if (isNaN(id) || isNaN(pageIndex) || pageIndex < 0) continue;
        var exportContainer = tableCarrierExportContainer(src);
        var exportTargetId = exportContainer ? Number(exportContainer.id) : id;
        var sourceIds = exportContainer ? [Number(exportContainer.id), id] : [id];
        sourceIds = sourceIds.concat(sortedIds(src.tableSourceObjectIds || []));
        sourceIds = sortedIds(sourceIds);
        var hiddenTextFrameIds = exportContainerHiddenTextFrameIds(src, exportContainer);
        var exportSourceObjectIds = [exportTargetId];
        var seenKey = "pass.decoration_groups|page:" + String(pageIndex)
                + "|src:" + _sourceSetKey(sourceIds);
        if (candidateSeen && candidateSeen[seenKey]) continue;
        if (candidateSeen) candidateSeen[seenKey] = true;
        candidates.push({
            candidateId: _candidateCompositeId("pass.decoration_groups", pageIndex,
                    sourceIds, "table_carrier_textless_shell"),
            passId: "pass.decoration_groups",
            sourceObjectIds: sourceIds,
            primarySourceObjectId: id,
            pageIndex: pageIndex,
            kind: "TableCarrierTextlessShell",
            unit: "ITEM",
            mode: "TEXTLESS_CANDIDATE",
            candidatePurpose: "SHELL_CANDIDATE",
            bounds: exportContainer && exportContainer.bounds ? exportContainer.bounds : (src.bounds || null),
            parentId: src.parentId !== undefined ? src.parentId : null,
            parentKind: src.parentKind || null,
            composite: false,
            compositeRole: "table_carrier_textless_shell",
            slotRole: "shell_slot_only",
            tableDecorationRole: "table_carrier_textless_shell",
            exportSourceObjectIds: exportSourceObjectIds,
            exportTargetObjectId: exportTargetId,
            hiddenVisualSourceObjectIds: [],
            visualSourceObjectIds: exportSourceObjectIds.slice(0),
            styleSourceObjectIds: sortedIds(src.tableSourceObjectIds || []),
            ownedTextFrameIds: [],
            editableTextFrameIds: hiddenTextFrameIds.slice(0),
            hiddenTextFrameIds: hiddenTextFrameIds.slice(0),
            requiresTextHidden: hiddenTextFrameIds.length > 0,
            textOwner: "hwpx_tf",
            containsEditableText: false,
            completePngTextAllowed: false,
            ownershipSlot: "SHELL_SLOT",
            materialization: "EXTRACTED_PNG_VECTOR",
            textAction: "DROP_TEXT",
            visualAction: "PLACE_TEXT_SHELL",
            visualLayer: "LABEL_BACKDROP",
            placement: src.storyAnchorPlacement === "INLINE" ? "INLINE" : "FLOATING",
            coordinateSpace: src.storyAnchorPlacement === "INLINE" ? "STORY_FLOW" : "PAGE",
            zOrder: src.zOrder !== undefined && src.zOrder !== null ? Number(src.zOrder) : 0,
            protectFromPageTextlessGroup: true,
            required: false,
            reason: "table_carrier_textless_shell"
        });
        appended++;
    }
    return { appendedCount: appended };
}

function _candidateIsProtectedDecorationSlot(candidate) {
    if (!candidate) return false;
    if (candidate.protectFromPageTextlessGroup === true) return true;
    if (candidate.ownershipSlot === "SHELL_SLOT"
            && candidate.visualAction === "PLACE_TEXT_SHELL"
            && (candidate.textOwner === "hwpx_tf"
                || (candidate.ownedTextFrameIds && candidate.ownedTextFrameIds.length > 0)
                || (candidate.editableTextFrameIds && candidate.editableTextFrameIds.length > 0)
                || (candidate.hiddenTextFrameIds && candidate.hiddenTextFrameIds.length > 0))) {
        return true;
    }
    return candidate.compositeRole === "table_carrier_sibling_decoration"
            || candidate.reason === "table_carrier_sibling_decoration";
}

function _appendMasterCompositeExtractionCandidates(doc, ctx, candidates, seen, planCache, sourceIndex) {
    var masterCompositeClusterCache = {};

    function boundsIntersectForPlan(a, b, pad) {
        pad = pad || 0;
        try {
            return a[2] >= b[0] - pad && a[0] <= b[2] + pad &&
                   a[3] >= b[1] - pad && a[1] <= b[3] + pad;
        } catch (e) {}
        return false;
    }

    function boundsAreaForPlan(b) {
        try {
            if (!b || b.length < 4) return 0;
            var h = Math.max(0, Number(b[2]) - Number(b[0]));
            var w = Math.max(0, Number(b[3]) - Number(b[1]));
            return h * w;
        } catch (e) {}
        return 0;
    }

    function boundsIntersectionAreaForPlan(a, b) {
        try {
            if (!a || !b || a.length < 4 || b.length < 4) return 0;
            var t = Math.max(Number(a[0]), Number(b[0]));
            var l = Math.max(Number(a[1]), Number(b[1]));
            var bt = Math.min(Number(a[2]), Number(b[2]));
            var r = Math.min(Number(a[3]), Number(b[3]));
            if (bt <= t || r <= l) return 0;
            return (bt - t) * (r - l);
        } catch (e) {}
        return 0;
    }

    function boundsIntersectionForPlan(a, b) {
        try {
            if (!a || !b || a.length < 4 || b.length < 4) return null;
            var t = Math.max(Number(a[0]), Number(b[0]));
            var l = Math.max(Number(a[1]), Number(b[1]));
            var bt = Math.min(Number(a[2]), Number(b[2]));
            var r = Math.min(Number(a[3]), Number(b[3]));
            if (bt <= t || r <= l) return null;
            return [t, l, bt, r];
        } catch (e) {}
        return null;
    }

    function boundsDifferForPlan(a, b, eps) {
        try {
            if (!a || !b || a.length < 4 || b.length < 4) return true;
            eps = eps || 0.01;
            return Math.abs(Number(a[0]) - Number(b[0])) > eps
                    || Math.abs(Number(a[1]) - Number(b[1])) > eps
                    || Math.abs(Number(a[2]) - Number(b[2])) > eps
                    || Math.abs(Number(a[3]) - Number(b[3])) > eps;
        } catch (e) {}
        return true;
    }

    function boundsRelativeToPageBoundsForPlan(bounds, pageBounds) {
        try {
            if (!bounds || !pageBounds || bounds.length < 4 || pageBounds.length < 4) return null;
            return [
                Number(bounds[0]) - Number(pageBounds[0]),
                Number(bounds[1]) - Number(pageBounds[1]),
                Number(bounds[2]) - Number(pageBounds[0]),
                Number(bounds[3]) - Number(pageBounds[1])
            ];
        } catch (e) {}
        return null;
    }

    function masterEntriesStronglyOverlapForPlan(a, b, pad) {
        if (!a || !b) return false;
        if (!boundsIntersectForPlan(a.bounds, b.bounds, pad)) return false;
        var overlap = boundsIntersectionAreaForPlan(a.bounds, b.bounds);
        if (overlap <= 0.01) return false;
        var areaA = boundsAreaForPlan(a.bounds);
        var areaB = boundsAreaForPlan(b.bounds);
        var minArea = Math.min(areaA, areaB);
        if (minArea <= 0.01) return false;
        return overlap / minArea >= 0.05;
    }

    function isTopLevelMasterItemForPlan(item) {
        try {
            if (!item) return false;
            if (isOnHiddenLayer(item)) return false;
            try { if (item.visible === false) return false; } catch (eVis) {}
            try { if (item.nonprinting) return false; } catch (eNp) {}
            var p = item.parent;
            while (p) {
                var pn = "";
                try { pn = p.constructor.name; } catch (eName) { break; }
                if (pn === "Page" || pn === "Spread" || pn === "MasterSpread" || pn === "Document") return true;
                return false;
            }
        } catch (e) {}
        return false;
    }

    function sameMasterPageForPlan(a, b) {
        try {
            if (!a || !b) return false;
            if (a === b) return true;
        } catch (eSame) {}
        try { return a.id === b.id; } catch (eId) {}
        return false;
    }

    function itemOverspansMasterPageForPlan(itemBounds, masterPageBounds) {
        try {
            if (!itemBounds || !masterPageBounds || itemBounds.length < 4 || masterPageBounds.length < 4) return false;
            var pageH = Math.abs(Number(masterPageBounds[2]) - Number(masterPageBounds[0]));
            var pageW = Math.abs(Number(masterPageBounds[3]) - Number(masterPageBounds[1]));
            var h = Math.abs(Number(itemBounds[2]) - Number(itemBounds[0]));
            var w = Math.abs(Number(itemBounds[3]) - Number(itemBounds[1]));
            return pageH > 0.01 && pageW > 0.01 && (h > pageH * 1.25 || w > pageW * 1.25);
        } catch (eOverspan) {}
        return false;
    }

    function masterItemBelongsToMasterSideForPlan(item, masterPage, itemBounds, masterPageBounds, allowMasterSpreadFragment) {
        try {
            if (!item || !masterPage) return false;
            try {
                var parentPage = item.parentPage;
                if (parentPage && sameMasterPageForPlan(parentPage, masterPage)
                        && !itemOverspansMasterPageForPlan(itemBounds, masterPageBounds)) {
                    return true;
                }
            } catch (eParentPage) {}
            var cur = item.parent;
            var hop = 0;
            while (cur && hop < 10) {
                var kind = "";
                try { kind = cur.constructor.name; } catch (eKind) { break; }
                if (kind === "Page") {
                    return sameMasterPageForPlan(cur, masterPage);
                }
                if (kind === "MasterSpread" || kind === "Document") break;
                try { cur = cur.parent; } catch (eParent) { break; }
                hop++;
            }
            if (allowMasterSpreadFragment && itemBounds && masterPageBounds) {
                return boundsIntersectForPlan(itemBounds, masterPageBounds, 0.5);
            }
        } catch (e) {}
        return false;
    }

    function masterDirectChildItemsForPlan(item) {
        var out = [];
        try {
            if (!item || _itemKind(item) !== "Group") return out;
            var rootId = item.id;
            var nested = item.allPageItems;
            for (var i = 0; nested && i < nested.length; i++) {
                try {
                    var child = nested[i];
                    if (!child || child.id === undefined || child.id === null) continue;
                    var parent = child.parent;
                    if (!parent || parent.id !== rootId) continue;
                    if (_itemKind(child) === "TextFrame" && isDynamicMasterTextFrameForPlan(child)) continue;
                    if (isOnHiddenLayer(child)) continue;
                    try { if (child.visible === false) continue; } catch (eVis) {}
                    try { if (child.nonprinting) continue; } catch (eNp) {}
                    out.push(child);
                } catch (eChild) {}
            }
        } catch (e) {}
        return out;
    }

    function isDynamicMasterTextFrameForPlan(tf) {
        try {
            if (!tf || _itemKind(tf) !== "TextFrame") return false;
            var story = tf.parentStory;
            if (!story) return false;
            try {
                if (String(story.contents).indexOf("\u0018") >= 0) return true;
            } catch (eContents) {}
            try {
                var tvInst = story.textVariableInstances;
                if (tvInst && tvInst.length > 0) {
                    var stripped = "";
                    try {
                        stripped = String(story.contents)
                            .replace(/\uFEFF/g, "")
                            .replace(/\uFFFC/g, "")
                            .replace(/\u0016/g, "")
                            .replace(/\u0018/g, "")
                            .replace(/[\s\r\n]/g, "");
                    } catch (eStrip) {}
                    if (stripped.length === 0) return true;
                }
            } catch (eTv) {}
        } catch (e) {}
        return false;
    }

    function masterGroupShouldUseChildEntriesForPlan(item, bounds, pageBounds) {
        try {
            if (!item || _itemKind(item) !== "Group") return false;
            if (!bounds || !pageBounds || bounds.length < 4 || pageBounds.length < 4) return false;
            var pageH = Math.abs(Number(pageBounds[2]) - Number(pageBounds[0]));
            var pageW = Math.abs(Number(pageBounds[3]) - Number(pageBounds[1]));
            var h = Math.abs(Number(bounds[2]) - Number(bounds[0]));
            var w = Math.abs(Number(bounds[3]) - Number(bounds[1]));
            if (pageH <= 0.01 || pageW <= 0.01 || h <= 0.01 || w <= 0.01) return false;
            if (h <= pageH * 1.25 && w <= pageW * 1.25) return false;
            return masterDirectChildItemsForPlan(item).length > 0;
        } catch (e) {}
        return false;
    }

    function isPaperFillOnlyMasterMaskForPlan(item, bounds, pageBounds) {
        try {
            if (!item || !bounds || !pageBounds || bounds.length < 4 || pageBounds.length < 4) return false;
            if (!hasVisibleFill(item)) return false;
            if (hasVisibleStroke(item)) return false;
            var fillName = "";
            try { fillName = item.fillColor ? String(item.fillColor.name || "") : ""; } catch (eFill) {}
            fillName = fillName.toLowerCase();
            if (fillName !== "paper" && fillName !== "[paper]") return false;
            if (_hasPlacedVisual(item)) return false;
            try {
                var nested = item.allPageItems;
                for (var ni = 0; nested && ni < nested.length; ni++) {
                    var child = nested[ni];
                    if (!child) continue;
                    if (_hasPlacedVisual(child)) return false;
                    if (hasVisibleStroke(child)) return false;
                    if (hasVisibleFill(child)) {
                        var childFill = "";
                        try { childFill = child.fillColor ? String(child.fillColor.name || "") : ""; } catch (eChildFill) {}
                        childFill = childFill.toLowerCase();
                        if (childFill !== "paper" && childFill !== "[paper]") return false;
                    }
                }
            } catch (eNested) {}
            var pageH = Math.abs(Number(pageBounds[2]) - Number(pageBounds[0]));
            var pageW = Math.abs(Number(pageBounds[3]) - Number(pageBounds[1]));
            var h = Math.abs(Number(bounds[2]) - Number(bounds[0]));
            var w = Math.abs(Number(bounds[3]) - Number(bounds[1]));
            if (pageH <= 0.01 || pageW <= 0.01 || h <= 0.01 || w <= 0.01) return false;
            var touchesEdge = bounds[0] <= pageBounds[0] + 0.01
                    || bounds[1] <= pageBounds[1] + 0.01
                    || bounds[2] >= pageBounds[2] - 0.01
                    || bounds[3] >= pageBounds[3] - 0.01;
            return touchesEdge && (h >= pageH * 0.5 || w >= pageW * 0.5);
        } catch (e) {}
        return false;
    }

    function appendPlanMasterEntries(entries, item, bounds, pageBounds, masterPage) {
        if (isPaperFillOnlyMasterMaskForPlan(item, bounds, pageBounds)) return;
        if (masterGroupShouldUseChildEntriesForPlan(item, bounds, pageBounds)) {
            var children = masterDirectChildItemsForPlan(item);
            for (var ci = 0; ci < children.length; ci++) {
                try {
                    var cb = _itemBounds(children[ci]);
                    var childOnMasterSide = masterItemBelongsToMasterSideForPlan(children[ci], masterPage, cb, pageBounds, true);
                    if (!cb || !childOnMasterSide) continue;
                    if (isPaperFillOnlyMasterMaskForPlan(children[ci], cb, pageBounds)) continue;
                    entries.push({ item: children[ci], bounds: cb });
                } catch (eChildEntry) {}
            }
            return;
        }
        entries.push({ item: item, bounds: bounds });
    }

    function clusterPlanMasterEntries(entries) {
        var clusters = [];
        var used = [];
        var pad = 2.0;
        for (var i = 0; i < entries.length; i++) {
            if (used[i]) continue;
            var cluster = [];
            var queue = [i];
            used[i] = true;
            while (queue.length > 0) {
                var idx = queue.shift();
                cluster.push(entries[idx]);
                for (var j = 0; j < entries.length; j++) {
                    if (used[j]) continue;
                    var touches = false;
                    for (var k = 0; k < cluster.length; k++) {
                        if (masterEntriesStronglyOverlapForPlan(cluster[k], entries[j], pad)) {
                            touches = true;
                            break;
                        }
                    }
                    if (touches) {
                        used[j] = true;
                        queue.push(j);
                    }
                }
            }
            clusters.push(cluster);
        }
        return clusters;
    }

    function unionPlanEntryBounds(entries) {
        var union = null;
        for (var i = 0; i < entries.length; i++) {
            try {
                var b = entries[i].bounds;
                if (!b || b.length < 4) continue;
                if (!union) {
                    union = [b[0], b[1], b[2], b[3]];
                } else {
                    union[0] = Math.min(union[0], b[0]);
                    union[1] = Math.min(union[1], b[1]);
                    union[2] = Math.max(union[2], b[2]);
                    union[3] = Math.max(union[3], b[3]);
                }
            } catch (e) {}
        }
        return union;
    }

    function collectPlanEntrySourceIds(entries, includeTextFrames) {
        var sourceIds = [], seenIds = {};
        function addItemSource(item) {
            try {
                if (!item || item.id === undefined || item.id === null) return;
                if (!includeTextFrames && _itemKind(item) === "TextFrame") return;
                _pushUniqueId(sourceIds, seenIds, item.id);
            } catch (eAddItemSource) {}
        }
        for (var i = 0; i < entries.length; i++) {
            try {
                // Master composites are page-applied source clusters, not page-local
                // source-index bundles. Use the full InDesign source tree here so
                // Stage 1 candidate ownership and the export executor share exactly
                // the same slot source set.
                addItemSource(entries[i].item);
                var nested = null;
                try { nested = entries[i].item.allPageItems; } catch (eNestedItems) {}
                for (var ni = 0; nested && ni < nested.length; ni++) {
                    addItemSource(nested[ni]);
                }
            } catch (e) {}
        }
        sourceIds.sort(function(a, b) { return Number(a) - Number(b); });
        return sourceIds;
    }

    function collectPlanEntryTextFrameIds(entries) {
        var ids = [], seen = {};
        function addTextFrame(tf) {
            try {
                if (!tf || _itemKind(tf) !== "TextFrame") return;
                if (tf.id === undefined || tf.id === null) return;
                _pushUniqueId(ids, seen, tf.id);
            } catch (eAddTf) {}
        }
        for (var i = 0; i < entries.length; i++) {
            try {
                var item = entries[i].item;
                addTextFrame(item);
                var nested = item.allPageItems;
                for (var ni = 0; nested && ni < nested.length; ni++) {
                    addTextFrame(nested[ni]);
                }
            } catch (eEntryTextFrames) {}
        }
        ids.sort(function(a, b) { return Number(a) - Number(b); });
        return ids;
    }

    function firstPlanEntryNonTextEntry(entries) {
        for (var i = 0; i < entries.length; i++) {
            try {
                var item = entries[i].item;
                if (!item || _itemKind(item) === "TextFrame") continue;
                if (item.id !== undefined && item.id !== null) return entries[i];
            } catch (eEntryPrimary) {}
        }
        return null;
    }

    try {
        for (var pp = ctx.startPage - 1; pp < ctx.endPage && pp < doc.pages.length; pp++) {
            var page = doc.pages[pp];
            var master = null;
            try { master = page.appliedMaster; } catch (eMaster) {}
            if (!master) continue;
            var side = 0;
            try {
                var spreadPages = page.parent.pages;
                for (var spi = 0; spi < spreadPages.length; spi++) {
                    if (spreadPages[spi].id === page.id) { side = spi; break; }
                }
            } catch (eSide) {}
            var masterPageBounds = null;
            var masterPage = null;
            try { masterPage = master.pages[side]; } catch (eMasterPage) {}
            try {
                masterPageBounds = sourceIndex && sourceIndex.pageBounds
                        ? sourceIndex.pageBounds(pp)
                        : null;
            } catch (eSourcePageBounds) {
                masterPageBounds = null;
            }
            if (!masterPage || !masterPageBounds) continue;
            var cacheKey = String(master.id) + "|" + String(side);
            var cachedClusters = masterCompositeClusterCache[cacheKey];
            if (!cachedClusters) {
                var entries = [];
                var items = [];
                try { items = master.allPageItems; } catch (eItems) {}
                for (var ii = 0; ii < items.length; ii++) {
                    try {
                        var item = items[ii];
                        if (!isTopLevelMasterItemForPlan(item)) continue;
                        var b = _itemBounds(item);
                        if (!b) continue;
                        var itemOnMasterSide = masterItemBelongsToMasterSideForPlan(
                                item, masterPage, b, masterPageBounds,
                                itemOverspansMasterPageForPlan(b, masterPageBounds)
                                        || masterGroupShouldUseChildEntriesForPlan(item, b, masterPageBounds));
                        if (!itemOnMasterSide) continue;
                        appendPlanMasterEntries(entries, item, b, masterPageBounds, masterPage);
                    } catch (eItem) {}
                }
                cachedClusters = [];
                if (entries.length > 0) {
                    var clusters = clusterPlanMasterEntries(entries);
                    for (var ci = 0; ci < clusters.length; ci++) {
                        var sourceIds = collectPlanEntrySourceIds(clusters[ci], false);
                        if (sourceIds.length === 0) continue;
                        var hiddenTextFrameIds = collectPlanEntryTextFrameIds(clusters[ci]);
                        var primaryEntry = firstPlanEntryNonTextEntry(clusters[ci]);
                        var primarySourceObjectId = primaryEntry && primaryEntry.item
                                ? primaryEntry.item.id
                                : null;
                        if (primarySourceObjectId === null || primarySourceObjectId === undefined) continue;
                        var clusterBounds = unionPlanEntryBounds(clusters[ci]) || masterPageBounds;
                        var visualSourceBounds = primaryEntry && primaryEntry.bounds
                                ? primaryEntry.bounds
                                : clusterBounds;
                        var absoluteVisibleBounds = boundsIntersectionForPlan(visualSourceBounds, masterPageBounds)
                                || visualSourceBounds;
                        var visibleClusterBounds = boundsRelativeToPageBoundsForPlan(
                                absoluteVisibleBounds, masterPageBounds) || absoluteVisibleBounds;
                        var renderSourceBounds = boundsRelativeToPageBoundsForPlan(
                                visualSourceBounds, masterPageBounds) || visualSourceBounds;
                        var cropSourceBounds = boundsDifferForPlan(visualSourceBounds, absoluteVisibleBounds, 0.01)
                                ? renderSourceBounds
                                : null;
                        cachedClusters.push({
                            sourceIds: sourceIds,
                            hiddenTextFrameIds: hiddenTextFrameIds,
                            primarySourceObjectId: primarySourceObjectId,
                            bounds: visibleClusterBounds,
                            sourceBounds: visualSourceBounds,
                            renderSourceBounds: renderSourceBounds,
                            cropSourceBounds: cropSourceBounds,
                            clusterIndex: ci
                        });
                    }
                }
                masterCompositeClusterCache[cacheKey] = cachedClusters;
            }
            for (var ci2 = 0; ci2 < cachedClusters.length; ci2++) {
                var cached = cachedClusters[ci2];
                if (!cached || !cached.sourceIds || cached.sourceIds.length === 0) continue;
                _pushExtractionCandidate(candidates, seen, "pass.master_page_graphics", null, {
                    sourceId: null,
                    sourceObjectIds: cached.sourceIds,
                    hiddenTextFrameIds: cached.hiddenTextFrameIds || [],
                    editableTextFrameIds: [],
                    requiresTextHidden: cached.hiddenTextFrameIds
                            && cached.hiddenTextFrameIds.length > 0,
                    primarySourceObjectId: cached.primarySourceObjectId,
                    exportTargetObjectId: cached.primarySourceObjectId,
                    pageIndex: pp,
                    kind: "MasterPageComposite",
                    unit: "MASTER_ITEM",
                    mode: "TEXTLESS_CANDIDATE",
                    candidatePurpose: "MASTER_CANDIDATE",
                    bounds: cached.bounds,
                    sourceBounds: cached.sourceBounds || null,
                    renderSourceBounds: cached.renderSourceBounds || null,
                    cropSourceBounds: cached.cropSourceBounds || null,
                    composite: true,
                    compositeRole: "applied_master_page_cluster",
                    suffix: "master_" + master.id + "_side_" + side + "_cluster_" + cached.clusterIndex
                });
            }
        }
    } catch (eMasterCompositeCandidates) {}
}

function _unionTableCellTextFrameDescendantIds(rootId, textFrameIds, sourceById, childIdsByParentId) {
    var out = (textFrameIds || []).slice(0);
    if (rootId === null || rootId === undefined || !sourceById || !childIdsByParentId) return out;
    var seen = {};
    for (var i = 0; i < out.length; i++) seen[String(out[i])] = true;
    var queue = (childIdsByParentId[String(rootId)] || []).slice(0);
    var guard = 0;
    while (queue.length > 0 && guard < 500) {
        guard++;
        var id = queue.shift();
        var src = sourceById[String(id)];
        if (!src) continue;
        if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) continue;
        if (src.sourceHidden === true || src.hiddenByParent === true) continue;
        var kind = String(src.kind || "");
        if (kind === "Group") {
            var childIds = childIdsByParentId[String(id)] || [];
            for (var ci = 0; ci < childIds.length; ci++) queue.push(childIds[ci]);
            continue;
        }
        if (kind !== "TextFrame") continue;
        if (src.hasTablesInStory === true
                && src.storyHasVisibleTableCellText === true
                && src.markerOnlyContents !== false
                && src.hasText !== true
                && !seen[String(src.id)]) {
            seen[String(src.id)] = true;
            out.push(src.id);
        }
    }
    return _sortedNumericIds(out);
}

function _appendInlineObjectExtractionCandidates(doc, ctx, allItems, sourceItems, candidates, seen, planCache) {
    // Inline ownership is Stage 0 metadata. Do not rescan Story.allPageItems here:
    // that path is slow on large documents and can reinterpret anchored/floating
    // placement after source_index.jsx has already resolved it.
    var stats = {
        sourceItemCount: sourceItems ? sourceItems.length : 0,
        consideredInlineFlow: 0,
        skippedTextFrame: 0,
        skippedParentInlineCarrier: 0,
        skippedPageRange: 0,
        skippedNoDomItem: 0,
        skippedNoVisualSource: 0,
        emitted: 0
    };
    var sourceInfoById = {};
    var childIdsByParentId = {};
    for (var ii = 0; sourceItems && ii < sourceItems.length; ii++) {
        var info = sourceItems[ii];
        if (!info || info.id === null || info.id === undefined) continue;
        sourceInfoById[String(info.id)] = info;
        if (info.parentId !== null && info.parentId !== undefined) {
            var parentKey = String(info.parentId);
            if (!childIdsByParentId[parentKey]) childIdsByParentId[parentKey] = [];
            childIdsByParentId[parentKey].push(info.id);
        }
    }

    function directEditableTextChildIds(sourceId) {
        var ids = [];
        var children = childIdsByParentId[String(sourceId)] || [];
        for (var ci = 0; ci < children.length; ci++) {
            var child = sourceInfoById[String(children[ci])];
            if (child && child.kind === "TextFrame"
                    && child.textFrameClass === "editable" && child.hasText === true) {
                ids.push(child.id);
            }
        }
        return _sortedNumericIds(ids);
    }

    function pageIndexInCurrentExtraction(pageIndex) {
        if (pageIndex === null || pageIndex === undefined || pageIndex < 0) return false;
        return _candidatePageInRange(pageIndex, ctx);
    }

    function hasInlineCarrierParent(sourceInfo) {
        if (!sourceInfo || sourceInfo.parentId === null || sourceInfo.parentId === undefined) return false;
        var parent = sourceInfoById[String(sourceInfo.parentId)];
        if (!parent || !_isInlineFlowItemBySourceInfo(parent)) return false;
        var parentKind = String(parent.kind || "");
        if (String(sourceInfo.kind || "") === "Group"
                && directEditableTextChildIds(sourceInfo.id).length > 1) {
            return false;
        }
        return parentKind === "Group" || parentKind === "Rectangle";
    }

    function candidateKindCanBeInlineVisual(kind) {
        kind = String(kind || "");
        return kind === "Group" || kind === "Rectangle" || kind === "Oval"
                || kind === "Polygon" || kind === "GraphicLine" || kind === "Image"
                || kind === "PDF" || kind === "EPS";
    }

    for (var si = 0; sourceItems && si < sourceItems.length; si++) {
        var sourceInfo = sourceItems[si];
        if (!sourceInfo || sourceInfo.id === null || sourceInfo.id === undefined) continue;
        if (!_isInlineFlowItemBySourceInfo(sourceInfo)) continue;
        stats.consideredInlineFlow++;
        if (sourceInfo.kind === "TextFrame") {
            stats.skippedTextFrame++;
            continue;
        }
        if (!candidateKindCanBeInlineVisual(sourceInfo.kind)) continue;
        if (hasInlineCarrierParent(sourceInfo)) {
            stats.skippedParentInlineCarrier++;
            continue;
        }
        var inlinePageIndex = sourceInfo.pageIndex;
        if (!pageIndexInCurrentExtraction(inlinePageIndex)) {
            stats.skippedPageRange++;
            continue;
        }
        if (_inlineTableShellFullyAbsorbedByTableStyle(sourceInfo.id, sourceInfoById, childIdsByParentId)) {
            // 표 전용 TF + 표 속성 rect 그룹: table_only_text_frame plan 이
            // 텍스트/스타일을 소유하므로 인라인 시각 후보를 만들지 않는다.
            stats.skippedTableShellStyleAbsorbed = (stats.skippedTableShellStyleAbsorbed || 0) + 1;
            continue;
        }
        var inlineItem = planCache && planCache.domItem ? planCache.domItem(sourceInfo.id) : null;
        if (!inlineItem) {
            stats.skippedNoDomItem++;
            continue;
        }

        var inlineSourceIds = [];
        try {
            inlineSourceIds = planCache ? planCache.sourceObjectIds(inlineItem) : _collectSourceObjectIds(inlineItem);
        } catch (eInlineSourceIds) {}
        var inlineEditableTextFrameIds = [];
        try {
            inlineEditableTextFrameIds = planCache
                    ? planCache.textFrameIds(inlineItem, true, true)
                    : _collectTextFrameIds(inlineItem, true, true);
        } catch (eInlineEditableIds) {}
        // 표 전용 TF 자손은 DOM contents 가 표 앵커 문자뿐이라 위 수집에서
        // 빠진다. 셀 텍스트 소유자로 합류시켜 PNG 텍스트 굽기를 막는다.
        inlineEditableTextFrameIds = _unionTableCellTextFrameDescendantIds(
                sourceInfo.id, inlineEditableTextFrameIds, sourceInfoById, childIdsByParentId);

        var ownsTextByCompletePng = String(sourceInfo.kind || "") === "Group"
                && directEditableTextChildIds(sourceInfo.id).length > 1;
        var inlineRequiresTextHidden = inlineEditableTextFrameIds
                && inlineEditableTextFrameIds.length > 0 && !ownsTextByCompletePng;
        var inlineVisualSourceIds = ownsTextByCompletePng
                ? [sourceInfo.id]
                : (inlineRequiresTextHidden
                ? []
                : _inlineCarrierVisualSourceObjectIds(inlineItem, sourceInfo, inlineSourceIds));
        if (!inlineRequiresTextHidden
                && (!inlineVisualSourceIds || inlineVisualSourceIds.length === 0)) {
            stats.skippedNoVisualSource++;
            continue;
        }

        var attrs = {
            sourceObjectIds: inlineSourceIds,
            exportSourceObjectIds: inlineVisualSourceIds,
            visualSourceObjectIds: inlineVisualSourceIds,
            pageIndex: inlinePageIndex,
            unit: "INLINE_OBJECT",
            mode: "TEXTLESS_CANDIDATE",
            candidatePurpose: "INLINE_CANDIDATE",
            editableTextFrameIds: inlineEditableTextFrameIds,
            ownedTextFrameIds: ownsTextByCompletePng ? inlineEditableTextFrameIds : [],
            hiddenTextFrameIds: inlineRequiresTextHidden ? inlineEditableTextFrameIds : [],
            requiresTextHidden: inlineRequiresTextHidden,
            textOwner: ownsTextByCompletePng ? "indesign_png" : (inlineRequiresTextHidden ? "hwpx_tf" : "none"),
            containsEditableText: ownsTextByCompletePng,
            completePngTextAllowed: ownsTextByCompletePng,
            materialization: ownsTextByCompletePng ? "COMPLETE_PNG" : null,
            textAction: ownsTextByCompletePng ? "OWNED_BY_PNG" : (inlineRequiresTextHidden ? "OWNED_BY_HWPX_TEXT" : null),
            visualAction: ownsTextByCompletePng ? "PLACE_INLINE_PNG" : null,
            ownershipSlot: ownsTextByCompletePng ? "CONTENT_VISUAL_SLOT" : null,
            exportTargetObjectId: ownsTextByCompletePng ? sourceInfo.id : null,
            placement: "INLINE",
            coordinateSpace: "STORY_FLOW",
            inlineSourceTreeClosed: ownsTextByCompletePng
        };
        if (!inlineRequiresTextHidden) {
            attrs.compositeRole = "inline_textless_native_shape";
            attrs.slotRole = "inline_textless_native_shape";
        }
        var before = candidates ? candidates.length : 0;
        _pushExtractionCandidate(candidates, seen, "pass.inline_objects", inlineItem, attrs);
        if (candidates && candidates.length > before) stats.emitted++;
    }

    try { writeJson(ctx.outputDir + "/_inline_candidate_source_scan_stats.json", stats); } catch (eStats) {}
}

function _includeOwnedInlineVisualsInTextlessShellCandidates(candidates, allItems, planCache, sourceItems) {
    // Legacy ownership bridge disabled.
    //
    // This path matched tiny inline carriers to nearby page-plane visual roots
    // using geometric adjacency, then rewrote the page/textless candidate source
    // sets. That violates the source-ownership policy: ownership must come from
    // resolved source structure/ObjectPlan, not bounds-only matching. It also
    // caused text-shell graphics to disappear from the page root plane while a
    // non-inline page group was emitted as pass.inline_objects.
    return candidates || [];

    if (!candidates || candidates.length === 0) return candidates || [];
    var inlineVisualDetector = _createInlineVisualSourceDetector(allItems, planCache);
    var sourceIndexes = _buildSourceItemIndexes(sourceItems || []);
    var sourceInfoById = sourceIndexes.sourceInfoById || {};
    var childIdsByParentId = sourceIndexes.childIdsByParentId || {};

    function mergeIds(base, extra) {
        var out = [];
        var seen = {};
        for (var bi = 0; base && bi < base.length; bi++) _pushUniqueId(out, seen, base[bi]);
        for (var ei = 0; extra && ei < extra.length; ei++) _pushUniqueId(out, seen, extra[ei]);
        return _sortedNumericIds(out);
    }

    function removeIds(base, removed) {
        return _removeSourceIds(base || [], removed || []);
    }

    function shouldInspectCandidate(candidate) {
        if (!candidate || candidate.passId !== "pass.decoration_groups") return false;
        if (candidate.candidatePurpose !== "SHELL_CANDIDATE") return false;
        if (candidate.textOwner !== "hwpx_tf") return false;
        if (candidate.slotRole !== "shell_slot_only") return false;
        if (!candidate.hiddenTextFrameIds || candidate.hiddenTextFrameIds.length === 0) return false;
        return true;
    }

    function filterTextFrameIds(ids) {
        var out = [];
        var seen = {};
        for (var i = 0; ids && i < ids.length; i++) {
            var item = planCache && planCache.domItem ? planCache.domItem(ids[i]) : null;
            try {
                if (item && item.constructor.name === "TextFrame") {
                    _pushUniqueId(out, seen, ids[i]);
                }
            } catch (e) {}
        }
        return _sortedNumericIds(out);
    }

    function assertExecutableShellCandidate(candidate) {
        if (!candidate || _candidateHasExecutableShellMaterial(candidate, sourceInfoById, childIdsByParentId)) return;
        throw new Error("Non-executable shell candidate after inline visual ownership merge"
                + " candidateId=" + String(candidate.candidateId || "")
                + " passId=" + String(candidate.passId || "")
                + " pageIndex=" + String(candidate.pageIndex)
                + " sourceObjectIds=" + String((candidate.sourceObjectIds || []).join(",")));
    }

    for (var ci = 0; ci < candidates.length; ci++) {
        var candidate = candidates[ci];
        if (!shouldInspectCandidate(candidate)) continue;
        var targetId = candidate.exportTargetObjectId !== undefined && candidate.exportTargetObjectId !== null
                ? candidate.exportTargetObjectId
                : candidate.primarySourceObjectId;
        var targetItem = planCache && planCache.domItem ? planCache.domItem(targetId) : null;
        if (!targetItem) continue;
        var inlineVisualIds = [];
        try { inlineVisualIds = collectTfInlineVisualIds(targetItem); } catch (eInlineVisuals) { inlineVisualIds = []; }
        if (inlineVisualIds && inlineVisualIds.length > 0) {
            inlineVisualIds = inlineVisualDetector.expandInlineVisualSourceIds(inlineVisualIds);
            candidate.sourceObjectIds = mergeIds(candidate.sourceObjectIds || [], inlineVisualIds);
            candidate.hiddenVisualSourceObjectIds = mergeIds(candidate.hiddenVisualSourceObjectIds || [], inlineVisualIds);
            candidate.exportSourceObjectIds = removeIds(candidate.exportSourceObjectIds || [], inlineVisualIds);
        }
        var hiddenInlineSourceIds = inlineVisualDetector.collectHiddenTextFrameInlineSourceIds(candidate.hiddenTextFrameIds || []);
        if (hiddenInlineSourceIds && hiddenInlineSourceIds.length > 0) {
            if (candidate.compositeRole !== "source_declared_closed_text_shell"
                    && candidate.sourceDeclaredClosedTextShell !== true) {
                candidate.sourceObjectIds = mergeIds(candidate.sourceObjectIds || [], hiddenInlineSourceIds);
                candidate.hiddenTextFrameIds = mergeIds(candidate.hiddenTextFrameIds || [],
                        filterTextFrameIds(hiddenInlineSourceIds));
                candidate.exportSourceObjectIds = removeIds(candidate.exportSourceObjectIds || [], hiddenInlineSourceIds);
            }
        }
        candidate.composite = candidate.sourceObjectIds.length > 1 || candidate.composite === true;
        candidate.candidateId = candidate.composite
                ? _candidateCompositeId(candidate.passId, candidate.pageIndex, candidate.sourceObjectIds, candidate.slotRole || candidate.suffix)
                : _candidateId(candidate.passId, candidate.primarySourceObjectId, candidate.pageIndex);
        assertExecutableShellCandidate(candidate);
    }
    return candidates;
}

function _appendMultiTextParentGroupExportCandidatesFromSourceItems(sourceItems, candidates, candidateSeen) {
    if (!sourceItems || !candidates) return candidates || [];
    var indexes = null;
    try { indexes = _buildSourceItemIndexes(sourceItems || []); } catch (eIndex) { indexes = null; }
    if (!indexes) return candidates;
    var sourceInfoById = indexes.sourceInfoById || {};
    var childIdsByParentId = indexes.childIdsByParentId || {};
    var sourceOrderById = {};
    var subtreeByRootId = {};
    var inlineAnchorById = {};
    var visibleVisualById = {};
    var depthById = {};
    var coveredDescendantGroupRoots = {};

    for (var sourceOrderIndex = 0; sourceOrderIndex < sourceItems.length; sourceOrderIndex++) {
        var orderedSource = sourceItems[sourceOrderIndex];
        if (!orderedSource || orderedSource.id === null || orderedSource.id === undefined) continue;
        sourceOrderById[String(orderedSource.id)] = sourceOrderIndex;
    }

    function info(id) {
        return sourceInfoById ? sourceInfoById[String(id)] : null;
    }

    function hasInlineAnchorMetadata(src) {
        if (!src) return false;
        var cacheKey = String(src.id);
        if (inlineAnchorById.hasOwnProperty(cacheKey)) return inlineAnchorById[cacheKey];
        var ap = String(src.anchoredPosition || "");
        var sp = String(src.storyAnchorPlacement || "");
        var result = false;
        if (ap.length > 0 && ap !== "PAGE") result = true;
        if (sp.length > 0 && sp !== "PAGE") result = true;
        inlineAnchorById[cacheKey] = result;
        return result;
    }

    function collectSubtree(rootId) {
        var rootKey = String(rootId);
        if (subtreeByRootId.hasOwnProperty(rootKey)) return subtreeByRootId[rootKey];
        var ids = [], seen = {};
        function visit(id) {
            if (id === null || id === undefined || seen[String(id)]) return;
            seen[String(id)] = true;
            ids.push(id);
            var children = childIdsByParentId[String(id)] || [];
            for (var ci = 0; ci < children.length; ci++) visit(children[ci]);
        }
        visit(rootId);
        subtreeByRootId[rootKey] = _sortedNumericIds(ids);
        return subtreeByRootId[rootKey];
    }

    function sourceHasVisibleVisual(src) {
        if (!src) return false;
        var cacheKey = String(src.id);
        if (visibleVisualById.hasOwnProperty(cacheKey)) return visibleVisualById[cacheKey];
        var result = false;
        var kind = String(src.kind || "");
        if (kind === "TextFrame") return false;
        if (src.visible === false || src.hiddenLayer === true) return false;
        if (kind === "Image" || kind === "PDF" || kind === "EPS") return true;
        result = src.hasPlacedVisual === true
                || src.hasVisibleFill === true
                || src.hasVisibleStroke === true
                || src.hasCandidateVectorPaint === true
                || kind === "Group";
        visibleVisualById[cacheKey] = result;
        return result;
    }

    function sourceIsPlacedVisualLeaf(src) {
        if (!src) return false;
        var kind = String(src.kind || "");
        return kind === "Image" || kind === "PDF" || kind === "EPS"
                || src.hasPlacedVisual === true;
    }

    function sourceTreeDepth(src) {
        if (!src || src.id === null || src.id === undefined) return 0;
        var key = String(src.id);
        if (depthById.hasOwnProperty(key)) return depthById[key];
        var depth = 0;
        var current = src;
        var seen = {};
        while (current && current.parentId !== null && current.parentId !== undefined) {
            var parentKey = String(current.parentId);
            if (seen[parentKey]) break;
            seen[parentKey] = true;
            var parent = info(current.parentId);
            if (!parent) break;
            depth++;
            current = parent;
        }
        depthById[key] = depth;
        return depth;
    }

    function markDescendantGroupRootsCovered(subtree, rootId) {
        for (var i = 0; subtree && i < subtree.length; i++) {
            var id = subtree[i];
            if (String(id) === String(rootId)) continue;
            var src = info(id);
            if (!src || String(src.kind || "") !== "Group") continue;
            coveredDescendantGroupRoots[String(id)] = true;
        }
    }

    function pushCandidate(candidate) {
        if (!candidate || !candidate.candidateId) return;
        var key = String(candidate.candidateId);
        if (candidateSeen && candidateSeen[key]) return;
        if (candidateSeen) candidateSeen[key] = true;
        candidates.push(candidate);
    }

    var groupRoots = [];
    for (var gi = 0; gi < sourceItems.length; gi++) {
        var groupRoot = sourceItems[gi];
        if (!groupRoot || String(groupRoot.kind || "") !== "Group") continue;
        groupRoots.push(groupRoot);
    }
    groupRoots.sort(function(a, b) {
        var depthDelta = sourceTreeDepth(a) - sourceTreeDepth(b);
        if (depthDelta !== 0) return depthDelta;
        var ai = sourceOrderById[String(a.id)];
        var bi = sourceOrderById[String(b.id)];
        return Number(ai || 0) - Number(bi || 0);
    });

    for (var i = 0; i < groupRoots.length; i++) {
        var root = groupRoots[i];
        if (!root || String(root.kind || "") !== "Group") continue;
        if (coveredDescendantGroupRoots[String(root.id)]) continue;
        if (root.visible === false || root.hiddenLayer === true) continue;
        if (hasInlineAnchorMetadata(root)) continue;
        var subtree = collectSubtree(root.id);
        if (subtree.length <= 1) continue;
        var editableIds = [], editableSeen = {};
        var visualIds = [], visualSeen = {};
        var hasTableText = false;
        var hasNestedInline = false;
        var hasPlacedVisualLeaf = false;
        for (var si = 0; si < subtree.length; si++) {
            var src = info(subtree[si]);
            if (!src) continue;
            if (src.id !== root.id && hasInlineAnchorMetadata(src)) {
                hasNestedInline = true;
                break;
            }
            if (String(src.kind || "") === "TextFrame") {
                if (src.hasTablesInStory === true || Number(src.tableCountInStory || 0) > 0) {
                    hasTableText = true;
                    break;
                }
                if (src.textFrameClass === "editable" && src.hasText === true) {
                    _pushUniqueId(editableIds, editableSeen, src.id);
                }
                continue;
            }
            if (sourceIsPlacedVisualLeaf(src)) {
                hasPlacedVisualLeaf = true;
            }
            if (sourceHasVisibleVisual(src)) {
                _pushUniqueId(visualIds, visualSeen, src.id);
            }
        }
        if (hasNestedInline || hasTableText) continue;
        if (hasPlacedVisualLeaf) continue;
        if (editableIds.length < 2 || visualIds.length === 0) continue;
        editableIds = _sortedNumericIds(editableIds);
        visualIds = _sortedNumericIds(visualIds);
        subtree = _sortedNumericIds(subtree);
        var candidateId = _candidateCompositeId(
                "pass.decoration_groups",
                root.pageIndex,
                subtree,
                "textless_group_visual_slot");
        pushCandidate({
            candidateId: candidateId,
            passId: "pass.decoration_groups",
            sourceObjectIds: subtree,
            executionSourceObjectIds: subtree.slice(0),
            primarySourceObjectId: root.id,
            pageIndex: root.pageIndex,
            kind: root.kind || "Group",
            unit: "GROUP_OR_ITEM",
            mode: "TEXTLESS_CANDIDATE",
            candidatePurpose: "SHELL_CANDIDATE",
            bounds: root.bounds || null,
            parentId: root.parentId,
            parentKind: root.parentKind || null,
            composite: true,
            compositeRole: "textless_group_visual_slot",
            slotRole: "textless_group_visual_slot",
            exportSourceObjectIds: visualIds,
            exportTargetObjectId: root.id,
            hiddenVisualSourceObjectIds: editableIds.slice(0),
            visualSourceObjectIds: visualIds.slice(0),
            ownedTextFrameIds: editableIds.slice(0),
            editableTextFrameIds: editableIds.slice(0),
            hiddenTextFrameIds: editableIds.slice(0),
            requiresTextHidden: true,
            textOwner: "hwpx_tf",
            containsEditableText: false,
            completePngTextAllowed: false,
            materialization: "EXTRACTED_PNG_VECTOR",
            textAction: "OWNED_BY_HWPX_TEXT",
            visualAction: "PLACE_TEXT_SHELL",
            visualLayer: "LABEL_BACKDROP",
            placement: "FLOATING",
            coordinateSpace: "PAGE",
            ownershipSlot: "TEXTLESS_GROUP_VISUAL_SLOT",
            reason: "multi_text_parent_group_textless_visual",
            zOrder: root.zOrder !== undefined ? root.zOrder : 0,
            required: false
        });
        markDescendantGroupRootsCovered(subtree, root.id);
    }
    return candidates;
}

function _absorbInlineDecorationDescendantsIntoTextShellCandidates(candidates, sourceItems) {
    if (!candidates || candidates.length === 0) return candidates || [];
    var indexes = null;
    try { indexes = _buildSourceItemIndexes(sourceItems || []); } catch (eIndex) { indexes = null; }
    if (!indexes) return candidates;
    var sourceInfoById = indexes.sourceInfoById || {};
    var childIdsByParentId = indexes.childIdsByParentId || {};

    function info(id) {
        return sourceInfoById ? sourceInfoById[String(id)] : null;
    }

    function idSet(ids) {
        var out = {};
        for (var i = 0; ids && i < ids.length; i++) out[String(ids[i])] = true;
        return out;
    }

    function addId(ids, seen, id) {
        if (id === null || id === undefined) return false;
        var key = String(id);
        if (seen[key]) return false;
        seen[key] = true;
        ids.push(Number(id));
        return true;
    }

    function mergeSorted(base, extra) {
        var ids = [];
        var seen = {};
        for (var i = 0; base && i < base.length; i++) addId(ids, seen, base[i]);
        for (var j = 0; extra && j < extra.length; j++) addId(ids, seen, extra[j]);
        return _sortedNumericIds(ids);
    }

    function isInlineRoot(src) {
        if (!src) return false;
        return typeof _isInlineFlowItemBySourceInfo === "function"
                ? _isInlineFlowItemBySourceInfo(src)
                : (String(src.storyAnchorPlacement || "").toUpperCase() === "INLINE"
                    || String(src.anchoredPosition || "").toUpperCase() === "INLINE_POSITION"
                    || String(src.anchoredPosition || "").toUpperCase() === "INLINEPOSITION");
    }

    function isDescendantOfSource(childId, ancestorId) {
        if (childId === null || childId === undefined
                || ancestorId === null || ancestorId === undefined) {
            return false;
        }
        if (String(childId) === String(ancestorId)) return true;
        var current = info(childId);
        for (var depth = 0; depth < 64 && current; depth++) {
            var parentId = current.parentId;
            if (parentId === null || parentId === undefined) return false;
            if (String(parentId) === String(ancestorId)) return true;
            current = info(parentId);
        }
        return false;
    }

    function editableTextIdsInSubtree(rootId) {
        var ids = [];
        var seen = {};
        function visit(id) {
            var src = info(id);
            if (!src || src.hiddenLayer === true || src.visible === false) return;
            if (isEditableTextFrame(src)) {
                addId(ids, seen, src.id);
                return;
            }
            var children = childIdsByParentId[String(id)] || [];
            for (var ci = 0; ci < children.length; ci++) visit(children[ci]);
        }
        visit(rootId);
        return _sortedNumericIds(ids);
    }

    function sourceSetContainsAll(setIds, values) {
        var set = idSet(setIds || []);
        for (var i = 0; values && i < values.length; i++) {
            if (!set[String(values[i])]) return false;
        }
        return true;
    }

    function candidateEditableTextIds(candidate) {
        return mergeSorted(mergeSorted(candidate.ownedTextFrameIds || [], candidate.editableTextFrameIds || []),
                candidate.hiddenTextFrameIds || []);
    }

    function candidateSourceIdsAreInsideRoot(candidate, rootId) {
        var ids = candidate.sourceObjectIds || [];
        for (var i = 0; i < ids.length; i++) {
            if (!isDescendantOfSource(ids[i], rootId)) return false;
        }
        return true;
    }

    function inlineTextShellClosureRootId(candidate) {
        var rootId = candidate.primarySourceObjectId !== undefined && candidate.primarySourceObjectId !== null
                ? candidate.primarySourceObjectId
                : (candidate.sourceObjectIds && candidate.sourceObjectIds.length > 0 ? candidate.sourceObjectIds[0] : null);
        var root = info(rootId);
        if (!root) return null;
        if (String(root.kind || "") === "Group" && isInlineRoot(root)) return root.id;

        var parent = root.parentId !== null && root.parentId !== undefined ? info(root.parentId) : null;
        if (!parent || String(parent.kind || "") !== "Group" || !isInlineRoot(parent)) return null;
        if (!candidateSourceIdsAreInsideRoot(candidate, parent.id)) return null;

        var parentEditableIds = editableTextIdsInSubtree(parent.id);
        if (!parentEditableIds || parentEditableIds.length === 0) return null;
        if (!sourceSetContainsAll(candidateEditableTextIds(candidate), parentEditableIds)) return null;
        return parent.id;
    }

    function isDecorationKind(kind) {
        kind = String(kind || "");
        return kind === "Group"
                || kind === "Rectangle"
                || kind === "Oval"
                || kind === "Polygon"
                || kind === "GraphicLine";
    }

    function sourceHasPlacedContent(id) {
        var src = info(id);
        if (!src) return false;
        var kind = String(src.kind || "");
        if (kind === "Image" || kind === "PDF" || kind === "EPS") return true;
        if (src.hasPlacedVisual === true) return true;
        var children = childIdsByParentId[String(id)] || [];
        for (var ci = 0; ci < children.length; ci++) {
            if (sourceHasPlacedContent(children[ci])) return true;
        }
        return false;
    }

    function isEditableTextFrame(src) {
        return src
                && String(src.kind || "") === "TextFrame"
                && src.textFrameClass === "editable"
                && src.hasText === true
                && src.hiddenLayer !== true
                && src.visible !== false;
    }

    function isInlineTextShellCandidate(candidate) {
        if (!candidate || candidate.disabled === true) return false;
        if (candidate.passId !== "pass.inline_objects") return false;
        if (candidate.visualAction !== "PLACE_TEXT_SHELL" && candidate.textOwner !== "hwpx_tf") return false;
        var owned = candidate.ownedTextFrameIds || candidate.editableTextFrameIds || candidate.hiddenTextFrameIds || [];
        if (!owned || owned.length === 0) return false;
        return inlineTextShellClosureRootId(candidate) !== null;
    }

    function collectInlineShellSlot(rootId, candidate) {
        var sourceIds = [];
        var visualIds = [];
        var textIds = [];
        var sourceSeen = idSet(candidate.sourceObjectIds || []);
        var visualSeen = idSet(candidate.visualSourceObjectIds || candidate.exportSourceObjectIds || []);
        var textSeen = idSet(candidate.ownedTextFrameIds || candidate.editableTextFrameIds || candidate.hiddenTextFrameIds || []);
        var addedVisualCount = 0;

        function visit(id) {
            var src = info(id);
            if (!src || src.hiddenLayer === true || src.visible === false) return;
            var kind = String(src.kind || "");
            if (isEditableTextFrame(src)) {
                if (addId(sourceIds, sourceSeen, src.id)) {}
                addId(textIds, textSeen, src.id);
                return;
            }
            if (kind === "TextFrame") return;
            if (String(src.id) !== String(rootId) && sourceHasPlacedContent(src.id)) return;
            if (isDecorationKind(kind)) {
                if (addId(sourceIds, sourceSeen, src.id)) {}
                if (addId(visualIds, visualSeen, src.id)) addedVisualCount++;
            } else if (String(src.id) !== String(rootId)) {
                return;
            }
            var children = childIdsByParentId[String(src.id)] || [];
            for (var ci = 0; ci < children.length; ci++) visit(children[ci]);
        }

        visit(rootId);
        return {
            sourceIds: _sortedNumericIds(sourceIds),
            visualIds: _sortedNumericIds(visualIds),
            textIds: _sortedNumericIds(textIds),
            addedVisualCount: addedVisualCount
        };
    }

    for (var ci = 0; ci < candidates.length; ci++) {
        var candidate = candidates[ci];
        if (!isInlineTextShellCandidate(candidate)) continue;
        var rootId = inlineTextShellClosureRootId(candidate);
        if (rootId === null || rootId === undefined) continue;
        var slot = collectInlineShellSlot(rootId, candidate);
        if (!slot || slot.addedVisualCount <= 0) continue;
        var root = info(rootId);
        candidate.sourceObjectIds = mergeSorted(candidate.sourceObjectIds || [], slot.sourceIds);
        candidate.executionSourceObjectIds = mergeSorted(candidate.executionSourceObjectIds || candidate.sourceObjectIds || [], slot.sourceIds);
        candidate.exportSourceObjectIds = mergeSorted(candidate.exportSourceObjectIds || [], slot.visualIds);
        candidate.visualSourceObjectIds = mergeSorted(candidate.visualSourceObjectIds || [], slot.visualIds);
        candidate.ownedTextFrameIds = mergeSorted(candidate.ownedTextFrameIds || [], slot.textIds);
        candidate.editableTextFrameIds = mergeSorted(candidate.editableTextFrameIds || [], slot.textIds);
        candidate.hiddenTextFrameIds = mergeSorted(candidate.hiddenTextFrameIds || [], slot.textIds);
        candidate.hiddenVisualSourceObjectIds = mergeSorted(candidate.hiddenVisualSourceObjectIds || [], slot.textIds);
        candidate.inlineTextShellAbsorbedDecorationDescendants = true;
        candidate.inlineSourceTreeClosed = true;
        candidate.primarySourceObjectId = rootId;
        candidate.exportTargetObjectId = rootId;
        if (root) {
            candidate.bounds = root.bounds || candidate.bounds;
            candidate.parentId = root.parentId;
            candidate.parentKind = root.parentKind;
            candidate.anchoredPosition = root.anchoredPosition;
            candidate.storyAnchorPlacement = root.storyAnchorPlacement;
            if (root.zOrder !== undefined && root.zOrder !== null) candidate.zOrder = root.zOrder;
        }
        candidate.composite = true;
        candidate.candidateId = _candidateCompositeId(
                candidate.passId, candidate.pageIndex, candidate.sourceObjectIds, candidate.slotRole || candidate.suffix);
    }
    return candidates;
}

function _suppressChildExportsCoveredByTextlessGroupCandidates(candidates, sourceItems) {
    if (!candidates || candidates.length === 0) {
        return { candidates: candidates || [], suppressedCount: 0, suppressed: [] };
    }
    var indexes = null;
    try { indexes = _buildSourceItemIndexes(sourceItems || []); } catch (eIndex) { indexes = null; }
    var sourceInfoById = indexes ? indexes.sourceInfoById || {} : {};

    function info(id) {
        return sourceInfoById ? sourceInfoById[String(id)] : null;
    }

    function parentIdOf(id) {
        var src = info(id);
        if (!src) return null;
        return src.parentId !== undefined && src.parentId !== null ? src.parentId : null;
    }

    function isDescendantOf(childId, parentId) {
        if (childId === null || childId === undefined || parentId === null || parentId === undefined) return false;
        if (String(childId) === String(parentId)) return true;
        var cur = parentIdOf(childId);
        var guard = 0;
        while (cur !== null && cur !== undefined && guard++ < 100) {
            if (String(cur) === String(parentId)) return true;
            cur = parentIdOf(cur);
        }
        return false;
    }

    function ids(candidate, field) {
        return _sortedNumericIds(candidate && candidate[field] || []);
    }

    function textFrameIds(candidate) {
        var merged = [];
        var seen = {};
        function add(values) {
            for (var i = 0; values && i < values.length; i++) {
                var id = values[i];
                var key = String(id);
                if (seen[key]) continue;
                seen[key] = true;
                merged.push(id);
            }
        }
        add(candidate && candidate.ownedTextFrameIds);
        add(candidate && candidate.editableTextFrameIds);
        add(candidate && candidate.hiddenTextFrameIds);
        return _sortedNumericIds(merged);
    }

    function containsAll(ownerIds, childIds) {
        if (!childIds || childIds.length === 0) return false;
        var set = {};
        for (var i = 0; ownerIds && i < ownerIds.length; i++) set[String(ownerIds[i])] = true;
        return containsAllInSet(set, childIds);
    }

    function containsAllInSet(ownerSet, childIds) {
        if (!childIds || childIds.length === 0) return false;
        for (var ci = 0; ci < childIds.length; ci++) {
            if (!ownerSet || !ownerSet[String(childIds[ci])]) return false;
        }
        return true;
    }

    function idSet(ids) {
        var set = {};
        for (var i = 0; ids && i < ids.length; i++) set[String(ids[i])] = true;
        return set;
    }

    function primaryId(candidate) {
        if (!candidate) return null;
        if (candidate.primarySourceObjectId !== undefined && candidate.primarySourceObjectId !== null) {
            return candidate.primarySourceObjectId;
        }
        var sourceIds = ids(candidate, "sourceObjectIds");
        return sourceIds.length > 0 ? sourceIds[0] : null;
    }

    function isTextlessParentGroup(candidate) {
        return candidate
                && candidate.passId === "pass.decoration_groups"
                && candidate.slotRole === "textless_group_visual_slot"
                && candidate.textOwner === "hwpx_tf";
    }

    function isInlineTextlessShellCarrier(candidate) {
        var textIds = textFrameIds(candidate);
        return candidate
                && candidate.passId === "pass.inline_objects"
                && (candidate.textOwner === "hwpx_tf" || textIds.length > 0)
                && candidate.ownershipSlot !== "CONTENT_VISUAL_SLOT"
                && candidate.visualAction !== "PLACE_INLINE_PNG"
                && candidate.visualAction !== "PLACE_FLOATING_PNG";
    }

    function isTextlessShellCarrier(candidate) {
        return isTextlessParentGroup(candidate) || isInlineTextlessShellCarrier(candidate);
    }

    function isSuppressibleChild(candidate) {
        if (!candidate || candidate.disabled === true) return false;
        if (_candidateIsProtectedDecorationSlot(candidate)) return false;
        if (isTextlessParentGroup(candidate)) return true;
        if (candidate.passId === "pass.inline_objects"
                && candidate.slotRole === "inline_textless_sibling_decoration_slot"
                && (!candidate.ownedTextFrameIds || candidate.ownedTextFrameIds.length === 0)) {
            return true;
        }
        if (candidate.visualAction === "DROP_VISUAL") return false;
        if (candidate.materialization === "HWPX_TEXT"
                || candidate.materialization === "HWPX_TABLE_STYLE"
                || candidate.materialization === "NATIVE_SOURCE_SHAPE") {
            return false;
        }
        return candidate.passId === "pass.decoration_groups"
                || candidate.passId === "pass.image_textless_groups"
                || candidate.passId === "pass.image_placed_frames"
                || candidate.passId === "pass.vector_shape_frames"
                || candidate.passId === "pass.complex_graphic_frames";
    }

    var parents = [];
    for (var pi = 0; pi < candidates.length; pi++) {
        if (isTextlessShellCarrier(candidates[pi])) {
            parents.push({
                candidate: candidates[pi],
                primaryId: primaryId(candidates[pi]),
                editableIds: textFrameIds(candidates[pi])
            });
        }
    }
    if (parents.length === 0) {
        return { candidates: candidates, suppressedCount: 0, suppressed: [] };
    }

    var out = [];
    var suppressed = [];
    for (var ci = 0; ci < candidates.length; ci++) {
        var candidate = candidates[ci];
        if (!isSuppressibleChild(candidate)) {
            out.push(candidate);
            continue;
        }
        var candidatePrimary = primaryId(candidate);
        var coveredBy = null;
        for (var pp = 0; pp < parents.length; pp++) {
            var parent = parents[pp];
            if (String(candidate.pageIndex) !== String(parent.candidate.pageIndex)) continue;
            if (String(candidate.candidateId || "") === String(parent.candidate.candidateId || "")) continue;
            var candidateTextIds = textFrameIds(candidate);
            if ((isTextlessParentGroup(candidate) || candidateTextIds.length > 0)
                    && !containsAll(parent.editableIds || [], candidateTextIds)) {
                continue;
            }
            if (!isDescendantOf(candidatePrimary, parent.primaryId)) continue;
            coveredBy = parent.candidate;
            break;
        }
        if (!coveredBy) {
            out.push(candidate);
            continue;
        }
        suppressed.push({
            candidateId: candidate.candidateId || null,
            suppressedByCandidateId: coveredBy.candidateId || null,
            pageIndex: candidate.pageIndex,
            primarySourceObjectId: candidatePrimary,
            reason: "covered_by_textless_parent_group_export"
        });
    }
    return {
        candidates: out,
        suppressedCount: suppressed.length,
        suppressed: suppressed
    };
}

function _suppressChildShellSlotsCoveredByCompositeShellCandidates(candidates, sourceItems) {
    if (!candidates || candidates.length === 0) {
        return { candidates: candidates || [], suppressedCount: 0, suppressed: [] };
    }

    function ids(candidate, field) {
        return _sortedNumericIds(candidate && candidate[field] || []);
    }

    function containsAll(ownerIds, childIds) {
        if (!childIds || childIds.length === 0) return false;
        var set = {};
        for (var i = 0; ownerIds && i < ownerIds.length; i++) set[String(ownerIds[i])] = true;
        for (var ci = 0; ci < childIds.length; ci++) {
            if (!set[String(childIds[ci])]) return false;
        }
        return true;
    }

    function visibleExportIds(candidate) {
        var visualIds = ids(candidate, "visualSourceObjectIds");
        if (visualIds.length > 0) return visualIds;
        var exportIds = ids(candidate, "exportSourceObjectIds");
        if (exportIds.length > 0) return exportIds;
        return ids(candidate, "sourceObjectIds");
    }

    function ownershipSlot(candidate) {
        if (!candidate) return "CONTENT_VISUAL_SLOT";
        if (candidate.ownershipSlot) {
            return candidate.ownershipSlot === "TEXTLESS_GROUP_VISUAL_SLOT"
                    ? "SHELL_SLOT"
                    : candidate.ownershipSlot;
        }
        if (candidate.visualAction === "PLACE_TEXT_SHELL") return "SHELL_SLOT";
        if (candidate.passId === "pass.decoration_groups"
                || candidate.passId === "pass.editable_textframe_visual_shells"
                || candidate.slotRole === "shell_slot_only") {
            return "SHELL_SLOT";
        }
        return "CONTENT_VISUAL_SLOT";
    }

    function hasOwnedText(candidate) {
        return (candidate.ownedTextFrameIds && candidate.ownedTextFrameIds.length > 0)
                || (candidate.editableTextFrameIds && candidate.editableTextFrameIds.length > 0)
                || (candidate.hiddenTextFrameIds && candidate.hiddenTextFrameIds.length > 0)
                || candidate.textOwner === "hwpx_tf";
    }

    function isCompositeShellOwner(candidate) {
        if (!candidate || candidate.disabled === true) return false;
        if (ownershipSlot(candidate) !== "SHELL_SLOT") return false;
        if (candidate.visualAction && candidate.visualAction !== "PLACE_TEXT_SHELL"
                && candidate.visualAction !== "PLACE_FLOATING_PNG") {
            return false;
        }
        var visibleIds = visibleExportIds(candidate);
        if (!visibleIds || visibleIds.length === 0) return false;
        if (visibleIds.length === 1 && !hasOwnedText(candidate)) return false;
        return candidate.composite === true
                || visibleIds.length > 1
                || hasOwnedText(candidate)
                || (candidate.sourceObjectIds && candidate.sourceObjectIds.length > 1);
    }

    function isSuppressibleShellChild(candidate) {
        if (!candidate || candidate.disabled === true) return false;
        if (ownershipSlot(candidate) !== "SHELL_SLOT") return false;
        if (candidate.placement === "INLINE") return false;
        if (candidate.slotRole === "direct_child_shell_slot"
                || candidate.compositeRole === "direct_child_shell_slot") {
            return false;
        }
        if (hasOwnedText(candidate)) return false;
        if (candidate.visualAction === "DROP_VISUAL") return false;
        if (candidate.passId !== "pass.vector_shape_frames"
                && candidate.passId !== "pass.decoration_groups"
                && candidate.passId !== "pass.editable_textframe_visual_shells"
                && candidate.passId !== "pass.complex_graphic_frames") {
            return false;
        }
        var visibleIds = visibleExportIds(candidate);
        return visibleIds && visibleIds.length > 0;
    }

    var owners = [];
    for (var oi = 0; oi < candidates.length; oi++) {
        var owner = candidates[oi];
        if (!isCompositeShellOwner(owner)) continue;
        owners.push({
            candidate: owner,
            visibleIds: visibleExportIds(owner),
            sourceIds: ids(owner, "sourceObjectIds")
        });
    }
    if (owners.length === 0) {
        return { candidates: candidates, suppressedCount: 0, suppressed: [] };
    }

    var out = [];
    var suppressed = [];
    for (var ci = 0; ci < candidates.length; ci++) {
        var candidate = candidates[ci];
        if (!isSuppressibleShellChild(candidate)) {
            out.push(candidate);
            continue;
        }
        var childVisibleIds = visibleExportIds(candidate);
        var childSourceIds = ids(candidate, "sourceObjectIds");
        var coveredBy = null;
        for (var oo = 0; oo < owners.length; oo++) {
            var ownerEntry = owners[oo];
            var ownerCandidate = ownerEntry.candidate;
            if (ownerCandidate === candidate) continue;
            if (String(ownerCandidate.candidateId || "") === String(candidate.candidateId || "")) continue;
            if (String(ownerCandidate.pageIndex) !== String(candidate.pageIndex)) continue;
            if (ownerEntry.visibleIds.length <= childVisibleIds.length
                    && ownerEntry.sourceIds.length <= childSourceIds.length) {
                continue;
            }
            if (!containsAll(ownerEntry.visibleIds, childVisibleIds)
                    && !containsAll(ownerEntry.sourceIds, childSourceIds)) {
                continue;
            }
            coveredBy = ownerCandidate;
            break;
        }
        if (!coveredBy) {
            out.push(candidate);
            continue;
        }
        suppressed.push({
            candidateId: candidate.candidateId || null,
            suppressedByCandidateId: coveredBy.candidateId || null,
            pageIndex: candidate.pageIndex,
            sourceObjectIds: childSourceIds,
            visibleSourceObjectIds: childVisibleIds,
            reason: "covered_by_composite_shell_slot_owner"
        });
    }

    return {
        candidates: out,
        suppressedCount: suppressed.length,
        suppressed: suppressed
    };
}

function _appendPageTextlessGraphicGroupCandidates(candidates, sourceItems, candidateSeen, sourceIndex) {
    if (!candidates || candidates.length === 0) {
        return { appendedCount: 0, componentCount: 0, components: [] };
    }
    var indexes = null;
    try { indexes = _buildSourceItemIndexes(sourceItems || []); } catch (eIndex) { indexes = null; }
    var sourceInfoById = indexes ? indexes.sourceInfoById || {} : {};
    var childIdsByParentId = indexes ? indexes.childIdsByParentId || {} : {};
    var pageWideBackgroundMinZByPage = {};
    var descendantIdsBySourceId = {};
    var clipCarryingParentIdBySourceId = {};
    var nonPageAncestorIdsBySourceId = {};
    var selfAndAncestorIdsBySourceId = {};
    var sourceExpansionByKey = {};

    function ids(candidate, field) {
        return _sortedNumericIds(candidate && candidate[field] || []);
    }

    function mergeIds(target, seen, values) {
        for (var i = 0; values && i < values.length; i++) {
            _pushUniqueId(target, seen, values[i]);
        }
    }

    function visibleExportIds(candidate) {
        var exportIds = ids(candidate, "exportSourceObjectIds");
        if (exportIds.length > 0) return exportIds;
        var visualIds = ids(candidate, "visualSourceObjectIds");
        if (visualIds.length > 0) return visualIds;
        return ids(candidate, "sourceObjectIds");
    }

    function sourceIds(candidate) {
        return ids(candidate, "sourceObjectIds");
    }

    function info(id) {
        return sourceInfoById ? sourceInfoById[String(id)] : null;
    }

    function sourceIsTextLike(id) {
        var src = info(id);
        if (!src) return false;
        var kind = String(src.kind || "");
        return kind === "TextFrame" || kind === "Story" || kind === "Character"
                || kind === "InsertionPoint" || kind === "Cell";
    }

    function sourceIsPageRootCandidate(src) {
        if (!src || src.id === null || src.id === undefined) return false;
        if (src.parentId === null || src.parentId === undefined) return true;
        var parent = info(src.parentId);
        var parentKind = parent ? String(parent.kind || "") : String(src.parentKind || "");
        return parentKind === "Page" || parentKind === "Spread"
                || parentKind === "MasterSpread" || parentKind === "Document";
    }

    function sourceBoundsArea(b) {
        if (!b || b.length < 4) return 0;
        return Math.max(0, Number(b[2]) - Number(b[0]))
                * Math.max(0, Number(b[3]) - Number(b[1]));
    }

    function pageBounds(pageIndex) {
        try {
            if (sourceIndex && sourceIndex.pageBounds) return sourceIndex.pageBounds(Number(pageIndex));
        } catch (ePageBounds) {}
        return null;
    }

    function boundsIntersects(a, b) {
        if (!a || !b || a.length < 4 || b.length < 4) return false;
        return a[2] > b[0] && a[0] < b[2] && a[3] > b[1] && a[1] < b[3];
    }

    function sourceLooksLikePageWideSingleColorFill(src, pageIndex) {
        if (!src || !src.bounds || src.bounds.length < 4) return false;
        if (src.hasPlacedVisual === true || src.hasPlacedVisualInSubtree === true) return false;
        if (src.hasVisibleFill !== true || src.hasVisibleStroke === true) return false;
        if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) return false;
        var kind = String(src.kind || "");
        if (kind !== "Rectangle" && kind !== "Polygon" && kind !== "Oval") return false;
        var pb = pageBounds(pageIndex);
        if (!pb || pb.length < 4 || !boundsIntersects(src.bounds, pb)) return false;
        var pageWidth = Math.max(0, Number(pb[3]) - Number(pb[1]));
        var pageHeight = Math.max(0, Number(pb[2]) - Number(pb[0]));
        if (pageWidth <= 0 || pageHeight <= 0) return false;
        var b = src.bounds;
        var width = Math.max(0, Number(b[3]) - Number(b[1]));
        var height = Math.max(0, Number(b[2]) - Number(b[0]));
        var touchesLeft = Number(b[1]) <= Number(pb[1]) + 1.0;
        var touchesRight = Number(b[3]) >= Number(pb[3]) - 1.0;
        var touchesTop = Number(b[0]) <= Number(pb[0]) + 1.0;
        var touchesBottom = Number(b[2]) >= Number(pb[2]) - 1.0;
        var spansWidth = width >= pageWidth * 0.85 && touchesLeft && touchesRight;
        var spansHeight = height >= pageHeight * 0.85 && touchesTop && touchesBottom;
        return spansWidth || spansHeight;
    }

    function minPageWideBackgroundZ(pageIndex) {
        var key = String(pageIndex);
        if (pageWideBackgroundMinZByPage.hasOwnProperty(key)) {
            return pageWideBackgroundMinZByPage[key];
        }
        var minZ = null;
        var pb = pageBounds(pageIndex);
        for (var i = 0; sourceItems && i < sourceItems.length; i++) {
            var src = sourceItems[i];
            if (!src || !src.bounds || src.bounds.length < 4) continue;
            if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) continue;
            if (src.isInline === true) continue;
            if (!pb || pb.length < 4 || !boundsIntersects(src.bounds, pb)) continue;
            var z = Number(src.zOrder || 0);
            if (minZ === null || z < minZ) minZ = z;
        }
        pageWideBackgroundMinZByPage[key] = minZ;
        return minZ;
    }

    function descendantIds(sourceId) {
        var cacheKey = String(sourceId);
        if (descendantIdsBySourceId[cacheKey]) return descendantIdsBySourceId[cacheKey];
        var out = [];
        var seen = {};
        function visit(id) {
            if (id === null || id === undefined || seen[String(id)]) return;
            seen[String(id)] = true;
            out.push(id);
            var children = childIdsByParentId[String(id)] || [];
            for (var ci = 0; ci < children.length; ci++) visit(children[ci]);
        }
        visit(sourceId);
        out = _sortedNumericIds(out);
        descendantIdsBySourceId[cacheKey] = out;
        return out;
    }

    function clipCarryingParentId(id) {
        var key = String(id);
        if (clipCarryingParentIdBySourceId.hasOwnProperty(key)) {
            return clipCarryingParentIdBySourceId[key];
        }
        var out = null;
        try {
            if (sourceIndex && sourceIndex.clipCarryingParentIdOfSource) {
                out = sourceIndex.clipCarryingParentIdOfSource(id);
            }
        } catch (eClipParent) {}
        clipCarryingParentIdBySourceId[key] = out;
        return out;
    }

    function expandSourceIds(sourceIds, includeTextLike, includeInlineFlow) {
        var expansionRoots = sourceIds;
        if (sourceIds && sourceIds.length > 128) {
            expansionRoots = topLevelStructuralIds(sourceIds);
        }
        var cacheKey = _sourceSetKey(_sortedNumericIds(expansionRoots || []))
                + "|text:" + (includeTextLike ? "1" : "0")
                + "|inline:" + (includeInlineFlow ? "1" : "0");
        if (sourceExpansionByKey[cacheKey]) return sourceExpansionByKey[cacheKey];
        var out = [];
        var seen = {};
        var expanded = [];
        var expandedSet = {};
        for (var i = 0; expansionRoots && i < expansionRoots.length; i++) {
            var descendants = descendantIds(expansionRoots[i]);
            for (var di = 0; di < descendants.length; di++) {
                _pushUniqueId(expanded, expandedSet, descendants[di]);
            }
        }
        for (var ei = 0; ei < expanded.length; ei++) {
            var id = expanded[ei];
            var clipParentId = clipCarryingParentId(id);
            if (clipParentId !== null && clipParentId !== undefined
                    && !expandedSet[String(clipParentId)]) {
                continue;
            }
            if (!includeTextLike && sourceIsTextLike(id)) continue;
            if (includeInlineFlow !== true && sourceIsInlineFlow(id)) continue;
            if (sourceIsStoryAnchoredMaterial(id)) continue;
            _pushUniqueId(out, seen, id);
        }
        out = _sortedNumericIds(out);
        sourceExpansionByKey[cacheKey] = out;
        return out;
    }

    function sourceIsInlineFlow(id) {
        var src = info(id);
        if (!src) return false;
        if (typeof _isInlineFlowItemBySourceInfo === "function"
                && _isInlineFlowItemBySourceInfo(src)) {
            return true;
        }
        return false;
    }

    function sourceIsStoryAnchoredMaterial(id) {
        var src = info(id);
        if (!src) return false;
        var anchoredPosition = String(src.anchoredPosition || "").toUpperCase();
        var storyAnchorPlacement = String(src.storyAnchorPlacement || "").toUpperCase();
        return anchoredPosition === "ANCHORED"
                || storyAnchorPlacement === "FLOATING_ANCHORED";
    }

    function sourcePageIndex(id) {
        var src = info(id);
        return src && src.pageIndex !== undefined && src.pageIndex !== null
                ? Number(src.pageIndex)
                : null;
    }

    function sourceHasCrossPageClipParent(id, pageIndex) {
        var clipParentId = clipCarryingParentId(id);
        if (clipParentId === null || clipParentId === undefined) return false;
        var clipPageIndex = sourcePageIndex(clipParentId);
        return clipPageIndex !== null
                && pageIndex !== null
                && pageIndex !== undefined
                && Number(clipPageIndex) !== Number(pageIndex);
    }

    function candidateHasCrossPageClipParentContext(candidate, pageIndex) {
        var values = visibleExportIds(candidate)
                .concat(sourceIds(candidate))
                .concat(candidate && candidate.visualSourceObjectIds || []);
        var seen = {};
        for (var i = 0; i < values.length; i++) {
            var id = values[i];
            if (id === null || id === undefined || seen[String(id)]) continue;
            seen[String(id)] = true;
            if (sourceHasCrossPageClipParent(id, pageIndex)) return true;
        }
        return false;
    }

    function sourceHasPageWideBackgroundShapeOnPage(src, rolePageIndex) {
        if (!sourceLooksLikePageWideSingleColorFill(src, rolePageIndex)) return false;
        if (_isBackgroundLayerName(src.layerName)) return true;
        return true;
    }

    function sourceHasPageWideBackgroundShape(id, pageIndex) {
        var src = info(id);
        if (!src) return false;
        if (sourceHasPageWideBackgroundShapeOnPage(src, pageIndex)) return true;
        if (src.pageIndex !== null && src.pageIndex !== undefined
                && String(src.pageIndex) !== String(pageIndex)) {
            return sourceHasPageWideBackgroundShapeOnPage(src, src.pageIndex);
        }
        return false;
    }

    function candidateHasBackgroundRole(candidate) {
        if (!candidate) return false;
        if (candidate.passId === "pass.page_backgrounds") return true;
        if (candidate.compositeRole === "background_vector_source") return true;
        if (candidate.slotRole === "background_shell_slot") return true;
        if (candidate.visualLayer === "PAGE_BACKGROUND") return true;
        var src = sourceIds(candidate);
        for (var i = 0; i < src.length; i++) {
            if (sourceHasPageWideBackgroundShape(src[i], candidate.pageIndex)) return true;
        }
        return false;
    }

    function candidateHasEditableTextSignal(candidate) {
        if (!candidate) return false;
        if (candidate.containsEditableText === true) return true;
        if (candidate.ownedTextFrameIds && candidate.ownedTextFrameIds.length > 0) return true;
        if (candidate.editableTextFrameIds && candidate.editableTextFrameIds.length > 0) return true;
        if (candidate.hiddenTextFrameIds && candidate.hiddenTextFrameIds.length > 0) return true;
        if (candidate.hiddenVisualSourceObjectIds) {
            for (var i = 0; i < candidate.hiddenVisualSourceObjectIds.length; i++) {
                var src = info(candidate.hiddenVisualSourceObjectIds[i]);
                if (src && String(src.kind || "") === "TextFrame"
                        && src.textFrameClass === "editable") {
                    return true;
                }
            }
        }
        return false;
    }

    function markBackgroundCandidate(candidate) {
        if (!candidate || candidate.passId === "pass.page_backgrounds") return false;
        if (!candidateHasBackgroundRole(candidate)) return false;
        if (candidateHasEditableTextSignal(candidate)) return false;
        candidate.disabled = false;
        candidate.compositeRole = "background_vector_source";
        candidate.slotRole = "background_shell_slot";
        candidate.materialization = "EXTRACTED_PNG_VECTOR";
        candidate.textAction = "DROP_TEXT";
        candidate.visualAction = "PLACE_FLOATING_PNG";
        candidate.visualLayer = "CONTENT_VISUAL";
        candidate.placement = "FLOATING";
        candidate.coordinateSpace = "PAGE";
        candidate.ownershipSlot = "SHELL_SLOT";
        candidate.backgroundDeferred = false;
        candidate.backgroundReason = "page_wide_single_color_fill_excluded_from_overlap_group";
        return true;
    }

    function bounds(candidate) {
        var b = candidate && candidate.bounds;
        if (!b || b.length < 4) return null;
        return [Number(b[0]), Number(b[1]), Number(b[2]), Number(b[3])];
    }

    function sourceBounds(id) {
        var src = info(id);
        if (!src || !src.bounds || src.bounds.length < 4) return null;
        return [
            Number(src.bounds[0]),
            Number(src.bounds[1]),
            Number(src.bounds[2]),
            Number(src.bounds[3])
        ];
    }

    function boundsHasArea(b) {
        return b && (b[2] - b[0]) > 0.1 && (b[3] - b[1]) > 0.1;
    }

    function boundsOverlapWithPad(a, b, pad) {
        if (!a || !b) return false;
        return a[2] >= b[0] - pad && a[0] <= b[2] + pad
                && a[3] >= b[1] - pad && a[1] <= b[3] + pad;
    }

    function boundsAreaValue(b) {
        if (!boundsHasArea(b)) return 0;
        return Math.max(0, b[2] - b[0]) * Math.max(0, b[3] - b[1]);
    }

    function boundsIntersectionArea(a, b) {
        if (!a || !b) return 0;
        var top = Math.max(a[0], b[0]);
        var left = Math.max(a[1], b[1]);
        var bottom = Math.min(a[2], b[2]);
        var right = Math.min(a[3], b[3]);
        if (bottom <= top || right <= left) return 0;
        return (bottom - top) * (right - left);
    }

    function boundsBroadlyContains(a, b) {
        var aArea = boundsAreaValue(a);
        var bArea = boundsAreaValue(b);
        if (aArea <= 0 || bArea <= 0) return false;
        var small = Math.min(aArea, bArea);
        var large = Math.max(aArea, bArea);
        if (large < small * 4.0) return false;
        var intersection = boundsIntersectionArea(a, b);
        return intersection >= small * 0.92;
    }

    function boundsMostlyContains(container, child) {
        var childArea = boundsAreaValue(child);
        if (childArea <= 0) return false;
        return boundsIntersectionArea(container, child) >= childArea * 0.92;
    }

    function axisOverlapRatio(aMin, aMax, bMin, bMax) {
        var overlap = Math.min(aMax, bMax) - Math.max(aMin, bMin);
        if (overlap <= 0) return 0;
        var minLen = Math.min(Math.max(0, aMax - aMin), Math.max(0, bMax - bMin));
        return minLen > 0 ? overlap / minLen : 0;
    }

    function axisGap(aMin, aMax, bMin, bMax) {
        if (aMax < bMin) return bMin - aMax;
        if (bMax < aMin) return aMin - bMax;
        return 0;
    }

    function boundsVisuallyAdjacent(a, b) {
        if (!boundsHasArea(a) || !boundsHasArea(b)) return false;
        if (boundsBroadlyContains(a, b)) return false;
        var minArea = Math.min(boundsAreaValue(a), boundsAreaValue(b));
        if (minArea > 0 && boundsIntersectionArea(a, b) >= minArea * 0.03) return true;
        var aHeight = Math.max(0, a[2] - a[0]);
        var bHeight = Math.max(0, b[2] - b[0]);
        var aWidth = Math.max(0, a[3] - a[1]);
        var bWidth = Math.max(0, b[3] - b[1]);
        var yOverlap = axisOverlapRatio(a[0], a[2], b[0], b[2]);
        var xGap = axisGap(a[1], a[3], b[1], b[3]);
        var rowGapLimit = Math.max(3.0, Math.min(6.0, Math.min(aHeight, bHeight) * 0.35));
        if (yOverlap >= 0.35 && xGap <= rowGapLimit) return true;
        var xOverlap = axisOverlapRatio(a[1], a[3], b[1], b[3]);
        var yGap = axisGap(a[0], a[2], b[0], b[2]);
        var columnGapLimit = Math.max(3.0, Math.min(6.0, Math.min(aHeight, bHeight) * 0.15));
        return xOverlap >= 0.65 && yGap > 0.1 && yGap <= columnGapLimit;
    }

    function entryRootZRange(entry) {
        if (!entry || !entry.candidate) return null;
        var ids = (entry.candidate.exportSourceObjectIds || [])
                .concat(entry.candidate.visualSourceObjectIds || [])
                .concat(entry.candidate.sourceObjectIds || []);
        if (!ids || ids.length === 0) return null;
        ids = depthGuardTopLevelStructuralIds(ids);
        var min = null;
        var max = null;
        for (var i = 0; ids && i < ids.length; i++) {
            var src = info(ids[i]);
            if (!src || src.zOrder === null || src.zOrder === undefined) continue;
            var z = Number(src.zOrder);
            if (isNaN(z)) continue;
            min = min === null ? z : Math.min(min, z);
            max = max === null ? z : Math.max(max, z);
        }
        if (min === null || max === null) {
            var fallback = Number(entry.candidate.zOrder || 0);
            if (isNaN(fallback)) return null;
            min = fallback;
            max = fallback;
        }
        return { min: min, max: max };
    }

    function depthGuardTopLevelStructuralIds(ids) {
        ids = _sortedNumericIds(ids || []);
        if (ids.length <= 1) return ids;
        var sourceSet = {};
        for (var i = 0; i < ids.length; i++) sourceSet[String(ids[i])] = true;
        var out = [];
        var seen = {};
        for (var ii = 0; ii < ids.length; ii++) {
            var id = ids[ii];
            var cur = info(id);
            var hasParentInSet = false;
            var guard = 0;
            while (cur && guard++ < 64) {
                var parentId = cur.parentId;
                if (parentId === null || parentId === undefined) break;
                if (sourceSet[String(parentId)]) {
                    hasParentInSet = true;
                    break;
                }
                cur = info(parentId);
            }
            if (!hasParentInSet) _pushUniqueId(out, seen, id);
        }
        return out.length > 0 && out.length < ids.length ? _sortedNumericIds(out) : ids;
    }

    function entriesSourceDepthCompatible(a, b) {
        var ar = entryRootZRange(a);
        var br = entryRootZRange(b);
        if (!ar || !br) return true;
        var gap = 0;
        if (ar.max < br.min) {
            gap = br.min - ar.max;
        } else if (br.max < ar.min) {
            gap = ar.min - br.max;
        }
        if (gap <= 8) return true;
        var aSpan = Math.max(0, ar.max - ar.min);
        var bSpan = Math.max(0, br.max - br.min);
        return gap <= Math.max(2, Math.min(aSpan, bSpan) * 0.25);
    }

    function unionBounds(a, b) {
        if (!a) return b ? b.slice(0) : null;
        if (!b) return a.slice(0);
        return [
            Math.min(a[0], b[0]),
            Math.min(a[1], b[1]),
            Math.max(a[2], b[2]),
            Math.max(a[3], b[3])
        ];
    }

    function zOrderOfCandidate(candidate) {
        if (candidate && candidate.zOrder !== undefined && candidate.zOrder !== null) {
            var z = Number(candidate.zOrder);
            if (!isNaN(z)) return z;
        }
        return 0;
    }

    function sourceHasAncestorInSet(id, sourceSet) {
        var cur = info(id);
        var guard = 0;
        while (cur && guard++ < 64) {
            var parentId = cur.parentId;
            if (parentId === null || parentId === undefined) return false;
            if (sourceSet[String(parentId)]) return true;
            cur = info(parentId);
        }
        return false;
    }

    function nonPageAncestorIds(id) {
        var key = String(id);
        if (nonPageAncestorIdsBySourceId[key]) return nonPageAncestorIdsBySourceId[key];
        var out = [];
        var seen = {};
        var cur = info(id);
        var guard = 0;
        while (cur && guard++ < 64) {
            var kind = String(cur.kind || "");
            if (kind === "Page" || kind === "Spread" || kind === "Document"
                    || kind === "Story" || kind === "Character"
                    || kind === "InsertionPoint" || kind === "Cell") {
                break;
            }
            if (cur.id !== null && cur.id !== undefined) {
                _pushUniqueId(out, seen, cur.id);
            }
            if (cur.parentId === null || cur.parentId === undefined) break;
            cur = info(cur.parentId);
        }
        nonPageAncestorIdsBySourceId[key] = out;
        return out;
    }

    function idSetFromIds(ids) {
        var set = {};
        var count = 0;
        for (var i = 0; ids && i < ids.length; i++) {
            if (ids[i] === null || ids[i] === undefined) continue;
            var key = String(ids[i]);
            if (set[key]) continue;
            set[key] = true;
            count++;
        }
        set.__count = count;
        return set;
    }

    function setsIntersect(a, b) {
        if (!a || !b) return false;
        var left = a;
        var right = b;
        if (a.__count !== undefined && b.__count !== undefined && Number(a.__count) > Number(b.__count)) {
            left = b;
            right = a;
        }
        for (var key in left) {
            if (!left.hasOwnProperty(key) || key === "__count") continue;
            if (right[key]) return true;
        }
        return false;
    }

    function selfAndAncestorIds(id) {
        var key = String(id);
        if (selfAndAncestorIdsBySourceId[key]) return selfAndAncestorIdsBySourceId[key];
        var out = [];
        var seen = {};
        var cur = info(id);
        var guard = 0;
        while (cur && guard++ < 64) {
            if (cur.id !== null && cur.id !== undefined && !seen[String(cur.id)]) {
                seen[String(cur.id)] = true;
                out.push(cur.id);
            }
            if (cur.parentId === null || cur.parentId === undefined) break;
            cur = info(cur.parentId);
        }
        selfAndAncestorIdsBySourceId[key] = out;
        return out;
    }

    function selfAndAncestorSet(ids) {
        var set = {};
        var count = 0;
        for (var i = 0; ids && i < ids.length; i++) {
            var lineage = selfAndAncestorIds(ids[i]);
            for (var li = 0; li < lineage.length; li++) {
                var key = String(lineage[li]);
                if (set[key]) continue;
                set[key] = true;
                count++;
            }
        }
        set.__count = count;
        return set;
    }

    function nonPageAncestorSetForIds(ids) {
        var set = {};
        var count = 0;
        for (var i = 0; ids && i < ids.length; i++) {
            var ancestors = nonPageAncestorIds(ids[i]);
            for (var ai = 0; ai < ancestors.length; ai++) {
                var key = String(ancestors[ai]);
                if (set[key]) continue;
                set[key] = true;
                count++;
            }
        }
        set.__count = count;
        return set;
    }

    function sourceIdsHaveAncestorRelation(aIds, bIds) {
        var bSet = {};
        for (var bi = 0; bIds && bi < bIds.length; bi++) bSet[String(bIds[bi])] = true;
        for (var ai = 0; aIds && ai < aIds.length; ai++) {
            if (bSet[String(aIds[ai])]) return true;
            if (sourceHasAncestorInSet(aIds[ai], bSet)) return true;
        }
        var aSet = {};
        for (var aj = 0; aIds && aj < aIds.length; aj++) aSet[String(aIds[aj])] = true;
        for (var bj = 0; bIds && bj < bIds.length; bj++) {
            if (sourceHasAncestorInSet(bIds[bj], aSet)) return true;
        }
        return false;
    }

    function entriesShareStructuralRoot(a, b) {
        if (!a || !b) return false;
        if (a._sourceIdSet && b._sourceIdSet
                && a._selfAndAncestorSet && b._selfAndAncestorSet
                && a._nonPageAncestorSet && b._nonPageAncestorSet) {
            if (setsIntersect(a._selfAndAncestorSet, b._sourceIdSet)
                    || setsIntersect(b._selfAndAncestorSet, a._sourceIdSet)) {
                return true;
            }
            return setsIntersect(a._nonPageAncestorSet, b._nonPageAncestorSet);
        }
        var aIds = (a.sourceIds || []).concat(a.exportIds || []);
        var bIds = (b.sourceIds || []).concat(b.exportIds || []);
        if (sourceIdsHaveAncestorRelation(aIds, bIds)) return true;

        var aAncestors = {};
        for (var ai = 0; ai < aIds.length; ai++) {
            var aa = nonPageAncestorIds(aIds[ai]);
            for (var aai = 0; aai < aa.length; aai++) aAncestors[String(aa[aai])] = true;
        }
        for (var bi = 0; bi < bIds.length; bi++) {
            var ba = nonPageAncestorIds(bIds[bi]);
            for (var bai = 0; bai < ba.length; bai++) {
                if (aAncestors[String(ba[bai])]) return true;
            }
        }
        return false;
    }

    function entryLooksConcreteVisualContainer(entry) {
        if (!entry) return false;
        var idsToCheck = (entry.exportIds || []).concat(entry.sourceIds || []);
        var seen = {};
        for (var i = 0; i < idsToCheck.length; i++) {
            var id = idsToCheck[i];
            if (seen[String(id)]) continue;
            seen[String(id)] = true;
            var src = info(id);
            if (!src) continue;
            if (sourceIsTextlessTextFrameShellMaterial(src)) return true;
            var kind = String(src.kind || "");
            if (kind !== "Rectangle" && kind !== "Oval" && kind !== "Polygon") continue;
            if (src.hasPlacedVisual === true || src.hasPlacedVisualInSubtree === true) continue;
            if (src.hasVisibleFill === true || src.hasVisibleStroke === true
                    || src.hasCandidateVectorPaint === true) {
                return true;
            }
        }
        return false;
    }

    function entriesContainmentConnected(a, b, structurallyConnected) {
        if (!a || !b) return false;
        if (boundsMostlyContains(a.bounds, b.bounds)) {
            if (!structurallyConnected && entryIsPageSpanningPlacedVisualCarrier(a)) {
                return false;
            }
            return structurallyConnected || entryLooksConcreteVisualContainer(a);
        }
        if (boundsMostlyContains(b.bounds, a.bounds)) {
            if (!structurallyConnected && entryIsPageSpanningPlacedVisualCarrier(b)) {
                return false;
            }
            return structurallyConnected || entryLooksConcreteVisualContainer(b);
        }
        return false;
    }

    function candidateHasSingleTopLevelStructuralRoot(candidate) {
        if (!candidate) return false;
        var ids = visibleExportIds(candidate);
        if (!ids || ids.length === 0) ids = sourceIds(candidate);
        ids = topLevelStructuralIds(ids || []);
        return ids.length === 1;
    }

    function entrySpansPageCarrierBounds(entryBounds, pageIndex) {
        if (!boundsHasArea(entryBounds)) return false;
        var pb = pageBounds(pageIndex);
        if (!pb || pb.length < 4 || !boundsIntersects(entryBounds, pb)) return false;
        var pageWidth = Math.max(0, Number(pb[3]) - Number(pb[1]));
        var pageHeight = Math.max(0, Number(pb[2]) - Number(pb[0]));
        if (pageWidth <= 0 || pageHeight <= 0) return false;
        var width = Math.max(0, Number(entryBounds[3]) - Number(entryBounds[1]));
        var height = Math.max(0, Number(entryBounds[2]) - Number(entryBounds[0]));
        var touchesLeft = Number(entryBounds[1]) <= Number(pb[1]) + 1.0;
        var touchesRight = Number(entryBounds[3]) >= Number(pb[3]) - 1.0;
        var touchesTop = Number(entryBounds[0]) <= Number(pb[0]) + 1.0;
        var touchesBottom = Number(entryBounds[2]) >= Number(pb[2]) - 1.0;
        var spansWidth = width >= pageWidth * 0.85 && touchesLeft && touchesRight;
        var spansHeight = height >= pageHeight * 0.85 && touchesTop && touchesBottom;
        return spansWidth || spansHeight;
    }

    function entryIsPageSpanningPlacedVisualCarrier(entry) {
        if (!entry || !entry.candidate) return false;
        var candidate = entry.candidate;
        if (!candidateIsPlacedVisualCarrier(candidate)) return false;
        if (!candidateHasSingleTopLevelStructuralRoot(candidate)) return false;
        var pageIndex = candidate.pageIndex;
        if (pageIndex === null || pageIndex === undefined) return false;
        return entrySpansPageCarrierBounds(entry.bounds, Number(pageIndex));
    }

    function visibleBoundsSourceId(id, sourceSet) {
        var src = info(id);
        if (!src) return id;
        if (String(src.kind || "") !== "Image") return id;
        var parentId = src.parentId;
        var parent = parentId !== null && parentId !== undefined ? info(parentId) : null;
        if (!parent || !parent.bounds) return id;
        var parentKind = String(parent.kind || "");
        if (parentKind !== "Rectangle" && parentKind !== "Oval" && parentKind !== "Polygon") return id;
        return parentId;
    }

    function effectiveCandidateBounds(candidate) {
        var candidateBounds = bounds(candidate);
        var idsForBounds = visibleExportIds(candidate);
        if (!idsForBounds || idsForBounds.length === 0) idsForBounds = sourceIds(candidate);
        if (!idsForBounds || idsForBounds.length === 0) return candidateBounds;
        if (idsForBounds.length > 256 && boundsHasArea(candidateBounds)) {
            return candidateBounds;
        }

        var sourceSet = {};
        for (var i = 0; i < idsForBounds.length; i++) sourceSet[String(idsForBounds[i])] = true;

        var b = null;
        var used = {};
        for (var si = 0; si < idsForBounds.length; si++) {
            var id = idsForBounds[si];
            if (sourceHasAncestorInSet(id, sourceSet)) continue;
            var boundsId = visibleBoundsSourceId(id, sourceSet);
            if (used[String(boundsId)]) continue;
            used[String(boundsId)] = true;
            var sb = sourceBounds(boundsId);
            if (!boundsHasArea(sb)) continue;
            b = unionBounds(b, sb);
        }
        return boundsHasArea(b) ? b : candidateBounds;
    }

    function topLevelStructuralIds(ids) {
        ids = _sortedNumericIds(ids || []);
        if (ids.length <= 128) return ids;
        var sourceSet = {};
        for (var i = 0; i < ids.length; i++) sourceSet[String(ids[i])] = true;
        var out = [];
        var seen = {};
        for (var ii = 0; ii < ids.length; ii++) {
            var id = ids[ii];
            if (sourceHasAncestorInSet(id, sourceSet)) continue;
            _pushUniqueId(out, seen, id);
        }
        return out.length > 0 && out.length < ids.length ? _sortedNumericIds(out) : ids;
    }

    function isEligible(candidate) {
        if (!candidate || candidate.disabled === true) return false;
        if (_candidateIsProtectedDecorationSlot(candidate)) return false;
        if (candidate.passId === "pass.page_textless_graphic_groups") return false;
        if (candidate.passId !== "pass.decoration_groups"
                && candidate.passId !== "pass.image_textless_groups"
                && candidate.passId !== "pass.image_placed_frames"
                && candidate.passId !== "pass.vector_shape_frames"
                && candidate.passId !== "pass.complex_graphic_frames") {
            return false;
        }
        if (candidateHasBackgroundRole(candidate)) return false;
        if (candidate.placement === "INLINE") return false;
        if (candidate.visualAction === "DROP_VISUAL") return false;
        if (candidate.materialization === "HWPX_TEXT"
                || candidate.materialization === "HWPX_TABLE_STYLE"
                || candidate.materialization === "NATIVE_SOURCE_SHAPE") {
            return false;
        }
        var pageIndex = candidate.pageIndex;
        if (pageIndex === null || pageIndex === undefined || Number(pageIndex) < 0) return false;
        if (candidateHasCrossPageClipParentContext(candidate, pageIndex)) return false;
        var b = bounds(candidate);
        if (!boundsHasArea(b)) return false;
        var exportIds = visibleExportIds(candidate);
        if (exportIds.length === 0) return false;
        var hasNonTextVisual = false;
        for (var i = 0; i < exportIds.length; i++) {
            if (sourceIsInlineFlow(exportIds[i])) return false;
            if (sourceIsStoryAnchoredMaterial(exportIds[i])) return false;
            if (!sourceIsTextLike(exportIds[i])) hasNonTextVisual = true;
        }
        return hasNonTextVisual;
    }

    function candidateEntry(candidate, index) {
        var entrySourceIds = sourceIds(candidate);
        var entryExportIds = visibleExportIds(candidate);
        var structuralIds = unionSourceIds(entrySourceIds, entryExportIds || []);
        var compactStructuralIds = topLevelStructuralIds(structuralIds);
        return {
            candidate: candidate,
            index: index,
            pageIndex: Number(candidate.pageIndex),
            bounds: effectiveCandidateBounds(candidate),
            sourceIds: entrySourceIds,
            exportIds: entryExportIds,
            zOrder: zOrderOfCandidate(candidate),
            _sourceIdSet: idSetFromIds(compactStructuralIds),
            _selfAndAncestorSet: selfAndAncestorSet(compactStructuralIds),
            _nonPageAncestorSet: nonPageAncestorSetForIds(compactStructuralIds),
            _structuralSourceCount: structuralIds.length,
            _compactStructuralSourceCount: compactStructuralIds.length
        };
    }

    for (var bi = 0; bi < candidates.length; bi++) {
        markBackgroundCandidate(candidates[bi]);
    }

    var byPage = {};
    for (var ci = 0; ci < candidates.length; ci++) {
        var candidate = candidates[ci];
        if (!isEligible(candidate)) continue;
        var entry = candidateEntry(candidate, ci);
        var pageKey = String(entry.pageIndex);
        if (!byPage[pageKey]) byPage[pageKey] = [];
        byPage[pageKey].push(entry);
    }

    function buildComponents(entries) {
        var components = [];
        var used = {};
        var pad = 0.5;
        var structuralRelationCache = {};
        for (var ei = 0; ei < entries.length; ei++) entries[ei]._componentEntryIndex = ei;
        function entriesAreConnected(aIndex, bIndex) {
            var key = aIndex < bIndex ? String(aIndex) + "|" + String(bIndex) : String(bIndex) + "|" + String(aIndex);
            if (structuralRelationCache.hasOwnProperty(key)) return structuralRelationCache[key];
            var structurallyConnected = boundsOverlapWithPad(entries[aIndex].bounds, entries[bIndex].bounds, pad)
                    && entriesShareStructuralRoot(entries[aIndex], entries[bIndex]);
            var connected = structurallyConnected
                    || entriesContainmentConnected(entries[aIndex], entries[bIndex], structurallyConnected)
                    || (entriesSourceDepthCompatible(entries[aIndex], entries[bIndex])
                            && boundsVisuallyAdjacent(entries[aIndex].bounds, entries[bIndex].bounds));
            structuralRelationCache[key] = connected;
            return connected;
        }
        for (var i = 0; i < entries.length; i++) {
            if (used[i]) continue;
            var component = [];
            var queue = [i];
            var queueIndex = 0;
            used[i] = true;
            while (queueIndex < queue.length) {
                var idx = queue[queueIndex++];
                component.push(entries[idx]);
                for (var j = 0; j < entries.length; j++) {
                    if (used[j]) continue;
                    var touches = false;
                    for (var k = 0; k < component.length; k++) {
                        if (entriesAreConnected(component[k]._componentEntryIndex, j)) {
                            touches = true;
                            break;
                        }
                    }
                    if (!touches) continue;
                    used[j] = true;
                    queue.push(j);
                }
            }
            if (component.length > 1) components.push(component);
        }
        return components;
    }

    function textFrameIdsFromSources(sourceIds) {
        var out = [];
        var seen = {};
        for (var i = 0; i < sourceIds.length; i++) {
            var src = info(sourceIds[i]);
            if (!src || String(src.kind || "") !== "TextFrame") continue;
            if (!isEditableTextFrameSource(src)) continue;
            _pushUniqueId(out, seen, sourceIds[i]);
        }
        return _sortedNumericIds(out);
    }

    function isEditableTextFrameSource(src) {
        if (!src || String(src.kind || "") !== "TextFrame") return false;
        if (src.textFrameClass === "editable") return true;
        if (src.hasText === true) return true;
        return Number(src.textLength || 0) > 0;
    }

    function sourceIsTextlessTextFrameShellMaterial(src) {
        if (!src || String(src.kind || "") !== "TextFrame") return false;
        if (src.hasText === true || Number(src.textLength || 0) > 0) return false;
        return src.hasVisibleFill === true || src.hasVisibleStroke === true;
    }

    function isInlineTextFrameSource(src) {
        if (!src) return false;
        return typeof _isInlineFlowItemBySourceInfo === "function"
                ? _isInlineFlowItemBySourceInfo(src)
                : (String(src.storyAnchorPlacement || "").toUpperCase() === "INLINE"
                    || String(src.anchoredPosition || "").toUpperCase() === "INLINE_POSITION"
                    || String(src.anchoredPosition || "").toUpperCase() === "INLINEPOSITION");
    }

    function splitTextFrameIdsForPageTextlessGroup(sourceIds) {
        var hidden = [];
        var hiddenSeen = {};
        var pngOwned = [];
        var pngOwnedSeen = {};
        for (var i = 0; i < sourceIds.length; i++) {
            var src = info(sourceIds[i]);
            if (!src || String(src.kind || "") !== "TextFrame") continue;
            if (sourceIsTextlessTextFrameShellMaterial(src)) continue;
            if (!isEditableTextFrameSource(src)) continue;
            if (src.simpleMarkerLabelContents === true && !isInlineTextFrameSource(src)) {
                _pushUniqueId(pngOwned, pngOwnedSeen, sourceIds[i]);
            } else {
                _pushUniqueId(hidden, hiddenSeen, sourceIds[i]);
            }
        }
        return {
            hiddenTextFrameIds: _sortedNumericIds(hidden),
            pngOwnedTextFrameIds: _sortedNumericIds(pngOwned)
        };
    }

    function pngOwnedTextAncestorSourceIds(sourceIds, textFrameIds) {
        var sourceSet = {};
        var out = [];
        var seen = {};
        for (var si = 0; sourceIds && si < sourceIds.length; si++) {
            sourceSet[String(sourceIds[si])] = true;
        }
        for (var ti = 0; textFrameIds && ti < textFrameIds.length; ti++) {
            var cur = info(textFrameIds[ti]);
            var lastNonTextAncestorId = null;
            var guard = 0;
            while (cur && cur.parentId !== null && cur.parentId !== undefined && guard++ < 64) {
                var parentId = cur.parentId;
                if (!sourceSet[String(parentId)]) break;
                var parent = info(parentId);
                if (!parent) break;
                var parentKind = String(parent.kind || "");
                if (parentKind !== "TextFrame" && parentKind !== "Story"
                        && parentKind !== "Character" && parentKind !== "InsertionPoint"
                        && parentKind !== "Cell") {
                    lastNonTextAncestorId = parentId;
                }
                cur = parent;
            }
            if (lastNonTextAncestorId !== null && !seen[String(lastNonTextAncestorId)]) {
                seen[String(lastNonTextAncestorId)] = true;
                out.push(lastNonTextAncestorId);
            }
        }
        return _sortedNumericIds(out);
    }

    function unionSourceIds(a, b) {
        var out = [];
        var seen = {};
        mergeIds(out, seen, a || []);
        mergeIds(out, seen, b || []);
        return _sortedNumericIds(out);
    }

    function subtractSourceIds(ids, removeIds) {
        var remove = {};
        for (var i = 0; removeIds && i < removeIds.length; i++) {
            remove[String(removeIds[i])] = true;
        }
        var out = [];
        var seen = {};
        for (var j = 0; ids && j < ids.length; j++) {
            if (remove[String(ids[j])]) continue;
            _pushUniqueId(out, seen, ids[j]);
        }
        return _sortedNumericIds(out);
    }

    function sourceHasVisiblePaint(id) {
        var src = info(id);
        if (!src) return false;
        if (sourceIsTextlessTextFrameShellMaterial(src)) return true;
        if (sourceIsTextLike(id)) return false;
        return src.hasCandidateVectorPaint === true
                || src.hasVisibleFill === true
                || src.hasVisibleStroke === true
                || src.hasPlacedVisual === true;
    }

    function visiblePaintSourceIds(sourceIds, pageIndex) {
        var out = [];
        var seen = {};
        for (var i = 0; i < sourceIds.length; i++) {
            var id = sourceIds[i];
            if (!sourceBelongsToPageTextlessGroupPage(id, pageIndex)) continue;
            if (!sourceHasVisiblePaint(id)) continue;
            if (sourceIsInlineFlow(id)) continue;
            if (sourceIsStoryAnchoredMaterial(id)) continue;
            if (sourceHasCrossPageClipParent(id, pageIndex)) continue;
            _pushUniqueId(out, seen, id);
        }
        return _sortedNumericIds(out);
    }

    function inlineVisualSourceIdsForPageTextlessGroup(sourceIds, pageIndex) {
        var out = [];
        var seen = {};
        for (var i = 0; sourceIds && i < sourceIds.length; i++) {
            var id = sourceIds[i];
            if (!sourceBelongsToPageTextlessGroupPage(id, pageIndex)) continue;
            if (!sourceHasVisiblePaint(id)) continue;
            if (sourceIsInlineFlow(id) || sourceIsStoryAnchoredMaterial(id)) {
                _pushUniqueId(out, seen, id);
            }
        }
        return _sortedNumericIds(out);
    }

    function filterPageTextlessGroupSourceIds(sourceIds, pageIndex) {
        var out = [];
        var seen = {};
        for (var i = 0; sourceIds && i < sourceIds.length; i++) {
            var id = sourceIds[i];
            if (!sourceBelongsToPageTextlessGroupPage(id, pageIndex)) continue;
            _pushUniqueId(out, seen, id);
        }
        return _sortedNumericIds(out);
    }

    function sourceBelongsToPageTextlessGroupPage(id, pageIndex) {
        var pi = sourcePageIndex(id);
        if (pi === null || pi === undefined || pageIndex === null || pageIndex === undefined) return true;
        if (Number(pi) === Number(pageIndex)) return true;
        return sourceHasCrossPageClipParent(id, pageIndex);
    }

    function sourceIsDescendantOf(sourceId, rootId) {
        if (sourceId === null || sourceId === undefined || rootId === null || rootId === undefined) return false;
        if (String(sourceId) === String(rootId)) return true;
        var cur = info(sourceId);
        var guard = 0;
        while (cur && cur.parentId !== null && cur.parentId !== undefined && guard++ < 128) {
            if (String(cur.parentId) === String(rootId)) return true;
            cur = info(cur.parentId);
        }
        return false;
    }

    function nearestClosedGroupRootForExportIds(sourceIds, exportIds, pageIndex, excludedTextFrameIds) {
        if (!sourceIds || !exportIds || exportIds.length < 128) return null;
        var sourceSet = {};
        for (var si = 0; si < sourceIds.length; si++) sourceSet[String(sourceIds[si])] = true;
        var first = info(exportIds[0]);
        var roots = [];
        var guard = 0;
        while (first && guard++ < 128) {
            var firstKind = String(first.kind || "");
            if (sourceSet[String(first.id)] && firstKind === "Group"
                    && sourceBelongsToPageTextlessGroupPage(first.id, pageIndex)) {
                roots.push(first.id);
            }
            if (first.parentId === null || first.parentId === undefined) break;
            first = info(first.parentId);
        }
        for (var ri = 0; ri < roots.length; ri++) {
            var rootId = roots[ri];
            var containsAll = true;
            for (var ei = 0; ei < exportIds.length; ei++) {
                if (!sourceIsDescendantOf(exportIds[ei], rootId)) {
                    containsAll = false;
                    break;
                }
            }
            if (containsAll) return rootId;
        }
        return null;
    }

    function sourceHasAncestorInIdSet(id, idSet) {
        var cur = info(id);
        var guard = 0;
        while (cur && cur.parentId !== null && cur.parentId !== undefined && guard++ < 128) {
            if (idSet[String(cur.parentId)]) return true;
            cur = info(cur.parentId);
        }
        return false;
    }

    function atomicExportTargetIdsForExportIds(sourceIds, exportIds, pageIndex, excludedTextFrameIds) {
        if (exportIds && exportIds.length >= 128) {
            var topExportIds = topLevelStructuralIds(exportIds);
            if (topExportIds.length > 0 && topExportIds.length < exportIds.length) {
                var topOut = [];
                var topSeen = {};
                for (var ti = 0; ti < topExportIds.length; ti++) {
                    var topId = topExportIds[ti];
                    if (!sourceBelongsToPageTextlessGroupPage(topId, pageIndex)) continue;
                    var topSrc = info(topId);
                    if (!topSrc || sourceIsTextLike(topId)) continue;
                    _pushUniqueId(topOut, topSeen, topId);
                }
                if (topOut.length > 0) return _sortedNumericIds(topOut);
            }
        }
        var rootId = nearestClosedGroupRootForExportIds(
                sourceIds, exportIds, pageIndex, excludedTextFrameIds);
        if (rootId !== null && rootId !== undefined) return [rootId];
        if (!exportIds || exportIds.length < 128) return [];
        var exportSet = {};
        for (var ei = 0; ei < exportIds.length; ei++) exportSet[String(exportIds[ei])] = true;
        var out = [];
        var seen = {};
        for (var i = 0; i < exportIds.length; i++) {
            var id = exportIds[i];
            if (sourceHasAncestorInIdSet(id, exportSet)) continue;
            if (!sourceBelongsToPageTextlessGroupPage(id, pageIndex)) continue;
            var src = info(id);
            if (!src || sourceIsTextLike(id)) continue;
            _pushUniqueId(out, seen, id);
        }
        out = _sortedNumericIds(out);
        return out.length > 0 && out.length < exportIds.length ? out : [];
    }

    function candidateIsPlacedVisualCarrier(candidate) {
        if (!candidate) return false;
        if (candidate.passId === "pass.image_placed_frames"
                || candidate.passId === "pass.image_textless_groups") {
            return true;
        }
        var exportIds = visibleExportIds(candidate);
        for (var i = 0; i < exportIds.length; i++) {
            var src = info(exportIds[i]);
            if (!src) continue;
            var kind = String(src.kind || "");
            if (kind === "Image" || kind === "PDF" || kind === "EPS"
                    || src.hasPlacedVisual === true) {
                return true;
            }
        }
        return false;
    }

    function componentHasDominantPlacedVisualCarrier(component, componentBounds) {
        if (!component || !boundsHasArea(componentBounds)) return false;
        var componentArea = boundsAreaValue(componentBounds);
        if (componentArea <= 0) return false;
        for (var i = 0; i < component.length; i++) {
            var entry = component[i];
            if (!entry || !candidateIsPlacedVisualCarrier(entry.candidate)) continue;
            var b = entry.bounds;
            if (!boundsHasArea(b)) continue;
            var intersection = boundsIntersectionArea(componentBounds, b);
            var entryArea = boundsAreaValue(b);
            if (entryArea <= 0) continue;
            if (intersection >= entryArea * 0.92
                    && entryArea >= componentArea * 0.55) {
                return true;
            }
        }
        return false;
    }

    function candidateIsTextShellOwner(candidate) {
        if (!candidate) return false;
        if (candidate.candidatePurpose === "SHELL_CANDIDATE") return true;
        if (candidate.visualAction === "PLACE_TEXT_SHELL") return true;
        if (candidate.ownershipSlot === "SHELL_SLOT") return true;
        var slotRole = String(candidate.slotRole || "");
        var compositeRole = String(candidate.compositeRole || "");
        return slotRole.indexOf("shell") >= 0 || compositeRole.indexOf("shell") >= 0;
    }

    function componentHasTextShellOwner(component) {
        for (var i = 0; component && i < component.length; i++) {
            if (candidateIsTextShellOwner(component[i] && component[i].candidate)) return true;
        }
        return false;
    }

    function appendNonShellLocalComponentCandidate(component, pageKey, coveredCandidateIds) {
        var localSourceIds = [], localSourceSeen = {};
        var localExportIds = [], localExportSeen = {};
        var localBounds = null;
        var localMinZ = null;
        var localEntryCount = 0;
        var localCoveredCandidateIds = [];
        for (var ei = 0; component && ei < component.length; ei++) {
            var entry = component[ei];
            if (!entry || candidateIsTextShellOwner(entry.candidate)) continue;
            mergeIds(localSourceIds, localSourceSeen, expandSourceIds(entry.sourceIds, true, false));
            mergeIds(localExportIds, localExportSeen, expandSourceIds(entry.exportIds, false, false));
            localBounds = unionBounds(localBounds, entry.bounds);
            localMinZ = localMinZ === null ? entry.zOrder : Math.min(localMinZ, entry.zOrder);
            localCoveredCandidateIds.push(entry.candidate.candidateId || null);
            localEntryCount++;
        }
        if (localEntryCount < 2) return null;
        localSourceIds = _sortedNumericIds(localSourceIds);
        localExportIds = _sortedNumericIds(localExportIds);
        localSourceIds = filterPageTextlessGroupSourceIds(localSourceIds, Number(pageKey));
        localExportIds = filterPageTextlessGroupSourceIds(localExportIds, Number(pageKey));
        localExportIds = unionSourceIds(localExportIds, visiblePaintSourceIds(localSourceIds, Number(pageKey)));
        if (localExportIds.length < 2 || localSourceIds.length < 2) return null;

        var localExecutionIds = localSourceIds.slice(0);
        var localVisualIds = localExportIds.slice(0);
        var localAtomicTargetIds = atomicExportTargetIdsForExportIds(
                localSourceIds, localExportIds, Number(pageKey), []);
        var localAtomicRootId = localAtomicTargetIds.length === 1 ? localAtomicTargetIds[0] : null;
        var localAtomic = localAtomicTargetIds.length > 0;
        var localCandidateId = _candidateCompositeId(
                "pass.page_textless_graphic_groups",
                Number(pageKey),
                localSourceIds,
                "visual_adjacency_local_non_shell");
        var localSeenKey = "pass.page_textless_graphic_groups|page:" + pageKey
                + "|src:" + _sourceSetKey(localSourceIds);
        if (candidateSeen && candidateSeen[localSeenKey]) return null;
        if (candidateSeen) candidateSeen[localSeenKey] = true;
        var candidate = {
            candidateId: localCandidateId,
            passId: "pass.page_textless_graphic_groups",
            sourceObjectIds: localSourceIds,
            executionSourceObjectIds: localExecutionIds,
            primarySourceObjectId: localSourceIds.length > 0 ? localSourceIds[0] : null,
            pageIndex: Number(pageKey),
            kind: "PageTextlessGraphicGroup",
            unit: "PAGE_GRAPHIC_GROUP",
            mode: "TEXTLESS_CANDIDATE",
            candidatePurpose: "CONTENT_CANDIDATE",
            bounds: localBounds,
            parentId: null,
            parentKind: "Page",
            anchoredPosition: null,
            storyAnchorPlacement: null,
            composite: true,
            compositeRole: "page_textless_graphic_group",
            slotRole: "page_textless_graphic_group",
            exportSourceObjectIds: localExportIds,
            exportTargetObjectId: localAtomicRootId,
            atomicExportTargetObjectId: localAtomicRootId,
            atomicExportTargetObjectIds: localAtomicTargetIds,
            atomicTextlessVectorContent: localAtomic,
            atomicContentVisualSlot: localAtomic,
            hiddenVisualSourceObjectIds: [],
            visualSourceObjectIds: localVisualIds,
            styleSourceObjectIds: [],
            ownedTextFrameIds: [],
            editableTextFrameIds: [],
            hiddenTextFrameIds: [],
            requiresTextHidden: false,
            textOwner: "none",
            containsEditableText: false,
            completePngTextAllowed: false,
            ownershipSlot: "CONTENT_VISUAL_SLOT",
            materialization: "EXTRACTED_PNG_VECTOR",
            textAction: "DROP_TEXT",
            visualAction: "PLACE_FLOATING_PNG",
            visualLayer: "CONTENT_VISUAL",
            placement: "FLOATING",
            coordinateSpace: "PAGE",
            zOrder: localMinZ !== null ? localMinZ : 0,
            coveredCandidateIds: localCoveredCandidateIds,
            required: false,
            reason: localAtomic
                    ? "atomic_page_textless_non_shell_component_from_text_shell_split"
                    : "page_textless_non_shell_component_from_text_shell_split"
        };
        candidates.push(candidate);
        return {
            candidateId: localCandidateId,
            pageIndex: Number(pageKey),
            coveredCandidateIds: localCoveredCandidateIds,
            coveredCandidateCount: localCoveredCandidateIds.length,
            sourceObjectCount: localSourceIds.length,
            exportSourceObjectCount: localExportIds.length,
            atomicTextlessVectorContent: localAtomic,
            atomicExportTargetObjectId: localAtomicRootId,
            atomicExportTargetObjectIds: localAtomicTargetIds,
            hiddenTextFrameCount: 0,
            pngOwnedTextFrameCount: 0,
            bounds: localBounds,
            splitFromCoveredCandidateIds: coveredCandidateIds || [],
            reason: "split_non_shell_component_from_text_shell_owner"
        };
    }

    function appendPageRootTextlessVisualPlaneCandidates() {
        var rootCandidates = [];
        var rootSeen = {};
        var rootDiagnostics = [];
        var pages = {};
        function ensurePageEntry(pageIndex) {
            var pageKey = String(pageIndex);
            if (!pages[pageKey]) {
                pages[pageKey] = {
                    pageIndex: pageIndex,
                    rootIds: [],
                    rootSeen: {},
                    sourceObjectIds: [],
                    sourceSeen: {},
                    excludedInlineSourceObjectIds: [],
                    exportSourceObjectIds: [],
                    exportSeen: {},
                    hiddenVisualSourceObjectIds: [],
                    hiddenVisualSeen: {},
                    hiddenTextFrameIds: [],
                    hiddenTextFrameSeen: {},
                    visualBounds: null,
                    minZOrder: null
                };
            }
            return pages[pageKey];
        }
        function candidatePageIndexesForSourceId(sourceId) {
            var src = info(sourceId);
            var pages = [];
            if (src && src.rangeTargetPageIndexes && src.rangeTargetPageIndexes.length > 0) {
                for (var ri = 0; ri < src.rangeTargetPageIndexes.length; ri++) {
                    pages.push(Number(src.rangeTargetPageIndexes[ri]));
                }
            } else {
                var pi = src && src.pageIndex !== undefined && src.pageIndex !== null
                        ? Number(src.pageIndex)
                        : sourcePageIndex(sourceId);
                if (pi !== null && pi !== undefined && !isNaN(Number(pi))) pages = [Number(pi)];
            }
            var out = [];
            var seen = {};
            for (var pi2 = 0; pages && pi2 < pages.length; pi2++) {
                var pageIndex = Number(pages[pi2]);
                if (isNaN(pageIndex) || pageIndex < 0 || seen[String(pageIndex)]) continue;
                seen[String(pageIndex)] = true;
                out.push(pageIndex);
            }
            out.sort(function(a, b) { return a - b; });
            return out;
        }
        function pageLocalSourceObjectIdsForSource(sourceId, pageIndex, fallbackIds) {
            return _sortedNumericIds(fallbackIds && fallbackIds.length > 0
                    ? fallbackIds
                    : [sourceId]);
        }
        function topPageLocalRootId(sourceId, pageIndex) {
            var cur = info(sourceId);
            if (!cur) return sourceId;
            var top = sourceId;
            var guard = 0;
            while (cur && cur.parentId !== null && cur.parentId !== undefined && guard++ < 64) {
                var parent = info(cur.parentId);
                if (!parent) break;
                var parentKind = String(parent.kind || "");
                if (parentKind === "Page" || parentKind === "Spread"
                        || parentKind === "MasterSpread" || parentKind === "Document") {
                    break;
                }
                if (!sourceBelongsToPageTextlessGroupPage(parent.id, pageIndex)) break;
                top = parent.id;
                cur = parent;
            }
            return top;
        }
        function pageRootPlanePageBounds(pageIndex) {
            try {
                if (sourceIndex && sourceIndex.pageBounds) {
                    var pb = sourceIndex.pageBounds(Number(pageIndex));
                    if (pb && pb.length >= 4
                            && Number(pb[2]) > Number(pb[0])
                            && Number(pb[3]) > Number(pb[1])) {
                        return pb;
                    }
                }
            } catch (ePageRootBounds) {}
            return null;
        }
        function pageRootPlaneIntersects(a, b) {
            if (!a || !b || a.length < 4 || b.length < 4) return false;
            return Number(a[2]) > Number(b[0]) && Number(a[0]) < Number(b[2])
                    && Number(a[3]) > Number(b[1]) && Number(a[1]) < Number(b[3]);
        }
        function pageRootPlanePageRelativeIntersection(bounds, pageBounds) {
            if (!pageRootPlaneIntersects(bounds, pageBounds)) return null;
            var top = Math.max(Number(bounds[0]), Number(pageBounds[0]));
            var left = Math.max(Number(bounds[1]), Number(pageBounds[1]));
            var bottom = Math.min(Number(bounds[2]), Number(pageBounds[2]));
            var right = Math.min(Number(bounds[3]), Number(pageBounds[3]));
            if (bottom <= top || right <= left) return null;
            return [
                top - Number(pageBounds[0]),
                left - Number(pageBounds[1]),
                bottom - Number(pageBounds[0]),
                right - Number(pageBounds[1])
            ];
        }
        function pageRootPlanePageRelativeBounds(bounds, pageBounds) {
            if (!bounds || !pageBounds || bounds.length < 4 || pageBounds.length < 4) return null;
            return [
                Number(bounds[0]) - Number(pageBounds[0]),
                Number(bounds[1]) - Number(pageBounds[1]),
                Number(bounds[2]) - Number(pageBounds[0]),
                Number(bounds[3]) - Number(pageBounds[1])
            ];
        }
        function pageRootPlaneBoundsDiffer(a, b, eps) {
            if (!a || !b || a.length < 4 || b.length < 4) return true;
            var tolerance = eps === undefined ? 0.01 : Number(eps);
            for (var di = 0; di < 4; di++) {
                if (Math.abs(Number(a[di]) - Number(b[di])) > tolerance) return true;
            }
            return false;
        }
        function addPageRootPlaneSources(pageEntry, rootIds, sourceIds, exportIds, hiddenVisualIds, hiddenTextFrameIds, visualBounds, minZOrder) {
            mergeIds(pageEntry.rootIds, pageEntry.rootSeen, rootIds || []);
            mergeIds(pageEntry.sourceObjectIds, pageEntry.sourceSeen, sourceIds || []);
            mergeIds(pageEntry.exportSourceObjectIds, pageEntry.exportSeen, exportIds || []);
            mergeIds(pageEntry.hiddenVisualSourceObjectIds, pageEntry.hiddenVisualSeen, hiddenVisualIds || []);
            mergeIds(pageEntry.hiddenTextFrameIds, pageEntry.hiddenTextFrameSeen, hiddenTextFrameIds || []);
            pageEntry.visualBounds = unionBounds(pageEntry.visualBounds, visualBounds);
            if (minZOrder !== null && minZOrder !== undefined) {
                pageEntry.minZOrder = pageEntry.minZOrder === null
                        ? minZOrder
                        : Math.min(pageEntry.minZOrder, minZOrder);
            }
        }
        // Legacy descendant-closure page grouping is intentionally disabled.
        // Page-root planes are built mechanically from visible non-text,
        // non-inline source items below.
        for (var psi = 0; sourceItems && psi < sourceItems.length; psi++) {
            var src = sourceItems[psi];
            if (!src || src.id === null || src.id === undefined) continue;
            if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) continue;
            if (sourceIsTextLike(src.id)) continue;
            if (!sourceHasVisiblePaint(src.id)) continue;
            if (sourceIsInlineFlow(src.id)) continue;
            if (sourceIsStoryAnchoredMaterial(src.id)) continue;
            var srcPages = candidatePageIndexesForSourceId(src.id);
            for (var spi2 = 0; spi2 < srcPages.length; spi2++) {
                var srcPageIndex = Number(srcPages[spi2]);
                if (isNaN(srcPageIndex) || srcPageIndex < 0) continue;
                var localIds = pageLocalSourceObjectIdsForSource(src.id, srcPageIndex, [src.id]);
                for (var li = 0; li < localIds.length; li++) {
                    var localId = localIds[li];
                    var localSrc = info(localId);
                    if (!localSrc) continue;
                    if (sourceIsTextLike(localId)) continue;
                    if (localSrc.visible === false || localSrc.hiddenLayer === true || localSrc.nonprinting === true) continue;
                    if (!sourceHasVisiblePaint(localId)) continue;
                    if (sourceIsInlineFlow(localId)) continue;
                    if (sourceIsStoryAnchoredMaterial(localId)) continue;
                    var srcBounds = localSrc.bounds && localSrc.bounds.length >= 4 ? localSrc.bounds : null;
                    if (!srcBounds || sourceBoundsArea(srcBounds) <= 0) continue;
                    var localPageEntry = ensurePageEntry(srcPageIndex);
                    var localRootId = localId;
                    var srcZ = localSrc.zOrder !== undefined && localSrc.zOrder !== null ? Number(localSrc.zOrder) : 0;
                    addPageRootPlaneSources(
                            localPageEntry,
                            [localRootId],
                            [localId],
                            [localId],
                            [],
                            [],
                            srcBounds,
                            srcZ);
                }
            }
        }
        for (var pageKey in pages) {
            if (!pages.hasOwnProperty(pageKey)) continue;
            var pageEntry = pages[pageKey];
            pageEntry.rootIds = _sortedNumericIds(pageEntry.rootIds);
            pageEntry.sourceObjectIds = _sortedNumericIds(pageEntry.sourceObjectIds);
            pageEntry.exportSourceObjectIds = _sortedNumericIds(pageEntry.exportSourceObjectIds);
            pageEntry.hiddenVisualSourceObjectIds = _sortedNumericIds(pageEntry.hiddenVisualSourceObjectIds);
            pageEntry.hiddenTextFrameIds = _sortedNumericIds(pageEntry.hiddenTextFrameIds);
            if (!pageEntry.rootIds || pageEntry.rootIds.length < 1) continue;
            if (!pageEntry.exportSourceObjectIds || pageEntry.exportSourceObjectIds.length < 1) continue;
            if (!pageEntry.visualBounds || sourceBoundsArea(pageEntry.visualBounds) <= 0) continue;
            var rootKey = "pass.page_textless_graphic_groups|page:" + pageKey
                    + "|page-root-plane";
            if (candidateSeen && candidateSeen[rootKey]) continue;
            if (candidateSeen) candidateSeen[rootKey] = true;
            var candidateId = _candidateCompositeId(
                    "pass.page_textless_graphic_groups",
                    pageEntry.pageIndex,
                    pageEntry.rootIds,
                    "page_root_textless_visual_plane");
            var sourceIdsForCandidate = unionSourceIds(
                    pageEntry.sourceObjectIds,
                    unionSourceIds(pageEntry.hiddenVisualSourceObjectIds,
                            pageEntry.excludedInlineSourceObjectIds));
            var sourceVisualBounds = pageEntry.visualBounds ? pageEntry.visualBounds.slice(0) : null;
            var pageLocalBounds = sourceVisualBounds;
            var cropSourceBounds = null;
            var pageBounds = pageRootPlanePageBounds(pageEntry.pageIndex);
            if (pageBounds && sourceVisualBounds) {
                var pageIntersection = pageRootPlanePageRelativeIntersection(sourceVisualBounds, pageBounds);
                if (pageIntersection) {
                    pageLocalBounds = pageIntersection;
                    var sourcePageRelative = pageRootPlanePageRelativeBounds(sourceVisualBounds, pageBounds);
                    if (sourcePageRelative
                            && pageRootPlaneBoundsDiffer(sourceVisualBounds, [
                                Math.max(Number(sourceVisualBounds[0]), Number(pageBounds[0])),
                                Math.max(Number(sourceVisualBounds[1]), Number(pageBounds[1])),
                                Math.min(Number(sourceVisualBounds[2]), Number(pageBounds[2])),
                                Math.min(Number(sourceVisualBounds[3]), Number(pageBounds[3]))
                            ], 0.01)) {
                        cropSourceBounds = sourcePageRelative;
                    }
                }
            }
            rootCandidates.push({
                candidateId: candidateId,
                passId: "pass.page_textless_graphic_groups",
                sourceObjectIds: sourceIdsForCandidate,
                executionSourceObjectIds: sourceIdsForCandidate.slice(0),
                primarySourceObjectId: pageEntry.rootIds.length > 0 ? pageEntry.rootIds[0] : null,
                pageIndex: pageEntry.pageIndex,
                kind: "PageRootTextlessVisualPlane",
                unit: "PAGE_GRAPHIC_GROUP",
                mode: "TEXTLESS_CANDIDATE",
                candidatePurpose: "CONTENT_CANDIDATE",
                bounds: pageLocalBounds || pageEntry.visualBounds,
                sourceBounds: sourceVisualBounds,
                cropSourceBounds: cropSourceBounds,
                parentId: null,
                parentKind: "Page",
                anchoredPosition: null,
                storyAnchorPlacement: null,
                composite: true,
                compositeRole: "page_root_textless_visual_plane",
                slotRole: "page_root_textless_visual_plane",
                exportSourceObjectIds: pageEntry.exportSourceObjectIds.slice(0),
                exportTargetObjectId: null,
                hiddenVisualSourceObjectIds: unionSourceIds(
                        pageEntry.hiddenVisualSourceObjectIds,
                        pageEntry.excludedInlineSourceObjectIds),
                visualSourceObjectIds: pageEntry.exportSourceObjectIds.slice(0),
                styleSourceObjectIds: [],
                ownedTextFrameIds: [],
                editableTextFrameIds: pageEntry.hiddenTextFrameIds.slice(0),
                hiddenTextFrameIds: pageEntry.hiddenTextFrameIds.slice(0),
                requiresTextHidden: pageEntry.hiddenTextFrameIds.length > 0,
                textOwner: pageEntry.hiddenTextFrameIds.length > 0 ? "hwpx_tf" : "none",
                containsEditableText: false,
                completePngTextAllowed: false,
                ownershipSlot: "SHELL_SLOT",
                materialization: "EXTRACTED_PNG_VECTOR",
                textAction: pageEntry.hiddenTextFrameIds.length > 0 ? "OWNED_BY_HWPX_TEXT" : "DROP_TEXT",
                visualAction: "PLACE_PAGE_BACKGROUND_PNG",
                visualLayer: "PAGE_BACKGROUND",
                placement: "FLOATING",
                coordinateSpace: "PAGE",
                zOrder: pageEntry.minZOrder !== null && pageEntry.minZOrder !== undefined
                        ? pageEntry.minZOrder
                        : 0,
                required: false,
                reason: "page_root_textless_visual_plane"
            });
            rootDiagnostics.push({
                candidateId: candidateId,
                pageIndex: pageEntry.pageIndex,
                rootSourceObjectIds: pageEntry.rootIds,
                sourceObjectCount: sourceIdsForCandidate.length,
                exportSourceObjectCount: pageEntry.exportSourceObjectIds.length,
                hiddenTextFrameCount: pageEntry.hiddenTextFrameIds.length,
                bounds: pageEntry.visualBounds,
                emitted: true,
                reason: "page_root_textless_visual_plane"
            });
        }
        for (var ri = 0; ri < rootCandidates.length; ri++) {
            candidates.push(rootCandidates[ri]);
        }
        return {
            appendedCount: rootCandidates.length,
            components: rootDiagnostics
        };
    }

    var rootPlaneDiagnostics = appendPageRootTextlessVisualPlaneCandidates();
    return {
        appendedCount: rootPlaneDiagnostics.appendedCount,
        componentCount: rootPlaneDiagnostics.components.length,
        components: rootPlaneDiagnostics.components
    };
}

function _mergeOverlappingPageTextlessGraphicGroupCandidates(candidates, sourceItems) {
    // Page-root textless visual planes are already the ordinary page graphic
    // grouping contract. Do not re-merge them by visual adjacency.
    return {
        candidates: candidates || [],
        mergedCount: 0,
        merged: [],
        suppressed: true,
        reason: "page_root_textless_visual_plane_disables_visual_adjacency_merge"
    };
    if (!candidates || candidates.length === 0) {
        return { candidates: candidates || [], mergedCount: 0, merged: [] };
    }
    var indexes = null;
    try { indexes = _buildSourceItemIndexes(sourceItems || []); } catch (eIndex) { indexes = null; }
    var sourceInfoById = indexes ? indexes.sourceInfoById || {} : {};

    function isPageGroup(candidate) {
        return candidate && candidate.passId === "pass.page_textless_graphic_groups";
    }

    function candidateBounds(candidate) {
        var b = candidate && candidate.bounds;
        if (!b || b.length < 4) return null;
        return [Number(b[0]), Number(b[1]), Number(b[2]), Number(b[3])];
    }

    function boundsHasArea(b) {
        return b && (b[2] - b[0]) > 0.1 && (b[3] - b[1]) > 0.1;
    }

    function boundsOverlapWithPad(a, b, pad) {
        if (!a || !b) return false;
        return a[2] >= b[0] - pad && a[0] <= b[2] + pad
                && a[3] >= b[1] - pad && a[1] <= b[3] + pad;
    }

    function boundsAreaValue(b) {
        if (!boundsHasArea(b)) return 0;
        return Math.max(0, b[2] - b[0]) * Math.max(0, b[3] - b[1]);
    }

    function boundsIntersectionArea(a, b) {
        if (!a || !b) return 0;
        var top = Math.max(a[0], b[0]);
        var left = Math.max(a[1], b[1]);
        var bottom = Math.min(a[2], b[2]);
        var right = Math.min(a[3], b[3]);
        if (bottom <= top || right <= left) return 0;
        return (bottom - top) * (right - left);
    }

    function boundsBroadlyContains(a, b) {
        var aArea = boundsAreaValue(a);
        var bArea = boundsAreaValue(b);
        if (aArea <= 0 || bArea <= 0) return false;
        var small = Math.min(aArea, bArea);
        var large = Math.max(aArea, bArea);
        if (large < small * 4.0) return false;
        var intersection = boundsIntersectionArea(a, b);
        return intersection >= small * 0.92;
    }

    function boundsMostlyContains(container, child) {
        var childArea = boundsAreaValue(child);
        if (childArea <= 0) return false;
        return boundsIntersectionArea(container, child) >= childArea * 0.92;
    }

    function axisOverlapRatio(aMin, aMax, bMin, bMax) {
        var overlap = Math.min(aMax, bMax) - Math.max(aMin, bMin);
        if (overlap <= 0) return 0;
        var minLen = Math.min(Math.max(0, aMax - aMin), Math.max(0, bMax - bMin));
        return minLen > 0 ? overlap / minLen : 0;
    }

    function axisGap(aMin, aMax, bMin, bMax) {
        if (aMax < bMin) return bMin - aMax;
        if (bMax < aMin) return aMin - bMax;
        return 0;
    }

    function boundsVisuallyAdjacent(a, b) {
        if (!boundsHasArea(a) || !boundsHasArea(b)) return false;
        if (boundsBroadlyContains(a, b)) return false;
        var minArea = Math.min(boundsAreaValue(a), boundsAreaValue(b));
        if (minArea > 0 && boundsIntersectionArea(a, b) >= minArea * 0.03) return true;
        var aHeight = Math.max(0, a[2] - a[0]);
        var bHeight = Math.max(0, b[2] - b[0]);
        var aWidth = Math.max(0, a[3] - a[1]);
        var bWidth = Math.max(0, b[3] - b[1]);
        var yOverlap = axisOverlapRatio(a[0], a[2], b[0], b[2]);
        var xGap = axisGap(a[1], a[3], b[1], b[3]);
        var rowGapLimit = Math.max(3.0, Math.min(6.0, Math.min(aHeight, bHeight) * 0.35));
        if (yOverlap >= 0.35 && xGap <= rowGapLimit) return true;
        var xOverlap = axisOverlapRatio(a[1], a[3], b[1], b[3]);
        var yGap = axisGap(a[0], a[2], b[0], b[2]);
        var columnGapLimit = Math.max(3.0, Math.min(6.0, Math.min(aHeight, bHeight) * 0.15));
        return xOverlap >= 0.65 && yGap > 0.1 && yGap <= columnGapLimit;
    }

    function entryRootZRange(entry) {
        if (!entry || !entry.candidate) return null;
        var ids = (entry.candidate.exportSourceObjectIds || [])
                .concat(entry.candidate.visualSourceObjectIds || [])
                .concat(entry.candidate.sourceObjectIds || []);
        if (!ids || ids.length === 0) return null;
        ids = depthGuardTopLevelStructuralIds(ids);
        var min = null;
        var max = null;
        for (var i = 0; ids && i < ids.length; i++) {
            var src = info(ids[i]);
            if (!src || src.zOrder === null || src.zOrder === undefined) continue;
            var z = Number(src.zOrder);
            if (isNaN(z)) continue;
            min = min === null ? z : Math.min(min, z);
            max = max === null ? z : Math.max(max, z);
        }
        if (min === null || max === null) {
            var fallback = Number(entry.candidate.zOrder || 0);
            if (isNaN(fallback)) return null;
            min = fallback;
            max = fallback;
        }
        return { min: min, max: max };
    }

    function depthGuardTopLevelStructuralIds(ids) {
        ids = _sortedNumericIds(ids || []);
        if (ids.length <= 1) return ids;
        var sourceSet = {};
        for (var i = 0; i < ids.length; i++) sourceSet[String(ids[i])] = true;
        var out = [];
        var seen = {};
        for (var ii = 0; ii < ids.length; ii++) {
            var id = ids[ii];
            var cur = info(id);
            var hasParentInSet = false;
            var guard = 0;
            while (cur && guard++ < 64) {
                var parentId = cur.parentId;
                if (parentId === null || parentId === undefined) break;
                if (sourceSet[String(parentId)]) {
                    hasParentInSet = true;
                    break;
                }
                cur = info(parentId);
            }
            if (!hasParentInSet) _pushUniqueId(out, seen, id);
        }
        return out.length > 0 && out.length < ids.length ? _sortedNumericIds(out) : ids;
    }

    function entriesSourceDepthCompatible(a, b) {
        var ar = entryRootZRange(a);
        var br = entryRootZRange(b);
        if (!ar || !br) return true;
        var gap = 0;
        if (ar.max < br.min) {
            gap = br.min - ar.max;
        } else if (br.max < ar.min) {
            gap = ar.min - br.max;
        }
        if (gap <= 8) return true;
        var aSpan = Math.max(0, ar.max - ar.min);
        var bSpan = Math.max(0, br.max - br.min);
        return gap <= Math.max(2, Math.min(aSpan, bSpan) * 0.25);
    }

    function unionBounds(a, b) {
        if (!a) return b ? b.slice(0) : null;
        if (!b) return a.slice(0);
        return [
            Math.min(a[0], b[0]),
            Math.min(a[1], b[1]),
            Math.max(a[2], b[2]),
            Math.max(a[3], b[3])
        ];
    }

    function copyCandidate(candidate) {
        var out = {};
        for (var key in candidate) {
            if (!candidate.hasOwnProperty(key)) continue;
            var value = candidate[key];
            out[key] = value && value.slice ? value.slice(0) : value;
        }
        return out;
    }

    function mergeIds(target, seen, values) {
        for (var i = 0; values && i < values.length; i++) {
            _pushUniqueId(target, seen, values[i]);
        }
    }

    function info(id) {
        return sourceInfoById ? sourceInfoById[String(id)] : null;
    }

    function sourcePageIndex(id) {
        var src = info(id);
        return src && src.pageIndex !== undefined && src.pageIndex !== null
                ? Number(src.pageIndex)
                : null;
    }

    function clipCarryingParentId(id) {
        var src = info(id);
        if (!src || src.parentId === null || src.parentId === undefined) return null;
        var parent = info(src.parentId);
        for (var depth = 0; depth < 16 && parent; depth++) {
            var kind = String(parent.kind || "");
            if (kind === "Rectangle" || kind === "Oval" || kind === "Polygon") {
                return parent.hasChildren ? parent.id : null;
            }
            if (kind !== "Group") return null;
            if (parent.parentId === null || parent.parentId === undefined) return null;
            parent = info(parent.parentId);
        }
        return null;
    }

    function sourceHasCrossPageClipParent(id, pageIndex) {
        var clipParentId = clipCarryingParentId(id);
        if (clipParentId === null || clipParentId === undefined) return false;
        var clipPageIndex = sourcePageIndex(clipParentId);
        return clipPageIndex !== null
                && pageIndex !== null
                && pageIndex !== undefined
                && Number(clipPageIndex) !== Number(pageIndex);
    }

    function sourceBelongsToPageTextlessGroupPage(id, pageIndex) {
        var pi = sourcePageIndex(id);
        if (pi === null || pi === undefined || pageIndex === null || pageIndex === undefined) return true;
        if (Number(pi) === Number(pageIndex)) return true;
        return sourceHasCrossPageClipParent(id, pageIndex);
    }

    function sourceIsDescendantOf(sourceId, rootId) {
        if (sourceId === null || sourceId === undefined || rootId === null || rootId === undefined) return false;
        if (String(sourceId) === String(rootId)) return true;
        var cur = info(sourceId);
        var guard = 0;
        while (cur && cur.parentId !== null && cur.parentId !== undefined && guard++ < 128) {
            if (String(cur.parentId) === String(rootId)) return true;
            cur = info(cur.parentId);
        }
        return false;
    }

    function nearestClosedGroupRootForExportIds(sourceIds, exportIds, pageIndex, hiddenTextFrameIds) {
        if (!sourceIds || !exportIds || exportIds.length < 128) return null;
        var sourceSet = {};
        for (var si = 0; si < sourceIds.length; si++) sourceSet[String(sourceIds[si])] = true;
        var first = info(exportIds[0]);
        var roots = [];
        var guard = 0;
        while (first && guard++ < 128) {
            var firstKind = String(first.kind || "");
            if (sourceSet[String(first.id)] && firstKind === "Group"
                    && sourceBelongsToPageTextlessGroupPage(first.id, pageIndex)) {
                roots.push(first.id);
            }
            if (first.parentId === null || first.parentId === undefined) break;
            first = info(first.parentId);
        }
        for (var ri = 0; ri < roots.length; ri++) {
            var rootId = roots[ri];
            var containsAll = true;
            for (var ei = 0; ei < exportIds.length; ei++) {
                if (!sourceIsDescendantOf(exportIds[ei], rootId)) {
                    containsAll = false;
                    break;
                }
            }
            if (containsAll) return rootId;
        }
        return null;
    }

    function sourceHasAncestorInIdSet(id, idSet) {
        var cur = info(id);
        var guard = 0;
        while (cur && cur.parentId !== null && cur.parentId !== undefined && guard++ < 128) {
            if (idSet[String(cur.parentId)]) return true;
            cur = info(cur.parentId);
        }
        return false;
    }

    function atomicExportTargetIdsForExportIds(sourceIds, exportIds, pageIndex, hiddenTextFrameIds) {
        var rootId = nearestClosedGroupRootForExportIds(
                sourceIds, exportIds, pageIndex, hiddenTextFrameIds);
        if (rootId !== null && rootId !== undefined) return [rootId];
        if (!exportIds || exportIds.length < 128) return [];
        var exportSet = {};
        for (var ei = 0; ei < exportIds.length; ei++) exportSet[String(exportIds[ei])] = true;
        var out = [];
        var seen = {};
        for (var i = 0; i < exportIds.length; i++) {
            var id = exportIds[i];
            if (sourceHasAncestorInIdSet(id, exportSet)) continue;
            if (!sourceBelongsToPageTextlessGroupPage(id, pageIndex)) continue;
            var src = info(id);
            if (!src || sourceIsTextLike(id)) continue;
            _pushUniqueId(out, seen, id);
        }
        out = _sortedNumericIds(out);
        return out.length > 0 && out.length < exportIds.length ? out : [];
    }

    function filterPageTextlessGroupSourceIds(sourceIds, pageIndex) {
        var out = [];
        var seen = {};
        for (var i = 0; sourceIds && i < sourceIds.length; i++) {
            var id = sourceIds[i];
            if (!sourceBelongsToPageTextlessGroupPage(id, pageIndex)) continue;
            _pushUniqueId(out, seen, id);
        }
        return _sortedNumericIds(out);
    }

    function sourceHasAncestorInSet(id, sourceSet) {
        var cur = info(id);
        var guard = 0;
        while (cur && guard++ < 64) {
            var parentId = cur.parentId;
            if (parentId === null || parentId === undefined) return false;
            if (sourceSet[String(parentId)]) return true;
            cur = info(parentId);
        }
        return false;
    }

    function nonPageAncestorIds(id) {
        var out = [];
        var seen = {};
        var cur = info(id);
        var guard = 0;
        while (cur && guard++ < 64) {
            var kind = String(cur.kind || "");
            if (kind === "Page" || kind === "Spread" || kind === "Document"
                    || kind === "Story" || kind === "Character"
                    || kind === "InsertionPoint" || kind === "Cell") {
                break;
            }
            if (cur.id !== null && cur.id !== undefined) {
                _pushUniqueId(out, seen, cur.id);
            }
            if (cur.parentId === null || cur.parentId === undefined) break;
            cur = info(cur.parentId);
        }
        return out;
    }

    function sourceIdsHaveAncestorRelation(aIds, bIds) {
        var bSet = {};
        for (var bi = 0; bIds && bi < bIds.length; bi++) bSet[String(bIds[bi])] = true;
        for (var ai = 0; aIds && ai < aIds.length; ai++) {
            if (bSet[String(aIds[ai])]) return true;
            if (sourceHasAncestorInSet(aIds[ai], bSet)) return true;
        }
        var aSet = {};
        for (var aj = 0; aIds && aj < aIds.length; aj++) aSet[String(aIds[aj])] = true;
        for (var bj = 0; bIds && bj < bIds.length; bj++) {
            if (sourceHasAncestorInSet(bIds[bj], aSet)) return true;
        }
        return false;
    }

    function entriesShareStructuralRoot(a, b) {
        if (!a || !b) return false;
        var aIds = (a.candidate.sourceObjectIds || []).concat(a.candidate.exportSourceObjectIds || []);
        var bIds = (b.candidate.sourceObjectIds || []).concat(b.candidate.exportSourceObjectIds || []);
        if (sourceIdsHaveAncestorRelation(aIds, bIds)) return true;
        var aAncestors = {};
        for (var ai = 0; ai < aIds.length; ai++) {
            var aa = nonPageAncestorIds(aIds[ai]);
            for (var aai = 0; aai < aa.length; aai++) aAncestors[String(aa[aai])] = true;
        }
        for (var bi = 0; bi < bIds.length; bi++) {
            var ba = nonPageAncestorIds(bIds[bi]);
            for (var bai = 0; bai < ba.length; bai++) {
                if (aAncestors[String(ba[bai])]) return true;
            }
        }
        return false;
    }

    function entrySourceIds(entry) {
        if (!entry || !entry.candidate) return [];
        return (entry.candidate.exportSourceObjectIds || [])
                .concat(entry.candidate.visualSourceObjectIds || [])
                .concat(entry.candidate.sourceObjectIds || []);
    }

    function entryLooksConcreteVisualContainer(entry) {
        var idsToCheck = entrySourceIds(entry);
        var seen = {};
        for (var i = 0; i < idsToCheck.length; i++) {
            var id = idsToCheck[i];
            if (seen[String(id)]) continue;
            seen[String(id)] = true;
            var src = info(id);
            if (!src) continue;
            if (sourceIsTextlessTextFrameShellMaterial(src)) return true;
            var kind = String(src.kind || "");
            if (kind !== "Rectangle" && kind !== "Oval" && kind !== "Polygon") continue;
            if (src.hasPlacedVisual === true || src.hasPlacedVisualInSubtree === true) continue;
            if (src.hasVisibleFill === true || src.hasVisibleStroke === true
                    || src.hasCandidateVectorPaint === true) {
                return true;
            }
        }
        return false;
    }

    function entriesContainmentConnected(a, b, structurallyConnected) {
        if (!a || !b) return false;
        if (boundsMostlyContains(a.bounds, b.bounds)) {
            if (!structurallyConnected && entryIsPageSpanningPlacedVisualCarrier(a)) {
                return false;
            }
            return structurallyConnected || entryLooksConcreteVisualContainer(a);
        }
        if (boundsMostlyContains(b.bounds, a.bounds)) {
            if (!structurallyConnected && entryIsPageSpanningPlacedVisualCarrier(b)) {
                return false;
            }
            return structurallyConnected || entryLooksConcreteVisualContainer(b);
        }
        return false;
    }

    function candidateHasSingleTopLevelStructuralRoot(candidate) {
        if (!candidate) return false;
        var ids = candidateVisibleExportIdsLocal(candidate);
        if (!ids || ids.length === 0) ids = candidateSourceIdsLocal(candidate);
        ids = topLevelStructuralIdsLocal(ids || []);
        return ids.length === 1;
    }

    function candidateVisibleExportIdsLocal(candidate) {
        var ids = candidate && candidate.exportSourceObjectIds
                ? candidate.exportSourceObjectIds.slice(0)
                : [];
        if ((!ids || ids.length === 0) && candidate && candidate.visualSourceObjectIds) {
            ids = candidate.visualSourceObjectIds.slice(0);
        }
        return _sortedNumericIds(ids || []);
    }

    function candidateSourceIdsLocal(candidate) {
        return _sortedNumericIds(candidate && candidate.sourceObjectIds
                ? candidate.sourceObjectIds.slice(0)
                : []);
    }

    function topLevelStructuralIdsLocal(ids) {
        ids = _sortedNumericIds(ids || []);
        if (ids.length <= 1) return ids;
        var sourceSet = {};
        for (var i = 0; i < ids.length; i++) sourceSet[String(ids[i])] = true;
        var out = [];
        var seen = {};
        for (var ii = 0; ii < ids.length; ii++) {
            var id = ids[ii];
            if (sourceHasAncestorInSet(id, sourceSet)) continue;
            _pushUniqueId(out, seen, id);
        }
        return out.length > 0 && out.length < ids.length ? _sortedNumericIds(out) : ids;
    }

    function candidateIsPlacedVisualCarrier(candidate) {
        if (!candidate) return false;
        if (candidate.passId === "pass.image_placed_frames"
                || candidate.passId === "pass.image_textless_groups") {
            return true;
        }
        var exportIds = candidateVisibleExportIdsLocal(candidate);
        for (var i = 0; i < exportIds.length; i++) {
            var src = info(exportIds[i]);
            if (!src) continue;
            var kind = String(src.kind || "");
            if (kind === "Image" || kind === "PDF" || kind === "EPS"
                    || src.hasPlacedVisual === true) {
                return true;
            }
        }
        return false;
    }

    function entrySpansPageCarrierBounds(entryBounds, pageIndex) {
        if (!entryBounds || entryBounds.length < 4) return false;
        var pb = pageBoundsLocal(pageIndex);
        if (!pb || pb.length < 4 || !intersectsLocal(entryBounds, pb)) return false;
        var pageWidth = Math.max(0, Number(pb[3]) - Number(pb[1]));
        var pageHeight = Math.max(0, Number(pb[2]) - Number(pb[0]));
        if (pageWidth <= 0 || pageHeight <= 0) return false;
        var width = Math.max(0, Number(entryBounds[3]) - Number(entryBounds[1]));
        var height = Math.max(0, Number(entryBounds[2]) - Number(entryBounds[0]));
        var touchesLeft = Number(entryBounds[1]) <= Number(pb[1]) + 1.0;
        var touchesRight = Number(entryBounds[3]) >= Number(pb[3]) - 1.0;
        var touchesTop = Number(entryBounds[0]) <= Number(pb[0]) + 1.0;
        var touchesBottom = Number(entryBounds[2]) >= Number(pb[2]) - 1.0;
        var spansWidth = width >= pageWidth * 0.85 && touchesLeft && touchesRight;
        var spansHeight = height >= pageHeight * 0.85 && touchesTop && touchesBottom;
        return spansWidth || spansHeight;
    }

    function pageBoundsLocal(pageIndex) {
        try {
            if (sourceIndex && sourceIndex.pageBounds) return sourceIndex.pageBounds(Number(pageIndex));
        } catch (ePageBounds) {}
        return null;
    }

    function intersectsLocal(a, b) {
        if (!a || !b || a.length < 4 || b.length < 4) return false;
        return a[2] > b[0] && a[0] < b[2] && a[3] > b[1] && a[1] < b[3];
    }

    function entryIsPageSpanningPlacedVisualCarrier(entry) {
        if (!entry || !entry.candidate) return false;
        var candidate = entry.candidate;
        if (!candidateIsPlacedVisualCarrier(candidate)) return false;
        if (!candidateHasSingleTopLevelStructuralRoot(candidate)) return false;
        var pageIndex = candidate.pageIndex;
        if (pageIndex === null || pageIndex === undefined) return false;
        return entrySpansPageCarrierBounds(entry.bounds, Number(pageIndex));
    }

    function mergedIds(component, field) {
        var out = [];
        var seen = {};
        for (var i = 0; i < component.length; i++) {
            mergeIds(out, seen, component[i].candidate[field] || []);
        }
        return _sortedNumericIds(out);
    }

    function sourceIsTextlessTextFrameShellMaterial(src) {
        if (!src || String(src.kind || "") !== "TextFrame") return false;
        if (src.hasText === true || Number(src.textLength || 0) > 0) return false;
        return src.hasVisibleFill === true || src.hasVisibleStroke === true;
    }

    function sourceIsTextLike(id) {
        var src = info(id);
        if (!src) return false;
        var kind = String(src.kind || "");
        return kind === "TextFrame" || kind === "Story" || kind === "Character"
                || kind === "InsertionPoint" || kind === "Cell";
    }

    function sourceHasVisiblePaint(id) {
        var src = info(id);
        if (!src) return false;
        if (sourceIsTextlessTextFrameShellMaterial(src)) return true;
        if (sourceIsTextLike(id)) return false;
        return src.hasCandidateVectorPaint === true
                || src.hasVisibleFill === true
                || src.hasVisibleStroke === true
                || src.hasPlacedVisual === true;
    }

    function sourceIsInlineFlow(id) {
        var src = info(id);
        if (!src) return false;
        return typeof _isInlineFlowItemBySourceInfo === "function"
                ? _isInlineFlowItemBySourceInfo(src)
                : (String(src.storyAnchorPlacement || "").toUpperCase() === "INLINE"
                    || String(src.anchoredPosition || "").toUpperCase() === "INLINE_POSITION"
                    || String(src.anchoredPosition || "").toUpperCase() === "INLINEPOSITION");
    }

    function sourceIsStoryAnchoredMaterial(id) {
        var src = info(id);
        if (!src) return false;
        var anchoredPosition = String(src.anchoredPosition || "").toUpperCase();
        var storyAnchorPlacement = String(src.storyAnchorPlacement || "").toUpperCase();
        return anchoredPosition === "ANCHORED"
                || storyAnchorPlacement === "FLOATING_ANCHORED";
    }

    function visiblePaintSourceIds(sourceIds, pageIndex) {
        var out = [];
        var seen = {};
        for (var i = 0; sourceIds && i < sourceIds.length; i++) {
            var id = sourceIds[i];
            if (!sourceBelongsToPageTextlessGroupPage(id, pageIndex)) continue;
            if (!sourceHasVisiblePaint(id)) continue;
            if (sourceIsInlineFlow(id)) continue;
            if (sourceIsStoryAnchoredMaterial(id)) continue;
            if (sourceHasCrossPageClipParent(id, pageIndex)) continue;
            _pushUniqueId(out, seen, id);
        }
        return _sortedNumericIds(out);
    }

    function splitTextFrameIdsForPageTextlessGroup(sourceIds) {
        var hidden = [];
        var hiddenSeen = {};
        var pngOwned = [];
        var pngOwnedSeen = {};
        for (var i = 0; sourceIds && i < sourceIds.length; i++) {
            var src = info(sourceIds[i]);
            if (!src || String(src.kind || "") !== "TextFrame") continue;
            if (sourceIsTextlessTextFrameShellMaterial(src)) continue;
            if (!isEditableTextFrameSource(src)) continue;
            if (src.simpleMarkerLabelContents === true && !isInlineTextFrameSource(src)) {
                _pushUniqueId(pngOwned, pngOwnedSeen, sourceIds[i]);
            } else {
                _pushUniqueId(hidden, hiddenSeen, sourceIds[i]);
            }
        }
        return {
            hiddenTextFrameIds: _sortedNumericIds(hidden),
            pngOwnedTextFrameIds: _sortedNumericIds(pngOwned)
        };
    }

    function pngOwnedTextAncestorSourceIds(sourceIds, textFrameIds) {
        var sourceSet = {};
        var out = [];
        var seen = {};
        for (var si = 0; sourceIds && si < sourceIds.length; si++) {
            sourceSet[String(sourceIds[si])] = true;
        }
        for (var ti = 0; textFrameIds && ti < textFrameIds.length; ti++) {
            var cur = info(textFrameIds[ti]);
            var lastNonTextAncestorId = null;
            var guard = 0;
            while (cur && cur.parentId !== null && cur.parentId !== undefined && guard++ < 64) {
                var parentId = cur.parentId;
                if (!sourceSet[String(parentId)]) break;
                var parent = info(parentId);
                if (!parent) break;
                var parentKind = String(parent.kind || "");
                if (parentKind !== "TextFrame" && parentKind !== "Story"
                        && parentKind !== "Character" && parentKind !== "InsertionPoint"
                        && parentKind !== "Cell") {
                    lastNonTextAncestorId = parentId;
                }
                cur = parent;
            }
            if (lastNonTextAncestorId !== null && !seen[String(lastNonTextAncestorId)]) {
                seen[String(lastNonTextAncestorId)] = true;
                out.push(lastNonTextAncestorId);
            }
        }
        return _sortedNumericIds(out);
    }

    function isEditableTextFrameSource(src) {
        if (!src || String(src.kind || "") !== "TextFrame") return false;
        if (src.textFrameClass === "editable") return true;
        if (src.hasText === true) return true;
        return Number(src.textLength || 0) > 0;
    }

    function isInlineTextFrameSource(src) {
        if (!src) return false;
        return typeof _isInlineFlowItemBySourceInfo === "function"
                ? _isInlineFlowItemBySourceInfo(src)
                : (String(src.storyAnchorPlacement || "").toUpperCase() === "INLINE"
                    || String(src.anchoredPosition || "").toUpperCase() === "INLINE_POSITION"
                    || String(src.anchoredPosition || "").toUpperCase() === "INLINEPOSITION");
    }

    function unionSourceIds(a, b) {
        var out = [];
        var seen = {};
        mergeIds(out, seen, a || []);
        mergeIds(out, seen, b || []);
        return _sortedNumericIds(out);
    }

    function subtractSourceIds(ids, removeIds) {
        var remove = {};
        for (var i = 0; removeIds && i < removeIds.length; i++) {
            remove[String(removeIds[i])] = true;
        }
        var out = [];
        var seen = {};
        for (var j = 0; ids && j < ids.length; j++) {
            if (remove[String(ids[j])]) continue;
            _pushUniqueId(out, seen, ids[j]);
        }
        return _sortedNumericIds(out);
    }

    function minZ(component) {
        var out = null;
        for (var i = 0; i < component.length; i++) {
            var z = Number(component[i].candidate.zOrder || 0);
            if (out === null || z < out) out = z;
        }
        return out === null ? 0 : out;
    }

    var byPage = {};
    for (var ci = 0; ci < candidates.length; ci++) {
        var candidate = candidates[ci];
        if (!isPageGroup(candidate)) continue;
        var b = candidateBounds(candidate);
        if (!boundsHasArea(b)) continue;
        var pageKey = String(candidate.pageIndex);
        if (!byPage[pageKey]) byPage[pageKey] = [];
        byPage[pageKey].push({ index: ci, candidate: candidate, bounds: b });
    }

    var replacementByIndex = {};
    var suppressedIndex = {};
    var diagnostics = [];
    var pad = 0.5;
    for (var pageKey in byPage) {
        if (!byPage.hasOwnProperty(pageKey)) continue;
        var entries = byPage[pageKey];
        var used = {};
        var structuralRelationCache = {};
        for (var entryIndex = 0; entryIndex < entries.length; entryIndex++) {
            entries[entryIndex]._componentEntryIndex = entryIndex;
        }
        function entriesAreConnected(aIndex, bIndex) {
            var key = aIndex < bIndex ? String(aIndex) + "|" + String(bIndex) : String(bIndex) + "|" + String(aIndex);
            if (structuralRelationCache.hasOwnProperty(key)) return structuralRelationCache[key];
            var structurallyConnected = boundsOverlapWithPad(entries[aIndex].bounds, entries[bIndex].bounds, pad)
                    && entriesShareStructuralRoot(entries[aIndex], entries[bIndex]);
            var connected = structurallyConnected
                    || entriesContainmentConnected(entries[aIndex], entries[bIndex], structurallyConnected)
                    || (entriesSourceDepthCompatible(entries[aIndex], entries[bIndex])
                            && boundsVisuallyAdjacent(entries[aIndex].bounds, entries[bIndex].bounds));
            structuralRelationCache[key] = connected;
            return connected;
        }
        for (var i = 0; i < entries.length; i++) {
            if (used[i]) continue;
            var component = [];
            var queue = [i];
            var queueIndex = 0;
            used[i] = true;
            while (queueIndex < queue.length) {
                var idx = queue[queueIndex++];
                component.push(entries[idx]);
                for (var j = 0; j < entries.length; j++) {
                    if (used[j]) continue;
                    var touches = false;
                    for (var k = 0; k < component.length; k++) {
                        if (entriesAreConnected(component[k]._componentEntryIndex, j)) {
                            touches = true;
                            break;
                        }
                    }
                    if (!touches) continue;
                    used[j] = true;
                    queue.push(j);
                }
            }
            if (component.length < 2) continue;

            var merged = copyCandidate(component[0].candidate);
            merged.sourceObjectIds = mergedIds(component, "sourceObjectIds");
            merged.visualSourceObjectIds = mergedIds(component, "visualSourceObjectIds");
            merged.exportSourceObjectIds = mergedIds(component, "exportSourceObjectIds");
            merged.hiddenVisualSourceObjectIds = mergedIds(component, "hiddenVisualSourceObjectIds");
            merged.editableTextFrameIds = mergedIds(component, "editableTextFrameIds");
            merged.hiddenTextFrameIds = mergedIds(component, "hiddenTextFrameIds");
            merged.ownedTextFrameIds = mergedIds(component, "ownedTextFrameIds");
            merged.styleSourceObjectIds = mergedIds(component, "styleSourceObjectIds");
            merged.sourceObjectIds = filterPageTextlessGroupSourceIds(
                    merged.sourceObjectIds, Number(pageKey));
            merged.visualSourceObjectIds = filterPageTextlessGroupSourceIds(
                    merged.visualSourceObjectIds, Number(pageKey));
            merged.exportSourceObjectIds = filterPageTextlessGroupSourceIds(
                    merged.exportSourceObjectIds, Number(pageKey));
            merged.hiddenVisualSourceObjectIds = filterPageTextlessGroupSourceIds(
                    merged.hiddenVisualSourceObjectIds, Number(pageKey));
            merged.editableTextFrameIds = filterPageTextlessGroupSourceIds(
                    merged.editableTextFrameIds, Number(pageKey));
            merged.hiddenTextFrameIds = filterPageTextlessGroupSourceIds(
                    merged.hiddenTextFrameIds, Number(pageKey));
            merged.ownedTextFrameIds = filterPageTextlessGroupSourceIds(
                    merged.ownedTextFrameIds, Number(pageKey));
            merged.styleSourceObjectIds = filterPageTextlessGroupSourceIds(
                    merged.styleSourceObjectIds, Number(pageKey));
            var textFrameSplit = splitTextFrameIdsForPageTextlessGroup(merged.sourceObjectIds);
            merged.hiddenTextFrameIds = textFrameSplit.hiddenTextFrameIds;
            merged.editableTextFrameIds = textFrameSplit.hiddenTextFrameIds;
            merged.hiddenVisualSourceObjectIds = textFrameSplit.hiddenTextFrameIds;
            merged.ownedTextFrameIds = textFrameSplit.pngOwnedTextFrameIds;
            var visiblePagePaintIds = visiblePaintSourceIds(merged.sourceObjectIds || [], Number(pageKey));
            var pngOwnedAncestorIds = pngOwnedTextAncestorSourceIds(
                    merged.sourceObjectIds || [], merged.ownedTextFrameIds || []);
            merged.visualSourceObjectIds = unionSourceIds(
                    merged.visualSourceObjectIds || [], visiblePagePaintIds);
            merged.exportSourceObjectIds = unionSourceIds(
                    merged.exportSourceObjectIds || [], visiblePagePaintIds);
            merged.visualSourceObjectIds = unionSourceIds(
                    merged.visualSourceObjectIds || [], pngOwnedAncestorIds);
            merged.exportSourceObjectIds = unionSourceIds(
                    merged.exportSourceObjectIds || [], pngOwnedAncestorIds);
            merged.visualSourceObjectIds = subtractSourceIds(
                    merged.visualSourceObjectIds || [], merged.hiddenTextFrameIds || []);
            merged.exportSourceObjectIds = subtractSourceIds(
                    merged.exportSourceObjectIds || [], merged.hiddenTextFrameIds || []);
            merged.exportSourceObjectIds = unionSourceIds(
                    merged.exportSourceObjectIds || [], merged.ownedTextFrameIds || []);
            merged.executionSourceObjectIds = subtractSourceIds(
                    merged.sourceObjectIds || [], merged.hiddenTextFrameIds || []);
            merged.executionSourceObjectIds = unionSourceIds(
                    merged.executionSourceObjectIds || [], pngOwnedAncestorIds);
            merged.executionSourceObjectIds = unionSourceIds(
                    merged.executionSourceObjectIds || [], merged.ownedTextFrameIds || []);
            merged.bounds = null;
            for (var bi = 0; bi < component.length; bi++) {
                merged.bounds = unionBounds(merged.bounds, component[bi].bounds);
            }
            merged.zOrder = minZ(component);
            merged.primarySourceObjectId = merged.sourceObjectIds.length > 0 ? merged.sourceObjectIds[0] : null;
            if (merged.ownedTextFrameIds && merged.ownedTextFrameIds.length > 0) {
                merged.hiddenTextFrameIds = unionSourceIds(
                        merged.hiddenTextFrameIds || [], merged.ownedTextFrameIds || []);
                merged.editableTextFrameIds = unionSourceIds(
                        merged.editableTextFrameIds || [], merged.ownedTextFrameIds || []);
            }
            merged.ownedTextFrameIds = [];
            merged.hiddenVisualSourceObjectIds = unionSourceIds(
                    merged.hiddenVisualSourceObjectIds || [], merged.hiddenTextFrameIds || []);
            merged.visualSourceObjectIds = subtractSourceIds(
                    merged.visualSourceObjectIds || [], merged.hiddenTextFrameIds || []);
            merged.exportSourceObjectIds = subtractSourceIds(
                    merged.exportSourceObjectIds || [], merged.hiddenTextFrameIds || []);
            merged.executionSourceObjectIds = subtractSourceIds(
                    merged.executionSourceObjectIds || [], merged.hiddenTextFrameIds || []);
            merged.requiresTextHidden = merged.hiddenTextFrameIds.length > 0
                    || merged.editableTextFrameIds.length > 0;
            merged.textOwner = merged.requiresTextHidden ? "hwpx_tf" : "none";
            merged.containsEditableText = false;
            merged.completePngTextAllowed = false;
            merged.materialization = "EXTRACTED_PNG_VECTOR";
            merged.textAction = merged.requiresTextHidden ? "OWNED_BY_HWPX_TEXT" : "DROP_TEXT";
            var atomicTargetIds = atomicExportTargetIdsForExportIds(
                    merged.sourceObjectIds || [],
                    merged.exportSourceObjectIds || [],
                    Number(pageKey),
                    merged.hiddenTextFrameIds || []);
            var atomicRootId = atomicTargetIds.length === 1 ? atomicTargetIds[0] : null;
            var atomic = atomicTargetIds.length > 0;
            merged.exportTargetObjectId = atomicRootId;
            merged.atomicExportTargetObjectId = atomicRootId;
            merged.atomicExportTargetObjectIds = atomicTargetIds;
            merged.atomicTextlessVectorContent = atomic;
            merged.atomicContentVisualSlot = atomic;
            merged.reason = "merged_visual_adjacency_page_textless_graphic_group_candidates";
            if (atomic) {
                merged.reason = "merged_atomic_page_textless_vector_content";
            }
            merged.candidateId = _candidateCompositeId(
                    "pass.page_textless_graphic_groups",
                    Number(pageKey),
                    merged.sourceObjectIds,
                    "merged_visual_adjacency");

            replacementByIndex[String(component[0].index)] = merged;
            var mergedCandidateIds = [];
            for (var ci2 = 0; ci2 < component.length; ci2++) {
                mergedCandidateIds.push(component[ci2].candidate.candidateId || null);
                if (ci2 > 0) suppressedIndex[String(component[ci2].index)] = true;
            }
            diagnostics.push({
                candidateId: merged.candidateId,
                pageIndex: Number(pageKey),
                mergedCandidateIds: mergedCandidateIds,
                sourceObjectCount: merged.sourceObjectIds.length,
                exportSourceObjectCount: merged.exportSourceObjectIds.length,
                atomicTextlessVectorContent: atomic,
                atomicExportTargetObjectId: atomicRootId,
                atomicExportTargetObjectIds: atomicTargetIds,
                hiddenTextFrameCount: merged.hiddenTextFrameIds.length,
                pngOwnedTextFrameCount: merged.ownedTextFrameIds.length,
                bounds: merged.bounds
            });
        }
    }

    if (diagnostics.length === 0) {
        return { candidates: candidates, mergedCount: 0, merged: [] };
    }

    var out = [];
    for (var oi = 0; oi < candidates.length; oi++) {
        if (suppressedIndex[String(oi)]) continue;
        var replacement = replacementByIndex[String(oi)];
        out.push(replacement || candidates[oi]);
    }
    return {
        candidates: out,
        mergedCount: diagnostics.length,
        merged: diagnostics
    };
}

function _suppressCrossPageClipParentSourceSetDecorations(candidates, sourceItems) {
    if (!candidates || candidates.length === 0) {
        return { candidates: candidates || [], suppressedCount: 0, suppressed: [] };
    }
    var indexes = null;
    try { indexes = _buildSourceItemIndexes(sourceItems || []); } catch (eIndex) { indexes = null; }
    var sourceInfoById = indexes ? indexes.sourceInfoById || {} : {};

    function isClipParentSourceSetDecoration(candidate) {
        return candidate
                && candidate.passId === "pass.decoration_groups"
                && candidate.compositeRole === "clip_parent_source_set";
    }

    function isDecorationCandidate(candidate) {
        return candidate && candidate.passId === "pass.decoration_groups";
    }

    function primarySourceId(candidate) {
        if (!candidate) return null;
        if (candidate.primarySourceObjectId !== undefined && candidate.primarySourceObjectId !== null) {
            return candidate.primarySourceObjectId;
        }
        return candidate.sourceObjectIds && candidate.sourceObjectIds.length > 0
                ? candidate.sourceObjectIds[0]
                : null;
    }

    function sourcePageIndex(sourceId) {
        var src = sourceInfoById ? sourceInfoById[String(sourceId)] : null;
        return src && src.pageIndex !== undefined && src.pageIndex !== null
                ? Number(src.pageIndex)
                : null;
    }

    function clipCarryingParentId(id) {
        var src = sourceInfoById ? sourceInfoById[String(id)] : null;
        if (!src || src.parentId === null || src.parentId === undefined) return null;
        var parent = sourceInfoById[String(src.parentId)];
        for (var depth = 0; depth < 16 && parent; depth++) {
            var kind = String(parent.kind || "");
            if (kind === "Rectangle" || kind === "Oval" || kind === "Polygon") {
                return parent.hasChildren ? parent.id : null;
            }
            if (kind !== "Group") return null;
            if (parent.parentId === null || parent.parentId === undefined) return null;
            parent = sourceInfoById[String(parent.parentId)];
        }
        return null;
    }

    function sourceHasCrossPageClipParent(id, pageIndex) {
        var clipParentId = clipCarryingParentId(id);
        if (clipParentId === null || clipParentId === undefined) return false;
        var clipPageIndex = sourcePageIndex(clipParentId);
        return clipPageIndex !== null
                && pageIndex !== null
                && pageIndex !== undefined
                && Number(clipPageIndex) !== Number(pageIndex);
    }

    function candidateHasCrossPageClipParentContext(candidate, pageIndex) {
        var values = []
                .concat(candidate && candidate.exportSourceObjectIds || [])
                .concat(candidate && candidate.visualSourceObjectIds || [])
                .concat(candidate && candidate.sourceObjectIds || []);
        var seen = {};
        for (var i = 0; i < values.length; i++) {
            var id = values[i];
            if (id === null || id === undefined || seen[String(id)]) continue;
            seen[String(id)] = true;
            if (sourceHasCrossPageClipParent(id, pageIndex)) return true;
        }
        return false;
    }

    var out = [];
    var suppressed = [];
    for (var i = 0; i < candidates.length; i++) {
        var candidate = candidates[i];
        if (!isDecorationCandidate(candidate)) {
            out.push(candidate);
            continue;
        }
        var primaryId = primarySourceId(candidate);
        var primaryPageIndex = sourcePageIndex(primaryId);
        var candidatePageIndex = candidate.pageIndex !== undefined && candidate.pageIndex !== null
                ? Number(candidate.pageIndex)
                : null;
        var primaryCrossPage = isClipParentSourceSetDecoration(candidate)
                && primaryPageIndex !== null
                && candidatePageIndex !== null
                && primaryPageIndex !== candidatePageIndex;
        if (primaryCrossPage || candidateHasCrossPageClipParentContext(candidate, candidatePageIndex)) {
            suppressed.push({
                candidateId: candidate.candidateId || null,
                pageIndex: candidatePageIndex,
                primarySourceObjectId: primaryId,
                primarySourcePageIndex: primaryPageIndex,
                sourceObjectCount: candidate.sourceObjectIds ? candidate.sourceObjectIds.length : 0,
                reason: primaryCrossPage
                        ? "cross_page_clip_parent_source_set_without_parent_context"
                        : "cross_page_clip_parent_context_without_parent_context"
            });
            continue;
        }
        out.push(candidate);
    }
    return {
        candidates: out,
        suppressedCount: suppressed.length,
        suppressed: suppressed
    };
}

function _suppressChildExportsCoveredByPageTextlessGraphicGroups(candidates, sourceItems) {
    if (!candidates || candidates.length === 0) {
        return { candidates: candidates || [], suppressedCount: 0, suppressed: [] };
    }
    var indexes = null;
    try { indexes = _buildSourceItemIndexes(sourceItems || []); } catch (eIndex) { indexes = null; }
    var sourceInfoById = indexes ? indexes.sourceInfoById || {} : {};

    function ids(candidate, field) {
        return _sortedNumericIds(candidate && candidate[field] || []);
    }

    function info(id) {
        return sourceInfoById ? sourceInfoById[String(id)] : null;
    }

    function sourceIsStoryAnchoredMaterial(id) {
        var src = info(id);
        if (!src) return false;
        var anchoredPosition = String(src.anchoredPosition || "").toUpperCase();
        var storyAnchorPlacement = String(src.storyAnchorPlacement || "").toUpperCase();
        return anchoredPosition === "ANCHORED"
                || storyAnchorPlacement === "FLOATING_ANCHORED";
    }

    function candidateHasStoryAnchoredMaterial(candidate) {
        var values = ids(candidate, "exportSourceObjectIds")
                .concat(ids(candidate, "visualSourceObjectIds"))
                .concat(ids(candidate, "sourceObjectIds"));
        for (var i = 0; i < values.length; i++) {
            if (sourceIsStoryAnchoredMaterial(values[i])) return true;
        }
        return false;
    }

    function visibleExportIds(candidate) {
        var exportIds = ids(candidate, "exportSourceObjectIds");
        if (exportIds.length > 0) return exportIds;
        var visualIds = ids(candidate, "visualSourceObjectIds");
        if (visualIds.length > 0) return visualIds;
        return ids(candidate, "sourceObjectIds");
    }

    function containsAll(ownerIds, childIds) {
        if (!childIds || childIds.length === 0) return false;
        var set = {};
        for (var i = 0; ownerIds && i < ownerIds.length; i++) set[String(ownerIds[i])] = true;
        for (var ci = 0; ci < childIds.length; ci++) {
            if (!set[String(childIds[ci])]) return false;
        }
        return true;
    }

    function pageTextlessSuppressIdSet(ids) {
        var set = {};
        for (var i = 0; ids && i < ids.length; i++) set[String(ids[i])] = true;
        return set;
    }

    function pageTextlessSuppressContainsAll(ownerSet, childIds) {
        if (!childIds || childIds.length === 0) return false;
        for (var i = 0; i < childIds.length; i++) {
            if (!ownerSet || !ownerSet[String(childIds[i])]) return false;
        }
        return true;
    }

    function pageTextlessSuppressIntersects(ownerSet, childIds) {
        if (!ownerSet || !childIds || childIds.length === 0) return false;
        for (var i = 0; i < childIds.length; i++) {
            if (ownerSet[String(childIds[i])]) return true;
        }
        return false;
    }

    function isPageGroup(candidate) {
        return candidate && candidate.passId === "pass.page_textless_graphic_groups";
    }

    function isPageRootTextlessPlane(candidate) {
        return candidate
                && candidate.passId === "pass.page_textless_graphic_groups"
                && (candidate.slotRole === "page_root_textless_visual_plane"
                    || candidate.compositeRole === "page_root_textless_visual_plane");
    }

    function isSuppressibleChild(candidate) {
        if (!candidate || candidate.disabled === true || isPageGroup(candidate)) return false;
        if (_candidateIsProtectedDecorationSlot(candidate)) return false;
        if (candidate.passId !== "pass.decoration_groups"
                && candidate.passId !== "pass.editable_textframe_visual_shells"
                && candidate.passId !== "pass.image_textless_groups"
                && candidate.passId !== "pass.image_placed_frames"
                && candidate.passId !== "pass.vector_shape_frames"
                && candidate.passId !== "pass.complex_graphic_frames") {
            return false;
        }
        if (candidate.visualAction === "DROP_VISUAL") return false;
        if (candidate.materialization === "HWPX_TEXT"
                || candidate.materialization === "HWPX_TABLE_STYLE"
                || candidate.materialization === "NATIVE_SOURCE_SHAPE") {
            return false;
        }
        if (candidateHasStoryAnchoredMaterial(candidate)) return false;
        return true;
    }

    var groups = [];
    for (var gi = 0; gi < candidates.length; gi++) {
        var group = candidates[gi];
        if (!isPageGroup(group)) continue;
        groups.push({
            candidate: group,
            sourceIds: ids(group, "sourceObjectIds"),
            exportIds: visibleExportIds(group)
        });
        groups[groups.length - 1].sourceSet = pageTextlessSuppressIdSet(groups[groups.length - 1].sourceIds);
        groups[groups.length - 1].exportSet = pageTextlessSuppressIdSet(groups[groups.length - 1].exportIds);
        groups[groups.length - 1].isPageRootPlane = isPageRootTextlessPlane(group);
    }
    if (groups.length === 0) return { candidates: candidates, suppressedCount: 0, suppressed: [] };

    var out = [];
    var suppressed = [];
    for (var ci = 0; ci < candidates.length; ci++) {
        var candidate = candidates[ci];
        if (!isSuppressibleChild(candidate)) {
            out.push(candidate);
            continue;
        }
        var childVisibleIds = visibleExportIds(candidate);
        var coveredBy = null;
        for (var gg = 0; gg < groups.length; gg++) {
            var owner = groups[gg];
            if (String(candidate.pageIndex) !== String(owner.candidate.pageIndex)) continue;
            var candidateSourceIds = ids(candidate, "sourceObjectIds");
            var coveredByPageRootPlane = owner.isPageRootPlane
                    && (pageTextlessSuppressIntersects(owner.exportSet, childVisibleIds)
                        || pageTextlessSuppressIntersects(owner.exportSet, candidateSourceIds)
                        || pageTextlessSuppressIntersects(owner.sourceSet, candidateSourceIds));
            if (!coveredByPageRootPlane
                    && !pageTextlessSuppressContainsAll(owner.exportSet, childVisibleIds)
                    && !pageTextlessSuppressContainsAll(owner.sourceSet, candidateSourceIds)) {
                continue;
            }
            coveredBy = owner.candidate;
            break;
        }
        if (!coveredBy) {
            out.push(candidate);
            continue;
        }
        suppressed.push({
            candidateId: candidate.candidateId || null,
            suppressedByCandidateId: coveredBy.candidateId || null,
            pageIndex: candidate.pageIndex,
            reason: "covered_by_page_textless_graphic_group"
        });
    }
    return {
        candidates: out,
        suppressedCount: suppressed.length,
        suppressed: suppressed
    };
}

function _excludeProtectedDecorationSourcesFromPageTextlessGroups(candidates) {
    if (!candidates || candidates.length === 0) {
        return { candidates: candidates || [], excludedCount: 0, excluded: [] };
    }
    function ids(candidate, field) {
        return _sortedNumericIds(candidate && candidate[field] || []);
    }
    function idSet(values) {
        var set = {};
        for (var i = 0; values && i < values.length; i++) set[String(values[i])] = true;
        return set;
    }
    function removeIds(values, removeSet) {
        var out = [];
        var seen = {};
        for (var i = 0; values && i < values.length; i++) {
            if (removeSet[String(values[i])]) continue;
            _pushUniqueId(out, seen, values[i]);
        }
        return _sortedNumericIds(out);
    }
    function unionIds(a, b) {
        var out = [];
        var seen = {};
        for (var ai = 0; a && ai < a.length; ai++) _pushUniqueId(out, seen, a[ai]);
        for (var bi = 0; b && bi < b.length; bi++) _pushUniqueId(out, seen, b[bi]);
        return _sortedNumericIds(out);
    }
    function intersectCandidateIds(candidate, removeSet) {
        var out = [];
        var seen = {};
        var candidateIds = unionIds(
                candidate ? candidate.exportSourceObjectIds || [] : [],
                candidate ? candidate.visualSourceObjectIds || [] : []);
        for (var ii = 0; ii < candidateIds.length; ii++) {
            if (removeSet[String(candidateIds[ii])]) _pushUniqueId(out, seen, candidateIds[ii]);
        }
        return _sortedNumericIds(out);
    }
    function copyCandidate(candidate) {
        var copy = {};
        for (var key in candidate) {
            if (!candidate.hasOwnProperty || !candidate.hasOwnProperty(key)) continue;
            var value = candidate[key];
            copy[key] = value && value.constructor === Array ? value.slice(0) : value;
        }
        return copy;
    }
    function canExcludeProtectedDecorationSources(candidate) {
        if (!candidate) return false;
        if (_candidateIsProtectedDecorationSlot(candidate)) return false;
        if (candidate.ownershipSlot === "SHELL_SLOT") return false;
        if (candidate.visualAction === "DROP_VISUAL") return false;
        if (candidate.passId === "pass.page_textless_graphic_groups") return true;
        if (candidate.passId === "pass.image_textless_groups"
                && candidate.ownershipSlot === "CONTENT_VISUAL_SLOT") {
            return false;
        }
        if (candidate.passId === "pass.decoration_groups"
                && candidate.ownershipSlot === "CONTENT_VISUAL_SLOT"
                && (candidate.slotRole === "textless_group_visual_slot"
                    || candidate.compositeRole === "textless_group_visual_slot")) {
            return true;
        }
        return false;
    }
    var protectedByPage = {};
    var protectedGlobal = [];
    for (var pi = 0; pi < candidates.length; pi++) {
        var protectedCandidate = candidates[pi];
        if (!_candidateIsProtectedDecorationSlot(protectedCandidate)) continue;
        var pageKey = String(protectedCandidate.pageIndex);
        if (!protectedByPage[pageKey]) protectedByPage[pageKey] = [];
        protectedByPage[pageKey] = unionIds(
                protectedByPage[pageKey], ids(protectedCandidate, "exportSourceObjectIds"));
        protectedByPage[pageKey] = unionIds(
                protectedByPage[pageKey], ids(protectedCandidate, "visualSourceObjectIds"));
        protectedGlobal = unionIds(protectedGlobal, ids(protectedCandidate, "exportSourceObjectIds"));
        protectedGlobal = unionIds(protectedGlobal, ids(protectedCandidate, "visualSourceObjectIds"));
    }
    var excluded = [];
    var out = [];
    for (var ci = 0; ci < candidates.length; ci++) {
        var candidate = candidates[ci];
        if (!canExcludeProtectedDecorationSources(candidate)) {
            out.push(candidate);
            continue;
        }
        var remove = unionIds(protectedGlobal, protectedByPage[String(candidate.pageIndex)] || []);
        if (!remove || remove.length === 0) {
            out.push(candidate);
            continue;
        }
        var removeSet = idSet(remove);
        var removedFromCandidate = intersectCandidateIds(candidate, removeSet);
        var nextExport = removeIds(candidate.exportSourceObjectIds || [], removeSet);
        var nextVisual = removeIds(candidate.visualSourceObjectIds || [], removeSet);
        if (_sourceSetKey(nextExport) === _sourceSetKey(candidate.exportSourceObjectIds || [])
                && _sourceSetKey(nextVisual) === _sourceSetKey(candidate.visualSourceObjectIds || [])) {
            out.push(candidate);
            continue;
        }
        var pruned = copyCandidate(candidate);
        pruned.exportSourceObjectIds = nextExport;
        pruned.visualSourceObjectIds = nextVisual;
        pruned.hiddenVisualSourceObjectIds = unionIds(
                pruned.hiddenVisualSourceObjectIds || [], removedFromCandidate);
        pruned.reason = candidate.passId === "pass.page_textless_graphic_groups"
                ? "page_textless_group_excludes_protected_decoration_sources"
                : "composite_visual_excludes_protected_decoration_sources";
        if (nextExport.length === 0 && nextVisual.length === 0) {
            excluded.push({
                candidateId: candidate.candidateId || null,
                pageIndex: candidate.pageIndex,
                removedSourceObjectIds: removedFromCandidate,
                suppressed: true,
                reason: "composite_visual_fully_owned_by_protected_decoration_sources"
            });
            continue;
        }
        out.push(pruned);
        excluded.push({
            candidateId: candidate.candidateId || null,
            pageIndex: candidate.pageIndex,
            removedSourceObjectIds: removedFromCandidate,
            suppressed: false
        });
    }
    return { candidates: out, excludedCount: excluded.length, excluded: excluded };
}

function _assignInlineCarrierPageVisuals(candidates, sourceItems) {
    // Legacy visual-adjacency bridge disabled.
    //
    // This path searched for a nearby page visual root around an inline carrier
    // and emitted that root again as `pass.inline_objects`. That is not source
    // ownership: it is geometry-based ownership reconstruction. It can split a
    // page/background plane after Stage 1 has already assigned ownership, causing
    // the same speech-bubble/shell sources to appear in both page PNG and inline
    // PNG paths. Inline visuals must be emitted only when the source itself is
    // inline according to resolved metadata/ObjectPlan.
    return { candidates: candidates || [], assignedCount: 0, assigned: [] };

    if (!candidates || candidates.length === 0 || !sourceItems || sourceItems.length === 0) {
        return { candidates: candidates || [], assignedCount: 0, assigned: [] };
    }
    var indexes = _buildSourceItemIndexes(sourceItems);
    var sourceInfoById = indexes.sourceInfoById || {};
    var childIdsByParentId = indexes.childIdsByParentId || {};
    var sourceHasPlacedVisualCache = {};
    var descendantsCache = {};
    var visualIdsForSubtreeCache = {};
    var textIdsForSubtreeCache = {};
    var placedVisualSourcesByPage = null;

    function source(id) {
        return sourceInfoById ? sourceInfoById[String(id)] : null;
    }
    function copyCandidate(candidate) {
        var copy = {};
        for (var key in candidate) {
            if (candidate.hasOwnProperty && !candidate.hasOwnProperty(key)) continue;
            var value = candidate[key];
            copy[key] = value && value.constructor === Array ? value.slice(0) : value;
        }
        return copy;
    }
    function bounds(src) {
        return src && src.bounds && src.bounds.length >= 4 ? src.bounds : null;
    }
    function area(b) {
        if (!b || b.length < 4) return Number.MAX_VALUE;
        return Math.max(0, Number(b[2]) - Number(b[0]))
                * Math.max(0, Number(b[3]) - Number(b[1]));
    }
    function unionBounds(a, b) {
        if (!b || b.length < 4) return a;
        if (!a) return [b[0], b[1], b[2], b[3]];
        return [
            Math.min(a[0], b[0]),
            Math.min(a[1], b[1]),
            Math.max(a[2], b[2]),
            Math.max(a[3], b[3])
        ];
    }
    function intersectionArea(a, b) {
        if (!a || !b || a.length < 4 || b.length < 4) return 0;
        var top = Math.max(Number(a[0]), Number(b[0]));
        var left = Math.max(Number(a[1]), Number(b[1]));
        var bottom = Math.min(Number(a[2]), Number(b[2]));
        var right = Math.min(Number(a[3]), Number(b[3]));
        if (bottom <= top || right <= left) return 0;
        return (bottom - top) * (right - left);
    }
    function boundsRelated(a, b) {
        var inter = intersectionArea(a, b);
        if (inter <= 0) return false;
        var smaller = Math.min(area(a), area(b));
        if (smaller <= 0) return false;
        return inter / smaller >= 0.70;
    }
    function isInlineCarrier(src) {
        if (!src) return false;
        var anchor = String(src.storyAnchorPlacement || "").toUpperCase();
        var anchored = String(src.anchoredPosition || "").toUpperCase();
        if (anchor !== "INLINE"
                && anchored !== "INLINE_POSITION"
                && anchored !== "INLINEPOSITION") {
            return false;
        }
        if (String(src.kind || "") === "TextFrame") return false;
        if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) return false;
        if (src.hasChildren === true) return false;
        if (src.hasText === true || Number(src.textLength || 0) > 0) return false;
        if (src.hasPlacedVisual === true
                || src.hasVisibleFill === true
                || src.hasVisibleStroke === true
                || src.hasCandidateVectorPaint === true) {
            return false;
        }
        return bounds(src) !== null;
    }
    function isTextLike(src) {
        var kind = String(src && src.kind || "");
        return kind === "TextFrame" || kind === "Story"
                || kind === "Character" || kind === "InsertionPoint" || kind === "Cell";
    }
    function isFloatingAnchoredSource(src) {
        if (!src) return false;
        var placement = String(src.storyAnchorPlacement || "").toUpperCase();
        var anchoredPosition = String(src.anchoredPosition || "").toUpperCase();
        return placement === "FLOATING_ANCHORED" || anchoredPosition === "ANCHORED";
    }
    function hasFloatingAnchoredAncestor(src) {
        if (!src) return false;
        var parentId = src.parentId;
        var guard = 0;
        while (parentId !== null && parentId !== undefined && guard < 200) {
            guard++;
            var parent = source(parentId);
            if (!parent) return false;
            if (isFloatingAnchoredSource(parent)) return true;
            parentId = parent.parentId;
        }
        return false;
    }
    function isInlineFlow(src) {
        if (!src) return false;
        if (isFloatingAnchoredSource(src) || hasFloatingAnchoredAncestor(src)) return false;
        return typeof _isInlineFlowItemBySourceInfo === "function"
                ? _isInlineFlowItemBySourceInfo(src)
                : (String(src.storyAnchorPlacement || "").toUpperCase() === "INLINE"
                    || String(src.anchoredPosition || "").toUpperCase() === "INLINE_POSITION"
                    || String(src.anchoredPosition || "").toUpperCase() === "INLINEPOSITION");
    }
    function sourceHasPlacedVisual(id, visiting) {
        var cacheKey = String(id);
        if (sourceHasPlacedVisualCache.hasOwnProperty(cacheKey)) {
            return sourceHasPlacedVisualCache[cacheKey];
        }
        var src = source(id);
        if (!src) {
            sourceHasPlacedVisualCache[cacheKey] = false;
            return false;
        }
        if (src.hasPlacedVisual === true) {
            sourceHasPlacedVisualCache[cacheKey] = true;
            return true;
        }
        visiting = visiting || {};
        if (visiting[cacheKey]) return false;
        visiting[cacheKey] = true;
        var children = childIdsByParentId[cacheKey] || [];
        for (var i = 0; i < children.length; i++) {
            if (sourceHasPlacedVisual(children[i], visiting)) {
                sourceHasPlacedVisualCache[cacheKey] = true;
                return true;
            }
        }
        sourceHasPlacedVisualCache[cacheKey] = false;
        return false;
    }
    function sourceHasVisibleMaterial(id) {
        var src = source(id);
        if (!src || isTextLike(src)) return false;
        if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) return false;
        return src.hasPlacedVisual === true
                || src.hasVisibleFill === true
                || src.hasVisibleStroke === true
                || src.hasCandidateVectorPaint === true;
    }
    function descendants(id) {
        var cacheKey = String(id);
        if (descendantsCache.hasOwnProperty(cacheKey)) return descendantsCache[cacheKey].slice(0);
        var out = [];
        var seen = {};
        function visit(current) {
            if (current === null || current === undefined || seen[String(current)]) return;
            seen[String(current)] = true;
            out.push(current);
            var children = childIdsByParentId[String(current)] || [];
            for (var ci = 0; ci < children.length; ci++) visit(children[ci]);
        }
        visit(id);
        out = _sortedNumericIds(out);
        descendantsCache[cacheKey] = out.slice(0);
        return out;
    }
    function visualIdsForSubtree(ids) {
        var cacheKey = _sourceSetKey(ids || []);
        if (visualIdsForSubtreeCache.hasOwnProperty(cacheKey)) {
            return visualIdsForSubtreeCache[cacheKey].slice(0);
        }
        var out = [];
        var seen = {};
        for (var i = 0; i < ids.length; i++) {
            if (sourceHasVisibleMaterial(ids[i])) _pushUniqueId(out, seen, ids[i]);
        }
        out = _sortedNumericIds(out);
        visualIdsForSubtreeCache[cacheKey] = out.slice(0);
        return out;
    }
    function textIdsForSubtree(ids) {
        var cacheKey = _sourceSetKey(ids || []);
        if (textIdsForSubtreeCache.hasOwnProperty(cacheKey)) {
            return textIdsForSubtreeCache[cacheKey].slice(0);
        }
        var out = [];
        var seen = {};
        for (var i = 0; i < ids.length; i++) {
            var src = source(ids[i]);
            if (src && String(src.kind || "") === "TextFrame") _pushUniqueId(out, seen, ids[i]);
        }
        out = _sortedNumericIds(out);
        textIdsForSubtreeCache[cacheKey] = out.slice(0);
        return out;
    }
    function climbVisualRoot(id, carrierBounds) {
        var current = source(id);
        if (!current) return null;
        var best = current;
        while (best && best.parentId !== null && best.parentId !== undefined) {
            var parent = source(best.parentId);
            if (!parent || isTextLike(parent) || isInlineFlow(parent)) break;
            if (String(parent.kind || "") !== "Group") break;
            if (String(parent.pageIndex) !== String(current.pageIndex)) break;
            if (!boundsRelated(bounds(parent), carrierBounds)) break;
            if (!sourceHasPlacedVisual(parent.id, {})) break;
            best = parent;
        }
        return best;
    }
    function bestVisualRootForCarrier(carrier) {
        var carrierBounds = bounds(carrier);
        var best = null;
        var bestArea = Number.MAX_VALUE;
        var candidatesForPage = placedVisualSourcesForPage(carrier.pageIndex);
        for (var pi = 0; pi < candidatesForPage.length; pi++) {
            var src = candidatesForPage[pi];
            if (!boundsRelated(bounds(src), carrierBounds)) continue;
            var root = climbVisualRoot(src.id, carrierBounds);
            if (!root || !sourceHasPlacedVisual(root.id, {})) continue;
            if (!boundsRelated(bounds(root), carrierBounds)) continue;
            var rootArea = area(bounds(root));
            if (rootArea < bestArea) {
                best = root;
                bestArea = rootArea;
            }
        }
        return best;
    }
    function placedVisualSourcesForPage(pageIndex) {
        if (!placedVisualSourcesByPage) {
            placedVisualSourcesByPage = {};
            for (var key in sourceInfoById) {
                if (!sourceInfoById.hasOwnProperty(key)) continue;
                var src = sourceInfoById[key];
                if (!src || src.id === null || src.id === undefined) continue;
                if (isTextLike(src) || isInlineFlow(src)) continue;
                if (!sourceHasPlacedVisual(src.id, {})) continue;
                var pageKey = String(src.pageIndex);
                if (!placedVisualSourcesByPage[pageKey]) placedVisualSourcesByPage[pageKey] = [];
                placedVisualSourcesByPage[pageKey].push(src);
            }
        }
        return placedVisualSourcesByPage[String(pageIndex)] || [];
    }
    function candidateExistsForRoot(rootId, anchorId) {
        for (var ci = 0; ci < candidates.length; ci++) {
            var c = candidates[ci];
            if (!c) continue;
            if (String(c.inlineAnchorSourceObjectId || "") === String(anchorId)
                    && _sourceIdsContain(c.sourceObjectIds || [], rootId)) {
                return true;
            }
        }
        return false;
    }
    function removeIds(values, removeSet) {
        var out = [];
        var seen = {};
        for (var i = 0; values && i < values.length; i++) {
            if (removeSet[String(values[i])]) continue;
            _pushUniqueId(out, seen, values[i]);
        }
        return _sortedNumericIds(out);
    }
    function addIds(values, addIds) {
        var out = [];
        var seen = {};
        for (var i = 0; values && i < values.length; i++) {
            _pushUniqueId(out, seen, values[i]);
        }
        for (var ai = 0; addIds && ai < addIds.length; ai++) {
            _pushUniqueId(out, seen, addIds[ai]);
        }
        return _sortedNumericIds(out);
    }
    function visualIdsFromSet(removeSet) {
        var out = [];
        var seen = {};
        for (var key in removeSet) {
            if (!removeSet.hasOwnProperty(key)) continue;
            var id = Number(key);
            if (isNaN(id)) continue;
            if (sourceHasVisibleMaterial(id)) _pushUniqueId(out, seen, id);
        }
        return _sortedNumericIds(out);
    }
    function prunePageGroups(outCandidates, rootIdsByPage) {
        var pruned = [];
        for (var ci = 0; ci < outCandidates.length; ci++) {
            var c = outCandidates[ci];
            if (!c || c.passId !== "pass.page_textless_graphic_groups"
                    || c.inlineAnchorSourceObjectId) {
                pruned.push(c);
                continue;
            }
            var removeIdsForPage = rootIdsByPage[String(c.pageIndex)] || [];
            if (removeIdsForPage.length === 0) {
                pruned.push(c);
                continue;
            }
            var removeSet = {};
            for (var ri = 0; ri < removeIdsForPage.length; ri++) {
                var subtree = descendants(removeIdsForPage[ri]);
                for (var si = 0; si < subtree.length; si++) removeSet[String(subtree[si])] = true;
            }
            var removedVisibleIds = visualIdsFromSet(removeSet);
            var next = copyCandidate(c);
            next.sourceObjectIds = removeIds(next.sourceObjectIds || [], removeSet);
            next.executionSourceObjectIds = removeIds(next.executionSourceObjectIds || [], removeSet);
            next.visualSourceObjectIds = removeIds(next.visualSourceObjectIds || [], removeSet);
            next.exportSourceObjectIds = removeIds(next.exportSourceObjectIds || [], removeSet);
            next.hiddenVisualSourceObjectIds = addIds(
                    next.hiddenVisualSourceObjectIds || [],
                    removedVisibleIds);
            next.excludedInlineSourceObjectIds = addIds(
                    next.excludedInlineSourceObjectIds || [],
                    removedVisibleIds);
            next.ownedTextFrameIds = removeIds(next.ownedTextFrameIds || [], removeSet);
            next.editableTextFrameIds = removeIds(next.editableTextFrameIds || [], removeSet);
            next.hiddenTextFrameIds = removeIds(next.hiddenTextFrameIds || [], removeSet);
            next.reason = "page_textless_group_excludes_inline_carrier_visual_source";
            if ((next.exportSourceObjectIds || []).length < 1 && (next.visualSourceObjectIds || []).length < 1) {
                continue;
            }
            pruned.push(next);
        }
        return pruned;
    }
    function suppressCandidatesOwnedByInlineCarrierVisual(outCandidates) {
        var owners = [];
        for (var oi = 0; oi < outCandidates.length; oi++) {
            var owner = outCandidates[oi];
            if (!owner || !owner.inlineAnchorSourceObjectId) continue;
            if (owner.visualAction !== "PLACE_INLINE_PNG") continue;
            owners.push({
                candidate: owner,
                sourceIds: owner.sourceObjectIds || [],
                exportIds: owner.exportSourceObjectIds || []
            });
        }
        if (owners.length === 0) return outCandidates;
        var out = [];
        for (var ci = 0; ci < outCandidates.length; ci++) {
            var c = outCandidates[ci];
            if (!c || c.inlineAnchorSourceObjectId) {
                out.push(c);
                continue;
            }
            var suppressed = false;
            for (var oi2 = 0; oi2 < owners.length; oi2++) {
                var ownerEntry = owners[oi2];
                if (String(c.pageIndex) !== String(ownerEntry.candidate.pageIndex)) continue;
                if (_sourceSetContainsAll(ownerEntry.sourceIds, c.sourceObjectIds || [])
                        || _sourceSetContainsAll(ownerEntry.sourceIds, c.exportSourceObjectIds || [])
                        || _sourceSetContainsAll(ownerEntry.exportIds, c.exportSourceObjectIds || [])) {
                    suppressed = true;
                    break;
                }
            }
            if (!suppressed) out.push(c);
        }
        return out;
    }

    var assigned = [];
    var appended = [];
    var rootsByPage = {};
    for (var i = 0; i < sourceItems.length; i++) {
        var carrier = sourceItems[i];
        if (!isInlineCarrier(carrier)) continue;
        var root = bestVisualRootForCarrier(carrier);
        if (!root) continue;
        if (candidateExistsForRoot(root.id, carrier.id)) continue;
        var subtreeIds = descendants(root.id);
        var exportIds = visualIdsForSubtree(subtreeIds);
        if (!exportIds || exportIds.length === 0) continue;
        var hiddenTextIds = textIdsForSubtree(subtreeIds);
        var sourceBounds = null;
        for (var bi = 0; bi < subtreeIds.length; bi++) {
            var src = source(subtreeIds[bi]);
            if (!src || isTextLike(src)) continue;
            sourceBounds = unionBounds(sourceBounds, bounds(src));
        }
        var candidateId = _candidateCompositeId(
                "pass.inline_objects",
                Number(carrier.pageIndex),
                subtreeIds,
                "inline_carrier_" + String(carrier.id));
        var requiresTextHidden = hiddenTextIds.length > 0;
        appended.push({
            candidateId: candidateId,
            passId: "pass.inline_objects",
            sourceObjectIds: subtreeIds,
            executionSourceObjectIds: exportIds.slice(0),
            primarySourceObjectId: root.id,
            pageIndex: Number(carrier.pageIndex),
            kind: root.kind || "InlineCarrierVisual",
            unit: "INLINE_OBJECT",
            mode: "TEXTLESS_CANDIDATE",
            candidatePurpose: "INLINE_CANDIDATE",
            bounds: sourceBounds || bounds(root),
            parentId: root.parentId !== undefined ? root.parentId : null,
            parentKind: root.parentKind || null,
            composite: true,
            compositeRole: "inline_flow_visual_root",
            slotRole: "inline_flow_visual_root",
            exportSourceObjectIds: exportIds.slice(0),
            exportTargetObjectId: root.id,
            hiddenVisualSourceObjectIds: hiddenTextIds,
            visualSourceObjectIds: exportIds.slice(0),
            styleSourceObjectIds: [],
            ownedTextFrameIds: [],
            editableTextFrameIds: hiddenTextIds,
            hiddenTextFrameIds: hiddenTextIds,
            requiresTextHidden: requiresTextHidden,
            textOwner: requiresTextHidden ? "hwpx_tf" : "none",
            containsEditableText: false,
            completePngTextAllowed: false,
            ownershipSlot: "CONTENT_VISUAL_SLOT",
            materialization: "EXTRACTED_PNG_VECTOR",
            textAction: "DROP_TEXT",
            visualAction: "PLACE_INLINE_PNG",
            visualLayer: "CONTENT_VISUAL",
            placement: "INLINE",
            coordinateSpace: "STORY_FLOW",
            inlineAnchorSourceObjectId: carrier.id,
            inlineSourceTreeClosed: true,
            zOrder: root.zOrder !== undefined ? root.zOrder : 0,
            required: false,
            reason: "inline_carrier_page_visual_source"
        });
        if (!rootsByPage[String(carrier.pageIndex)]) rootsByPage[String(carrier.pageIndex)] = [];
        rootsByPage[String(carrier.pageIndex)].push(root.id);
        assigned.push({
            inlineAnchorSourceObjectId: carrier.id,
            visualRootSourceObjectId: root.id,
            sourceObjectIds: subtreeIds,
            exportSourceObjectIds: exportIds,
            pageIndex: Number(carrier.pageIndex),
            candidateId: candidateId
        });
    }
    if (appended.length === 0) {
        return { candidates: candidates, assignedCount: 0, assigned: [] };
    }
    var out = [];
    for (var oi = 0; oi < candidates.length; oi++) out.push(candidates[oi]);
    for (var ai = 0; ai < appended.length; ai++) out.push(appended[ai]);
    out = prunePageGroups(out, rootsByPage);
    out = suppressCandidatesOwnedByInlineCarrierVisual(out);
    return { candidates: out, assignedCount: assigned.length, assigned: assigned };
}

function _mergePageTextlessGraphicGroupDiagnostics(target, appended, suppression) {
    if (!target || !appended) return;
    target.appendedCount = Number(target.appendedCount || 0) + Number(appended.appendedCount || 0);
    target.componentCount = Number(target.componentCount || 0) + Number(appended.componentCount || 0);
    target.components = (target.components || []).concat(appended.components || []);
    if (suppression) {
        target.executionSuppressedCount = Number(target.executionSuppressedCount || 0)
                + Number(suppression.suppressedCount || 0);
        target.executionSuppressed = (target.executionSuppressed || []).concat(suppression.suppressed || []);
    }
}

function _normalizePageCoordinateCandidateBounds(candidates, sourceIndex) {
    if (!candidates || !sourceIndex || !sourceIndex.pageBounds) return candidates || [];
    var normalized = 0;

    function hasArea(b) {
        return b && b.length >= 4
                && Number(b[2]) > Number(b[0])
                && Number(b[3]) > Number(b[1]);
    }

    function intersects(a, b) {
        if (!hasArea(a) || !hasArea(b)) return false;
        return Number(a[2]) > Number(b[0]) && Number(a[0]) < Number(b[2])
                && Number(a[3]) > Number(b[1]) && Number(a[1]) < Number(b[3]);
    }

    function pageRelativeIntersection(bounds, pageBounds) {
        if (!intersects(bounds, pageBounds)) return null;
        var top = Math.max(Number(bounds[0]), Number(pageBounds[0]));
        var left = Math.max(Number(bounds[1]), Number(pageBounds[1]));
        var bottom = Math.min(Number(bounds[2]), Number(pageBounds[2]));
        var right = Math.min(Number(bounds[3]), Number(pageBounds[3]));
        if (bottom <= top || right <= left) return null;
        return [
            top - Number(pageBounds[0]),
            left - Number(pageBounds[1]),
            bottom - Number(pageBounds[0]),
            right - Number(pageBounds[1])
        ];
    }

    function pageRelativeBounds(bounds, pageBounds) {
        if (!hasArea(bounds) || !hasArea(pageBounds)) return null;
        return [
            Number(bounds[0]) - Number(pageBounds[0]),
            Number(bounds[1]) - Number(pageBounds[1]),
            Number(bounds[2]) - Number(pageBounds[0]),
            Number(bounds[3]) - Number(pageBounds[1])
        ];
    }

    function containsBounds(outer, inner, eps) {
        if (!hasArea(outer) || !hasArea(inner)) return false;
        eps = eps || 0;
        return Number(outer[0]) <= Number(inner[0]) + eps
                && Number(outer[1]) <= Number(inner[1]) + eps
                && Number(outer[2]) >= Number(inner[2]) - eps
                && Number(outer[3]) >= Number(inner[3]) - eps;
    }

    function materiallyLargerBounds(outer, inner, eps) {
        if (!hasArea(outer) || !hasArea(inner)) return false;
        eps = eps || 0;
        return Number(outer[3]) - Number(outer[1]) > Number(inner[3]) - Number(inner[1]) + eps
                || Number(outer[2]) - Number(outer[0]) > Number(inner[2]) - Number(inner[0]) + eps;
    }

    for (var i = 0; i < candidates.length; i++) {
        var candidate = candidates[i];
        if (!candidate || !hasArea(candidate.bounds)) continue;
        if (candidate.placement === "INLINE" || candidate.coordinateSpace === "STORY_FLOW") continue;
        if (candidate.pageIndex === null || candidate.pageIndex === undefined) continue;
        var pb = null;
        try { pb = sourceIndex.pageBounds(Number(candidate.pageIndex)); } catch (ePageBounds) { pb = null; }
        if (!hasArea(pb)) continue;
        var rel = pageRelativeIntersection(candidate.bounds, pb);
        if (!rel) continue;
        if (Math.abs(Number(candidate.bounds[0]) - rel[0]) < 0.001
                && Math.abs(Number(candidate.bounds[1]) - rel[1]) < 0.001
                && Math.abs(Number(candidate.bounds[2]) - rel[2]) < 0.001
                && Math.abs(Number(candidate.bounds[3]) - rel[3]) < 0.001) {
            continue;
        }
        candidate.sourceBounds = candidate.sourceBounds || candidate.bounds.slice(0);
        candidate.bounds = rel;
        normalized++;
    }
    try {
        if (normalized > 0 && sourceIndex.stats) {
            sourceIndex.stats.pageCoordinateCandidateBoundsNormalized = normalized;
        }
    } catch (eStats) {}
    return candidates;
}

function _expandCrossPageFloatingVisualCandidates(candidates, sourceIndex) {
    if (!candidates || !sourceIndex || !sourceIndex.pageBounds || !sourceIndex.candidatePageIndexes) {
        return candidates || [];
    }
    var out = [];
    var seen = {};
    var expanded = 0;
    var sourceById = {};
    try {
        var sourceItems = sourceIndex.sourceItems || [];
        for (var si = 0; si < sourceItems.length; si++) {
            var src = sourceItems[si];
            if (src && src.id !== null && src.id !== undefined) sourceById[String(src.id)] = src;
        }
    } catch (eSourceById) {}

    function hasArea(b) {
        return b && b.length >= 4
                && Number(b[2]) > Number(b[0])
                && Number(b[3]) > Number(b[1]);
    }

    function intersects(a, b) {
        if (!hasArea(a) || !hasArea(b)) return false;
        return Number(a[2]) > Number(b[0]) && Number(a[0]) < Number(b[2])
                && Number(a[3]) > Number(b[1]) && Number(a[1]) < Number(b[3]);
    }

    function pageRelativeIntersection(bounds, pageBounds) {
        if (!intersects(bounds, pageBounds)) return null;
        var top = Math.max(Number(bounds[0]), Number(pageBounds[0]));
        var left = Math.max(Number(bounds[1]), Number(pageBounds[1]));
        var bottom = Math.min(Number(bounds[2]), Number(pageBounds[2]));
        var right = Math.min(Number(bounds[3]), Number(pageBounds[3]));
        if (bottom <= top || right <= left) return null;
        return [
            top - Number(pageBounds[0]),
            left - Number(pageBounds[1]),
            bottom - Number(pageBounds[0]),
            right - Number(pageBounds[1])
        ];
    }

    function isCrossPageFloatingVisualCandidate(candidate) {
        if (!candidate || candidate.disabled === true) return false;
        if (candidate.placement === "INLINE" || candidate.coordinateSpace === "STORY_FLOW") return false;
        if (String(candidate.placement || "") !== "FLOATING") return false;
        if (String(candidate.coordinateSpace || "") !== "PAGE") return false;
        var action = String(candidate.visualAction || "");
        if (action !== "PLACE_FLOATING_PNG" && action !== "PLACE_TEXT_SHELL") return false;
        return candidate.slotRole === "background_shell_slot"
                || candidate.slotRole === "shell_slot_only"
                || candidate.slotRole === "page_textless_graphic_group"
                || candidate.visualLayer === "PAGE_BACKGROUND"
                || candidate.visualLayer === "LABEL_BACKDROP"
                || candidate.visualLayer === "CONTENT_VISUAL"
                || candidate.passId === "pass.page_backgrounds";
    }

    function primaryVisualSourceId(candidate) {
        var visualIds = candidate && candidate.visualSourceObjectIds || [];
        if (visualIds.length > 0) return visualIds[0];
        if (candidate && candidate.primarySourceObjectId !== null
                && candidate.primarySourceObjectId !== undefined) {
            return candidate.primarySourceObjectId;
        }
        var sourceIds = candidate && candidate.sourceObjectIds || [];
        return sourceIds.length > 0 ? sourceIds[0] : null;
    }

    function copyArray(value) {
        return value && value.slice ? value.slice(0) : value;
    }

    function copyCandidate(candidate) {
        var clone = {};
        for (var key in candidate) {
            if (!candidate.hasOwnProperty(key)) continue;
            clone[key] = copyArray(candidate[key]);
        }
        return clone;
    }

    function candidateKey(candidate) {
        return String(candidate.passId || "")
                + "|page:" + String(candidate.pageIndex)
                + "|src:" + _sourceSetKey(candidate.sourceObjectIds || []);
    }

    function absoluteSourceBoundsForRoot(rootId) {
        var src = sourceById[String(rootId)];
        return src && hasArea(src.bounds) ? src.bounds.slice(0) : null;
    }

    function register(candidate) {
        if (!candidate) return;
        seen[candidateKey(candidate)] = true;
    }

    for (var i = 0; i < candidates.length; i++) register(candidates[i]);

    for (var ci = 0; ci < candidates.length; ci++) {
        var candidate = candidates[ci];
        out.push(candidate);
        if (!isCrossPageFloatingVisualCandidate(candidate)) continue;

        var rootId = primaryVisualSourceId(candidate);
        if (rootId === null || rootId === undefined) continue;
        var pages = [];
        try { pages = sourceIndex.candidatePageIndexes(rootId) || []; } catch (ePages) { pages = []; }
        if (!pages || pages.length <= 1) continue;

        var sourceBounds = candidate.sourceBounds && hasArea(candidate.sourceBounds)
                ? candidate.sourceBounds.slice(0)
                : absoluteSourceBoundsForRoot(rootId);
        if (!hasArea(sourceBounds) && candidate.bounds && hasArea(candidate.bounds)) {
            sourceBounds = candidate.bounds.slice(0);
        }
        if (!hasArea(sourceBounds)) continue;

        var currentPage = Number(candidate.pageIndex);
        for (var pi = 0; pi < pages.length; pi++) {
            var pageIndex = Number(pages[pi]);
            if (isNaN(pageIndex) || pageIndex === currentPage) continue;
            var pb = null;
            try { pb = sourceIndex.pageBounds(pageIndex); } catch (ePageBounds) { pb = null; }
            var rel = pageRelativeIntersection(sourceBounds, pb);
            if (!rel) continue;
            var sourcePageRelative = pageRelativeBounds(sourceBounds, pb);

            var clone = copyCandidate(candidate);
            clone.pageIndex = pageIndex;
            clone.originalPageIndex = currentPage;
            clone.crossPageFloatingVisualClone = true;
            clone.crossPageCloneOfCandidateId = candidate.candidateId || null;
            clone.sourceBounds = sourceBounds.slice(0);
            clone.bounds = rel;
            if (sourcePageRelative && containsBounds(sourcePageRelative, rel, 0.05)
                    && materiallyLargerBounds(sourcePageRelative, rel, 0.5)) {
                clone.renderSourceBounds = sourcePageRelative;
                clone.cropSourceBounds = sourcePageRelative;
            } else {
                clone.renderSourceBounds = clone.renderSourceBounds || rel;
                clone.cropSourceBounds = null;
            }
            clone.coordinateSpace = candidate.coordinateSpace || "PAGE";
            clone.placement = candidate.placement || "FLOATING";

            clone.primarySourceObjectId = rootId;
            if (!clone.visualSourceObjectIds || clone.visualSourceObjectIds.length === 0) {
                clone.visualSourceObjectIds = [rootId];
            }
            clone.candidateId = (clone.sourceObjectIds && clone.sourceObjectIds.length > 1)
                    ? _candidateCompositeId(clone.passId, pageIndex, clone.sourceObjectIds,
                            "cross_page_floating_visual")
                    : _candidateId(clone.passId, rootId, pageIndex);
            var key = candidateKey(clone);
            if (seen[key]) continue;
            seen[key] = true;
            out.push(clone);
            expanded++;
        }
    }
    try {
        if (expanded > 0 && sourceIndex.stats) {
            sourceIndex.stats.crossPageFloatingVisualCandidatesExpanded = expanded;
        }
    } catch (eStats) {}
    return out;
}

function _appendUnclaimedVisibleVectorExecutionCandidates(candidates, sourceItems, sourceIndex) {
    if (!candidates || !sourceItems || sourceItems.length === 0) {
        return { warningCount: 0, warnings: [] };
    }
    var infoById = {};
    var childIdsByParentId = {};
    for (var i = 0; i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!src || src.id === null || src.id === undefined) continue;
        infoById[String(src.id)] = src;
        if (src.parentId !== null && src.parentId !== undefined) {
            var parentKey = String(src.parentId);
            if (!childIdsByParentId[parentKey]) childIdsByParentId[parentKey] = [];
            childIdsByParentId[parentKey].push(src.id);
        }
    }

    function mark(ids, claimed) {
        for (var mi = 0; ids && mi < ids.length; mi++) {
            if (ids[mi] === null || ids[mi] === undefined) continue;
            claimed[String(ids[mi])] = true;
        }
    }

    function containsAllIds(containerIds, requiredIds) {
        if (!requiredIds || requiredIds.length === 0) return true;
        var set = {};
        for (var i = 0; containerIds && i < containerIds.length; i++) {
            set[String(containerIds[i])] = true;
        }
        for (var ri = 0; ri < requiredIds.length; ri++) {
            if (!set[String(requiredIds[ri])]) return false;
        }
        return true;
    }

    function unionCandidateVisibleIds(candidate) {
        var out = [];
        var seen = {};
        mark(candidate && candidate.sourceObjectIds || [], seen);
        mark(candidate && candidate.visualSourceObjectIds || [], seen);
        mark(candidate && candidate.exportSourceObjectIds || [], seen);
        mark(candidate && candidate.hiddenVisualSourceObjectIds || [], seen);
        for (var key in seen) {
            if (seen.hasOwnProperty(key)) out.push(Number(key));
        }
        return _sortedNumericIds(out);
    }

    function sourceHasPlacedVisualInSubtree(sourceId, visiting) {
        var src = infoById[String(sourceId)];
        if (!src) return false;
        var key = String(sourceId);
        visiting = visiting || {};
        if (visiting[key]) return false;
        visiting[key] = true;
        var kind = String(src.kind || "");
        if (kind === "Image" || kind === "PDF" || kind === "EPS") return true;
        if (src.hasPlacedVisual === true) return true;
        var children = childIdsByParentId[key] || [];
        for (var ci = 0; ci < children.length; ci++) {
            if (sourceHasPlacedVisualInSubtree(children[ci], visiting)) return true;
        }
        return false;
    }

    function sourceHasEditableTextDescendant(sourceId, pageIndex, visiting) {
        var src = infoById[String(sourceId)];
        if (!src) return false;
        var key = String(sourceId);
        visiting = visiting || {};
        if (visiting[key]) return false;
        visiting[key] = true;
        if (String(src.kind || "") === "TextFrame"
                && src.textFrameClass === "editable"
                && src.hasText === true
                && (pageIndex === null || pageIndex === undefined || src.pageIndex === pageIndex)) {
            return true;
        }
        var children = childIdsByParentId[key] || [];
        for (var ci = 0; ci < children.length; ci++) {
            if (sourceHasEditableTextDescendant(children[ci], pageIndex, visiting)) return true;
        }
        return false;
    }

    function editableTextFrameIdsForSubtree(sourceId, pageIndex, visiting, out, seen) {
        var src = infoById[String(sourceId)];
        if (!src) return out || [];
        var key = String(sourceId);
        visiting = visiting || {};
        out = out || [];
        seen = seen || {};
        if (visiting[key]) return out;
        visiting[key] = true;
        if (String(src.kind || "") === "TextFrame"
                && src.textFrameClass === "editable"
                && src.hasText === true
                && (pageIndex === null || pageIndex === undefined || src.pageIndex === pageIndex)) {
            _pushUniqueId(out, seen, sourceId);
        }
        var children = childIdsByParentId[key] || [];
        for (var ci = 0; ci < children.length; ci++) {
            editableTextFrameIdsForSubtree(children[ci], pageIndex, visiting, out, seen);
        }
        return _sortedNumericIds(out);
    }

    function editableTextFrameIdsForSubtree(sourceId, pageIndex, visiting, out, seen) {
        var src = infoById[String(sourceId)];
        if (!src) return out || [];
        visiting = visiting || {};
        out = out || [];
        seen = seen || {};
        var key = String(sourceId);
        if (visiting[key]) return out;
        visiting[key] = true;
        if (String(src.kind || "") === "TextFrame"
                && src.textFrameClass === "editable"
                && src.hasText === true
                && (pageIndex === null || pageIndex === undefined || src.pageIndex === pageIndex)) {
            _pushUniqueId(out, seen, sourceId);
        }
        var children = childIdsByParentId[key] || [];
        for (var ci = 0; ci < children.length; ci++) {
            editableTextFrameIdsForSubtree(children[ci], pageIndex, visiting, out, seen);
        }
        return _sortedNumericIds(out);
    }

    function sourceLooksLikeVisibleVectorMaterial(src) {
        if (!src) return false;
        if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) return false;
        var kind = String(src.kind || "");
        if (kind !== "Group" && kind !== "Rectangle" && kind !== "Oval"
                && kind !== "Polygon" && kind !== "GraphicLine") {
            return false;
        }
        if (sourceHasPlacedVisualInSubtree(src.id)) return false;
        if (kind === "Group") {
            return src.hasChildren === true
                    && (src.hasVisibleFill === true || src.hasVisibleStroke === true);
        }
        return src.hasVisibleFill === true
                || src.hasVisibleStroke === true
                || src.hasCandidateVectorPaint === true;
    }

    function collectSamePageSubtree(sourceId, pageIndex, seen, out) {
        var src = infoById[String(sourceId)];
        if (!src) return;
        if (pageIndex !== null && pageIndex !== undefined
                && src.pageIndex !== null && src.pageIndex !== undefined
                && src.pageIndex !== pageIndex) {
            return;
        }
        _pushUniqueId(out, seen, sourceId);
        var children = childIdsByParentId[String(sourceId)] || [];
        for (var ci = 0; ci < children.length; ci++) {
            collectSamePageSubtree(children[ci], pageIndex, seen, out);
        }
    }

    function sourceSetHasVisibleVectorMaterial(sourceIds) {
        for (var si = 0; sourceIds && si < sourceIds.length; si++) {
            if (sourceLooksLikeVisibleVectorMaterial(infoById[String(sourceIds[si])])) return true;
        }
        return false;
    }

    var claimed = {};
    for (var ci = 0; ci < candidates.length; ci++) {
        var candidate = candidates[ci];
        if (!_candidateCanClaimVisibleSourceSlot(candidate)) continue;
        mark(candidate.sourceObjectIds, claimed);
        mark(candidate.visualSourceObjectIds, claimed);
        mark(candidate.exportSourceObjectIds, claimed);
        mark(candidate.hiddenVisualSourceObjectIds, claimed);
    }

    var warnings = [];
    for (var si = 0; si < sourceItems.length; si++) {
        var src = sourceItems[si];
        if (!src || src.id === null || src.id === undefined) continue;
        if (claimed[String(src.id)] === true) continue;
        if (!sourceLooksLikeVisibleVectorMaterial(src)) continue;
        if (sourceHasEditableTextDescendant(src.id, src.pageIndex)) continue;

        var sourceIds = [];
        var seen = {};
        collectSamePageSubtree(src.id, src.pageIndex, seen, sourceIds);
        sourceIds = _sortedNumericIds(sourceIds.length > 0 ? sourceIds : [src.id]);
        if (!sourceSetHasVisibleVectorMaterial(sourceIds)) continue;

        var candidateId = sourceIds.length > 1
                ? _candidateCompositeId("pass.decoration_groups", src.pageIndex, sourceIds,
                        "unclaimed_visible_vector_source")
                : _candidateId("pass.decoration_groups", src.id, src.pageIndex);
        warnings.push({
            code: "unclaimed_visible_vector_source",
            severity: "WARNING",
            candidateId: candidateId,
            passId: "pass.decoration_groups",
            sourceObjectIds: sourceIds,
            primarySourceObjectId: src.id,
            pageIndex: src.pageIndex,
            kind: src.kind || null,
            bounds: src.bounds || null,
            parentId: src.parentId,
            parentKind: src.parentKind || null,
            composite: sourceIds.length > 1,
            compositeRole: sourceIds.length > 1
                    ? "unclaimed_visible_vector_source_set"
                    : "unclaimed_visible_vector_source",
            slotRole: "shell_slot_only",
            ownershipSlot: "SHELL_SLOT",
            reason: "unclaimed_visible_vector_source",
            zOrder: src.zOrder !== undefined ? src.zOrder : 0
        });
        mark(sourceIds, claimed);
    }
    return { warningCount: warnings.length, warnings: warnings };
}

function _appendUnclaimedVisibleVectorOwnershipCandidates(candidates, sourceItems, candidateSeen, sourceIndex) {
    if (!candidates || !sourceItems || sourceItems.length === 0) {
        return { appendedCount: 0, appended: [] };
    }
    var infoById = {};
    var childIdsByParentId = {};
    for (var i = 0; i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!src || src.id === null || src.id === undefined) continue;
        infoById[String(src.id)] = src;
        if (src.parentId !== null && src.parentId !== undefined) {
            var parentKey = String(src.parentId);
            if (!childIdsByParentId[parentKey]) childIdsByParentId[parentKey] = [];
            childIdsByParentId[parentKey].push(src.id);
        }
    }

    function mark(ids, claimed) {
        for (var mi = 0; ids && mi < ids.length; mi++) {
            if (ids[mi] === null || ids[mi] === undefined) continue;
            claimed[String(ids[mi])] = true;
        }
    }

    function containsAllIds(containerIds, requiredIds) {
        if (!requiredIds || requiredIds.length === 0) return true;
        var set = {};
        for (var i = 0; containerIds && i < containerIds.length; i++) {
            set[String(containerIds[i])] = true;
        }
        for (var ri = 0; ri < requiredIds.length; ri++) {
            if (!set[String(requiredIds[ri])]) return false;
        }
        return true;
    }

    function unionCandidateVisibleIds(candidate) {
        var out = [];
        var seen = {};
        mark(candidate && candidate.sourceObjectIds || [], seen);
        mark(candidate && candidate.visualSourceObjectIds || [], seen);
        mark(candidate && candidate.exportSourceObjectIds || [], seen);
        mark(candidate && candidate.hiddenVisualSourceObjectIds || [], seen);
        for (var key in seen) {
            if (seen.hasOwnProperty(key)) out.push(Number(key));
        }
        return _sortedNumericIds(out);
    }

    function sourceIsInlineFlow(sourceId) {
        var src = infoById[String(sourceId)];
        if (!src) return false;
        return typeof _isInlineFlowItemBySourceInfo === "function"
                ? _isInlineFlowItemBySourceInfo(src)
                : (String(src.storyAnchorPlacement || "").toUpperCase() === "INLINE"
                    || String(src.anchoredPosition || "").toUpperCase() === "INLINE_POSITION"
                    || String(src.anchoredPosition || "").toUpperCase() === "INLINEPOSITION");
    }

    function sourceHasEditableTextDescendant(sourceId, pageIndex, visiting) {
        var src = infoById[String(sourceId)];
        if (!src) return false;
        var key = String(sourceId);
        visiting = visiting || {};
        if (visiting[key]) return false;
        visiting[key] = true;
        if (String(src.kind || "") === "TextFrame"
                && src.textFrameClass === "editable"
                && src.hasText === true
                && (pageIndex === null || pageIndex === undefined || src.pageIndex === pageIndex)) {
            return true;
        }
        var children = childIdsByParentId[key] || [];
        for (var ci = 0; ci < children.length; ci++) {
            if (sourceHasEditableTextDescendant(children[ci], pageIndex, visiting)) return true;
        }
        return false;
    }

    function editableTextFrameIdsForSubtree(sourceId, pageIndex, visiting, out, seen) {
        var src = infoById[String(sourceId)];
        if (!src) return out || [];
        var key = String(sourceId);
        visiting = visiting || {};
        out = out || [];
        seen = seen || {};
        if (visiting[key]) return out;
        visiting[key] = true;
        if (String(src.kind || "") === "TextFrame"
                && src.textFrameClass === "editable"
                && src.hasText === true
                && (pageIndex === null || pageIndex === undefined || src.pageIndex === pageIndex)) {
            _pushUniqueId(out, seen, sourceId);
        }
        var children = childIdsByParentId[key] || [];
        for (var ci = 0; ci < children.length; ci++) {
            editableTextFrameIdsForSubtree(children[ci], pageIndex, visiting, out, seen);
        }
        return _sortedNumericIds(out);
    }

    function sourceHasPlacedVisualInSubtree(sourceId, visiting) {
        var src = infoById[String(sourceId)];
        if (!src) return false;
        var key = String(sourceId);
        visiting = visiting || {};
        if (visiting[key]) return false;
        visiting[key] = true;
        var kind = String(src.kind || "");
        if (kind === "Image" || kind === "PDF" || kind === "EPS") return true;
        if (src.hasPlacedVisual === true) return true;
        var children = childIdsByParentId[key] || [];
        for (var ci = 0; ci < children.length; ci++) {
            if (sourceHasPlacedVisualInSubtree(children[ci], visiting)) return true;
        }
        return false;
    }

    function sourceHasVectorPaintInSubtree(sourceId, visiting) {
        var src = infoById[String(sourceId)];
        if (!src) return false;
        var key = String(sourceId);
        visiting = visiting || {};
        if (visiting[key]) return false;
        visiting[key] = true;
        if (src.hasVisibleFill === true
                || src.hasVisibleStroke === true
                || src.hasCandidateVectorPaint === true) {
            return true;
        }
        var children = childIdsByParentId[key] || [];
        for (var ci = 0; ci < children.length; ci++) {
            if (sourceHasVectorPaintInSubtree(children[ci], visiting)) return true;
        }
        return false;
    }

    function sourceLooksLikeVisibleVectorMaterial(src) {
        if (!src) return false;
        if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) return false;
        var kind = String(src.kind || "");
        if (kind !== "Group" && kind !== "Rectangle" && kind !== "Oval"
                && kind !== "Polygon" && kind !== "GraphicLine") {
            return false;
        }
        var hasPlacedVisualTree = sourceHasPlacedVisualInSubtree(src.id);
        var hasVectorPaintTree = sourceHasVectorPaintInSubtree(src.id);
        if (kind === "Group") {
            return src.hasChildren === true
                    && (hasPlacedVisualTree || hasVectorPaintTree);
        }
        return hasPlacedVisualTree || hasVectorPaintTree;
    }

    function sourceTreeHasPlacedVisual(sourceIds) {
        for (var i = 0; sourceIds && i < sourceIds.length; i++) {
            if (sourceHasPlacedVisualInSubtree(sourceIds[i])) return true;
        }
        return false;
    }

    function sourceHasOwnVisibleVectorPaint(src) {
        if (!src) return false;
        return src.hasVisibleFill === true
                || src.hasVisibleStroke === true
                || src.hasCandidateVectorPaint === true;
    }

    function sourceIsPlacedVisualLeaf(src) {
        if (!src) return false;
        var kind = String(src.kind || "");
        return kind === "Image" || kind === "PDF" || kind === "EPS";
    }

    function collectVisibleMaterialLeaves(sourceId, seen, out) {
        var src = infoById[String(sourceId)];
        if (!src) return;
        var key = String(sourceId);
        if (seen[key]) return;
        seen[key] = true;

        if (sourceIsPlacedVisualLeaf(src)) {
            _pushUniqueId(out, {}, sourceId);
            return;
        }

        if (sourceHasOwnVisibleVectorPaint(src)) {
            _pushUniqueId(out, {}, sourceId);
        }

        var children = childIdsByParentId[key] || [];
        for (var ci = 0; ci < children.length; ci++) {
            collectVisibleMaterialLeaves(children[ci], seen, out);
        }
    }

    function visibleMaterialLeavesAllClaimed(sourceIds, claimed) {
        var leaves = [];
        var seen = {};
        for (var i = 0; sourceIds && i < sourceIds.length; i++) {
            collectVisibleMaterialLeaves(sourceIds[i], seen, leaves);
        }
        if (!leaves || leaves.length === 0) return false;
        for (var li = 0; li < leaves.length; li++) {
            if (claimed[String(leaves[li])] !== true) return false;
        }
        return true;
    }

    function candidatePagesForSource(src) {
        var pages = [];
        try {
            if (sourceIndex && sourceIndex.candidatePageIndexes) {
                pages = sourceIndex.candidatePageIndexes(src.id) || [];
            }
        } catch (ePages) {
            pages = [];
        }
        if ((!pages || pages.length === 0) && src && src.pageIndex !== null && src.pageIndex !== undefined) {
            pages = [src.pageIndex];
        }
        return pages || [];
    }

    function isTextLike(src) {
        var kind = String(src && src.kind || "");
        return kind === "TextFrame" || kind === "Story"
                || kind === "Character" || kind === "InsertionPoint" || kind === "Cell";
    }

    function isInlineFlowSource(src) {
        if (!src) return false;
        return typeof _isInlineFlowItemBySourceInfo === "function"
                ? _isInlineFlowItemBySourceInfo(src)
                : (String(src.storyAnchorPlacement || "").toUpperCase() === "INLINE"
                    || String(src.anchoredPosition || "").toUpperCase() === "INLINE_POSITION"
                    || String(src.anchoredPosition || "").toUpperCase() === "INLINEPOSITION");
    }

    function isFloatingAnchoredSource(src) {
        if (!src) return false;
        var placement = String(src.storyAnchorPlacement || "").toUpperCase();
        var anchoredPosition = String(src.anchoredPosition || "").toUpperCase();
        return placement === "FLOATING_ANCHORED" || anchoredPosition === "ANCHORED";
    }

    function hasFloatingAnchoredAncestor(src) {
        if (!src) return false;
        var parentId = src.parentId;
        var guard = 0;
        while (parentId !== null && parentId !== undefined && guard < 200) {
            guard++;
            var parent = infoById[String(parentId)];
            if (!parent) return false;
            if (isFloatingAnchoredSource(parent)) return true;
            parentId = parent.parentId;
        }
        return false;
    }

    function canBeInlineVisualContainer(parent, child) {
        if (!parent || isTextLike(parent)) return false;
        var kind = String(parent.kind || "");
        if (kind !== "Group" && kind !== "Rectangle"
                && kind !== "Oval" && kind !== "Polygon") {
            return false;
        }
        if (parent.visible === false || parent.hiddenLayer === true || parent.nonprinting === true) return false;
        if (isFloatingAnchoredSource(parent)) return false;
        if (child && parent.pageIndex !== null && parent.pageIndex !== undefined
                && child.pageIndex !== null && child.pageIndex !== undefined
                && parent.pageIndex >= 0 && child.pageIndex >= 0
                && String(parent.pageIndex) !== String(child.pageIndex)) {
            return false;
        }
        if (isInlineFlowSource(parent)) return true;
        return child && isInlineFlowSource(child)
                && parent.storyId !== null && parent.storyId !== undefined
                && child.storyId !== null && child.storyId !== undefined
                && String(parent.storyId) === String(child.storyId);
    }

    function topInlineVisibleRoot(src) {
        if (!src || !isInlineFlowSource(src) || isTextLike(src)) return null;
        var best = src;
        var guard = 0;
        while (best && best.parentId !== null && best.parentId !== undefined && guard < 200) {
            guard++;
            var parent = infoById[String(best.parentId)];
            if (!canBeInlineVisualContainer(parent, best)) break;
            best = parent;
        }
        return best;
    }

    function topFloatingAnchoredVisualRoot(src) {
        if (!src || isTextLike(src)) return null;
        var best = null;
        var current = src;
        var guard = 0;
        while (current && guard < 200) {
            guard++;
            if (isFloatingAnchoredSource(current)) best = current;
            if (current.parentId === null || current.parentId === undefined) break;
            current = infoById[String(current.parentId)] || null;
        }
        return best;
    }

    function unionSourceBounds(sourceIds) {
        var out = null;
        for (var i = 0; sourceIds && i < sourceIds.length; i++) {
            var src = infoById[String(sourceIds[i])];
            if (!src || !src.bounds || src.bounds.length < 4 || isTextLike(src)) continue;
            if (!out) {
                out = src.bounds.slice(0);
                continue;
            }
            out[0] = Math.min(out[0], src.bounds[0]);
            out[1] = Math.min(out[1], src.bounds[1]);
            out[2] = Math.max(out[2], src.bounds[2]);
            out[3] = Math.max(out[3], src.bounds[3]);
        }
        return out;
    }

    function pageLocalSourceIdsForFallback(src, pageIndex, fallbackIds) {
        var ids = null;
        try {
            if (sourceIndex && sourceIndex.pageLocalSourceObjectIds) {
                ids = sourceIndex.pageLocalSourceObjectIds(src.id, pageIndex);
            }
        } catch (ePageLocal) {
            ids = null;
        }
        return _sortedNumericIds(ids && ids.length > 0 ? ids : fallbackIds);
    }

    function sourceLooksLikeVectorShellMaterial(src) {
        if (!src) return false;
        if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) return false;
        var kind = String(src.kind || "");
        if (kind !== "Group" && kind !== "Rectangle" && kind !== "Oval"
                && kind !== "Polygon" && kind !== "GraphicLine") {
            return false;
        }
        if (src.hasPlacedVisual === true) return false;
        return src.hasVisibleFill === true
                || src.hasVisibleStroke === true
                || src.hasCandidateVectorPaint === true;
    }

    function collectUnclaimedVectorShellSubtree(sourceId, pageIndex, claimed, seen, out, forceIncludeRoot) {
        var src = infoById[String(sourceId)];
        if (!src) return out || [];
        seen = seen || {};
        out = out || [];
        if (seen[String(sourceId)]) return out;
        seen[String(sourceId)] = true;
        if (pageIndex !== null && pageIndex !== undefined
                && src.pageIndex !== null && src.pageIndex !== undefined
                && Number(src.pageIndex) !== Number(pageIndex)) {
            return out;
        }
        if (sourceLooksLikeVectorShellMaterial(src)
                && (forceIncludeRoot === true || claimed[String(sourceId)] !== true)) {
            _pushUniqueId(out, {}, sourceId);
        }
        var kind = String(src.kind || "");
        if (kind === "Image" || kind === "PDF" || kind === "EPS" || src.hasPlacedVisual === true) {
            return out;
        }
        var children = childIdsByParentId[String(sourceId)] || [];
        for (var ci = 0; ci < children.length; ci++) {
            collectUnclaimedVectorShellSubtree(children[ci], pageIndex, claimed, seen, out, false);
        }
        return out;
    }

    function collectSamePageSubtree(sourceId, pageIndex, seen, out) {
        var src = infoById[String(sourceId)];
        if (!src) return;
        if (pageIndex !== null && pageIndex !== undefined
                && src.pageIndex !== null && src.pageIndex !== undefined
                && Number(src.pageIndex) !== Number(pageIndex)) {
            return;
        }
        _pushUniqueId(out, seen, sourceId);
        var children = childIdsByParentId[String(sourceId)] || [];
        for (var ci = 0; ci < children.length; ci++) {
            collectSamePageSubtree(children[ci], pageIndex, seen, out);
        }
    }

    var claimed = {};
    for (var ci = 0; ci < candidates.length; ci++) {
        var candidate = candidates[ci];
        if (!_candidateCanClaimVisibleSourceSlot(candidate)) continue;
        mark(candidate.sourceObjectIds, claimed);
        mark(candidate.visualSourceObjectIds, claimed);
        mark(candidate.exportSourceObjectIds, claimed);
        mark(candidate.hiddenVisualSourceObjectIds, claimed);
    }

    var appended = [];
    var inlineRootSeen = {};
    var floatingAnchoredRootSeen = {};
    for (var si = 0; si < sourceItems.length; si++) {
        var src = sourceItems[si];
        if (!src || src.id === null || src.id === undefined) continue;
        if (!sourceLooksLikeVisibleVectorMaterial(src)) continue;
        if (claimed[String(src.id)] === true) {
            if (src.hasChildren !== true) continue;
        }
        if (isFloatingAnchoredSource(src) || hasFloatingAnchoredAncestor(src)) {
            var floatingRoot = topFloatingAnchoredVisualRoot(src);
            if (!floatingRoot || isTextLike(floatingRoot)) continue;
            var floatingRootKey = String(floatingRoot.id);
            if (floatingAnchoredRootSeen[floatingRootKey]) continue;
            floatingAnchoredRootSeen[floatingRootKey] = true;

            var floatingPageIndex = Number(floatingRoot.pageIndex);
            if (isNaN(floatingPageIndex) || floatingPageIndex < 0) {
                floatingPageIndex = Number(src.pageIndex);
            }
            if (isNaN(floatingPageIndex) || floatingPageIndex < 0) continue;

            var hasEditableFloatingTextDescendant =
                    sourceHasEditableTextDescendant(floatingRoot.id, floatingPageIndex);
            var floatingSourceIds = [];
            var floatingSeen = {};
            if (hasEditableFloatingTextDescendant) {
                collectUnclaimedVectorShellSubtree(
                        floatingRoot.id,
                        floatingPageIndex,
                        claimed,
                        floatingSeen,
                        floatingSourceIds,
                        true);
            } else {
                collectSamePageSubtree(floatingRoot.id, floatingPageIndex, floatingSeen, floatingSourceIds);
            }
            floatingSourceIds = pageLocalSourceIdsForFallback(
                    floatingRoot,
                    floatingPageIndex,
                    floatingSourceIds.length > 0 ? floatingSourceIds : [floatingRoot.id]);
            if (!floatingSourceIds || floatingSourceIds.length === 0) continue;
            if (visibleMaterialLeavesAllClaimed(floatingSourceIds, claimed)) continue;

            var floatingEditableTextFrameIds = hasEditableFloatingTextDescendant
                    ? editableTextFrameIdsForSubtree(floatingRoot.id, floatingPageIndex)
                    : [];
            var floatingHasPlacedVisualTree = hasEditableFloatingTextDescendant
                    ? false
                    : sourceTreeHasPlacedVisual(floatingSourceIds);
            var floatingSourceKind = String(floatingRoot.kind || src.kind || "");
            var floatingCandidatePassId = floatingHasPlacedVisualTree
                    ? (floatingSourceKind === "Group" ? "pass.image_textless_groups" : "pass.image_placed_frames")
                    : "pass.decoration_groups";
            var floatingCandidateUnit = floatingHasPlacedVisualTree
                    ? (floatingCandidatePassId === "pass.image_textless_groups" ? "GROUP" : "ITEM")
                    : (floatingSourceIds.length > 1 ? "GROUP_OR_ITEM" : "ITEM");
            var floatingCandidateId = floatingSourceIds.length > 1
                    ? _candidateCompositeId(
                            floatingCandidatePassId,
                            floatingPageIndex,
                            floatingSourceIds,
                            "story_anchored_visible_root")
                    : _candidateId(floatingCandidatePassId, floatingRoot.id, floatingPageIndex);
            var floatingSeenKey = floatingCandidatePassId + "|page:" + String(floatingPageIndex)
                    + "|src:" + _sourceSetKey(floatingSourceIds);
            if (candidateSeen) candidateSeen[floatingSeenKey] = true;
            var floatingOwnershipSlot = floatingHasPlacedVisualTree ? "CONTENT_VISUAL_SLOT" : "SHELL_SLOT";
            var floatingVisualLayer = floatingHasPlacedVisualTree ? "CONTENT_VISUAL" : "LABEL_BACKDROP";
            var floatingVisualAction = floatingHasPlacedVisualTree ? "PLACE_FLOATING_PNG" : "PLACE_TEXT_SHELL";
            var floatingCandidatePurpose = floatingHasPlacedVisualTree ? "CONTENT_CANDIDATE" : "SHELL_CANDIDATE";
            var floatingSlotRole = floatingHasPlacedVisualTree
                    ? "story_anchored_content_visual_slot"
                    : "story_anchored_shell_slot";
            var floatingCandidateMode = floatingCandidatePassId === "pass.image_placed_frames"
                    ? "ORIGINAL_VISUAL"
                    : "TEXTLESS_CANDIDATE";
            var floatingCompositeRole = floatingHasPlacedVisualTree
                    ? "source_required_story_anchored_content_visual_set"
                    : "source_required_story_anchored_shell_set";
            var floatingSingleRole = floatingHasPlacedVisualTree
                    ? "source_required_story_anchored_content_visual"
                    : "source_required_story_anchored_shell";
            candidates.push({
                candidateId: floatingCandidateId,
                passId: floatingCandidatePassId,
                sourceObjectIds: floatingSourceIds,
                executionSourceObjectIds: floatingSourceIds.slice(0),
                primarySourceObjectId: floatingRoot.id,
                pageIndex: floatingPageIndex,
                kind: floatingRoot.kind || src.kind || "StoryAnchoredVisibleRoot",
                unit: floatingCandidateUnit,
                mode: floatingCandidateMode,
                candidatePurpose: floatingCandidatePurpose,
                bounds: unionSourceBounds(floatingSourceIds) || floatingRoot.bounds || src.bounds || null,
                parentId: floatingRoot.parentId,
                parentKind: floatingRoot.parentKind || null,
                anchoredPosition: floatingRoot.anchoredPosition,
                storyAnchorPlacement: floatingRoot.storyAnchorPlacement,
                composite: floatingSourceIds.length > 1,
                compositeRole: floatingSourceIds.length > 1 ? floatingCompositeRole : floatingSingleRole,
                slotRole: floatingSlotRole,
                exportSourceObjectIds: floatingSourceIds.slice(0),
                exportTargetObjectId: floatingRoot.id,
                hiddenVisualSourceObjectIds: [],
                visualSourceObjectIds: floatingSourceIds.slice(0),
                styleSourceObjectIds: [],
                ownedTextFrameIds: floatingEditableTextFrameIds.slice(0),
                editableTextFrameIds: floatingEditableTextFrameIds.slice(0),
                hiddenTextFrameIds: [],
                requiresTextHidden: false,
                textOwner: floatingEditableTextFrameIds.length > 0 ? "hwpx_tf" : "none",
                containsEditableText: false,
                completePngTextAllowed: false,
                ownershipSlot: floatingOwnershipSlot,
                materialization: "EXTRACTED_PNG_VECTOR",
                textAction: "DROP_TEXT",
                visualAction: floatingVisualAction,
                visualLayer: floatingVisualLayer,
                placement: "FLOATING",
                coordinateSpace: "PAGE",
                zOrder: floatingRoot.zOrder !== undefined ? floatingRoot.zOrder : (src.zOrder !== undefined ? src.zOrder : 0),
                required: true,
                requiredSlot: floatingOwnershipSlot,
                requiredSlotReason: floatingHasPlacedVisualTree
                        ? "visible_story_anchored_content_material"
                        : "visible_story_anchored_shell_material",
                reason: "source_required_story_anchored_visual_root"
            });
            appended.push({
                candidateId: floatingCandidateId,
                sourceObjectIds: floatingSourceIds,
                primarySourceObjectId: floatingRoot.id,
                pageIndex: floatingPageIndex,
                requiredSlot: floatingOwnershipSlot,
                requiredSlotReason: floatingHasPlacedVisualTree
                        ? "visible_story_anchored_content_material"
                        : "visible_story_anchored_shell_material",
                reason: "source_required_story_anchored_visual_root"
            });
            mark(floatingSourceIds, claimed);
            continue;
        }
        if (sourceIsInlineFlow(src.id)) {
            var inlineRoot = topInlineVisibleRoot(src) || src;
            if (isTextLike(inlineRoot)) continue;
            var inlineRootKey = String(inlineRoot.id);
            if (inlineRootSeen[inlineRootKey]) continue;
            inlineRootSeen[inlineRootKey] = true;

            var inlinePageIndex = Number(inlineRoot.pageIndex);
            if (isNaN(inlinePageIndex) || inlinePageIndex < 0) {
                inlinePageIndex = Number(src.pageIndex);
            }
            if (isNaN(inlinePageIndex) || inlinePageIndex < 0) continue;

            var inlineSourceIds = [];
            var inlineSeen = {};
            collectUnclaimedVectorShellSubtree(
                    inlineRoot.id, inlinePageIndex, claimed, inlineSeen, inlineSourceIds, true);
            inlineSourceIds = pageLocalSourceIdsForFallback(
                    inlineRoot,
                    inlinePageIndex,
                    inlineSourceIds.length > 0 ? inlineSourceIds : [inlineRoot.id]);
            if (!inlineSourceIds || inlineSourceIds.length === 0) continue;
            if (visibleMaterialLeavesAllClaimed(inlineSourceIds, claimed)) continue;

            var inlineCandidateId = inlineSourceIds.length > 1
                    ? _candidateCompositeId("pass.inline_objects", inlinePageIndex, inlineSourceIds,
                            "unclaimed_inline_visible_vector_root")
                    : _candidateId("pass.inline_objects", inlineRoot.id, inlinePageIndex);
            var inlineCandidate = {
                candidateId: inlineCandidateId,
                passId: "pass.inline_objects",
                sourceObjectIds: inlineSourceIds,
                executionSourceObjectIds: inlineSourceIds.slice(0),
                primarySourceObjectId: inlineRoot.id,
                pageIndex: inlinePageIndex,
                kind: inlineRoot.kind || src.kind || "InlineVisibleVectorRoot",
                unit: "INLINE_OBJECT",
                mode: "TEXTLESS_CANDIDATE",
                candidatePurpose: "INLINE_CANDIDATE",
                bounds: unionSourceBounds(inlineSourceIds) || inlineRoot.bounds || src.bounds || null,
                parentId: inlineRoot.parentId,
                parentKind: inlineRoot.parentKind || null,
                anchoredPosition: inlineRoot.anchoredPosition,
                storyAnchorPlacement: inlineRoot.storyAnchorPlacement,
                composite: inlineSourceIds.length > 1,
                compositeRole: inlineSourceIds.length > 1
                        ? "source_required_inline_visible_vector_root_set"
                        : "source_required_inline_visible_vector_root",
                slotRole: "inline_flow_visual_root",
                exportSourceObjectIds: inlineSourceIds.slice(0),
                exportTargetObjectId: inlineRoot.id,
                hiddenVisualSourceObjectIds: [],
                visualSourceObjectIds: inlineSourceIds.slice(0),
                styleSourceObjectIds: [],
                ownedTextFrameIds: [],
                editableTextFrameIds: [],
                hiddenTextFrameIds: [],
                requiresTextHidden: false,
                textOwner: "none",
                containsEditableText: false,
                completePngTextAllowed: false,
                ownershipSlot: "CONTENT_VISUAL_SLOT",
                materialization: "EXTRACTED_PNG_VECTOR",
                textAction: "DROP_TEXT",
                visualAction: "PLACE_INLINE_PNG",
                visualLayer: "CONTENT_VISUAL",
                placement: "INLINE",
                coordinateSpace: "STORY_FLOW",
                inlineAnchorSourceObjectId: inlineRoot.id,
                inlineSourceTreeClosed: true,
                zOrder: inlineRoot.zOrder !== undefined ? inlineRoot.zOrder : (src.zOrder !== undefined ? src.zOrder : 0),
                required: true,
                requiredSlot: "CONTENT_VISUAL_SLOT",
                requiredSlotReason: "visible_inline_vector_material",
                reason: "source_required_inline_visible_vector_root"
            };
            candidates.push(inlineCandidate);
            appended.push({
                candidateId: inlineCandidateId,
                sourceObjectIds: inlineSourceIds,
                primarySourceObjectId: inlineRoot.id,
                pageIndex: inlinePageIndex,
                requiredSlot: "CONTENT_VISUAL_SLOT",
                requiredSlotReason: "visible_inline_vector_material",
                reason: "source_required_inline_visible_vector_root"
            });
            mark(inlineSourceIds, claimed);
            continue;
        }

        var targetPages = candidatePagesForSource(src);
        for (var pi = 0; pi < targetPages.length; pi++) {
            var targetPageIndex = targetPages[pi];
            targetPageIndex = Number(targetPageIndex);
            if (isNaN(targetPageIndex) || targetPageIndex < 0) continue;
            var sourceIds = [];
            var seen = {};
            var hasEditableTextDescendant = sourceHasEditableTextDescendant(src.id, targetPageIndex);
            if (hasEditableTextDescendant) {
                collectUnclaimedVectorShellSubtree(src.id, targetPageIndex, claimed, seen, sourceIds, true);
            } else {
                collectSamePageSubtree(src.id, targetPageIndex, seen, sourceIds);
            }
            sourceIds = pageLocalSourceIdsForFallback(
                    src, targetPageIndex, sourceIds.length > 0 ? sourceIds : [src.id]);
            if (visibleMaterialLeavesAllClaimed(sourceIds, claimed)) continue;
            var hasPlacedVisualTree = hasEditableTextDescendant ? false : sourceTreeHasPlacedVisual(sourceIds);
            var sourceKind = String(src.kind || "");
            var candidatePassId = hasPlacedVisualTree
                    ? (sourceKind === "Group" ? "pass.image_textless_groups" : "pass.image_placed_frames")
                    : "pass.decoration_groups";
            var candidateUnit = hasPlacedVisualTree
                    ? (candidatePassId === "pass.image_textless_groups" ? "GROUP" : "ITEM")
                    : (sourceIds.length > 1 ? "GROUP_OR_ITEM" : "ITEM");
            var candidateId = sourceIds.length > 1
                    ? _candidateCompositeId(candidatePassId, targetPageIndex, sourceIds,
                            "unclaimed_visible_vector_source")
                    : _candidateId(candidatePassId, src.id, targetPageIndex);
            var seenKey = candidatePassId + "|page:" + String(targetPageIndex)
                    + "|src:" + _sourceSetKey(sourceIds);
            if (candidateSeen) candidateSeen[seenKey] = true;
            var ownershipSlot = hasPlacedVisualTree ? "CONTENT_VISUAL_SLOT" : "SHELL_SLOT";
            var visualLayer = hasPlacedVisualTree ? "CONTENT_VISUAL" : "LABEL_BACKDROP";
            var visualAction = hasPlacedVisualTree ? "PLACE_FLOATING_PNG" : "PLACE_TEXT_SHELL";
            var candidatePurpose = hasPlacedVisualTree ? "CONTENT_CANDIDATE" : "SHELL_CANDIDATE";
            var slotRole = hasPlacedVisualTree ? "content_visual_slot" : "shell_slot_only";
            var candidateMode = candidatePassId === "pass.image_placed_frames"
                    ? "ORIGINAL_VISUAL"
                    : "TEXTLESS_CANDIDATE";
            var compositeRole = hasPlacedVisualTree
                    ? "source_required_content_visual_set"
                    : "source_required_visible_vector_shell_set";
            var singleRole = hasPlacedVisualTree
                    ? "source_required_content_visual"
                    : "source_required_visible_vector_shell";
            var candidate = {
                candidateId: candidateId,
                passId: candidatePassId,
                sourceObjectIds: sourceIds,
                executionSourceObjectIds: sourceIds.slice(0),
                primarySourceObjectId: src.id,
                pageIndex: targetPageIndex,
                kind: src.kind || "VectorSource",
                unit: candidateUnit,
                mode: candidateMode,
                candidatePurpose: candidatePurpose,
                bounds: src.bounds || null,
                parentId: src.parentId,
                parentKind: src.parentKind || null,
                anchoredPosition: src.anchoredPosition,
                storyAnchorPlacement: src.storyAnchorPlacement,
                composite: sourceIds.length > 1,
                compositeRole: sourceIds.length > 1 ? compositeRole : singleRole,
                slotRole: slotRole,
                exportSourceObjectIds: sourceIds.slice(0),
                exportTargetObjectId: src.id,
                hiddenVisualSourceObjectIds: [],
                visualSourceObjectIds: sourceIds.slice(0),
                styleSourceObjectIds: [],
                ownedTextFrameIds: [],
                editableTextFrameIds: [],
                hiddenTextFrameIds: [],
                requiresTextHidden: false,
                textOwner: "none",
                containsEditableText: false,
                completePngTextAllowed: false,
                ownershipSlot: ownershipSlot,
                materialization: "EXTRACTED_PNG_VECTOR",
                textAction: "DROP_TEXT",
                visualAction: visualAction,
                visualLayer: visualLayer,
                placement: "FLOATING",
                coordinateSpace: "PAGE",
                zOrder: src.zOrder !== undefined ? src.zOrder : 0,
                required: true,
                requiredSlot: ownershipSlot,
                requiredSlotReason: hasPlacedVisualTree
                        ? "visible_placed_material_tree"
                        : "visible_vector_material",
                reason: "source_required_visible_vector_shell"
            };
            candidate.slotRole = slotRole;
            candidates.push(candidate);
            appended.push({
                candidateId: candidateId,
                sourceObjectIds: sourceIds,
                primarySourceObjectId: src.id,
                pageIndex: targetPageIndex,
                requiredSlot: ownershipSlot,
                requiredSlotReason: candidate.requiredSlotReason,
                reason: "source_required_visible_vector_shell"
            });
            mark(sourceIds, claimed);
        }
    }
    return { appendedCount: appended.length, appended: appended };
}

function _reportUnresolvedVisibleVectorCoverage(
        candidates, sourceCoverageDiagnostics, sourceItems, objectPlanDiagnostics) {
    if (!candidates || !sourceCoverageDiagnostics || !sourceItems) {
        return { warningCount: 0, warnings: [], appendedCount: 0, appended: [] };
    }
    var infoById = {};
    var childIdsByParentId = {};
    for (var i = 0; i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!src || src.id === null || src.id === undefined) continue;
        infoById[String(src.id)] = src;
        if (src.parentId !== null && src.parentId !== undefined) {
            var parentKey = String(src.parentId);
            if (!childIdsByParentId[parentKey]) childIdsByParentId[parentKey] = [];
            childIdsByParentId[parentKey].push(src.id);
        }
    }
    function collectSamePageSubtree(sourceId, pageIndex, seen, out) {
        var src = infoById[String(sourceId)];
        if (!src) return;
        if (pageIndex !== null && pageIndex !== undefined
                && src.pageIndex !== null && src.pageIndex !== undefined
                && Number(src.pageIndex) !== Number(pageIndex)) {
            return;
        }
        _pushUniqueId(out, seen, sourceId);
        var children = childIdsByParentId[String(sourceId)] || [];
        for (var ci = 0; ci < children.length; ci++) {
            collectSamePageSubtree(children[ci], pageIndex, seen, out);
        }
    }
    function visibleSourceIdsForCoverageReport(src) {
        if (!src) return [];
        var subtree = [];
        collectSamePageSubtree(src.id, src.pageIndex, {}, subtree);
        return _sortedNumericIds(subtree.length > 0 ? subtree : [src.id]);
    }
    function isVisibleVectorSource(src) {
        if (!src) return false;
        if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) return false;
        var kind = String(src.kind || "");
        if (kind !== "Group" && kind !== "Rectangle" && kind !== "Oval"
                && kind !== "Polygon" && kind !== "GraphicLine"
                && kind !== "Image" && kind !== "PDF" && kind !== "EPS") {
            return false;
        }
        return src.hasVisibleFill === true
                || src.hasVisibleStroke === true
                || src.hasCandidateVectorPaint === true
                || src.hasPlacedVisual === true
                || kind === "Image" || kind === "PDF" || kind === "EPS";
    }
    var existing = {};
    for (var ci = 0; ci < candidates.length; ci++) {
        var candidate = candidates[ci];
        if (!candidate) continue;
        var key = String(candidate.passId || "") + "|" + String(candidate.pageIndex) + "|"
                + _sourceSetKey(candidate.sourceObjectIds || []);
        existing[key] = true;
    }
    var warnings = [];
    var claimed = {};
    for (var ci2 = 0; ci2 < candidates.length; ci2++) {
        var existingCandidate = candidates[ci2];
        if (!_candidateCanClaimVisibleSourceSlot(existingCandidate)) continue;
        var existingIds = [];
        existingIds = existingIds.concat(existingCandidate.sourceObjectIds || []);
        existingIds = existingIds.concat(existingCandidate.visualSourceObjectIds || []);
        existingIds = existingIds.concat(existingCandidate.exportSourceObjectIds || []);
        existingIds = existingIds.concat(existingCandidate.hiddenVisualSourceObjectIds || []);
        for (var ei = 0; ei < existingIds.length; ei++) {
            if (existingIds[ei] !== null && existingIds[ei] !== undefined) {
                claimed[String(existingIds[ei])] = true;
            }
        }
    }
    var rows = sourceCoverageDiagnostics.sourceObjects || [];
    for (var ri = 0; ri < rows.length; ri++) {
        var row = rows[ri];
        if (!row || row.coverageStatus !== "UNRESOLVED") continue;
        var src = infoById[String(row.sourceObjectId)];
        if (!isVisibleVectorSource(src)) continue;
        if (claimed[String(src.id)] === true) continue;
        var sourceIds = visibleSourceIdsForCoverageReport(src);
        if (!sourceIds || sourceIds.length === 0) sourceIds = [src.id];
        var allClaimed = true;
        for (var si2 = 0; si2 < sourceIds.length; si2++) {
            if (claimed[String(sourceIds[si2])] !== true) {
                allClaimed = false;
                break;
            }
        }
        if (allClaimed) continue;
        var sourceKey = "pass.decoration_groups|" + String(src.pageIndex) + "|" + _sourceSetKey(sourceIds);
        if (existing[sourceKey]) continue;
        for (var mi = 0; mi < sourceIds.length; mi++) {
            claimed[String(sourceIds[mi])] = true;
        }
        warnings.push({
            code: "source_unresolved_visible_vector",
            severity: "ERROR",
            passId: "diagnostic.source_coverage",
            sourceObjectIds: sourceIds,
            primarySourceObjectId: src.id,
            pageIndex: src.pageIndex,
            kind: src.kind || null,
            bounds: src.bounds || null,
            parentId: src.parentId,
            parentKind: src.parentKind || null,
            composite: false,
            compositeRole: "source_unresolved_visible_vector",
            requiredSlotReason: "visible_vector_material_without_stage1_owner",
            reason: "source_unresolved_visible_vector_no_coverage_closure",
            zOrder: src.zOrder !== undefined ? src.zOrder : 0,
            coverageStatus: row.coverageStatus || null,
            coverageClaimKinds: row.coverageClaimKinds || []
        });
        existing[sourceKey] = true;
    }
    return {
        warningCount: warnings.length,
        warnings: warnings,
        appendedCount: 0,
        appended: []
    };
}

function _backfillVisibleCandidateVisualSources(candidates, sourceItems) {
    if (!candidates || candidates.length === 0) return candidates || [];
    var indexes = null;
    try { indexes = _buildSourceItemIndexes(sourceItems || []); } catch (eIndex) { indexes = null; }
    var sourceInfoById = indexes ? indexes.sourceInfoById || {} : {};
    var childIdsByParentId = indexes ? indexes.childIdsByParentId || {} : {};

    function isVisibleCandidate(candidate) {
        if (!candidate || candidate.disabled === true) return false;
        var action = String(candidate.visualAction || "");
        if (action === "DROP_VISUAL" || action === "") return false;
        var materialization = String(candidate.materialization || "");
        if (materialization === "HWPX_TEXT"
                || materialization === "HWPX_TABLE_STYLE"
                || materialization === "NATIVE_SOURCE_SHAPE") {
            return false;
        }
        return true;
    }

    function sourceIsHiddenByTextOwnership(candidate, sourceId) {
        var key = String(sourceId);
        var textIds = candidate.hiddenTextFrameIds || candidate.editableTextFrameIds || [];
        for (var ti = 0; ti < textIds.length; ti++) {
            if (String(textIds[ti]) === key) return true;
        }
        var hiddenIds = candidate.hiddenVisualSourceObjectIds || [];
        for (var hi = 0; hi < hiddenIds.length; hi++) {
            if (String(hiddenIds[hi]) === key) return true;
        }
        return false;
    }

    for (var ci = 0; ci < candidates.length; ci++) {
        var candidate = candidates[ci];
        if (!isVisibleCandidate(candidate)) continue;
        if (candidate.visualSourceObjectIds && candidate.visualSourceObjectIds.length > 0) continue;
        var exportIds = candidate.exportSourceObjectIds || [];
        if (!exportIds || exportIds.length === 0) continue;
        var visualIds = [];
        var seen = {};
        for (var ei = 0; ei < exportIds.length; ei++) {
            var sourceId = exportIds[ei];
            if (sourceIsHiddenByTextOwnership(candidate, sourceId)) continue;
            if (!_sourceHasExecutableShellMaterialMetadataInIndex(
                    sourceId, sourceInfoById, childIdsByParentId)) {
                continue;
            }
            _pushUniqueId(visualIds, seen, sourceId);
        }
        if (visualIds.length === 0) continue;
        candidate.visualSourceObjectIds = _sortedNumericIds(visualIds);
        candidate.visualSourceBackfilledFromExportSourceIds = true;
    }
    return candidates;
}

function _appendInlineFlowVisualRootCandidates(candidates, sourceItems, candidateSeen, options) {
    options = options || {};
    var fullDiagnostics = options.fullDiagnostics === true;
    if (!candidates || !sourceItems || sourceItems.length === 0) {
        return { appendedCount: 0, appended: [], skipCounts: {}, samples: [] };
    }
    var sourceInfoById = {};
    var childIdsByParentId = {};
    for (var i = 0; i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!src || src.id === null || src.id === undefined) continue;
        sourceInfoById[String(src.id)] = src;
        if (src.parentId !== null && src.parentId !== undefined) {
            var parentKey = String(src.parentId);
            if (!childIdsByParentId[parentKey]) childIdsByParentId[parentKey] = [];
            childIdsByParentId[parentKey].push(src.id);
        }
    }
    var descendantCache = {};
    var visibleMaterialCache = {};
    var editableTextIdsCache = {};

    function source(id) {
        if (id === null || id === undefined) return null;
        return sourceInfoById[String(id)] || null;
    }
    function isFloatingAnchoredSource(src) {
        if (!src) return false;
        var placement = String(src.storyAnchorPlacement || "").toUpperCase();
        var anchoredPosition = String(src.anchoredPosition || "").toUpperCase();
        return placement === "FLOATING_ANCHORED" || anchoredPosition === "ANCHORED";
    }
    function hasFloatingAnchoredAncestor(src) {
        if (!src) return false;
        var parentId = src.parentId;
        var guard = 0;
        while (parentId !== null && parentId !== undefined && guard < 200) {
            guard++;
            var parent = source(parentId);
            if (!parent) return false;
            if (isFloatingAnchoredSource(parent)) return true;
            parentId = parent.parentId;
        }
        return false;
    }
    function isTextLike(src) {
        var kind = String(src && src.kind || "");
        return kind === "TextFrame" || kind === "Story"
                || kind === "Character" || kind === "InsertionPoint" || kind === "Cell";
    }
    function isInlineFlow(src) {
        if (!src) return false;
        return typeof _isInlineFlowItemBySourceInfo === "function"
                ? _isInlineFlowItemBySourceInfo(src)
                : (String(src.storyAnchorPlacement || "").toUpperCase() === "INLINE"
                    || String(src.anchoredPosition || "").toUpperCase() === "INLINE_POSITION"
                    || String(src.anchoredPosition || "").toUpperCase() === "INLINEPOSITION");
    }
    function hasInlineFlowDescendantContract(sourceId, visiting) {
        var src = source(sourceId);
        if (!src) return false;
        var key = String(sourceId);
        visiting = visiting || {};
        if (visiting[key]) return true;
        visiting[key] = true;
        if (isTextLike(src)) return isInlineFlow(src);
        var children = childIdsByParentId[key] || [];
        if (children.length === 0) return isInlineFlow(src);
        var hasInlineLeaf = false;
        for (var ci = 0; ci < children.length; ci++) {
            var child = source(children[ci]);
            if (!child) continue;
            if (child.visible === false || child.hiddenLayer === true || child.nonprinting === true) continue;
            if (isTextLike(child) || (childIdsByParentId[String(child.id)] || []).length === 0) {
                if (!isInlineFlow(child)) return false;
                hasInlineLeaf = true;
                continue;
            }
            if (!hasInlineFlowDescendantContract(child.id, visiting)) return false;
            hasInlineLeaf = true;
        }
        return hasInlineLeaf;
    }
    function isInlineFlowContainerByChildContract(src, child) {
        if (!src || !child) return false;
        if (String(src.kind || "") !== "Group") return false;
        if (!isInlineFlow(child) && !hasInlineFlowDescendantContract(child.id, {})) return false;
        return hasInlineFlowDescendantContract(src.id, {});
    }
    function canBeInlineVisualContainer(src, child) {
        if (!src || isTextLike(src)) return false;
        var kind = String(src.kind || "");
        if (kind !== "Group" && kind !== "Rectangle"
                && kind !== "Oval" && kind !== "Polygon") {
            return false;
        }
        if (isFloatingAnchoredSource(src) || hasFloatingAnchoredAncestor(src)) return false;
        if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) return false;
        if (child && src.pageIndex !== null && src.pageIndex !== undefined
                && child.pageIndex !== null && child.pageIndex !== undefined
                && src.pageIndex >= 0 && child.pageIndex >= 0
                && String(src.pageIndex) !== String(child.pageIndex)) {
            return false;
        }
        return isInlineFlow(src) || isInlineFlowContainerByChildContract(src, child);
    }
    function descendants(id) {
        var key = String(id);
        if (descendantCache[key]) return descendantCache[key].slice(0);
        var out = [];
        var seen = {};
        function visit(current) {
            if (current === null || current === undefined || seen[String(current)]) return;
            seen[String(current)] = true;
            out.push(current);
            var children = childIdsByParentId[String(current)] || [];
            for (var ci = 0; ci < children.length; ci++) visit(children[ci]);
        }
        visit(id);
        out = _sortedNumericIds(out);
        descendantCache[key] = out.slice(0);
        return out;
    }
    function hasDirectVisibleMaterial(src) {
        if (!src || isTextLike(src)) return false;
        if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) return false;
        var kind = String(src.kind || "");
        return kind === "Image" || kind === "PDF" || kind === "EPS"
                || src.hasPlacedVisual === true
                || src.hasVisibleFill === true
                || src.hasVisibleStroke === true
                || src.hasCandidateVectorPaint === true;
    }
    function subtreeHasVisibleMaterial(id) {
        var key = String(id);
        if (visibleMaterialCache.hasOwnProperty(key)) return visibleMaterialCache[key];
        var ids = descendants(id);
        for (var i = 0; i < ids.length; i++) {
            if (hasDirectVisibleMaterial(source(ids[i]))) {
                visibleMaterialCache[key] = true;
                return true;
            }
        }
        visibleMaterialCache[key] = false;
        return false;
    }
    function editableTextIds(id) {
        var key = String(id);
        if (editableTextIdsCache.hasOwnProperty(key)) return editableTextIdsCache[key].slice(0);
        var ids = descendants(id);
        var out = [];
        var seen = {};
        for (var i = 0; i < ids.length; i++) {
            var src = source(ids[i]);
            if (src && String(src.kind || "") === "TextFrame"
                    && src.textFrameClass === "editable"
                    && (src.hasText === true || sourceOwnsTableCellText(src))) {
                _pushUniqueId(out, seen, ids[i]);
            }
        }
        out = _sortedNumericIds(out);
        editableTextIdsCache[key] = out.slice(0);
        return out;
    }
    // 표 전용 TF(콘텐츠가 표 앵커 문자뿐)는 hasText=false 지만 셀 텍스트를
    // 소유한다. PNG 가 표 텍스트를 굽지 않도록 편집 텍스트로 취급한다.
    function sourceOwnsTableCellText(src) {
        return src
                && src.hasTablesInStory === true
                && src.storyHasVisibleTableCellText === true
                && src.markerOnlyContents !== false;
    }
    function visibleIds(ids) {
        var out = [];
        var seen = {};
        for (var i = 0; i < ids.length; i++) {
            if (hasDirectVisibleMaterial(source(ids[i]))) {
                _pushUniqueId(out, seen, ids[i]);
            }
        }
        return _sortedNumericIds(out);
    }
    function unionSourceBounds(ids) {
        var out = null;
        for (var i = 0; i < ids.length; i++) {
            var src = source(ids[i]);
            if (!src || isTextLike(src) || !src.bounds || src.bounds.length < 4) continue;
            if (!out) {
                out = src.bounds.slice(0);
            } else {
                out = [
                    Math.min(out[0], src.bounds[0]),
                    Math.min(out[1], src.bounds[1]),
                    Math.max(out[2], src.bounds[2]),
                    Math.max(out[3], src.bounds[3])
                ];
            }
        }
        return out;
    }
    function topInlineVisualRoot(src) {
        if (!src || !isInlineFlow(src) || isTextLike(src)) return null;
        var best = src;
        var guard = 0;
        while (best && best.parentId !== null && best.parentId !== undefined && guard < 200) {
            guard++;
            var parent = source(best.parentId);
            if (!canBeInlineVisualContainer(parent, best)) break;
            best = parent;
        }
        return best;
    }
    function sourceAlreadyCovered(sourceIds) {
        for (var ci = 0; ci < candidates.length; ci++) {
            var candidate = candidates[ci];
            if (!candidate) continue;
            var owned = [];
            owned = owned.concat(candidate.sourceObjectIds || []);
            owned = owned.concat(candidate.visualSourceObjectIds || []);
            owned = owned.concat(candidate.exportSourceObjectIds || []);
            if (_sourceSetContainsAll(owned, sourceIds)) return true;
        }
        return false;
    }

    var diagnostics = {
        examinedInlineSourceCount: 0,
        inlineVisibleMaterialSourceCount: 0,
        rootCount: 0,
        appendedCount: 0,
        skipCounts: {},
        samples: []
    };
    function noteSkip(code, item, root, extra) {
        if (!diagnostics.skipCounts[code]) diagnostics.skipCounts[code] = 0;
        diagnostics.skipCounts[code]++;
        if (!fullDiagnostics) return;
        if (diagnostics.samples.length >= 80) return;
        diagnostics.samples.push({
            code: code,
            sourceObjectId: item && item.id,
            kind: item && item.kind,
            pageIndex: item && item.pageIndex,
            parentId: item && item.parentId,
            parentKind: item && item.parentKind,
            storyId: item && item.storyId,
            rootSourceObjectId: root && root.id,
            rootKind: root && root.kind,
            rootParentId: root && root.parentId,
            rootParentKind: root && root.parentKind,
            extra: extra || null
        });
    }

    var roots = {};
    var appended = [];
    for (var si = 0; si < sourceItems.length; si++) {
        var item = sourceItems[si];
        if (!item || !isInlineFlow(item) || isTextLike(item)) continue;
        diagnostics.examinedInlineSourceCount++;
        if (item.visible === false || item.hiddenLayer === true || item.nonprinting === true) {
            noteSkip("source_not_visible", item, null, {
                visible: item.visible,
                hiddenLayer: item.hiddenLayer,
                nonprinting: item.nonprinting
            });
            continue;
        }
        if (hasFloatingAnchoredAncestor(item)) {
            noteSkip("floating_anchored_ancestor_owns_visual_tree", item, null, null);
            continue;
        }
        var root = topInlineVisualRoot(item);
        if (!root || isTextLike(root)) {
            noteSkip("no_inline_visual_root", item, root, null);
            continue;
        }
        var rootKey = String(root.id);
        if (roots[rootKey]) {
            noteSkip("duplicate_root", item, root, null);
            continue;
        }
        if (!subtreeHasVisibleMaterial(root.id)) {
            noteSkip("no_visible_material", item, root, null);
            continue;
        }
        if (_inlineTableShellFullyAbsorbedByTableStyle(root.id, sourceInfoById, childIdsByParentId)) {
            // 표 전용 TF + 표 속성 rect 만으로 이루어진 인라인 그룹.
            // 표 텍스트/스타일은 table_only_text_frame plan 이 소유하므로
            // PNG 후보를 만들면 표가 이미지로 중복된다.
            noteSkip("inline_table_shell_style_absorbed", item, root, null);
            continue;
        }
        diagnostics.inlineVisibleMaterialSourceCount++;
        roots[rootKey] = true;
        diagnostics.rootCount++;
        var hiddenTextIds = editableTextIds(root.id);
        var subtreeIds = descendants(root.id);
        var exportIds = visibleIds(subtreeIds);
        if (!exportIds || exportIds.length === 0) {
            noteSkip("root_without_export_ids", item, root, { subtreeIds: subtreeIds });
            continue;
        }
        if (fullDiagnostics && sourceAlreadyCovered(exportIds)) {
            noteSkip("source_previously_claimed_but_still_planned", item, root, { exportIds: exportIds });
        }
        var pageIndex = Number(root.pageIndex);
        if (isNaN(pageIndex) || pageIndex < 0) pageIndex = Number(item.pageIndex);
        var candidateId = _candidateCompositeId(
                "pass.inline_objects",
                pageIndex,
                subtreeIds,
                "inline_flow_visual_root_" + rootKey);
        if (candidateSeen && candidateSeen[candidateId]) {
            noteSkip("candidate_id_seen", item, root, { candidateId: candidateId });
            continue;
        }
        if (candidateSeen) candidateSeen[candidateId] = true;
        var ownsTextByCompletePng = hiddenTextIds.length > 1;
        appended.push({
            candidateId: candidateId,
            passId: "pass.inline_objects",
            sourceObjectIds: subtreeIds,
            executionSourceObjectIds: hiddenTextIds.length > 0 ? subtreeIds.slice(0) : exportIds.slice(0),
            primarySourceObjectId: root.id,
            pageIndex: pageIndex,
            kind: root.kind || "InlineFlowVisualRoot",
            unit: "INLINE_OBJECT",
            mode: "TEXTLESS_CANDIDATE",
            candidatePurpose: "INLINE_CANDIDATE",
            bounds: unionSourceBounds(subtreeIds) || root.bounds || null,
            parentId: root.parentId !== undefined ? root.parentId : null,
            parentKind: root.parentKind || null,
            composite: subtreeIds.length > 1,
            compositeRole: "inline_flow_visual_root",
            slotRole: "inline_flow_visual_root",
            exportSourceObjectIds: exportIds.slice(0),
            exportTargetObjectId: root.id,
            hiddenVisualSourceObjectIds: [],
            visualSourceObjectIds: exportIds.slice(0),
            styleSourceObjectIds: [],
            ownedTextFrameIds: hiddenTextIds.slice(0),
            editableTextFrameIds: hiddenTextIds.slice(0),
            hiddenTextFrameIds: hiddenTextIds.slice(0),
            requiresTextHidden: hiddenTextIds.length > 0 && ownsTextByCompletePng !== true,
            textOwner: ownsTextByCompletePng
                    ? "indesign_png"
                    : (hiddenTextIds.length > 0 ? "hwpx_tf" : "none"),
            containsEditableText: false,
            completePngTextAllowed: ownsTextByCompletePng,
            ownershipSlot: "CONTENT_VISUAL_SLOT",
            materialization: ownsTextByCompletePng ? "COMPLETE_PNG" : "EXTRACTED_PNG_VECTOR",
            textAction: ownsTextByCompletePng
                    ? "OWNED_BY_PNG"
                    : (hiddenTextIds.length > 0 ? "OWNED_BY_HWPX_TEXT" : "DROP_TEXT"),
            visualAction: "PLACE_INLINE_PNG",
            visualLayer: "CONTENT_VISUAL",
            placement: "INLINE",
            coordinateSpace: "STORY_FLOW",
            inlineAnchorSourceObjectId: root.id,
            inlineSourceTreeClosed: true,
            zOrder: root.zOrder !== undefined ? root.zOrder : 0,
            required: false,
            reason: "inline_flow_visual_root"
        });
    }
    for (var ai = 0; ai < appended.length; ai++) candidates.push(appended[ai]);
    diagnostics.appendedCount = appended.length;
    return {
        appendedCount: appended.length,
        appended: fullDiagnostics ? appended : [],
        skipCounts: diagnostics.skipCounts,
        samples: fullDiagnostics ? diagnostics.samples : [],
        diagnostics: diagnostics
    };
}

function _inlineFlowVisualRootCandidateSnapshot(candidates, label) {
    var out = {
        label: label || "",
        count: 0,
        candidates: []
    };
    for (var i = 0; candidates && i < candidates.length; i++) {
        var c = candidates[i];
        if (!c) continue;
        if (c.compositeRole !== "inline_flow_visual_root"
                && c.slotRole !== "inline_flow_visual_root"
                && c.reason !== "inline_flow_visual_root") {
            continue;
        }
        out.count++;
        if (out.candidates.length >= 80) continue;
        out.candidates.push({
            candidateId: c.candidateId || null,
            passId: c.passId || null,
            pageIndex: c.pageIndex,
            sourceObjectIds: c.sourceObjectIds || [],
            visualSourceObjectIds: c.visualSourceObjectIds || [],
            exportSourceObjectIds: c.exportSourceObjectIds || [],
            hiddenVisualSourceObjectIds: c.hiddenVisualSourceObjectIds || [],
            editableTextFrameIds: c.editableTextFrameIds || [],
            hiddenTextFrameIds: c.hiddenTextFrameIds || [],
            ownedTextFrameIds: c.ownedTextFrameIds || [],
            textAction: c.textAction || null,
            visualAction: c.visualAction || null,
            ownershipSlot: c.ownershipSlot || null,
            placement: c.placement || null,
            coordinateSpace: c.coordinateSpace || null,
            suppressedByDirectChildShellSlots: c.suppressedByDirectChildShellSlots === true,
            suppressedByDirectChildShellSlotsReason: c.suppressedByDirectChildShellSlotsReason || null
        });
    }
    return out;
}

function _candidateListHasObjectPlanLikeExecutionFields(candidates) {
    if (!candidates || candidates.length === 0) return false;
    var checked = 0;
    for (var i = 0; i < candidates.length; i++) {
        var candidate = candidates[i];
        if (!candidate || candidate.disabled === true) continue;
        if (candidate.passId === "pass.master_page_graphics") continue;
        checked++;
        if (!candidate.visualAction || !candidate.placement || !candidate.ownershipSlot) {
            return false;
        }
    }
    return checked > 0;
}

function _singleTextlessPlaneEmptyDiagnostics(candidates, mode, reason) {
    return {
        candidates: candidates || [],
        appendedCount: 0,
        appended: [],
        assignedCount: 0,
        assigned: [],
        componentCount: 0,
        components: [],
        mergedCount: 0,
        merged: [],
        suppressedCount: 0,
        suppressed: [],
        warningCount: 0,
        warnings: [],
        skippedByGraphicsMode: mode || null,
        reason: reason || "single_textless_plane_owns_page_visuals"
    };
}

function _appendMixedBundlePlacedVisualCandidates(sourceItems, sourceIndex, candidates, candidateSeen) {
    var sourceById = {};
    var childIdsByParentId = {};
    var appended = 0;
    var skipped = {
        noPlacedVisual: 0,
        noMixedOwnerGroup: 0,
        noSourceIds: 0,
        duplicate: 0
    };

    for (var si = 0; sourceItems && si < sourceItems.length; si++) {
        var src = sourceItems[si];
        if (!src || src.id === null || src.id === undefined) continue;
        sourceById[String(src.id)] = src;
        if (src.parentId !== null && src.parentId !== undefined) {
            var parentKey = String(src.parentId);
            if (!childIdsByParentId[parentKey]) childIdsByParentId[parentKey] = [];
            childIdsByParentId[parentKey].push(src.id);
        }
    }

    function sourceKind(src) {
        return String((src && (src.kind || src.type || src.itemType)) || "");
    }

    function isPagePositioned(src) {
        if (!src) return false;
        if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) return false;
        if (src.pageIndex === null || src.pageIndex === undefined || Number(src.pageIndex) < 0) return false;
        if (src.storyTextInlineSlot === true || src.tableCellStoryTextInlineSlot === true) return false;
        if (src.storyAnchorPlacement && String(src.storyAnchorPlacement) !== "PAGE") return false;
        if (String(src.anchoredPosition || "").toUpperCase() === "INLINE_POSITION") return false;
        return true;
    }

    function hasPlacedVisualBranch(src) {
        if (!src) return false;
        var kind = sourceKind(src);
        if (kind === "Image" || kind === "PDF" || kind === "EPS") return true;
        return src.hasPlacedVisual === true;
    }

    function subtreeHasEditableText(sourceId, visited) {
        var key = String(sourceId);
        if (visited[key]) return false;
        visited[key] = true;
        var src = sourceById[key];
        if (!src) return false;
        if (sourceKind(src) === "TextFrame"
                && src.textFrameClass === "editable"
                && (src.hasText === true || Number(src.textLength || 0) > 0)) {
            return true;
        }
        var children = childIdsByParentId[key] || [];
        for (var ci = 0; ci < children.length; ci++) {
            if (subtreeHasEditableText(children[ci], visited)) return true;
        }
        return false;
    }

    function groupHasEditableTextOutsideSubtree(groupId, subtreeRootId) {
        var children = childIdsByParentId[String(groupId)] || [];
        for (var ci = 0; ci < children.length; ci++) {
            var childId = children[ci];
            if (String(childId) === String(subtreeRootId)) continue;
            if (subtreeHasEditableText(childId, {})) return true;
        }
        return false;
    }

    function mixedOwnerGroupForPlacedBranch(src) {
        var parentId = src ? src.parentId : null;
        var guard = 0;
        while (parentId !== null && parentId !== undefined && guard < 32) {
            var parent = sourceById[String(parentId)];
            if (!parent || sourceKind(parent) !== "Group") break;
            if (parent.hiddenLayer === true || parent.visible === false || parent.nonprinting === true) break;
            if (parent.pageIndex !== null && parent.pageIndex !== undefined
                    && src.pageIndex !== null && src.pageIndex !== undefined
                    && Number(parent.pageIndex) !== Number(src.pageIndex)) {
                break;
            }
            if (groupHasEditableTextOutsideSubtree(parent.id, src.id)) return parent;
            parentId = parent.parentId;
            guard++;
        }
        return null;
    }

    function nonNoneTextWrapSource(src) {
        if (!src) return null;
        var mode = String(src.textWrapMode || "");
        if (!mode || mode === "None") return null;
        return src;
    }

    function textWrapContractForPlacedBranch(src, ownerGroup) {
        var contractSource = nonNoneTextWrapSource(src) || nonNoneTextWrapSource(ownerGroup);
        var parentId = src ? src.parentId : null;
        var guard = 0;
        while (!contractSource && parentId !== null && parentId !== undefined && guard < 32) {
            var parent = sourceById[String(parentId)];
            contractSource = nonNoneTextWrapSource(parent);
            if (contractSource) break;
            parentId = parent ? parent.parentId : null;
            guard++;
        }
        if (!contractSource) return null;
        return {
            sourceObjectId: contractSource.id,
            mode: contractSource.textWrapMode,
            side: contractSource.textWrapSide || "BothSides",
            top: Number(contractSource.textWrapTop || 0),
            left: Number(contractSource.textWrapLeft || 0),
            bottom: Number(contractSource.textWrapBottom || 0),
            right: Number(contractSource.textWrapRight || 0)
        };
    }

    function pageLocalSourceObjectIds(sourceId, pageIndex) {
        try {
            var ids = sourceIndex && sourceIndex.pageLocalSourceObjectIds
                    ? sourceIndex.pageLocalSourceObjectIds(sourceId, pageIndex)
                    : null;
            if (ids && ids.length > 0) return _sortedNumericIds(ids);
        } catch (ePageLocalMixedPlacedVisual) {}
        return [sourceId];
    }

    for (var i = 0; sourceItems && i < sourceItems.length; i++) {
        var itemInfo = sourceItems[i];
        if (!isPagePositioned(itemInfo)) continue;
        if (!hasPlacedVisualBranch(itemInfo)) {
            skipped.noPlacedVisual++;
            continue;
        }
        var ownerGroup = mixedOwnerGroupForPlacedBranch(itemInfo);
        if (!ownerGroup) {
            skipped.noMixedOwnerGroup++;
            continue;
        }
        var sourceObjectIds = pageLocalSourceObjectIds(itemInfo.id, itemInfo.pageIndex);
        if (!sourceObjectIds || sourceObjectIds.length === 0) {
            skipped.noSourceIds++;
            continue;
        }
        var before = candidates ? candidates.length : 0;
        var item = null;
        try { item = sourceIndex && sourceIndex.domItem ? sourceIndex.domItem(itemInfo.id) : null; } catch (eDomItemMixedPlacedVisual) {}
        var candidateAttrs = {
            sourceId: itemInfo.id,
            sourceObjectIds: sourceObjectIds,
            visualSourceObjectIds: sourceObjectIds,
            exportSourceObjectIds: sourceObjectIds,
            exportTargetObjectId: itemInfo.id,
            pageIndex: itemInfo.pageIndex,
            kind: itemInfo.kind,
            unit: "ITEM",
            mode: "ORIGINAL_VISUAL",
            candidatePurpose: "CONTENT_CANDIDATE",
            compositeRole: sourceObjectIds.length > 1
                    ? "mixed_bundle_placed_visual_branch"
                    : null,
            slotRole: "content_visual_slot",
            bounds: itemInfo.bounds,
            parentId: itemInfo.parentId,
            parentKind: itemInfo.parentKind,
            anchoredPosition: itemInfo.anchoredPosition,
            storyAnchorPlacement: itemInfo.storyAnchorPlacement,
            zOrder: itemInfo.zOrder,
            textOwner: "none",
            containsEditableText: false,
            mixedOwnerGroupSourceObjectId: ownerGroup.id,
            requiredSlot: "CONTENT_VISUAL_SLOT",
            requiredSlotReason: "mixed_source_bundle_placed_visual_branch"
        };
        var textWrapContract = textWrapContractForPlacedBranch(itemInfo, ownerGroup);
        if (textWrapContract) {
            candidateAttrs.textWrapMode = textWrapContract.mode;
            candidateAttrs.textWrapSide = textWrapContract.side;
            candidateAttrs.textWrapTop = textWrapContract.top;
            candidateAttrs.textWrapLeft = textWrapContract.left;
            candidateAttrs.textWrapBottom = textWrapContract.bottom;
            candidateAttrs.textWrapRight = textWrapContract.right;
            candidateAttrs.textWrapSourceObjectId = textWrapContract.sourceObjectId;
        }
        _pushExtractionCandidate(candidates, candidateSeen,
                "pass.image_placed_frames", item, candidateAttrs);
        if ((candidates ? candidates.length : 0) > before) {
            appended++;
        } else {
            skipped.duplicate++;
        }
    }

    return {
        appendedCount: appended,
        skipped: skipped
    };
}

function _canonicalPagePlaneSyntheticSourceId(pageIndex) {
    return -940000000 + Number(pageIndex || 0);
}

function _canonicalPagePlaneCandidateId(pageIndex) {
    return "cand.pass.page_textless_graphic_groups.page."
            + String(pageIndex)
            + ".single_textless_page_plane";
}

function _canonicalPagePlaneObjectPlanId(pageIndex) {
    return "objectPlan.page_textless_plane.page." + String(pageIndex);
}

function _canonicalPagePlaneBundleId(pageIndex) {
    return "bundle.page_textless_plane.page." + String(pageIndex);
}

function _canonicalPagePlaneBounds(doc, pageIndex) {
    try {
        var page = doc.pages[pageIndex];
        var pb = page.bounds;
        return [0, 0, Number(pb[2]) - Number(pb[0]), Number(pb[3]) - Number(pb[1])];
    } catch (ePagePlaneBounds) {}
    return null;
}

function _canonicalPagePlaneExistingPlanSourceIds(plan) {
    var ids = [];
    var seen = {};
    function add(values) {
        for (var i = 0; values && i < values.length; i++) {
            var id = Number(values[i]);
            if (isNaN(id)) continue;
            if (seen[String(id)]) continue;
            seen[String(id)] = true;
            ids.push(id);
        }
    }
    add(plan ? plan.sourceObjectIds : []);
    add(plan ? plan.visualSourceObjectIds : []);
    add(plan ? plan.exportSourceObjectIds : []);
    return _sortedNumericIds(ids);
}

function _canonicalPagePlaneShouldOwnExistingPlan(plan) {
    if (!plan) return false;
    if (plan.materialization === "PAGE_PLANE_PNG") return false;
    if (plan.absorbedByObjectPlanId) return false;
    if (plan.placement !== "FLOATING" || plan.coordinateSpace !== "PAGE") return false;
    if (plan.layoutOnlyInlineSlot === true
            || plan.sourceInlineFlow === true
            || plan.inlineCompositeLayoutDescendant === true
            || plan.pagePositionedAnchoredSource === true
            || (plan.inlineAnchorSourceObjectId !== null
                    && plan.inlineAnchorSourceObjectId !== undefined)) {
        return false;
    }
    if (plan.textAction === "OWNED_BY_PNG"
            || plan.completePngTextAllowed === true
            || plan.materialization === "COMPLETE_PNG") {
        return false;
    }
    if (plan.visualAction === "PLACE_TABLE_STYLE"
            || plan.materialization === "HWPX_TABLE_STYLE"
            || plan.ownershipSlot === "TABLE_STYLE_SLOT"
            || plan.slotRole === "table_textless_shell_slot"
            || plan.compositeRole === "table_carrier_textless_shell") {
        return false;
    }
    if (plan.textWrapMode || plan.textWrapSourceObjectId !== null
            && plan.textWrapSourceObjectId !== undefined) {
        return false;
    }
    if (plan.visualAction !== "PLACE_FLOATING_PNG"
            && plan.visualAction !== "PLACE_TEXT_SHELL"
            && plan.visualAction !== "PLACE_PAGE_BACKGROUND_PNG") {
        return false;
    }
    if (plan.materialization !== "EXTRACTED_PNG_VECTOR"
            && plan.materialization !== "TEXTLESS_VISUAL_FRAGMENT") {
        return false;
    }
    if (plan.ownedTextFrameIds && plan.ownedTextFrameIds.length > 0) return false;
    if (plan.textAction !== "DROP_TEXT") return false;
    return _canonicalPagePlaneExistingPlanSourceIds(plan).length > 0;
}

function _canonicalPagePlaneAbsorbExistingObjectPlans(objectPlanDiagnostics) {
    var objectPlans = objectPlanDiagnostics && objectPlanDiagnostics.objectPlans
            ? objectPlanDiagnostics.objectPlans
            : [];
    var planeByPage = {};
    var absorbed = [];

    function addCoverage(plane, ids) {
        if (!plane || !ids || ids.length === 0) return;
        var existing = plane.coverageSourceObjectIds || [];
        plane.coverageSourceObjectIds = _sortedNumericIds(existing.concat(ids));
    }

    for (var pi = 0; pi < objectPlans.length; pi++) {
        var plan = objectPlans[pi];
        if (!plan || plan.materialization !== "PAGE_PLANE_PNG") continue;
        if (plan.slotRole !== "page_textless_plane"
                && plan.compositeRole !== "single_textless_page_plane") continue;
        planeByPage[String(plan.pageIndex)] = plan;
    }

    for (var i = 0; i < objectPlans.length; i++) {
        var member = objectPlans[i];
        if (!_canonicalPagePlaneShouldOwnExistingPlan(member)) continue;
        var plane = planeByPage[String(member.pageIndex)];
        if (!plane) continue;
        var memberSourceIds = _canonicalPagePlaneExistingPlanSourceIds(member);
        member.absorbedByObjectPlanId = plane.objectPlanId;
        member.absorbedByMaterialization = "PAGE_PLANE_PNG";
        member.visualAction = "DROP_VISUAL";
        member.materialization = "HWPX_TEXT";
        member.executable = false;
        member.required = false;
        member.disabled = true;
        member.reason = String(member.reason || "object_plan")
                + ":absorbed_by_canonical_single_textless_page_plane";
        if (!plane.absorbedObjectPlanIds) plane.absorbedObjectPlanIds = [];
        plane.absorbedObjectPlanIds.push(member.objectPlanId || member.candidateId || null);
        addCoverage(plane, memberSourceIds);
        absorbed.push({
            pageIndex: member.pageIndex,
            objectPlanId: member.objectPlanId || null,
            candidateId: member.candidateId || null,
            passId: member.passId || null,
            sourceObjectIds: memberSourceIds
        });
    }

    for (var pk in planeByPage) {
        if (!planeByPage.hasOwnProperty(pk)) continue;
        var planePlan = planeByPage[pk];
        if (planePlan.absorbedObjectPlanIds) {
            planePlan.absorbedObjectPlanIds = _sortedStringValues(planePlan.absorbedObjectPlanIds);
            planePlan.reason = String(planePlan.reason || "canonical_single_textless_page_plane")
                    + ":owns_absorbed_page_floating_textless_visuals";
        }
    }

    if (objectPlanDiagnostics && objectPlanDiagnostics.summary) {
        objectPlanDiagnostics.summary.canonicalPagePlaneAbsorption = {
            absorbedPlanCount: absorbed.length,
            absorbed: absorbed.slice(0, 200)
        };
    }
    return {
        absorbedPlanCount: absorbed.length,
        absorbed: absorbed
    };
}

function _sortedStringValues(values) {
    var out = [];
    var seen = {};
    for (var i = 0; values && i < values.length; i++) {
        if (values[i] === null || values[i] === undefined) continue;
        var key = String(values[i]);
        if (seen[key]) continue;
        seen[key] = true;
        out.push(key);
    }
    out.sort();
    return out;
}

function _appendCanonicalPagePlaneObjectPlans(doc, sourceItems, objectPlanDiagnostics) {
    if (!objectPlanDiagnostics || !objectPlanDiagnostics.objectPlans) {
        return { appendedCount: 0, appended: [] };
    }
    var objectPlans = objectPlanDiagnostics.objectPlans;
    var claimedVisibleSourceIds = {};
    var completePngTextOwnerSourceIds = {};
    var tableStyleHiddenSourceIdsByPage = {};
    var absorbableExistingPlanSourceIdsByPage = {};
    function markIds(ids) {
        for (var i = 0; ids && i < ids.length; i++) {
            if (ids[i] === null || ids[i] === undefined) continue;
            claimedVisibleSourceIds[String(ids[i])] = true;
        }
    }
    function addHiddenTableStyleIds(pageIndex, ids) {
        if (pageIndex === null || pageIndex === undefined) return;
        var pageKey = String(pageIndex);
        if (!tableStyleHiddenSourceIdsByPage[pageKey]) tableStyleHiddenSourceIdsByPage[pageKey] = [];
        var seen = {};
        for (var existingIndex = 0; existingIndex < tableStyleHiddenSourceIdsByPage[pageKey].length; existingIndex++) {
            seen[String(tableStyleHiddenSourceIdsByPage[pageKey][existingIndex])] = true;
        }
        for (var i = 0; ids && i < ids.length; i++) {
            if (ids[i] === null || ids[i] === undefined) continue;
            if (seen[String(ids[i])]) continue;
            seen[String(ids[i])] = true;
            tableStyleHiddenSourceIdsByPage[pageKey].push(ids[i]);
        }
        tableStyleHiddenSourceIdsByPage[pageKey] =
                _sortedNumericIds(tableStyleHiddenSourceIdsByPage[pageKey]);
    }
    function markTableStyleSourceIds(plan) {
        if (!plan) return;
        if (plan.materialization !== "HWPX_TABLE_STYLE"
                && plan.visualAction !== "PLACE_TABLE_STYLE"
                && plan.ownershipSlot !== "TABLE_STYLE_SLOT") return;
        var ids = [];
        ids = ids.concat(plan.sourceObjectIds || []);
        ids = ids.concat(plan.visualSourceObjectIds || []);
        ids = ids.concat(plan.styleSourceObjectIds || []);
        ids = ids.concat(plan.exportSourceObjectIds || []);
        ids = ids.concat(plan.hiddenVisualSourceObjectIds || []);
        markIds(ids);
        addHiddenTableStyleIds(plan.pageIndex, ids);
    }
    function markCompletePngTextOwnerIds(plan) {
        if (!plan) return;
        if (plan.textAction !== "OWNED_BY_PNG") return;
        if (plan.materialization !== "COMPLETE_PNG") return;
        if (plan.visualAction !== "PLACE_INLINE_PNG"
                && plan.visualAction !== "PLACE_FLOATING_PNG") return;
        if (!plan.ownedTextFrameIds || plan.ownedTextFrameIds.length === 0) return;
        var ids = [];
        ids = ids.concat(plan.sourceObjectIds || []);
        ids = ids.concat(plan.visualSourceObjectIds || []);
        ids = ids.concat(plan.exportSourceObjectIds || []);
        ids = ids.concat(plan.hiddenVisualSourceObjectIds || []);
        ids = ids.concat(plan.ownedTextFrameIds || []);
        for (var ci = 0; ci < ids.length; ci++) {
            if (ids[ci] === null || ids[ci] === undefined) continue;
            completePngTextOwnerSourceIds[String(ids[ci])] = true;
        }
        markIds(ids);
    }
    function rememberAbsorbableExistingPlan(plan) {
        if (!_canonicalPagePlaneShouldOwnExistingPlan(plan)) return false;
        var pageKey = String(plan.pageIndex);
        if (!absorbableExistingPlanSourceIdsByPage[pageKey]) {
            absorbableExistingPlanSourceIdsByPage[pageKey] = [];
        }
        absorbableExistingPlanSourceIdsByPage[pageKey] = _sortedNumericIds(
                absorbableExistingPlanSourceIdsByPage[pageKey].concat(
                        _canonicalPagePlaneExistingPlanSourceIds(plan)));
        return true;
    }
    try {
        if (typeof _tableStyleSourceObjectIdsByPageForPagePlaneHide === "function") {
            var sourceBasedTableStyleHiddenIdsByPage =
                    _tableStyleSourceObjectIdsByPageForPagePlaneHide(sourceItems || []);
            for (var sourceBasedPageKey in sourceBasedTableStyleHiddenIdsByPage) {
                if (!sourceBasedTableStyleHiddenIdsByPage.hasOwnProperty(sourceBasedPageKey)) continue;
                var sourceBasedIds = sourceBasedTableStyleHiddenIdsByPage[sourceBasedPageKey] || [];
                addHiddenTableStyleIds(Number(sourceBasedPageKey), sourceBasedIds);
                markIds(sourceBasedIds);
            }
        }
    } catch (eSourceBasedTableStyleHide) {}
    for (var pi = 0; pi < objectPlans.length; pi++) {
        var existing = objectPlans[pi];
        if (!existing || existing.materialization === "PAGE_PLANE_PNG") continue;
        markTableStyleSourceIds(existing);
        markCompletePngTextOwnerIds(existing);
        if (!_sourceCoveragePlanHasVisibleVisual(existing)) continue;
        if (rememberAbsorbableExistingPlan(existing)) continue;
        markIds(existing.sourceObjectIds || []);
        markIds(existing.visualSourceObjectIds || []);
        markIds(existing.exportSourceObjectIds || []);
        markIds(existing.hiddenVisualSourceObjectIds || []);
    }

    var coverageByPage = {};
    var excludedInlineByPage = {};
    function sourceKind(src) {
        return String((src && (src.kind || src.type || src.itemType)) || "");
    }
    function hasVisibleFramePaint(src) {
        if (!src) return false;
        if (src.hasVisibleFill === true || src.hasVisibleStroke === true) return true;
        var fill = String(src.fillColorName || src.fillColor || "");
        if (fill && fill !== "None" && fill !== "[None]") return true;
        var stroke = String(src.strokeColorName || src.strokeColor || "");
        var strokeWeight = Number(src.strokeWeight || 0);
        return stroke && stroke !== "None" && stroke !== "[None]" && strokeWeight > 0;
    }
    function isStoryFlowInlineTextFramePaintSource(src) {
        if (!src || sourceKind(src) !== "TextFrame") return false;
        if (!hasVisibleFramePaint(src)) return false;
        if (src.storyTextInlineSlot === true) return true;
        if (String(src.storyAnchorPlacement || "").toUpperCase() === "INLINE") return true;
        if (String(src.anchoredPosition || "").toUpperCase() === "INLINE_POSITION") return true;
        return src.isInline === true;
    }
    function rememberExcludedInlineSource(src) {
        if (!isStoryFlowInlineTextFramePaintSource(src)) return;
        var pageIndex = src.pageIndex === null || src.pageIndex === undefined
                ? -1
                : Number(src.pageIndex);
        if (isNaN(pageIndex) || pageIndex < 0) return;
        var pageKey = String(pageIndex);
        if (!excludedInlineByPage[pageKey]) excludedInlineByPage[pageKey] = [];
        excludedInlineByPage[pageKey].push(src.id);
    }
    for (var si = 0; sourceItems && si < sourceItems.length; si++) {
        var src = sourceItems[si];
        if (!src || src.id === null || src.id === undefined) continue;
        rememberExcludedInlineSource(src);
        if (claimedVisibleSourceIds[String(src.id)]) continue;
        if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) continue;
        if (src.pageIndex === null || src.pageIndex === undefined || Number(src.pageIndex) < 0) continue;
        if (isStoryFlowInlineTextFramePaintSource(src)) continue;
        if (src.storyTextInlineSlot === true) continue;
        if (src.storyAnchorPlacement && String(src.storyAnchorPlacement) !== "PAGE") continue;
        if (!_sourceCoverageHasPotentialVisibleMaterial(src)) continue;
        var pageKey = String(src.pageIndex);
        if (!coverageByPage[pageKey]) coverageByPage[pageKey] = [];
        coverageByPage[pageKey].push(src.id);
    }
    for (var absorbablePageKey in absorbableExistingPlanSourceIdsByPage) {
        if (!absorbableExistingPlanSourceIdsByPage.hasOwnProperty(absorbablePageKey)) continue;
        if (!coverageByPage[absorbablePageKey]) coverageByPage[absorbablePageKey] = [];
        coverageByPage[absorbablePageKey] = _sortedNumericIds(
                coverageByPage[absorbablePageKey].concat(
                        absorbableExistingPlanSourceIdsByPage[absorbablePageKey] || []));
    }

    var appended = [];
    for (var pageKey in coverageByPage) {
        if (!coverageByPage.hasOwnProperty(pageKey)) continue;
        var pageIndex = Number(pageKey);
        var coverageIds = _sortedNumericIds(coverageByPage[pageKey]);
        if (coverageIds.length === 0) continue;
        var excludedInlineSourceObjectIds = _sortedNumericIds(excludedInlineByPage[pageKey] || []);
        var hiddenTableStyleSourceObjectIds =
                _sortedNumericIds(tableStyleHiddenSourceIdsByPage[pageKey] || []);
        var syntheticSourceId = _canonicalPagePlaneSyntheticSourceId(pageIndex);
        var plan = {
            objectPlanId: _canonicalPagePlaneObjectPlanId(pageIndex),
            bundleId: _canonicalPagePlaneBundleId(pageIndex),
            candidateId: _canonicalPagePlaneCandidateId(pageIndex),
            passId: "pass.page_textless_graphic_groups",
            pageIndex: pageIndex,
            kind: "PAGE_TEXTLESS_PLANE",
            unit: "PAGE",
            mode: "TEXTLESS_PAGE_PLANE",
            candidatePurpose: "PAGE_PLANE",
            compositeRole: "single_textless_page_plane",
            slotRole: "page_textless_plane",
            primarySourceObjectId: syntheticSourceId,
            sourceObjectIds: [syntheticSourceId],
            sourceRootObjectIds: [syntheticSourceId],
            clusterSourceObjectIds: [syntheticSourceId],
            visualSourceObjectIds: [syntheticSourceId],
            exportSourceObjectIds: [syntheticSourceId],
            coverageSourceObjectIds: coverageIds,
            hiddenTableStyleSourceObjectIds: hiddenTableStyleSourceObjectIds,
            excludedInlineSourceObjectIds: excludedInlineSourceObjectIds,
            materialization: "PAGE_PLANE_PNG",
            textAction: "DROP_TEXT",
            visualAction: "PLACE_FLOATING_PNG",
            placement: "FLOATING",
            coordinateSpace: "PAGE",
            visualLayer: "PAGE_BACKGROUND",
            policyLayer: "BACKGROUND",
            zOrder: -900000,
            reason: "canonical_single_textless_page_plane_owns_page_floating_visual_sources",
            bounds: _canonicalPagePlaneBounds(doc, pageIndex),
            ownershipSlot: "CONTENT_VISUAL_SLOT",
            contractStatus: "READY_FOR_STAGE1_IMPORT",
            migrationStatus: "READY_EXACT_CLUSTER",
            migrationBlocker: "NONE",
            executable: true,
            required: true,
            requiredSlot: "CONTENT_VISUAL_SLOT",
            requiredSlotReason: "page_plane_canonical_visual_owner",
            excludedInlineSourceCount: excludedInlineSourceObjectIds.length,
            hiddenTableStyleSourceCount: hiddenTableStyleSourceObjectIds.length
        };
        objectPlans.push(plan);
        appended.push({
            pageIndex: pageIndex,
            objectPlanId: plan.objectPlanId,
            candidateId: plan.candidateId,
            syntheticSourceObjectId: syntheticSourceId,
            coverageSourceCount: coverageIds.length,
            excludedInlineSourceCount: excludedInlineSourceObjectIds.length,
            hiddenTableStyleSourceCount: hiddenTableStyleSourceObjectIds.length
        });
    }

    if (objectPlanDiagnostics.summary) {
        objectPlanDiagnostics.summary.planCount = objectPlans.length;
        objectPlanDiagnostics.summary.objectPlanCount = objectPlans.length;
        objectPlanDiagnostics.summary.executablePlanCount =
                (objectPlanDiagnostics.summary.executablePlanCount || 0) + appended.length;
        objectPlanDiagnostics.summary.importReadyPlanCount =
                (objectPlanDiagnostics.summary.importReadyPlanCount || 0) + appended.length;
        if (!objectPlanDiagnostics.summary.materializationCounts) {
            objectPlanDiagnostics.summary.materializationCounts = {};
        }
        objectPlanDiagnostics.summary.materializationCounts.PAGE_PLANE_PNG =
                (objectPlanDiagnostics.summary.materializationCounts.PAGE_PLANE_PNG || 0)
                + appended.length;
        objectPlanDiagnostics.summary.pagePlaneMaterialization = {
            appendedCount: appended.length,
            appended: appended,
            completePngTextOwnerExcludedSourceCount:
                    _objectPlanMapKeyCount(completePngTextOwnerSourceIds),
            hiddenTableStyleSourcePageCount:
                    _objectPlanMapKeyCount(tableStyleHiddenSourceIdsByPage)
        };
    }
    return {
        appendedCount: appended.length,
        appended: appended,
        completePngTextOwnerExcludedSourceCount:
                _objectPlanMapKeyCount(completePngTextOwnerSourceIds),
        hiddenTableStyleSourcePageCount:
                _objectPlanMapKeyCount(tableStyleHiddenSourceIdsByPage),
        hiddenTableStyleSourceIdsByPage: tableStyleHiddenSourceIdsByPage
    };
}

function _buildExtractionPlan(doc, ctx, allItems) {
    _marker(ctx.outputDir, "03d01_plan_init");
    try { writeProgress(ctx.outputDir, "planning_source_index", 0, allItems ? allItems.length : 0, "build source index"); } catch (ePlanSourceIndexProgressStart) {}
    var candidates = [];
    var candidateSeen = {};
    var sourceIndex = _buildSourceIndexFromAllItems(doc, ctx, allItems);
    try { writeProgress(ctx.outputDir, "planning_source_index", allItems ? allItems.length : 0, allItems ? allItems.length : 0, "source index ready"); } catch (ePlanSourceIndexProgressDone) {}
    _marker(ctx.outputDir, "03d01g_plan_sourceIndex_returned");
    ctx._sourceIndexForRender = sourceIndex;
    var sourceItems = sourceIndex.sourceItems;
    try {
        _marker(ctx.outputDir, "03d01h_pagePlaneTableStyleHide_start");
        if (!ctx.pagePlaneHiddenTableStyleSourceObjectIdsByPage
                && typeof _tableStyleSourceObjectIdsByPageForPagePlaneHide === "function") {
            ctx.pagePlaneHiddenTableStyleSourceObjectIdsByPage =
                    _tableStyleSourceObjectIdsByPageForPagePlaneHide(sourceItems || []);
        }
        if (typeof _globalSourceBundleTextRangeShellInlineHideCandidates === "function") {
            ctx.pagePlaneSourceBundleTextRangeShellHideCandidateCount =
                    _globalSourceBundleTextRangeShellInlineHideCandidates(sourceItems || []).length;
        }
        _marker(ctx.outputDir, "03d01i_pagePlaneTableStyleHide_done");
    } catch (ePagePlaneTableStyleHideFromPlan) {
        ctx.pagePlaneHiddenTableStyleSourceObjectIdsByPage =
                ctx.pagePlaneHiddenTableStyleSourceObjectIdsByPage || {};
        ctx.pagePlaneSourceBundleTextRangeShellHideCandidateCount =
                ctx.pagePlaneSourceBundleTextRangeShellHideCandidateCount || 0;
        try { _marker(ctx.outputDir, "03d01i_pagePlaneTableStyleHide_fallback"); } catch (ePagePlaneTableStyleHideMarker) {}
    }
    try { writeJson(ctx.outputDir + "/_source_index_stats.json", sourceIndex.stats || {}); } catch (eSourceIndexStats) {}
    _marker(ctx.outputDir, "03d02_plan_sourceItems");

    _marker(ctx.outputDir, "03d03_plan_childIndex");

    var sourceClusterDiagnostics = _buildSourceClusters(sourceItems);
    var sourceClusterIndex = _createSourceClusterIndex(sourceItems, sourceClusterDiagnostics);
    _marker(ctx.outputDir, "03d04_plan_sourceClusters");
    try {
        writeJson(ctx.outputDir + "/_base_candidate_perf.json", {
            stage: "03d05_plan_baseCandidates",
            sourceItemCount: sourceItems ? sourceItems.length : 0,
            candidateCountAtStart: 0,
            candidateCountAtEnd: 0,
            elapsedMs: 0,
            reason: "page_visuals_are_exported_by_single_textless_page_plane"
        });
    } catch (eSinglePlaneBasePerf) {}
    _marker(ctx.outputDir, "03d05_plan_baseCandidates");
    _marker(ctx.outputDir, "03d05a_plan_textFrameStyleShellCandidates");
    var mixedBundlePlacedVisualCandidateDiagnostics =
            _appendMixedBundlePlacedVisualCandidates(sourceItems, sourceIndex, candidates, candidateSeen);
    try {
        writeJson(ctx.outputDir + "/mixed-bundle-placed-visual-candidates.json",
                mixedBundlePlacedVisualCandidateDiagnostics || {});
    } catch (eMixedBundlePlacedVisualDiagnostics) {}

    var planCache = _createExtractionPlanSourceIndexCache(doc, sourceIndex);
    _appendInlineObjectExtractionCandidates(doc, ctx, allItems, sourceItems, candidates, candidateSeen, planCache);
    _marker(ctx.outputDir, "03d06_plan_inlineCandidates");
    _appendSourceDeclaredInlineShellCandidates(ctx, sourceItems, allItems, candidates, candidateSeen, planCache);
    _marker(ctx.outputDir, "03d07_plan_declaredInlineShells");
    _marker(ctx.outputDir, "03d08_plan_textOwningShellGroups");
    var inlineFlowVisualRootDiagnostics =
            _appendInlineFlowVisualRootCandidates(candidates, sourceItems, candidateSeen, {
                fullDiagnostics: ctx.writePlannerDiagnostics === true
            });
    if (ctx.writePlannerDiagnostics === true) {
        try {
            writeJson(ctx.outputDir + "/inline-flow-visual-root-diagnostics.json",
                    inlineFlowVisualRootDiagnostics || {});
        } catch (eInlineFlowRootDiagnostics) {}
    }
    _marker(ctx.outputDir, "03d08i_plan_inlineFlowVisualRoots");

    _appendMasterCompositeExtractionCandidates(doc, ctx, candidates, candidateSeen, planCache, sourceIndex);
    _marker(ctx.outputDir, "03d09_plan_masterComposite");
    candidates = _normalizeExtractionCandidateOwnershipSlots(candidates, sourceItems);
    try {
        writeJson(ctx.outputDir + "/legacy-normalization-filters.json",
                typeof _LEGACY_NORMALIZATION_FILTER_DIAGNOSTICS !== "undefined"
                        ? _LEGACY_NORMALIZATION_FILTER_DIAGNOSTICS || {}
                        : {});
    } catch (eLegacyNormalizationFiltersWrite) {}
    if (ctx.writePlannerDiagnostics === true) {
        try {
            writeJson(ctx.outputDir + "/inline-flow-root-after-normalize.json",
                    _inlineFlowVisualRootCandidateSnapshot(candidates, "after_normalize"));
        } catch (eInlineFlowRootAfterNormalizeWrite) {}
    }
    _marker(ctx.outputDir, "03d10_plan_normalizeSlots");
    _marker(ctx.outputDir, "03d10a_plan_restoreTextFrameStyleShellCandidates");
    candidates = _includeOwnedInlineVisualsInTextlessShellCandidates(candidates, allItems, planCache, sourceItems);
    _marker(ctx.outputDir, "03d11_plan_includeInlineVisuals");
    candidates = _absorbInlineDecorationDescendantsIntoTextShellCandidates(candidates, sourceItems);
    _marker(ctx.outputDir, "03d11a_plan_absorbInlineTextShellDecorationDescendants");
    _marker(ctx.outputDir, "03d12_plan_multiTextParentGroups");
    var tableCarrierTextlessShellAppendDiagnostics =
            _appendTableCarrierTextlessShellCandidates(sourceItems, candidates, candidateSeen);
    try {
        writeJson(ctx.outputDir + "/table-carrier-textless-shell-candidates.json",
                tableCarrierTextlessShellAppendDiagnostics || {});
    } catch (eTableCarrierTextlessShellWrite) {}
    var tableCarrierTextlessShellDiagnostics = _singleTextlessPlaneEmptyDiagnostics(candidates, ctx.graphicsMode,
            "table_carrier_visual_shell_covered_by_page_plane");
    _marker(ctx.outputDir, "03d12a0_plan_tableCarrierTextlessShells");
    var tableCarrierSiblingDecorationAppendDiagnostics =
            _appendTableCarrierSiblingDecorationCandidates(sourceItems, candidates, candidateSeen);
    try {
        writeJson(ctx.outputDir + "/table-carrier-sibling-decoration-candidates.json",
                tableCarrierSiblingDecorationAppendDiagnostics || {});
    } catch (eTableCarrierSiblingDecorationWrite) {}
    var tableCarrierSiblingDecorationDiagnostics = _singleTextlessPlaneEmptyDiagnostics(candidates, ctx.graphicsMode,
            "table_carrier_sibling_decoration_covered_by_page_plane");
    _marker(ctx.outputDir, "03d12a1_plan_tableCarrierSiblingDecorations");
    var pageTextlessGraphicGroupDiagnostics = _singleTextlessPlaneEmptyDiagnostics(candidates, ctx.graphicsMode,
            "page_textless_group_candidates_replaced_by_single_page_plane_export");
    _marker(ctx.outputDir, "03d12b_plan_pageTextlessGraphicGroups");
    var pageTextlessGraphicGroupMergeDiagnostics = _singleTextlessPlaneEmptyDiagnostics(candidates, ctx.graphicsMode,
            "no_page_textless_group_candidates_to_merge");
    candidates = pageTextlessGraphicGroupMergeDiagnostics.candidates;
    _marker(ctx.outputDir, "03d12b1_plan_mergeOverlappingPageTextlessGraphicGroups");
    var inlineCarrierPageVisualDiagnostics = _singleTextlessPlaneEmptyDiagnostics(candidates, ctx.graphicsMode,
            "floating_page_visuals_covered_by_page_plane");
    candidates = inlineCarrierPageVisualDiagnostics.candidates;
    _marker(ctx.outputDir, "03d12b1a_plan_assignInlineCarrierPageVisuals");
    var protectedDecorationPageGroupExclusionDiagnostics = _singleTextlessPlaneEmptyDiagnostics(candidates, ctx.graphicsMode,
            "no_page_textless_group_candidates_to_exclude");
    candidates = protectedDecorationPageGroupExclusionDiagnostics.candidates;
    _marker(ctx.outputDir, "03d12b1b_plan_excludeProtectedDecorationFromPageGroups");
    var crossPageClipParentDecorationSuppressionDiagnostics = _singleTextlessPlaneEmptyDiagnostics(candidates, ctx.graphicsMode,
            "cross_page_clip_parent_decorations_covered_by_page_plane");
    candidates = crossPageClipParentDecorationSuppressionDiagnostics.candidates;
    _marker(ctx.outputDir, "03d12b2_plan_suppressCrossPageClipParentDecorations");
    var pageTextlessGraphicGroupSuppressionDiagnostics = _singleTextlessPlaneEmptyDiagnostics(candidates, ctx.graphicsMode,
            "no_page_textless_group_children_to_suppress");
    candidates = pageTextlessGraphicGroupSuppressionDiagnostics.candidates;
    _marker(ctx.outputDir, "03d12c_plan_suppressPageTextlessGraphicGroupChildren");
    var preObjectPlanTextlessShellSuppressionDiagnostics = _singleTextlessPlaneEmptyDiagnostics(candidates, ctx.graphicsMode,
            "page_level_textless_shell_suppression_not_needed");
    candidates = preObjectPlanTextlessShellSuppressionDiagnostics.candidates;
    _marker(ctx.outputDir, "03d12a_plan_suppressTextlessGroupChildrenBeforeObjectPlans");
    var unclaimedVisibleVectorOwnershipDiagnostics = _singleTextlessPlaneEmptyDiagnostics(candidates, ctx.graphicsMode,
            "unclaimed_floating_vectors_covered_by_page_plane");
    _marker(ctx.outputDir, "03d12d_plan_unclaimedVisibleVectorOwnership");
    var protectedDecorationPageGroupExclusionAfterUnclaimedDiagnostics = _singleTextlessPlaneEmptyDiagnostics(candidates, ctx.graphicsMode,
            "no_unclaimed_page_textless_group_candidates_to_exclude");
    candidates = protectedDecorationPageGroupExclusionAfterUnclaimedDiagnostics.candidates;
    _marker(ctx.outputDir, "03d12d1_plan_excludeProtectedDecorationFromPageGroupsAfterUnclaimed");
    var pageTextlessGraphicGroupSuppressionAfterUnclaimedDiagnostics = _singleTextlessPlaneEmptyDiagnostics(candidates, ctx.graphicsMode,
            "no_unclaimed_page_textless_group_children_to_suppress");
    candidates = pageTextlessGraphicGroupSuppressionAfterUnclaimedDiagnostics.candidates;
    _marker(ctx.outputDir, "03d12d2_plan_suppressPageTextlessGraphicGroupChildrenAfterUnclaimed");
    var sourceClusterQueryDiagnostics = _buildSourceClusterQueryDiagnostics(sourceClusterIndex, candidates);
    _marker(ctx.outputDir, "03d13_plan_clusterQueries");
    candidates = _backfillVisibleCandidateVisualSources(candidates, sourceItems);
    _marker(ctx.outputDir, "03d13a_plan_backfillVisualSources");
    candidates = _normalizePageCoordinateCandidateBounds(candidates, sourceIndex);
    _marker(ctx.outputDir, "03d13b_plan_normalizePageCoordinateBounds");
    _marker(ctx.outputDir, "03d13b1_plan_expandCrossPageFloatingVisuals");
    var preObjectPlanSourceSlotCanonicalizationDiagnostics = null;
    if (_candidateListHasObjectPlanLikeExecutionFields(candidates)) {
        preObjectPlanSourceSlotCanonicalizationDiagnostics =
                _canonicalizeSourceSlotSubsumedCandidatesWithDiagnostics(
                        candidates, sourceItems);
        candidates = preObjectPlanSourceSlotCanonicalizationDiagnostics.candidates;
    }
    _marker(ctx.outputDir, "03d13c_plan_preObjectPlanCanonicalizeSourceSlots");
    if (ctx.writePlannerDiagnostics === true) {
        try {
            writeJson(ctx.outputDir + "/inline-flow-root-before-planner-bundles.json",
                    _inlineFlowVisualRootCandidateSnapshot(candidates, "before_planner_bundles"));
        } catch (eInlineFlowRootBeforePlannerBundlesWrite) {}
    }
    var plannerBundleDiagnostics = _buildPlannerBundles(sourceItems, candidates, {
        sourceClusterDiagnostics: sourceClusterDiagnostics,
        sourceClusterIndex: sourceClusterIndex
    });
    if (ctx.writePlannerDiagnostics === true) {
        try {
            writeJson(ctx.outputDir + "/planner-bundles-diagnostics.json",
                    plannerBundleDiagnostics || {});
        } catch (ePlannerBundleDiagnosticsWrite) {}
    }
    _marker(ctx.outputDir, "03d14_plan_plannerBundles");
    writeProgress(ctx.outputDir, "object_plan_build", 0,
            plannerBundleDiagnostics && plannerBundleDiagnostics.bundles
                    ? plannerBundleDiagnostics.bundles.length : 0,
            "build object plan diagnostics");
    _marker(ctx.outputDir, "03d14a_plan_objectPlans_start");
    var objectPlanDiagnostics = _buildObjectPlanDiagnosticsFromPlannerBundles(
            plannerBundleDiagnostics, sourceItems, { outputDir: ctx.outputDir });
    writeProgress(ctx.outputDir, "object_plan_build",
            plannerBundleDiagnostics && plannerBundleDiagnostics.bundles
                    ? plannerBundleDiagnostics.bundles.length : 0,
            plannerBundleDiagnostics && plannerBundleDiagnostics.bundles
                    ? plannerBundleDiagnostics.bundles.length : 0,
            "object plan diagnostics ready");
    _marker(ctx.outputDir, "03d14b_plan_objectPlans_built");
    var canonicalPagePlaneObjectPlanDiagnostics =
            _appendCanonicalPagePlaneObjectPlans(doc, sourceItems, objectPlanDiagnostics);
    var canonicalPagePlaneAbsorptionDiagnostics =
            _canonicalPagePlaneAbsorbExistingObjectPlans(objectPlanDiagnostics);
    _marker(ctx.outputDir, "03d15_plan_objectPlans");
    if (ctx.writePlannerDiagnostics === true) {
        try {
            writeJson(ctx.outputDir + "/object-plans-before-execution-bridge.json",
                    _slimObjectPlanDiagnosticsForWrite(objectPlanDiagnostics));
        } catch (eObjectPlansBeforeExecutionBridgeWrite) {}
    }
    var executionCandidates = _buildExecutionCandidatesFromObjectPlans(candidates, objectPlanDiagnostics);
    _marker(ctx.outputDir, "03d16_plan_buildExecutionCandidates");
    if (ctx.writePlannerDiagnostics === true) {
        try {
            writeJson(ctx.outputDir + "/object-plan-execution-bridge-before-source-slot-canonicalization.json",
                    objectPlanDiagnostics.executionCandidateBridge || {});
        } catch (eObjectPlanExecutionBridgeBeforeSourceSlotCanonicalizationWrite) {}
    }
    executionCandidates = _excludeDirectChildShellSourcesFromParentShellExports(executionCandidates, sourceItems);
    _marker(ctx.outputDir, "03d16a_plan_excludeChildShellSourcesFromParentExports");
    if (ctx.writePlannerDiagnostics === true) {
        try {
            writeJson(ctx.outputDir + "/execution-candidates-before-source-slot-canonicalization.json",
                    executionCandidates || []);
        } catch (eExecutionCandidatesBeforeSourceSlotCanonicalizationWrite) {}
    }
    var sourceSlotCanonicalizationDiagnostics = _canonicalizeSourceSlotSubsumedCandidatesWithDiagnostics(
            executionCandidates, sourceItems);
    executionCandidates = sourceSlotCanonicalizationDiagnostics.candidates;
    if (preObjectPlanSourceSlotCanonicalizationDiagnostics) {
        try {
            sourceSlotCanonicalizationDiagnostics.preObjectPlanDiagnostics =
                    preObjectPlanSourceSlotCanonicalizationDiagnostics.diagnostics;
        } catch (ePreObjectPlanCanonicalizationAttach) {}
    }
    if (ctx.writePlannerDiagnostics === true) {
        try {
            writeJson(ctx.outputDir + "/source-slot-canonicalization-diagnostics.json",
                    sourceSlotCanonicalizationDiagnostics.diagnostics || {});
        } catch (eSourceSlotCanonicalizationDiagnostics) {}
    }
    _marker(ctx.outputDir, "03d16b_plan_canonicalizeSourceSlots");
    var multiTextParentSuppressionDiagnostics = _suppressChildExportsCoveredByTextlessGroupCandidates(
            executionCandidates, sourceItems);
    executionCandidates = multiTextParentSuppressionDiagnostics.candidates;
    _marker(ctx.outputDir, "03d16b2_plan_suppressTextlessGroupChildren");
    var pageTextlessExecutionSuppressionDiagnostics =
            _suppressChildExportsCoveredByPageTextlessGraphicGroups(executionCandidates, sourceItems);
    executionCandidates = pageTextlessExecutionSuppressionDiagnostics.candidates;
    _marker(ctx.outputDir, "03d16b3_plan_suppressPageTextlessGraphicGroupChildren");
    var compositeShellExecutionSuppressionDiagnostics =
            _suppressChildShellSlotsCoveredByCompositeShellCandidates(executionCandidates, sourceItems);
    executionCandidates = compositeShellExecutionSuppressionDiagnostics.candidates;
    _marker(ctx.outputDir, "03d16b4_plan_suppressCompositeShellSlotChildren");
    if ((sourceSlotCanonicalizationDiagnostics.diagnostics
                    && sourceSlotCanonicalizationDiagnostics.diagnostics.suppressedCount > 0)
            || multiTextParentSuppressionDiagnostics.suppressedCount > 0
            || pageTextlessExecutionSuppressionDiagnostics.suppressedCount > 0
            || compositeShellExecutionSuppressionDiagnostics.suppressedCount > 0) {
        _marker(ctx.outputDir, "03d16c_plan_skipPlannerBundleRebuildAfterSubsumed");
        if (ctx.writePlannerDiagnostics === true) {
            try {
                writeJson(ctx.outputDir + "/execution-candidates-after-subsumed-before-secondary-suppressions.json",
                        executionCandidates || []);
            } catch (eExecutionCandidatesAfterSubsumedBeforeSecondarySuppressionsWrite) {}
        }
        var secondaryMultiTextParentSuppressionDiagnostics = _suppressChildExportsCoveredByTextlessGroupCandidates(
                executionCandidates, sourceItems);
        executionCandidates = secondaryMultiTextParentSuppressionDiagnostics.candidates;
        multiTextParentSuppressionDiagnostics = {
            candidates: executionCandidates,
            suppressedCount: (multiTextParentSuppressionDiagnostics.suppressedCount || 0)
                    + (secondaryMultiTextParentSuppressionDiagnostics.suppressedCount || 0),
            suppressed: (multiTextParentSuppressionDiagnostics.suppressed || []).concat(
                    secondaryMultiTextParentSuppressionDiagnostics.suppressed || [])
        };
        _marker(ctx.outputDir, "03d16g_plan_suppressTextlessGroupChildrenAfterSubsumed");
        var secondaryPageTextlessExecutionSuppressionDiagnostics =
                _suppressChildExportsCoveredByPageTextlessGraphicGroups(executionCandidates, sourceItems);
        executionCandidates = secondaryPageTextlessExecutionSuppressionDiagnostics.candidates;
        pageTextlessExecutionSuppressionDiagnostics = {
            candidates: executionCandidates,
            suppressedCount: (pageTextlessExecutionSuppressionDiagnostics.suppressedCount || 0)
                    + (secondaryPageTextlessExecutionSuppressionDiagnostics.suppressedCount || 0),
            suppressed: (pageTextlessExecutionSuppressionDiagnostics.suppressed || []).concat(
                    secondaryPageTextlessExecutionSuppressionDiagnostics.suppressed || [])
        };
        _marker(ctx.outputDir, "03d16g0_plan_suppressPageTextlessGraphicGroupChildrenAfterSubsumed");
        var secondaryCompositeShellExecutionSuppressionDiagnostics =
                _suppressChildShellSlotsCoveredByCompositeShellCandidates(executionCandidates, sourceItems);
        executionCandidates = secondaryCompositeShellExecutionSuppressionDiagnostics.candidates;
        compositeShellExecutionSuppressionDiagnostics = {
            candidates: executionCandidates,
            suppressedCount: (compositeShellExecutionSuppressionDiagnostics.suppressedCount || 0)
                    + (secondaryCompositeShellExecutionSuppressionDiagnostics.suppressedCount || 0),
            suppressed: (compositeShellExecutionSuppressionDiagnostics.suppressed || []).concat(
                    secondaryCompositeShellExecutionSuppressionDiagnostics.suppressed || [])
        };
        _marker(ctx.outputDir, "03d16g0a_plan_suppressCompositeShellSlotChildrenAfterSubsumed");
        var plannerBundleExecutionSyncDiagnostics =
                _syncPlannerBundleDiagnosticsToExecutionCandidates(
                        plannerBundleDiagnostics, executionCandidates, {
                            objectPlanDiagnostics: objectPlanDiagnostics,
                            reason: "post_object_plan_subsumed_execution_suppression"
                        });
        _marker(ctx.outputDir, "03d16g0a0_plan_syncPlannerBundlesAfterSubsumed");
        var objectPlanExecutionSyncDiagnostics =
                _syncObjectPlanDiagnosticsToExecutionCandidates(
                        objectPlanDiagnostics, executionCandidates, {
                            reason: "post_object_plan_subsumed_execution_suppression"
                        });
        _marker(ctx.outputDir, "03d16g0a1_plan_syncObjectPlansAfterSubsumed");
        if (ctx.writePlannerDiagnostics === true) {
            try {
                writeJson(ctx.outputDir + "/planner-bundle-execution-sync-diagnostics.json",
                        plannerBundleExecutionSyncDiagnostics || {});
            } catch (ePlannerBundleExecutionSyncDiagnosticsWrite) {}
            try {
                writeJson(ctx.outputDir + "/object-plan-execution-sync-diagnostics.json",
                        objectPlanExecutionSyncDiagnostics || {});
            } catch (eObjectPlanExecutionSyncDiagnosticsWrite) {}
        }
    }
    executionCandidates = _backfillVisibleCandidateVisualSources(executionCandidates, sourceItems);
    _marker(ctx.outputDir, "03d16g0b_plan_backfillExecutionVisualSources");
    var unclaimedVisibleVectorFallbackDiagnostics = _singleTextlessPlaneEmptyDiagnostics(executionCandidates, ctx.graphicsMode,
            "unclaimed_visible_vector_execution_fallback_replaced_by_page_plane");
    _marker(ctx.outputDir, "03d16g1_plan_warnUnclaimedVisibleVectors");
    var sourceCoverageOptions = {
        fullDiagnostics: ctx.writePlannerDiagnostics === true
    };
    var sourceCoverageDiagnostics = _buildSourceCoverageDiagnostics(
            sourceItems, executionCandidates, objectPlanDiagnostics, sourceCoverageOptions);
    var unresolvedVisibleVectorCoverageDiagnostics = _singleTextlessPlaneEmptyDiagnostics(executionCandidates, ctx.graphicsMode,
            "page_floating_graphics_are_validated_through_single_textless_page_plane");
    _marker(ctx.outputDir, "03d16g6_plan_warnUnresolvedVisibleVectors");
    _marker(ctx.outputDir, "03d16h0_plan_sourceCoverageBuild");
    try { writeJson(ctx.outputDir + "/source-coverage.json", sourceCoverageDiagnostics); } catch (eSourceCoverageWrite) {}
    _marker(ctx.outputDir, "03d16h_plan_sourceCoverage");
    var sourceOwnershipModelDiagnostics = _buildSourceOwnershipModelDiagnostics(
            sourceItems, executionCandidates, objectPlanDiagnostics, {
                fullDiagnostics: ctx.writePlannerDiagnostics === true
            });
    _marker(ctx.outputDir, "03d16i0_plan_sourceOwnershipModelBuild");
    if (ctx.writePlannerDiagnostics === true) {
        try { writeJson(ctx.outputDir + "/source-bundles.json", sourceOwnershipModelDiagnostics.sourceBundles); } catch (eSourceBundlesWrite) {}
        _marker(ctx.outputDir, "03d16i1_plan_writeSourceBundles");
        try { writeJson(ctx.outputDir + "/ownership-slots.json", sourceOwnershipModelDiagnostics.ownershipSlots); } catch (eOwnershipSlotsWrite) {}
        _marker(ctx.outputDir, "03d16i2_plan_writeOwnershipSlots");
        try { writeJson(ctx.outputDir + "/slot-owners.json", sourceOwnershipModelDiagnostics.slotOwners); } catch (eSlotOwnersWrite) {}
        _marker(ctx.outputDir, "03d16i3_plan_writeSlotOwners");
    } else {
        try {
            writeJson(ctx.outputDir + "/source-ownership-model-summary.json", {
                schemaVersion: 1,
                policy: "POLICY-source-ownership",
                mode: "source-ownership-model-summary",
                sourceBundles: sourceOwnershipModelDiagnostics.sourceBundles.summary,
                ownershipSlots: sourceOwnershipModelDiagnostics.ownershipSlots.summary,
                slotOwners: sourceOwnershipModelDiagnostics.slotOwners.summary,
                renderUnits: sourceOwnershipModelDiagnostics.renderUnits.summary,
                summary: sourceOwnershipModelDiagnostics.summary,
                fullDiagnosticsSkipped: true
            });
        } catch (eSourceOwnershipSummaryWrite) {}
        _marker(ctx.outputDir, "03d16i1_plan_writeSourceOwnershipSummary");
    }
    try {
        writeJson(ctx.outputDir + "/render-units.json",
                ctx.writePlannerDiagnostics === true
                        ? sourceOwnershipModelDiagnostics.renderUnits
                        : _slimRenderUnitsForWrite(sourceOwnershipModelDiagnostics.renderUnits));
    } catch (eRenderUnitsWrite) {}
    _marker(ctx.outputDir, "03d16i4_plan_writeRenderUnits");
    _marker(ctx.outputDir, "03d16i_plan_sourceOwnershipModel");
    _attachRenderUnitsToExecutionCandidates(executionCandidates, sourceOwnershipModelDiagnostics);
    _marker(ctx.outputDir, "03d16j_plan_attachRenderUnits");
    var sourceSlotRegistryDiagnostics = _buildSourceSlotRegistryDiagnostics(
            plannerBundleDiagnostics, objectPlanDiagnostics, sourceItems);
    _marker(ctx.outputDir, "03d17_plan_sourceSlotRegistry");
    var executionCandidateContractDiagnostics =
            _buildExecutionCandidateContractDiagnostics(executionCandidates);
    _marker(ctx.outputDir, "03d18_plan_executionCandidateContract");

    var renderDpi = CONFIG && CONFIG.rendering ? CONFIG.rendering.pngExportResolution : null;
    var plan = {
        schemaVersion: 1,
        policy: "POLICY-extraction-planning",
        scriptVersion: EXTRACT_SCRIPT_VERSION,
        mode: "canonical-single-textless-page-plane-plan",
        sourceDocument: ctx.inddPath,
        outputDir: ctx.outputDir,
        pageRange: {
            requestedStartPage: ctx.requestedStartPage,
            requestedEndPage: ctx.requestedEndPage,
            startPage: ctx.startPage,
            endPage: ctx.endPage,
            pageCount: ctx.pageCount,
            rangePageCount: ctx.rangePageCount,
            rangeInputMode: ctx.rangeInputMode,
            resolvedRangeMode: ctx.resolvedRangeMode,
            physicalRange: !!ctx.physicalRange,
            chunkMode: !!ctx.chunkMode
        },
        renderOptions: {
            perfMode: ctx.perfMode,
            pngExportResolution: renderDpi,
            spreadMode: !!ctx.spreadMode,
            skipPdf: !!ctx.skipPdf
        },
        sourceItems: sourceItems,
        sourceClusterSummary: sourceClusterDiagnostics.summary,
        sourceClusterQuerySummary: sourceClusterQueryDiagnostics.summary,
        plannerBundleSummary: plannerBundleDiagnostics.summary,
        objectPlanSummary: objectPlanDiagnostics.summary,
        pagePlaneObjectPlanSummary: canonicalPagePlaneObjectPlanDiagnostics,
        canonicalPagePlaneAbsorptionSummary: canonicalPagePlaneAbsorptionDiagnostics,
        sourceCoverageSummary: sourceCoverageDiagnostics.summary,
        sourceOwnershipModelSummary: sourceOwnershipModelDiagnostics.summary,
        renderUnits: sourceOwnershipModelDiagnostics.renderUnits
                ? sourceOwnershipModelDiagnostics.renderUnits.renderUnits || []
                : [],
        preObjectPlanTextlessShellSuppressionSummary: {
            suppressedCount: preObjectPlanTextlessShellSuppressionDiagnostics.suppressedCount,
            suppressed: preObjectPlanTextlessShellSuppressionDiagnostics.suppressed
        },
        crossPageClipParentDecorationSuppressionSummary: {
            suppressedCount: crossPageClipParentDecorationSuppressionDiagnostics.suppressedCount,
            suppressed: crossPageClipParentDecorationSuppressionDiagnostics.suppressed
        },
        pageTextlessGraphicGroupSummary: {
            appendedCount: pageTextlessGraphicGroupDiagnostics.appendedCount,
            componentCount: pageTextlessGraphicGroupDiagnostics.componentCount,
            components: pageTextlessGraphicGroupDiagnostics.components,
            mergedCount: pageTextlessGraphicGroupMergeDiagnostics.mergedCount,
            merged: pageTextlessGraphicGroupMergeDiagnostics.merged,
            inlineCarrierAssignedCount: inlineCarrierPageVisualDiagnostics.assignedCount,
            inlineCarrierAssigned: inlineCarrierPageVisualDiagnostics.assigned,
            preObjectPlanSuppressedCount: pageTextlessGraphicGroupSuppressionDiagnostics.suppressedCount,
            preObjectPlanSuppressed: pageTextlessGraphicGroupSuppressionDiagnostics.suppressed,
            executionSuppressedCount: pageTextlessExecutionSuppressionDiagnostics.suppressedCount,
            executionSuppressed: pageTextlessExecutionSuppressionDiagnostics.suppressed,
            compositeShellExecutionSuppressedCount:
                    compositeShellExecutionSuppressionDiagnostics.suppressedCount,
            compositeShellExecutionSuppressed:
                    compositeShellExecutionSuppressionDiagnostics.suppressed
        },
        visibleVectorOwnershipWarningSummary: {
            unclaimedWarningCount: unclaimedVisibleVectorFallbackDiagnostics.warningCount || 0,
            unclaimedWarnings: unclaimedVisibleVectorFallbackDiagnostics.warnings || [],
            unclaimedOwnershipAppendCount: unclaimedVisibleVectorOwnershipDiagnostics.appendedCount || 0,
            unclaimedOwnershipAppended: unclaimedVisibleVectorOwnershipDiagnostics.appended || [],
            unresolvedWarningCount: unresolvedVisibleVectorCoverageDiagnostics.warningCount || 0,
            unresolvedWarnings: unresolvedVisibleVectorCoverageDiagnostics.warnings || []
        },
        sourceSlotCanonicalizationSummary: sourceSlotCanonicalizationDiagnostics.diagnostics.summary,
        executionCandidateContractSummary: executionCandidateContractDiagnostics.summary,
        legacyNormalizationFilterSummary: typeof _LEGACY_NORMALIZATION_FILTER_DIAGNOSTICS !== "undefined"
                && _LEGACY_NORMALIZATION_FILTER_DIAGNOSTICS
                ? _LEGACY_NORMALIZATION_FILTER_DIAGNOSTICS.summary
                : null,
        exactShellSlotDuplicateSummary: typeof _EXACT_SHELL_SLOT_DUPLICATE_DIAGNOSTICS !== "undefined"
                && _EXACT_SHELL_SLOT_DUPLICATE_DIAGNOSTICS
                ? _EXACT_SHELL_SLOT_DUPLICATE_DIAGNOSTICS.summary
                : null,
        sourceSlotRegistrySummary: sourceSlotRegistryDiagnostics.summary,
        candidates: executionCandidates,
        exportPasses: _buildExtractionPasses()
    };
    ctx._extractionPlanDiagnostics = {
        sourceClusterDiagnostics: sourceClusterDiagnostics,
        sourceClusterQueryDiagnostics: sourceClusterQueryDiagnostics,
        plannerBundleDiagnostics: plannerBundleDiagnostics,
        objectPlanDiagnostics: objectPlanDiagnostics,
        pagePlaneObjectPlanDiagnostics: canonicalPagePlaneObjectPlanDiagnostics,
        sourceCoverageDiagnostics: sourceCoverageDiagnostics,
        sourceOwnershipModelDiagnostics: sourceOwnershipModelDiagnostics,
        preObjectPlanTextlessShellSuppressionDiagnostics:
                preObjectPlanTextlessShellSuppressionDiagnostics,
        pageTextlessGraphicGroupDiagnostics: pageTextlessGraphicGroupDiagnostics,
        inlineCarrierPageVisualDiagnostics: inlineCarrierPageVisualDiagnostics,
        pageTextlessGraphicGroupSuppressionDiagnostics:
                pageTextlessGraphicGroupSuppressionDiagnostics,
        pageTextlessExecutionSuppressionDiagnostics:
                pageTextlessExecutionSuppressionDiagnostics,
        compositeShellExecutionSuppressionDiagnostics:
                compositeShellExecutionSuppressionDiagnostics,
        visibleVectorOwnershipWarningDiagnostics: {
            unclaimed: unclaimedVisibleVectorFallbackDiagnostics,
            unclaimedOwnership: unclaimedVisibleVectorOwnershipDiagnostics,
            unresolved: unresolvedVisibleVectorCoverageDiagnostics
        },
        sourceSlotCanonicalizationDiagnostics: sourceSlotCanonicalizationDiagnostics.diagnostics,
        executionCandidateContractDiagnostics: executionCandidateContractDiagnostics,
        legacyNormalizationFilterDiagnostics:
                typeof _LEGACY_NORMALIZATION_FILTER_DIAGNOSTICS !== "undefined"
                        ? _LEGACY_NORMALIZATION_FILTER_DIAGNOSTICS
                        : null,
        exactShellSlotDuplicateDiagnostics:
                typeof _EXACT_SHELL_SLOT_DUPLICATE_DIAGNOSTICS !== "undefined"
                        ? _EXACT_SHELL_SLOT_DUPLICATE_DIAGNOSTICS
                        : null,
        sourceSlotRegistryDiagnostics: sourceSlotRegistryDiagnostics
    };
    return plan;
}

function _attachRenderUnitsToExecutionCandidates(executionCandidates, sourceOwnershipModelDiagnostics) {
    if (!executionCandidates || !sourceOwnershipModelDiagnostics
            || !sourceOwnershipModelDiagnostics.renderUnits) {
        return;
    }
    var renderUnits = sourceOwnershipModelDiagnostics.renderUnits.renderUnits || [];
    var byCandidateId = {};
    var bySlotIdentityKey = {};
    for (var ri = 0; ri < renderUnits.length; ri++) {
        var unit = renderUnits[ri];
        if (!unit) continue;
        if (unit.candidateId) byCandidateId[String(unit.candidateId)] = unit;
        if (unit.slotIdentityKey) bySlotIdentityKey[String(unit.slotIdentityKey)] = unit;
    }

    function candidateOwnershipSlot(candidate) {
        if (!candidate) return "CONTENT_VISUAL_SLOT";
        if (candidate.ownershipSlot) {
            if (candidate.ownershipSlot === "TEXTLESS_GROUP_VISUAL_SLOT") return "SHELL_SLOT";
            return candidate.ownershipSlot;
        }
        if (candidate.visualAction === "PLACE_TEXT_SHELL") return "SHELL_SLOT";
        if (candidate.visualAction === "PLACE_TABLE_STYLE") return "TABLE_STYLE_SLOT";
        if (candidate.passId === "pass.decoration_groups"
                || candidate.passId === "pass.editable_textframe_visual_shells"
                || candidate.slotRole === "shell_slot_only"
                || candidate.slotRole === "textless_group_visual_slot") {
            return "SHELL_SLOT";
        }
        return "CONTENT_VISUAL_SLOT";
    }

    function candidatePlacement(candidate) {
        if (candidate && candidate.placement) return candidate.placement;
        if (candidate && candidate.passId === "pass.inline_objects") return "INLINE";
        return "FLOATING";
    }

    function candidateSlotIdentityKey(candidate) {
        if (!candidate) return null;
        var ids = candidate.visualSourceObjectIds && candidate.visualSourceObjectIds.length > 0
                ? candidate.visualSourceObjectIds
                : (candidate.exportSourceObjectIds && candidate.exportSourceObjectIds.length > 0
                        ? candidate.exportSourceObjectIds
                        : candidate.sourceObjectIds || []);
        var sourceKey = _sourceSetKey(ids || []);
        if (!sourceKey) return null;
        return String(candidate.pageIndex) + "|"
                + candidatePlacement(candidate) + "|"
                + candidateOwnershipSlot(candidate) + "|"
                + sourceKey;
    }

    for (var ci = 0; ci < executionCandidates.length; ci++) {
        var candidate = executionCandidates[ci];
        if (!candidate || !candidate.candidateId) continue;
        var renderUnit = byCandidateId[String(candidate.candidateId)]
                || bySlotIdentityKey[String(candidateSlotIdentityKey(candidate))];
        if (!renderUnit) continue;
        candidate.renderUnitId = renderUnit.renderUnitId || null;
        candidate.renderUnitSlotIdentityKey = renderUnit.slotIdentityKey || null;
    }
}

function _excludeDirectChildShellSourcesFromParentShellExports(candidates, sourceItems) {
    if (!candidates || candidates.length === 0) return candidates || [];
    var indexes = null;
    try { indexes = _buildSourceItemIndexes(sourceItems || []); } catch (eIndex) { indexes = null; }
    var sourceInfoById = indexes ? indexes.sourceInfoById || {} : {};

    function info(id) {
        return sourceInfoById ? sourceInfoById[String(id)] : null;
    }

    function parentIdOf(id) {
        var src = info(id);
        if (!src) return null;
        return src.parentId !== undefined && src.parentId !== null ? src.parentId : null;
    }

    function isDescendantOf(childId, parentId) {
        if (childId === null || childId === undefined || parentId === null || parentId === undefined) return false;
        var cur = parentIdOf(childId);
        var guard = 0;
        while (cur !== null && cur !== undefined && guard++ < 100) {
            if (String(cur) === String(parentId)) return true;
            cur = parentIdOf(cur);
        }
        return false;
    }

    function ids(candidate, field) {
        return _sortedNumericIds(candidate && candidate[field] || []);
    }

    function visibleExportIds(candidate) {
        var exportIds = ids(candidate, "exportSourceObjectIds");
        if (exportIds.length > 0) return exportIds;
        var visualIds = ids(candidate, "visualSourceObjectIds");
        if (visualIds.length > 0) return visualIds;
        return ids(candidate, "sourceObjectIds");
    }

    function sourceIds(candidate) {
        return ids(candidate, "sourceObjectIds");
    }

    function primaryId(candidate) {
        if (!candidate) return null;
        if (candidate.primarySourceObjectId !== undefined && candidate.primarySourceObjectId !== null) {
            return candidate.primarySourceObjectId;
        }
        var srcIds = sourceIds(candidate);
        return srcIds.length > 0 ? srcIds[0] : null;
    }

    function idSet(values) {
        var set = {};
        for (var i = 0; values && i < values.length; i++) set[String(values[i])] = true;
        return set;
    }

    function containsAll(ownerIds, childIds) {
        if (!childIds || childIds.length === 0) return false;
        var set = idSet(ownerIds || []);
        for (var i = 0; i < childIds.length; i++) {
            if (!set[String(childIds[i])]) return false;
        }
        return true;
    }

    function isParentShell(candidate) {
        return candidate
                && candidate.passId === "pass.decoration_groups"
                && candidate.candidatePurpose === "SHELL_CANDIDATE"
                && candidate.slotRole === "shell_slot_only"
                && candidate.textOwner === "hwpx_tf";
    }

    function isDirectChildShell(candidate) {
        return candidate
                && candidate.passId === "pass.decoration_groups"
                && candidate.candidatePurpose === "SHELL_CANDIDATE"
                && (candidate.slotRole === "direct_child_shell_slot"
                    || candidate.compositeRole === "direct_child_shell_slot");
    }

    function copyCandidate(candidate) {
        var copy = {};
        for (var key in candidate) {
            if (!candidate.hasOwnProperty || !candidate.hasOwnProperty(key)) continue;
            var value = candidate[key];
            copy[key] = value && value.constructor === Array ? value.slice(0) : value;
        }
        return copy;
    }

    var childShellsByPage = {};
    for (var ci = 0; ci < candidates.length; ci++) {
        var child = candidates[ci];
        if (!isDirectChildShell(child)) continue;
        var pageKey = String(child.pageIndex);
        if (!childShellsByPage[pageKey]) childShellsByPage[pageKey] = [];
        childShellsByPage[pageKey].push({
            candidate: child,
            primaryId: primaryId(child),
            exportIds: visibleExportIds(child)
        });
    }

    var out = [];
    for (var pi = 0; pi < candidates.length; pi++) {
        var parent = candidates[pi];
        if (!isParentShell(parent)) {
            out.push(parent);
            continue;
        }
        var parentPrimaryId = primaryId(parent);
        var parentExportIds = visibleExportIds(parent);
        var childShells = childShellsByPage[String(parent.pageIndex)] || [];
        var removeIds = [];
        var removeSeen = {};
        for (var si = 0; si < childShells.length; si++) {
            var childEntry = childShells[si];
            if (String(childEntry.primaryId) === String(parentPrimaryId)) continue;
            if (!isDescendantOf(childEntry.primaryId, parentPrimaryId)) continue;
            if (!containsAll(parentExportIds, childEntry.exportIds)) continue;
            for (var ri = 0; ri < childEntry.exportIds.length; ri++) {
                _pushUniqueId(removeIds, removeSeen, childEntry.exportIds[ri]);
            }
        }
        if (removeIds.length === 0) {
            out.push(parent);
            continue;
        }

        var pruned = copyCandidate(parent);
        var nextExportIds = _removeSourceIds(pruned.exportSourceObjectIds || [], removeIds);
        if ((pruned.exportSourceObjectIds || []).length > 0 && nextExportIds.length === 0) {
            out.push(parent);
            continue;
        }
        pruned.exportSourceObjectIds = nextExportIds;
        pruned.visualSourceObjectIds = _removeSourceIds(pruned.visualSourceObjectIds || [], removeIds);
        pruned.styleSourceObjectIds = _removeSourceIds(pruned.styleSourceObjectIds || [], removeIds);
        pruned.reason = "parent_shell_export_excludes_child_shell_sources";
        out.push(pruned);
    }
    return out;
}
