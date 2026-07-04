/**
 * Resolved document/style/color/font and composed-line collectors.
 *
 * This module only reads IDML/InDesign resolved input data.
 * It must not decide ownership, placement, materialization, or layer.
 */

function normalizeResolvedInsetSpacing(rawInset) {
    try {
        if (rawInset && typeof rawInset.length === "number" && rawInset.length >= 4) {
            return [
                Number(rawInset[0] || 0),
                Number(rawInset[1] || 0),
                Number(rawInset[2] || 0),
                Number(rawInset[3] || 0)
            ];
        }
    } catch (eList) {}
    if (rawInset === undefined || rawInset === null) return [0, 0, 0, 0];
    var one = Number(rawInset);
    if (!isFinite(one)) {
        try { one = Number(rawInset.value); } catch (eValue) {}
    }
    if (!isFinite(one)) {
        try { one = parseFloat(String(rawInset)); } catch (eString) {}
    }
    if (!isFinite(one)) one = 0;
    return [one, one, one, one];
}


function collectNativeParentTextShellCandidates(textFrames, pageItems) {
    var result = [];
    var itemById = {};
    for (var i = 0; pageItems && i < pageItems.length; i++) {
        if (!pageItems[i] || pageItems[i].id === null || pageItems[i].id === undefined) continue;
        itemById[String(pageItems[i].id)] = pageItems[i];
    }
    for (var t = 0; textFrames && t < textFrames.length; t++) {
        var tf = textFrames[t];
        if (!tf || tf.id === null || tf.id === undefined) continue;
        var tfItem = itemById[String(tf.id)];
        if (!tfItem || !tfItem.parentId) continue;
        var parent = itemById[String(tfItem.parentId)];
        if (!isNativeParentTextShellSource(parent)) continue;
        var tfBounds = tf.geometricBounds || tfItem.geometricBounds;
        var shellBounds = parent.geometricBounds;
        if (!tfBounds || !shellBounds) continue;
        var tfArea = boundsArea(tfBounds);
        if (tfArea <= 0) continue;
        var overlap = boundsOverlapArea(tfBounds, shellBounds) / tfArea;
        if (overlap < 0.75) continue;
        result.push({
            textFrameId: parseInt(tf.id, 10),
            shellSourceObjectId: parseInt(parent.id, 10),
            sourceObjectIds: [parseInt(parent.id, 10), parseInt(tf.id, 10)],
            visualSourceObjectIds: [parseInt(parent.id, 10)],
            pageIndex: tf.pageIndex !== undefined ? tf.pageIndex : tfItem.pageIndex,
            overlapRatio: overlap,
            reason: "native_parent_text_shell_candidate"
        });
    }
    return result;
}

function isNativeParentTextShellSource(item) {
    if (!item || item.hiddenByParent || item.visible === false) return false;
    var type = item.type || "";
    if (type !== "Rectangle" && type !== "Polygon" && type !== "Oval") return false;
    if (!item.geometricBounds) return false;
    var hasFill = item.fillColorName && item.fillColorName !== "None" && item.fillColorName !== "[None]";
    var hasStroke = item.strokeColorName && item.strokeColorName !== "None" && item.strokeColorName !== "[None]"
            && item.strokeWeight !== undefined && item.strokeWeight > 0.01;
    var hasCorner = item.cornerRadius !== undefined && item.cornerRadius > 0;
    return hasFill || hasStroke || hasCorner;
}

