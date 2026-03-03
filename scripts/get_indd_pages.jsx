/**
 * get_indd_pages.jsx — InDesign ExtendScript (경량)
 *
 * .indd 파일을 열어 페이지 목록만 수집하고 pages.json으로 저장.
 * 문서는 닫지 않으므로 후속 extract_indd.jsx 실행 시 재활용된다.
 *
 * arguments: [inddPath, outputDir]
 * 출력: outputDir/pages.json, outputDir/.done
 */

// --- JSON 폴리필 ---
if (typeof JSON === "undefined") {
    JSON = {};
}
if (typeof JSON.stringify !== "function") {
    JSON.stringify = function (val) {
        return _jsonSerialize("", { "": val });
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

    function _jsonSerialize(key, holder) {
        var val = holder[key];
        if (val === null || val === undefined) return "null";
        var t = typeof val;
        if (t === "boolean") return val ? "true" : "false";
        if (t === "number") return isFinite(val) ? String(val) : "null";
        if (t === "string") return _jsonQuote(val);
        if (val instanceof Array) {
            var a = [];
            for (var i = 0; i < val.length; i++) {
                a.push(_jsonSerialize(String(i), val));
            }
            return "[" + a.join(",") + "]";
        }
        if (t === "object") {
            var o = [];
            for (var k in val) {
                if (val.hasOwnProperty(k)) {
                    var v = _jsonSerialize(k, val);
                    if (v !== undefined) {
                        o.push(_jsonQuote(k) + ":" + v);
                    }
                }
            }
            return "{" + o.join(",") + "}";
        }
        return "null";
    }
}

// --- 메인 ---

function main(args) {
    var inddPath = args[0];
    var outputDir = args[1];

    var savedInteractionLevel = app.scriptPreferences.userInteractionLevel;
    var savedEnableRedraw = app.scriptPreferences.enableRedraw;
    var savedCheckLinks = app.linkingPreferences.checkLinksAtOpen;
    var savedFindMissing = app.linkingPreferences.findMissingLinksAtOpen;

    try {
        app.scriptPreferences.userInteractionLevel = UserInteractionLevels.NEVER_INTERACT;
        app.scriptPreferences.enableRedraw = false;
        app.linkingPreferences.checkLinksAtOpen = false;
        app.linkingPreferences.findMissingLinksAtOpen = false;

        // 문서 열기 (창 표시 안 함) — 이미 열려있으면 재사용
        var doc = app.open(File(inddPath), false);

        // 페이지 목록 수집
        var pages = [];
        for (var i = 0; i < doc.pages.length; i++) {
            var p = doc.pages[i];
            pages.push({
                name: p.name,
                index: i
            });
        }

        var result = {
            pageCount: doc.pages.length,
            pages: pages
        };

        // pages.json 쓰기
        var f = File(outputDir + "/pages.json");
        f.encoding = "UTF-8";
        f.open("w");
        f.write(JSON.stringify(result));
        f.close();

        // 문서는 닫지 않음 — 후속 extract_indd.jsx에서 재활용

        // 성공 시그널
        var df = File(outputDir + "/.done");
        df.encoding = "UTF-8";
        df.open("w");
        df.write(JSON.stringify({ status: "ok" }));
        df.close();

    } catch (e) {
        var ef = File(outputDir + "/.done");
        ef.encoding = "UTF-8";
        ef.open("w");
        ef.write(JSON.stringify({ status: "error", message: e.message }));
        ef.close();
    } finally {
        app.scriptPreferences.userInteractionLevel = savedInteractionLevel;
        app.scriptPreferences.enableRedraw = savedEnableRedraw;
        app.linkingPreferences.checkLinksAtOpen = savedCheckLinks;
        app.linkingPreferences.findMissingLinksAtOpen = savedFindMissing;
    }
}

main(arguments);
