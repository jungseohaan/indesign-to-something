/*
 * Input preparation utilities for extract_indd.jsx.
 *
 * This module prepares config, page range, page hash data, and links.
 * It must not decide ownership, placement, layer, or materialization.
 */

function loadConversionConfig(configPath) {
    var defaults = {
        rendering: {
            // source ownership policy: 텍스트 이미지 렌더링 제거 (Tier A/B). false면 기존 동작.
            // - masterPageEditable: 조건 5 (item.masterPageItem 오버라이드) → editable
            // - hashiraEditable: 조건 6 (margin + 하시라 paragraph/character style) → editable
            // - rotationEditable: 회전 텍스트 (조건 8.5) → editable + HWPX rotation
            // - nonprintingEditable: 조건 3 (item.nonprinting) → editable. 마스터 헤더 케이스 (frame 3854 등)
            textFrame: { maxTextLength: 30,
                decorativeLargeText: { enabled: true, minFontSize: 16, excludeBlack: true, blackThreshold: 0.90 },
                decorativeStyledText: { enabled: true, maxTextLength: 10, excludeBlack: true, blackThreshold: 0.90, requireObjectStyle: true },
                // source ownership policy 추가 플래그
                // - inlineTextEditable: 조건 7 (isInlineItem) 에서 텍스트 콘텐츠가 있으면 editable 로
                // - groupShortTextEditable: 조건 8 (Group 안 짧은 장식) 도 editable 로 (콘텐츠 텍스트 보존)
                // - oneCharEditable: 조건 11 (≤1자 빈 프레임) 에서 실제 1자가 있으면 editable 로
                spec025: { masterPageEditable: true, hashiraEditable: true, rotationEditable: true,
                    // inlineTextEditable: 인라인 앵커 TextFrame 을 editable 로 승격.
                    // inlineTextMaxLen 이하 짧은 텍스트만 (배지/라벨 케이스). 긴 인라인은 부모 flow 의
                    // ORC embedding 과 중복되므로 background 로 유지.
                    nonprintingEditable: true, inlineTextEditable: true, inlineTextMaxLen: 3,
                    groupShortTextEditable: true, oneCharEditable: true,
                    // 장식 텍스트 (decorativeLargeText, decorativeStyledText) 도 editable 로 — 큰 단원 제목/장 제목이
                    // PNG 가 아닌 검색 가능 텍스트로 변환됨. HWPX 가 큰 폰트/색상/장식 효과를 어느 정도 재현.
                    decorativeLargeTextEditable: true, decorativeStyledTextEditable: true,
                    // 9.5 박스 라벨 (테두리+짧은 텍스트 → renderable) 도 editable 로.
                    boxLabelEditable: true }
            },
            transparency: { opacityThreshold: 100, tintThreshold: 30 },
            rotation: { minAngle: 0.1 },
            pngExportResolution: 220
        },
        extraction: {
            collectFonts: false,
            measureFontMetrics: false,
            collectFontStatus: false
        }
    };
    if (!configPath) { $.writeln("[Config] no configPath"); return defaults; }
    try {
        var f = File(configPath);
        $.writeln("[Config] path: " + f.fsName + " exists=" + f.exists);
        if (!f.exists) return defaults;
        f.encoding = "UTF-8";
        f.open("r");
        var raw = f.read();
        f.close();
        var parsed = JSON.parse(raw);
        // 얕은 병합: 각 섹션 키를 _mergeKeys로 일괄 복사
        if (parsed.rendering) {
            var r = parsed.rendering;
            if (r.pngExportResolution !== undefined)
                defaults.rendering.pngExportResolution = r.pngExportResolution;
            _mergeKeys(defaults.rendering.textFrame, r.textFrame,
                ["maxTextLength"]);
            _mergeKeys(defaults.rendering.textFrame.decorativeLargeText,
                r.textFrame && r.textFrame.decorativeLargeText,
                ["enabled", "minFontSize", "excludeBlack", "blackThreshold"]);
            _mergeKeys(defaults.rendering.textFrame.decorativeStyledText,
                r.textFrame && r.textFrame.decorativeStyledText,
                ["enabled", "maxTextLength", "excludeBlack", "blackThreshold", "requireObjectStyle"]);
            _mergeKeys(defaults.rendering.transparency, r.transparency,
                ["opacityThreshold", "tintThreshold"]);
            _mergeKeys(defaults.rendering.rotation, r.rotation,
                ["minAngle"]);
            // source ownership policy 플래그
            _mergeKeys(defaults.rendering.textFrame.spec025,
                r.textFrame && r.textFrame.spec025,
                ["masterPageEditable", "hashiraEditable", "rotationEditable", "nonprintingEditable",
                 "inlineTextEditable", "inlineTextMaxLen", "groupShortTextEditable", "oneCharEditable",
                 "decorativeLargeTextEditable", "decorativeStyledTextEditable", "boxLabelEditable"]);
        }
        if (parsed.extraction) {
            _mergeKeys(defaults.extraction, parsed.extraction,
                ["collectFonts", "measureFontMetrics", "collectFontStatus"]);
        }
        $.writeln("[Config] conversion-config.json loaded: " + configPath);
        return defaults;
    } catch (e) {
        $.writeln("[Config] load failed: " + e + " → defaults");
        return defaults;
    }
}

