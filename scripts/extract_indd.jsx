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
var EXTRACT_SCRIPT_VERSION = "6";

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
    var _JSON_MAX_DEPTH = 30;

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
            if (c === '"') result += '\\"';
            else if (c === '\\') result += '\\\\';
            else if (c === '\n') result += '\\n';
            else if (c === '\r') result += '\\r';
            else if (c === '\t') result += '\\t';
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
var _ctfCache = null;   // itemId → "editable"|"renderable"|"background"|null
var _badgeCache = null; // itemId → true|false

// --- 전역 설정 ---
var CONFIG = null;

// CONFIG.rendering.textFrame.spec025 에 대한 null-safe 접근자.
// 파일 전역에서 반복되는 4단계 null 체크를 단일 호출로 대체한다.
function _spec025() {
    return CONFIG && CONFIG.rendering && CONFIG.rendering.textFrame
           && CONFIG.rendering.textFrame.spec025;
}

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
            // - rotationEditable: 회전 텍스트 (조건 8.5 / isRenderableTextFrame) → editable + HWPX rotation
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
            badge: { enabled: true, maxSize: 50, maxTextLength: 20, requireShape: true, allowImage: false, badgeDpi: 600, maxAspectRatio: 4.5, nestedEnabled: true, nestedMaxTextLength: 6, decorationMergeEnabled: true, decorationMergeMinOverlap: 0.5, decorationMergeAdjacency: 20 },
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
            _mergeKeys(defaults.rendering.badge, r.badge,
                ["enabled", "maxSize", "maxTextLength", "requireShape", "allowImage", "badgeDpi",
                 "maxAspectRatio", "nestedEnabled", "nestedMaxTextLength",
                 "decorationMergeEnabled", "decorationMergeMinOverlap", "decorationMergeAdjacency"]);
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

/**
 * 문자의 채움 색상이 검정(또는 검정 근사)인지 판별한다.
 * @param character InDesign Character 객체
 * @param blackThreshold 검정 판정 임계값 (0.90 = 90% 이상이면 검정)
 * @return true이면 검정색
 */
function isBlackColor(character, blackThreshold) {
    if (!blackThreshold) blackThreshold = 0.90;
    try {
        var color = character.fillColor;
        if (!color) return true; // 색상 없으면 검정 취급

        // 스와치 이름 검사
        var name = color.name;
        if (name === "Black" || name === "[Black]") return true;
        if (name === "Registration") return true;

        // 색상값 검사
        var space = color.space;
        if (space === ColorSpace.CMYK) {
            var cv = color.colorValue;
            // K >= blackThreshold*100% AND C+M+Y < 10%
            if (cv[3] >= blackThreshold * 100 && (cv[0] + cv[1] + cv[2]) < 10) return true;
        } else if (space === ColorSpace.RGB) {
            var cv = color.colorValue;
            // R, G, B 모두 25 이하이면 검정
            var darkThreshold = (1.0 - blackThreshold) * 255;
            if (cv[0] <= darkThreshold && cv[1] <= darkThreshold && cv[2] <= darkThreshold) return true;
        }

        // 틴트 검사: 검정 스와치에 높은 틴트
        var tint = 100;
        try { tint = character.fillTint; } catch (e2) {}
        if ((name === "Black" || name === "[Black]") && tint >= blackThreshold * 100) return true;

        return false;
    } catch (e) {
        return true; // 오류 시 검정 취급 (렌더링 안 함)
    }
}

// =============================================================================
// SECTION 2: PAGE UTILITIES
// 페이지 해시 계산 / 아이템-페이지 맵 / 배지 배치 export
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

