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
var EXTRACT_SCRIPT_VERSION = "1";

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

// --- 전역 설정 ---
var CONFIG = null;

// --- conversion-config.json 로더 ---

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
            badge: { enabled: true, maxSize: 50, maxTextLength: 20, requireShape: true, allowImage: false, badgeDpi: 600, maxAspectRatio: 4.5, nestedEnabled: true, nestedMaxTextLength: 3, decorationMergeEnabled: true, decorationMergeMinOverlap: 0.5, decorationMergeAdjacency: 20 },
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
        // 얕은 병합: rendering 섹션만 사용
        if (parsed.rendering) {
            if (parsed.rendering.textFrame) {
                if (parsed.rendering.textFrame.maxTextLength !== undefined)
                    defaults.rendering.textFrame.maxTextLength = parsed.rendering.textFrame.maxTextLength;
                if (parsed.rendering.textFrame.decorativeLargeText) {
                    var dlt = parsed.rendering.textFrame.decorativeLargeText;
                    if (dlt.enabled !== undefined) defaults.rendering.textFrame.decorativeLargeText.enabled = dlt.enabled;
                    if (dlt.minFontSize !== undefined) defaults.rendering.textFrame.decorativeLargeText.minFontSize = dlt.minFontSize;
                    if (dlt.excludeBlack !== undefined) defaults.rendering.textFrame.decorativeLargeText.excludeBlack = dlt.excludeBlack;
                    if (dlt.blackThreshold !== undefined) defaults.rendering.textFrame.decorativeLargeText.blackThreshold = dlt.blackThreshold;
                }
                if (parsed.rendering.textFrame.decorativeStyledText) {
                    var dst = parsed.rendering.textFrame.decorativeStyledText;
                    if (dst.enabled !== undefined) defaults.rendering.textFrame.decorativeStyledText.enabled = dst.enabled;
                    if (dst.maxTextLength !== undefined) defaults.rendering.textFrame.decorativeStyledText.maxTextLength = dst.maxTextLength;
                    if (dst.excludeBlack !== undefined) defaults.rendering.textFrame.decorativeStyledText.excludeBlack = dst.excludeBlack;
                    if (dst.blackThreshold !== undefined) defaults.rendering.textFrame.decorativeStyledText.blackThreshold = dst.blackThreshold;
                    if (dst.requireObjectStyle !== undefined) defaults.rendering.textFrame.decorativeStyledText.requireObjectStyle = dst.requireObjectStyle;
                }
            }
            if (parsed.rendering.badge) {
                var b = parsed.rendering.badge;
                if (b.enabled !== undefined) defaults.rendering.badge.enabled = b.enabled;
                if (b.maxSize !== undefined) defaults.rendering.badge.maxSize = b.maxSize;
                if (b.maxTextLength !== undefined) defaults.rendering.badge.maxTextLength = b.maxTextLength;
                if (b.requireShape !== undefined) defaults.rendering.badge.requireShape = b.requireShape;
                if (b.allowImage !== undefined) defaults.rendering.badge.allowImage = b.allowImage;
                if (b.badgeDpi !== undefined) defaults.rendering.badge.badgeDpi = b.badgeDpi;
                if (b.maxAspectRatio !== undefined) defaults.rendering.badge.maxAspectRatio = b.maxAspectRatio;
                if (b.nestedEnabled !== undefined) defaults.rendering.badge.nestedEnabled = b.nestedEnabled;
                if (b.nestedMaxTextLength !== undefined) defaults.rendering.badge.nestedMaxTextLength = b.nestedMaxTextLength;
                if (b.decorationMergeEnabled !== undefined) defaults.rendering.badge.decorationMergeEnabled = b.decorationMergeEnabled;
                if (b.decorationMergeMinOverlap !== undefined) defaults.rendering.badge.decorationMergeMinOverlap = b.decorationMergeMinOverlap;
                if (b.decorationMergeAdjacency !== undefined) defaults.rendering.badge.decorationMergeAdjacency = b.decorationMergeAdjacency;
            }
            if (parsed.rendering.transparency) {
                var t = parsed.rendering.transparency;
                if (t.opacityThreshold !== undefined) defaults.rendering.transparency.opacityThreshold = t.opacityThreshold;
                if (t.tintThreshold !== undefined) defaults.rendering.transparency.tintThreshold = t.tintThreshold;
            }
            if (parsed.rendering.rotation) {
                if (parsed.rendering.rotation.minAngle !== undefined)
                    defaults.rendering.rotation.minAngle = parsed.rendering.rotation.minAngle;
            }
            if (parsed.rendering.pngExportResolution !== undefined)
                defaults.rendering.pngExportResolution = parsed.rendering.pngExportResolution;
            // SPEC-025 플래그
            if (parsed.rendering.textFrame && parsed.rendering.textFrame.spec025) {
                var s25 = parsed.rendering.textFrame.spec025;
                if (s25.masterPageEditable !== undefined)
                    defaults.rendering.textFrame.spec025.masterPageEditable = s25.masterPageEditable;
                if (s25.hashiraEditable !== undefined)
                    defaults.rendering.textFrame.spec025.hashiraEditable = s25.hashiraEditable;
                if (s25.rotationEditable !== undefined)
                    defaults.rendering.textFrame.spec025.rotationEditable = s25.rotationEditable;
                if (s25.nonprintingEditable !== undefined)
                    defaults.rendering.textFrame.spec025.nonprintingEditable = s25.nonprintingEditable;
                if (s25.inlineTextEditable !== undefined)
                    defaults.rendering.textFrame.spec025.inlineTextEditable = s25.inlineTextEditable;
                if (s25.inlineTextMaxLen !== undefined)
                    defaults.rendering.textFrame.spec025.inlineTextMaxLen = s25.inlineTextMaxLen;
                if (s25.groupShortTextEditable !== undefined)
                    defaults.rendering.textFrame.spec025.groupShortTextEditable = s25.groupShortTextEditable;
                if (s25.oneCharEditable !== undefined)
                    defaults.rendering.textFrame.spec025.oneCharEditable = s25.oneCharEditable;
                if (s25.decorativeLargeTextEditable !== undefined)
                    defaults.rendering.textFrame.spec025.decorativeLargeTextEditable = s25.decorativeLargeTextEditable;
                if (s25.decorativeStyledTextEditable !== undefined)
                    defaults.rendering.textFrame.spec025.decorativeStyledTextEditable = s25.decorativeStyledTextEditable;
                if (s25.boxLabelEditable !== undefined)
                    defaults.rendering.textFrame.spec025.boxLabelEditable = s25.boxLabelEditable;
            }
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

        var pxPerPt = dpi / 72.0;
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
                    childTextFrameIds: sb.childTextFrameIds
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

// --- 메인 ---

function main(args) {
    var inddPath = args[0];
    var outputDir = args[1];
    // 페이지 범위 (1-based, 0이면 전체)
    var startPage = parseInt(args[2], 10) || 0;
    var endPage = parseInt(args[3], 10) || 0;
    // 스프레드 모드 ("1"이면 PDF를 스프레드 단위로 내보냄)
    var spreadMode = (args[4] === "1");
    // PDF 전용 모드 ("1"이면 IDML/resolved 생략, PDF만 내보냄)
    var pdfOnly = (args[5] === "1");
    // conversion-config.json 경로 (선택적)
    var configPath = args[6] || null;
    // SPEC-030: 성능 모드 ("fast" | "standard" | "high"). 기본 "standard".
    // fast=PNG 150 DPI + preview.pdf 스킵, standard=220 DPI + PDF 포함, high=300 DPI + PDF 포함
    var perfMode = (args[7] || "standard").toLowerCase();
    // SPEC-030: preview.pdf 스킵 ("1" 이면 PDF 미생성). perfMode "fast" 의 기본 동작이지만 명시도 가능.
    var skipPdf = (args[8] === "1") || (perfMode === "fast");
    // SPEC-030 B.2: 렌더링 스킵할 1-based 페이지 인덱스 JSON 배열 (예: "[1,3,5]").
    // 변경되지 않은 페이지는 PNG 렌더링을 건너뛰고 캐시에서 복사한다.
    var skipRenderPagesRaw = args[9] || "";
    var skipRenderPagesMap = {};  // {pageIdx(1-based): true}
    if (skipRenderPagesRaw) {
        try {
            var _srp = JSON.parse(skipRenderPagesRaw);
            for (var _si = 0; _si < _srp.length; _si++) skipRenderPagesMap[_srp[_si]] = true;
        } catch (e) {}
    }
    // SPEC-030 B.2: "pre_scan" 모드 — 해시만 계산하고 렌더링 없이 종료.
    var extractMode = (args[10] || "full").toLowerCase();
    CONFIG = loadConversionConfig(configPath);
    // SPEC-030: perfMode 가 pngExportResolution 을 override (config 값 < CLI 값).
    if (perfMode === "fast") {
        CONFIG.rendering.pngExportResolution = 150;
    } else if (perfMode === "high") {
        CONFIG.rendering.pngExportResolution = 300;
    }
    // config 디버그 기록
    try {
        var cfgLog = File(outputDir + "/_config_jsx_debug.log");
        cfgLog.encoding = "UTF-8";
        cfgLog.open("w");
        cfgLog.writeln("configPath=" + configPath);
        cfgLog.writeln("perfMode=" + perfMode);
        cfgLog.writeln("skipPdf=" + skipPdf);
        cfgLog.writeln("pngExportResolution=" + CONFIG.rendering.pngExportResolution);
        var _s25 = CONFIG.rendering.textFrame && CONFIG.rendering.textFrame.spec025;
        cfgLog.writeln("spec025=" + (_s25 ? ("masterPageEditable=" + _s25.masterPageEditable + " hashiraEditable=" + _s25.hashiraEditable + " rotationEditable=" + _s25.rotationEditable + " nonprintingEditable=" + _s25.nonprintingEditable) : "MISSING"));
        cfgLog.close();
    } catch(e) {}

    // --- 기존 환경설정 저장 ---
    var savedInteractionLevel = app.scriptPreferences.userInteractionLevel;
    var savedEnableRedraw = app.scriptPreferences.enableRedraw;
    var savedCheckLinks = app.linkingPreferences.checkLinksAtOpen;
    var savedFindMissing = app.linkingPreferences.findMissingLinksAtOpen;

    var doc = null;
    try {
        // --- 다이얼로그 억제 (headless) ---
        // 누락 폰트, 링크 관련 팝업을 모두 자동 dismiss
        app.scriptPreferences.userInteractionLevel = UserInteractionLevels.NEVER_INTERACT;
        app.scriptPreferences.enableRedraw = false;
        app.linkingPreferences.checkLinksAtOpen = false;
        app.linkingPreferences.findMissingLinksAtOpen = false;

        _marker(outputDir, "00_start");
        writeProgress(outputDir, "open", 0, 0);

        // 0. 이전 배치에서 닫히지 않은 문서 정리
        try {
            while (app.documents.length > 0) {
                app.documents[0].close(SaveOptions.NO);
            }
        } catch (e) {}

        // 1. 문서 열기 (창 표시 안 함)
        _marker(outputDir, "01_open");
        var inddFile = File(inddPath);
        if (!inddFile.exists) {
            var parentFolder = inddFile.parent;
            if (!parentFolder || !parentFolder.exists) {
                throw new Error("상위 폴더에 접근할 수 없습니다: " + (parentFolder ? parentFolder.fsName : inddPath) + "\nmacOS 설정 > 개인정보 보호 > 파일 및 폴더에서 InDesign의 접근 권한을 확인해주세요.");
            } else {
                throw new Error("INDD 파일을 찾을 수 없습니다: " + inddPath);
            }
        }
        doc = app.open(inddFile, false);

        // 눈금자 원점을 SPREAD로 고정 (geometricBounds가 스프레드 전역 좌표가 되도록).
        // 문서별로 PAGE_ORIGIN/SPREAD_ORIGIN이 달라 pageRelativeBounds 계산이 음수가 되는 현상 방지.
        try {
            doc.viewPreferences.rulerOrigin = RulerOrigin.SPREAD_ORIGIN;
        } catch (eRuler) {}

        // 1.5. 링크 업데이트 (페이지 렌더링 전에 원본 이미지 연결)
        try {
            var inddParent = File(inddPath).parent;
            var linksFolders = [
                Folder(inddParent + "/Links"),
                Folder(inddParent)
            ];
            var fixedCount = 0;
            var missingCount = 0;
            for (var li = 0; li < doc.links.length; li++) {
                var link = doc.links[li];
                try {
                    if (link.status === LinkStatus.NORMAL) continue;
                    if (link.status === LinkStatus.LINK_OUT_OF_DATE) {
                        link.update();
                        fixedCount++;
                    } else if (link.status === LinkStatus.LINK_MISSING) {
                        var found = false;
                        for (var fi = 0; fi < linksFolders.length; fi++) {
                            if (!linksFolders[fi].exists) continue;
                            var linkFile = File(linksFolders[fi] + "/" + link.name);
                            if (linkFile.exists) {
                                link.relink(linkFile);
                                link.update();
                                fixedCount++;
                                found = true;
                                break;
                            }
                        }
                        if (!found) missingCount++;
                    }
                } catch (le) {}
            }
            if (fixedCount > 0 || missingCount > 0) {
                $.writeln("[Links] early fix: fixed=" + fixedCount + " missing=" + missingCount);
            }
        } catch (e) {}

        var pageCount = doc.pages.length;

        // 문서 페이지 번호 → 물리 인덱스 변환
        // cases.json의 pages는 문서 페이지 번호(예: "10-29")이므로
        // InDesign 페이지 이름과 비교하여 물리 인덱스로 변환
        if (startPage > 0 || endPage > 0) {
            for (var pi = 0; pi < pageCount; pi++) {
                var pageName = parseInt(doc.pages[pi].name, 10);
                if (!isNaN(pageName)) {
                    if (pageName === startPage) startPage = pi + 1;
                    if (pageName === endPage) endPage = pi + 1;
                }
            }
        }

        // 페이지 범위 보정
        if (startPage < 1) startPage = 1;
        if (endPage < 1 || endPage > pageCount) endPage = pageCount;
        var rangePageCount = endPage - startPage + 1;

        if (!pdfOnly) {
            _marker(outputDir, "02_idml_export");
            writeProgress(outputDir, "idml", 0, pageCount);

            // 2. IDML 내보내기 (전체 — API 제한)
            var idmlFile = File(outputDir + "/output.idml");
            doc.exportFile(ExportFormat.INDESIGN_MARKUP, idmlFile);

            // 2.5. allPageItems 1회 수집 (성능 최적화: 6회 → 1회)
            _marker(outputDir, "03_allPageItems");
            var allItems = doc.allPageItems;

            // SPEC-030 B.2: 페이지 해시 + 아이템 맵 계산 → page_hashes.json / page_item_map.json
            _marker(outputDir, "03b_pageHashes");
            try {
                var _pageData = buildPageData(doc, startPage, endPage, allItems);
                var _phFile = File(outputDir + "/page_hashes.json");
                _phFile.encoding = "UTF-8"; _phFile.open("w");
                _phFile.write(JSON.stringify(_pageData.hashes)); _phFile.close();
                var _pmFile = File(outputDir + "/page_item_map.json");
                _pmFile.encoding = "UTF-8"; _pmFile.open("w");
                _pmFile.write(JSON.stringify(_pageData.itemMap)); _pmFile.close();
            } catch (eHash) { $.writeln("[pageHash] error: " + eHash); }

            // SPEC-030 B.2: pre_scan 모드 — 해시/맵만 생성하고 렌더링 없이 종료
            if (extractMode === "pre_scan") {
                doc.close(SaveOptions.NO);
                doc = null;
                writeDone(outputDir, "ok", null);
                return;
            }

            // 2.6~2.11. 기존 개별 렌더 함수 비활성화 → 페이지 단위 렌더링으로 통합
            var renderedFrames = [];
            var renderedPdfFrames = [];
            var renderedGraphicFrames = [];
            var renderedImageFrames = [];

            // 2.12. 페이지 단위 배경 렌더링
            // 편집 가능한 텍스트 프레임만 숨기고 페이지를 통째로 PDF/PNG 렌더
            _marker(outputDir, "04_pageRendering");
            writeProgress(outputDir, "rendered_frames", 0, rangePageCount);

            var bgResult = exportPageBackgrounds(doc, outputDir, startPage, endPage, allItems, skipRenderPagesMap);
            var renderedFloatingItems = bgResult.items;
            var editableFrameIds = bgResult.editableFrameIds;
            // 테이블+인라인 렌더링 결과를 renderedFloatingItems에 추가
            if (bgResult.tableInlineRendered) {
                for (var tir = 0; tir < bgResult.tableInlineRendered.length; tir++) {
                    renderedFloatingItems.push(bgResult.tableInlineRendered[tir]);
                }
            }
            try { $.gc(); } catch (e) {}

            // 2.13. 배지 그룹 + 장식 텍스트 프레임 렌더링
            _marker(outputDir, "05_badgeRendering");
            var rtfResult = exportRenderedTextFrames(doc, outputDir, startPage, endPage, allItems, editableFrameIds, skipRenderPagesMap);
            renderedFrames = rtfResult.frames;
            var badgeChildIds = rtfResult.badgeChildIds;
            // 배지 자식 항목을 renderedFloatingItems에 추가
            for (var ri = 0; ri < renderedFrames.length; ri++) {
                renderedFloatingItems.push(renderedFrames[ri]);
            }
            try { $.gc(); } catch (e) {}

            _marker(outputDir, "10_collectResolved");
            writeProgress(outputDir, "resolved", 0, rangePageCount);

            // 3. resolved 속성 수집 (페이지 범위 필터링)
            var resolved = collectResolved(doc, outputDir, rangePageCount, startPage, endPage, editableFrameIds);
            resolved.renderedTextFrames = renderedFrames;
            resolved.renderedPdfFrames = renderedPdfFrames;
            resolved.renderedGraphicFrames = renderedGraphicFrames;
            resolved.renderedImageFrames = renderedImageFrames;
            resolved.renderedFloatingItems = renderedFloatingItems;

            // 편집 가능 TextFrame ID 목록 (배경에서 숨겨진 프레임 = 글상자로 배치할 대상)
            var editableIdList = [];
            for (var eid in editableFrameIds) {
                // SPEC-025: synthetic master instance IDs (예: "2453_pi20") 는 문자열로 유지
                if (/^[0-9]+$/.test(eid)) {
                    editableIdList.push(parseInt(eid, 10));
                } else {
                    editableIdList.push(eid);
                }
            }
            resolved.editableTextFrameIds = editableIdList;

            _marker(outputDir, "11_writeJson");
            writeJson(outputDir + "/resolved.json", resolved);

            // GC 후 참조 해제 — InDesign 엔진 정리 부담 감소
            resolved = null;
            allItems = null;
            renderedFrames = null;
            renderedPdfFrames = null;
            renderedGraphicFrames = null;
            decoChildIds = null;
            renderedVectorFrames = null;
            renderedImageFrames = null;
            try { $.gc(); } catch (e) {}
        }

        writeProgress(outputDir, "pdf", rangePageCount, rangePageCount);

        // 4. 링크 업데이트 (PDF 고해상도 이미지용)
        try {
            var inddParent = File(inddPath).parent;
            // Links 폴더 후보: INDD 옆 Links/, INDD와 같은 폴더
            var linksFolders = [
                Folder(inddParent + "/Links"),
                Folder(inddParent)
            ];
            var fixedCount = 0;
            var missingCount = 0;
            for (var li = 0; li < doc.links.length; li++) {
                var link = doc.links[li];
                try {
                    if (link.status === LinkStatus.NORMAL) continue;
                    if (link.status === LinkStatus.LINK_OUT_OF_DATE) {
                        link.update();
                        fixedCount++;
                    } else if (link.status === LinkStatus.LINK_MISSING) {
                        var found = false;
                        for (var fi = 0; fi < linksFolders.length; fi++) {
                            if (!linksFolders[fi].exists) continue;
                            var linkFile = File(linksFolders[fi] + "/" + link.name);
                            if (linkFile.exists) {
                                link.relink(linkFile);
                                link.update();
                                fixedCount++;
                                found = true;
                                break;
                            }
                        }
                        if (!found) missingCount++;
                    }
                } catch (le) {}
            }
            if (fixedCount > 0 || missingCount > 0) {
                $.writeln("[Links] fixed=" + fixedCount + " missing=" + missingCount);
            }
        } catch (e) {
            // 링크 업데이트 실패는 무시
        }

        // 5. PDF 프리뷰 (HWPX와 함께 출력용) — SPEC-030: skipPdf 이면 생성 안 함
        if (!skipPdf) {
            _marker(outputDir, "12_pdf_export");
            try {
                var pdfFile = File(outputDir + "/preview.pdf");

                app.pdfExportPreferences.exportReaderSpreads = spreadMode;
                // SPEC-030 A.3: 페이지 범위가 지정된 경우 PDF도 해당 범위만 출력
                if (startPage === 1 && endPage === pageCount) {
                    app.pdfExportPreferences.pageRange = PageRange.ALL_PAGES;
                } else {
                    app.pdfExportPreferences.pageRange = startPage + "-" + endPage;
                }
                app.pdfExportPreferences.colorBitmapSampling = Sampling.BICUBIC_DOWNSAMPLE;
                app.pdfExportPreferences.colorBitmapSamplingDPI = 300;
                app.pdfExportPreferences.colorBitmapCompression = BitmapCompression.JPEG;
                app.pdfExportPreferences.colorBitmapQuality = CompressionQuality.HIGH;
                app.pdfExportPreferences.grayscaleBitmapSampling = Sampling.BICUBIC_DOWNSAMPLE;
                app.pdfExportPreferences.grayscaleBitmapSamplingDPI = 300;
                app.pdfExportPreferences.grayscaleBitmapCompression = BitmapCompression.JPEG;
                app.pdfExportPreferences.grayscaleBitmapQuality = CompressionQuality.HIGH;
                app.pdfExportPreferences.monochromeBitmapSampling = Sampling.BICUBIC_DOWNSAMPLE;
                app.pdfExportPreferences.monochromeBitmapSamplingDPI = 1200;
                app.pdfExportPreferences.cropImagesToFrames = true;
                app.pdfExportPreferences.compressTextAndLineArt = true;
                app.pdfExportPreferences.acrobatCompatibility = AcrobatCompatibility.ACROBAT_7;
                app.pdfExportPreferences.subsetFontsBelow = 100;
                app.pdfExportPreferences.optimizePDF = true;

                doc.exportFile(ExportFormat.PDF_TYPE, pdfFile);
            } catch (e) {
                // PDF 내보내기 실패는 무시
            }
        }
        _marker(outputDir, "13_done");

        // 6. 문서 닫기 (저장 안 함)
        doc.close(SaveOptions.NO);
        doc = null;

        // 7. 성공 시그널
        writeDone(outputDir, "ok", null);
    } catch (e) {
        // 에러 시그널
        writeDone(outputDir, "error", e.message);
    } finally {
        // --- 반드시 현재 문서 닫기 (에러로 close에 도달 못한 경우) ---
        if (doc) {
            try { doc.close(SaveOptions.NO); } catch (e) {}
        }
        // --- 반드시 원래 환경설정 복원 ---
        app.scriptPreferences.userInteractionLevel = savedInteractionLevel;
        app.scriptPreferences.enableRedraw = savedEnableRedraw;
        app.linkingPreferences.checkLinksAtOpen = savedCheckLinks;
        app.linkingPreferences.findMissingLinksAtOpen = savedFindMissing;
    }
}

// --- 통합 렌더링 (캐시 기반) ---

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

    // SPEC-030 B.4: 배지 총 개수 선집계 (진행 표시용)
    var _totalBadges = 0;
    for (var _bc = 0; _bc < allItems.length; _bc++) {
        if (allItems[_bc].constructor.name === "Group" && isBadgeGroup(allItems[_bc])) _totalBadges++;
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
        if (!isBadgeGroup(_bgrp)) continue;
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
                if (_bChk[_bei].constructor.name === "TextFrame" && classifyTextFrame(_bChk[_bei]) === "editable") {
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
                if (_bChItems[_bci].constructor.name === "TextFrame") _bChildTfIds.push(_bChItems[_bci].id);
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
        if (!isBadgeGroup(grp)) continue;
        // 이미 부모 배지의 childId로 등록된 그룹은 건너뜀
        if (badgeGroupChildIds[grp.id]) continue;

        var grpPage = null;
        try { grpPage = grp.parentPage; } catch (e) {}
        // 인라인 Group은 parentPage가 null → visibleBounds로 페이지 매칭
        if (!grpPage) {
            try {
                var _gb = grp.visibleBounds; // [top, left, bottom, right]
                var _gcy = (_gb[0] + _gb[2]) / 2;
                var _gcx = (_gb[1] + _gb[3]) / 2;
                for (var pi = 0; pi < doc.pages.length; pi++) {
                    var _pg = doc.pages[pi];
                    var _pb = _pg.bounds;
                    if (_gcy >= _pb[0] && _gcy <= _pb[2] && _gcx >= _pb[1] && _gcx <= _pb[3]) {
                        grpPage = _pg;
                        break;
                    }
                }
            } catch (e) {}
        }
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

        // SPEC-025: editable 로 분류된 자식 TF 는 PNG 에서 제외 → HWPX 가 별도 텍스트 오버레이로 검색 가능 + 시각 중복 방지.
        var hiddenForExport = [];
        try {
            var __grpItemsForHide = grp.allPageItems;
            for (var __hi = 0; __hi < __grpItemsForHide.length; __hi++) {
                var __hItem = __grpItemsForHide[__hi];
                try {
                    if (__hItem.constructor.name !== "TextFrame") continue;
                    if (classifyTextFrame(__hItem) !== "editable") continue;
                    if (__hItem.visible) {
                        __hItem.visible = false;
                        hiddenForExport.push(__hItem);
                    }
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
                childTextFrameIds: childTextFrameIds
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

        // 페이지 결정
        var nGrpPage = null;
        try { nGrpPage = nGrp.parentPage; } catch (e) {}
        if (!nGrpPage) {
            try {
                var nVb = nGrp.visibleBounds;
                var nCy = (nVb[0] + nVb[2]) / 2;
                var nCx = (nVb[1] + nVb[3]) / 2;
                for (var npi = 0; npi < doc.pages.length; npi++) {
                    var nPg = doc.pages[npi];
                    var nPb = nPg.bounds;
                    if (nCy >= nPb[0] && nCy <= nPb[2] && nCx >= nPb[1] && nCx <= nPb[3]) {
                        nGrpPage = nPg; break;
                    }
                }
            } catch (e) {}
        }
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
                childTextFrameIds: [nBadgeTf.id]
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

        if (isTextFrame && classifyTextFrame(item) !== "renderable") continue;

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
        // renderTarget !== item: item 이 non-editable TF 이고 회전된 Rectangle 안에 있는 경우
        // Java 파이프라인이 이 TF 를 텍스트박스로 재배치 → 부모 PNG 에 텍스트 중복 방지를 위해 숨김.
        if (renderTarget !== item && isTextFrame && !editableFrameIds[item.id]) {
            var pRot = 0;
            try { pRot = renderTarget.absoluteRotationAngle; } catch (ePRot) {}
            if (Math.abs(pRot) > 0.5 && renderTarget.constructor.name === "Rectangle") {
                try {
                    if (item.visible) {
                        item.visible = false;
                        hiddenEditable.push(item);
                    }
                } catch (eRotHide) {}
            }
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
                        if (bgVbBounds) {
                            var bpb = parentPage.bounds;
                            bgVbBounds[0] -= bpb[0];
                            bgVbBounds[1] -= bpb[1];
                            bgVbBounds[2] -= bpb[0];
                            bgVbBounds[3] -= bpb[1];
                        }
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

            if (bounds) {
                var pageBounds = parentPage.bounds;
                bounds[0] -= pageBounds[0];
                bounds[1] -= pageBounds[1];
                bounds[2] -= pageBounds[0];
                bounds[3] -= pageBounds[1];
            }

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
    var saved = [];
    try {
        var noneColor = renderTarget.parentPage
            ? renderTarget.parentPage.parent.parent.swatches.item("None")
            : app.documents[0].swatches.item("None");
        var nested = renderTarget.allPageItems;
        for (var hi = 0; hi < nested.length; hi++) {
            if (nested[hi].constructor.name === "TextFrame") {
                try {
                    var tf = nested[hi];
                    var chars = tf.parentStory.characters;
                    if (chars.length === 0) continue;
                    // 문자별 fillColor/strokeColor 저장 후 None으로 변경
                    var charData = [];
                    for (var ci = 0; ci < chars.length; ci++) {
                        try {
                            charData.push({
                                fill: chars[ci].fillColor,
                                stroke: chars[ci].strokeColor
                            });
                            chars[ci].fillColor = noneColor;
                            chars[ci].strokeColor = noneColor;
                        } catch (e2) { charData.push(null); }
                    }
                    saved.push({ tf: tf, charData: charData });
                } catch (e) {}
            }
        }
    } catch (e) {}
    return saved;
}

function restoreTextFrames(saved) {
    for (var ri = 0; ri < saved.length; ri++) {
        try {
            var tf = saved[ri].tf;
            var charData = saved[ri].charData;
            var chars = tf.parentStory.characters;
            for (var ci = 0; ci < chars.length && ci < charData.length; ci++) {
                if (charData[ci]) {
                    try {
                        chars[ci].fillColor = charData[ci].fill;
                        chars[ci].strokeColor = charData[ci].stroke;
                    } catch (e2) {}
                }
            }
        } catch (e) {}
    }
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

        var domId = item.id;
        var fileName = "graphic_" + domId + ".png";
        var outFile = File(renderDir + "/" + fileName);

        try {
            var hiddenTFs = hideTextFrames(item);
            item.exportFile(ExportFormat.PNG_FORMAT, outFile);
            restoreTextFrames(hiddenTFs);

            var bounds = null;
            try { bounds = arrCopy(item.visibleBounds); } catch (e) {}
            if (!bounds) {
                try { bounds = arrCopy(item.geometricBounds); } catch (e) {}
            }

            if (bounds) {
                var pageBounds = parentPage.bounds;
                bounds[0] -= pageBounds[0];
                bounds[1] -= pageBounds[1];
                bounds[2] -= pageBounds[0];
                bounds[3] -= pageBounds[1];
            }

            // 자식 ID 수집 (IDML 파이프라인에서 중복 배치 방지)
            var childIds = [];
            try {
                var allNested = item.allPageItems;
                for (var ci = 0; ci < allNested.length; ci++) {
                    childIds.push(allNested[ci].id);
                }
            } catch (e2) {}

            renderedGraphicFrames.push({
                id: domId,
                file: "rendered_frames/" + fileName,
                bounds: bounds,
                pageIndex: parentPage.documentOffset,
                childIds: childIds.length > 0 ? childIds : undefined
            });
        } catch (e) {}
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

            if (bounds) {
                var pageBounds = parentPage.bounds;
                bounds[0] -= pageBounds[0];
                bounds[1] -= pageBounds[1];
                bounds[2] -= pageBounds[0];
                bounds[3] -= pageBounds[1];
            }

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
 * 이미지 배치 프레임(PSD, AI 등)을 PNG로 렌더링한다.
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
        var fileName = "img_" + domId + ".png";
        var outFile = File(renderDir + "/" + fileName);

        try {
            _marker(outputDir, "08_img_" + domId + "_hide");
            var hiddenTFs = isGroupRender ? hideTextFrames(renderTarget) : [];
            _marker(outputDir, "08_img_" + domId + "_export");
            renderTarget.exportFile(ExportFormat.PNG_FORMAT, outFile);
            _marker(outputDir, "08_img_" + domId + "_restore");
            if (hiddenTFs.length > 0) restoreTextFrames(hiddenTFs);

            var bounds = null;
            try { bounds = arrCopy(renderTarget.visibleBounds); } catch (e) {}
            if (!bounds) {
                try { bounds = arrCopy(renderTarget.geometricBounds); } catch (e) {}
            }

            if (bounds) {
                var pageBounds = parentPage.bounds;
                bounds[0] -= pageBounds[0];
                bounds[1] -= pageBounds[1];
                bounds[2] -= pageBounds[0];
                bounds[3] -= pageBounds[1];
            }

            var childIds = null;
            if (isGroupRender) {
                childIds = [];
                try {
                    var nested = renderTarget.allPageItems;
                    for (var ci = 0; ci < nested.length; ci++) {
                        var cn = nested[ci].constructor.name;
                        if (cn === "Rectangle" || cn === "Oval" || cn === "Polygon") {
                            var hasImg = false;
                            try { hasImg = nested[ci].images && nested[ci].images.length > 0; } catch (e3) {}
                            if (hasImg) childIds.push(nested[ci].id);
                        }
                    }
                } catch (e) {}
            }

            renderedImageFrames.push({
                id: domId,
                file: "rendered_frames/" + fileName,
                bounds: bounds,
                pageIndex: parentPage.documentOffset,
                childImageIds: childIds
            });
        } catch (e) {}
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
function exportDecorationGroups(doc, outputDir, startPage, endPage, badgeChildIds, allItems) {
    var renderDir = Folder(outputDir + "/rendered_frames");
    renderDir.create();

    var results = [];
    var decoChildIds = {};   // 자식 DOM ID → true (개별 렌더링 제외용)
    var renderedIds = {};

    // Pass 1: Oval/Rectangle 클리핑 컨테이너 (nested items가 모두 도형/그룹)
    for (var i = 0; i < allItems.length; i++) {
        var item = allItems[i];
        var cName = item.constructor.name;

        if (cName !== "Oval" && cName !== "Rectangle") continue;
        if (isOnHiddenLayer(item)) continue;

        var hasNested = false;
        try { hasNested = item.allPageItems && item.allPageItems.length > 0; } catch (e) {}
        if (!hasNested) continue;

        // contentType이 GRAPHIC_TYPE이 아니면 스킵 (텍스트 컨테이너 등)
        try {
            if (item.contentType !== ContentType.GRAPHIC_TYPE) continue;
        } catch (e) { continue; }

        // 이미지가 배치된 프레임은 스킵
        var hasPlaced = false;
        try { hasPlaced = item.images && item.images.length > 0; } catch (e) {}
        if (!hasPlaced) try { hasPlaced = item.pdfs && item.pdfs.length > 0; } catch (e) {}
        if (!hasPlaced) try { hasPlaced = item.epss && item.epss.length > 0; } catch (e) {}
        if (hasPlaced) continue;

        // 내부 아이템이 모두 도형/그룹인지 확인
        if (!isAllShapeChildren(item)) continue;

        var parentPage = null;
        try { parentPage = item.parentPage; } catch (e) {}
        if (!parentPage) continue;
        var pgIdx = parentPage.documentOffset + 1;
        if (pgIdx < startPage || pgIdx > endPage) continue;

        var domId = item.id;
        if (renderedIds[domId]) continue;
        if (badgeChildIds && badgeChildIds[domId]) continue;

        // 렌더링
        try {
            var fileName = "deco_" + domId + ".png";
            var outFile = File(renderDir + "/" + fileName);
            item.exportFile(ExportFormat.PNG_FORMAT, outFile);

            var bounds = null;
            try { bounds = arrCopy(item.visibleBounds); } catch (e) {}
            if (!bounds) try { bounds = arrCopy(item.geometricBounds); } catch (e) {}

            if (bounds) {
                var pageBounds = parentPage.bounds;
                bounds[0] -= pageBounds[0];
                bounds[1] -= pageBounds[1];
                bounds[2] -= pageBounds[0];
                bounds[3] -= pageBounds[1];
            }

            results.push({
                id: domId,
                file: "rendered_frames/" + fileName,
                bounds: bounds,
                pageIndex: parentPage.documentOffset
            });
            renderedIds[domId] = true;

            // 자식 ID 모두 제외 대상에 등록
            var nested = item.allPageItems;
            for (var ni = 0; ni < nested.length; ni++) {
                decoChildIds[nested[ni].id] = true;
            }
        } catch (e) {}
    }

    // Pass 2: 도형만으로 구성된 일반 Group (텍스트/이미지 없음, 배지 아님)
    for (var gi = 0; gi < allItems.length; gi++) {
        var grp = allItems[gi];
        if (grp.constructor.name !== "Group") continue;
        if (isOnHiddenLayer(grp)) continue;

        var grpDomId = grp.id;
        if (renderedIds[grpDomId]) continue;
        if (decoChildIds[grpDomId]) continue;  // 이미 클리핑 컨테이너 자식
        if (badgeChildIds && badgeChildIds[grpDomId]) continue;

        // 부모가 이미 처리된 컨테이너면 스킵
        try {
            var pItem = grp.parent;
            if (pItem && renderedIds[pItem.id]) continue;
            if (pItem && decoChildIds[pItem.id]) continue;
        } catch (e) {}

        // 내부 아이템이 모두 도형/그룹인지 확인
        if (!isAllShapeChildren(grp)) continue;

        // 최소 자식 수 — 2개 이상이어야 그룹 렌더링 의미 있음
        var childCount = 0;
        try { childCount = grp.allPageItems.length; } catch (e) {}
        if (childCount < 2) continue;

        var grpPage = null;
        try { grpPage = grp.parentPage; } catch (e) {}
        if (!grpPage) {
            try {
                var _gb = grp.visibleBounds;
                var _gcy = (_gb[0] + _gb[2]) / 2;
                var _gcx = (_gb[1] + _gb[3]) / 2;
                for (var pi = 0; pi < doc.pages.length; pi++) {
                    var _pg = doc.pages[pi];
                    var _pb = _pg.bounds;
                    if (_gcy >= _pb[0] && _gcy <= _pb[2] && _gcx >= _pb[1] && _gcx <= _pb[3]) {
                        grpPage = _pg;
                        break;
                    }
                }
            } catch (e) {}
        }
        // 인라인 앵커 그룹: 자식 pageItem의 parentPage를 폴백으로 사용
        if (!grpPage) {
            try {
                var _pi = grp.pageItems;
                for (var _k = 0; _k < _pi.length; _k++) {
                    try { grpPage = _pi[_k].parentPage; } catch (e2) {}
                    if (grpPage) break;
                }
            } catch (e) {}
        }
        if (!grpPage) continue;
        var grpPgIdx = grpPage.documentOffset + 1;
        if (grpPgIdx < startPage || grpPgIdx > endPage) continue;

        // 부모 Group도 도형 전용이면 부모로 승격 (인라인 그룹 전체를 하나의 deco로)
        var _renderSiblings = false;
        try {
            var _par = grp.parent;
            if (_par && _par.constructor.name === "Group"
                && !renderedIds[_par.id] && !(badgeChildIds && badgeChildIds[_par.id])) {
                if (isAllShapeChildren(_par)) {
                    grp = _par;
                    grpDomId = _par.id;
                } else {
                    // 부모가 TextFrame 등 비도형 포함 → 형제 도형을 개별 렌더
                    _renderSiblings = true;
                }
            }
        } catch (e) {}
        if (renderedIds[grpDomId]) continue;

        // 현재 그룹 렌더
        try {
            var grpFileName = "deco_" + grpDomId + ".png";
            var grpOutFile = File(renderDir + "/" + grpFileName);
            grp.exportFile(ExportFormat.PNG_FORMAT, grpOutFile);

            var grpBounds = null;
            try { grpBounds = arrCopy(grp.visibleBounds); } catch (e) {}
            if (!grpBounds) try { grpBounds = arrCopy(grp.geometricBounds); } catch (e) {}

            if (grpBounds) {
                var grpPageBounds = grpPage.bounds;
                grpBounds[0] -= grpPageBounds[0];
                grpBounds[1] -= grpPageBounds[1];
                grpBounds[2] -= grpPageBounds[0];
                grpBounds[3] -= grpPageBounds[1];
            }

            results.push({
                id: grpDomId,
                file: "rendered_frames/" + grpFileName,
                bounds: grpBounds,
                pageIndex: grpPage.documentOffset
            });
            renderedIds[grpDomId] = true;

            var grpNested = grp.allPageItems;
            for (var gni = 0; gni < grpNested.length; gni++) {
                decoChildIds[grpNested[gni].id] = true;
            }
        } catch (e) {}

        // 부모 그룹의 형제 도형(GraphicLine 등)을 개별 렌더
        if (_renderSiblings) {
            try {
                var _origGrp = allItems[gi];
                var _parRef = _origGrp.parent;
                // allPageItems로 구체 타입 취득 후 직접 자식만 필터
                var _allKids = _parRef.allPageItems;
                var _parItems = [];
                for (var _fi = 0; _fi < _allKids.length; _fi++) {
                    try { if (_allKids[_fi].parent.id === _parRef.id) _parItems.push(_allKids[_fi]); } catch(e3) {}
                }
                var _parPageBounds = grpPage.bounds;
                for (var _si = 0; _si < _parItems.length; _si++) {
                    var sib = _parItems[_si];
                    var sibId = sib.id;
                    if (sibId === grpDomId) continue;
                    if (renderedIds[sibId] || decoChildIds[sibId]) continue;
                    var sibCn = sib.constructor.name;
                    if (sibCn !== "GraphicLine" && sibCn !== "Rectangle"
                        && sibCn !== "Polygon" && sibCn !== "Oval" && sibCn !== "Group") continue;
                    if (sibCn === "Group" && !isAllShapeChildren(sib)) continue;

                    try {
                        var sibFileName = "deco_" + sibId + ".png";
                        var sibOutFile = File(renderDir + "/" + sibFileName);
                        sib.exportFile(ExportFormat.PNG_FORMAT, sibOutFile);

                        var sibBounds = null;
                        try { sibBounds = arrCopy(sib.visibleBounds); } catch (e2) {}
                        if (!sibBounds) try { sibBounds = arrCopy(sib.geometricBounds); } catch (e2) {}

                        if (sibBounds) {
                            sibBounds[0] -= _parPageBounds[0];
                            sibBounds[1] -= _parPageBounds[1];
                            sibBounds[2] -= _parPageBounds[0];
                            sibBounds[3] -= _parPageBounds[1];
                        }

                        results.push({
                            id: sibId,
                            file: "rendered_frames/" + sibFileName,
                            bounds: sibBounds,
                            pageIndex: grpPage.documentOffset
                        });
                        renderedIds[sibId] = true;

                        try {
                            var sibNested = sib.allPageItems;
                            for (var sni = 0; sni < sibNested.length; sni++) {
                                decoChildIds[sibNested[sni].id] = true;
                            }
                        } catch (e2) {}
                    } catch (e2) {}
                }
            } catch (e) {}
        }
    }

    // Pass 3: 컨테이너 도형+특수효과 텍스트 복합 그룹 (표지판, 장식 카드 등)
    // 조건:
    //   (1) convex 도형(Rectangle/Oval)이 컨테이너 역할 (가장 큰 바운딩 박스)
    //       TextFrame, 테이블셀은 컨테이너가 아님
    //   (2) 나머지 모든 객체가 컨테이너 bounds 내부에 존재
    //   (3) 모든 텍스트에 특수효과 있음 (outline, shadow, skew, rotation 등)
    for (var ci = 0; ci < allItems.length; ci++) {
        var cGrp = allItems[ci];
        if (cGrp.constructor.name !== "Group") continue;
        if (isOnHiddenLayer(cGrp)) continue;

        var cDomId = cGrp.id;
        if (renderedIds[cDomId]) continue;
        if (decoChildIds[cDomId]) continue;
        if (badgeChildIds && badgeChildIds[cDomId]) continue;

        // 부모가 이미 처리된 그룹이면 스킵
        try {
            var cPar = cGrp.parent;
            if (cPar && (renderedIds[cPar.id] || decoChildIds[cPar.id])) continue;
        } catch (e) {}

        // --- 조건 1: convex 컨테이너 도형 찾기 ---
        var containerShape = null;
        var containerBounds = null;
        var containerArea = 0;
        var hasText = false;
        var cNested;
        try {
            cNested = cGrp.allPageItems;
            if (cNested.length < 2) continue;
            for (var cn2 = 0; cn2 < cNested.length; cn2++) {
                var cnItem = cNested[cn2];
                var cnName = cnItem.constructor.name;
                if (cnName === "TextFrame") {
                    hasText = true;
                } else if (cnName === "Rectangle" || cnName === "Oval") {
                    try {
                        var sb = cnItem.geometricBounds;
                        var sArea = (sb[2] - sb[0]) * (sb[3] - sb[1]);
                        if (sArea > containerArea) {
                            containerArea = sArea;
                            containerShape = cnItem;
                            containerBounds = sb;
                        }
                    } catch (e2) {}
                }
            }
        } catch (e) { continue; }
        if (!hasText || !containerShape || !containerBounds) continue;

        // --- 조건 2: 나머지 모든 객체가 컨테이너 내부에 존재 ---
        var allInside = true;
        var TOLERANCE = 1.0;  // 1pt 허용 오차
        try {
            for (var cn4 = 0; cn4 < cNested.length; cn4++) {
                if (cNested[cn4] === containerShape) continue;
                try {
                    var childBb = cNested[cn4].geometricBounds;
                    if (childBb[0] < containerBounds[0] - TOLERANCE ||
                        childBb[1] < containerBounds[1] - TOLERANCE ||
                        childBb[2] > containerBounds[2] + TOLERANCE ||
                        childBb[3] > containerBounds[3] + TOLERANCE) {
                        allInside = false;
                        break;
                    }
                } catch (e3) {}
            }
        } catch (e) { allInside = false; }
        if (!allInside) continue;

        // --- 조건 3: 모든 텍스트에 특수효과 있어야 함 ---
        // 특수효과: outline(strokeColor), shadow, skew, rotation
        // 노말 텍스트가 하나라도 있으면 스킵
        var allTextSpecial = true;
        try {
            for (var cn5 = 0; cn5 < cNested.length; cn5++) {
                if (cNested[cn5].constructor.name !== "TextFrame") continue;
                var ctf = cNested[cn5];
                // 빈 텍스트 프레임은 무시
                try {
                    var ctfContent = ctf.contents;
                    if (!ctfContent || ctfContent.replace(/[\s\r\n]/g, "").length === 0) continue;
                } catch (e3) { continue; }

                var tfSpecial = false;
                // 프레임 회전
                try { if (Math.abs(ctf.rotationAngle) > 0.1) tfSpecial = true; } catch (e3) {}
                // 문자 단위 특수효과 확인
                if (!tfSpecial) {
                    try {
                        var ctChars = ctf.characters;
                        var charAllSpecial = true;
                        for (var cc = 0; cc < ctChars.length && cc < 30; cc++) {
                            var ch = ctChars[cc];
                            var chContent = ch.contents;
                            // 공백/줄바꿈은 건너뜀
                            if (chContent === " " || chContent === "\r" || chContent === "\n") continue;
                            var chSpecial = false;
                            // 텍스트 외곽선(stroke)
                            try {
                                var chSc = ch.strokeColor;
                                if (chSc && chSc.name !== "None") chSpecial = true;
                            } catch (e4) {}
                            // 기울임(skew)
                            if (!chSpecial) {
                                try { if (Math.abs(ch.skewAngle) > 0.1) chSpecial = true; } catch (e4) {}
                            }
                            // 그림자(shadow) — dropShadowSettings
                            if (!chSpecial) {
                                try {
                                    var ds = ch.dropShadowSettings;
                                    if (ds && ds.mode && ds.mode.toString() !== "ShadowMode.NONE"
                                        && ds.mode.toString() !== "ShadowMode.NONE") chSpecial = true;
                                } catch (e4) {}
                            }
                            if (!chSpecial) { charAllSpecial = false; break; }
                        }
                        if (charAllSpecial) tfSpecial = true;
                    } catch (e3) {}
                }
                // 프레임 레벨 그림자
                if (!tfSpecial) {
                    try {
                        var tfDs = ctf.transparencySettings.dropShadowSettings;
                        if (tfDs && tfDs.mode && tfDs.mode.toString() !== "ShadowMode.NONE") tfSpecial = true;
                    } catch (e3) {}
                }
                if (!tfSpecial) { allTextSpecial = false; break; }
            }
        } catch (e) { allTextSpecial = false; }
        if (!allTextSpecial) continue;



        // 페이지 범위 확인
        var cPage = null;
        try { cPage = cGrp.parentPage; } catch (e) {}
        if (!cPage) continue;
        var cPgIdx = cPage.documentOffset + 1;
        if (cPgIdx < startPage || cPgIdx > endPage) continue;

        // 렌더링
        try {
            var cFileName = "deco_" + cDomId + ".png";
            var cOutFile = File(renderDir + "/" + cFileName);
            cGrp.exportFile(ExportFormat.PNG_FORMAT, cOutFile);

            var cBounds = null;
            try { cBounds = arrCopy(cGrp.visibleBounds); } catch (e) {}
            if (!cBounds) try { cBounds = arrCopy(cGrp.geometricBounds); } catch (e) {}

            if (cBounds) {
                var cPageBounds = cPage.bounds;
                cBounds[0] -= cPageBounds[0];
                cBounds[1] -= cPageBounds[1];
                cBounds[2] -= cPageBounds[0];
                cBounds[3] -= cPageBounds[1];
            }

            // 자식 ID 수집
            var cAllNested = cGrp.allPageItems;
            var cChildIdArr = [];
            for (var cni = 0; cni < cAllNested.length; cni++) {
                var cChildId = cAllNested[cni].id;
                cChildIdArr.push(cChildId);
                decoChildIds[cChildId] = true;
            }

            results.push({
                id: cDomId,
                file: "rendered_frames/" + cFileName,
                bounds: cBounds,
                pageIndex: cPage.documentOffset,
                childIds: cChildIdArr
            });
            renderedIds[cDomId] = true;
        } catch (e) {}
    }

    // Pass 4: 비사각형 폴리곤 다수 포함 그룹 (벌집 그리드 등)
    // 조건: 중첩 자식 중 비사각형 Polygon(꼭짓점 5개 이상)이 3개 이상
    for (var p4i = 0; p4i < allItems.length; p4i++) {
        var p4Grp = allItems[p4i];
        if (p4Grp.constructor.name !== "Group") continue;
        if (isOnHiddenLayer(p4Grp)) continue;

        var p4Id = p4Grp.id;
        if (renderedIds[p4Id]) continue;
        if (decoChildIds[p4Id]) continue;
        if (badgeChildIds && badgeChildIds[p4Id]) continue;

        // 부모가 이미 처리됨
        try {
            var p4Par = p4Grp.parent;
            if (p4Par && (renderedIds[p4Par.id] || decoChildIds[p4Par.id])) continue;
        } catch (e) {}

        // 비사각형 도형 카운트 (Rectangle/Polygon 모두 path points > 4이면 비사각형)
        var nonRectCount = 0;
        var p4Nested;
        try {
            p4Nested = p4Grp.allPageItems;
            for (var p4n = 0; p4n < p4Nested.length; p4n++) {
                var p4cn = p4Nested[p4n].constructor.name;
                if (p4cn !== "Polygon" && p4cn !== "Rectangle") continue;
                try {
                    var p4pts = p4Nested[p4n].paths[0].pathPoints.length;
                    if (p4pts > 4) nonRectCount++;
                } catch (e2) {}
            }
        } catch (e) { continue; }

        if (nonRectCount < 3) continue;

        // 텍스트 프레임을 포함한 그룹은 제외 (텍스트가 이미지로 변환되는 것 방지)
        var p4HasTF = false;
        for (var p4t = 0; p4t < p4Nested.length; p4t++) {
            if (p4Nested[p4t].constructor.name === "TextFrame") {
                p4HasTF = true;
                break;
            }
        }
        if (p4HasTF) continue;

        // 페이지 확인
        var p4Page = null;
        try { p4Page = p4Grp.parentPage; } catch (e) {}
        if (!p4Page) continue;
        var p4PgIdx = p4Page.documentOffset + 1;
        if (p4PgIdx < startPage || p4PgIdx > endPage) continue;



        // 렌더링
        try {
            var p4FileName = "deco_" + p4Id + ".png";
            var p4OutFile = File(renderDir + "/" + p4FileName);
            p4Grp.exportFile(ExportFormat.PNG_FORMAT, p4OutFile);

            var p4Bounds = null;
            try { p4Bounds = arrCopy(p4Grp.visibleBounds); } catch (e) {}
            if (!p4Bounds) try { p4Bounds = arrCopy(p4Grp.geometricBounds); } catch (e) {}

            if (p4Bounds) {
                var p4PageBounds = p4Page.bounds;
                p4Bounds[0] -= p4PageBounds[0];
                p4Bounds[1] -= p4PageBounds[1];
                p4Bounds[2] -= p4PageBounds[0];
                p4Bounds[3] -= p4PageBounds[1];
            }

            var p4ChildIds = [];
            var p4ChildIdMap = {};
            for (var p4ci = 0; p4ci < p4Nested.length; p4ci++) {
                var p4ChildId = p4Nested[p4ci].id;
                p4ChildIds.push(p4ChildId);
                p4ChildIdMap[p4ChildId] = true;
                decoChildIds[p4ChildId] = true;
            }

            // 이전 Pass에서 이미 렌더링된 자식 엔트리를 results에서 제거
            var p4Cleaned = [];
            for (var p4ri = 0; p4ri < results.length; p4ri++) {
                if (!p4ChildIdMap[results[p4ri].id]) {
                    p4Cleaned.push(results[p4ri]);
                }
            }
            results = p4Cleaned;

            results.push({
                id: p4Id,
                file: "rendered_frames/" + p4FileName,
                bounds: p4Bounds,
                pageIndex: p4Page.documentOffset,
                childIds: p4ChildIds
            });
            renderedIds[p4Id] = true;
        } catch (e) {}
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

        // 부모 체인을 거슬러 올라가며 인라인 앵커 여부 확인
        // (Group 내부 도형이 TextFrame/Cell/Story에 앵커된 경우 스킵)
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
        // 부모가 Group이면 Group의 parentPage를 우선 사용
        // (스프레드 경계에서 도형 중심 기준 판정이 부정확할 수 있음)
        if (!parentPage || (item.parent && item.parent.constructor.name === "Group")) {
            try {
                var grpPage = item.parent.parentPage;
                if (grpPage) parentPage = grpPage;
            } catch (e) {}
        }
        if (!parentPage) continue;
        var pgIdx = parentPage.documentOffset + 1;
        if (pgIdx < startPage || pgIdx > endPage) continue;

        // 최소 크기 필터 — 3pt 미만 도형은 시각적으로 무의미하므로 건너뜀
        try {
            var gb = item.geometricBounds; // [top, left, bottom, right]
            var shapeW = gb[3] - gb[1];
            var shapeH = gb[2] - gb[0];
            if (shapeW < 3 && shapeH < 3) continue;
        } catch (e) {}

        var hasNestedItems = false;
        try { hasNestedItems = item.allPageItems && item.allPageItems.length > 0; } catch (e) {}
        if (hasNestedItems) {
            try { if (item.contentType === ContentType.GRAPHIC_TYPE) continue; } catch (e) {}
        }

        try {
            var fileName = "shape_" + domId + ".png";
            var outFile = File(renderDir + "/" + fileName);
            item.exportFile(ExportFormat.PNG_FORMAT, outFile);

            var bounds = null;
            try { bounds = arrCopy(item.visibleBounds); } catch (e) {}
            if (!bounds) {
                try { bounds = arrCopy(item.geometricBounds); } catch (e) {}
            }

            if (bounds) {
                var pageBounds = parentPage.bounds;
                bounds[0] -= pageBounds[0];
                bounds[1] -= pageBounds[1];
                bounds[2] -= pageBounds[0];
                bounds[3] -= pageBounds[1];
            }

            results.push({
                id: domId,
                file: "rendered_frames/" + fileName,
                bounds: bounds,
                pageIndex: parentPage.documentOffset
            });
            renderedIds[domId] = true;
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

function isBadgeGroup(group) {
    var cfg = CONFIG.rendering.badge;
    if (!cfg.enabled) return false;

    var hasShape = false;
    var hasImage = false;
    var totalTextLen = 0;

    try {
        var items = group.allPageItems;
        for (var i = 0; i < items.length; i++) {
            var item = items[i];
            var cName = item.constructor.name;
            if (cName === "Rectangle" || cName === "Polygon"
                || cName === "Oval" || cName === "GraphicLine") {
                try {
                    if (item.images && item.images.length > 0) { hasImage = true; continue; }
                    if (item.epss && item.epss.length > 0) { hasImage = true; continue; }
                    if (item.pdfs && item.pdfs.length > 0) { hasImage = true; continue; }
                } catch (e) {}
                hasShape = true;
                // TextPath 텍스트도 카운트
                try {
                    if (item.textPaths && item.textPaths.length > 0) {
                        for (var tp = 0; tp < item.textPaths.length; tp++) {
                            try { totalTextLen += item.textPaths[tp].texts[0].contents.replace(/[\s\uFEFF]/g, "").length; } catch (e2) {}
                        }
                    }
                } catch (e) {}
            } else if (cName === "TextFrame") {
                try {
                    var __tfLen = item.contents.replace(/[\s\uFEFF]/g, "").length;
                    totalTextLen += __tfLen;
                    // SPEC-028: \uBE48 story + (fill \uB610\uB294 stroke) TextFrame \uB3C4 \uB3C4\uD615\uC73C\uB85C \uC778\uC815.
                    // \uC608: outlined \uBB38\uC790 (Black fill + \uBCF5\uC7A1 path), \uBC15\uC2A4 \uD14C\uB450\uB9AC (stroke).
                    if (__tfLen === 0) {
                        var __hasFill = false, __hasStroke = false;
                        try { __hasFill = item.fillColor && item.fillColor.name !== "None" && item.fillColor.name !== "[None]"; } catch (eF) {}
                        try { __hasStroke = item.strokeColor && item.strokeColor.name !== "None" && item.strokeColor.name !== "[None]" && item.strokeWeight > 0; } catch (eS) {}
                        if (__hasFill || __hasStroke) hasShape = true;
                    }
                } catch (e) {}
            } else if (cName === "Image" || cName === "EPS" || cName === "PDF") {
                hasImage = true;
            }
        }
    } catch (e) {
        return false;
    }


    // 조건 1: 이미지 허용 여부
    if (hasImage && !cfg.allowImage) return false;
    // 조건 2: 도형 필수 여부
    if (cfg.requireShape && !hasShape) return false;
    // 조건 3: 텍스트 길이
    if (totalTextLen === 0 || totalTextLen > cfg.maxTextLength) return false;
    // 조건 4: 크기 제한 (짧은 변 기준, pt 변환)
    try {
        var gb = group.geometricBounds;
        var gw = gb[3] - gb[1];
        var gh = gb[2] - gb[0];
        var scale = 72 / 25.4; // 기본 mm→pt
        try {
            var pageW = group.parentPage.bounds[3] - group.parentPage.bounds[1];
            if (pageW > 0 && pageW < 300) scale = 72 / 25.4;
            else if (pageW > 0 && pageW < 30) scale = 72;
        } catch (e) {
            // parentPage가 null(인라인 그룹) → 기본 스케일 사용
        }
        var minDim = Math.min(gw, gh) * scale;
        var maxDim = Math.max(gw, gh) * scale;
        if (minDim > cfg.maxSize) return false;
        // 종횡비 체크: 길이:짧이 > cfg.maxAspectRatio 이면 배너(Lesson 타이틀 등) 이므로 배지 아님
        var aspectLimit = (cfg.maxAspectRatio !== undefined) ? cfg.maxAspectRatio : 4.5;
        if (minDim > 0 && maxDim / minDim > aspectLimit) return false;
    } catch (e) {
        return false;
    }

    return true;
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
    // 1. TextFrame만 처리
    if (item.constructor.name !== "TextFrame") return null;

    // SPEC-025 Tier A.5 보강 (2026-05-22): 마스터 페이지 본체 텍스트 검출.
    // 기존 조건 5 (`item.masterPageItem`) 는 오버라이드만 잡고, 마스터 스프레드 위 원본 텍스트는
    // 검출하지 못함. 부모 체인을 거슬러 올라가 MasterSpread 가 있으면 마스터 본체로 간주.
    try {
        var __s25master = CONFIG && CONFIG.rendering && CONFIG.rendering.textFrame && CONFIG.rendering.textFrame.spec025;
        if (__s25master && __s25master.masterPageEditable) {
            var __onMasterBody = false;
            try {
                var __mp = item.parent;
                var __hop = 0;
                while (__mp && __hop < 10) {
                    if (__mp.constructor && __mp.constructor.name === "MasterSpread") { __onMasterBody = true; break; }
                    __mp = __mp.parent;
                    __hop++;
                }
            } catch (e) {}
            if (__onMasterBody) {
                // 텍스트 콘텐츠가 있어야 의미 있음. 빈 마스터 placeholder 는 background.
                var __cmp = "";
                try { __cmp = String(item.contents).replace(/[\s﻿\r\n￼]/g, ""); } catch (e) {}
                if (__cmp.length > 0) return "editable";
            }
        }
    } catch (e) {}

    // 1.5. 비균일/축소 변환된 TextFrame → background
    // (예: 부록 181p 의 0.184x 축소 TextFrame — HWPX 는 ItemTransform 의 스케일을
    //  글상자 크기/폰트에 그대로 반영하지 못해 글자가 너무 크게 출력됨.
    //  배경 PDF 에 이미 시각적으로 렌더되어 있으므로 background 로 처리.)
    try {
        var sx15 = 1, sy15 = 1;
        try {
            var ahs = item.absoluteHorizontalScale;
            var avs = item.absoluteVerticalScale;
            if (typeof ahs === "number" && ahs > 0) sx15 = ahs / 100;
            if (typeof avs === "number" && avs > 0) sy15 = avs / 100;
        } catch (e) {}
        if ((sx15 > 0 && (sx15 < 0.6 || sx15 > 1.6))
            || (sy15 > 0 && (sy15 < 0.6 || sy15 > 1.6))) {
            return "background";
        }
    } catch (e) {}

    // SPEC-025 Tier B: 회전된 TextFrame 은 모든 renderable 분기보다 먼저 editable 로 직행.
    // (조건 8/8.5/9/9.5 등이 renderable/background 로 분기하기 전 차단)
    // 회전이 부모 Group 에 걸려 있을 수 있어 absoluteRotationAngle 사용.
    // 본인이 background-쪽 (hidden/nonprinting 등) 인 경우는 아래 검사가 처리.
    var __spec025RotBypass = false;
    try {
        var __s25rotTop = CONFIG && CONFIG.rendering && CONFIG.rendering.textFrame && CONFIG.rendering.textFrame.spec025;
        if (__s25rotTop && __s25rotTop.rotationEditable) {
            var __rotTop = 0;
            try { __rotTop = item.absoluteRotationAngle; } catch (e) {}
            if (!__rotTop) { try { __rotTop = item.rotationAngle; } catch (e) {} }
            // 부모 Group 회전도 합산
            if (!__rotTop) {
                try {
                    var __par = item.parent;
                    while (__par && __par.constructor && __par.constructor.name === "Group") {
                        var __pRot = 0;
                        try { __pRot = __par.absoluteRotationAngle; } catch (e) {}
                        if (!__pRot) { try { __pRot = __par.rotationAngle; } catch (e) {} }
                        if (__pRot) { __rotTop = __pRot; break; }
                        __par = __par.parent;
                    }
                } catch (e) {}
            }
            if (Math.abs(__rotTop) > 3.0) __spec025RotBypass = true;
        }
    } catch (e) {}

    // 2. 숨겨진 레이어
    if (isOnHiddenLayer(item)) return "background";
    // 3. 비인쇄
    // SPEC-025 Tier A: spec025.nonprintingEditable=true이면 editable로 변환.
    // 마스터 스프레드의 단원명 머리말 등이 nonprinting=true 인 경우가 많아 보통 검색 가능 텍스트로 살리는 게 좋음.
    try {
        if (item.nonprinting) {
            var s25np = CONFIG && CONFIG.rendering && CONFIG.rendering.textFrame && CONFIG.rendering.textFrame.spec025;
            if (!(s25np && s25np.nonprintingEditable)) return "background";
        }
    } catch (e) {}
    // 4. 자동 페이지 번호 마커
    try {
        if (item.parentStory.contents.indexOf("\u0018") >= 0) return "background";
    } catch (e) {}
    // 5. 마스터 페이지 오버라이드
    // SPEC-025 Tier A: spec025.masterPageEditable=true이면 editable로 변환 (검색 가능 텍스트)
    try {
        if (item.masterPageItem) {
            var s25mp = CONFIG && CONFIG.rendering && CONFIG.rendering.textFrame && CONFIG.rendering.textFrame.spec025;
            if (!(s25mp && s25mp.masterPageEditable)) return "background";
        }
    } catch (e) {}
    // 6. 마진 영역의 짧은 텍스트 (페이지 번호, 하시라 등)
    try {
        var ppg = item.parentPage;
        if (ppg) {
            var pgB = ppg.bounds;
            var pgW = pgB[3] - pgB[1], pgH = pgB[2] - pgB[0];
            var tfB = item.geometricBounds;
            var tfTop = tfB[0] - pgB[0], tfBot = tfB[2] - pgB[0];
            var tfLeft = tfB[1] - pgB[1], tfRight = tfB[3] - pgB[1];
            var trimmed6 = "";
            try { trimmed6 = item.contents.replace(/[\s\uFEFF\r\n\u0016\u0018\uFFFC]/g, ""); } catch (e4) {}
            var inMarginArea = (tfTop < pgH * 0.10 || tfBot > pgH * 0.90
                || tfRight <= pgW * 0.25 || tfLeft >= pgW * 0.75);
            var hasTable6 = false;
            try {
                hasTable6 = (item.parentStory && item.parentStory.tables.length > 0)
                    || (item.contents.indexOf("\u0016") >= 0);
            } catch (e5) {}
            // 글자 수 대신 단락 + 캐릭터 스타일명 패턴으로 하시라/페이지번호 식별.
            // SPEC-025: 일부 문서는 paragraphStyle 이 "[단락 스타일 없음]" 이고 characterStyle 에 "**하시라_소단원" 같은
            // 식별자만 둠 → 캐릭터 스타일도 함께 검사한다.
            var hashiraStyle6 = false;
            function _isHashiraName6(n) {
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
            try {
                // 단락 스타일 검사
                var ps6 = null;
                try { ps6 = item.parentStory.paragraphs[0].appliedParagraphStyle; } catch (e) {}
                var styleName6 = "";
                try { styleName6 = ps6 ? (ps6.name || "") : ""; } catch (e) {}
                if (_isHashiraName6(styleName6)) hashiraStyle6 = true;
                // 캐릭터 스타일 검사 (story 내 모든 텍스트 런 — 보통 1~3개)
                if (!hashiraStyle6) {
                    try {
                        var trs6 = item.parentStory.textStyleRanges;
                        for (var ti6 = 0; ti6 < trs6.length; ti6++) {
                            var cs6 = null;
                            try { cs6 = trs6[ti6].appliedCharacterStyle; } catch (e) {}
                            var csName6 = "";
                            try { csName6 = cs6 ? (cs6.name || "") : ""; } catch (e) {}
                            if (_isHashiraName6(csName6)) { hashiraStyle6 = true; break; }
                        }
                    } catch (e) {}
                }
            } catch (e) {}
            if (hashiraStyle6 && inMarginArea && !hasTable6) {
                // SPEC-025 Tier A: spec025.hashiraEditable=true이면 editable로 변환 (단원명/하시라 검색 가능)
                var s25h = CONFIG && CONFIG.rendering && CONFIG.rendering.textFrame && CONFIG.rendering.textFrame.spec025;
                if (!(s25h && s25h.hashiraEditable)) return "background";
            }
        }
    } catch (e) {}
    // 7. 인라인 객체는 부모가 관리
    // SPEC-025: inlineTextEditable=true 면 텍스트 콘텐츠가 있는 인라인 TextFrame 은 editable 로
    // (인라인 그래픽 객체는 contents 가 비어 있어 그대로 background)
    if (isInlineItem(item)) {
        var inlineBypass = false;
        try {
            var s25in = CONFIG && CONFIG.rendering && CONFIG.rendering.textFrame && CONFIG.rendering.textFrame.spec025;
            if (s25in && s25in.inlineTextEditable) {
                // item.contents 는 InDesign DOM 게터로 호출마다 다른 값을 반환할 수 있어 한 번만 캐싱.
                // 정규식 source 는 ASCII \u 이스케이프만 사용 — ExtendScript 가 raw 제어바이트(0x16/0x18)가 들어간 character class 를 잘못 파싱해서 모든 문자를 매칭하는 버그가 있음.
                var __cached7 = ""; try { __cached7 = String(item.contents); } catch (e) {}
                var trimmed7 = "";
                try { trimmed7 = __cached7.replace(/[\s\u0016\u0018\uFEFF\uFFFC]/g, ""); } catch (e) {}
                // 짧은 인라인 (배지/라벨) 만 editable 로. 긴 인라인은 부모 flow 의 ORC embedding 과 중복 위험.
                var maxLen7 = (typeof s25in.inlineTextMaxLen === "number") ? s25in.inlineTextMaxLen : 3;
                if (trimmed7.length > 0 && trimmed7.length <= maxLen7) inlineBypass = true;
            }
        } catch (e) {}
        if (!inlineBypass) return "background";
    }
    // 8. Group 안의 짧은 장식 TextFrame (10자 이하 + 비검정색)
    try {
        var parentType8 = item.parent.constructor.name;
        if (parentType8 === "Group") {
            var groupText8 = item.contents.replace(/[\s\uFEFF\r\n\u0016]/g, "");
            var hasTable8 = false;
            try { hasTable8 = item.parentStory && item.parentStory.tables.length > 0; } catch (e6) {}
            if (groupText8.length <= 10 && !hasTable8) {
                var isDeco8 = false;
                try {
                    var fc8 = item.parentStory.characters[0].fillColor;
                    if (fc8 && fc8.name && fc8.name !== "Black" && fc8.name !== "[Black]") isDeco8 = true;
                } catch (e2) { isDeco8 = true; }
                var hasStroke8 = false;
                try {
                    var ch8 = item.parentStory.characters[0];
                    var sc8 = ch8.strokeColor;
                    var sw8 = ch8.strokeWeight;
                    if (sc8 && sc8.name && sc8.name !== "None" && sc8.name !== "[None]" && sw8 && sw8 > 0) {
                        hasStroke8 = true;
                    }
                } catch (e3) {}
                // SPEC-025 Tier B: 회전 bypass 이면 background 분류 건너뛰고 editable 로 보냄
                // SPEC-025: groupShortTextEditable=true 면 Group 안 짧은 텍스트도 editable 유지 (콘텐츠 보존)
                var groupShortBypass = false;
                try {
                    var s25g8 = CONFIG && CONFIG.rendering && CONFIG.rendering.textFrame && CONFIG.rendering.textFrame.spec025;
                    if (s25g8 && s25g8.groupShortTextEditable) groupShortBypass = true;
                } catch (e) {}
                if (isDeco8 && !hasStroke8 && !__spec025RotBypass && !groupShortBypass) return "background";
            }
        }
    } catch (e) {}
    // 8.5. 부모에 배경색이 있는 짧은 텍스트 → renderable (배지)
    try {
        var trimmed85 = item.contents.replace(/[\s\uFEFF\r\n\u0016\uFFFC]/g, "");
        if (trimmed85.length > 0 && trimmed85.length <= 10) {
            var parent85 = item.parent;
            if (parent85 && parent85.constructor.name !== "Story" && parent85.constructor.name !== "Spread" && parent85.constructor.name !== "Page") {
                var pFill85 = "None";
                try { pFill85 = parent85.fillColor ? parent85.fillColor.name : "None"; } catch (e) {}
                if (pFill85 !== "None" && pFill85 !== "[None]") {
                    // SPEC-025 Tier B: 상단에서 계산된 회전 bypass 사용
                    if (!__spec025RotBypass) return "renderable";
                }
            }
        }
    } catch (e) {}
    // 8.7. 같은 Group 안의 멀티레이어 텍스트 컴포지트 → background (개별 frame 추출 안 함)
    // 예: 부록 도비라 "Appendix" 의 그림자 효과 (검정 ppendix + 흰 ppendix 가 같은 Group 안)
    // — 같은 Group 의 TextFrame 형제들 중 하나라도 같은 텍스트 내용을 가지면 컴포지트로 간주.
    // 성능: 형제가 너무 많으면(예: 수식 그룹) O(N²) 회피를 위해 건너뜀.
    try {
        var par87 = item.parent;
        if (par87 && par87.constructor.name === "Group" && par87.textFrames
                && par87.textFrames.length >= 2 && par87.textFrames.length <= 10) {
            var myText87 = "";
            try { myText87 = (item.contents + "").replace(/\s/g, ""); } catch (e) {}
            if (myText87.length > 0 && myText87.length <= 30) {
                for (var st87 = 0; st87 < par87.textFrames.length; st87++) {
                    var sib87 = par87.textFrames[st87];
                    if (sib87.id === item.id) continue;
                    var sibText87 = "";
                    try { sibText87 = (sib87.contents + "").replace(/\s/g, ""); } catch (e) {}
                    if (sibText87 === myText87) {
                        return "background";
                    }
                }
            }
        }
    } catch (e) {
    }
    // 9. 회전/효과/특수 스타일 → 별도 PNG 렌더링
    // SPEC-025 Tier B: 회전 bypass 이면 isRenderableTextFrame 결과 무시 (rotation 자체는 HWPX 가 처리)
    if (!__spec025RotBypass && isRenderableTextFrame(item)) {
        return "renderable";
    }
    // 9.5. 박스 라벨 패턴 (테두리 + 짧은 텍스트) → renderable
    // 예: "QR 54", "QR 55", "QR 56" 보더 박스 라벨. 위치(마진/본문)와 무관하게 적용.
    // (둥근 모서리 여부는 무관 — strokeColor 와 strokeWeight 만으로 판단)
    try {
        var trimmed95 = "";
        try { trimmed95 = item.contents.replace(/[\r\n\s]/g, ""); } catch (e) {}
        if (trimmed95.length > 0 && trimmed95.length <= 10) {
            var sc95 = "None", sw95 = 0;
            try { sc95 = item.strokeColor ? item.strokeColor.name : "None"; } catch (e) {}
            try { sw95 = item.strokeWeight || 0; } catch (e) {}
            var hasStroke95 = (sc95 !== "None" && sc95 !== "[None]") && sw95 > 0;
            // SPEC-025 Tier B: 회전 bypass 이면 box label renderable 분기도 건너뜀
            // SPEC-025: boxLabelEditable=true 면 박스 라벨 (테두리+짧은 텍스트) 도 editable 로
            var s25box = CONFIG && CONFIG.rendering && CONFIG.rendering.textFrame && CONFIG.rendering.textFrame.spec025;
            var skipBoxLabel = (s25box && s25box.boxLabelEditable);
            if (hasStroke95 && !__spec025RotBypass && !skipBoxLabel) {
                return "renderable";
            }
        }
    } catch (e) {}
    // 10. 빈 TextFrame + fill/stroke → 배경에 포함
    try {
        var trimmed10 = item.contents.replace(/[\r\n\s\uFFFC]/g, "");
        if (trimmed10.length === 0) {
            var fc10 = "None", sc10 = "None", sw10 = 0;
            try { fc10 = item.fillColor ? item.fillColor.name : "None"; } catch (e) {}
            try { sc10 = item.strokeColor ? item.strokeColor.name : "None"; } catch (e) {}
            try { sw10 = item.strokeWeight || 0; } catch (e) {}
            if ((fc10 !== "None" && fc10 !== "[None]") ||
                ((sc10 !== "None" && sc10 !== "[None]") && sw10 > 0)) {
                return "background";
            }
        }
    } catch (e) {}
    // 11. 실질적으로 빈 프레임: parentStory.contents가 공백이고 테이블도 없음
    // (IDML Story에는 긴 텍스트가 있지만 InDesign DOM에서는 이 프레임에 표시되지 않음)
    // 단, 테이블이 있으면 editable 유지 (테이블은 contents에 포함되지 않음)
    try {
        var storyText11 = item.parentStory.contents.replace(/[\s\uFEFF\uFFFC\r\n\u0016\u0018]/g, "");
        if (storyText11.length <= 1) {
            var hasTables11 = false;
            try { hasTables11 = item.parentStory.tables.length > 0; } catch (e2) {}
            // SPEC-025 Tier B: 회전된 짧은 라벨 ("1", "2", ...) 은 background 분류 건너뛰고 editable 유지
            // SPEC-025: oneCharEditable=true 면 실제 1자가 있는 프레임도 editable (예: "예", "I" 같은 식별자)
            var oneCharBypass = false;
            try {
                var s25oc = CONFIG && CONFIG.rendering && CONFIG.rendering.textFrame && CONFIG.rendering.textFrame.spec025;
                if (s25oc && s25oc.oneCharEditable && storyText11.length >= 1) oneCharBypass = true;
            } catch (e) {}
            if (!hasTables11 && !__spec025RotBypass && !oneCharBypass) return "background";
        }
    } catch (e) {}
    // 나머지 = 편집 가능 본문 텍스트
    return "editable";
}

/**
 * TextFrame이 이미지 렌더링 대상인지 판별한다.
 * 회전, 스트로크, 그림자 등 HWPX에서 재현 불가한 효과가 있는 경우에만 렌더링.
 */
function isRenderableTextFrame(tf) {
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
            var s25rot = CONFIG && CONFIG.rendering && CONFIG.rendering.textFrame && CONFIG.rendering.textFrame.spec025;
            if (!(s25rot && s25rot.rotationEditable)) return true;
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
            var s25dlt = CONFIG.rendering.textFrame.spec025;
            if (!(s25dlt && s25dlt.decorativeLargeTextEditable)) {
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

        // 장식 스타일 텍스트: 짧은 텍스트(≤10자) + 비검정 + Object Style(배경색/테두리/둥근모서리)
        // SPEC-025: decorativeStyledTextEditable=true 면 장식 라벨/배지도 editable 로
        try {
            var s25dst = CONFIG.rendering.textFrame.spec025;
            if (s25dst && s25dst.decorativeStyledTextEditable) throw new Error("skip-by-spec025");
            var dstCfg = CONFIG.rendering.textFrame.decorativeStyledText;
            if (dstCfg.enabled && trimmed.length <= dstCfg.maxTextLength) {
                var hasObjStyle = false;
                if (dstCfg.requireObjectStyle) {
                    // 배경색 체크
                    try {
                        var fillName = tf.fillColor ? tf.fillColor.name : "None";
                       
                        if (fillName !== "None" && fillName !== "[None]") hasObjStyle = true;
                    } catch (e) {}
                    // 테두리 체크
                    if (!hasObjStyle) {
                        try {
                            var sw95b = tf.strokeWeight || 0;
                            if (sw95b > 0) {
                                var sName = tf.strokeColor ? tf.strokeColor.name : "None";
                               
                                if (sName !== "None" && sName !== "[None]") hasObjStyle = true;
                            }
                        } catch (e) {}
                    }
                    // 부모 객체의 배경색 체크 (TextFrame이 Rectangle 안에 있는 경우)
                    if (!hasObjStyle) {
                        try {
                            var parent = tf.parent;
                            var pName = parent ? parent.constructor.name : "null";
                           
                            if (parent && pName !== "Story" && pName !== "Spread" && pName !== "Page") {
                                var pFill = parent.fillColor ? parent.fillColor.name : "None";
                               
                                if (pFill !== "None" && pFill !== "[None]") hasObjStyle = true;
                            }
                        } catch (e) {}
                    }
                } else {
                    hasObjStyle = true; // requireObjectStyle=false이면 무조건 통과
                }

               
                if (hasObjStyle) {
                    if (!dstCfg.excludeBlack || !isBlackColor(firstChar, dstCfg.blackThreshold)) {
                       
                        return true;
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

// --- resolved 속성 수집 ---

function collectResolved(doc, outputDir, rangePageCount, startPage, endPage, editableIds) {
    writeProgress(outputDir, "resolved_styles", 0, rangePageCount);
    var docInfo = collectDocumentInfo(doc);
    var paraStyles = collectParagraphStyles(doc);
    // SPEC-030 B.3: characterStyles는 Java 측 resolved 파이프라인에서 미사용 — 수집 생략
    // var charStyles = collectCharacterStyles(doc);
    var colors = collectColors(doc);
    var fonts = collectFonts(doc);

    // 범위 내 페이지의 텍스트프레임에 연결된 스토리 ID 수집
    var rangeStoryIds = {};
    try {
        var tfs = doc.textFrames.everyItem().getElements();
        for (var ti = 0; ti < tfs.length; ti++) {
            var tf = tfs[ti];
            try {
                var pp = tf.parentPage;
                if (pp) {
                    var pgIdx = pp.documentOffset + 1;
                    if (pgIdx >= startPage && pgIdx <= endPage) {
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

    // SPEC-025: 마스터 스프레드의 TextFrame 스토리도 rangeStoryIds 에 추가 (Phase 5 master instancing 용)
    // 적용된 마스터에 한해 수집 → 미적용 마스터는 무시.
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
    var textFrames = collectTextFrames(doc, startPage, endPage, editableIds);

    // SPEC-025 Phase 5: 마스터 스프레드 TextFrame 을 적용 페이지마다 인스턴스화 (frame + story clone)
    try { instanceMasterFrames(doc, startPage, endPage, textFrames, stories, editableIds); } catch (ePhase5) { $.writeln("[SPEC-025 Phase 5 error] " + ePhase5); }

    writeProgress(outputDir, "resolved_items", 0, rangePageCount);
    var pages = collectPages(doc, startPage, endPage);
    var pageItems = collectPageItems(doc, startPage, endPage);

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
        var cls = classifyTextFrame(allItems[i]);
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
                                var gpItems = inItem.pageItems;
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
                var cls2 = classifyTextFrame(allItems[ri2]);
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
            if (bItem.constructor.name === "Group" && isBadgeGroup(bItem)) {
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
            if (isBadgeGroup(nbItem)) continue;
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
            if (classifyTextFrame(rtItem) !== "renderable") continue;
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

        // 2. PDF 배경 렌더링
        writeProgress(outputDir, "rendered_frames", pi, doc.pages.length); // pdf 시작 전 heartbeat
        var pdfFileName = "page_bg_" + pi + ".pdf";
        var bgPdfFile = File(renderDir + "/" + pdfFileName);
        try {
            app.pdfExportPreferences.pageRange = page.name;
            doc.exportFile(ExportFormat.PDF_TYPE, bgPdfFile);
        } catch (ePdf) {}

        // 3. PNG 배경도 항상 생성 (Java에서 PDF 래스터화 비활성화 상태)
        writeProgress(outputDir, "rendered_frames", pi, doc.pages.length); // png 시작 전 heartbeat
        var pngFileName = "page_bg_" + pi + ".png";
        var outFile = File(renderDir + "/" + pngFileName);
        try {
            app.pngExportPreferences.pageString = page.name;
            app.pngExportPreferences.pngExportRange = PNGExportRangeEnum.EXPORT_RANGE;
            doc.exportFile(ExportFormat.PNG_FORMAT, outFile);
        } catch (e) {}

        // 4. 숨긴 텍스트 프레임 복원
        for (var ri = 0; ri < hiddenItems.length; ri++) {
            try { hiddenItems[ri].visible = true; } catch (e) {}
        }

        // 4. 결과 추가
        var pageBounds = page.bounds;
        var entry = {
            id: pi,
            file: "rendered_frames/" + pngFileName,
            pdfFile: "rendered_frames/" + pdfFileName,
            pdfPageIndex: 0,
            bounds: [0, 0, pageBounds[2] - pageBounds[0], pageBounds[3] - pageBounds[1]],
            pageIndex: page.documentOffset,
            zOrder: 0,
            type: "page_background",
            childIds: null
        };
        results.push(entry);

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

function collectTextFrames(doc, startPage, endPage, editableIds) {
    if (!editableIds) editableIds = {};
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
                    var storyParas = tf.parentStory.paragraphs.everyItem().getElements();
                    var firstIdx = frameParas[0].index;
                    var lastIdx = frameParas[frameParas.length - 1].index;
                    for (var sp = 0; sp < storyParas.length; sp++) {
                        if (storyParas[sp].index === firstIdx) fData.paragraphStart = sp;
                        if (storyParas[sp].index === lastIdx) fData.paragraphEnd = sp;
                    }

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
                var firstStory = tf.parentStory;
                if (firstStory) {
                    var paras = firstStory.paragraphs.everyItem().getElements();
                    for (var psi = 0; psi < paras.length && psi < 20; psi++) {
                        try {
                            var ps = paras[psi].appliedParagraphStyle;
                            if (ps && ps.name && !seenPs[ps.name]) { seenPs[ps.name] = true; paraStyles.push(ps.name); }
                        } catch (e1) {}
                    }
                    var chars = firstStory.characters.everyItem().getElements();
                    var sampled = Math.min(chars.length, 30);
                    for (var csi = 0; csi < sampled; csi++) {
                        try {
                            var cs = chars[csi].appliedCharacterStyle;
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
                        var sp2 = tf2.parentStory.paragraphs.everyItem().getElements();
                        var fi2 = fp2[0].index;
                        var li2 = fp2[fp2.length - 1].index;
                        for (var sk = 0; sk < sp2.length; sk++) {
                            if (sp2[sk].index === fi2) fData2.paragraphStart = sk;
                            if (sp2[sk].index === li2) fData2.paragraphEnd = sk;
                        }
                        // SPEC-030 B.3: paragraphYOffsets 계산 제거 (Java 신 파이프라인 미사용)
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
    try { s25 = CONFIG && CONFIG.rendering && CONFIG.rendering.textFrame && CONFIG.rendering.textFrame.spec025; } catch (e) {}
    if (!s25 || !(s25.masterPageEditable || s25.nonprintingEditable || s25.hashiraEditable)) return;

    // 1) 페이지 → 적용된 마스터 매핑 (side 정보 포함)
    var masterToPages = {};  // masterSpreadId → [{docIdx, side}, ...]
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
                masterToPages[mid].push({ docIdx: pp, side: pgSide });
            } catch (e) {}
        }
    } catch (e) {}

    // 2) storyId → story 인덱스 매핑 (deep clone 용)
    var storyById = {};
    for (var si = 0; si < stories.length; si++) {
        storyById[stories[si].id] = stories[si];
    }

    // 3) 마스터 스프레드 순회 → 각 TextFrame 인스턴스화
    var msArr = [];
    try { msArr = doc.masterSpreads.everyItem().getElements(); } catch (e) {}
    var frameClones = 0, storyClones = 0;
    for (var ms = 0; ms < msArr.length; ms++) {
        var mspread = msArr[ms];
        var msId = "";
        try { msId = mspread.id.toString(); } catch (e) { continue; }
        var appliedPages = masterToPages[msId] || [];
        if (appliedPages.length === 0) continue;
        var msItems = [];
        try { msItems = mspread.allPageItems; } catch (e) {}
        for (var mi = 0; mi < msItems.length; mi++) {
            var mtf = msItems[mi];
            try { if (mtf.constructor.name !== "TextFrame") continue; } catch (e) { continue; }
            // editable 분류만 인스턴스화 (background/renderable 은 PNG 처리)
            var cls = null;
            try { cls = classifyTextFrame(mtf); } catch (e) {}
            if (cls !== "editable") continue;
            var baseId = ""; try { baseId = mtf.id.toString(); } catch (e) { continue; }
            // SPEC-025: 마스터 TF 가 위치한 master page (LEFT/RIGHT) 식별 →
            // 동일 side 의 doc page 에만 인스턴스 생성 (반대편 페이지로의 잘못된 복제 방지).
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
            // 적용 페이지마다 frame + story clone 추가
            for (var ap = 0; ap < appliedPages.length; ap++) {
                var pgEntry = appliedPages[ap];
                // side 매칭: SINGLE 마스터/페이지는 무조건 통과, LEFT/RIGHT 는 동일 side 만.
                if (mtfSide !== "SINGLE" && pgEntry.side !== "SINGLE" && mtfSide !== pgEntry.side) continue;
                var docPgIdx = pgEntry.docIdx;
                var cloneFrameId = baseId + "_pi" + docPgIdx;
                var cloneStoryId = origStoryId ? (origStoryId + "_pi" + docPgIdx) : null;
                var clone = {
                    id: cloneFrameId,
                    masterSourceId: baseId,
                    isMasterInstance: true,
                    pageIndex: docPgIdx,
                    storyId: cloneStoryId,
                    overflows: false,
                    lineCount: 0,
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
                editableIds[cloneFrameId] = true;
                textFrames.push(clone);
                frameClones++;
                // 스토리도 clone (synthetic id) — Java StoryConverter 가 독립 처리.
                // ExtendScript JSON.stringify 가 일부 객체에서 실패하는 경우가 있어 shallow clone (paragraphs 등 reference 공유).
                if (origStoryId && storyById[origStoryId] && cloneStoryId) {
                    var origSt = storyById[origStoryId];
                    var stClone = {
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

                for (var spi = 0; spi < spreadPageIdxs.length; spi++) {
                    var docPgIdx2 = spreadPageIdxs[spi];
                    var ocCloneId = ocBaseId + "_oc" + docPgIdx2;
                    var ocCloneStoryId = ocStoryId ? (ocStoryId + "_oc" + docPgIdx2) : null;
                    var ocClone = {
                        id: ocCloneId,
                        masterSourceId: ocBaseId,
                        isMasterInstance: true,
                        pageIndex: docPgIdx2,
                        storyId: ocCloneStoryId,
                        overflows: false,
                        lineCount: 0,
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

function collectPages(doc, startPage, endPage) {
    var pages = [];
    for (var i = 0; i < doc.pages.length; i++) {
        var pgIdx = i + 1; // 1-based
        if (pgIdx < startPage || pgIdx > endPage) continue;
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

function collectPageItems(doc, startPage, endPage) {
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

function arrCopy(a) {
    return [a[0], a[1], a[2], a[3]];
}

// --- 유틸리티 ---

function writeJson(path, obj) {
    // SPEC-030 A.6: indent 제거 (`null, 2` → 무인자).
    // 출력 크기 -38% (4.9MB → 3.0MB) — Java Gson 파싱 속도/메모리 사용량/디스크 캐시 모두 개선.
    // (참고: 청크 단위 stream write 시도했으나 ExtendScript f.write() 호출 오버헤드가 커서
    //  실제론 느려짐 → 단일 stringify 가 최적)
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
