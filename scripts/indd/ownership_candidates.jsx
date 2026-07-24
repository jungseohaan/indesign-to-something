/*
 * Extraction candidate helpers for extract_indd.jsx.
 *
 * This module may construct candidate records and candidate metadata.
 * It must not perform post-hoc ownership correction, visual dropping, or
 * placement/layer reinterpretation.
 */

function _candidatePageInRange(pageIndex, ctx) {
    if (pageIndex === null || pageIndex === undefined || pageIndex < 0) return false;
    var oneBased = pageIndex + 1;
    return oneBased >= ctx.startPage && oneBased <= ctx.endPage;
}

function _isBackgroundLayerName(layerName) {
    if (!layerName) return false;
    var lower = String(layerName).toLowerCase();
    return lower.indexOf("\uBC30\uACBD") >= 0
            || lower.indexOf("\uBC14\uD0D5") >= 0
            || lower.indexOf("background") >= 0
            || lower === "bg"
            || lower.indexOf("backdrop") >= 0;
}

function _diagnosticItemZOrder(item, fallback) {
    try {
        if (item && item.index !== undefined && item.index !== null) {
            var indexValue = Number(item.index);
            if (!isNaN(indexValue)) return indexValue;
        }
    } catch (eIndex) {}
    return fallback !== undefined ? fallback : null;
}

function _isSimpleMarkerLabelTextForOwnership(text) {
    text = String(text || "").replace(/[\s\r\n\t\u0016\u0018\u0003\uFFFC]/g, "");
    if (!text) return false;
    if (/^(가|나|다|라|마|바|ㄱ|ㄴ|ㄷ|ㄹ|ㅁ|ㅂ|ㅅ|ㅇ|ㅈ|ㅊ|ㅋ|ㅌ|ㅍ|ㅎ)$/.test(text)) return true;
    if (/^[0-9]{1,2}$/.test(text)) return true;
    if (text.length === 1) {
        var code = text.charCodeAt(0);
        if (code >= 0x2460 && code <= 0x2473) return true;
    }
    return false;
}

// SPEC-048: 게이지형 벡터 그래픽 판정.
// storyTextInlineSlot=true + FLOATING_ANCHORED 인 Group 이 GraphicLine(눈금선·화살표)
// 다수를 품으면 게이지다. 라벨(배경도형+텍스트)은 GraphicLine 0개라 구분된다.
// 게이지는 편집 텍스트("100%")를 분리하지 않고 통짜 PNG + 페이지 좌표 floating 배치한다.
// (eval 모듈 로드에서 파일 간 전역 var 는 공유되지 않아 함수로 정의)
function _gaugeLikeGraphicLineMin() { return 8; }

function _gaugeGraphicLineDescendantCountFromMaps(sourceId, sourceInfoById, childIdsByParentId) {
    var count = 0;
    var stack = [sourceId];
    var seen = {};
    while (stack.length > 0) {
        var id = stack.pop();
        var key = String(id);
        if (seen[key]) continue;
        seen[key] = true;
        var children = (childIdsByParentId && childIdsByParentId[key]) || [];
        for (var i = 0; i < children.length; i++) {
            var child = sourceInfoById ? sourceInfoById[String(children[i])] : null;
            if (child && String(child.kind || "") === "GraphicLine") count++;
            stack.push(children[i]);
        }
    }
    return count;
}

function _isGaugeLikeFloatingAnchoredInlineGroupByMaps(sourceInfo, sourceInfoById, childIdsByParentId) {
    if (!sourceInfo || String(sourceInfo.kind || "") !== "Group") return false;
    if (sourceInfo.storyTextInlineSlot !== true) return false;
    if (String(sourceInfo.storyAnchorPlacement || "").toUpperCase() !== "FLOATING_ANCHORED") return false;
    return _gaugeGraphicLineDescendantCountFromMaps(sourceInfo.id, sourceInfoById, childIdsByParentId)
            >= _gaugeLikeGraphicLineMin();
}

function _candidateId(passId, sourceId, pageIndex) {
    var safePass = String(passId || "pass.unknown").replace(/[^A-Za-z0-9_.-]/g, "_");
    if (sourceId !== null && sourceId !== undefined) return "cand." + safePass + ".page." + pageIndex + ".src." + sourceId;
    return "cand." + safePass + ".page." + pageIndex;
}

function _candidateCompositeId(passId, pageIndex, sourceIds, suffix) {
    var safePass = String(passId || "pass.unknown").replace(/[^A-Za-z0-9_.-]/g, "_");
    var ids = sourceIds || [];
    var first = ids.length > 0 ? ids[0] : "none";
    var last = ids.length > 0 ? ids[ids.length - 1] : "none";
    var hash = _sourceSetStableHash(ids);
    var extra = suffix ? "." + String(suffix).replace(/[^A-Za-z0-9_.-]/g, "_") : "";
    return "cand." + safePass + ".composite.page." + pageIndex
            + ".src." + first + "." + last + ".n" + ids.length + ".h" + hash + extra;
}

