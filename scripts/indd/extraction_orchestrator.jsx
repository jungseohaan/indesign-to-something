/**
 * Extraction top-level orchestration.
 *
 * This module opens the document, runs the already-planned extraction passes,
 * writes resolved data, and exports the optional preview. It must not create
 * new ownership decisions or reinterpret ObjectPlan placement/layer metadata.
 */

// =============================================================================
// EXTRACTION ORCHESTRATION
// main() — 문서 오픈 → 플랜 실행 → resolved 수집 → PDF → 완료 시그널
// =============================================================================

// 렌더링 페이즈 2.12~3: 배경 PNG → 배지 → 이미지 → 데코 → 그래픽 → 벡터 → 마스터 → resolved.json 기록
function _posixShellQuote(value) {
    var s = String(value || "");
    return "'" + s.replace(/'/g, "'\"'\"'") + "'";
}

function _appleScriptStringLiteral(value) {
    var s = String(value || "");
    return "\"" + s.replace(/\\/g, "\\\\").replace(/"/g, "\\\"") + "\"";
}

function _converterJarPathForRepoRoot(repoRoot) {
    if (!repoRoot) return null;
    var names = [
        "idml-to-something-1.0.9-cli.jar",
        "hwpxlib-1.0.9-cli.jar",
        "hwpxlib-cli.jar",
        "idml-converter.jar"
    ];
    var dirs = [
        repoRoot,
        repoRoot + "/converter/target",
        repoRoot + "/target"
    ];
    for (var di = 0; di < dirs.length; di++) {
        for (var ni = 0; ni < names.length; ni++) {
            var p = dirs[di] + "/" + names[ni];
            if (File(p).exists) return p;
        }
    }
    return null;
}

function _repoRootFromFolder(folder) {
    if (!folder) return null;
    var current = Folder(folder.fsName || String(folder));
    for (var depth = 0; current && depth < 8; depth++) {
        if (_converterJarPathForRepoRoot(current.fsName)) return current.fsName;
        current = current.parent;
    }
    return null;
}

function _repoRootFromConfigPath(configPath, ctx) {
    try {
        if (configPath) {
            var f = File(String(configPath));
            if (f && f.exists && f.parent) {
                var rootFromConfig = _repoRootFromFolder(f.parent);
                if (rootFromConfig) return rootFromConfig;
            }
        }
    } catch (eConfigRoot) {}
    try {
        if (ctx && ctx.extractScriptPath) {
            var extractScript = File(String(ctx.extractScriptPath));
            if (extractScript && extractScript.exists && extractScript.parent) {
                var rootFromScript = _repoRootFromFolder(extractScript.parent);
                if (rootFromScript) return rootFromScript;
            }
        }
    } catch (eExtractScriptRoot) {}
    try {
        if (typeof $ !== "undefined" && $.fileName) {
            var scriptFile = File($.fileName);
            if (scriptFile && scriptFile.parent) {
                var rootFromDollar = _repoRootFromFolder(scriptFile.parent);
                if (rootFromDollar) return rootFromDollar;
            }
        }
    } catch (eScriptRoot) {}
    return null;
}

function _sourceSiblingIdmlFile(ctx) {
    var inddFile = File(ctx.inddPath);
    var name = inddFile.name || "output.indd";
    var dot = name.lastIndexOf(".");
    var base = dot >= 0 ? name.substring(0, dot) : name;
    return File(inddFile.parent.fsName + "/" + base + ".idml");
}

function _findReusableSiblingIdml(ctx) {
    var exact = _sourceSiblingIdmlFile(ctx);
    if (exact.exists) return { file: exact, match: "same_basename" };
    return { file: exact, match: "export_target" };
}

function _copyFileReplacing(src, dest) {
    if (!src || !src.exists) {
        throw new Error("IDML source file missing: " + (src ? src.fsName : "<null>"));
    }
    if (dest.exists) {
        try { dest.remove(); } catch (eRemove) {}
    }
    if (!src.copy(dest.fsName)) {
        throw new Error("IDML copy failed: " + src.fsName + " -> " + dest.fsName);
    }
}

function _exportIdmlReplacing(doc, dest) {
    if (!doc) {
        throw new Error("IDML fallback export failed: document is not available");
    }
    if (dest.exists) {
        try { dest.remove(); } catch (eRemove) {}
    }
    doc.exportFile(ExportFormat.INDESIGN_MARKUP, dest);
    if (!dest.exists) {
        throw new Error("IDML fallback export failed: output missing: " + dest.fsName);
    }
}

function _prepareExtractionIdml(doc, ctx) {
    var outputIdml = File(ctx.outputDir + "/output.idml");
    var sibling = _findReusableSiblingIdml(ctx);
    var siblingIdml = sibling.file;
    var mode = "sibling_reuse";
    var copyFallbackReason = null;

    if (!siblingIdml.exists) {
        mode = "sibling_export";
        writeProgress(ctx.outputDir, "idml_export_to_source_folder", 0, ctx.pageCount);
        doc.exportFile(ExportFormat.INDESIGN_MARKUP, siblingIdml);
    } else {
        writeProgress(ctx.outputDir, "idml_sibling_reuse", 0, ctx.pageCount);
    }

    try {
        _copyFileReplacing(siblingIdml, outputIdml);
    } catch (eCopy) {
        copyFallbackReason = String(eCopy && eCopy.message ? eCopy.message : eCopy);
        mode = mode + "_copy_fallback_export";
        writeProgress(ctx.outputDir, "idml_copy_fallback_export", 0, ctx.pageCount);
        _exportIdmlReplacing(doc, outputIdml);
    }
    writeJson(ctx.outputDir + "/idml-source.json", {
        mode: mode,
        match: sibling.match,
        sourceINDD: ctx.inddPath,
        sourceIDML: siblingIdml.fsName,
        outputIDML: outputIdml.fsName,
        copyFallbackReason: copyFallbackReason
    });
    return outputIdml;
}

function _loadIdmlZOrderMap(ctx) {
    if (!ctx || ctx.chunkMode) return null;
    var idmlPath = ctx.outputDir + "/output.idml";
    var mapPath = ctx.outputDir + "/idml-zorder-map.json";
    var logPath = ctx.outputDir + "/_idml_zorder_map.log";
    var summary = {
        status: "skipped",
        reason: null,
        mapPath: mapPath,
        count: 0
    };
    try {
        var existingMapFile = File(mapPath);
        if (existingMapFile.exists) {
            var existingDoc = readJson(mapPath);
            var existingMap = existingDoc && existingDoc.zOrderBySourceObjectId
                    ? existingDoc.zOrderBySourceObjectId
                    : null;
            if (existingMap) {
                ctx.idmlZOrderBySourceObjectId = existingMap;
                summary.status = "ok";
                summary.reason = "reused_existing_map";
                summary.count = existingDoc.count || 0;
                writeJson(ctx.outputDir + "/idml-zorder-map-summary.json", summary);
                return existingMap;
            }
        }
        var repoRoot = _repoRootFromConfigPath(ctx.configPath, ctx);
        if (!repoRoot) {
            summary.reason = "missing_repo_root";
            writeJson(ctx.outputDir + "/idml-zorder-map-summary.json", summary);
            return null;
        }
        var jarPath = _converterJarPathForRepoRoot(repoRoot);
        if (!jarPath || !File(jarPath).exists) {
            summary.reason = "converter_jar_missing";
            summary.repoRoot = repoRoot;
            summary.jarPath = jarPath || (repoRoot + "/converter/target/idml-to-something-1.0.9-cli.jar");
            writeJson(ctx.outputDir + "/idml-zorder-map-summary.json", summary);
            return null;
        }
        var javaProbe = "if [ -x /opt/homebrew/opt/openjdk/bin/java ]; then printf %s /opt/homebrew/opt/openjdk/bin/java; "
                + "elif command -v java >/dev/null 2>&1; then command -v java; else printf %s java; fi";
        var command = "JAVA_BIN=$(" + javaProbe + "); "
                + "\"$JAVA_BIN\" -jar " + _posixShellQuote(jarPath)
                + " --idml-zorder-map " + _posixShellQuote(idmlPath)
                + " " + _posixShellQuote(mapPath)
                + " > " + _posixShellQuote(logPath) + " 2>&1";
        app.doScript("do shell script " + _appleScriptStringLiteral(command),
                ScriptLanguage.APPLESCRIPT_LANGUAGE);
        var doc = readJson(mapPath);
        var map = doc && doc.zOrderBySourceObjectId ? doc.zOrderBySourceObjectId : null;
        if (!map) {
            summary.status = "failed";
            summary.reason = "map_file_missing_or_invalid";
            writeJson(ctx.outputDir + "/idml-zorder-map-summary.json", summary);
            return null;
        }
        ctx.idmlZOrderBySourceObjectId = map;
        summary.status = "ok";
        summary.reason = null;
        summary.count = doc.count || 0;
        writeJson(ctx.outputDir + "/idml-zorder-map-summary.json", summary);
        return map;
    } catch (eZOrderMap) {
        summary.status = "failed";
        summary.reason = String(eZOrderMap);
        try { writeJson(ctx.outputDir + "/idml-zorder-map-summary.json", summary); } catch (eWriteSummary) {}
    }
    return null;
}

function _spreadChunkSafeName(value) {
    return String(value === null || value === undefined ? "unknown" : value)
            .replace(/[^A-Za-z0-9_.-]/g, "_");
}

function _ensureFolder(path) {
    var folder = Folder(path);
    if (!folder.exists) folder.create();
    return folder;
}

function _selectedSpreadChunks(doc, ctx) {
    var chunks = [];
    var bySpread = {};
    var order = [];
    for (var pi = 0; pi < doc.pages.length; pi++) {
        var page = doc.pages[pi];
        var pageNumber = page.documentOffset + 1;
        if (pageNumber < ctx.startPage || pageNumber > ctx.endPage) continue;
        var spreadId = null;
        try { spreadId = _pageSpreadIdForIndex(doc, pi); } catch (eSpreadId) {}
        var spreadKey = spreadId !== null && spreadId !== undefined
                ? String(spreadId)
                : "page_" + String(pageNumber);
        if (!bySpread[spreadKey]) {
            bySpread[spreadKey] = {
                spreadId: spreadId,
                spreadKey: spreadKey,
                pageIndexes: [],
                startPage: pageNumber,
                endPage: pageNumber
            };
            order.push(spreadKey);
        }
        bySpread[spreadKey].pageIndexes.push(pi);
        bySpread[spreadKey].startPage = Math.min(bySpread[spreadKey].startPage, pageNumber);
        bySpread[spreadKey].endPage = Math.max(bySpread[spreadKey].endPage, pageNumber);
    }
    for (var oi = 0; oi < order.length; oi++) {
        var chunk = bySpread[order[oi]];
        chunk.chunkIndex = chunks.length + 1;
        chunk.pageCount = chunk.pageIndexes.length;
        chunks.push(chunk);
    }
    return chunks;
}

function _cloneContextForSpreadChunk(ctx, chunk, chunkDir) {
    var out = {};
    for (var key in ctx) {
        if (ctx.hasOwnProperty && !ctx.hasOwnProperty(key)) continue;
        out[key] = ctx[key];
    }
    out.rootOutputDir = ctx.outputDir;
    out.outputDir = chunkDir;
    out.startPage = chunk.startPage;
    out.endPage = chunk.endPage;
    out.requestedStartPage = chunk.startPage;
    out.requestedEndPage = chunk.endPage;
    out.rangePageCount = chunk.pageCount;
    out.rangeInputMode = "spread_chunk";
    out.resolvedRangeMode = "spread_chunk";
    out.chunkMode = false;
    out.spreadChunkMode = true;
    out.spreadChunkIndex = chunk.chunkIndex;
    out.spreadChunkKey = chunk.spreadKey;
    out.spreadChunkPageIndexes = chunk.pageIndexes.slice(0);
    out.validationWarnings = [];
    out.skippedValidationWarnings = [];
    return out;
}

function _copyAndRewriteChunkFilePath(row, field, rootOutputDir, chunkDir, chunkLabel) {
    if (!row || !row[field]) return;
    if (row.globalRenderedFrame === true) return;
    var rel = String(row[field]);
    if (rel.indexOf("rendered_frames/") !== 0) return;
    var slash = rel.lastIndexOf("/");
    var fileName = slash >= 0 ? rel.substring(slash + 1) : rel;
    var prefixedName = chunkLabel + "__" + fileName;
    var src = File(chunkDir + "/" + rel);
    var destDir = _ensureFolder(rootOutputDir + "/rendered_frames");
    var dest = File(destDir.fsName + "/" + prefixedName);
    try {
        if (src.exists && !dest.exists) src.copy(dest.fsName);
    } catch (eCopyRendered) {}
    row[field] = "rendered_frames/" + prefixedName;
}

