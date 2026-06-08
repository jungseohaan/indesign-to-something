/**
 * extract_indd.jsx — InDesign ExtendScript
 *
 * InDesign에서 .indd 파일을 열어 IDML + resolved.json + preview.pdf를 추출한다.
 * osascript의 `do script ... language javascript`로 호출되며,
 * arguments로 [inddPath, outputDir]을 수신한다.
 *
 * 출력:
 *   outputDir/output.idml       — IDML 패키지
 *   outputDir/resolved.json     — resolved 속성 (스타일, 폰트, 색상 등)
 *   outputDir/preview.pdf       — PDF 프리뷰 (실패 시 생략)
 *   outputDir/.done             — 완료 시그널 (JSON: {status, message?})
 */

// SPEC-011: 추출 캐시 무효화용 스크립트 버전.
// 출력 형식이나 추출 로직이 변경되면 이 값을 올려서 모든 캐시를 강제 무효화한다.
// (mtime/size 기반 자동 무효화와 별개로 명시적 버전 관리 채널)
var EXTRACT_SCRIPT_VERSION = "17";

// =============================================================================
// SECTION 1: BOOTSTRAP
// JSON 폴리필 / 타이밍 마커 / 전역 설정 / Config 로더 / 색상 유틸
// =============================================================================

// --- JSON 폴리필 (ExtendScript는 ES3 기반, JSON 미지원) ---
if (typeof JSON === "undefined") {
    JSON = {};
}
if (typeof JSON.stringify !== "function") {
    // 최대 재귀 깊이 (InDesign DOM 객체 순환 참조 방지)
    var _JSON_MAX_DEPTH = 60;

    JSON.stringify = function (val, replacer, space) {
        var indent = "";
        var gap = "";
        if (typeof space === "number") {
            for (var s = 0; s < space; s++) gap += " ";
        } else if (typeof space === "string") {
            gap = space;
        }
        return _jsonSerialize("", { "": val }, gap, indent, 0);
    };

    function _jsonQuote(str) {
        str = String(str);
        var result = '"';
        for (var i = 0; i < str.length; i++) {
            var c = str.charAt(i);
            var code = str.charCodeAt(i);
            if (c === '"') result += '\\"';
            else if (c === '\\') result += '\\\\';
            else if (c === '\n') result += '\\n';
            else if (c === '\r') result += '\\r';
            else if (c === '\t') result += '\\t';
            // InDesign 고유 제어 문자 — JSON 파서 오류 방지
            else if (code === 0xFFFD || code === 0xFFFC || code === 0xFEFF ||
                     code === 0x0016 || code === 0x0018 || code === 0x0003 ||
                     (code < 0x0020 && code !== 0x0009)) {
                result += '\\u' + ('0000' + code.toString(16)).slice(-4);
            }
            else result += c;
        }
        return result + '"';
    }

    function _jsonSerialize(key, holder, gap, indent, depth) {
        var val = holder[key];
        if (val === null) return "null";
        if (val === undefined) return "null";
        // 깊이 제한 — InDesign DOM 순환 참조 시 스택 오버플로 방지
        if (depth > _JSON_MAX_DEPTH) return "null";
        var t = typeof val;
        if (t === "boolean") return val ? "true" : "false";
        if (t === "number") {
            if (isFinite(val)) return String(val);
            return "null";
        }
        if (t === "string") return _jsonQuote(val);
        if (t === "function") return "null";
        // InDesign DOM 객체 감지 — toSpecifier가 있으면 DOM 객체
        if (typeof val.toSpecifier === "function") {
            try { return _jsonQuote(val.toSpecifier()); } catch (e) { return "null"; }
        }
        // Array
        if (val instanceof Array) {
            var arrParts = [];
            var newIndent = indent + gap;
            for (var i = 0; i < val.length; i++) {
                arrParts.push(_jsonSerialize(String(i), val, gap, newIndent, depth + 1));
            }
            if (arrParts.length === 0) return "[]";
            if (gap) {
                return "[\n" + newIndent + arrParts.join(",\n" + newIndent) + "\n" + indent + "]";
            }
            return "[" + arrParts.join(",") + "]";
        }
        // Object
        if (t === "object") {
            var objParts = [];
            var newIndent2 = indent + gap;
            for (var k in val) {
                if (val.hasOwnProperty(k)) {
                    var v = _jsonSerialize(k, val, gap, newIndent2, depth + 1);
                    if (v !== undefined) {
                        objParts.push(_jsonQuote(k) + (gap ? ": " : ":") + v);
                    }
                }
            }
            if (objParts.length === 0) return "{}";
            if (gap) {
                return "{\n" + newIndent2 + objParts.join(",\n" + newIndent2) + "\n" + indent + "}";
            }
            return "{" + objParts.join(",") + "}";
        }
        return "null";
    }
}

// --- 디버그 마커 (크래시 지점 추적) ---
function _marker(outputDir, tag) {
    try {
        var f = File(outputDir + "/.crash_marker");
        f.encoding = "UTF-8";
        f.open("w");
        f.write(tag);
        f.close();
    } catch (e) {}
    // SPEC-030: phase 별 elapsed ms 누적 (지난 _marker 호출 이후 경과 시간 기록)
    try {
        var now = (new Date()).getTime();
        if (!_phaseTimingState) {
            _phaseTimingState = { lastTime: now, lastTag: tag, startTime: now };
            // 기존 로그 초기화
            var f0 = File(outputDir + "/_phase_timing.log");
            f0.encoding = "UTF-8";
            f0.open("w");
            f0.writeln("# SPEC-030 phase timing (ms)");
            f0.writeln("tag\televatedTotalMs\tdeltaMs");
            f0.writeln(tag + "\t0\t0");
            f0.close();
            return;
        }
        var delta = now - _phaseTimingState.lastTime;
        var total = now - _phaseTimingState.startTime;
        var f1 = File(outputDir + "/_phase_timing.log");
        f1.encoding = "UTF-8";
        f1.open("e"); // append
        f1.seek(0, 2);
        f1.writeln(tag + "\t" + total + "\t" + delta);
        f1.close();
        _phaseTimingState.lastTime = now;
        _phaseTimingState.lastTag = tag;
    } catch (e) {}
}

// SPEC-030: phase 타이밍 누적 상태 (extract 한 번에 하나).
var _phaseTimingState = null;
var _ctfCache = null;          // itemId → "editable"|"renderable"|"background"|null
var _hiddenLayerCache = null;  // itemId → boolean (isOnHiddenLayer 결과 캐시)

// --- 전역 설정 ---
var CONFIG = null;

// item의 parentPage를 반환한다.
// parentPage가 null인 인라인/스프레드 경계 아이템은 visibleBounds 중심점으로 doc.pages를 탐색한다.
function _resolveParentPage(item, doc) {
    var pg = null;
    try { pg = item.parentPage; } catch (e) {}
    if (pg) return pg;
    try {
        var vb = null;
        try { vb = item.visibleBounds; } catch (e) {}
        if (!vb) vb = item.geometricBounds;
        var cy = (vb[0] + vb[2]) / 2, cx = (vb[1] + vb[3]) / 2;
        for (var i = 0; i < doc.pages.length; i++) {
            var pb = doc.pages[i].bounds;
            if (cy >= pb[0] && cy <= pb[2] && cx >= pb[1] && cx <= pb[3]) return doc.pages[i];
        }
    } catch (e) {}
    return null;
}

// bounds 배열 [top, left, bottom, right]을 page 기준 상대 좌표로 in-place 변환한다.
function _toPageRelativeBounds(bounds, page) {
    var pb = page.bounds;
    bounds[0] -= pb[0]; bounds[1] -= pb[1];
    bounds[2] -= pb[0]; bounds[3] -= pb[1];
}

// --- conversion-config.json 로더 ---

// src의 keys 배열 키를 dst에 복사한다. src가 없거나 키가 undefined면 건너뛴다.
function _mergeKeys(dst, src, keys) {
    if (!dst || !src) return;
    for (var _i = 0; _i < keys.length; _i++) {
        if (src[keys[_i]] !== undefined) dst[keys[_i]] = src[keys[_i]];
    }
}

function loadConversionConfig(configPath) {
    var defaults = {
        rendering: {
            // SPEC-025: 텍스트 이미지 렌더링 제거 (Tier A/B). false면 기존 동작.
            // - masterPageEditable: 조건 5 (item.masterPageItem 오버라이드) → editable
            // - hashiraEditable: 조건 6 (margin + 하시라 paragraph/character style) → editable
            // - rotationEditable: 회전 텍스트 (조건 8.5) → editable + HWPX rotation
            // - nonprintingEditable: 조건 3 (item.nonprinting) → editable. 마스터 헤더 케이스 (frame 3854 등)
            textFrame: { maxTextLength: 30,
                decorativeLargeText: { enabled: true, minFontSize: 16, excludeBlack: true, blackThreshold: 0.90 },
                decorativeStyledText: { enabled: true, maxTextLength: 10, excludeBlack: true, blackThreshold: 0.90, requireObjectStyle: true },
                // SPEC-025 추가 플래그
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
            // SPEC-025 플래그
            _mergeKeys(defaults.rendering.textFrame.spec025,
                r.textFrame && r.textFrame.spec025,
                ["masterPageEditable", "hashiraEditable", "rotationEditable", "nonprintingEditable",
                 "inlineTextEditable", "inlineTextMaxLen", "groupShortTextEditable", "oneCharEditable",
                 "decorativeLargeTextEditable", "decorativeStyledTextEditable", "boxLabelEditable"]);
        }
        $.writeln("[Config] conversion-config.json loaded: " + configPath);
        return defaults;
    } catch (e) {
        $.writeln("[Config] load failed: " + e + " → defaults");
        return defaults;
    }
}

// =============================================================================
// SECTION 2: PAGE UTILITIES
// 페이지 해시 계산 / 아이템-페이지 맵
// =============================================================================

// --- SPEC-030 B.2: 페이지별 해시 + 아이템 맵 ---

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

// pre_scan 전용: doc.allPageItems 없이 page.allPageItems만 사용 — IDML export 불필요.
// hash 계산은 buildPageData/computePageHash와 동일한 알고리즘 (결과 호환).
function buildPageDataFast(doc, startPage, endPage) {
    var hashes = {}, itemMap = {};
    for (var pi = 0; pi < doc.pages.length; pi++) {
        var page = doc.pages[pi];
        var pgIdx = page.documentOffset + 1;
        if (pgIdx < startPage || pgIdx > endPage) continue;
        var h = 0;
        itemMap[pgIdx] = [];
        var items;
        try { items = page.allPageItems; } catch (e) { hashes[pgIdx] = "0"; continue; }
        h = (h * 31 + items.length) | 0;
        for (var i = 0; i < items.length; i++) {
            try {
                h = (h * 31 + items[i].id) | 0;
                itemMap[pgIdx].push(items[i].id);
                var b = items[i].geometricBounds;
                h = (h * 31 + Math.round(b[0] * 10)) | 0;
                h = (h * 31 + Math.round(b[1] * 10)) | 0;
                h = (h * 31 + Math.round(b[2] * 10)) | 0;
                h = (h * 31 + Math.round(b[3] * 10)) | 0;
            } catch (e2) {}
        }
        try {
            var tfs = page.textFrames;
            for (var t = 0; t < tfs.length; t++) {
                try {
                    var txt = tfs[t].contents;
                    h = (h * 31 + txt.length) | 0;
                    var len = Math.min(txt.length, 200);
                    for (var c = 0; c < len; c++) h = (h * 31 + txt.charCodeAt(c)) | 0;
                } catch (e3) {}
            }
        } catch (e) {}
        hashes[pgIdx] = (h >>> 0).toString(16);
    }
    return { hashes: hashes, itemMap: itemMap };
}

/**
 * 전체 페이지 범위에 대해 해시 맵과 아이템-페이지 맵을 한 번에 빌드한다.
 * hashes: {pageIdx(1-based): hashStr}
 * itemMap: {pageIdx(1-based): [domId, ...]}
 */
function buildPageData(doc, startPage, endPage, allItems) {
    var hashes = {};
    var itemMap = {};

    // 페이지 해시
    for (var pi = 0; pi < doc.pages.length; pi++) {
        var page = doc.pages[pi];
        var pgIdx = page.documentOffset + 1;
        if (pgIdx < startPage || pgIdx > endPage) continue;
        hashes[pgIdx] = computePageHash(page);
        itemMap[pgIdx] = [];
    }

    // allItems → 페이지 귀속 (item.parentPage 사용, 없으면 bounds 기반 매칭)
    for (var ai = 0; ai < allItems.length; ai++) {
        try {
            var item = allItems[ai];
            var itemPage = null;
            try { itemPage = item.parentPage; } catch (e) {}
            if (!itemPage) {
                // bounds 기반 페이지 탐색
                try {
                    var ib = item.visibleBounds;
                    var icy = (ib[0] + ib[2]) / 2;
                    var icx = (ib[1] + ib[3]) / 2;
                    for (var pp = 0; pp < doc.pages.length; pp++) {
                        var _p = doc.pages[pp];
                        var _pb = _p.bounds;
                        if (icy >= _pb[0] && icy <= _pb[2] && icx >= _pb[1] && icx <= _pb[3]) {
                            itemPage = _p;
                            break;
                        }
                    }
                } catch (e2) {}
            }
            if (!itemPage) continue;
            var ipIdx = itemPage.documentOffset + 1;
            if (itemMap[ipIdx]) itemMap[ipIdx].push(item.id);
        } catch (e) {}
    }

    return { hashes: hashes, itemMap: itemMap };
}


// =============================================================================
// SECTION 3: MAIN ENTRY POINT
// main() — 문서 오픈 → 렌더링 → resolved 수집 → PDF → 완료 시그널
// H-2 리팩토링 후 SECTION 6 말미로 이동 예정
// =============================================================================

// args 배열을 파싱해 ctx 객체를 반환하고 전역 CONFIG를 초기화한다.
function _parseArgs(args) {
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
        // SPEC-030 B.2: "pre_scan" 모드 — 해시만 계산하고 렌더링 없이 종료
        extractMode:        (args[10] || "full").toLowerCase(),
        // 분할 추출 모드: IDML 재내보내기 생략, resolved를 resolved_START_END.json에 저장
        chunkMode:          (args[11] === "1"),
        // 청크/물리 범위 모드: startPage/endPage가 물리 인덱스이므로 북 페이지 번호 변환 생략
        physicalRange:      (args[12] === "1"),
        // 아래는 _computePageRange에서 채워짐
        pageCount: 0, rangePageCount: 0
    };
    ctx.skipPdf = (args[8] === "1") || (ctx.perfMode === "fast");

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
        cfgLog.writeln("pngExportResolution=" + CONFIG.rendering.pngExportResolution);
        cfgLog.close();
    } catch (e) {}

    return ctx;
}

// 문서 페이지 범위를 계산해 ctx.startPage/endPage/rangePageCount/pageCount를 갱신한다.
function _computePageRange(doc, ctx) {
    ctx.pageCount = doc.pages.length;
    // 문서 페이지 번호(예: "10-29") → 물리 인덱스 변환
    // physicalRange/chunkMode 시: startPage/endPage가 이미 물리 인덱스이므로 변환 생략
    if ((ctx.startPage > 0 || ctx.endPage > 0) && !ctx.physicalRange && !ctx.chunkMode) {
        for (var pi = 0; pi < ctx.pageCount; pi++) {
            var pageName = parseInt(doc.pages[pi].name, 10);
            if (!isNaN(pageName)) {
                if (pageName === ctx.startPage) ctx.startPage = pi + 1;
                if (pageName === ctx.endPage)   ctx.endPage   = pi + 1;
            }
        }
    }
    if (ctx.startPage < 1) ctx.startPage = 1;
    if (ctx.endPage < 1 || ctx.endPage > ctx.pageCount) ctx.endPage = ctx.pageCount;
    ctx.rangePageCount = ctx.endPage - ctx.startPage + 1;
}

// 문서의 누락/만료 링크를 갱신한다. 렌더링 전(PNG용)과 PDF 내보내기 전(고해상도용) 2회 호출된다.
function _fixLinks(doc, inddPath) {
    try {
        var inddParent = File(inddPath).parent;
        var linksFolders = [Folder(inddParent + "/Links"), Folder(inddParent)];
        var fixedCount = 0, missingCount = 0;
        for (var li = 0; li < doc.links.length; li++) {
            var lnk = doc.links[li];
            try {
                if (lnk.status === LinkStatus.NORMAL) continue;
                if (lnk.status === LinkStatus.LINK_OUT_OF_DATE) {
                    lnk.update(); fixedCount++;
                } else if (lnk.status === LinkStatus.LINK_MISSING) {
                    var found = false;
                    for (var fi = 0; fi < linksFolders.length; fi++) {
                        if (!linksFolders[fi].exists) continue;
                        var linkFile = File(linksFolders[fi] + "/" + lnk.name);
                        if (linkFile.exists) { lnk.relink(linkFile); lnk.update(); fixedCount++; found = true; break; }
                    }
                    if (!found) missingCount++;
                }
            } catch (le) {}
        }
        if (fixedCount > 0 || missingCount > 0)
            $.writeln("[Links] fixed=" + fixedCount + " missing=" + missingCount);
    } catch (e) {}
}

// 렌더링 페이즈 2.12~3: 배경 PNG → 배지 → 이미지 → 데코 → 그래픽 → 벡터 → 마스터 → resolved.json 기록
function _runRenderPhases(doc, ctx, allItems) {
    function addItemType(arr, type) {
        for (var _i = 0; _i < arr.length; _i++) arr[_i].type = type;
    }

    function renderedItemDedupeKey(item) {
        if (!item) return "";
        function arrKey(a) {
            if (!a || !a.length) return "";
            var out = [];
            for (var i = 0; i < a.length; i++) out.push(String(a[i]));
            return out.join(",");
        }
        function boundsKey(b) {
            if (!b || b.length < 4) return "";
            return [
                Math.round(b[0] * 100),
                Math.round(b[1] * 100),
                Math.round(b[2] * 100),
                Math.round(b[3] * 100)
            ].join(",");
        }
        return [
            item.id,
            item.pageIndex,
            item.type || "",
            item.file || "",
            item.reason || "",
            boundsKey(item.bounds),
            arrKey(item.sourceObjectIds)
        ].join("|");
    }

    function dedupeRenderedFloatingItems(items) {
        var out = [];
        var seen = {};
        for (var i = 0; i < items.length; i++) {
            var item = items[i];
            var key = renderedItemDedupeKey(item);
            if (seen[key]) continue;
            seen[key] = true;
            out.push(item);
        }
        return out;
    }

    // 03c. allItems 전체 분류 캐시 — classifyTextFrame의 중복 DOM 호출 제거
    _ctfCache = {};
    _hiddenLayerCache = {};  // isOnHiddenLayer 캐시 리셋 (새 allItems 기준)
    for (var _pci = 0; _pci < allItems.length; _pci++) {
        try {
            var _pcIt = allItems[_pci];
            if (_pcIt.constructor.name === "TextFrame") _ctfCache[_pcIt.id] = classifyTextFrame(_pcIt);
        } catch (e) {}
    }
    _marker(ctx.outputDir, "03c_preClassify");

    _marker(ctx.outputDir, "04_pageRendering");
    writeProgress(ctx.outputDir, "rendered_frames", 0, ctx.rangePageCount);

    // 2.12. inline_object + table_inline 추출
    var bgResult = exportPageBackgrounds(doc, ctx.outputDir, ctx.startPage, ctx.endPage, allItems, ctx.skipRenderPagesMap);
    var renderedFloatingItems = bgResult.items;
    var editableFrameIds = bgResult.editableFrameIds;
    if (bgResult.tableInlineRendered) {
        for (var tir = 0; tir < bgResult.tableInlineRendered.length; tir++)
            renderedFloatingItems.push(bgResult.tableInlineRendered[tir]);
    }
    try { $.gc(); } catch (e) {}
    // exportPageBackgrounds의 finally 블록이 true로 복원하지만, 예외 전파 등 만일의 경우를 대비한 안전망.
    try { app.pngExportPreferences.transparentBackground = true; } catch (e) {}

    // 2.14. 이미지 프레임 개별 렌더링 (Rectangle/Oval/Polygon에 place된 이미지)
    _marker(ctx.outputDir, "06_imgFrames");
    var renderedImageFrames = exportImagePlacedFrames(doc, ctx.outputDir, ctx.startPage, ctx.endPage, allItems);
    addItemType(renderedImageFrames, "page_object");
    for (var ii = 0; ii < renderedImageFrames.length; ii++) renderedFloatingItems.push(renderedImageFrames[ii]);
    try { $.gc(); } catch (e) {}

    // 2.15. 장식 그룹 렌더링 — exportImagePlacedFrames에서 처리된 ID 제외
    var imgRenderedIds = {};
    for (var iri = 0; iri < renderedImageFrames.length; iri++) imgRenderedIds[renderedImageFrames[iri].id] = true;
    _marker(ctx.outputDir, "07_decoGroups");
    var decoResult  = exportDecorationGroups(doc, ctx.outputDir, ctx.startPage, ctx.endPage, allItems, imgRenderedIds);
    var decoChildIds = decoResult.childIds || {};
    addItemType(decoResult.frames, "page_object");
    for (var di = 0; di < decoResult.frames.length; di++) renderedFloatingItems.push(decoResult.frames[di]);
    try { $.gc(); } catch (e) {}

    // 2.16. 복합 그래픽 프레임 렌더링
    _marker(ctx.outputDir, "08_complexFrames");
    var renderedGraphicFrames = exportComplexGraphicFrames(doc, ctx.outputDir, ctx.startPage, ctx.endPage, allItems);
    addItemType(renderedGraphicFrames, "page_object");
    for (var ci = 0; ci < renderedGraphicFrames.length; ci++) renderedFloatingItems.push(renderedGraphicFrames[ci]);
    try { $.gc(); } catch (e) {}

    // 2.17. 벡터 도형 렌더링
    _marker(ctx.outputDir, "09_shapeFrames");
    var renderedVectorFrames = exportVectorShapeFrames(doc, ctx.outputDir, ctx.startPage, ctx.endPage, decoChildIds, allItems);
    addItemType(renderedVectorFrames, "page_object");
    for (var vi = 0; vi < renderedVectorFrames.length; vi++) renderedFloatingItems.push(renderedVectorFrames[vi]);
    try { $.gc(); } catch (e) {}

    // 2.18. 마스터 스프레드 그래픽 렌더링
    _marker(ctx.outputDir, "09b_masterGraphics");
    var renderedMasterGraphics = exportMasterPageGraphics(doc, ctx.outputDir, ctx.startPage, ctx.endPage);
    addItemType(renderedMasterGraphics, "page_object");
    for (var mgi = 0; mgi < renderedMasterGraphics.length; mgi++) renderedFloatingItems.push(renderedMasterGraphics[mgi]);
    try { $.gc(); } catch (e) {}

    // 2.19. editable TextFrame의 시각 껍데기 렌더링 (fill/stroke만 PNG, 텍스트는 HWPX TF)
    _marker(ctx.outputDir, "09c_editableTFVisualShells");
    var tfShellFrames = exportEditableTextFrameVisualShells(doc, ctx.outputDir, ctx.startPage, ctx.endPage, decoChildIds, editableFrameIds, allItems);
    addItemType(tfShellFrames, "page_object");
    for (var otfi = 0; otfi < tfShellFrames.length; otfi++) renderedFloatingItems.push(tfShellFrames[otfi]);
    try { $.gc(); } catch (e) {}

    renderedFloatingItems = dedupeRenderedFloatingItems(renderedFloatingItems);

    // 추출 통계 기록
    try {
        var _statsEndMs = (new Date()).getTime();
        var _statsStartMs = _phaseTimingState ? _phaseTimingState.startTime : _statsEndMs;
        var _inlineCount = 0;
        for (var _si2 = 0; _si2 < renderedFloatingItems.length; _si2++) {
            if (renderedFloatingItems[_si2].type === "inline_object") _inlineCount++;
        }
        var _statsObj = {
            elapsed_ms: _statsEndMs - _statsStartMs,
            total_page_count: ctx.pageCount,
            page_count: ctx.rangePageCount,

            deco_group_count: decoResult.frames.length,
            image_frame_count: renderedImageFrames.length,
            complex_frame_count: renderedGraphicFrames.length,
            shape_count: renderedVectorFrames.length,
            master_graphic_count: renderedMasterGraphics.length,
            inline_object_count: _inlineCount
        };
        var _sf = File(ctx.outputDir + "/_extract_stats.json");
        _sf.encoding = "UTF-8"; _sf.open("w"); _sf.write(JSON.stringify(_statsObj)); _sf.close();
    } catch (eStats) {}

    // 3. resolved 속성 수집
    _marker(ctx.outputDir, "10_collectResolved");
    writeProgress(ctx.outputDir, "resolved", 0, ctx.rangePageCount);
    var resolved = collectResolved(doc, ctx.outputDir, ctx.rangePageCount, ctx.startPage, ctx.endPage, editableFrameIds, ctx.skipRenderPagesMap, allItems);
    resolved.renderedTextFrames    = [];
    resolved.renderedPdfFrames     = [];
    resolved.renderedGraphicFrames = renderedGraphicFrames;
    resolved.renderedImageFrames   = renderedImageFrames;
    resolved.renderedFloatingItems = renderedFloatingItems;

    // 편집 가능 TextFrame ID 목록 (SPEC-025: synthetic master instance ID는 문자열로 유지)
    var pngOwnedTextFrameIds = {};
    try {
        for (var _poi = 0; _poi < renderedFloatingItems.length; _poi++) {
            var _po = renderedFloatingItems[_poi];
            if (!_po || _po.textOwner !== "indesign_png" || !_po.editableTextFrameIds) continue;
            for (var _ptfi = 0; _ptfi < _po.editableTextFrameIds.length; _ptfi++) {
                pngOwnedTextFrameIds[String(_po.editableTextFrameIds[_ptfi])] = true;
            }
        }
    } catch (ePngOwned) {}
    var editableIdList = [];
    for (var eid in editableFrameIds) {
        if (pngOwnedTextFrameIds[String(eid)]) continue;
        if (/^[0-9]+$/.test(eid)) editableIdList.push(parseInt(eid, 10));
        else editableIdList.push(eid);
    }
    resolved.editableTextFrameIds = editableIdList;

    // Safety: 모든 export 단계에서 예외/stale DOM으로 restore가 누락된 경우 대비.
    // editableFrameIds TF를 강제 복원 (visible=true) — PDF 프리뷰 이전 최종 보정.
    for (var _sri = 0; _sri < allItems.length; _sri++) {
        try {
            var _srItem = allItems[_sri];
            if (_srItem.constructor.name === "TextFrame"
                    && editableFrameIds[_srItem.id]
                    && !_srItem.visible) {
                _srItem.visible = true;
            }
        } catch (e) {}
    }

    _marker(ctx.outputDir, "11_writeJson");
    // chunkMode=true(후속 청크)면 resolved_START_END.json으로 저장 (Rust가 나중에 병합)
    var _resolvedFileName = ctx.chunkMode
        ? ("resolved_" + ctx.startPage + "_" + ctx.endPage + ".json")
        : "resolved.json";
    writeJson(ctx.outputDir + "/" + _resolvedFileName, resolved);
}

// PDF 프리뷰를 내보낸다. ctx.skipPdf=true이면 아무것도 하지 않는다.
function _exportPdf(doc, ctx) {
    if (ctx.skipPdf) return;
    _marker(ctx.outputDir, "12_pdf_export");
    try {
        app.pdfExportPreferences.exportReaderSpreads = ctx.spreadMode;
        // SPEC-030 A.3: 페이지 범위가 지정된 경우 PDF도 해당 범위만 출력
        if (ctx.startPage === 1 && ctx.endPage === ctx.pageCount) {
            app.pdfExportPreferences.pageRange = PageRange.ALL_PAGES;
        } else {
            app.pdfExportPreferences.pageRange = ctx.startPage + "-" + ctx.endPage;
        }
        app.pdfExportPreferences.colorBitmapSampling           = Sampling.BICUBIC_DOWNSAMPLE;
        app.pdfExportPreferences.colorBitmapSamplingDPI        = 300;
        app.pdfExportPreferences.colorBitmapCompression        = BitmapCompression.JPEG;
        app.pdfExportPreferences.colorBitmapQuality            = CompressionQuality.HIGH;
        app.pdfExportPreferences.grayscaleBitmapSampling       = Sampling.BICUBIC_DOWNSAMPLE;
        app.pdfExportPreferences.grayscaleBitmapSamplingDPI    = 300;
        app.pdfExportPreferences.grayscaleBitmapCompression    = BitmapCompression.JPEG;
        app.pdfExportPreferences.grayscaleBitmapQuality        = CompressionQuality.HIGH;
        app.pdfExportPreferences.monochromeBitmapSampling      = Sampling.BICUBIC_DOWNSAMPLE;
        app.pdfExportPreferences.monochromeBitmapSamplingDPI   = 1200;
        app.pdfExportPreferences.cropImagesToFrames            = true;
        app.pdfExportPreferences.compressTextAndLineArt        = true;
        app.pdfExportPreferences.acrobatCompatibility          = AcrobatCompatibility.ACROBAT_7;
        app.pdfExportPreferences.subsetFontsBelow              = 100;
        app.pdfExportPreferences.optimizePDF                   = true;
        doc.exportFile(ExportFormat.PDF_TYPE, File(ctx.outputDir + "/preview.pdf"));
    } catch (e) {}
}