function _sourceSetStableHash(sourceIds) {
    var ids = sourceIds || [];
    var h = 17;
    for (var i = 0; i < ids.length; i++) {
        var n = Number(ids[i] || 0);
        h = (h * 131 + n) % 1000000007;
    }
    return h;
}

function _pushExtractionCandidate(candidates, seen, passId, item, attrs) {
    attrs = attrs || {};
    var id = attrs.sourceId !== undefined ? attrs.sourceId : _itemId(item);
    var sourceObjectIds = attrs.sourceObjectIds || (id !== null && id !== undefined ? [id] : []);
    var exportSourceObjectIds = attrs.exportSourceObjectIds || [];
    var pageIndex = attrs.pageIndex !== undefined ? attrs.pageIndex : _pageIndexOfItem(null, item);
    var key = passId + "|page:" + pageIndex + "|" + (sourceObjectIds.length > 0 ? ("src:" + _sourceSetKey(sourceObjectIds)) : "page-only");
    if (seen[key]) return;
    seen[key] = true;
    var composite = attrs.composite === true || sourceObjectIds.length > 1;
    candidates.push({
        candidateId: composite
                ? _candidateCompositeId(passId, pageIndex, sourceObjectIds, attrs.suffix)
                : _candidateId(passId, id, pageIndex),
        passId: passId,
        sourceObjectIds: sourceObjectIds,
        primarySourceObjectId: attrs.primarySourceObjectId !== undefined
                ? attrs.primarySourceObjectId
                : (sourceObjectIds.length > 0 ? sourceObjectIds[0] : null),
        pageIndex: pageIndex,
        kind: attrs.kind || _itemKind(item),
        unit: attrs.unit || null,
        mode: attrs.mode || null,
        candidatePurpose: attrs.candidatePurpose || null,
        bounds: attrs.bounds || _itemBounds(item),
        sourceBounds: attrs.sourceBounds || null,
        renderSourceBounds: attrs.renderSourceBounds || null,
        cropSourceBounds: attrs.cropSourceBounds || null,
        parentId: attrs.parentId !== undefined ? attrs.parentId : _itemParentId(item),
        parentKind: attrs.parentKind || _itemParentKind(item),
        anchoredPosition: attrs.anchoredPosition !== undefined ? attrs.anchoredPosition : _itemAnchoredPosition(item),
        storyAnchorPlacement: attrs.storyAnchorPlacement !== undefined
                ? attrs.storyAnchorPlacement
                : _storyAnchorPlacementForItem(null, item, null),
        composite: composite,
        compositeRole: attrs.compositeRole || null,
        slotRole: attrs.slotRole || null,
        exportSourceObjectIds: exportSourceObjectIds,
        exportTargetObjectId: attrs.exportTargetObjectId !== undefined ? attrs.exportTargetObjectId : null,
        hiddenVisualSourceObjectIds: attrs.hiddenVisualSourceObjectIds || [],
        visualSourceObjectIds: attrs.visualSourceObjectIds || [],
        styleSourceObjectIds: attrs.styleSourceObjectIds || [],
        editableTextFrameIds: attrs.editableTextFrameIds || [],
        hiddenTextFrameIds: attrs.hiddenTextFrameIds || [],
        requiresTextHidden: attrs.requiresTextHidden === true,
        textOwner: attrs.textOwner || null,
        containsEditableText: attrs.containsEditableText === true,
        completePngTextAllowed: attrs.completePngTextAllowed === true,
        materialization: attrs.materialization || null,
        textAction: attrs.textAction || null,
        visualAction: attrs.visualAction || null,
        visualLayer: attrs.visualLayer || null,
        placement: attrs.placement || null,
        coordinateSpace: attrs.coordinateSpace || null,
        ownershipSlot: attrs.ownershipSlot || null,
        sourceInlineFlow: attrs.sourceInlineFlow === true,
        storyTextInlineSlot: attrs.storyTextInlineSlot === true,
        tableCellStoryTextInlineSlot: attrs.tableCellStoryTextInlineSlot === true,
        pagePositionedAnchoredSource: attrs.pagePositionedAnchoredSource === true,
        inlineAnchorSourceObjectId: attrs.inlineAnchorSourceObjectId !== undefined
                ? attrs.inlineAnchorSourceObjectId
                : null,
        inlineSourceTreeClosed: attrs.inlineSourceTreeClosed === true,
        textWrapMode: attrs.textWrapMode || null,
        textWrapSide: attrs.textWrapSide || null,
        textWrapTop: attrs.textWrapTop !== undefined ? attrs.textWrapTop : null,
        textWrapLeft: attrs.textWrapLeft !== undefined ? attrs.textWrapLeft : null,
        textWrapBottom: attrs.textWrapBottom !== undefined ? attrs.textWrapBottom : null,
        textWrapRight: attrs.textWrapRight !== undefined ? attrs.textWrapRight : null,
        textWrapSourceObjectId: attrs.textWrapSourceObjectId !== undefined
                ? attrs.textWrapSourceObjectId
                : null,
        clipParentShellOwnerSourceId: attrs.clipParentShellOwnerSourceId !== undefined
                ? attrs.clipParentShellOwnerSourceId
                : null,
        clipParentShellOwnerSourceObjectIds: attrs.clipParentShellOwnerSourceObjectIds || [],
        zOrder: attrs.zOrder !== undefined ? attrs.zOrder : _diagnosticItemZOrder(item, 0),
        required: attrs.required === true
    });
}

