/**
 * Master page/applied page graphic render executor.
 *
 * This module executes planned master graphic extraction only.
 * It must not create ownership, placement, or fallback decisions.
 */

function exportMasterPageGraphics(doc, outputDir, startPage, endPage, masterCandidates) {
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
                        if (_boundsIntersect(cluster[k].bounds, pageItems[j].bounds, pad)) {
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

    function _findMasterCandidateForSources(pageIndex, sourceIds) {
        if (pageIndex === null || pageIndex === undefined || pageIndex < 0) return null;
        if (!sourceIds || sourceIds.length === 0) return null;
        var list = masterCandidatesByPage[String(pageIndex)] || [];
        var bestSuperset = null;
        var bestSupersetSize = 999999;
        for (var i = 0; i < list.length; i++) {
            var candidate = list[i];
            if (!candidate || !candidate.sourceObjectIds || candidate.sourceObjectIds.length === 0) continue;
            if (_sameSourceSet(candidate.sourceObjectIds, sourceIds)) {
                return _candidateMatch(candidate, "candidate_source_set_direct");
            }
            // Master composite candidates are declared from the applied master
            // source graph. During export, a visible cluster can be a strict
            // subset of that declared graph because only items intersecting
            // the concrete master page side are overridden/exported. This is
            // still an execution of the Stage 1 candidate, not new ownership.
            if (candidate.composite === true
                    && _sourceSetContainsAll(candidate.sourceObjectIds, sourceIds)
                    && candidate.sourceObjectIds.length < bestSupersetSize) {
                bestSuperset = candidate;
                bestSupersetSize = candidate.sourceObjectIds.length;
            }
        }
        if (bestSuperset) {
            return _candidateMatch(bestSuperset, "candidate_source_set_contains_master_cluster");
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
            if (_isDynamicMasterTextFrame(tf)) return true;
            return _textFrameHasContent(tf);
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
            try { mpBnds = mspread.pages[mpIdx].bounds; } catch (e) { continue; }
            if (!mpBnds || mpBnds.length < 4) continue;
            dbg("  masterPage[" + mpIdx + "] bounds=[" + mpBnds.join(",") + "]");

            // 이 마스터 페이지에 실제로 보이는 top-level item을 포함한다.
            // 큰 스프레드/배경 객체는 visible intersection이 ownership 기준이다.
            // pad 기반 교차는 bleed가 있는 작은 edge decoration에만 허용한다.
            var pageItems = [];
            for (var pii = 0; pii < masterGraphicItems.length; pii++) {
                try {
                    var pib = _masterItemBounds(masterGraphicItems[pii]);
                    if (pib && _masterItemBelongsToPage(pib, mpBnds)) {
                        // InDesign's master allPageItems order is part of the
                        // source depth evidence. Earlier entries are visually
                        // above later master background faces, so convert the
                        // order into an HWPX-increasing z depth once here.
                        pageItems.push({
                            item: masterGraphicItems[pii],
                            bounds: pib,
                            sourceDepthOrder: masterGraphicItems.length - 1 - pii
                        });
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
                var plannedOnAppliedPage = false;
                var appliedPages = masterToPages[msId] || [];
                for (var api = 0; api < appliedPages.length; api++) {
                    if (appliedPages[api].masterPageIdx !== mpIdx) continue;
                    if (_findMasterCandidateForSources(appliedPages[api].docIdx, clusterSourceIds)) {
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
                var hiddenTextSourceIds = _collectMasterTextlessTextFrameIds(clusterItems);
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
                            sourceZOrder: directEntry.sourceDepthOrder
                        };
                        var directInlineAnchorSourceId = _directInlineAnchorSourceId(directEntry, directSourceIds);
                        if (directInlineAnchorSourceId !== null && directInlineAnchorSourceId !== undefined) {
                            directCluster.inlineAnchorSourceObjectId = directInlineAnchorSourceId;
                            directCluster.inlineSourceTreeClosed = true;
                        }
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
            for (var mi = 0; mi < masters.length; mi++) {
                var master = masters[mi];
                if (!master) continue;
                if (!_boundsHasVisibleArea([
                        master.relTop,
                        master.relLeft,
                        master.relBottom,
                        master.relRight
                    ])) continue;
                var masterCandidateMatch = _findMasterCandidateForSources(
                        pgEntry.docIdx,
                        master.sourceObjectIds || [parseInt(msId2, 10)]);
                if (!masterCandidateMatch) {
                    dbg("  SKIP result docIdx=" + pgEntry.docIdx + " masterPageIdx=" + pgEntry.masterPageIdx +
                        " cluster=" + mi + ": no applied-page candidate at result creation");
                    continue;
                }
                dbg("  result docIdx=" + pgEntry.docIdx + " masterPageIdx=" + pgEntry.masterPageIdx +
                    " cluster=" + mi +
                    " rel=["+master.relTop+","+master.relLeft+","+master.relBottom+","+master.relRight+"]");
                var pageTextFrameIds = _pageCloneTextFrameIds(master.hiddenTextFrameIds, pgEntry.docIdx);
                results.push(applyRenderOwnership({
                    id: parseInt(msId2, 10) * 100 + mi,
                    candidateId: masterCandidateMatch.candidateId,
                    candidateMatchStrategy: masterCandidateMatch.strategy,
                    file: master.relPath,
                    bounds: [master.relTop, master.relLeft, master.relBottom, master.relRight],
                    cropSourceBounds: master.cropSourceBounds,
                    inlineAnchorSourceObjectId: master.inlineAnchorSourceObjectId,
                    inlineSourceTreeClosed: master.inlineSourceTreeClosed === true,
                    pageIndex: pgEntry.docIdx,
                    zOrder: master.sourceZOrder !== null && master.sourceZOrder !== undefined
                            ? master.sourceZOrder
                            : mi,
                    isMasterGraphic: true
                }, null, {
                    sourceObjectIds: master.sourceObjectIds || [parseInt(msId2, 10)],
                    editableTextFrameIds: pageTextFrameIds,
                    textFrameIds: pageTextFrameIds,
                    hiddenTextFrameIds: pageTextFrameIds,
                    textHiddenBeforeExport: pageTextFrameIds.length > 0,
                    textOwner: pageTextFrameIds.length > 0 ? "hwpx_tf" : "none",
                    containsText: false,
                    containsEditableText: false,
                    placementAllowed: true,
                    reason: pageTextFrameIds.length > 0
                            ? "master_graphic_textless"
                            : "master_graphic"
                }));
            }
        }
    }

    dbg("DONE: " + results.length + " master graphic entries");
    if (debugLog) try { debugLog.close(); } catch (e) {}
    $.writeln("[exportMasterPageGraphics] " + results.length + " entries (v13 composed-master-page)");
    return results;
}
