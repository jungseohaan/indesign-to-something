package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase0.InfraSetup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase1.PageLayoutBuilder;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase2.FramePlacer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3.StoryConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase4.TableBuilder;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase4_5.BulletInserter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase5.WrapPhase5;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared.ParagraphTextHelpers;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase6.BackgroundInjector;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase7.RenderableFramePlacer;
// FontMapper import removed (unused in new pipeline)
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStyleDef;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStoryParser;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.*;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.BTFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHGrepFractionConverter;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHTextClassifier;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.NPFontGlyphMap;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.image.BufferedImage;
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

        // Phase 0: IDML 폰트/스타일/색상 정의 복사 + 스타일 resolver 초기화
        this.styleResolver = StylePropertyResolver.fromIdmlDir(idmlDir);
        copyIDMLDefinitions(doc);
        enrichStyleAlignmentFromResolved(doc);

        // Phase 1: 페이지/섹션 빌드
        List<ASTSection> sections = buildSections();
        for (ASTSection sec : sections) {
            doc.addSection(sec);
        }

        // Phase 2: TextFrame 분류 및 배치
        placeTextFrames(sections);
        tagPhase(sections, "Phase2.placeTextFrames");

        // Phase 3: Story→단락→런 변환
        convertStories(sections);
        tagPhase(sections, "Phase3.convertStories");

        // Phase 4: 테이블 포함 TextFrame → ASTTable 변환
        placeTablesFromIDML(sections);
        tagPhase(sections, "Phase4.placeTablesFromIDML");

        // Phase 4.5: 불릿 스타일 자동 삽입
        // 대신 HwpxParagraphBuilder.applyBetweenLinesSpacing에서 줄간격 조정
        insertBulletsForBulletStyles(sections);
        tagPhase(sections, "Phase4_5.insertBulletsForBulletStyles");

        // Phase 5: textwrap 글상자 분할 (변환 완료된 블록을 wrapIndent 기반으로 분할)
        splitByWrapIndent(sections);
        tagPhase(sections, "Phase5.splitByWrapIndent");

        // Phase 6: 페이지 배경 PNG 주입
        injectPageBackgrounds(sections);
        tagPhase(sections, "Phase6.injectPageBackgrounds");

        // Phase 7: renderable TF(배지)를 플로팅 이미지로 배치
        placeRenderableFrames(sections);
        tagPhase(sections, "Phase7.placeRenderableFrames");

        System.err.println("[ResolvedToASTBuilder] Built " + sections.size() + " sections");
        // SPEC-016 Phase 2: 매칭 신뢰도 비율 리포트
        int total = spec016Counts[0] + spec016Counts[1] + spec016Counts[2];
        if (total > 0) {
            System.err.println("[SPEC-016] HIGH=" + spec016Counts[0]
                    + " MEDIUM=" + spec016Counts[1]
                    + " LOW=" + spec016Counts[2]
                    + " (total=" + total + ")");
        }
        return doc;
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

    // ═══════════════════════════════════════════════════
    // Phase 0: IDML 정의 복사
    // ═══════════════════════════════════════════════════

    /**
     * SPEC-013 Stage 3: Phase 0 IDML 정의 복사는 {@link InfraSetup}로 위임. 동작 동일.
     */
    private void copyIDMLDefinitions(ASTDocument doc) {
        ResolvedBuildContext ctx = buildPhaseContext();
        ctx.astDocument = doc; // Phase 0 시점은 build()의 로컬 doc과 동일
        InfraSetup.copyIDMLDefinitions(ctx);
    }

    // ═══════════════════════════════════════════════════
    // Phase 1: 페이지/섹션 빌드
    // ═══════════════════════════════════════════════════

    /**
     * SPEC-013 Stage 4: Phase 1 페이지/섹션 빌드는 {@link PageLayoutBuilder}로 위임. 동작 동일.
     * ctx에 채워진 pageDocOffsetToSection 매핑을 인스턴스 필드로 다시 복사해 toSectionIndex 호환 유지.
     */
    private List<ASTSection> buildSections() {
        ResolvedBuildContext ctx = buildPhaseContext();
        List<ASTSection> sections = PageLayoutBuilder.build(ctx);
        this.pageDocOffsetToSection = ctx.pageDocOffsetToSection;
        return sections;
    }

    /**
     * document pageIndex (페이지 오프셋) → sections 리스트 인덱스 변환.
     * 부분 추출 시 pageIndex가 4,5,6이지만 sections는 0,1,2인 경우 매핑.
     */
    private int toSectionIndex(int docPageIndex) {
        if (pageDocOffsetToSection == null) return docPageIndex;
        Integer secIdx = pageDocOffsetToSection.get(docPageIndex);
        return secIdx != null ? secIdx : docPageIndex;
    }

    // ═══════════════════════════════════════════════════
    // Phase 2: TextFrame 분류 및 배치
    // ═══════════════════════════════════════════════════

    /**
     * SPEC-013 Stage 8: Phase 2 TextFrame 분류/배치는 {@link FramePlacer}로 위임. 동작 동일.
     */
    /**
     * SPEC-013 Stage 8: Phase 2 TextFrame 분류/배치는 {@link FramePlacer}로 위임. 동작 동일.
     */
    private void placeTextFrames(List<ASTSection> sections) {
        FramePlacer.placeTextFrames(buildPhaseContext(), sections);
    }


    // ═══════════════════════════════════════════════════
    // Phase 2a: composedLines 기반 글상자 배치
    // ═══════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════
    // Phase 5: textwrap 글상자 분할 (후처리)
    // Phase 3에서 완성된 블록을 composedLine wrapIndent 기반으로 분할.
    // 런 분할, 인라인 처리, 폰트 매핑 등이 모두 적용된 후 실행.
    // ═══════════════════════════════════════════════════

    private void splitByWrapIndent(List<ASTSection> sections) {
        WrapPhase5.splitByWrapIndent(buildPhaseContext(), sections);
    }


    // SPEC-013 Stage 8: placeByYGapSplit는 phase2/FramePlacer로 이동.


    // ═══════════════════════════════════════════════════
    // Phase 4: 테이블 포함 TextFrame → ASTTable 변환
    // ═══════════════════════════════════════════════════

    /**
     * editable이 아닌(배경에 포함된) TextFrame 중 테이블을 포함한 프레임을 찾아
     * IDML Story XML에서 테이블을 파싱하고 ASTTable로 변환한다.
     */
    /**
     * SPEC-013 Stage 6: Phase 4 테이블 변환은 {@link TableBuilder}로 위임. 동작 동일.
     */
    private void placeTablesFromIDML(List<ASTSection> sections) {
        TableBuilder.placeTablesFromIDML(buildPhaseContext(), sections);
    }

    /**
     * IDML Story XML을 로드하고 캐시한다.
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
            String hexId2 = Integer.toHexString(Integer.parseInt(storyId));
            IDMLStory story = IDMLStoryParser.parseStory(xmlDoc, "u" + hexId2);
            idmlStoryCache.put(storyId, story);
            return story;
        } catch (Exception e) {
            return null;
        }
    }


    // ═══════════════════════════════════════════════════
    // Phase 3: Story → 단락 → 런 변환 (StoryConverter로 위임)
    // ═══════════════════════════════════════════════════

    /** SPEC-013 Stage 9: Phase 3는 {@link kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3.StoryConverter}로 위임. 동작 동일. */
    private void convertStories(List<ASTSection> sections) {
        StoryConverter.convertStories(buildPhaseContext(), sections);
    }


    /**
     * 텍스트 기반 resolved 런 매칭.
     * 매칭 성공 시 resolvedRunIdx를 매칭된 인덱스+1로 갱신 (순차 추적).
     * 매칭 실패 시 null 반환 (호출측에서 defaultRR 사용).
     */
    private int[] lastMatchResult = new int[]{0}; // [0]=matched index


    // ═══════════════════════════════════════════════════
    // 인라인 객체 로드
    // ═══════════════════════════════════════════════════


    /**
     * 인라인 앵커 객체가 부모 TextFrame의 X 범위 밖에 있는지 판별.
     * 커스텀 위치 앵커(AnchoredPosition=Anchored + 큰 오프셋)는 텍스트 흐름과 무관하게 배치되므로
     * 인라인 삽입하면 안 된다.
     */


    // SPEC-013 Stage 8: isNestedInTextFrame은 phase2/FramePlacer로 이동.


    /**
     * SPEC-013 Stage 7: Phase 4.5 불릿 삽입은 {@link BulletInserter}로 위임. 동작 동일.
     */
    private void insertBulletsForBulletStyles(List<ASTSection> sections) {
        BulletInserter.run(buildPhaseContext(), sections);
    }

    /**
     * SPEC-013 Stage 5: Phase 7(renderable 배지 배치)는 {@link RenderableFramePlacer}로 위임.
     * 동작은 동일.
     */
    private void placeRenderableFrames(List<ASTSection> sections) {
        RenderableFramePlacer.place(buildPhaseContext(), sections);
    }

    /**
     * SPEC-013 Stage 5: Phase 6(페이지 배경 PNG 주입)는 {@link BackgroundInjector}로 위임.
     * 동작은 동일.
     */
    private void injectPageBackgrounds(List<ASTSection> sections) {
        BackgroundInjector.inject(buildPhaseContext(), sections);
    }

    /**
     * 분리된 Phase 클래스가 사용할 컨텍스트를 ResolvedToASTBuilder의 인스턴스 상태에서 채워서 반환.
     * 점진적 분리 단계라 매 호출마다 새 컨텍스트를 만들지만, 후속 단계에서 build() 시작 시
     * 한 번만 만들고 재사용하도록 바뀔 예정.
     */
    private ResolvedBuildContext buildPhaseContext() {
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
        // SPEC-013 Stage 6: lazy IDML 인프라 콜백/공급자
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

    /**
     * IDMLPage → section index 매핑.
     */
    private int findSectionIndex(kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLPage page, List<ASTSection> sections) {
        String pageName = page.name();
        if (pageName != null) {
            for (int i = 0; i < sections.size(); i++) {
                try {
                    if (sections.get(i).pageNumber() == Integer.parseInt(pageName)) return i;
                } catch (NumberFormatException e) { /* ignore */ }
            }
        }
        // fallback: spread 내 페이지 순서로 매핑
        int idx = 0;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLSpread s : idmlDocument.spreads()) {
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLPage p : s.pages()) {
                if (p == page) return idx < sections.size() ? idx : -1;
                idx++;
            }
        }
        return -1;
    }


    /**
     * resolved paragraphStyles의 justification으로 ASTDocument 스타일 보강.
     * 1차: resolved.json top-level paragraphStyles (가장 정확한 소스)
     * 2차: resolved Story 단락의 개별 justification (fallback)
     * IDML Styles.xml에 이미 Justification이 설정된 스타일은 건너뜀.
     */
    /**
     * SPEC-013 Stage 3: Phase 0 스타일 alignment 보강은 {@link InfraSetup}로 위임. 동작 동일.
     */
    private void enrichStyleAlignmentFromResolved(ASTDocument doc) {
        ResolvedBuildContext ctx = buildPhaseContext();
        ctx.astDocument = doc;
        InfraSetup.enrichStyleAlignmentFromResolved(ctx);
    }

    /**
     * 테이블 셀에 인라인 객체(Group, Rectangle 등)가 포함되어 있는지 확인.
     * SPEC-013 Stage 6: hasInlineObjectsInTable / renderTableAsImage 는
     * {@link kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase4.TableBuilder}로 이동.
     */
}