function _inlineCompleteMarkerDecisionForOwnership(item, editableTextFrameIds, itemById) {
    var ids = editableTextFrameIds || [];
    if (!item || ids.length !== 1) return false;
    var markerText = null;
    function maybeReadTextFrame(tf) {
        if (!tf) return false;
        try {
            if (tf.constructor.name !== "TextFrame") return false;
            if (String(tf.id) !== String(ids[0])) return false;
            markerText = _plainTextOfTextFrameForOwnership(tf);
            return true;
        } catch (eReadTf) {}
        return false;
    }
    try {
        var nested = item.allPageItems;
        for (var i = 0; i < nested.length; i++) {
            if (maybeReadTextFrame(nested[i])) break;
        }
    } catch (eAll) {}
    if (markerText === null) {
        try {
            var textFrames = item.textFrames;
            var count = textFrames && textFrames.length !== undefined
                    ? Number(textFrames.length || 0)
                    : 0;
            for (var ti = 0; ti < count; ti++) {
                if (maybeReadTextFrame(textFrames[ti])) break;
            }
        } catch (eTextFrames) {}
    }
    if (markerText === null) {
        try {
            maybeReadTextFrame(item);
        } catch (eSelf) {}
    }
    if (markerText === null && itemById) {
        try {
            maybeReadTextFrame(itemById[String(ids[0])]);
        } catch (eById) {}
    }
    return _isSimpleMarkerLabelTextForOwnership(markerText);
}

function _inlineCompositeCompletePngDecisionForOwnership(item, editableTextFrameIds, itemById) {
    var ids = editableTextFrameIds || [];
    if (!item || ids.length === 0) return false;
    // A shell that directly composes multiple editable TFs is an atomic inline
    // label.  Keep the closed source bundle as one COMPLETE_PNG instead of
    // requiring every TF fragment to independently match the single-marker
    // vocabulary.  A shell with one TF still uses the stricter marker rule.
    if (ids.length > 1) return true;
    var markerById = {};

    function maybeReadTextFrame(tf) {
        if (!tf) return false;
        try {
            if (tf.constructor.name !== "TextFrame") return false;
            var tfId = tf.id !== undefined && tf.id !== null ? String(tf.id) : null;
            if (!tfId || markerById.hasOwnProperty(tfId)) return false;
            markerById[tfId] = _isSimpleMarkerLabelTextForOwnership(
                    _plainTextOfTextFrameForOwnership(tf));
            return true;
        } catch (eReadTf) {}
        return false;
    }

    try {
        var nested = item.allPageItems;
        for (var i = 0; nested && i < nested.length; i++) {
            maybeReadTextFrame(nested[i]);
        }
    } catch (eAll) {}
    try {
        var textFrames = item.textFrames;
        var count = textFrames && textFrames.length !== undefined
                ? Number(textFrames.length || 0)
                : 0;
        for (var ti = 0; ti < count; ti++) {
            maybeReadTextFrame(textFrames[ti]);
        }
    } catch (eTextFrames) {}
    if (itemById) {
        for (var ii = 0; ii < ids.length; ii++) {
            var tf = itemById[String(ids[ii])];
            if (tf) maybeReadTextFrame(tf);
        }
    }

    for (var mi = 0; mi < ids.length; mi++) {
        if (markerById[String(ids[mi])] !== true) {
            return false;
        }
    }
    return true;
}

