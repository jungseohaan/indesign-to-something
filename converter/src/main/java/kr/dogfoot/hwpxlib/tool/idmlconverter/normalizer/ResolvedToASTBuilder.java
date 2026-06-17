package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionTiming;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase0.InfraSetup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase1.MasterHashiraPlacer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase1.MasterPageNumberPlacer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase1.PageLayoutBuilder;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase2.FramePlacer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3.StoryConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase4.TableBuilder;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase4_5.BulletInserter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase4_7.NumberedSideHeadTableNormalizer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase5.WrapPhase5;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualBuilder;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.OwnershipPlanner;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.OwnershipPlanValidator;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.AnchoredTablePlanner;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.SideHeadFlowPlanner;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.SimpleButtonLabelPlanner;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStoryParser;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.*;

import java.io.File;
import java.util.*;

/**
 * IDML-Free 파이프라인: resolved.json만으로 ASTDocument를 빌드한다.
 * 기존 4-stage 파이프라인(Stage1~4 + ResolvedMerger + ASTPageProcessor)을 대체.
 *
 * 좌표 흐름: resolved.json (page-relative, mm) → scaleFactor → points → pointsToHwpunits() → HWPUNIT
 * AST에는 최종 HWPUNIT 좌표만 저장.
 */
public class ResolvedToASTBuilder {

    private final ResolvedData resolvedData;
    private final String basePath;       // resolved.json 부모 디렉토리
    private final double scaleFactor;    // mm → pt 변환 (resolved 좌표 → points)
    private final File idmlDir;          // IDML 압축 해제 디렉토리 (Story XML 로드용)
    private final int pngExportDpi;      // ExtendScript PNG 내보내기 해상도
    private final Map<String, IDMLStory> idmlStoryCache = new HashMap<>();
    private StylePropertyResolver styleResolver;
    private ASTDocument astDoc; // Phase 0에서 스타일 정의 접근용
    private Map<Integer, Integer> pageDocOffsetToSection; // document pageIndex → section list index
    private ResolvedBuildContext ctx; // build() 시작 시 1회 생성, 모든 phase가 공유

    // SPEC-016 Phase 2: 매칭 신뢰도 카운터 — build() 종료 시 stderr에 비율 출력
    // [0]=HIGH, [1]=MEDIUM, [2]=LOW. ctx.spec016Counts와 같은 배열을 공유.
    private final int[] spec016Counts = new int[3];

    // SPEC-015: --debug-ast 활성화 여부. true면 phase별로 새로 만든 블록에 DebugMeta.createdAt 자동 부여.
    private boolean debugAst;
    public ResolvedToASTBuilder debugAst(boolean v) { this.debugAst = v; return this; }

    // SPEC-017: 테이블 셀 품질 게이트 정책 (ConversionConfig에서 주입).
    private kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionConfig.TableQualityGateConfig tableQualityGate;
    public ResolvedToASTBuilder tableQualityGate(kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionConfig.TableQualityGateConfig v) {
        this.tableQualityGate = v; return this;
    }


    // Lazy-loaded IDML 인프라 (색상 fallback / 테이블 셀 변환용)
    private kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument idmlDocument;
    private kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver colorResolver;
    private kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader imageLoader;

    private void ensureIdmlDocument() {
        if (idmlDocument != null || idmlDir == null) return;
        try {
            try (ConversionTiming.Scope ignored = ConversionTiming.time("infra.idmlDocumentLoad")) {
                idmlDocument = kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLLoader.loadFromDirectory(idmlDir);
            }
        } catch (Exception e) {
            System.err.println("[ResolvedToASTBuilder] IDML document load failed: " + e.getMessage());
        }
    }

    private void ensureColorInfra() {
        if (colorResolver != null || idmlDir == null) return;
        ensureIdmlDocument();
        if (idmlDocument == null) return;
        try {
            try (ConversionTiming.Scope ignored = ConversionTiming.time("infra.colorResolverInit")) {
                colorResolver = new kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver(idmlDocument);
            }
        } catch (Exception e) {
            System.err.println("[ResolvedToASTBuilder] color resolver init failed: " + e.getMessage());
        }
    }