function _rewriteChunkRenderedPathsInArray(rows, rootOutputDir, chunkDir, chunkLabel) {
    for (var i = 0; rows && i < rows.length; i++) {
        _copyAndRewriteChunkFilePath(rows[i], "file", rootOutputDir, chunkDir, chunkLabel);
        _copyAndRewriteChunkFilePath(rows[i], "relPath", rootOutputDir, chunkDir, chunkLabel);
        if (rows[i] && rows[i].files) {
            for (var fi = 0; fi < rows[i].files.length; fi++) {
                var wrapper = { file: rows[i].files[fi] };
                _copyAndRewriteChunkFilePath(wrapper, "file", rootOutputDir, chunkDir, chunkLabel);
                rows[i].files[fi] = wrapper.file;
            }
        }
    }
}

function _mergeArrayUnique(target, rows, keyPrefix) {
    if (!rows) return;
    if (!target._seen) target._seen = {};
    for (var i = 0; i < rows.length; i++) {
        var row = rows[i];
        var key = null;
        try {
            if (_mergeKeyPrefersRenderUnit(keyPrefix, row)) key = _renderedRowMergeKey(row);
            else if (keyPrefix === "result" && row && row.exportId) key = "export:" + row.exportId;
            else if (row && row.id !== null && row.id !== undefined) key = "id:" + row.id;
            else if (row && row.self) key = "self:" + row.self;
            else if (row && row.name) key = "name:" + row.name;
            else if (row && row.pageIndex !== null && row.pageIndex !== undefined) key = "page:" + row.pageIndex;
            else if (row && row.exportId) key = "export:" + row.exportId;
        } catch (eKey) {}
        if (!key) key = keyPrefix + ":" + i + ":" + target.length;
        if (target._seen[key]) continue;
        target._seen[key] = true;
        target.push(row);
    }
}

function _mergeKeyPrefersRenderUnit(keyPrefix, row) {
    if (!row) return false;
    if (keyPrefix === "result") return !!row.exportId || !!row.renderUnitId || !!row.file;
    if (keyPrefix === "renderedFloatingItems"
            || keyPrefix === "renderedImageFrames"
            || keyPrefix === "renderedGraphicFrames"
            || keyPrefix === "renderedPdfFrames"
            || keyPrefix === "renderedTextFrames") {
        return !!row.renderUnitId || !!row.file || row.pageIndex !== null && row.pageIndex !== undefined;
    }
    return false;
}

function _renderedRowMergeKey(row) {
    if (!row) return null;
    if (row.exportId) return "export:" + row.exportId;
    if (row.renderUnitId) return "renderUnit:" + row.renderUnitId;
    var page = row.pageIndex !== null && row.pageIndex !== undefined ? String(row.pageIndex) : "none";
    var id = row.id !== null && row.id !== undefined ? String(row.id) : "none";
    var file = row.file ? String(row.file) : "";
    return "rendered:" + page + ":" + id + ":" + file;
}

function _mergeObjectPlansUnique(target, rows, rootOutputDir, chunkDir, chunkLabel) {
    if (!rows) return;
    if (!target._seen) target._seen = {};
    for (var i = 0; i < rows.length; i++) {
        var row = rows[i];
        if (!row) continue;
        _copyAndRewriteChunkFilePath(row, "file", rootOutputDir, chunkDir, chunkLabel);
        var key = row.objectPlanId
                ? "objectPlanId:" + String(row.objectPlanId)
                : (row.candidateId
                        ? "candidateId:" + String(row.candidateId)
                        : "objectPlan:" + i + ":" + target.length);
        if (target._seen[key]) continue;
        target._seen[key] = true;
        target.push(row);
    }
}

function _mergedObjectPlanSummary(objectPlans) {
    var summary = {
        planCount: 0,
        executablePlanCount: 0,
        textActionCounts: {},
        visualActionCounts: {},
        materializationCounts: {},
        placementCounts: {},
        coordinateSpaceCounts: {},
        visualLayerCounts: {},
        plansWithVisualSources: 0,
        plansWithStyleSources: 0,
        plansWithOwnedTextFrames: 0,
        importReadyPlanCount: 0,
        contractStatusCounts: {}
    };
    for (var i = 0; objectPlans && i < objectPlans.length; i++) {
        var plan = objectPlans[i];
        if (!plan) continue;
        summary.planCount++;
        if (plan.executable === true) summary.executablePlanCount++;
        _incrementObjectPlanSummary(summary.textActionCounts, plan.textAction);
        _incrementObjectPlanSummary(summary.visualActionCounts, plan.visualAction);
        _incrementObjectPlanSummary(summary.materializationCounts, plan.materialization);
        _incrementObjectPlanSummary(summary.placementCounts, plan.placement);
        _incrementObjectPlanSummary(summary.coordinateSpaceCounts, plan.coordinateSpace);
        _incrementObjectPlanSummary(summary.visualLayerCounts, plan.visualLayer);
        if (plan.visualSourceObjectIds && plan.visualSourceObjectIds.length > 0) summary.plansWithVisualSources++;
        if (plan.styleSourceObjectIds && plan.styleSourceObjectIds.length > 0) summary.plansWithStyleSources++;
        if (plan.ownedTextFrameIds && plan.ownedTextFrameIds.length > 0) summary.plansWithOwnedTextFrames++;
        if (plan.contractStatus === "READY_FOR_STAGE1_IMPORT") summary.importReadyPlanCount++;
        _incrementObjectPlanSummary(
                summary.contractStatusCounts,
                plan.contractStatus || "UNKNOWN");
    }
    return summary;
}

function _stripMergeSeenArrays(obj) {
    for (var key in obj) {
        if (!obj.hasOwnProperty(key)) continue;
        if (obj[key] && obj[key]._seen) {
            try { delete obj[key]._seen; } catch (eDeleteSeen) { obj[key]._seen = undefined; }
        }
    }
}

function _mergeObjectMap(target, source) {
    for (var key in source) {
        if (!source.hasOwnProperty(key)) continue;
        target[key] = source[key];
    }
}

function _mergeSpreadChunkOutputs(ctx, chunks) {
    var resolved = {};
    var resolvedArrayKeys = [
        "paragraphStyles", "colors", "fonts", "stories", "textFrames", "pages",
        "pageItems", "fontMetrics", "renderedTextFrames", "renderedPdfFrames",
        "renderedGraphicFrames", "renderedImageFrames", "renderedFloatingItems",
        "editableTextFrameIds", "textlessShellDiagnostics"
    ];
    for (var rk = 0; rk < resolvedArrayKeys.length; rk++) resolved[resolvedArrayKeys[rk]] = [];
    var extractionResults = {
        schemaVersion: 1,
        policy: "POLICY-extraction-planning",
        scriptVersion: EXTRACT_SCRIPT_VERSION,
        mode: "spread-chunk-results",
        sourceDocument: ctx.inddPath,
        outputDir: ctx.outputDir,
        pageRange: {
            requestedStartPage: ctx.requestedStartPage,
            requestedEndPage: ctx.requestedEndPage,
            startPage: ctx.startPage,
            endPage: ctx.endPage,
            pageCount: ctx.pageCount,
            rangePageCount: ctx.rangePageCount,
            rangeInputMode: ctx.rangeInputMode,
            resolvedRangeMode: "spread_chunks"
        },
        counts: {},
        results: [],
        diagnostics: [],
        validation: { status: "OK", issueCount: 0, issues: [] }
    };
    var pageHashes = {};
    var pageItemMap = {};
    var objectPlans = [];
    var chunkSummaries = [];
    var atomicTextlessVectorCollectionSummary = {
        enabled: false,
        groupCount: 0,
        coveredSourceObjectCount: 0,
        targetRootCount: 0,
        hiddenSourceObjectCount: 0,
        skippedPageItemCount: 0,
        compactRootCount: 0
    };
    for (var ci = 0; ci < chunks.length; ci++) {
        var chunk = chunks[ci];
        var chunkLabel = "spread_" + _spreadChunkSafeName(chunk.spreadKey);
        var chunkDir = ctx.outputDir + "/chunks/" + chunkLabel;
        var chunkResolved = readJson(chunkDir + "/resolved.json") || {};
        var chunkResults = readJson(chunkDir + "/extraction-results.json") || {};
        var chunkHashes = readJson(chunkDir + "/page_hashes.json") || {};
        var chunkItemMap = readJson(chunkDir + "/page_item_map.json") || {};
        var chunkObjectPlans = readJson(chunkDir + "/object-plans.json") || {};

        if (!resolved.documentInfo && chunkResolved.documentInfo) resolved.documentInfo = chunkResolved.documentInfo;
        for (var ar = 0; ar < resolvedArrayKeys.length; ar++) {
            var arrayKey = resolvedArrayKeys[ar];
            var rows = chunkResolved[arrayKey] || [];
            _rewriteChunkRenderedPathsInArray(rows, ctx.outputDir, chunkDir, chunkLabel);
            _mergeArrayUnique(resolved[arrayKey], rows, arrayKey);
        }
        var chunkAtomicSummary = chunkResolved.atomicTextlessVectorCollectionSummary || null;
        if (chunkAtomicSummary && chunkAtomicSummary.enabled) {
            atomicTextlessVectorCollectionSummary.enabled = true;
            atomicTextlessVectorCollectionSummary.groupCount += Number(chunkAtomicSummary.groupCount || 0);
            atomicTextlessVectorCollectionSummary.coveredSourceObjectCount += Number(chunkAtomicSummary.coveredSourceObjectCount || 0);
            atomicTextlessVectorCollectionSummary.targetRootCount += Number(chunkAtomicSummary.targetRootCount || 0);
            atomicTextlessVectorCollectionSummary.hiddenSourceObjectCount += Number(chunkAtomicSummary.hiddenSourceObjectCount || 0);
            atomicTextlessVectorCollectionSummary.skippedPageItemCount += Number(chunkAtomicSummary.skippedPageItemCount || 0);
            atomicTextlessVectorCollectionSummary.compactRootCount += Number(chunkAtomicSummary.compactRootCount || 0);
        }
        _rewriteChunkRenderedPathsInArray(chunkResults.results || [], ctx.outputDir, chunkDir, chunkLabel);
        _mergeArrayUnique(extractionResults.results, chunkResults.results || [], "result");
        _mergeArrayUnique(extractionResults.diagnostics, chunkResults.diagnostics || [], "diagnostic");
        _mergeObjectPlansUnique(
                objectPlans,
                chunkObjectPlans.objectPlans || [],
                ctx.outputDir,
                chunkDir,
                chunkLabel);
        _mergeObjectMap(pageHashes, chunkHashes);
        _mergeObjectMap(pageItemMap, chunkItemMap);
        chunkSummaries.push({
            chunkIndex: chunk.chunkIndex,
            spreadKey: chunk.spreadKey,
            startPage: chunk.startPage,
            endPage: chunk.endPage,
            pageCount: chunk.pageCount,
            resultCount: chunkResults.results ? chunkResults.results.length : 0
        });
    }
    _stripMergeSeenArrays(resolved);
    _stripMergeSeenArrays(extractionResults);
    if (objectPlans._seen) {
        try { delete objectPlans._seen; } catch (eObjectPlanSeen) { objectPlans._seen = undefined; }
    }
    if (atomicTextlessVectorCollectionSummary.enabled) {
        resolved.atomicTextlessVectorCollectionSummary = atomicTextlessVectorCollectionSummary;
    }
    extractionResults.counts.renderedFloatingItems = resolved.renderedFloatingItems.length;
    extractionResults.counts.renderedImageFrames = resolved.renderedImageFrames.length;
    extractionResults.counts.renderedGraphicFrames = resolved.renderedGraphicFrames.length;
    extractionResults.counts.renderedVectorFrames = 0;
    extractionResults.counts.renderedMasterGraphics = 0;
    extractionResults.counts.textFrameShellFrames = 0;
    extractionResults.exportUnits = _buildExportUnitsFromExtractionResults(extractionResults);
    writeResolvedJson(ctx.outputDir + "/resolved.json", resolved, ctx.outputDir);
    writeJson(ctx.outputDir + "/extraction-results.json", extractionResults);
    writeJson(ctx.outputDir + "/export-units.json", extractionResults.exportUnits);
    var mergedObjectPlanSummary = _mergedObjectPlanSummary(objectPlans);
    writeJson(ctx.outputDir + "/object-plans.json", {
        schemaVersion: 1,
        policy: "POLICY-source-ownership",
        mode: "object-plan-diagnostics-slim",
        summary: mergedObjectPlanSummary,
        validation: {
            issueCount: 0,
            importReadyPlanCount: mergedObjectPlanSummary.importReadyPlanCount,
            contractStatusCounts: mergedObjectPlanSummary.contractStatusCounts,
            issueCodeCounts: {},
            issuesOmitted: true,
            issuePreview: []
        },
        fullDiagnosticsSkipped: true,
        objectPlans: objectPlans
    });
    writeJson(ctx.outputDir + "/page_hashes.json", pageHashes);
    writeJson(ctx.outputDir + "/page_item_map.json", pageItemMap);
    writeJson(ctx.outputDir + "/spread-chunks.json", {
        schemaVersion: 1,
        mode: "spread_chunks",
        chunkCount: chunks.length,
        chunks: chunkSummaries
    });
    writeJson(ctx.outputDir + "/extraction-plan.json", {
        schemaVersion: 1,
        policy: "POLICY-extraction-planning",
        scriptVersion: EXTRACT_SCRIPT_VERSION,
        mode: "spread-chunks-merged-plan",
        sourceDocument: ctx.inddPath,
        outputDir: ctx.outputDir,
        pageRange: extractionResults.pageRange,
        spreadChunks: chunkSummaries
    });
    writeJson(ctx.outputDir + "/_extract_stats.json", {
        elapsed_ms: _phaseTimingState ? (new Date()).getTime() - _phaseTimingState.startTime : null,
        total_page_count: ctx.pageCount,
        requested_start_page: ctx.requestedStartPage,
        requested_end_page: ctx.requestedEndPage,
        start_page: ctx.startPage,
        end_page: ctx.endPage,
        page_count: ctx.rangePageCount,
        spread_chunk_count: chunks.length
    });
}