function _createExtractionPlanSourceIndexCache(doc, sourceIndex) {
    function itemId(item) {
        try { return item && item.id !== undefined ? item.id : null; } catch (e) {}
        return null;
    }

    function sourceInfoForItem(item) {
        var id = itemId(item);
        return sourceIndex && id !== null && id !== undefined
                ? sourceIndex.sourceInfo(id)
                : null;
    }

    function fallbackItemInfo(item) {
        var id = itemId(item);
        var kind = _itemKind(item);
        var textFrameClass = null;
        var textLength = null;
        if (kind === "TextFrame") {
            try { textFrameClass = classifyTextFrameCached(item); } catch (eClass) { textFrameClass = null; }
            textLength = _textLengthOfItem(item);
        }
        return {
            id: id,
            pageIndex: _pageIndexOfItem(doc, item),
            kind: kind,
            parentId: _itemParentId(item),
            parentKind: _itemParentKind(item),
            bounds: _itemBounds(item),
            zOrder: _diagnosticItemZOrder(item, null),
            layerName: _itemLayerName(item),
            visible: _itemVisible(item),
            hiddenLayer: false,
            textFrameClass: textFrameClass,
            textLength: textLength,
            hasText: textLength !== null ? textLength > 0 : null,
            hasChildren: false,
            hasPlacedVisual: false,
            hasCandidateVectorPaint: false,
            hasVisibleFill: false,
            hasVisibleStroke: false,
            fillColor: null,
            fillColorName: null,
            fillTint: null,
            strokeColor: null,
            strokeColorName: null,
            strokeTint: null,
            strokeWeight: null,
            cornerRadius: null
        };
    }

    return {
        itemInfo: function(item) {
            return sourceInfoForItem(item) || fallbackItemInfo(item);
        },
        candidatePageIndexes: function(item) {
            var id = itemId(item);
            if (sourceIndex && id !== null && id !== undefined) {
                return sourceIndex.candidatePageIndexes(id);
            }
            var info = fallbackItemInfo(item);
            return info.pageIndex >= 0 ? [info.pageIndex] : [];
        },
        sourceObjectIds: function(item) {
            var id = itemId(item);
            if (sourceIndex && id !== null && id !== undefined) {
                var indexedSourceIds = sourceIndex.sourceObjectIds(id);
                if (indexedSourceIds && indexedSourceIds.length > 0) return indexedSourceIds;
            }
            return _collectSourceObjectIds(item);
        },
        pageLocalSourceObjectIds: function(item, pageIndex) {
            var id = itemId(item);
            if (sourceIndex && id !== null && id !== undefined) {
                var indexedPageLocalSourceIds = sourceIndex.pageLocalSourceObjectIds(id, pageIndex);
                if (indexedPageLocalSourceIds && indexedPageLocalSourceIds.length > 0) {
                    return indexedPageLocalSourceIds;
                }
            }
            return _collectSourceObjectIds(item);
        },
        hasPlacedVisual: function(item) {
            var id = itemId(item);
            if (sourceIndex && id !== null && id !== undefined) {
                return sourceIndex.hasPlacedVisual(id);
            }
            return _hasPlacedVisual(item);
        },
        hasPlacedVisualInSourceTree: function(item) {
            var id = itemId(item);
            if (sourceIndex && id !== null && id !== undefined) {
                return sourceIndex.hasPlacedVisualInSubtree(id);
            }
            return _hasPlacedVisual(item);
        },
        textFrameIds: function(item, editableOnly, requireContent) {
            var id = itemId(item);
            if (sourceIndex && id !== null && id !== undefined) {
                return sourceIndex.textFrameIdsInSubtree(id, editableOnly, requireContent);
            }
            return _collectTextFrameIds(item, editableOnly, requireContent);
        },
        hasEditableTextDescendantOutsideSubtree: function(root, subtreeRoot) {
            var rootId = itemId(root);
            var subtreeRootId = itemId(subtreeRoot);
            if (sourceIndex && rootId !== null && rootId !== undefined
                    && subtreeRootId !== null && subtreeRootId !== undefined) {
                return sourceIndex.hasEditableTextDescendantOutsideSubtree(rootId, subtreeRootId);
            }
            return false;
        },
        clipCarryingParentOfItem: function(item) {
            var id = itemId(item);
            if (sourceIndex && id !== null && id !== undefined) {
                var parentId = sourceIndex.clipCarryingParentIdOfSource(id);
                return parentId !== null && parentId !== undefined ? sourceIndex.domItem(parentId) : null;
            }
            return null;
        },
        domItem: function(sourceId) {
            if (!sourceIndex || sourceId === null || sourceId === undefined) return null;
            return sourceIndex.domItem(sourceId);
        },
        candidateAttrs: function(item, attrs) {
            attrs = attrs || {};
            var info = sourceInfoForItem(item);
            if (info) {
                if (attrs.sourceObjectIds === undefined
                        && info.id !== null && info.id !== undefined) {
                    attrs.sourceObjectIds = [info.id];
                }
                if (attrs.kind === undefined) attrs.kind = info.kind;
                if (attrs.bounds === undefined) attrs.bounds = info.bounds;
                if (attrs.parentId === undefined) attrs.parentId = info.parentId;
                if (attrs.parentKind === undefined) attrs.parentKind = info.parentKind;
            }
            return attrs;
        }
    };
}