function main(args) {
    var ctx = _parseArgs(args);

    // 환경설정 저장 (finally에서 복원)
    var savedInteractionLevel = app.scriptPreferences.userInteractionLevel;
    var savedEnableRedraw     = app.scriptPreferences.enableRedraw;
    var savedCheckLinks       = app.linkingPreferences.checkLinksAtOpen;
    var savedFindMissing      = app.linkingPreferences.findMissingLinksAtOpen;

    var doc = null;
    try {
        // 다이얼로그 억제 (headless) — 누락 폰트·링크 팝업 자동 dismiss
        app.scriptPreferences.userInteractionLevel = UserInteractionLevels.NEVER_INTERACT;
        app.scriptPreferences.enableRedraw         = false;
        app.linkingPreferences.checkLinksAtOpen    = false;
        app.linkingPreferences.findMissingLinksAtOpen = false;
        try { app.preflightOptions.preflightOn = false; } catch (e) {}   // 대용량 문서 preflight blocking 방지
        try { app.generalPreferences.ungroupRemembersLayers = false; } catch (e) {}

        _marker(ctx.outputDir, "00_start");
        writeProgress(ctx.outputDir, "close_docs", 0, 0);

        // 0. 이전 배치에서 닫히지 않은 문서 정리
        try { while (app.documents.length > 0) app.documents[0].close(SaveOptions.NO); } catch (e) {}

        // 1. 문서 열기
        writeProgress(ctx.outputDir, "open", 0, 0);
        _marker(ctx.outputDir, "01_open");
        var inddFile = File(ctx.inddPath);
        if (!inddFile.exists) {
            var parentFolder = inddFile.parent;
            if (!parentFolder || !parentFolder.exists)
                throw new Error("상위 폴더에 접근할 수 없습니다: " + (parentFolder ? parentFolder.fsName : ctx.inddPath)
                    + "\nmacOS 설정 > 개인정보 보호 > 파일 및 폴더에서 InDesign의 접근 권한을 확인해주세요.");
            throw new Error("INDD 파일을 찾을 수 없습니다: " + ctx.inddPath);
        }
        doc = app.open(inddFile, false);
        // 눈금자 원점을 SPREAD로 고정 (geometricBounds가 스프레드 전역 좌표가 되도록)
        try { doc.viewPreferences.rulerOrigin = RulerOrigin.SPREAD_ORIGIN; } catch (e) {}

        // 1.5. 링크 업데이트 (PNG 렌더링 전 — 원본 이미지 연결)
        _fixLinks(doc, ctx.inddPath);

        _computePageRange(doc, ctx);

        if (!ctx.pdfOnly) {
            // SPEC-030 B.2: pre_scan 모드 — IDML export/allPageItems 없이 hash만 빠르게 생성
            if (ctx.extractMode === "pre_scan") {
                _marker(ctx.outputDir, "03b_pageHashes");
                try {
                    var _pdFast = buildPageDataFast(doc, ctx.startPage, ctx.endPage);
                    var _phF = File(ctx.outputDir + "/page_hashes.json");
                    _phF.encoding = "UTF-8"; _phF.open("w"); _phF.write(JSON.stringify(_pdFast.hashes)); _phF.close();
                    var _pmF = File(ctx.outputDir + "/page_item_map.json");
                    _pmF.encoding = "UTF-8"; _pmF.open("w"); _pmF.write(JSON.stringify(_pdFast.itemMap)); _pmF.close();
                } catch (eHashF) { $.writeln("[pageHash fast] error: " + eHashF); }
                doc.close(SaveOptions.NO); doc = null;
                writeDone(ctx.outputDir, "ok", null); return;
            } else {
            // 2. IDML 내보내기 (전체 — API 제한으로 범위 지정 불가)
            // chunkMode=true(후속 청크)면 이미 청크1에서 IDML이 생성되었으므로 생략
            _marker(ctx.outputDir, "02_idml_export");
            if (!ctx.chunkMode) {
                writeProgress(ctx.outputDir, "idml", 0, ctx.pageCount);
                doc.exportFile(ExportFormat.INDESIGN_MARKUP, File(ctx.outputDir + "/output.idml"));
            }

            // 2.5. allPageItems 수집 — 청크 범위(startPage..endPage)만 수집
            // doc.allPageItems는 문서 전체를 로드하므로 26p 문서에서 10p 청크도 전체 메모리 점유 → -609
            _marker(ctx.outputDir, "03_allPageItems");
            var allItems = [];
            var _aiEnd = Math.min(ctx.endPage, doc.pages.length);
            for (var _aip = ctx.startPage - 1; _aip < _aiEnd; _aip++) {
                try {
                    var _pgItems = doc.pages[_aip].allPageItems;
                    for (var _pii = 0; _pii < _pgItems.length; _pii++) allItems.push(_pgItems[_pii]);
                } catch (e) {}
            }

            // SPEC-030 B.2: 페이지 해시 + 아이템 맵 → page_hashes.json / page_item_map.json (캐시 저장용)
            _marker(ctx.outputDir, "03b_pageHashes");
            try {
                var _pd = buildPageData(doc, ctx.startPage, ctx.endPage, allItems);
                var _ph = File(ctx.outputDir + "/page_hashes.json");
                _ph.encoding = "UTF-8"; _ph.open("w"); _ph.write(JSON.stringify(_pd.hashes)); _ph.close();
                var _pm = File(ctx.outputDir + "/page_item_map.json");
                _pm.encoding = "UTF-8"; _pm.open("w"); _pm.write(JSON.stringify(_pd.itemMap)); _pm.close();
            } catch (eHash) { $.writeln("[pageHash] error: " + eHash); }

            _runRenderPhases(doc, ctx, allItems);
            allItems = null; try { $.gc(); } catch (e) {}
            } // end else (not pre_scan)
        }

        writeProgress(ctx.outputDir, "pdf", ctx.rangePageCount, ctx.rangePageCount);

        // 4. 링크 업데이트 (PDF 고해상도 이미지용)
        _fixLinks(doc, ctx.inddPath);

        // 5. PDF 프리뷰
        _exportPdf(doc, ctx);
        _marker(ctx.outputDir, "13_done");

        doc.close(SaveOptions.NO); doc = null;
        writeDone(ctx.outputDir, "ok", null);
    } catch (e) {
        writeDone(ctx.outputDir, "error", e.message);
    } finally {
        if (doc) { try { doc.close(SaveOptions.NO); } catch (e2) {} }
        app.scriptPreferences.userInteractionLevel    = savedInteractionLevel;
        app.scriptPreferences.enableRedraw            = savedEnableRedraw;
        app.linkingPreferences.checkLinksAtOpen       = savedCheckLinks;
        app.linkingPreferences.findMissingLinksAtOpen = savedFindMissing;
        try { app.preflightOptions.preflightOn = true; } catch (e) {}
    }
}

// =============================================================================
// SECTION 4: CLASSIFIER
// TextFrame 분류 / 배지 판별 / 배경 탐색 / 레이어 검사
// 원래 RENDER PIPELINE 뒤에 위치했으나 선언 호이스팅으로 동작 동일 — 가독성 위해 앞으로 이동
// =============================================================================

/**
 * TextFrame의 경로가 타원형(oval)인지 확인한다.
 *
 * 1차: Bezier 핸들 비교 — 핸들이 앵커와 다르면 곡선
 * 2차: 앵커 위치 비교 — 타원의 4개 앵커는 경계 상자의 각 변 중앙에 위치함
 *      (직사각형 앵커는 코너에 위치하므로 구별 가능)
 */
function hasNonRectangularPath(item) {
    try {
        var paths = item.paths;
        if (!paths || paths.length === 0) return false;
        var path = paths[0];
        if (!path) return false;
        var pts = path.pathPoints;
        if (!pts || pts.length === 0) return false;

        // 1차: Bezier 핸들 비교
        try {
            for (var i = 0; i < pts.length; i++) {
                var ap = pts[i].anchor;
                var ld = pts[i].leftDirection;
                var rd = pts[i].rightDirection;
                if (ap && ld && (Math.abs(ld[0] - ap[0]) > 0.5 || Math.abs(ld[1] - ap[1]) > 0.5)) return true;
                if (ap && rd && (Math.abs(rd[0] - ap[0]) > 0.5 || Math.abs(rd[1] - ap[1]) > 0.5)) return true;
            }
        } catch (e1) {}

        // 2차: 앵커 위치로 판별 — 타원은 앵커 4개가 각 변 중앙에 위치
        try {
            if (pts.length === 4) {
                var gb = item.geometricBounds; // [top, left, bottom, right]
                var cx = (gb[1] + gb[3]) / 2;
                var cy = (gb[0] + gb[2]) / 2;
                var TOL = (gb[3] - gb[1]) * 0.05 + 1; // 폭의 5% 또는 최소 1pt
                var extremeCount = 0;
                for (var j = 0; j < 4; j++) {
                    var a = pts[j].anchor; // [x, y]
                    var onEdge =
                        (Math.abs(a[1] - gb[0]) < TOL && Math.abs(a[0] - cx) < TOL) || // top
                        (Math.abs(a[0] - gb[3]) < TOL && Math.abs(a[1] - cy) < TOL) || // right
                        (Math.abs(a[1] - gb[2]) < TOL && Math.abs(a[0] - cx) < TOL) || // bottom
                        (Math.abs(a[0] - gb[1]) < TOL && Math.abs(a[1] - cy) < TOL);   // left
                    if (onEdge) extremeCount++;
                }
                if (extremeCount === 4) return true;
            }
        } catch (e2) {}

    } catch (e) {}
    return false;
}

function hasVisibleFill(item) {
    try {
        var fc = item.fillColor;
        var name = fc ? fc.name : "None";
        if (!name || name === "None" || name === "[None]") return false;
        try {
            var opacity = item.fillTransparencySettings.blendingSettings.opacity;
            if (opacity !== undefined && opacity !== null && opacity <= 0) return false;
        } catch (eOpacity) {}
        return true;
    } catch (e) {}
    return false;
}

function hasVisibleStroke(item) {
    try {
        var sc = item.strokeColor;
        var name = sc ? sc.name : "None";
        var sw = item.strokeWeight || 0;
        if (!name || name === "None" || name === "[None]" || sw <= 0) return false;
        try {
            var opacity = item.strokeTransparencySettings.blendingSettings.opacity;
            if (opacity !== undefined && opacity !== null && opacity <= 0) return false;
        } catch (eOpacity) {}
        return true;
    } catch (e) {}
    return false;
}

function exportTextFrameShellFallbackShape(item, parentPage, outFile) {
    var shell = null;
    try {
        var gb = item.geometricBounds;
        shell = parentPage.rectangles.add({ geometricBounds: gb });
        try { shell.fillColor = item.fillColor; } catch (eFill) {}
        try { shell.fillTint = item.fillTint; } catch (eTint) {}
        try { shell.strokeColor = item.strokeColor; } catch (eStroke) {}
        try { shell.strokeTint = item.strokeTint; } catch (eStrokeTint) {}
        try { shell.strokeWeight = item.strokeWeight; } catch (eStrokeWeight) {}
        try {
            shell.transparencySettings.blendingSettings.opacity =
                item.transparencySettings.blendingSettings.opacity;
        } catch (eOpacity) {}

        // Preserve rounded-corner text frame shells such as large Paper backgrounds.
        try { shell.topLeftCornerOption = item.topLeftCornerOption; } catch (eTLO) {}
        try { shell.topRightCornerOption = item.topRightCornerOption; } catch (eTRO) {}
        try { shell.bottomLeftCornerOption = item.bottomLeftCornerOption; } catch (eBLO) {}
        try { shell.bottomRightCornerOption = item.bottomRightCornerOption; } catch (eBRO) {}
        try { shell.topLeftCornerRadius = item.topLeftCornerRadius; } catch (eTLR) {}
        try { shell.topRightCornerRadius = item.topRightCornerRadius; } catch (eTRR) {}
        try { shell.bottomLeftCornerRadius = item.bottomLeftCornerRadius; } catch (eBLR) {}
        try { shell.bottomRightCornerRadius = item.bottomRightCornerRadius; } catch (eBRR) {}

        shell.exportFile(ExportFormat.PNG_FORMAT, outFile);
        return true;
    } catch (e) {
        return false;
    } finally {
        try { if (shell) shell.remove(); } catch (e2) {}
    }
}

function hasRoundedTextFrameCorners(item) {
    function _cornerRadiusValue(v) {
        try {
            var n = Number(v);
            if (!isNaN(n)) return n;
        } catch (e0) {}
        try {
            var parsed = parseFloat(String(v));
            return isNaN(parsed) ? 0 : parsed;
        } catch (e1) {}
        return 0;
    }
    function _isRoundedOption(v) {
        try {
            var s = String(v || "");
            if (!s || s === "CornerOptions.NONE") return false;
            return s.indexOf("ROUNDED") >= 0
                || s.indexOf("FANCY") >= 0
                || s.indexOf("BEVEL") >= 0
                || s.indexOf("INSET") >= 0;
        } catch (e2) {}
        return false;
    }
    try {
        var tl = _cornerRadiusValue(item.topLeftCornerRadius);
        var tr = _cornerRadiusValue(item.topRightCornerRadius);
        var bl = _cornerRadiusValue(item.bottomLeftCornerRadius);
        var br = _cornerRadiusValue(item.bottomRightCornerRadius);
        if (tl > 0.1 || tr > 0.1 || bl > 0.1 || br > 0.1) return true;
    } catch (e) {}
    try {
        if (_isRoundedOption(item.topLeftCornerOption)
                || _isRoundedOption(item.topRightCornerOption)
                || _isRoundedOption(item.bottomLeftCornerOption)
                || _isRoundedOption(item.bottomRightCornerOption)) {
            return true;
        }
    } catch (e2) {}
    return false;
}

function visibleTextLengthOfTextFrame(tf) {
    try {
        var text = tf.contents || "";
        text = String(text).replace(/[\s\r\n\t\u0016\u0018\u0003\uFFFC﻿]/g, "");
        return text.length;
    } catch (e) {}
    return 0;
}

function boundsOverlapArea(a, b) {
    if (!a || !b) return 0;
    var left = Math.max(a[1], b[1]);
    var top = Math.max(a[0], b[0]);
    var right = Math.min(a[3], b[3]);
    var bottom = Math.min(a[2], b[2]);
    var w = right - left;
    var h = bottom - top;
    return w > 0 && h > 0 ? w * h : 0;
}

function boundsArea(b) {
    if (!b) return 0;
    var w = b[3] - b[1];
    var h = b[2] - b[0];
    return w > 0 && h > 0 ? w * h : 0;
}

function shouldExportRectStrokeTextFrameShell(item, allItems) {
    var gb = null;
    try { gb = item.geometricBounds; } catch (e) {}
    if (!gb) return false;
    var tfW = Math.abs(gb[3] - gb[1]);
    var tfH = Math.abs(gb[2] - gb[0]);
    if (tfW < 10 || tfH < 10) return false;

    // Large layout frames are real visual shells even when rectangular.
    if (tfW >= 50 && tfH >= 45) return true;

    // Rounded stroke-only frames are commonly worksheet answer/layout shells.
    if (hasRoundedTextFrameCorners(item) && tfW >= 30 && tfH >= 20) return true;

    // A rectangular stroke-only empty TF that contains semantic text frames is
    // a layout shell. Preserve it instead of relying on brittle absolute size
    // thresholds.
    try {
        var page = item.parentPage;
        for (var i = 0; i < allItems.length; i++) {
            var other = allItems[i];
            if (!other || other === item || other.constructor.name !== "TextFrame") continue;
            try { if (other.parentPage !== page) continue; } catch (ePage) {}
            if (visibleTextLengthOfTextFrame(other) < 2) continue;
            var ob = null;
            try { ob = other.geometricBounds; } catch (eOb) {}
            var oa = boundsArea(ob);
            if (oa <= 0) continue;
            if (boundsOverlapArea(gb, ob) / oa >= 0.10) return true;
        }
    } catch (e3) {}

    return false;
}

/**
 * editable TextFrame의 fill/stroke 시각 요소만 PNG로 내보낸다.
 * 텍스트와 인라인 객체는 HWPX TF가 소유하므로 복제본의 contents를 비워 중복을 막는다.
 */
function exportEditableTextFrameVisualShells(doc, outputDir, startPage, endPage, decoChildIds, editableIds, allItems) {
    var renderDir = Folder(outputDir + "/rendered_frames");
    renderDir.create();

    var results = [];

    for (var i = 0; i < allItems.length; i++) {
        var item = allItems[i];
        if (item.constructor.name !== "TextFrame") continue;

        var domId = item.id;
        if (isOnHiddenLayer(item)) continue;
        var _tfHasContent = false;
        try { _tfHasContent = item.contents.replace(/[\s﻿]/g, "").length > 0; } catch (e) {}
        if (decoChildIds && decoChildIds[domId] && _tfHasContent) continue;
        // editable TF이거나 빈 TF(텍스트 없음)인 경우 처리.
        // 내용 있는 비-편집 TF는 exportRenderedTextFrames에서 이미 처리됨.
        var _tfIsEditable = editableIds && editableIds[domId];
        if (!_tfIsEditable) {
            if (_tfHasContent) continue;
        }

        var hasFill = hasVisibleFill(item);
        var hasStroke = hasVisibleStroke(item);
        if (!hasFill && !hasStroke) continue;

        // stroke-only TF는 이전 정책처럼 비직사각형/대형 윤곽선만 보존한다.
        // fill이 있는 TF는 배경/말풍선/라벨로 쓰이는 경우가 많아 형태와 관계없이 보존한다.
        var isNonRect = hasNonRectangularPath(item);
        if (!hasFill && !isNonRect) {
            // 직사각 stroke-only TF도 라운드 코너/내부 semantic TF가 있으면
            // 레이아웃 shell로 보존한다. 50pt 절대 문턱은 페이지별로 쉽게 흔들린다.
            if (!shouldExportRectStrokeTextFrameShell(item, allItems)) continue;
        }

        var parentPage = null;
        try { parentPage = item.parentPage; } catch (e) {}
        if (!parentPage) continue;
        var pgIdx = parentPage.documentOffset + 1;
        if (pgIdx < startPage || pgIdx > endPage) continue;

        // 최소 크기 필터 (10pt 미만 무시)
        try {
            var gb = item.geometricBounds;
            if ((gb[3] - gb[1]) < 10 && (gb[2] - gb[0]) < 10) continue;
        } catch (e) {}

        // 복제본에서 텍스트/인라인 객체를 비우고 fill/stroke만 PNG 내보내기
        try {
            var fileName = "tf_shell_" + domId + ".png";
            var outFile = File(renderDir + "/" + fileName);

            var dup = item.duplicate();
            try {
                dup.contents = "";
                dup.exportFile(ExportFormat.PNG_FORMAT, outFile);
            } finally {
                try { dup.remove(); } catch (e2) {}
            }
            try {
                if (!outFile.exists || outFile.length < 1024) {
                    exportTextFrameShellFallbackShape(item, parentPage, outFile);
                }
            } catch (eFallback) {
                exportTextFrameShellFallbackShape(item, parentPage, outFile);
            }

            var bounds = null;
            try { bounds = arrCopy(item.visibleBounds); } catch (e) {}
            if (!bounds) try { bounds = arrCopy(item.geometricBounds); } catch (e) {}
            if (bounds) _toPageRelativeBounds(bounds, parentPage);
            var _z = 0;
            try { _z = getItemZOrder(item); } catch (e) {}

            results.push(applyRenderOwnership({
                id: domId,
                file: "rendered_frames/" + fileName,
                bounds: bounds,
                pageIndex: parentPage.documentOffset,
                zOrder: _z
            }, item, {
                textHiddenBeforeExport: true,
                textOwner: "hwpx_tf",
                containsText: false,
                containsEditableText: false,
                placementAllowed: true,
                reason: "editable_textframe_visual_shell"
            }));
        } catch (e) {}
    }

    return results;
}

/**
 * 아이템 또는 부모 체인에 숨김 레이어가 있는지 검사한다.
 */
function isOnHiddenLayer(item) {
    try {
        var itemId = item.id;
        if (_hiddenLayerCache && _hiddenLayerCache[itemId] !== undefined) return _hiddenLayerCache[itemId];
        var result = false;
        var cur = item;
        while (cur) {
            try {
                if (cur.itemLayer && !cur.itemLayer.visible) { result = true; break; }
            } catch (e) {}
            try { cur = cur.parent; } catch (e) { break; }
            if (!cur || cur.constructor.name === "Spread"
                || cur.constructor.name === "Page"
                || cur.constructor.name === "Document") break;
        }
        if (_hiddenLayerCache) _hiddenLayerCache[itemId] = result;
        return result;
    } catch (e) {}
    return false;
}


/**
 * 그룹 전체는 뱃지 조건에 안 맞지만 내부에 뱃지 패턴(도형 + 짧은 숫자 TF)이
 * 있는 경우를 찾는다. 예: 질문 그룹 = {Oval + "2" TF + 긴 질문 TF}.
 *
 * 감지되면 (badgeShape, badgeTextFrame) 페어를 반환. 없으면 null.
 * 렌더링 단계에서 페어 외 형제를 임시 숨긴 뒤 부모 그룹을 exportFile 한다.
 */
/**
 * TextFrame을 분류한다.
 * @param {PageItem} item - allPageItems 항목
 * @return {string|null}
 *   "background" - 배경 PNG에 포함 (non-editable)
 *   "editable"   - 배경에서 숨기고 HWPX 글상자로 변환
 *   null         - TextFrame이 아님 (건너뜀)
 */
function classifyTextFrame(item) {
    if (item.constructor.name !== "TextFrame") return null;
    if (isOnHiddenLayer(item)) return "background";
    try { if (item.nonprinting) return "background"; } catch (e) {}
    try { if (!item.parentPage) return "background"; } catch (e) {}
    return "editable";
}

// _runRenderPhases에서 allItems 전체를 한 번만 분류한 결과를 조회. 캐시 미초기화 시 직접 호출로 폴백.
function classifyTextFrameCached(item) {
    if (_ctfCache === null) return classifyTextFrame(item);
    var id = item.id;
    if (_ctfCache[id] === undefined) _ctfCache[id] = classifyTextFrame(item);
    return _ctfCache[id];
}


// =============================================================================
// SECTION 5: RENDER PIPELINE
// PNG export — 복합 그래픽 / PDF / 이미지 / 데코 / 벡터
// =============================================================================

// --- 렌더링 시 TextFrame 텍스트 제거 헬퍼 ---

function hideTextFrames(renderTarget) {
    // TextFrame 자체를 숨기면 fill/stroke로 만든 박스 그래픽까지 사라진다.
    // content opacity만 0으로 낮춰 텍스트 픽셀만 제거하고 프레임 그래픽은 보존한다.
    var saved = [];
    try {
        var nested = renderTarget.allPageItems;
        for (var hi = 0; hi < nested.length; hi++) {
            if (nested[hi].constructor.name === "TextFrame") {
                try {
                    var tf = nested[hi];
                    var contentBlend = tf.contentTransparencySettings.blendingSettings;
                    var oldOpacity = contentBlend.opacity;
                    contentBlend.opacity = 0;
                    saved.push({ tf: tf, mode: "contentOpacity", opacity: oldOpacity });
                } catch (eOpacity) {
                    // Very old/odd TextFrame objects may not expose content transparency.
                    // Use visible=false only as a last resort.
                    try {
                        var tfFallback = nested[hi];
                        var wasVisible = tfFallback.visible;
                        tfFallback.visible = false;
                        saved.push({ tf: tfFallback, mode: "visible", wasVisible: wasVisible });
                    } catch (eVisible) {}
                }
            }
        }
    } catch (e) {}
    return saved;
}

function hideRepeatedCellBackgroundCandidates(renderTarget) {
    // Mixed groups can contain both a decorative title/icon and InDesign table-cell
    // background rectangles. Later Java absorbs those cell fills into HWP table cells,
    // so keeping them in the group PNG creates duplicate boxes. Hide only repeated,
    // fill-only, axis-aligned rectangular backgrounds during PNG export.
    var saved = [];
    try {
        var nested = renderTarget.allPageItems;
        var buckets = {};
        for (var i = 0; i < nested.length; i++) {
            var it = nested[i];
            var cn = it.constructor.name;
            if (cn !== "Rectangle" && cn !== "TextFrame") continue;
            if (isOnHiddenLayer(it)) continue;
            try { if (it.nonprinting) continue; } catch (eNp) {}
            try { if (Math.abs(it.rotationAngle || 0) > 0.1) continue; } catch (eRot) {}

            var fillName = null;
            try { fillName = it.fillColor ? it.fillColor.name : null; } catch (eFill) {}
            if (!fillName || fillName === "None" || fillName === "[None]") continue;

            var strokeName = null;
            try { strokeName = it.strokeColor ? it.strokeColor.name : null; } catch (eStroke) {}
            var strokeWeight = 0;
            try { strokeWeight = it.strokeWeight || 0; } catch (eSw) {}
            if (strokeName && strokeName !== "None" && strokeName !== "[None]" && strokeWeight > 0.01) continue;

            var b = null;
            try { b = it.geometricBounds; } catch (eB) {}
            if (!b || b.length < 4) continue;
            var h = b[2] - b[0], w = b[3] - b[1];
            if (h <= 1.0 || w <= 1.0) continue;
            if (h > 28 || w > 90) continue;

            var key = fillName;
            if (!buckets[key]) buckets[key] = [];
            buckets[key].push({ item: it, bounds: [b[0], b[1], b[2], b[3]], width: w, height: h });
        }

        for (var key in buckets) {
            if (!buckets.hasOwnProperty(key)) continue;
            var arr = buckets[key];
            if (arr.length < 3) continue;
            arr.sort(function(a, b) {
                if (Math.abs(a.bounds[0] - b.bounds[0]) > 1) return a.bounds[0] - b.bounds[0];
                return a.bounds[1] - b.bounds[1];
            });

            // Require a row/list-like repeated structure: same top or same left
            // among at least three candidates. This avoids hiding isolated label pills.
            var topBins = {}, leftBins = {};
            for (var ai = 0; ai < arr.length; ai++) {
                var topKey = String(Math.round(arr[ai].bounds[0] / 2));
                var leftKey = String(Math.round(arr[ai].bounds[1] / 2));
                topBins[topKey] = (topBins[topKey] || 0) + 1;
                leftBins[leftKey] = (leftBins[leftKey] || 0) + 1;
            }
            var repeated = false;
            for (var tk in topBins) if (topBins.hasOwnProperty(tk) && topBins[tk] >= 3) repeated = true;
            for (var lk in leftBins) if (leftBins.hasOwnProperty(lk) && leftBins[lk] >= 3) repeated = true;
            if (!repeated) continue;

            for (var si = 0; si < arr.length; si++) {
                var item = arr[si].item;
                var state = { item: item, mode: "cellBg" };
                var changed = false;
                try {
                    state.fillColor = item.fillColor;
                    item.fillColor = app.activeDocument.swatches.itemByName("None");
                    changed = true;
                } catch (eNoneFill) {
                    try {
                        state.fillColor = item.fillColor;
                        item.fillColor = app.activeDocument.swatches.itemByName("[None]");
                        changed = true;
                    } catch (eNoneFill2) {}
                }
                try {
                    var blend = item.transparencySettings.blendingSettings;
                    state.opacity = blend.opacity;
                    blend.opacity = 0;
                    changed = true;
                } catch (eOp) {}
                try {
                    var fillBlend = item.fillTransparencySettings.blendingSettings;
                    state.fillOpacity = fillBlend.opacity;
                    fillBlend.opacity = 0;
                    changed = true;
                } catch (eFillOp) {}
                try {
                    var strokeBlend = item.strokeTransparencySettings.blendingSettings;
                    state.strokeOpacity = strokeBlend.opacity;
                    strokeBlend.opacity = 0;
                    changed = true;
                } catch (eStrokeOp) {}
                if (!changed) {
                    try {
                        state.wasVisible = item.visible;
                        item.visible = false;
                        state.mode = "visible";
                        changed = true;
                    } catch (eVis) {}
                }
                if (changed) {
                    saved.push(state);
                }
            }
        }
    } catch (e) {}
    return saved;
}

