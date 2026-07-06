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

function _repoRootFromConfigPath(configPath) {
    try {
        if (configPath) {
            var f = File(String(configPath));
            if (f && f.exists && f.parent) return f.parent.fsName;
        }
    } catch (eConfigRoot) {}
    try {
        if (typeof $ !== "undefined" && $.fileName) {
            var scriptFile = File($.fileName);
            if (scriptFile && scriptFile.parent && scriptFile.parent.parent) {
                return scriptFile.parent.parent.fsName;
            }
        }
    } catch (eScriptRoot) {}
    return null;
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
        var repoRoot = _repoRootFromConfigPath(ctx.configPath);
        if (!repoRoot) {
            summary.reason = "missing_repo_root";
            writeJson(ctx.outputDir + "/idml-zorder-map-summary.json", summary);
            return null;
        }
        var jarPath = repoRoot + "/converter/target/idml-to-something-1.0.9-cli.jar";
        if (!File(jarPath).exists) {
            summary.reason = "converter_jar_missing";
            summary.jarPath = jarPath;
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

    _marker(ctx.outputDir, "03d_buildExtractionPlan_start");
    ctx.extractionPlan = _buildExtractionPlan(doc, ctx, allItems);
    _marker(ctx.outputDir, "03d_buildExtractionPlan_done");
    _extractionCandidateLookup = _buildExtractionCandidateLookup(ctx.extractionPlan);
    _marker(ctx.outputDir, "03e_buildCandidateLookup_done");
    var extractionItemById = _buildItemById(allItems);
    _marker(ctx.outputDir, "03f_buildItemById_done");
    writeJson(ctx.outputDir + "/extraction-plan.json",
            ctx.writePlannerDiagnostics === true
                    ? ctx.extractionPlan
                    : _slimExtractionPlanForWrite(ctx.extractionPlan));
    _marker(ctx.outputDir, "03g_writeExtractionPlan_done");
    writeJson(ctx.outputDir + "/source-graph.json",
            _buildSourceGraphFromExtractionPlan(ctx.extractionPlan));
    _marker(ctx.outputDir, "03g2_writeSourceGraph_done");
    var planDiagnostics = ctx._extractionPlanDiagnostics || {};
    var plannerBundleDiagnostics = planDiagnostics.plannerBundleDiagnostics
            || _buildPlannerBundles(ctx.extractionPlan.sourceItems, ctx.extractionPlan.candidates);
    _marker(ctx.outputDir, "03i_buildPlannerBundles_done");
    var objectPlanDiagnostics = planDiagnostics.objectPlanDiagnostics
            || _buildObjectPlanDiagnosticsFromPlannerBundles(
                    plannerBundleDiagnostics, ctx.extractionPlan.sourceItems);
    _marker(ctx.outputDir, "03j_buildObjectPlans_done");
    writeJson(ctx.outputDir + "/object-plans.json", objectPlanDiagnostics);
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
    _requireExtractionPass(ctx, "pass.page_backgrounds");
    var pageBackgroundResult = exportPageBackgrounds(doc, ctx.outputDir, ctx.startPage, ctx.endPage,
            _extractionCandidatesForPass(ctx.extractionPlan, "pass.page_backgrounds"),
            ctx.skipRenderPagesMap);
    var renderedFloatingItems = pageBackgroundResult.items || [];
    _addRenderMeta(renderedFloatingItems, null, "pass.page_backgrounds");
    _marker(ctx.outputDir, "04a_pageBackgrounds");

    var editableFrameIds = collectEditableFrameIds(allItems);
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
    var inlineResult = exportInlineObjects(doc, ctx.outputDir, ctx.startPage, ctx.endPage,
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
    try { $.gc(); } catch (e) {}
    // exportInlineObjects의 finally 블록이 true로 복원하지만, 예외 전파 등 만일의 경우를 대비한 안전망.
    try { app.pngExportPreferences.transparentBackground = true; } catch (e) {}
    _marker(ctx.outputDir, "04c_inlineObjects");

    // 2.14. 이미지 프레임 개별 렌더링 (Rectangle/Oval/Polygon에 place된 이미지)
    _marker(ctx.outputDir, "06_imgFrames");
    _requireExtractionPass(ctx, "pass.image_placed_frames");
    _requireExtractionPass(ctx, "pass.image_textless_groups");
    var renderedImageFrames = exportImagePlacedFrames(doc, ctx.outputDir, ctx.startPage, ctx.endPage,
            extractionItemById,
            _pngExtractionCandidatesForPass(ctx.extractionPlan, "pass.image_textless_groups"),
            _pngExtractionCandidatesForPass(ctx.extractionPlan, "pass.image_placed_frames"));
    _addRenderMeta(renderedImageFrames, "page_object", "pass.image_placed_frames");
    for (var ii = 0; ii < renderedImageFrames.length; ii++) renderedFloatingItems.push(renderedImageFrames[ii]);
    try { $.gc(); } catch (e) {}

    // 2.15. 장식 그룹 렌더링 — exportImagePlacedFrames에서 처리된 ID 제외
    var imgRenderedIds = {};
    for (var iri = 0; iri < renderedImageFrames.length; iri++) imgRenderedIds[renderedImageFrames[iri].id] = true;

    _marker(ctx.outputDir, "06b_pageTextlessGroups");
    _requireExtractionPass(ctx, "pass.page_textless_graphic_groups");
    var pageTextlessGroupPngCandidates =
            _pngExtractionCandidatesForPass(ctx.extractionPlan, "pass.page_textless_graphic_groups");
    var pageTextlessGroupResult = exportDecorationGroups(doc, ctx.outputDir, ctx.startPage, ctx.endPage,
            extractionItemById,
            pageTextlessGroupPngCandidates,
            imgRenderedIds,
            ctx.extractionPlan.sourceItems);
    _addRenderMeta(pageTextlessGroupResult.frames, "page_object", "pass.page_textless_graphic_groups");
    for (var ptgi = 0; ptgi < pageTextlessGroupResult.frames.length; ptgi++) {
        renderedFloatingItems.push(pageTextlessGroupResult.frames[ptgi]);
        imgRenderedIds[pageTextlessGroupResult.frames[ptgi].id] = true;
    }
    try { $.gc(); } catch (ePageTextlessGc) {}

    _marker(ctx.outputDir, "07_decoGroups");
    _requireExtractionPass(ctx, "pass.decoration_groups");
    var decoPngCandidates = _pngExtractionCandidatesForPass(ctx.extractionPlan, "pass.decoration_groups");
    var decoNonPngCandidates = _nonPngExtractionCandidatesForPass(ctx.extractionPlan, "pass.decoration_groups");
    try {
        writeJson(ctx.outputDir + "/decoration-non-png-export-skip.json", {
            schemaVersion: 1,
            policy: "POLICY-source-ownership",
            passId: "pass.decoration_groups",
            skippedPngExportCount: decoNonPngCandidates ? decoNonPngCandidates.length : 0,
            candidates: decoNonPngCandidates || [],
            reason: "non_png_materialization_uses_object_plan_source_item"
        });
    } catch (eDecoNonPngStats) {}
    var decoResult  = exportDecorationGroups(doc, ctx.outputDir, ctx.startPage, ctx.endPage,
            extractionItemById,
            decoPngCandidates,
            imgRenderedIds,
            ctx.extractionPlan.sourceItems);
    var decoChildIds = decoResult.childIds || {};
    _addRenderMeta(decoResult.frames, "page_object", "pass.decoration_groups");
    for (var di = 0; di < decoResult.frames.length; di++) renderedFloatingItems.push(decoResult.frames[di]);
    try { $.gc(); } catch (e) {}

    // 2.16. 복합 그래픽 프레임 렌더링
    _marker(ctx.outputDir, "08_complexFrames");
    _requireExtractionPass(ctx, "pass.complex_graphic_frames");
    var renderedGraphicFrames = exportComplexGraphicFrames(doc, ctx.outputDir, ctx.startPage, ctx.endPage,
            extractionItemById, _pngExtractionCandidatesForPass(ctx.extractionPlan, "pass.complex_graphic_frames"));
    _addRenderMeta(renderedGraphicFrames, "page_object", "pass.complex_graphic_frames");
    for (var ci = 0; ci < renderedGraphicFrames.length; ci++) renderedFloatingItems.push(renderedGraphicFrames[ci]);
    try { $.gc(); } catch (e) {}

    // 2.17. 벡터 도형 렌더링
    // 보이는 leaf vector source는 Stage 1 plan이 선언한 source set을
    // 그대로 추출한다. Java/HWPX 단계에서 bounds/style로 다시 그리는
    // native fallback은 source visual 실행 산출물이 아니므로 사용하지 않는다.
    _marker(ctx.outputDir, "09_shapeFrames");
    _requireExtractionPass(ctx, "pass.vector_shape_frames");
    var renderedVectorFrames = exportDecorationGroups(doc, ctx.outputDir, ctx.startPage, ctx.endPage,
            extractionItemById,
            _pngExtractionCandidatesForPass(ctx.extractionPlan, "pass.vector_shape_frames"),
            imgRenderedIds,
            ctx.extractionPlan.sourceItems);
    _addRenderMeta(renderedVectorFrames.frames, "page_object", "pass.vector_shape_frames");
    for (var vsi = 0; vsi < renderedVectorFrames.frames.length; vsi++) renderedFloatingItems.push(renderedVectorFrames.frames[vsi]);
    try { $.gc(); } catch (e) {}

    // 2.18. 마스터 스프레드 그래픽 렌더링
    _marker(ctx.outputDir, "09b_masterGraphics");
    _requireExtractionPass(ctx, "pass.master_page_graphics");
    var renderedMasterGraphics = exportMasterPageGraphics(doc, ctx.outputDir, ctx.startPage, ctx.endPage,
            _pngExtractionCandidatesForPass(ctx.extractionPlan, "pass.master_page_graphics"));
    _addRenderMeta(renderedMasterGraphics, "page_object", "pass.master_page_graphics");
    for (var mgi = 0; mgi < renderedMasterGraphics.length; mgi++) renderedFloatingItems.push(renderedMasterGraphics[mgi]);
    try { $.gc(); } catch (e) {}

    // 2.19. editable TextFrame의 시각 껍데기 렌더링 (fill/stroke만 PNG, 텍스트는 HWPX TF)
    _marker(ctx.outputDir, "09c_editableTFVisualShells");
    _requireExtractionPass(ctx, "pass.editable_textframe_visual_shells");
    var tfShellFrames = exportEditableTextFrameVisualShells(doc, ctx.outputDir, ctx.startPage, ctx.endPage,
            decoChildIds, editableFrameIds, extractionItemById,
            _pngExtractionCandidatesForPass(ctx.extractionPlan, "pass.editable_textframe_visual_shells"),
            allItems);
    _addRenderMeta(tfShellFrames, "page_object", "pass.editable_textframe_visual_shells");
    for (var otfi = 0; otfi < tfShellFrames.length; otfi++) renderedFloatingItems.push(tfShellFrames[otfi]);
    try { $.gc(); } catch (e) {}

    try {
        _marker(ctx.outputDir, "09d_extractionResults");
        _stampExportUnitsOnRenderedItems(ctx, renderedFloatingItems);
        var textlessDiagnostics = [];
        try {
            textlessDiagnostics = textlessDiagnostics.concat(
                    pageTextlessGroupResult.textlessShellDiagnostics || []);
            textlessDiagnostics = textlessDiagnostics.concat(
                    decoResult.textlessShellDiagnostics || []);
        } catch (eTextlessDiagMerge) {
            textlessDiagnostics = decoResult.textlessShellDiagnostics || [];
        }
        var extractionResults = _buildExtractionResults(ctx, renderedFloatingItems,
                renderedImageFrames, renderedGraphicFrames, renderedVectorFrames,
                renderedMasterGraphics, tfShellFrames,
                textlessDiagnostics);
        writeJson(ctx.outputDir + "/extraction-results.json", extractionResults);
        writeJson(ctx.outputDir + "/export-units.json", extractionResults.exportUnits);
        if (extractionResults.validation && extractionResults.validation.status !== "OK") {
            throw new Error("Extraction plan validation failed: " + extractionResults.validation.issueCount + " issue(s)");
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

            deco_group_count: decoResult.frames.length,
            image_frame_count: renderedImageFrames.length,
            complex_frame_count: renderedGraphicFrames.length,
            shape_count: renderedVectorFrames.length,
            master_graphic_count: renderedMasterGraphics.length,
            inline_object_count: _inlineCount
        };
        var _sf = File(ctx.outputDir + "/_extract_stats.json");
        _sf.encoding = "UTF-8"; _sf.open("w"); _sf.write(JSON.stringify(_statsObj)); _sf.close();
    } catch (eStats) {}

    // 3. resolved 속성 수집
    _marker(ctx.outputDir, "10_collectResolved");
    writeProgress(ctx.outputDir, "resolved", 0, ctx.rangePageCount);
    var resolved = collectResolved(doc, ctx.outputDir, ctx.rangePageCount, ctx.startPage, ctx.endPage, editableFrameIds, ctx.skipRenderPagesMap, allItems);
    resolved.renderedTextFrames    = [];
    resolved.renderedPdfFrames     = [];
    resolved.renderedGraphicFrames = renderedGraphicFrames;
    resolved.renderedImageFrames   = renderedImageFrames;
    resolved.renderedFloatingItems = renderedFloatingItems;
    try {
        resolved.textlessShellDiagnostics = []
                .concat(pageTextlessGroupResult.textlessShellDiagnostics || [])
                .concat(decoResult.textlessShellDiagnostics || []);
    } catch (eResolvedTextlessDiagMerge) {
        resolved.textlessShellDiagnostics = decoResult.textlessShellDiagnostics || [];
    }
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
    var savedInteractionLevel = app.scriptPreferences.userInteractionLevel;
    var savedEnableRedraw     = app.scriptPreferences.enableRedraw;
    var savedCheckLinks       = app.linkingPreferences.checkLinksAtOpen;
    var savedFindMissing      = app.linkingPreferences.findMissingLinksAtOpen;

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
        app.scriptPreferences.userInteractionLevel = UserInteractionLevels.NEVER_INTERACT;
        app.scriptPreferences.enableRedraw         = false;
        app.linkingPreferences.checkLinksAtOpen    = false;
        app.linkingPreferences.findMissingLinksAtOpen = false;
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
        if (!inddFile.exists) {
            var parentFolder = inddFile.parent;
            if (!parentFolder || !parentFolder.exists)
                throw new Error("상위 폴더에 접근할 수 없습니다: " + (parentFolder ? parentFolder.fsName : ctx.inddPath)
                    + "\nmacOS 설정 > 개인정보 보호 > 파일 및 폴더에서 InDesign의 접근 권한을 확인해주세요.");
            throw new Error("INDD 파일을 찾을 수 없습니다: " + ctx.inddPath);
        }
        doc = app.open(inddFile, false);
        // 눈금자 원점을 SPREAD로 고정 (geometricBounds가 스프레드 전역 좌표가 되도록)
        try { doc.viewPreferences.rulerOrigin = RulerOrigin.SPREAD_ORIGIN; } catch (e) {}

        // 1.5. 링크 업데이트 (PNG 렌더링 전 — 원본 이미지 연결)
        _fixLinks(doc, ctx.inddPath);

        _computePageRange(doc, ctx);

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
            // 2. IDML 내보내기 (전체 — API 제한으로 범위 지정 불가)
            // chunkMode=true(후속 청크)면 이미 청크1에서 IDML이 생성되었으므로 생략
            _marker(ctx.outputDir, "02_idml_export");
            if (!ctx.chunkMode) {
                writeProgress(ctx.outputDir, "idml", 0, ctx.pageCount);
                doc.exportFile(ExportFormat.INDESIGN_MARKUP, File(ctx.outputDir + "/output.idml"));
                _marker(ctx.outputDir, "02b_idml_zorder_map");
                _loadIdmlZOrderMap(ctx);
            }

            // 2.5. allPageItems 수집 — 청크 범위(startPage..endPage)와
            // 같은 스프레드에서 범위 페이지를 실제로 침범하는 sibling-page 객체만 포함한다.
            // pageIndex는 source anchor일 뿐 visible-page ownership 경계가 아니다.
            // doc.allPageItems는 문서 전체를 로드하므로 26p 문서에서 10p 청크도 전체 메모리 점유 → -609
            _marker(ctx.outputDir, "03_allPageItems");
            var allItems = collectRangePageItems(doc, ctx.startPage, ctx.endPage);

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

            _runRenderPhases(doc, ctx, allItems);
            allItems = null; try { $.gc(); } catch (e) {}
            } // end else (not pre_scan)
        }

        writeProgress(ctx.outputDir, "pdf", ctx.rangePageCount, ctx.rangePageCount);

        // 4. 링크 업데이트 (PDF 고해상도 이미지용)
        _fixLinks(doc, ctx.inddPath);

        // 5. PDF 프리뷰
        _exportPdf(doc, ctx);
        _marker(ctx.outputDir, "13_done");

        doc.close(SaveOptions.NO); doc = null;
        writeDone(ctx.outputDir, "ok", null);
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
        app.scriptPreferences.userInteractionLevel    = savedInteractionLevel;
        app.scriptPreferences.enableRedraw            = savedEnableRedraw;
        app.linkingPreferences.checkLinksAtOpen       = savedCheckLinks;
        app.linkingPreferences.findMissingLinksAtOpen = savedFindMissing;
        try { app.preflightOptions.preflightOn = true; } catch (e) {}
    }
}