function _appendSourceDeclaredInlineShellCandidates(ctx, sourceItems, allItems, candidates, seen, planCache) {
    var sourceInfoById = {};
    var childIdsByParentId = {};
    for (var i = 0; sourceItems && i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!src || src.id === null || src.id === undefined) continue;
        var idKey = String(src.id);
        sourceInfoById[idKey] = src;
        if (src.parentId !== null && src.parentId !== undefined) {
            var parentKey = String(src.parentId);
            if (!childIdsByParentId[parentKey]) childIdsByParentId[parentKey] = [];
            childIdsByParentId[parentKey].push(src.id);
        }
    }

    function pageIndexInCurrentExtraction(pageIndex) {
        if (pageIndex === null || pageIndex === undefined || pageIndex < 0) return false;
        if (ctx && ctx.rangePageCount !== undefined && pageIndex < ctx.rangePageCount) return true;
        return _candidatePageInRange(pageIndex, ctx);
    }

    function directEditableTextChildren(sourceId) {
        var children = childIdsByParentId[String(sourceId)] || [];
        var ids = [];
        for (var ci = 0; ci < children.length; ci++) {
            var child = sourceInfoById[String(children[ci])];
            if (!child) continue;
            if (child.kind !== "TextFrame") continue;
            if (child.textFrameClass !== "editable") continue;
            if (child.hasText !== true) continue;
            ids.push(child.id);
        }
        return _sortedNumericIds(ids);
    }

    function closedSourceSubtree(sourceId) {
        var ids = [];
        var seenIds = {};
        function visit(id) {
            if (id === null || id === undefined || seenIds[String(id)]) return;
            if (!sourceInfoById[String(id)]) return;
            seenIds[String(id)] = true;
            ids.push(id);
            var children = childIdsByParentId[String(id)] || [];
            for (var ci = 0; ci < children.length; ci++) visit(children[ci]);
        }
        visit(sourceId);
        return _sortedNumericIds(ids);
    }

    for (var si = 0; sourceItems && si < sourceItems.length; si++) {
        var sourceEntry = sourceItems[si];
        if (!sourceEntry || sourceEntry.id === null || sourceEntry.id === undefined) continue;
        if (sourceEntry.kind !== "Rectangle" && sourceEntry.kind !== "Oval"
                && sourceEntry.kind !== "Polygon" && sourceEntry.kind !== "Group") continue;
        if (!_isInlineFlowItemBySourceInfo(sourceEntry)) continue;
        if (!pageIndexInCurrentExtraction(sourceEntry.pageIndex)) continue;
        var shellItem = planCache && planCache.domItem ? planCache.domItem(sourceEntry.id) : null;
        if (!shellItem) continue;
        var editableTextFrameIds = directEditableTextChildren(sourceEntry.id);
        if (!editableTextFrameIds || editableTextFrameIds.length === 0) continue;
        var completeMarkerOwnsText = _inlineCompositeCompletePngDecisionForOwnership(
                shellItem, editableTextFrameIds)
                // SPEC-048: 게이지는 "100%" 가 simple marker 가 아니어도 통짜 PNG 로 소유
                || _isGaugeLikeFloatingAnchoredInlineGroupByMaps(
                        sourceEntry, sourceInfoById, childIdsByParentId);
        var completeMarkerFloatingAnchored = completeMarkerOwnsText
                && String(sourceEntry.storyAnchorPlacement || "").toUpperCase() === "FLOATING_ANCHORED";
        var sourceObjectIds = completeMarkerOwnsText
                ? closedSourceSubtree(sourceEntry.id)
                : _sortedNumericIds([sourceEntry.id].concat(editableTextFrameIds));
        var completeExportSourceIds = completeMarkerOwnsText ? [sourceEntry.id] : [];
        var requiresTextHidden = editableTextFrameIds.length > 0 && !completeMarkerOwnsText;
        _pushExtractionCandidate(candidates, seen, "pass.inline_objects", shellItem, {
            sourceObjectIds: sourceObjectIds,
            exportSourceObjectIds: completeExportSourceIds,
            visualSourceObjectIds: completeExportSourceIds,
            exportTargetObjectId: completeMarkerOwnsText ? sourceEntry.id : null,
            pageIndex: sourceEntry.pageIndex,
            unit: "INLINE_OBJECT",
            mode: "TEXTLESS_CANDIDATE",
            candidatePurpose: "INLINE_CANDIDATE",
            editableTextFrameIds: editableTextFrameIds,
            ownedTextFrameIds: completeMarkerOwnsText ? editableTextFrameIds : [],
            hiddenTextFrameIds: requiresTextHidden ? editableTextFrameIds : [],
            requiresTextHidden: requiresTextHidden,
            textOwner: completeMarkerOwnsText ? "indesign_png" : (requiresTextHidden ? "hwpx_tf" : "none"),
            containsEditableText: completeMarkerOwnsText,
            completePngTextAllowed: completeMarkerOwnsText,
            materialization: completeMarkerOwnsText ? "COMPLETE_PNG" : null,
            textAction: completeMarkerOwnsText ? "OWNED_BY_PNG" : null,
            visualAction: completeMarkerOwnsText
                    ? (completeMarkerFloatingAnchored ? "PLACE_FLOATING_PNG" : "PLACE_INLINE_PNG")
                    : null,
            ownershipSlot: completeMarkerOwnsText ? "CONTENT_VISUAL_SLOT" : null,
            placement: completeMarkerFloatingAnchored ? "FLOATING" : "INLINE",
            coordinateSpace: completeMarkerFloatingAnchored ? "PAGE" : "STORY_FLOW",
            storyAnchorPlacement: sourceEntry.storyAnchorPlacement || null,
            anchoredPosition: sourceEntry.anchoredPosition || null,
            sourceInlineFlow: !completeMarkerFloatingAnchored && sourceEntry.storyTextInlineSlot === true,
            storyTextInlineSlot: sourceEntry.storyTextInlineSlot === true,
            tableCellStoryTextInlineSlot: sourceEntry.tableCellStoryTextInlineSlot === true,
            pagePositionedAnchoredSource: completeMarkerFloatingAnchored,
            inlineAnchorSourceObjectId: completeMarkerFloatingAnchored ? null : sourceEntry.id,
            inlineSourceTreeClosed: !completeMarkerFloatingAnchored && completeMarkerOwnsText
        });
    }
}