// SPEC-030 B.1: 한 페이지의 단순 배지(데코/editable TF 없음)를 임시 그룹으로 묶어
// 배치 export하고 crop 매니페스트 배열을 반환한다.
// param: simpleBadges [{grp, grpDomId, grpPage, childIds, childTextFrameIds}]
// return: [{rf: renderedFrames 엔트리, crop: {src,dst,x,y,w,h}}] — 실패 시 []
function exportPageBadgesBatched(doc, page, simpleBadges, renderDir, dpi) {
    if (simpleBadges.length < 2) return [];
    var results = [];
    var pgIdx0 = page.documentOffset; // 0-based
    var batchFileName = "badge_batch_p" + (pgIdx0 + 1) + ".png";
    var batchFile = File(renderDir + "/" + batchFileName);
    var dups = [];
    try {
        for (var i = 0; i < simpleBadges.length; i++) {
            dups.push(simpleBadges[i].grp.duplicate());
        }
        // PNG 배경만 추출: 각 복제본의 TextFrame 자식 숨기기 (내용 있는 TF만 — 빈 TF는 시각적 컨테이너)
        for (var hi = 0; hi < dups.length; hi++) {
            try {
                var dupItems = dups[hi].allPageItems;
                for (var hj = 0; hj < dupItems.length; hj++) {
                    try {
                        if (dupItems[hj].constructor.name !== "TextFrame" || !dupItems[hj].visible) continue;
                        var _bTfCont = "";
                        try { _bTfCont = (dupItems[hj].contents + "").replace(/[\s\r\n﻿￼]/g, ""); } catch (e) {}
                        if (_bTfCont.length > 0) dupItems[hj].visible = false;
                    } catch (eHj) {}
                }
            } catch (eHi) {}
        }
        var tempGroup = doc.groups.add(dups);
        try {
            tempGroup.exportFile(ExportFormat.PNG_FORMAT, batchFile);
        } finally {
            try { tempGroup.remove(); } catch (er) {}
        }

        // union bounds of all simple badges (pt)
        var uVB = null;
        for (var j = 0; j < simpleBadges.length; j++) {
            try {
                var bvb = simpleBadges[j].grp.visibleBounds;
                if (!uVB) { uVB = [bvb[0], bvb[1], bvb[2], bvb[3]]; }
                else {
                    uVB[0] = Math.min(uVB[0], bvb[0]);
                    uVB[1] = Math.min(uVB[1], bvb[1]);
                    uVB[2] = Math.max(uVB[2], bvb[2]);
                    uVB[3] = Math.max(uVB[3], bvb[3]);
                }
            } catch (e) {}
        }
        if (!uVB) return [];

        // visibleBounds는 문서 측정 단위로 반환됨. 단위별로 px 변환 계수 계산.
        var _hUnit = doc.viewPreferences.horizontalMeasurementUnits;
        var pxPerPt;
        if (_hUnit == MeasurementUnits.MILLIMETERS)   { pxPerPt = dpi / 25.4; }
        else if (_hUnit == MeasurementUnits.INCHES)    { pxPerPt = dpi; }
        else                                           { pxPerPt = dpi / 72.0; }
        var pageBounds = page.bounds;

        for (var k = 0; k < simpleBadges.length; k++) {
            var sb = simpleBadges[k];
            var ivb = null;
            try { ivb = sb.grp.visibleBounds; } catch (e) {}
            if (!ivb) continue;

            var cX = Math.round((ivb[1] - uVB[1]) * pxPerPt);
            var cY = Math.round((ivb[0] - uVB[0]) * pxPerPt);
            var cW = Math.max(1, Math.round((ivb[3] - ivb[1]) * pxPerPt));
            var cH = Math.max(1, Math.round((ivb[2] - ivb[0]) * pxPerPt));

            var indivFileName = "badge_" + sb.grpDomId + ".png";
            var relBounds = [
                ivb[0] - pageBounds[0], ivb[1] - pageBounds[1],
                ivb[2] - pageBounds[0], ivb[3] - pageBounds[1]
            ];

            results.push({
                rf: {
                    id: sb.grpDomId,
                    file: "rendered_frames/" + indivFileName,
                    bounds: relBounds,
                    pageIndex: pgIdx0,
                    type: "badge_group",
                    childIds: sb.childIds,
                    childTextFrameIds: sb.childTextFrameIds,
                    textHiddenBeforeExport: true
                },
                crop: {
                    src: "rendered_frames/" + batchFileName,
                    dst: "rendered_frames/" + indivFileName,
                    x: cX, y: cY, w: cW, h: cH
                }
            });
        }
    } catch (e) {
        for (var dx = 0; dx < dups.length; dx++) {
            try { dups[dx].remove(); } catch (er) {}
        }
        return [];
    }
    return results;
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
        var _s25 = _spec025();
        cfgLog.writeln("spec025=" + (_s25 ? ("masterPageEditable=" + _s25.masterPageEditable
            + " hashiraEditable=" + _s25.hashiraEditable
            + " rotationEditable=" + _s25.rotationEditable
            + " nonprintingEditable=" + _s25.nonprintingEditable) : "MISSING"));
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

    // 03c. allItems 전체 분류 캐시 — classifyTextFrame/isBadgeGroup의 중복 DOM 호출 제거
    _ctfCache = {}; _badgeCache = {};
    for (var _pci = 0; _pci < allItems.length; _pci++) {
        try {
            var _pcIt = allItems[_pci];
            var _pcCn = _pcIt.constructor.name;
            if (_pcCn === "TextFrame") _ctfCache[_pcIt.id] = classifyTextFrame(_pcIt);
            if (_pcCn === "Group")     _badgeCache[_pcIt.id] = isBadgeGroup(_pcIt);
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

    // 2.13. 배지 그룹 + 장식 텍스트 프레임 렌더링
    _marker(ctx.outputDir, "05_badgeRendering");
    var rtfResult = exportRenderedTextFrames(doc, ctx.outputDir, ctx.startPage, ctx.endPage, allItems, editableFrameIds, ctx.skipRenderPagesMap);
    var renderedFrames = rtfResult.frames;
    var badgeChildIds  = rtfResult.badgeChildIds;
    for (var ri = 0; ri < renderedFrames.length; ri++) renderedFloatingItems.push(renderedFrames[ri]);
    try { $.gc(); } catch (e) {}

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
    var decoResult  = exportDecorationGroups(doc, ctx.outputDir, ctx.startPage, ctx.endPage, badgeChildIds, allItems, imgRenderedIds);
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
    var renderedVectorFrames = exportVectorShapeFrames(doc, ctx.outputDir, ctx.startPage, ctx.endPage, badgeChildIds, decoChildIds, allItems);
    addItemType(renderedVectorFrames, "page_object");
    for (var vi = 0; vi < renderedVectorFrames.length; vi++) renderedFloatingItems.push(renderedVectorFrames[vi]);
    try { $.gc(); } catch (e) {}

    // 2.18. 마스터 스프레드 그래픽 렌더링
    _marker(ctx.outputDir, "09b_masterGraphics");
    var renderedMasterGraphics = exportMasterPageGraphics(doc, ctx.outputDir, ctx.startPage, ctx.endPage);
    addItemType(renderedMasterGraphics, "page_object");
    for (var mgi = 0; mgi < renderedMasterGraphics.length; mgi++) renderedFloatingItems.push(renderedMasterGraphics[mgi]);
    try { $.gc(); } catch (e) {}

    // 2.19. 타원형 TextFrame 윤곽선 렌더링 (비직사각형 stroke TF)
    _marker(ctx.outputDir, "09c_ovalTFShapes");
    var ovalTFFrames = exportOvalShapeTextFrames(doc, ctx.outputDir, ctx.startPage, ctx.endPage, badgeChildIds, decoChildIds, editableFrameIds, allItems);
    addItemType(ovalTFFrames, "page_object");
    for (var otfi = 0; otfi < ovalTFFrames.length; otfi++) renderedFloatingItems.push(ovalTFFrames[otfi]);
    try { $.gc(); } catch (e) {}

    // 추출 통계 기록
    try {
        var _statsEndMs = (new Date()).getTime();
        var _statsStartMs = _phaseTimingState ? _phaseTimingState.startTime : _statsEndMs;
        var _inlineCount = 0;
        for (var _si2 = 0; _si2 < bgResult.items.length; _si2++) {
            if (bgResult.items[_si2].type === "inline_object") _inlineCount++;
        }
        var _statsObj = {
            elapsed_ms: _statsEndMs - _statsStartMs,
            total_page_count: ctx.pageCount,
            page_count: ctx.rangePageCount,
            badge_count: renderedFrames.length,
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
    var resolved = collectResolved(doc, ctx.outputDir, ctx.rangePageCount, ctx.startPage, ctx.endPage, editableFrameIds, ctx.skipRenderPagesMap);
    resolved.renderedTextFrames    = renderedFrames;
    resolved.renderedPdfFrames     = [];
    resolved.renderedGraphicFrames = renderedGraphicFrames;
    resolved.renderedImageFrames   = renderedImageFrames;
    resolved.renderedFloatingItems = renderedFloatingItems;

    // 편집 가능 TextFrame ID 목록 (SPEC-025: synthetic master instance ID는 문자열로 유지)
    var editableIdList = [];
    for (var eid in editableFrameIds) {
        if (/^[0-9]+$/.test(eid)) editableIdList.push(parseInt(eid, 10));
        else editableIdList.push(eid);
    }
    resolved.editableTextFrameIds = editableIdList;

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

/**
 * editable TextFrame 중 가시적 stroke가 있는 것의 윤곽선만 PNG로 내보낸다.
 * exportPageBackgrounds가 editable TF를 숨기므로 stroke가 배경 PNG에 누락됨.
 * 복제본에서 텍스트를 비우고 윤곽선만 렌더링해 page_object로 추가.
 */
function exportOvalShapeTextFrames(doc, outputDir, startPage, endPage, badgeChildIds, decoChildIds, editableIds, allItems) {
    var renderDir = Folder(outputDir + "/rendered_frames");
    renderDir.create();

    var results = [];

    for (var i = 0; i < allItems.length; i++) {
        var item = allItems[i];
        if (item.constructor.name !== "TextFrame") continue;

        var domId = item.id;
        if (isOnHiddenLayer(item)) continue;
        if (badgeChildIds && badgeChildIds[domId]) continue;
        if (decoChildIds && decoChildIds[domId]) continue;
        // editable TF이거나 빈 TF(텍스트 없음)인 경우 처리.
        // 내용 있는 비-편집 TF는 exportRenderedTextFrames에서 이미 처리됨.
        var _tfIsEditable = editableIds && editableIds[domId];
        if (!_tfIsEditable) {
            var _tfHasContent = false;
            try { _tfHasContent = item.contents.replace(/[\s﻿]/g, "").length > 0; } catch (e) {}
            if (_tfHasContent) continue;
        }

        // 가시적 stroke 확인
        var hasStroke = false;
        try {
            var sc = item.strokeColor;
            var sw = item.strokeWeight || 0;
            hasStroke = sc && sc.name !== "None" && sc.name !== "[None]" && sw > 0;
        } catch (e) {}
        if (!hasStroke) continue;

        // 비직사각형(타원/곡선) 경로 우선 확인, 실패하면 크기 조건으로 폴백
        // (paths API가 TextFrame에서 접근 불가한 InDesign 버전 대응)
        var isNonRect = hasNonRectangularPath(item);
        if (!isNonRect) {
            // 경로 판별 실패 또는 직사각형 → 크기가 충분히 크면 포함
            // (배경 PNG에서 숨겨진 stroke를 복원하기 위해 대형 bordered TF도 대상)
            try {
                var gbb = item.geometricBounds;
                var tfW = Math.abs(gbb[3] - gbb[1]);
                var tfH = Math.abs(gbb[2] - gbb[0]);
                if (tfW < 50 || tfH < 50) continue;  // 50pt 미만 소형은 스킵
            } catch (e) { continue; }
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

        // 복제본에서 텍스트를 비우고 윤곽선만 PNG 내보내기
        try {
            var fileName = "oval_tf_" + domId + ".png";
            var outFile = File(renderDir + "/" + fileName);

            var dup = item.duplicate();
            try {
                dup.contents = "";
                dup.exportFile(ExportFormat.PNG_FORMAT, outFile);
            } finally {
                try { dup.remove(); } catch (e2) {}
            }

            var bounds = null;
            try { bounds = arrCopy(item.visibleBounds); } catch (e) {}
            if (!bounds) try { bounds = arrCopy(item.geometricBounds); } catch (e) {}
            if (bounds) _toPageRelativeBounds(bounds, parentPage);

            results.push({
                id: domId,
                file: "rendered_frames/" + fileName,
                bounds: bounds,
                pageIndex: parentPage.documentOffset
            });
        } catch (e) {}
    }

    return results;
}

/**
 * 아이템 또는 부모 체인에 숨김 레이어가 있는지 검사한다.
 */
function isOnHiddenLayer(item) {
    try {
        var cur = item;
        while (cur) {
            try {
                if (cur.itemLayer && !cur.itemLayer.visible) return true;
            } catch (e) {}
            try { cur = cur.parent; } catch (e) { break; }
            if (!cur || cur.constructor.name === "Spread"
                || cur.constructor.name === "Page"
                || cur.constructor.name === "Document") break;
        }
    } catch (e) {}
    return false;
}

/**
 * 벡터 도형(>=1) + 비어있지 않은 텍스트 TF(>=1)를 포함하는 그룹이면 true.
 * 글자 수, 크기, 종횡비 조건 없음.
 * 모든 텍스트는 TF로 변환하는 원칙에 따라 그룹 배경(도형)은 PNG, 텍스트는 별도 TF로 처리.
 */
function isBadgeGroup(group) {
    // 크기가 80mm 이상인 그룹은 배지가 아님 (스프레드 전체에 걸치는 장식 그룹 등)
    try {
        var _gb = group.geometricBounds;
        var _gw = _gb[3] - _gb[1];
        var _gh = _gb[2] - _gb[0];
        if (_gw > 80 || _gh > 80) return false;
    } catch (e) {}

    var hasShape = false;
    var hasText = false;

    try {
        var items = group.allPageItems;
        for (var i = 0; i < items.length; i++) {
            var item = items[i];
            var cName = item.constructor.name;
            if (cName === "Rectangle" || cName === "Polygon"
                || cName === "Oval" || cName === "GraphicLine") {
                var hasPlaced = false;
                try { hasPlaced = (item.images && item.images.length > 0)
                    || (item.epss && item.epss.length > 0)
                    || (item.pdfs && item.pdfs.length > 0); } catch (e) {}
                if (!hasPlaced) hasShape = true;
            } else if (cName === "TextFrame") {
                try {
                    if (item.contents.replace(/[\s\uFEFF]/g, "").length > 0) hasText = true;
                } catch (e) {}
            }
        }
    } catch (e) {
        return false;
    }

    return hasShape && hasText;
}

/**
 * 그룹 전체는 뱃지 조건에 안 맞지만 내부에 뱃지 패턴(도형 + 짧은 숫자 TF)이
 * 있는 경우를 찾는다. 예: 질문 그룹 = {Oval + "2" TF + 긴 질문 TF}.
 *
 * 감지되면 (badgeShape, badgeTextFrame) 페어를 반환. 없으면 null.
 * 렌더링 단계에서 페어 외 형제를 임시 숨긴 뒤 부모 그룹을 exportFile 한다.
 */
function findNestedBadgePattern(group) {
    var cfg = CONFIG.rendering.badge;
    if (!cfg.enabled || !cfg.nestedEnabled) return null;

    var maxLen = (cfg.nestedMaxTextLength !== undefined) ? cfg.nestedMaxTextLength : 3;
    var aspectLimit = (cfg.maxAspectRatio !== undefined) ? cfg.maxAspectRatio : 4.5;

    // 직속 자식만 검사 (allPageItems는 재귀 → 오판 위험)
    var shapes = [];
    var textFrames = [];
    try {
        var rects = group.rectangles;
        for (var i = 0; i < rects.length; i++) {
            try { if (rects[i].parent === group) shapes.push(rects[i]); } catch (e) {}
        }
        var ovals = group.ovals;
        for (var i = 0; i < ovals.length; i++) {
            try { if (ovals[i].parent === group) shapes.push(ovals[i]); } catch (e) {}
        }
        var polys = group.polygons;
        for (var i = 0; i < polys.length; i++) {
            try { if (polys[i].parent === group) shapes.push(polys[i]); } catch (e) {}
        }
        var tfs = group.textFrames;
        for (var i = 0; i < tfs.length; i++) {
            try { if (tfs[i].parent === group) textFrames.push(tfs[i]); } catch (e) {}
        }
    } catch (e) {
        return null;
    }

    if (shapes.length === 0 || textFrames.length === 0) return null;

    var scale = 72 / 25.4; // mm → pt
    try {
        var pageW = group.parentPage ? (group.parentPage.bounds[3] - group.parentPage.bounds[1]) : 0;
        if (pageW > 0 && pageW < 30) scale = 72;
    } catch (e) {}

    // 각 도형에 대해 내부에 완전 포함된 짧은 TF를 찾는다
    for (var si = 0; si < shapes.length; si++) {
        var shape = shapes[si];
        var sb = null;
        try { sb = shape.geometricBounds; } catch (e) { continue; } // [t, l, b, r]
        if (!sb) continue;

        // 이미지/EPS/PDF 포함한 도형은 스킵 (컨텐츠 이미지)
        try {
            if (shape.images && shape.images.length > 0) continue;
            if (shape.epss && shape.epss.length > 0) continue;
            if (shape.pdfs && shape.pdfs.length > 0) continue;
        } catch (e) {}

        var sh = sb[2] - sb[0], sw = sb[3] - sb[1];
        var minD = Math.min(sw, sh) * scale;
        var maxD = Math.max(sw, sh) * scale;
        if (minD <= 0) continue;
        if (minD > cfg.maxSize) continue;
        if (maxD / minD > aspectLimit) continue;

        // 내부에 완전 포함된 짧은 TF 찾기
        for (var ti = 0; ti < textFrames.length; ti++) {
            var tf = textFrames[ti];
            var tb = null;
            try { tb = tf.geometricBounds; } catch (e) { continue; }
            if (!tb) continue;
            var ovT = Math.max(sb[0], tb[0]);
            var ovL = Math.max(sb[1], tb[1]);
            var ovB = Math.min(sb[2], tb[2]);
            var ovR = Math.min(sb[3], tb[3]);
            if (ovB <= ovT || ovR <= ovL) continue;
            var ovA = (ovB - ovT) * (ovR - ovL);
            var tfA = (tb[2] - tb[0]) * (tb[3] - tb[1]);
            if (tfA <= 0) continue;
            if (ovA / tfA < 0.9) continue;  // TF 가 도형 안에 90% 이상 포함

            var txt = "";
            try { txt = tf.contents.replace(/\s/g, ""); } catch (e) { continue; }
            if (txt.length === 0 || txt.length > maxLen) continue;

            return { shape: shape, textFrame: tf };
        }
    }
    return null;
}

/**
 * 매칭된 뱃지 그룹과 시각적으로 한 덩어리를 이루지만 구조적으로는 별개인 외곽선 데코
 * (주로 폰트가 폴리곤으로 변환된 라벨 — 예: "Step 1") 를 찾는다 (SPEC-022).
 *
 * 조건:
 *  - 직속 부모가 Spread 또는 Page 인 최상위 아이템
 *  - Polygon 또는 Polygon만 들어있는 Group
 *  - visibleBounds 가 뱃지 visibleBounds 와 50% 이상 겹치거나, 위/아래로 5pt 이내 인접
 *  - 짧은 변(minDim) ≤ 뱃지 maxDim × 1.5 (너무 큰 데코는 제외)
 *
 * 반환: 합쳐야 할 PageItem 배열 (없으면 빈 배열).
 */
function findOverlappingDecorations(badgeGroup, allItems) {
    var cfg = CONFIG.rendering.badge;
    if (!cfg.decorationMergeEnabled) return [];

    var minOverlap = (cfg.decorationMergeMinOverlap !== undefined) ? cfg.decorationMergeMinOverlap : 0.5;
    var adjacency = (cfg.decorationMergeAdjacency !== undefined) ? cfg.decorationMergeAdjacency : 20;

    var bb = null;
    try { bb = badgeGroup.visibleBounds; } catch (e) { return []; }
    if (!bb) return [];
    var bT = bb[0], bL = bb[1], bB = bb[2], bR = bb[3];
    var bH = bB - bT, bW = bR - bL;
    if (bH <= 0 || bW <= 0) return [];
    var bMaxDim = Math.max(bH, bW);

    // adjacency 는 pt 단위 — 문서 단위가 mm 인 경우 pt→mm 변환
    var adjUnit = adjacency * 25.4 / 72;

    // 뱃지 자신과 자식 ID 집합 (재포함 방지)
    var badgeDescendants = {};
    try {
        var bd = badgeGroup.allPageItems;
        for (var i = 0; i < bd.length; i++) badgeDescendants[bd[i].id] = true;
    } catch (e) {}
    badgeDescendants[badgeGroup.id] = true;

    // 같은 page 만 고려 (다른 페이지의 우연한 좌표 겹침 방지).
    // parentPage 는 인라인이거나 spread-spanning 인 경우 null 일 수 있음.
    var badgePageId = null;
    try {
        if (badgeGroup.parentPage && badgeGroup.parentPage.isValid) {
            badgePageId = badgeGroup.parentPage.id;
        }
    } catch (e) {}

    // 뱃지의 조상 체인(부모, 조부모 …)을 모두 허용 부모로 등록 →
    // 뱃지가 다중 컴포지트 그룹 안에 있을 때(부모/조부모 등) 형제·사촌 데코까지 검출.
    var badgeAncestorIds = {};
    try {
        var bp = badgeGroup.parent;
        var hops = 0;
        while (bp && hops < 6) {
            badgeAncestorIds[bp.id] = true;
            var bpName = bp.constructor.name;
            if (bpName === "Spread" || bpName === "Page" || bpName === "MasterSpread") break;
            try { bp = bp.parent; } catch (e) { break; }
            hops++;
        }
    } catch (e) {}

    var matches = [];

    for (var ai = 0; ai < allItems.length; ai++) {
        var item = allItems[ai];
        if (!item || badgeDescendants[item.id]) continue;
        var cn;
        try { cn = item.constructor.name; } catch (e) { continue; }
        // 빠른 타입 필터: Polygon 또는 Group 만 통과 (DOM 깊은 검사 전에 컷)
        if (cn !== "Polygon" && cn !== "Group") continue;
        if (isOnHiddenLayer(item)) continue;
        if (cn === "Group") {
            // 직속/재귀 자식이 모두 Polygon 인지 검사 — 큰 그룹은 스킵 (성능/정확성)
            var onlyPoly = true;
            try {
                var gd = item.allPageItems;
                if (gd.length === 0 || gd.length > 50) onlyPoly = false;
                for (var gi = 0; onlyPoly && gi < gd.length; gi++) {
                    var gn = gd[gi].constructor.name;
                    if (gn !== "Polygon" && gn !== "Group") { onlyPoly = false; break; }
                }
            } catch (e) { onlyPoly = false; }
            if (!onlyPoly) continue;
        }

        // 후보 자격: parent 가 Spread/Page/MasterSpread 이거나 뱃지의 조상 체인 중 한 그룹과 일치
        var parName = "";
        var parId = null;
        try {
            var par = item.parent;
            if (par) { parName = par.constructor.name; parId = par.id; }
        } catch (e) { continue; }
        var topLevel = (parName === "Spread" || parName === "Page" || parName === "MasterSpread");
        var sharedAncestor = (parId !== null && badgeAncestorIds[parId]);
        if (!topLevel && !sharedAncestor) continue;

        // 같은 page 인지 확인 (parentPage 기준)
        if (badgePageId !== null) {
            var itemPageId = null;
            try {
                if (item.parentPage && item.parentPage.isValid) itemPageId = item.parentPage.id;
            } catch (e) {}
            if (itemPageId !== badgePageId) continue;
        }

        var ib = null;
        try { ib = item.visibleBounds; } catch (e) { continue; }
        if (!ib) continue;
        var iT = ib[0], iL = ib[1], iB = ib[2], iR = ib[3];
        var iH = iB - iT, iW = iR - iL;
        if (iH <= 0 || iW <= 0) continue;

        // 크기 필터
        // - minDim: 짧은 변이 뱃지 maxDim × 1.5 초과면 제외 (큰 데코)
        // - maxDim: 긴 변도 뱃지 maxDim × 1.5 초과면 제외 (가로/세로로 길게 뻗은 도로/패스 등이
        //   adjacency 만족으로 잘못 매칭되어 거대한 그림자가 PNG에 합쳐지는 것 방지)
        var iMinDim = Math.min(iH, iW);
        var iMaxDim = Math.max(iH, iW);
        if (iMinDim > bMaxDim * 1.5) continue;
        if (iMaxDim > bMaxDim * 1.5) continue;

        // 겹침 또는 인접 검사
        var ovT = Math.max(bT, iT), ovL = Math.max(bL, iL);
        var ovB = Math.min(bB, iB), ovR = Math.min(bR, iR);
        var hasOverlap = (ovB > ovT && ovR > ovL);
        var matched = false;

        if (hasOverlap) {
            var ovA = (ovB - ovT) * (ovR - ovL);
            var iA = iH * iW;
            if (iA > 0 && ovA / iA >= minOverlap) matched = true;
        } else {
            var horizOv = Math.min(bR, iR) - Math.max(bL, iL);
            if (horizOv > 0 && horizOv / Math.min(bW, iW) >= 0.5) {
                var vertGap = Math.min(Math.abs(iB - bT), Math.abs(bB - iT));
                if (vertGap <= adjUnit) matched = true;
            }
        }

        if (matched) matches.push(item);
    }

    return matches;
}

/**
 * SPEC-023: renderable TextFrame 의 라운드 사각형 배경 도형 검색.
 * Pass 2 에서 외곽선 텍스트 배지(예: "Read", "Write On") 가 별개 PageItem 으로 존재하는
 * 배경 도형 위에 놓여 있을 때, 배경을 함께 export 대상에 포함하기 위해 호출한다.
 *
 * @param {TextFrame} tf - renderable TextFrame
 * @param {Array} candidates - 후보 PageItem 배열 (사전 필터된 Rectangle/Polygon/Oval)
 * @return {PageItem|null} 매칭된 배경 도형 또는 null
 */
function findRenderableTextBackground(tf, candidates) {
    var tfBounds = null;
    try { tfBounds = tf.visibleBounds; } catch (e) { return null; }
    if (!tfBounds) return null;
    var tT = tfBounds[0], tL = tfBounds[1], tB = tfBounds[2], tR = tfBounds[3];
    var tH = tB - tT, tW = tR - tL;
    if (tH <= 0 || tW <= 0) return null;
    var tArea = tH * tW;

    var tfPageId = null;
    try {
        if (tf.parentPage && tf.parentPage.isValid) tfPageId = tf.parentPage.id;
    } catch (e) {}

    // 부모 체인(최대 6 hop)
    var tfAncestors = {};
    try {
        var tp = tf.parent;
        var hops = 0;
        while (tp && hops < 6) {
            tfAncestors[tp.id] = true;
            var tpName = tp.constructor.name;
            if (tpName === "Spread" || tpName === "Page" || tpName === "MasterSpread") break;
            try { tp = tp.parent; } catch (e) { break; }
            hops++;
        }
    } catch (e) {}

    var best = null;
    var bestArea = Infinity;

    for (var ai = 0; ai < candidates.length; ai++) {
        var item = candidates[ai];
        if (!item || item.id === tf.id) continue;
        var cn;
        try { cn = item.constructor.name; } catch (e) { continue; }
        if (cn !== "Rectangle" && cn !== "Polygon" && cn !== "Oval") continue;
        if (isOnHiddenLayer(item)) continue;

        // fill 검사
        var fc = null;
        try { fc = item.fillColor; } catch (e) {}
        var fcName = "";
        try { if (fc) fcName = fc.name; } catch (e) {}
        if (!fcName || fcName === "None" || fcName === "[None]") continue;

        // 같은 page
        if (tfPageId !== null) {
            var ipid = null;
            try { if (item.parentPage && item.parentPage.isValid) ipid = item.parentPage.id; } catch (e) {}
            if (ipid !== tfPageId) continue;
        }

        // 부모 자격
        var parName = "", parId = null;
        try {
            var par = item.parent;
            if (par) { parName = par.constructor.name; parId = par.id; }
        } catch (e) { continue; }
        var topLevel = (parName === "Spread" || parName === "Page" || parName === "MasterSpread");
        var sharedAncestor = (parId !== null && tfAncestors[parId]);
        if (!topLevel && !sharedAncestor) continue;

        var ib = null;
        try { ib = item.visibleBounds; } catch (e) { continue; }
        if (!ib) continue;
        var iT = ib[0], iL = ib[1], iB = ib[2], iR = ib[3];
        var iH = iB - iT, iW = iR - iL;
        if (iH <= 0 || iW <= 0) continue;
        var iArea = iH * iW;

        // 페이지 전면 배경 제외 — 높이는 TF 와 비슷해야(pill 형태), 너비는 자유
        // (예: 긴 pill 안에 짧은 라벨)
        // 임계값 × 3 은 page28 의 "Up" outlined letter (tH≈13mm) 가 그 위 거대한
        // Rectangle (iH=40mm) 을 bg 로 잘못 매칭하던 케이스가 있어 × 2 로 강화.
        // SPEC-023 의 pill 배경 (tH ≈ iH) 은 비율 1× 근방이라 영향 없음.
        if (iH > tH * 2) continue;
        if (iArea > 50000) continue; // 절대 상한 (≈ 페이지 절반)

        // contains: TF 가 배경 안에 80% 이상 들어있어야 함
        var ovT = Math.max(tT, iT), ovL = Math.max(tL, iL);
        var ovB = Math.min(tB, iB), ovR = Math.min(tR, iR);
        if (ovB <= ovT || ovR <= ovL) continue;
        var ovA = (ovB - ovT) * (ovR - ovL);
        if (ovA / tArea < 0.8) continue;

        if (iArea < bestArea) {
            best = item;
            bestArea = iArea;
        }
    }

    return best;
}

// --- classifyTextFrame 조건별 헬퍼 (H-3) ---

function _isHashiraStyleName(n) {
    if (!n) return false;
    var nl = n.toLowerCase();
    return n.indexOf("하시라") >= 0
        || n.indexOf("페이지번호") >= 0
        || n.indexOf("쪽수") >= 0
        || n.indexOf("기둥") >= 0
        || nl.indexOf("page number") >= 0
        || nl.indexOf("running head") >= 0
        || nl.indexOf("folio") >= 0;
}

// Tier A.5: 마스터 스프레드 본체 텍스트 → editable
function _ctfCheckMasterBody(item, ovr) {
    try {

        if (ovr.masterEditable) {
            var onMaster = false;
            try {
                var mp = item.parent; var hop = 0;
                while (mp && hop < 10) {
                    if (mp.constructor && mp.constructor.name === "MasterSpread") { onMaster = true; break; }
                    mp = mp.parent; hop++;
                }
            } catch (e) {}
            if (onMaster) {
                var txt = "";
                try { txt = String(item.contents).replace(/[\s﻿\r\n￼﻿]/g, ""); } catch (e) {}
                if (txt.length > 0) return "editable";
            }
        }
    } catch (e) {}
    return null;
}

// 1.5: 비균일/축소 변환 → background (HWPX 스케일 재현 불가)
function _ctfCheckScaled(item) {
    try {
        var sx = 1, sy = 1;
        try { var ahs = item.absoluteHorizontalScale; if (typeof ahs === "number" && ahs > 0) sx = ahs / 100; } catch (e) {}
        try { var avs = item.absoluteVerticalScale; if (typeof avs === "number" && avs > 0) sy = avs / 100; } catch (e) {}
        if ((sx > 0 && (sx < 0.6 || sx > 1.6)) || (sy > 0 && (sy < 0.6 || sy > 1.6))) return "background";
    } catch (e) {}
    return null;
}

// Tier B: 회전 bypass 플래그 계산 (절대 회전각, 부모 Group 포함)
function _ctfComputeRotBypass(item, ovr) {
    try {

        if (ovr.rotEditable) {
            var rot = 0;
            try { rot = item.absoluteRotationAngle; } catch (e) {}
            if (!rot) { try { rot = item.rotationAngle; } catch (e) {} }
            if (!rot) {
                try {
                    var par = item.parent;
                    while (par && par.constructor && par.constructor.name === "Group") {
                        var pRot = 0;
                        try { pRot = par.absoluteRotationAngle; } catch (e) {}
                        if (!pRot) { try { pRot = par.rotationAngle; } catch (e) {} }
                        if (pRot) { rot = pRot; break; }
                        par = par.parent;
                    }
                } catch (e) {}
            }
            if (Math.abs(rot) > 3.0) return true;
        }
    } catch (e) {}
    return false;
}

// 2: 숨겨진 레이어 → background
function _ctfCheckHidden(item) {
    if (isOnHiddenLayer(item)) return "background";
    return null;
}

// 3: 비인쇄 → background (spec025.nonprintingEditable 이면 editable 유지)
// 단, 비인쇄 마스터 페이지 아이템은 플래그와 무관하게 항상 background.
// (마스터에서 파생됐으나 Nonprinting=true인 아이템은 의도적으로 숨겨진 하시라/장식 요소)
function _ctfCheckNonprinting(item, ovr) {
    try {
        if (item.nonprinting) {
            var isMaster = false;
            try { isMaster = !!item.masterPageItem; } catch (e) {}
            // parentPage가 null이거나 TF top이 페이지 top 위 → off-canvas (pasteboard) → background 강제
            var isOffCanvas = false;
            try {
                var ppg = item.parentPage;
                if (!ppg) isOffCanvas = true;
                else if (item.geometricBounds[0] < ppg.bounds[0]) isOffCanvas = true;
            } catch (e) {}
            if (isMaster || isOffCanvas || !ovr.nonprintEditable) return "background";
        }
    } catch (e) {}
    return null;
}

// 4: 자동 페이지 번호 마커 → background
function _ctfCheckAutoPageNumber(item) {
    try {
        if (item.parentStory.contents.indexOf("") >= 0) return "background";
    } catch (e) {}
    return null;
}

// 5: 마스터 페이지 오버라이드 → background (spec025.masterPageEditable 이면 editable 유지)
function _ctfCheckMasterOverride(item, ovr) {
    try {
        if (item.masterPageItem) {

            if (!ovr.masterEditable) return "background";
        }
    } catch (e) {}
    return null;
}

// 6: 마진 영역 하시라/페이지번호 패턴 → background (spec025.hashiraEditable 이면 editable 유지)
function _ctfCheckHashira(item, ovr) {
    try {
        var ppg = item.parentPage;
        if (!ppg) return null;
        var pgB = ppg.bounds;
        var pgW = pgB[3] - pgB[1], pgH = pgB[2] - pgB[0];
        var tfB = item.geometricBounds;
        var tfTop = tfB[0] - pgB[0], tfBot = tfB[2] - pgB[0];
        var tfLeft = tfB[1] - pgB[1], tfRight = tfB[3] - pgB[1];
        var inMarginArea = (tfTop < pgH * 0.10 || tfBot > pgH * 0.90
            || tfRight <= pgW * 0.25 || tfLeft >= pgW * 0.75);
        var hasTable = false;
        try {
            hasTable = (item.parentStory && item.parentStory.tables.length > 0)
                || (item.contents.indexOf("") >= 0);
        } catch (e) {}
        var hashiraStyle = false;
        try {
            var ps = null; var styleName = "";
            try { ps = item.parentStory.paragraphs[0].appliedParagraphStyle; } catch (e) {}
            try { styleName = ps ? (ps.name || "") : ""; } catch (e) {}
            if (_isHashiraStyleName(styleName)) hashiraStyle = true;
            if (!hashiraStyle) {
                try {
                    var trs = item.parentStory.textStyleRanges;
                    for (var ti = 0; ti < trs.length; ti++) {
                        var cs = null; var csName = "";
                        try { cs = trs[ti].appliedCharacterStyle; } catch (e) {}
                        try { csName = cs ? (cs.name || "") : ""; } catch (e) {}
                        if (_isHashiraStyleName(csName)) { hashiraStyle = true; break; }
                    }
                } catch (e) {}
            }
        } catch (e) {}
        if (hashiraStyle && inMarginArea && !hasTable) {
            
            if (!ovr.hashiraEditable) return "background";
        }
    } catch (e) {}
    return null;
}

// 7: 인라인 객체 → background (spec025.inlineTextEditable + 짧은 텍스트이면 editable 유지)
function _ctfCheckInline(item, ovr) {
    if (!isInlineItem(item)) return null;
    var inlineBypass = false;
    try {
        
        if (ovr.inlineEditable) {
            var cached = ""; try { cached = String(item.contents); } catch (e) {}
            var trimmed = ""; try { trimmed = cached.replace(/[\s﻿￼]/g, ""); } catch (e) {}
            var maxLen = ovr.inlineMaxLen;
            if (trimmed.length > 0 && trimmed.length <= maxLen) inlineBypass = true;
        }
    } catch (e) {}
    if (!inlineBypass) return "background";
    return null;
}

// 8a: Group + 긴 텍스트(>10자) + 배경색 도형에 완전 포함 → null (editable bypass)
// 배경 도형은 deco 그룹 PNG로, 텍스트는 HWPX 글상자로 각자 배치.
// hideTextFrames()가 TF를 숨겨 "background" 반환 시 텍스트가 PNG에도, HWPX에도 미표시됨.
function _ctfCheckGroupLongTextInContainer(item, rotBypass) {
    try {
        if (item.parent.constructor.name !== "Group") return null;
        var groupText = item.contents.replace(/[\s﻿\r\n]/g, "");
        var hasTable = false;
        try { hasTable = item.parentStory && item.parentStory.tables.length > 0; } catch (e) {}
        if (groupText.length <= 10 || hasTable || rotBypass || isBadgeGroup(item.parent)) return null;
        var grpItems = [];
        try { grpItems = item.parent.allPageItems; } catch (e) {}
        for (var gi = 0; gi < grpItems.length; gi++) {
            var gn = grpItems[gi];
            var gnCn = gn.constructor.name;
            if (gnCn !== "Rectangle" && gnCn !== "Oval" && gnCn !== "Polygon") continue;
            try {
                var gnFill = gn.fillColor;
                if (!gnFill || gnFill.name === "None" || gnFill.name === "[None]") continue;
                var cb = gn.geometricBounds;
                var tfb = item.geometricBounds;
                var TOL = 3.0;
                if (tfb[0] >= cb[0]-TOL && tfb[1] >= cb[1]-TOL &&
                    tfb[2] <= cb[2]+TOL && tfb[3] <= cb[3]+TOL) {
                    return null; // editable 폴스루
                }
            } catch (e) {}
        }
    } catch (e) {}
    return null;
}

// 8b: Group + 짧은 텍스트(≤10자) + 비검정 + 무스트로크 → background (장식 레이블)
// 배지 그룹(isBadgeGroup) 및 spec025.groupShortTextEditable 플래그로 bypass 가능.
function _ctfCheckGroupShortDeco(item, rotBypass, ovr) {
    try {
        if (item.parent.constructor.name !== "Group") return null;
        var groupText = item.contents.replace(/[\s﻿\r\n]/g, "");
        var hasTable = false;
        try { hasTable = item.parentStory && item.parentStory.tables.length > 0; } catch (e) {}
        if (groupText.length > 10 || hasTable) return null;
        var isDeco = false;
        try {
            var fc = item.parentStory.characters[0].fillColor;
            if (fc && fc.name && fc.name !== "Black" && fc.name !== "[Black]") isDeco = true;
        } catch (e) { isDeco = true; }
        var hasStroke = false;
        try {
            var ch = item.parentStory.characters[0];
            var sc = ch.strokeColor; var sw = ch.strokeWeight;
            if (sc && sc.name && sc.name !== "None" && sc.name !== "[None]" && sw && sw > 0) hasStroke = true;
        } catch (e) {}
        var groupShortBypass = false;
        try {
            
            if (ovr.groupShortEdit) groupShortBypass = true;
        } catch (e) {}
        if (isDeco && !hasStroke && !rotBypass && !groupShortBypass) return "background";
    } catch (e) {}
    return null;
}

// 8.5/9(decorativeStyledText)/9.5 통합: 짧은 텍스트(≤maxLen) + [프레임 fill | stroke | 부모 fill] → renderable
// Path A (fill): ovr.decoStyledEdit로 bypass. excludeBlack 컨픽 적용.
// Path B (stroke): ovr.boxLabelEdit로 bypass. 색상 필터 없음.
function _ctfCheckDecorativeShortText(item, rotBypass, ovr) {
    if (rotBypass) return null;
    try {
        var trimmed = item.contents.replace(/[\s﻿\r\n￼]/g, "");
        var dstCfg = CONFIG.rendering.textFrame.decorativeStyledText;
        var maxLen = (dstCfg && dstCfg.enabled) ? dstCfg.maxTextLength : 10;
        if (trimmed.length === 0 || trimmed.length > maxLen) return null;
        var firstChar = null;
        try { firstChar = item.parentStory.characters[0]; } catch (e) {}
        // Path A: TF fill 또는 부모 fill (excludeBlack 필터 적용)
        if (!ovr.decoStyledEdit) {
            var hasFill = false;
            try {
                var fn = item.fillColor ? item.fillColor.name : "None";
                if (fn !== "None" && fn !== "[None]") hasFill = true;
            } catch (e) {}
            if (!hasFill) {
                try {
                    var par = item.parent;
                    var pn = par ? par.constructor.name : "null";
                    if (par && pn !== "Story" && pn !== "Spread" && pn !== "Page") {
                        var pf = par.fillColor ? par.fillColor.name : "None";
                        if (pf !== "None" && pf !== "[None]") hasFill = true;
                    }
                } catch (e) {}
            }
            if (hasFill) {
                var isBlk = dstCfg.excludeBlack && firstChar && isBlackColor(firstChar, dstCfg.blackThreshold);
                if (!isBlk) return "renderable";
            }
        }
        // Path B: TF stroke (excludeBlack 필터 없음 — 테두리만 있으면 렌더링)
        if (!ovr.boxLabelEdit) {
            try {
                var sw = item.strokeWeight || 0;
                if (sw > 0) {
                    var sn = item.strokeColor ? item.strokeColor.name : "None";
                    if (sn !== "None" && sn !== "[None]") return "renderable";
                }
            } catch (e) {}
        }
    } catch (e) {}
    return null;
}

// 8.7: 같은 Group 안 동일 텍스트 형제 (멀티레이어 컴포지트) → background
function _ctfCheckTextComposite(item) {
    try {
        var par = item.parent;
        if (!par || par.constructor.name !== "Group" || !par.textFrames
                || par.textFrames.length < 2 || par.textFrames.length > 10) return null;
        var myText = ""; try { myText = (item.contents + "").replace(/\s/g, ""); } catch (e) {}
        if (myText.length === 0 || myText.length > 30) return null;
        for (var i = 0; i < par.textFrames.length; i++) {
            var sib = par.textFrames[i];
            if (sib.id === item.id) continue;
            var sibText = ""; try { sibText = (sib.contents + "").replace(/\s/g, ""); } catch (e) {}
            if (sibText === myText) return "background";
        }
    } catch (e) {}
    return null;
}


// 10: 빈 TextFrame + fill/stroke 있음 → renderable(stroke) or background(fill-only)
// fillColor="" (빈 문자열)은 실제 fill 없음으로 처리 ("None"/"[None]"과 동일하게 취급)
// stroke 있는 빈 TF = 장식 테두리 → 개별 PNG 렌더 (페이지 배경 비활성화 시에도 표시)
function _ctfCheckEmptyFilled(item) {
    try {
        var trimmed = item.contents.replace(/[\r\n\s￼]/g, "");
        if (trimmed.length !== 0) return null;
        var fc = "None", sc = "None", sw = 0;
        try { fc = item.fillColor ? item.fillColor.name : "None"; } catch (e) {}
        try { sc = item.strokeColor ? item.strokeColor.name : "None"; } catch (e) {}
        try { sw = item.strokeWeight || 0; } catch (e) {}
        var hasFill = (fc !== "None" && fc !== "[None]" && fc !== "");
        var hasStroke = (sc !== "None" && sc !== "[None]") && sw > 0;
        if (hasFill || hasStroke) {
            if (hasStroke) return "renderable";
            return "background";
        }
    } catch (e) {}
    return null;
}

// 11: 실질적으로 빈 스토리 (공백/제어문자만) → background
function _ctfCheckEmptyStory(item, rotBypass, ovr) {
    try {
        var storyText = item.parentStory.contents.replace(/[\s﻿￼\r\n]/g, "");
        if (storyText.length > 1) return null;
        var hasTables = false;
        try { hasTables = item.parentStory.tables.length > 0; } catch (e) {}
        var oneCharBypass = false;
        try {
            
            if (ovr.oneCharEdit && storyText.length >= 1) oneCharBypass = true;
        } catch (e) {}
        if (!hasTables && !rotBypass && !oneCharBypass) return "background";
    } catch (e) {}
    return null;
}

// spec025 override 플래그를 한 번만 읽어 반환 (classifyTextFrame 진입 시 1회 호출)
function _ctfBuildOverrides() {
    var s25 = null;
    try { s25 = _spec025(); } catch (e) {}
    return {
        masterEditable:   !!(s25 && s25.masterPageEditable),
        nonprintEditable: !!(s25 && s25.nonprintingEditable),
        hashiraEditable:  !!(s25 && s25.hashiraEditable),
        inlineEditable:   !!(s25 && s25.inlineTextEditable),
        inlineMaxLen:     (s25 && typeof s25.inlineTextMaxLen === "number") ? s25.inlineTextMaxLen : 3,
        groupShortEdit:   !!(s25 && s25.groupShortTextEditable),
        boxLabelEdit:     !!(s25 && s25.boxLabelEditable),
        oneCharEdit:      !!(s25 && s25.oneCharEditable),
        rotEditable:      !!(s25 && s25.rotationEditable),
        decoLargeEdit:    !!(s25 && s25.decorativeLargeTextEditable),
        decoStyledEdit:   !!(s25 && s25.decorativeStyledTextEditable),
    };
}

/**
 * TextFrame을 분류한다.
 * @param {PageItem} item - allPageItems 항목
 * @return {string|null}
 *   "background" - 배경 PNG에 포함 (non-editable)
 *   "editable"   - 배경에서 숨기고 HWPX 글상자로 변환
 *   "renderable" - 배경에 포함 + 별도 장식 PNG 렌더링
 *   null         - TextFrame이 아님 (건너뜀)
 */
function classifyTextFrame(item) {
    if (item.constructor.name !== "TextFrame") return null;

    var ovr = _ctfBuildOverrides();
    var r;
    if ((r = _ctfCheckMasterBody(item, ovr)) !== null) return r;  // Tier A.5
    if ((r = _ctfCheckScaled(item)) !== null) return r;       // 1.5

    var rotBypass = _ctfComputeRotBypass(item, ovr);               // Tier B

    if ((r = _ctfCheckHidden(item)) !== null) return r;           // 2
    if ((r = _ctfCheckNonprinting(item, ovr)) !== null) return r;      // 3
    if ((r = _ctfCheckAutoPageNumber(item)) !== null) return r;   // 4
    if ((r = _ctfCheckMasterOverride(item, ovr)) !== null) return r;   // 5
    if ((r = _ctfCheckHashira(item, ovr)) !== null) return r;          // 6
    if ((r = _ctfCheckInline(item, ovr)) !== null) return r;           // 7
    if ((r = _ctfCheckGroupLongTextInContainer(item, rotBypass)) !== null) return r;  // 8a
    if ((r = _ctfCheckGroupShortDeco(item, rotBypass, ovr)) !== null) return r;            // 8b
    if ((r = _ctfCheckDecorativeShortText(item, rotBypass, ovr)) !== null) return r;  // 8.5
    if ((r = _ctfCheckTextComposite(item)) !== null) return r;    // 8.7
    if (!rotBypass && isRenderableTextFrame(item, ovr)) return "renderable";    // 9
    if ((r = _ctfCheckEmptyFilled(item)) !== null) return r;      // 10
    if ((r = _ctfCheckEmptyStory(item, rotBypass, ovr)) !== null) return r;     // 11
    return "editable";
}

// _runRenderPhases에서 allItems 전체를 한 번만 분류한 결과를 조회. 캐시 미초기화 시 직접 호출로 폴백.
function classifyTextFrameCached(item) {
    if (_ctfCache === null) return classifyTextFrame(item);
    var id = item.id;
    if (_ctfCache[id] === undefined) _ctfCache[id] = classifyTextFrame(item);
    return _ctfCache[id];
}
function isBadgeGroupCached(item) {
    if (_badgeCache === null) return isBadgeGroup(item);
    var id = item.id;
    if (_badgeCache[id] === undefined) _badgeCache[id] = isBadgeGroup(item);
    return _badgeCache[id];
}

/**
 * TextFrame이 이미지 렌더링 대상인지 판별한다.
 * 회전, 스트로크, 그림자 등 HWPX에서 재현 불가한 효과가 있는 경우에만 렌더링.
 */
function isRenderableTextFrame(tf, ovr) {
    // 스레드된 프레임은 짧은 텍스트(< 30자)일 때만 여기까지 오므로 제외하지 않음
    // (장식 텍스트가 스레드 체인에 포함될 수 있음)
    try {
        if (!tf.parentStory) return false;
    } catch (e) { return false; }

    // 인라인/앵커/표 셀 제외
    try {
        var pType = tf.parent.constructor.name;
        if (pType === "Character" || pType === "InsertionPoint"
            || pType === "Story" || pType === "Cell") return false;
    } catch (e) {}

    // Story의 텍스트 내용으로 판정
    var text = "";
    try { text = tf.parentStory.contents; } catch (e) { return false; }

    // 제어문자나 앵커 객체 마커(U+FFFC)가 포함된 텍스트 제외
    // 단, \t(0x09), \n(0x0A), \r(0x0D)은 정상적인 단락/줄 구분자이므로 허용
    if (/[\x00-\x08\x0B\x0C\x0E-\x1F\uFFFC]/.test(text)) return false;

    // 공백 제거 후 길이 판정: 1자 이상이면 효과 체크 진행
    var trimmed = text.replace(/[\s\uFEFF]/g, "");
    if (trimmed.length === 0) return false;

    // 회전된 프레임 → 기본은 PNG 렌더링.
    // SPEC-025 Tier B: spec025.rotationEditable=true이면 HWPX TextBox rotationInfo로 변환
    // (FrameTransformations.convertRotatedFloatingBlock 경로). 텍스트 검색 가능.
    // 회전이 부모 Group 에 걸려 있을 수 있으므로 absoluteRotationAngle 우선 사용.
    try {
        var rotR = 0;
        try { rotR = tf.absoluteRotationAngle; } catch (e) {}
        if (!rotR) { try { rotR = tf.rotationAngle; } catch (e) {} }
        if (Math.abs(rotR) > 3.0) {
            
            if (!ovr.rotEditable) return true;
        }
    } catch (e) {}

    // 텍스트에 효과가 적용된 경우 → 렌더링 (HWPX에서 재현 불가)
    try {
        var firstChar = tf.parentStory.characters[0];

        // 텍스트 스트로크(외곽선) — strokeColor가 None이 아니면 렌더링
        try {
            if (firstChar.strokeWeight > 0) {
                var _scName = "None";
                try { _scName = firstChar.strokeColor ? firstChar.strokeColor.name : "None"; } catch (e) {}
                if (_scName !== "None" && _scName !== "[None]") return true;
            }
        } catch (e) {}

        // 드롭 섀도우
        try {
            var dss = tf.transparencySettings.dropShadowSettings;
            if (dss.mode != ShadowMode.NONE) { return true; }
        } catch (e) {}

        // 외부 광선 (Outer Glow)
        try {
            if (tf.transparencySettings.outerGlowSettings.applied) { return true; }
        } catch (e) {}

        // 내부 광선 (Inner Glow)
        try {
            if (tf.transparencySettings.innerGlowSettings.applied) { return true; }
        } catch (e) {}

        // 베벨/엠보스
        try {
            if (tf.transparencySettings.bevelAndEmbossSettings.applied) { return true; }
        } catch (e) {}

        // 새틴 (Satin)
        try {
            if (tf.transparencySettings.satinSettings.applied) { return true; }
        } catch (e) {}

        // 프레임 투명도 (불투명도 < 100%)
        try {
            var opacity = tf.transparencySettings.blendingSettings.opacity;
            if (opacity < 100) { return true; }
        } catch (e) {}

        // 텍스트 수준 드롭 섀도우 (문자에 직접 적용)
        try {
            var charDss = firstChar.transparencySettings.dropShadowSettings;
            if (charDss.mode != ShadowMode.NONE) { return true; }
        } catch (e) {}

        // 텍스트 수준 투명도
        try {
            var charOpacity = firstChar.transparencySettings.blendingSettings.opacity;
            if (charOpacity < 100) { return true; }
        } catch (e) {}

        // 장식 대형 컬러 텍스트: fontSize >= minFontSize AND 색상이 검정이 아님
        // SPEC-025: decorativeLargeTextEditable=true 면 큰 단원 제목/장 제목도 editable 로 변환 (검색 가능)
        try {
            
            if (!ovr.decoLargeEdit) {
                var dltCfg = CONFIG.rendering.textFrame.decorativeLargeText;
                if (dltCfg.enabled) {
                    var fontSize = firstChar.pointSize;
                   
                    if (fontSize >= dltCfg.minFontSize) {
                        if (!dltCfg.excludeBlack || !isBlackColor(firstChar, dltCfg.blackThreshold)) {
                           
                            return true;
                        }
                    }
                }
            }
        } catch (e) { }


    } catch (e) { }

   
    return false;
}

/**
 * 텍스트가 흰색 또는 희미한 색상인지 판별.
 * 배경이 없으면 보이지 않는 텍스트 → 렌더링 대상.
 */
/**
 * TextFrame이 장식 스타일 텍스트 배지인지 판별.
 * 조건: 짧은 텍스트(≤maxTextLength) + 비검정 + Object Style(배경색/테두리/둥근모서리).
 */
function isDecorativeStyledTextFrame(tf) {
    var cfg = CONFIG.rendering.textFrame.decorativeStyledText;
    if (!cfg || !cfg.enabled) return false;

    try {
        var text = tf.parentStory.contents;
        var trimmed = text.replace(/[\s\uFEFF\r\n]/g, "");
        if (trimmed.length === 0 || trimmed.length > cfg.maxTextLength) return false;

        // 검정색 체크
        if (cfg.excludeBlack) {
            var firstChar = tf.parentStory.characters[0];
            if (isBlackColor(firstChar, cfg.blackThreshold)) return false;
        }

        // Object Style 체크
        if (cfg.requireObjectStyle) {
            var hasStyle = false;
            try {
                var fillName = tf.fillColor ? tf.fillColor.name : "None";
                if (fillName !== "None" && fillName !== "[None]") hasStyle = true;
            } catch (e) {}
            if (!hasStyle) {
                try {
                    if (tf.strokeWeight > 0) {
                        var sName = tf.strokeColor ? tf.strokeColor.name : "None";
                        if (sName !== "None" && sName !== "[None]") hasStyle = true;
                    }
                } catch (e) {}
            }
            if (!hasStyle) return false;
        }

        return true;
    } catch (e) {
        return false;
    }
}

function isLightColoredText(tf) {
    try {
        var firstChar = tf.parentStory.characters[0];
        var fillName = firstChar.fillColor.name;
        // Paper = 흰색
        if (fillName === "Paper" || fillName === "[Paper]") return true;
        // tint가 매우 낮은 경우 (어떤 색이든 희미함)
        try {
            var tint = firstChar.fillTint;
            if (tint >= 0 && tint <= 30) return true;
        } catch (e) {}
        // CMYK 밝은 색 판별 (잉크량 합계가 매우 낮음)
        try {
            var cv = firstChar.fillColor.colorValue;
            var space = firstChar.fillColor.space;
            if (space.toString() === "ColorSpace.CMYK") {
                if (cv[0] + cv[1] + cv[2] + cv[3] <= 20) return true;
            }
        } catch (e) {}
    } catch (e) {}
    return false;
}

// =============================================================================
// SECTION 5: RENDER PIPELINE
// PNG export — 배지 / 텍스트프레임 / 복합 그래픽 / PDF / 이미지 / 데코 / 벡터
// =============================================================================


/**
 * 짧은 텍스트 프레임(장식 텍스트)과 배지 그룹을 PNG로 렌더링한다.
 */
function exportRenderedTextFrames(doc, outputDir, startPage, endPage, allItems, editableFrameIds, skipRenderPagesMap) {
    if (!skipRenderPagesMap) skipRenderPagesMap = {};
    var renderDir = Folder(outputDir + "/rendered_frames");
    renderDir.create();

    var renderedFrames = [];
    var renderedIds = {};
    var badgeGroupChildIds = {};
    if (!editableFrameIds) editableFrameIds = {};



    app.pngExportPreferences.exportResolution = CONFIG.rendering.pngExportResolution || 220;
    app.pngExportPreferences.antiAlias = true;
    app.pngExportPreferences.transparentBackground = true;
    app.pngExportPreferences.pngQuality = PNGQualityEnum.MAXIMUM;

    // SPEC-022 데코 후보 사전 필터 (큰 문서 O(N²) 회피)
    var decoCandidatesPass1 = [];
    if (CONFIG.rendering.badge.decorationMergeEnabled) {
        for (var dcp1 = 0; dcp1 < allItems.length; dcp1++) {
            try {
                var dcIt = allItems[dcp1];
                var dcCn = dcIt.constructor.name;
                if (dcCn === "Polygon" || dcCn === "Group") decoCandidatesPass1.push(dcIt);
            } catch (e) {}
        }
    }

    // SPEC-023 배경 후보 사전 필터: fill 있는 Rectangle/Polygon/Oval
    var bgCandidatesPass2 = [];
    for (var bcp2 = 0; bcp2 < allItems.length; bcp2++) {
        try {
            var bcIt = allItems[bcp2];
            var bcCn = bcIt.constructor.name;
            if (bcCn !== "Rectangle" && bcCn !== "Polygon" && bcCn !== "Oval") continue;
            var bcFc = null;
            try { bcFc = bcIt.fillColor; } catch (e) {}
            var bcFcName = "";
            try { if (bcFc) bcFcName = bcFc.name; } catch (e) {}
            if (!bcFcName || bcFcName === "None" || bcFcName === "[None]") continue;
            bgCandidatesPass2.push(bcIt);
        } catch (e) {}
    }

    // SPEC-030 B.4: 배지 총 개수 선집계 (진행 표시용). A.4: inline 배지는 PNG 미생성 → 카운트 제외
    var _totalBadges = 0;
    for (var _bc = 0; _bc < allItems.length; _bc++) {
        if (allItems[_bc].constructor.name === "Group" && isBadgeGroupCached(allItems[_bc]) && !isInlineItem(allItems[_bc])) _totalBadges++;
    }
    var _renderedBadges = 0;

    // SPEC-030 B.1: 단순 배지(데코/editable TF 없음)를 페이지별로 모아 배치 export
    var _batchExportedIds = {};
    var _cropManifest = [];
    var _badgesByPage = {};
    for (var _bgi = 0; _bgi < allItems.length; _bgi++) {
        var _bgrp = allItems[_bgi];
        if (_bgrp.constructor.name !== "Group") continue;
        if (isOnHiddenLayer(_bgrp)) continue;
        if (!isBadgeGroupCached(_bgrp)) continue;
        // A.4: inline 배지는 Java가 INLINE_TEXT_FRAME으로 처리 → 배치 export 제외
        if (isInlineItem(_bgrp)) continue;
        var _bgrpPage = null;
        try { _bgrpPage = _bgrp.parentPage; } catch (e) {}
        if (!_bgrpPage) {
            try {
                var _bgvb = _bgrp.visibleBounds;
                var _bgcy = (_bgvb[0] + _bgvb[2]) / 2;
                var _bgcx = (_bgvb[1] + _bgvb[3]) / 2;
                for (var _bpgi = 0; _bpgi < doc.pages.length; _bpgi++) {
                    var _bpgo = doc.pages[_bpgi];
                    var _bpgb = _bpgo.bounds;
                    if (_bgcy >= _bpgb[0] && _bgcy <= _bpgb[2] && _bgcx >= _bpgb[1] && _bgcx <= _bpgb[3]) {
                        _bgrpPage = _bpgo; break;
                    }
                }
            } catch (e) {}
        }
        if (!_bgrpPage) continue;
        var _bgrpPgIdx = _bgrpPage.documentOffset + 1;
        if (_bgrpPgIdx < startPage || _bgrpPgIdx > endPage) continue;
        if (skipRenderPagesMap[_bgrpPgIdx]) continue;
        // 데코 있으면 배치 제외
        if (decoCandidatesPass1.length > 0 && findOverlappingDecorations(_bgrp, decoCandidatesPass1).length > 0) continue;
        // editable TF 자식 있으면 배치 제외
        var _bHasEditable = false;
        try {
            var _bChk = _bgrp.allPageItems;
            for (var _bei = 0; _bei < _bChk.length; _bei++) {
                if (_bChk[_bei].constructor.name === "TextFrame" && classifyTextFrameCached(_bChk[_bei]) === "editable") {
                    _bHasEditable = true; break;
                }
            }
        } catch (e) {}
        if (_bHasEditable) continue;
        // 배치 후보 등록
        var _bChildIds = [], _bChildTfIds = [];
        try {
            var _bChItems = _bgrp.allPageItems;
            for (var _bci = 0; _bci < _bChItems.length; _bci++) {
                _bChildIds.push(_bChItems[_bci].id);
                if (_bChItems[_bci].constructor.name === "TextFrame") {
                    _bChildTfIds.push(_bChItems[_bci].id);
                    // PNG에서 텍스트를 숨기므로 HWPX 텍스트 오버레이가 필요 → editable로 승격
                    var _bTfContents = "";
                    try { _bTfContents = (_bChItems[_bci].contents + "").replace(/[\s﻿\r\n￼]/g, ""); } catch (e) {}
                    if (_bTfContents.length > 0) editableFrameIds[_bChItems[_bci].id] = true;
                }
            }
        } catch (e) {}
        if (!_badgesByPage[_bgrpPgIdx]) _badgesByPage[_bgrpPgIdx] = [];
        _badgesByPage[_bgrpPgIdx].push({ grp: _bgrp, grpDomId: _bgrp.id, grpPage: _bgrpPage, childIds: _bChildIds, childTextFrameIds: _bChildTfIds });
    }
    // 페이지당 2개 이상인 경우 배치 export
    var _bDpi = CONFIG.rendering.pngExportResolution || 220;
    for (var _bpKey in _badgesByPage) {
        var _bpBadges = _badgesByPage[_bpKey];
        if (_bpBadges.length < 2) continue;
        var _bpPage = _bpBadges[0].grpPage;
        var _bpResults = exportPageBadgesBatched(doc, _bpPage, _bpBadges, renderDir, _bDpi);
        if (_bpResults.length === 0) continue; // 실패 → 개별 export로 폴백
        for (var _bpr = 0; _bpr < _bpResults.length; _bpr++) {
            var _bpEntry = _bpResults[_bpr];
            _batchExportedIds[_bpEntry.rf.id] = true;
            renderedIds[_bpEntry.rf.id] = _bpEntry.rf;
            renderedFrames.push(_bpEntry.rf);
            for (var _bptfi = 0; _bptfi < _bpEntry.rf.childTextFrameIds.length; _bptfi++) {
                var _bptfId = _bpEntry.rf.childTextFrameIds[_bptfi];
                renderedFrames.push({ id: _bptfId, file: _bpEntry.rf.file, bounds: _bpEntry.rf.bounds, pageIndex: _bpEntry.rf.pageIndex, type: "badge_group_child", badgeGroupId: _bpEntry.rf.id });
                renderedIds[_bptfId] = _bpEntry.rf;
            }
            for (var _bgcid = 0; _bgcid < _bpEntry.rf.childIds.length; _bgcid++) {
                badgeGroupChildIds[_bpEntry.rf.childIds[_bgcid]] = true;
            }
            _cropManifest.push(_bpEntry.crop);
        }
        // 배치 export 완료 배지는 개별 진행 카운트에서 제외
        _totalBadges -= _bpResults.length;
    }

    // Pass 1: 배지 그룹 감지 및 렌더링
    // 부모 그룹이 이미 렌더된 경우, 자식 그룹은 건너뜀 (중복 방지)
    for (var gi = 0; gi < allItems.length; gi++) {
        var grp = allItems[gi];
        if (grp.constructor.name !== "Group") continue;
        if (isOnHiddenLayer(grp)) continue;
        if (!isBadgeGroupCached(grp)) continue;
        // 이미 부모 배지의 childId로 등록된 그룹은 건너뜀
        if (badgeGroupChildIds[grp.id]) continue;

        // 인라인 Group은 parentPage가 null → visibleBounds로 페이지 매칭
        var grpPage = _resolveParentPage(grp, doc);
        if (!grpPage) continue;
        var grpPgIdx = grpPage.documentOffset + 1;
        if (grpPgIdx < startPage || grpPgIdx > endPage) continue;
        if (skipRenderPagesMap[grpPgIdx]) continue; // SPEC-030 B.2

        var grpDomId = grp.id;
        if (renderedIds[grpDomId]) continue;
        if (_batchExportedIds[grpDomId]) continue; // SPEC-030 B.1: 배치 export 완료

        var childIds = [];
        var childTextFrameIds = [];
        try {
            var grpItems = grp.allPageItems;
            for (var ci = 0; ci < grpItems.length; ci++) {
                var child = grpItems[ci];
                childIds.push(child.id);
                badgeGroupChildIds[child.id] = true;
                if (child.constructor.name === "TextFrame") {
                    childTextFrameIds.push(child.id);
                }
            }
        } catch (e) {}

        // A.4: inline 배지 PNG 스킵 — Java가 tryInlineGroupAsSingleBadge / tryInlineGroupAsBoxList로 처리.
        // AboveLine 앵커(floating) 배지만 PNG 필요. AnchorPosition 조회 실패 시 안전하게 export 유지.
        if (isInlineItem(grp)) {
            var _isAboveLine = false;
            try {
                var _as = grp.anchoredObjectSettings;
                _isAboveLine = (_as.anchorPosition === AnchorPosition.ABOVE_LINE);
            } catch (e) {}
            if (!_isAboveLine) {
                // child TF 텍스트 수집 유지 (editable 등록)
                for (var _a4i = 0; _a4i < childTextFrameIds.length; _a4i++) {
                    editableFrameIds[childTextFrameIds[_a4i]] = true;
                }
                continue;
            }
        }

        var grpFileName = "badge_" + grpDomId + ".png";
        var grpOutFile = File(renderDir + "/" + grpFileName);
        // SPEC-030 B.4: 배지별 진행 정보
        _renderedBadges++;
        writeProgress(outputDir, "render_badge", _renderedBadges, _totalBadges, "p" + grpPgIdx + "_" + grpDomId);

        // SPEC-022: 뱃지와 시각적으로 한 덩어리지만 별개 그룹인 외곽선 데코 (예: outlined "Step 1")
        // 를 찾아서 합성 PNG 로 export
        var overlappingDecos = decoCandidatesPass1.length > 0
                ? findOverlappingDecorations(grp, decoCandidatesPass1)
                : [];

        // 합성 bounds — 뱃지 + 데코의 union
        var combinedVB = null;
        try { combinedVB = arrCopy(grp.visibleBounds); } catch (e) {}
        if (!combinedVB) {
            try { combinedVB = arrCopy(grp.geometricBounds); } catch (e) {}
        }
        for (var di = 0; di < overlappingDecos.length; di++) {
            try {
                var dvb = overlappingDecos[di].visibleBounds;
                if (combinedVB) {
                    combinedVB[0] = Math.min(combinedVB[0], dvb[0]);
                    combinedVB[1] = Math.min(combinedVB[1], dvb[1]);
                    combinedVB[2] = Math.max(combinedVB[2], dvb[2]);
                    combinedVB[3] = Math.max(combinedVB[3], dvb[3]);
                }
            } catch (e) {}
        }

        // 데코를 badgeGroupChildIds 에 미리 등록 → page_bg 에서 숨김
        for (var di2 = 0; di2 < overlappingDecos.length; di2++) {
            var deco = overlappingDecos[di2];
            childIds.push(deco.id);
            badgeGroupChildIds[deco.id] = true;
            // 데코가 Group이면 자손도 포함
            if (deco.constructor.name === "Group") {
                try {
                    var decoChildren = deco.allPageItems;
                    for (var dci = 0; dci < decoChildren.length; dci++) {
                        badgeGroupChildIds[decoChildren[dci].id] = true;
                    }
                } catch (e) {}
            }
        }

        // 배지 그룹 내 non-inline TextFrame 은 HWPX 가 별도 텍스트 오버레이로 배치 → PNG 에서 제외.
        // inline TF(배지 안 "가"/"나" 같은 Paper 흰색 텍스트)는 제외 대상에서 제외:
        //   transparentBackground=true 에서 흰색 픽셀이 alpha=0(투명 구멍)이 되어
        //   HWPX 흰 배경 위에서 배경색과 대비되어 텍스트가 보이게 된다.
        // classifyTextFrame 체크 제거: "editable" 이외 분류(renderable 등)도 non-inline 이면 숨겨야 함.
        var hiddenForExport = [];
        try {
            var __grpItemsForHide = grp.allPageItems;
            for (var __hi = 0; __hi < __grpItemsForHide.length; __hi++) {
                var __hItem = __grpItemsForHide[__hi];
                try {
                    if (__hItem.constructor.name !== "TextFrame") continue;
                    if (!__hItem.visible) continue;
                    // 빈 TF(시각적 컨테이너)는 숨기지 않음 — 내용 있는 TF만 숨겨야 배경 그래픽이 보임
                    var __hCont = "";
                    try { __hCont = (__hItem.contents + "").replace(/[\s\r\n﻿￼]/g, ""); } catch (eC) {}
                    if (__hCont.length === 0) continue;
                    __hItem.visible = false;
                    hiddenForExport.push(__hItem);
                } catch (eH) {}
            }
        } catch (eH2) {}

        try {
            if (overlappingDecos.length === 0) {
                // 데코 없음 — 기존 단일 그룹 export
                grp.exportFile(ExportFormat.PNG_FORMAT, grpOutFile);
            } else {
                // SPEC-022: duplicate → 임시 그룹 → export → 제거 (SPEC-021 패턴 재사용)
                var dups = [];
                var grpDup = null;
                try {
                    grpDup = grp.duplicate();
                    dups.push(grpDup);
                    for (var di3 = 0; di3 < overlappingDecos.length; di3++) {
                        dups.push(overlappingDecos[di3].duplicate());
                    }
                    var tempCombined = doc.groups.add(dups);
                    try {
                        tempCombined.exportFile(ExportFormat.PNG_FORMAT, grpOutFile);
                    } finally {
                        try { tempCombined.remove(); } catch (er) {}
                    }
                } catch (eDup) {
                    // 합성 실패 시 fallback: 원본 그룹만 export
                    for (var dx = 0; dx < dups.length; dx++) {
                        try { dups[dx].remove(); } catch (er) {}
                    }
                    grp.exportFile(ExportFormat.PNG_FORMAT, grpOutFile);
                    combinedVB = null;
                    try { combinedVB = arrCopy(grp.visibleBounds); } catch (e) {}
                }
            }

            var grpBounds = combinedVB;
            if (!grpBounds) {
                try { grpBounds = arrCopy(grp.visibleBounds); } catch (e) {}
            }
            if (!grpBounds) {
                try { grpBounds = arrCopy(grp.geometricBounds); } catch (e) {}
            }

            if (grpBounds) {
                var grpPageBounds = grpPage.bounds;
                grpBounds[0] -= grpPageBounds[0];
                grpBounds[1] -= grpPageBounds[1];
                grpBounds[2] -= grpPageBounds[0];
                grpBounds[3] -= grpPageBounds[1];
            }

            var grpEntry = {
                id: grpDomId,
                file: "rendered_frames/" + grpFileName,
                bounds: grpBounds,
                pageIndex: grpPage.documentOffset,
                type: "badge_group",
                childIds: childIds,
                childTextFrameIds: childTextFrameIds,
                textHiddenBeforeExport: hiddenForExport.length > 0
            };
            renderedFrames.push(grpEntry);
            renderedIds[grpDomId] = grpEntry;

            for (var ti = 0; ti < childTextFrameIds.length; ti++) {
                var tfChildId = childTextFrameIds[ti];
                renderedFrames.push({
                    id: tfChildId,
                    file: "rendered_frames/" + grpFileName,
                    bounds: grpBounds,
                    pageIndex: grpPage.documentOffset,
                    type: "badge_group_child",
                    badgeGroupId: grpDomId
                });
                renderedIds[tfChildId] = grpEntry;
            }
        } catch (e) {}
        // SPEC-025: PNG export 가 끝났으므로 임시로 숨긴 editable TF 가시성 복원.
        for (var __rh = 0; __rh < hiddenForExport.length; __rh++) {
            try { hiddenForExport[__rh].visible = true; } catch (eR) {}
        }
    }

    // Pass 1.5: 중첩 뱃지 패턴 감지 및 렌더링 (SPEC-021)
    // 그룹 전체가 isBadgeGroup 을 통과하지 못했지만 내부에 (도형+짧은 숫자 TF) 페어가
    // 있는 경우 (예: Q2~Q6 번호 뱃지). 페어만 duplicate 후 임시 그룹으로 묶어 exportFile.
    var nbMatched = 0;
    for (var ngi = 0; ngi < allItems.length; ngi++) {
        var nGrp = allItems[ngi];
        if (nGrp.constructor.name !== "Group") continue;
        if (isOnHiddenLayer(nGrp)) continue;
        if (renderedIds[nGrp.id]) continue;           // 이미 isBadgeGroup 통과
        if (badgeGroupChildIds[nGrp.id]) continue;    // 상위 뱃지의 자식

        var pattern = findNestedBadgePattern(nGrp);
        if (!pattern) continue;

        var nBadgeShape = pattern.shape;
        var nBadgeTf = pattern.textFrame;

        // 페이지 결정 (인라인 Group은 parentPage null → visibleBounds 폴백)
        var nGrpPage = _resolveParentPage(nGrp, doc);
        if (!nGrpPage) continue;
        var nPgIdx = nGrpPage.documentOffset + 1;
        if (nPgIdx < startPage || nPgIdx > endPage) continue;
        if (skipRenderPagesMap[nPgIdx]) continue; // SPEC-030 B.2

        var nGrpId = nGrp.id;
        var nFileName = "badge_" + nGrpId + ".png";
        var nOutFile = File(renderDir + "/" + nFileName);

        // 원본 페어를 duplicate → 새 임시 그룹으로 묶고 export → 임시 그룹 삭제.
        // duplicate 는 원본과 겹쳐 생성되므로 페어 크기만큼의 PNG를 얻을 수 있음.
        // (직접 groups.add(원본페어) 는 "매개변수 잘못됨" 에러 발생 — 이미 다른 그룹 소속이기 때문)
        var nDupShape = null, nDupTf = null, nTempGrp = null;
        var nRealBounds = null;
        try {
            var origShapeB = nBadgeShape.visibleBounds || nBadgeShape.geometricBounds;
            var origTfB = nBadgeTf.visibleBounds || nBadgeTf.geometricBounds;
            nRealBounds = [
                Math.min(origShapeB[0], origTfB[0]),
                Math.min(origShapeB[1], origTfB[1]),
                Math.max(origShapeB[2], origTfB[2]),
                Math.max(origShapeB[3], origTfB[3])
            ];
            nDupShape = nBadgeShape.duplicate();
            nDupTf = nBadgeTf.duplicate();
            nTempGrp = doc.groups.add([nDupShape, nDupTf]);
        } catch (eGrp) {
            try { if (nDupShape) nDupShape.remove(); } catch (e) {}
            try { if (nDupTf) nDupTf.remove(); } catch (e) {}
            continue;
        }

        try {
            // 배경만 추출: TF 복제본 숨기고 도형만 export
            try { nDupTf.visible = false; } catch (eHide) {}
            nTempGrp.exportFile(ExportFormat.PNG_FORMAT, nOutFile);

            var nBounds = nRealBounds;
            if (nBounds) {
                var nPageB = nGrpPage.bounds;
                nBounds = [
                    nBounds[0] - nPageB[0],
                    nBounds[1] - nPageB[1],
                    nBounds[2] - nPageB[0],
                    nBounds[3] - nPageB[1]
                ];
            }

            var nEntry = {
                id: nGrpId,
                file: "rendered_frames/" + nFileName,
                bounds: nBounds,
                pageIndex: nGrpPage.documentOffset,
                type: "badge_group",
                childIds: [nBadgeShape.id, nBadgeTf.id],
                childTextFrameIds: [nBadgeTf.id],
                textHiddenBeforeExport: true
            };
            renderedFrames.push(nEntry);
            renderedIds[nGrpId] = nEntry;
            badgeGroupChildIds[nBadgeShape.id] = true;
            badgeGroupChildIds[nBadgeTf.id] = true;

            renderedFrames.push({
                id: nBadgeTf.id,
                file: "rendered_frames/" + nFileName,
                bounds: nBounds,
                pageIndex: nGrpPage.documentOffset,
                type: "badge_group_child",
                badgeGroupId: nGrpId
            });
            renderedIds[nBadgeTf.id] = nEntry;
            nbMatched++;
        } catch (eExp) {}

        // 임시 그룹 및 duplicated 페어 삭제 → 원본은 보존됨
        try { nTempGrp.remove(); } catch (eR) {}
    }
    $.writeln("[SPEC-021] nested badge pass: matched=" + nbMatched);

    // Pass 2: 개별 TextFrame / TextPath 렌더링
    for (var i = 0; i < allItems.length; i++) {
        var item = allItems[i];

        var isTextFrame = (item.constructor.name === "TextFrame");
        var hasTextPath = false;
        try { hasTextPath = item.textPaths && item.textPaths.length > 0; } catch (e) {}

        if (!isTextFrame && !hasTextPath) continue;
        if (isOnHiddenLayer(item)) continue;
        if (badgeGroupChildIds[item.id]) continue;

        var parentPage = null;
        try { parentPage = item.parentPage; } catch (e) {}
        if (!parentPage && isTextFrame) {
            try {
                var _story = item.parentStory;
                var _text = _story.contents.replace(/[\s\uFEFF]/g, "");
                var _fontSize = _story.characters[0].pointSize;
                if (_text.length > 0 && _text.length < 30 && _fontSize >= 16) {
                    var _vb = item.visibleBounds;
                    var _cy = (_vb[0] + _vb[2]) / 2;
                    var _cx = (_vb[1] + _vb[3]) / 2;
                    for (var pi = 0; pi < doc.pages.length; pi++) {
                        var _pg = doc.pages[pi];
                        var _pb = _pg.bounds;
                        if (_cy >= _pb[0] && _cy <= _pb[2] && _cx >= _pb[1] && _cx <= _pb[3]) {
                            parentPage = _pg;
                            break;
                        }
                    }
                }
            } catch (e) {}
        }
        if (!parentPage) continue;
        var pgIdx = parentPage.documentOffset + 1;
        if (pgIdx < startPage || pgIdx > endPage) continue;
        if (skipRenderPagesMap[pgIdx]) continue; // SPEC-030 B.2

        if (isTextFrame && classifyTextFrameCached(item) !== "renderable") continue;

        var renderTarget = item;
        var parentItem = null;
        try {
            parentItem = item.parent;
            var pName = parentItem ? parentItem.constructor.name : "";
            if (parentItem && parentItem.id
                && (pName === "Rectangle" || pName === "Polygon"
                    || pName === "GraphicLine" || pName === "Oval")) {
                renderTarget = parentItem;
            }
            if (hasTextPath && pName === "Group" && parentItem && parentItem.id) {
                renderTarget = parentItem;
            }
        } catch (e) {}

        var domId = renderTarget.id;
        if (renderedIds[domId]) {
            if (renderTarget !== item) {
                renderedFrames.push({
                    id: item.id,
                    file: renderedIds[domId].file,
                    bounds: renderedIds[domId].bounds,
                    pageIndex: renderedIds[domId].pageIndex
                });
            }
            continue;
        }
        var fileName = "frame_" + domId + ".png";
        var outFile = File(renderDir + "/" + fileName);

        // SPEC-023: renderable TextFrame 의 배경 도형 검색
        // (renderTarget 이 이미 부모 컨테이너로 바뀐 경우는 건너뜀 — 컨테이너에 이미 배경이 포함됨)
        var bgItem = null;
        if (isTextFrame && renderTarget === item) {
            bgItem = findRenderableTextBackground(item, bgCandidatesPass2);
        }

        // renderTarget이 컨테이너(부모)이면 그 안의 editable TextFrame을
        // 임시로 숨겨서 PNG에 본문 텍스트가 중복 캡처되는 것을 방지.
        var hiddenEditable = [];
        if (renderTarget !== item) {
            try {
                var nestedItems = renderTarget.allPageItems;
                for (var nei = 0; nei < nestedItems.length; nei++) {
                    var nItem = nestedItems[nei];
                    try {
                        if (nItem.constructor.name === "TextFrame"
                            && editableFrameIds[nItem.id]
                            && nItem.visible) {
                            nItem.visible = false;
                            hiddenEditable.push(nItem);
                        }
                    } catch (eHide) {}
                }
            } catch (eHideAll) {}
        }
        // renderTarget !== item: non-editable TF 가 부모 컨테이너로 렌더링되는 경우
        // Java 파이프라인이 이 TF 를 텍스트박스로 재배치 → 부모 PNG 에 텍스트 중복 방지를 위해 숨김.
        if (renderTarget !== item && isTextFrame && !editableFrameIds[item.id]) {
            try {
                if (item.visible) {
                    item.visible = false;
                    hiddenEditable.push(item);
                }
            } catch (eRotHide) {}
        }

        try {
            // SPEC-023: 배경 도형이 있으면 별도 PNG 로 추가 render
            // (TF 와 bg 를 한 PNG 로 합치는 방식은 Read 같은 케이스에서 텍스트가 누락되어 비신뢰)
            // → 두 개의 floating 이미지로 등록, z-order 로 자연스럽게 겹침.
            if (bgItem) {
                var bgFileName = "frame_" + bgItem.id + ".png";
                var bgOutFile = File(renderDir + "/" + bgFileName);
                if (!renderedIds[bgItem.id]) {
                    try {
                        bgItem.exportFile(ExportFormat.PNG_FORMAT, bgOutFile);
                        var bgVbBounds = null;
                        try { bgVbBounds = arrCopy(bgItem.visibleBounds); } catch (e) {}
                        if (!bgVbBounds) { try { bgVbBounds = arrCopy(bgItem.geometricBounds); } catch (e) {} }
                        if (bgVbBounds) _toPageRelativeBounds(bgVbBounds, parentPage);
                        // bg 의 실제 allItems index 를 찾아 정확한 z-order 할당
                        var bgIndex = i + 100;
                        for (var bii = i + 1; bii < allItems.length; bii++) {
                            if (allItems[bii] && allItems[bii].id === bgItem.id) { bgIndex = bii; break; }
                        }
                        var bgEntry = {
                            id: bgItem.id,
                            file: "rendered_frames/" + bgFileName,
                            bounds: bgVbBounds,
                            pageIndex: parentPage.documentOffset,
                            zOrder: bgIndex
                        };
                        renderedFrames.push(bgEntry);
                        renderedIds[bgItem.id] = bgEntry;
                    } catch (eBgEx) {}
                }
                // page_bg 에서 bg 도형 숨김
                badgeGroupChildIds[bgItem.id] = true;
            }

            renderTarget.exportFile(ExportFormat.PNG_FORMAT, outFile);

            var bounds = null;
            try { bounds = arrCopy(renderTarget.visibleBounds); } catch (e) {}
            if (!bounds) {
                try { bounds = arrCopy(renderTarget.geometricBounds); } catch (e) {}
            }

            if (bounds) _toPageRelativeBounds(bounds, parentPage);

            // InDesign allPageItems 순서: index 0 = 맨 앞. 큰 값일수록 뒤.
            // HWPX zOrder와 방향이 반대이므로 Phase7에서 역매핑.
            var entry = {
                id: domId,
                file: "rendered_frames/" + fileName,
                bounds: bounds,
                pageIndex: parentPage.documentOffset,
                zOrder: i
            };
            renderedFrames.push(entry);
            renderedIds[domId] = entry;

            if (renderTarget !== item) {
                renderedFrames.push({
                    id: item.id,
                    file: "rendered_frames/" + fileName,
                    bounds: bounds,
                    pageIndex: parentPage.documentOffset,
                    zOrder: i
                });
            }
        } catch (e) {}

        // 숨긴 editable TextFrame 복원
        for (var rh = 0; rh < hiddenEditable.length; rh++) {
            try { hiddenEditable[rh].visible = true; } catch (eR) {}
        }
    }

    // SPEC-030 B.1: crop manifest 저장 (Rust 측 sips 크롭 대상 목록)
    if (_cropManifest.length > 0) {
        try {
            var _cmFile = File(outputDir + "/_crop_manifest.json");
            _cmFile.open("w");
            _cmFile.write(JSON.stringify(_cropManifest));
            _cmFile.close();
        } catch (eCm) {}
    }

    return { frames: renderedFrames, badgeChildIds: badgeGroupChildIds };
}

// --- 렌더링 시 TextFrame 텍스트 제거 헬퍼 ---

function hideTextFrames(renderTarget) {
    // tf.visible = false 방식: 문자별 color 저장 없이 TF 자체를 숨김.
    // 이전 per-character fillColor 방식은 수천 개 charData 객체를 JS 힙에 쌓아
    // 57회 반복 후 JS 힙 고갈로 InDesign 크래시를 유발했음.
    var saved = [];
    try {
        var nested = renderTarget.allPageItems;
        for (var hi = 0; hi < nested.length; hi++) {
            if (nested[hi].constructor.name === "TextFrame") {
                try {
                    var tf = nested[hi];
                    var wasVisible = tf.visible;
                    tf.visible = false;
                    saved.push({ tf: tf, wasVisible: wasVisible });
                } catch (e) {}
            }
        }
    } catch (e) {}
    return saved;
}

function restoreTextFrames(saved) {
    for (var ri = 0; ri < saved.length; ri++) {
        try {
            saved[ri].tf.visible = saved[ri].wasVisible;
        } catch (e) {}
    }
}

/**
 * renderTarget 자손 중 badge_group인 Group을 숨긴다.
 * deco 그룹 렌더링 시 badge PNG가 이중으로 포함되지 않도록 하기 위함.
 * badge_group 자체의 visible을 false로 설정하고 복원 정보를 반환.
 */
function hideBadgeGroupDescendants(renderTarget) {
    var saved = [];
    try {
        var nested = renderTarget.allPageItems;
        for (var i = 0; i < nested.length; i++) {
            var item = nested[i];
            if (item.constructor.name !== "Group") continue;
            if (!isBadgeGroup(item)) continue;
            try {
                var wasVisible = item.visible;
                item.visible = false;
                saved.push({ item: item, wasVisible: wasVisible });
            } catch (e) {}
        }
    } catch (e) {}
    return saved;
}

function restoreBadgeGroupDescendants(saved) {
    for (var i = 0; i < saved.length; i++) {
        try {
            saved[i].item.visible = saved[i].wasVisible;
        } catch (e) {}
    }
}

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

            var hiddenTFs = hideTextFrames(item);
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
        }

        if (_gExportOk) {
            renderedGraphicFrames.push({
                id: domId,
                file: "rendered_frames/" + fileName,
                bounds: _gBounds,
                pageIndex: _gPageIdx,
                childIds: _gChildIds.length > 0 ? _gChildIds : undefined,
                zOrder: _gZOrder
            });
        }
    }

    return renderedGraphicFrames;
}

/**
 * PDF 배치 프레임을 PNG로 렌더링한다.
 */
function exportPdfPlacedFrames(doc, outputDir, startPage, endPage, allItems) {
    var renderDir = Folder(outputDir + "/rendered_frames");
    renderDir.create();

    var renderedPdfFrames = [];

    for (var i = 0; i < allItems.length; i++) {
        var item = allItems[i];
        var cName = item.constructor.name;

        if (cName !== "Rectangle" && cName !== "Oval"
            && cName !== "Polygon" && cName !== "GraphicLine") continue;
        if (isOnHiddenLayer(item)) continue;

        var hasPdf = false;
        try { hasPdf = item.pdfs && item.pdfs.length > 0; } catch (e) {}
        if (!hasPdf) continue;

        var parentPage = null;
        try { parentPage = item.parentPage; } catch (e) {}
        if (!parentPage) continue;
        var pgIdx = parentPage.documentOffset + 1;
        if (pgIdx < startPage || pgIdx > endPage) continue;

        var domId = item.id;
        var fileName = "pdf_" + domId + ".png";
        var outFile = File(renderDir + "/" + fileName);

        try {
            item.exportFile(ExportFormat.PNG_FORMAT, outFile);

            var bounds = null;
            try { bounds = arrCopy(item.visibleBounds); } catch (e) {}
            if (!bounds) {
                try { bounds = arrCopy(item.geometricBounds); } catch (e) {}
            }

            if (bounds) _toPageRelativeBounds(bounds, parentPage);

            renderedPdfFrames.push({
                id: domId,
                file: "rendered_frames/" + fileName,
                bounds: bounds,
                pageIndex: parentPage.documentOffset
            });
        } catch (e) {}
    }

    return renderedPdfFrames;
}

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

        var hasImage = false;
        try { hasImage = item.images && item.images.length > 0; } catch (e) {}
        if (!hasImage) continue;

        var hasPdf = false;
        try { hasPdf = item.pdfs && item.pdfs.length > 0; } catch (e) {}
        if (hasPdf) continue;

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

        if (!isGroupRender) {
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
                    renderedImageFrames.push({
                        id: domId,
                        file: "rendered_frames/" + dstFileName,
                        imageFormat: srcExt,
                        bounds: bounds,
                        pageIndex: parentPage.documentOffset,
                        childImageIds: null
                    });
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
            var hiddenTFs = isGroupRender ? hideTextFrames(renderTarget) : [];

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
        }

        // push는 try 블록 밖에서 실행 — restore/gc 예외가 발생해도 반드시 등록.
        if (_imgExportOk) {
            // 그룹 항목을 배경 폴리곤보다 먼저 등록
            // BackgroundInjector.addBlockAtFront 특성상 나중 등록 항목이 XML 앞에 위치 →
            // 그룹(일러스트)이 폴리곤(S자 밴드) 위에 렌더링됨
            renderedImageFrames.push({
                id: domId,
                file: "rendered_frames/" + fileName,
                bounds: bounds,
                pageIndex: _imgPageIdx,
                childImageIds: _imgChildIds,
                zOrder: _imgZOrder
            });

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
                    renderedImageFrames.push({
                        id: bgPolyDomId,
                        file: "rendered_frames/" + bgPolyFileName,
                        bounds: bgPolyBounds,
                        pageIndex: _imgPageIdx,
                        childImageIds: null,
                        zOrder: getItemZOrder(bgPoly)
                    });
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
function exportImagePlacedFramesSafe(doc, outputDir, startPage, endPage, allItems) {
    // InDesign이 특정 이미지 프레임의 .images 접근에서 C++ 크래시(SIGSEGV)를
    // 일으키는 문서가 있으므로, 이미지 프레임 렌더링은 건너뛴다.
    // Java 변환기가 IDML 링크에서 직접 이미지를 처리한다.
    return [];
}

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
function exportDecorationGroups(doc, outputDir, startPage, endPage, badgeChildIds, allItems, imgRenderedIds) {
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
        if (badgeChildIds && badgeChildIds[id]) return true;
        if (imgRenderedIds && imgRenderedIds[id]) return true;
        try {
            var par = item.parent;
            if (par && (renderedIds[par.id] || decoChildIds[par.id])) return true;
        } catch (e) {}
        return false;
    }

    // PNG 렌더 + results 등록 (자식 claim 포함)
    function _decoRender(item, page, childIdMap) {
        var domId = item.id;
        var fileName = "deco_" + domId + ".png";
        item.exportFile(ExportFormat.PNG_FORMAT, File(renderDir + "/" + fileName));

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

        var entry = { id: domId, file: "rendered_frames/" + fileName, bounds: bounds, pageIndex: page.documentOffset, zOrder: getItemZOrder(item) };
        if (childIds.length > 0) entry.childIds = childIds;
        results.push(entry);
        renderedIds[domId] = true;
        return childIds;
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
                    // 내부에 배지 그룹이 있으면 배지 렌더링 경로가 따로 처리 → textComposite 금지
                    var hasBadgeChild = false;
                    for (var kb = 0; kb < nested.length; kb++) {
                        if (nested[kb].constructor.name === "Group" && isBadgeGroup(nested[kb])) {
                            hasBadgeChild = true; break;
                        }
                    }
                    if (noTableInTfs && !hasBadgeChild) return "textComposite";
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
                    results.push({
                        id: _sh.id,
                        file: "rendered_frames/" + _wFile,
                        bounds: _wBounds,
                        pageIndex: grpPage.documentOffset,
                        whiteStroke: true,
                        zOrder: getItemZOrder(_sh)
                    });
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
        if (badgeChildIds && badgeChildIds[domId]) continue;

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
                if (par && par.constructor.name === "Group"
                        && !renderedIds[par.id] && !(badgeChildIds && badgeChildIds[par.id])) {
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

            if (kind === "mixedGroup") {
                // TF를 숨기고 도형만 PNG로 렌더 — TF 텍스트는 기존 파이프라인이 처리
                var savedTFs = hideTextFrames(grp);
                var savedBadges = hideBadgeGroupDescendants(grp);
                _decoRender(grp, grpPage, null);
                restoreTextFrames(savedTFs);
                restoreBadgeGroupDescendants(savedBadges);
                // Color/Paper (흰색) 획 도형은 투명배경 PNG에서 소실 → 검은색으로 임시 변환 후 개별 추출
                _exportPaperStrokeShapes(grp, grpPage);
            } else {
                // badge_group 자손을 숨기고 렌더 — badge PNG는 별도 exportRenderedTextFrames에서 처리
                var savedBadges = hideBadgeGroupDescendants(grp);
                _decoRender(grp, grpPage, null);
                restoreBadgeGroupDescendants(savedBadges);
            }
        } catch (e) {}

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
function exportVectorShapeFrames(doc, outputDir, startPage, endPage, badgeChildIds, decoChildIds, allItems) {
    var renderDir = Folder(outputDir + "/rendered_frames");
    renderDir.create();

    var results = [];
    var renderedIds = {};

    // 도형 1개 개별 export 헬퍼
    function _exportSingleShape(item, domId, parentPage) {
        try {
            var fileName = "shape_" + domId + ".png";
            var outFile = File(renderDir + "/" + fileName);
            item.exportFile(ExportFormat.PNG_FORMAT, outFile);
            var bounds = null;
            try { bounds = arrCopy(item.visibleBounds); } catch (e) {}
            if (!bounds) try { bounds = arrCopy(item.geometricBounds); } catch (e) {}
            if (bounds) _toPageRelativeBounds(bounds, parentPage);
            results.push({ id: domId, file: "rendered_frames/" + fileName, bounds: bounds, pageIndex: parentPage.documentOffset, zOrder: getItemZOrder(item) });
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
        if (badgeChildIds && badgeChildIds[domId]) continue;
        if (decoChildIds && decoChildIds[domId]) continue;

        var hasPlaced = false;
        try { hasPlaced = item.images && item.images.length > 0; } catch (e) {}
        if (!hasPlaced) try { hasPlaced = item.pdfs && item.pdfs.length > 0; } catch (e) {}
        if (!hasPlaced) try { hasPlaced = item.epss && item.epss.length > 0; } catch (e) {}
        if (hasPlaced) continue;

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
    // Strategy v6: 임시 신규 페이지에서 마스터 override → PNG 내보내기.
    //
    // v5 문제: overriddenMasterPageItem 속성이 ExtendScript에서 항상 throw →
    //   try-catch가 null로 처리 → 콘텐츠 페이지 422 아이템 중 overriddenItems: 0.
    //
    // v6 전략:
    //   - 각 마스터 스프레드별로 doc 끝에 임시 페이지 추가
    //   - 마스터 스프레드 적용 → 비-TF 최상위 마스터 아이템을 임시 페이지에 override()
    //   - 새 페이지이므로 "already locally overridden" 오류 없이 전부 성공
    //   - 그룹 + exportFile → PNG
    //   - 임시 페이지 remove() → override 아이템 자동 삭제 (문서 복원)
    //   - 그룹 bounds는 임시 페이지 기준 상대 좌표로 저장
    //     (모든 동일 마스터 페이지는 동일 하시라 레이아웃 → 공유 가능)

    var debugLog = File(outputDir + "/_master_debug.log");
    try { debugLog.open("w"); } catch (e) { debugLog = null; }
    function dbg(msg) {
        $.writeln("[exportMasterPageGraphics] " + msg);
        if (debugLog) try { debugLog.writeln(msg); } catch (e) {}
    }

    dbg("START v10 startPage=" + startPage + " endPage=" + endPage + " docPages=" + doc.pages.length);

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

    // msId + "_" + masterPageIdx → { relPath, relTop, relLeft, relBottom, relRight }
    // 마스터 스프레드의 좌/우 페이지별로 분리 export
    var exportedMasterByPage = {};

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
                    if (mItem.constructor.name === "TextFrame") continue;
                    if (mItem.constructor.name === "Group") continue;
                    var mHasTF = false;
                    try {
                        var mSubs = mItem.allPageItems;
                        for (var msi = 0; msi < mSubs.length; msi++) {
                            if (mSubs[msi].constructor.name === "TextFrame") { mHasTF = true; break; }
                        }
                    } catch (eMSub) {}
                    if (mHasTF) continue;
                    masterGraphicItems.push(mItem);
                } catch (e) {}
            }
        } catch (e) { dbg("  ERROR allPageItems: " + e); }
        dbg("  masterGraphicItems: " + masterGraphicItems.length);
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

            // 이 마스터 페이지에 속하는 아이템: 아이템 중심 X가 페이지 left~right 범위 내
            var pageItems = [];
            for (var pii = 0; pii < masterGraphicItems.length; pii++) {
                try {
                    var pib = masterGraphicItems[pii].geometricBounds;
                    var centerX = (pib[1] + pib[3]) / 2;
                    if (centerX >= mpBnds[1] - 5 && centerX <= mpBnds[3] + 5) {
                        pageItems.push({ item: masterGraphicItems[pii], bounds: pib });
                    }
                } catch (e) {}
            }
            dbg("  masterPage[" + mpIdx + "] items: " + pageItems.length);
            if (pageItems.length === 0) { exportedMasterByPage[masterKey] = null; continue; }

            var bestItem = null, bestArea = 0, grpBnds = null;
            for (var li = 0; li < pageItems.length; li++) {
                try {
                    var lb = pageItems[li].bounds;
                    var la = Math.abs((lb[2] - lb[0]) * (lb[3] - lb[1]));
                    dbg("  item[" + li + "] " + pageItems[li].item.constructor.name +
                        " bounds=[" + lb.join(",") + "] area=" + la.toFixed(1));
                    if (la > bestArea) { bestArea = la; bestItem = pageItems[li].item; grpBnds = lb; }
                } catch (eLi) {}
            }
            if (!bestItem) { exportedMasterByPage[masterKey] = null; continue; }

            var mFileName = "master_" + msId + "_" + mpIdx + ".png";
            var mOutFile = File(renderDir + "/" + mFileName);
            try { bestItem.exportFile(ExportFormat.PNG_FORMAT, mOutFile, false); } catch (eExp) {
                dbg("  export error: " + eExp);
            }
            if (!mOutFile.exists) {
                dbg("  FAIL: " + mFileName + " not created");
                exportedMasterByPage[masterKey] = null;
                continue;
            }
            dbg("  SUCCESS: " + mFileName + " (" + mOutFile.length + " bytes)");
            exportedMasterByPage[masterKey] = {
                relPath:   "rendered_frames/" + mFileName,
                relTop:    grpBnds[0] - mpBnds[0],
                relLeft:   grpBnds[1] - mpBnds[1],
                relBottom: grpBnds[2] - mpBnds[0],
                relRight:  grpBnds[3] - mpBnds[1]
            };
            dbg("  relBounds: ["+exportedMasterByPage[masterKey].relTop+","+exportedMasterByPage[masterKey].relLeft+
                ","+exportedMasterByPage[masterKey].relBottom+","+exportedMasterByPage[masterKey].relRight+"]");
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
            var master = exportedMasterByPage[masterKey2];
            if (!master) continue;
            if ((master.relRight - master.relLeft) <= 0) continue;
            dbg("  result docIdx=" + pgEntry.docIdx + " masterPageIdx=" + pgEntry.masterPageIdx +
                " rel=["+master.relTop+","+master.relLeft+","+master.relBottom+","+master.relRight+"]");
            results.push({
                id: parseInt(msId2, 10),
                file: master.relPath,
                bounds: [master.relTop, master.relLeft, master.relBottom, master.relRight],
                pageIndex: pgEntry.docIdx,
                zOrder: 0,
                isMasterGraphic: true
            });
        }
    }

    dbg("DONE: " + results.length + " master graphic entries");
    if (debugLog) try { debugLog.close(); } catch (e) {}
    $.writeln("[exportMasterPageGraphics] " + results.length + " entries (v6 temp-page)");
    return results;
}


// =============================================================================
// SECTION 6: RESOLVED COLLECTOR
// resolved.json 수집 — 스토리 / TF / 마스터 인스턴스화 / 페이지 / 아이템
// =============================================================================

function collectResolved(doc, outputDir, rangePageCount, startPage, endPage, editableIds, skipRenderPagesMap) {
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
    var textFrames = collectTextFrames(doc, startPage, endPage, editableIds, skipRenderPagesMap);

    // SPEC-025 Phase 5: 마스터 스프레드 TextFrame 을 적용 페이지마다 인스턴스화 (frame + story clone)
    // 증분 추출에서도 실행 (마스터 인스턴스는 경량 연산)
    try { instanceMasterFrames(doc, startPage, endPage, textFrames, stories, editableIds); } catch (ePhase5) { $.writeln("[SPEC-025 Phase 5 error] " + ePhase5); }

    writeProgress(outputDir, "resolved_items", 0, rangePageCount);
    var pages = collectPages(doc, startPage, endPage, skipRenderPagesMap);
    var pageItems = collectPageItems(doc, startPage, endPage, skipRenderPagesMap);

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

    // 테이블+인라인 TextFrame 별도 렌더링:
    // 테이블 셀에 인라인 객체(Group/Rectangle 등)가 포함된 TextFrame을 통째로 PNG로
    // 렌더링하여 renderedFloatingItems에 추가한다.
    //
    // SPEC-018: 트리거 조건 강화 (점검 내용 테이블 등 편집성 손실 방지)
    //   - 셀 텍스트 임계값 5: "예/아니요" 같은 라벨 셀은 텍스트로 인정.
    //   - 본문 텍스트 셀(임계값 이상)이 TEXT_CELL_FLOOR개 이상이면 fallback 안 함.
    //     점검 내용 표는 본문 셀 9개라서 안전하게 ASTTable 경로 유지.
    //   - 보조 조건: 인라인 셀 비율 70% 이상일 때만 fallback (옵션 안전 가드)
    //   - 셀 안 인라인 객체는 inlineObjects 메커니즘이 별도 PNG로 추출하고
    //     Java TableBuilder.extractCellInlines가 floating ASTFigure로 분리한다.
    var TABLE_INLINE_TEXT_THRESHOLD = 5;
    var TABLE_INLINE_RATIO = 0.7;
    var TABLE_TEXT_CELL_FLOOR = 4; // 본문 텍스트 셀이 이 수 이상이면 fallback 안 함
    var tableInlineRendered = [];  // { id, file, bounds, pageIndex, type }
    for (var ti = 0; ti < editableFrames.length; ti++) {
        var tTf = editableFrames[ti];
        try {
            var tStory = tTf.parentStory;
            // 테이블이 있는지
            if (!tStory.tables || tStory.tables.length === 0) continue;
            var inlineCellCount = 0;
            var textCellCount = 0;
            var totalCellCount = 0;
            for (var tbi = 0; tbi < tStory.tables.length; tbi++) {
                var tbl = tStory.tables[tbi];
                for (var tci = 0; tci < tbl.cells.length; tci++) {
                    var tCell = tbl.cells[tci];
                    totalCellCount++;
                    var cellText = "";
                    try { cellText = tCell.contents.replace(/\uFFFC/g, "").replace(/[\r\n]/g, ""); } catch(e) {}
                    if (cellText.length >= TABLE_INLINE_TEXT_THRESHOLD) {
                        textCellCount++;
                        continue;
                    }
                    var cellHasInline = false;
                    try {
                        var cellItems = tCell.allPageItems;
                        for (var cii = 0; cii < cellItems.length; cii++) {
                            var cn = cellItems[cii].constructor.name;
                            if (cn === "Group" || cn === "Rectangle" || cn === "Polygon" || cn === "Oval") {
                                cellHasInline = true;
                                break;
                            }
                        }
                    } catch(e) {}
                    if (cellHasInline) inlineCellCount++;
                }
            }
            // 본문 텍스트 셀이 충분하면 (점검 내용 등) fallback 안 함
            if (textCellCount >= TABLE_TEXT_CELL_FLOOR) continue;
            // 인라인 셀 비율 70% 이상에서만 fallback 발동
            var hasTableInline = totalCellCount > 0
                && (inlineCellCount / totalCellCount) >= TABLE_INLINE_RATIO;
            if (!hasTableInline) continue;

            // 이 TextFrame을 통째로 PNG로 렌더링
            var tDomId = tTf.id;
            var tOutFile = File(outputDir + "/rendered_frames/table_" + tDomId + ".png");
            try {
                tTf.exportFile(ExportFormat.PNG_FORMAT, tOutFile);
                if (tOutFile.exists) {
                    var tBounds = tTf.visibleBounds; // [top, left, bottom, right]
                    var tPageIdx = -1;
                    try { tPageIdx = tTf.parentPage.documentOffset; } catch(e) {}
                    if (tPageIdx >= 0 && startPage > 0) tPageIdx -= (startPage - 1);
                    tableInlineRendered.push({
                        id: tDomId,
                        file: "rendered_frames/table_" + tDomId + ".png",
                        bounds: [tBounds[0], tBounds[1], tBounds[2], tBounds[3]],
                        pageIndex: tPageIdx,
                        type: "table_inline"
                    });
                }
            } catch(e) {
            }
        } catch(e) {}
    }

    // 인라인 객체 추출: 편집 TextFrame의 Story에 앵커된 그래픽을 개별 PNG로 렌더
    var inlineObjects = [];  // { id, file, parentStoryId, bounds, pageIndex }
    var processedStoryIds = {};  // Story 중복 방지
    for (var ei = 0; ei < editableFrames.length; ei++) {
        var eTf = editableFrames[ei];
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
                // Group 내부의 TextFrame/Table 텍스트는 별도로 floating textbox/table로 배치되므로
                // 렌더링 PNG에는 텍스트를 제외 (배경 도형/테이블 셀 그리드만 캡처).
                // parentStory.texts[0].fillColor와 cell.texts[0].fillColor를 None Swatch로 잠시 변경 → export → 복원.
                var savedFills = []; // [{txt, fillColor, fillTint}]
                var noneSwatch = null;
                try { noneSwatch = doc.swatches.itemByName("None"); } catch (eNone) {}
                try {
                    // SPEC-020: Group 뿐 아니라 Rectangle/Polygon/Oval 컨테이너 안의
                    // TextFrame 텍스트도 PNG 렌더링 시 숨김 — 텍스트는 변환 결과에서
                    // 별도 오버레이되므로 PNG에 같이 그려지면 이중 렌더링이 발생한다.
                    var containerType = inItem.constructor.name;
                    var shouldHideText = (containerType === "Group"
                            || containerType === "Rectangle"
                            || containerType === "Polygon"
                            || containerType === "Oval")
                            && noneSwatch && noneSwatch.isValid;
                    var inlineConsumedChildIds = [];
                    if (shouldHideText) {
                        // 컨테이너에 채움/테두리 색이 있는 "버튼/배지" 형태일 때만 라벨 보존 적용.
                        // (수식 그룹 처럼 투명한 컨테이너 안의 짧은 텍스트는 일반 글상자로 처리해야 함)
                        var isButtonContainer = false;
                        try {
                            var bcFill = inItem.fillColor ? inItem.fillColor.name : "None";
                            var bcStroke = inItem.strokeColor ? inItem.strokeColor.name : "None";
                            var bcSW = inItem.strokeWeight || 0;
                            isButtonContainer = (bcFill !== "None" && bcFill !== "[None]")
                                || ((bcStroke !== "None" && bcStroke !== "[None]") && bcSW > 0);
                        } catch (e) {}
                        if (!isButtonContainer && containerType === "Group") {
                            try {
                                // 직속 자식(pageItems)이 Group만 있는 경우 중첩 배지를 놓칠 수 있으므로
                                // allPageItems 로 전체 후손을 확인 (예: 그룹→그룹→Rectangle 구조)
                                var gpItems = inItem.allPageItems;
                                for (var gpi = 0; gpi < gpItems.length; gpi++) {
                                    var gpIt = gpItems[gpi];
                                    var gpCn = gpIt.constructor.name;
                                    if (gpCn !== "Rectangle" && gpCn !== "Polygon" && gpCn !== "Oval") continue;
                                    var gpFill = "None", gpStroke = "None", gpSW = 0;
                                    try { gpFill = gpIt.fillColor ? gpIt.fillColor.name : "None"; } catch (e) {}
                                    try { gpStroke = gpIt.strokeColor ? gpIt.strokeColor.name : "None"; } catch (e) {}
                                    try { gpSW = gpIt.strokeWeight || 0; } catch (e) {}
                                    if ((gpFill !== "None" && gpFill !== "[None]")
                                        || ((gpStroke !== "None" && gpStroke !== "[None]") && gpSW > 0)) {
                                        isButtonContainer = true;
                                        break;
                                    }
                                }
                            } catch (e) {}
                        }
                        var groupItems = inItem.allPageItems;
                        for (var gki = 0; gki < groupItems.length; gki++) {
                            var gki_it = groupItems[gki];
                            if (gki_it.constructor.name === "TextFrame") {
                                // 짧은 라벨(예: "After You Read", "Click Me" 등 ≤ 20자) 은
                                // 컨테이너의 시각적 일부 → PNG 에 포함시킴 (텍스트 위치가 버튼/배지 디자인의 핵심).
                                var labelTxt = "";
                                try { labelTxt = (gki_it.contents + "").replace(/[\s﻿\r\n￼]/g, ""); } catch (e) {}
                                if (isButtonContainer && labelTxt.length > 0 && labelTxt.length <= 20) {
                                    inlineConsumedChildIds.push(gki_it.id);
                                    continue;  // hide 하지 않음
                                }
                                try {
                                    var st = gki_it.parentStory;
                                    if (st && st.texts.length > 0) {
                                        var txt = st.texts[0];
                                        savedFills.push({txt: txt, fillColor: txt.fillColor, fillTint: txt.fillTint});
                                        txt.fillColor = noneSwatch;
                                    }
                                    // 테이블 셀 텍스트도 숨김 (story.tables[].cells[].texts)
                                    if (st && st.tables && st.tables.length > 0) {
                                        for (var ti = 0; ti < st.tables.length; ti++) {
                                            var tbl = st.tables[ti];
                                            try {
                                                for (var ci = 0; ci < tbl.cells.length; ci++) {
                                                    var cell = tbl.cells[ci];
                                                    try {
                                                        if (cell.texts.length > 0) {
                                                            var ctx = cell.texts[0];
                                                            savedFills.push({txt: ctx, fillColor: ctx.fillColor, fillTint: ctx.fillTint});
                                                            ctx.fillColor = noneSwatch;
                                                        }
                                                    } catch (eCell) {}
                                                }
                                            } catch (eTbl) {}
                                        }
                                    }
                                } catch (eHide) {}
                            }
                        }
                    }
                } catch (eWalk) {}
                try {
                    inItem.exportFile(ExportFormat.PNG_FORMAT, inOutFile);
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

                        inlineObjects.push({
                            id: inId,
                            file: "rendered_frames/" + inFileName,
                            parentStoryId: eStory.id.toString(),
                            bounds: inBounds,
                            pageIndex: inPageIdx,
                            type: "inline_object",
                            childIds: inlineConsumedChildIds
                        });
                    }
                } catch (eRender) {}
                // fillColor 복원
                for (var hri = 0; hri < savedFills.length; hri++) {
                    try {
                        savedFills[hri].txt.fillColor = savedFills[hri].fillColor;
                        savedFills[hri].txt.fillTint = savedFills[hri].fillTint;
                    } catch (eRestore) {}
                }
            }
        } catch (eInline) {}
    }

    // ── 페이지별 배경 렌더링 (PDF + PNG 폴백) ──
    // PDF 고해상도 배경을 직접 생성 (editable 판별 통일을 위해 extract_indd.jsx에서 수행)
    // PDF 설정 (고해상도 유지)
    try {
        app.pdfExportPreferences.exportReaderSpreads = false;
        try { app.pdfExportPreferences.colorBitmapSampling = Sampling.NONE; } catch(e1){}
        try { app.pdfExportPreferences.colorBitmapCompression = BitmapCompression.NONE; } catch(e2){}
        try { app.pdfExportPreferences.grayscaleBitmapSampling = Sampling.NONE; } catch(e3){}
        try { app.pdfExportPreferences.grayscaleBitmapCompression = BitmapCompression.NONE; } catch(e4){}
        try { app.pdfExportPreferences.monochromeBitmapSampling = Sampling.NONE; } catch(e5){}
        try { app.pdfExportPreferences.monochromeBitmapCompression = BitmapCompression.NONE; } catch(e6){}
    } catch (ePdfPref) {}

    // renderable TF와 부모 객체도 배경에서 숨김 대상에 추가
    // 단, 부모가 Group이고 여러 형제가 있으면 Group 전체를 숨기면 배경 도형이 사라지므로
    // 작은 래퍼 도형(Rectangle/Polygon/Oval)에 한해 부모 숨김을 수행한다.
    var renderableItems = [];
    for (var ri2 = 0; ri2 < allItems.length; ri2++) {
        try {
            if (allItems[ri2].constructor.name === "TextFrame") {
                var cls2 = classifyTextFrameCached(allItems[ri2]);
                if (cls2 === "renderable") {
                    renderableItems.push(allItems[ri2]);
                    try {
                        var rParent = allItems[ri2].parent;
                        if (rParent) {
                            var rParentName = rParent.constructor.name;
                            if (rParentName === "Rectangle" || rParentName === "Polygon"
                                || rParentName === "Oval" || rParentName === "GraphicLine") {
                                renderableItems.push(rParent);
                            }
                        }
                    } catch (e) {}
                }
            }
        } catch (e) {}
    }

    // 같은 story를 editable TF와 공유하는 non-editable TF도 배경에서 숨김 대상에 추가
    var editableStoryIds = {};
    for (var esi = 0; esi < editableFrames.length; esi++) {
        try {
            var esId = editableFrames[esi].parentStory.id;
            editableStoryIds[esId] = true;
        } catch (e) {}
    }
    var framesToHide = editableFrames.slice(0); // 복사
    for (var rhi = 0; rhi < renderableItems.length; rhi++) {
        framesToHide.push(renderableItems[rhi]);
    }
    for (var ai = 0; ai < allItems.length; ai++) {
        try {
            if (allItems[ai].constructor.name === "TextFrame"
                && !editableFrameIds[allItems[ai].id]
                && allItems[ai].parentStory
                && editableStoryIds[allItems[ai].parentStory.id]) {
                framesToHide.push(allItems[ai]);
            }
        } catch (e) {}
    }
    // 배지 그룹은 별도 PNG로 렌더링되어 위에 덮어씌워지므로 배경에서 숨김
    // (숨기지 않으면 배지가 배경에도 남고 덮어씌운 배지가 중복 표시됨)
    // 성능: SPEC-022 데코 검색은 Polygon/polygon-only Group 만 후보이므로
    // 미리 한 번 필터링한 슬림 배열을 사용 (큰 문서에서 O(N²) 회피).
    var decoCandidates = [];
    if (CONFIG.rendering.badge.decorationMergeEnabled) {
        for (var dci = 0; dci < allItems.length; dci++) {
            try {
                var dcItem = allItems[dci];
                var dcCn = dcItem.constructor.name;
                if (dcCn !== "Polygon" && dcCn !== "Group") continue;
                decoCandidates.push(dcItem);
            } catch (e) {}
        }
    }
    for (var bi = 0; bi < allItems.length; bi++) {
        try {
            var bItem = allItems[bi];
            if (bItem.constructor.name === "Group" && isBadgeGroupCached(bItem)) {
                framesToHide.push(bItem);
                // SPEC-022: 뱃지와 시각적으로 합쳐 렌더되는 외곽선 데코도 배경에서 숨김
                if (decoCandidates.length > 0) {
                    var ovDecos = findOverlappingDecorations(bItem, decoCandidates);
                    for (var ovi = 0; ovi < ovDecos.length; ovi++) {
                        framesToHide.push(ovDecos[ovi]);
                    }
                }
            }
        } catch (e) {}
    }
    // SPEC-021: 중첩 뱃지 페어 (도형+짧은 숫자 TF) 만 배경에서 숨김
    // 그룹 자체는 본문 질문 텍스트 등을 포함하므로 숨기면 안됨
    for (var nbi = 0; nbi < allItems.length; nbi++) {
        try {
            var nbItem = allItems[nbi];
            if (nbItem.constructor.name !== "Group") continue;
            // 이미 isBadgeGroup 통과한 그룹은 위에서 숨겨짐
            if (isBadgeGroupCached(nbItem)) continue;
            var nbPat = findNestedBadgePattern(nbItem);
            if (nbPat) {
                framesToHide.push(nbPat.shape);
                framesToHide.push(nbPat.textFrame);
            }
        } catch (e) {}
    }
    // TextPath(곡선 텍스트)가 Pass 2에서 frame_NNNN.png로 별도 렌더링되므로
    // 해당 부모 Group/Path는 배경에서 숨겨 중복 표시 방지.
    for (var tpi = 0; tpi < allItems.length; tpi++) {
        try {
            var tpItem = allItems[tpi];
            var tpHas = false;
            try { tpHas = tpItem.textPaths && tpItem.textPaths.length > 0; } catch (e) {}
            if (!tpHas) continue;
            // Pass 2 의 renderTarget 로직과 동일: 부모가 Group이면 Group 전체를 숨김
            var tpTarget = tpItem;
            try {
                var tpParent = tpItem.parent;
                if (tpParent && tpParent.constructor
                    && (tpParent.constructor.name === "Group"
                        || tpParent.constructor.name === "Rectangle"
                        || tpParent.constructor.name === "Polygon"
                        || tpParent.constructor.name === "Oval"
                        || tpParent.constructor.name === "GraphicLine")
                    && tpParent.id) {
                    tpTarget = tpParent;
                }
            } catch (e) {}
            framesToHide.push(tpTarget);
        } catch (e) {}
    }

    // SPEC-023: renderable TF 의 라운드 사각형 배경 도형도 배경에서 숨김 (별도 PNG 로 추출되므로 중복 방지)
    var bgCandidatesPb = [];
    for (var bcpb = 0; bcpb < allItems.length; bcpb++) {
        try {
            var bcpbIt = allItems[bcpb];
            var bcpbCn = bcpbIt.constructor.name;
            if (bcpbCn !== "Rectangle" && bcpbCn !== "Polygon" && bcpbCn !== "Oval") continue;
            var bcpbFc = null;
            try { bcpbFc = bcpbIt.fillColor; } catch (e) {}
            var bcpbFcName = "";
            try { if (bcpbFc) bcpbFcName = bcpbFc.name; } catch (e) {}
            if (!bcpbFcName || bcpbFcName === "None" || bcpbFcName === "[None]") continue;
            bgCandidatesPb.push(bcpbIt);
        } catch (e) {}
    }
    for (var rti = 0; rti < allItems.length; rti++) {
        try {
            var rtItem = allItems[rti];
            if (rtItem.constructor.name !== "TextFrame") continue;
            if (classifyTextFrameCached(rtItem) !== "renderable") continue;
            var rtBg = findRenderableTextBackground(rtItem, bgCandidatesPb);
            if (rtBg) framesToHide.push(rtBg);
        } catch (e) {}
    }

    // 페이지별 프레임 인덱스 미리 빌드 (O(pages × frames) → O(frames) + O(pages))
    var framesByPage = {};  // pageOffset → [frame, ...]
    var spreadFrames = [];  // parentPage 없는 프레임 (Spread 직속)
    var globalHide = [];    // SPEC-025: 마스터 스프레드 아이템 (모든 페이지 export 동안 숨김)
    for (var fi = 0; fi < framesToHide.length; fi++) {
        var efr = framesToHide[fi];
        try {
            var efPage = efr.parentPage;
            if (efPage) {
                // SPEC-025: 마스터 스프레드의 page → globalHide
                var efParent = null;
                try { efParent = efPage.parent; } catch (e2) {}
                if (efParent && efParent.constructor && efParent.constructor.name === "MasterSpread") {
                    globalHide.push(efr);
                    continue;
                }
                var efPgOff = efPage.documentOffset;
                if (!framesByPage[efPgOff]) framesByPage[efPgOff] = [];
                framesByPage[efPgOff].push(efr);
            } else {
                spreadFrames.push(efr);
            }
        } catch (e) {
            spreadFrames.push(efr);
        }
    }
    // SPEC-025: 마스터 스프레드 아이템을 PDF/PNG export 전 일괄 숨김
    var globalHidden = [];
    for (var ghi = 0; ghi < globalHide.length; ghi++) {
        try {
            if (globalHide[ghi].visible) {
                globalHide[ghi].visible = false;
                globalHidden.push(globalHide[ghi]);
            }
        } catch (e) {}
    }

    for (var pi = 0; pi < doc.pages.length; pi++) {
        var page = doc.pages[pi];
        var pgIdx = page.documentOffset + 1;
        if (pgIdx < startPage || pgIdx > endPage) continue;
        // SPEC-030 B.2: 변경되지 않은 페이지는 PNG/PDF 렌더링 스킵 (캐시에서 복사)
        if (skipRenderPagesMap[pgIdx]) {
            writeProgress(outputDir, "rendered_frames", pi, doc.pages.length);
            continue;
        }

        // 1. 해당 페이지의 편집 가능 텍스트 프레임 숨기기 (PDF/PNG 공통)
        var hiddenItems = [];
        // 이 페이지 소속 프레임
        var pageOwnFrames = framesByPage[page.documentOffset] || [];
        for (var hi = 0; hi < pageOwnFrames.length; hi++) {
            try {
                if (pageOwnFrames[hi].visible) {
                    pageOwnFrames[hi].visible = false;
                    hiddenItems.push(pageOwnFrames[hi]);
                }
            } catch (e) {}
        }
        // Spread 직속 프레임은 bounds 겹침 체크
        var pb = page.bounds;
        for (var si = 0; si < spreadFrames.length; si++) {
            var sf = spreadFrames[si];
            try {
                var sfb = sf.visibleBounds;
                var overlapH = sfb[3] > pb[1] && sfb[1] < pb[3];
                var overlapV = sfb[2] > pb[0] && sfb[0] < pb[2];
                if (overlapH && overlapV && sf.visible) {
                    sf.visible = false;
                    hiddenItems.push(sf);
                }
            } catch (e) {}
        }

        // 2. 숨긴 텍스트 프레임 복원 (inline_object 추출 후)
        for (var ri = 0; ri < hiddenItems.length; ri++) {
            try { hiddenItems[ri].visible = true; } catch (e) {}
        }

        writeProgress(outputDir, "rendered_frames", pi + 1, doc.pages.length);
    }

    // SPEC-025: 마스터 스프레드 아이템 visibility 복원
    for (var ghr = 0; ghr < globalHidden.length; ghr++) {
        try { globalHidden[ghr].visible = true; } catch (e) {}
    }

    // localDisplaySetting 복원
    for (var ri = 0; ri < savedLocalSettings.length; ri++) {
        try { savedLocalSettings[ri].item.localDisplaySetting = savedLocalSettings[ri].setting; } catch (e) {}
    }

    // Display Performance 복원
    if (savedDisplayPerf !== null) {
        try { doc.viewPreferences.displayPerformance = savedDisplayPerf; } catch (e) {}
    }

    // 인라인 객체를 결과에 추가
    for (var ioi = 0; ioi < inlineObjects.length; ioi++) {
        results.push(inlineObjects[ioi]);
    }
    if (inlineObjects.length > 0) {
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

function collectCharacterStyles(doc) {
    var styles = [];
    for (var i = 0; i < doc.allCharacterStyles.length; i++) {
        var cs = doc.allCharacterStyles[i];
        try {
            var data = {
                name: cs.name,
                basedOn: cs.basedOn ? cs.basedOn.name : null,
                fontFamily: null,
                fontStyle: cs.fontStyle,
                fontSize: cs.pointSize,
                underline: cs.underline,
                strikeThru: cs.strikeThru
            };
            try { data.fontFamily = cs.appliedFont ? cs.appliedFont.fontFamily : null; } catch (e) {}
            styles.push(data);
        } catch (e) {
            styles.push({ name: cs.name, error: e.message });
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
        // 디버그
        if (runText.indexOf("2x-3y") >= 0) {
            var dbgFile2 = File(Folder.temp + "/grep_dbg.txt");
            dbgFile2.open("w");
            dbgFile2.write("hasGrepStyles=" + hasGrepStyles + "\n");
            try {
                var ps2 = para.appliedParagraphStyle;
                dbgFile2.write("styleName=" + ps2.name + "\n");
                dbgFile2.write("nestedGrepStyles exists=" + (ps2.nestedGrepStyles != undefined) + "\n");
                dbgFile2.write("nestedGrepStyles length=" + ps2.nestedGrepStyles.length + "\n");
                for (var gi = 0; gi < Math.min(3, ps2.nestedGrepStyles.length); gi++) {
                    dbgFile2.write("  grep[" + gi + "] pattern=" + ps2.nestedGrepStyles[gi].grepExpression + "\n");
                }
            } catch (e4) {
                dbgFile2.write("error: " + e4.message + "\n");
            }
            dbgFile2.close();
        }

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

function collectTextFrames(doc, startPage, endPage, editableIds, skipRenderPagesMap) {
    if (!editableIds) editableIds = {};
    if (!skipRenderPagesMap) skipRenderPagesMap = {};
    var frames = [];
    var collectedStoryIds = {};  // 범위 내 프레임의 storyId 수집
    var collectedTfIds = {};     // 수집된 프레임 ID 추적

    try {
        // doc.textFrames는 페이지 소속 프레임만 포함할 수 있으므로
        // allPageItems에서 TextFrame을 수집하여 Spread 직속 프레임도 포함
        var allItems = doc.allPageItems;
        var tfs = [];
        for (var ai = 0; ai < allItems.length; ai++) {
            if (allItems[ai].constructor.name === "TextFrame") tfs.push(allItems[ai]);
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
    var s25 = null;
    try { s25 = _spec025(); } catch (e) {}
    if (!s25 || !(s25.masterPageEditable || s25.nonprintingEditable || s25.hashiraEditable)) return;

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
            // (_ctfCheckHashira 가 hashiraEditable=true 시 null 반환 → editable 경로로 빠짐)
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

function collectPageItems(doc, startPage, endPage, skipRenderPagesMap) {
    if (!skipRenderPagesMap) skipRenderPagesMap = {};
    var items = [];
    var allItems = doc.allPageItems;
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