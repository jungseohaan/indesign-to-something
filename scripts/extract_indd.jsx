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
    JSON.stringify = function (val, replacer, space) {
        var indent = "";
        var gap = "";
        if (typeof space === "number") {
            for (var s = 0; s < space; s++) gap += " ";
        } else if (typeof space === "string") {
            gap = space;
        }
        return _jsonSerialize("", { "": val }, gap, indent);
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

    function _jsonSerialize(key, holder, gap, indent) {
        var val = holder[key];
        if (val === null) return "null";
        if (val === undefined) return "null";
        var t = typeof val;
        if (t === "boolean") return val ? "true" : "false";
        if (t === "number") {
            if (isFinite(val)) return String(val);
            return "null";
        }
        if (t === "string") return _jsonQuote(val);
        // Array
        if (val instanceof Array) {
            var arrParts = [];
            var newIndent = indent + gap;
            for (var i = 0; i < val.length; i++) {
                arrParts.push(_jsonSerialize(String(i), val, gap, newIndent));
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
                    var v = _jsonSerialize(k, val, gap, newIndent2);
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

        writeProgress(outputDir, "open", 0, 0);

        // 0. 이전 배치에서 닫히지 않은 문서 정리
        try {
            while (app.documents.length > 0) {
                app.documents[0].close(SaveOptions.NO);
            }
        } catch (e) {}

        // 1. 문서 열기 (창 표시 안 함)
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
            writeProgress(outputDir, "idml", 0, pageCount);

            // 2. IDML 내보내기 (전체 — API 제한)
            var idmlFile = File(outputDir + "/output.idml");
            doc.exportFile(ExportFormat.INDESIGN_MARKUP, idmlFile);

            // 2.5. allPageItems 1회 수집 (성능 최적화: 6회 → 1회)
            var allItems = doc.allPageItems;

            // 2.6. 짧은 텍스트 프레임 렌더링
            writeProgress(outputDir, "rendered_frames", 0, rangePageCount);
            var renderedResult = exportRenderedTextFrames(doc, outputDir, startPage, endPage, allItems);
            var renderedFrames = renderedResult.frames;
            var badgeChildIds = renderedResult.badgeChildIds;

            // 2.7. PDF 배치 프레임 렌더링 (멀티페이지 PDF 지원)
            var renderedPdfFrames = exportPdfPlacedFrames(doc, outputDir, startPage, endPage, allItems);

            // 2.8. 복합 장식 그래픽 렌더링 (중첩 도형, 사선 패턴 등)
            var renderedGraphicFrames = exportComplexGraphicFrames(doc, outputDir, startPage, endPage, allItems);

            // 2.9. 벡터 도형/그룹 InDesign 렌더링
            var renderedVectorFrames = exportVectorShapeFrames(doc, outputDir, startPage, endPage, badgeChildIds, allItems);
            for (var vi = 0; vi < renderedVectorFrames.length; vi++) {
                renderedGraphicFrames.push(renderedVectorFrames[vi]);
            }

            // 2.10. 이미지 배치 프레임 렌더링 (PSD, AI 등 → PNG)
            var renderedImageFrames = [];
            try {
                renderedImageFrames = exportImagePlacedFrames(doc, outputDir, startPage, endPage, allItems);
            } catch (imgEx) {}

            writeProgress(outputDir, "resolved", 0, rangePageCount);

            // 3. resolved 속성 수집 (페이지 범위 필터링)
            var resolved = collectResolved(doc, outputDir, rangePageCount, startPage, endPage);
            resolved.renderedTextFrames = renderedFrames;
            resolved.renderedPdfFrames = renderedPdfFrames;
            resolved.renderedGraphicFrames = renderedGraphicFrames;
            resolved.renderedImageFrames = renderedImageFrames;
            writeJson(outputDir + "/resolved.json", resolved);
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

        // 5. PDF 프리뷰
        try {
            var pdfFile = File(outputDir + "/preview.pdf");

            app.pdfExportPreferences.exportReaderSpreads = spreadMode;

            // 이미지: 300 DPI 다운샘플링 + JPEG HIGH 압축
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

            // 프레임 밖 이미지 제외, 텍스트/벡터 압축
            app.pdfExportPreferences.cropImagesToFrames = true;
            app.pdfExportPreferences.compressTextAndLineArt = true;

            // PDF 버전, 폰트 서브셋
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

    app.pngExportPreferences.exportResolution = 300;
    app.pngExportPreferences.antiAlias = true;
    app.pngExportPreferences.transparentBackground = true;
    app.pngExportPreferences.pngQuality = PNGQualityEnum.MAXIMUM;

    // Pass 1: 배지 그룹 감지 및 렌더링
    for (var gi = 0; gi < allItems.length; gi++) {
        var grp = allItems[gi];
        if (grp.constructor.name !== "Group") continue;
        if (isOnHiddenLayer(grp)) continue;
        if (!isBadgeGroup(grp)) continue;

        var grpPage = null;
        try { grpPage = grp.parentPage; } catch (e) {}
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

            renderedGraphicFrames.push({
                id: domId,
                file: "rendered_frames/" + fileName,
                bounds: bounds,
                pageIndex: parentPage.documentOffset
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
            var hiddenTFs = isGroupRender ? hideTextFrames(renderTarget) : [];
            renderTarget.exportFile(ExportFormat.PNG_FORMAT, outFile);
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
 * 벡터 도형을 PNG로 렌더링한다.
 */
function exportVectorShapeFrames(doc, outputDir, startPage, endPage, badgeChildIds, allItems) {
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

        var hasPlaced = false;
        try { hasPlaced = item.images && item.images.length > 0; } catch (e) {}
        if (!hasPlaced) try { hasPlaced = item.pdfs && item.pdfs.length > 0; } catch (e) {}
        if (!hasPlaced) try { hasPlaced = item.epss && item.epss.length > 0; } catch (e) {}
        if (hasPlaced) continue;

        try {
            var pName = item.parent.constructor.name;
            if (pName === "TextFrame" || pName === "Character"
                || pName === "InsertionPoint" || pName === "Cell"
                || pName === "Story") continue;
        } catch (e) {}

        var parentPage = null;
        try { parentPage = item.parentPage; } catch (e) {}
        if (!parentPage) continue;
        var pgIdx = parentPage.documentOffset + 1;
        if (pgIdx < startPage || pgIdx > endPage) continue;

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
    var hasShape = false;
    var hasShortText = false;
    var hasLongText = false;
    var hasImage = false;
    var hasSubGroup = false;
    var totalTextLen = 0;

    try {
        var items = group.allPageItems;
        for (var i = 0; i < items.length; i++) {
            var item = items[i];
            var cName = item.constructor.name;
            if (cName === "Rectangle" || cName === "Polygon"
                || cName === "Oval" || cName === "GraphicLine") {
                // 이미지가 포함된 Rectangle은 도형이 아닌 이미지 컨테이너
                try {
                    if (item.images && item.images.length > 0) { hasImage = true; continue; }
                    if (item.epss && item.epss.length > 0) { hasImage = true; continue; }
                    if (item.pdfs && item.pdfs.length > 0) { hasImage = true; continue; }
                } catch (e) {}
                hasShape = true;
                // TextPath가 있는 도형: 곡선 텍스트도 배지 텍스트로 인식
                try {
                    if (item.textPaths && item.textPaths.length > 0) {
                        for (var tp = 0; tp < item.textPaths.length; tp++) {
                            var tpTxt = "";
                            try { tpTxt = item.textPaths[tp].texts[0].contents; } catch (e2) {}
                            var tpTrimmed = tpTxt.replace(/[\s\uFEFF]/g, "");
                            totalTextLen += tpTrimmed.length;
                            if (tpTrimmed.length > 0 && tpTrimmed.length <= 15) {
                                hasShortText = true;
                            }
                            if (tpTrimmed.length > 30) {
                                hasLongText = true;
                            }
                        }
                    }
                } catch (e) {}
            } else if (cName === "TextFrame") {
                var txt = "";
                try { txt = item.contents; } catch (e) {}
                var trimmed = txt.replace(/[\s\uFEFF]/g, "");
                totalTextLen += trimmed.length;
                // 폰트 크기 12pt 초과 → 배지 아님 (제목/장식 텍스트)
                try {
                    if (item.parentStory.characters[0].pointSize > 12) return false;
                } catch (e) {}
                if (trimmed.length > 0 && trimmed.length <= 15) {
                    hasShortText = true;
                }
                if (trimmed.length > 30) {
                    hasLongText = true;
                }
            } else if (cName === "Group") {
                hasSubGroup = true;
            } else if (cName === "Image" || cName === "EPS" || cName === "PDF") {
                hasImage = true;
            }
        }
    } catch (e) {
        return false;
    }

    // 배지: 도형+짧은텍스트, 이미지/서브그룹 없음, 긴 텍스트 없음
    if (!(hasShape && hasShortText && !hasImage && !hasSubGroup && !hasLongText)) return false;

    // 전체 텍스트가 너무 많으면 배지가 아님 (콘텐츠 박스)
    if (totalTextLen > 40) return false;

    // 크기 제한: geometricBounds는 문서 측정 단위로 반환되므로
    // points로 변환하여 비교
    // TextPath 포함 그룹은 곡선 경로로 인해 바운딩 박스가 크므로 제한 완화 (350pt)
    var hasTextPathInGroup = false;
    try {
        for (var ti = 0; ti < items.length; ti++) {
            try {
                if (items[ti].textPaths && items[ti].textPaths.length > 0) {
                    hasTextPathInGroup = true; break;
                }
            } catch (e) {}
        }
    } catch (e) {}
    var maxSize = hasTextPathInGroup ? 350 : 150;
    try {
        var gb = group.geometricBounds; // [top, left, bottom, right]
        var gw = gb[3] - gb[1];
        var gh = gb[2] - gb[0];
        // 문서 단위 → points 변환 (페이지 폭 비교)
        var pageW = group.parentPage.bounds[3] - group.parentPage.bounds[1];
        var scale = 1;
        if (pageW > 0 && pageW < 300) scale = 72 / 25.4; // mm → pt
        else if (pageW > 0 && pageW < 30) scale = 72; // inch → pt
        if (gw * scale > maxSize || gh * scale > maxSize) return false;
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

    // 공백 제거 후 길이 판정
    var trimmed = text.replace(/[\s\uFEFF]/g, "");
    if (trimmed.length === 0) return false;
    if (trimmed.length >= 30) return false;

    // 회전된 프레임 → 항상 렌더링 (HWPX에서 회전 텍스트 재현 불가)
    try {
        if (Math.abs(tf.rotationAngle) > 0.1) return true;
    } catch (e) {}

    // 텍스트에 효과가 적용된 경우 → 렌더링 (HWPX에서 재현 불가)
    try {
        var firstChar = tf.parentStory.characters[0];

        // 텍스트 스트로크(외곽선) — strokeWeight > 0이면 아웃라인 효과
        try {
            if (firstChar.strokeWeight > 0) return true;
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
    } catch (e) {}

    return false;
}

/**
 * 텍스트가 흰색 또는 희미한 색상인지 판별.
 * 배경이 없으면 보이지 않는 텍스트 → 렌더링 대상.
 */
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

function collectResolved(doc, outputDir, rangePageCount, startPage, endPage) {
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
    var textFrames = collectTextFrames(doc, startPage, endPage);

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
 * 문서에 사용된 폰트의 글리프 메트릭을 측정한다.
 * 임시 TextFrame을 생성하여 한글/영문 샘플 텍스트의 폭, weight, x-height, ascent/descent를 측정.
 */
function measureFontMetrics(doc) {
    var korSample = "\uAC00\uB098\uB2E4\uB77C\uB9C8\uBC14\uC0AC\uC544\uC790\uCC28\uCE74\uD0C0\uD30C\uD558"; // 가나다라마바사아자차카타파하
    var latSample = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    var testSize = 10; // 10pt 기준

    // 문서에서 사용된 폰트 패밀리 수집
    var usedFonts = {};
    var stories = doc.stories;
    for (var i = 0; i < stories.length; i++) {
        var chars = stories[i].characters;
        var step = Math.max(1, Math.floor(chars.length / 20)); // 최대 20개 샘플링
        for (var j = 0; j < chars.length; j += step) {
            try {
                var af = chars[j].appliedFont;
                var ff = af.fontFamily;
                if (!usedFonts[ff]) {
                    usedFonts[ff] = {
                        font: af,
                        style: af.fontStyleName
                    };
                }
            } catch(e) {}
        }
    }

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
            var fontRef = fontInfo.font;

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

            // 한글 폭 측정
            var avgKorWidth = 0;
            try {
                tf.contents = korSample;
                tf.characters.everyItem().appliedFont = fontRef;
                tf.characters.everyItem().pointSize = testSize;
                tf.characters.everyItem().tracking = 0;
                tf.characters.everyItem().desiredLetterSpacing = 0;
                if (tf.lines.length > 0) {
                    var korTotalWidth = tf.lines[0].endHorizontalOffset - tf.lines[0].horizontalOffset;
                    avgKorWidth = korTotalWidth / korSample.length;
                }
            } catch(e2) { /* 한글 미지원 폰트 */ }

            // 영문 폭 측정
            var avgLatWidth = 0;
            try {
                tf.contents = latSample;
                tf.characters.everyItem().appliedFont = fontRef;
                tf.characters.everyItem().pointSize = testSize;
                tf.characters.everyItem().tracking = 0;
                tf.characters.everyItem().desiredLetterSpacing = 0;
                if (tf.lines.length > 0) {
                    var latTotalWidth = tf.lines[0].endHorizontalOffset - tf.lines[0].horizontalOffset;
                    avgLatWidth = latTotalWidth / latSample.length;
                }
            } catch(e3) {}

            // x-height 측정 (소문자 x)
            var xHeight = 0;
            try {
                tf.contents = "x";
                tf.characters.everyItem().appliedFont = fontRef;
                tf.characters.everyItem().pointSize = testSize;
                if (tf.lines.length > 0 && tf.characters.length > 0) {
                    var charBounds = tf.characters[0].geometricBounds; // [top, left, bottom, right]
                    xHeight = charBounds[2] - charBounds[0]; // 근사치
                }
            } catch(e4) {}

            // ascent/descent — 대문자 기준
            var ascent = 0, descent = 0;
            try {
                tf.contents = "Hg";
                tf.characters.everyItem().appliedFont = fontRef;
                tf.characters.everyItem().pointSize = testSize;
                if (tf.characters.length >= 2) {
                    var baseline = tf.characters[0].baseline;
                    var topBound = tf.characters[0].geometricBounds[0];
                    var bottomBound = tf.characters[1].geometricBounds[2]; // 'g' descender
                    ascent = baseline - topBound;
                    descent = bottomBound - baseline;
                }
            } catch(e5) {}

            results.push({
                family: family,
                style: fontInfo.style,
                korWidth: Math.round(avgKorWidth * 100) / 100,
                latWidth: Math.round(avgLatWidth * 100) / 100,
                weight: weight,
                xHeight: Math.round(xHeight * 100) / 100,
                ascent: Math.round(ascent * 100) / 100,
                descent: Math.round(descent * 100) / 100
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

        // 문단 수집
        var paras = story.paragraphs.everyItem().getElements();
        for (var p = 0; p < paras.length; p++) {
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

                    paraData.runs.push(runData);
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
                    rowHeights: []
                };
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

function collectTextFrames(doc, startPage, endPage) {
    var frames = [];
    try {
        var tfs = doc.textFrames.everyItem().getElements();
        for (var i = 0; i < tfs.length; i++) {
            var tf = tfs[i];
            // 페이지 범위 필터
            try {
                var pp = tf.parentPage;
                if (pp) {
                    var pgIdx = pp.documentOffset + 1;
                    if (pgIdx < startPage || pgIdx > endPage) continue;
                }
            } catch (e) {}
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
                }
            } catch (e) {}

            // Phase 3: 프레임 메타데이터 보강
            try { fData.geometricBounds = [tf.geometricBounds[0], tf.geometricBounds[1], tf.geometricBounds[2], tf.geometricBounds[3]]; } catch (e) {}
            try { fData.columnCount = tf.textFramePreferences.textColumnCount; } catch (e) {}
            try { fData.columnGutter = tf.textFramePreferences.textColumnGutter; } catch (e) {}
            try { var is = tf.textFramePreferences.insetSpacing; fData.insetSpacing = [is[0], is[1], is[2], is[3]]; } catch (e) {}
            try { fData.verticalJustification = tf.textFramePreferences.verticalJustification.toString(); } catch (e) {}
            try { fData.rotationAngle = tf.absoluteRotationAngle; } catch (e) {}

            frames.push(fData);
        }
    } catch (e) {
        // 텍스트 프레임 접근 실패 시 무시
    }
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