function _isExtractionShellCandidate(candidate) {
    return candidate
            && candidate.candidatePurpose === "SHELL_CANDIDATE"
            && (candidate.passId === "pass.decoration_groups"
                    || candidate.passId === "pass.editable_textframe_visual_shells")
            && candidate.sourceObjectIds
            && candidate.sourceObjectIds.length > 0;
}

function _isPlannerDeclaredDirectChildShellSlot(candidate) {
    if (!candidate) return false;
    if (candidate.slotRole !== "direct_child_shell_slot"
            && candidate.compositeRole !== "direct_child_shell_slot") return false;
    return candidate.exportSourceObjectIds && candidate.exportSourceObjectIds.length > 0;
}

function _isDirectChildShellSlotCandidate(candidate) {
    if (!candidate) return false;
    if (candidate.slotRole === "direct_child_shell_slot"
            || candidate.compositeRole === "direct_child_shell_slot") {
        return true;
    }
    if (String(candidate.candidateId || "").indexOf("direct_child_shell_slot") >= 0) return true;
    return false;
}

function _isSlotOnlyShellWithHiddenChildren(candidate) {
    return _isExtractionShellCandidate(candidate)
            && (candidate.slotRole === "shell_slot_only" || candidate.mode === "SLOT_ONLY")
            && candidate.hiddenVisualSourceObjectIds
            && candidate.hiddenVisualSourceObjectIds.length > 0;
}

function _candidateEffectiveVisualSourceIds(candidate) {
    if (!candidate) return [];
    if (candidate.exportSourceObjectIds && candidate.exportSourceObjectIds.length > 0) {
        return _sortedNumericIds(candidate.exportSourceObjectIds);
    }
    if (_isSlotOnlyShellWithHiddenChildren(candidate)) return [];
    return _sortedNumericIds(candidate.sourceObjectIds || []);
}

function _candidateVisibleExportSourceIds(candidate) {
    var ids = candidate && candidate.exportSourceObjectIds && candidate.exportSourceObjectIds.length > 0
            ? candidate.exportSourceObjectIds
            : (candidate ? candidate.sourceObjectIds : []);
    var styleSource = {};
    for (var si = 0; candidate && candidate.styleSourceObjectIds && si < candidate.styleSourceObjectIds.length; si++) {
        styleSource[String(candidate.styleSourceObjectIds[si])] = true;
    }
    var hiddenText = {};
    for (var hi = 0; candidate && candidate.hiddenTextFrameIds && hi < candidate.hiddenTextFrameIds.length; hi++) {
        hiddenText[String(candidate.hiddenTextFrameIds[hi])] = true;
    }
    var editableText = {};
    for (var ei = 0; candidate && candidate.editableTextFrameIds && ei < candidate.editableTextFrameIds.length; ei++) {
        editableText[String(candidate.editableTextFrameIds[ei])] = true;
    }
    var out = [];
    var seen = {};
    for (var vi = 0; ids && vi < ids.length; vi++) {
        var id = ids[vi];
        if (!styleSource[String(id)] && (hiddenText[String(id)] || editableText[String(id)])) continue;
        _pushUniqueId(out, seen, id);
    }
    return _sortedNumericIds(out);
}

