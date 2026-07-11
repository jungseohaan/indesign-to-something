// Shared IO/JSON/progress utilities for extract_indd.jsx.
// This module must not decide ownership, placement, layer, or materialization.

if (typeof _JSON_MAX_DEPTH === "undefined") {
    var _JSON_MAX_DEPTH = 60;
}

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
    if (depth > _JSON_MAX_DEPTH) return "null";
    var t = typeof val;
    if (t === "boolean") return val ? "true" : "false";
    if (t === "number") {
        if (isFinite(val)) return String(val);
        return "null";
    }
    if (t === "string") return _jsonQuote(val);
    if (t === "function") return "null";
    if (typeof val.toSpecifier === "function") {
        try { return _jsonQuote(val.toSpecifier()); } catch (e) { return "null"; }
    }
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

function _marker(outputDir, tag) {
    try {
        var f = File(outputDir + "/.crash_marker");
        f.encoding = "UTF-8";
        f.open("w");
        f.write(tag);
        f.close();
    } catch (e) {}
    try {
        var now = (new Date()).getTime();
        if (!_phaseTimingState) {
            _phaseTimingState = { lastTime: now, lastTag: tag, startTime: now };
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
        f1.open("e");
        f1.seek(0, 2);
        f1.writeln(tag + "\t" + total + "\t" + delta);
        f1.close();
        _phaseTimingState.lastTime = now;
        _phaseTimingState.lastTag = tag;
    } catch (e) {}
}

function _mergeKeys(dst, src, keys) {
    if (!dst || !src) return;
    for (var _i = 0; _i < keys.length; _i++) {
        if (src[keys[_i]] !== undefined) dst[keys[_i]] = src[keys[_i]];
    }
}

function arrCopy(a) {
    return [a[0], a[1], a[2], a[3]];
}

function writeJson(path, obj) {
    var f = File(path);
    f.encoding = "UTF-8";
    f.open("w");
    f.write(JSON.stringify(obj));
    f.close();
}

function appendJsonLine(path, obj) {
    var f = File(path);
    f.encoding = "UTF-8";
    if (!f.exists) {
        f.open("w");
        f.close();
    }
    f.open("e");
    f.seek(0, 2);
    f.writeln(JSON.stringify(obj));
    f.close();
}

function appendDiag(outputDir, fileName, obj) {
    if (!outputDir || !fileName || !obj) return;
    try {
        if (!obj.tsIso) {
            try { obj.tsIso = (new Date()).toISOString(); } catch (eTs) {}
        }
        appendJsonLine(outputDir + "/" + fileName, obj);
    } catch (eAppendDiag) {}
}

function readJson(path) {
    var f = File(path);
    if (!f.exists) return null;
    f.encoding = "UTF-8";
    if (!f.open("r")) return null;
    var text = f.read();
    f.close();
    if (JSON && typeof JSON.parse === "function") {
        return JSON.parse(text);
    }
    return eval("(" + text + ")");
}

function writeResolvedJson(path, obj, outputDir) {
    var f = File(path);
    f.encoding = "UTF-8";
    f.open("w");
    var state = { file: f, buffer: "", threshold: 65536 };
    var keys = [
        "documentInfo",
        "paragraphStyles",
        "colors",
        "fonts",
        "stories",
        "textFrames",
        "pages",
        "pageItems",
        "fontMetrics",
        "renderedTextFrames",
        "renderedPdfFrames",
        "renderedGraphicFrames",
        "renderedImageFrames",
        "renderedFloatingItems",
        "editableTextFrameIds"
    ];
    var written = {};
    var first = true;
    try {
        _jsonStreamWrite(state, "{");
        for (var i = 0; i < keys.length; i++) {
            var key = keys[i];
            if (!_jsonHasOwn(obj, key)) continue;
            if (!first) _jsonStreamWrite(state, ",");
            first = false;
            _jsonStreamWrite(state, _jsonStreamQuote(key));
            _jsonStreamWrite(state, ":");
            _jsonStreamTopLevelValue(state, obj[key]);
            _jsonStreamFlush(state);
            written[key] = true;
            if (outputDir) _marker(outputDir, "11_writeJson_" + key);
        }
        for (var k in obj) {
            if (!_jsonHasOwn(obj, k) || written[k]) continue;
            if (!first) _jsonStreamWrite(state, ",");
            first = false;
            _jsonStreamWrite(state, _jsonStreamQuote(k));
            _jsonStreamWrite(state, ":");
            _jsonStreamTopLevelValue(state, obj[k]);
            _jsonStreamFlush(state);
            if (outputDir) _marker(outputDir, "11_writeJson_" + k);
        }
        _jsonStreamWrite(state, "}");
        _jsonStreamFlush(state);
    } finally {
        try { f.close(); } catch (eClose) {}
    }
    if (outputDir) _marker(outputDir, "11_writeJson_done");
}

function _jsonHasOwn(obj, key) {
    return obj && obj.hasOwnProperty && obj.hasOwnProperty(key);
}

function _jsonStreamWrite(state, s) {
    state.buffer += s;
    if (state.buffer.length >= state.threshold) _jsonStreamFlush(state);
}

function _jsonStreamFlush(state) {
    if (!state.buffer || state.buffer.length === 0) return;
    state.file.write(state.buffer);
    state.buffer = "";
}

function _jsonStreamQuote(str) {
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
        else if (code === 0xFFFD || code === 0xFFFC || code === 0xFEFF ||
                 code === 0x0016 || code === 0x0018 || code === 0x0003 ||
                 (code < 0x0020 && code !== 0x0009)) {
            result += '\\u' + ('0000' + code.toString(16)).slice(-4);
        }
        else result += c;
    }
    return result + '"';
}

function _jsonStreamTopLevelValue(state, val) {
    if (val instanceof Array) {
        _jsonStreamArrayByElement(state, val);
        return;
    }
    _jsonStreamWrite(state, JSON.stringify(val));
}

function _jsonStreamArrayByElement(state, arr) {
    _jsonStreamWrite(state, "[");
    for (var i = 0; i < arr.length; i++) {
        if (i > 0) _jsonStreamWrite(state, ",");
        _jsonStreamWrite(state, JSON.stringify(arr[i]));
    }
    _jsonStreamWrite(state, "]");
}

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