/**
 * 페이지의 경량 체크섬을 계산한다.
 * item ID 목록 + 각 item의 geometricBounds를 폴리노미얼 해시로 압축.
 * ExtendScript는 SHA256을 제공하지 않으므로 간단한 uint32 해시 사용.
 */
function computePageHash(page) {
    var h = 0;
    var items;
    try { items = page.allPageItems; } catch (e) { return "0"; }
    h = (h * 31 + items.length) | 0;
    for (var i = 0; i < items.length; i++) {
        try {
            h = (h * 31 + items[i].id) | 0;
            var b = items[i].geometricBounds; // [top, left, bottom, right]
            h = (h * 31 + Math.round(b[0] * 10)) | 0;
            h = (h * 31 + Math.round(b[1] * 10)) | 0;
            h = (h * 31 + Math.round(b[2] * 10)) | 0;
            h = (h * 31 + Math.round(b[3] * 10)) | 0;
        } catch (e2) {}
    }
    // 텍스트 프레임 내용 샘플 (첫 200자)
    try {
        var tfs = page.textFrames;
        for (var t = 0; t < tfs.length; t++) {
            try {
                var txt = tfs[t].contents;
                var len = Math.min(txt.length, 200);
                h = (h * 31 + txt.length) | 0;
                for (var c = 0; c < len; c++) {
                    h = (h * 31 + txt.charCodeAt(c)) | 0;
                }
            } catch (e3) {}
        }
    } catch (e) {}
    return (h >>> 0).toString(16);
}

// pre_scan 전용: 전체 doc.allPageItems 없이 range page와 same-spread sibling page items만 사용 — IDML export 불필요.
// hash 계산은 buildPageData/computePageHash와 동일한 알고리즘 (결과 호환).
function buildPageDataFast(doc, startPage, endPage) {
    return buildPageData(doc, startPage, endPage, collectRangePageItems(doc, startPage, endPage));
}

/**
 * 전체 페이지 범위에 대해 해시 맵과 아이템-페이지 맵을 한 번에 빌드한다.
 * hashes: {pageIdx(1-based): hashStr}
 * itemMap: {pageIdx(1-based): [domId, ...]}
 */
