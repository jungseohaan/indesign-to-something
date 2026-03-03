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

    // --- 기존 환경설정 저장 ---
    var savedInteractionLevel = app.scriptPreferences.userInteractionLevel;
    var savedEnableRedraw = app.scriptPreferences.enableRedraw;
    var savedCheckLinks = app.linkingPreferences.checkLinksAtOpen;
    var savedFindMissing = app.linkingPreferences.findMissingLinksAtOpen;

    try {
        // --- 다이얼로그 억제 (headless) ---
        // 누락 폰트, 링크 관련 팝업을 모두 자동 dismiss
        app.scriptPreferences.userInteractionLevel = UserInteractionLevels.NEVER_INTERACT;
        app.scriptPreferences.enableRedraw = false;
        app.linkingPreferences.checkLinksAtOpen = false;
        app.linkingPreferences.findMissingLinksAtOpen = false;

        writeProgress(outputDir, "open", 0, 0);

        // 1. 문서 열기 (창 표시 안 함)
        var doc = app.open(File(inddPath), false);
        var pageCount = doc.pages.length;

        // 페이지 범위 보정
        if (startPage < 1) startPage = 1;
        if (endPage < 1 || endPage > pageCount) endPage = pageCount;
        var rangePageCount = endPage - startPage + 1;

        writeProgress(outputDir, "idml", 0, pageCount);

        // 2. IDML 내보내기 (전체 — API 제한)
        var idmlFile = File(outputDir + "/output.idml");
        doc.exportFile(ExportFormat.INDESIGN_MARKUP, idmlFile);

        writeProgress(outputDir, "resolved", 0, rangePageCount);

        // 3. resolved 속성 수집 (페이지 범위 필터링)
        var resolved = collectResolved(doc, outputDir, rangePageCount, startPage, endPage);
        writeJson(outputDir + "/resolved.json", resolved);

        writeProgress(outputDir, "pdf", rangePageCount, rangePageCount);

        // 4. PDF 프리뷰 (페이지 범위 적용)
        try {
            var pdfFile = File(outputDir + "/preview.pdf");
            // 페이지 범위가 있으면 해당 범위만 PDF 내보내기
            if (startPage >= 1 && endPage <= pageCount && rangePageCount < pageCount) {
                app.pdfExportPreferences.pageRange = "" + startPage + "-" + endPage;
            } else {
                app.pdfExportPreferences.pageRange = PageRange.ALL_PAGES;
            }
            doc.exportFile(ExportFormat.PDF_TYPE, pdfFile);
        } catch (e) {
            // PDF 내보내기 실패는 무시
        }

        // 5. 문서 닫기 (저장 안 함)
        doc.close(SaveOptions.NO);

        // 6. 성공 시그널
        writeDone(outputDir, "ok", null);
    } catch (e) {
        // 에러 시그널
        writeDone(outputDir, "error", e.message);
    } finally {
        // --- 반드시 원래 환경설정 복원 ---
        app.scriptPreferences.userInteractionLevel = savedInteractionLevel;
        app.scriptPreferences.enableRedraw = savedEnableRedraw;
        app.linkingPreferences.checkLinksAtOpen = savedCheckLinks;
        app.linkingPreferences.findMissingLinksAtOpen = savedFindMissing;
    }
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
                    var pgIdx = pp.documentOffset + 1; // 1-based
                    if (pgIdx >= startPage && pgIdx <= endPage) {
                        try {
                            rangeStoryIds[tf.parentStory.id.toString()] = true;
                        } catch (e2) {}
                    }
                }
            } catch (e) {}
        }
    } catch (e) {}

    writeProgress(outputDir, "resolved_stories", 0, rangePageCount);
    var stories = collectStories(doc, outputDir, rangePageCount, rangeStoryIds);

    writeProgress(outputDir, "resolved_frames", 0, rangePageCount);
    var textFrames = collectTextFrames(doc, startPage, endPage);

    writeProgress(outputDir, "resolved_items", 0, rangePageCount);
    var pages = collectPages(doc, startPage, endPage);
    var pageItems = collectPageItems(doc, startPage, endPage);

    return {
        documentInfo: docInfo,
        paragraphStyles: paraStyles,
        characterStyles: charStyles,
        colors: colors,
        fonts: fonts,
        stories: stories,
        textFrames: textFrames,
        pages: pages,
        pageItems: pageItems
    };
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