function restoreRepeatedCellBackgroundCandidates(saved) {
    if (!saved) return;
    for (var i = saved.length - 1; i >= 0; i--) {
        try {
            if (saved[i].mode === "cellBg") {
                if (saved[i].fillColor !== undefined) {
                    saved[i].item.fillColor = saved[i].fillColor;
                }
                if (saved[i].opacity !== undefined) {
                    saved[i].item.transparencySettings.blendingSettings.opacity = saved[i].opacity;
                }
                if (saved[i].fillOpacity !== undefined) {
                    saved[i].item.fillTransparencySettings.blendingSettings.opacity = saved[i].fillOpacity;
                }
                if (saved[i].strokeOpacity !== undefined) {
                    saved[i].item.strokeTransparencySettings.blendingSettings.opacity = saved[i].strokeOpacity;
                }
            } else if (saved[i].mode === "visible") {
                saved[i].item.visible = saved[i].wasVisible;
            } else if (saved[i].mode === "opacity") {
                saved[i].item.transparencySettings.blendingSettings.opacity = saved[i].opacity;
            }
        } catch (e) {}
    }
}

function _isTopLevelInlineVisualItem(item) {
    try {
        if (!item || item.constructor.name === "TextFrame") return false;
        if (!isInlineItem(item)) return false;
        var p = item.parent;
        if (p && (p.constructor.name === "Group"
                || p.constructor.name === "Rectangle"
                || p.constructor.name === "Polygon"
                || p.constructor.name === "Oval")
                && isInlineItem(p)) {
            return false;
        }
        return true;
    } catch (e) {}
    return false;
}

function collectTfInlineVisualIds(renderTarget) {
    var ids = [], seen = {};
    if (!renderTarget) return ids;

    var nestedIds = {};
    try { nestedIds[renderTarget.id] = true; } catch (e) {}
    try {
        var nested = renderTarget.allPageItems;
        for (var ni = 0; ni < nested.length; ni++) {
            try { nestedIds[nested[ni].id] = true; } catch (e2) {}
        }
    } catch (e) {}

    function collectFromStory(tf) {
        try {
            if (classifyTextFrameCached(tf) !== "editable") return;
            var storyItems = tf.parentStory.allPageItems;
            for (var si = 0; si < storyItems.length; si++) {
                var it = storyItems[si];
                if (!_isTopLevelInlineVisualItem(it)) continue;
                if (!nestedIds[it.id]) continue;
                _pushUniqueId(ids, seen, it.id);
            }
        } catch (e) {}
    }

    try {
        if (renderTarget.constructor.name === "TextFrame") collectFromStory(renderTarget);
    } catch (e) {}
    try {
        var all = renderTarget.allPageItems;
        for (var ai = 0; ai < all.length; ai++) {
            try {
                if (all[ai].constructor.name === "TextFrame") collectFromStory(all[ai]);
            } catch (e2) {}
        }
    } catch (e) {}
    return ids;
}

function hideTextFrameOwnedInlineVisuals(renderTarget) {
    var saved = [];
    var ids = collectTfInlineVisualIds(renderTarget);
    if (!ids || ids.length === 0) return saved;
    var idMap = {};
    for (var ii = 0; ii < ids.length; ii++) idMap[ids[ii].toString()] = true;

    try {
        var nested = renderTarget.allPageItems;
        for (var ni = 0; ni < nested.length; ni++) {
            var it = nested[ni];
            if (!idMap[it.id.toString()]) continue;
            var wasVisible = true;
            try { wasVisible = it.visible; } catch (eVis) {}
            try {
                it.visible = false;
                saved.push({ tf: it, mode: "visible", wasVisible: wasVisible });
            } catch (eHide) {}
        }
    } catch (e) {}
    return saved;
}

function hideTextFramesAndOwnedInlineVisuals(renderTarget) {
    var saved = hideTextFrames(renderTarget);
    var savedInline = hideTextFrameOwnedInlineVisuals(renderTarget);
    for (var i = 0; i < savedInline.length; i++) saved.push(savedInline[i]);
    return saved;
}

function restoreTextFrames(saved) {
    for (var ri = 0; ri < saved.length; ri++) {
        try {
            if (saved[ri].mode === "contentOpacity") {
                saved[ri].tf.contentTransparencySettings.blendingSettings.opacity = saved[ri].opacity;
            } else {
                saved[ri].tf.visible = saved[ri].wasVisible;
            }
        } catch (e) {}
    }
}

function _pushUniqueId(arr, seen, id) {
    if (id === undefined || id === null) return;
    var key = id.toString();
    if (seen[key]) return;
    seen[key] = true;
    arr.push(id);
}

function _textFrameHasContent(tf) {
    try {
        var txt = (tf.contents + "").replace(/[\s\r\n\uFEFF\uFFFC\u0016]/g, "");
        return txt.length > 0;
    } catch (e) {}
    return false;
}

function _collectSourceObjectIds(item) {
    var ids = [], seen = {};
    try { _pushUniqueId(ids, seen, item.id); } catch (e) {}
    try {
        var nested = item.allPageItems;
        for (var i = 0; i < nested.length; i++) {
            try { _pushUniqueId(ids, seen, nested[i].id); } catch (e2) {}
        }
    } catch (e) {}
    return ids;
}

function _collectTextFrameIds(item, editableOnly, requireContent) {
    var ids = [], seen = {};
    function visit(tf) {
        try {
            if (requireContent && !_textFrameHasContent(tf)) return;
            if (editableOnly && classifyTextFrameCached(tf) !== "editable") return;
            _pushUniqueId(ids, seen, tf.id);
        } catch (e) {}
    }
    try { if (item.constructor.name === "TextFrame") visit(item); } catch (e) {}
    try {
        var nested = item.allPageItems;
        for (var i = 0; i < nested.length; i++) {
            try {
                if (nested[i].constructor.name === "TextFrame") visit(nested[i]);
            } catch (e2) {}
        }
    } catch (e) {}
    return ids;
}

function _collectVisualOnlyChildIds(item) {
    var ids = [], seen = {};
    try {
        var nested = item.allPageItems;
        for (var i = 0; i < nested.length; i++) {
            try {
                if (nested[i].constructor.name !== "TextFrame") {
                    _pushUniqueId(ids, seen, nested[i].id);
                }
            } catch (e2) {}
        }
    } catch (e) {}
    return ids;
}

function _filterIds(ids, removeIds) {
    if (!ids || ids.length === 0 || !removeIds || removeIds.length === 0) return ids;
    var remove = {};
    for (var ri = 0; ri < removeIds.length; ri++) remove[removeIds[ri].toString()] = true;
    var out = [];
    for (var ii = 0; ii < ids.length; ii++) {
        if (!remove[ids[ii].toString()]) out.push(ids[ii]);
    }
    return out;
}

function applyRenderOwnership(entry, renderTarget, opts) {
    if (!entry) return entry;
    opts = opts || {};
    var sourceIds = opts.sourceObjectIds || (renderTarget ? _collectSourceObjectIds(renderTarget) : []);
    var editableTfIds = opts.editableTextFrameIds;
    if (editableTfIds === undefined || editableTfIds === null) {
        editableTfIds = renderTarget ? _collectTextFrameIds(renderTarget, true, true) : [];
    }
    var allTextIds = opts.textFrameIds;
    if (allTextIds === undefined || allTextIds === null) {
        allTextIds = renderTarget ? _collectTextFrameIds(renderTarget, false, true) : [];
    }
    var textHidden = opts.textHiddenBeforeExport === true;
    var textOwner = opts.textOwner;
    if (!textOwner) {
        textOwner = editableTfIds.length > 0 ? "hwpx_tf" : (allTextIds.length > 0 ? "indesign_png" : "none");
    }
    var containsText = opts.containsText;
    if (containsText === undefined || containsText === null) containsText = !textHidden && allTextIds.length > 0;
    var containsEditableText = opts.containsEditableText;
    if (containsEditableText === undefined || containsEditableText === null) {
        containsEditableText = !textHidden && editableTfIds.length > 0;
    }
    var placementAllowed = opts.placementAllowed;
    if (placementAllowed === undefined || placementAllowed === null) {
        placementAllowed = !(containsEditableText && textOwner !== "indesign_png");
    }

    entry.visualOwner = opts.visualOwner || "indesign_png";
    entry.textOwner = textOwner;
    entry.containsText = containsText ? true : false;
    entry.containsEditableText = containsEditableText ? true : false;
    entry.placementAllowed = placementAllowed ? true : false;
    entry.overlapPolicy = opts.overlapPolicy || entry.overlapPolicy || "in_front_of_text";
    entry.reason = opts.reason || entry.reason || (textHidden ? "visual_only_text_hidden" : "ownership_default");
    entry.placementRole = opts.placementRole || entry.placementRole || inferPlacementRole(entry, textOwner, textHidden);
    entry.zSource = opts.zSource || entry.zSource
            || ((sourceIds && sourceIds.length > 0) ? "sourceObjectIds" : "absoluteZOrderIndex");
    if (sourceIds && sourceIds.length > 0) entry.sourceObjectIds = sourceIds;
    if (editableTfIds && editableTfIds.length > 0) entry.editableTextFrameIds = editableTfIds;
    var visualOnlyIds = opts.visualOnlyChildIds;
    if (visualOnlyIds === undefined || visualOnlyIds === null) {
        visualOnlyIds = renderTarget ? _collectVisualOnlyChildIds(renderTarget) : [];
    }
    visualOnlyIds = _filterIds(visualOnlyIds, opts.tfInlineVisualIds);
    if (visualOnlyIds && visualOnlyIds.length > 0) entry.visualOnlyChildIds = visualOnlyIds;
    if (opts.tfInlineVisualIds && opts.tfInlineVisualIds.length > 0) {
        entry.tfInlineVisualIds = opts.tfInlineVisualIds;
    }
    if (textHidden) entry.textHiddenBeforeExport = true;
    return entry;
}

function inferPlacementRole(entry, textOwner, textHidden) {
    var reason = entry && entry.reason ? entry.reason : "";
    var type = entry && entry.type ? entry.type : "";
    if (reason === "editable_textframe_visual_shell") return "tf_visual_shell";
    if (reason === "master_graphic" || reason === "master_page_graphic" || reason === "master_side_composite") return "master_graphic";
    if (type === "inline_object") return "inline_object";
    if (textOwner === "indesign_png") return "atomic_text_visual";
    if (textHidden) return "visual_only_png";
    return "floating_visual";
}

/**
 * renderTarget 자손 중 badge_group인 Group을 숨긴다.
 * deco 그룹 렌더링 시 badge PNG가 이중으로 포함되지 않도록 하기 위함.
 * badge_group 자체의 visible을 false로 설정하고 복원 정보를 반환.
 */

function getItemZOrder(item) {
    try { return item.absoluteZOrderIndex; } catch(e) { return 0; }
}

/**
 * 복합 장식 그래픽 프레임을 PNG로 렌더링한다.
 */
function exportComplexGraphicFrames(doc, outputDir, startPage, endPage, allItems) {
    var renderDir = Folder(outputDir + "/rendered_frames");
    renderDir.create();

    var renderedGraphicFrames = [];

    for (var i = 0; i < allItems.length; i++) {
        var item = allItems[i];
        var cName = item.constructor.name;

        if (cName !== "Rectangle" && cName !== "Oval"
            && cName !== "Polygon") continue;
        if (isOnHiddenLayer(item)) continue;

        try {
            if (item.contentType !== ContentType.GRAPHIC_TYPE) continue;
        } catch (e) { continue; }

        var hasPlacedContent = false;
        try { hasPlacedContent = item.images && item.images.length > 0; } catch (e) {}
        if (!hasPlacedContent) {
            try { hasPlacedContent = item.pdfs && item.pdfs.length > 0; } catch (e) {}
        }
        if (!hasPlacedContent) {
            try { hasPlacedContent = item.epss && item.epss.length > 0; } catch (e) {}
        }
        if (hasPlacedContent) continue;

        var hasNestedItems = false;
        try { hasNestedItems = item.allPageItems && item.allPageItems.length > 0; } catch (e) {}
        if (!hasNestedItems) continue;

        var onlyTextFrames = true;
        try {
            var nested = item.allPageItems;
            for (var ni = 0; ni < nested.length; ni++) {
                if (nested[ni].constructor.name !== "TextFrame") {
                    onlyTextFrames = false;
                    break;
                }
            }
        } catch (e) { onlyTextFrames = false; }
        if (onlyTextFrames) continue;

        var parentPage = null;
        try { parentPage = item.parentPage; } catch (e) {}
        if (!parentPage) continue;
        var pgIdx = parentPage.documentOffset + 1;
        if (pgIdx < startPage || pgIdx > endPage) continue;

        // 스프레드를 걸치는 프레임도 export: BackgroundInjector가 페이지별로 크롭/분할 처리함

        var domId = item.id;
        var fileName = "graphic_" + domId + ".png";
        var outFile = File(renderDir + "/" + fileName);
        var _gPageIdx  = parentPage.documentOffset;  // 단순 정수, 이미 검증됨
        var _gZOrder   = 0;   // try 블록 안에서 갱신
        var _gBounds   = null;
        var _gExportOk = false;
        var _gChildIds = [];

        try {
            // bounds를 try 안에서 계산 (visibleBounds/geometricBounds도 예외 가능)
            try { _gBounds = arrCopy(item.visibleBounds); } catch (e) {}
            if (!_gBounds) { try { _gBounds = arrCopy(item.geometricBounds); } catch (e) {} }
            if (_gBounds) _toPageRelativeBounds(_gBounds, parentPage);

            var _gTfInlineVisualIds = collectTfInlineVisualIds(item);
            var hiddenTFs = hideTextFramesAndOwnedInlineVisuals(item);
            try {
                item.exportFile(ExportFormat.PNG_FORMAT, outFile);
                _gExportOk = true;
            } catch (eExp) {
                try { _gExportOk = outFile.exists; } catch (e2) {}
            }

            // zOrder/childIds를 restore 전에 저장 — restore 예외가 push를 막지 않도록.
            if (_gExportOk) {
                try { _gZOrder = getItemZOrder(item); } catch (e) {}
                try {
                    var allNested = item.allPageItems;
                    for (var ci = 0; ci < allNested.length; ci++) {
                        _gChildIds.push(allNested[ci].id);
                    }
                } catch (e2) {}
            }

            try { restoreTextFrames(hiddenTFs); } catch (e) {}
        } catch (e) {
            try { _gExportOk = outFile.exists; } catch (e2) {}
            // outer catch: inner try-catch를 우회한 예외가 있어도 TF 복원
            try { if (hiddenTFs && hiddenTFs.length > 0) restoreTextFrames(hiddenTFs); } catch (e3) {}
        }

        if (_gExportOk) {
            renderedGraphicFrames.push(applyRenderOwnership({
                id: domId,
                file: "rendered_frames/" + fileName,
                bounds: _gBounds,
                pageIndex: _gPageIdx,
                childIds: _gChildIds.length > 0 ? _gChildIds : undefined,
                zOrder: _gZOrder
            }, item, {
                textHiddenBeforeExport: true,
                tfInlineVisualIds: _gTfInlineVisualIds,
                reason: "complex_graphic_text_hidden"
            }));
        }
    }

    return renderedGraphicFrames;
}

/**
 * PDF 배치 프레임을 PNG로 렌더링한다.
 */
/**
 * 이미지 배치 프레임을 처리한다.
 *
 * standalone 프레임 (그룹 없음): 소스 파일 직접 복사 — exportFile 불필요, 메모리 0.
 * 그룹 내 프레임 (텍스트/도형과 함께): exportFile(PNG) + 아이템별 $.gc().
 *
 * 이전 exportFile 전용 방식은 57개 이상 처리 시 InDesign 메모리 고갈 크래시 발생.
 */
function exportImagePlacedFrames(doc, outputDir, startPage, endPage, allItems) {
    var renderDir = Folder(outputDir + "/rendered_frames");
    renderDir.create();

    var renderedImageFrames = [];
    var processedGroupIds = {};

    for (var i = 0; i < allItems.length; i++) {
        var item = allItems[i];
        var cName = item.constructor.name;

        if (cName !== "Rectangle" && cName !== "Oval"
            && cName !== "Polygon") continue;
        if (isOnHiddenLayer(item)) continue;

        var hasPdf = false;
        try { hasPdf = item.pdfs && item.pdfs.length > 0; } catch (e) {}
        var hasImage = false;
        try { hasImage = item.images && item.images.length > 0; } catch (e) {}
        if (!hasImage && !hasPdf) continue;

        var renderTarget = item;
        var isGroupRender = false;
        try {
            if (item.parent && item.parent.constructor.name === "Group") {
                var grp = item.parent;
                if (!processedGroupIds[grp.id]) {
                    renderTarget = grp;
                    isGroupRender = true;
                    processedGroupIds[grp.id] = true;
                } else {
                    continue;
                }
            }
        } catch (e) {}

        var parentPage = null;
        try { parentPage = renderTarget.parentPage; } catch (e) {}
        if (!parentPage) {
            try {
                var p = renderTarget.parent;
                while (p && !parentPage) {
                    try { parentPage = p.parentPage; } catch (e2) {}
                    if (!parentPage) p = p.parent;
                }
            } catch (e) {}
        }
        if (!parentPage) continue;
        var pgIdx = parentPage.documentOffset + 1;
        if (pgIdx < startPage || pgIdx > endPage) continue;

        var domId = renderTarget.id;

        var bounds = null;
        try { bounds = arrCopy(renderTarget.visibleBounds); } catch (e) {}
        if (!bounds) try { bounds = arrCopy(renderTarget.geometricBounds); } catch (e) {}
        if (bounds) _toPageRelativeBounds(bounds, parentPage);

        if (!isGroupRender && hasImage) {
            // standalone: 소스 파일 직접 복사 (exportFile 없음 → 메모리 부하 0)
            var srcPath = null;
            var srcExt = "jpg";
            try {
                var lnk = item.images[0].itemLink;
                if (lnk && lnk.filePath) {
                    srcPath = lnk.filePath;
                    var m = srcPath.match(/\.([^.]+)$/);
                    if (m) srcExt = m[1].toLowerCase();
                }
            } catch (e) {}

            // JPEG/PNG만 직접 복사; 그 외(PSD/AI/EPS)는 exportFile 경로로 fallback
            var isCopyable = (srcExt === "jpg" || srcExt === "jpeg" || srcExt === "png"
                              || srcExt === "gif" || srcExt === "bmp");
            if (srcPath && isCopyable) {
                var srcFile = File(srcPath);
                if (!srcFile.exists) {
                    // 상대 경로 폴백: document folder 기준
                    try {
                        var docFolder = Folder(doc.filePath);
                        srcFile = File(docFolder + "/" + srcPath);
                    } catch (e) {}
                }
                if (srcFile.exists) {
                    var dstFileName = "img_" + domId + "." + srcExt;
                    var dstFile = File(renderDir + "/" + dstFileName);
                    try { srcFile.copy(dstFile); } catch (e) {}
                    renderedImageFrames.push(applyRenderOwnership({
                        id: domId,
                        file: "rendered_frames/" + dstFileName,
                        imageFormat: srcExt,
                        bounds: bounds,
                        pageIndex: parentPage.documentOffset,
                        childImageIds: null
                    }, item, {
                        textOwner: "none",
                        reason: "standalone_image_copy"
                    }));
                    continue;
                }
                // 소스 파일 없음 → exportFile fallback
            }
            // isCopyable 아니거나 경로 없음 → exportFile fallback (아래 공통 경로)
        }

        // 그룹 렌더링 또는 exportFile fallback
        var fileName = "img_" + domId + ".png";
        var outFile = File(renderDir + "/" + fileName);
        // pageIndex를 try 블록 밖에서 미리 저장 (단순 정수, 이미 검증됨).
        var _imgPageIdx  = parentPage.documentOffset;
        var _imgZOrder   = 0;   // try 블록 안에서 갱신
        var _imgChildIds = null;
        var _imgExportOk = false;
        var _imgBgPolygons = [];
        try {
            _marker(outputDir, "08_img_" + domId + "_hide");
            var _imgTfInlineVisualIds = isGroupRender ? collectTfInlineVisualIds(renderTarget) : [];
            var hiddenTFs = isGroupRender ? hideTextFramesAndOwnedInlineVisuals(renderTarget) : [];

            // 그룹 직계 자식 중 배경 폴리곤(Polygon, 배치 이미지 없음, non-None fill)을
            // 그룹 PNG 내보내기 전 임시 숨김. 배경 폴리곤을 이미지와 합성하면
            // S자 밴드 등 선명한 경계가 이미지 콘텐츠에 묻혀 소실될 수 있음.
            // 이후 폴리곤을 개별 PNG로 내보내 BackgroundInjector에서 그룹 뒤에 배치.
            if (isGroupRender) {
                try {
                    var directChildren = renderTarget.pageItems;
                    for (var bpi = 0; bpi < directChildren.length; bpi++) {
                        var bgChild = directChildren[bpi];
                        if (bgChild.constructor.name !== "Polygon") continue;
                        var bgVisible = true;
                        try { bgVisible = bgChild.visible; } catch (e2) {}
                        if (!bgVisible) continue;
                        var bgHasPlaced = false;
                        try { bgHasPlaced = bgChild.images && bgChild.images.length > 0; } catch (e2) {}
                        if (!bgHasPlaced) try { bgHasPlaced = bgChild.pdfs && bgChild.pdfs.length > 0; } catch (e2) {}
                        if (bgHasPlaced) continue;
                        var bgHasFill = false;
                        try {
                            var bgFc = bgChild.fillColor;
                            bgHasFill = bgFc && bgFc.name && bgFc.name !== "None";
                        } catch (e2) {}
                        if (!bgHasFill) continue;
                        _imgBgPolygons.push(bgChild);
                        try { bgChild.visible = false; } catch (e2) {}
                    }
                } catch (e) {}
            }

            _marker(outputDir, "08_img_" + domId + "_export");
            try {
                renderTarget.exportFile(ExportFormat.PNG_FORMAT, outFile);
                _imgExportOk = true;
            } catch (eExp) {
                try { _imgExportOk = outFile.exists; } catch (e2) {}
            }
            _marker(outputDir, "08_img_" + domId + "_restore");

            // zOrder/childIds를 restore 전에 저장 — restore 예외가 push를 막지 않도록.
            if (_imgExportOk) {
                try { _imgZOrder = getItemZOrder(renderTarget); } catch (e) {}
                if (isGroupRender) {
                    _imgChildIds = [];
                    try {
                        var nested = renderTarget.allPageItems;
                        for (var ci = 0; ci < nested.length; ci++) {
                            _imgChildIds.push(nested[ci].id);
                        }
                    } catch (e) {}
                }
            }

            try { if (hiddenTFs.length > 0) restoreTextFrames(hiddenTFs); } catch (e) {}

            // 배경 폴리곤 가시성 복원
            for (var bri = 0; bri < _imgBgPolygons.length; bri++) {
                try { _imgBgPolygons[bri].visible = true; } catch (e2) {}
            }

            try { $.gc(); } catch (gcErr) {}  // 아이템별 GC: 누적 메모리 해제
        } catch (e) {
            try { _imgExportOk = outFile.exists; } catch (e2) {}
            // outer catch: inner try-catch를 우회한 예외가 있어도 TF/bgPolygon 복원
            try { if (hiddenTFs && hiddenTFs.length > 0) restoreTextFrames(hiddenTFs); } catch (e3) {}
            for (var _brf = 0; _brf < _imgBgPolygons.length; _brf++) {
                try { _imgBgPolygons[_brf].visible = true; } catch (e3) {}
            }
        }

        // push는 try 블록 밖에서 실행 — restore/gc 예외가 발생해도 반드시 등록.
        if (_imgExportOk) {
            // 그룹 항목을 배경 폴리곤보다 먼저 등록
            // BackgroundInjector.addBlockAtFront 특성상 나중 등록 항목이 XML 앞에 위치 →
            // 그룹(일러스트)이 폴리곤(S자 밴드) 위에 렌더링됨
            renderedImageFrames.push(applyRenderOwnership({
                id: domId,
                file: "rendered_frames/" + fileName,
                bounds: bounds,
                pageIndex: _imgPageIdx,
                childImageIds: _imgChildIds,
                zOrder: _imgZOrder
            }, renderTarget, {
                textHiddenBeforeExport: isGroupRender,
                tfInlineVisualIds: _imgTfInlineVisualIds,
                reason: isGroupRender ? "image_group_text_hidden" : (hasPdf ? "pdf_export" : "image_export")
            }));

            // 배경 폴리곤 개별 PNG 내보내기 및 등록
            for (var bei = 0; bei < _imgBgPolygons.length; bei++) {
                var bgPoly = _imgBgPolygons[bei];
                var bgPolyDomId = bgPoly.id;
                var bgPolyFileName = "shape_" + bgPolyDomId + ".png";
                var bgPolyOutFile = File(renderDir + "/" + bgPolyFileName);
                try {
                    bgPoly.exportFile(ExportFormat.PNG_FORMAT, bgPolyOutFile);
                    var bgPolyBounds = null;
                    try { bgPolyBounds = arrCopy(bgPoly.visibleBounds); } catch (e2) {}
                    if (!bgPolyBounds) try { bgPolyBounds = arrCopy(bgPoly.geometricBounds); } catch (e2) {}
                    if (bgPolyBounds) _toPageRelativeBounds(bgPolyBounds, parentPage);
                    renderedImageFrames.push(applyRenderOwnership({
                        id: bgPolyDomId,
                        file: "rendered_frames/" + bgPolyFileName,
                        bounds: bgPolyBounds,
                        pageIndex: _imgPageIdx,
                        childImageIds: null,
                        zOrder: getItemZOrder(bgPoly)
                    }, bgPoly, {
                        textOwner: "none",
                        reason: "image_group_background_polygon"
                    }));
                } catch (e2) {}
            }
        }
    }

    return renderedImageFrames;
}

/**
 * exportImagePlacedFrames의 안전 버전.
 * exportFile(PNG_FORMAT)를 사용하지 않고, 이미지 링크 경로와 bounds 정보만 수집한다.
 * InDesign이 특정 이미지 프레임의 exportFile에서 C++ 크래시(SIGSEGV)를 일으키는
 * 문서에 대한 폴백으로, Java 변환기가 IDML 링크에서 직접 이미지를 처리하게 한다.
 */
/**
 * 도형만으로 구성된 장식 그룹(클리핑 Oval/Rect 포함)을 그룹 단위로 PNG 렌더링한다.
 * 개별 도형 렌더링(exportVectorShapeFrames) 전에 호출하여 중복 방지.
 *
 * 대상:
 * - Group의 allPageItems가 모두 도형(Rect/Polygon/Oval/GraphicLine/Group)
 * - Oval/Rectangle 클리핑 컨테이너 내부에 도형 그룹이 있는 경우
 *
 * @return {{ frames: Array, childIds: Object }}
 */
