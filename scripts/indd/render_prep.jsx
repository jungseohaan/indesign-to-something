/**
 * Render preparation and legacy metadata helpers.
 *
 * These helpers prepare/hide/restore source items and annotate already-planned
 * render outputs. They must not create new ownership decisions outside
 * Stage 1/ObjectPlan.
 */

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
 * 아이템 또는 부모 Group 체인에 visible=false가 있는지 검사한다.
 * Hidden layer와 달리 IDML의 Visible=false parent Group은 child TextFrame의
 * itemLayer/nonprinting에는 드러나지 않으므로 별도로 source visibility로 추적한다.
 */
function isHiddenByVisibility(item) {
    try {
        var itemId = item.id;
        if (_hiddenVisibilityCache && _hiddenVisibilityCache[itemId] !== undefined) {
            return _hiddenVisibilityCache[itemId];
        }
        var result = false;
        var cur = item;
        while (cur) {
            try {
                if (cur.visible === false) { result = true; break; }
            } catch (e) {}
            try { cur = cur.parent; } catch (e) { break; }
            if (!cur || cur.constructor.name === "Spread"
                || cur.constructor.name === "Page"
                || cur.constructor.name === "Document"
                || cur.constructor.name === "MasterSpread") break;
        }
        if (_hiddenVisibilityCache) _hiddenVisibilityCache[itemId] = result;
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
    if (isHiddenByVisibility(item)) return "background";
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

/**
 * Blank the text of tables nested in a frame.
 *
 * A frame's content opacity does not reach a table it contains: the table draws
 * as its own content, so its cell text survives an export that was meant to be
 * textless. Returns restore states, empty when the frame has no table.
 */
function _hideTableCellTextForExport(tf) {
    var states = [];
    if (!tf) return states;
    var tables = null;
    try { tables = tf.tables.everyItem().getElements(); } catch (eTables) { return states; }
    for (var ti = 0; tables && ti < tables.length; ti++) {
        var cells = null;
        try { cells = tables[ti].cells.everyItem().getElements(); } catch (eCells) { continue; }
        for (var ci = 0; cells && ci < cells.length; ci++) {
            var target = null;
            try {
                if (cells[ci].texts && cells[ci].texts.length > 0) target = cells[ci].texts[0];
            } catch (eCellText) {}
            if (!target) continue;
            try {
                var blend = target.transparencySettings.blendingSettings;
                states.push({ target: target, opacity: blend.opacity });
                blend.opacity = 0;
            } catch (eCellOpacity) {}
        }
    }
    return states;
}

function _restoreTableCellTextForExport(states) {
    for (var i = 0; states && i < states.length; i++) {
        try {
            states[i].target.transparencySettings.blendingSettings.opacity = states[i].opacity;
        } catch (eRestoreCellOpacity) {}
    }
}

function hideOneTextFrameContent(tf, opts) {
    opts = opts || {};
    if (opts.forceHidden === true) {
        try {
            var wasVisibleForced = tf.visible;
            tf.visible = false;
            return { tf: tf, mode: "visible", wasVisible: wasVisibleForced };
        } catch (eForceVisible) {}
    }
    var preserveFrameVisual = opts.preserveFrameVisual === true
            && textFrameHasVisibleContainerVisual(tf);
    var preferTextPaintOnly = opts.preferTextPaintOnly === true;
    var doc = null;
    try { doc = app.activeDocument; } catch (eDoc) {}
    var noneSwatch = null;
    if (doc) {
        try { noneSwatch = doc.swatches.itemByName("None"); } catch (eNone) {}
        if (!noneSwatch) {
            try { noneSwatch = doc.swatches.itemByName("[None]"); } catch (eBracketNone) {}
        }
    }
    var blackSwatch = null;
    if (doc) {
        try { blackSwatch = doc.swatches.itemByName("Black"); } catch (eBlack) {}
        if (!blackSwatch) {
            try { blackSwatch = doc.swatches.itemByName("[Black]"); } catch (eBracketBlack) {}
        }
    }

    if (!preferTextPaintOnly) {
        try {
            var contentBlend = tf.contentTransparencySettings.blendingSettings;
            var oldOpacity = contentBlend.opacity;
            contentBlend.opacity = 0;
            // Content opacity blanks the frame's own text, but a table inside the
            // frame draws as its own content and keeps rendering. A frame whose
            // text lives in a table would otherwise export with that text baked
            // in — visible both in the shell PNG and in the page plane.
            var cellStates = _hideTableCellTextForExport(tf);
            return {
                tf: tf,
                mode: "contentOpacity",
                opacity: oldOpacity,
                cellTextStates: cellStates
            };
        } catch (eContentOpacityFirst) {}
    }

    function hideTextPaintTarget(target) {
        var state = { target: target };
        var changed = false;
        var hidGlyphPaint = false;
        try { state.fillColor = target.fillColor; } catch (eFillRead) {}
        try { state.fillTint = target.fillTint; } catch (eFillTintRead) {}
        try { state.strokeColor = target.strokeColor; } catch (eStrokeRead) {}
        try { state.strokeTint = target.strokeTint; } catch (eStrokeTintRead) {}
        try { state.opacity = target.transparencySettings.blendingSettings.opacity; } catch (eOpacityRead) {}
        try { state.fillOpacity = target.fillTransparencySettings.blendingSettings.opacity; } catch (eFillOpacityRead) {}
        try { state.strokeOpacity = target.strokeTransparencySettings.blendingSettings.opacity; } catch (eStrokeOpacityRead) {}
        try { state.underline = target.underline; } catch (eUnderlineRead) {}
        try { state.underlineColor = target.underlineColor; } catch (eUnderlineColorRead) {}
        try { state.underlineTint = target.underlineTint; } catch (eUnderlineTintRead) {}
        try { state.strikeThru = target.strikeThru; } catch (eStrikeRead) {}
        try { state.strikeThroughColor = target.strikeThroughColor; } catch (eStrikeColorRead) {}
        try { state.strikeThroughTint = target.strikeThroughTint; } catch (eStrikeTintRead) {}

        if (noneSwatch) {
            try { target.fillColor = noneSwatch; changed = true; hidGlyphPaint = true; } catch (eFillWrite) {}
            try { target.strokeColor = noneSwatch; changed = true; hidGlyphPaint = true; } catch (eStrokeWrite) {}
            try { target.underlineColor = noneSwatch; } catch (eUnderlineColorWrite) {}
            try { target.strikeThroughColor = noneSwatch; } catch (eStrikeColorWrite) {}
        }
        if (blackSwatch) {
            try { target.fillColor = blackSwatch; changed = true; hidGlyphPaint = true; } catch (eBlackFillWrite) {}
            try { target.strokeColor = blackSwatch; changed = true; hidGlyphPaint = true; } catch (eBlackStrokeWrite) {}
        }
        try { target.fillTint = 0; changed = true; hidGlyphPaint = true; } catch (eFillTintWrite) {}
        try { target.strokeTint = 0; changed = true; hidGlyphPaint = true; } catch (eStrokeTintWrite) {}
        try { target.transparencySettings.blendingSettings.opacity = 0; changed = true; hidGlyphPaint = true; } catch (eOpacityWrite) {}
        try { target.fillTransparencySettings.blendingSettings.opacity = 0; changed = true; hidGlyphPaint = true; } catch (eFillOpacityWrite) {}
        try { target.strokeTransparencySettings.blendingSettings.opacity = 0; changed = true; hidGlyphPaint = true; } catch (eStrokeOpacityWrite) {}
        try { target.underline = false; changed = true; } catch (eUnderlineWrite) {}
        try { target.strikeThru = false; changed = true; } catch (eStrikeWrite) {}
        return changed && hidGlyphPaint ? state : null;
    }

    try {
        var savedTargets = [];
        try {
            if (tf.texts && tf.texts.length > 0) {
                var textState = hideTextPaintTarget(tf.texts[0]);
                if (textState) savedTargets.push(textState);
            }
        } catch (eTextTarget) {}
        var ranges = tf.textStyleRanges.everyItem().getElements();
        for (var ri = 0; ri < ranges.length; ri++) {
            var rangeState = hideTextPaintTarget(ranges[ri]);
            if (rangeState) savedTargets.push(rangeState);
        }
        try {
            var tables = tf.tables.everyItem().getElements();
            for (var ti = 0; ti < tables.length; ti++) {
                var cells = tables[ti].cells.everyItem().getElements();
                for (var ci = 0; ci < cells.length; ci++) {
                    try {
                        if (cells[ci].texts && cells[ci].texts.length > 0) {
                            var cellTextState = hideTextPaintTarget(cells[ci].texts[0]);
                            if (cellTextState) savedTargets.push(cellTextState);
                        }
                    } catch (eCellText) {}
                    try {
                        var cellRanges = cells[ci].textStyleRanges.everyItem().getElements();
                        for (var cri = 0; cri < cellRanges.length; cri++) {
                            var cellRangeState = hideTextPaintTarget(cellRanges[cri]);
                            if (cellRangeState) savedTargets.push(cellRangeState);
                        }
                    } catch (eCellRanges) {}
                }
            }
        } catch (eTableTextTargets) {}
        if (savedTargets.length > 0) {
            return { tf: tf, mode: "textPaintTargets", targets: savedTargets };
        }
    } catch (eTextStyleRanges) {}

    if (preserveFrameVisual === true) {
        return null;
    }

    try {
        try {
            var wasVisible = tf.visible;
            tf.visible = false;
            return { tf: tf, mode: "visible", wasVisible: wasVisible };
        } catch (eVisible) {}
    } catch (eOpacity) {}
    return null;
}

function textFrameHasVisibleContainerVisual(tf) {
    if (!tf) return false;
    if (hasVisibleFramePaint(tf)) return true;
    if (hasVisibleTablePaint(tf)) return true;
    return false;
}

function swatchLooksNone(value) {
    if (value === null || value === undefined) return true;
    var name = null;
    try { name = String(value.name || ""); } catch (eName) {}
    if (name === null || name === "") {
        try { name = String(value); } catch (eString) {}
    }
    if (name === null) return false;
    return name === "None" || name === "[None]" || name === "$ID/[None]";
}

function numericOrZero(value) {
    var num = Number(value);
    if (isNaN(num)) return 0;
    return num;
}

function hasVisibleFramePaint(item) {
    if (!item) return false;
    try {
        if (!swatchLooksNone(item.fillColor)) return true;
    } catch (eFill) {}
    try {
        if (numericOrZero(item.strokeWeight) > 0
                && !swatchLooksNone(item.strokeColor)) {
            return true;
        }
    } catch (eStroke) {}
    return false;
}

function hasVisibleTablePaint(tf) {
    try {
        var tables = tf.tables.everyItem().getElements();
        for (var ti = 0; ti < tables.length; ti++) {
            if (hasVisibleTableObjectPaint(tables[ti])) return true;
            var cells = tables[ti].cells.everyItem().getElements();
            for (var ci = 0; ci < cells.length; ci++) {
                if (hasVisibleTableObjectPaint(cells[ci])) return true;
            }
        }
    } catch (eTables) {}
    return false;
}

function hasVisibleTableObjectPaint(item) {
    if (!item) return false;
    try {
        if (!swatchLooksNone(item.fillColor)) return true;
    } catch (eFill) {}
    var sides = ["top", "left", "bottom", "right"];
    for (var i = 0; i < sides.length; i++) {
        var side = sides[i];
        var weightName = side + "EdgeStrokeWeight";
        var colorName = side + "EdgeStrokeColor";
        try {
            if (numericOrZero(item[weightName]) > 0
                    && !swatchLooksNone(item[colorName])) {
                return true;
            }
        } catch (eSide) {}
    }
    try {
        if (numericOrZero(item.strokeWeight) > 0
                && !swatchLooksNone(item.strokeColor)) {
            return true;
        }
    } catch (eStroke) {}
    return false;
}

function hideTextFrames(renderTarget, opts) {
    // TextFrame 자체를 숨기면 fill/stroke로 만든 박스 그래픽까지 사라진다.
    // content opacity만 0으로 낮춰 텍스트 픽셀만 제거하고 프레임 그래픽은 보존한다.
    opts = opts || {};
    var targetTextFrameIdSet = null;
    if (opts.textFrameIds && opts.textFrameIds.length > 0) {
        targetTextFrameIdSet = {};
        for (var ti = 0; ti < opts.textFrameIds.length; ti++) {
            targetTextFrameIdSet[String(opts.textFrameIds[ti])] = true;
        }
    }
    function shouldHideTextFrame(tf) {
        if (!targetTextFrameIdSet) return true;
        try { return targetTextFrameIdSet[String(tf.id)] === true; } catch (eId) {}
        return false;
    }
    var saved = [];
    var rootTextFrameId = null;
    try {
        if (renderTarget && renderTarget.constructor.name === "TextFrame") {
            rootTextFrameId = renderTarget.id;
            if (shouldHideTextFrame(renderTarget)) {
                var rootSaved = hideOneTextFrameContent(renderTarget, opts);
                if (rootSaved) saved.push(rootSaved);
            }
        }
    } catch (eRoot) {}
    try {
        var nested = renderTarget.allPageItems;
        for (var hi = 0; hi < nested.length; hi++) {
            if (nested[hi].constructor.name === "TextFrame") {
                try {
                    var tf = nested[hi];
                    if (rootTextFrameId !== null && tf.id === rootTextFrameId) continue;
                    if (!shouldHideTextFrame(tf)) continue;
                    var itemSaved = hideOneTextFrameContent(tf, opts);
                    if (itemSaved) saved.push(itemSaved);
                } catch (eOne) {}
            }
        }
    } catch (e) {}
    return saved;
}

function hideTextFramesFromNestedItems(nested, opts) {
    opts = opts || {};
    var targetTextFrameIdSet = null;
    if (opts.textFrameIds && opts.textFrameIds.length > 0) {
        targetTextFrameIdSet = {};
        for (var ti = 0; ti < opts.textFrameIds.length; ti++) {
            targetTextFrameIdSet[String(opts.textFrameIds[ti])] = true;
        }
    }
    var saved = [];
    if (!nested) return saved;
    for (var hi = 0; hi < nested.length; hi++) {
        try {
            if (nested[hi].constructor.name !== "TextFrame") continue;
            if (targetTextFrameIdSet && targetTextFrameIdSet[String(nested[hi].id)] !== true) continue;
            var itemSaved = hideOneTextFrameContent(nested[hi], opts);
            if (itemSaved) saved.push(itemSaved);
        } catch (eOpacity) {}
    }
    return saved;
}

function hideRepeatedCellBackgroundCandidates(renderTarget) {
    // Table/cell decoration is source-owned textless visual material.  Older
    // migration code hid repeated cell background rectangles here because Java
    // later projected them into HWP table style.  V2 no longer absorbs table
    // decoration into HWPX table properties, so these source shapes must stay
    // visible in the extracted PNG/vector group.
    return [];

    // Legacy implementation kept below as dead reference until the remaining
    // table-style projection code is fully removed.
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

// ── 사이드박스 deco 그룹 분해: 대형 배경/제목바/불릿을 통-PNG에서 분리 ──

function _isSolidFillShape(it) {
    try {
        if (Math.abs(it.rotationAngle || 0) > 0.1) return false;
    } catch (eRot) {}
    var fn = null;
    try { fn = it.fillColor ? it.fillColor.name : null; } catch (eF) {}
    if (!fn || fn === "None" || fn === "[None]" || fn === "Paper") return false;
    try { if (it.fillColor.constructor.name === "Gradient") return false; } catch (eGr) {}
    try { if (it.transparencySettings.blendingSettings.opacity < 95) return false; } catch (eOp) {}
    try { if (it.transparencySettings.dropShadowSettings.mode === ShadowMode.DROP) return false; } catch (eSh) {}
    try { if (it.transparencySettings.gradientFeatherSettings.applied) return false; } catch (eGf) {}
    return true;
}

function _largestEditableTextFrameBounds(grp) {
    var best = null, bestArea = 0;
    try {
        var nested = grp.allPageItems;
        for (var i = 0; i < nested.length; i++) {
            var it = nested[i];
            try { if (it.constructor.name !== "TextFrame") continue; } catch (e0) { continue; }
            try { if (isOnHiddenLayer(it)) continue; } catch (e1) {}
            var b = null; try { b = it.geometricBounds; } catch (e2) {}
            if (!b || b.length < 4) continue;
            var a = (b[2] - b[0]) * (b[3] - b[1]);
            if (a > bestArea) { bestArea = a; best = [b[0], b[1], b[2], b[3]]; }
        }
    } catch (e) {}
    return best;
}

// 단일 대형 솔리드 배경 Rectangle(사이드박스 전체 배경). 0개/2개+면 null(기존 베이킹 유지).
function _findLargeSolidFillBackgroundChild(grp) {
    try {
        var grpB = grp.geometricBounds;
        if (!grpB || grpB.length < 4) return null;
        var grpArea = (grpB[2] - grpB[0]) * (grpB[3] - grpB[1]);
        if (grpArea <= 0) return null;
        var tfB = _largestEditableTextFrameBounds(grp);
        if (!tfB) return null;
        var tfArea = (tfB[2] - tfB[0]) * (tfB[3] - tfB[1]);
        if (tfArea <= 0) return null;

        var candidate = null, count = 0;
        var nested = grp.allPageItems;
        for (var i = 0; i < nested.length; i++) {
            var rc = nested[i];
            try { if (rc.constructor.name !== "Rectangle") continue; } catch (e0) { continue; }
            try { if (isOnHiddenLayer(rc)) continue; } catch (e1) {}
            try { if (rc.nonprinting) continue; } catch (e2) {}
            if (isInlineItem(rc)) continue;            // 인라인 앵커 도형 제외
            if (!_isSolidFillShape(rc)) continue;
            var rb = null; try { rb = rc.geometricBounds; } catch (e3) {}
            if (!rb || rb.length < 4) continue;
            var rArea = (rb[2] - rb[0]) * (rb[3] - rb[1]);
            if (rArea < grpArea * 0.55) continue;       // 그룹의 절반 이상
            var ov = boundsOverlapArea(rb, tfB);
            if (ov / tfArea < 0.75) continue;           // 메인 TF를 덮음
            candidate = rc; count++;
        }
        return count === 1 ? candidate : null;
    } catch (e) { return null; }
}

// 그룹 안 인라인 앵커 시각 객체(불릿 등) — 본문이 인라인 렌더하므로 PNG에서 제거.
function _findInlineAnchoredVisualItems(grp) {
    var items = [];
    try {
        var nested = grp.allPageItems;
        for (var i = 0; i < nested.length; i++) {
            var it = nested[i];
            try { if (it.constructor.name === "TextFrame") continue; } catch (e0) { continue; }
            try { if (isOnHiddenLayer(it)) continue; } catch (e1) {}
            if (isInlineItem(it)) items.push(it);
        }
    } catch (e) {}
    return items;
}

// 범용: 도형들을 PNG 굽기 전 숨김(fill=None+opacity0, 폴백 visible=false). restore 대칭.
function _hideItemsForExport(items) {
    var saved = [];
    if (!items) return saved;
    for (var i = 0; i < items.length; i++) {
        var item = items[i];
        try {
            if (item && item.constructor && item.constructor.name === "TextFrame") {
                var wasVisibleTf = item.visible;
                item.visible = false;
                saved.push({
                    item: item,
                    mode: "visible",
                    wasVisible: wasVisibleTf,
                    reason: "planned_inline_textframe_hidden_for_page_plane"
                });
                continue;
            }
        } catch (eTextFrameVisible) {}
        var state = { item: item, mode: "cellBg" };
        var changed = false;
        try { state.fillColor = item.fillColor; item.fillColor = app.activeDocument.swatches.itemByName("None"); changed = true; }
        catch (e1) { try { state.fillColor = item.fillColor; item.fillColor = app.activeDocument.swatches.itemByName("[None]"); changed = true; } catch (e2) {} }
        try { var blend = item.transparencySettings.blendingSettings; state.opacity = blend.opacity; blend.opacity = 0; changed = true; } catch (e3) {}
        try { var fb = item.fillTransparencySettings.blendingSettings; state.fillOpacity = fb.opacity; fb.opacity = 0; changed = true; } catch (e4) {}
        try { var sb = item.strokeTransparencySettings.blendingSettings; state.strokeOpacity = sb.opacity; sb.opacity = 0; changed = true; } catch (e5) {}
        if (!changed) { try { state.wasVisible = item.visible; item.visible = false; state.mode = "visible"; changed = true; } catch (e6) {} }
        if (changed) saved.push(state);
    }
    return saved;
}

function _restoreItemsForExport(saved) {
    restoreRepeatedCellBackgroundCandidates(saved);
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

function collectTfInlineVisualIdsFromNestedItems(renderTarget, nested) {
    if (!nested) return collectTfInlineVisualIds(renderTarget);
    var ids = [], seen = {};
    if (!renderTarget) return ids;

    var nestedIds = {};
    try { nestedIds[renderTarget.id] = true; } catch (e) {}
    for (var ni = 0; ni < nested.length; ni++) {
        try { nestedIds[nested[ni].id] = true; } catch (e2) {}
    }

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
    for (var ai = 0; ai < nested.length; ai++) {
        try {
            if (nested[ai].constructor.name === "TextFrame") collectFromStory(nested[ai]);
        } catch (e2) {}
    }
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

function hideTextFrameOwnedInlineVisualsFromNestedItems(nested, ids) {
    var saved = [];
    if (!nested || !ids || ids.length === 0) return saved;
    var idMap = {};
    for (var ii = 0; ii < ids.length; ii++) idMap[ids[ii].toString()] = true;

    for (var ni = 0; ni < nested.length; ni++) {
        try {
            var it = nested[ni];
            if (!idMap[it.id.toString()]) continue;
            var wasVisible = true;
            try { wasVisible = it.visible; } catch (eVis) {}
            it.visible = false;
            saved.push({ tf: it, mode: "visible", wasVisible: wasVisible });
        } catch (eHide) {}
    }
    return saved;
}

function hideTextFramesAndOwnedInlineVisuals(renderTarget, opts) {
    var saved = hideTextFrames(renderTarget, opts);
    var savedInline = hideTextFrameOwnedInlineVisuals(renderTarget);
    for (var i = 0; i < savedInline.length; i++) saved.push(savedInline[i]);
    return saved;
}

function clearTableCellTextContentsForExport(renderTarget) {
    try {
        var tables = renderTarget.tables.everyItem().getElements();
        for (var ti = 0; ti < tables.length; ti++) {
            var cells = tables[ti].cells.everyItem().getElements();
            for (var ci = 0; ci < cells.length; ci++) {
                try {
                    if (cells[ci].texts && cells[ci].texts.length > 0) {
                        cells[ci].texts[0].contents = "";
                    }
                } catch (eCellTextClear) {}
            }
        }
    } catch (eRootTables) {}
    try {
        var nested = renderTarget.allPageItems;
        for (var ni = 0; ni < nested.length; ni++) {
            try {
                if (!nested[ni] || !nested[ni].tables) continue;
                var nestedTables = nested[ni].tables.everyItem().getElements();
                for (var nti = 0; nti < nestedTables.length; nti++) {
                    var nestedCells = nestedTables[nti].cells.everyItem().getElements();
                    for (var nci = 0; nci < nestedCells.length; nci++) {
                        try {
                            if (nestedCells[nci].texts && nestedCells[nci].texts.length > 0) {
                                nestedCells[nci].texts[0].contents = "";
                            }
                        } catch (eNestedCellTextClear) {}
                    }
                }
            } catch (eNestedTables) {}
        }
    } catch (eNestedItems) {}
}

function hideTextFramesAndOwnedInlineVisualsFromNestedItems(nested, inlineVisualIds, opts) {
    var saved = hideTextFramesFromNestedItems(nested, opts);
    var savedInline = hideTextFrameOwnedInlineVisualsFromNestedItems(nested, inlineVisualIds);
    for (var i = 0; i < savedInline.length; i++) saved.push(savedInline[i]);
    return saved;
}

function restoreTextFrames(saved) {
    for (var ri = 0; ri < saved.length; ri++) {
        try {
            if (saved[ri].mode === "textPaintTargets" || saved[ri].mode === "textStyleRanges") {
                var targets = saved[ri].targets || saved[ri].ranges || [];
                for (var r = targets.length - 1; r >= 0; r--) {
                    var state = targets[r];
                    var target = state.target || state.range;
                    try { if (state.fillColor !== undefined) target.fillColor = state.fillColor; } catch (eFill) {}
                    try { if (state.fillTint !== undefined) target.fillTint = state.fillTint; } catch (eFillTint) {}
                    try { if (state.strokeColor !== undefined) target.strokeColor = state.strokeColor; } catch (eStroke) {}
                    try { if (state.strokeTint !== undefined) target.strokeTint = state.strokeTint; } catch (eStrokeTint) {}
                    try { if (state.opacity !== undefined) target.transparencySettings.blendingSettings.opacity = state.opacity; } catch (eOpacity) {}
                    try { if (state.fillOpacity !== undefined) target.fillTransparencySettings.blendingSettings.opacity = state.fillOpacity; } catch (eFillOpacity) {}
                    try { if (state.strokeOpacity !== undefined) target.strokeTransparencySettings.blendingSettings.opacity = state.strokeOpacity; } catch (eStrokeOpacity) {}
                    try { if (state.underline !== undefined) target.underline = state.underline; } catch (eUnderline) {}
                    try { if (state.underlineColor !== undefined) target.underlineColor = state.underlineColor; } catch (eUnderlineColor) {}
                    try { if (state.underlineTint !== undefined) target.underlineTint = state.underlineTint; } catch (eUnderlineTint) {}
                    try { if (state.strikeThru !== undefined) target.strikeThru = state.strikeThru; } catch (eStrike) {}
                    try { if (state.strikeThroughColor !== undefined) target.strikeThroughColor = state.strikeThroughColor; } catch (eStrikeColor) {}
                    try { if (state.strikeThroughTint !== undefined) target.strikeThroughTint = state.strikeThroughTint; } catch (eStrikeTint) {}
                }
            } else if (saved[ri].mode === "contentOpacity") {
                saved[ri].tf.contentTransparencySettings.blendingSettings.opacity = saved[ri].opacity;
                _restoreTableCellTextForExport(saved[ri].cellTextStates);
            } else {
                saved[ri].tf.visible = saved[ri].wasVisible;
            }
        } catch (e) {}
    }
}

function snapshotEditableTextFramePaintState(allItems, editableFrameIds) {
    var snapshots = [];
    if (!allItems || !editableFrameIds) return snapshots;

    for (var i = 0; i < allItems.length; i++) {
        try {
            var tf = allItems[i];
            if (!tf || !tf.constructor || tf.constructor.name !== "TextFrame") continue;
            if (!editableFrameIds[tf.id]) continue;

            var entry = {
                tf: tf,
                targets: []
            };
            try { entry.visible = tf.visible; } catch (eVisible) {}
            try {
                entry.contentOpacity =
                        tf.contentTransparencySettings.blendingSettings.opacity;
            } catch (eContentOpacity) {}
            try {
                entry.frameOpacity =
                        tf.transparencySettings.blendingSettings.opacity;
            } catch (eFrameOpacity) {}

            snapshots.push(entry);
        } catch (eSnapshot) {}
    }
    return snapshots;
}

// SPEC-086: 각 렌더 패스는 자신이 바꾼 text paint target을 같은 패스의 finally에서
// 복원한다. 이 장기 snapshot은 패스 밖에서 바뀔 수 있는 frame-level state만 지키는
// 최종 안전망이다. 모든 textStyleRange의 10개 paint 속성을 다시 읽는 것은 resolved
// 수집 직전에 InDesign 재조판을 유발하며, u2에서 복원만 93초가 걸렸다.
// target 단위 복원을 여기에 다시 추가하지 말고, paint를 바꾸는 렌더 패스의 saved
// state/restore 쌍에 포함한다.
function _restorePaintPropIfChanged(target, prop, value) {
    if (value === undefined) return;
    try {
        var current;
        try { current = target[prop]; } catch (eRead) { target[prop] = value; return; }
        if (current === value) return;
        // Swatch/Color 류는 접근마다 새 래퍼가 와서 참조 비교 불가 — id 로 비교
        try {
            if (current && value && current.id !== undefined && value.id !== undefined
                    && current.id === value.id) {
                return;
            }
        } catch (eIdCompare) {}
        target[prop] = value;
    } catch (eWrite) {}
}

function restoreEditableTextFramePaintState(snapshots) {
    if (!snapshots) return;
    for (var i = snapshots.length - 1; i >= 0; i--) {
        try {
            var entry = snapshots[i];
            var tf = entry.tf;
            if (entry.visible !== undefined) {
                try { if (tf.visible !== entry.visible) tf.visible = entry.visible; } catch (eVisible) {}
            }
            if (entry.contentOpacity !== undefined) {
                try {
                    var cb = tf.contentTransparencySettings.blendingSettings;
                    if (cb.opacity !== entry.contentOpacity) cb.opacity = entry.contentOpacity;
                } catch (eContentOpacity) {}
            }
            if (entry.frameOpacity !== undefined) {
                try {
                    var fb = tf.transparencySettings.blendingSettings;
                    if (fb.opacity !== entry.frameOpacity) fb.opacity = entry.frameOpacity;
                } catch (eFrameOpacity) {}
            }
            var targets = entry.targets || [];
            for (var r = targets.length - 1; r >= 0; r--) {
                var state = targets[r];
                var target = state.target;
                _restorePaintPropIfChanged(target, "fillColor", state.fillColor);
                _restorePaintPropIfChanged(target, "fillTint", state.fillTint);
                _restorePaintPropIfChanged(target, "strokeColor", state.strokeColor);
                _restorePaintPropIfChanged(target, "strokeTint", state.strokeTint);
                _restorePaintPropIfChanged(target, "underline", state.underline);
                _restorePaintPropIfChanged(target, "underlineColor", state.underlineColor);
                _restorePaintPropIfChanged(target, "underlineTint", state.underlineTint);
                _restorePaintPropIfChanged(target, "strikeThru", state.strikeThru);
                _restorePaintPropIfChanged(target, "strikeThroughColor", state.strikeThroughColor);
                _restorePaintPropIfChanged(target, "strikeThroughTint", state.strikeThroughTint);
            }
        } catch (eRestoreSnapshot) {}
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

function _boundsOverlap(a, b) {
    if (!a || !b || a.length < 4 || b.length < 4) return false;
    var top = Math.max(a[0], b[0]);
    var left = Math.max(a[1], b[1]);
    var bottom = Math.min(a[2], b[2]);
    var right = Math.min(a[3], b[3]);
    return right > left && bottom > top;
}

function _boundsIntersectionStrict(a, b) {
    if (!a || !b || a.length < 4 || b.length < 4) return null;
    var top = Math.max(a[0], b[0]);
    var left = Math.max(a[1], b[1]);
    var bottom = Math.min(a[2], b[2]);
    var right = Math.min(a[3], b[3]);
    if (right <= left || bottom <= top) return null;
    return [top, left, bottom, right];
}

function _boundsDiffer(a, b, eps) {
    eps = eps || 0.01;
    if (!a || !b || a.length < 4 || b.length < 4) return false;
    return Math.abs(a[0] - b[0]) > eps || Math.abs(a[1] - b[1]) > eps ||
           Math.abs(a[2] - b[2]) > eps || Math.abs(a[3] - b[3]) > eps;
}

function _pageBoundsForIndex(doc, pageIndex) {
    try {
        if (!doc || pageIndex < 0 || pageIndex >= doc.pages.length) return null;
        var pb = doc.pages[pageIndex].bounds;
        if (!pb || pb.length < 4) return null;
        return [Number(pb[0]), Number(pb[1]), Number(pb[2]), Number(pb[3])];
    } catch (e) {
        return null;
    }
}

function _pageSpreadIdForIndex(doc, pageIndex) {
    try {
        if (!doc || pageIndex < 0 || pageIndex >= doc.pages.length) return null;
        var spread = doc.pages[pageIndex].parent;
        return spread ? spread.id : null;
    } catch (e) {
        return null;
    }
}

function _sameSpreadPageIndex(doc, a, b) {
    if (a === null || a === undefined || b === null || b === undefined) return false;
    if (a < 0 || b < 0) return false;
    var spreadA = _pageSpreadIdForIndex(doc, a);
    var spreadB = _pageSpreadIdForIndex(doc, b);
    return spreadA !== null && spreadB !== null && spreadA === spreadB;
}

function _sourceObjectIdsAllowId(sourceObjectIds, id) {
    if (!sourceObjectIds || sourceObjectIds.length === 0) return true;
    return _idArrayContains(sourceObjectIds, id);
}

function _itemTreeContainsId(item, id) {
    if (!item || id === undefined || id === null) return false;
    var key = String(id);
    try {
        if (item.id !== undefined && item.id !== null && String(item.id) === key) return true;
    } catch (eSelf) {}
    try {
        var nested = item.allPageItems;
        for (var i = 0; i < nested.length; i++) {
            try {
                if (nested[i].id !== undefined && nested[i].id !== null && String(nested[i].id) === key) {
                    return true;
                }
            } catch (eNested) {}
        }
    } catch (eAll) {}
    return false;
}

function _sourceObjectIdsAllowItemForExport(sourceObjectIds, item) {
    if (!sourceObjectIds || sourceObjectIds.length === 0) return true;
    try {
        if (_sourceObjectIdsAllowId(sourceObjectIds, item.id)) return true;
    } catch (eDirect) {}
    try {
        var cur = item.parent;
        while (cur) {
            try {
                if (cur.id !== undefined && cur.id !== null
                        && _sourceObjectIdsAllowId(sourceObjectIds, cur.id)) {
                    return true;
                }
            } catch (eCurId) {}
            var kind = "";
            try { kind = cur.constructor ? String(cur.constructor.name || "") : ""; } catch (eKind) {}
            if (kind === "Spread" || kind === "Page" || kind === "Document" || kind === "Story") break;
            try { cur = cur.parent; } catch (eParent) { break; }
        }
    } catch (eAncestor) {}
    for (var i = 0; i < sourceObjectIds.length; i++) {
        if (_itemTreeContainsId(item, sourceObjectIds[i])) return true;
    }
    return false;
}

function _collectOutOfScopeChildrenForSourceIds(item, sourceObjectIds) {
    var out = [];
    if (!sourceObjectIds || sourceObjectIds.length === 0) return out;
    try {
        var nested = item.allPageItems;
        for (var i = 0; i < nested.length; i++) {
            try {
                if (_sourceObjectIdsAllowItemForExport(sourceObjectIds, nested[i])) continue;
                out.push(nested[i]);
            } catch (eChild) {}
        }
    } catch (e) {}
    return out;
}

function _filterIdsAllowedBySourceObjectIds(ids, sourceObjectIds) {
    if (!ids || ids.length === 0) return ids || [];
    if (!sourceObjectIds || sourceObjectIds.length === 0) return ids;
    var out = [];
    for (var i = 0; i < ids.length; i++) {
        if (_sourceObjectIdsAllowId(sourceObjectIds, ids[i])) out.push(ids[i]);
    }
    return out;
}

function _collectSourceObjectIdsFromNestedItems(item, nested) {
    var ids = [], seen = {};
    try { _pushUniqueId(ids, seen, item.id); } catch (e) {}
    if (!nested) return ids;
    for (var i = 0; i < nested.length; i++) {
        try { _pushUniqueId(ids, seen, nested[i].id); } catch (e2) {}
    }
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

function _collectTextFrameIdsFromNestedItems(item, nested, editableOnly, requireContent) {
    var ids = [], seen = {};
    function visit(tf) {
        try {
            if (requireContent && !_textFrameHasContent(tf)) return;
            if (editableOnly && classifyTextFrameCached(tf) !== "editable") return;
            _pushUniqueId(ids, seen, tf.id);
        } catch (e) {}
    }
    try { if (item.constructor.name === "TextFrame") visit(item); } catch (e) {}
    if (!nested) return ids;
    for (var i = 0; i < nested.length; i++) {
        try {
            if (nested[i].constructor.name === "TextFrame") visit(nested[i]);
        } catch (e2) {}
    }
    return ids;
}

function _itemConstructorName(item) {
    try { return item && item.constructor ? item.constructor.name : ""; } catch (e) {}
    return "";
}

function _findNestedPageItemById(root, id) {
    if (!root || id === undefined || id === null) return null;
    var key = id.toString();
    try {
        if (root.id !== undefined && root.id !== null && root.id.toString() === key) return root;
    } catch (e0) {}
    try {
        var nested = root.allPageItems;
        for (var i = 0; i < nested.length; i++) {
            try {
                if (nested[i].id !== undefined && nested[i].id !== null && nested[i].id.toString() === key) {
                    return nested[i];
                }
            } catch (e1) {}
        }
    } catch (e2) {}
    return null;
}

function _commonPageItemParentForIds(ids, itemById) {
    if (!ids || ids.length === 0 || !itemById) return null;
    var items = [];
    for (var i = 0; i < ids.length; i++) {
        var item = itemById[String(ids[i])];
        if (!item) return null;
        items.push(item);
    }
    function parentChain(item) {
        var chain = [];
        var cur = null;
        try { cur = item.parent; } catch (eParent) { cur = null; }
        while (cur) {
            var name = "";
            try { name = cur.constructor ? cur.constructor.name : ""; } catch (eName) {}
            if (name === "Page" || name === "Spread" || name === "Document" || name === "Story") break;
            try {
                if (cur.id !== undefined && cur.id !== null) chain.push(cur);
            } catch (eId) {}
            try { cur = cur.parent; } catch (eNext) { break; }
        }
        return chain;
    }
    function containsAll(candidate) {
        for (var ii = 0; ii < items.length; ii++) {
            if (_findNestedPageItemById(candidate, ids[ii]) === null) return false;
        }
        return true;
    }
    var firstChain = parentChain(items[0]);
    for (var ci = 0; ci < firstChain.length; ci++) {
        if (containsAll(firstChain[ci])) return firstChain[ci];
    }
    return null;
}

function _pageItemContainsAllIds(root, ids) {
    if (!root || !ids || ids.length === 0) return false;
    for (var i = 0; i < ids.length; i++) {
        if (_findNestedPageItemById(root, ids[i]) === null) return false;
    }
    return true;
}

function _idArrayContains(ids, id) {
    if (!ids || id === undefined || id === null) return false;
    if (ids.length > 32) {
        try {
            var firstKey = ids.length > 0 && ids[0] !== undefined && ids[0] !== null ? ids[0].toString() : "";
            var lastKey = ids.length > 0 && ids[ids.length - 1] !== undefined && ids[ids.length - 1] !== null
                    ? ids[ids.length - 1].toString()
                    : "";
            var signature = String(ids.length) + "|" + firstKey + "|" + lastKey;
            if (!ids._idArrayContainsLookup || ids._idArrayContainsLookupSignature !== signature) {
                var lookup = {};
                for (var li = 0; li < ids.length; li++) {
                    if (ids[li] !== undefined && ids[li] !== null) lookup[ids[li].toString()] = true;
                }
                ids._idArrayContainsLookup = lookup;
                ids._idArrayContainsLookupSignature = signature;
            }
            return ids._idArrayContainsLookup[id.toString()] === true;
        } catch (eLookup) {}
    }
    var key = id.toString();
    for (var i = 0; i < ids.length; i++) {
        if (ids[i] !== undefined && ids[i] !== null && ids[i].toString() === key) return true;
    }
    return false;
}

function _idArraySubset(a, b) {
    if (!a) return true;
    for (var i = 0; i < a.length; i++) {
        if (!_idArrayContains(b, a[i])) return false;
    }
    return true;
}

function _removeIds(ids, removeIds) {
    var out = [];
    if (!ids) return out;
    for (var i = 0; i < ids.length; i++) {
        if (!_idArrayContains(removeIds, ids[i])) out.push(ids[i]);
    }
    return out;
}

function _hasVisibleAtomicPaint(item) {
    if (!item) return false;
    var cName = _itemConstructorName(item);
    if (cName === "Group") return true;
    if (!(cName === "Oval" || cName === "Rectangle" || cName === "Polygon")) return false;
    try {
        var fillName = item.fillColor ? item.fillColor.name : null;
        if (fillName && fillName !== "None" && fillName !== "[None]") return true;
    } catch (e0) {}
    try {
        var strokeName = item.strokeColor ? item.strokeColor.name : null;
        var weight = item.strokeWeight || 0;
        if (strokeName && strokeName !== "None" && strokeName !== "[None]" && weight > 0) return true;
    } catch (e1) {}
    return false;
}

function _findAtomicRootForTextFrame(renderTarget, textFrameId) {
    var tf = _findNestedPageItemById(renderTarget, textFrameId);
    if (!tf) return null;
    var parent = null;
    try { parent = tf.parent; } catch (e0) { parent = null; }
    for (var depth = 0; depth < 8 && parent; depth++) {
        if (_hasVisibleAtomicPaint(parent)) {
            var sourceIds = _collectSourceObjectIds(parent);
            if (_idArrayContains(sourceIds, textFrameId)) return parent;
        }
        try {
            var pName = _itemConstructorName(parent);
            if (pName === "Page" || pName === "Spread" || pName === "Document" || pName === "Story") break;
        } catch (e1) {}
        try { parent = parent.parent; } catch (e2) { parent = null; }
    }
    if (_hasVisibleAtomicPaint(renderTarget)) {
        var renderSourceIds = _collectSourceObjectIds(renderTarget);
        if (_idArrayContains(renderSourceIds, textFrameId)) return renderTarget;
    }
    return null;
}

function _pageLocalVisualFragmentsForBounds(doc, anchorPage, sourceBounds, startPage, endPage) {
    var fragments = [];
    if (!doc || !anchorPage || !sourceBounds || sourceBounds.length < 4) return fragments;
    var anchorIdx = -1;
    try { anchorIdx = anchorPage.documentOffset; } catch (eAnchor) { anchorIdx = -1; }
    if (anchorIdx < 0) return fragments;
    var first = Math.max(0, (startPage || 1) - 1);
    var last = Math.min(doc.pages.length - 1, (endPage || doc.pages.length) - 1);
    for (var pi = first; pi <= last; pi++) {
        if (!_sameSpreadPageIndex(doc, anchorIdx, pi)) continue;
        var page = null;
        try { page = doc.pages[pi]; } catch (ePage) { page = null; }
        if (!page) continue;
        var pb = null;
        try { pb = page.bounds; } catch (eBounds) { pb = null; }
        var intersection = _boundsIntersectionStrict(sourceBounds, pb);
        if (!intersection) continue;
        var relBounds = _pageRelativeBoundsCopy(intersection, page);
        if (!relBounds) continue;
        var cropSource = null;
        if (_boundsDiffer(sourceBounds, intersection, 0.01)) {
            cropSource = _pageRelativeBoundsCopy(sourceBounds, page);
        }
        fragments.push({
            page: page,
            pageIndex: pi,
            bounds: relBounds,
            cropSourceBounds: cropSource
        });
    }
    return fragments;
}

function _fragmentsNeedCropSource(fragments) {
    if (!fragments || fragments.length === 0) return false;
    for (var i = 0; i < fragments.length; i++) {
        if (fragments[i] && fragments[i].cropSourceBounds) return true;
    }
    return false;
}

function collectRangePageItems(doc, startPage, endPage) {
    var allItems = [];
    var seen = {};
    var targetSpreads = {};
    var targetPageIndexesByItemId = {};
    function addItem(item, targetPageIndex) {
        try {
            if (!item) return;
            var key = String(item.id);
            if (targetPageIndex !== null && targetPageIndex !== undefined) {
                if (!targetPageIndexesByItemId[key]) targetPageIndexesByItemId[key] = {};
                targetPageIndexesByItemId[key][String(targetPageIndex)] = true;
            }
            if (!seen[key]) {
                seen[key] = true;
                allItems.push(item);
            }
        } catch (e) {}
    }
    function isSpreadOffCanvasTextFrame(tf) {
        try {
            if (!tf || tf.constructor.name !== "TextFrame") return false;
        } catch (eType) { return false; }
        try {
            var parentPage = tf.parentPage;
            if (parentPage) return false;
        } catch (ePage) {}
        try {
            var cur = tf.parent;
            var hop = 0;
            while (cur && hop < 8) {
                var name = "";
                try { name = cur.constructor.name; } catch (eName) {}
                if (name === "Cell") return false;
                if (name === "Spread") return true;
                if (name === "MasterSpread") return false;
                try { cur = cur.parent; } catch (eParent) { break; }
                hop++;
            }
        } catch (eParentChain) {}
        return false;
    }
    function hasMeaningfulStoryText(tf) {
        try {
            return String(tf.contents || "").replace(/[\s﻿\r\n\u0016\u0018\uFFFC]/g, "").length > 0;
        } catch (eText) {}
        return false;
    }
    var startIdx = Math.max(0, (startPage || 1) - 1);
    var endIdx = Math.min(doc.pages.length - 1, (endPage || doc.pages.length) - 1);
    for (var targetIdx = startIdx; targetIdx <= endIdx; targetIdx++) {
        var targetPage = null;
        try { targetPage = doc.pages[targetIdx]; } catch (eTargetPage) { targetPage = null; }
        if (!targetPage) continue;
        try { targetSpreads[String(targetPage.parent.id)] = targetPage.parent; } catch (eTargetSpread) {}
        var targetBounds = null;
        try { targetBounds = targetPage.bounds; } catch (eTargetBounds) { targetBounds = null; }
        var spreadPages = [];
        try {
            var parentSpreadPages = targetPage.parent.pages;
            for (var spi = 0; spi < parentSpreadPages.length; spi++) {
                spreadPages.push(parentSpreadPages[spi]);
            }
        } catch (eSpreadPages) {
            spreadPages = [targetPage];
        }
        for (var sp = 0; sp < spreadPages.length; sp++) {
            var page = spreadPages[sp];
            var pageItems = [];
            try { pageItems = page.allPageItems; } catch (ePageItems) { pageItems = []; }
            for (var ii = 0; ii < pageItems.length; ii++) {
                var item = pageItems[ii];
                var ownerIdx = _pageIndexOfItem(doc, item);
                if (ownerIdx === targetIdx) {
                    addItem(item, targetIdx);
                    continue;
                }
                if (!_sameSpreadPageIndex(doc, ownerIdx, targetIdx)) continue;
                var b = null;
                try { b = _itemBounds(item); } catch (eItemBounds) { b = null; }
                if (targetBounds && b && _boundsOverlap(targetBounds, b)) addItem(item, targetIdx);
            }
        }
    }
    for (var spreadId in targetSpreads) {
        if (!targetSpreads.hasOwnProperty(spreadId)) continue;
        var spread = targetSpreads[spreadId];
        var spreadItems = [];
        try { spreadItems = spread.allPageItems; } catch (eSpreadItems) { spreadItems = []; }
        for (var osi = 0; osi < spreadItems.length; osi++) {
            var tf = spreadItems[osi];
            if (!isSpreadOffCanvasTextFrame(tf)) continue;
            if (!hasMeaningfulStoryText(tf)) continue;
            try { if (isOnHiddenLayer(tf)) continue; } catch (eHidden) {}
            addItem(tf, null);
            try {
                var storyItems = tf.parentStory.allPageItems;
                for (var sii = 0; sii < storyItems.length; sii++) {
                    addItem(storyItems[sii], null);
                }
            } catch (eStoryItems) {}
        }
    }
    collectRangePageItems.lastTargetPageIndexesByItemId = targetPageIndexesByItemId;
    return allItems;
}

function _closedAtomicBundleForEditableTextFrames(renderTarget, editableTfIds, renderSourceIds) {
    if (!renderTarget || !editableTfIds || editableTfIds.length === 0) return null;
    var root = null;
    for (var i = 0; i < editableTfIds.length; i++) {
        var candidate = _findAtomicRootForTextFrame(renderTarget, editableTfIds[i]);
        if (!candidate) return null;
        if (root === null) {
            root = candidate;
        } else {
            try {
                if (root.id.toString() !== candidate.id.toString()) {
                    if (_hasVisibleAtomicPaint(renderTarget)) {
                        root = renderTarget;
                    } else {
                        return null;
                    }
                }
            } catch (e) { return null; }
        }
    }
    var atomicSourceIds = _collectSourceObjectIds(root);
    for (var t = 0; t < editableTfIds.length; t++) {
        if (!_idArrayContains(atomicSourceIds, editableTfIds[t])) return null;
    }
    if (!_idArraySubset(renderSourceIds, atomicSourceIds)) return null;
    return {
        sourceIds: atomicSourceIds,
        visualSourceIds: _removeIds(atomicSourceIds, editableTfIds)
    };
}

function _isAtomicCompletePngReason(reason) {
    return reason === "visual_marker_label_indesign_png"
            || reason === "inline_graphic_only";
}

function _isAtomicTextlessShellReasonForMetadata(reason) {
    return reason === "visual_label_text_hidden_shell"
            || reason === "atomic_ownership_root_text_hidden_shell"
            || reason === "leaf_group_text_hidden_shell"
            || reason === "editable_composite_text_hidden_shell"
            || reason === "inline_text_hidden";
}

function _isAtomicGraphicOnlyReasonForMetadata(reason) {
    return reason === "graphic_ownership_root"
            || reason === "inline_graphic_only"
            || reason === "pure_decoration_group"
            || reason === "decoration_group";
}

function _annotateAtomicObjectOwnership(entry, renderTarget, opts, sourceIds, editableTfIds, textOwner) {
    if (!entry || !renderTarget) return entry;
    var reason = opts && opts.reason ? opts.reason : (entry.reason || "");
    var kind = null;
    var hasEditableText = editableTfIds && editableTfIds.length > 0;
    if (textOwner === "none"
            && !hasEditableText
            && _isAtomicGraphicOnlyReasonForMetadata(reason)
            && _itemConstructorName(renderTarget) === "Group"
            && sourceIds
            && sourceIds.length > 1) {
        entry.atomicObjectKind = "GRAPHIC_ONLY";
        entry.atomicSourceObjectIds = sourceIds;
        entry.atomicOwnedTextFrameIds = [];
        entry.atomicVisualSourceObjectIds = sourceIds;
        return entry;
    }
    if (!hasEditableText) return entry;
    if (textOwner === "indesign_png" && _isAtomicCompletePngReason(reason)) {
        kind = "COMPLETE_PNG";
    } else if (textOwner === "hwpx_tf" && _isAtomicTextlessShellReasonForMetadata(reason)) {
        kind = "TEXTLESS_SHELL_WITH_TF";
    }
    if (!kind) return entry;
    var bundle = _closedAtomicBundleForEditableTextFrames(renderTarget, editableTfIds, sourceIds);
    if (!bundle) return entry;
    entry.atomicObjectKind = kind;
    entry.atomicSourceObjectIds = bundle.sourceIds;
    entry.atomicOwnedTextFrameIds = editableTfIds;
    entry.atomicVisualSourceObjectIds = bundle.visualSourceIds;
    return entry;
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

function _collectVisualOnlyChildIdsFromNestedItems(nested) {
    var ids = [], seen = {};
    if (!nested) return ids;
    for (var i = 0; i < nested.length; i++) {
        try {
            if (nested[i].constructor.name !== "TextFrame") {
                _pushUniqueId(ids, seen, nested[i].id);
            }
        } catch (e2) {}
    }
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

function _copyLayerInfo(entry, item) {
    if (!entry || !item) return entry;
    try {
        if (item.itemLayer) {
            try { entry.layerId = item.itemLayer.id ? item.itemLayer.id.toString() : null; } catch (e0) {}
            try { entry.layerName = item.itemLayer.name || null; } catch (e1) {}
            try { entry.layerIndex = item.itemLayer.index; } catch (e2) {}
        }
    } catch (e) {}
    return entry;
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
    _copyLayerInfo(entry, opts.layerSource || renderTarget);
    if (sourceIds && sourceIds.length > 0) entry.sourceObjectIds = sourceIds;
    if (editableTfIds && editableTfIds.length > 0) entry.editableTextFrameIds = editableTfIds;
    if (opts.hiddenTextFrameIds && opts.hiddenTextFrameIds.length > 0) {
        entry.hiddenTextFrameIds = opts.hiddenTextFrameIds;
    }
    if (opts.hiddenVisualSourceObjectIds && opts.hiddenVisualSourceObjectIds.length > 0) {
        entry.hiddenVisualSourceObjectIds = opts.hiddenVisualSourceObjectIds;
    }
    if (opts.excludedInlineSourceObjectIds && opts.excludedInlineSourceObjectIds.length > 0) {
        entry.excludedInlineSourceObjectIds = opts.excludedInlineSourceObjectIds;
    }
    if (opts.exportSourceObjectIds && opts.exportSourceObjectIds.length > 0) {
        entry.exportSourceObjectIds = opts.exportSourceObjectIds;
    }
    if (opts.absorbedFloatingShellFragmentSourceObjectIds
            && opts.absorbedFloatingShellFragmentSourceObjectIds.length > 0) {
        entry.absorbedFloatingShellFragmentSourceObjectIds =
                opts.absorbedFloatingShellFragmentSourceObjectIds;
    }
    if (opts.exportTargetObjectId !== undefined && opts.exportTargetObjectId !== null) {
        entry.exportTargetObjectId = opts.exportTargetObjectId;
    }
    if (opts.slotRole) entry.slotRole = opts.slotRole;
    if (opts.renderMode) entry.renderMode = opts.renderMode;
    if (opts.inlineAnchorSourceObjectId !== undefined && opts.inlineAnchorSourceObjectId !== null) {
        entry.inlineAnchorSourceObjectId = opts.inlineAnchorSourceObjectId;
    }
    if (opts.inlineSourceTreeClosed !== undefined && opts.inlineSourceTreeClosed !== null) {
        entry.inlineSourceTreeClosed = opts.inlineSourceTreeClosed === true;
    }
    if (opts.placement) entry.placement = opts.placement;
    if (opts.coordinateSpace) entry.coordinateSpace = opts.coordinateSpace;
    _annotateAtomicObjectOwnership(entry, renderTarget, opts, sourceIds, editableTfIds, textOwner);
    var visualOnlyIds = opts.visualOnlyChildIds;
    if (visualOnlyIds === undefined || visualOnlyIds === null) {
        visualOnlyIds = opts.exportSourceObjectIds && opts.exportSourceObjectIds.length > 0
                ? opts.exportSourceObjectIds
                : (renderTarget ? _collectVisualOnlyChildIds(renderTarget) : []);
    }
    visualOnlyIds = _filterIds(visualOnlyIds, opts.tfInlineVisualIds);
    if (visualOnlyIds && visualOnlyIds.length > 0) entry.visualOnlyChildIds = visualOnlyIds;
    if (opts.tfInlineVisualIds && opts.tfInlineVisualIds.length > 0) {
        entry.tfInlineVisualIds = opts.tfInlineVisualIds;
    }
    // 통-PNG에서 풀어준(굽지 않은) 대형 솔리드 배경 / 제목바 도형 → Java가 네이티브 fill로 렌더.
    if (opts.nativeFillChildIds && opts.nativeFillChildIds.length > 0) {
        entry.nativeFillChildIds = opts.nativeFillChildIds;
    }
    if (opts.titleBackgroundChildIds && opts.titleBackgroundChildIds.length > 0) {
        entry.titleBackgroundChildIds = opts.titleBackgroundChildIds;
    }
    if (textHidden) entry.textHiddenBeforeExport = true;
    return entry;
}

function inferPlacementRole(entry, textOwner, textHidden) {
    var type = entry && entry.type ? entry.type : "";
    if (type === "inline_object") return "inline_object";
    if (type === "page_background_plane") return "page_visual_plane";
    if (textOwner === "indesign_png") return "atomic_text_visual";
    if (textHidden) return "visual_only_png";
    return "page_visual_plane";
}

function getItemZOrder(item) {
    try { return item.absoluteZOrderIndex; } catch(e) { return 0; }
}

// =============================================================================
// SECTION 6: RESOLVED COLLECTOR
// resolved.json 수집 — 스토리 / TF / 마스터 인스턴스화 / 페이지 / 아이템
// =============================================================================

function collectEditableFrameIds(allItems) {
    var editableFrameIds = {};
    for (var i = 0; i < allItems.length; i++) {
        try {
            if (classifyTextFrameCached(allItems[i]) === "editable") {
                editableFrameIds[allItems[i].id] = true;
            }
        } catch (e) {}
    }
    return editableFrameIds;
}

/**
 * Page background pass.
 *
 * The old full-page background PNG path is intentionally inactive: page-level
 * rendering now happens through explicit object candidates. Keep this pass as
 * a planned no-op so `pass.page_backgrounds` cannot become a broad bucket for
 * arbitrary page items again.
 */

/**
 * 문서에 사용된 폰트의 글리프 메트릭을 측정한다.
 * 임시 TextFrame을 생성하여 한글/영문 샘플 텍스트의 폭, weight, x-height, ascent/descent를 측정.
 */
