package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase0.InfraSetup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase1.PageLayoutBuilder;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase2.FramePlacer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3.StoryConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase4.TableBuilder;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase4_5.BulletInserter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase5.WrapPhase5;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase6.BackgroundInjector;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase7.RenderableFramePlacer;
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


    // Lazy-loaded IDML 인프라 (테이블 셀 변환용)
    private kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument idmlDocument;
    private kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver colorResolver;
    private kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader imageLoader;

    private void ensureIdmlInfra() {
        if (idmlDocument != null || idmlDir == null) return;
        try {
            idmlDocument = kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLLoader.loadFromDirectory(idmlDir);
            colorResolver = new kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver(idmlDocument);
            // imageLoader: 테이블 셀 내 인라인 이미지/그래픽 렌더링용
            kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertOptions opts = new kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertOptions();
            opts.includeImages(true);
            if (resolvedData.basePath() != null) {
                opts.linksDirectory(resolvedData.basePath() + "/Links");
            }
            imageLoader = new kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader(idmlDocument, opts);
        } catch (Exception e) {
            System.err.println("[ResolvedToASTBuilder] IDML infra load failed: " + e.getMessage());
        }
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
        ASTDocument doc = new ASTDocument();
        this.astDoc = doc;
        doc.sourceFormat("Resolved");
        this.styleResolver = StylePropertyResolver.fromIdmlDir(idmlDir);
        this.ctx = newContext();

        // Phase 0: IDML 폰트/스타일/색상 정의 복사 + 스타일 alignment 보강
        InfraSetup.copyIDMLDefinitions(this.ctx);
        InfraSetup.enrichStyleAlignmentFromResolved(this.ctx);

        // Phase 1: 페이지/섹션 빌드
        List<ASTSection> sections = PageLayoutBuilder.build(this.ctx);
        this.pageDocOffsetToSection = this.ctx.pageDocOffsetToSection; // toSectionIndex() 호환
        for (ASTSection sec : sections) {
            doc.addSection(sec);
        }

        // Phase 2: TextFrame 분류 및 배치
        FramePlacer.placeTextFrames(this.ctx, sections);
        tagPhase(sections, "Phase2.placeTextFrames");

        // Phase 2.5 pre-scan: inline anchor 중 짧은 단문(≤3자) renderable 항목을 미리 등록
        // → Phase 3의 loadInlineObject가 건너뛰어 Phase 7 TextFrameBlock과 중복 렌더링 방지
        RenderableFramePlacer.preRegisterInlineShortTextItems(this.ctx);

        // Phase 3: Story→단락→런 변환
        StoryConverter.convertStories(this.ctx, sections);
        tagPhase(sections, "Phase3.convertStories");

        // Phase 4: 테이블 포함 TextFrame → ASTTable 변환
        TableBuilder.placeTablesFromIDML(this.ctx, sections);
        tagPhase(sections, "Phase4.placeTablesFromIDML");

        // Phase 4.5: 불릿 스타일 자동 삽입
        BulletInserter.run(this.ctx, sections);
        tagPhase(sections, "Phase4_5.insertBulletsForBulletStyles");

        // Phase 4.7: 단일 행 글상자 폭 자동 확장 (font metric 기반)
        kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase4_7.SingleLineExpander.run(this.ctx, sections);
        tagPhase(sections, "Phase4_7.singleLineExpand");

        // Phase 5: textwrap 글상자 분할 (composedLine wrapIndent 기반)
        WrapPhase5.splitByWrapIndent(this.ctx, sections);
        tagPhase(sections, "Phase5.splitByWrapIndent");

        // Phase 6: 페이지 배경 PNG 주입
        BackgroundInjector.inject(this.ctx, sections);
        tagPhase(sections, "Phase6.injectPageBackgrounds");

        // Phase 7: renderable TF(배지)를 플로팅 이미지로 배치
        RenderableFramePlacer.place(this.ctx, sections);
        tagPhase(sections, "Phase7.placeRenderableFrames");

        System.err.println("[ResolvedToASTBuilder] Built " + sections.size() + " sections");
        reportSpec016Counts();
        return doc;
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

        String hexId;
        try {
            hexId = Integer.toHexString(Integer.parseInt(storyId));
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
            return story;
        } catch (Exception e) {
            return null;
        }
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