function _sourceInfoHasVisiblePaintMetadata(src) {
    if (!src) return false;
    if (src.hasVisibleFill === true || src.hasVisibleStroke === true) return true;
    var fillName = String(src.fillColorName || src.fillColor || "");
    if (fillName && fillName !== "None" && fillName !== "[None]") return true;
    var strokeName = String(src.strokeColorName || src.strokeColor || "");
    var strokeWeight = Number(src.strokeWeight || 0);
    return strokeName && strokeName !== "None" && strokeName !== "[None]" && strokeWeight > 0;
}

function _sourceHasVisiblePaintMetadataInIndex(sourceId, sourceInfoById) {
    var src = sourceInfoById ? sourceInfoById[String(sourceId)] : null;
    return _sourceInfoHasVisiblePaintMetadata(src);
}

function _sourceHasTextFrameShellStyleMetadataInIndex(sourceId, sourceInfoById) {
    var src = sourceInfoById ? sourceInfoById[String(sourceId)] : null;
    if (!src || String(src.kind || "") !== "TextFrame") return false;
    return _sourceInfoHasVisiblePaintMetadata(src);
}

function _sourceHasPlacedVisualMetadataInIndex(sourceId, sourceInfoById, childIdsByParentId, visiting) {
    var src = sourceInfoById ? sourceInfoById[String(sourceId)] : null;
    if (!src) return false;
    var key = String(sourceId);
    visiting = visiting || {};
    if (visiting[key]) return false;
    visiting[key] = true;
    var kind = String(src.kind || "");
    if (kind === "Image" || kind === "PDF") return true;
    if (src.hasPlacedVisual === true || src.hasPlacedVisualInSubtree === true) return true;
    var children = childIdsByParentId ? (childIdsByParentId[key] || []) : [];
    for (var ci = 0; ci < children.length; ci++) {
        if (_sourceHasPlacedVisualMetadataInIndex(children[ci], sourceInfoById, childIdsByParentId, visiting)) {
            return true;
        }
    }
    return false;
}

function _sourceHasExecutableShellMaterialMetadataInIndex(sourceId, sourceInfoById, childIdsByParentId) {
    var src = sourceInfoById ? sourceInfoById[String(sourceId)] : null;
    if (!src) return false;
    var kind = String(src.kind || "");
    if (kind === "TextFrame") {
        return _sourceHasTextFrameShellStyleMetadataInIndex(sourceId, sourceInfoById);
    }
    if (kind === "Rectangle" || kind === "Oval" || kind === "Polygon" || kind === "GraphicLine") {
        return _sourceHasVisiblePaintMetadataInIndex(sourceId, sourceInfoById)
                || _sourceHasPlacedVisualMetadataInIndex(sourceId, sourceInfoById, childIdsByParentId);
    }
    if (kind === "Group") {
        return _sourceHasVisiblePaintMetadataInIndex(sourceId, sourceInfoById)
                || _sourceHasPlacedVisualMetadataInIndex(sourceId, sourceInfoById, childIdsByParentId);
    }
    return _sourceHasPlacedVisualMetadataInIndex(sourceId, sourceInfoById, childIdsByParentId);
}

function _candidateHasExecutableShellMaterial(candidate, sourceInfoById, childIdsByParentId) {
    if (!candidate || candidate.candidatePurpose !== "SHELL_CANDIDATE") return true;
    if (candidate.materialization === "NATIVE_SOURCE_SHAPE"
            || candidate.materialization === "HWPX_TABLE_STYLE"
            || candidate.materialization === "HWPX_TEXT") {
        return true;
    }
    var ids = _candidateVisibleExportSourceIds(candidate);
    if (!ids || ids.length === 0) return false;
    for (var i = 0; i < ids.length; i++) {
        if (_sourceHasExecutableShellMaterialMetadataInIndex(ids[i], sourceInfoById, childIdsByParentId)) {
            return true;
        }
    }
    return false;
}

function _candidateSourceKindPriority(sourceId, sourceInfoById) {
    var src = sourceInfoById ? sourceInfoById[String(sourceId)] : null;
    var kind = src ? String(src.kind || "") : "";
    if (kind === "Group") return 0;
    if (kind === "Rectangle" || kind === "Oval" || kind === "Polygon") return 1;
    if (kind === "GraphicLine") return 2;
    if (kind === "TextFrame") return 9;
    return 4;
}