    private void ensureImageInfra() {
        if (imageLoader != null || idmlDir == null) return;
        ensureColorInfra();
        if (idmlDocument == null) return;
        try {
            // imageLoader: 테이블 셀 내 인라인 이미지/그래픽 렌더링용
            kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertOptions opts = new kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertOptions();
            opts.includeImages(true);
            if (resolvedData.basePath() != null) {
                opts.linksDirectory(resolvedData.basePath() + "/Links");
            }
            try (ConversionTiming.Scope ignored = ConversionTiming.time("infra.imageLoaderInit")) {
                imageLoader = new kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader(idmlDocument, opts);
            }
        } catch (Exception e) {
            System.err.println("[ResolvedToASTBuilder] image infra init failed: " + e.getMessage());
        }
    }

    private void ensureIdmlInfra() {
        ensureImageInfra();
    }

    public ResolvedToASTBuilder(ResolvedData resolvedData) {
        this(resolvedData, null, 300);
    }

    public ResolvedToASTBuilder(ResolvedData resolvedData, File idmlDir) {
        this(resolvedData, idmlDir, 300);
    }

    public ResolvedToASTBuilder(ResolvedData resolvedData, File idmlDir, int pngExportDpi) {
        this.resolvedData = resolvedData;
        this.basePath = resolvedData.basePath();
        this.scaleFactor = resolvedData.scaleFactor();
        this.idmlDir = idmlDir;
        this.pngExportDpi = pngExportDpi;
    }