function exportDecorationGroups(doc, outputDir, startPage, endPage, allItems, imgRenderedIds) {
    var renderDir = Folder(outputDir + "/rendered_frames");
    renderDir.create();

    var results = [];
    var decoChildIds = {};
    var renderedIds = {};

    // ── 공통 헬퍼 ────────────────────────────────────────────────────────────

    function _decoHasPlaced(item) {
        try { if (item.images && item.images.length > 0) return true; } catch (e) {}
        try { if (item.pdfs   && item.pdfs.length   > 0) return true; } catch (e) {}
        try { if (item.epss   && item.epss.length   > 0) return true; } catch (e) {}
        return false;
    }

    // Group용 가드: renderedIds / decoChildIds / badge / imgRendered / 부모 체크
    function _decoGroupSkip(id, item) {
        if (renderedIds[id]) return true;
        if (decoChildIds[id]) return true;
        if (imgRenderedIds && imgRenderedIds[id]) return true;
        try {
            var par = item.parent;
            if (par && (renderedIds[par.id] || decoChildIds[par.id])) return true;
        } catch (e) {}
        return false;
    }

    // PNG 렌더 + results 등록 (자식 claim 포함)
    function _decoRender(item, page, childIdMap, ownershipOpts) {
        var domId = item.id;
        var fileName = "deco_" + domId + ".png";
        var _outFile = File(renderDir + "/" + fileName);
        // exportFile을 독립 try로 감싸 InDesign 2026 예외가 push를 건너뛰지 않도록 보호
        var _exportOk = false;
        try {
            item.exportFile(ExportFormat.PNG_FORMAT, _outFile);
            _exportOk = true;
        } catch (eExp) {
            try { _exportOk = _outFile.exists; } catch (e2) {}
        }
        if (!_exportOk) return [];

        var bounds = null;
        try { bounds = arrCopy(item.visibleBounds); } catch (e) {}
        if (!bounds) try { bounds = arrCopy(item.geometricBounds); } catch (e) {}
        if (bounds) _toPageRelativeBounds(bounds, page);

        var childIds = [];
        try {
            var nested = item.allPageItems;
            for (var i = 0; i < nested.length; i++) {
                var cid = nested[i].id;
                decoChildIds[cid] = true;
                childIds.push(cid);
                if (childIdMap) childIdMap[cid] = true;
            }
        } catch (e) {}

        var _z = 0;
        try { _z = getItemZOrder(item); } catch (e) {}
        var entry = { id: domId, file: "rendered_frames/" + fileName, bounds: bounds, pageIndex: page.documentOffset, zOrder: _z };
        if (childIds.length > 0) entry.childIds = childIds;
        results.push(applyRenderOwnership(entry, item, ownershipOpts || { reason: "decoration_group" }));
        renderedIds[domId] = true;
        return childIds;
    }

    function _boundsOfItem(item) {
        var b = null;
        try { b = arrCopy(item.visibleBounds); } catch (e) {}
        if (!b) try { b = arrCopy(item.geometricBounds); } catch (e2) {}
        return b;
    }

    function _textStatsOfGroup(grp) {
        var stats = { count: 0, length: 0, text: "", hasTable: false, titleLabelStyle: false };
        try {
            var nested = grp.allPageItems;
            for (var i = 0; i < nested.length; i++) {
                var item = nested[i];
                if (item.constructor.name !== "TextFrame") continue;
                stats.count++;
                try {
                    if (item.parentStory && item.parentStory.tables && item.parentStory.tables.length > 0) {
                        stats.hasTable = true;
                    }
                } catch (eTable) {}
                try {
                    var text = item.contents || "";
                    text = String(text).replace(/[\s\r\n\t\u0016\u0018\u0003\uFFFC]/g, "");
                    stats.length += text.length;
                    stats.text += text;
                } catch (eText) {}
                try {
                    var ps = item.parentStory && item.parentStory.paragraphs.length > 0
                            ? item.parentStory.paragraphs[0].appliedParagraphStyle
                            : null;
                    var styleName = ps ? String(ps.name || "") : "";
                    if (styleName.indexOf("#표제목") === 0) stats.titleLabelStyle = true;
                } catch (eStyle) {}
            }
        } catch (e) {}
        return stats;
    }

    function _isVisualMarkerLabelGroup(grp) {
        var stats = _textStatsOfGroup(grp);
        if (stats.count !== 1 || stats.length < 1 || stats.length > 2) return false;
        var text = stats.text || "";
        // 선택지/번호/체크박스처럼 텍스트보다 마커 시각성이 우선인 짧은 버튼만
        // InDesign PNG가 텍스트까지 소유할 수 있다. "나는", "굴을" 같은 짧은
        // 의미 단어 라벨은 editable TF로 유지하고 배경만 shell PNG로 추출한다.
        if (/^(가|나|다|라|마|바|ㄱ|ㄴ|ㄷ|ㄹ|ㅁ|ㅂ|ㅅ|ㅇ|ㅈ|ㅊ|ㅋ|ㅌ|ㅍ|ㅎ)$/.test(text)) return true;
        if (/^[0-9]{1,2}$/.test(text)) return true;
        if (/^[①-⑳]$/.test(text)) return true;
        return false;
    }

    function _renderEditableVisualLabelShell(grp, page, reason) {
        var editableIds = [];
        try { editableIds = _collectTextFrameIds(grp, true, true); } catch (eIds) {}
        var savedTFs = null;
        try {
            savedTFs = hideTextFrames(grp);
            _decoRender(grp, page, null, {
                textHiddenBeforeExport: true,
                textOwner: "hwpx_tf",
                placementAllowed: true,
                editableTextFrameIds: editableIds,
                reason: reason || "visual_label_text_hidden_shell"
            });
        } catch (e) {
            // fall through to restore
        } finally {
            try { if (savedTFs && savedTFs.length > 0) restoreTextFrames(savedTFs); } catch (eRestore) {}
        }
    }

    function _hasVisualShapeChild(grp) {
        try {
            var nested = grp.allPageItems;
            for (var i = 0; i < nested.length; i++) {
                var cn = nested[i].constructor.name;
                if (cn === "Rectangle" || cn === "Oval" || cn === "Polygon"
                        || cn === "GraphicLine" || cn === "Group") return true;
            }
        } catch (e) {}
        return false;
    }

    function _isStrokeLineGridGroup(grp) {
        try {
            var nested = grp.allPageItems;
            var lineLikeCount = 0;
            var dottedCount = 0;
            for (var i = 0; i < nested.length; i++) {
                var item = nested[i];
                var cn = item.constructor.name;
                if (cn === "Group") continue;
                if (cn === "TextFrame") return false;
                if (cn !== "GraphicLine" && cn !== "Rectangle" && cn !== "Polygon" && cn !== "Oval") {
                    return false;
                }
                try {
                    if (item.images && item.images.length > 0) return false;
                    if (item.pdfs && item.pdfs.length > 0) return false;
                    if (item.epss && item.epss.length > 0) return false;
                } catch (ePlaced) {}

                var sw = 0;
                try { sw = item.strokeWeight || 0; } catch (eSw) {}
                if (sw <= 0) continue;

                var hasFill = false;
                try {
                    var fc = item.fillColor;
                    var fn = fc ? String(fc.name || "") : "";
                    hasFill = fn && fn !== "None" && fn !== "[None]";
                } catch (eFill) {}

                var gb = null;
                try { gb = item.geometricBounds; } catch (eGb) {}
                var w = gb ? Math.abs(gb[3] - gb[1]) : 0;
                var h = gb ? Math.abs(gb[2] - gb[0]) : 0;
                var thinStrokeBox = (cn !== "GraphicLine" && !hasFill && (w <= 6 || h <= 6));
                if (cn === "GraphicLine" || thinStrokeBox) {
                    lineLikeCount++;
                    try {
                        var st = String(item.strokeType ? item.strokeType.name : "");
                        if (/Dotted|Dots|Dashed/i.test(st)) dottedCount++;
                    } catch (eSt) {}
                }
            }
            return lineLikeCount >= 2 && dottedCount >= 1;
        } catch (e) {}
        return false;
    }

    function _isShortVisualLabelGroup(grp) {
        var stats = _textStatsOfGroup(grp);
        if (stats.count < 1 || stats.length < 1 || stats.length > 8 || stats.hasTable) return false;
        if (stats.titleLabelStyle) return false;
        if (!_hasVisualShapeChild(grp)) return false;
        var b = _boundsOfItem(grp);
        if (!b) return false;
        var h = Math.abs(b[2] - b[0]);
        var w = Math.abs(b[3] - b[1]);
        // 작은 배지/버튼/라벨만 InDesign PNG가 텍스트까지 소유한다.
        // 긴 제목 라벨이나 활동 지시문 컨테이너는 HWPX TF로 유지한다.
        return h <= 14 && w <= 55;
    }

    function _isLargeMixedParentGroup(grp) {
        var stats = _textStatsOfGroup(grp);
        if (stats.count < 4) return false;
        var b = _boundsOfItem(grp);
        if (!b) return false;
        var h = Math.abs(b[2] - b[0]);
        var w = Math.abs(b[3] - b[1]);
        return h >= 30 && w >= 80;
    }

    function _renderAtomicVisualClusters(grp, page) {
        var rendered = 0;
        try {
            var nested = grp.allPageItems;
            for (var i = 0; i < nested.length; i++) {
                var child = nested[i];
                if (!child || child.constructor.name !== "Group") continue;
                var childId = child.id;
                if (renderedIds[childId] || decoChildIds[childId]) continue;
                if (!_isShortVisualLabelGroup(child)) continue;
                var editableIds = [];
                try { editableIds = _collectTextFrameIds(child, true, true); } catch (eIds) {}
                if (_isVisualMarkerLabelGroup(child)) {
                    _decoRender(child, page, null, {
                        textOwner: "indesign_png",
                        containsText: true,
                        containsEditableText: true,
                        placementAllowed: true,
                        editableTextFrameIds: editableIds,
                        reason: "visual_marker_label_indesign_png"
                    });
                } else {
                    _renderEditableVisualLabelShell(child, page, "visual_label_text_hidden_shell");
                }
                rendered++;
            }
        } catch (e) {}
        return rendered;
    }

    // Group의 parentPage 탐색 (3단계 폴백)
    function _resolveGroupPage(grp) {
        var page = null;
        try { page = grp.parentPage; } catch (e) {}
        if (!page) {
            try {
                var gb = grp.visibleBounds;
                var cy = (gb[0] + gb[2]) / 2, cx = (gb[1] + gb[3]) / 2;
                for (var pi = 0; pi < doc.pages.length; pi++) {
                    var pb = doc.pages[pi].bounds;
                    if (cy >= pb[0] && cy <= pb[2] && cx >= pb[1] && cx <= pb[3]) { page = doc.pages[pi]; break; }
                }
            } catch (e) {}
        }
        if (!page) {
            try {
                var items = grp.pageItems;
                for (var k = 0; k < items.length; k++) {
                    try { page = items[k].parentPage; } catch (e2) {}
                    if (page) break;
                }
            } catch (e) {}
        }
        return page;
    }

    // TF 하나에 특수효과(outline/shadow/skew/rotation)가 있는지 확인
    function _tfHasSpecialEffect(tf) {
        try { if (Math.abs(tf.rotationAngle) > 0.1) return true; } catch (e) {}
        try {
            var chars = tf.characters;
            var allSpecial = true;
            for (var i = 0; i < chars.length && i < 30; i++) {
                var ch = chars[i], c = ch.contents;
                if (c === " " || c === "\r" || c === "\n") continue;
                var special = false;
                try { var sc = ch.strokeColor; if (sc && sc.name !== "None") special = true; } catch (e2) {}
                if (!special) try { if (Math.abs(ch.skewAngle) > 0.1) special = true; } catch (e2) {}
                if (!special) try {
                    var ds = ch.dropShadowSettings;
                    if (ds && ds.mode && ds.mode.toString() !== "ShadowMode.NONE") special = true;
                } catch (e2) {}
                if (!special) { allSpecial = false; break; }
            }
            if (allSpecial) return true;
        } catch (e) {}
        try {
            var fds = tf.transparencySettings.dropShadowSettings;
            if (fds && fds.mode && fds.mode.toString() !== "ShadowMode.NONE") return true;
        } catch (e) {}
        return false;
    }

    // Group 분류: "pureShape" | "textComposite" | "hexGrid" | null
    function _classifyGroup(grp) {
        var nested;
        try { nested = grp.allPageItems; } catch (e) { return null; }

        // Dotted/dashed line grids are fragile as a single InDesign PNG export:
        // vertical strokes can disappear while the group still claims its child IDs.
        // Keep children available for vector_shape export instead of rendering the group.
        if (_isStrokeLineGridGroup(grp)) return null;

        // pureShape: 도형/그룹만 (자식 1개 이상)
        if (isAllShapeChildren(grp)) return "pureShape";

        if (nested.length < 2) return null;

        var nonRectCount = 0, hasTF = false;
        var containerShape = null, containerBounds = null, containerArea = 0;
        for (var i = 0; i < nested.length; i++) {
            var n = nested[i], cn = n.constructor.name;
            if (cn === "TextFrame") { hasTF = true; }
            if (cn === "Polygon" || cn === "Rectangle") {
                try { if (n.paths[0].pathPoints.length > 4) nonRectCount++; } catch (e) {}
            }
            if (cn === "Rectangle" || cn === "Oval") {
                try {
                    var sb = n.geometricBounds, area = (sb[2]-sb[0])*(sb[3]-sb[1]);
                    if (area > containerArea) { containerArea = area; containerShape = n; containerBounds = sb; }
                } catch (e) {}
            }
        }

        // hexGrid: 비사각형 Polygon 3개 이상, TF 없음
        if (!hasTF && nonRectCount >= 3) return "hexGrid";

        // textComposite: 컨테이너 도형 + 모든 TF에 특수효과
        if (hasTF && containerShape) {
            var TOL = 1.0, allInside = true;
            for (var j = 0; j < nested.length; j++) {
                if (nested[j] === containerShape) continue;
                try {
                    var bb = nested[j].geometricBounds;
                    if (bb[0] < containerBounds[0]-TOL || bb[1] < containerBounds[1]-TOL ||
                        bb[2] > containerBounds[2]+TOL || bb[3] > containerBounds[3]+TOL) { allInside = false; break; }
                } catch (e) {}
            }
            if (allInside) {
                var allSpecial = true;
                for (var k = 0; k < nested.length; k++) {
                    if (nested[k].constructor.name !== "TextFrame") continue;
                    try {
                        var content = nested[k].contents;
                        if (!content || content.replace(/[\s\r\n]/g, "").length === 0) continue;
                    } catch (e) { continue; }
                    if (!_tfHasSpecialEffect(nested[k])) { allSpecial = false; break; }
                }
                if (allSpecial) return "textComposite";

                // 컨테이너 배경색이 있고 TF가 모두 안쪽에 있으면 → textComposite (PNG 통 렌더)
                // mixedGroup 경로(TF 숨김 + 별도 Java 배치)는 TF가 badge-shift에 의해
                // 수평 이탈하는 버그가 있으므로, 배경 박스 레이블형 그룹은 여기서 차단.
                var containerHasFill = false;
                try {
                    var _cFill = containerShape.fillColor;
                    if (_cFill && _cFill.name !== "None" && _cFill.name !== "[None]") containerHasFill = true;
                } catch (e) {}
                if (containerHasFill) {
                    var noTableInTfs = true;
                    for (var kt = 0; kt < nested.length; kt++) {
                        if (nested[kt].constructor.name !== "TextFrame") continue;
                        try { if (nested[kt].parentStory.tables.length > 0) { noTableInTfs = false; break; } } catch (e) {}
                    }
                    if (noTableInTfs) return "textComposite";
                }
            }
        }

        // mixedGroup: 도형 + 일반 TF 혼합 (컨테이너 fill 없거나 TF가 컨테이너 밖으로 벗어남)
        // → TF 숨기고 도형만 PNG 렌더, TF 텍스트는 Java 파이프라인
        if (hasTF) {
            var hasShape = false;
            for (var m = 0; m < nested.length; m++) {
                var mn = nested[m].constructor.name;
                if (mn === "Rectangle" || mn === "Oval" || mn === "Polygon"
                        || mn === "GraphicLine" || mn === "Group") { hasShape = true; break; }
            }
            if (hasShape) return "mixedGroup";
        }

        return null;
    }

    /**
     * mixedGroup 내 "Color/Paper"(흰색) 획 도형을 검은색으로 임시 변환 후 개별 PNG 내보내기.
     * InDesign 투명배경 PNG 내보내기 시 흰색 획이 역마트 알고리즘으로 소실되는 문제 방지.
     * 내보낸 PNG는 검은 획으로 저장되며 Java에서 흰색으로 반전하여 배치.
     */
    function _exportPaperStrokeShapes(grp, grpPage) {
        try {
            var nested = grp.allPageItems;
            for (var _pi = 0; _pi < nested.length; _pi++) {
                var _sh = nested[_pi];
                var _cn;
                try { _cn = _sh.constructor.name; } catch(_e) { continue; }
                if (_cn !== "Polygon" && _cn !== "Oval" && _cn !== "GraphicLine"
                        && _cn !== "Rectangle" && _cn !== "TextFrame") continue;

                var _sName = "";
                try { _sName = _sh.strokeColor.name; } catch(_e) {}
                if (_sName !== "Paper") continue;

                var _sw = 0;
                try { _sw = _sh.strokeWeight; } catch(_e) {}
                if (_sw <= 0.01) continue;

                var _origStroke = null;
                try { _origStroke = _sh.strokeColor; } catch(_e) {}
                var _colored = false;
                try { _sh.strokeColor = doc.swatches.itemByName("Black"); _colored = true; } catch(_e) {}
                if (!_colored) {
                    try { _sh.strokeColor = doc.colors.itemByName("Black"); _colored = true; } catch(_e) {}
                }
                if (!_colored) continue;

                // TextFrame: 텍스트 컨텐츠를 숨겨 테두리(stroke)만 내보내기
                var _savedContentOp = null;
                if (_cn === "TextFrame") {
                    try {
                        _savedContentOp = _sh.contentTransparencySettings.blendingSettings.opacity;
                        _sh.contentTransparencySettings.blendingSettings.opacity = 0;
                    } catch(_e) { _savedContentOp = null; }
                }

                try {
                    var _wFile = "white_shape_" + _sh.id + ".png";
                    _sh.exportFile(ExportFormat.PNG_FORMAT, File(renderDir + "/" + _wFile));
                    var _wBounds = null;
                    try { _wBounds = arrCopy(_sh.visibleBounds); } catch(_e) {}
                    if (!_wBounds) try { _wBounds = arrCopy(_sh.geometricBounds); } catch(_e) {}
                    if (_wBounds) _toPageRelativeBounds(_wBounds, grpPage);
                    results.push(applyRenderOwnership({
                        id: _sh.id,
                        file: "rendered_frames/" + _wFile,
                        bounds: _wBounds,
                        pageIndex: grpPage.documentOffset,
                        whiteStroke: true,
                        zOrder: getItemZOrder(_sh)
                    }, _sh, {
                        textHiddenBeforeExport: _cn === "TextFrame",
                        textOwner: _cn === "TextFrame" ? "hwpx_tf" : "none",
                        reason: "paper_stroke_visual_only"
                    }));
                } catch(_e2) {}

                if (_origStroke) try { _sh.strokeColor = _origStroke; } catch(_e) {}
                // TextFrame: 컨텐츠 opacity 복원
                if (_savedContentOp !== null) {
                    try { _sh.contentTransparencySettings.blendingSettings.opacity = _savedContentOp; } catch(_e) {}
                }
            }
        } catch(_e) {}
    }

    // ── Pass 1: Oval/Rectangle 클리핑 컨테이너 ───────────────────────────────
    // (Group이 아닌 도형 타입이므로 별도 루프 유지)
    for (var i = 0; i < allItems.length; i++) {
        var item = allItems[i];
        var cName = item.constructor.name;
        if (cName !== "Oval" && cName !== "Rectangle") continue;
        if (isOnHiddenLayer(item)) continue;

        var domId = item.id;
        if (renderedIds[domId]) continue;

        var hasNested = false;
        try { hasNested = item.allPageItems && item.allPageItems.length > 0; } catch (e) {}
        if (!hasNested) continue;
        try { if (item.contentType !== ContentType.GRAPHIC_TYPE) continue; } catch (e) { continue; }
        if (_decoHasPlaced(item)) continue;
        if (!isAllShapeChildren(item)) continue;

        var p1Page = null;
        try { p1Page = item.parentPage; } catch (e) {}
        if (!p1Page) continue;
        if (p1Page.documentOffset + 1 < startPage || p1Page.documentOffset + 1 > endPage) continue;

        try { _decoRender(item, p1Page, null); } catch (e) {}
    }

    // ── Pass 2~4 통합: Group ─────────────────────────────────────────────────
    for (var gi = 0; gi < allItems.length; gi++) {
        var grp = allItems[gi];
        if (grp.constructor.name !== "Group") continue;
        if (isOnHiddenLayer(grp)) continue;

        var grpId = grp.id;
        if (_decoGroupSkip(grpId, grp)) continue;

        var kind = _classifyGroup(grp);
        if (!kind) continue;

        // pureShape: 부모 승격 또는 형제 렌더 결정
        var renderSiblings = false;
        var origGi = gi;
        if (kind === "pureShape") {
            try {
                var par = grp.parent;
                if (par && par.constructor.name === "Group" && !renderedIds[par.id]) {
                    if (isAllShapeChildren(par)) {
                        grp = par; grpId = par.id;  // 부모로 승격
                    } else {
                        renderSiblings = true;  // 부모에 TF 있음 → 형제 개별 렌더
                    }
                }
            } catch (e) {}
            if (renderedIds[grpId]) continue;
        }

        var grpPage = _resolveGroupPage(grp);
        if (!grpPage) continue;
        if (grpPage.documentOffset + 1 < startPage || grpPage.documentOffset + 1 > endPage) continue;

        var _savedTFsForCatch = null;
        var _savedCellBgsForCatch = null;
        try {
            // hexGrid: 자신의 자식이 이전 Pass에서 개별 렌더된 경우 results에서 제거
            if (kind === "hexGrid") {

                var hexChildMap = {};
                try {
                    var hexNested = grp.allPageItems;
                    for (var hi = 0; hi < hexNested.length; hi++) hexChildMap[hexNested[hi].id] = true;
                } catch (e) {}
                var cleaned = [];
                for (var ri = 0; ri < results.length; ri++) {
                    if (!hexChildMap[results[ri].id]) cleaned.push(results[ri]);
                }
                results = cleaned;
            }

            var editableTfIdsForGroup = [];
            if (kind === "mixedGroup" || kind === "textComposite") {
                try { editableTfIdsForGroup = _collectTextFrameIds(grp, true, true); } catch (e) {}
            }

            if ((kind === "mixedGroup" || kind === "textComposite") && _isShortVisualLabelGroup(grp)) {
                var _labelEditableIds = [];
                try { _labelEditableIds = _collectTextFrameIds(grp, true, true); } catch (e) {}
                if (_isVisualMarkerLabelGroup(grp)) {
                    _decoRender(grp, grpPage, null, {
                        textOwner: "indesign_png",
                        containsText: true,
                        containsEditableText: true,
                        placementAllowed: true,
                        editableTextFrameIds: _labelEditableIds,
                        reason: "visual_marker_label_indesign_png"
                    });
                } else {
                    _renderEditableVisualLabelShell(grp, grpPage, "visual_label_text_hidden_shell");
                }
            } else if (kind === "mixedGroup" && _isLargeMixedParentGroup(grp)) {
                _renderAtomicVisualClusters(grp, grpPage);
                // 큰 부모 mixedGroup은 통 이미지로 만들지 않는다. 자식 atomic cluster와
                // 이후 vector/shape 렌더가 각 시각 단위를 소유한다.
                _exportPaperStrokeShapes(grp, grpPage);
            } else if (kind === "mixedGroup" || (kind === "textComposite" && editableTfIdsForGroup.length > 0)) {
                // TF 텍스트만 숨기고 도형/anchored visual은 부모 PNG에 포함한다.
                // 말풍선 brace, 답안 밑줄처럼 TF story에 앵커되어도 시각 껍데기의 일부인 객체는
                // 부모 PNG의 visualOnlyChildIds로 소유시켜 Java inline 재배치를 막는다.
                var savedTFs = hideTextFrames(grp);
                var savedCellBgs = hideRepeatedCellBackgroundCandidates(grp);
                _savedTFsForCatch = savedTFs;
                _savedCellBgsForCatch = savedCellBgs;
                _decoRender(grp, grpPage, null, {
                    textHiddenBeforeExport: true,
                    textOwner: "hwpx_tf",
                    editableTextFrameIds: editableTfIdsForGroup.length > 0 ? editableTfIdsForGroup : undefined,
                    reason: kind === "textComposite" ? "text_composite_editable_text_hidden" : "mixed_group_text_hidden"
                });
                restoreRepeatedCellBackgroundCandidates(savedCellBgs);
                restoreTextFrames(savedTFs);
                _savedCellBgsForCatch = null;
                _savedTFsForCatch = null;
                // Color/Paper (흰색) 획 도형은 투명배경 PNG에서 소실 → 검은색으로 임시 변환 후 개별 추출
                _exportPaperStrokeShapes(grp, grpPage);
            } else {
                _decoRender(grp, grpPage, null, kind === "textComposite" ? {
                    textOwner: "indesign_png",
                    containsText: true,
                    placementAllowed: true,
                    reason: "text_composite_indesign_png"
                } : {
                    textOwner: "none",
                    reason: "pure_decoration_group"
                });
            }
        } catch (e) {
            // outer catch: 예외가 발생해도 숨겼던 TF 복원
            try { if (_savedCellBgsForCatch && _savedCellBgsForCatch.length > 0) restoreRepeatedCellBackgroundCandidates(_savedCellBgsForCatch); } catch (e1) {}
            try { if (_savedTFsForCatch && _savedTFsForCatch.length > 0) restoreTextFrames(_savedTFsForCatch); } catch (e2) {}
        }

        // pureShape + renderSiblings: 부모 그룹의 형제 도형을 개별 렌더
        if (renderSiblings) {
            try {
                var origGrp = allItems[origGi];
                var parRef = origGrp.parent;
                var allKids = parRef.allPageItems;
                for (var fi = 0; fi < allKids.length; fi++) {
                    try {
                        var sib = allKids[fi];
                        if (sib.parent.id !== parRef.id) continue;
                        var sibId = sib.id;
                        if (sibId === grpId || renderedIds[sibId] || decoChildIds[sibId]) continue;
                        var sibCn = sib.constructor.name;
                        if (sibCn !== "GraphicLine" && sibCn !== "Rectangle" &&
                                sibCn !== "Polygon" && sibCn !== "Oval" && sibCn !== "Group") continue;
                        if (sibCn === "Group" && !isAllShapeChildren(sib)) continue;
                        _decoRender(sib, grpPage, null);
                    } catch (e2) {}
                }
            } catch (e) {}
        }
    }

    return { frames: results, childIds: decoChildIds };
}

/**
 * 아이템의 모든 자식이 도형(Rect/Polygon/Oval/GraphicLine) 또는 Group인지 확인.
 * TextFrame, Image 등 비도형 자식이 하나라도 있으면 false.
 */
function isAllShapeChildren(item) {
    try {
        var nested = item.allPageItems;
        for (var i = 0; i < nested.length; i++) {
            var cn = nested[i].constructor.name;
            if (cn !== "Rectangle" && cn !== "Polygon"
                && cn !== "Oval" && cn !== "GraphicLine"
                && cn !== "Group") {
                return false;
            }
        }
        return nested.length > 0;
    } catch (e) {
        return false;
    }
}

/**
 * 벡터 도형을 PNG로 렌더링한다.
 */