function _chooseFallbackPrimarySourceId(sourceIds, sourceInfoById) {
    if (!sourceIds || sourceIds.length === 0) return null;
    var best = sourceIds[0];
    var bestPriority = _candidateSourceKindPriority(best, sourceInfoById);
    for (var i = 1; i < sourceIds.length; i++) {
        var id = sourceIds[i];
        var priority = _candidateSourceKindPriority(id, sourceInfoById);
        if (priority < bestPriority) {
            best = id;
            bestPriority = priority;
        }
    }
    return best;
}

function _candidateEditableTextIds(candidate, sourceInfoById) {
    var out = [], seen = {};
    if (!candidate || !candidate.sourceObjectIds) return out;
    for (var i = 0; i < candidate.sourceObjectIds.length; i++) {
        var id = candidate.sourceObjectIds[i];
        var src = sourceInfoById[String(id)];
        if (!src) continue;
        if (src.kind !== "TextFrame") continue;
        if (src.textFrameClass !== "editable") continue;
        if (src.hasText !== true) continue;
        _pushUniqueId(out, seen, id);
    }
    return out;
}

function _candidateMetaSourceContainsAll(containerMeta, memberMeta) {
    if (!containerMeta || !memberMeta || !memberMeta.sourceIds || memberMeta.sourceIds.length === 0) return false;
    for (var i = 0; i < memberMeta.sourceIds.length; i++) {
        if (!containerMeta.sourceIdSet[String(memberMeta.sourceIds[i])]) return false;
    }
    return true;
}

function _candidateSourceIdArrayContainsAll(containerIds, memberIds) {
    return _sourceSetContainsAll(containerIds, memberIds);
}

function _candidateEditableTextIntersects(aMeta, bMeta) {
    if (!aMeta || !bMeta || !aMeta.editableTextIds || !bMeta.editableTextIds) return false;
    if (aMeta.editableTextIds.length === 0 || bMeta.editableTextIds.length === 0) return false;
    var probe = aMeta.editableTextIds.length <= bMeta.editableTextIds.length ? aMeta : bMeta;
    var target = probe === aMeta ? bMeta : aMeta;
    for (var i = 0; i < probe.editableTextIds.length; i++) {
        if (target.editableTextIdSet[String(probe.editableTextIds[i])]) return true;
    }
    return false;
}

function _buildExtractionCandidateMeta(candidate, index, sourceInfoById) {
    var sourceIds = candidate && candidate.sourceObjectIds ? candidate.sourceObjectIds : [];
    var editableIds = _candidateEditableTextIds(candidate, sourceInfoById);
    return {
        index: index,
        candidate: candidate,
        pageKey: candidate && candidate.pageIndex !== null && candidate.pageIndex !== undefined
                ? String(candidate.pageIndex)
                : "none",
        sourceIds: sourceIds,
        sourceKey: _sourceSetKey(sourceIds),
        sourceIdSet: _sourceIdSet(sourceIds),
        editableTextIds: editableIds,
        editableTextIdSet: _sourceIdSet(editableIds),
        hasEditableText: editableIds.length > 0,
        isShell: _isExtractionShellCandidate(candidate)
    };
}

function _refreshExtractionCandidateMeta(meta, sourceInfoById) {
    if (!meta) return meta;
    var refreshed = _buildExtractionCandidateMeta(meta.candidate, meta.index, sourceInfoById);
    meta.sourceIds = refreshed.sourceIds;
    meta.sourceKey = refreshed.sourceKey;
    meta.sourceIdSet = refreshed.sourceIdSet;
    meta.editableTextIds = refreshed.editableTextIds;
    meta.editableTextIdSet = refreshed.editableTextIdSet;
    meta.hasEditableText = refreshed.hasEditableText;
    meta.isShell = refreshed.isShell;
    return meta;
}

function _buildExtractionCandidateMetaIndexes(candidates, sourceInfoById) {
    var metas = [];
    var indexesByPage = {};
    var shellIndexesByPage = {};
    for (var metaIdx = 0; candidates && metaIdx < candidates.length; metaIdx++) {
        var meta = _buildExtractionCandidateMeta(candidates[metaIdx], metaIdx, sourceInfoById);
        metas.push(meta);
        if (!indexesByPage[meta.pageKey]) indexesByPage[meta.pageKey] = [];
        indexesByPage[meta.pageKey].push(metaIdx);
        if (meta.isShell) {
            if (!shellIndexesByPage[meta.pageKey]) shellIndexesByPage[meta.pageKey] = [];
            shellIndexesByPage[meta.pageKey].push(metaIdx);
        }
    }
    return {
        metas: metas,
        indexesByPage: indexesByPage,
        shellIndexesByPage: shellIndexesByPage
    };
}