function _prepareSpreadChunkSourceInfoCache(doc, ctx) {
    if (!ctx || ctx.spreadChunkSourceInfoById) return;
    var startedAt = (new Date()).getTime();
    var previousRangeTargetPageIndexesBySourceId = ctx.rangeTargetPageIndexesBySourceId;
    var allItems = null;
    var summary = {
        schemaVersion: 1,
        mode: "spread_chunks_source_info_cache",
        status: "skipped",
        itemCount: 0,
        sourceObjectCount: 0,
        elapsedMs: null,
        sourceIndexStats: null
    };
    try {
        _marker(ctx.outputDir, "03a_spreadChunkSourceInfoCache_start");
        allItems = collectRangePageItems(doc, ctx.startPage, ctx.endPage);
        ctx.rangeTargetPageIndexesBySourceId =
                collectRangePageItems.lastTargetPageIndexesByItemId || {};
        var sourceIndex = _buildSourceIndexFromAllItems(doc, ctx, allItems);
        ctx.spreadChunkSourceInfoById = sourceIndex && sourceIndex.sourceInfoById
                ? sourceIndex.sourceInfoById
                : {};
        summary.status = "ok";
        summary.itemCount = allItems ? allItems.length : 0;
        summary.sourceObjectCount = sourceIndex && sourceIndex.sourceItems
                ? sourceIndex.sourceItems.length
                : 0;
        summary.sourceIndexStats = sourceIndex ? (sourceIndex.stats || null) : null;
        _marker(ctx.outputDir, "03a_spreadChunkSourceInfoCache_done");
    } catch (eSpreadChunkSourceCache) {
        ctx.spreadChunkSourceInfoById = null;
        summary.status = "fallback";
        summary.error = String(eSpreadChunkSourceCache);
        try { _marker(ctx.outputDir, "03a_spreadChunkSourceInfoCache_fallback"); } catch (eMarker) {}
    } finally {
        summary.elapsedMs = (new Date()).getTime() - startedAt;
        try { writeJson(ctx.outputDir + "/spread-chunk-source-info-cache.json", summary); } catch (eWriteSpreadCache) {}
        ctx.rangeTargetPageIndexesBySourceId = previousRangeTargetPageIndexesBySourceId;
        allItems = null;
        try { $.gc(); } catch (eGc) {}
    }
}

function _indexSingleTextlessPagePlaneFrames(result) {
    var byPageIndex = {};
    var frames = result && result.frames ? result.frames : [];
    for (var i = 0; i < frames.length; i++) {
        var frame = frames[i];
        if (!frame || frame.pageIndex === null || frame.pageIndex === undefined) continue;
        byPageIndex[String(frame.pageIndex)] = {
            file: frame.file || null,
            bounds: frame.bounds || null,
            fileBytes: frame.exportSanity ? frame.exportSanity.fileBytes || 0 : 0
        };
    }
    return byPageIndex;
}

// SPEC-049: 페이지 평면 숨김 정책의 결정론적 서명.
// 페이지 평면은 세 종류의 숨김 정책에 의존한다:
//   1. table-style source hide (pagePlaneHiddenTableStyleSourceObjectIdsByPage)
//   2. source-bundle text-range shell hide (…ShellHideCandidateCount)
//   3. mixed-bundle placed-visual hide (…MixedBundlePlacedVisualHideCandidateCount)
//   4. complete-PNG text owner source hide
// 셋 다 sourceItems / extractionPlan 에서 순수 도출되어 같은 INDD → 같은 값이다.
// 이 서명을 캐시 파일명에 넣어 "같은 페이지 + 같은 숨김 정책 = 같은 캐시 파일"을
// 보장한다. 정책이 (추출기 변경 등으로) 달라지면 서명이 바뀌어 자동 miss →
// 오염된 캐시 재사용을 막는다. 숨김이 하나도 없으면 null(레거시 파일명 유지).
function _pagePlaneHideSignature(ctx) {
    if (!ctx) return null;
    var byPage = ctx.pagePlaneHiddenTableStyleSourceObjectIdsByPage;
    var completeByPage = ctx.pagePlaneCompletePngTextOwnerSourceObjectIdsByPage;
    var shellCount = ctx.pagePlaneSourceBundleTextRangeShellHideCandidateCount || 0;
    var mixedCount = ctx.pagePlaneMixedBundlePlacedVisualHideCandidateCount || 0;
    var tableKeyCount = (byPage ? _objectPlanMapKeyCount(byPage) : 0);
    var completeKeyCount = (completeByPage ? _objectPlanMapKeyCount(completeByPage) : 0);
    if (tableKeyCount === 0 && shellCount === 0 && mixedCount === 0
            && completeKeyCount === 0) return null;
    // (1) table-style: 페이지 인덱스 오름차순, 각 페이지의 object id 오름차순으로
    // 정규화해 순회 순서에 무관한 안정적 문자열을 만든다.
    var parts = [];
    if (byPage && tableKeyCount > 0) {
        var pageKeys = [];
        for (var pk in byPage) {
            if (byPage.hasOwnProperty(pk)) pageKeys.push(pk);
        }
        pageKeys.sort(function (a, b) { return Number(a) - Number(b); });
        for (var i = 0; i < pageKeys.length; i++) {
            var key = pageKeys[i];
            var ids = byPage[key];
            var idList = [];
            if (ids) {
                if (ids.length !== undefined && typeof ids !== "string") {
                    for (var j = 0; j < ids.length; j++) idList.push(String(ids[j]));
                } else {
                    for (var idk in ids) {
                        if (ids.hasOwnProperty(idk)) idList.push(String(idk));
                    }
                }
            }
            idList.sort();
            parts.push(key + ":" + idList.join(","));
        }
    }
    if (completeByPage && completeKeyCount > 0) {
        var completePageKeys = [];
        for (var cpk in completeByPage) {
            if (completeByPage.hasOwnProperty(cpk)) completePageKeys.push(cpk);
        }
        completePageKeys.sort(function (a, b) { return Number(a) - Number(b); });
        for (var ci = 0; ci < completePageKeys.length; ci++) {
            var completeKey = completePageKeys[ci];
            var completeIds = completeByPage[completeKey];
            var completeIdList = [];
            if (completeIds) {
                if (completeIds.length !== undefined && typeof completeIds !== "string") {
                    for (var cj = 0; cj < completeIds.length; cj++) {
                        completeIdList.push(String(completeIds[cj]));
                    }
                } else {
                    for (var completeIdKey in completeIds) {
                        if (completeIds.hasOwnProperty(completeIdKey)) {
                            completeIdList.push(String(completeIdKey));
                        }
                    }
                }
            }
            completeIdList.sort();
            parts.push("C" + completeKey + ":" + completeIdList.join(","));
        }
    }
    // (2)(3) shell / mixed 는 결정론적 후보 카운트로 서명한다 (id 목록은 ctx 에
    // 저장되지 않지만, 같은 INDD 면 카운트도 동일하므로 서명 목적에 충분하다).
    var raw = "T=" + parts.join("|") + ";S=" + shellCount + ";M=" + mixedCount;
    // djb2 해시 → 8자리 hex.
    var h = 5381;
    for (var c = 0; c < raw.length; c++) {
        h = ((h * 33) ^ raw.charCodeAt(c)) | 0;
    }
    return "hs" + (h >>> 0).toString(16);
}

function _pagePlaneCacheFileName(pageIndex, pageHash, hideSig) {
    // The cache directory is already keyed by immutable INDD path/size/mtime,
    // extractor version, graphics mode, and perf mode. The legacy page_hashes
    // include session-local DOM ids for some generated instances, so using them
    // in the file name makes deterministic source pages miss the cache.
    //
    // SPEC-049: when table-style source hiding is active, the page plane depends
    // on which sources are hidden. hideSig (deterministic per INDD) is appended
    // so cached planes only match when produced under the same hide set.
    var base = "page_background_plane_p" + String(pageIndex + 1);
    if (hideSig) base += "_" + hideSig;
    return base + ".png";
}

function _copyFileReplacingGeneric(src, dest, label) {
    if (!src || !src.exists) {
        throw new Error((label || "file") + " source file missing: " + (src ? src.fsName : "<null>"));
    }
    if (dest.exists) {
        try { dest.remove(); } catch (eRemove) {}
    }
    if (!src.copy(dest.fsName)) {
        throw new Error((label || "file") + " copy failed: " + src.fsName + " -> " + dest.fsName);
    }
}

function _pageLocalBoundsForCache(page) {
    try {
        var pb = page.bounds;
        return [0, 0, Number(pb[2]) - Number(pb[0]), Number(pb[3]) - Number(pb[1])];
    } catch (eBounds) {}
    return null;
}

function _restoreCachedSingleTextlessPagePlanes(doc, ctx) {
    var startedAt = (new Date()).getTime();
    var summary = {
        schemaVersion: 1,
        mode: "single-textless-page-plane-cache-restore",
        status: "skipped",
        reason: null,
        cacheDir: ctx ? ctx.pagePlaneCacheDir || null : null,
        pageCount: ctx ? ctx.rangePageCount || 0 : 0,
        hitCount: 0,
        missCount: 0,
        allHit: false,
        elapsedMs: null,
        entries: []
    };
    try {
        if (!ctx || !ctx.pagePlaneCacheDir || ctx.graphicsMode !== "single-textless-plane") {
            summary.reason = "cache_not_configured";
            return summary;
        }
        // SPEC-049: 세 종류의 숨김 정책(table-style / text-range-shell /
        // mixed-bundle-placed-visual)은 더 이상 캐시를 무효화하지 않는다. 셋 다
        // 결정론적이므로 서명(hideSig)을 캐시 파일명에 반영해 안전하게 재사용한다.
        var hideSig = _pagePlaneHideSignature(ctx);
        summary.hideSignature = hideSig || null;
        var cacheDir = Folder(ctx.pagePlaneCacheDir);
        if (!cacheDir.exists) {
            summary.reason = "cache_dir_missing";
            return summary;
        }
        var pageHashes = readJson(ctx.outputDir + "/page_hashes.json") || {};
        var renderDir = _ensureFolder(ctx.outputDir + "/rendered_frames");
        var restored = {};
        var allHit = true;
        for (var pageNumber = ctx.startPage; pageNumber <= ctx.endPage; pageNumber++) {
            var pageIndex = pageNumber - 1;
            var hash = pageHashes[String(pageIndex + 1)];
            var page = null;
            try { page = doc.pages[pageIndex]; } catch (ePage) {}
            var entry = {
                pageIndex: pageIndex,
                pageHash: hash || null,
                hit: false,
                cacheFile: null,
                file: null,
                bounds: page ? _pageLocalBoundsForCache(page) : null,
                fileBytes: 0
            };
            if (!hash) {
                summary.missCount++;
                allHit = false;
                entry.reason = "missing_page_hash";
                summary.entries.push(entry);
                continue;
            }
            var cacheFile = File(cacheDir.fsName + "/" + _pagePlaneCacheFileName(pageIndex, hash, hideSig));
            entry.cacheFile = cacheFile.fsName;
            if (!cacheFile.exists) {
                summary.missCount++;
                allHit = false;
                entry.reason = "cache_file_missing";
                summary.entries.push(entry);
                continue;
            }
            var outName = "page_background_plane_p" + String(pageIndex + 1) + ".png";
            var outFile = File(renderDir.fsName + "/" + outName);
            _copyFileReplacingGeneric(cacheFile, outFile, "page plane cache");
            entry.hit = true;
            entry.file = "rendered_frames/" + outName;
            try { entry.fileBytes = outFile.length || 0; } catch (eLen) {}
            summary.hitCount++;
            summary.entries.push(entry);
            restored[String(pageIndex)] = {
                file: entry.file,
                bounds: entry.bounds,
                fileBytes: entry.fileBytes
            };
        }
        summary.allHit = allHit && summary.hitCount === summary.pageCount;
        if (!summary.allHit) {
            ctx.globalSingleTextlessPagePlanesByPageIndex = null;
            summary.status = "miss";
            summary.reason = "not_all_pages_cached";
            return summary;
        }
        ctx.globalSingleTextlessPagePlanesByPageIndex = restored;
        summary.status = "hit";
        summary.reason = "all_pages_restored";
        return summary;
    } catch (eCacheRestore) {
        ctx.globalSingleTextlessPagePlanesByPageIndex = null;
        summary.status = "error";
        summary.reason = String(eCacheRestore);
        return summary;
    } finally {
        summary.elapsedMs = (new Date()).getTime() - startedAt;
        try { writeJson(ctx.outputDir + "/single-textless-page-plane-cache-restore.json", summary); } catch (eWriteRestoreSummary) {}
    }
}