function exportVectorShapeFrames(doc, outputDir, startPage, endPage, decoChildIds, allItems) {
    var renderDir = Folder(outputDir + "/rendered_frames");
    renderDir.create();

    var results = [];
    var renderedIds = {};

    function _isExplicitlyInvisibleTint(tint) {
        return tint !== undefined && tint !== null && Number(tint) === 0;
    }

    function _hasVisibleVectorFill(item) {
        try {
            var fc = item.fillColor;
            var name = fc ? fc.name : "None";
            if (!name || name === "None" || name === "[None]") return false;
            try {
                var tint = item.fillTint;
                if (_isExplicitlyInvisibleTint(tint)) return false;
            } catch (eTint) {}
            try {
                var opacity = item.fillTransparencySettings.blendingSettings.opacity;
                if (opacity !== undefined && opacity !== null && opacity <= 0) return false;
            } catch (eOpacity) {}
            return true;
        } catch (e) {}
        return false;
    }

    function _hasVisibleVectorStroke(item) {
        try {
            var sc = item.strokeColor;
            var name = sc ? sc.name : "None";
            var sw = item.strokeWeight || 0;
            if (!name || name === "None" || name === "[None]" || sw <= 0) return false;
            try {
                var tint = item.strokeTint;
                if (_isExplicitlyInvisibleTint(tint)) return false;
            } catch (eTint) {}
            try {
                var opacity = item.strokeTransparencySettings.blendingSettings.opacity;
                if (opacity !== undefined && opacity !== null && opacity <= 0) return false;
            } catch (eOpacity) {}
            return true;
        } catch (e) {}
        return false;
    }

    // 도형 1개 개별 export 헬퍼
    function _exportSingleShape(item, domId, parentPage) {
        var fileName = "shape_" + domId + ".png";
        var outFile = File(renderDir + "/" + fileName);
        // exportFile을 독립 try로 감싸 InDesign 2026 예외가 push를 건너뛰지 않도록 보호
        var _exportOk = false;
        try {
            item.exportFile(ExportFormat.PNG_FORMAT, outFile);
            _exportOk = true;
        } catch (eExp) {
            try { _exportOk = outFile.exists; } catch (e2) {}
        }
        if (!_exportOk) return;
        try {
            var bounds = null;
            try { bounds = arrCopy(item.visibleBounds); } catch (e) {}
            if (!bounds) try { bounds = arrCopy(item.geometricBounds); } catch (e) {}
            if (bounds) _toPageRelativeBounds(bounds, parentPage);
            var _z = 0;
            try { _z = getItemZOrder(item); } catch (e) {}
            results.push(applyRenderOwnership({
                id: domId,
                file: "rendered_frames/" + fileName,
                bounds: bounds,
                pageIndex: parentPage.documentOffset,
                zOrder: _z
            }, item, {
                textOwner: "none",
                reason: "vector_shape"
            }));
            renderedIds[domId] = true;
        } catch (e) {}
    }

    // 1. 자격 있는 도형을 페이지별로 수집
    var shapesByPage = {}; // pgIdx(1-based) → { page, shapes:[{item,domId}] }

    for (var si = 0; si < allItems.length; si++) {
        var item = allItems[si];
        var cName = item.constructor.name;

        if (cName !== "Rectangle" && cName !== "Polygon"
            && cName !== "Oval" && cName !== "GraphicLine") continue;

        var domId = item.id;
        if (renderedIds[domId]) continue;
        if (isOnHiddenLayer(item)) continue;
        if (decoChildIds && decoChildIds[domId]) continue;

        var hasPlaced = false;
        try { hasPlaced = item.images && item.images.length > 0; } catch (e) {}
        if (!hasPlaced) try { hasPlaced = item.pdfs && item.pdfs.length > 0; } catch (e) {}
        if (!hasPlaced) try { hasPlaced = item.epss && item.epss.length > 0; } catch (e) {}
        if (hasPlaced) continue;
        if (!_hasVisibleVectorFill(item) && !_hasVisibleVectorStroke(item)) continue;

        // 인라인 앵커 확인
        var isInline = false;
        try {
            var cur = item.parent;
            while (cur) {
                var pn = cur.constructor.name;
                if (pn === "TextFrame" || pn === "Character"
                    || pn === "InsertionPoint" || pn === "Cell"
                    || pn === "Story") { isInline = true; break; }
                if (pn === "Spread" || pn === "Page" || pn === "Document") break;
                cur = cur.parent;
            }
        } catch (e) {}
        if (isInline) continue;

        var parentPage = null;
        try { parentPage = item.parentPage; } catch (e) {}
        if (!parentPage || (item.parent && item.parent.constructor.name === "Group")) {
            try { var grpPage = item.parent.parentPage; if (grpPage) parentPage = grpPage; } catch (e) {}
        }
        if (!parentPage) parentPage = _resolveParentPage(item, doc);
        if (!parentPage) continue;
        var pgIdx = parentPage.documentOffset + 1;
        if (pgIdx < startPage || pgIdx > endPage) continue;

        // 최소 크기 필터 — 3pt 미만
        try {
            var gb = item.geometricBounds;
            if ((gb[3]-gb[1]) < 3 && (gb[2]-gb[0]) < 3) continue;
        } catch (e) {}

        var hasNestedItems = false;
        try { hasNestedItems = item.allPageItems && item.allPageItems.length > 0; } catch (e) {}
        if (hasNestedItems) {
            try { if (item.contentType === ContentType.GRAPHIC_TYPE) continue; } catch (e) {}
        }

        if (!shapesByPage[pgIdx]) shapesByPage[pgIdx] = { page: parentPage, shapes: [] };
        shapesByPage[pgIdx].shapes.push({ item: item, domId: domId });
    }

    // 2. 페이지별 렌더링 — 개별 export (duplicate+group 방식은 메모리 스파이크로 -609 유발)
    for (var pgNum in shapesByPage) {
        var pageData = shapesByPage[pgNum];
        var shapes = pageData.shapes;
        var pg = pageData.page;

        for (var k = 0; k < shapes.length; k++) {
            _exportSingleShape(shapes[k].item, shapes[k].domId, pg);
            // 5개마다 GC 유도 — 누적 메모리 압박 완화
            if (k % 5 === 4) { try { $.gc(); } catch (e) {} }
        }
        // 페이지 완료 후 GC
        try { $.gc(); } catch (e) {}
    }

    return results;
}

/**
 * 마스터 스프레드의 그래픽 아이템(선, 원, 도형, 그룹)을 아이템별로 PNG 렌더링.
 * TextFrame은 instanceMasterFrames가 처리하므로 여기서는 그래픽 아이템만.
 */