function buildPageData(doc, startPage, endPage, allItems) {
    var hashes = {};
    var itemMap = {};
    var itemSeen = {};
    var targetPageIndexes = [];
    var pageBoundsByIndex = {};
    var pagesBySpreadId = {};
    var textHashByItemId = {};

    // 페이지별 hash seed만 만든다. 실제 hash 구성은 이미 Stage 0에서 수집한
    // range-local allItems를 재사용한다. page.allPageItems를 다시 순회하면
    // InDesign DOM을 중복으로 펼치게 되어 full extraction에서 큰 병목이 된다.
    for (var pi = 0; pi < doc.pages.length; pi++) {
        var page = doc.pages[pi];
        var pgIdx = page.documentOffset + 1;
        if (pgIdx < startPage || pgIdx > endPage) continue;
        hashes[pgIdx] = ((pgIdx * 2654435761) >>> 0).toString(16);
        itemMap[pgIdx] = [];
        itemSeen[pgIdx] = {};
        targetPageIndexes.push(pi);
        pageBoundsByIndex[String(pi)] = _pageBoundsForIndex(doc, pi);
        var spreadId = _pageSpreadIdForIndex(doc, pi);
        var spreadKey = spreadId !== null && spreadId !== undefined ? String(spreadId) : "page:" + String(pi);
        if (!pagesBySpreadId[spreadKey]) pagesBySpreadId[spreadKey] = [];
        pagesBySpreadId[spreadKey].push(pi);
    }

    function textHashContribution(item, id) {
        var key = String(id);
        if (textHashByItemId.hasOwnProperty(key)) return textHashByItemId[key];
        var h = 0;
        try {
            if (_itemKind(item) === "TextFrame") {
                var txt = String(item.contents || "");
                var len = Math.min(txt.length, 200);
                h = (h * 31 + txt.length) | 0;
                for (var c = 0; c < len; c++) {
                    h = (h * 31 + txt.charCodeAt(c)) | 0;
                }
            }
        } catch (eTextHash) {}
        textHashByItemId[key] = h;
        return h;
    }

    function addPageItem(pgIdx, item, bounds) {
        if (!item || !itemMap[pgIdx]) return;
        var id = null;
        try { id = item.id; } catch (eId) {}
        if (id === null || id === undefined) return;
        var key = String(id);
        if (itemSeen[pgIdx][key]) return;
        itemSeen[pgIdx][key] = true;
        itemMap[pgIdx].push(id);
        try {
            var h = parseInt(hashes[pgIdx] || "0", 16) | 0;
            h = (h * 31 + Number(id)) | 0;
            if (bounds && bounds.length >= 4) {
                h = (h * 31 + Math.round(bounds[0] * 10)) | 0;
                h = (h * 31 + Math.round(bounds[1] * 10)) | 0;
                h = (h * 31 + Math.round(bounds[2] * 10)) | 0;
                h = (h * 31 + Math.round(bounds[3] * 10)) | 0;
            }
            h = (h * 31 + textHashContribution(item, id)) | 0;
            hashes[pgIdx] = (h >>> 0).toString(16);
        } catch (eHash) {}
    }

    // allItems → visible-page 귀속.
    // parentPage는 source anchor일 뿐이며, 같은 spread 안에서 실제 page bounds와 교차하면
    // 해당 페이지의 itemMap/cache hash에 포함한다.
    for (var ai = 0; ai < allItems.length; ai++) {
        try {
            var item = allItems[ai];
            var itemBounds = _itemBounds(item);
            var sourcePageIndex = _pageIndexOfItem(doc, item);
            var candidatePages = targetPageIndexes;
            if (sourcePageIndex >= 0) {
                var sourceSpreadId = _pageSpreadIdForIndex(doc, sourcePageIndex);
                var sourceSpreadKey = sourceSpreadId !== null && sourceSpreadId !== undefined
                        ? String(sourceSpreadId)
                        : "page:" + String(sourcePageIndex);
                candidatePages = pagesBySpreadId[sourceSpreadKey] || [];
            }
            for (var cp = 0; cp < candidatePages.length; cp++) {
                var pp = candidatePages[cp];
                if (!itemMap[pp + 1]) continue;
                if (sourcePageIndex === pp) {
                    addPageItem(pp + 1, item, itemBounds);
                    continue;
                }
                var pb = pageBoundsByIndex[String(pp)];
                if (itemBounds && pb && _boundsOverlap(pb, itemBounds)) {
                    addPageItem(pp + 1, item, itemBounds);
                }
            }
        } catch (e) {}
    }

    return { hashes: hashes, itemMap: itemMap };
}