function _storeCachedSingleTextlessPagePlanes(ctx, pageTextlessGroupResult) {
    var startedAt = (new Date()).getTime();
    var summary = {
        schemaVersion: 1,
        mode: "single-textless-page-plane-cache-store",
        status: "skipped",
        reason: null,
        cacheDir: ctx ? ctx.pagePlaneCacheDir || null : null,
        storedCount: 0,
        skippedCount: 0,
        elapsedMs: null,
        entries: []
    };
    try {
        if (!ctx || !ctx.pagePlaneCacheDir || ctx.graphicsMode !== "single-textless-plane") {
            summary.reason = "cache_not_configured";
            return summary;
        }
        // SPEC-049: 세 종류의 숨김 정책은 더 이상 캐시 저장을 막지 않는다.
        // 숨김 서명(hideSig)을 파일명에 반영해 저장한다.
        var hideSig = _pagePlaneHideSignature(ctx);
        summary.hideSignature = hideSig || null;
        var cacheDir = _ensureFolder(ctx.pagePlaneCacheDir);
        var pageHashes = readJson(ctx.outputDir + "/page_hashes.json") || {};
        var frames = pageTextlessGroupResult && pageTextlessGroupResult.frames
                ? pageTextlessGroupResult.frames
                : [];
        for (var i = 0; i < frames.length; i++) {
            var frame = frames[i];
            if (!frame || frame.pageIndex === null || frame.pageIndex === undefined || !frame.file) {
                summary.skippedCount++;
                continue;
            }
            var pageIndex = parseInt(frame.pageIndex, 10);
            var hash = pageHashes[String(pageIndex + 1)];
            var entry = {
                pageIndex: pageIndex,
                pageHash: hash || null,
                sourceFile: frame.file,
                cacheFile: null,
                stored: false
            };
            if (!hash) {
                summary.skippedCount++;
                entry.reason = "missing_page_hash";
                summary.entries.push(entry);
                continue;
            }
            var src = File(ctx.outputDir + "/" + frame.file);
            var dest = File(cacheDir.fsName + "/" + _pagePlaneCacheFileName(pageIndex, hash, hideSig));
            entry.cacheFile = dest.fsName;
            if (!src.exists) {
                summary.skippedCount++;
                entry.reason = "source_missing";
                summary.entries.push(entry);
                continue;
            }
            if (!dest.exists) {
                _copyFileReplacingGeneric(src, dest, "page plane cache store");
                entry.stored = true;
                summary.storedCount++;
            } else {
                entry.stored = false;
                entry.reason = "already_cached";
                summary.skippedCount++;
            }
            summary.entries.push(entry);
        }
        summary.status = "ok";
        summary.reason = "stored_available_planes";
        try {
            writeJson(cacheDir.fsName + "/metadata.json", {
                schemaVersion: 1,
                mode: "single-textless-page-plane-cache",
                graphicsMode: ctx.graphicsMode,
                perfMode: ctx.perfMode,
                pageCount: ctx.rangePageCount,
                updatedAt: (new Date()).toString()
            });
        } catch (eWriteCacheMetadata) {}
        return summary;
    } catch (eCacheStore) {
        summary.status = "error";
        summary.reason = String(eCacheStore);
        return summary;
    } finally {
        summary.elapsedMs = (new Date()).getTime() - startedAt;
        try { writeJson(ctx.outputDir + "/single-textless-page-plane-cache-store.json", summary); } catch (eWriteStoreSummary) {}
    }
}

function _prepareGlobalSingleTextlessPagePlanes(doc, ctx) {
    if (!ctx || ctx.globalSingleTextlessPagePlanesByPageIndex) return;
    if (ctx.graphicsMode && ctx.graphicsMode !== "single-textless-plane") return;
    var startedAt = (new Date()).getTime();
    var allItems = null;
    var summary = {
        schemaVersion: 1,
        mode: "global-single-textless-page-plane-preexport",
        status: "skipped",
        elapsedMs: null,
        pageCount: 0,
        renderedFrameCount: 0,
        hiddenTextItemCount: 0,
        hiddenInlineItemCount: 0,
        perfBreakdown: null
    };
    try {
        _marker(ctx.outputDir, "03a_globalSingleTextlessPagePlanes_start");
        allItems = collectRangePageItems(doc, ctx.startPage, ctx.endPage);
        var sourceIndex = _buildSourceIndexFromAllItems(doc, ctx, allItems);
        var itemById = sourceIndex && sourceIndex.domById ? sourceIndex.domById : _buildItemById(allItems);
        ctx.pagePlaneHiddenTableStyleSourceObjectIdsByPage =
                ctx.pagePlaneHiddenTableStyleSourceObjectIdsByPage
                || _tableStyleSourceObjectIdsByPageForPagePlaneHide(
                        sourceIndex && sourceIndex.sourceItems ? sourceIndex.sourceItems : []);
        var inlineCandidates = _globalSingleTextlessInlineHideCandidates(
                sourceIndex && sourceIndex.sourceItems ? sourceIndex.sourceItems : []);
        var sourceBundleTextRangeShellInlineCandidates =
                _globalSourceBundleTextRangeShellInlineHideCandidates(
                        sourceIndex && sourceIndex.sourceItems ? sourceIndex.sourceItems : []);
        ctx.pagePlaneSourceBundleTextRangeShellHideCandidateCount =
                sourceBundleTextRangeShellInlineCandidates.length;
        inlineCandidates = inlineCandidates.concat(sourceBundleTextRangeShellInlineCandidates);
        var result = exportSingleTextlessPagePlanes(
                doc,
                ctx.outputDir,
                ctx.startPage,
                ctx.endPage,
                allItems,
                itemById,
                inlineCandidates,
                {
                    globalPreExport: true,
                    inlineFallbackAllItems: true,
                    hiddenTableStyleSourceObjectIdsByPage:
                            ctx.pagePlaneHiddenTableStyleSourceObjectIdsByPage,
                    hiddenCompletePngTextOwnerSourceObjectIdsByPage:
                            ctx.pagePlaneCompletePngTextOwnerSourceObjectIdsByPage
                });
        ctx.globalSingleTextlessPagePlanesByPageIndex =
                _indexSingleTextlessPagePlaneFrames(result);
        summary.status = "ok";
        summary.pageCount = ctx.rangePageCount;
        summary.renderedFrameCount = result && result.frames ? result.frames.length : 0;
        var diag = readJson(ctx.outputDir + "/single-textless-page-plane-export.json") || {};
        summary.perfBreakdown = diag.perfBreakdown || null;
        if (summary.perfBreakdown) {
            summary.hiddenTextItemCount = summary.perfBreakdown.hiddenTextItemCount || 0;
            summary.hiddenInlineItemCount = summary.perfBreakdown.hiddenInlineItemCount || 0;
        }
        _marker(ctx.outputDir, "03a_globalSingleTextlessPagePlanes_done");
    } catch (eGlobalSinglePlane) {
        ctx.globalSingleTextlessPagePlanesByPageIndex = null;
        summary.status = "fallback";
        summary.error = String(eGlobalSinglePlane);
        try { _marker(ctx.outputDir, "03a_globalSingleTextlessPagePlanes_fallback"); } catch (eMarker) {}
    } finally {
        summary.elapsedMs = (new Date()).getTime() - startedAt;
        try { writeJson(ctx.outputDir + "/global-single-textless-page-plane-preexport.json", summary); } catch (eWriteGlobalPlane) {}
        allItems = null;
        try { $.gc(); } catch (eGc) {}
    }
}

function _tableStyleSourceObjectIdsByPageForPagePlaneHide(sourceItems) {
    var byPage = {};
    if (!sourceItems || sourceItems.length === 0) return byPage;
    var sourceById = typeof _objectPlanSourceInfoById === "function"
            ? _objectPlanSourceInfoById(sourceItems)
            : {};
    if (!sourceById || typeof _tableOnlyTextFrameStyleSourceObjectIds !== "function") return byPage;

    function add(pageIndex, ids) {
        if (pageIndex === null || pageIndex === undefined) return;
        var pageNumber = Number(pageIndex);
        if (isNaN(pageNumber) || pageNumber < 0) return;
        var pageKey = String(pageNumber);
        if (!byPage[pageKey]) byPage[pageKey] = [];
        var seen = {};
        for (var existingIndex = 0; existingIndex < byPage[pageKey].length; existingIndex++) {
            seen[String(byPage[pageKey][existingIndex])] = true;
        }
        for (var i = 0; ids && i < ids.length; i++) {
            var id = Number(ids[i]);
            if (isNaN(id)) continue;
            if (!sourceById[String(id)]) continue;
            if (seen[String(id)]) continue;
            seen[String(id)] = true;
            byPage[pageKey].push(id);
        }
        byPage[pageKey] = _sortedNumericIds(byPage[pageKey]);
    }

    for (var i = 0; i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!src || String(src.kind || "") !== "TextFrame") continue;
        if (src.hasTablesInStory !== true) continue;
        if (src.storyHasVisibleTableCellText !== true) continue;
        if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) continue;
        var id = Number(src.id);
        if (isNaN(id)) continue;
        add(src.pageIndex, [id].concat(_tableOnlyTextFrameStyleSourceObjectIds(src, id, sourceById)));
    }
    return byPage;
}

function _globalSingleTextlessInlineHideCandidates(sourceItems) {
    var candidates = [];
    for (var i = 0; sourceItems && i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!src || src.id === null || src.id === undefined) continue;
        if (src.visible === false || src.hiddenLayer === true || src.nonprinting === true) continue;
        var kind = String(src.kind || "");
        var inline =
                src.storyTextInlineSlot === true
                || src.tableCellStoryTextInlineSlot === true
                || String(src.storyAnchorPlacement || "").toUpperCase() === "INLINE"
                || String(src.anchoredPosition || "").toUpperCase() === "INLINE_POSITION"
                || String(src.anchoredPosition || "").toUpperCase() === "INLINEPOSITION"
                || src.isInline === true
                || String(src.parentKind || "") === "Character"
                || String(src.parentKind || "") === "InsertionPoint";
        if (!inline) continue;
        if (kind !== "TextFrame" && src.tableCellStoryTextInlineSlot !== true) continue;
        var id = Number(src.id);
        if (isNaN(id)) continue;
        candidates.push({
            candidateId: "global.single_textless.inline_hide." + String(id),
            passId: "pass.inline_objects",
            pageIndex: src.pageIndex,
            kind: "TextFrame",
            primarySourceObjectId: id,
            exportTargetObjectId: id,
            sourceObjectIds: [id],
            exportSourceObjectIds: [id],
            visualAction: "PLACE_INLINE_PNG",
            materialization: "EXTRACTED_PNG_VECTOR",
            placement: "INLINE",
            coordinateSpace: "STORY_FLOW",
            compositeRole: "global_single_textless_inline_hide",
            slotRole: "inline_hide"
        });
    }
    return candidates;
}

