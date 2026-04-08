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
    /**
     * SPEC-016 Phase 2: LOW 매칭 진단용 텍스트 마커. 시스템 프로퍼티
     * {@code -Dspec016.debug.text=예쁜} 으로 지정하면 해당 텍스트가 포함된 LOW 세그먼트를
     * stderr에 상세 출력한다. 잔존 과제 1번(첫 번째 "예쁜" 케이스 LOW 원인 분석)용.
     */
    private static final String SPEC016_DEBUG_TEXT = System.getProperty("spec016.debug.text");


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

        // Phase 2.5: 겹치는 프레임 폭 축소 (비활성 — 과도한 축소 문제)
        // shrinkOverlappingFrames(sections);

        // Phase 3: Story→단락→런 변환
        convertStories(sections);

        // Phase 4: 테이블 포함 TextFrame → ASTTable 변환
        placeTablesFromIDML(sections);

        // Phase 4.5: 큰 숫자 런 단락 분리 + 불릿 스타일 자동 삽입
        // splitLargeNumberParagraphs — 비활성화: 번호와 본문이 같은 줄에 있어야 함
        // 대신 HwpxParagraphBuilder.applyBetweenLinesSpacing에서 줄간격 조정
        insertBulletsForBulletStyles(sections);

        // Phase 5: textwrap 글상자 분할 (변환 완료된 블록을 wrapIndent 기반으로 분할)
        splitByWrapIndent(sections);

        // Phase 6: 페이지 배경 PNG 주입
        injectPageBackgrounds(sections);

        // Phase 7: renderable TF(배지)를 플로팅 이미지로 배치
        placeRenderableFrames(sections);

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

    /**
     * InDesign 조판 결과(composedLines)를 기반으로 글상자를 배치한다.
     * 연속 라인의 X 범위(left, right)가 같은 그룹을 하나의 글상자로 묶는다.
     * TextWrap으로 좁아진 영역은 자연스럽게 별도 그룹이 된다.
     * 텍스트/스타일은 convertStories에서 IDML Story 기반으로 채움.
     */
    private void placeComposedLines(ResolvedTextFrame tf, ASTSection section,
                                     double pageLeft, double pageTop) {
        List<ResolvedTextFrame.ComposedLine> lines = tf.composedLines();
        if (lines.isEmpty()) return;

        String sourceIdBase = "u" + Integer.toHexString(Integer.parseInt(tf.id()));
        double tolerance = 2.0; // X 범위 허용 오차 (mm 단위, scaleFactor 적용 전)

        // 라인을 X 범위(left, right)가 같은 그룹으로 묶기
        List<List<ResolvedTextFrame.ComposedLine>> groups = new ArrayList<>();
        List<ResolvedTextFrame.ComposedLine> currentGroup = new ArrayList<>();
        double groupLeft = -1, groupRight = -1;

        for (ResolvedTextFrame.ComposedLine line : lines) {
            if (line.bounds() == null || line.bounds().length < 4) continue;

            double lineLeft = line.bounds()[1];
            double lineRight = line.bounds()[3];

            if (currentGroup.isEmpty()) {
                currentGroup.add(line);
                groupLeft = lineLeft;
                groupRight = lineRight;
            } else if (Math.abs(lineLeft - groupLeft) < tolerance
                    && Math.abs(lineRight - groupRight) < tolerance) {
                currentGroup.add(line);
            } else {
                groups.add(currentGroup);
                currentGroup = new ArrayList<>();
                currentGroup.add(line);
                groupLeft = lineLeft;
                groupRight = lineRight;
            }
        }
        if (!currentGroup.isEmpty()) {
            groups.add(currentGroup);
        }

        // 그룹이 1개면 기존 경로와 동일 (분할 불필요) → 일반 블록으로 배치
        if (groups.size() <= 1) return; // fallback: 기존 placeTextFrames 경로 사용

        // 각 그룹을 ASTTextFrameBlock으로 변환 (텍스트 없이 위치만)
        // 각 그룹의 텍스트 범위를 문자 수로 기록하여 convertStories에서 분배
        int charOffset = 0;
        for (int gi = 0; gi < groups.size(); gi++) {
            List<ResolvedTextFrame.ComposedLine> group = groups.get(gi);

            // 그룹 bounds 계산
            double minLeft = Double.MAX_VALUE, minTop = Double.MAX_VALUE;
            double maxRight = -Double.MAX_VALUE, maxBottom = -Double.MAX_VALUE;
            int groupCharCount = 0;
            for (ResolvedTextFrame.ComposedLine line : group) {
                double[] b = line.bounds();
                if (b[0] < minTop) minTop = b[0];
                if (b[1] < minLeft) minLeft = b[1];
                if (b[2] > maxBottom) maxBottom = b[2];
                if (b[3] > maxRight) maxRight = b[3];
                if (line.text() != null) groupCharCount += line.text().length();
            }

            double gx = (minLeft - pageLeft) * scaleFactor;
            double gy = (minTop - pageTop) * scaleFactor;
            double gw = (maxRight - minLeft) * scaleFactor;
            double gh = (maxBottom - minTop) * scaleFactor;

            if (gx < 0) { gw += gx; gx = 0; }
            if (gy < 0) { gh += gy; gy = 0; }
            if (gw <= 0 || gh <= 0) continue;

            ASTTextFrameBlock block = new ASTTextFrameBlock();
            block.sourceId(sourceIdBase + "_g" + gi);
            block.x(CoordinateConverter.pointsToHwpunits(gx));
            block.y(CoordinateConverter.pointsToHwpunits(gy));
            block.width(CoordinateConverter.pointsToHwpunits(gw));
            block.height(CoordinateConverter.pointsToHwpunits(gh));
            block.zOrder(tf.zOrder());
            block.storyId(tf.storyId());
            block.composedCharStart(charOffset);
            block.composedCharEnd(charOffset + groupCharCount);

            charOffset += groupCharCount;
            section.addBlock(block);
        }

    }

    // ═══════════════════════════════════════════════════
    // Phase 2.5: 겹치는 프레임 폭 축소
    // ═══════════════════════════════════════════════════

    /**
     * 같은 페이지에서 editable 프레임끼리 겹치면 큰 프레임의 폭을 줄인다.
     * 수직 겹침이 큰 프레임 높이의 30% 미만이면 무시.
     */
    private void shrinkOverlappingFrames(List<ASTSection> sections) {
        for (ASTSection sec : sections) {
            List<ASTTextFrameBlock> tfBlocks = new ArrayList<>();
            for (ASTBlock blk : sec.blocks()) {
                if (blk instanceof ASTTextFrameBlock) {
                    tfBlocks.add((ASTTextFrameBlock) blk);
                }
            }
            if (tfBlocks.size() < 2) continue;

            for (int i = 0; i < tfBlocks.size(); i++) {
                ASTTextFrameBlock a = tfBlocks.get(i);
                long aArea = a.width() * a.height();
                for (int j = i + 1; j < tfBlocks.size(); j++) {
                    ASTTextFrameBlock b = tfBlocks.get(j);
                    long bArea = b.width() * b.height();

                    // 겹침 판정
                    long aRight = a.x() + a.width();
                    long aBottom = a.y() + a.height();
                    long bRight = b.x() + b.width();
                    long bBottom = b.y() + b.height();
                    boolean hOverlap = aRight > b.x() && bRight > a.x();
                    boolean vOverlap = aBottom > b.y() && bBottom > a.y();
                    if (!hOverlap || !vOverlap) continue;

                    // 수직 겹침 비율: 큰 프레임 높이의 30% 미만이면 무시
                    long overlapTop = Math.max(a.y(), b.y());
                    long overlapBottom = Math.min(aBottom, bBottom);
                    long overlapH = overlapBottom - overlapTop;
                    ASTTextFrameBlock big = aArea >= bArea ? a : b;
                    ASTTextFrameBlock small = aArea >= bArea ? b : a;
                    if (overlapH < big.height() * 3 / 10) continue;

                    long bigRight = big.x() + big.width();
                    long smallRight = small.x() + small.width();

                    if (small.x() > big.x()) {
                        // small이 big 우측에 있음 → big의 right를 축소
                        long newWidth = small.x() - big.x();
                        if (newWidth > 0 && newWidth < big.width()) {
                            big.width(newWidth);
                        }
                    } else if (smallRight < bigRight) {
                        // small이 big 좌측에 있음 → big의 left를 축소
                        long shift = smallRight - big.x();
                        if (shift > 0 && shift < big.width()) {
                            big.x(big.x() + shift);
                            big.width(big.width() - shift);
                        }
                    }
                }
            }
        }
    }

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




    private static final String BULLET_CHARS = "●•◆◇▶▷■□";











    /**
     * 텍스트에 수식 기호 또는 EH 인코딩 문자가 포함되어 있는지 확인.
     */

    private static boolean hasMathOrEHChars(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ("+=<>≤≥±×÷√²³^_π∑∫∞".indexOf(c) >= 0) return true;
            if (c >= 0xDA && c <= 0xE2) return true; // EH encoded chars
        }
        return false;
    }



    /**
     * resolved 런 목록에서 텍스트가 매칭되는 런을 찾음.
     * IDML 런과 resolved 런의 텍스트 경계가 다를 수 있으므로, 텍스트 포함 여부로 매칭.
     */
    /**
     * IDML 단일 런을 resolved 런 경계에서 분할.
     * GREP 스타일로 인해 하나의 IDML CharacterStyleRange가 resolved에서 여러 런(다른 색상/폰트)으로
     * 분리된 경우, resolved 런의 텍스트를 기준으로 IDML 런을 분할하여 각각 올바른 색상을 적용.
     * @return 분할 성공 시 true, 실패 시 false (호출측에서 기존 로직 사용)
     */






    /**
     * 텍스트 기반 resolved 런 매칭.
     * 매칭 성공 시 resolvedRunIdx를 매칭된 인덱스+1로 갱신 (순차 추적).
     * 매칭 실패 시 null 반환 (호출측에서 defaultRR 사용).
     */
    private int[] lastMatchResult = new int[]{0}; // [0]=matched index









    /**
     * 텍스트 기반 단락 분배: frameParaTexts로 IDML 단락을 각 프레임에 할당.
     * paragraphStart/End 인덱스 대신, 텍스트 내용을 순차 매칭하여 프레임 간 단락 분할을 정확히 처리.
     */



    // SPEC-013 shared: 단락 텍스트 헬퍼는 ParagraphTextHelpers로 이동.
    // 호출처가 많아 instance 래퍼를 잠시 유지 (stage 12에서 직접 호출로 정리).
    private ASTParagraph createContinuationParagraph(ASTParagraph original, int skipLen, String expectedText) {
        return ParagraphTextHelpers.createContinuationParagraph(original, skipLen, expectedText);
    }

    private String getParaPlainText(ASTParagraph para) {
        return ParagraphTextHelpers.getParaPlainText(para);
    }

    private ASTParagraph createSplitParagraph(ASTParagraph original, String frameText) {
        return ParagraphTextHelpers.createSplitParagraph(original, frameText);
    }


    // ═══════════════════════════════════════════════════
    // 인라인 객체 로드
    // ═══════════════════════════════════════════════════


    /**
     * 인라인 앵커 객체가 부모 TextFrame의 X 범위 밖에 있는지 판별.
     * 커스텀 위치 앵커(AnchoredPosition=Anchored + 큰 오프셋)는 텍스트 흐름과 무관하게 배치되므로
     * 인라인 삽입하면 안 된다.
     */





    // SPEC-013 Stage 8: isNestedInTextFrame은 phase2/FramePlacer로 이동.

    // ═══════════════════════════════════════════════════
    // Phase 4: 테이블 배치
    // ═══════════════════════════════════════════════════

    private int findPageForStory(String storyId) {
        // storyId → textFrame → pageIndex (section index로 변환)
        for (ResolvedTextFrame tf : resolvedData.textFrames()) {
            if (storyId.equals(tf.storyId())) {
                return toSectionIndex(tf.pageIndex());
            }
        }
        return -1;
    }

    // ═══════════════════════════════════════════════════
    // ═══════════════════════════════════════════════════
    // Phase 4.5: 큰 숫자 런 단락 분리
    // ═══════════════════════════════════════════════════

    /**
     * SPEC-013 Stage 7: Phase 4.5 불릿 삽입은 {@link BulletInserter}로 위임. 동작 동일.
     */
    private void insertBulletsForBulletStyles(List<ASTSection> sections) {
        BulletInserter.run(buildPhaseContext(), sections);
    }

    /**
     * 첫 런이 큰 폰트(나머지의 1.5배 이상)이고 짧은 텍스트(≤3자)인 단락을 분리.
     * 번호(큰 폰트) + 본문(작은 폰트) 패턴 → 번호를 별도 단락으로 분리하여 줄간격 독립 적용.
     */
    /**
     * 인라인 객체(INLINE_OBJECT)가 포함된 단락을 분리.
     * 인라인 객체 전 텍스트 / 인라인 객체 / 인라인 객체 후 텍스트를 각각 별도 단락으로 분리하여
     * 인라인 객체 단락에는 객체 높이에 맞는 줄간격을 설정.
     */
    private void splitInlineObjectParagraphs(List<ASTSection> sections) {
        int splitCount = 0;
        for (ASTSection section : sections) {
            for (ASTBlock blk : section.blocks()) {
                if (!(blk instanceof ASTTextFrameBlock)) continue;
                ASTTextFrameBlock tfb = (ASTTextFrameBlock) blk;
                if (tfb.paragraphs() == null) continue;

                List<ASTParagraph> newParas = new ArrayList<>();
                for (ASTParagraph para : tfb.paragraphs()) {
                    List<ASTInlineItem> items = para.items();
                    if (items == null) { newParas.add(para); continue; }

                    // 인라인 객체가 있는지 확인
                    boolean hasInlineObj = false;
                    for (ASTInlineItem item : items) {
                        if (item.itemType() == ASTInlineItem.ItemType.INLINE_OBJECT) {
                            ASTInlineObject obj = (ASTInlineObject) item;
                            if (obj.kind() == ASTInlineObject.ObjectKind.IMAGE && obj.height() > 1000) {
                                hasInlineObj = true;
                                break;
                            }
                        }
                    }
                    if (!hasInlineObj) { newParas.add(para); continue; }

                    // 인라인 객체 기준으로 단락 분리
                    List<ASTInlineItem> currentRuns = new ArrayList<>();
                    for (ASTInlineItem item : items) {
                        if (item.itemType() == ASTInlineItem.ItemType.INLINE_OBJECT) {
                            ASTInlineObject obj = (ASTInlineObject) item;
                            if (obj.kind() == ASTInlineObject.ObjectKind.IMAGE && obj.height() > 1000) {
                                // 이전 텍스트 런들을 별도 단락으로
                                if (!currentRuns.isEmpty()) {
                                    ASTParagraph textPara = new ASTParagraph();
                                    textPara.paragraphStyleRef(para.paragraphStyleRef());
                                    textPara.alignment(para.alignment());
                                    if (para.lineSpacing() != null) {
                                        textPara.lineSpacing(para.lineSpacing());
                                        textPara.lineSpacingType(para.lineSpacingType());
                                    }
                                    for (ASTInlineItem r : currentRuns) textPara.addItem(r);
                                    newParas.add(textPara);
                                    currentRuns = new ArrayList<>();
                                }
                                // 인라인 객체를 별도 단락으로 (높이에 맞는 줄간격)
                                ASTParagraph objPara = new ASTParagraph();
                                objPara.paragraphStyleRef(para.paragraphStyleRef());
                                objPara.alignment(para.alignment());
                                objPara.lineSpacing((int)(obj.height() * 1.2));
                                objPara.lineSpacingType("fixed");
                                objPara.addItem(item);
                                newParas.add(objPara);
                                splitCount++;
                                continue;
                            }
                        }
                        currentRuns.add(item);
                    }
                    // 나머지 텍스트 런
                    if (!currentRuns.isEmpty()) {
                        ASTParagraph tailPara = new ASTParagraph();
                        tailPara.paragraphStyleRef(para.paragraphStyleRef());
                        tailPara.alignment(para.alignment());
                        if (para.lineSpacing() != null) {
                            tailPara.lineSpacing(para.lineSpacing());
                            tailPara.lineSpacingType(para.lineSpacingType());
                        }
                        for (ASTInlineItem r : currentRuns) tailPara.addItem(r);
                        newParas.add(tailPara);
                    }
                }
                if (newParas.size() != tfb.paragraphs().size()) {
                    tfb.paragraphs().clear();
                    for (ASTParagraph p : newParas) tfb.addParagraph(p);
                }
            }
        }
        if (splitCount > 0) {
            System.err.println("[ResolvedToASTBuilder] Phase 4.5: " + splitCount + " inline-object paragraphs split");
        }
    }

    private void splitLargeNumberParagraphs(List<ASTSection> sections) {
        int splitCount = 0;
        for (ASTSection section : sections) {
            for (ASTBlock blk : section.blocks()) {
                if (!(blk instanceof ASTTextFrameBlock)) continue;
                ASTTextFrameBlock tfb = (ASTTextFrameBlock) blk;
                if (tfb.paragraphs() == null) continue;

                List<ASTParagraph> newParas = new ArrayList<>();
                for (ASTParagraph para : tfb.paragraphs()) {
                    List<ASTInlineItem> items = para.items();
                    if (items == null || items.size() < 2) { newParas.add(para); continue; }

                    // 첫 런이 큰 폰트 + 짧은 텍스트인지 감지
                    ASTInlineItem first = items.get(0);
                    if (first.itemType() != ASTInlineItem.ItemType.TEXT_RUN) { newParas.add(para); continue; }
                    ASTTextRun firstRun = (ASTTextRun) first;
                    Integer firstFs = firstRun.fontSizeHwpunits();
                    String firstText = firstRun.text();
                    if (firstFs == null || firstText == null || firstText.trim().length() > 3) { newParas.add(para); continue; }

                    // 나머지 런의 대표 폰트 크기 (가장 긴 텍스트)
                    int bodyFs = 0;
                    int bodyMaxLen = 0;
                    for (int i = 1; i < items.size(); i++) {
                        if (items.get(i).itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                            ASTTextRun tr = (ASTTextRun) items.get(i);
                            Integer fs = tr.fontSizeHwpunits();
                            String t = tr.text();
                            int len = (t != null) ? t.trim().length() : 0;
                            if (fs != null && len > bodyMaxLen) { bodyMaxLen = len; bodyFs = fs; }
                        }
                    }

                    if (bodyFs > 0 && firstFs > bodyFs * 1.5) {
                        // 분리: 첫 런만 별도 단락
                        ASTParagraph numPara = new ASTParagraph();
                        numPara.paragraphStyleRef(para.paragraphStyleRef());
                        numPara.alignment(para.alignment());
                        numPara.addItem(firstRun);
                        // 첫 런 크기에 맞는 줄간격 설정
                        numPara.lineSpacing((int)(firstFs * 1.3));
                        numPara.lineSpacingType("fixed");
                        newParas.add(numPara);

                        // 나머지 런들을 새 단락으로
                        ASTParagraph bodyPara = new ASTParagraph();
                        bodyPara.paragraphStyleRef(para.paragraphStyleRef());
                        bodyPara.alignment(para.alignment());
                        if (para.lineSpacing() != null) {
                            bodyPara.lineSpacing(para.lineSpacing());
                            bodyPara.lineSpacingType(para.lineSpacingType());
                        }
                        if (para.spaceBefore() != null) bodyPara.spaceBefore(para.spaceBefore());
                        if (para.spaceAfter() != null) bodyPara.spaceAfter(para.spaceAfter());
                        if (para.firstLineIndent() != null) bodyPara.firstLineIndent(para.firstLineIndent());
                        if (para.leftMargin() != null) bodyPara.leftMargin(para.leftMargin());
                        for (int i = 1; i < items.size(); i++) {
                            bodyPara.addItem(items.get(i));
                        }
                        newParas.add(bodyPara);
                        splitCount++;
                    } else {
                        newParas.add(para);
                    }
                }
                // 단락 리스트 교체
                if (newParas.size() != tfb.paragraphs().size()) {
                    tfb.paragraphs().clear();
                    for (ASTParagraph p : newParas) tfb.addParagraph(p);
                }
            }
        }
        if (splitCount > 0) {
            System.err.println("[ResolvedToASTBuilder] Phase 4.5: " + splitCount + " large-number paragraphs split");
        }
    }

    // Phase 5: 페이지 배경 PNG 주입
    // ═══════════════════════════════════════════════════

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
        return ctx;
    }

    // ═══════════════════════════════════════════════════
    // Phase 7: IDML 독립 이미지 프레임 배치
    // ═══════════════════════════════════════════════════

    /**
     * renderedFloatingItems에 포함되지 않은 IDML 이미지 프레임을 찾아 ASTFigure로 변환한다.
     * ExtendScript에서 렌더링하지 못한 독립 이미지(Group 내 이미지 포함)를 보완.
     */
    private void placeUnrenderedImages(List<ASTSection> sections) {
        ensureIdmlInfra();
        if (idmlDocument == null) return;

        // 이미 rendered된 ID 수집 (배경 PNG에 포함된 이미지는 중복 배치 방지)
        Set<String> renderedIds = new HashSet<>();
        for (RenderedGroup rg : resolvedData.allRenderedFloatingItems()) {
            renderedIds.add(String.valueOf(rg.id()));
            if (rg.childIds() != null) {
                for (int cid : rg.childIds()) renderedIds.add(String.valueOf(cid));
            }
        }
        for (RenderedGroup rt : resolvedData.allRenderedTextFrames()) {
            renderedIds.add(String.valueOf(rt.id()));
            if (rt.childIds() != null) {
                for (int cid : rt.childIds()) renderedIds.add(String.valueOf(cid));
            }
        }
        // 이미 ASTFigure로 배치된 sourceId 수집
        Set<String> placedSourceIds = new HashSet<>();
        for (ASTSection section : sections) {
            for (ASTBlock blk : section.blocks()) {
                if (blk.blockType() == ASTBlock.BlockType.FIGURE) {
                    ASTFigure fig = (ASTFigure) blk;
                    if (fig.sourceId() != null) placedSourceIds.add(fig.sourceId());
                }
            }
        }

        System.err.println("[Phase7] renderedIds=" + renderedIds.size() + " placedSourceIds=" + placedSourceIds.size());
        int imageCount = 0;
        int skippedRendered = 0, skippedPlaced = 0;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLSpread spread : idmlDocument.spreads()) {
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLPage page : spread.pages()) {
                int pageIdx = findSectionIndex(page, sections);
                if (pageIdx < 0 || pageIdx >= sections.size()) continue;

                List<kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLImageFrame> imageFrames = spread.getImageFramesOnPage(page);
                for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLImageFrame imgFrame : imageFrames) {
                    String selfId = imgFrame.selfId();
                    if (selfId == null) continue;

                    // IDML hex ID → decimal
                    String decimalId = selfId.startsWith("u")
                            ? String.valueOf(Integer.parseInt(selfId.substring(1), 16))
                            : selfId;

                    // 이미 rendered 또는 배치된 이미지는 건너뜀
                    if (renderedIds.contains(decimalId)) { skippedRendered++; continue; }
                    if (placedSourceIds.contains(selfId)) { skippedPlaced++; continue; }

                    // 이미지 로딩 및 ASTFigure 생성
                    ResolvedPage rPage = (pageIdx < resolvedData.pages().size())
                            ? resolvedData.pages().get(pageIdx) : null;
                    ASTFigure fig = ASTFigureBuilder.createFigureFromImageFrame(
                            imgFrame, page, imageLoader, colorResolver, resolvedData, rPage);
                    if (fig != null && fig.imageData() != null && fig.width() > 0 && fig.height() > 0) {
                        fig.fromGroup(true); // IN_FRONT_OF_TEXT
                        sections.get(pageIdx).addBlock(fig);
                        placedSourceIds.add(selfId);
                        imageCount++;
                    }
                }
            }
        }
        if (imageCount > 0 || skippedRendered > 0) {
            System.err.println("[ResolvedToASTBuilder] Phase 7: " + imageCount + " images placed, " + skippedRendered + " skipped(rendered), " + skippedPlaced + " skipped(placed)");
        }
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
