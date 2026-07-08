/**
 * Text story and run collectors.
 *
 * This module reads composed story/table/cell text data only.
 * It must not decide visual ownership, placement, or layer.
 */

/**
 * GREP/중첩 스타일 보정: story.characters로 문자별 색상/크기를 확인하여 런을 분할.
 * textStyleRange.characters는 GREP 스타일을 반영하지 않으므로,
 * story.characters의 index를 사용하여 실제 렌더링 속성을 조회.
 */
function hasMeaningfulNumber(v) {
    return typeof v === "number" && isFinite(v) && v > 0;
}

function hasMeaningfulString(v) {
    if (typeof v !== "string") return false;
    return v !== "" && v !== "NothingEnum.NOTHING" && v !== "[None]" && v !== "None";
}

function normalizeTextFrameInsetSpacing(rawInset) {
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

function paragraphGeneratedPrefixText(para) {
    if (!para) return "";
    var candidates = [
        "bulletsAndNumberingResultText",
        "numberingResultText",
        "bulletAndNumberingResultText"
    ];
    for (var i = 0; i < candidates.length; i++) {
        try {
            var value = para[candidates[i]];
            if (typeof value === "string" && value.length > 0) return value;
        } catch (e) {}
    }
    return "";
}

function characterStyleChangesExportedRunProps(cs) {
    if (!cs) return false;
    try {
        var fc = cs.fillColor;
        if (fc && fc.name && fc.name !== "None" && fc.name !== "[None]") return true;
    } catch (eFill) {}
    try {
        if (hasMeaningfulNumber(cs.pointSize)) return true;
    } catch (eSize) {}
    try {
        var af = cs.appliedFont;
        if (af && hasMeaningfulString(af.fontFamily)) return true;
    } catch (eFont) {}
    try {
        if (hasMeaningfulString(cs.fontStyle)) return true;
    } catch (eStyle) {}
    return false;
}

function buildParagraphCharacterCorrectionDescriptor(para) {
    var descriptor = { needs: false, grepExpressions: [], hasNonGrepRule: false };
    try {
        var ps = para.appliedParagraphStyle;
        if (!ps) return descriptor;
        try {
            var ngs = ps.nestedGrepStyles.everyItem().getElements();
            for (var gi = 0; gi < ngs.length; gi++) {
                if (characterStyleChangesExportedRunProps(ngs[gi].appliedCharacterStyle)) {
                    descriptor.needs = true;
                    try { descriptor.grepExpressions.push(String(ngs[gi].grepExpression || "")); } catch (eExpr) { descriptor.grepExpressions.push(""); }
                }
            }
        } catch (eGrep) {}
        try {
            var ns = ps.nestedStyles.everyItem().getElements();
            for (var ni = 0; ni < ns.length; ni++) {
                if (characterStyleChangesExportedRunProps(ns[ni].appliedCharacterStyle)) {
                    descriptor.needs = true;
                    descriptor.hasNonGrepRule = true;
                }
            }
        } catch (eNested) {}
        try {
            var nls = ps.nestedLineStyles.everyItem().getElements();
            for (var li = 0; li < nls.length; li++) {
                if (characterStyleChangesExportedRunProps(nls[li].appliedCharacterStyle)) {
                    descriptor.needs = true;
                    descriptor.hasNonGrepRule = true;
                }
            }
        } catch (eLine) {}
    } catch (e) {}
    return descriptor;
}

function grepExpressionCouldAffectText(expr, text) {
    if (!expr) return true;
    text = text || "";
    if (expr === "·") return text.indexOf("·") >= 0;
    if (expr === "~") return text.indexOf("~") >= 0;
    if (expr === "-") return text.indexOf("-") >= 0;
    if (expr === "」") return text.indexOf("」") >= 0;
    if (expr === "[●]") return text.indexOf("●") >= 0;
    if (expr === "[㈀-㉻①-ⓩ]") return /[㈀-㉻①-ⓩ]/.test(text);
    if (expr.indexOf("<.+?>") >= 0) return /<[^>]+>/.test(text);
    if (expr.indexOf("\\(.+?\\)") >= 0) return /[()]/.test(text);
    if (expr.indexOf("[‘\\’\\“\\”]") >= 0) return /[‘’“”]/.test(text);
    if (expr.indexOf("(?<=^).+?(?=: )") >= 0) return text.indexOf(": ") >= 0;
    if (expr.indexOf("~K") >= 0) return text.indexOf("~") >= 0 && text.indexOf("K") >= 0;
    if (expr.indexOf("(?<=") >= 0 && expr.indexOf("(?=$)") >= 0) return text.indexOf("\b") >= 0;
    if (expr.indexOf("(?<=") >= 0 && expr.indexOf("\\-+?") >= 0) return text.indexOf("\b") >= 0 && text.indexOf("-") >= 0;
    return true;
}

function correctionDescriptorNeedsRunScan(descriptor, text) {
    if (!descriptor || !descriptor.needs) return false;
    if (descriptor.hasNonGrepRule) return true;
    var exprs = descriptor.grepExpressions || [];
    if (exprs.length === 0) return true;
    for (var i = 0; i < exprs.length; i++) {
        if (grepExpressionCouldAffectText(exprs[i], text)) return true;
    }
    return false;
}

function cellMayHaveAnchoredPageItems(cell) {
    try {
        var items = cell.allPageItems;
        return items && items.length > 0;
    } catch (e) {}
    return false;
}

function splitRunByStoryChars(story, rng, runData, para, needsCharacterCorrection, correctionDescriptor) {
    if (!needsCharacterCorrection) return [runData];
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
        var colorCache = {};
        function getCharColor(absIdx) {
            if (colorCache[absIdx] !== undefined) return colorCache[absIdx];
            var color = null;
            try { color = para.characters[absIdx].fillColor ? para.characters[absIdx].fillColor.name : null; } catch (e) {}
            colorCache[absIdx] = color;
            return color;
        }
        function getCharProps(absIdx) {
            if (propsCache[absIdx]) return propsCache[absIdx];
            var color = null, size = null, font = null, style = null;
            var baselineShift = null, position = null, charStyle = null;
            color = getCharColor(absIdx);
            try { size = para.characters[absIdx].pointSize; } catch (e) {}
            try { font = para.characters[absIdx].appliedFont.fontFamily; } catch (e) {}
            try { style = para.characters[absIdx].fontStyle; } catch (e) {}
            try { baselineShift = para.characters[absIdx].baselineShift; } catch (e) {}
            try { position = para.characters[absIdx].position ? para.characters[absIdx].position.toString() : null; } catch (e) {}
            try { charStyle = para.characters[absIdx].appliedCharacterStyle ? para.characters[absIdx].appliedCharacterStyle.name : null; } catch (e) {}
            // GREP 스타일로 적용된 이탤릭 감지: fontStyle이 숫자(가변 폰트 웨이트)인데
            // appliedCharacterStyle에 "이탤릭" 또는 "Italic"이 포함되면 fontStyle을 "Italic"으로 보정
            if (style && /^\d+$/.test(style)) {
                try {
                    var csName = charStyle;
                    if (csName && (csName.indexOf("이탤릭") >= 0 || csName.toLowerCase().indexOf("italic") >= 0)) {
                        style = "Italic";
                    }
                } catch (e2) {}
            }
            var p = {
                color: color,
                size: size,
                font: font,
                style: style,
                baselineShift: baselineShift,
                position: position,
                charStyle: charStyle
            };
            propsCache[absIdx] = p;
            return p;
        }
        function propsEqual(a, b) {
            return a.color === b.color
                    && a.size === b.size
                    && a.font === b.font
                    && a.style === b.style
                    && a.baselineShift === b.baselineShift
                    && a.position === b.position
                    && a.charStyle === b.charStyle;
        }
        function addBoundary(boundaries, offset) {
            if (offset <= 0 || offset >= rngLen) return;
            boundaries.push(offset);
        }
        function appendFullPropBoundariesInRange(boundaries, startOffset, endOffset) {
            if (endOffset - startOffset <= 1) return;
            var prevPropsInRange = getCharProps(rngStart + startOffset);
            for (var piRange = startOffset + 1; piRange < endOffset; piRange++) {
                var curPropsInRange = getCharProps(rngStart + piRange);
                if (!propsEqual(prevPropsInRange, curPropsInRange)) {
                    addBoundary(boundaries, piRange);
                }
                prevPropsInRange = curPropsInRange;
            }
        }
        function buildRunsFromBoundaries(boundaries) {
            if (!boundaries || boundaries.length === 0) return [runData];
            boundaries.sort(function(a, b) { return a - b; });
            var uniq = [];
            for (var ub = 0; ub < boundaries.length; ub++) {
                if (ub === 0 || boundaries[ub] !== boundaries[ub - 1]) uniq.push(boundaries[ub]);
            }
            var fullTextForBuild = runData.text || "";
            var useSliceForBuild = (fullTextForBuild.length === rngLen);
            var startsForBuild = [0];
            for (var bs = 0; bs < uniq.length; bs++) startsForBuild.push(uniq[bs]);
            var endsForBuild = [];
            for (var be = 0; be < uniq.length; be++) endsForBuild.push(uniq[be]);
            endsForBuild.push(rngLen);

            var builtRuns = [];
            for (var br = 0; br < startsForBuild.length; br++) {
                var segSForBuild = startsForBuild[br];
                var segEForBuild = endsForBuild[br];
                var propsForBuild = getCharProps(rngStart + segSForBuild);
                var textForBuild = useSliceForBuild
                        ? fullTextForBuild.substring(segSForBuild, segEForBuild)
                        : "";
                if (!useSliceForBuild) {
                    for (var tbi = segSForBuild; tbi < segEForBuild; tbi++) {
                        try { textForBuild += para.characters[rngStart + tbi].contents; } catch (eTextBuild) {}
                    }
                }
                if (textForBuild.length > 0) {
                    var builtRun = {};
                    for (var bk in runData) { if (runData.hasOwnProperty(bk)) builtRun[bk] = runData[bk]; }
                    builtRun.text = textForBuild;
                    builtRun.fillColor = propsForBuild.color;
                    if (propsForBuild.size) builtRun.fontSize = propsForBuild.size;
                    if (propsForBuild.font) builtRun.fontFamily = propsForBuild.font;
                    if (propsForBuild.style) builtRun.fontStyle = propsForBuild.style;
                    builtRun.baselineShift = propsForBuild.baselineShift;
                    builtRun.position = propsForBuild.position;
                    builtRun.charStyle = propsForBuild.charStyle;
                    builtRuns.push(builtRun);
                }
            }
            return builtRuns.length > 0 ? builtRuns : [runData];
        }

        // ParagraphStyle에 GREP/중첩 스타일이 있으면 빠른 체크 건너뛰고 바로 스캔.
        var hasGrepStyles = !!needsCharacterCorrection;
        var firstProps = getCharProps(rngStart);
        if (hasGrepStyles && !correctionDescriptorNeedsRunScan(correctionDescriptor, runData.text || "")) {
            runData.fillColor = firstProps.color;
            if (firstProps.size) runData.fontSize = firstProps.size;
            if (firstProps.font) runData.fontFamily = firstProps.font;
            if (firstProps.style) runData.fontStyle = firstProps.style;
            runData.baselineShift = firstProps.baselineShift;
            runData.position = firstProps.position;
            runData.charStyle = firstProps.charStyle;
            return [runData];
        }
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
        // GREP 있고 빠른 체크 실패 → 색상 경계를 먼저 전수 검사한다.
        // 대부분의 GREP/중첩 보정은 색상 변경이며, font/size/style까지 매 문자
        // 조회하면 InDesign DOM 비용이 매우 커진다. 색상 경계가 없을 때만
        // 소수 샘플로 비색상 변화 가능성을 확인한다.
        if (!allSame && hasGrepStyles && rngLen > 1) {
            var grepBoundaries = [];
            var prevColor = getCharColor(rngStart);
            for (var gi = 1; gi < rngLen; gi++) {
                var curColor = getCharColor(rngStart + gi);
                if (prevColor !== curColor) {
                    grepBoundaries.push(gi);
                }
                prevColor = curColor;
            }
            if (grepBoundaries.length > 0) {
                // 색상 경계는 빠른 1차 segment 후보일 뿐이다.
                // 각 색상 segment 내부에서도 fontSize/fontFamily/fontStyle 변화를
                // 정밀 검사해, 같은 색상 안의 폰트/크기 변경을 놓치지 않는다.
                var refinedBoundaries = [];
                var colorSegStart = 0;
                for (var gb = 0; gb < grepBoundaries.length; gb++) {
                    var colorSegEnd = grepBoundaries[gb];
                    addBoundary(refinedBoundaries, colorSegEnd);
                    appendFullPropBoundariesInRange(refinedBoundaries, colorSegStart, colorSegEnd);
                    colorSegStart = colorSegEnd;
                }
                appendFullPropBoundariesInRange(refinedBoundaries, colorSegStart, rngLen);
                return buildRunsFromBoundaries(refinedBoundaries);
            }
            if (rngLen <= 40) {
                var propBoundaries = [];
                appendFullPropBoundariesInRange(propBoundaries, 0, rngLen);
                if (propBoundaries.length > 0) {
                    return buildRunsFromBoundaries(propBoundaries);
                }
                runData.fillColor = firstProps.color;
                if (firstProps.size) runData.fontSize = firstProps.size;
                if (firstProps.font) runData.fontFamily = firstProps.font;
                if (firstProps.style) runData.fontStyle = firstProps.style;
                runData.baselineShift = firstProps.baselineShift;
                runData.position = firstProps.position;
                runData.charStyle = firstProps.charStyle;
                return [runData];
            }
            var sampleMismatch = false;
            var sampleSeen = {};
            function checkFullPropSample(offset) {
                if (offset < 0 || offset >= rngLen) return;
                if (sampleSeen[offset]) return;
                sampleSeen[offset] = true;
                if (!propsEqual(firstProps, getCharProps(rngStart + offset))) sampleMismatch = true;
            }
            checkFullPropSample(rngLen - 1);
            checkFullPropSample(Math.floor(rngLen / 2));
            checkFullPropSample(Math.floor(rngLen / 4));
            checkFullPropSample(Math.floor(rngLen * 3 / 4));
            var edgeLimit = Math.min(rngLen, 12);
            for (var edge = 0; edge < edgeLimit && !sampleMismatch; edge++) {
                checkFullPropSample(edge);
            }
            if (!sampleMismatch) {
                runData.fillColor = firstProps.color;
                if (firstProps.size) runData.fontSize = firstProps.size;
                if (firstProps.font) runData.fontFamily = firstProps.font;
                if (firstProps.style) runData.fontStyle = firstProps.style;
                runData.baselineShift = firstProps.baselineShift;
                runData.position = firstProps.position;
                runData.charStyle = firstProps.charStyle;
                return [runData];
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
            runData.baselineShift = firstProps.baselineShift;
            runData.position = firstProps.position;
            runData.charStyle = firstProps.charStyle;
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
                splitRun.baselineShift = segProps.baselineShift;
                splitRun.position = segProps.position;
                splitRun.charStyle = segProps.charStyle;
                result.push(splitRun);
            }
        }

        return result.length > 0 ? result : [runData];
    } catch (e) {
        runData._splitError = e.toString();
        return [runData];
    }
}