function _globalSourceBundleTextRangeShellInlineHideCandidates(sourceItems) {
    var candidates = [];
    if (!sourceItems || sourceItems.length === 0) return candidates;
    var sourceById = {};
    var childrenByParentId = {};
    for (var i = 0; i < sourceItems.length; i++) {
        var src = sourceItems[i];
        if (!src || src.id === null || src.id === undefined) continue;
        sourceById[String(src.id)] = src;
        if (src.parentId !== null && src.parentId !== undefined && String(src.parentId) !== "") {
            var key = String(src.parentId);
            if (!childrenByParentId[key]) childrenByParentId[key] = [];
            childrenByParentId[key].push(src);
        }
    }
    function visibleShell(src) {
        if (!src || src.visible === false || src.hiddenLayer === true || src.nonprinting === true) return false;
        var kind = String(src.kind || src.type || "");
        if (kind !== "Polygon" && kind !== "Rectangle" && kind !== "Oval") return false;
        return src.hasVisibleFill === true || src.hasVisibleStroke === true;
    }
    function editableTextFrame(src) {
        return !!(src
                && String(src.kind || src.type || "") === "TextFrame"
                && src.textFrameClass === "editable"
                && src.hasText === true
                && src.visible !== false
                && src.hiddenLayer !== true
                && src.nonprinting !== true
                && src.leadingStyledTextRanges
                && src.leadingStyledTextRanges.length > 0);
    }
    function sourceIsInlineFlow(src) {
        if (!src) return false;
        var placement = String(src.storyAnchorPlacement || "").toUpperCase();
        var anchoredPosition = String(src.anchoredPosition || "").toUpperCase();
        if (placement === "FLOATING_ANCHORED" || anchoredPosition === "ANCHORED") return false;
        if (src.storyTextInlineSlot === true || src.tableCellStoryTextInlineSlot === true
                || src.isInline === true) {
            return true;
        }
        return placement === "INLINE"
                || anchoredPosition === "INLINE_POSITION"
                || anchoredPosition === "INLINEPOSITION";
    }
    function bundleIsInlineFlow(group, children) {
        if (sourceIsInlineFlow(group)) return true;
        for (var i = 0; children && i < children.length; i++) {
            if (sourceIsInlineFlow(children[i])) return true;
        }
        return false;
    }
    function center(bounds, axis) {
        if (!bounds || bounds.length < 4) return 0;
        return axis === 0 ? (Number(bounds[0]) + Number(bounds[2])) / 2
                : (Number(bounds[1]) + Number(bounds[3])) / 2;
    }
    var seen = {};
    for (var gi = 0; gi < sourceItems.length; gi++) {
        var group = sourceItems[gi];
        if (!group || String(group.kind || group.type || "") !== "Group") continue;
        var children = childrenByParentId[String(group.id)] || [];
        if (!bundleIsInlineFlow(group, children)) continue;
        var textFrames = [];
        var shells = [];
        var otherVisible = 0;
        for (var ci = 0; ci < children.length; ci++) {
            var child = children[ci];
            if (editableTextFrame(child)) {
                textFrames.push(child);
            } else if (visibleShell(child)) {
                shells.push(child);
            } else if (child && child.visible !== false && child.hiddenLayer !== true && child.nonprinting !== true) {
                otherVisible++;
            }
        }
        if (textFrames.length === 0 || shells.length === 0 || otherVisible > 0) continue;
        var rangeCount = 0;
        for (var ti = 0; ti < textFrames.length; ti++) {
            rangeCount += textFrames[ti].leadingStyledTextRanges
                    ? textFrames[ti].leadingStyledTextRanges.length
                    : 0;
        }
        if (rangeCount === 0) continue;
        shells.sort(function(a, b) {
            var ay = center(a.bounds, 0);
            var by = center(b.bounds, 0);
            if (ay !== by) return ay - by;
            var ax = center(a.bounds, 1);
            var bx = center(b.bounds, 1);
            if (ax !== bx) return ax - bx;
            return Number(a.zOrder || 0) - Number(b.zOrder || 0);
        });
        var pairCount = Math.min(shells.length, rangeCount);
        for (var pi = 0; pi < pairCount; pi++) {
            var shell = shells[pi];
            var shellId = Number(shell.id);
            var textFrameId = Number(textFrames[0].id);
            if (isNaN(shellId) || isNaN(textFrameId) || seen[String(shellId)]) continue;
            seen[String(shellId)] = true;
            candidates.push({
                candidateId: "global.source_bundle_text_range_shell.inline_hide." + String(shellId),
                passId: "pass.inline_objects",
                pageIndex: shell.pageIndex,
                kind: shell.kind || "Polygon",
                primarySourceObjectId: shellId,
                exportTargetObjectId: shellId,
                sourceObjectIds: [shellId, textFrameId],
                exportSourceObjectIds: [shellId],
                visualAction: "PLACE_TEXT_SHELL",
                materialization: "EXTRACTED_PNG_VECTOR",
                placement: "INLINE",
                coordinateSpace: "STORY_FLOW",
                compositeRole: "source_bundle_text_range_shell",
                slotRole: "source_bundle_text_range_shell_slot"
            });
        }
    }
    return candidates;
}

function _runSpreadChunkExtraction(doc, ctx) {
    var chunks = _selectedSpreadChunks(doc, ctx);
    if (!chunks || chunks.length === 0) {
        throw new Error("spread_chunks mode found no selected spreads");
    }
    _ensureFolder(ctx.outputDir + "/chunks");
    _prepareSpreadChunkSourceInfoCache(doc, ctx);
    _prepareGlobalSingleTextlessPagePlanes(doc, ctx);
    for (var ci = 0; ci < chunks.length; ci++) {
        var chunk = chunks[ci];
        var chunkLabel = "spread_" + _spreadChunkSafeName(chunk.spreadKey);
        var chunkDir = ctx.outputDir + "/chunks/" + chunkLabel;
        _ensureFolder(chunkDir);
        writeProgress(ctx.outputDir, "spread_chunk", chunk.chunkIndex, chunks.length,
                "spread " + chunk.spreadKey + " pages " + chunk.startPage + ".." + chunk.endPage);
        var chunkCtx = _cloneContextForSpreadChunk(ctx, chunk, chunkDir);
        var savedPhaseTiming = _phaseTimingState;
        _phaseTimingState = null;
        try {
            var allItems = collectRangePageItems(doc, chunk.startPage, chunk.endPage);
            chunkCtx.rangeTargetPageIndexesBySourceId = collectRangePageItems.lastTargetPageIndexesByItemId || {};
            _marker(chunkCtx.outputDir, "03b_pageHashes_start");
            try {
                var pageData = buildPageData(doc, chunk.startPage, chunk.endPage, allItems);
                writeJson(chunkCtx.outputDir + "/page_hashes.json", pageData.hashes);
                writeJson(chunkCtx.outputDir + "/page_item_map.json", pageData.itemMap);
            } catch (eChunkHash) {
                $.writeln("[spread_chunks pageHash] error: " + eChunkHash);
            }
            _marker(chunkCtx.outputDir, "03b_pageHashes_done");
            _runRenderPhases(doc, chunkCtx, allItems);
            allItems = null;
            try { $.gc(); } catch (eGc) {}
        } finally {
            _phaseTimingState = savedPhaseTiming;
        }
    }
    _mergeSpreadChunkOutputs(ctx, chunks);
}

