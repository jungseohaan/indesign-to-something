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

        // 1. 문서 열기 (창 표시 안 함)
        var doc = app.open(File(inddPath), false);

        // 2. IDML 내보내기
        var idmlFile = File(outputDir + "/output.idml");
        doc.exportFile(ExportFormat.INDESIGN_MARKUP, idmlFile);

        // 3. resolved 속성 수집
        var resolved = collectResolved(doc);
        writeJson(outputDir + "/resolved.json", resolved);

        // 4. PDF 프리뷰 (선택 — 실패해도 진행)
        try {
            var pdfFile = File(outputDir + "/preview.pdf");
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

function collectResolved(doc) {
    return {
        documentInfo: collectDocumentInfo(doc),
        paragraphStyles: collectParagraphStyles(doc),
        characterStyles: collectCharacterStyles(doc),
        colors: collectColors(doc),
        fonts: collectFonts(doc),
        stories: collectStories(doc),
        textFrames: collectTextFrames(doc)
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

function collectStories(doc) {
    var stories = [];
    for (var s = 0; s < doc.stories.length; s++) {
        var story = doc.stories[s];
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

function collectTextFrames(doc) {
    var frames = [];
    try {
        var tfs = doc.textFrames.everyItem().getElements();
        for (var i = 0; i < tfs.length; i++) {
            var tf = tfs[i];
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

            // 이 프레임에 표시되는 문단의 Story 내 인덱스 범위
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
                }
            } catch (e) {}

            frames.push(fData);
        }
    } catch (e) {
        // 텍스트 프레임 접근 실패 시 무시
    }
    return frames;
}

// --- 유틸리티 ---

function writeJson(path, obj) {
    var f = File(path);
    f.encoding = "UTF-8";
    f.open("w");
    f.write(JSON.stringify(obj, null, 2));
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
