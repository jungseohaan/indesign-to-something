/**
 * Master page/applied page graphic render executor.
 *
 * This module executes planned master graphic extraction only.
 * It must not create ownership, placement, or fallback decisions.
 */

var MASTER_PAGE_GRAPHICS_PLANE_Z_ORDER = -1000000;

function exportMasterPageGraphics(doc, outputDir, startPage, endPage, masterCandidates, inlineCandidates) {
    // Strategy v13: 마스터 page side의 visible visual layer를 합성 PNG로 내보낸다.
    //
    // 이전 구현은 각 master page side에서 가장 큰 non-TF/non-Group item 하나만 export했다.
    // 큰 배경 사각형만 선택되어 그 위의 흰 오버레이/그룹 장식이 누락되는 문제가 있었다.
    //
    // v13 전략:
    //   - 각 마스터 스프레드 page side별 top-level item을 수집한다.
    //   - 문서 끝의 임시 페이지에 적용 마스터를 지정하고, 해당 side item을 override한다.
    //   - override된 item들을 하나의 group으로 export해 InDesign z-order를 보존한다.
    //   - editable master TextFrame 텍스트는 중복 방지를 위해 content만 숨긴다.
    //   - 임시 페이지를 제거해 원본 문서 변경을 남기지 않는다.

    var debugLog = File(outputDir + "/_master_debug.log");
    try { debugLog.open("w"); } catch (e) { debugLog = null; }
    function dbg(msg) {
        $.writeln("[exportMasterPageGraphics] " + msg);
        if (debugLog) try { debugLog.writeln(msg); } catch (e) {}
    }

    dbg("START v13 startPage=" + startPage + " endPage=" + endPage + " docPages=" + doc.pages.length);

    var renderDir = Folder(outputDir + "/rendered_frames");
    renderDir.create();
    var results = [];
    var masterCandidatesByPage = {};
    for (var _mci = 0; masterCandidates && _mci < masterCandidates.length; _mci++) {
        var _mc = masterCandidates[_mci];
        if (!_mc || _mc.pageIndex === null || _mc.pageIndex === undefined) continue;
        var _mck = String(_mc.pageIndex);
        if (!masterCandidatesByPage[_mck]) masterCandidatesByPage[_mck] = [];
        masterCandidatesByPage[_mck].push(_mc);
    }
    var inlineCandidatesByPage = {};
    for (var _ici = 0; inlineCandidates && _ici < inlineCandidates.length; _ici++) {
        var _ic = inlineCandidates[_ici];
        if (!_ic || _ic.pageIndex === null || _ic.pageIndex === undefined) continue;
        if (_ic.placement && _ic.placement !== "INLINE") continue;
        if (_ic.visualAction && _ic.visualAction !== "PLACE_INLINE_PNG") continue;
        var _ick = String(_ic.pageIndex);
        if (!inlineCandidatesByPage[_ick]) inlineCandidatesByPage[_ick] = [];
        inlineCandidatesByPage[_ick].push(_ic);
    }

    // masterSpreadId → [{docIdx, masterPageIdx}, ...] 빌드
    // masterPageIdx: 마스터 스프레드 내 페이지 인덱스 (0=좌, 1=우)
    // 문서 페이지가 스프레드 내 몇 번째 페이지인지로 결정
    var masterToPages = {};
    var masterToPagesCnt = 0;
    try {
        for (var pp = 0; pp < doc.pages.length; pp++) {
            try {
                var pgNum = pp + 1;
                if (pgNum < startPage || pgNum > endPage) continue;
                var am = doc.pages[pp].appliedMaster;
                if (!am) continue;
                var mid = am.id.toString();
                var masterPageIdx = 0;
                try {
                    var docPageObj = doc.pages[pp];
                    var parentSprd = docPageObj.parent;
                    var sprdPgs = parentSprd.pages;
                    for (var spi2 = 0; spi2 < sprdPgs.length; spi2++) {
                        if (sprdPgs[spi2].id === docPageObj.id) { masterPageIdx = spi2; break; }
                    }
                } catch (eSide) {}
                if (!masterToPages[mid]) { masterToPages[mid] = []; masterToPagesCnt++; }
                masterToPages[mid].push({ docIdx: pp, masterPageIdx: masterPageIdx });
            } catch (e) {}
        }
    } catch (e) { dbg("masterToPages build error: " + e); }
    dbg("masterToPages: " + masterToPagesCnt + " unique masters");
    if (masterToPagesCnt === 0) {
        if (debugLog) try { debugLog.close(); } catch (e) {}
        return results;
    }

    var msArr = [];
    try { msArr = doc.masterSpreads.everyItem().getElements(); } catch (e) {}

    // PNG 설정
    var savedRes, savedTransp, savedSpread;
    try { savedRes = app.pngExportPreferences.exportResolution; } catch (e) {}
    try { savedTransp = app.pngExportPreferences.transparentBackground; } catch (e) {}
    try { savedSpread = app.pngExportPreferences.exportingSpread; } catch (e) {}
    try { app.pngExportPreferences.exportResolution = 150; } catch (e) {}
    try { app.pngExportPreferences.transparentBackground = true; } catch (e) {}
    try { app.pngExportPreferences.exportingSpread = false; } catch (e) {}

    // msId + "_" + masterPageIdx → [{ relPath, relTop, relLeft, relBottom, relRight }, ...]
    // 마스터 스프레드의 좌/우 페이지별로 분리 export
    var exportedMasterByPage = {};

    var VISIBLE_INTERSECTION_EPS = 0.01;

    function _boundsIntersect(a, b, pad) {
        pad = pad || 0;
        try {
            return a[2] >= b[0] - pad && a[0] <= b[2] + pad &&
                   a[3] >= b[1] - pad && a[1] <= b[3] + pad;
        } catch (e) {}
        return false;
    }

    function _boundsHasVisibleArea(b) {
        try {
            if (!b || b.length < 4) return false;
            return (b[2] - b[0]) > VISIBLE_INTERSECTION_EPS &&
                   (b[3] - b[1]) > VISIBLE_INTERSECTION_EPS;
        } catch (e) {}
        return false;
    }

    function _boundsArea(b) {
        try {
            if (!b || b.length < 4) return 0;
            var h = Math.max(0, Number(b[2]) - Number(b[0]));
            var w = Math.max(0, Number(b[3]) - Number(b[1]));
            return h * w;
        } catch (e) {}
        return 0;
    }

    function _boundsIntersectionArea(a, b) {
        var inter = _boundsIntersection(a, b);
        return inter ? _boundsArea(inter) : 0;
    }

    function _masterEntriesStronglyOverlap(a, b, pad) {
        if (!a || !b) return false;
        if (!_boundsIntersect(a.bounds, b.bounds, pad)) return false;
        var overlap = _boundsIntersectionArea(a.bounds, b.bounds);
        if (overlap <= VISIBLE_INTERSECTION_EPS) return false;
        var minArea = Math.min(_boundsArea(a.bounds), _boundsArea(b.bounds));
        if (minArea <= VISIBLE_INTERSECTION_EPS) return false;
        return overlap / minArea >= 0.05;
    }

    function _boundsIntersection(a, b) {
        try {
            if (!a || !b || a.length < 4 || b.length < 4) return null;
            var t = Math.max(a[0], b[0]);
            var l = Math.max(a[1], b[1]);
            var bt = Math.min(a[2], b[2]);
            var r = Math.min(a[3], b[3]);
            if ((bt - t) <= VISIBLE_INTERSECTION_EPS || (r - l) <= VISIBLE_INTERSECTION_EPS) return null;
            return [t, l, bt, r];
        } catch (e) {}
        return null;
    }

    function _hasVisibleIntersection(a, b) {
        return !!_boundsIntersection(a, b);
    }

    function _isSmallMasterEdgeDecoration(itemBounds, pageBounds) {
        try {
            if (!itemBounds || !pageBounds || itemBounds.length < 4 || pageBounds.length < 4) return false;
            var pageH = Math.abs(pageBounds[2] - pageBounds[0]);
            var pageW = Math.abs(pageBounds[3] - pageBounds[1]);
            var itemH = Math.abs(itemBounds[2] - itemBounds[0]);
            var itemW = Math.abs(itemBounds[3] - itemBounds[1]);
            if (pageH <= 0.01 || pageW <= 0.01 || itemH <= 0.01 || itemW <= 0.01) return false;
            return itemH <= pageH * 0.35 && itemW <= pageW * 0.55;
        } catch (e) {}
        return false;
    }

    function _masterItemBelongsToPage(itemBounds, pageBounds) {
        try {
            if (!itemBounds || !pageBounds || itemBounds.length < 4 || pageBounds.length < 4) return false;
            // Actual page intersection is the canonical ownership signal. Padded
            // intersection exists only for small edge decorations that intentionally
            // bleed just outside the page. Large spread/page composites must not be
            // pulled across the facing-page boundary because that makes one side's
            // master background disappear from the extraction result set.
            if (_hasVisibleIntersection(itemBounds, pageBounds)) return true;
            return _isSmallMasterEdgeDecoration(itemBounds, pageBounds)
                    && _boundsIntersect(itemBounds, pageBounds, 5);
        } catch (e) {}
        return false;
    }

    function _masterItemBelongsToMasterSide(item, masterPage) {
        try {
            if (!item || !masterPage) return false;
            try {
                var parentPage = item.parentPage;
                if (parentPage && parentPage.id === masterPage.id) return true;
            } catch (eParentPage) {}
            var cur = item.parent;
            var hop = 0;
            while (cur && hop < 10) {
                var kind = "";
                try { kind = cur.constructor.name; } catch (eKind) { break; }
                if (kind === "Page") {
                    try { return cur.id === masterPage.id; } catch (ePageId) { return false; }
                }
                if (kind === "MasterSpread" || kind === "Document") return false;
                try { cur = cur.parent; } catch (eParent) { break; }
                hop++;
            }
        } catch (e) {}
        return false;
    }

    function _boundsDiffer(a, b, eps) {
        eps = eps || 0.01;
        try {
            if (!a || !b || a.length < 4 || b.length < 4) return false;
            return Math.abs(a[0] - b[0]) > eps || Math.abs(a[1] - b[1]) > eps ||
                   Math.abs(a[2] - b[2]) > eps || Math.abs(a[3] - b[3]) > eps;
        } catch (e) {}
        return false;
    }

    function _masterItemBounds(item) {
        var b = null;
        try { b = arrCopy(item.visibleBounds); } catch (e) {}
        if (!b) try { b = arrCopy(item.geometricBounds); } catch (e2) {}
        return b;
    }

    function _clusterMasterPageItems(pageItems) {
        var clusters = [];
        var used = [];
        var pad = 2.0;
        for (var i = 0; i < pageItems.length; i++) {
            if (used[i]) continue;
            var cluster = [];
            var queue = [i];
            used[i] = true;
            while (queue.length > 0) {
                var idx = queue.shift();
                cluster.push(pageItems[idx]);
                for (var j = 0; j < pageItems.length; j++) {
                    if (used[j]) continue;
                    var touches = false;
                    for (var k = 0; k < cluster.length; k++) {
                        if (_masterEntriesStronglyOverlap(cluster[k], pageItems[j], pad)) {
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

    function _unionMasterEntryBounds(entries) {
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

    function _copyMasterEntryLayerInfo(target, sourceEntry) {
        if (!target || !sourceEntry || !sourceEntry.item) return target;
        try {
            _copyLayerInfo(target, sourceEntry.item);
        } catch (e) {}
        return target;
    }

    function _copyMasterMatchedCandidateContract(target, candidate) {
        if (!target || !candidate) return target;
        if (!_masterCandidateContractCompatible(target, candidate)) {
            return target;
        }
        var fields = [
            "objectPlanId",
            "renderUnitId",
            "renderUnitSlotIdentityKey",
            "materialization",
            "textAction",
            "visualAction",
            "visualLayer",
            "ownershipSlot",
            "slotRole",
            "contractStatus",
            "mode",
            "candidatePurpose",
            "compositeRole"
        ];
        for (var i = 0; i < fields.length; i++) {
            var key = fields[i];
            if (!candidate.hasOwnProperty || !candidate.hasOwnProperty(key)) continue;
            var value = candidate[key];
            if (value === undefined || value === null || value === "") continue;
            target[key] = value;
        }
        if (candidate.passId) target.planPassId = candidate.passId;
        if (candidate.mode) target.renderMode = candidate.mode;
        return target;
    }

    function _masterSlotChannelOf(entry) {
        if (!entry) return "UNKNOWN";
        var slotRole = entry.slotRole ? String(entry.slotRole) : "";
        if (slotRole === "CONTENT_VISUAL_SLOT") return "CONTENT";
        if (slotRole === "TEXTLESS_SHELL_SLOT" || slotRole === "SHELL_SLOT") return "SHELL";
        var ownershipSlot = entry.ownershipSlot ? String(entry.ownershipSlot) : "";
        if (ownershipSlot === "CONTENT_VISUAL_SLOT") return "CONTENT";
        if (ownershipSlot === "TEXTLESS_SHELL_SLOT" || ownershipSlot === "SHELL_SLOT") return "SHELL";
        var textOwner = entry.textOwner ? String(entry.textOwner) : "";
        if (textOwner === "hwpx_tf") return "SHELL";
        if (textOwner === "none") return "CONTENT";
        var visualAction = entry.visualAction ? String(entry.visualAction) : "";
        if (visualAction === "PLACE_TEXT_SHELL") return "SHELL";
        if (visualAction === "PLACE_FLOATING_PNG" || visualAction === "PLACE_INLINE_PNG") return "CONTENT";
        return "UNKNOWN";
    }

    function _masterCandidateContractCompatible(target, candidate) {
        var targetChannel = _masterSlotChannelOf(target);
        var candidateChannel = _masterSlotChannelOf(candidate);
        if (targetChannel === "UNKNOWN" || candidateChannel === "UNKNOWN") return true;
        return targetChannel === candidateChannel;
    }

    function _primaryMasterLayerEntry(entries) {
        if (!entries || entries.length === 0) return null;
        var primary = null;
        for (var i = 0; i < entries.length; i++) {
            var entry = entries[i];
            if (!entry || !entry.item) continue;
            if (!primary) {
                primary = entry;
                continue;
            }
            var primaryDepth = primary.sourceDepthOrder;
            var entryDepth = entry.sourceDepthOrder;
            if (primaryDepth === null || primaryDepth === undefined) primaryDepth = Number.MAX_VALUE;
            if (entryDepth === null || entryDepth === undefined) entryDepth = Number.MAX_VALUE;
            if (entryDepth < primaryDepth) primary = entry;
        }
        return primary;
    }

    function _masterEntrySourceDepth(entries) {
        var maxDepth = null;
        for (var i = 0; entries && i < entries.length; i++) {
            var depth = entries[i] ? entries[i].sourceDepthOrder : null;
            if (depth === null || depth === undefined) continue;
            if (maxDepth === null || depth > maxDepth) maxDepth = depth;
        }
        return maxDepth;
    }

    function _boundsAspect(b) {
        try {
            if (!b || b.length < 4) return 0;
            var h = Math.abs(b[2] - b[0]);
            var w = Math.abs(b[3] - b[1]);
            if (h <= 0.01 || w <= 0.01) return 0;
            return w / h;
        } catch (e) {
            return 0;
        }
    }

    function _boundsAspectFitAtSourceAnchor(sourceBnds, exportBnds) {
        try {
            if (!sourceBnds || sourceBnds.length < 4 || !exportBnds || exportBnds.length < 4) {
                return sourceBnds || exportBnds;
            }
            var sourceH = Math.abs(sourceBnds[2] - sourceBnds[0]);
            var sourceW = Math.abs(sourceBnds[3] - sourceBnds[1]);
            var exportAspect = _boundsAspect(exportBnds);
            if (sourceH <= 0.01 || sourceW <= 0.01 || exportAspect <= 0.01) return sourceBnds;

            var sourceAspect = sourceW / sourceH;
            if (sourceAspect < exportAspect) {
                var fittedH = sourceW / exportAspect;
                return [sourceBnds[0], sourceBnds[1], sourceBnds[0] + fittedH, sourceBnds[3]];
            }

            var fittedW = sourceH * exportAspect;
            return [sourceBnds[0], sourceBnds[1], sourceBnds[2], sourceBnds[1] + fittedW];
        } catch (e) {
            return sourceBnds || exportBnds;
        }
    }

    function _clampSmallMasterDecorationInsidePage(bnds, pageBnds) {
        try {
            if (!bnds || bnds.length < 4 || !pageBnds || pageBnds.length < 4) return bnds;
            // Master graphics frequently use bleed/overhang intentionally
            // (page-number badges, hashira strips, edge markers). If the item already intersects
            // the page, preserve the original master coordinates and let the placement stage crop
            // the visible page intersection. Moving it inside destroys the left/right clipping.
            if (_boundsIntersection(bnds, pageBnds)) return bnds;
            var pageH = Math.abs(pageBnds[2] - pageBnds[0]);
            var pageW = Math.abs(pageBnds[3] - pageBnds[1]);
            var h = Math.abs(bnds[2] - bnds[0]);
            var w = Math.abs(bnds[3] - bnds[1]);
            if (pageH <= 0.01 || pageW <= 0.01 || h <= 0.01 || w <= 0.01) return bnds;

            var smallDecoration = h <= pageH * 0.35 && w <= pageW * 0.55;
            if (!smallDecoration) return bnds;

            var relTop = bnds[0] - pageBnds[0];
            var relLeft = bnds[1] - pageBnds[1];
            var bleedThreshold = 6.0;
            var dy = relTop < -bleedThreshold ? -relTop : 0;
            var dx = relLeft < -bleedThreshold ? -relLeft : 0;
            if (dy <= 0 && dx <= 0) return bnds;
            return [bnds[0] + dy, bnds[1] + dx, bnds[2] + dy, bnds[3] + dx];
        } catch (e) {
            return bnds;
        }
    }

    function _isTopLevelMasterItem(item) {
        try {
            if (!item) return false;
            if (isOnHiddenLayer(item)) return false;
            try { if (item.visible === false) return false; } catch (eVis) {}
            try { if (item.nonprinting) return false; } catch (eNp) {}

            var p = item.parent;
            while (p) {
                var pn = "";
                try { pn = p.constructor.name; } catch (eName) { break; }
                if (pn === "Group") return false;
                if (pn === "Page" || pn === "Spread" || pn === "MasterSpread" || pn === "Document") return true;
                try { p = p.parent; } catch (eParent) { break; }
            }
        } catch (e) {}
        return false;
    }

    function _directMasterGroupChildItems(item) {
        var out = [];
        try {
            if (!item || !item.constructor || item.constructor.name !== "Group") return out;
            var rootId = item.id;
            var nested = item.allPageItems;
            for (var i = 0; nested && i < nested.length; i++) {
                try {
                    var child = nested[i];
                    if (!child || child.id === undefined || child.id === null) continue;
                    var parent = child.parent;
                    if (!parent || parent.id !== rootId) continue;
                    if (child.constructor && child.constructor.name === "TextFrame"
                            && _isDynamicMasterTextFrame(child)) continue;
                    if (isOnHiddenLayer(child)) continue;
                    try { if (child.visible === false) continue; } catch (eVis) {}
                    try { if (child.nonprinting) continue; } catch (eNp) {}
                    out.push(child);
                } catch (eChild) {}
            }
        } catch (e) {}
        return out;
    }

    function _masterHasPlacedVisual(item) {
        try { if (item.images && item.images.length > 0) return true; } catch (eImage) {}
        try { if (item.pdfs && item.pdfs.length > 0) return true; } catch (ePdf) {}
        try { if (item.epss && item.epss.length > 0) return true; } catch (eEps) {}
        return false;
    }

    function _masterGroupShouldUseChildEntries(item, bounds, pageBounds) {
        try {
            if (!item || !item.constructor || item.constructor.name !== "Group") return false;
            if (!bounds || !pageBounds || bounds.length < 4 || pageBounds.length < 4) return false;
            var pageH = Math.abs(Number(pageBounds[2]) - Number(pageBounds[0]));
            var pageW = Math.abs(Number(pageBounds[3]) - Number(pageBounds[1]));
            var h = Math.abs(Number(bounds[2]) - Number(bounds[0]));
            var w = Math.abs(Number(bounds[3]) - Number(bounds[1]));
            if (pageH <= 0.01 || pageW <= 0.01 || h <= 0.01 || w <= 0.01) return false;
            if (h <= pageH * 1.25 && w <= pageW * 1.25) return false;
            return _directMasterGroupChildItems(item).length > 0;
        } catch (e) {}
        return false;
    }

    function _isPaperFillOnlyMasterMask(item, bounds, pageBounds) {
        try {
            if (!item || !bounds || !pageBounds || bounds.length < 4 || pageBounds.length < 4) return false;
            if (!hasVisibleFill(item)) return false;
            if (hasVisibleStroke(item)) return false;
            var fillName = "";
            try { fillName = item.fillColor ? String(item.fillColor.name || "") : ""; } catch (eFill) {}
            fillName = fillName.toLowerCase();
            if (fillName !== "paper" && fillName !== "[paper]") return false;
            if (_masterHasPlacedVisual(item)) return false;
            try {
                var nested = item.allPageItems;
                for (var ni = 0; nested && ni < nested.length; ni++) {
                    var child = nested[ni];
                    if (!child) continue;
                    if (_masterHasPlacedVisual(child)) return false;
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

    function _appendMasterPageEntries(pageItems, item, bounds, pageBounds, sourceDepthOrder, masterPage) {
        if (_isPaperFillOnlyMasterMask(item, bounds, pageBounds)) return;
        if (_masterGroupShouldUseChildEntries(item, bounds, pageBounds)) {
            var children = _directMasterGroupChildItems(item);
            for (var ci = 0; ci < children.length; ci++) {
                try {
                    var cb = _masterItemBounds(children[ci]);
                    var childOnMasterSide = _masterItemBelongsToMasterSide(children[ci], masterPage);
                    if (!cb || (!childOnMasterSide && !_masterItemBelongsToPage(cb, pageBounds))) continue;
                    if (_isPaperFillOnlyMasterMask(children[ci], cb, pageBounds)) continue;
                    pageItems.push({
                        item: children[ci],
                        bounds: cb,
                        sourceDepthOrder: (sourceDepthOrder * 1000) + (children.length - 1 - ci)
                    });
                } catch (eChildEntry) {}
            }
            return;
        }
        pageItems.push({
            item: item,
            bounds: bounds,
            sourceDepthOrder: sourceDepthOrder
        });
    }

    function _collectIdsForMasterItems(entries) {
        var ids = [], seen = {};
        for (var i = 0; i < entries.length; i++) {
            try {
                var item = entries[i].item;
                _pushUniqueId(ids, seen, item.id);
                var nested = item.allPageItems;
                for (var ni = 0; ni < nested.length; ni++) {
                    try { _pushUniqueId(ids, seen, nested[ni].id); } catch (eNested) {}
                }
            } catch (e) {}
        }
        return ids;
    }

    function _directInlineAnchorSourceId(entry, sourceIds) {
        try {
            if (!entry || !entry.item || entry.item.id === undefined) return null;
            var rootId = Number(entry.item.id);
            if (!sourceIds || sourceIds.length === 0 || !_idArrayContains(sourceIds, rootId)) return null;
            if (typeof _storyAnchorPlacementForItem !== "function") return null;
            if (_storyAnchorPlacementForItem(doc, entry.item, null) !== "INLINE") return null;
            return rootId;
        } catch (e) {
            return null;
        }
    }

    function _sameSourceSet(a, b) {
        return _sourceSetKey(a || []) === _sourceSetKey(b || []);
    }

    function _masterCandidateComparableSourceIds(candidate) {
        if (!candidate) return [];
        if (candidate.exportSourceObjectIds && candidate.exportSourceObjectIds.length > 0) {
            return candidate.exportSourceObjectIds;
        }
        if (candidate.visualSourceObjectIds && candidate.visualSourceObjectIds.length > 0) {
            return candidate.visualSourceObjectIds;
        }
        if (candidate.executionSourceObjectIds && candidate.executionSourceObjectIds.length > 0) {
            return candidate.executionSourceObjectIds;
        }
        return candidate.sourceObjectIds || [];
    }

    function _normalizeMasterClusterMatchSourceIds(sourceIds, hiddenTextSourceIds) {
        if (!sourceIds || sourceIds.length === 0) return [];
        var normalized = sourceIds.slice(0);
        if (hiddenTextSourceIds && hiddenTextSourceIds.length > 0) {
            normalized = _removeSourceIds(normalized, hiddenTextSourceIds);
        }
        return normalized;
    }

    function _findMasterCandidateForSources(pageIndex, sourceIds, hiddenTextSourceIds, allowSuperset) {
        if (pageIndex === null || pageIndex === undefined || pageIndex < 0) return null;
        if (!sourceIds || sourceIds.length === 0) return null;
        if (allowSuperset === undefined || allowSuperset === null) allowSuperset = true;
        var matchSourceIds = _normalizeMasterClusterMatchSourceIds(sourceIds, hiddenTextSourceIds);
        if (!matchSourceIds || matchSourceIds.length === 0) matchSourceIds = sourceIds;
        var list = masterCandidatesByPage[String(pageIndex)] || [];
        var bestSuperset = null;
        var bestSupersetSize = 999999;
        for (var i = 0; i < list.length; i++) {
            var candidate = list[i];
            var candidateSourceIds = _masterCandidateComparableSourceIds(candidate);
            if (!candidate || !candidateSourceIds || candidateSourceIds.length === 0) continue;
            if (_sameSourceSet(candidateSourceIds, sourceIds)) {
                return _candidateMatch(candidate, "candidate_source_set_direct");
            }
            if (_sameSourceSet(candidateSourceIds, matchSourceIds)) {
                return _candidateMatch(candidate, "candidate_source_set_without_hidden_master_text");
            }
            // Master composite candidates are declared from the applied master
            // source graph. During export, a visible cluster can be a strict
            // subset of that declared graph because only items intersecting
            // the concrete master page side are overridden/exported. This is
            // still an execution of the Stage 1 candidate, not new ownership.
            if (allowSuperset
                    && candidate.composite === true
                    && _sourceSetContainsAll(candidateSourceIds, matchSourceIds)
                    && candidateSourceIds.length < bestSupersetSize) {
                bestSuperset = candidate;
                bestSupersetSize = candidateSourceIds.length;
            }
        }
        if (bestSuperset) {
            return _candidateMatch(bestSuperset, "candidate_source_set_contains_master_cluster");
        }
        return null;
    }

    function _findInlineCandidateForMasterDirectSources(pageIndex, sourceIds) {
        if (pageIndex === null || pageIndex === undefined || pageIndex < 0) return null;
        if (!sourceIds || sourceIds.length === 0) return null;
        var list = inlineCandidatesByPage[String(pageIndex)] || [];
        var best = null;
        var bestSize = 999999;
        for (var i = 0; i < list.length; i++) {
            var candidate = list[i];
            if (!candidate || !candidate.sourceObjectIds || candidate.sourceObjectIds.length === 0) continue;
            if (!_sourceSetContainsAll(sourceIds, candidate.sourceObjectIds)) continue;
            var candidateSize = candidate.sourceObjectIds.length;
            if (candidateSize < bestSize) {
                best = candidate;
                bestSize = candidateSize;
            }
        }
        return best ? _candidateMatch(best, "candidate_inline_source_set_contained_by_master_direct") : null;
    }

    function _appliedMasterSnapshotCandidate(pageIndex) {
        if (pageIndex === null || pageIndex === undefined || pageIndex < 0) return null;
        var list = masterCandidatesByPage[String(pageIndex)] || [];
        if (!list || list.length === 0) return null;
        var best = null;
        var bestScore = -999999;
        for (var i = 0; i < list.length; i++) {
            var candidate = list[i];
            if (!candidate) continue;
            var ids = _masterCandidateComparableSourceIds(candidate);
            if (!ids || ids.length === 0) continue;
            var score = ids.length;
            if (candidate.slotRole === "page_background_plane"
                    || candidate.compositeRole === "page_background_plane") score += 100000;
            if (candidate.passId === "pass.master_page_graphics") score += 10000;
            if (candidate.composite === true) score += 1000;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best ? _candidateMatch(best, "applied_master_snapshot_candidate") : null;
    }

    function _isTopLevelPageLocalItem(item, page) {
        try {
            if (!item || !page) return false;
            try {
                var parentPage = item.parentPage;
                if (!parentPage || parentPage.id !== page.id) return false;
            } catch (eParentPage) {
                return false;
            }
            var cur = item.parent;
            var guard = 0;
            while (cur && guard++ < 16) {
                var kind = "";
                try { kind = cur.constructor.name; } catch (eKind) { break; }
                if (kind === "Page") {
                    try { return cur.id === page.id; } catch (ePageId) { return false; }
                }
                if (kind === "Spread") return true;
                if (kind === "Document" || kind === "MasterSpread") return false;
                if (kind === "Group" || kind === "TextFrame" || kind === "Rectangle"
                        || kind === "Oval" || kind === "Polygon" || kind === "GraphicLine") {
                    return false;
                }
                try { cur = cur.parent; } catch (eParent) { break; }
            }
        } catch (e) {}
        return false;
    }

    function _hidePageLocalItemsForAppliedMasterSnapshot(page) {
        var saved = [];
        try {
            var items = page.allPageItems;
            for (var i = 0; items && i < items.length; i++) {
                try {
                    var item = items[i];
                    if (!_isTopLevelPageLocalItem(item, page)) continue;
                    var wasVisible = true;
                    try { wasVisible = item.visible !== false; } catch (eVisible) { wasVisible = true; }
                    if (!wasVisible) continue;
                    saved.push({ item: item, visible: wasVisible });
                    item.visible = false;
                } catch (eItem) {}
            }
        } catch (e) {}
        return saved;
    }

    function _restorePageLocalItemsForAppliedMasterSnapshot(saved) {
        for (var i = 0; saved && i < saved.length; i++) {
            try {
                if (saved[i] && saved[i].item && saved[i].item.isValid) {
                    saved[i].item.visible = saved[i].visible;
                }
            } catch (e) {}
        }
    }

    function _exportAppliedMasterSnapshotPage(page, file) {
        if (!page || !file) return false;
        var ok = false;
        try {
            page.exportFile(ExportFormat.PNG_FORMAT, file, false);
            ok = file.exists;
        } catch (ePageExport) {
            dbg("  appliedMasterSnapshot page.exportFile failed: " + ePageExport);
        }
        if (ok) return true;

        var savedPageString;
        var hadPageString = false;
        try {
            savedPageString = app.pngExportPreferences.pageString;
            hadPageString = true;
            app.pngExportPreferences.pageString = String(page.name);
            doc.exportFile(ExportFormat.PNG_FORMAT, file, false);
            ok = file.exists;
        } catch (eDocExport) {
            dbg("  appliedMasterSnapshot doc.exportFile failed page=" + page.name + ": " + eDocExport);
        } finally {
            try {
                if (hadPageString) app.pngExportPreferences.pageString = savedPageString;
            } catch (eRestorePageString) {}
        }
        return ok;
    }

    function _tryCreateAppliedMasterSnapshotResult(pgEntry, msId, masterPageIdx) {
        // Do not materialize an applied-page snapshot as a master graphic.
        //
        // Stage 1 candidates name the exact source bundle to execute. Exporting
        // the concrete document page here (`page.exportFile`) can capture
        // page-local graphics that are not part of that master source bundle,
        // producing duplicate page backgrounds/content. Master graphics are
        // therefore emitted only through the source-scoped master cluster/direct
        // exports below.
        return null;
        try {
            if (!pgEntry || pgEntry.docIdx === null || pgEntry.docIdx === undefined) return null;
            var candidateMatch = _appliedMasterSnapshotCandidate(pgEntry.docIdx);
            if (!candidateMatch || !candidateMatch.candidate) return null;
            var page = doc.pages[pgEntry.docIdx];
            if (!page) return null;
            var pageBounds = null;
            try { pageBounds = arrCopy(page.bounds); } catch (eBounds) {}
            if (!pageBounds || pageBounds.length < 4) return null;

            var fileName = "master_applied_" + String(msId) + "_" +
                    String(masterPageIdx) + "_pi" + String(pgEntry.docIdx) + ".png";
            var outFile = File(renderDir + "/" + fileName);
            var hidden = _hidePageLocalItemsForAppliedMasterSnapshot(page);
            var hiddenMasterText = [];
            try {
                try { hiddenMasterText = _hideMasterTextlessTextContent(page.appliedMaster); } catch (eHideMaster) {}
                if (!_exportAppliedMasterSnapshotPage(page, outFile)) return null;
            } finally {
                try { restoreTextFrames(hiddenMasterText); } catch (eRestoreMaster) {}
                _restorePageLocalItemsForAppliedMasterSnapshot(hidden);
            }
            if (!outFile.exists || outFile.length <= 0) return null;

            var candidate = candidateMatch.candidate;
            var sourceIds = (candidate.sourceObjectIds && candidate.sourceObjectIds.length > 0)
                    ? candidate.sourceObjectIds
                    : _masterCandidateComparableSourceIds(candidate);
            var exportIds = (candidate.exportSourceObjectIds && candidate.exportSourceObjectIds.length > 0)
                    ? candidate.exportSourceObjectIds
                    : sourceIds;
            var relBounds = [
                0,
                0,
                Number(pageBounds[2]) - Number(pageBounds[0]),
                Number(pageBounds[3]) - Number(pageBounds[1])
            ];
            dbg("  appliedMasterSnapshot result docIdx=" + pgEntry.docIdx +
                " rel=[" + relBounds.join(",") + "] file=" + fileName);
            var resultEntry = applyRenderOwnership({
                id: parseInt(msId, 10) * 100000 + pgEntry.docIdx,
                candidateId: candidateMatch.candidateId,
                candidateMatchStrategy: candidateMatch.strategy,
                file: "rendered_frames/" + fileName,
                bounds: relBounds,
                pageIndex: pgEntry.docIdx,
                zOrder: MASTER_PAGE_GRAPHICS_PLANE_Z_ORDER,
                isMasterGraphic: true,
                type: "page_object",
                placement: "FLOATING",
                coordinateSpace: "PAGE",
                planPassId: "pass.master_page_graphics",
                slotRole: candidate.slotRole || "page_background_plane",
                compositeRole: candidate.compositeRole || "page_background_plane",
                visualLayer: candidate.visualLayer || "PAGE_BACKGROUND",
                layerIndex: candidate.sourceLayerIndex !== undefined && candidate.sourceLayerIndex !== null
                        ? candidate.sourceLayerIndex
                        : 0
            }, null, {
                sourceObjectIds: sourceIds,
                exportSourceObjectIds: exportIds,
                editableTextFrameIds: [],
                textFrameIds: [],
                hiddenTextFrameIds: [],
                textHiddenBeforeExport: false,
                textOwner: "none",
                containsText: false,
                containsEditableText: false,
                placementAllowed: true,
                reason: "applied_master_snapshot"
            });
            _copyMasterMatchedCandidateContract(resultEntry, candidate);
            return resultEntry;
        } catch (e) {
            dbg("  appliedMasterSnapshot result error: " + e);
        }
        return null;
    }

    function _isDynamicMasterTextFrame(tf) {
        try {
            var story = tf.parentStory;
            if (!story) return false;
            try {
                if (String(story.contents).indexOf("\u0018") >= 0) return true; // auto page number
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

    function _isMasterTextlessTextFrame(tf) {
        try {
            if (!tf || tf.constructor.name !== "TextFrame") return false;
            // Applied-master visual planes are PNG-owned. Static master labels
            // such as section-end badges are part of the master graphic, even
            // when InDesign represents the lettering as a TextFrame. Hide only
            // dynamic text variables such as page numbers, which are emitted by
            // the HWPX text/page-number path.
            return _isDynamicMasterTextFrame(tf);
        } catch (e) {}
        return false;
    }

    function _hideMasterTextlessTextContent(renderTarget) {
        var saved = [];
        function hideOne(tf) {
            try {
                if (!_isMasterTextlessTextFrame(tf)) return;
                var hidden = hideOneTextFrameContent(tf);
                if (hidden) saved.push(hidden);
            } catch (e) {}
        }
        try {
            if (renderTarget.constructor.name === "TextFrame") hideOne(renderTarget);
        } catch (e) {}
        try {
            var nested = renderTarget.allPageItems;
            for (var hi = 0; hi < nested.length; hi++) {
                try {
                    if (nested[hi].constructor.name === "TextFrame") hideOne(nested[hi]);
                } catch (e2) {}
            }
        } catch (e3) {}
        return saved;
    }

    function _collectMasterTextlessTextFrameIds(entries) {
        var ids = [], seen = {};
        function collectOne(tf) {
            try {
                if (!_isMasterTextlessTextFrame(tf)) return;
                _pushUniqueId(ids, seen, tf.id);
            } catch (e) {}
        }
        for (var ei = 0; entries && ei < entries.length; ei++) {
            try {
                var item = entries[ei].item;
                if (!item) continue;
                if (item.constructor && item.constructor.name === "TextFrame") collectOne(item);
                var nested = item.allPageItems;
                for (var ni = 0; ni < nested.length; ni++) {
                    try {
                        if (nested[ni].constructor.name === "TextFrame") collectOne(nested[ni]);
                    } catch (eNested) {}
                }
            } catch (eEntry) {}
        }
        return ids;
    }

    function _pageCloneTextFrameIds(masterTextFrameIds, docPageIndex) {
        var out = [];
        for (var i = 0; masterTextFrameIds && i < masterTextFrameIds.length; i++) {
            var id = masterTextFrameIds[i];
            if (id === null || id === undefined) continue;
            out.push(String(id) + "_pi" + docPageIndex);
        }
        return out;
    }

    for (var ms = 0; ms < msArr.length; ms++) {
        var mspread = msArr[ms];
        var msId = "";
        try { msId = mspread.id.toString(); } catch (e) { continue; }
        if (!masterToPages[msId] || masterToPages[msId].length === 0) continue;

        dbg("masterSpread id=" + msId);

        var masterGraphicItems = [];
        try {
            var msAllItems = mspread.allPageItems;
            dbg("  mspread.allPageItems: " + msAllItems.length);
            for (var mti = 0; mti < msAllItems.length; mti++) {
                try {
                    var mItem = msAllItems[mti];
                    if (!_isTopLevelMasterItem(mItem)) continue;
                    masterGraphicItems.push(mItem);
                } catch (e) {}
            }
        } catch (e) { dbg("  ERROR allPageItems: " + e); }
        dbg("  masterTopLevelItems: " + masterGraphicItems.length);
        if (masterGraphicItems.length === 0) continue;

        // 마스터 스프레드 내 각 페이지별로 아이템 분리 → 페이지별 PNG export
        var mspreadPagesCount = 0;
        try { mspreadPagesCount = mspread.pages.length; } catch (e) {}
        if (mspreadPagesCount === 0) continue;

        for (var mpIdx = 0; mpIdx < mspreadPagesCount; mpIdx++) {
            var masterKey = msId + "_" + mpIdx;
            if (exportedMasterByPage[masterKey] !== undefined) continue;

            var mpBnds = null;
            var masterPage = null;
            try { masterPage = mspread.pages[mpIdx]; } catch (eMasterPage) { masterPage = null; }
            try { mpBnds = masterPage ? masterPage.bounds : null; } catch (e) { continue; }
            if (!mpBnds || mpBnds.length < 4) continue;
            dbg("  masterPage[" + mpIdx + "] bounds=[" + mpBnds.join(",") + "]");

            // 이 마스터 페이지에 실제로 보이는 top-level item을 포함한다.
            // 큰 스프레드/배경 객체는 visible intersection이 ownership 기준이다.
            // pad 기반 교차는 bleed가 있는 작은 edge decoration에만 허용한다.
            var pageItems = [];
            for (var pii = 0; pii < masterGraphicItems.length; pii++) {
                try {
                    var pib = _masterItemBounds(masterGraphicItems[pii]);
                    var itemOnMasterSide = _masterItemBelongsToMasterSide(masterGraphicItems[pii], masterPage);
                    if (pib && (itemOnMasterSide || _masterItemBelongsToPage(pib, mpBnds))) {
                        // InDesign's master allPageItems order is part of the
                        // source depth evidence. Earlier entries are visually
                        // above later master background faces, so convert the
                        // order into an HWPX-increasing z depth once here.
                        _appendMasterPageEntries(
                            pageItems,
                            masterGraphicItems[pii],
                            pib,
                            mpBnds,
                            masterGraphicItems.length - 1 - pii,
                            masterPage
                        );
                    }
                } catch (e) {}
            }
            dbg("  masterPage[" + mpIdx + "] items: " + pageItems.length);
            if (pageItems.length === 0) { exportedMasterByPage[masterKey] = null; continue; }

            for (var li = 0; li < pageItems.length; li++) {
                try {
                    var lb = pageItems[li].bounds;
                    var la = Math.abs((lb[2] - lb[0]) * (lb[3] - lb[1]));
                    dbg("  item[" + li + "] " + pageItems[li].item.constructor.name +
                        " bounds=[" + lb.join(",") + "] area=" + la.toFixed(1));
                } catch (eLi) {}
            }

            var clusters = _clusterMasterPageItems(pageItems);
            dbg("  masterPage[" + mpIdx + "] clusters: " + clusters.length);
            var exportedClusters = [];
            for (var ci = 0; ci < clusters.length; ci++) {
                var clusterItems = clusters[ci];
                var clusterSourceIds = _collectIdsForMasterItems(clusterItems);
                var hiddenTextSourceIds = _collectMasterTextlessTextFrameIds(clusterItems);
                var plannedOnAppliedPage = false;
                var appliedPages = masterToPages[msId] || [];
                for (var api = 0; api < appliedPages.length; api++) {
                    if (appliedPages[api].masterPageIdx !== mpIdx) continue;
                    if (_findMasterCandidateForSources(
                            appliedPages[api].docIdx,
                            clusterSourceIds,
                            hiddenTextSourceIds)) {
                        plannedOnAppliedPage = true;
                        break;
                    }
                }
                if (!plannedOnAppliedPage) {
                    dbg("  SKIP cluster[" + ci + "]: no applied-page master composite candidate for sources=[" +
                        clusterSourceIds.join(",") + "]");
                    continue;
                }
                var mFileName = "master_" + msId + "_" + mpIdx + "_" + ci + ".png";
                var mOutFile = File(renderDir + "/" + mFileName);
                var tempPage = null;
                var exportTarget = null;
                var overriddenItems = [];
                var overriddenSourceEntries = [];
                var directSourceEntries = [];
                var hiddenText = [];
                var grpBnds = null;
                var originalClusterBnds = _unionMasterEntryBounds(clusterItems);
                try {
                    tempPage = doc.pages.add(LocationOptions.AT_END);
                    try { tempPage.appliedMaster = mspread; } catch (eAM) {}
                    for (var oi = 0; oi < clusterItems.length; oi++) {
                        try {
                            var ov = clusterItems[oi].item.override(tempPage);
                            if (ov) {
                                overriddenItems.push(ov);
                                overriddenSourceEntries.push(clusterItems[oi]);
                            }
                        } catch (eOv) {
                            dbg("    override fail itemId=" + clusterItems[oi].item.id + ": " + eOv);
                            directSourceEntries.push(clusterItems[oi]);
                        }
                    }
                    if (overriddenItems.length === 0) {
                        dbg("  FAIL cluster[" + ci + "]: no overridden master items");
                    } else {
                        if (overriddenItems.length === 1) {
                            exportTarget = overriddenItems[0];
                        } else {
                            try { exportTarget = doc.groups.add(overriddenItems); } catch (eGrp) {
                                dbg("  group error: " + eGrp);
                                exportTarget = overriddenItems[0];
                            }
                        }

                        hiddenText = _hideMasterTextlessTextContent(exportTarget);
                        try { exportTarget.exportFile(ExportFormat.PNG_FORMAT, mOutFile, false); } catch (eExp) {
                            dbg("  export error: " + eExp);
                        }
                        try { grpBnds = arrCopy(exportTarget.visibleBounds); } catch (eVB) {}
                        if (!grpBnds) try { grpBnds = arrCopy(exportTarget.geometricBounds); } catch (eGB) {}
                    }
                } catch (eOuter) {
                    dbg("  composed export error: " + eOuter);
                } finally {
                    try { restoreTextFrames(hiddenText); } catch (eRestore) {}
                    try { if (tempPage) tempPage.remove(); } catch (eRm) {}
                }
                if (mOutFile.exists && grpBnds) {
                    dbg("  SUCCESS: " + mFileName + " (" + mOutFile.length + " bytes)");
                    // Override용 임시 페이지는 문서 끝에 추가되며 page origin이 실제 master side와 다르다.
                    // exportTarget bounds에서 tempPage.bounds를 빼면 모든 master graphic이 임시 페이지
                    // offset만큼 위/왼쪽으로 밀린다. 배치 좌표는 원본 master item cluster bounds를
                    // master page bounds 기준으로 계산해야 한다.
                    var exportedOriginalBnds = _unionMasterEntryBounds(overriddenSourceEntries) || originalClusterBnds;
                    var placementBnds = exportedOriginalBnds || grpBnds;
                    var sourceAspect = _boundsAspect(exportedOriginalBnds);
                    var exportAspect = _boundsAspect(grpBnds);
                    if (exportedOriginalBnds && grpBnds && sourceAspect > 0 && exportAspect > 0) {
                        var aspectDelta = Math.abs(sourceAspect - exportAspect) / exportAspect;
                        if (aspectDelta > 0.12) {
                            placementBnds = _boundsAspectFitAtSourceAnchor(exportedOriginalBnds, grpBnds);
                            dbg("  aspect-adjust cluster[" + ci + "]: sourceAspect=" + sourceAspect.toFixed(3) +
                                " exportAspect=" + exportAspect.toFixed(3) +
                                " source=[" + exportedOriginalBnds.join(",") + "]" +
                                " fitted=[" + placementBnds.join(",") + "]");
                        }
                    }
                    var unclampedPlacementBnds = placementBnds;
                    placementBnds = _clampSmallMasterDecorationInsidePage(placementBnds, mpBnds);
                    if (placementBnds !== unclampedPlacementBnds) {
                        dbg("  bleed-clamp cluster[" + ci + "]: from=[" + unclampedPlacementBnds.join(",") +
                            "] to=[" + placementBnds.join(",") + "]");
                    }
                    var relBase = mpBnds;
                    var cropSourceBnds = placementBnds;
                    var visiblePlacementBnds = _boundsIntersection(placementBnds, mpBnds);
                    if (!visiblePlacementBnds) {
                        dbg("  SKIP cluster[" + ci + "]: no visible master page intersection after placement");
                    } else {
                        var exportedCluster = {
                            relPath:   "rendered_frames/" + mFileName,
                            relTop:    visiblePlacementBnds[0] - relBase[0],
                            relLeft:   visiblePlacementBnds[1] - relBase[1],
                            relBottom: visiblePlacementBnds[2] - relBase[0],
                            relRight:  visiblePlacementBnds[3] - relBase[1],
                            sourceObjectIds: _collectIdsForMasterItems(overriddenSourceEntries),
                            sourceZOrder: _masterEntrySourceDepth(overriddenSourceEntries)
                        };
                        if (hiddenTextSourceIds.length > 0) {
                            exportedCluster.hiddenTextFrameIds = hiddenTextSourceIds;
                        }
                        if (_boundsDiffer(cropSourceBnds, visiblePlacementBnds, 0.01)) {
                            exportedCluster.cropSourceBounds = [
                                cropSourceBnds[0] - relBase[0],
                                cropSourceBnds[1] - relBase[1],
                                cropSourceBnds[2] - relBase[0],
                                cropSourceBnds[3] - relBase[1]
                            ];
                        }
                        _copyMasterEntryLayerInfo(
                            exportedCluster,
                            _primaryMasterLayerEntry(overriddenSourceEntries)
                        );
                        exportedClusters.push(exportedCluster);
                        dbg("  relBounds[" + ci + "]: ["+exportedCluster.relTop+","+exportedCluster.relLeft+
                            ","+exportedCluster.relBottom+","+exportedCluster.relRight+"]" +
                            (exportedCluster.cropSourceBounds ? " cropSource=[" + exportedCluster.cropSourceBounds.join(",") + "]" : ""));
                    }
                } else {
                    if (!mOutFile.exists) dbg("  FAIL: " + mFileName + " not created");
                    if (!grpBnds) dbg("  FAIL: no composed bounds");
                }
                if (directSourceEntries.length > 0) {
                    for (var foi = 0; foi < directSourceEntries.length; foi++) {
                        var directEntry = directSourceEntries[foi];
                        var directBounds = directEntry ? directEntry.bounds : null;
                        var directVisibleBounds = _boundsIntersection(directBounds, mpBnds);
                        if (!directVisibleBounds) continue;
                        var directSourceIds = _collectIdsForMasterItems([directEntry]);
                        if (!directSourceIds || directSourceIds.length === 0) continue;

                        var directName = "master_" + msId + "_" + mpIdx + "_" + ci + "_direct_" + foi + ".png";
                        var directFile = File(renderDir + "/" + directName);
                        var directHiddenText = [];
                        var directHiddenTextSourceIds = _collectMasterTextlessTextFrameIds([directEntry]);
                        try {
                            directHiddenText = _hideMasterTextlessTextContent(directEntry.item);
                            directEntry.item.exportFile(ExportFormat.PNG_FORMAT, directFile, false);
                        } catch (eDirectExp) {
                            dbg("    direct master export fail itemId=" + directEntry.item.id + ": " + eDirectExp);
                        } finally {
                            try { restoreTextFrames(directHiddenText); } catch (eDirectRestore) {}
                        }
                        if (!directFile.exists) continue;

                        var directCluster = {
                            relPath:   "rendered_frames/" + directName,
                            relTop:    directVisibleBounds[0] - mpBnds[0],
                            relLeft:   directVisibleBounds[1] - mpBnds[1],
                            relBottom: directVisibleBounds[2] - mpBnds[0],
                            relRight:  directVisibleBounds[3] - mpBnds[1],
                            sourceObjectIds: directSourceIds,
                            sourceZOrder: directEntry.sourceDepthOrder,
                            masterDirectChildCluster: true
                        };
                        if (directHiddenTextSourceIds.length > 0) {
                            directCluster.hiddenTextFrameIds = directHiddenTextSourceIds;
                        }
                        if (!_boundsHasVisibleArea([
                                directCluster.relTop,
                                directCluster.relLeft,
                                directCluster.relBottom,
                                directCluster.relRight
                            ])) {
                            dbg("  SKIP directMaster[" + ci + "." + foi + "]: no page-local visible area");
                            continue;
                        }
                        if (_boundsDiffer(directBounds, directVisibleBounds, 0.01)) {
                            directCluster.cropSourceBounds = [
                                directBounds[0] - mpBnds[0],
                                directBounds[1] - mpBnds[1],
                                directBounds[2] - mpBnds[0],
                                directBounds[3] - mpBnds[1]
                            ];
                        }
                        _copyMasterEntryLayerInfo(directCluster, directEntry);
                        exportedClusters.push(directCluster);
                        dbg("  directMaster[" + ci + "." + foi + "]: " + directName +
                            " rel=[" + directCluster.relTop + "," + directCluster.relLeft +
                            "," + directCluster.relBottom + "," + directCluster.relRight + "]" +
                            (directCluster.cropSourceBounds ? " cropSource=[" + directCluster.cropSourceBounds.join(",") + "]" : ""));
                    }
                }
            }
            exportedMasterByPage[masterKey] = exportedClusters.length > 0 ? exportedClusters : null;
        }
    }

    // PNG 설정 복원
    try { if (savedRes !== undefined) app.pngExportPreferences.exportResolution = savedRes; } catch (e) {}
    try { if (savedTransp !== undefined) app.pngExportPreferences.transparentBackground = savedTransp; } catch (e) {}
    try { if (savedSpread !== undefined) app.pngExportPreferences.exportingSpread = savedSpread; } catch (e) {}

    // 결과 생성: 각 콘텐츠 페이지 × 해당 마스터 페이지 인덱스
    for (var msId2 in masterToPages) {
        if (!masterToPages.hasOwnProperty(msId2)) continue;
        var pgList = masterToPages[msId2];
        for (var pli = 0; pli < pgList.length; pli++) {
            var pgEntry = pgList[pli];
            var masterKey2 = msId2 + "_" + pgEntry.masterPageIdx;
            var masters = exportedMasterByPage[masterKey2];
            if (!masters || masters.length === 0) continue;
            var appliedMasterSnapshot = _tryCreateAppliedMasterSnapshotResult(
                    pgEntry, msId2, pgEntry.masterPageIdx);
            if (appliedMasterSnapshot) {
                results.push(appliedMasterSnapshot);
                continue;
            }
            for (var mi = 0; mi < masters.length; mi++) {
                var master = masters[mi];
                if (!master) continue;
                if (!_boundsHasVisibleArea([
                        master.relTop,
                        master.relLeft,
                        master.relBottom,
                        master.relRight
                    ])) continue;
                var isDirectMasterFragment = master.masterDirectChildCluster === true;
                var inlineCandidateMatch = isDirectMasterFragment
                        ? _findInlineCandidateForMasterDirectSources(
                                pgEntry.docIdx,
                                master.sourceObjectIds || [parseInt(msId2, 10)])
                        : null;
                var masterCandidateMatch = inlineCandidateMatch || _findMasterCandidateForSources(
                        pgEntry.docIdx,
                        master.sourceObjectIds || [parseInt(msId2, 10)],
                        master.hiddenTextFrameIds || [],
                        true);
                if (!masterCandidateMatch) {
                    dbg("  SKIP result docIdx=" + pgEntry.docIdx + " masterPageIdx=" + pgEntry.masterPageIdx +
                        " cluster=" + mi + ": no applied-page candidate at result creation");
                    continue;
                }
                var matchedCandidate = masterCandidateMatch ? (masterCandidateMatch.candidate || null) : null;
                var matchedInline = inlineCandidateMatch && matchedCandidate;
                var resultSourceObjectIds = isDirectMasterFragment
                        ? (master.sourceObjectIds || [parseInt(msId2, 10)])
                        : (matchedCandidate && matchedCandidate.sourceObjectIds
                        && matchedCandidate.sourceObjectIds.length > 0
                        ? matchedCandidate.sourceObjectIds
                        : (master.sourceObjectIds || [parseInt(msId2, 10)]));
                var resultExportSourceObjectIds = isDirectMasterFragment
                        ? resultSourceObjectIds
                        : (matchedCandidate && matchedCandidate.exportSourceObjectIds
                        && matchedCandidate.exportSourceObjectIds.length > 0
                        ? matchedCandidate.exportSourceObjectIds
                        : resultSourceObjectIds);
                dbg("  result docIdx=" + pgEntry.docIdx + " masterPageIdx=" + pgEntry.masterPageIdx +
                    " cluster=" + mi +
                    " rel=["+master.relTop+","+master.relLeft+","+master.relBottom+","+master.relRight+"]");
                var pageTextFrameIds = _pageCloneTextFrameIds(master.hiddenTextFrameIds, pgEntry.docIdx);
                var localSlotRole = matchedInline
                        ? (matchedCandidate.slotRole || matchedCandidate.ownershipSlot || "CONTENT_VISUAL_SLOT")
                        : (pageTextFrameIds.length > 0 ? "TEXTLESS_SHELL_SLOT" : "CONTENT_VISUAL_SLOT");
                var resultEntry = applyRenderOwnership({
                    id: parseInt(msId2, 10) * 100 + mi,
                    candidateId: masterCandidateMatch ? masterCandidateMatch.candidateId : null,
                    candidateMatchStrategy: masterCandidateMatch ? masterCandidateMatch.strategy : null,
                    file: master.relPath,
                    bounds: [master.relTop, master.relLeft, master.relBottom, master.relRight],
                    cropSourceBounds: master.cropSourceBounds,
                    inlineAnchorSourceObjectId: master.inlineAnchorSourceObjectId,
                    inlineSourceTreeClosed: master.inlineSourceTreeClosed === true,
                    pageIndex: pgEntry.docIdx,
                    zOrder: matchedInline
                            ? (master.sourceZOrder !== null && master.sourceZOrder !== undefined
                                ? master.sourceZOrder
                                : mi)
                            : MASTER_PAGE_GRAPHICS_PLANE_Z_ORDER,
                    isMasterGraphic: !matchedInline,
                    type: matchedInline ? "inline_object" : "page_object",
                    placementRole: matchedInline ? "inline_object" : null,
                    placement: matchedInline ? "INLINE" : "FLOATING",
                    coordinateSpace: matchedInline ? "STORY_FLOW" : "PAGE",
                    planPassId: matchedInline ? "pass.inline_objects" : "pass.master_page_graphics",
                    slotRole: localSlotRole
                }, null, {
                    sourceObjectIds: resultSourceObjectIds,
                    exportSourceObjectIds: resultExportSourceObjectIds,
                    editableTextFrameIds: matchedInline ? [] : pageTextFrameIds,
                    textFrameIds: matchedInline ? [] : pageTextFrameIds,
                    hiddenTextFrameIds: matchedInline ? [] : pageTextFrameIds,
                    textHiddenBeforeExport: matchedInline ? false : pageTextFrameIds.length > 0,
                    textOwner: matchedInline ? "none" : (pageTextFrameIds.length > 0 ? "hwpx_tf" : "none"),
                    containsText: false,
                    containsEditableText: false,
                    placementAllowed: true,
                    reason: matchedInline
                            ? "inline_graphic_only"
                            : (pageTextFrameIds.length > 0
                            ? "master_graphic_textless"
                            : "master_graphic")
                });
                if (master.layerId !== undefined && master.layerId !== null) resultEntry.layerId = master.layerId;
                if (master.layerName !== undefined && master.layerName !== null) resultEntry.layerName = master.layerName;
                if (master.layerIndex !== undefined && master.layerIndex !== null) resultEntry.layerIndex = master.layerIndex;
                if (matchedCandidate) {
                    _copyMasterMatchedCandidateContract(resultEntry, matchedCandidate);
                }
                results.push(resultEntry);
            }
        }
    }

    dbg("DONE: " + results.length + " master graphic entries");
    if (debugLog) try { debugLog.close(); } catch (e) {}
    $.writeln("[exportMasterPageGraphics] " + results.length + " entries (v13 composed-master-page)");
    return results;
}