function _runRenderPhases(doc, ctx, allItems) {
    // 03c. allItems 전체 분류 캐시 — classifyTextFrame의 중복 DOM 호출 제거
    _ctfCache = {};
    _hiddenLayerCache = {};  // isOnHiddenLayer 캐시 리셋 (새 allItems 기준)
    _hiddenVisibilityCache = {};  // isHiddenByVisibility 캐시 리셋 (새 allItems 기준)
    var _preClassifyStats = {
        totalPageItems: allItems.length,
        mode: "lazy_source_index",
        skippedEagerPass: true
    };
    try { writeJson(ctx.outputDir + "/_preclassify_stats.json", _preClassifyStats); } catch (ePreClassifyStats) {}
    _marker(ctx.outputDir, "03c_preClassify");

    writeProgress(ctx.outputDir, "planning", 0, ctx.rangePageCount);
    _marker(ctx.outputDir, "03d_buildExtractionPlan_start");
    ctx.extractionPlan = _buildExtractionPlan(doc, ctx, allItems);
    _marker(ctx.outputDir, "03d_buildExtractionPlan_done");
    // SPEC-049: mixed-bundle placed-visual 숨김 후보 카운트를 plan 완료 직후에
    // 미리 계산한다. 실제 렌더/세팅은 하위 단계(image_placed_frames pass)에서
    // 그대로 일어나지만, 페이지 평면 캐시 서명(_pagePlaneHideSignature)이
    // restore(아래) 와 store(렌더 후) 양쪽에서 동일한 값을 봐야 hit 이 성립하므로,
    // restore 이전에 결정론적 카운트를 확정해 둔다. extractionPlan 에서 순수
    // 도출되므로 여기서 계산해도 하위 단계 값과 항상 일치한다.
    try {
        if (ctx.pagePlaneMixedBundlePlacedVisualHideCandidateCount === undefined
                || ctx.pagePlaneMixedBundlePlacedVisualHideCandidateCount === null) {
            var mixedPreCandidates = _pngExtractionCandidatesForPass(ctx.extractionPlan, "pass.image_placed_frames");
            ctx.pagePlaneMixedBundlePlacedVisualHideCandidateCount =
                    (mixedPreCandidates && mixedPreCandidates.length) || 0;
        }
    } catch (eMixedPreCount) {
        ctx.pagePlaneMixedBundlePlacedVisualHideCandidateCount =
                ctx.pagePlaneMixedBundlePlacedVisualHideCandidateCount || 0;
    }
    // SPEC-054: text-only 는 PNG 렌더를 만들지 않으므로 페이지 평면 캐시도 불필요.
    var _contentTextOnly = ctx.contentMode === "text-only";
    if (ctx.deferPagePlaneCacheRestoreUntilAfterPlan === true && !_contentTextOnly) {
        _marker(ctx.outputDir, "03b1_pagePlaneCacheRestore_afterPlan_start");
        _restoreCachedSingleTextlessPagePlanes(doc, ctx);
        _marker(ctx.outputDir, "03b1_pagePlaneCacheRestore_afterPlan_done");
        ctx.deferPagePlaneCacheRestoreUntilAfterPlan = false;
    }
    _extractionCandidateLookup = _buildExtractionCandidateLookup(ctx.extractionPlan);
    _marker(ctx.outputDir, "03e_buildCandidateLookup_done");
    var extractionItemById = ctx._sourceIndexForRender && ctx._sourceIndexForRender.domById
            ? ctx._sourceIndexForRender.domById
            : _buildItemById(allItems);
    _marker(ctx.outputDir, "03f_buildItemById_done");
    writeJson(ctx.outputDir + "/extraction-plan.json",
            ctx.writePlannerDiagnostics === true
                    ? ctx.extractionPlan
                    : _slimExtractionPlanForWrite(ctx.extractionPlan));
    _marker(ctx.outputDir, "03g_writeExtractionPlan_done");
    writeJson(ctx.outputDir + "/source-graph.json",
            ctx.writePlannerDiagnostics === true
                    ? _buildSourceGraphFromExtractionPlan(ctx.extractionPlan)
                    : _buildSourceGraphSummaryFromExtractionPlan(ctx.extractionPlan));
    _marker(ctx.outputDir, "03g2_writeSourceGraph_done");
    var planDiagnostics = ctx._extractionPlanDiagnostics || {};
    var plannerBundleDiagnostics = planDiagnostics.plannerBundleDiagnostics
            || _buildPlannerBundles(ctx.extractionPlan.sourceItems, ctx.extractionPlan.candidates);
    _marker(ctx.outputDir, "03i_buildPlannerBundles_done");
    var objectPlanDiagnostics = planDiagnostics.objectPlanDiagnostics
            || _buildObjectPlanDiagnosticsFromPlannerBundles(
                    plannerBundleDiagnostics, ctx.extractionPlan.sourceItems);
    _marker(ctx.outputDir, "03j_buildObjectPlans_done");
    writeJson(ctx.outputDir + "/object-plans.json",
            ctx.writePlannerDiagnostics === true
                    ? objectPlanDiagnostics
                    : _slimObjectPlanDiagnosticsForWrite(objectPlanDiagnostics, {
                        importReadyOnly: ctx.perfMode === "fast"
                    }));
    _marker(ctx.outputDir, "03l_writeObjectPlans_done");
    var objectPlanValidationGate = _assertObjectPlanGate(ctx, objectPlanDiagnostics);
    ctx.extractionPlan.objectPlanValidationGateSummary = {
        status: objectPlanValidationGate.status,
        issueCount: objectPlanValidationGate.issueCount,
        issueCodeCounts: objectPlanValidationGate.issueCodeCounts
    };
    _marker(ctx.outputDir, "03l2_objectPlanValidationGate_done");
    var sourceOwnershipStageGate = _assertSourceOwnershipStageGate(
            ctx,
            planDiagnostics.sourceCoverageDiagnostics,
            planDiagnostics.sourceOwnershipModelDiagnostics,
            objectPlanDiagnostics);
    ctx.extractionPlan.sourceOwnershipStageGateSummary = {
        status: sourceOwnershipStageGate.status,
        issueCount: sourceOwnershipStageGate.issueCount,
        issueCodeCounts: sourceOwnershipStageGate.issueCodeCounts
    };
    _marker(ctx.outputDir, "03l3_sourceOwnershipStageGate_done");
    writeJson(ctx.outputDir + "/exact-shell-slot-duplicates.json",
            planDiagnostics.exactShellSlotDuplicateDiagnostics
                    || {
                        schemaVersion: 1,
                        policy: "POLICY-source-ownership",
                        mode: "exact-shell-slot-duplicate-diagnostics",
                        summary: ctx.extractionPlan.exactShellSlotDuplicateSummary || {}
                    });
    _marker(ctx.outputDir, "03k3_writeExactShellSlotDuplicates_done");
    writeJson(ctx.outputDir + "/legacy-normalization-filters.json",
            planDiagnostics.legacyNormalizationFilterDiagnostics
                    || {
                        schemaVersion: 1,
                        policy: "POLICY-source-ownership",
                        mode: "legacy-normalization-filter-diagnostics",
                        summary: ctx.extractionPlan.legacyNormalizationFilterSummary || {}
                    });
    _marker(ctx.outputDir, "03k4_writeLegacyNormalizationFilters_done");
    if (ctx.writePlannerDiagnostics === true) {
        var sourceClusterDiagnosticsForWrite = planDiagnostics.sourceClusterDiagnostics
                || _buildSourceClusters(ctx.extractionPlan.sourceItems);
        sourceClusterDiagnosticsForWrite.queryDiagnostics = planDiagnostics.sourceClusterQueryDiagnostics
                || _buildSourceClusterQueryDiagnostics(
                        _createSourceClusterIndex(ctx.extractionPlan.sourceItems, sourceClusterDiagnosticsForWrite),
                        ctx.extractionPlan.candidates);
        writeJson(ctx.outputDir + "/source-clusters.json", sourceClusterDiagnosticsForWrite);
        _marker(ctx.outputDir, "03h_writeSourceClusters_done");
        writeJson(ctx.outputDir + "/planner-bundles.json", plannerBundleDiagnostics);
        _marker(ctx.outputDir, "03k_writePlannerBundles_done");
        var sourceSlotCanonicalizationDiagnostics = planDiagnostics.sourceSlotCanonicalizationDiagnostics
                || {
                            schemaVersion: 1,
                            policy: "POLICY-source-ownership",
                            mode: "source-slot-canonicalization-filter",
                            summary: ctx.extractionPlan.sourceSlotCanonicalizationSummary || {}
                        };
        writeJson(ctx.outputDir + "/source-slot-canonicalization.json",
                sourceSlotCanonicalizationDiagnostics);
        _marker(ctx.outputDir, "03k2_writeSourceSlotCanonicalization_done");
        writeJson(ctx.outputDir + "/source-slot-registry.json",
                planDiagnostics.sourceSlotRegistryDiagnostics
                        || _buildSourceSlotRegistryDiagnostics(
                                plannerBundleDiagnostics, objectPlanDiagnostics, ctx.extractionPlan.sourceItems));
        _marker(ctx.outputDir, "03m_writeSourceSlotRegistry_done");
        writeJson(ctx.outputDir + "/execution-candidate-contract.json",
                planDiagnostics.executionCandidateContractDiagnostics
                        || {
                            schemaVersion: 1,
                            policy: "POLICY-source-ownership",
                            mode: "execution-candidate-contract-diagnostics",
                            summary: ctx.extractionPlan.executionCandidateContractSummary || {}
                        });
        _marker(ctx.outputDir, "03n_writeExecutionCandidateContract_done");
    } else {
        writeJson(ctx.outputDir + "/planner-diagnostics-summary.json", {
            schemaVersion: 1,
            policy: "POLICY-source-ownership",
            mode: "planner-diagnostics-summary",
            sourceClusterSummary: ctx.extractionPlan.sourceClusterSummary,
            sourceClusterQuerySummary: ctx.extractionPlan.sourceClusterQuerySummary,
            plannerBundleSummary: ctx.extractionPlan.plannerBundleSummary,
            objectPlanSummary: ctx.extractionPlan.objectPlanSummary,
            sourceCoverageSummary: ctx.extractionPlan.sourceCoverageSummary,
            sourceOwnershipModelSummary: ctx.extractionPlan.sourceOwnershipModelSummary,
            objectPlanValidationGateSummary: ctx.extractionPlan.objectPlanValidationGateSummary,
            sourceOwnershipStageGateSummary: ctx.extractionPlan.sourceOwnershipStageGateSummary,
            preObjectPlanTextlessShellSuppressionSummary:
                    ctx.extractionPlan.preObjectPlanTextlessShellSuppressionSummary,
            inlineMicroVectorPatternCollapseSummary:
                    ctx.extractionPlan.inlineMicroVectorPatternCollapseSummary,
            sourceSlotCanonicalizationSummary: ctx.extractionPlan.sourceSlotCanonicalizationSummary,
            executionCandidateContractSummary: ctx.extractionPlan.executionCandidateContractSummary,
            legacyNormalizationFilterSummary: ctx.extractionPlan.legacyNormalizationFilterSummary,
            exactShellSlotDuplicateSummary: ctx.extractionPlan.exactShellSlotDuplicateSummary,
            sourceSlotRegistrySummary: ctx.extractionPlan.sourceSlotRegistrySummary,
            fullDiagnosticsSkipped: true,
            enableFullDiagnostics: "run with argument 13 '--diagnostics' or perfMode diagnostics/debug"
        });
        _marker(ctx.outputDir, "03h_writePlannerDiagnosticsSummary_done");
    }

    _marker(ctx.outputDir, "04_pageRendering");
    writeProgress(ctx.outputDir, "rendered_frames", 0, ctx.rangePageCount);

    // 2.12. page background + inline object 후보 추출
    // SPEC-054 text-only: PNG 렌더 5단계(배경/인라인/데코/이미지프레임/페이지평면)
    // 전부 생략. 플랜·object-plans·resolved 수집은 그대로 유지한다.
    if (_contentTextOnly) {
        _marker(ctx.outputDir, "04x_contentMode_textOnly_renderSkipped");
    }
    _requireExtractionPass(ctx, "pass.page_backgrounds");
    var pageBackgroundResult = _contentTextOnly
            ? { items: [] }
            : exportPageBackgrounds(doc, ctx.outputDir, ctx.startPage, ctx.endPage,
                    _extractionCandidatesForPass(ctx.extractionPlan, "pass.page_backgrounds"),
                    ctx.skipRenderPagesMap);
    var renderedFloatingItems = pageBackgroundResult.items || [];
    _addRenderMeta(renderedFloatingItems, null, "pass.page_backgrounds");
    _marker(ctx.outputDir, "04a_pageBackgrounds");

    var editableFrameIds = collectEditableFrameIds(allItems);
    var editableTextPaintSnapshot = [];
    try {
        editableTextPaintSnapshot = snapshotEditableTextFramePaintState(allItems, editableFrameIds);
    } catch (eTextPaintSnapshot) {
        editableTextPaintSnapshot = [];
    }
    _marker(ctx.outputDir, "04b_collectEditableFrameIds");

    _requireExtractionPass(ctx, "pass.inline_objects");
    var inlinePngCandidates = _pngExtractionCandidatesForPass(ctx.extractionPlan, "pass.inline_objects");
    var inlineNonPngCandidates = _nonPngExtractionCandidatesForPass(ctx.extractionPlan, "pass.inline_objects");
    writeJson(ctx.outputDir + "/inline-non-png-export-skip.json", {
        passId: "pass.inline_objects",
        skippedCount: inlineNonPngCandidates.length,
        skippedCandidates: inlineNonPngCandidates,
        reason: "non_png_materialization_uses_object_plan_source_item"
    });
    var inlineResult = _contentTextOnly
            ? { items: [] }
            : exportInlineObjects(doc, ctx.outputDir, ctx.startPage, ctx.endPage,
                    allItems, extractionItemById,
                    inlinePngCandidates);
    _addRenderMeta(inlineResult.items, null, "pass.inline_objects");
    for (var inri = 0; inlineResult.items && inri < inlineResult.items.length; inri++) {
        renderedFloatingItems.push(inlineResult.items[inri]);
    }
    if (inlineResult.tableInlineRendered) {
        _addRenderMeta(inlineResult.tableInlineRendered, null, "pass.inline_objects");
        for (var tir = 0; tir < inlineResult.tableInlineRendered.length; tir++)
            renderedFloatingItems.push(inlineResult.tableInlineRendered[tir]);
    }
    _requireExtractionPass(ctx, "pass.decoration_groups");
    var decorationPngCandidates = _pngExtractionCandidatesForPass(ctx.extractionPlan, "pass.decoration_groups");
    var decorationResult = _contentTextOnly
            ? { items: [] }
            : exportInlineObjects(doc, ctx.outputDir, ctx.startPage, ctx.endPage,
                    allItems, extractionItemById,
                    decorationPngCandidates);
    _addRenderMeta(decorationResult.items, null, "pass.decoration_groups");
    for (var dri = 0; decorationResult.items && dri < decorationResult.items.length; dri++) {
        renderedFloatingItems.push(decorationResult.items[dri]);
    }
    _requireExtractionPass(ctx, "pass.master_page_graphics");
    var masterGraphicPngCandidates = _pngExtractionCandidatesForPass(ctx.extractionPlan, "pass.master_page_graphics");
    var masterGraphicResult = _contentTextOnly
            ? { items: [] }
            : exportInlineObjects(doc, ctx.outputDir, ctx.startPage, ctx.endPage,
                    allItems, extractionItemById,
                    masterGraphicPngCandidates);
    _addRenderMeta(masterGraphicResult.items, null, "pass.master_page_graphics");
    for (var mgi = 0; masterGraphicResult.items && mgi < masterGraphicResult.items.length; mgi++) {
        renderedFloatingItems.push(masterGraphicResult.items[mgi]);
    }
    _requireExtractionPass(ctx, "pass.image_placed_frames");
    var imagePlacedPngCandidates = _pngExtractionCandidatesForPass(ctx.extractionPlan, "pass.image_placed_frames");
    ctx.pagePlaneMixedBundlePlacedVisualHideCandidateCount = imagePlacedPngCandidates.length || 0;
    var imagePlacedResult = _contentTextOnly
            ? { items: [] }
            : exportInlineObjects(doc, ctx.outputDir, ctx.startPage, ctx.endPage,
                    allItems, extractionItemById,
                    imagePlacedPngCandidates);
    _addRenderMeta(imagePlacedResult.items, null, "pass.image_placed_frames");
    for (var ipi = 0; imagePlacedResult.items && ipi < imagePlacedResult.items.length; ipi++) {
        renderedFloatingItems.push(imagePlacedResult.items[ipi]);
    }
    try { $.gc(); } catch (e) {}
    // exportInlineObjects의 finally 블록이 true로 복원하지만, 예외 전파 등 만일의 경우를 대비한 안전망.
    try { app.pngExportPreferences.transparentBackground = true; } catch (e) {}
    _marker(ctx.outputDir, "04c_inlineObjects");

    var renderedImageFrames = [];
    var pageTextlessGroupResult = { frames: [], textlessShellDiagnostics: [], childIds: {} };
    var renderedGraphicFrames = [];
    var renderedVectorFrames = [];
    var renderedMasterGraphics = [];
    var tfShellFrames = [];

    _marker(ctx.outputDir, "06b_pageTextlessGroups");
    var pageBackgroundPlaneCandidates =
            _pngExtractionCandidatesForPass(ctx.extractionPlan, "pass.page_backgrounds");
    var pageTextlessGroupCandidates =
            _pngExtractionCandidatesForPass(ctx.extractionPlan, "pass.page_textless_graphic_groups");
    var pageTextlessPlanePages = [];
    var pageTextlessPlanePageSeen = {};
    function addPageTextlessPlanePage(candidate) {
        if (!candidate) return;
        var pageIndex = Number(candidate.pageIndex);
        if (isNaN(pageIndex) || pageIndex < 0) return;
        if (pageTextlessPlanePageSeen[String(pageIndex)]) return;
        pageTextlessPlanePageSeen[String(pageIndex)] = true;
        pageTextlessPlanePages.push(pageIndex);
    }
    for (var pbci = 0; pbci < pageBackgroundPlaneCandidates.length; pbci++) {
        addPageTextlessPlanePage(pageBackgroundPlaneCandidates[pbci]);
    }
    var pageTextlessGroupPageSeen = {};
    for (var ptgci = 0; ptgci < pageTextlessGroupCandidates.length; ptgci++) {
        addPageTextlessPlanePage(pageTextlessGroupCandidates[ptgci]);
        var ptgPageIndex = Number(pageTextlessGroupCandidates[ptgci].pageIndex);
        if (!isNaN(ptgPageIndex) && ptgPageIndex >= 0) {
            pageTextlessGroupPageSeen[String(ptgPageIndex)] = true;
        }
    }
    var pagePlaneExportOptions = {
        inlineFallbackAllItems: true,
        allowedPageIndexes: pageTextlessPlanePages,
        pagePlaneCandidates: pageTextlessGroupCandidates.concat(pageBackgroundPlaneCandidates),
        hiddenTableStyleSourceObjectIdsByPage:
                ctx.pagePlaneHiddenTableStyleSourceObjectIdsByPage || {},
        hiddenCompletePngTextOwnerSourceObjectIdsByPage:
                ctx.pagePlaneCompletePngTextOwnerSourceObjectIdsByPage || {}
    };
    // SPEC-049: restore 가 서명(hideSig) 기반으로 hit 을 판정했다면
    // globalSingleTextlessPagePlanesByPageIndex 는 현재 숨김 정책(table-style /
    // text-range-shell / mixed-bundle 포함)과 동일한 서명의 캐시다. 따라서
    // mixed-bundle 후보가 있어도 재사용은 안전하다. 이전의 mixedBundle>0 가드는
    // restore/store 가드와 함께 제거한다 (그 가드가 없으면 hit 이어도 fresh export
    // 되어 캐시가 무의미해진다).
    if (ctx.globalSingleTextlessPagePlanesByPageIndex) {
        pagePlaneExportOptions.precomputedPagePlanesByPageIndex =
                ctx.globalSingleTextlessPagePlanesByPageIndex;
    }
    var pagePlaneHideCandidates = inlinePngCandidates;
    if (imagePlacedPngCandidates && imagePlacedPngCandidates.length > 0) {
        pagePlaneHideCandidates = inlinePngCandidates.concat(imagePlacedPngCandidates);
    }
    if (!_contentTextOnly && pageTextlessPlanePages.length > 0) {
        pageTextlessGroupResult = exportSingleTextlessPagePlanes(
                doc,
                ctx.outputDir,
                ctx.startPage,
                ctx.endPage,
                allItems,
                extractionItemById,
                pagePlaneHideCandidates,
                pagePlaneExportOptions);
        _marker(ctx.outputDir, "06b1_pageTextlessGroups_exportDone");
        _storeCachedSingleTextlessPagePlanes(ctx, pageTextlessGroupResult);
        _marker(ctx.outputDir, "06b1a_pageTextlessGroups_cacheStoreDone");
    }
    for (var ptgm = 0; pageTextlessGroupResult.frames && ptgm < pageTextlessGroupResult.frames.length; ptgm++) {
        var ptgFrame = pageTextlessGroupResult.frames[ptgm];
        var ptgFramePageIndex = Number(ptgFrame && ptgFrame.pageIndex);
        var ptgFramePass = !isNaN(ptgFramePageIndex)
                && pageTextlessGroupPageSeen[String(ptgFramePageIndex)]
                ? "pass.page_textless_graphic_groups"
                : "pass.page_backgrounds";
        _addRenderMeta([ptgFrame], "page_object", ptgFramePass);
    }
    _marker(ctx.outputDir, "06b2_pageTextlessGroups_metaDone");
    for (var ptgi = 0; ptgi < pageTextlessGroupResult.frames.length; ptgi++) {
        renderedFloatingItems.push(pageTextlessGroupResult.frames[ptgi]);
    }
    _marker(ctx.outputDir, "06b3_pageTextlessGroups_pushDone");

    try {
        _marker(ctx.outputDir, "09d_extractionResults");
        _stampExportUnitsOnRenderedItems(ctx, renderedFloatingItems);
        var textlessDiagnostics = [];
        try {
            textlessDiagnostics = textlessDiagnostics.concat(
                    pageTextlessGroupResult.textlessShellDiagnostics || []);
        } catch (eTextlessDiagMerge) {
            textlessDiagnostics = [];
        }
        var extractionResults = _buildExtractionResults(ctx, renderedFloatingItems,
                renderedImageFrames, renderedGraphicFrames, renderedVectorFrames,
                renderedMasterGraphics, tfShellFrames,
                textlessDiagnostics);
        writeJson(ctx.outputDir + "/extraction-results.json", extractionResults);
        writeJson(ctx.outputDir + "/export-units.json", extractionResults.exportUnits);
        if (extractionResults.validation && extractionResults.validation.status !== "OK") {
            _pushExtractionValidationWarning(
                    ctx,
                    "Extraction plan validation failed: "
                            + extractionResults.validation.issueCount
                            + " issue(s): "
                            + _objectPlanGateIssueCodesForMessage(
                                    _objectPlanGateIssueCodeCounts(
                                            extractionResults.validation.issues || [])));
        }
    } catch (eExtractionResults) {
        try {
            var _er = File(ctx.outputDir + "/_extraction_results_error.log");
            _er.encoding = "UTF-8";
            _er.open("w");
            _er.write(String(eExtractionResults));
            _er.close();
        } catch (eErLog) {}
        throw eExtractionResults;
    }

    // 추출 통계 기록
    try {
        var _statsEndMs = (new Date()).getTime();
        var _statsStartMs = _phaseTimingState ? _phaseTimingState.startTime : _statsEndMs;
        var _inlineCount = 0;
        for (var _si2 = 0; _si2 < renderedFloatingItems.length; _si2++) {
            if (renderedFloatingItems[_si2].type === "inline_object") _inlineCount++;
        }
        var _statsObj = {
            elapsed_ms: _statsEndMs - _statsStartMs,
            total_page_count: ctx.pageCount,
            requested_start_page: ctx.requestedStartPage,
            requested_end_page: ctx.requestedEndPage,
            start_page: ctx.startPage,
            end_page: ctx.endPage,
            page_count: ctx.rangePageCount,
            range_input_mode: ctx.rangeInputMode,
            resolved_range_mode: ctx.resolvedRangeMode,

            deco_group_count: 0,
            image_frame_count: 0,
            complex_frame_count: 0,
            shape_count: 0,
            master_graphic_count: 0,
            inline_object_count: _inlineCount
        };
        var _sf = File(ctx.outputDir + "/_extract_stats.json");
        _sf.encoding = "UTF-8"; _sf.open("w"); _sf.write(JSON.stringify(_statsObj)); _sf.close();
    } catch (eStats) {}

    // 3. resolved 속성 수집
    // Render/export phases hide HWPX-owned TF paint while producing shell PNGs.
    // Restore from the source snapshot before resolved/preview so temporary
    // extraction state cannot leak into later stages.
    try {
        restoreEditableTextFramePaintState(editableTextPaintSnapshot);
    } catch (eTextPaintRestoreBeforeResolved) {}
    _marker(ctx.outputDir, "10_collectResolved");
    writeProgress(ctx.outputDir, "resolved", 0, ctx.rangePageCount);
    var resolvedOptions = {
        extractionCandidates: ctx.extractionPlan && ctx.extractionPlan.candidates
                ? ctx.extractionPlan.candidates
                : [],
        objectPlans: ctx.extractionPlan && ctx.extractionPlan.objectPlans
                ? ctx.extractionPlan.objectPlans
                : [],
        sourceItems: ctx.extractionPlan && ctx.extractionPlan.sourceItems
                ? ctx.extractionPlan.sourceItems
                : [],
        collectPageItemsFromSource: ctx.perfMode !== "diagnostics" && ctx.perfMode !== "debug",
        executionCandidates: ctx.extractionPlan && ctx.extractionPlan.executionCandidates
                ? ctx.extractionPlan.executionCandidates
                : [],
        renderedFloatingItems: renderedFloatingItems
    };
    var resolved = collectResolved(doc, ctx.outputDir, ctx.rangePageCount, ctx.startPage, ctx.endPage, editableFrameIds, ctx.skipRenderPagesMap, allItems, resolvedOptions);
    // SPEC-054: full 이 아닌 콘텐츠 모드는 Java 파이프라인 분기·phase 게이트가
    // 읽을 수 있게 documentInfo 에 마커를 남긴다.
    if (ctx.contentMode && ctx.contentMode !== "full" && resolved.documentInfo) {
        resolved.documentInfo.contentMode = ctx.contentMode;
    }
    resolved.renderedTextFrames    = [];
    resolved.renderedPdfFrames     = [];
    resolved.renderedGraphicFrames = renderedGraphicFrames;
    resolved.renderedImageFrames   = renderedImageFrames;
    resolved.renderedFloatingItems = renderedFloatingItems;
    resolved.textlessShellDiagnostics = pageTextlessGroupResult.textlessShellDiagnostics || [];
    try {
        var _diagFile = File(ctx.outputDir + "/textless-shell-diagnostics.jsonl");
        _diagFile.encoding = "UTF-8";
        _diagFile.open("w");
        var _diags = resolved.textlessShellDiagnostics || [];
        for (var _dsi = 0; _dsi < _diags.length; _dsi++) {
            _diagFile.writeln(JSON.stringify(_diags[_dsi]));
        }
        _diagFile.close();
    } catch (eDiagWrite) {}

    // 편집 가능 TextFrame ID 목록 (source ownership policy: synthetic master instance ID는 문자열로 유지)
    var pngOwnedTextFrameIds = {};
    try {
        for (var _poi = 0; _poi < renderedFloatingItems.length; _poi++) {
            var _po = renderedFloatingItems[_poi];
            if (!_po || _po.textOwner !== "indesign_png" || !_po.editableTextFrameIds) continue;
            for (var _ptfi = 0; _ptfi < _po.editableTextFrameIds.length; _ptfi++) {
                pngOwnedTextFrameIds[String(_po.editableTextFrameIds[_ptfi])] = true;
            }
        }
    } catch (ePngOwned) {}
    var editableIdList = [];
    for (var eid in editableFrameIds) {
        if (pngOwnedTextFrameIds[String(eid)]) continue;
        if (/^[0-9]+$/.test(eid)) editableIdList.push(parseInt(eid, 10));
        else editableIdList.push(eid);
    }
    resolved.editableTextFrameIds = editableIdList;

    // Safety: 모든 export 단계에서 예외/stale DOM으로 restore가 누락된 경우 대비.
    // editableFrameIds TF를 강제 복원 (visible=true) — PDF 프리뷰 이전 최종 보정.
    for (var _sri = 0; _sri < allItems.length; _sri++) {
        try {
            var _srItem = allItems[_sri];
            if (_srItem.constructor.name === "TextFrame"
                    && editableFrameIds[_srItem.id]
                    && !_srItem.visible) {
                _srItem.visible = true;
            }
        } catch (e) {}
    }
    try {
        restoreEditableTextFramePaintState(editableTextPaintSnapshot);
    } catch (eTextPaintRestoreAfterResolved) {}

    _marker(ctx.outputDir, "10_collectResolved_done");
    _marker(ctx.outputDir, "11_writeJson");
    // chunkMode=true(후속 청크)면 resolved_START_END.json으로 저장 (Rust가 나중에 병합)
    var _resolvedFileName = ctx.chunkMode
        ? ("resolved_" + ctx.startPage + "_" + ctx.endPage + ".json")
        : "resolved.json";
    writeResolvedJson(ctx.outputDir + "/" + _resolvedFileName, resolved, ctx.outputDir);
}

