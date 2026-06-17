package kr.dogfoot.hwpxlib.tool.idmlconverter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTToHwpxConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.legacy.IDMLNormalizer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedDataReader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedZOrderNormalizer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;

import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.FontMapper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * IDML -> HWPX 변환 메인 파사드.
 *
 * AST 파이프라인:
 * IDMLLoader -> IDMLNormalizer (4단계) -> ASTDocument -> ASTToHwpxConverter -> HWPX
 *
 * 사용 예:
 * <pre>{@code
 * // 직접 변환
 * ConvertResult result = IDMLToHwpxConverter.convert("input.idml", "output.hwpx",
 *                                                     ConvertOptions.defaults());
 *
 * // IDML -> AST JSON
 * String json = IDMLToHwpxConverter.toJson("input.idml", ConvertOptions.defaults());
 * }</pre>
 *
 * CLI:
 * <pre>
 * java IDMLToHwpxConverter input.idml output.hwpx [startPage] [endPage]
 * </pre>
 */
public class IDMLToHwpxConverter {

    static {
        // SPEC-031: DSL 규칙 엔진 초기화 — 클래스 로딩 시 1회 실행 (thread-safe)
        kr.dogfoot.hwpxlib.tool.idmlconverter.config.ConversionRulesKt.loadConversionRules();
    }

    /**
     * IDML 파일을 HWPX 파일로 변환한다.
     *
     * @param idmlPath IDML 파일 경로
     * @param hwpxPath 출력 HWPX 파일 경로
     * @param options  변환 옵션
     * @return 변환 결과 (경고, 통계)
     */
    public static ConvertResult convert(String idmlPath, String hwpxPath,
                                         ConvertOptions options) throws ConvertException {
        return convert(idmlPath, hwpxPath, options, ProgressReporter.NONE);
    }

