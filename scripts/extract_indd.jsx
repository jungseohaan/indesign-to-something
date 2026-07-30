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
var EXTRACT_SCRIPT_VERSION = "65";
var _EXTRACT_MODULE_LOAD_DEBUG = "";
var _EXTRACT_MODULE_ALIAS_SOURCE = "";
var _EXACT_SHELL_SLOT_DUPLICATE_DIAGNOSTICS = null;

function _loadExtractInddModules(scriptArgs, configPath) {
    if (typeof File === "undefined") {
        _EXTRACT_MODULE_LOAD_DEBUG = "File API is unavailable";
        return;
    }

    var candidates = [];
    var seen = {};
    var debug = [];
    function setDebug() {
        _EXTRACT_MODULE_LOAD_DEBUG = debug.join("\n");
        try {
            if (typeof $ !== "undefined" && $.global) $.global._extractModuleLoadDebug = _EXTRACT_MODULE_LOAD_DEBUG;
        } catch (eSetDebug) {}
    }
    function pushBaseFolder(folder) {
        if (!folder) return;
        try {
            var base = folder.fsName || String(folder);
            if (!seen[base]) {
                seen[base] = true;
                candidates.push(base);
                debug.push("candidate=" + base);
            }
        } catch (ePushFolder) {}
    }
    function pushBaseFromScriptPath(path) {
        if (!path) return;
        try {
            var f = File(String(path));
            if (f && f.parent) pushBaseFolder(f.parent);
        } catch (ePushBase) {}
    }
    function evalModuleFile(moduleFile) {
        moduleFile.encoding = "UTF-8";
        if (!moduleFile.open("r")) throw new Error("cannot open module");
        var source = moduleFile.read();
        moduleFile.close();
        var lines = source.split(/\r?\n/);
        var exportSource = "\n;(function(){ var __g = (typeof $ !== 'undefined' && $.global) ? $.global : this;\n";
        var aliasSource = "";
        for (var li = 0; li < lines.length; li++) {
            var match = /^function\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*\(/.exec(lines[li]);
            if (!match) continue;
            exportSource += "try { __g['" + match[1] + "'] = " + match[1] + "; } catch(e) {}\n";
            aliasSource += "var " + match[1] + " = $.global['" + match[1] + "'];\n";
        }
        exportSource += "})();";
        eval(source + exportSource);
        _EXTRACT_MODULE_ALIAS_SOURCE += aliasSource;
        try {
            if (typeof $ !== "undefined" && $.global) {
                $.global._extractModuleAliasSource = _EXTRACT_MODULE_ALIAS_SOURCE;
            }
        } catch (eAliasSource) {}
    }

    try {
        if (typeof $ !== "undefined" && $.fileName) pushBaseFromScriptPath($.fileName);
    } catch (eFileName) {}
    if (scriptArgs) {
        for (var ai = 0; ai < 24; ai++) {
            try {
                var rawArg = scriptArgs[ai];
                if (rawArg === undefined || rawArg === null) continue;
                var arg = String(rawArg || "");
                if (arg.indexOf("extract_indd.jsx") >= 0) pushBaseFromScriptPath(arg);
            } catch (eArgAt) {
                debug.push("argError" + ai + "=" + eArgAt);
            }
        }
    }
    try {
        if (configPath) {
            var configString = String(configPath);
            var slashIndex = configString.lastIndexOf("/");
            if (slashIndex > 0) pushBaseFolder(configString.substring(0, slashIndex) + "/scripts");
        }
    } catch (eConfigBase) {
        debug.push("configBaseError=" + eConfigBase);
    }
    // NOTE:
    // InDesign desktop `do script ... language javascript` 실행에서는
    // `Folder.current`가 invalid object(30614)로 깨져 있는 경우가 있다.
    // 이 fallback은 없어도 `$.fileName` / configPath / scriptArgs 경로로
    // 모듈 해석이 가능하므로, 여기서는 참조하지 않는다.

    var moduleNames = [
        "io_utils.jsx",
        "input_prepare.jsx",
        "source_ids.jsx",
        "page_geometry.jsx",
        "source_index.jsx",
        "ownership_candidates.jsx",
        "extraction_base_candidates.jsx",
        "inline_visual_source_detector.jsx",
        "extraction_passes.jsx",
        "source_declared_shell_candidates.jsx",
        "candidate_normalization_source_context.jsx",
        "candidate_normalization.jsx",
        "extraction_plan_builder.jsx",
        "source_clusters.jsx",
        "planner_bundles.jsx",
        "object_plans.jsx",
        "execution_candidates.jsx",
        "source_slot_registry.jsx",
        "render_matcher.jsx",
        "render_prep.jsx",
        "render_inline_objects.jsx",
        "resolved_collectors.jsx",
        "text_collectors.jsx",
        "preview_export.jsx",
        "export_units.jsx",
        "extraction_result_writer.jsx",
        "extraction_validation.jsx",
        "extraction_orchestrator.jsx"
    ];
    for (var bi = 0; bi < candidates.length; bi++) {
        try {
            var baseDir = candidates[bi];
            for (var mi = 0; mi < moduleNames.length; mi++) {
                var moduleFile = File(baseDir + "/indd/" + moduleNames[mi]);
                if (!moduleFile.exists) {
                    debug.push("missing=" + moduleFile.fsName);
                    throw new Error("missing module " + moduleNames[mi]);
                }
                evalModuleFile(moduleFile);
            }
            setDebug();
            return;
        } catch (eEvalModule) {
            debug.push("error=" + eEvalModule);
        }
    }
    setDebug();
}

// =============================================================================
// SECTION 1: BOOTSTRAP
// JSON 폴리필 / 타이밍 마커 / 전역 설정 / Config 로더 / 색상 유틸
// =============================================================================

// --- JSON 폴리필 (ExtendScript는 ES3 기반, JSON 미지원) ---
if (typeof JSON === "undefined") {
    JSON = {};
}
if (typeof JSON.stringify !== "function") {
    // 최대 재귀 깊이 (InDesign DOM 객체 순환 참조 방지)
    var _JSON_MAX_DEPTH = 60;

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

}

// SPEC-030: phase 타이밍 누적 상태 (extract 한 번에 하나).
var _phaseTimingState = null;
var _ctfCache = null;          // itemId → "editable"|"renderable"|"background"|null
var _hiddenLayerCache = null;  // itemId → boolean (isOnHiddenLayer 결과 캐시)
var _hiddenVisibilityCache = null;  // itemId → boolean (self/ancestor visible=false)
var _extractionCandidateLookup = null;  // ExtractionPlan candidate index for directed export.

// --- 전역 설정 ---
var CONFIG = null;

// =============================================================================
// SECTION 2: ROOT ENTRY
// 루트 파일은 모듈 로딩과 실행 트리거만 담당한다.
// 실제 추출 오케스트레이션은 scripts/indd/extraction_orchestrator.jsx에 있다.
// =============================================================================

// --- 실행 ---
var _extractBootstrapConfigPath = null;
try {
    _extractBootstrapConfigPath = arguments && arguments[6] ? arguments[6] : null;
} catch (eBootstrapConfig) {}
_loadExtractInddModules(arguments, _extractBootstrapConfigPath);
try {
    if (_EXTRACT_MODULE_ALIAS_SOURCE) eval(_EXTRACT_MODULE_ALIAS_SOURCE);
} catch (eBootstrapAlias) {}
main(arguments);