function main(args) {
    var ctx = _parseArgs(args);

    // 환경설정 저장 (finally에서 복원)
    var savedInteractionLevel = null;
    var savedEnableRedraw = null;
    var savedCheckLinks = null;
    var savedFindMissing = null;
    try { savedInteractionLevel = app.scriptPreferences.userInteractionLevel; } catch (eSavedInteraction) {}
    try { savedEnableRedraw = app.scriptPreferences.enableRedraw; } catch (eSavedRedraw) {}
    try { savedCheckLinks = app.linkingPreferences.checkLinksAtOpen; } catch (eSavedCheckLinks) {}
    try { savedFindMissing = app.linkingPreferences.findMissingLinksAtOpen; } catch (eSavedFindMissing) {}

    var doc = null;
    try {
        _loadExtractInddModules(args, ctx.configPath);
        if (typeof _buildSourceClusters !== "function") {
            try {
                var moduleLog = File(ctx.outputDir + "/_module_load_debug.log");
                moduleLog.encoding = "UTF-8";
                moduleLog.open("w");
                moduleLog.write(String(_EXTRACT_MODULE_LOAD_DEBUG || "no module candidates"));
                moduleLog.close();
            } catch (eModuleLog) {}
            throw new Error("extract_indd module load failed: scripts/indd/source_clusters.jsx");
        }

        // 다이얼로그 억제 (headless) — 누락 폰트·링크 팝업 자동 dismiss
        try { app.scriptPreferences.userInteractionLevel = UserInteractionLevels.NEVER_INTERACT; } catch (eNeverInteract) {}
        try { app.scriptPreferences.enableRedraw = false; } catch (eDisableRedraw) {}
        try { app.linkingPreferences.checkLinksAtOpen = false; } catch (eCheckLinks) {}
        try { app.linkingPreferences.findMissingLinksAtOpen = false; } catch (eFindLinks) {}
        try { app.preflightOptions.preflightOn = false; } catch (e) {}   // 대용량 문서 preflight blocking 방지
        try { app.generalPreferences.ungroupRemembersLayers = false; } catch (e) {}

        _marker(ctx.outputDir, "00_start");
        writeProgress(ctx.outputDir, "close_docs", 0, 0);

        // 0. 이전 배치에서 닫히지 않은 문서 정리
        try { while (app.documents.length > 0) app.documents[0].close(SaveOptions.NO); } catch (e) {}

        // 1. 문서 열기
        writeProgress(ctx.outputDir, "open", 0, 0);
        _marker(ctx.outputDir, "01_open");
        var inddFile = File(ctx.inddPath);
        appendDiag(ctx.outputDir, "_open_diagnostics.jsonl", {
            event: "before_open",
            inddPath: ctx.inddPath,
            outputDir: ctx.outputDir,
            chunkMode: !!ctx.chunkMode,
            extractMode: ctx.extractMode || null,
            pdfOnly: !!ctx.pdfOnly
        });
        if (!inddFile.exists) {
            var parentFolder = inddFile.parent;
            if (!parentFolder || !parentFolder.exists)
                throw new Error("상위 폴더에 접근할 수 없습니다: " + (parentFolder ? parentFolder.fsName : ctx.inddPath)
                    + "\nmacOS 설정 > 개인정보 보호 > 파일 및 폴더에서 InDesign의 접근 권한을 확인해주세요.");
            throw new Error("INDD 파일을 찾을 수 없습니다: " + ctx.inddPath);
        }
        appendDiag(ctx.outputDir, "_open_diagnostics.jsonl", {
            event: "before_open_file_verified",
            inddPath: inddFile.fsName,
            parentPath: inddFile.parent ? inddFile.parent.fsName : null,
            fileExists: true
        });
        doc = app.open(inddFile, false);
        _marker(ctx.outputDir, "01a_open_return");
        appendDiag(ctx.outputDir, "_open_diagnostics.jsonl", {
            event: "after_open_return",
            inddPath: inddFile.fsName
        });
        var docTouch = {
            event: "after_open_doc_touch",
            docName: null,
            pageCount: null,
            spreadCount: null,
            linkCount: null,
            saved: null,
            modified: null
        };
        try { docTouch.docName = String(doc.name); } catch (eDocName) { docTouch.docNameError = String(eDocName); }
        _marker(ctx.outputDir, "01b_doc_name");
        try { docTouch.pageCount = doc.pages ? doc.pages.length : null; } catch (eDocPages) { docTouch.pageCountError = String(eDocPages); }
        _marker(ctx.outputDir, "01c_doc_pages");
        try { docTouch.spreadCount = doc.spreads ? doc.spreads.length : null; } catch (eDocSpreads) { docTouch.spreadCountError = String(eDocSpreads); }
        _marker(ctx.outputDir, "01d_doc_spreads");
        try { docTouch.linkCount = doc.links ? doc.links.length : null; } catch (eDocLinks) { docTouch.linkCountError = String(eDocLinks); }
        _marker(ctx.outputDir, "01e_doc_links");
        try { docTouch.saved = !!doc.saved; } catch (eDocSaved) { docTouch.savedError = String(eDocSaved); }
        _marker(ctx.outputDir, "01f_doc_saved");
        try { docTouch.modified = !!doc.modified; } catch (eDocModified) { docTouch.modifiedError = String(eDocModified); }
        _marker(ctx.outputDir, "01g_doc_modified");
        appendDiag(ctx.outputDir, "_open_diagnostics.jsonl", docTouch);
        // 눈금자 원점을 SPREAD로 고정 (geometricBounds가 스프레드 전역 좌표가 되도록)
        try { doc.viewPreferences.rulerOrigin = RulerOrigin.SPREAD_ORIGIN; } catch (e) {}
        _marker(ctx.outputDir, "01h_doc_ruler_origin");
        appendDiag(ctx.outputDir, "_open_diagnostics.jsonl", {
            event: "after_open_ruler_origin"
        });

        // 1.5. 링크 업데이트 (PNG 렌더링 전 — 원본 이미지 연결)
        // SPEC-054 text-only: PNG 렌더가 없으므로 링크 수복(수십 초) 생략.
        if (ctx.contentMode === "text-only") {
            _marker(ctx.outputDir, "01i_fix_links_skipped_textOnly");
        } else {
            writeProgress(ctx.outputDir, "fix_links", 0, 0);
            appendDiag(ctx.outputDir, "_open_diagnostics.jsonl", {
                event: "before_fix_links"
            });
            _fixLinks(doc, ctx.inddPath, ctx.outputDir, "render_links");
            _marker(ctx.outputDir, "01i_fix_links_done");
            appendDiag(ctx.outputDir, "_open_diagnostics.jsonl", {
                event: "after_fix_links"
            });
        }

        appendDiag(ctx.outputDir, "_open_diagnostics.jsonl", {
            event: "before_compute_page_range"
        });
        _computePageRange(doc, ctx);
        _marker(ctx.outputDir, "01j_compute_page_range_done");
        appendDiag(ctx.outputDir, "_open_diagnostics.jsonl", {
            event: "after_compute_page_range",
            startPage: ctx.startPage,
            endPage: ctx.endPage,
            pageCount: ctx.pageCount,
            rangePageCount: ctx.rangePageCount
        });

        if (!ctx.pdfOnly) {
            // SPEC-030 B.2: pre_scan 모드 — IDML export/allPageItems 없이 hash만 빠르게 생성
            if (ctx.extractMode === "pre_scan") {
                _marker(ctx.outputDir, "03b_pageHashes_start");
                try {
                    var _pdFast = buildPageDataFast(doc, ctx.startPage, ctx.endPage);
                    var _phF = File(ctx.outputDir + "/page_hashes.json");
                    _phF.encoding = "UTF-8"; _phF.open("w"); _phF.write(JSON.stringify(_pdFast.hashes)); _phF.close();
                    var _pmF = File(ctx.outputDir + "/page_item_map.json");
                    _pmF.encoding = "UTF-8"; _pmF.open("w"); _pmF.write(JSON.stringify(_pdFast.itemMap)); _pmF.close();
                } catch (eHashF) { $.writeln("[pageHash fast] error: " + eHashF); }
                _marker(ctx.outputDir, "03b_pageHashes_done");
                doc.close(SaveOptions.NO); doc = null;
                writeDone(ctx.outputDir, "ok", null); return;
            } else {
            // 2. IDML 준비 (전체 — API 제한으로 범위 지정 불가)
            // 원본 INDD 옆의 같은 basename IDML을 우선 사용한다. 없으면
            // 원본 폴더에 IDML을 내보낸 뒤 extract/output.idml로 복사한다.
            // chunkMode=true(후속 청크)면 이미 청크1에서 IDML이 생성되었으므로 생략
            _marker(ctx.outputDir, "02_idml_export");
            if (!ctx.chunkMode) {
                var preparedIdml = _prepareExtractionIdml(doc, ctx);
                _marker(ctx.outputDir, "02_idml_" + (preparedIdml.exists ? "ready" : "missing"));
                _marker(ctx.outputDir, "02b_idml_zorder_map");
                _loadIdmlZOrderMap(ctx);
            }

            if (ctx.extractMode === "spread_chunks") {
                _runSpreadChunkExtraction(doc, ctx);
            } else {
            // 2.5. allPageItems 수집 — 청크 범위(startPage..endPage)와
            // 같은 스프레드에서 범위 페이지를 실제로 침범하는 sibling-page 객체만 포함한다.
            // pageIndex는 source anchor일 뿐 visible-page ownership 경계가 아니다.
            // doc.allPageItems는 문서 전체를 로드하므로 26p 문서에서 10p 청크도 전체 메모리 점유 → -609
            _marker(ctx.outputDir, "03_allPageItems");
            var allItems = collectRangePageItems(doc, ctx.startPage, ctx.endPage);
            ctx.rangeTargetPageIndexesBySourceId = collectRangePageItems.lastTargetPageIndexesByItemId || {};
            ctx.deferPagePlaneCacheRestoreUntilAfterPlan = true;

            // SPEC-030 B.2: 페이지 해시 + 아이템 맵 → page_hashes.json / page_item_map.json (캐시 저장용)
            _marker(ctx.outputDir, "03b_pageHashes_start");
            try {
                var _pd = buildPageData(doc, ctx.startPage, ctx.endPage, allItems);
                var _ph = File(ctx.outputDir + "/page_hashes.json");
                _ph.encoding = "UTF-8"; _ph.open("w"); _ph.write(JSON.stringify(_pd.hashes)); _ph.close();
                var _pm = File(ctx.outputDir + "/page_item_map.json");
                _pm.encoding = "UTF-8"; _pm.open("w"); _pm.write(JSON.stringify(_pd.itemMap)); _pm.close();
            } catch (eHash) { $.writeln("[pageHash] error: " + eHash); }
            _marker(ctx.outputDir, "03b_pageHashes_done");
            _marker(ctx.outputDir, "03b1_pagePlaneCacheRestore_start");
            // Requires table-style hide ownership, which is now derived from
            // the extraction plan source index to avoid a duplicate source-index pass.
            _marker(ctx.outputDir, "03b1_pagePlaneCacheRestore_done");

            _runRenderPhases(doc, ctx, allItems);
            allItems = null; try { $.gc(); } catch (e) {}
            }
            } // end else (not pre_scan)
        }

        writeProgress(ctx.outputDir, "pdf", ctx.rangePageCount, ctx.rangePageCount);

        // 4. 링크 업데이트 (PDF 고해상도 이미지용)
        writeProgress(ctx.outputDir, "pdf_fix_links", ctx.rangePageCount, ctx.rangePageCount);
        _fixLinks(doc, ctx.inddPath, ctx.outputDir, "pdf_links");

        // 5. PDF 프리뷰
        _exportPdf(doc, ctx);
        _marker(ctx.outputDir, "13_done");

        doc.close(SaveOptions.NO); doc = null;
        if (ctx.skippedValidationWarnings && ctx.skippedValidationWarnings.length > 0) {
            writeJson(ctx.outputDir + "/validation-skipped-warnings.json", {
                schemaVersion: 1,
                reason: "skipValidation",
                warningCount: ctx.skippedValidationWarnings.length,
                warnings: ctx.skippedValidationWarnings
            });
        }
        if (ctx.validationWarnings && ctx.validationWarnings.length > 0) {
            writeDone(ctx.outputDir, "warning", ctx.validationWarnings.join("\n"));
        } else {
            writeDone(ctx.outputDir, "ok", null);
        }
    } catch (e) {
        var errorMessage = "";
        try { errorMessage = e && e.message ? String(e.message) : String(e); } catch (eMsg) { errorMessage = "unknown error"; }
        try {
            if (e && e.name) errorMessage += " name=" + e.name;
            if (e && e.number) errorMessage += " number=" + e.number;
            if (e && e.line) errorMessage += " line=" + e.line;
            if (e && e.fileName) errorMessage += " file=" + e.fileName;
        } catch (eDetail) {}
        writeDone(ctx.outputDir, "error", errorMessage);
    } finally {
        if (doc) { try { doc.close(SaveOptions.NO); } catch (e2) {} }
        if (savedInteractionLevel !== null) try { app.scriptPreferences.userInteractionLevel = savedInteractionLevel; } catch (eRestoreInteraction) {}
        if (savedEnableRedraw !== null) try { app.scriptPreferences.enableRedraw = savedEnableRedraw; } catch (eRestoreRedraw) {}
        if (savedCheckLinks !== null) try { app.linkingPreferences.checkLinksAtOpen = savedCheckLinks; } catch (eRestoreCheckLinks) {}
        if (savedFindMissing !== null) try { app.linkingPreferences.findMissingLinksAtOpen = savedFindMissing; } catch (eRestoreFindMissing) {}
        try { app.preflightOptions.preflightOn = true; } catch (e) {}
    }
}