    /**
     * IDML 파일을 HWPX 파일로 변환한다 (진행률 보고 포함).
     *
     * @param idmlPath IDML 파일 경로
     * @param hwpxPath 출력 HWPX 파일 경로
     * @param options  변환 옵션
     * @param reporter 진행률 보고기
     * @return 변환 결과 (경고, 통계)
     */
    public static ConvertResult convert(String idmlPath, String hwpxPath,
                                         ConvertOptions options,
                                         ProgressReporter reporter) throws ConvertException {
        ConversionTiming timing = ConversionTiming.start(idmlPath, hwpxPath, options.resolvedJsonPath());
        boolean success = false;
        IDMLDocument idmlDoc = null;
        try {
        // Phase 1: IDML 로드
        reporter.reportProgress(0, 100, "IDML 파일 로딩 중...");
        try (ConversionTiming.Scope ignored = ConversionTiming.time("phase1.idmlLoad")) {
            idmlDoc = IDMLLoader.load(idmlPath);
        }
        try {
            String sourceFileName = new File(idmlPath).getName();

            // 초기 단계 경고 수집 (result 생성 전이므로 리스트에 보관)
            List<String> earlyWarnings = new ArrayList<>();

            // Phase 1.5: Resolved 데이터 조기 로딩 (Stage4 래스터화에서 사용)
            ResolvedData resolvedData = null;
            if (options.resolvedJsonPath() != null) {
                try {
                    reporter.reportProgress(3, 100, "resolved 데이터 로딩 중...");
                    try (ConversionTiming.Scope ignored = ConversionTiming.time("phase1_5.resolved.read")) {
                        resolvedData = ResolvedDataReader.read(options.resolvedJsonPath());
                    }
                    recordResolvedSummary(resolvedData);
                    try (ConversionTiming.Scope ignored = ConversionTiming.time("phase1_5.resolved.indexAndCrop")) {
                        resolvedData.basePath(getResolvedDir(options));
                        ResolvedZOrderNormalizer.applyFromIdml(resolvedData, idmlDoc);
                        applyCropManifest(resolvedData.basePath());
                    }
                } catch (Exception e) {
                    System.err.println("Warning: resolved.json 로드 실패 (무시): " + e.getMessage());
                    earlyWarnings.add("[Resolved] resolved.json 로드 실패: " + e.getMessage());
                }
            }

            // Phase 1.6: resolved 좌표 단위 정규화 (mm/in → pt)
            // InDesign DOM은 문서 측정 단위로 좌표를 반환하므로,
            // IDML 페이지 치수(항상 pt)와 비교하여 스케일 팩터를 계산한다.
            if (resolvedData != null) {
                try (ConversionTiming.Scope ignored = ConversionTiming.time("phase1_6.resolved.normalizeCoordinates")) {
                    List<IDMLPage> idmlPages = idmlDoc.getAllPages();
                    if (!idmlPages.isEmpty()) {
                        resolvedData.normalizeToPoints(idmlPages.get(0).widthPoints());
                    }
                }
            }

            // Phase 1.6b: ExtendScript 렌더 ID 통합 인덱스 빌드
            if (resolvedData != null) {
                try (ConversionTiming.Scope ignored = ConversionTiming.time("phase1_6b.resolved.buildRenderedIdSet")) {
                    resolvedData.buildRenderedIdSet();
                }
            }

            // Phase 2: AST 빌드
            reporter.reportProgress(5, 100, "AST 빌드 중...");
            ASTDocument astDoc;
            boolean isNewPipeline = resolvedData != null && !resolvedData.allRenderedFloatingItems().isEmpty();
            try (ConversionTiming.Scope ignored = ConversionTiming.time("phase2.astBuild.total")) {
                if (isNewPipeline) {
                    astDoc = new kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.ResolvedToASTBuilder(resolvedData, idmlDoc.tempDir(), options.config().pngExportResolution())
                            .debugAst(options.debugAst())
                            .tableQualityGate(options.config().tableQualityGate())
                            .build();
                } else {
                    // 레거시: IDML 기반 4단계 정규화 + Resolved 보강
                    astDoc = IDMLNormalizer.normalize(idmlDoc, options, sourceFileName, resolvedData, reporter);
                    runLegacyPostProcessing(astDoc, resolvedData, options, earlyWarnings, reporter);
                }
            }
            recordAstSummary(astDoc);

            // 정규화 완료 summary
            {
                int tfCount = 0, imgCount = 0, tblCount = 0;
                for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection sec : astDoc.sections()) {
                    for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock blk : sec.blocks()) {
                        if (blk instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock) tfCount++;
                        else if (blk instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure) imgCount++;
                        else if (blk instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable) tblCount++;
                    }
                }
                reporter.reportProgress(9, 100,
                        "정규화 완료 — " + astDoc.sections().size() + "p, "
                                + tfCount + " 텍스트프레임, " + imgCount + " 이미지, " + tblCount + " 테이블");
            }

            // Phase 2.8: 3계층 폰트 매퍼 초기화
            // --font-map 가 명시되지 않아도 jar/CWD 인근의 기본 font-mapping.json 을 자동 로드 →
            // FontStyle("30" 등 가변폰트 weight) 가 정적 fallback 에서 무시되는 문제 회피.
            FontMapper fontMapper = null;
            ConversionConfig config = options.config();
            String autoFontMapPath = options.fontMapPath();
            if (autoFontMapPath == null) {
                String[] candidates = {
                        System.getProperty("user.dir") + "/font-mapping.json",
                        new java.io.File(IDMLToHwpxConverter.class.getProtectionDomain()
                                .getCodeSource().getLocation().getPath()).getParent()
                                + "/../font-mapping.json"
                };
                for (String p : candidates) {
                    if (new java.io.File(p).exists()) { autoFontMapPath = p; break; }
                }
            }
            if (autoFontMapPath != null || !config.fontMappings().isEmpty()) {
                try (ConversionTiming.Scope ignored = ConversionTiming.time("phase2_8.fontMapper.init")) {
                    fontMapper = new FontMapper();
                    // config 기반 초기화 (기본 폰트, 매핑, 메트릭)
                    fontMapper.loadFromConfig(config);
                    // font-mapping.json 로드 (명시 또는 자동 발견)
                    if (autoFontMapPath != null) {
                        fontMapper.loadFontMapping(autoFontMapPath);
                    }
                    if (resolvedData != null) {
                        fontMapper.setIdmlMetrics(resolvedData.fontMetrics(), resolvedData.scaleFactor());
                    }
                }
            }

            // Phase 3: AST -> HWPX 직접 변환
            ConvertResult result;
            try (ConversionTiming.Scope ignored = ConversionTiming.time("phase3.astToHwpx.convert")) {
                result = ASTToHwpxConverter.convert(astDoc, reporter, null, fontMapper, config);
            }

            // 초기 단계 경고 + AST 정규화 경고를 결과에 병합
            for (String w : earlyWarnings) { result.addWarning(w); }
            for (String w : astDoc.warnings()) { result.addWarning(w); }

            // Phase 3.4: Semantic Block Discovery (development default: always write)
            try {
                reporter.reportProgress(92, 100, "Semantic Block 추출 중...");
                try (ConversionTiming.Scope ignored = ConversionTiming.time("phase3_4.semanticBlocks.extract")) {
                    extractSemanticBlocks(astDoc, resolvedData, hwpxPath, result);
                }
            } catch (Exception e) {
                String msg = "[SemanticBlock] 추출 실패 (HWPX 변환은 계속 진행): " + e.getMessage();
                System.err.println(msg);
                result.addWarning(msg);
            }

            // Phase 3.5: 시멘틱 레이어 추출 (SPEC-018 M3, 옵션)
            if (options.extractSemantics()) {
                try {
                    reporter.reportProgress(93, 100, "시멘틱 레이어 추출 중...");
                    try (ConversionTiming.Scope ignored = ConversionTiming.time("phase3_5.semantic.extract")) {
                        extractSemanticLayer(astDoc, hwpxPath, options, result);
                    }
                } catch (Exception e) {
                    String msg = "[Semantic] 시멘틱 추출 실패 (HWPX 변환은 계속 진행): " + e.getMessage();
                    System.err.println(msg);
                    result.addWarning(msg);
                }
            }

            // Phase 4: HWPX 파일 저장
            reporter.reportProgress(95, 100, "HWPX 파일 저장 중...");
            try {
                try (ConversionTiming.Scope ignored = ConversionTiming.time("phase4.hwpx.writeFile")) {
                    HWPXWriter.toFilepath(result.hwpxFile(), hwpxPath);
                }
            } catch (Exception e) {
                throw new ConvertException(ConvertException.Phase.HWPX_GENERATION,
                        "Failed to write HWPX file: " + e.getMessage(), e);
            }

            // 변환 완료 보고
            reporter.reportComplete(result);

            success = true;
            return result;
        } finally {
            if (idmlDoc != null) {
                try (ConversionTiming.Scope ignored = ConversionTiming.time("cleanup.idmlTempDir")) {
                    idmlDoc.cleanup();
                }
            }
        }
        } catch (ConvertException e) {
            timing.failed(e);
            throw e;
        } catch (RuntimeException e) {
            timing.failed(e);
            throw e;
        } finally {
            if (success) {
                timing.succeeded();
            }
            timing.writeAdjacentTo(hwpxPath);
            ConversionTiming.clear();
        }
    }

    private static void recordResolvedSummary(ResolvedData resolvedData) {
        if (resolvedData == null) return;
        ConversionTiming.metric("resolved.pages", resolvedData.pages().size());
        ConversionTiming.metric("resolved.stories", resolvedData.stories().size());
        ConversionTiming.metric("resolved.textFrames", resolvedData.textFrames().size());
        ConversionTiming.metric("resolved.pageItems", resolvedData.pageItems().size());
        ConversionTiming.metric("resolved.renderedFloatingItems", resolvedData.allRenderedFloatingItems().size());
        ConversionTiming.metric("resolved.renderedGraphicFrames", resolvedData.allRenderedGraphicFrames().size());
        ConversionTiming.metric("resolved.renderedImageFrames", resolvedData.allRenderedImageFrames().size());
        ConversionTiming.metric("resolved.renderedPdfFrames", resolvedData.allRenderedPdfFrames().size());
        ConversionTiming.metric("resolved.renderedCandidatesTotal",
                resolvedData.allRenderedFloatingItems().size()
                        + resolvedData.allRenderedGraphicFrames().size()
                        + resolvedData.allRenderedImageFrames().size()
                        + resolvedData.allRenderedPdfFrames().size());
        ConversionTiming.metric("resolved.fontMetrics", resolvedData.fontMetrics().size());
    }

    private static void recordAstSummary(ASTDocument astDoc) {
        if (astDoc == null) return;
        int textFrames = 0;
        int figures = 0;
        int tables = 0;
        int otherBlocks = 0;
        int figureImages = 0;
        long figureImageBytes = 0;
        int inlineImages = 0;
        long inlineImageBytes = 0;
        int textFrameImageFills = 0;
        long textFrameImageFillBytes = 0;
        java.util.Set<String> visibleImageSourceIds = new java.util.HashSet<>();
        for (ASTSection sec : astDoc.sections()) {
            for (ASTBlock blk : sec.blocks()) {
                if (blk instanceof ASTTextFrameBlock) {
                    textFrames++;
                    ASTTextFrameBlock tf = (ASTTextFrameBlock) blk;
                    if (tf.imageFillData() != null && tf.imageFillData().length > 0) {
                        textFrameImageFills++;
                        textFrameImageFillBytes += tf.imageFillData().length;
                        if (tf.sourceId() != null) visibleImageSourceIds.add(tf.sourceId());
                    }
                    InlineImageStats stats = collectInlineImageStats(tf.paragraphs());
                    inlineImages += stats.count;
                    inlineImageBytes += stats.bytes;
                    visibleImageSourceIds.addAll(stats.sourceIds);
                } else if (blk instanceof ASTFigure) {
                    figures++;
                    ASTFigure fig = (ASTFigure) blk;
                    if (fig.imageData() != null && fig.imageData().length > 0) {
                        figureImages++;
                        figureImageBytes += fig.imageData().length;
                        if (fig.sourceId() != null) visibleImageSourceIds.add(fig.sourceId());
                    }
                } else if (blk instanceof ASTTable) {
                    tables++;
                    InlineImageStats stats = collectTableImageStats((ASTTable) blk);
                    inlineImages += stats.count;
                    inlineImageBytes += stats.bytes;
                    visibleImageSourceIds.addAll(stats.sourceIds);
                } else {
                    otherBlocks++;
                }
            }
        }
        ConversionTiming.metric("ast.sections", astDoc.sections().size());
        ConversionTiming.metric("ast.backgrounds", astDoc.backgrounds().size());
        ConversionTiming.metric("ast.fonts", astDoc.fonts().size());
        ConversionTiming.metric("ast.paragraphStyles", astDoc.paragraphStyles().size());
        ConversionTiming.metric("ast.characterStyles", astDoc.characterStyles().size());
        ConversionTiming.metric("ast.textFrameBlocks", textFrames);
        ConversionTiming.metric("ast.figures", figures);
        ConversionTiming.metric("ast.tables", tables);
        ConversionTiming.metric("ast.otherBlocks", otherBlocks);
        ConversionTiming.metric("ast.warnings", astDoc.warnings().size());
        ConversionTiming.metric("ast.figureImages", figureImages);
        ConversionTiming.metric("ast.figureImageBytes", figureImageBytes);
        ConversionTiming.metric("ast.inlineImages", inlineImages);
        ConversionTiming.metric("ast.inlineImageBytes", inlineImageBytes);
        ConversionTiming.metric("ast.textFrameImageFills", textFrameImageFills);
        ConversionTiming.metric("ast.textFrameImageFillBytes", textFrameImageFillBytes);
        ConversionTiming.metric("ast.visibleImageSourceIds", visibleImageSourceIds.size());
        // Keep this estimate local to AST creation: it compares source-level visible image
        // outputs after planning/legacy bridges have run, without changing placement.
        ConversionTiming.metric("ast.visibleImageOutputs",
                figureImages + inlineImages + textFrameImageFills);
    }

    private static InlineImageStats collectInlineImageStats(java.util.List<ASTParagraph> paragraphs) {
        InlineImageStats stats = new InlineImageStats();
        if (paragraphs == null) return stats;
        for (ASTParagraph paragraph : paragraphs) {
            if (paragraph == null || paragraph.items() == null) continue;
            for (ASTInlineItem item : paragraph.items()) {
                if (item instanceof ASTInlineObject) {
                    collectInlineObjectImageStats((ASTInlineObject) item, stats);
                }
            }
            if (paragraph.inlineTable() != null) {
                stats.add(collectTableImageStats(paragraph.inlineTable()));
            }
        }
        return stats;
    }

    private static InlineImageStats collectTableImageStats(ASTTable table) {
        InlineImageStats stats = new InlineImageStats();
        if (table == null || table.rows() == null) return stats;
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell != null) {
                    stats.add(collectInlineImageStats(cell.paragraphs()));
                }
            }
        }
        return stats;
    }

    private static void collectInlineObjectImageStats(ASTInlineObject obj, InlineImageStats stats) {
        if (obj == null || stats == null) return;
        if (obj.imageData() != null && obj.imageData().length > 0) {
            stats.count++;
            stats.bytes += obj.imageData().length;
            if (obj.sourceId() != null) stats.sourceIds.add(obj.sourceId());
        }
        if (obj.imageFillData() != null && obj.imageFillData().length > 0) {
            stats.count++;
            stats.bytes += obj.imageFillData().length;
            if (obj.sourceId() != null) stats.sourceIds.add(obj.sourceId());
        }
        if (obj.overlayFrames() != null) {
            for (ASTInlineObject overlay : obj.overlayFrames()) {
                collectInlineObjectImageStats(overlay, stats);
            }
        }
        stats.add(collectInlineImageStats(obj.paragraphs()));
        if (obj.inlineTables() != null) {
            for (ASTTable table : obj.inlineTables()) {
                stats.add(collectTableImageStats(table));
            }
        }
    }

    private static final class InlineImageStats {
        int count;
        long bytes;
        final java.util.Set<String> sourceIds = new java.util.HashSet<>();

        void add(InlineImageStats other) {
            if (other == null) return;
            this.count += other.count;
            this.bytes += other.bytes;
            this.sourceIds.addAll(other.sourceIds);
        }
    }

    /**
     * IDML 파일로부터 ASTDocument만 빌드한다 (HWPX 변환 없음).
     * Markdown 내보내기 등 non-HWPX 출력 경로에서 사용.
     */
    public static ASTDocument buildAst(String idmlPath, ConvertOptions options,
                                        ProgressReporter reporter) throws ConvertException {
        IDMLDocument idmlDoc = IDMLLoader.load(idmlPath);
        try {
            String sourceFileName = new File(idmlPath).getName();

            ResolvedData resolvedData = null;
            if (options.resolvedJsonPath() != null) {
                try {
                    resolvedData = ResolvedDataReader.read(options.resolvedJsonPath());
                    resolvedData.basePath(getResolvedDir(options));
                    ResolvedZOrderNormalizer.applyFromIdml(resolvedData, idmlDoc);
                    applyCropManifest(resolvedData.basePath());
                } catch (Exception e) {
                    System.err.println("Warning: resolved.json 로드 실패 (무시): " + e.getMessage());
                }
            }

            if (resolvedData != null) {
                List<IDMLPage> idmlPages = idmlDoc.getAllPages();
                if (!idmlPages.isEmpty()) {
                    resolvedData.normalizeToPoints(idmlPages.get(0).widthPoints());
                }
                resolvedData.buildRenderedIdSet();
            }

            boolean isNewPipeline = resolvedData != null && !resolvedData.allRenderedFloatingItems().isEmpty();
            ASTDocument astDoc;
            if (isNewPipeline) {
                astDoc = new kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.ResolvedToASTBuilder(
                        resolvedData, idmlDoc.tempDir(), options.config().pngExportResolution())
                        .debugAst(options.debugAst())
                        .tableQualityGate(options.config().tableQualityGate())
                        .build();
            } else {
                List<String> earlyWarnings = new ArrayList<>();
                astDoc = IDMLNormalizer.normalize(idmlDoc, options, sourceFileName, resolvedData, reporter);
                runLegacyPostProcessing(astDoc, resolvedData, options, earlyWarnings, reporter);
            }

            return astDoc;
        } finally {
            idmlDoc.cleanup();
        }
    }

    /**
     * IDML 파일을 HWPXFile 객체로 변환한다 (파일 저장 없이).
     *
     * @param idmlPath IDML 파일 경로
     * @param options  변환 옵션
     * @return HWPXFile 객체
     */
    public static HWPXFile convertToHwpxFile(String idmlPath,
                                              ConvertOptions options) throws ConvertException {
        IDMLDocument idmlDoc = IDMLLoader.load(idmlPath);
        try {
            String sourceFileName = new File(idmlPath).getName();
            ASTDocument astDoc = IDMLNormalizer.normalize(idmlDoc, options, sourceFileName);
            ConvertResult result = ASTToHwpxConverter.convert(astDoc);
            return result.hwpxFile();
        } finally {
            idmlDoc.cleanup();
        }
    }

    /**
     * IDML 파일을 AST JSON 문자열로 변환한다.
     *
     * @param idmlPath IDML 파일 경로
     * @param options  변환 옵션
     * @return JSON 문자열
     */
    public static String toJson(String idmlPath, ConvertOptions options) throws ConvertException {
        IDMLDocument idmlDoc = IDMLLoader.load(idmlPath);
        try {
            String sourceFileName = new File(idmlPath).getName();
            ASTDocument astDoc = IDMLNormalizer.normalize(idmlDoc, options, sourceFileName);
            return ASTSerializer.toJson(astDoc);
        } finally {
            idmlDoc.cleanup();
        }
    }


    private static void runLegacyPostProcessing(
            ASTDocument astDoc, ResolvedData resolvedData, ConvertOptions options,
            List<String> earlyWarnings, ProgressReporter reporter) {
        // Phase 2.5: Resolved 텍스트/스타일 보강
        if (resolvedData != null) {
            try {
                reporter.reportProgress(8, 100, "resolved 데이터 병합 중...");
                kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedMerger.enrich(astDoc, resolvedData);
                kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedFrameDistributor.distribute(astDoc, resolvedData);
            } catch (Exception e) {
                System.err.println("Warning: resolved.json 병합 실패 (무시): " + e.getMessage());
                earlyWarnings.add("[Resolved] resolved.json 병합 실패: " + e.getMessage());
            }
            // Phase 2.6: Resolved 오버레이 좌표 보강
            try {
                kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedOverlayEnricher.enrich(astDoc, resolvedData);
            } catch (Exception e) {
                System.err.println("Warning: resolved overlay 보강 실패 (무시): " + e.getMessage());
                earlyWarnings.add("[Resolved] overlay 보강 실패: " + e.getMessage());
            }
        }
        // SPEC-035: source ownership/placement is decided by ObjectPlan only.
        // Legacy floating-image merge mutates text-frame geometry after planning,
        // so it is intentionally disabled.
        // Phase 2.10: orphan 렌더 그래픽 주입
        if (resolvedData != null) {
            injectOrphanRenderedGraphics(astDoc, resolvedData, options);
        }
    }

    private static void collectInlineSourceIds(
            java.util.List<kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph> paragraphs,
            java.util.Set<String> usedSourceIds) {
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph para : paragraphs) {
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem item : para.items()) {
                if (item.itemType() == kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem.ItemType.INLINE_OBJECT) {
                    kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject obj =
                            (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject) item;
                    if (obj.sourceId() != null) {
                        usedSourceIds.add(obj.sourceId());
                    }
                }
            }
        }
    }

    private static java.util.Set<Integer> collectRenderedCoverageForUsedSources(
            ResolvedData resolvedData, java.util.Set<String> usedSourceIds) {
        java.util.Set<Integer> coveredIds = new java.util.HashSet<>();
        if (resolvedData == null || usedSourceIds == null || usedSourceIds.isEmpty()) {
            return coveredIds;
        }

        java.util.List<RenderedGroup> groups = new java.util.ArrayList<>();
        groups.addAll(resolvedData.allRenderedFloatingItems());
        groups.addAll(resolvedData.allRenderedGraphicFrames());
        groups.addAll(resolvedData.allRenderedImageFrames());
        groups.addAll(resolvedData.allRenderedPdfFrames());

        boolean changed;
        do {
            int before = coveredIds.size();
            for (RenderedGroup rg : groups) {
                if (rg == null) continue;
                if (isRenderedSourceUsed(rg, usedSourceIds) || coveredIds.contains(rg.id())) {
                    addRenderedCoverageIds(rg, coveredIds);
                }
            }
            changed = coveredIds.size() != before;
        } while (changed);
        return coveredIds;
    }

    private static boolean isRenderedSourceUsed(
            RenderedGroup rg, java.util.Set<String> usedSourceIds) {
        if (rg == null || usedSourceIds == null) return false;
        return usedSourceIds.contains("u" + Integer.toHexString(rg.id()))
                || usedSourceIds.contains("page_obj_" + rg.id());
    }

    private static void addRenderedCoverageIds(
            RenderedGroup rg, java.util.Set<Integer> coveredIds) {
        if (rg == null || coveredIds == null) return;
        coveredIds.add(rg.id());
        addAllIds(rg.sourceObjectIds(), coveredIds);
        addAllIds(rg.childIds(), coveredIds);
        addAllIds(rg.childImageIds(), coveredIds);
        addAllIds(rg.visualOnlyChildIds(), coveredIds);
        addAllIds(rg.tfInlineVisualIds(), coveredIds);
    }

    private static void addAllIds(int[] ids, java.util.Set<Integer> target) {
        if (ids == null || target == null) return;
        for (int id : ids) {
            target.add(id);
        }
    }

    private static int adjustRenderedBackgroundShellZOrder(
            RenderedGroup rg, ASTSection section, ASTFigure figure, int currentZ) {
        if (!isRenderedBackgroundShellCandidate(rg)
                || section == null || figure == null
                || figure.width() <= 0 || figure.height() <= 0) {
            return currentZ;
        }
        int minOverlapZ = Integer.MAX_VALUE;
        for (ASTBlock block : section.blocks()) {
            if (block == null || block == figure) continue;
            long[] b = blockBounds(block);
            if (b == null) continue;
            long overlap = overlapArea(
                    figure.x(), figure.y(), figure.width(), figure.height(),
                    b[0], b[1], b[2], b[3]);
            if (overlap <= 0) continue;
            int z = blockZOrder(block);
            if (z <= 0) continue;
            if (z < minOverlapZ) minOverlapZ = z;
        }
        if (minOverlapZ == Integer.MAX_VALUE) return currentZ;
        return Math.max(0, Math.min(currentZ, minOverlapZ - 1));
    }

    private static boolean isRenderedBackgroundShellCandidate(RenderedGroup rg) {
        if (rg == null) return false;
        String reason = rg.reason();
        if (reason == null) return false;
        return "decoration_group".equals(reason)
                || reason.contains("complex_graphic_text_hidden")
                || reason.contains("mixed_group_text_hidden")
                || reason.contains("image_group_text_hidden")
                || reason.contains("visual_label_text_hidden_shell")
                || reason.contains("editable_composite_text_hidden_shell")
                || reason.contains("textframe_visual_shell");
    }

    private static long[] blockBounds(ASTBlock block) {
        if (block instanceof ASTTextFrameBlock) {
            ASTTextFrameBlock tf = (ASTTextFrameBlock) block;
            return new long[] { tf.x(), tf.y(), tf.effectiveWidth(), tf.height() };
        }
        if (block instanceof ASTFigure) {
            ASTFigure fig = (ASTFigure) block;
            return new long[] { fig.x(), fig.y(), fig.width(), fig.height() };
        }
        if (block instanceof ASTTable) {
            ASTTable table = (ASTTable) block;
            return new long[] { table.x(), table.y(), table.width(), table.height() };
        }
        return null;
    }

    private static int blockZOrder(ASTBlock block) {
        if (block instanceof ASTTextFrameBlock) return ((ASTTextFrameBlock) block).zOrder();
        if (block instanceof ASTFigure) return ((ASTFigure) block).zOrder();
        if (block instanceof ASTTable) return ((ASTTable) block).zOrder();
        return 0;
    }

    private static long overlapArea(
            long ax, long ay, long aw, long ah,
            long bx, long by, long bw, long bh) {
        long left = Math.max(ax, bx);
        long top = Math.max(ay, by);
        long right = Math.min(ax + aw, bx + bw);
        long bottom = Math.min(ay + ah, by + bh);
        if (right <= left || bottom <= top) return 0;
        return (right - left) * (bottom - top);
    }

    private static boolean isRenderedCoveredByUsedSource(
            RenderedGroup rg, java.util.Set<Integer> coveredIds) {
        if (rg == null || coveredIds == null || coveredIds.isEmpty()) return false;
        return coveredIds.contains(rg.id());
    }

    /**
     * VectorShape로 등록되지 않은 렌더 그래픽 프레임을 AST에 주입한다.
     * 깊이 중첩된 IDML 구조(TextFrame 내부 Oval 등)는 VectorShape 풀에 등록되지 않아
     * ASTFigureBuilder에서 처리되지 못한다. 이들 중 렌더링된 PNG가 있으면 직접 주입한다.
     */
    static void injectOrphanRenderedGraphics(
            ASTDocument astDoc, ResolvedData resolvedData, ConvertOptions options) {
        String resolvedDir = getResolvedDir(options);
        if (resolvedDir == null) return;

        // 이미 AST에 사용된 rendered graphic sourceId 수집
        java.util.Set<String> usedSourceIds = new java.util.HashSet<>();
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection sec : astDoc.sections()) {
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock blk : sec.blocks()) {
                if (blk instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure
                        && blk.sourceId() != null) {
                    usedSourceIds.add(blk.sourceId());
                }
                // TextFrameBlock 내 인라인 객체 sourceId 수집
                if (blk instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock) {
                    kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock tfb =
                            (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock) blk;
                    collectInlineSourceIds(tfb.paragraphs(), usedSourceIds);
                }
                // 테이블 셀 내 인라인 객체 sourceId 수집
                if (blk instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable) {
                    kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable tbl =
                            (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable) blk;
                    for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTableRow row : tbl.rows()) {
                        for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTableCell cell : row.cells()) {
                            collectInlineSourceIds(cell.paragraphs(), usedSourceIds);
                        }
                    }
                }
            }
        }
        java.util.Set<Integer> usedRenderedCoverageIds =
                collectRenderedCoverageForUsedSources(resolvedData, usedSourceIds);

        // 페이지 bounds 캐시 (pageIndex → resolvedPage)
        List<kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection> sections = astDoc.sections();

        // 페이지별 기존 ASTFigure + ASTTable bounds 수집 (orphan 중복 주입 방지용)
        Map<Integer, java.util.List<long[]>> pageFigureBounds = new java.util.HashMap<>();
        for (int si = 0; si < sections.size(); si++) {
            java.util.List<long[]> bounds = new java.util.ArrayList<>();
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock blk : sections.get(si).blocks()) {
                if (blk instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure) {
                    kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure fig =
                            (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure) blk;
                    bounds.add(new long[]{fig.x(), fig.y(), fig.x() + fig.width(), fig.y() + fig.height()});
                }
                // 테이블 영역도 수집: 테이블과 동일 위치의 그래픽 프레임 중복 주입 방지
                if (blk instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable) {
                    kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable tbl =
                            (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable) blk;
                    bounds.add(new long[]{tbl.x(), tbl.y(), tbl.x() + tbl.width(), tbl.y() + tbl.height()});
                }
            }
            pageFigureBounds.put(si, bounds);
        }

        // 배지 그룹을 OrphanGraphic 대상에 추가
        // (인라인 처리된 배지는 isConsumedRenderedGraphic으로 건너뜀)
        java.util.List<RenderedGroup> orphanTargets = new java.util.ArrayList<>(
                resolvedData.allRenderedGraphicFrames());
        // PDF 배치 프레임도 orphan 대상에 추가 (IDML에서 PDF 링크를 직접 변환하지 못하므로)
        orphanTargets.addAll(resolvedData.allRenderedPdfFrames());

        // 부모-자식 관계: childIds에 포함된 ID는 부모 렌더링에 이미 포함되므로 건너뜀
        // 단, 부모 자체가 렌더 가능한 파일을 가진 경우에만 (부모가 필터되면 자식 허용)
        java.util.Set<Integer> orphanChildIdSet = new java.util.HashSet<>();
        for (RenderedGroup rg : orphanTargets) {
            if (rg.childIds() != null && rg.file() != null) {
                for (int cid : rg.childIds()) {
                    orphanChildIdSet.add(cid);
                }
            }
        }

        Map<Integer, Integer> pageOrphanZCounter = new java.util.HashMap<>();
        // 페이지별 주입된 orphan bounds 추적 (자식 포함 관계 중복 방지)
        Map<Integer, java.util.List<long[]>> pageOrphanBounds = new java.util.HashMap<>();
        // 주입된 orphan의 childIds 집합 (배지 중복 방지)
        java.util.Set<Integer> placedOrphanChildIds = new java.util.HashSet<>();
        for (RenderedGroup rg : orphanTargets) {
            if (rg.file() == null || rg.bounds() == null) continue;

            // 부모 그룹의 childIds에 포함된 자식은 건너뜀 (부모가 전체 렌더링)
            if (orphanChildIdSet.contains(rg.id())) continue;

            // DOM ID → IDML hex ID 변환하여 이미 사용된 것인지 확인
            String idmlHexId = "u" + Integer.toHexString(rg.id());
            if (usedSourceIds.contains(idmlHexId)) continue;
            // BackgroundInjector가 page_obj_N 형식으로 이미 배치한 항목 스킵
            if (usedSourceIds.contains("page_obj_" + rg.id())) continue;
            if (isRenderedCoveredByUsedSource(rg, usedRenderedCoverageIds)) continue;

            // vectorShapes 처리 대상 및 클리핑 자식 건너뜀 (중복 주입 방지)
            if (astDoc.orphanExcludeIds().contains(idmlHexId)) continue;

            // 인라인 그래픽으로 이미 처리된 deco 건너뜀 (중복 주입 방지)
            if (resolvedData.isConsumedRenderedGraphic(String.valueOf(rg.id()))) continue;

            int pageIdx = rg.pageIndex();
            if (pageIdx < 0 || pageIdx >= sections.size()) continue;

            kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage resolvedPage =
                    resolvedData.getPage(pageIdx);

            // PNG 로드
            java.io.File pngFile = new java.io.File(resolvedDir, rg.file());
            if (!pngFile.exists()) {
                continue;
            }

            try {
                byte[] data = java.nio.file.Files.readAllBytes(pngFile.toPath());
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(pngFile);
                if (img == null) continue;

                // 위치/크기 계산 (ExtendScript에서 이미 pageBounds를 뺀 페이지 상대 좌표)
                double[] bounds = rg.bounds();
                long figX, figY, figW, figH;
                figX = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                        .pointsToHwpunits(bounds[1]);
                figY = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                        .pointsToHwpunits(bounds[0]);
                figW = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                        .pointsToHwpunits(bounds[3] - bounds[1]);
                figH = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                        .pointsToHwpunits(bounds[2] - bounds[0]);
                // PNG 비율로 높이 보정
                if (img.getWidth() > 0) {
                    long geoBottom = figY + figH;
                    figH = Math.round(figW * ((double) img.getHeight() / img.getWidth()));
                    // 음수 Y (페이지 위 확장) 시 바닥 가장자리 기하학적 위치 유지
                    if (figY < 0) {
                        figY = geoBottom - figH;
                    }
                }

                // 기존 ASTFigure/ASTTable과 겹치는 도형 건너뜀 (중복 방지)
                // 양방향 겹침: orphan과 기존 figure 모두 50% 이상 겹쳐야 중복으로 판단
                // (배경 도형은 기존 figure보다 훨씬 크므로 단방향이면 잘못 필터됨)
                boolean overlapsExisting = false;
                long figArea = figW * figH;
                java.util.List<long[]> existingBounds = pageFigureBounds.get(pageIdx);
                if (existingBounds != null && figArea > 0) {
                    for (long[] eb : existingBounds) {
                        long overlapLeft = Math.max(figX, eb[0]);
                        long overlapTop = Math.max(figY, eb[1]);
                        long overlapRight = Math.min(figX + figW, eb[2]);
                        long overlapBottom = Math.min(figY + figH, eb[3]);
                        if (overlapLeft < overlapRight && overlapTop < overlapBottom) {
                            long overlapArea = (overlapRight - overlapLeft) * (overlapBottom - overlapTop);
                            double orphanRatio = (double) overlapArea / figArea;
                            long ebArea = (eb[2] - eb[0]) * (eb[3] - eb[1]);
                            double existingRatio = ebArea > 0 ? (double) overlapArea / ebArea : 0;
                            // 양쪽 모두 50% 이상 겹쳐야 중복 (같은 크기/위치의 객체)
                            // 단, 한쪽이 다른 쪽을 거의 완전히 포함(>90%)하면
                            // 배경/컨테이너 관계이므로 중복 아님
                            boolean isContainment = (existingRatio > 0.9 && orphanRatio < 0.9)
                                    || (orphanRatio > 0.9 && existingRatio < 0.9);
                            if (orphanRatio > 0.7 && existingRatio > 0.7 && !isContainment) {
                                overlapsExisting = true;
                                break;
                            }
                        }
                    }
                }
                if (overlapsExisting) continue;

                // 같은 페이지에 이미 주입된 orphan에 포함되는 자식 건너뜀
                // (부모 그룹의 렌더 이미지가 자식을 포함하므로 자식은 중복)
                boolean containedByOrphan = false;
                java.util.List<long[]> existingOrphans = pageOrphanBounds.get(pageIdx);
                if (existingOrphans != null && figArea > 0) {
                    for (long[] ob : existingOrphans) {
                        long obW = ob[2] - ob[0];
                        long obH = ob[3] - ob[1];
                        long obArea = obW * obH;
                        // ob가 현재 orphan을 80% 이상 포함하면 건너뜀
                        long oLeft = Math.max(figX, ob[0]);
                        long oTop = Math.max(figY, ob[1]);
                        long oRight = Math.min(figX + figW, ob[2]);
                        long oBottom = Math.min(figY + figH, ob[3]);
                        if (oLeft < oRight && oTop < oBottom) {
                            long oArea = (oRight - oLeft) * (oBottom - oTop);
                            double containRatio = (double) oArea / figArea;
                            if (containRatio > 0.8) {
                                // 크기가 비슷하면 동일 객체 중복 → 건너뜀
                                // 크기가 크게 다르면 배경-컨텐츠 레이어 관계 → 유지
                                long minArea = Math.min(figArea, obArea);
                                long maxArea = Math.max(figArea, obArea);
                                double sizeRatio = maxArea > 0 ? (double) minArea / maxArea : 0;
                                if (sizeRatio > 0.9) {
                                    // 면적 비 90% 이상 → 거의 같은 크기 → 중복
                                    containedByOrphan = true;
                                    break;
                                }
                                // 면적 비 90% 미만 → 배경 위 컨텐츠 레이어 → 유지
                            }
                        }
                    }
                }
                if (containedByOrphan) continue;

                // 페이지 밖 음수 좌표 → crop fraction 설정
                // HwpxImageBuilder가 hasCrop일 때 x<0, y<0을 자동 클램핑하므로
                // 원래 크기를 유지하고 fraction만 설정
                double cropLeft = 0, cropTop = 0;
                if (figX < 0) {
                    cropLeft = (double) (-figX) / figW;
                }
                if (figY < 0) {
                    cropTop = (double) (-figY) / figH;
                }

                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure fig =
                        new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure();
                fig.x(figX);
                fig.y(figY);
                fig.width(figW);
                fig.height(figH);
                fig.imageData(data);
                fig.imageFormat("png");
                fig.pixelWidth(img.getWidth());
                fig.pixelHeight(img.getHeight());
                fig.sourceId(idmlHexId);
                if (cropLeft > 0 || cropTop > 0) {
                    fig.cropLeftFraction(cropLeft);
                    fig.cropTopFraction(cropTop);
                }

                // z-order: IDML 스프레드 파싱 시 할당된 원본 z-order 사용
                // (AST 텍스트 프레임과 동일한 스케일 → inFrontBlocks 정렬에서 올바른 스태킹)
                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection section = sections.get(pageIdx);
                java.util.Map<String, Integer> zMap = astDoc.idmlZOrders();
                Integer idmlZ = rg.zOrderKnown() ? rg.zOrder() : null;
                if (idmlZ == null) {
                    idmlZ = (zMap != null) ? zMap.get(idmlHexId) : null;
                }
                if (idmlZ == null) {
                    // 폴백: 순차 카운터
                    Integer orphanZ = pageOrphanZCounter.get(pageIdx);
                    if (orphanZ == null) orphanZ = 0;
                    idmlZ = orphanZ;
                    pageOrphanZCounter.put(pageIdx, orphanZ + 1);
                }
                fig.zOrder(idmlZ);
                // renderedGraphicFrame(벡터 도형)은 텍스트/배지보다 앞에 표시
                // IDML z-order 없으면 높은 값으로 설정하여 배경 위에 배치
                if (idmlZ <= 0 && resolvedData.getRenderedGraphicFrameByIdmlId(idmlHexId) != null) {
                    fig.zOrder(9000 + pageOrphanZCounter.getOrDefault(pageIdx, 0));
                }

                // 배경 vs 전경 판단: 이미지 면적이 페이지의 50% 이상이면 배경(BEHIND_TEXT)
                boolean isBackground = false;
                kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage bgPage =
                        (resolvedData != null) ? resolvedData.getPage(pageIdx) : null;
                if (bgPage != null && bgPage.bounds() != null) {
                    double[] pb = bgPage.bounds();
                    double pageArea = (pb[2] - pb[0]) * (pb[3] - pb[1]);
                    double orphanArea = (double) figW * figH / (100.0 * 100.0); // hwpunit → pt
                    if (pageArea > 0 && orphanArea / pageArea > 0.5) {
                        isBackground = true;
                    }
                }
                boolean demoteToBehindText = false;
                if (isBackground) {
                    fig.zOrder(0);  // 배경은 최하위 z-order
                } else {
                    int beforeAdjustZ = fig.zOrder();
                    int adjustedZ = adjustRenderedBackgroundShellZOrder(rg, section, fig, beforeAdjustZ);
                    fig.zOrder(adjustedZ);
                    demoteToBehindText = adjustedZ < beforeAdjustZ;
                }
                fig.fromGroup(!isBackground && !demoteToBehindText);  // 배경이면 BEHIND_TEXT, 아니면 IN_FRONT_OF_TEXT

                section.addBlock(fig);

                // 주입된 orphan bounds 추적
                java.util.List<long[]> orphanList = pageOrphanBounds.get(pageIdx);
                if (orphanList == null) {
                    orphanList = new java.util.ArrayList<>();
                    pageOrphanBounds.put(pageIdx, orphanList);
                }
                orphanList.add(new long[]{figX, figY, figX + figW, figY + figH});
                // 주입된 orphan의 자식 ID 기록 (자식 배지 중복 방지)
                if (rg.childIds() != null) {
                    for (int cid : rg.childIds()) {
                        placedOrphanChildIds.add(cid);
                    }
                }

                // === 스프레드 걸침 감지: 그래픽이 인접 페이지까지 확장되면 추가 배치 ===
                if (resolvedPage != null && resolvedPage.bounds() != null) {
                    double pageRight = resolvedPage.bounds()[3];  // 현재 페이지 오른쪽 끝 (spread coords, pt)
                    double graphicRight = bounds[3];              // 그래픽 오른쪽 끝
                    int adjPageIdx = pageIdx + 1;
                    // 그래픽이 현재 페이지를 넘어서고 다음 페이지가 존재하면
                    if (graphicRight > pageRight + 10 && adjPageIdx < sections.size()) {
                        kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage adjPage =
                                resolvedData.getPage(adjPageIdx);
                        if (adjPage != null && adjPage.bounds() != null) {
                            double adjPageLeft = adjPage.bounds()[1];
                            double graphicLeft = bounds[1];
                            double totalW = graphicRight - graphicLeft;  // 전체 그래픽 폭
                            double totalH = bounds[2] - bounds[0];
                            if (totalW > 0 && totalH > 0) {
                                // 인접 페이지 상대 좌표
                                double adjRelX = graphicLeft - adjPageLeft;  // 음수 (페이지 왼쪽 바깥)
                                double adjRelY = bounds[0] - adjPage.bounds()[0];
                                long adjFigX = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                                        .pointsToHwpunits(adjRelX);
                                long adjFigY = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                                        .pointsToHwpunits(adjRelY);
                                long adjFigW = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                                        .pointsToHwpunits(totalW);
                                long adjFigH = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                                        .pointsToHwpunits(totalH);
                                // PNG 비율 보정
                                if (img.getWidth() > 0) {
                                    adjFigH = Math.round(adjFigW * ((double) img.getHeight() / img.getWidth()));
                                }
                                // crop fraction (왼쪽 부분 = 이전 페이지 영역)
                                double adjCropLeft = 0, adjCropTop = 0;
                                if (adjFigX < 0) {
                                    adjCropLeft = (double) (-adjFigX) / adjFigW;
                                }
                                if (adjFigY < 0) {
                                    adjCropTop = (double) (-adjFigY) / adjFigH;
                                }

                                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure adjFig =
                                        new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure();
                                adjFig.x(adjFigX);
                                adjFig.y(adjFigY);
                                adjFig.width(adjFigW);
                                adjFig.height(adjFigH);
                                adjFig.imageData(data);
                                adjFig.imageFormat("png");
                                adjFig.pixelWidth(img.getWidth());
                                adjFig.pixelHeight(img.getHeight());
                                adjFig.sourceId(idmlHexId + "_adj");
                                adjFig.fromGroup(true);
                                if (adjCropLeft > 0 || adjCropTop > 0) {
                                    adjFig.cropLeftFraction(adjCropLeft);
                                    adjFig.cropTopFraction(adjCropTop);
                                }
                                adjFig.zOrder(idmlZ);
                                sections.get(adjPageIdx).addBlock(adjFig);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // skip
            }
        }

        // === 렌더링된 이미지 프레임 orphan 주입 ===
        // renderedGraphicFrame이 이미 같은 영역을 커버하면 이미지 프레임 스킵
        // (graphic 렌더는 배경 이미지까지 포함하므로 image 렌더가 중복됨)
        Map<Integer, java.util.List<double[]>> graphicBoundsPerPage = new java.util.HashMap<>();
        for (RenderedGroup gfx : resolvedData.allRenderedGraphicFrames()) {
            if (gfx.bounds() != null && gfx.pageIndex() >= 0) {
                graphicBoundsPerPage.computeIfAbsent(gfx.pageIndex(), k -> new java.util.ArrayList<>())
                        .add(gfx.bounds());
            }
        }
        java.util.Set<Integer> coveredChildIds = new java.util.HashSet<>();
        // 자식 수 내림차순 정렬 → 큰 그룹 먼저 처리
        java.util.List<RenderedGroup> sortedImgFrames = new java.util.ArrayList<>(
                resolvedData.allRenderedImageFrames());
        sortedImgFrames.sort((a, b) -> {
            int aLen = a.childImageIds() != null ? a.childImageIds().length : 0;
            int bLen = b.childImageIds() != null ? b.childImageIds().length : 0;
            return Integer.compare(bLen, aLen);
        });

        for (RenderedGroup rg : sortedImgFrames) {
            if (rg.file() == null || rg.bounds() == null) continue;

            String idmlHexId = "u" + Integer.toHexString(rg.id());
            if (usedSourceIds.contains(idmlHexId)) continue;
            // BackgroundInjector가 page_obj_N 형식으로 이미 배치한 항목 스킵
            if (usedSourceIds.contains("page_obj_" + rg.id())) continue;
            if (isRenderedCoveredByUsedSource(rg, usedRenderedCoverageIds)) continue;

            // 자식 이미지가 이미 사용되었거나 상위 그룹에 의해 커버됨
            if (rg.childImageIds() != null) {
                boolean anyChildUsed = false;
                for (int childId : rg.childImageIds()) {
                    String childHex = "u" + Integer.toHexString(childId);
                    if (usedSourceIds.contains(childHex) || coveredChildIds.contains(childId)) {
                        anyChildUsed = true;
                        break;
                    }
                }
                if (anyChildUsed) continue;
            }

            // 이 그룹 자체가 상위 그룹에 의해 이미 커버된 경우
            if (coveredChildIds.contains(rg.id())) continue;

            int pageIdx = rg.pageIndex();
            if (pageIdx < 0 || pageIdx >= sections.size()) continue;

            // 같은 페이지의 renderedGraphicFrame이 이 이미지와 대부분 겹치면 스킵
            // (graphic 렌더는 배경 이미지까지 포함하므로 image 렌더가 중복됨)
            if (rg.bounds() != null) {
                java.util.List<double[]> gfxBounds = graphicBoundsPerPage.get(pageIdx);
                if (gfxBounds != null) {
                    boolean coveredByGraphic = false;
                    double[] ib = rg.bounds(); // [top, left, bottom, right]
                    double imgArea = (ib[2] - ib[0]) * (ib[3] - ib[1]);
                    if (imgArea > 0) {
                        for (double[] gb : gfxBounds) {
                            // overlap 영역 계산
                            double oTop = Math.max(gb[0], ib[0]);
                            double oLeft = Math.max(gb[1], ib[1]);
                            double oBottom = Math.min(gb[2], ib[2]);
                            double oRight = Math.min(gb[3], ib[3]);
                            if (oBottom > oTop && oRight > oLeft) {
                                double overlapArea = (oBottom - oTop) * (oRight - oLeft);
                                if (overlapArea / imgArea > 0.7) {
                                    coveredByGraphic = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (coveredByGraphic) {
                        continue;
                    }
                }
            }

            kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage resolvedPage =
                    resolvedData.getPage(pageIdx);

            java.io.File pngFile = new java.io.File(resolvedDir, rg.file());
            if (!pngFile.exists()) continue;

            try {
                byte[] data = java.nio.file.Files.readAllBytes(pngFile.toPath());
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(pngFile);
                if (img == null) continue;

                double[] bounds = rg.bounds();
                long figX, figY, figW, figH;
                if (resolvedPage != null && resolvedPage.bounds() != null) {
                    double[] rel = resolvedPage.spreadBoundsToPageRelative(bounds);
                    figX = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                            .pointsToHwpunits(rel[0]);
                    figY = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                            .pointsToHwpunits(rel[1]);
                    figW = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                            .pointsToHwpunits(bounds[3] - bounds[1]);
                    figH = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                            .pointsToHwpunits(bounds[2] - bounds[0]);
                } else {
                    figX = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                            .pointsToHwpunits(bounds[1]);
                    figY = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                            .pointsToHwpunits(bounds[0]);
                    figW = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                            .pointsToHwpunits(bounds[3] - bounds[1]);
                    figH = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                            .pointsToHwpunits(bounds[2] - bounds[0]);
                }
                if (img.getWidth() > 0) {
                    figH = Math.round(figW * ((double) img.getHeight() / img.getWidth()));
                }

                // 음수 좌표 클램프: HWPX는 음수 오프셋을 지원하지 않음
                // 크기는 유지하여 페이지 전체를 덮도록 함 (bleed 영역은 페이지 밖으로 확장)
                if (figX < 0) figX = 0;
                if (figY < 0) figY = 0;

                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection section = sections.get(pageIdx);

                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure fig =
                        new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure();
                fig.x(figX);
                fig.y(figY);
                fig.width(figW);
                fig.height(figH);
                fig.imageData(data);
                fig.imageFormat("png");
                fig.pixelWidth(img.getWidth());
                fig.pixelHeight(img.getHeight());
                fig.sourceId(idmlHexId);
                fig.fromGroup(true);

                // z-order: 겹치는 도형 기준
                long figArea = figW * figH;
                int minSmallerZ = Integer.MAX_VALUE;
                int maxOverlapZ = 0;
                boolean hasSmallerOverlap = false;
                for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock blk : section.blocks()) {
                    if (blk instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure) {
                        kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure existing =
                                (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure) blk;
                        long ew = existing.width(), eh = existing.height();
                        long ox1 = Math.max(existing.x(), figX);
                        long oy1 = Math.max(existing.y(), figY);
                        long ox2 = Math.min(existing.x() + ew, figX + figW);
                        long oy2 = Math.min(existing.y() + eh, figY + figH);
                        if (ox2 > ox1 && oy2 > oy1) {
                            if (existing.zOrder() > maxOverlapZ) {
                                maxOverlapZ = existing.zOrder();
                            }
                            long existArea = ew * eh;
                            if (existArea < figArea) {
                                hasSmallerOverlap = true;
                                if (existing.zOrder() < minSmallerZ) {
                                    minSmallerZ = existing.zOrder();
                                }
                            }
                        }
                    }
                }
                if (hasSmallerOverlap) {
                    fig.zOrder(Math.max(0, minSmallerZ - 1));
                } else {
                    fig.zOrder(maxOverlapZ + 1);
                }

                section.addBlock(fig);
                // 이 그룹의 자식 ID를 커버 목록에 추가 (하위 그룹 중복 방지)
                if (rg.childImageIds() != null) {
                    for (int childId : rg.childImageIds()) {
                        coveredChildIds.add(childId);
                    }
                }
            } catch (Exception e) {
                // skip
            }
        }
    }

    /** 문서 전체 페이지 인덱스(0-based)로 IDMLPage를 찾는다. */
    private static IDMLPage findPageByIndex(IDMLDocument doc, int pageIndex) {
        int idx = 0;
        for (IDMLSpread sp : doc.spreads()) {
            for (IDMLPage pg : sp.pages()) {
                if (idx == pageIndex) return pg;
                idx++;
            }
        }
        return null;
    }

    /** resolved.json의 부모 디렉토리를 반환한다. */
    static String getResolvedDir(ConvertOptions options) {
        if (options.resolvedJsonPath() == null) return null;
        java.io.File resolvedFile = new java.io.File(options.resolvedJsonPath());
        if (!resolvedFile.isAbsolute()) {
            resolvedFile = resolvedFile.getAbsoluteFile();
        }
        return resolvedFile.getParent();
    }

    // SPEC-030 B.1: _crop_manifest.json이 있으면 배치 PNG에서 개별 배지 PNG를 크롭한다.
    // 데스크탑 앱(Rust)이 크롭을 적용하지 못한 경우(CLI 직접 변환 등) 여기서 보정.
    // 구 JSX 버그: visibleBounds가 mm 단위일 때 dpi/72 계수를 사용해 좌표가 작게 계산됨.
    // 보정: 배치 PNG 너비와 매니페스트 max(x+w)의 비율로 스케일을 자동 감지.
    private static void applyCropManifest(String baseDir) {
        if (baseDir == null) return;
        java.io.File manifest = new java.io.File(baseDir, "_crop_manifest.json");
        if (!manifest.exists()) return;
        try {
            String json = new String(java.nio.file.Files.readAllBytes(manifest.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
            java.util.regex.Pattern objPat = java.util.regex.Pattern.compile("\\{([^}]+)\\}");
            java.util.regex.Pattern strPat = java.util.regex.Pattern.compile("\"(src|dst)\"\\s*:\\s*\"([^\"]+)\"");
            java.util.regex.Pattern numPat = java.util.regex.Pattern.compile("\"([xywh])\"\\s*:\\s*(-?\\d+)");
            // 1패스: 전체 파싱
            java.util.List<java.util.Map<String, Object>> entries = new java.util.ArrayList<>();
            java.util.regex.Matcher objM = objPat.matcher(json);
            while (objM.find()) {
                String obj = objM.group(1);
                java.util.regex.Matcher sm = strPat.matcher(obj);
                java.util.Map<String, String> strs = new java.util.HashMap<>();
                while (sm.find()) strs.put(sm.group(1), sm.group(2));
                java.util.regex.Matcher nm = numPat.matcher(obj);
                java.util.Map<String, Integer> nums = new java.util.HashMap<>();
                while (nm.find()) nums.put(nm.group(1), Integer.parseInt(nm.group(2)));
                if (strs.get("src") == null || strs.get("dst") == null) continue;
                java.util.Map<String, Object> e = new java.util.HashMap<>();
                e.put("src", strs.get("src")); e.put("dst", strs.get("dst"));
                e.put("x", nums.getOrDefault("x", 0)); e.put("y", nums.getOrDefault("y", 0));
                e.put("w", nums.getOrDefault("w", 0)); e.put("h", nums.getOrDefault("h", 0));
                entries.add(e);
            }
            // 2패스: src별로 배치 PNG 로드 + 스케일 자동 감지 + 크롭
            java.util.Map<String, java.awt.image.BufferedImage> batches = new java.util.HashMap<>();
            java.util.Map<String, Double> scales = new java.util.HashMap<>();
            java.util.Set<String> srcFiles = new java.util.HashSet<>();
            int count = 0;
            // 스케일 계산: src별 max(x+w)를 배치 너비와 비교
            for (java.util.Map<String, Object> e : entries) {
                String src = (String) e.get("src");
                if (!batches.containsKey(src)) {
                    java.io.File sf = new java.io.File(baseDir, src);
                    if (!sf.exists()) continue;
                    java.awt.image.BufferedImage b = javax.imageio.ImageIO.read(sf);
                    if (b != null) { batches.put(src, b); scales.put(src, 1.0); }
                }
                if (!batches.containsKey(src)) continue;
                int xw = (Integer) e.get("x") + (Integer) e.get("w");
                if (xw > 0) {
                    double ratio = (double) batches.get(src).getWidth() / xw;
                    // 구 JSX 버그로 인해 ratio ≈ 2.83 (72/25.4) 이면 mm 단위 보정 필요
                    if (ratio > scales.get(src)) scales.put(src, ratio);
                }
            }
            // 스케일이 1.3~4.0 범위면 보정 적용, 그 외는 1.0 (신 JSX 또는 pt 문서)
            for (String src : scales.keySet()) {
                double r = scales.get(src);
                if (r < 1.3 || r > 4.0) scales.put(src, 1.0);
            }
            // 크롭
            for (java.util.Map<String, Object> e : entries) {
                String src = (String) e.get("src"), dst = (String) e.get("dst");
                java.awt.image.BufferedImage batch = batches.get(src);
                if (batch == null) continue;
                java.io.File dstFile = new java.io.File(baseDir, dst);
                if (dstFile.exists()) continue;
                double sc = scales.getOrDefault(src, 1.0);
                int x = (int) Math.round((Integer) e.get("x") * sc);
                int y = (int) Math.round((Integer) e.get("y") * sc);
                int w = (int) Math.round((Integer) e.get("w") * sc);
                int h = (int) Math.round((Integer) e.get("h") * sc);
                if (w <= 0 || h <= 0) continue;
                x = Math.max(0, Math.min(x, batch.getWidth() - 1));
                y = Math.max(0, Math.min(y, batch.getHeight() - 1));
                w = Math.min(w, batch.getWidth() - x);
                h = Math.min(h, batch.getHeight() - y);
                if (w <= 0 || h <= 0) continue;
                try {
                    java.awt.image.BufferedImage crop = batch.getSubimage(x, y, w, h);
                    javax.imageio.ImageIO.write(crop, "PNG", dstFile);
                    srcFiles.add(src);
                    count++;
                } catch (Exception ex) { /* skip */ }
            }
            if (count > 0) System.err.println("[IDMLToHwpxConverter] crop_manifest: " + count + " badge PNG(s) cropped");
            for (String s : srcFiles) { new java.io.File(baseDir, s).delete(); }
            manifest.delete();
        } catch (Exception ex) { /* skip manifest failures */ }
    }

    /**
     * SPEC-018 M3: 시멘틱 레이어 추출 + JSON 저장.
     *
     * <p>{@link ConvertOptions#extractSemantics()} 가 true 일 때만 호출.
     * 실패해도 변환 자체는 계속 진행 (호출 측에서 try/catch).</p>
     *
     * <p>스키마 해석 우선순위:</p>
     * <ol>
     *   <li>options.semanticSchema() 가 절대/상대 파일 경로로 존재하면 그 파일</li>
     *   <li>그 외에는 classpath 리소스 {@code semantic-schemas/<value>.schema.json}</li>
     *   <li>null/빈값이면 기본 스키마 {@code common}</li>
     * </ol>
     */
    private static void extractSemanticLayer(
            ASTDocument astDoc,
            String hwpxPath,
            ConvertOptions options,
            ConvertResult result) throws java.io.IOException {
        kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.io.SchemaLoader loader =
                new kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.io.SchemaLoader();

        String schemaSpec = options.semanticSchema();
        kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.SemanticSchema schema;
        if (schemaSpec == null || schemaSpec.isEmpty()) {
            schema = loader.loadResource("semantic-schemas/common.schema.json");
        } else {
            java.io.File f = new java.io.File(schemaSpec);
            if (f.isFile()) {
                schema = loader.loadFromFile(f.toPath());
            } else {
                // 단순 ID 또는 classpath 경로 둘 다 시도
                String resourcePath = schemaSpec.contains("/")
                        ? schemaSpec
                        : ("semantic-schemas/" + schemaSpec + ".schema.json");
                schema = loader.loadResource(resourcePath);
            }
        }

        kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.SemanticLayer layer =
                kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.SemanticExtractor.extract(astDoc, schema);

        String outputPath = options.semanticOutput();
        if (outputPath == null || outputPath.isEmpty()) {
            outputPath = defaultSemanticOutputPath(hwpxPath);
        }

        java.nio.file.Path outPath = java.nio.file.Paths.get(outputPath);
        java.nio.file.Path parent = outPath.getParent();
        if (parent != null && !java.nio.file.Files.exists(parent)) {
            java.nio.file.Files.createDirectories(parent);
        }
        kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.io.SemanticLayerWriter.write(layer, outPath);

        int classified = 0;
        int unknown = 0;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.SemanticNode n : layer.nodes) {
            if (kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.SemanticTypes.LABEL_UNKNOWN.equals(n.label)) {
                unknown++;
            } else {
                classified++;
            }
        }
        System.out.println("[Semantic] " + layer.nodes.size() + " nodes ("
                + classified + " classified, " + unknown + " unknown), "
                + layer.relations.size() + " relations → " + outputPath);
        result.addWarning("[Semantic] 추출 완료: " + layer.nodes.size() + " 노드, "
                + classified + " 분류 → " + outputPath);
    }

    /**
     * Semantic Block Discovery JSON을 HWPX 옆에 생성한다.
     *
     * <p>개발 중에는 항상 생성하고, 안정화 후 ConvertOptions로 옵션화한다.</p>
     */
    private static void extractSemanticBlocks(
            ASTDocument astDoc,
            ResolvedData resolvedData,
            String hwpxPath,
            ConvertResult result) throws java.io.IOException {
        String documentName = astDoc != null && astDoc.sourceFile() != null
                ? astDoc.sourceFile()
                : (hwpxPath == null ? "out" : new File(hwpxPath).getName());
        kr.dogfoot.hwpxlib.tool.idmlconverter.semanticblock.SemanticBlockDocument document =
                kr.dogfoot.hwpxlib.tool.idmlconverter.semanticblock.SemanticBlockDetector.detect(
                        astDoc, documentName, resolvedData);
        java.nio.file.Path outPath = java.nio.file.Paths.get(defaultSemanticBlocksOutputPath(hwpxPath));
        kr.dogfoot.hwpxlib.tool.idmlconverter.semanticblock.SemanticBlockWriter.write(document, outPath);
        ConversionTiming.metric("semanticBlocks.blocks", document.summary.blocks);
        ConversionTiming.metric("semanticBlocks.members", document.summary.members);
        ConversionTiming.metric("semanticBlocks.anchors", document.summary.anchors);
        result.addWarning("[SemanticBlock] " + outPath + " 생성 완료: "
                + document.summary.blocks + " blocks, " + document.summary.members + " members");
    }

    /** hwpx 경로 → 같은 디렉토리의 .semantic-blocks.json. */
    private static String defaultSemanticBlocksOutputPath(String hwpxPath) {
        if (hwpxPath == null) return "out.semantic-blocks.json";
        int dot = hwpxPath.lastIndexOf('.');
        if (dot < 0) return hwpxPath + ".semantic-blocks.json";
        return hwpxPath.substring(0, dot) + ".semantic-blocks.json";
    }

    /** hwpx 경로 → 같은 디렉토리의 .semantic.json. */
    private static String defaultSemanticOutputPath(String hwpxPath) {
        if (hwpxPath == null) return "out.semantic.json";
        int dot = hwpxPath.lastIndexOf('.');
        if (dot < 0) return hwpxPath + ".semantic.json";
        return hwpxPath.substring(0, dot) + ".semantic.json";
    }

    /**
     * CLI 메인 메서드.
     *
     * 사용법: java IDMLToHwpxConverter input.idml output.hwpx [startPage] [endPage]
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: IDMLToHwpxConverter <input.idml> <output.hwpx> [startPage] [endPage]");
            System.err.println("  startPage, endPage: 1-based page numbers (0 = no limit)");
            System.exit(1);
            return;
        }

        String idmlPath = args[0];
        String hwpxPath = args[1];
        int startPage = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        int endPage = args.length > 3 ? Integer.parseInt(args[3]) : 0;

        ConvertOptions options = ConvertOptions.defaults()
                .startPage(startPage)
                .endPage(endPage);

        try {
            System.out.println("Converting: " + idmlPath + " -> " + hwpxPath);
            if (startPage > 0 || endPage > 0) {
                System.out.println("Page range: " + (startPage > 0 ? startPage : "start")
                        + " ~ " + (endPage > 0 ? endPage : "end"));
            }

            ConvertResult result = convert(idmlPath, hwpxPath, options);

            System.out.println("Conversion completed: " + result.summary());
            if (result.hasWarnings()) {
                System.out.println("Warnings (" + result.warnings().size() + "):");
                for (String warning : result.summarizedWarnings()) {
                    System.out.println("  - " + warning);
                }
            }
        } catch (ConvertException e) {
            System.err.println("Conversion failed [" + e.phase() + "]: " + e.getMessage());
            if (e.getCause() != null) {
                e.getCause().printStackTrace(System.err);
            }
            System.exit(2);
        }
    }
}