// --- 스토리 수집 (문단 속성 + 런 확장 속성) ---

function collectStories(doc, outputDir, rangePageCount, rangeStoryIds, cachedAllItems) {
    var stories = [];
    var totalStories = doc.stories.length;
    var collectStats = {
        stories: 0,
        paragraphs: 0,
        correctionParagraphs: 0,
        textStyleRanges: 0,
        splitCalls: 0,
        splitCorrectionCalls: 0,
        splitInputChars: 0,
        splitOutputRuns: 0,
        splitChangedCalls: 0,
        splitMs: 0,
        anchorRuns: 0,
        tables: 0,
        cells: 0,
        paragraphLoopMs: 0,
        tableLoopMs: 0,
        correctionStyles: {}
    };
    var characterCorrectionByStyleId = {};
    function paragraphCharacterCorrectionDescriptorCached(para) {
        var key = null;
        try {
            var ps = para.appliedParagraphStyle;
            if (ps && ps.id !== undefined) key = String(ps.id);
            else if (ps && ps.name) key = String(ps.name);
        } catch (eKey) {}
        if (key !== null && characterCorrectionByStyleId[key] !== undefined) {
            return characterCorrectionByStyleId[key];
        }
        var result = buildParagraphCharacterCorrectionDescriptor(para);
        if (key !== null) characterCorrectionByStyleId[key] = result;
        return result;
    }
    function stripControlTextLocal(text) {
        var src = "";
        try { src = String(text || ""); } catch (eString) { return ""; }
        var out = "";
        for (var i = 0; i < src.length; i++) {
            var code = src.charCodeAt(i);
            if (code <= 32) continue;
            if (code === 0xFFFC || code === 0xFEFF) continue;
            out += String.fromCharCode(code);
        }
        return out;
    }
    function isMarkerOnlyTextFrameLocal(tf) {
        try {
            if (!tf || !tf.constructor || tf.constructor.name !== "TextFrame") return false;
            return stripControlTextLocal(tf.contents).length === 0;
        } catch (e) {}
        return false;
    }
    function textFrameContentBoundsPageRelativeLocal(tf) {
        try {
            if (!tf || !tf.constructor || tf.constructor.name !== "TextFrame") return null;
            var gb = null;
            try { gb = arrCopy(tf.geometricBounds); } catch (eGb) {}
            if (!gb || gb.length < 4) return null;
            var page = null;
            try { page = tf.parentPage; } catch (ePage) {}
            if (!page) return null;
            var pb = null;
            try { pb = arrCopy(page.bounds); } catch (ePb) {}
            if (!pb || pb.length < 4) return null;
            var inset = [0, 0, 0, 0];
            try {
                inset = normalizeTextFrameInsetSpacing(tf.textFramePreferences.insetSpacing);
            } catch (eInset) {}
            return [
                gb[0] + inset[0] - pb[0],
                gb[1] + inset[1] - pb[1],
                gb[2] - inset[2] - pb[0],
                gb[3] - inset[3] - pb[1]
            ];
        } catch (e) {}
        return null;
    }
    function firstTextFrameFromStoryLocal(story) {
        if (!story) return null;
        var storyId = null;
        try { storyId = String(story.id); } catch (eStoryId) {}
        if (!storyId) return null;
        var items = cachedAllItems || null;
        if (!items) {
            try { items = doc.allPageItems; } catch (eAllItems) {}
        }
        if (items) {
            for (var i = 0; i < items.length; i++) {
                var item = items[i];
                try {
                    if (!item || !item.constructor || item.constructor.name !== "TextFrame") continue;
                    if (!item.parentStory) continue;
                    if (String(item.parentStory.id) === storyId) return item;
                } catch (eItem) {}
            }
        }
        return null;
    }
    var storyCandidates = null;
    if (rangeStoryIds) {
        storyCandidates = [];
        var expectedStoryCount = 0;
        for (var storyId in rangeStoryIds) {
            if (!rangeStoryIds.hasOwnProperty(storyId)) continue;
            expectedStoryCount++;
            try {
                var fastStory = doc.stories.itemByID(parseInt(storyId, 10));
                if (fastStory && fastStory.isValid) {
                    storyCandidates.push(fastStory);
                }
            } catch (eFastStory) {}
        }
        if (storyCandidates.length === 0 || storyCandidates.length < expectedStoryCount) {
            storyCandidates = null;
        } else {
            totalStories = storyCandidates.length;
        }
    }
    var collected = 0;
    var loopCount = storyCandidates ? storyCandidates.length : totalStories;
    for (var s = 0; s < loopCount; s++) {
        var story = storyCandidates ? storyCandidates[s] : doc.stories[s];
        // 페이지 범위 필터: 범위 내 프레임과 연결된 스토리만 수집
        if (rangeStoryIds && !rangeStoryIds[story.id.toString()]) {
            continue;
        }
        collected++;
        collectStats.stories++;
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
        var paragraphLoopStartMs = (new Date()).getTime();
        for (var p = 0; p < paraLimit; p++) {
            var para = paras[p];
            var correctionDescriptor = paragraphCharacterCorrectionDescriptorCached(para);
            var needsCharacterCorrection = !!(correctionDescriptor && correctionDescriptor.needs);
            collectStats.paragraphs++;
            var paraStyleNameForStats = "[Unknown]";
            try { paraStyleNameForStats = para.appliedParagraphStyle.name; } catch (eStyleStatsName) {}
            if (needsCharacterCorrection) {
                collectStats.correctionParagraphs++;
                if (!collectStats.correctionStyles[paraStyleNameForStats]) {
                    collectStats.correctionStyles[paraStyleNameForStats] = {
                        paragraphs: 0,
                        textStyleRanges: 0,
                        splitCalls: 0,
                        splitMs: 0,
                        splitChangedCalls: 0
                    };
                }
                collectStats.correctionStyles[paraStyleNameForStats].paragraphs++;
            }
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
                generatedPrefixText: null,
                tabStops: [],
                runs: []
            };

            paraData.styleName = paraStyleNameForStats;
            var generatedPrefix = paragraphGeneratedPrefixText(para);
            if (generatedPrefix) {
                paraData.generatedPrefixText = generatedPrefix;
            }
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
            try {
                var ranges = para.textStyleRanges.everyItem().getElements();
                for (var r = 0; r < ranges.length; r++) {
                    var rng = ranges[r];
                    collectStats.textStyleRanges++;
                    if (needsCharacterCorrection && collectStats.correctionStyles[paraStyleNameForStats]) {
                        collectStats.correctionStyles[paraStyleNameForStats].textStyleRanges++;
                    }
                    // 단락 경계 안에서만 텍스트 사용
                    var rngText = rng.contents;
                    // Most paragraph ranges are already paragraph-local. Avoid
                    // character index DOM access unless InDesign returned a
                    // cross-paragraph range.
                    var needsRangeBoundaryClip = false;
                    try {
                        needsRangeBoundaryClip = typeof rngText === "string" && rngText.indexOf("\r") >= 0;
                    } catch (eBoundaryCheck) {}
                    if (needsRangeBoundaryClip) {
                        try {
                            var paraStartIdx = -1, paraEndIdx = -1;
                            var paraChars = para.characters;
                            if (paraChars.length > 0) {
                                paraStartIdx = paraChars[0].index;
                                paraEndIdx = paraStartIdx + paraChars.length; // exclusive
                            }
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
                        } catch (eClip) {
                            try {
                                if (typeof rngText === "string" && rngText.indexOf("\r") >= 0) {
                                    rngText = rngText.split("\r")[0];
                                }
                            } catch (eFallbackClip) {}
                        }
                    }
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
                    if (needsCharacterCorrection) {
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
                    }
                    // 확장 속성
                    try { runData.tracking = rng.tracking; } catch (e) {}
                    try { runData.horizontalScale = rng.horizontalScale; } catch (e) {}
                    try { runData.verticalScale = rng.verticalScale; } catch (e) {}
                    try { runData.baselineShift = rng.baselineShift; } catch (e) {}
                    try { runData.position = rng.position.toString(); } catch (e) {}
                    try { runData.underline = rng.underline; } catch (e) {}
                    try { runData.strikeThru = rng.strikeThru; } catch (e) {}

                    // U+FFFC(인라인 마커) + U+0016(블록 앵커 마커) 분리
                    // inline anchor source policy: InDesign이 사용자 정의 위치/Above-Line 앵커에는 \x16 을 쓰는데
                    // 기존 로직은 \uFFFC 만 인식해서 모든 \x16 앵커가 누락됐다.
                    var runText = runData.text || "";
                    if (runText.indexOf("\uFFFC") >= 0 || runText.indexOf("\u0016") >= 0) {
                        collectStats.anchorRuns++;
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
                                            anchoredIds.push({
                                                id: anchItems[0].id,
                                                storyAnchorPlacement: _storyAnchorPlacementForItem(doc, anchItems[0], story)
                                            });
                                        } else {
                                            anchoredIds.push({ id: null, storyAnchorPlacement: null });
                                        }
                                    } catch (ea) { anchoredIds.push({ id: null, storyAnchorPlacement: null }); }
                                }
                            }
                        } catch (ea2) {}

                        var anchorIdx = 0;
                        for (var pi2 = 0; pi2 < parts.length; pi2++) {
                            if (parts[pi2].length > 0) {
	                                var partRun = {};
	                                for (var rk in runData) { partRun[rk] = runData[rk]; }
	                                partRun.text = parts[pi2];
                                if (needsCharacterCorrection) {
                                    collectStats.splitCalls++;
                                    collectStats.splitCorrectionCalls++;
                                    collectStats.splitInputChars += partRun.text ? partRun.text.length : 0;
                                    var splitStartMs1 = (new Date()).getTime();
	                                    var partSplits = splitRunByStoryChars(story, rng, partRun, para, needsCharacterCorrection, correctionDescriptor);
                                    collectStats.splitMs += (new Date()).getTime() - splitStartMs1;
                                    collectStats.splitOutputRuns += partSplits.length;
                                    if (collectStats.correctionStyles[paraStyleNameForStats]) {
                                        collectStats.correctionStyles[paraStyleNameForStats].splitCalls++;
                                        collectStats.correctionStyles[paraStyleNameForStats].splitMs += (new Date()).getTime() - splitStartMs1;
                                    }
                                    if (partSplits.length > 1) {
                                        collectStats.splitChangedCalls++;
                                        if (collectStats.correctionStyles[paraStyleNameForStats]) {
                                            collectStats.correctionStyles[paraStyleNameForStats].splitChangedCalls++;
                                        }
                                    }
	                                    for (var ps = 0; ps < partSplits.length; ps++) {
	                                        paraData.runs.push(partSplits[ps]);
	                                    }
                                } else {
                                    paraData.runs.push(partRun);
                                }
                            }
                            if (pi2 < parts.length - 1) {
                                var anchorRecord = anchorIdx < anchoredIds.length ? anchoredIds[anchorIdx] : null;
                                if (anchorRecord && anchorRecord.id !== null && anchorRecord.id !== undefined
                                        && anchorRecord.storyAnchorPlacement !== "FLOATING_ANCHORED") {
                                    paraData.runs.push({
                                        type: "inline_anchor",
                                        anchoredObjectId: anchorRecord.id,
                                        storyAnchorPlacement: anchorRecord.storyAnchorPlacement || null
                                    });
                                }
                                anchorIdx++;
                            }
                        }
	                    } else {
	                        // GREP/중첩 스타일 보정: story.characters로 문자별 색상/크기 확인
                        if (needsCharacterCorrection) {
                            collectStats.splitCalls++;
                            collectStats.splitCorrectionCalls++;
                            collectStats.splitInputChars += runData.text ? runData.text.length : 0;
                            var splitStartMs2 = (new Date()).getTime();
	                            var splitRuns = splitRunByStoryChars(story, rng, runData, para, needsCharacterCorrection, correctionDescriptor);
                            collectStats.splitMs += (new Date()).getTime() - splitStartMs2;
                            collectStats.splitOutputRuns += splitRuns.length;
                            if (collectStats.correctionStyles[paraStyleNameForStats]) {
                                collectStats.correctionStyles[paraStyleNameForStats].splitCalls++;
                                collectStats.correctionStyles[paraStyleNameForStats].splitMs += (new Date()).getTime() - splitStartMs2;
                            }
                            if (splitRuns.length > 1) {
                                collectStats.splitChangedCalls++;
                                if (collectStats.correctionStyles[paraStyleNameForStats]) {
                                    collectStats.correctionStyles[paraStyleNameForStats].splitChangedCalls++;
                                }
                            }
	                            for (var sr = 0; sr < splitRuns.length; sr++) {
	                                paraData.runs.push(splitRuns[sr]);
	                            }
                        } else {
                            paraData.runs.push(runData);
                        }
                    }
                }
            } catch (e) {
                // textStyleRanges 접근 실패 시 무시
            }
            storyData.paragraphs.push(paraData);
        }
        collectStats.paragraphLoopMs += (new Date()).getTime() - paragraphLoopStartMs;

        // 테이블 수집 (실제 치수)
        var tableLoopStartMs = (new Date()).getTime();
        try {
            var tables = story.tables.everyItem().getElements();
            for (var ti = 0; ti < tables.length; ti++) {
                var tbl = tables[ti];
                collectStats.tables++;
                var tblData = {
                    id: tbl.id.toString(),
                    rowCount: tbl.rows.length,
                    columnCount: tbl.columns.length,
                    columnWidths: [],
                    rowHeights: [],
                    bounds: null  // [top, left, bottom, right] page-relative
                };
                var tblCols = null;
                var tblRows = null;
                try { tblCols = tbl.columns.everyItem().getElements(); } catch (eCols) {}
                try { tblRows = tbl.rows.everyItem().getElements(); } catch (eRows) {}
                // 테이블 위치는 source container가 명확하면 그 content box를 우선한다.
                // table-only TextFrame은 table 자체를 담는 carrier source이므로,
                // 셀 텍스트 baseline으로 table top을 역산하면 vertical justification/first
                // baseline 정책에 따라 source grid보다 위아래로 흔들린다.
                try {
                    var firstCell = tbl.cells[0];
                    var tblOwnerTextFrame = null;
                    try { tblOwnerTextFrame = firstCell.texts[0].parentTextFrames[0]; } catch (eOwnerTf) {}
                    if (!isMarkerOnlyTextFrameLocal(tblOwnerTextFrame)) {
                        tblOwnerTextFrame = firstTextFrameFromStoryLocal(story);
                    }
                    if (isMarkerOnlyTextFrameLocal(tblOwnerTextFrame)) {
                        tblData.bounds = textFrameContentBoundsPageRelativeLocal(tblOwnerTextFrame);
                        tblData.boundsSource = "table_only_carrier_text_frame";
                    }
                } catch (eCarrierBounds) {}
                // 일반 story table fallback: 첫 셀의 baseline + 행/열 크기로 bounds 계산.
                try {
                    if (tblData.bounds) throw "table bounds already resolved from carrier";
                    var firstCell = tbl.cells[0];
                    var firstIP = firstCell.insertionPoints[0];
                    var baseline = firstIP.baseline;
                    var horzOffset = firstIP.horizontalOffset;

                    var tblTotalW = 0;
                    for (var twi = 0; tblCols && twi < tblCols.length; twi++) tblTotalW += tblCols[twi].width;
                    var tblTotalH = 0;
                    for (var thi = 0; tblRows && thi < tblRows.length; thi++) tblTotalH += tblRows[thi].height;

                    var tblTop = baseline - (tblRows && tblRows.length > 0 ? tblRows[0].height : 0);
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
                    var cols = tblCols || tbl.columns.everyItem().getElements();
                    for (var ci = 0; ci < cols.length; ci++) {
                        tblData.columnWidths.push(cols[ci].width);
                    }
                } catch (e) {}
                try {
                    var rows = tblRows || tbl.rows.everyItem().getElements();
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
	                        collectStats.cells++;
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
                            var previousCellParagraphLastAnchorId = null;
                            function cellRunHasMeaningfulTextForBoundaryDedupe(run) {
                                var text = "";
                                try {
                                    if (run && run.text) {
                                        text = String(run.text);
                                    }
                                } catch (eText) {}
                                if (!text) {
                                    return false;
                                }
                                text = text.replace(/[\uFFFC\u0016\r\n\t\b\u00A0\u2000-\u200B\uFEFF ]/g, "");
                                return text.length > 0;
                            }
                            function cellRunsHaveMeaningfulTextForBoundaryDedupe(runs) {
                                if (!runs) {
                                    return false;
                                }
                                for (var dr = 0; dr < runs.length; dr++) {
                                    if (runs[dr] && runs[dr].type !== "inline_anchor"
                                            && cellRunHasMeaningfulTextForBoundaryDedupe(runs[dr])) {
                                        return true;
                                    }
                                }
                                return false;
                            }
                            function lastInlineAnchorIdForBoundaryDedupe(runs) {
                                if (!runs) {
                                    return null;
                                }
                                for (var lr = runs.length - 1; lr >= 0; lr--) {
                                    if (runs[lr] && runs[lr].type === "inline_anchor"
                                            && runs[lr].anchoredObjectId !== null
                                            && runs[lr].anchoredObjectId !== undefined) {
                                        return runs[lr].anchoredObjectId;
                                    }
                                }
                                return null;
                            }
                            function shouldAppendCellInlineAnchorForBoundaryDedupe(cpData, anchorId, prevParagraphLastAnchorId) {
                                if (anchorId === null || anchorId === undefined) {
                                    return false;
                                }
                                var runs = cpData && cpData.runs ? cpData.runs : [];
                                if (runs.length > 0) {
                                    var lastRun = runs[runs.length - 1];
                                    if (lastRun && lastRun.type === "inline_anchor"
                                            && String(lastRun.anchoredObjectId) === String(anchorId)) {
                                        return false;
                                    }
                                }
                                if (!cellRunsHaveMeaningfulTextForBoundaryDedupe(runs)
                                        && prevParagraphLastAnchorId !== null
                                        && prevParagraphLastAnchorId !== undefined
                                        && String(prevParagraphLastAnchorId) === String(anchorId)) {
                                    return false;
                                }
                                return true;
                            }
                            function truncateCellParagraphRunsAtFirstHardReturn(runs) {
                                if (!runs || runs.length === 0) {
                                    return runs || [];
                                }
                                var out = [];
                                for (var tr = 0; tr < runs.length; tr++) {
                                    var run = runs[tr];
                                    if (!run || run.type === "inline_anchor") {
                                        out.push(run);
                                        continue;
                                    }
                                    var text = "";
                                    try {
                                        text = run.text !== null && run.text !== undefined
                                                ? String(run.text)
                                                : "";
                                    } catch (eText) {
                                        text = "";
                                    }
                                    var hardReturn = text.indexOf("\r");
                                    if (hardReturn < 0) {
                                        out.push(run);
                                        continue;
                                    }
                                    var copy = {};
                                    for (var key in run) {
                                        copy[key] = run[key];
                                    }
                                    copy.text = text.substring(0, hardReturn + 1);
                                    if (copy.text.length > 0) {
                                        out.push(copy);
                                    }
                                    return out;
                                }
                                return out;
                            }
                            for (var cp = 0; cp < cellParas.length; cp++) {
                                var cellPara = cellParas[cp];
                                var cpData = { runs: [] };
                                var cellGeneratedPrefix = paragraphGeneratedPrefixText(cellPara);
                                if (cellGeneratedPrefix) {
                                    cpData.generatedPrefixText = cellGeneratedPrefix;
                                }
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
                                        // inline anchor source policy: 셀 런도 inline anchor 마커(\uFFFC/\u0016) 분리
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
                                                            if (crcAnch.length > 0) {
                                                                cellAnchoredIds.push({
                                                                    id: crcAnch[0].id,
                                                                    storyAnchorPlacement: _storyAnchorPlacementForItem(doc, crcAnch[0], story)
                                                                });
                                                            } else {
                                                                cellAnchoredIds.push({ id: null, storyAnchorPlacement: null });
                                                            }
                                                        } catch (eca) { cellAnchoredIds.push({ id: null, storyAnchorPlacement: null }); }
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
                                                    var cellAnchorRecord = cellAnchorIdx < cellAnchoredIds.length ? cellAnchoredIds[cellAnchorIdx] : null;
                                                    if (cellAnchorRecord && cellAnchorRecord.id !== null && cellAnchorRecord.id !== undefined
                                                            && cellAnchorRecord.storyAnchorPlacement !== "FLOATING_ANCHORED"
                                                            && shouldAppendCellInlineAnchorForBoundaryDedupe(
                                                                cpData,
                                                                cellAnchorRecord.id,
                                                                previousCellParagraphLastAnchorId
                                                            )) {
                                                        cpData.runs.push({
                                                            type: "inline_anchor",
                                                            anchoredObjectId: cellAnchorRecord.id,
                                                            storyAnchorPlacement: cellAnchorRecord.storyAnchorPlacement || null
                                                        });
                                                    }
                                                    cellAnchorIdx++;
                                                }
                                            }
                                        } else {
                                            cpData.runs.push(runData);
                                        }
                                    }
                                } catch (ec2) {}
                                cpData.runs = truncateCellParagraphRunsAtFirstHardReturn(cpData.runs);
                                previousCellParagraphLastAnchorId = lastInlineAnchorIdForBoundaryDedupe(cpData.runs);
                                cellData.paragraphs.push(cpData);
                            }
                        } catch (ec3) {}
                        // inline anchor source policy: 셀 텍스트에 anchor 마커가 없어도 (IDML에 <Content>가 없고
                        // Group이 CharacterStyleRange에 직접 임베드된 경우) cell.allPageItems
                        // 로 fallback. 이미 paragraphs 에서 잡힌 anchor ID는 건너뛴다.
                        try {
                            if (cellMayHaveAnchoredPageItems(cell)) {
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
                                // 각 문자의 pageItems 에서 직접 앵커 조회. cell.allPageItems 는
                                // 후보 존재 여부 gate 로만 쓰고, 재귀 결과를 visible anchor 로 쓰지 않는다.
                                var missedAnchors = [];
                                try {
                                    var cellChars = cell.characters.everyItem().getElements();
                                    for (var cchi = 0; cchi < cellChars.length; cchi++) {
                                        try {
	                                            var directAnch = cellChars[cchi].pageItems;
	                                            for (var dai = 0; dai < directAnch.length; dai++) {
	                                                var daId = directAnch[dai].id;
	                                                if (_storyAnchorPlacementForItem(doc, directAnch[dai], story) === "FLOATING_ANCHORED") {
	                                                    continue;
	                                                }
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
        collectStats.tableLoopMs += (new Date()).getTime() - tableLoopStartMs;

        stories.push(storyData);
    }
    try {
        var statsFile = File(outputDir + "/_story_collect_stats.json");
        statsFile.encoding = "UTF-8";
        statsFile.open("w");
        statsFile.write(JSON.stringify(collectStats, null, 2));
        statsFile.close();
    } catch (eStats) {}
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
            try { fData.insetSpacing = normalizeTextFrameInsetSpacing(tf.textFramePreferences.insetSpacing); } catch (e) {}
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
            _copyLayerInfo(fData, tf);

            // source ownership policy: 진단/분류 정보 (텍스트 이미지 렌더링 제거 작업용)
            // SPEC-030: classification 제거 (Java 신 파이프라인 미사용)
            try { fData.isMasterPageItem = !!tf.masterPageItem; } catch (e) { fData.isMasterPageItem = false; }
            try { fData.nonprinting = !!tf.nonprinting; } catch (e) { fData.nonprinting = false; }
            try { fData.onHiddenLayer = isOnHiddenLayer(tf); } catch (e) { fData.onHiddenLayer = false; }
            try { fData.visible = (tf.visible !== false); } catch (e) { fData.visible = true; }
            try { fData.hiddenByParent = isHiddenByVisibility(tf); } catch (e) { fData.hiddenByParent = false; }
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
                try { fData2.insetSpacing = normalizeTextFrameInsetSpacing(tf2.textFramePreferences.insetSpacing); } catch (e) {}
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
 * source ownership policy Phase 5: 마스터 스프레드 TextFrame 을 적용 페이지마다 인스턴스화한다.
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
