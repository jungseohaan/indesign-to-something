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
}

// --- 전역 설정 ---
var CONFIG = null;

// --- conversion-config.json 로더 ---

function loadConversionConfig(configPath) {
    var defaults = {
        rendering: {
            textFrame: { maxTextLength: 30, decorativeLargeText: { enabled: true, minFontSize: 16, excludeBlack: true, blackThreshold: 0.90 }, decorativeStyledText: { enabled: true, maxTextLength: 10, excludeBlack: true, blackThreshold: 0.90, requireObjectStyle: true } },
            badge: { enabled: true, maxSize: 50, maxTextLength: 20, requireShape: true, allowImage: false, badgeDpi: 600 },
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
    CONFIG = loadConversionConfig(configPath);
    // config 디버그 기록
    try {
        var cfgLog = File(outputDir + "/_config_jsx_debug.log");
        cfgLog.encoding = "UTF-8";
        cfgLog.open("w");
        cfgLog.writeln("configPath=" + configPath);
        cfgLog.writeln("pngExportResolution=" + CONFIG.rendering.pngExportResolution);
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

            // 2.6~2.11. 기존 개별 렌더 함수 비활성화 → 페이지 단위 렌더링으로 통합
            var renderedFrames = [];
            var renderedPdfFrames = [];
            var renderedGraphicFrames = [];
            var renderedImageFrames = [];

            // 2.12. 페이지 단위 배경 렌더링
            // 편집 가능한 텍스트 프레임만 숨기고 페이지를 통째로 PDF/PNG 렌더
            _marker(outputDir, "04_pageRendering");
            writeProgress(outputDir, "rendered_frames", 0, rangePageCount);

            var bgResult = exportPageBackgrounds(doc, outputDir, startPage, endPage, allItems);
            var renderedFloatingItems = bgResult.items;
            var editableFrameIds = bgResult.editableFrameIds;
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
                editableIdList.push(parseInt(eid, 10));
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

        // 5. PDF 프리뷰 (HWPX와 함께 출력용)
        try {
            var pdfFile = File(outputDir + "/preview.pdf");

            app.pdfExportPreferences.exportReaderSpreads = spreadMode;
            app.pdfExportPreferences.pageRange = PageRange.ALL_PAGES;
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
function exportRenderedTextFrames(doc, outputDir, startPage, endPage, allItems) {
    var renderDir = Folder(outputDir + "/rendered_frames");
    renderDir.create();

    var renderedFrames = [];
    var renderedIds = {};
    var badgeGroupChildIds = {};

    app.pngExportPreferences.exportResolution = CONFIG.rendering.pngExportResolution || 220;
    app.pngExportPreferences.antiAlias = true;
    app.pngExportPreferences.transparentBackground = true;
    app.pngExportPreferences.pngQuality = PNGQualityEnum.MAXIMUM;

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

        var grpDomId = grp.id;
        if (renderedIds[grpDomId]) continue;

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

        try {
            grp.exportFile(ExportFormat.PNG_FORMAT, grpOutFile);

            var grpBounds = null;
            try { grpBounds = arrCopy(grp.visibleBounds); } catch (e) {}
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
    }

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

        if (isTextFrame && !isRenderableTextFrame(item)) continue;

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

        try {
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

            var entry = {
                id: domId,
                file: "rendered_frames/" + fileName,
                bounds: bounds,
                pageIndex: parentPage.documentOffset
            };
            renderedFrames.push(entry);
            renderedIds[domId] = entry;

            if (renderTarget !== item) {
                renderedFrames.push({
                    id: item.id,
                    file: "rendered_frames/" + fileName,
                    bounds: bounds,
                    pageIndex: parentPage.documentOffset
                });
            }
        } catch (e) {}
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

        $.writeln("[Pass3 MATCH] id=" + cDomId
            + " container=" + containerShape.constructor.name + " area=" + containerArea.toFixed(0)
            + " children=" + cNested.length);

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

        $.writeln("[Pass4 MATCH] id=" + p4Id + " nonRectPolygons=" + nonRectCount
            + " children=" + p4Nested.length);

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
                try { totalTextLen += item.contents.replace(/[\s\uFEFF]/g, "").length; } catch (e) {}
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
        var pageW = group.parentPage.bounds[3] - group.parentPage.bounds[1];
        var scale = 1;
        if (pageW > 0 && pageW < 300) scale = 72 / 25.4;
        else if (pageW > 0 && pageW < 30) scale = 72;
        var minDim = Math.min(gw, gh) * scale;
        if (minDim > cfg.maxSize) return false;
    } catch (e) {
        return false;
    }

    return true;
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

    // 회전된 프레임 → 항상 렌더링 (HWPX에서 회전 텍스트 재현 불가)
    try {
        if (Math.abs(tf.rotationAngle) > 0.1) return true;
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
            if (dss.mode != ShadowMode.NONE) return true;
        } catch (e) {}

        // 외부 광선 (Outer Glow)
        try {
            if (tf.transparencySettings.outerGlowSettings.applied) return true;
        } catch (e) {}

        // 내부 광선 (Inner Glow)
        try {
            if (tf.transparencySettings.innerGlowSettings.applied) return true;
        } catch (e) {}

        // 베벨/엠보스
        try {
            if (tf.transparencySettings.bevelAndEmbossSettings.applied) return true;
        } catch (e) {}

        // 새틴 (Satin)
        try {
            if (tf.transparencySettings.satinSettings.applied) return true;
        } catch (e) {}

        // 프레임 투명도 (불투명도 < 100%)
        try {
            var opacity = tf.transparencySettings.blendingSettings.opacity;
            if (opacity < 100) return true;
        } catch (e) {}

        // 텍스트 수준 드롭 섀도우 (문자에 직접 적용)
        try {
            var charDss = firstChar.transparencySettings.dropShadowSettings;
            if (charDss.mode != ShadowMode.NONE) return true;
        } catch (e) {}

        // 텍스트 수준 투명도
        try {
            var charOpacity = firstChar.transparencySettings.blendingSettings.opacity;
            if (charOpacity < 100) return true;
        } catch (e) {}

        // 장식 대형 컬러 텍스트: fontSize >= minFontSize AND 색상이 검정이 아님
        try {
            var dltCfg = CONFIG.rendering.textFrame.decorativeLargeText;
            if (dltCfg.enabled) {
                var fontSize = firstChar.pointSize;
                if (fontSize >= dltCfg.minFontSize) {
                    if (!dltCfg.excludeBlack || !isBlackColor(firstChar, dltCfg.blackThreshold)) {
                        return true;
                    }
                }
            }
        } catch (e) {}

        // 장식 스타일 텍스트: 짧은 텍스트(≤10자) + 비검정 + Object Style(배경색/테두리/둥근모서리)
        try {
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
                            if (tf.strokeWeight > 0) {
                                var sName = tf.strokeColor ? tf.strokeColor.name : "None";
                                if (sName !== "None" && sName !== "[None]") hasObjStyle = true;
                            }
                        } catch (e) {}
                    }
                    // 둥근 모서리 체크
                    if (!hasObjStyle) {
                        try {
                            if (tf.topLeftCornerOption !== CornerOptions.NONE
                                || tf.topRightCornerOption !== CornerOptions.NONE) hasObjStyle = true;
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
        } catch (e) {}
    } catch (e) {}

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
            if (!hasStyle) {
                try {
                    if (tf.topLeftCornerOption !== CornerOptions.NONE
                        || tf.topRightCornerOption !== CornerOptions.NONE) hasStyle = true;
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
    var charStyles = collectCharacterStyles(doc);
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

    writeProgress(outputDir, "resolved_stories", 0, rangePageCount);
    var stories = collectStories(doc, outputDir, rangePageCount, rangeStoryIds);

    writeProgress(outputDir, "resolved_frames", 0, rangePageCount);
    var textFrames = collectTextFrames(doc, startPage, endPage, editableIds);

    writeProgress(outputDir, "resolved_items", 0, rangePageCount);
    var pages = collectPages(doc, startPage, endPage);
    var pageItems = collectPageItems(doc, startPage, endPage);

    var fontMetrics = measureFontMetrics(doc);

    return {
        documentInfo: docInfo,
        paragraphStyles: paraStyles,
        characterStyles: charStyles,
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
function exportPageBackgrounds(doc, outputDir, startPage, endPage, allItems) {
    var renderDir = Folder(outputDir + "/rendered_frames");
    renderDir.create();

    app.pngExportPreferences.exportResolution = CONFIG.rendering.pngExportResolution || 220;
    app.pngExportPreferences.antiAlias = true;
    app.pngExportPreferences.transparentBackground = true;
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

    // 편집 가능 텍스트 프레임 수집 (숨길 대상)
    // 조건: 본문 텍스트 (긴 텍스트, 비장식)
    var editableFrames = [];
    var editableFrameIds = {};  // id → true (Java에서 글상자 배치 시 사용)
    for (var i = 0; i < allItems.length; i++) {
        var item = allItems[i];
        // 1. TextFrame만 처리
        if (item.constructor.name !== "TextFrame") continue;
        // 2. 숨겨진 레이어
        if (isOnHiddenLayer(item)) continue;
        // 3. 비인쇄
        try { if (item.nonprinting) continue; } catch (e) {}
        // 5. 마스터 페이지 오버라이드
        try {
            if (item.masterPageItem) continue;
        } catch (e) {}
        // 7. 인라인 객체는 부모가 관리
        if (isInlineItem(item)) continue;

        // 8. Group 안의 짧은 장식 TextFrame (10자 이하 + 장식 효과)
        try {
            var parentType = item.parent.constructor.name;
            if (parentType === "Group") {
                var groupText = item.contents.replace(/[\s\uFEFF\r\n]/g, "");
                if (groupText.length <= 10) {
                    var isDecorative = false;
                    try {
                        var chars = item.parentStory.characters;
                        if (chars.length > 0) {
                            var fc = chars[0].fillColor;
                            if (fc && fc.name && fc.name !== "Black" && fc.name !== "[Black]") {
                                isDecorative = true;
                            }
                        }
                    } catch (e2) { isDecorative = true; }
                    if (isDecorative) continue;
                }
            }
        } catch (e) {}

        // 9. 렌더 대상: 글자 1자 이상 + 회전/효과/특수 스타일
        if (isRenderableTextFrame(item)) continue;

        // 11. 빈 TextFrame + 투명 아닌(stroke/fill 있는) → 배경에 포함
        try {
            var trimmedContents = item.contents.replace(/[\r\n\s\uFFFC]/g, "");
            if (trimmedContents.length === 0) {
                var hasVisualProps = false;
                try {
                    var _fc = item.fillColor ? item.fillColor.name : "None";
                    var _sc = item.strokeColor ? item.strokeColor.name : "None";
                    var _sw = item.strokeWeight || 0;
                    if ((_fc !== "None" && _fc !== "[None]") || ((_sc !== "None" && _sc !== "[None]") && _sw > 0)) {
                        hasVisualProps = true;
                    }
                } catch (e2) {}
                if (hasVisualProps) continue;
            }
        } catch (e) {}

        // 나머지 = 편집 가능 본문 텍스트 → 숨겨서 배경에서 제외
        editableFrames.push(item);
        // ID 기록 (Java에서 글상자 배치 시 사용)
        editableFrameIds[item.id] = true;
    }

    // 인라인 객체 추출: 편집 TextFrame 안의 앵커된 그래픽을 개별 PNG로 렌더
    var inlineObjects = [];  // { id, file, parentStoryId, bounds, pageIndex }
    for (var ei = 0; ei < editableFrames.length; ei++) {
        var eTf = editableFrames[ei];
        try {
            var eStory = eTf.parentStory;
            var eAllItems = eTf.allPageItems;
            for (var eai = 0; eai < eAllItems.length; eai++) {
                var inItem = eAllItems[eai];
                // TextFrame 자체는 건너뜀 (자식 객체만)
                if (inItem === eTf) continue;
                var inName = inItem.constructor.name;
                // 인라인 앵커 객체만 (부모가 Character/InsertionPoint/Story)
                if (!isInlineItem(inItem)) continue;

                var inId = inItem.id;
                var inFileName = "inline_" + inId + ".png";
                var inOutFile = File(renderDir + "/" + inFileName);
                try {
                    inItem.exportFile(ExportFormat.PNG_FORMAT, inOutFile);
                    if (inOutFile.exists) {
                        var inBounds = null;
                        try {
                            inBounds = arrCopy(inItem.visibleBounds);
                            if (!inBounds) inBounds = arrCopy(inItem.geometricBounds);
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
                            type: "inline_object"
                        });
                    }
                } catch (eRender) {}
            }
        } catch (eInline) {}
    }

    // ── 페이지별 배경 렌더링 ──
    // PDF 배경은 별도 스크립트(export_pdf_bg.jsx)에서 생성.
    // 여기서는 PNG 폴백 + 결과 항목 생성.

    for (var pi = 0; pi < doc.pages.length; pi++) {
        var page = doc.pages[pi];
        var pgIdx = page.documentOffset + 1;
        if (pgIdx < startPage || pgIdx > endPage) continue;

        // PDF 파일 존재 여부 확인 (export_pdf_bg.jsx가 먼저 실행된 경우)
        var pdfFileName = "page_bg_" + pi + ".pdf";
        var bgPdfFile = File(renderDir + "/" + pdfFileName);
        var hasPdf = bgPdfFile.exists;

        var pngFileName = null;
        if (!hasPdf) {
            // PDF 없음 → PNG 렌더링
            // 1. 해당 페이지의 편집 가능 텍스트 프레임 숨기기
            var hiddenItems = [];
            var pb = page.bounds;
            for (var hi = 0; hi < editableFrames.length; hi++) {
                var tf = editableFrames[hi];
                try {
                    // parentPage 비교 또는 visibleBounds로 페이지 소속 판단
                    var onThisPage = false;
                    try {
                        onThisPage = (tf.parentPage === page);
                    } catch (e) {}
                    if (!onThisPage) {
                        // visibleBounds 폴백: spread 좌표에서 페이지 bounds와 겹침 확인
                        try {
                            var tfb = tf.visibleBounds;
                            // 겹침 확인 (중심점이 아닌 영역 겹침)
                            var overlapH = tfb[3] > pb[1] && tfb[1] < pb[3];
                            var overlapV = tfb[2] > pb[0] && tfb[0] < pb[2];
                            onThisPage = overlapH && overlapV;
                        } catch (e) {}
                    }
                    if (onThisPage && tf.visible) {
                        tf.visible = false;
                        hiddenItems.push(tf);
                    }
                } catch (e) {}
            }

            // 2. 페이지 PNG 렌더링
            pngFileName = "page_bg_" + pi + ".png";
            var outFile = File(renderDir + "/" + pngFileName);
            try {
                app.pngExportPreferences.pageString = page.name;
                app.pngExportPreferences.pngExportRange = PNGExportRangeEnum.EXPORT_RANGE;
                doc.exportFile(ExportFormat.PNG_FORMAT, outFile);
            } catch (e) {}

            // 3. 숨긴 텍스트 프레임 복원
            for (var ri = 0; ri < hiddenItems.length; ri++) {
                try { hiddenItems[ri].visible = true; } catch (e) {}
            }
        }

        // 4. 결과 추가
        var pageBounds = page.bounds;
        var entry = {
            id: pi,
            file: hasPdf ? null : (pngFileName ? ("rendered_frames/" + pngFileName) : null),
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
        $.writeln("[exportPageBackgrounds] " + inlineObjects.length + " inline objects rendered");
    }

    return { items: results, editableFrameIds: editableFrameIds };
}

/**
 * 모든 플로팅(비인라인) 그래픽을 PNG로 렌더링한다.
 * 인라인 객체, 본문 TextFrame, 숨겨진 레이어, Nonprinting 요소는 제외.
 *
 * @return {Array} renderedFloatingItems 배열
 */
function exportAllFloatingItems(doc, outputDir, startPage, endPage, allItems, alreadyRenderedIds) {
    if (!alreadyRenderedIds) alreadyRenderedIds = {};
    var renderDir = Folder(outputDir + "/rendered_frames");
    renderDir.create();

    app.pngExportPreferences.exportResolution = CONFIG.rendering.pngExportResolution || 220;
    app.pngExportPreferences.antiAlias = true;
    app.pngExportPreferences.transparentBackground = true;
    app.pngExportPreferences.pngQuality = PNGQualityEnum.MAXIMUM;

    var results = [];
    var processedIds = {};  // DOM id → true (중복 방지)
    var childCoveredIds = {};  // Group childIds → true (자식 개별 렌더 방지)
    var zCounter = {};  // pageIndex → counter

    // 렌더 대상 타입
    var RENDER_TYPES = {
        "Rectangle": true, "Polygon": true, "Oval": true,
        "GraphicLine": true, "Group": true, "TextFrame": true
    };

    // 레이어 순서 맵: doc.layers는 위→아래 순서 (index 0 = top)
    var layerIndexMap = {};  // layer name → index
    try {
        var docLayers = doc.layers;
        for (var li = 0; li < docLayers.length; li++) {
            layerIndexMap[docLayers[li].name] = li;
        }
    } catch (e) {}

    // 페이지별 "커버 레이어": 페이지의 80% 이상을 덮는 불투명 객체의 레이어 인덱스
    // 이 레이어보다 아래(인덱스 큰) 레이어의 객체는 가려져서 렌더 불필요
    var pageCoverLayerIdx = {};  // pageDocOffset → layerIndex

    // 1차 패스: 커버 레이어 탐지
    for (var ci = 0; ci < allItems.length; ci++) {
        var cItem = allItems[ci];
        var cName2 = cItem.constructor.name;
        if (cName2 !== "Rectangle" && cName2 !== "Polygon" && cName2 !== "Group") continue;
        try {
            var cPage = cItem.parentPage;
            if (!cPage) continue;
            var cBounds = cItem.geometricBounds;
            var cPageBounds = cPage.bounds;
            var cItemW = cBounds[3] - cBounds[1];
            var cItemH = cBounds[2] - cBounds[0];
            var cPageW = cPageBounds[3] - cPageBounds[1];
            var cPageH = cPageBounds[2] - cPageBounds[0];
            var coverage = (cItemW * cItemH) / (cPageW * cPageH);
            if (coverage > 0.8) {
                var cLayerName = cItem.itemLayer.name;
                var cLayerIdx = layerIndexMap[cLayerName];
                if (cLayerIdx !== undefined) {
                    var pgOff = cPage.documentOffset;
                    if (pageCoverLayerIdx[pgOff] === undefined || cLayerIdx < pageCoverLayerIdx[pgOff]) {
                        pageCoverLayerIdx[pgOff] = cLayerIdx;
                    }
                }
            }
        } catch (e) {}
    }

    for (var i = 0; i < allItems.length; i++) {
        var item = allItems[i];
        var cName = item.constructor.name;
        if (!RENDER_TYPES[cName]) continue;

        var domId = item.id;

        // 이미 처리된 항목 건너뜀
        if (processedIds[domId]) continue;
        // 기존 렌더 함수에서 이미 렌더된 항목 건너뜀
        if (alreadyRenderedIds[domId]) continue;
        // 부모 Group에 의해 이미 커버된 자식 건너뜀
        if (childCoveredIds[domId]) continue;

        // 숨겨진 레이어
        if (isOnHiddenLayer(item)) continue;
        // Nonprinting
        try { if (item.nonprinting) continue; } catch (e) {}
        // 인라인(앵커) 객체 제외
        if (isInlineItem(item)) continue;

        // TextFrame: 본문 텍스트는 제외, 장식/배지만 렌더
        if (cName === "TextFrame") {
            if (!isRenderableTextFrame(item)) continue;
        }

        // 페이지 판별
        var parentPage = null;
        try { parentPage = item.parentPage; } catch (e) {}
        if (!parentPage) {
            // parentPage 없으면 visibleBounds로 페이지 매칭
            try {
                var vb = item.visibleBounds;
                var cy = (vb[0] + vb[2]) / 2;
                var cx = (vb[1] + vb[3]) / 2;
                for (var pi = 0; pi < doc.pages.length; pi++) {
                    var pg = doc.pages[pi];
                    var pb = pg.bounds;
                    if (cy >= pb[0] && cy <= pb[2] && cx >= pb[1] && cx <= pb[3]) {
                        parentPage = pg;
                        break;
                    }
                }
            } catch (e) {}
        }
        if (!parentPage) continue;
        var pgIdx = parentPage.documentOffset + 1;
        if (pgIdx < startPage || pgIdx > endPage) continue;

        // 레이어 필터: 커버 레이어보다 아래 레이어의 객체는 가려져서 렌더 불필요
        try {
            var itemLayerIdx = layerIndexMap[item.itemLayer.name];
            var coverIdx = pageCoverLayerIdx[parentPage.documentOffset];
            if (itemLayerIdx !== undefined && coverIdx !== undefined && itemLayerIdx > coverIdx) {
                continue;  // 배경에 가려진 하위 레이어 객체
            }
        } catch (e) {}

        // Group: 그룹 전체를 렌더하고 자식을 커버 처리
        if (cName === "Group") {
            try {
                var grpItems = item.allPageItems;
                for (var gi = 0; gi < grpItems.length; gi++) {
                    childCoveredIds[grpItems[gi].id] = true;
                }
            } catch (e) {}
        }

        // 렌더 대상 결정: TextFrame의 부모가 도형이면 도형째로 렌더
        var renderTarget = item;
        if (cName === "TextFrame") {
            try {
                var p = item.parent;
                var pn = p ? p.constructor.name : "";
                if (p && p.id && (pn === "Rectangle" || pn === "Polygon"
                    || pn === "GraphicLine" || pn === "Oval")) {
                    renderTarget = p;
                }
            } catch (e) {}
        }

        var targetId = renderTarget.id;
        if (processedIds[targetId]) continue;

        // PNG 렌더링
        var fileName = "float_" + targetId + ".png";
        var outFile = File(renderDir + "/" + fileName);
        var renderSuccess = false;

        try {
            renderTarget.exportFile(ExportFormat.PNG_FORMAT, outFile);
            renderSuccess = outFile.exists;
        } catch (e) {
            renderSuccess = false;
        }

        // bounds 계산 (page-relative)
        var bounds = null;
        try {
            bounds = arrCopy(renderTarget.visibleBounds);
            if (!bounds) bounds = arrCopy(renderTarget.geometricBounds);
            if (bounds) {
                var pageBounds = parentPage.bounds;
                bounds[0] -= pageBounds[0];
                bounds[1] -= pageBounds[1];
                bounds[2] -= pageBounds[0];
                bounds[3] -= pageBounds[1];
            }
        } catch (e) {}

        // childIds 수집 (Group인 경우)
        var childIds = null;
        if (cName === "Group") {
            childIds = [];
            try {
                var gItems = item.allPageItems;
                for (var ci = 0; ci < gItems.length; ci++) {
                    childIds.push(gItems[ci].id);
                }
            } catch (e) {}
        }

        // z-order (페이지 내 순서)
        var pageIdx = parentPage.documentOffset;
        if (!zCounter[pageIdx]) zCounter[pageIdx] = 0;
        var zOrder = zCounter[pageIdx]++;

        // 결과 추가
        var entry = {
            id: targetId,
            file: renderSuccess ? ("rendered_frames/" + fileName) : null,
            bounds: bounds,
            pageIndex: pageIdx,
            zOrder: zOrder,
            type: cName === "TextFrame" ? "text_decoration"
                : cName === "Group" ? "group"
                : (cName === "Rectangle" || cName === "Polygon" || cName === "Oval" || cName === "GraphicLine") ? "vector"
                : "other",
            childIds: childIds
        };
        results.push(entry);
        processedIds[targetId] = true;
        processedIds[domId] = true;
    }

    return results;
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

function collectParagraphStyles(doc) {
    var styles = [];
    for (var i = 0; i < doc.allParagraphStyles.length; i++) {
        var ps = doc.allParagraphStyles[i];
        try {
            var data = {
                name: ps.name,
                basedOn: ps.basedOn ? ps.basedOn.name : null,
                fontFamily: null,
                fontStyle: ps.fontStyle,
                fontSize: ps.pointSize,
                leading: ps.leading,
                autoLeading: ps.autoLeading,
                justification: ps.justification.toString(),
                spaceBefore: ps.spaceBefore,
                spaceAfter: ps.spaceAfter,
                firstLineIndent: ps.firstLineIndent,
                leftIndent: ps.leftIndent,
                rightIndent: ps.rightIndent,
                hyphenation: ps.hyphenation,
                dropCapLines: ps.dropCapLines,
                dropCapCharacters: ps.dropCapCharacters,
                keepWithNext: null,
                keepAllLinesTogether: null
            };
            try { data.fontFamily = ps.appliedFont ? ps.appliedFont.fontFamily : null; } catch (e) {}
            try { data.keepWithNext = ps.keepWithNext.toString(); } catch (e) {}
            try { data.keepAllLinesTogether = ps.keepAllLinesTogether.toString(); } catch (e) {}
            // 탭 정지
            try {
                var ts = ps.tabStops;
                if (ts && ts.length > 0) {
                    data.tabStops = [];
                    for (var t = 0; t < ts.length; t++) {
                        data.tabStops.push({
                            position: ts[t].position,
                            alignment: ts[t].alignment.toString(),
                            leader: ts[t].leader || ""
                        });
                    }
                }
            } catch (e) {}
            styles.push(data);
        } catch (e) {
            styles.push({ name: ps.name, error: e.message });
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

    // 단락 인덱스 추적: 라인의 contents에 \r이 있으면 단락 끝
    var paraIndex = 0;

    for (var li = 0; li < lines.length; li++) {
        var line = lines[li];
        var lineData = {
            bounds: null,
            text: "",
            paraIndex: paraIndex
        };

        // line에는 geometricBounds가 없으므로 baseline/ascent/descent/horizontalOffset으로 계산
        try {
            var bl = line.baseline;
            var asc = line.ascent;
            var desc = line.descent;
            var hOff = line.horizontalOffset;
            var endH = line.endHorizontalOffset;
            lineData.bounds = [bl - asc, hOff, bl + desc, endH];
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
 * GREP/중첩 스타일 보정: textStyleRange 내에서 문자별 fillColor가 다르면 런을 분할.
 * 성능을 위해 첫 문자와 마지막 문자의 색상만 먼저 비교하고, 같으면 분할하지 않음.
 */
function splitRunByCharColor(rng, runData) {
    try {
        var chars = rng.characters.everyItem().getElements();
        if (chars.length <= 1) return [runData];

        // 첫 문자와 마지막 문자 색상 비교 (빠른 체크)
        var firstColor = null, lastColor = null;
        try { firstColor = chars[0].fillColor ? chars[0].fillColor.name : null; } catch (e) {}
        try { lastColor = chars[chars.length - 1].fillColor ? chars[chars.length - 1].fillColor.name : null; } catch (e) {}
        if (firstColor === lastColor) return [runData]; // 색상 동일 → 분할 불필요

        // 문자별 스캔하여 색상 변화 지점에서 분할
        var result = [];
        var curColor = firstColor;
        var curText = "";
        for (var ci = 0; ci < chars.length; ci++) {
            var chColor = null;
            try { chColor = chars[ci].fillColor ? chars[ci].fillColor.name : null; } catch (e) {}
            if (chColor !== curColor && curText.length > 0) {
                // 색상 변경 → 이전 런 저장
                var splitRun = {};
                for (var k in runData) { if (runData.hasOwnProperty(k)) splitRun[k] = runData[k]; }
                splitRun.text = curText;
                splitRun.fillColor = curColor;
                result.push(splitRun);
                curText = "";
                curColor = chColor;
            }
            try { curText += chars[ci].contents; } catch (e) {}
        }
        // 마지막 런
        if (curText.length > 0) {
            var lastRun = {};
            for (var k2 in runData) { if (runData.hasOwnProperty(k2)) lastRun[k2] = runData[k2]; }
            lastRun.text = curText;
            lastRun.fillColor = curColor;
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
            try {
                var ranges = para.textStyleRanges.everyItem().getElements();
                for (var r = 0; r < ranges.length; r++) {
                    var rng = ranges[r];
                    var runData = {
                        text: rng.contents,
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
                    // 확장 속성
                    try { runData.tracking = rng.tracking; } catch (e) {}
                    try { runData.horizontalScale = rng.horizontalScale; } catch (e) {}
                    try { runData.verticalScale = rng.verticalScale; } catch (e) {}
                    try { runData.baselineShift = rng.baselineShift; } catch (e) {}
                    try { runData.position = rng.position.toString(); } catch (e) {}
                    try { runData.underline = rng.underline; } catch (e) {}
                    try { runData.strikeThru = rng.strikeThru; } catch (e) {}

                    // U+FFFC(인라인 객체 마커) 분리
                    var runText = runData.text || "";
                    if (runText.indexOf("\uFFFC") >= 0) {
                        // U+FFFC를 기준으로 텍스트 분할, 마커 위치에 inline_anchor 삽입
                        var parts = runText.split("\uFFFC");
                        // 인라인 객체 찾기: 이 range 내의 anchored objects
                        var anchoredIds = [];
                        try {
                            var rngChars = rng.characters.everyItem().getElements();
                            for (var rc = 0; rc < rngChars.length; rc++) {
                                if (rngChars[rc].contents === "\uFFFC") {
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
                                paraData.runs.push(partRun);
                            }
                            // U+FFFC 마커 (마지막 part 뒤에는 없음)
                            if (pi2 < parts.length - 1) {
                                paraData.runs.push({
                                    type: "inline_anchor",
                                    anchoredObjectId: anchorIdx < anchoredIds.length ? anchoredIds[anchorIdx] : null
                                });
                                anchorIdx++;
                            }
                        }
                    } else {
                        // GREP/중첩 스타일 색상 보정: 런 내에서 문자별 색상이 다르면 분할
                        var splitRuns = splitRunByCharColor(rng, runData);
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
                                        cpData.runs.push(runData);
                                    }
                                } catch (ec2) {}
                                cellData.paragraphs.push(cpData);
                            }
                        } catch (ec3) {}
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

    // DEBUG: editableIds 상태를 파일로 기록
    var _dbgLines = [];
    var _eidCount = 0;
    var _sampleKeys = [];
    for (var _ek in editableIds) {
        if (editableIds.hasOwnProperty(_ek)) {
            _eidCount++;
            if (_sampleKeys.length < 5) _sampleKeys.push(_ek + "(" + typeof _ek + ")");
        }
    }
    _dbgLines.push("editableIds count=" + _eidCount + " samples=" + _sampleKeys.join(","));

    try {
        // doc.textFrames는 페이지 소속 프레임만 포함할 수 있으므로
        // allPageItems에서 TextFrame을 수집하여 Spread 직속 프레임도 포함
        var allItems = doc.allPageItems;
        var tfs = [];
        for (var ai = 0; ai < allItems.length; ai++) {
            if (allItems[ai].constructor.name === "TextFrame") tfs.push(allItems[ai]);
        }
        _dbgLines.push("tfs count=" + tfs.length);

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

                    // 각 단락의 첫 줄 Y좌표 (프레임 상단 기준 오프셋, points)
                    var frameBounds = tf.geometricBounds; // [top, left, bottom, right]
                    var frameTop = frameBounds[0];
                    var paraYOffsets = [];
                    for (var fp = 0; fp < frameParas.length; fp++) {
                        try {
                            var lines = frameParas[fp].lines.everyItem().getElements();
                            if (lines.length > 0) {
                                // 첫 줄의 baseline을 사용하되, ascent 보정을 위해 bounds 사용
                                var lineBounds = lines[0].geometricBounds; // [top, left, bottom, right]
                                paraYOffsets.push(Math.round((lineBounds[0] - frameTop) * 100) / 100);
                            } else {
                                paraYOffsets.push(-1);
                            }
                        } catch (e2) {
                            paraYOffsets.push(-1);
                        }
                    }
                    fData.paragraphYOffsets = paraYOffsets;

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
                        var fb2 = tf2.geometricBounds;
                        var ft2 = fb2[0];
                        var py2 = [];
                        for (var fk = 0; fk < fp2.length; fk++) {
                            try {
                                var ln2 = fp2[fk].lines.everyItem().getElements();
                                if (ln2.length > 0) {
                                    py2.push(Math.round((ln2[0].geometricBounds[0] - ft2) * 100) / 100);
                                } else { py2.push(-1); }
                            } catch (e3) { py2.push(-1); }
                        }
                        fData2.paragraphYOffsets = py2;
                    }
                } catch (e) {}
                try { fData2.geometricBounds = [tf2.geometricBounds[0], tf2.geometricBounds[1], tf2.geometricBounds[2], tf2.geometricBounds[3]]; } catch (e) {}
                try { fData2.columnCount = tf2.textFramePreferences.textColumnCount; } catch (e) {}
                try { fData2.columnGutter = tf2.textFramePreferences.textColumnGutter; } catch (e) {}
                try { var is2 = tf2.textFramePreferences.insetSpacing; fData2.insetSpacing = [is2[0], is2[1], is2[2], is2[3]]; } catch (e) {}
                try { fData2.verticalJustification = tf2.textFramePreferences.verticalJustification.toString(); } catch (e) {}
                try { fData2.rotationAngle = tf2.absoluteRotationAngle; } catch (e) {}
                frames.push(fData2);
                $.writeln("[collectTextFrames] Pass2: added thread frame id=" + tf2.id + " storyId=" + sid2);
            }
        }
    } catch (e) {
        // 텍스트 프레임 접근 실패 시 무시
    }
    // DEBUG: 로그 파일 출력
    _dbgLines.push("frames=" + frames.length);
    try {
        var _dbgFile = File("/tmp/_debug_collectTF.log");
        _dbgFile.encoding = "UTF-8";
        _dbgFile.open("w");
        _dbgFile.write(_dbgLines.join("\n"));
        _dbgFile.close();
    } catch (e) {}
    return frames;
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
        try { data.visibleBounds = arrCopy(pi.visibleBounds); } catch (e) {}

        // 절대 변환
        try { data.absoluteRotationAngle = pi.absoluteRotationAngle; } catch (e) {}
        try { data.absoluteShearAngle = pi.absoluteShearAngle; } catch (e) {}
        try { data.absoluteHorizontalScale = pi.absoluteHorizontalScale; } catch (e) {}
        try { data.absoluteVerticalScale = pi.absoluteVerticalScale; } catch (e) {}
        try { data.absoluteFlip = pi.absoluteFlip.toString(); } catch (e) {}

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

        // 그라디언트 페더
        try {
            var gfs = pi.transparencySettings.gradientFeatherSettings;
            if (gfs && gfs.applied) {
                data.gradientFeather = {
                    applied: true,
                    angle: gfs.angle,
                    length: gfs.length,
                    type: gfs.type.toString()
                };
            }
        } catch (e) {}

        // 드롭 섀도우
        try {
            var ds = pi.transparencySettings.dropShadowSettings;
            if (ds) {
                var dsMode = ds.mode.toString();
                if (dsMode !== "ShadowMode.NONE") {
                    data.dropShadow = {
                        angle: ds.angle,
                        distance: ds.distance,
                        size: ds.size,
                        opacity: ds.opacity
                    };
                    try { data.dropShadow.colorName = ds.effectColor ? ds.effectColor.name : null; } catch (e2) {}
                }
            }
        } catch (e) {}

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
            try { data.clipContent = pi.clipContent; } catch (e) {}
        }

        // NEW: page-relative bounds
        if (piPageIdx >= 0) {
            try {
                var piPage = pi.parentPage;
                if (piPage && data.geometricBounds) {
                    var piPB = piPage.bounds;
                    data.pageRelativeBounds = [
                        data.geometricBounds[0] - piPB[0],
                        data.geometricBounds[1] - piPB[1],
                        data.geometricBounds[2] - piPB[0],
                        data.geometricBounds[3] - piPB[1]
                    ];
                }
            } catch (e) {}
        }

        items.push(data);
    }
    return items;
}

function arrCopy(a) {
    return [a[0], a[1], a[2], a[3]];
}

// --- 유틸리티 ---

function writeJson(path, obj) {
    var f = File(path);
    f.encoding = "UTF-8";
    f.open("w");
    f.write(JSON.stringify(obj, null, 2));
    f.close();
}

function writeProgress(outputDir, step, current, total) {
    var obj = { step: step, current: current, total: total };
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