// args 배열을 파싱해 ctx 객체를 반환하고 전역 CONFIG를 초기화한다.
function _parseArgs(args) {
    var graphicsMode = "single-textless-plane";
    try { CANONICAL_GRAPHICS_MODE = graphicsMode; } catch (eGraphicsModeGlobal) {}
    var ctx = {
        inddPath:           args[0],
        outputDir:          args[1],
        startPage:          parseInt(args[2], 10) || 0,
        endPage:            parseInt(args[3], 10) || 0,
        spreadMode:         (args[4] === "1"),
        pdfOnly:            (args[5] === "1"),
        configPath:         args[6] || null,
        perfMode:           (args[7] || "standard").toLowerCase(),
        skipRenderPagesMap: {},
        extractScriptPath:   args[16] || null,
        graphicsMode:        graphicsMode,
        // SPEC-030 B.2: "pre_scan" 모드 — 해시만 계산하고 렌더링 없이 종료
        extractMode:        (args[10] || "full").toLowerCase(),
        // 분할 추출 모드: IDML 재내보내기 생략, resolved를 resolved_START_END.json에 저장
        chunkMode:          (args[11] === "1"),
        // 청크/물리 범위 모드: startPage/endPage가 물리 인덱스이므로 북 페이지 번호 변환 생략
        physicalRange:      (args[12] === "1"),
        // 아래는 _computePageRange에서 채워짐
        pageCount: 0, rangePageCount: 0
    };
    ctx.writePlannerDiagnostics = args[13] === "1"
            || args[13] === "--diagnostics"
            || args[13] === "diagnostics"
            || ctx.perfMode === "diagnostics"
            || ctx.perfMode === "debug";
    ctx.skipValidation = args[14] === "1"
            || args[14] === "--skip-validation"
            || args[14] === "skip-validation"
            || graphicsMode === "single-textless-plane";
    ctx.reuseExistingIdml = args[15] === "1"
            || args[15] === "--reuse-idml"
            || args[15] === "reuse-idml";
    ctx.requestedStartPage = ctx.startPage;
    ctx.requestedEndPage = ctx.endPage;
    ctx.rangeInputMode = ctx.physicalRange ? "physical" : "auto";
    ctx.resolvedRangeMode = "all";
    ctx.skipPdf = (args[8] === "1");

    var skipRenderPagesRaw = args[9] || "";
    if (skipRenderPagesRaw) {
        try {
            var _srp = JSON.parse(skipRenderPagesRaw);
            for (var _si = 0; _si < _srp.length; _si++) ctx.skipRenderPagesMap[_srp[_si]] = true;
        } catch (e) {}
    }

    CONFIG = loadConversionConfig(ctx.configPath);
    // SPEC-030: perfMode 가 pngExportResolution 을 override (config 값 < CLI 값)
    if (ctx.perfMode === "fast")       CONFIG.rendering.pngExportResolution = 150;
    else if (ctx.perfMode === "high")  CONFIG.rendering.pngExportResolution = 300;

    try {
        var cfgLog = File(ctx.outputDir + "/_config_jsx_debug.log");
        cfgLog.encoding = "UTF-8"; cfgLog.open("w");
        cfgLog.writeln("configPath=" + ctx.configPath);
        cfgLog.writeln("perfMode=" + ctx.perfMode);
        cfgLog.writeln("skipPdf=" + ctx.skipPdf);
        cfgLog.writeln("writePlannerDiagnostics=" + ctx.writePlannerDiagnostics);
        cfgLog.writeln("skipValidation=" + ctx.skipValidation);
        cfgLog.writeln("reuseExistingIdml=" + ctx.reuseExistingIdml);
        cfgLog.writeln("graphicsMode=" + ctx.graphicsMode);
        cfgLog.writeln("pngExportResolution=" + CONFIG.rendering.pngExportResolution);
        try { cfgLog.writeln("moduleLoadDebug=" + _EXTRACT_MODULE_LOAD_DEBUG); } catch (eModuleDebugLog) {}
        cfgLog.close();
    } catch (e) {}

    return ctx;
}