function exportMasterPageGraphics(doc, outputDir, startPage, endPage) {
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

    function _boundsIntersect(a, b, pad) {
        pad = pad || 0;
        try {
            return a[2] >= b[0] - pad && a[0] <= b[2] + pad &&
                   a[3] >= b[1] - pad && a[1] <= b[3] + pad;
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
            if (t >= bt || l >= r) return null;
            return [t, l, bt, r];
        } catch (e) {}
        return null;
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

    function _hideEditableTextContent(renderTarget) {
        var saved = [];
        function hideOne(tf) {
            try {
                if (classifyTextFrameCached(tf) !== "editable" && !_isDynamicMasterTextFrame(tf)) return;
                try {
                    var contentBlend = tf.contentTransparencySettings.blendingSettings;
                    var oldOpacity = contentBlend.opacity;
                    contentBlend.opacity = 0;
                    saved.push({ tf: tf, mode: "contentOpacity", opacity: oldOpacity });
                } catch (eOpacity) {
                    var wasVisible = true;
                    try { wasVisible = tf.visible; } catch (eVis) {}
                    try {
                        tf.visible = false;
                        saved.push({ tf: tf, mode: "visible", wasVisible: wasVisible });
                    } catch (eVisible) {}
                }
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

            // 이 마스터 페이지에 걸치는 top-level item을 모두 포함한다.
            // 중심점 기준은 스프레드 걸침/오버레이 객체를 누락시키므로 교차 판정으로 통일한다.
            var pageItems = [];
            for (var pii = 0; pii < masterGraphicItems.length; pii++) {
                try {
                    var pib = _masterItemBounds(masterGraphicItems[pii]);
                    if (pib && _boundsIntersect(pib, mpBnds, 5)) {
                        pageItems.push({ item: masterGraphicItems[pii], bounds: pib });
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
                var mFileName = "master_" + msId + "_" + mpIdx + "_" + ci + ".png";
                var mOutFile = File(renderDir + "/" + mFileName);
                var tempPage = null;
                var exportTarget = null;
                var overriddenItems = [];
                var overriddenSourceEntries = [];
                var failedOverrideEntries = [];
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
                            failedOverrideEntries.push(clusterItems[oi]);
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

                        hiddenText = _hideEditableTextContent(exportTarget);
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
                            sourceObjectIds: _collectIdsForMasterItems(overriddenSourceEntries)
                        };
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

                // Some valid master items cannot be overridden onto a temporary page
                // (InDesign reports them as already overridden or invalid). Export those
                // source master items directly so the composed master layer cannot silently
                // lose top-level logo/deco groups.
                for (var foi = 0; foi < failedOverrideEntries.length; foi++) {
                    var failedEntry = failedOverrideEntries[foi];
                    var fFileName = "master_" + msId + "_" + mpIdx + "_" + ci + "_fallback_" + foi + ".png";
                    var fOutFile = File(renderDir + "/" + fFileName);
                    var fHiddenText = [];
                    var fBnds = null;
                    try {
                        fHiddenText = _hideEditableTextContent(failedEntry.item);
                        failedEntry.item.exportFile(ExportFormat.PNG_FORMAT, fOutFile, false);
                        try { fBnds = arrCopy(failedEntry.item.visibleBounds); } catch (eFv) {}
                        if (!fBnds) try { fBnds = arrCopy(failedEntry.item.geometricBounds); } catch (eFg) {}
                    } catch (eF) {
                        dbg("  fallback export error itemId=" + failedEntry.item.id + ": " + eF);
                    } finally {
                        try { restoreTextFrames(fHiddenText); } catch (eFr) {}
                    }
                    if (!fOutFile.exists || !fBnds) {
                        dbg("  fallback FAIL itemId=" + failedEntry.item.id + " file=" + fFileName);
                        continue;
                    }
                    var fPlacementBnds = failedEntry.bounds || fBnds;
                    var fRelBase = mpBnds;
                    var fVisibleBnds = _boundsIntersection(fPlacementBnds, mpBnds);
                    if (!fVisibleBnds) {
                        dbg("  fallback SKIP itemId=" + failedEntry.item.id + ": no visible master page intersection");
                        continue;
                    }
                    var fCluster = {
                        relPath:   "rendered_frames/" + fFileName,
                        relTop:    fVisibleBnds[0] - fRelBase[0],
                        relLeft:   fVisibleBnds[1] - fRelBase[1],
                        relBottom: fVisibleBnds[2] - fRelBase[0],
                        relRight:  fVisibleBnds[3] - fRelBase[1],
                        sourceObjectIds: _collectIdsForMasterItems([failedEntry])
                    };
                    if (_boundsDiffer(fPlacementBnds, fVisibleBnds, 0.01)) {
                        fCluster.cropSourceBounds = [
                            fPlacementBnds[0] - fRelBase[0],
                            fPlacementBnds[1] - fRelBase[1],
                            fPlacementBnds[2] - fRelBase[0],
                            fPlacementBnds[3] - fRelBase[1]
                        ];
                    }
                    exportedClusters.push(fCluster);
                    dbg("  fallback SUCCESS itemId=" + failedEntry.item.id + " file=" + fFileName +
                        " rel=[" + fCluster.relTop + "," + fCluster.relLeft + "," +
                        fCluster.relBottom + "," + fCluster.relRight + "]");
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
                if ((master.relRight - master.relLeft) <= 0) continue;
                dbg("  result docIdx=" + pgEntry.docIdx + " masterPageIdx=" + pgEntry.masterPageIdx +
                    " cluster=" + mi +
                    " rel=["+master.relTop+","+master.relLeft+","+master.relBottom+","+master.relRight+"]");
                results.push(applyRenderOwnership({
                    id: parseInt(msId2, 10) * 100 + mi,
                    file: master.relPath,
                    bounds: [master.relTop, master.relLeft, master.relBottom, master.relRight],
                    cropSourceBounds: master.cropSourceBounds,
                    pageIndex: pgEntry.docIdx,
                    zOrder: mi,
                    isMasterGraphic: true
                }, null, {
                    sourceObjectIds: master.sourceObjectIds || [parseInt(msId2, 10)],
                    textOwner: "none",
                    reason: "master_graphic"
                }));
            }
        }
    }

    dbg("DONE: " + results.length + " master graphic entries");
    if (debugLog) try { debugLog.close(); } catch (e) {}
    $.writeln("[exportMasterPageGraphics] " + results.length + " entries (v13 composed-master-page)");
    return results;
}


// =============================================================================
// SECTION 6: RESOLVED COLLECTOR
// resolved.json 수집 — 스토리 / TF / 마스터 인스턴스화 / 페이지 / 아이템
// =============================================================================

function collectResolved(doc, outputDir, rangePageCount, startPage, endPage, editableIds, skipRenderPagesMap, cachedAllItems) {
    if (!skipRenderPagesMap) skipRenderPagesMap = {};

    writeProgress(outputDir, "resolved_styles", 0, rangePageCount);
    var docInfo = collectDocumentInfo(doc);
    var paraStyles = collectParagraphStyles(doc);
    // SPEC-030 B.3: characterStyles는 Java 측 resolved 파이프라인에서 미사용 — 수집 생략
    // var charStyles = collectCharacterStyles(doc);
    var colors = collectColors(doc);
    var fonts = collectFonts(doc);

    // 범위 내 페이지의 텍스트프레임에 연결된 스토리 ID 수집
    // SPEC-030: 증분 추출 시 변경 페이지의 스토리만 수집 (skipRenderPagesMap 제외)
    var rangeStoryIds = {};
    try {
        var tfs = doc.textFrames.everyItem().getElements();
        for (var ti = 0; ti < tfs.length; ti++) {
            var tf = tfs[ti];
            try {
                var pp = tf.parentPage;
                if (pp) {
                    var pgIdx = pp.documentOffset + 1;
                    if (pgIdx >= startPage && pgIdx <= endPage && !skipRenderPagesMap[pgIdx]) {
                        try {
                            rangeStoryIds[tf.parentStory.id.toString()] = true;
                        } catch (e2) {}
                    }
                }
            } catch (e) {}
        }
    } catch (e) {}
    // 그룹 내부 TextFrame은 parentPage가 null이므로
    // 페이지의 allPageItems에서 TextFrame을 추가 수집
    try {
        for (var pi = 0; pi < doc.pages.length; pi++) {
            var page = doc.pages[pi];
            var pgIdx2 = page.documentOffset + 1;
            if (pgIdx2 < startPage || pgIdx2 > endPage) continue;
            if (skipRenderPagesMap[pgIdx2]) continue; // SPEC-030
            var allItems = page.allPageItems;
            for (var ai = 0; ai < allItems.length; ai++) {
                try {
                    if (allItems[ai].constructor.name === "TextFrame") {
                        rangeStoryIds[allItems[ai].parentStory.id.toString()] = true;
                    }
                } catch (e3) {}
            }
        }
    } catch (e) {}

    // SPEC-025: off-canvas TF story 도 rangeStoryIds 에 추가.
    // off-canvas TF 는 parentPage=null 이므로 위 두 수집 경로에서 모두 누락됨.
    // instanceMasterFrames 가 스토리 클론을 만들려면 원본 story 가 storyById 에 있어야 함.
    try {
        var _allSpreads = doc.spreads.everyItem().getElements();
        for (var _ocRsi = 0; _ocRsi < _allSpreads.length; _ocRsi++) {
            var _ocSpItems = [];
            try { _ocSpItems = _allSpreads[_ocRsi].allPageItems; } catch (e) {}
            for (var _ocSii = 0; _ocSii < _ocSpItems.length; _ocSii++) {
                try {
                    if (_ocSpItems[_ocSii].constructor.name !== "TextFrame") continue;
                    var _ocPp = null; try { _ocPp = _ocSpItems[_ocSii].parentPage; } catch (e) {}
                    if (_ocPp) continue;
                    rangeStoryIds[_ocSpItems[_ocSii].parentStory.id.toString()] = true;
                } catch (e) {}
            }
        }
    } catch (e) {}

    // SPEC-025: 마스터 스프레드의 TextFrame 스토리도 rangeStoryIds 에 추가 (Phase 5 master instancing 용)
    // 증분 추출에서도 마스터 스토리는 항상 수집 (변경 여부와 무관)
    try {
        var _appliedMasterIds = {};
        for (var pp25 = 0; pp25 < doc.pages.length; pp25++) {
            try {
                var pgIdx25 = pp25 + 1;
                if (pgIdx25 < startPage || pgIdx25 > endPage) continue;
                var am25 = doc.pages[pp25].appliedMaster;
                if (am25) _appliedMasterIds[am25.id.toString()] = true;
            } catch (e) {}
        }
        var _msAll = doc.masterSpreads.everyItem().getElements();
        for (var msi25 = 0; msi25 < _msAll.length; msi25++) {
            var _ms25 = _msAll[msi25];
            if (!_appliedMasterIds[_ms25.id.toString()]) continue;
            var _msItems25 = _ms25.allPageItems;
            for (var mi25 = 0; mi25 < _msItems25.length; mi25++) {
                try {
                    if (_msItems25[mi25].constructor.name === "TextFrame") {
                        rangeStoryIds[_msItems25[mi25].parentStory.id.toString()] = true;
                    }
                } catch (e) {}
            }
        }
    } catch (e) {}

    writeProgress(outputDir, "resolved_stories", 0, rangePageCount);
    var stories = collectStories(doc, outputDir, rangePageCount, rangeStoryIds);

    writeProgress(outputDir, "resolved_frames", 0, rangePageCount);
    var textFrames = collectTextFrames(doc, startPage, endPage, editableIds, skipRenderPagesMap, cachedAllItems);

    // SPEC-025 Phase 5: 마스터 스프레드 TextFrame 을 적용 페이지마다 인스턴스화 (frame + story clone)
    // 증분 추출에서도 실행 (마스터 인스턴스는 경량 연산)
    try { instanceMasterFrames(doc, startPage, endPage, textFrames, stories, editableIds); } catch (ePhase5) { $.writeln("[SPEC-025 Phase 5 error] " + ePhase5); }

    writeProgress(outputDir, "resolved_items", 0, rangePageCount);
    var pages = collectPages(doc, startPage, endPage, skipRenderPagesMap);
    var pageItems = collectPageItems(doc, startPage, endPage, skipRenderPagesMap, cachedAllItems);

    var fontMetrics = measureFontMetrics(doc);

    return {
        documentInfo: docInfo,
        paragraphStyles: paraStyles,
        colors: colors,
        fonts: fonts,
        stories: stories,
        textFrames: textFrames,
        pages: pages,
        pageItems: pageItems,
        fontMetrics: fontMetrics
    };
}

/**
 * 아이템이 텍스트 흐름 안의 인라인(앵커) 객체인지 판별한다.
 */
/**
 * TextFrame이 다른 TextFrame 안에 중첩되어 있는지 확인.
 * allPageItems로 순회 시 중첩 TextFrame이 독립 항목으로 나타나지만,
 * 부모 TextFrame/Group이 배경에 포함되므로 별도 처리 불필요.
 */
function isNestedInTextFrame(item) {
    try {
        var cur = item.parent;
        // 부모 체인을 2단계까지 확인 (Group > TextFrame 패턴)
        for (var depth = 0; depth < 5 && cur; depth++) {
            var pn = cur.constructor.name;
            if (pn === "TextFrame") return true;
            if (pn === "Spread" || pn === "Page" || pn === "Document") return false;
            try { cur = cur.parent; } catch (e) { break; }
        }
    } catch (e) {}
    return false;
}

function isInlineItem(item) {
    try {
        var cur = item.parent;
        while (cur) {
            var pn = cur.constructor.name;
            if (pn === "TextFrame" || pn === "Character"
                || pn === "InsertionPoint" || pn === "Cell"
                || pn === "Story") return true;
            if (pn === "Spread" || pn === "Page" || pn === "Document") return false;
            try { cur = cur.parent; } catch (e) { break; }
        }
    } catch (e) {}
    return false;
}

/**
 * 페이지 단위 배경 렌더링: 편집 가능 텍스트 프레임만 숨기고 페이지를 통째로 PNG 렌더.
 * 모든 그래픽/벡터/이미지/장식 텍스트가 한 장에 캡처되어 z-order/레이어 문제 해결.
 *
 * @return {Array} renderedFloatingItems 호환 배열 (페이지당 1개 엔트리)
 */
function exportPageBackgrounds(doc, outputDir, startPage, endPage, allItems, skipRenderPagesMap) {
    if (!skipRenderPagesMap) skipRenderPagesMap = {};
    var renderDir = Folder(outputDir + "/rendered_frames");
    renderDir.create();

    app.pngExportPreferences.exportResolution = CONFIG.rendering.pngExportResolution || 220;
    app.pngExportPreferences.antiAlias = true;
    // 페이지 배경은 흰 배경으로 렌더링 — transparentBackground=true 시 연한 CMYK 색상(C=0 M=0.1 Y=0.08 K=0 등)이
    // "흰 바탕 언멀티플라이" 처리로 거의 투명하게 렌더되어 배경 도형이 사라지는 현상 방지
    app.pngExportPreferences.transparentBackground = false;
    app.pngExportPreferences.pngQuality = PNGQualityEnum.MAXIMUM;

    // 고품질 이미지 표시로 전환 (배치 이미지를 원본 해상도로 렌더)
    var savedDisplayPerf = null;
    try {
        savedDisplayPerf = doc.viewPreferences.displayPerformance;
        doc.viewPreferences.displayPerformance = ViewDisplaySettings.HIGH_QUALITY;
    } catch (e) {}

    // 개별 페이지 아이템의 localDisplaySetting도 HIGH_QUALITY로 강제
    // (문서 레벨 설정을 개별 아이템이 Typical/Fast로 오버라이드하면 저품질 렌더링됨)
    var savedLocalSettings = [];
    try {
        for (var li = 0; li < allItems.length; li++) {
            try {
                var localSetting = allItems[li].localDisplaySetting;
                if (localSetting !== ViewDisplaySettings.DEFAULT_VALUE
                    && localSetting !== ViewDisplaySettings.HIGH_QUALITY) {
                    savedLocalSettings.push({ item: allItems[li], setting: localSetting });
                    allItems[li].localDisplaySetting = ViewDisplaySettings.HIGH_QUALITY;
                }
            } catch (e2) {}
        }
    } catch (e) {}

    var results = [];

    // 편집 가능 텍스트 프레임 수집 — classifyTextFrame()으로 단일 분류
    var editableFrames = [];
    var editableFrameIds = {};
    for (var i = 0; i < allItems.length; i++) {
        var cls = classifyTextFrameCached(allItems[i]);
        if (cls === "editable") {
            editableFrames.push(allItems[i]);
            editableFrameIds[allItems[i].id] = true;
        }
    }

    // Table-inline whole-text PNG fallback removed: editable table text is owned by HWPX.
    var tableInlineRendered = [];  // kept for result schema compatibility
    var inlineObjects = [];  // { id, file, parentStoryId, bounds, pageIndex }
    var processedStoryIds = {};  // Story 중복 방지
    var inlineScanFrames = editableFrames.slice(0);
    var inlineScanFrameIds = {};
    for (var _esid in editableFrameIds) inlineScanFrameIds[_esid] = true;
    try {
        var masterSpreadsForInline = doc.masterSpreads.everyItem().getElements();
        for (var msi = 0; msi < masterSpreadsForInline.length; msi++) {
            var masterItemsForInline = [];
            try { masterItemsForInline = masterSpreadsForInline[msi].allPageItems; } catch (eMsItems) {}
            for (var mii = 0; mii < masterItemsForInline.length; mii++) {
                var masterTfForInline = masterItemsForInline[mii];
                try {
                    if (masterTfForInline.constructor.name !== "TextFrame") continue;
                    if (classifyTextFrameCached(masterTfForInline) !== "editable") continue;
                    if (inlineScanFrameIds[masterTfForInline.id]) continue;
                    inlineScanFrames.push(masterTfForInline);
                    inlineScanFrameIds[masterTfForInline.id] = true;
                } catch (eMasterInlineTf) {}
            }
        }
    } catch (eMasterInlineScan) {}
    try {

    // 인라인 객체 추출: 편집 TextFrame의 Story에 앵커된 그래픽을 개별 PNG로 렌더
    for (var ei = 0; ei < inlineScanFrames.length; ei++) {
        var eTf = inlineScanFrames[ei];
        try {
            var eStory = eTf.parentStory;
            // 같은 Story를 이미 처리했으면 건너뜀
            var eStoryKey = eStory.id.toString();
            if (processedStoryIds[eStoryKey]) continue;
            processedStoryIds[eStoryKey] = true;
            // Story의 모든 pageItems에서 인라인 객체 탐색
            var eAllItems = eStory.allPageItems;
            for (var eai = 0; eai < eAllItems.length; eai++) {
                var inItem = eAllItems[eai];
                // TextFrame 자체는 건너뜀
                if (inItem.constructor.name === "TextFrame") continue;
                // 인라인 앵커 객체만 (부모가 Character/InsertionPoint/Story)
                if (!isInlineItem(inItem)) continue;
                // Group 등의 자식이 중복으로 나오지 않도록 최상위 인라인 객체만 처리
                // (부모가 Group이고 그 Group도 인라인이면 건너뜀 — Group 전체를 렌더링)
                try {
                    var inParent = inItem.parent;
                    if (inParent && inParent.constructor.name === "Group" && isInlineItem(inParent)) continue;
                    if (inParent && inParent.constructor.name === "Rectangle" && isInlineItem(inParent)) continue;
                } catch (ep) {}

                // 투명 스페이서 객체 건너뛰기 (fillColor=None, strokeColor=None, 콘텐츠 없음)
                try {
                    var inType = inItem.constructor.name;
                    if (inType === "Rectangle" || inType === "Polygon" || inType === "Oval") {
                        var inFill = inItem.fillColor ? inItem.fillColor.name : "None";
                        var inStroke = inItem.strokeColor ? inItem.strokeColor.name : "None";
                        var inSW = inItem.strokeWeight || 0;
                        var inContent = inItem.contentType ? inItem.contentType.toString() : "";
                        // 채움/선 없고, 그래픽 콘텐츠 없는 빈 프레임 → 스페이서
                        if ((inFill === "None" || inFill === "[None]") &&
                            ((inStroke === "None" || inStroke === "[None]") || inSW === 0) &&
                            inContent !== "1886548852" /* GraphicType with image */ ) {
                            // allGraphics 확인 — 이미지가 있으면 건너뛰지 않음
                            var hasGraphic = false;
                            try { hasGraphic = inItem.allGraphics && inItem.allGraphics.length > 0; } catch(eg) {}
                            if (!hasGraphic) continue;
                        }
                    }
                } catch (eSpacer) {}

                var inId = inItem.id;
                var inFileName = "inline_" + inId + ".png";
                var inOutFile = File(renderDir + "/" + inFileName);
                // Group 내부의 TextFrame 텍스트는 HWPX 텍스트로 별도 배치되므로
                // 렌더링 PNG에는 텍스트를 제외한다. fillColor=None 방식은 일부
                // inline export에서 픽셀에 텍스트가 남을 수 있어 TF 자체를 잠시 숨긴다.
                var savedInlineTextFrames = [];
                var inlineHiddenTextFrameIds = [];
                var inlineCompleteMarkerEditableIds = [];
                var inlineCompleteMarker = false;
                try {
                    // SPEC-020: Group 뿐 아니라 Rectangle/Polygon/Oval 컨테이너 안의
                    // TextFrame 텍스트도 PNG 렌더링 시 숨김 — 텍스트는 변환 결과에서
                    // 별도 오버레이되므로 PNG에 같이 그려지면 이중 렌더링이 발생한다.
                    var containerType = inItem.constructor.name;
                    inlineCompleteMarker = (containerType === "Group" && _isVisualMarkerLabelGroup(inItem));
                    if (inlineCompleteMarker) {
                        try { inlineCompleteMarkerEditableIds = _collectTextFrameIds(inItem, true, true); } catch (eIds) {}
                    }
                    var shouldHideText = (containerType === "Group"
                            || containerType === "Rectangle"
                            || containerType === "Polygon"
                            || containerType === "Oval");
                    if (inlineCompleteMarker) shouldHideText = false;
                    if (shouldHideText) {
                        var groupItems = inItem.allPageItems;
                        for (var gki = 0; gki < groupItems.length; gki++) {
                            var gki_it = groupItems[gki];
                            if (gki_it.constructor.name === "TextFrame") {
                                inlineHiddenTextFrameIds.push(gki_it.id);
                            }
                        }
                        if (inlineHiddenTextFrameIds.length > 0) {
                            savedInlineTextFrames = hideTextFrames(inItem);
                        }
                    }
                } catch (eWalk) {}
                try {
                    // 인라인 객체는 배경 위에 얹히므로 투명 배경 필요
                    try { app.pngExportPreferences.transparentBackground = true; } catch (e) {}
                    inItem.exportFile(ExportFormat.PNG_FORMAT, inOutFile);
                    try { app.pngExportPreferences.transparentBackground = false; } catch (e) {}
                    if (inOutFile.exists) {
                        var inBounds = null;
                        try {
                            // 텍스트 숨김 상태에서는 visibleBounds가 축소될 수 있으므로 geometricBounds 우선
                            inBounds = arrCopy(inItem.geometricBounds);
                        } catch (eb) {}

                        var inPageIdx = -1;
                        try {
                            var inPage = eTf.parentPage;
                            if (inPage) inPageIdx = inPage.documentOffset;
                        } catch (ep) {}

                        var _inlineHasHiddenText = inlineHiddenTextFrameIds && inlineHiddenTextFrameIds.length > 0;
                        inlineObjects.push(applyRenderOwnership({
                            id: inId,
                            file: "rendered_frames/" + inFileName,
                            parentStoryId: eStory.id.toString(),
                            bounds: inBounds,
                            pageIndex: inPageIdx,
                            type: "inline_object",
                            childIds: inlineHiddenTextFrameIds
                        }, inItem, {
                            textHiddenBeforeExport: _inlineHasHiddenText,
                            textOwner: inlineCompleteMarker ? "indesign_png" : (_inlineHasHiddenText ? "hwpx_tf" : "none"),
                            containsText: inlineCompleteMarker ? true : false,
                            containsEditableText: inlineCompleteMarker ? true : false,
                            placementAllowed: true,
                            editableTextFrameIds: inlineCompleteMarker ? inlineCompleteMarkerEditableIds : inlineHiddenTextFrameIds,
                            reason: inlineCompleteMarker ? "visual_marker_label_indesign_png" : (_inlineHasHiddenText ? "inline_text_hidden" : "inline_graphic_only")
                        }));
                    }
                } catch (eRender) {}
                restoreTextFrames(savedInlineTextFrames);
            }
        } catch (eInline) {}
    }
    } finally {
        // localDisplaySetting 복원
        for (var ri = 0; ri < savedLocalSettings.length; ri++) {
            try { savedLocalSettings[ri].item.localDisplaySetting = savedLocalSettings[ri].setting; } catch (e) {}
        }
        // Display Performance 복원
        if (savedDisplayPerf !== null) {
            try { doc.viewPreferences.displayPerformance = savedDisplayPerf; } catch (e) {}
        }
        // transparentBackground 복원 — inline render가 false로 두고 예외 탈출할 경우 대비
        try { app.pngExportPreferences.transparentBackground = true; } catch (e) {}
    }

    // 인라인 객체를 결과에 추가
    for (var ioi = 0; ioi < inlineObjects.length; ioi++) {
        results.push(inlineObjects[ioi]);
    }

    return { items: results, editableFrameIds: editableFrameIds, tableInlineRendered: tableInlineRendered };
}

/**
 * 문서에 사용된 폰트의 글리프 메트릭을 측정한다.
 * 임시 TextFrame을 생성하여 한글/영문 샘플 텍스트의 폭, weight, x-height, ascent/descent를 측정.
 */
function measureFontMetrics(doc) {
    var korSample = "\uAC00\uB098\uB2E4\uB77C\uB9C8\uBC14\uC0AC\uC544\uC790\uCC28\uCE74\uD0C0\uD30C\uD558"; // 가나다라마바사아자차카타파하
    var latSample = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    var testSize = 10; // 10pt 기준

    // 문서에서 사용된 폰트 수집 (doc.fonts = 실제 설치+사용 가능한 폰트)
    var usedFonts = {};
    try {
        var docFonts = doc.fonts;
        for (var i = 0; i < docFonts.length; i++) {
            try {
                var f = docFonts[i];
                var ff = f.fontFamily;
                if (f.status !== FontStatus.INSTALLED) continue; // 설치된 폰트만
                if (!usedFonts[ff]) {
                    usedFonts[ff] = {
                        fontObj: f, // 폰트 객체 직접 사용
                        style: f.fontStyleName
                    };
            }
        } catch(e) {}
    }
    } catch(e) {}

    var results = [];

    // 임시 TextFrame 생성
    var tf;
    try {
        tf = doc.pages[0].textFrames.add();
        tf.geometricBounds = [0, 0, 100, 1000]; // 충분히 넓게
        tf.textFramePreferences.autoSizingType = AutoSizingTypeEnum.OFF;
    } catch(e) {
        return results;
    }

    for (var family in usedFonts) {
        try {
            var fontInfo = usedFonts[family];
            var fontObj = fontInfo.fontObj; // 폰트 객체 직접 사용

            // weight 추론 (fontStyleName에서)
            var styleLower = fontInfo.style.toLowerCase();
            var weight = 400;
            if (styleLower.indexOf("thin") >= 0 || styleLower.indexOf("hairline") >= 0) weight = 100;
            else if (styleLower.indexOf("ultralight") >= 0 || styleLower.indexOf("extralight") >= 0) weight = 200;
            else if (styleLower.indexOf("light") >= 0) weight = 300;
            else if (styleLower.indexOf("black") >= 0 || styleLower.indexOf("heavy") >= 0) weight = 900;
            else if (styleLower.indexOf("extrabold") >= 0 || styleLower.indexOf("ultrabold") >= 0) weight = 800;
            else if (styleLower.indexOf("bold") >= 0) weight = 700;
            else if (styleLower.indexOf("semibold") >= 0 || styleLower.indexOf("demibold") >= 0) weight = 600;
            else if (styleLower.indexOf("medium") >= 0) weight = 500;

            // 한글 폭 측정 — 적용 후 실제 폰트 확인
            var avgKorWidth = 0;
            try {
                tf.contents = korSample;
                tf.characters.everyItem().appliedFont = fontObj;
                tf.characters.everyItem().pointSize = testSize;
                tf.characters.everyItem().tracking = 0;
                tf.characters.everyItem().desiredLetterSpacing = 0;
                // 실제 적용된 폰트가 요청한 폰트와 같은지 확인
                var actualFont = tf.characters[0].appliedFont.fontFamily;
                if (actualFont === family && tf.lines.length > 0) {
                    var korTotalWidth = tf.lines[0].endHorizontalOffset - tf.lines[0].horizontalOffset;
                    avgKorWidth = korTotalWidth / korSample.length;
                }
            } catch(e2) { /* 한글 미지원 폰트 */ }

            // 영문 폭 측정
            var avgLatWidth = 0;
            try {
                tf.contents = latSample;
                tf.characters.everyItem().appliedFont = fontObj;
                tf.characters.everyItem().pointSize = testSize;
                tf.characters.everyItem().tracking = 0;
                tf.characters.everyItem().desiredLetterSpacing = 0;
                var actualFontEn = tf.characters[0].appliedFont.fontFamily;
                if (actualFontEn === family && tf.lines.length > 0) {
                    var latTotalWidth = tf.lines[0].endHorizontalOffset - tf.lines[0].horizontalOffset;
                    avgLatWidth = latTotalWidth / latSample.length;
                }
            } catch(e3) {}

            // x-height, ascent/descent 생략 — korWidth/latWidth만 수집
            results.push({
                family: family,
                style: fontInfo.style,
                korWidth: Math.round(avgKorWidth * 100) / 100,
                latWidth: Math.round(avgLatWidth * 100) / 100,
                weight: weight
            });
        } catch(e6) {
            // 폰트 측정 실패 — 스킵
        }
    }

    try { tf.remove(); } catch(e) {}

    return results;
}

function collectDocumentInfo(doc) {
    var fullName = "";
    try { fullName = doc.fullName.fsName; } catch (e) { fullName = doc.name; }
    return {
        name: doc.name,
        fullName: fullName,
        pageCount: doc.pages.length,
        spreadCount: doc.spreads.length,
        storyCount: doc.stories.length,
        pageWidth: doc.documentPreferences.pageWidth,
        pageHeight: doc.documentPreferences.pageHeight,
        facingPages: doc.documentPreferences.facingPages
    };
}

// SPEC-030 B.3: Java는 name + justification만 사용 — 나머지 필드 제거
function collectParagraphStyles(doc) {
    var styles = [];
    for (var i = 0; i < doc.allParagraphStyles.length; i++) {
        var ps = doc.allParagraphStyles[i];
        try {
            styles.push({
                name: ps.name,
                justification: ps.justification.toString()
            });
        } catch (e) {
            try { styles.push({ name: ps.name, justification: "LEFT_ALIGN" }); } catch (e2) {}
        }
    }
    return styles;
}

// --- 색상 수집 (hex 변환 포함) ---

function clamp(v, lo, hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

function toHex2(n) {
    var hex = Math.round(clamp(n, 0, 255)).toString(16);
    return hex.length < 2 ? "0" + hex : hex;
}

function rgbToHex(r, g, b) {
    return "#" + toHex2(r) + toHex2(g) + toHex2(b);
}

function cmykToHex(c, m, y, k) {
    // CMYK 0~100 → RGB 0~255
    var c1 = c / 100, m1 = m / 100, y1 = y / 100, k1 = k / 100;
    var r = 255 * (1 - c1) * (1 - k1);
    var g = 255 * (1 - m1) * (1 - k1);
    var b = 255 * (1 - y1) * (1 - k1);
    return "#" + toHex2(r) + toHex2(g) + toHex2(b);
}

function colorToHex(c) {
    try {
        var space = c.space.toString();
        var vals = c.colorValue;
        if (space.indexOf("RGB") >= 0 && vals.length >= 3) {
            return rgbToHex(vals[0], vals[1], vals[2]);
        }
        if (space.indexOf("CMYK") >= 0 && vals.length >= 4) {
            return cmykToHex(vals[0], vals[1], vals[2], vals[3]);
        }
    } catch (e) {}
    return null;
}

function collectColors(doc) {
    var colors = [];
    for (var i = 0; i < doc.colors.length; i++) {
        var c = doc.colors[i];
        try {
            colors.push({
                name: c.name,
                model: c.model.toString(),
                space: c.space.toString(),
                colorValue: c.colorValue,
                hex: colorToHex(c)
            });
        } catch (e) {
            colors.push({ name: c.name, error: e.message });
        }
    }
    return colors;
}

function collectFonts(doc) {
    var fonts = [];
    for (var i = 0; i < doc.fonts.length; i++) {
        var f = doc.fonts[i];
        try {
            fonts.push({
                name: f.name,
                fontFamily: f.fontFamily,
                fontStyleName: f.fontStyleName,
                fontType: f.fontType.toString(),
                status: f.status.toString()
            });
        } catch (e) {
            fonts.push({ name: f.name || "unknown", error: e.message });
        }
    }
    return fonts;
}

/**
 * 텍스트 프레임의 조판 결과(composed lines)를 수집한다.
 * InDesign 조판 엔진이 처리한 실제 라인 배치를 그대로 추출.
 * @param tf TextFrame 객체
 * @returns composedLines 배열 [{bounds, text, paraIndex, runs}]
 */
function collectComposedLines(tf) {
    var result = [];
    var lines = tf.lines.everyItem().getElements();
    if (lines.length === 0) return result;

    // 프레임 기준 좌표 (insetSpacing 반영)
    var fBounds = tf.geometricBounds; // [top, left, bottom, right]
    var fLeft = fBounds[1];
    var fRight = fBounds[3];
    try {
        var inset = tf.textFramePreferences.insetSpacing;
        fLeft += inset[1];   // left inset
        fRight -= inset[3];  // right inset
    } catch (e) {}

    // 단락 인덱스 추적: 라인의 contents에 \r이 있으면 단락 끝
    var paraIndex = 0;

    for (var li = 0; li < lines.length; li++) {
        var line = lines[li];
        var lineData = {
            bounds: null,
            text: "",
            paraIndex: paraIndex,
            wrapIndentLeft: 0,
            wrapIndentRight: 0
        };

        // line에는 geometricBounds가 없으므로 baseline/ascent/descent/horizontalOffset으로 계산
        try {
            var bl = line.baseline;
            var asc = line.ascent;
            var desc = line.descent;
            var hOff = line.horizontalOffset;
            var endH = line.endHorizontalOffset;
            lineData.bounds = [bl - asc, hOff, bl + desc, endH];

            // wrap indent: 프레임 내부 기준 좌우 밀림량 (양수 = 밀림)
            var indentL = hOff - fLeft;
            var indentR = fRight - endH;
            if (indentL > 0.5) lineData.wrapIndentLeft = Math.round(indentL * 100) / 100;
            if (indentR > 0.5) lineData.wrapIndentRight = Math.round(indentR * 100) / 100;
        } catch (e) { continue; }

        try { lineData.text = line.contents; } catch (e) {}

        // \r로 끝나면 단락 끝 → 다음 라인은 새 단락
        if (lineData.text && lineData.text.charAt(lineData.text.length - 1) === "\r") {
            paraIndex++;
        }

        result.push(lineData);
    }
    return result;
}

/**
 * GREP/중첩 스타일 보정: story.characters로 문자별 색상/크기를 확인하여 런을 분할.
 * textStyleRange.characters는 GREP 스타일을 반영하지 않으므로,
 * story.characters의 index를 사용하여 실제 렌더링 속성을 조회.
 */
function splitRunByStoryChars(story, rng, runData, para) {
    try {
        // para.characters[idx] 개별 접근 (GREP 스타일 반영)
        // everyItem().getElements()는 GREP 미반영 가능
        var paraCharCount = para.characters.length;
        var rngCharCount = rng.characters.length;
        if (rngCharCount <= 1) return [runData];

        // rng 시작 위치 계산
        var rngStart = 0;
        try {
            var paraFirstIdx = para.characters[0].index;
            var rngFirstIdx = rng.characters[0].index;
            rngStart = rngFirstIdx - paraFirstIdx;
        } catch (e) {}
        if (rngStart < 0) rngStart = 0;

        // FFFC 분할된 partRun인 경우: runData.text가 rng.contents의 부분 문자열
        // → rng 내에서 partRun 텍스트의 실제 시작 위치를 찾아 rngStart 보정
        var runText = runData.text || "";
        var rngContents = "";
        try { rngContents = rng.contents; } catch (e) {}
        if (runText.length < rngCharCount && rngContents.indexOf("\uFFFC") >= 0) {
            // rng 원본에서 runText의 시작 위치 찾기 (FFFC 포함 인덱스)
            var searchIdx = rngContents.indexOf(runText);
            if (searchIdx > 0) {
                rngStart += searchIdx;
            } else if (searchIdx < 0 && runText.length > 3) {
                // 정확한 매칭 실패 시 앞부분 3자로 검색
                var key3 = runText.substring(0, 3);
                var keyIdx = rngContents.indexOf(key3);
                if (keyIdx > 0) rngStart += keyIdx;
            }
        }

        var rngLen = Math.min(runText.length > 0 ? runText.length : rngCharCount, paraCharCount - rngStart);
        if (rngLen <= 1) return [runData];

        // DOM 접근 캐시 (동일 인덱스 재접근 방지)
        var propsCache = {};
        function getCharProps(absIdx) {
            if (propsCache[absIdx]) return propsCache[absIdx];
            var color = null, size = null, font = null, style = null;
            try { color = para.characters[absIdx].fillColor ? para.characters[absIdx].fillColor.name : null; } catch (e) {}
            try { size = para.characters[absIdx].pointSize; } catch (e) {}
            try { font = para.characters[absIdx].appliedFont.fontFamily; } catch (e) {}
            try { style = para.characters[absIdx].fontStyle; } catch (e) {}
            // GREP 스타일로 적용된 이탤릭 감지: fontStyle이 숫자(가변 폰트 웨이트)인데
            // appliedCharacterStyle에 "이탤릭" 또는 "Italic"이 포함되면 fontStyle을 "Italic"으로 보정
            if (style && /^\d+$/.test(style)) {
                try {
                    var csName = para.characters[absIdx].appliedCharacterStyle.name;
                    if (csName && (csName.indexOf("이탤릭") >= 0 || csName.toLowerCase().indexOf("italic") >= 0)) {
                        style = "Italic";
                    }
                } catch (e2) {}
            }
            var p = { color: color, size: size, font: font, style: style };
            propsCache[absIdx] = p;
            return p;
        }
        function propsEqual(a, b) {
            return a.color === b.color && a.size === b.size && a.font === b.font && a.style === b.style;
        }

        // ParagraphStyle에 GREP 스타일이 있으면 빠른 체크 건너뛰고 바로 이진 탐색
        var hasGrepStyles = false;
        try {
            var ngs = para.appliedParagraphStyle.nestedGrepStyles;
            if (ngs && ngs.length > 0) hasGrepStyles = true;
        } catch (e3) {}
        var firstProps = getCharProps(rngStart);
        var allSame = false;
        if (!hasGrepStyles) {
            // GREP 없는 경우에만 빠른 체크 (3점 + 1/4, 3/4)
            var lastProps = getCharProps(rngStart + rngLen - 1);
            var midProps = rngLen > 2 ? getCharProps(rngStart + Math.floor(rngLen / 2)) : firstProps;
            allSame = propsEqual(firstProps, lastProps) && propsEqual(firstProps, midProps);
            if (allSame && rngLen > 4) {
                var q1Props = getCharProps(rngStart + Math.floor(rngLen / 4));
                var q3Props = getCharProps(rngStart + Math.floor(rngLen * 3 / 4));
                if (!propsEqual(firstProps, q1Props) || !propsEqual(firstProps, q3Props)) {
                    allSame = false;
                }
            }
        }
        // GREP 있고 빠른 체크 실패 → 순차 스캔으로 경계 찾기
        if (!allSame && hasGrepStyles && rngLen > 1) {
            var grepBoundaries = [];
            var prevProps = getCharProps(rngStart);
            for (var gi = 1; gi < rngLen; gi++) {
                var curProps = getCharProps(rngStart + gi);
                if (!propsEqual(prevProps, curProps)) {
                    grepBoundaries.push(gi);
                }
                prevProps = curProps;
            }
            if (grepBoundaries.length > 0) {
                // 세그먼트별 런 생성
                var fullText2 = runData.text || "";
                var useSlice2 = (fullText2.length === rngLen);
                var starts2 = [0];
                for (var gb = 0; gb < grepBoundaries.length; gb++) starts2.push(grepBoundaries[gb]);
                var ends2 = [];
                for (var gb2 = 0; gb2 < grepBoundaries.length; gb2++) ends2.push(grepBoundaries[gb2]);
                ends2.push(rngLen);

                var result2 = [];
                for (var gs2 = 0; gs2 < starts2.length; gs2++) {
                    var gS = starts2[gs2], gE = ends2[gs2];
                    var gProps = getCharProps(rngStart + gS);
                    var gText = useSlice2 ? fullText2.substring(gS, gE) : "";
                    if (!useSlice2) {
                        for (var gci = gS; gci < gE; gci++) {
                            try { gText += para.characters[rngStart + gci].contents; } catch (e) {}
                        }
                    }
                    if (gText.length > 0) {
                        var gRun = {};
                        for (var gk in runData) { if (runData.hasOwnProperty(gk)) gRun[gk] = runData[gk]; }
                        gRun.text = gText;
                        gRun.fillColor = gProps.color;
                        if (gProps.size) gRun.fontSize = gProps.size;
                        if (gProps.font) gRun.fontFamily = gProps.font;
                        if (gProps.style) gRun.fontStyle = gProps.style;
                        result2.push(gRun);
                    }
                }
                return result2.length > 0 ? result2 : [runData];
            }
        }
        if (allSame) {
            // SPEC-019: 호출 전 단계(줄 ~3329)에서 runData.fillColor가 rng 첫 비제어
            // 문자(예: 단락 시작 GREP "A_문항번호-자주"의 "4")의 색상으로 덮어써졌을 수
            // 있음. partRun 텍스트는 본문 위치(rngStart 보정 후)이므로 firstProps가
            // 진짜 본문 속성. 일관성 위해 runData를 firstProps로 갱신해서 반환.
            runData.fillColor = firstProps.color;
            if (firstProps.size) runData.fontSize = firstProps.size;
            if (firstProps.font) runData.fontFamily = firstProps.font;
            if (firstProps.style) runData.fontStyle = firstProps.style;
            return [runData];
        }

        // 이진 탐색으로 스타일 변경 경계 찾기 — O(k × log n) DOM 접근
        // (k=변경점 수, 보통 1~3개. 100자 런에서 300회→30회로 감소)
        var boundaries = [];
        function findBoundaries(lo, hi) {
            if (hi - lo <= 0) return;
            var loProps = getCharProps(rngStart + lo);
            var hiProps = getCharProps(rngStart + hi);
            if (hi - lo === 1) {
                if (!propsEqual(loProps, hiProps)) boundaries.push(hi);
                return;
            }
            var mid = Math.floor((lo + hi) / 2);
            var midProps = getCharProps(rngStart + mid);
            // A-B-A 대응: lo==hi여도 mid가 다르면 양쪽 재귀
            if (propsEqual(loProps, hiProps) && propsEqual(loProps, midProps)) return;
            findBoundaries(lo, mid);
            findBoundaries(mid, hi);
        }
        findBoundaries(0, rngLen - 1);

        if (boundaries.length === 0) return [runData];

        // 경계 정렬 & 중복 제거
        boundaries.sort(function(a, b) { return a - b; });
        var uniq = [boundaries[0]];
        for (var bi = 1; bi < boundaries.length; bi++) {
            if (boundaries[bi] !== boundaries[bi - 1]) uniq.push(boundaries[bi]);
        }

        // 세그먼트별 런 생성
        var fullText = runData.text || "";
        var useSlice = (fullText.length === rngLen);
        var segStarts = [0];
        for (var si = 0; si < uniq.length; si++) segStarts.push(uniq[si]);
        var segEnds = [];
        for (var si2 = 0; si2 < uniq.length; si2++) segEnds.push(uniq[si2]);
        segEnds.push(rngLen);

        var result = [];
        for (var s = 0; s < segStarts.length; s++) {
            var segS = segStarts[s];
            var segE = segEnds[s];
            var segProps = getCharProps(rngStart + segS);

            var segText = "";
            if (useSlice) {
                segText = fullText.substring(segS, segE);
            } else {
                for (var ci = segS; ci < segE; ci++) {
                    try { segText += para.characters[rngStart + ci].contents; } catch (e) {}
                }
            }

            if (segText.length > 0) {
                var splitRun = {};
                for (var k in runData) { if (runData.hasOwnProperty(k)) splitRun[k] = runData[k]; }
                splitRun.text = segText;
                splitRun.fillColor = segProps.color;
                if (segProps.size) splitRun.fontSize = segProps.size;
                if (segProps.font) splitRun.fontFamily = segProps.font;
                if (segProps.style) splitRun.fontStyle = segProps.style;
                result.push(splitRun);
            }
        }

        return result.length > 0 ? result : [runData];
    } catch (e) {
        runData._splitError = e.toString();
        return [runData];
    }
}

/**
 * (레거시) textStyleRange.characters 기반 분할 — GREP 스타일 미반영.
 */
function splitRunByCharColor(rng, runData) {
    try {
        var chars = rng.characters.everyItem().getElements();
        if (chars.length <= 1) return [runData];

        // 첫 문자와 마지막 문자 색상+크기 비교 (빠른 체크)
        var firstColor = null, lastColor = null;
        var firstSize = null, lastSize = null;
        try { firstColor = chars[0].fillColor ? chars[0].fillColor.name : null; } catch (e) {}
        try { lastColor = chars[chars.length - 1].fillColor ? chars[chars.length - 1].fillColor.name : null; } catch (e) {}
        try { firstSize = chars[0].pointSize; } catch (e) {}
        try { lastSize = chars[chars.length - 1].pointSize; } catch (e) {}
        if (firstColor === lastColor && firstSize === lastSize) return [runData]; // 동일 → 분할 불필요

        // 문자별 스캔하여 색상 또는 크기 변화 지점에서 분할
        var result = [];
        var curColor = firstColor;
        var curSize = firstSize;
        var curText = "";
        for (var ci = 0; ci < chars.length; ci++) {
            var chColor = null, chSize = null;
            try { chColor = chars[ci].fillColor ? chars[ci].fillColor.name : null; } catch (e) {}
            try { chSize = chars[ci].pointSize; } catch (e) {}
            if ((chColor !== curColor || chSize !== curSize) && curText.length > 0) {
                // 색상 또는 크기 변경 → 이전 런 저장
                var splitRun = {};
                for (var k in runData) { if (runData.hasOwnProperty(k)) splitRun[k] = runData[k]; }
                splitRun.text = curText;
                splitRun.fillColor = curColor;
                if (curSize) splitRun.fontSize = curSize;
                result.push(splitRun);
                curText = "";
                curColor = chColor;
                curSize = chSize;
            }
            try { curText += chars[ci].contents; } catch (e) {}
        }
        // 마지막 런
        if (curText.length > 0) {
            var lastRun = {};
            for (var k2 in runData) { if (runData.hasOwnProperty(k2)) lastRun[k2] = runData[k2]; }
            lastRun.text = curText;
            lastRun.fillColor = curColor;
            if (curSize) lastRun.fontSize = curSize;
            result.push(lastRun);
        }
        return result.length > 0 ? result : [runData];
    } catch (e) {
        return [runData];
    }
}

// --- 스토리 수집 (문단 속성 + 런 확장 속성) ---

function collectStories(doc, outputDir, rangePageCount, rangeStoryIds) {
    var stories = [];
    var totalStories = doc.stories.length;
    var collected = 0;
    for (var s = 0; s < totalStories; s++) {
        var story = doc.stories[s];
        // 페이지 범위 필터: 범위 내 프레임과 연결된 스토리만 수집
        if (rangeStoryIds && !rangeStoryIds[story.id.toString()]) {
            continue;
        }
        collected++;
        if (collected % 10 === 0) {
            writeProgress(outputDir, "resolved_stories", collected, totalStories);
        }
        var storyData = {
            id: story.id.toString(),
            length: story.length,
            paragraphCount: story.paragraphs.length,
            paragraphs: [],
            tables: []
        };

        // 문단 수집 — 실제 단락 수를 \r로 확인하여 중복 방지
        var paras = story.paragraphs.everyItem().getElements();
        var realParaCount = paras.length;
        try {
            var storyText = story.contents;
            if (typeof storyText === "string") {
                // \r로 분리된 실제 단락 수 (마지막 \r 이후 빈 문자열 제외)
                var splits = storyText.split("\r");
                while (splits.length > 0 && splits[splits.length - 1] === "") splits.pop();
                realParaCount = splits.length;
            }
        } catch (e) {}
        var paraLimit = Math.min(paras.length, realParaCount);
        for (var p = 0; p < paraLimit; p++) {
            var para = paras[p];
            var paraData = {
                styleName: "[Unknown]",
                leading: null,
                autoLeading: null,
                justification: null,
                spaceBefore: null,
                spaceAfter: null,
                firstLineIndent: null,
                leftIndent: null,
                rightIndent: null,
                shadingOn: false,
                shadingColor: null,
                shadingTint: null,
                tabStops: [],
                runs: []
            };

            try { paraData.styleName = para.appliedParagraphStyle.name; } catch (e) {}
            try { paraData.leading = para.leading; } catch (e) {}
            try { paraData.autoLeading = para.autoLeading; } catch (e) {}
            try { paraData.justification = para.justification.toString(); } catch (e) {}
            try { paraData.spaceBefore = para.spaceBefore; } catch (e) {}
            try { paraData.spaceAfter = para.spaceAfter; } catch (e) {}
            try { paraData.firstLineIndent = para.firstLineIndent; } catch (e) {}
            try { paraData.leftIndent = para.leftIndent; } catch (e) {}
            try { paraData.rightIndent = para.rightIndent; } catch (e) {}
            try { paraData.shadingOn = para.paragraphShadingOn; } catch (e) {}
            try { if (paraData.shadingOn && para.paragraphShadingColor) { paraData.shadingColor = para.paragraphShadingColor.name; } } catch (e) {}
            try { if (paraData.shadingOn) { paraData.shadingTint = para.paragraphShadingTint; } } catch (e) {}
            try {
                var ts = para.tabStops.everyItem().getElements();
                for (var ti = 0; ti < ts.length; ti++) {
                    paraData.tabStops.push({
                        position: ts[ti].position,
                        alignment: ts[ti].alignment.toString(),
                        leader: ts[ti].leader || null
                    });
                }
            } catch (e) {}

            // textStyleRanges 사용 (성능 최적화 — architecture.md 섹션 10)
            //
            // 주의: 하나의 CharacterStyleRange가 여러 단락(<Br/>)에 걸쳐 있는 경우,
            // `rng.contents`는 전체 범위의 텍스트를 반환한다(현재 단락 이후 텍스트까지 포함).
            // → 단락 경계로 범위를 자른 뒤 `contents`도 잘라서 사용한다.
            var paraStartIdx = -1, paraEndIdx = -1;
            try {
                var paraChars = para.characters;
                if (paraChars.length > 0) {
                    paraStartIdx = paraChars[0].index;
                    paraEndIdx = paraStartIdx + paraChars.length; // exclusive
                }
            } catch (e) {}
            try {
                var ranges = para.textStyleRanges.everyItem().getElements();
                for (var r = 0; r < ranges.length; r++) {
                    var rng = ranges[r];
                    // 단락 경계 안에서만 텍스트 사용
                    var rngText = rng.contents;
                    try {
                        if (paraStartIdx >= 0) {
                            var rngChars0 = rng.characters;
                            if (rngChars0.length > 0) {
                                var rngStartIdx = rngChars0[0].index;
                                var rngEndIdx = rngStartIdx + rngChars0.length;
                                var ovStart = Math.max(paraStartIdx, rngStartIdx);
                                var ovEnd = Math.min(paraEndIdx, rngEndIdx);
                                if (ovStart >= ovEnd) { continue; }
                                if (ovStart > rngStartIdx || ovEnd < rngEndIdx) {
                                    var sliceFrom = ovStart - rngStartIdx;
                                    var sliceLen = ovEnd - ovStart;
                                    rngText = rngText.substring(sliceFrom, sliceFrom + sliceLen);
                                }
                            }
                        }
                    } catch (eClip) {}
                    var runData = {
                        text: rngText,
                        fontFamily: null,
                        fontSize: null,
                        fontStyle: null,
                        fillColor: null,
                        charStyle: null,
                        // 확장 속성
                        tracking: null,
                        horizontalScale: null,
                        verticalScale: null,
                        baselineShift: null,
                        position: null,
                        underline: null,
                        strikeThru: null
                    };

                    try { runData.fontFamily = rng.appliedFont ? rng.appliedFont.fontFamily : null; } catch (e) {}
                    try { runData.fontSize = rng.pointSize; } catch (e) {}
                    try { runData.fontStyle = rng.fontStyle; } catch (e) {}
                    try { runData.fillColor = rng.fillColor ? rng.fillColor.name : null; } catch (e) {}
                    try { runData.charStyle = rng.appliedCharacterStyle ? rng.appliedCharacterStyle.name : null; } catch (e) {}
                    // GREP/Nested 스타일 색상 감지: 첫 번째 비제어 문자의 fillColor 확인
                    // textStyleRanges는 GREP 색상을 반영하지 않으므로 characters로 보정
                    try {
                        var rngChars = rng.characters;
                        for (var gci = 0; gci < rngChars.length && gci < 10; gci++) {
                            var gc = rngChars[gci].contents;
                            if (gc === "\uFFFC" || gc === "\u0016" || gc === "\u0018"
                                || gc === "\t" || gc === "\r" || gc === "\n" || gc === " ") continue;
                            var charFc = rngChars[gci].fillColor ? rngChars[gci].fillColor.name : null;
                            if (charFc && charFc !== runData.fillColor) {
                                runData.fillColor = charFc;
                            }
                            break;
                        }
                    } catch (eGrepColor) {}
                    // 확장 속성
                    try { runData.tracking = rng.tracking; } catch (e) {}
                    try { runData.horizontalScale = rng.horizontalScale; } catch (e) {}
                    try { runData.verticalScale = rng.verticalScale; } catch (e) {}
                    try { runData.baselineShift = rng.baselineShift; } catch (e) {}
                    try { runData.position = rng.position.toString(); } catch (e) {}
                    try { runData.underline = rng.underline; } catch (e) {}
                    try { runData.strikeThru = rng.strikeThru; } catch (e) {}

                    // U+FFFC(인라인 마커) + U+0016(블록 앵커 마커) 분리
                    // SPEC-020: InDesign이 사용자 정의 위치/Above-Line 앵커에는 \x16 을 쓰는데
                    // 기존 로직은 \uFFFC 만 인식해서 모든 \x16 앵커가 누락됐다.
                    var runText = runData.text || "";
                    if (runText.indexOf("\uFFFC") >= 0 || runText.indexOf("\u0016") >= 0) {
                        var parts = runText.split(/[\uFFFC\u0016]/);
                        var anchoredIds = [];
                        try {
                            var rngChars = rng.characters.everyItem().getElements();
                            for (var rc = 0; rc < rngChars.length; rc++) {
                                var rcContent = rngChars[rc].contents;
                                if (rcContent === "\uFFFC" || rcContent === "\u0016") {
                                    try {
                                        var anchItems = rngChars[rc].allPageItems;
                                        if (anchItems.length > 0) {
                                            anchoredIds.push(anchItems[0].id);
                                        } else {
                                            anchoredIds.push(null);
                                        }
                                    } catch (ea) { anchoredIds.push(null); }
                                }
                            }
                        } catch (ea2) {}

                        var anchorIdx = 0;
                        for (var pi2 = 0; pi2 < parts.length; pi2++) {
                            if (parts[pi2].length > 0) {
                                var partRun = {};
                                for (var rk in runData) { partRun[rk] = runData[rk]; }
                                partRun.text = parts[pi2];
                                var partSplits = splitRunByStoryChars(story, rng, partRun, para);
                                for (var ps = 0; ps < partSplits.length; ps++) {
                                    paraData.runs.push(partSplits[ps]);
                                }
                            }
                            if (pi2 < parts.length - 1) {
                                paraData.runs.push({
                                    type: "inline_anchor",
                                    anchoredObjectId: anchorIdx < anchoredIds.length ? anchoredIds[anchorIdx] : null
                                });
                                anchorIdx++;
                            }
                        }
                    } else {
                        // GREP/중첩 스타일 보정: story.characters로 문자별 색상/크기 확인
                        var splitRuns = splitRunByStoryChars(story, rng, runData, para);
                        for (var sr = 0; sr < splitRuns.length; sr++) {
                            paraData.runs.push(splitRuns[sr]);
                        }
                    }
                }
            } catch (e) {
                // textStyleRanges 접근 실패 시 무시
            }
            storyData.paragraphs.push(paraData);
        }

        // 테이블 수집 (실제 치수)
        try {
            var tables = story.tables.everyItem().getElements();
            for (var ti = 0; ti < tables.length; ti++) {
                var tbl = tables[ti];
                var tblData = {
                    id: tbl.id.toString(),
                    rowCount: tbl.rows.length,
                    columnCount: tbl.columns.length,
                    columnWidths: [],
                    rowHeights: [],
                    bounds: null  // [top, left, bottom, right] page-relative
                };
                // 테이블 절대 위치: 첫 셀의 baseline + 행/열 크기로 bounds 계산
                try {
                    var firstCell = tbl.cells[0];
                    var firstIP = firstCell.insertionPoints[0];
                    var baseline = firstIP.baseline;
                    var horzOffset = firstIP.horizontalOffset;

                    var tblCols = tbl.columns.everyItem().getElements();
                    var tblTotalW = 0;
                    for (var twi = 0; twi < tblCols.length; twi++) tblTotalW += tblCols[twi].width;
                    var tblRows = tbl.rows.everyItem().getElements();
                    var tblTotalH = 0;
                    for (var thi = 0; thi < tblRows.length; thi++) tblTotalH += tblRows[thi].height;

                    var tblTop = baseline - tblRows[0].height;
                    var tblParentPage = null;
                    try { tblParentPage = firstCell.texts[0].parentTextFrames[0].parentPage; } catch (ep) {}
                    if (tblParentPage) {
                        var tblPageBounds = tblParentPage.bounds;
                        tblData.bounds = [
                            tblTop - tblPageBounds[0],
                            horzOffset - tblPageBounds[1],
                            tblTop + tblTotalH - tblPageBounds[0],
                            horzOffset + tblTotalW - tblPageBounds[1]
                        ];
                    }
                } catch (e) {}
                try {
                    var cols = tbl.columns.everyItem().getElements();
                    for (var ci = 0; ci < cols.length; ci++) {
                        tblData.columnWidths.push(cols[ci].width);
                    }
                } catch (e) {}
                try {
                    var rows = tbl.rows.everyItem().getElements();
                    for (var ri = 0; ri < rows.length; ri++) {
                        tblData.rowHeights.push(rows[ri].height);
                    }
                } catch (e) {}
                // NEW: 셀 내용 수집
                try {
                    tblData.cells = [];
                    var cells = tbl.cells.everyItem().getElements();
                    for (var ci2 = 0; ci2 < cells.length; ci2++) {
                        var cell = cells[ci2];
                        var cellData = {
                            row: cell.rowSpan > 0 ? Math.floor(ci2 / tbl.columns.length) : 0,
                            col: ci2 % tbl.columns.length,
                            rowSpan: cell.rowSpan,
                            colSpan: cell.columnSpan,
                            paragraphs: []
                        };
                        try { cellData.fillColor = cell.fillColor.name; } catch (ec) {}
                        try {
                            cellData.insetSpacing = [
                                cell.topInset, cell.leftInset,
                                cell.bottomInset, cell.rightInset
                            ];
                        } catch (ec) {}
                        // 셀 내 단락 수집
                        try {
                            var cellParas = cell.paragraphs.everyItem().getElements();
                            for (var cp = 0; cp < cellParas.length; cp++) {
                                var cellPara = cellParas[cp];
                                var cpData = { runs: [] };
                                try {
                                    var cellTSRs = cellPara.textStyleRanges.everyItem().getElements();
                                    for (var cr = 0; cr < cellTSRs.length; cr++) {
                                        var cellRng = cellTSRs[cr];
                                        var runData = { text: "" };
                                        try { runData.text = cellRng.contents; } catch (er) {}
                                        try { runData.fontFamily = cellRng.appliedFont.fontFamily; } catch (er) {}
                                        try { runData.pointSize = cellRng.pointSize; } catch (er) {}
                                        try { runData.fontStyle = cellRng.fontStyle; } catch (er) {}
                                        try {
                                            if (cellRng.fillColor && cellRng.fillColor.name !== "None") {
                                                runData.fillColor = cellRng.fillColor.name;
                                            }
                                        } catch (er) {}
                                        // SPEC-020: 셀 런도 inline anchor 마커(\uFFFC/\u0016) 분리
                                        var cellRunText = runData.text || "";
                                        if (cellRunText.indexOf("\uFFFC") >= 0 || cellRunText.indexOf("\u0016") >= 0) {
                                            var cellParts = cellRunText.split(/[\uFFFC\u0016]/);
                                            var cellAnchoredIds = [];
                                            try {
                                                var cellRngChars = cellRng.characters.everyItem().getElements();
                                                for (var crc = 0; crc < cellRngChars.length; crc++) {
                                                    var crcContent = cellRngChars[crc].contents;
                                                    if (crcContent === "\uFFFC" || crcContent === "\u0016") {
                                                        try {
                                                            var crcAnch = cellRngChars[crc].allPageItems;
                                                            cellAnchoredIds.push(crcAnch.length > 0 ? crcAnch[0].id : null);
                                                        } catch (eca) { cellAnchoredIds.push(null); }
                                                    }
                                                }
                                            } catch (eca2) {}
                                            var cellAnchorIdx = 0;
                                            for (var cpi = 0; cpi < cellParts.length; cpi++) {
                                                if (cellParts[cpi].length > 0) {
                                                    var cellPartRun = {};
                                                    for (var ck in runData) { cellPartRun[ck] = runData[ck]; }
                                                    cellPartRun.text = cellParts[cpi];
                                                    cpData.runs.push(cellPartRun);
                                                }
                                                if (cpi < cellParts.length - 1) {
                                                    cpData.runs.push({
                                                        type: "inline_anchor",
                                                        anchoredObjectId: cellAnchorIdx < cellAnchoredIds.length ? cellAnchoredIds[cellAnchorIdx] : null
                                                    });
                                                    cellAnchorIdx++;
                                                }
                                            }
                                        } else {
                                            cpData.runs.push(runData);
                                        }
                                    }
                                } catch (ec2) {}
                                cellData.paragraphs.push(cpData);
                            }
                        } catch (ec3) {}
                        // SPEC-020: 셀 텍스트에 anchor 마커가 없어도 (IDML에 <Content>가 없고
                        // Group이 CharacterStyleRange에 직접 임베드된 경우) cell.allPageItems
                        // 로 fallback. 이미 paragraphs 에서 잡힌 anchor ID는 건너뛴다.
                        try {
                            var capturedIds = {};
                            for (var pp = 0; pp < cellData.paragraphs.length; pp++) {
                                var rr = cellData.paragraphs[pp].runs;
                                for (var rr2 = 0; rr2 < rr.length; rr2++) {
                                    if (rr[rr2].type === "inline_anchor" && rr[rr2].anchoredObjectId != null) {
                                        capturedIds[rr[rr2].anchoredObjectId] = true;
                                    }
                                }
                            }
                            // 셀에 직접 앵커된 객체만 수집: cell.characters 를 순회하며
                            // 각 문자의 allPageItems 에서 직접 앵커 조회. allPageItems 를
                            // 재귀적으로 쓰면 중첩 TextFrame 내부 앵커(예: 18083)까지 끌려온다.
                            var missedAnchors = [];
                            try {
                                var cellChars = cell.characters.everyItem().getElements();
                                for (var cchi = 0; cchi < cellChars.length; cchi++) {
                                    try {
                                        var directAnch = cellChars[cchi].pageItems;
                                        for (var dai = 0; dai < directAnch.length; dai++) {
                                            var daId = directAnch[dai].id;
                                            if (!capturedIds[daId]) {
                                                missedAnchors.push(daId);
                                                capturedIds[daId] = true;
                                            }
                                        }
                                    } catch (edc) {}
                                }
                            } catch (ecc) {}
                            if (missedAnchors.length > 0) {
                                // 빈 단락이 없으면 하나 추가
                                if (cellData.paragraphs.length === 0) {
                                    cellData.paragraphs.push({ runs: [] });
                                }
                                var lastP = cellData.paragraphs[cellData.paragraphs.length - 1];
                                for (var ma = 0; ma < missedAnchors.length; ma++) {
                                    lastP.runs.push({
                                        type: "inline_anchor",
                                        anchoredObjectId: missedAnchors[ma]
                                    });
                                }
                            }
                        } catch (efb) {}
                        tblData.cells.push(cellData);
                    }
                } catch (e) {}

                storyData.tables.push(tblData);
            }
        } catch (e) {
            // 테이블 접근 실패 시 무시
        }

        stories.push(storyData);
    }
    return stories;
}

// --- 텍스트 프레임 수집 (오버플로/줄 수) ---

function collectTextFrames(doc, startPage, endPage, editableIds, skipRenderPagesMap, cachedAllItems) {
    if (!editableIds) editableIds = {};
    if (!skipRenderPagesMap) skipRenderPagesMap = {};
    var frames = [];
    var collectedStoryIds = {};  // 범위 내 프레임의 storyId 수집
    var collectedTfIds = {};     // 수집된 프레임 ID 추적

    try {
        // doc.textFrames는 페이지 소속 프레임만 포함할 수 있으므로
        // allPageItems에서 TextFrame을 수집하여 Spread 직속 프레임도 포함
        var srcItems = cachedAllItems || doc.allPageItems;
        var tfs = [];
        for (var ai = 0; ai < srcItems.length; ai++) {
            if (srcItems[ai].constructor.name === "TextFrame") tfs.push(srcItems[ai]);
        }
        // Pass 1: 페이지 범위 내 프레임 수집
        for (var i = 0; i < tfs.length; i++) {
            var tf = tfs[i];
            try {
                var pp = tf.parentPage;
                if (pp) {
                    var pgIdx = pp.documentOffset + 1;
                    if (pgIdx < startPage || pgIdx > endPage) continue;
                    if (skipRenderPagesMap[pgIdx]) continue; // SPEC-030: 증분 추출 스킵
                }
            } catch (e) {}
            try { collectedStoryIds[tf.parentStory.id] = true; } catch (e) {}
            collectedTfIds[tf.id] = true;
            var fData = {
                id: tf.id.toString(),
                storyId: null,
                overflows: false,
                lineCount: 0,
                paragraphStart: -1,
                paragraphEnd: -1
            };
            try { fData.storyId = tf.parentStory.id.toString(); } catch (e) {}
            try { fData.overflows = tf.overflows; } catch (e) {}
            try { fData.lineCount = tf.lines.length; } catch (e) {}

            // 이 프레임에 표시되는 문단의 Story 내 인덱스 범위 + Y좌표
            try {
                var frameParas = tf.paragraphs.everyItem().getElements();
                if (frameParas.length > 0) {
                    // paragraph.index == 스토리 내 0-based 위치 → 직접 사용
                    // storyParas.everyItem() + 선형 탐색 불필요
                    fData.paragraphStart = frameParas[0].index;
                    fData.paragraphEnd = frameParas[frameParas.length - 1].index;

                    // SPEC-030 B.3: paragraphYOffsets 계산 제거 (Java 신 파이프라인 미사용)

                    // 프레임에 보이는 각 단락의 실제 텍스트 (단락 분할점 계산용)
                    var frameParaTexts = [];
                    for (var fpt = 0; fpt < frameParas.length; fpt++) {
                        try {
                            var paraContent = frameParas[fpt].contents;
                            // \r 제거 (단락 끝 구분자)
                            if (typeof paraContent === "string") {
                                paraContent = paraContent.replace(/\r$/g, "");
                            }
                            frameParaTexts.push(paraContent || "");
                        } catch (e3) {
                            frameParaTexts.push("");
                        }
                    }
                    fData.frameParaTexts = frameParaTexts;

                    // 프레임에 실제 보이는 전체 텍스트 (오버플로우 제외)
                    try {
                        var visibleText = tf.contents;
                        if (typeof visibleText === "string") {
                            visibleText = visibleText.replace(/\r/g, "\n");
                        }
                        fData.frameVisibleText = visibleText || "";
                    } catch (e4) {
                        fData.frameVisibleText = "";
                    }
                }
            } catch (e) {}

            // Phase 3: 프레임 메타데이터 보강
            try { fData.geometricBounds = [tf.geometricBounds[0], tf.geometricBounds[1], tf.geometricBounds[2], tf.geometricBounds[3]]; } catch (e) {}
            try { fData.columnCount = tf.textFramePreferences.textColumnCount; } catch (e) {}
            try { fData.columnGutter = tf.textFramePreferences.textColumnGutter; } catch (e) {}
            try { var is = tf.textFramePreferences.insetSpacing; fData.insetSpacing = [is[0], is[1], is[2], is[3]]; } catch (e) {}
            try { fData.verticalJustification = tf.textFramePreferences.verticalJustification.toString(); } catch (e) {}
            try { fData.rotationAngle = tf.absoluteRotationAngle; } catch (e) {}

            // NEW: 스레드 체인 (previous/next TextFrame)
            try {
                var prevTF = tf.previousTextFrame;
                fData.previousFrameId = (prevTF && prevTF.id) ? prevTF.id.toString() : null;
            } catch (e) { fData.previousFrameId = null; }
            try {
                var nextTF = tf.nextTextFrame;
                fData.nextFrameId = (nextTF && nextTF.id) ? nextTF.id.toString() : null;
            } catch (e) { fData.nextFrameId = null; }

            // NEW: 인라인 여부
            fData.isInline = isInlineItem(tf);

            // NEW: z-order (페이지 내 stacking 순서)
            // allPageItems 순서를 사용 — 나중에 페이지별로 재계산
            fData.zOrder = i;  // 임시값, 아래에서 페이지별로 재계산

            // NEW: 시각 속성 (pageItems에서 중복되지만 TextFrame에 직접 포함)
            try { fData.fillColor = tf.fillColor.name; } catch (e) { fData.fillColor = null; }
            try { fData.fillTint = tf.fillTint; } catch (e) {}
            try { fData.strokeColor = tf.strokeColor.name; } catch (e) { fData.strokeColor = null; }
            try { fData.strokeWeight = tf.strokeWeight; } catch (e) {}
            try { fData.opacity = tf.transparencySettings.blendingSettings.opacity; } catch (e) {}
            try { fData.cornerRadius = tf.topLeftCornerRadius; } catch (e) {}

            // SPEC-025: 진단/분류 정보 (텍스트 이미지 렌더링 제거 작업용)
            // SPEC-030: classification 제거 (Java 신 파이프라인 미사용)
            try { fData.isMasterPageItem = !!tf.masterPageItem; } catch (e) { fData.isMasterPageItem = false; }
            try { fData.nonprinting = !!tf.nonprinting; } catch (e) { fData.nonprinting = false; }
            try { fData.onHiddenLayer = isOnHiddenLayer(tf); } catch (e) { fData.onHiddenLayer = false; }
            try {
                var charStyles = [];
                var paraStyles = [];
                var seenCs = {}, seenPs = {};
                // frameParas는 위에서 이미 로드됨 → story 단락 재로드 불필요
                var parasSrc = (typeof frameParas !== "undefined" && frameParas) ? frameParas : [];
                for (var psi = 0; psi < parasSrc.length && psi < 20; psi++) {
                    try {
                        var ps = parasSrc[psi].appliedParagraphStyle;
                        if (ps && ps.name && !seenPs[ps.name]) { seenPs[ps.name] = true; paraStyles.push(ps.name); }
                    } catch (e1) {}
                }
                // characters 전체 로드(O(문자수)) 대신 textStyleRanges(O(스타일범위수))로 대체
                var firstStory = tf.parentStory;
                if (firstStory) {
                    var tsrs = firstStory.textStyleRanges.everyItem().getElements();
                    var sampled = Math.min(tsrs.length, 30);
                    for (var csi = 0; csi < sampled; csi++) {
                        try {
                            var cs = tsrs[csi].appliedCharacterStyle;
                            if (cs && cs.name && !seenCs[cs.name]) { seenCs[cs.name] = true; charStyles.push(cs.name); }
                        } catch (e1) {}
                    }
                }
                fData.paragraphStyles = paraStyles;
                fData.characterStyles = charStyles;
            } catch (e) {}

            // NEW: page-relative bounds (페이지 bounds 차감)
            try {
                var tfPage = tf.parentPage;
                if (tfPage) {
                    var pageBounds = tfPage.bounds;
                    fData.pageIndex = tfPage.documentOffset;
                    fData.pageRelativeBounds = [
                        fData.geometricBounds[0] - pageBounds[0],
                        fData.geometricBounds[1] - pageBounds[1],
                        fData.geometricBounds[2] - pageBounds[0],
                        fData.geometricBounds[3] - pageBounds[1]
                    ];
                }
            } catch (e) {}

            // Phase 4: 조판 결과(composedLines) 수집 — editable 프레임만
            try {
                var _eidMatch = !!(editableIds[tf.id]);
                if (i < 5 || tf.id === 34295) {
                    _dbgLines.push("P4 i=" + i + " tf.id=" + tf.id + " typeof=" + typeof tf.id + " match=" + _eidMatch);
                }
                if (_eidMatch) {
                    var _cl = collectComposedLines(tf);
                    if (_cl && _cl.length > 0) {
                        fData.composedLines = _cl;
                        _dbgLines.push("  CL ok: " + _cl.length + " lines");
                    }
                }
            } catch (e) {}

            frames.push(fData);
        }

        // Pass 2: 범위 내 Story와 같은 storyId를 가진 범위 밖 프레임 추가 수집
        // (스레드 체인의 일부가 다른 페이지에 있을 때 조판 엔진 단락 분배 정보 유지)
        for (var j = 0; j < tfs.length; j++) {
            var tf2 = tfs[j];
            if (collectedTfIds[tf2.id]) continue;  // 이미 수집됨
            var sid2 = null;
            try { sid2 = tf2.parentStory.id; } catch (e) {}
            if (sid2 && collectedStoryIds[sid2]) {
                var fData2 = {
                    id: tf2.id.toString(),
                    storyId: sid2.toString(),
                    overflows: false,
                    lineCount: 0,
                    paragraphStart: -1,
                    paragraphEnd: -1
                };
                try { fData2.overflows = tf2.overflows; } catch (e) {}
                try { fData2.lineCount = tf2.lines.length; } catch (e) {}
                try {
                    var fp2 = tf2.paragraphs.everyItem().getElements();
                    if (fp2.length > 0) {
                        fData2.paragraphStart = fp2[0].index;
                        fData2.paragraphEnd = fp2[fp2.length - 1].index;
                    }
                } catch (e) {}
                try { fData2.geometricBounds = [tf2.geometricBounds[0], tf2.geometricBounds[1], tf2.geometricBounds[2], tf2.geometricBounds[3]]; } catch (e) {}
                try { fData2.columnCount = tf2.textFramePreferences.textColumnCount; } catch (e) {}
                try { fData2.columnGutter = tf2.textFramePreferences.textColumnGutter; } catch (e) {}
                try { var is2 = tf2.textFramePreferences.insetSpacing; fData2.insetSpacing = [is2[0], is2[1], is2[2], is2[3]]; } catch (e) {}
                try { fData2.verticalJustification = tf2.textFramePreferences.verticalJustification.toString(); } catch (e) {}
                try { fData2.rotationAngle = tf2.absoluteRotationAngle; } catch (e) {}
                frames.push(fData2);
            }
        }

    } catch (e) {
        // 텍스트 프레임 접근 실패 시 무시
    }
    return frames;
}

/**
 * SPEC-025 Phase 5: 마스터 스프레드 TextFrame 을 적용 페이지마다 인스턴스화한다.
 *
 * 각 master TextFrame 에 대해:
 * 1. 적용된 doc page 마다 clone TextFrame entry 추가 (synthetic id+storyId)
 * 2. storyId 가 unique 하도록 새 story entry 도 추가 (원본 story paragraphs deep copy)
 *
 * 이 방식으로 Java Phase 3 StoryConverter 는 각 clone 을 독립 single-frame 스토리로 처리 →
 * 동일 본문이 페이지마다 중복 표시됨 (단원명/하시라 머리말 케이스).
 *
 * editableIds 에도 clone id 등록.
 */
function instanceMasterFrames(doc, startPage, endPage, textFrames, stories, editableIds) {
    if (!editableIds) editableIds = {};
    var s25 = CONFIG && CONFIG.rendering && CONFIG.rendering.textFrame && CONFIG.rendering.textFrame.spec025;

    // 1) 페이지 → 적용된 마스터 매핑 (side 정보 포함)
    var masterToPages = {};  // masterSpreadId → [{docIdx, side, spreadPageIdx}, ...]
    try {
        for (var pp = 0; pp < doc.pages.length; pp++) {
            try {
                var pgIdx = pp + 1;
                if (pgIdx < startPage || pgIdx > endPage) continue;
                var am = doc.pages[pp].appliedMaster;
                if (!am) continue;
                var mid = am.id.toString();
                if (!masterToPages[mid]) masterToPages[mid] = [];
                var pgSide = "SINGLE";
                try { pgSide = doc.pages[pp].side.toString(); } catch (e) {}
                // 스프레드 내 페이지 인덱스 (0=LEFT, 1=RIGHT) — side 문자열보다 신뢰성 높음
                var pgSpreadPageIdx = -1;
                try {
                    var pgSprd = doc.pages[pp].parent;
                    var pgSprdPgs = pgSprd.pages.everyItem().getElements();
                    for (var spI = 0; spI < pgSprdPgs.length; spI++) {
                        if (pgSprdPgs[spI].id === doc.pages[pp].id) { pgSpreadPageIdx = spI; break; }
                    }
                } catch (e) {}
                masterToPages[mid].push({ docIdx: pp, side: pgSide, spreadPageIdx: pgSpreadPageIdx });
            } catch (e) {}
        }
    } catch (e) {}

    // 2) storyId → story 인덱스 매핑 (deep clone 용)
    var storyById = {};
    for (var si = 0; si < stories.length; si++) {
        storyById[stories[si].id] = stories[si];
    }

    // 3) 페이지별 override set 사전 구축 → 인스턴스 루프 내 allPageItems 반복 조회 제거
    // pageOverrideMap[docPgIdx][masterBaseId] = true  →  O(1) lookup
    var pageOverrideMap = {};
    try {
        for (var ovPp = 0; ovPp < doc.pages.length; ovPp++) {
            try {
                var ovPgNum = ovPp + 1;
                if (ovPgNum < startPage || ovPgNum > endPage) continue;
                var ovItems = doc.pages[ovPp].allPageItems;
                for (var ovI = 0; ovI < ovItems.length; ovI++) {
                    try {
                        var ovMpi = ovItems[ovI].masterPageItem;
                        if (ovMpi) {
                            if (!pageOverrideMap[ovPp]) pageOverrideMap[ovPp] = {};
                            pageOverrideMap[ovPp][ovMpi.id.toString()] = true;
                        }
                    } catch (e) {}
                }
            } catch (e) {}
        }
    } catch (e) {}

    // 4) 마스터 스프레드 순회 → 각 TextFrame 인스턴스화
    var msArr = [];
    try { msArr = doc.masterSpreads.everyItem().getElements(); } catch (e) {}
    var frameClones = 0, storyClones = 0;
    for (var ms = 0; ms < msArr.length; ms++) {
        var mspread = msArr[ms];
        var msId = "";
        try { msId = mspread.id.toString(); } catch (e) { continue; }
        var appliedPages = masterToPages[msId] || [];
        if (appliedPages.length === 0) continue;
        // 마스터 스프레드 페이지 인덱스 맵: masterPageId → 스프레드 내 순서(0=LEFT,1=RIGHT)
        var mspPageIdxMap = {};
        try {
            var mspPgs = mspread.pages.everyItem().getElements();
            for (var mspI = 0; mspI < mspPgs.length; mspI++) {
                mspPageIdxMap[mspPgs[mspI].id.toString()] = mspI;
            }
        } catch (e) {}
        var msItems = [];
        try { msItems = mspread.allPageItems; } catch (e) {}
        for (var mi = 0; mi < msItems.length; mi++) {
            var mtf = msItems[mi];
            try { if (mtf.constructor.name !== "TextFrame") continue; } catch (e) { continue; }
            // editable 분류만 인스턴스화 (background/renderable 은 PNG 처리)
            // SPEC-025 Phase 5 보강: auto page number TF(ACE 18, \u0018) 및 text variable TF(\uFEFF)도
            // hashiraEditable=true 일 때 인스턴스화 → 하시라/페이지번호 변환 지원.
            var cls = null;
            try { cls = classifyTextFrameCached(mtf); } catch (e) {}
            // hashiraEditable=true 로 "editable" 분류된 마스터 TF 중 textvar 패턴은 skip
            if (cls === "editable" && s25 && s25.hashiraEditable) {
                try {
                    var tvInstE = mtf.parentStory.textVariableInstances;
                    if (tvInstE && tvInstE.length > 0) {
                        var tvStripE = "";
                        try { tvStripE = mtf.parentStory.contents.replace(/\uFEFF/g, "").replace(/\uFFFC/g, "").replace(/\u0016/g, "").replace(/\u0018/g, "").replace(/[\s\r\n]/g, ""); } catch (e) {}
                        if (tvStripE.length === 0) continue; // textvar running header → skip
                    }
                } catch (e) {}
            }
            var hashiraSpecialType = null; // "pagenum" | "textvar"
            var hashiraTextVarResolved = null;
            if (cls !== "editable") {
                if (s25 && s25.hashiraEditable) {
                    // Case A: auto page number TF
                    try {
                        if (mtf.parentStory.contents.indexOf("\u0018") >= 0) {
                            hashiraSpecialType = "pagenum";
                        }
                    } catch (eHP) {}
                    // Case B: text variable TF (contents\uFEFF\u0016\uFFFC 이외 가시 텍스트 없음)
                    if (!hashiraSpecialType) {
                        try {
                            var tvInst = null;
                            try { tvInst = mtf.parentStory.textVariableInstances; } catch (eTVI) {}
                            if (tvInst && tvInst.length > 0) {
                                var tvStripRaw = "";
                                try { tvStripRaw = mtf.parentStory.contents.replace(/\uFEFF/g, "").replace(/\uFFFC/g, "").replace(/\u0016/g, "").replace(/\u0018/g, "").replace(/[\s\r\n]/g, ""); } catch (eTS) {}
                                if (tvStripRaw.length === 0) {
                                    var tvParts = [];
                                    for (var tvIdx = 0; tvIdx < tvInst.length; tvIdx++) {
                                        try { tvParts.push(String(tvInst[tvIdx].resultText) || ""); } catch (eTVI2) {}
                                    }
                                    hashiraTextVarResolved = tvParts.join("");
                                    if (hashiraTextVarResolved.length > 0) {
                                        hashiraSpecialType = "textvar";
                                    }
                                }
                            }
                        } catch (eHTF) {}
                    }
                }
                // textvar(running header)는 자동 페이지번호(pagenum)와 중복 → skip
                if (hashiraSpecialType === "textvar") continue;
                if (!hashiraSpecialType) continue;
            }
            var baseId = ""; try { baseId = mtf.id.toString(); } catch (e) { continue; }
            // SPEC-025: 마스터 TF 가 위치한 master page 의 스프레드 내 인덱스(0=LEFT,1=RIGHT) →
            // 동일 인덱스의 content page 에만 인스턴스 생성 (반대편 페이지로의 잘못된 복제 방지).
            // side.toString() 은 InDesign 버전마다 반환값이 달라 신뢰하지 않음 → 인덱스 비교로 대체.
            var mtfSpreadPageIdx = -1;
            try { mtfSpreadPageIdx = mspPageIdxMap[mtf.parentPage.id.toString()]; if (mtfSpreadPageIdx === undefined) mtfSpreadPageIdx = -1; } catch (e) {}
            // 구 side 필드는 fallback 용으로만 유지
            var mtfSide = "SINGLE";
            try { mtfSide = mtf.parentPage.side.toString(); } catch (e) {}
            var origStoryId = null;
            try { origStoryId = mtf.parentStory.id.toString(); } catch (e) {}
            // 마스터 TextFrame 의 공통 메타데이터
            var commonGb = null;
            try { var gb = mtf.geometricBounds; commonGb = [gb[0], gb[1], gb[2], gb[3]]; } catch (e) {}
            var commonRot = 0;       try { commonRot = mtf.absoluteRotationAngle; } catch (e) {}
            var commonCols = 1;      try { commonCols = mtf.textFramePreferences.textColumnCount; } catch (e) {}
            var commonGutter = 0;    try { commonGutter = mtf.textFramePreferences.textColumnGutter; } catch (e) {}
            var commonInset = null;  try { var ins = mtf.textFramePreferences.insetSpacing; commonInset = [ins[0], ins[1], ins[2], ins[3]]; } catch (e) {}
            var commonVAlign = null; try { commonVAlign = mtf.textFramePreferences.verticalJustification.toString(); } catch (e) {}
            var commonFill = null;   try { commonFill = mtf.fillColor.name; } catch (e) {}
            var commonFillTint;      try { commonFillTint = mtf.fillTint; } catch (e) {}
            var commonStroke = null; try { commonStroke = mtf.strokeColor.name; } catch (e) {}
            var commonStrokeW;       try { commonStrokeW = mtf.strokeWeight; } catch (e) {}
            var commonOpacity;       try { commonOpacity = mtf.transparencySettings.blendingSettings.opacity; } catch (e) {}
            var commonCorner;        try { commonCorner = mtf.topLeftCornerRadius; } catch (e) {}
            var commonNonprint;      try { commonNonprint = !!mtf.nonprinting; } catch (e) { commonNonprint = false; }
            var commonHidden;        try { commonHidden = isOnHiddenLayer(mtf); } catch (e) { commonHidden = false; }
            var commonVisibleText = "";
            try {
                commonVisibleText = mtf.contents;
                if (typeof commonVisibleText === "string") commonVisibleText = commonVisibleText.replace(/\r/g, "\n");
            } catch (e) {}
            // 단락 인덱스 / Y 오프셋 / paraTexts (원본 master story 안에서 frame 영역)
            var commonParaStart = -1, commonParaEnd = -1, commonParaY = null, commonParaTexts = null;
            try {
                var mfp = mtf.paragraphs.everyItem().getElements();
                if (mfp.length > 0) {
                    var msp = mtf.parentStory.paragraphs.everyItem().getElements();
                    var mfiIdx = mfp[0].index;
                    var mliIdx = mfp[mfp.length - 1].index;
                    for (var msk = 0; msk < msp.length; msk++) {
                        if (msp[msk].index === mfiIdx) commonParaStart = msk;
                        if (msp[msk].index === mliIdx) commonParaEnd = msk;
                    }
                }
            } catch (e) {}
            // 마스터 TF 의 실제 조판 줄 수 (override 없는 페이지에서 배치 결정용)
            var commonLineCount = 0;
            try { commonLineCount = mtf.lines.length; } catch (e) {}
            // hashira special TF: synthetic story 생성을 위한 스타일 정보 수집
            var hashiraParaStyleName = null, hashiraJustification = null, hashiraLeading = null;
            var hashiraFontFamily = null, hashiraFontStyle = null, hashiraFontSize = null;
            var hashiraFillColor = null, hashiraCharStyle = null;
            if (hashiraSpecialType) {
                try {
                    var hParas = mtf.parentStory.paragraphs;
                    if (hParas.length > 0) {
                        var hp0 = hParas[0];
                        try { hashiraParaStyleName = hp0.appliedParagraphStyle.name; } catch (eHPS) {}
                        try { hashiraJustification = hp0.justification.toString(); } catch (eHJ) {}
                        try { hashiraLeading = hp0.leading; } catch (eHL) {}
                    }
                } catch (eHPI) {}
                try {
                    var hTsr = mtf.parentStory.textStyleRanges;
                    if (hTsr.length > 0) {
                        var hr0 = hTsr[0];
                        try { var hf0 = hr0.appliedFont; hashiraFontFamily = hf0 ? (hf0.name || null) : null; } catch (eHFF) {}
                        try { hashiraFontStyle = hr0.fontStyle; } catch (eHFS) {}
                        try { hashiraFontSize = hr0.pointSize; } catch (eHFZ) {}
                        try { var hfc0 = hr0.fillColor; hashiraFillColor = hfc0 ? (hfc0.name || null) : null; } catch (eHFC) {}
                        try { var hcs0 = hr0.appliedCharacterStyle; hashiraCharStyle = hcs0 ? (hcs0.name || null) : null; } catch (eHCS) {}
                    }
                } catch (eHTSR) {}
            }
            // 적용 페이지마다 frame + story clone 추가
            for (var ap = 0; ap < appliedPages.length; ap++) {
                var pgEntry = appliedPages[ap];
                // side 매칭: 스프레드 내 페이지 인덱스 기준 (0=LEFT, 1=RIGHT)
                // 인덱스를 구할 수 없는 경우(-1) → side 문자열 폴백 → SINGLE이면 무조건 통과
                if (mtfSpreadPageIdx >= 0 && pgEntry.spreadPageIdx >= 0) {
                    if (mtfSpreadPageIdx !== pgEntry.spreadPageIdx) continue;
                } else if (mtfSide !== "SINGLE" && pgEntry.side !== "SINGLE" && mtfSide !== pgEntry.side) {
                    continue;
                }
                var docPgIdx = pgEntry.docIdx;
                // 이 페이지에 baseId 마스터 TF 의 override 가 있으면 clone 불필요
                if (pageOverrideMap[docPgIdx] && pageOverrideMap[docPgIdx][baseId]) continue;
                var cloneFrameId = baseId + "_pi" + docPgIdx;
                var cloneStoryId = origStoryId ? (origStoryId + "_pi" + docPgIdx) : null;
                var clone = {
                    id: cloneFrameId,
                    masterSourceId: baseId,
                    isMasterInstance: true,
                    pageIndex: docPgIdx,
                    storyId: cloneStoryId,
                    overflows: false,
                    lineCount: commonLineCount,
                    paragraphStart: commonParaStart,
                    paragraphEnd: commonParaEnd,
                    geometricBounds: commonGb,
                    columnCount: commonCols,
                    columnGutter: commonGutter,
                    insetSpacing: commonInset,
                    verticalJustification: commonVAlign,
                    rotationAngle: commonRot,
                    fillColor: commonFill,
                    strokeColor: commonStroke,
                    // SPEC-030: classification 제거 (Java 신 파이프라인 미사용)
                    isMasterPageItem: false,  // 본인이 master 라서 override 아님
                    nonprinting: commonNonprint,
                    onHiddenLayer: commonHidden,
                    isInline: false,
                    frameVisibleText: commonVisibleText
                };
                if (typeof commonFillTint !== "undefined") clone.fillTint = commonFillTint;
                if (typeof commonStrokeW !== "undefined") clone.strokeWeight = commonStrokeW;
                if (typeof commonOpacity !== "undefined") clone.opacity = commonOpacity;
                if (typeof commonCorner !== "undefined") clone.cornerRadius = commonCorner;
                // pageRelativeBounds = master 좌표 그대로 (master/doc page 같은 page-origin)
                try {
                    if (commonGb) {
                        var dpb = doc.pages[docPgIdx].bounds;
                        clone.pageRelativeBounds = [
                            commonGb[0] - dpb[0],
                            commonGb[1] - dpb[1],
                            commonGb[2] - dpb[0],
                            commonGb[3] - dpb[1]
                        ];
                    }
                } catch (e) {}
                // hashira special: per-page frameVisibleText 오버라이드
                if (hashiraSpecialType === "pagenum") {
                    var pageNumStr = "";
                    try { pageNumStr = String(doc.pages[docPgIdx].name); } catch (ePN) {}
                    clone.frameVisibleText = pageNumStr;
                } else if (hashiraSpecialType === "textvar") {
                    clone.frameVisibleText = hashiraTextVarResolved;
                }
                editableIds[cloneFrameId] = true;
                textFrames.push(clone);
                frameClones++;
                // 스토리도 clone (synthetic id) — Java StoryConverter 가 독립 처리.
                if (cloneStoryId) {
                    var stClone;
                    if (hashiraSpecialType) {
                        // hashira special: synthetic story — resolved text 직접 삽입
                        var hResolvedText = clone.frameVisibleText || "";
                        var hRun = { text: hResolvedText };
                        if (hashiraFontFamily) hRun.fontFamily = hashiraFontFamily;
                        if (hashiraFontStyle) hRun.fontStyle = hashiraFontStyle;
                        if (hashiraFontSize != null) hRun.fontSize = hashiraFontSize;
                        if (hashiraFillColor) hRun.fillColor = hashiraFillColor;
                        if (hashiraCharStyle) hRun.charStyle = hashiraCharStyle;
                        var hPara = {
                            styleName: hashiraParaStyleName || "[단락 스타일 없음]",
                            leading: (hashiraLeading != null) ? hashiraLeading : null,
                            autoLeading: null,
                            justification: hashiraJustification || null,
                            spaceBefore: null, spaceAfter: null,
                            firstLineIndent: null, leftIndent: null, rightIndent: null,
                            shadingOn: false, shadingColor: null, shadingTint: null,
                            tabStops: [], runs: [hRun]
                        };
                        stClone = {
                            id: cloneStoryId,
                            length: hResolvedText.length,
                            paragraphCount: 1,
                            paragraphs: [hPara],
                            tables: []
                        };
                        stories.push(stClone);
                        storyClones++;
                    } else if (origStoryId && storyById[origStoryId]) {
                        // regular editable master TF: shallow clone (paragraphs reference 공유)
                        var origSt = storyById[origStoryId];
                        stClone = {
                            id: cloneStoryId,
                            length: origSt.length,
                            paragraphCount: origSt.paragraphCount,
                            paragraphs: origSt.paragraphs,
                            tables: origSt.tables
                        };
                        stories.push(stClone);
                        storyClones++;
                    }
                }
            }
        }
    }
    if (frameClones > 0 || storyClones > 0) {
        $.writeln("[SPEC-025 Phase 5] cloned " + frameClones + " master frame instances, " + storyClones + " story copies");
    }

    // SPEC-025 Phase 5 보강 (2026-05-22): off-canvas master override 처리.
    // TF.masterPageItem 이 설정된 일반 spread item 이 parentPage=null (pasteboard 영역, y<0 등) 일 경우
    // Phase 2 가 pageIndex<0 으로 건너뜀 → 인스턴스화 안 됨.
    // 해결: 부모 spread 의 모든 doc page 에 clone (보통 1~2 페이지 spread).
    var offCanvasClones = 0;
    try {
        for (var sp = 0; sp < doc.spreads.length; sp++) {
            var spread = doc.spreads[sp];
            var spreadItems = [];
            try { spreadItems = spread.allPageItems; } catch (e) { continue; }
            // spread 가 가진 doc page indices (1 또는 2)
            var spreadPageIdxs = [];
            try {
                for (var pi = 0; pi < spread.pages.length; pi++) {
                    var dpi = spread.pages[pi].documentOffset;
                    var dpg = dpi + 1; // 1-based
                    if (dpg < startPage || dpg > endPage) continue;
                    spreadPageIdxs.push(dpi);
                }
            } catch (e) {}
            if (spreadPageIdxs.length === 0) continue;

            for (var ii = 0; ii < spreadItems.length; ii++) {
                var oTf = spreadItems[ii];
                try { if (oTf.constructor.name !== "TextFrame") continue; } catch (e) { continue; }
                // 1) parentPage = null (off-canvas)
                var pp = null;
                try { pp = oTf.parentPage; } catch (e) {}
                if (pp) continue;
                // 부모가 Spread (또는 Group on Spread) 인 경우만 — Cell/Table 안에 있는 TF 는 제외
                var __parentChain = oTf.parent;
                var __isOnSpread = false;
                var __hopOC = 0;
                while (__parentChain && __hopOC < 8) {
                    var __pcName = "";
                    try { __pcName = __parentChain.constructor.name; } catch (e) {}
                    if (__pcName === "Cell") { __isOnSpread = false; break; } // 테이블 셀 안 → skip
                    if (__pcName === "Spread") { __isOnSpread = true; break; }
                    if (__pcName === "MasterSpread") { __isOnSpread = false; break; } // 이미 master 케이스에서 처리
                    try { __parentChain = __parentChain.parent; } catch (e) { break; }
                    __hopOC++;
                }
                if (!__isOnSpread) continue;
                // 2) inline 제외 (인라인 객체)
                try { if (isInlineItem(oTf)) continue; } catch (e) {}
                // 진단 로그 (id + masterPageItem flag + text 앞 40자)
                try {
                    var __mpi = false;
                    try { __mpi = !!oTf.masterPageItem; } catch (e) {}
                    var __snippet = "";
                    try { __snippet = String(oTf.contents).substring(0, 40); } catch (e) {}
                    $.writeln("[SPEC-025 off-canvas] id=" + oTf.id + " masterPageItem=" + __mpi + " text=" + __snippet);
                } catch (e) {}
                // 3) 텍스트 콘텐츠가 있어야 의미 있음 (background 빈칸/장식 제외).
                //    classifyTextFrame 가 renderable 로 분류해도 master override 는 검색 가능 텍스트로 처리.
                var ocText = "";
                try { ocText = String(oTf.contents).replace(/[\s﻿\r\n￼]/g, ""); } catch (e) {}
                if (ocText.length === 0) continue;
                // hidden layer 는 제외 (안 보여야 함)
                try { if (isOnHiddenLayer(oTf)) continue; } catch (e) {}

                var ocBaseId = ""; try { ocBaseId = oTf.id.toString(); } catch (e) { continue; }
                var ocStoryId = null;
                try { ocStoryId = oTf.parentStory.id.toString(); } catch (e) {}

                // 공통 메타데이터 수집
                var ocGb = null;
                try { var gb2 = oTf.geometricBounds; ocGb = [gb2[0], gb2[1], gb2[2], gb2[3]]; } catch (e) {}
                var ocRot = 0;       try { ocRot = oTf.absoluteRotationAngle; } catch (e) {}
                var ocCols = 1;      try { ocCols = oTf.textFramePreferences.textColumnCount; } catch (e) {}
                var ocGutter = 0;    try { ocGutter = oTf.textFramePreferences.textColumnGutter; } catch (e) {}
                var ocInset = null;  try { var ins2 = oTf.textFramePreferences.insetSpacing; ocInset = [ins2[0], ins2[1], ins2[2], ins2[3]]; } catch (e) {}
                var ocVAlign = null; try { ocVAlign = oTf.textFramePreferences.verticalJustification.toString(); } catch (e) {}
                var ocFill = null;   try { ocFill = oTf.fillColor.name; } catch (e) {}
                var ocFillTint;      try { ocFillTint = oTf.fillTint; } catch (e) {}
                var ocStroke = null; try { ocStroke = oTf.strokeColor.name; } catch (e) {}
                var ocStrokeW;       try { ocStrokeW = oTf.strokeWeight; } catch (e) {}
                var ocOpacity;       try { ocOpacity = oTf.transparencySettings.blendingSettings.opacity; } catch (e) {}
                var ocCorner;        try { ocCorner = oTf.topLeftCornerRadius; } catch (e) {}
                var ocNonprint;      try { ocNonprint = !!oTf.nonprinting; } catch (e) { ocNonprint = false; }
                var ocHidden;        try { ocHidden = isOnHiddenLayer(oTf); } catch (e) { ocHidden = false; }
                var ocVisibleText = "";
                try {
                    ocVisibleText = oTf.contents;
                    if (typeof ocVisibleText === "string") ocVisibleText = ocVisibleText.replace(/\r/g, "\n");
                } catch (e) {}
                // paragraph index 범위
                var ocParaStart = -1, ocParaEnd = -1;
                try {
                    var ocfp = oTf.paragraphs.everyItem().getElements();
                    if (ocfp.length > 0) {
                        var ocsp = oTf.parentStory.paragraphs.everyItem().getElements();
                        var ocfiIdx = ocfp[0].index;
                        var ocliIdx = ocfp[ocfp.length - 1].index;
                        for (var ocsk = 0; ocsk < ocsp.length; ocsk++) {
                            if (ocsp[ocsk].index === ocfiIdx) ocParaStart = ocsk;
                            if (ocsp[ocsk].index === ocliIdx) ocParaEnd = ocsk;
                        }
                    }
                } catch (e) {}

                // off-canvas TF 의 실제 조판 줄 수
                var ocLineCount = 0;
                try { ocLineCount = oTf.lines.length; } catch (e) {}
                for (var spi = 0; spi < spreadPageIdxs.length; spi++) {
                    var docPgIdx2 = spreadPageIdxs[spi];
                    var ocCloneId = ocBaseId + "_oc" + docPgIdx2;
                    var ocCloneStoryId = ocStoryId ? (ocStoryId + "_oc" + docPgIdx2) : null;
                    var ocClone = {
                        id: ocCloneId,
                        masterSourceId: ocBaseId,
                        isMasterInstance: false,  // off-canvas override는 마스터 spread 아이템 아님
                        pageIndex: docPgIdx2,
                        storyId: ocCloneStoryId,
                        overflows: false,
                        lineCount: ocLineCount,
                        paragraphStart: ocParaStart,
                        paragraphEnd: ocParaEnd,
                        geometricBounds: ocGb,
                        columnCount: ocCols,
                        columnGutter: ocGutter,
                        insetSpacing: ocInset,
                        verticalJustification: ocVAlign,
                        rotationAngle: ocRot,
                        fillColor: ocFill,
                        strokeColor: ocStroke,
                        isMasterPageItem: false,
                        nonprinting: ocNonprint,
                        onHiddenLayer: ocHidden,
                        isInline: false,
                        frameVisibleText: ocVisibleText
                    };
                    if (typeof ocFillTint !== "undefined") ocClone.fillTint = ocFillTint;
                    if (typeof ocStrokeW !== "undefined") ocClone.strokeWeight = ocStrokeW;
                    if (typeof ocOpacity !== "undefined") ocClone.opacity = ocOpacity;
                    if (typeof ocCorner !== "undefined") ocClone.cornerRadius = ocCorner;
                    try {
                        if (ocGb) {
                            var dpb2 = doc.pages[docPgIdx2].bounds;
                            ocClone.pageRelativeBounds = [
                                ocGb[0] - dpb2[0],
                                ocGb[1] - dpb2[1],
                                ocGb[2] - dpb2[0],
                                ocGb[3] - dpb2[1]
                            ];
                        }
                    } catch (e) {}
                    editableIds[ocCloneId] = true;
                    textFrames.push(ocClone);
                    offCanvasClones++;
                    // story clone
                    if (ocStoryId && storyById[ocStoryId] && ocCloneStoryId) {
                        var ocOrigSt = storyById[ocStoryId];
                        var ocStClone = {
                            id: ocCloneStoryId,
                            length: ocOrigSt.length,
                            paragraphCount: ocOrigSt.paragraphCount,
                            paragraphs: ocOrigSt.paragraphs,
                            tables: ocOrigSt.tables
                        };
                        stories.push(ocStClone);
                    }
                }
            }
        }
    } catch (eOC) { $.writeln("[SPEC-025 Phase 5 off-canvas error] " + eOC); }
    if (offCanvasClones > 0) {
        $.writeln("[SPEC-025 Phase 5] off-canvas overrides: " + offCanvasClones + " frame clones");
    }
}

// --- 페이지 수집 ---

function collectPages(doc, startPage, endPage, skipRenderPagesMap) {
    if (!skipRenderPagesMap) skipRenderPagesMap = {};
    var pages = [];
    for (var i = 0; i < doc.pages.length; i++) {
        var pgIdx = i + 1; // 1-based
        if (pgIdx < startPage || pgIdx > endPage) continue;
        if (skipRenderPagesMap[pgIdx]) continue; // SPEC-030: 증분 추출 스킵
        var pg = doc.pages[i];
        var data = {
            index: i,
            name: pg.name
        };
        try { data.bounds = arrCopy(pg.bounds); } catch (e) {}
        try {
            data.marginPreferences = {
                top: pg.marginPreferences.top,
                bottom: pg.marginPreferences.bottom,
                left: pg.marginPreferences.left,
                right: pg.marginPreferences.right
            };
        } catch (e) {}
        pages.push(data);
    }
    return pages;
}

// --- 페이지 아이템 수집 (벡터/이미지/그룹 속성 평탄화) ---

function collectPageItems(doc, startPage, endPage, skipRenderPagesMap, cachedAllItems) {
    if (!skipRenderPagesMap) skipRenderPagesMap = {};
    var items = [];
    var allItems = cachedAllItems || doc.allPageItems;
    for (var i = 0; i < allItems.length; i++) {
        var pi = allItems[i];

        // 페이지 범위 필터
        var piPageIdx = -1;
        try {
            var parentPage = pi.parentPage;
            if (parentPage) piPageIdx = parentPage.documentOffset;
        } catch (e) {}
        // piPageIdx는 0-based, startPage/endPage는 1-based
        if (piPageIdx >= 0) {
            var pgIdx1 = piPageIdx + 1;
            if (pgIdx1 < startPage || pgIdx1 > endPage) continue;
            if (skipRenderPagesMap[pgIdx1]) continue; // SPEC-030: 증분 추출 스킵
        }

        var data = {
            id: pi.id.toString(),
            type: pi.constructor.name,
            name: null,
            parentId: null,
            pageIndex: piPageIdx
        };

        // 이름
        try { data.name = pi.name; } catch (e) {}

        // 부모 관계 (Spread/Page 직속은 null)
        try {
            if (pi.parent && pi.parent.constructor.name !== "Spread"
                && pi.parent.constructor.name !== "Page"
                && pi.parent.constructor.name !== "MasterSpread") {
                data.parentId = pi.parent.id.toString();
            }
        } catch (e) {}

        // 기하 — InDesign이 모든 변환 적용한 절대 좌표 (pt)
        try { data.geometricBounds = arrCopy(pi.geometricBounds); } catch (e) {}
        // SPEC-030: visibleBounds 제거 — Java 측에서 사용 안 함 (geometricBounds 만 사용)

        // 절대 변환 — SPEC-030: rotationAngle 만 사용됨, shear/scale/flip 은 미사용 제거
        try { data.absoluteRotationAngle = pi.absoluteRotationAngle; } catch (e) {}

        // 채우기
        try {
            if (pi.fillColor && pi.fillColor.name !== "None") {
                data.fillColorName = pi.fillColor.name;
                data.fillTint = pi.fillTint;
            }
        } catch (e) {}

        // 스트로크
        try {
            if (pi.strokeColor && pi.strokeColor.name !== "None") {
                data.strokeColorName = pi.strokeColor.name;
                data.strokeTint = pi.strokeTint;
                data.strokeWeight = pi.strokeWeight;
                data.strokeAlignment = pi.strokeAlignment.toString();
            }
        } catch (e) {}

        // 투명도
        try { data.opacity = pi.transparencySettings.blendingSettings.opacity; } catch (e) {}

        // SPEC-030: 그라디언트 페더 / 드롭 섀도우 제거 — 신 파이프라인에서 pageItem 레벨 사용 안 함.
        //   (TextFrameBlock 의 dropShadow boolean 은 별도로 transparencySettings 에서 직접 추출)

        // 코너 반경
        try {
            if (pi.cornerRadius > 0) {
                data.cornerRadius = pi.cornerRadius;
            }
        } catch (e) {}

        // NEW: z-order (allPageItems 순서 = 시각적 스태킹)
        data.zOrder = i;

        // NEW: 인라인 여부
        data.isInline = isInlineItem(pi);

        // NEW: Group 자식 ID
        if (pi.constructor.name === "Group") {
            try {
                var grpChildren = pi.allPageItems;
                var childIds = [];
                for (var gc = 0; gc < grpChildren.length; gc++) {
                    childIds.push(grpChildren[gc].id);
                }
                data.childIds = childIds;
            } catch (e) {}
            // SPEC-030: clipContent 제거 — 신 파이프라인 미사용
        }

        // SPEC-030: pageRelativeBounds 제거 (pageItems 레벨) — Java 측 신 파이프라인 미사용.
        // TextFrame 의 pageRelativeBounds 는 별도 emission 위치(fData)에서 계속 유지.

        items.push(data);
    }
    return items;
}

// =============================================================================
// SECTION 7: UTILITIES & ENTRY
// arrCopy / writeJson / writeProgress / writeDone / 실행 트리거
// =============================================================================

function arrCopy(a) {
    return [a[0], a[1], a[2], a[3]];
}

// --- 유틸리티 ---

function writeJson(path, obj) {
    // SPEC-030 A.6: indent 제거 (`null, 2` → 무인자).
    // 출력 크기 -38% (4.9MB → 3.0MB) — Java Gson 파싱 속도/메모리 사용량/디스크 캐시 모두 개선.
    // (참고: 섹션별 분리 write / $.gc() 삽입 모두 시도했으나 단일 stringify 대비 유의미한 차이 없음)
    var f = File(path);
    f.encoding = "UTF-8";
    f.open("w");
    f.write(JSON.stringify(obj));
    f.close();
}

// SPEC-030 B.4: desc 파라미터 추가 (아이템 ID 등 세부 정보)
function writeProgress(outputDir, step, current, total, desc) {
    var obj = { step: step, current: current, total: total };
    if (desc) obj.desc = desc;
    var f = File(outputDir + "/.progress");
    f.encoding = "UTF-8";
    f.open("w");
    f.write(JSON.stringify(obj));
    f.close();
}

function writeDone(outputDir, status, message) {
    var obj = { status: status };
    if (message) {
        obj.message = message;
    }
    var f = File(outputDir + "/.done");
    f.encoding = "UTF-8";
    f.open("w");
    f.write(JSON.stringify(obj));
    f.close();
}

// --- 실행 ---
main(arguments);