    /**
     * resolved.json → ASTDocument 변환.
     */
    public ASTDocument build() {
        ConversionTiming.Scope totalScope = ConversionTiming.time("phase2.resolvedAstBuilder.total");
        try {
        ASTDocument doc = new ASTDocument();
        this.astDoc = doc;
        doc.sourceFormat("Resolved");
        try (ConversionTiming.Scope ignored = ConversionTiming.time("phase2.resolvedAstBuilder.styleResolver")) {
            this.styleResolver = StylePropertyResolver.fromIdmlDir(idmlDir);
        }
        this.ctx = newContext();

        List<ASTSection> sections;
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage0.inputPrepare")) {
            sections = prepareInput(doc);
        }
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage1.ownershipPlanner")) {
            planOwnership();
        }
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage2.textBuilder")) {
            buildTextContent(sections);
        }
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage4.layoutPostprocess")) {
            postprocessLayout(sections);
        }
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage2_5.visualOwnershipRefine")) {
            OwnershipPlanner.runVisualRefinement(this.ctx);
        }
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage3.visualBuilder")) {
            placeVisuals(sections);
        }

        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage4.validate.ownershipPlan")) {
            removeStage1OwnershipInvariantWarnings();
            OwnershipPlanValidator.validate(this.ctx);
        }
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage4.validate.writeDecisionLogs")) {
            writeOwnershipPlanLog();
            writeRenderDecisionLog();
        }
        System.err.println("[ResolvedToASTBuilder] Built " + sections.size() + " sections");
        reportSpec016Counts();
        return doc;
        } finally {
            totalScope.close();
        }
    }

    private void removeStage1OwnershipInvariantWarnings() {
        if (ctx == null || ctx.ownershipWarningLines == null || ctx.ownershipWarningLines.isEmpty()) {
            return;
        }
        ctx.ownershipWarningLines.removeIf(line ->
                line != null
                        && (line.contains("\"code\":\"DUPLICATE_VISIBLE_SOURCE\"")
                        || line.contains("\"code\":\"CONFLICTING_TEXT_OWNER\"")
                        || line.contains("\"code\":\"VISIBLE_VISUAL_CONTAINS_HWPX_TEXT_SOURCE\"")
                        || line.contains("\"code\":\"INLINE_FLOATING_SAME_DOM\"")
                        || line.contains("\"code\":\"DUPLICATE_VISIBLE_FILE_BOUNDS\"")
                        || line.contains("\"code\":\"TEXT_SHELL_ZORDER_GE_TEXT\"")));
    }

    /**
     * Stage 0: 입력 인덱스와 페이지 골격만 만든다.
     *
     * <p>이 단계에서는 객체 ownership, PNG 배치 여부, TextFrame 배치 여부를
     * 결정하지 않는다. 마스터 페이지 객체도 장기적으로는 OwnershipPlanner의
     * 입력으로 넘겨 Stage 2/3에서 실행한다.</p>
     */
    private List<ASTSection> prepareInput(ASTDocument doc) {
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage0.inputPrepare.copyIdmlDefinitions")) {
            InfraSetup.copyIDMLDefinitions(this.ctx);
        }
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage0.inputPrepare.enrichStyleAlignment")) {
            InfraSetup.enrichStyleAlignmentFromResolved(this.ctx);
        }

        List<ASTSection> sections;
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage0.inputPrepare.pageLayoutBuilder")) {
            sections = PageLayoutBuilder.build(this.ctx);
        }
        this.pageDocOffsetToSection = this.ctx.pageDocOffsetToSection; // toSectionIndex() 호환
        for (ASTSection sec : sections) {
            doc.addSection(sec);
        }

        // Phase 1.5: 마스터 페이지 쪽 번호 배치
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage0.inputPrepare.masterPageNumberPlacer")) {
            MasterPageNumberPlacer.place(this.ctx, sections);
        }

        // Phase 1.6: 마스터 페이지 하시라(러닝 헤더) 배치
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage0.inputPrepare.masterHashiraPlacer")) {
            MasterHashiraPlacer.place(this.ctx, sections);
        }

        return sections;
    }

    /**
     * Stage 1: SPEC-035 OwnershipPlanner 관찰 모드.
     *
     * <p>아직 legacy Phase 실행 결과를 바꾸지 않는다. ObjectPlan과 invariant
     * warning만 기록해 현재 정책 충돌을 눈으로 추적할 수 있게 한다.</p>
     */
    private void planOwnership() {
        OwnershipPlanner.runObservation(this.ctx);
        AnchoredTablePlanner.plan(this.ctx);
        SideHeadFlowPlanner.plan(this.ctx);
        SimpleButtonLabelPlanner.plan(this.ctx);
    }

    /**
     * Stage 2.5 (SPEC-036 (가)): Stage 2(텍스트/인라인 분류) 이후, 시각 배치(Phase 6/7) 이전에
     * visual ownership을 refine한다. Phase 0 planner는 Stage 2 산출물(셀 인라인 임베드, 컨셉
     * 다이어그램 TF 등)을 볼 수 없으므로, 그에 의존하는 suppress 결정을 여기서 plan(DROP_VISUAL)으로
     * 확정한다. 이렇게 하면 Phase 6/7은 휴리스틱 대신 plan 권위만 실행한다.
     *
     * <p>현재 이전 완료: cellInlineEmbeddedDomIds(셀에 인라인 임베드된 배지의 원본 floating PNG).
     * 후속: childOfGroup(부모에 구워진 자식) 등 — 각 클래스 골든디프 게이트로 단계 이전.</p>
     */
    /**
     * Stage 1/2: 현재는 legacy Phase들이 ownership 일부와 text/table 생성을
     * 함께 수행한다.
     *
     * <p>목표 구조에서는 Stage 1 OwnershipPlanner가 먼저 ObjectPlan을 만들고,
     * 이 메서드는 HWPX가 소유하는 텍스트/테이블만 생성한다. 따라서 새 분류
     * 로직은 이 메서드 안의 legacy Phase가 아니라 Planner로 이동해야 한다.</p>
     */
    private void buildTextContent(List<ASTSection> sections) {
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage2.textBuilder.framePlacer")) {
            FramePlacer.placeTextFrames(this.ctx, sections);
        }
        tagPhase(sections, "Stage2.TextBuilder.legacyFramePlacer");

        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage2.textBuilder.storyConverter")) {
            StoryConverter.convertStories(this.ctx, sections);
        }
        tagPhase(sections, "Stage2.TextBuilder.legacyStoryConverter");

        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage2.textBuilder.tableBuilder")) {
            TableBuilder.placeTablesFromIDML(this.ctx, sections);
        }
        tagPhase(sections, "Stage2.TextBuilder.legacyTableBuilder");
    }

    /**
     * Stage 4: 생성된 레이아웃을 보정한다.
     *
     * <p>이 단계는 줄바꿈/불릿/폭 보정처럼 이미 생성된 AST의 형태만 조정한다.
     * 새 visible 객체를 만들거나 ownership을 뒤집는 로직을 추가하지 않는다.</p>
     */
    private void postprocessLayout(List<ASTSection> sections) {
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage4.layoutPostprocess.numberedSideHeadTablesBridge")) {
            NumberedSideHeadTableNormalizer.run(this.ctx, sections);
        }
        tagPhase(sections, "Stage4.LayoutPostprocess.numberedSideHeadTablesBridge");

        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage4.layoutPostprocess.bulletInserter")) {
            BulletInserter.run(this.ctx, sections);
        }
        tagPhase(sections, "Stage4.LayoutPostprocess.insertBullets");

        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage4.layoutPostprocess.singleLineExpander")) {
            kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase4_7.SingleLineExpander.run(this.ctx, sections);
        }
        tagPhase(sections, "Stage4.LayoutPostprocess.singleLineExpand");

        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage4.layoutPostprocess.wrapPhase5")) {
            WrapPhase5.splitByWrapIndent(this.ctx, sections);
        }
        tagPhase(sections, "Stage4.LayoutPostprocess.splitByWrapIndent");
    }

    /**
     * Stage 3: 시각 객체를 배치한다.
     *
     * <p>VisualBuilder가 ObjectPlan의 visualAction/zOrder 실행 책임을 단일
     * 진입점으로 갖는다. 내부 legacy executor는 단계적으로 흡수 후 제거한다.</p>
     */
    private void placeVisuals(List<ASTSection> sections) {
        VisualBuilder.place(this.ctx, sections);
        tagPhase(sections, "Stage3.VisualBuilder");
    }

    private void writeRenderDecisionLog() {
        if (basePath == null || ctx == null || ctx.renderDecisionLines == null
                || ctx.renderDecisionLines.isEmpty()) {
            return;
        }
        try {
            java.nio.file.Path out = java.nio.file.Paths.get(basePath, "render-decisions.jsonl");
            java.nio.file.Files.write(out, ctx.renderDecisionLines,
                    java.nio.charset.StandardCharsets.UTF_8);
            System.err.println("[ResolvedToASTBuilder] render decisions: " + out);
        } catch (Exception e) {
            System.err.println("[ResolvedToASTBuilder] render decision log write failed: " + e.getMessage());
        }
    }

    private void writeOwnershipPlanLog() {
        if (basePath == null || ctx == null) {
            return;
        }
        ctx.rebuildOwnershipPlanLinesFromPlans();
        writeJsonLines("ownership-plan.jsonl", ctx.ownershipPlanLines, "ownership plan");
        writeJsonLines("ownership-warnings.jsonl", ctx.ownershipWarningLines, "ownership warnings");
    }

    private void writeJsonLines(String fileName, java.util.List<String> lines, String label) {
        try {
            java.nio.file.Path out = java.nio.file.Paths.get(basePath, fileName);
            java.nio.file.Files.write(out,
                    lines != null ? lines : java.util.Collections.emptyList(),
                    java.nio.charset.StandardCharsets.UTF_8);
            System.err.println("[ResolvedToASTBuilder] " + label + ": " + out);
        } catch (Exception e) {
            System.err.println("[ResolvedToASTBuilder] " + label + " write failed: " + e.getMessage());
        }
    }

    /** SPEC-016: 매칭 신뢰도 비율을 stderr에 리포트. */
    private void reportSpec016Counts() {
        int total = spec016Counts[0] + spec016Counts[1] + spec016Counts[2];
        if (total > 0) {
            System.err.println("[SPEC-016] HIGH=" + spec016Counts[0]
                    + " MEDIUM=" + spec016Counts[1]
                    + " LOW=" + spec016Counts[2]
                    + " (total=" + total + ")");
        }
    }

    /**
     * SPEC-015: 디버그 모드일 때 phase 종료 후 createdAt 미설정 블록을 현재 phase로 태그.
     * 새로 추가된 블록만 식별하기 어려우므로 "처음 발견 시점"을 createdAt으로 본다 — 한 번
     * 태그된 블록은 다음 phase가 덮어쓰지 않으므로 "최초로 만든 phase"가 정확히 기록된다.
     */
    private void tagPhase(List<ASTSection> sections, String phase) {
        if (!debugAst) return;
        for (ASTSection s : sections) {
            for (ASTBlock b : s.blocks()) {
                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.DebugMeta dm = b.debug();
                if (dm == null || dm.createdAt == null) {
                    b.debugOrNew().createdAt = phase;
                }
            }
        }
    }

    // ─── ctx에 노출되는 인스턴스 헬퍼들 ─────────────────────

    /**
     * document pageIndex (페이지 오프셋) → sections 리스트 인덱스 변환.
     * 부분 추출 시 pageIndex가 4,5,6이지만 sections는 0,1,2인 경우 매핑.
     * ctx.toSectionIndex로 phase에 노출.
     */
    private int toSectionIndex(int docPageIndex) {
        if (pageDocOffsetToSection == null) return docPageIndex;
        Integer secIdx = pageDocOffsetToSection.get(docPageIndex);
        return secIdx != null ? secIdx : docPageIndex;
    }

    /**
     * IDML Story XML을 로드하고 캐시한다. ctx.loadIDMLStory로 phase에 노출.
     */
    private IDMLStory loadIDMLStory(String storyId) {
        if (idmlStoryCache.containsKey(storyId)) return idmlStoryCache.get(storyId);

        String sourceStoryId = sourceStoryId(storyId);
        String hexId;
        try {
            if (sourceStoryId.startsWith("u") || sourceStoryId.startsWith("U")) {
                // IDML hex format: "u4daf" → hexId = "4daf"
                hexId = sourceStoryId.substring(1).toLowerCase();
            } else {
                // decimal format (from resolved.json): "17203" → hex
                hexId = Integer.toHexString(Integer.parseInt(sourceStoryId));
            }
        } catch (NumberFormatException e) {
            return null;
        }
        java.io.File storyFile = new java.io.File(idmlDir, "Stories/Story_u" + hexId + ".xml");
        if (!storyFile.exists()) return null;

        try {
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            org.w3c.dom.Document xmlDoc = factory.newDocumentBuilder().parse(storyFile);
            IDMLStory story = IDMLStoryParser.parseStory(xmlDoc, "u" + hexId);
            idmlStoryCache.put(storyId, story);
            if (!sourceStoryId.equals(storyId)) idmlStoryCache.put(sourceStoryId, story);
            idmlStoryCache.put("u" + hexId, story);
            return story;
        } catch (Exception e) {
            return null;
        }
    }

    private static String sourceStoryId(String storyId) {
        if (storyId == null) return "";
        int cut = -1;
        int pi = storyId.indexOf("_pi");
        int oc = storyId.indexOf("_oc");
        if (pi >= 0) cut = pi;
        if (oc >= 0) cut = cut < 0 ? oc : Math.min(cut, oc);
        return cut >= 0 ? storyId.substring(0, cut) : storyId;
    }

    // 텍스트 기반 resolved 런 매칭 — 매칭 성공 시 인덱스 추적용 (Phase 3에서 ctx.lastMatchResult로 공유)
    private final int[] lastMatchResult = new int[]{0}; // [0]=matched index

    /**
     * build() 시작 시 1회 호출되는 컨텍스트 생성기.
     * 모든 phase가 this.ctx를 공유하며, 메서드 ref/Supplier로 빌드 도중 변하는 상태
     * (idmlDocument, pageDocOffsetToSection 등)를 동적으로 반영한다.
     */
    private ResolvedBuildContext newContext() {
        ResolvedBuildContext ctx = new ResolvedBuildContext();
        ctx.resolvedData = this.resolvedData;
        ctx.basePath = this.basePath;
        ctx.scaleFactor = this.scaleFactor;
        ctx.idmlDir = this.idmlDir;
        ctx.pngExportDpi = this.pngExportDpi;
        ctx.astDocument = this.astDoc;
        ctx.styleResolver = this.styleResolver;
        ctx.pageDocOffsetToSection = this.pageDocOffsetToSection;
        ctx.toSectionIndex = this::toSectionIndex;
        ctx.ensureIdmlInfra = this::ensureIdmlInfra;
        ctx.ensureColorInfra = this::ensureColorInfra;
        ctx.ensureImageInfra = this::ensureImageInfra;
        ctx.idmlDocumentSupplier = () -> this.idmlDocument;
        ctx.colorResolverSupplier = () -> this.colorResolver;
        ctx.imageLoaderSupplier = () -> this.imageLoader;
        ctx.loadIDMLStory = this::loadIDMLStory;
        ctx.spec016Counts = this.spec016Counts;
        ctx.idmlStoryCache = this.idmlStoryCache;
        ctx.lastMatchResult = this.lastMatchResult;
        ctx.tableQualityGate = this.tableQualityGate;
        ctx.debugAst = this.debugAst;
        return ctx;
    }
}