function _pageLabelIndexMap(doc) {
    var byNumber = {};
    for (var pi = 0; pi < doc.pages.length; pi++) {
        var pageName = parseInt(doc.pages[pi].name, 10);
        if (!isNaN(pageName) && byNumber[pageName] === undefined) {
            byNumber[pageName] = pi + 1;
        }
    }
    return byNumber;
}

// 문서 페이지 범위를 계산해 ctx.startPage/endPage/rangePageCount/pageCount를 갱신한다.
function _computePageRange(doc, ctx) {
    ctx.pageCount = doc.pages.length;
    // Page range contract:
    // - physicalRange/chunkMode: startPage/endPage are local 1-based page indices.
    // - auto mode: document page labels are used only when every provided endpoint
    //   resolves as a label. Mixed label/physical interpretation is forbidden
    //   because it silently truncates local ranges in relabeled books (e.g. 42..93).
    if ((ctx.startPage > 0 || ctx.endPage > 0) && !ctx.physicalRange && !ctx.chunkMode) {
        var labelMap = _pageLabelIndexMap(doc);
        var hasStart = ctx.startPage > 0;
        var hasEnd = ctx.endPage > 0;
        var startLabelIndex = hasStart ? labelMap[ctx.startPage] : undefined;
        var endLabelIndex = hasEnd ? labelMap[ctx.endPage] : undefined;
        var startIsLabel = !hasStart || startLabelIndex !== undefined;
        var endIsLabel = !hasEnd || endLabelIndex !== undefined;

        if ((hasStart || hasEnd) && startIsLabel && endIsLabel && (hasStart && startLabelIndex !== undefined || hasEnd && endLabelIndex !== undefined)) {
            if (hasStart) ctx.startPage = startLabelIndex;
            if (hasEnd) ctx.endPage = endLabelIndex;
            ctx.resolvedRangeMode = "document_label";
        } else {
            ctx.resolvedRangeMode = "physical_auto";
            if ((hasStart && startLabelIndex !== undefined) || (hasEnd && endLabelIndex !== undefined)) {
                try {
                    $.writeln("[page-range] mixed label/physical endpoints; using physical indices start="
                        + ctx.requestedStartPage + " end=" + ctx.requestedEndPage);
                } catch (eRangeLog) {}
            }
        }
    } else if (ctx.physicalRange || ctx.chunkMode) {
        ctx.resolvedRangeMode = "physical";
    }
    if (ctx.startPage < 1) ctx.startPage = 1;
    if (ctx.endPage < 1 || ctx.endPage > ctx.pageCount) ctx.endPage = ctx.pageCount;
    ctx.rangePageCount = ctx.endPage - ctx.startPage + 1;
}

function _linkStatusLabel(status) {
    try {
        if (status === LinkStatus.NORMAL) return "NORMAL";
        if (status === LinkStatus.LINK_OUT_OF_DATE) return "LINK_OUT_OF_DATE";
        if (status === LinkStatus.LINK_MISSING) return "LINK_MISSING";
        if (status === LinkStatus.LINK_INACCESSIBLE) return "LINK_INACCESSIBLE";
        if (status === LinkStatus.LINK_EMBEDDED) return "LINK_EMBEDDED";
    } catch (eLinkStatusLabel) {}
    return String(status);
}