/**
 * 아이템이 텍스트 흐름 안의 인라인(앵커) 객체인지 판별한다.
 */
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
    var collectStatus = !!(CONFIG.extraction && CONFIG.extraction.collectFontStatus);
    for (var i = 0; i < doc.fonts.length; i++) {
        var f = doc.fonts[i];
        try {
            var data = {
                name: f.name,
                fontFamily: f.fontFamily,
                fontStyleName: f.fontStyleName,
                fontType: "",
                status: "SKIPPED"
            };
            if (collectStatus) {
                try { data.fontType = f.fontType.toString(); } catch (eType) {}
                try { data.status = f.status.toString(); } catch (eStatus) {}
            }
            fonts.push(data);
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


function collectComposedLines(tf) {
    var result = [];
    var lines = tf.lines.everyItem().getElements();
    if (lines.length === 0) return result;

    // 프레임 기준 좌표 (insetSpacing 반영)
    var fBounds = tf.geometricBounds; // [top, left, bottom, right]
    var fLeft = fBounds[1];
    var fRight = fBounds[3];
    try {
        var inset = normalizeResolvedInsetSpacing(tf.textFramePreferences.insetSpacing);
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

function collectResolved(doc, outputDir, rangePageCount, startPage, endPage, editableIds, skipRenderPagesMap, cachedAllItems) {
    if (!skipRenderPagesMap) skipRenderPagesMap = {};

    writeProgress(outputDir, "resolved_styles", 0, rangePageCount);
    _marker(outputDir, "10a_documentInfo");
    var docInfo = collectDocumentInfo(doc);
    _marker(outputDir, "10a_documentInfo_done");
    _marker(outputDir, "10b_paragraphStyles");
    var paraStyles = collectParagraphStyles(doc);
    _marker(outputDir, "10b_paragraphStyles_done");
    // SPEC-030 B.3: characterStyles는 Java 측 resolved 파이프라인에서 미사용 — 수집 생략
    // var charStyles = collectCharacterStyles(doc);
    _marker(outputDir, "10c_colors");
    var colors = collectColors(doc);
    _marker(outputDir, "10c_colors_done");
    _marker(outputDir, "10d_fonts");
    var fonts = [];
    if (CONFIG.extraction && CONFIG.extraction.collectFonts) {
        fonts = collectFonts(doc);
    }
    _marker(outputDir, "10d_fonts_done");

    // 범위 내 페이지의 텍스트프레임에 연결된 스토리 ID 수집
    // SPEC-030: 증분 추출 시 변경 페이지의 스토리만 수집 (skipRenderPagesMap 제외)
    _marker(outputDir, "10e_rangeStoryIds_docTextFrames");
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
    _marker(outputDir, "10e_rangeStoryIds_docTextFrames_done");
    // 그룹 내부 TextFrame은 parentPage가 null이므로
    // 페이지의 allPageItems에서 TextFrame을 추가 수집
    _marker(outputDir, "10f_rangeStoryIds_pageItems");
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
    _marker(outputDir, "10f_rangeStoryIds_pageItems_done");

    // source ownership policy: off-canvas TF story 도 rangeStoryIds 에 추가.
    // off-canvas TF 는 parentPage=null 이므로 위 두 수집 경로에서 모두 누락됨.
    // instanceMasterFrames 가 스토리 클론을 만들려면 원본 story 가 storyById 에 있어야 함.
    _marker(outputDir, "10g_rangeStoryIds_offCanvas");
    try {
        var _targetOffCanvasSpreadIds = {};
        for (var _ocPg = 0; _ocPg < doc.pages.length; _ocPg++) {
            var _ocPgIdx = _ocPg + 1;
            if (_ocPgIdx < startPage || _ocPgIdx > endPage) continue;
            if (skipRenderPagesMap[_ocPgIdx]) continue;
            try {
                var _ocPageSpread = doc.pages[_ocPg].parent;
                if (_ocPageSpread) _targetOffCanvasSpreadIds[String(_ocPageSpread.id)] = true;
            } catch (eTargetSpread) {}
        }
        var _allSpreads = doc.spreads.everyItem().getElements();
        for (var _ocRsi = 0; _ocRsi < _allSpreads.length; _ocRsi++) {
            try {
                if (!_targetOffCanvasSpreadIds[String(_allSpreads[_ocRsi].id)]) continue;
            } catch (eSpreadFilter) { continue; }
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
    _marker(outputDir, "10g_rangeStoryIds_offCanvas_done");

    // source ownership policy: 마스터 스프레드의 TextFrame 스토리도 rangeStoryIds 에 추가 (Phase 5 master instancing 용)
    // 증분 추출에서도 마스터 스토리는 항상 수집 (변경 여부와 무관)
    _marker(outputDir, "10h_rangeStoryIds_master");
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
    _marker(outputDir, "10h_rangeStoryIds_master_done");

    writeProgress(outputDir, "resolved_stories", 0, rangePageCount);
    _marker(outputDir, "10i_collectStories");
    var stories = collectStories(doc, outputDir, rangePageCount, rangeStoryIds, cachedAllItems);
    _marker(outputDir, "10i_collectStories_done");

    writeProgress(outputDir, "resolved_frames", 0, rangePageCount);
    _marker(outputDir, "10j_collectTextFrames");
    var textFrames = collectTextFrames(doc, startPage, endPage, editableIds, skipRenderPagesMap, cachedAllItems);
    _marker(outputDir, "10j_collectTextFrames_done");

    // source ownership policy Phase 5: 마스터 스프레드 TextFrame 을 적용 페이지마다 인스턴스화 (frame + story clone)
    // 증분 추출에서도 실행 (마스터 인스턴스는 경량 연산)
    _marker(outputDir, "10k_instanceMasterFrames");
    try { instanceMasterFrames(doc, startPage, endPage, textFrames, stories, editableIds); } catch (ePhase5) { $.writeln("[source ownership policy Phase 5 error] " + ePhase5); }
    _marker(outputDir, "10k_instanceMasterFrames_done");

    writeProgress(outputDir, "resolved_items", 0, rangePageCount);
    _marker(outputDir, "10l_collectPages");
    var pages = collectPages(doc, startPage, endPage, skipRenderPagesMap);
    _marker(outputDir, "10l_collectPages_done");
    _marker(outputDir, "10m_collectPageItems");
    var pageItems = collectPageItems(doc, startPage, endPage, skipRenderPagesMap, cachedAllItems);
    _marker(outputDir, "10m_collectPageItems_done");
    var nativeParentTextShellCandidates = collectNativeParentTextShellCandidates(textFrames, pageItems);

    _marker(outputDir, "10n_measureFontMetrics");
    var fontMetrics = [];
    if (CONFIG.extraction && CONFIG.extraction.measureFontMetrics) {
        fontMetrics = measureFontMetrics(doc);
    }
    _marker(outputDir, "10n_measureFontMetrics_done");

    return {
        documentInfo: docInfo,
        paragraphStyles: paraStyles,
        colors: colors,
        fonts: fonts,
        stories: stories,
        textFrames: textFrames,
        pages: pages,
        pageItems: pageItems,
        nativeParentTextShellCandidates: nativeParentTextShellCandidates,
        fontMetrics: fontMetrics
    };
}


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
            // source ownership policy Phase 5 보강: auto page number TF(ACE 18, \u0018) 및 text variable TF(\uFEFF)도
            // hashiraEditable=true 일 때 인스턴스화 → 하시라/페이지번호 변환 지원.
            var cls = null;
            try { cls = classifyTextFrameCached(mtf); } catch (e) {}
            var hashiraSpecialType = null; // "pagenum" | "textvar"
            var hashiraTextVarResolved = null;
            if (s25 && s25.hashiraEditable) {
                var hashiraStoryContents = "";
                var hashiraTextVariableInstances = null;
                try { hashiraStoryContents = mtf.parentStory.contents || ""; } catch (eHSC) {}
                try { hashiraTextVariableInstances = mtf.parentStory.textVariableInstances; } catch (eHTVI0) {}

                // Case A: auto page number TF. It uses the auto page marker but
                // does not expose TextVariableInstance entries. This must be
                // resolved in the source extraction stage, not recreated later
                // by Java master-page fallback code.
                try {
                    if (hashiraStoryContents.indexOf("\u0018") >= 0
                            && (!hashiraTextVariableInstances || hashiraTextVariableInstances.length === 0)) {
                        hashiraSpecialType = "pagenum";
                    }
                } catch (eHP) {}

                // Case B: text variable TF (contents\uFEFF\u0016\uFFFC 이외 가시 텍스트 없음)
                if (!hashiraSpecialType) {
                    try {
                        if (hashiraTextVariableInstances && hashiraTextVariableInstances.length > 0) {
                            var tvStripRaw = "";
                            try { tvStripRaw = hashiraStoryContents.replace(/\uFEFF/g, "").replace(/\uFFFC/g, "").replace(/\u0016/g, "").replace(/\u0018/g, "").replace(/[\s\r\n]/g, ""); } catch (eTS) {}
                            if (tvStripRaw.length === 0) {
                                var tvParts = [];
                                for (var tvIdx = 0; tvIdx < hashiraTextVariableInstances.length; tvIdx++) {
                                    try { tvParts.push(String(hashiraTextVariableInstances[tvIdx].resultText) || ""); } catch (eTVI2) {}
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
            // textvar(running header)는 resolved master TF로 새 배치하지 않는다.
            // 기존 master TF 안 placeholder 치환 경로만 사용한다.
            if (hashiraSpecialType === "textvar") continue;
            if (cls !== "editable" && !hashiraSpecialType) continue;
            var baseId = ""; try { baseId = mtf.id.toString(); } catch (e) { continue; }
            // source ownership policy: 마스터 TF 가 위치한 master page 의 스프레드 내 인덱스(0=LEFT,1=RIGHT) →
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
            var commonInset = null;  try { commonInset = normalizeResolvedInsetSpacing(mtf.textFramePreferences.insetSpacing); } catch (e) {}
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
        $.writeln("[source ownership policy Phase 5] cloned " + frameClones + " master frame instances, " + storyClones + " story copies");
    }

    // source ownership policy Phase 5 보강 (2026-05-22): off-canvas master override 처리.
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
                    $.writeln("[source ownership policy off-canvas] id=" + oTf.id + " masterPageItem=" + __mpi + " text=" + __snippet);
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
                var ocInset = null;  try { ocInset = normalizeResolvedInsetSpacing(oTf.textFramePreferences.insetSpacing); } catch (e) {}
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
    } catch (eOC) { $.writeln("[source ownership policy Phase 5 off-canvas error] " + eOC); }
    if (offCanvasClones > 0) {
        $.writeln("[source ownership policy Phase 5] off-canvas overrides: " + offCanvasClones + " frame clones");
    }
}


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
        // piPageIdx는 0-based, startPage/endPage는 1-based.
        // Spread-level 또는 cross-page source는 parentPage가 범위 밖이어도
        // requested page와 bounds가 겹치면 Stage 1 ObjectPlan의 실행 source가 될 수
        // 있다. parentPage만으로 버리면 NATIVE_SOURCE_SHAPE plan은 남고
        // resolved.pageItems source가 빠져 Stage 3에서 누락된다.
        if (piPageIdx >= 0) {
            var pgIdx1 = piPageIdx + 1;
            if (pgIdx1 < startPage || pgIdx1 > endPage) {
                var overlapPageIdx = _pageIndexBySpreadBounds(doc, pi, {
                    startPage: startPage,
                    endPage: endPage
                });
                if (overlapPageIdx < 0) continue;
                piPageIdx = overlapPageIdx;
                pgIdx1 = piPageIdx + 1;
            }
            if (skipRenderPagesMap[pgIdx1]) continue; // SPEC-030: 증분 추출 스킵
        }

        var data = {
            id: pi.id.toString(),
            type: pi.constructor.name,
            name: null,
            parentId: null,
            pageIndex: piPageIdx
        };
        try { data.visible = (pi.visible !== false); } catch (e) { data.visible = true; }
        try { data.hiddenByParent = isHiddenByVisibility(pi); } catch (e) { data.hiddenByParent = false; }

        // 이름
        try { data.name = pi.name; } catch (e) {}
        _copyLayerInfo(data, pi);

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

        data.anchoredPosition = _itemAnchoredPosition(pi);
        data.storyAnchorPlacement = _storyAnchorPlacementForItem(doc, pi, null);

        // NEW: 인라인 여부. Story에 매달린 객체라도 content box 밖에 놓인
        // anchored visual은 page-space floating source로 보존한다.
        data.isInline = isInlineItem(pi) && data.storyAnchorPlacement !== "FLOATING_ANCHORED";

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