// 문서의 누락/만료 링크를 갱신한다. 렌더링 전(PNG용)과 PDF 내보내기 전(고해상도용) 2회 호출된다.
function _fixLinks(doc, inddPath, outputDir, phaseName) {
    try {
        var inddParent = File(inddPath).parent;
        var linksFolders = [Folder(inddParent + "/Links"), Folder(inddParent)];
        var fixedCount = 0, missingCount = 0;
        var phase = phaseName || "fix_links";
        var allowOutOfDateUpdate = phase !== "render_links";
        appendDiag(outputDir, "_open_diagnostics.jsonl", {
            event: "fix_links_start",
            phase: phase,
            inddPath: inddPath,
            totalLinks: doc && doc.links ? doc.links.length : null,
            allowOutOfDateUpdate: allowOutOfDateUpdate
        });
        for (var li = 0; li < doc.links.length; li++) {
            var lnk = doc.links[li];
            try {
                if (lnk.status === LinkStatus.NORMAL) continue;
                appendDiag(outputDir, "_open_diagnostics.jsonl", {
                    event: "fix_links_candidate",
                    phase: phase,
                    index: li,
                    name: lnk.name || null,
                    status: _linkStatusLabel(lnk.status)
                });
                if (lnk.status === LinkStatus.LINK_OUT_OF_DATE) {
                    if (allowOutOfDateUpdate) {
                        lnk.update(); fixedCount++;
                        appendDiag(outputDir, "_open_diagnostics.jsonl", {
                            event: "fix_links_updated",
                            phase: phase,
                            index: li,
                            name: lnk.name || null,
                            status: _linkStatusLabel(lnk.status)
                        });
                    } else {
                        appendDiag(outputDir, "_open_diagnostics.jsonl", {
                            event: "fix_links_skip_out_of_date",
                            phase: phase,
                            index: li,
                            name: lnk.name || null,
                            status: _linkStatusLabel(lnk.status)
                        });
                    }
                } else if (lnk.status === LinkStatus.LINK_MISSING) {
                    var found = false;
                    for (var fi = 0; fi < linksFolders.length; fi++) {
                        if (!linksFolders[fi].exists) continue;
                        var linkFile = File(linksFolders[fi] + "/" + lnk.name);
                        if (linkFile.exists) {
                            appendDiag(outputDir, "_open_diagnostics.jsonl", {
                                event: "fix_links_relink_attempt",
                                phase: phase,
                                index: li,
                                name: lnk.name || null,
                                targetPath: linkFile.fsName
                            });
                            lnk.relink(linkFile);
                            lnk.update();
                            fixedCount++;
                            found = true;
                            appendDiag(outputDir, "_open_diagnostics.jsonl", {
                                event: "fix_links_relink_done",
                                phase: phase,
                                index: li,
                                name: lnk.name || null,
                                targetPath: linkFile.fsName,
                                status: _linkStatusLabel(lnk.status)
                            });
                            break;
                        }
                    }
                    if (!found) {
                        missingCount++;
                        appendDiag(outputDir, "_open_diagnostics.jsonl", {
                            event: "fix_links_missing_unresolved",
                            phase: phase,
                            index: li,
                            name: lnk.name || null
                        });
                    }
                }
            } catch (le) {
                appendDiag(outputDir, "_open_diagnostics.jsonl", {
                    event: "fix_links_error",
                    phase: phase,
                    index: li,
                    name: (lnk && lnk.name) ? lnk.name : null,
                    message: String(le)
                });
            }
        }
        appendDiag(outputDir, "_open_diagnostics.jsonl", {
            event: "fix_links_done",
            phase: phase,
            fixedCount: fixedCount,
            missingCount: missingCount
        });
        if (fixedCount > 0 || missingCount > 0)
            $.writeln("[Links] fixed=" + fixedCount + " missing=" + missingCount);
    } catch (e) {}
}
