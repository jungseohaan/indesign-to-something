package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
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

    /** ParagraphStyle에서 미리 구한 스타일 속성 (런에서 없을 때 폴백용) */
    private static class StyleContext {
        final String fillColor;
        final Double tracking;
        final String fontFamily;
        final Double fontSize;

        StyleContext(String fillColor, Double tracking, String fontFamily, Double fontSize) {
            this.fillColor = fillColor;
            this.tracking = tracking;
            this.fontFamily = fontFamily;
            this.fontSize = fontSize;
        }
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
        return doc;
    }

    // ═══════════════════════════════════════════════════
    // Phase 0: IDML 정의 복사
    // ═══════════════════════════════════════════════════

    private void copyIDMLDefinitions(ASTDocument doc) {
        if (idmlDir == null) {
            System.err.println("[ResolvedToASTBuilder] Phase 0: idmlDir is null — skipping");
            return;
        }

        try {
            // Styles.xml에서 폰트, 단락스타일, 색상 파싱
            File stylesFile = new File(new File(idmlDir, "Resources"), "Styles.xml");
            if (!stylesFile.exists()) return;
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // IDML의 ParagraphStyle은 200+ 속성을 가질 수 있으므로 제한 해제
            try { dbf.setAttribute("http://www.oracle.com/xml/jaxp/properties/elementAttributeLimit", "0"); } catch (Exception e) {}
            try { dbf.setAttribute("http://www.oracle.com/xml/jaxp/properties/totalEntitySizeLimit", "0"); } catch (Exception e) {}
            DocumentBuilder db = dbf.newDocumentBuilder();
            org.w3c.dom.Document xmlDoc = db.parse(stylesFile);

            // 폰트 정의 수집 (Fonts.xml에서)
            File fontsFile = new File(new File(idmlDir, "Resources"), "Fonts.xml");
            if (fontsFile.exists()) {
                try {
                    org.w3c.dom.Document fontsDoc = db.parse(fontsFile);
                    org.w3c.dom.NodeList fontFamilies = fontsDoc.getElementsByTagName("FontFamily");
                    for (int i = 0; i < fontFamilies.getLength(); i++) {
                        org.w3c.dom.Element ff = (org.w3c.dom.Element) fontFamilies.item(i);
                        String name = ff.getAttribute("Name");
                        if (name != null && !name.isEmpty()) {
                            ASTFontDef fd = new ASTFontDef();
                            fd.fontFamily(name);
                            doc.addFont(fd);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[ResolvedToASTBuilder] Fonts.xml 파싱 실패: " + e.getMessage());
                }
            }

            // 단락 스타일 수집
            org.w3c.dom.NodeList paraStyles = xmlDoc.getElementsByTagName("ParagraphStyle");
            for (int i = 0; i < paraStyles.getLength(); i++) {
                org.w3c.dom.Element ps = (org.w3c.dom.Element) paraStyles.item(i);
                String self = ps.getAttribute("Self");
                String name = ps.getAttribute("Name");
                if (self != null && name != null) {
                    // 폰트 정보 추출
                    org.w3c.dom.NodeList appliedFonts = ps.getElementsByTagName("AppliedFont");
                    String font = null;
                    if (appliedFonts.getLength() > 0) {
                        font = appliedFonts.item(0).getTextContent();
                    }
                    ASTStyleDef sd = new ASTStyleDef();
                    sd.styleId(self);
                    sd.styleName(name);
                    if (font != null) sd.fontFamily(font);
                    String fontSize = ps.getAttribute("PointSize");
                    if (fontSize != null && !fontSize.isEmpty()) {
                        try { sd.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(Double.parseDouble(fontSize))); } catch (NumberFormatException e) {}
                    }
                    sd.textColor(ps.getAttribute("FillColor"));
                    sd.fontStyle(ps.getAttribute("FontStyle"));
                    // 정렬 (Justification)
                    String justification = ps.getAttribute("Justification");
                    if (justification != null && !justification.isEmpty()) {
                        sd.alignment(justification);
                    }
                    doc.addParagraphStyle(sd);
                }
            }
            System.err.println("[ResolvedToASTBuilder] Phase 0: fonts=" + doc.fonts().size()
                    + " styles=" + doc.paragraphStyles().size());
        } catch (Exception e) {
            System.err.println("[ResolvedToASTBuilder] IDML 정의 복사 실패: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════
    // Phase 1: 페이지/섹션 빌드
    // ═══════════════════════════════════════════════════

    private List<ASTSection> buildSections() {
        List<ASTSection> sections = new ArrayList<>();
        List<ResolvedPage> pages = resolvedData.pages();

        for (int i = 0; i < pages.size(); i++) {
            ResolvedPage page = pages.get(i);
            ASTSection section = new ASTSection();

            // 페이지 번호: name이 숫자면 사용, 아니면 인덱스+1
            int pageNum = i + 1;
            try { pageNum = Integer.parseInt(page.name()); } catch (NumberFormatException e) {}
            section.pageNumber(pageNum);

            // 페이지 레이아웃 (normalizeToPoints() 후 이미 pt 단위)
            ASTPageLayout layout = new ASTPageLayout();
            layout.pageWidth(CoordinateConverter.pointsToHwpunits(page.width()));
            layout.pageHeight(CoordinateConverter.pointsToHwpunits(page.height()));
            layout.marginTop(CoordinateConverter.pointsToHwpunits(page.marginTop()));
            layout.marginBottom(CoordinateConverter.pointsToHwpunits(page.marginBottom()));
            layout.marginLeft(CoordinateConverter.pointsToHwpunits(page.marginLeft()));
            layout.marginRight(CoordinateConverter.pointsToHwpunits(page.marginRight()));
            layout.columnCount(1);
            layout.columnGutter(0);
            section.layout(layout);

            sections.add(section);
        }

        // document pageIndex → section list index 매핑 빌드
        pageDocOffsetToSection = new HashMap<>();
        for (int i = 0; i < pages.size(); i++) {
            pageDocOffsetToSection.put(pages.get(i).index(), i);
        }

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

    private void placeTextFrames(List<ASTSection> sections) {
        List<ResolvedTextFrame> frames = resolvedData.textFrames();

        for (ResolvedTextFrame tf : frames) {
            // 인라인 프레임은 Phase 3에서 처리
            // 단, non-editable + non-rendered + story 미공유 인라인이면 플로팅 전환
            boolean inlineToFloating = false;
            if (tf.isInline()) {
                if (!resolvedData.isEditableTextFrame(tf.id())) {
                    String vis = tf.frameVisibleText();
                    boolean hasText = vis != null && vis.replace("\uFFFC", "").replace("\r", "").replace("\n", "").trim().length() > 5;
                    int domIdInt = -1;
                    try { domIdInt = Integer.parseInt(tf.id()); } catch (NumberFormatException e) {}
                    boolean rendered = domIdInt >= 0 && resolvedData.isRenderedByOtherChannel(domIdInt);
                    boolean sharedWithEditable = false;
                    if (tf.storyId() != null) {
                        for (ResolvedTextFrame other : frames) {
                            if (tf.storyId().equals(other.storyId()) && resolvedData.isEditableTextFrame(other.id())) {
                                sharedWithEditable = true;
                                break;
                            }
                        }
                    }
                    // parentId가 있으면 다른 객체 안에 중첩 → 배경에서 부모와 함께 숨겨짐
                    boolean hasParent = false;
                    ResolvedPageItem rpi = resolvedData.getPageItem(tf.id());
                    if (rpi != null && rpi.parentId() != null) hasParent = true;
                    if (hasText && !rendered && !sharedWithEditable && hasParent) {
                        inlineToFloating = true;
                    } else {
                        continue;
                    }
                } else {
                    continue;
                }
            }

            // 다른 TextFrame 안에 중첩된 프레임은 건너뜀 (부모가 배경에 포함)
            if (!inlineToFloating && isNestedInTextFrame(tf)) continue;

            // 배경에 포함된 프레임은 건너뜀 (editable 프레임만 글상자로 배치)
            // 단, 같은 story를 editable TF와 공유하는 non-editable TF는 배치
            if (!inlineToFloating && !resolvedData.isEditableTextFrame(tf.id())) {
                boolean sharedWithEditable = false;
                if (tf.storyId() != null) {
                    for (ResolvedTextFrame other : frames) {
                        if (tf.storyId().equals(other.storyId()) && resolvedData.isEditableTextFrame(other.id())) {
                            sharedWithEditable = true;
                            break;
                        }
                    }
                }
                if (!sharedWithEditable) continue;
            }

            // 연결 글상자 체인: 후속 프레임은 건너뜀 (첫 프레임에서 병합 처리)
            // 단, 체인의 프레임들이 Y 방향으로 떨어져 있으면 병합하지 않음 (각각 배치)
            if (tf.previousFrameId() != null) {
                ResolvedTextFrame prevTf = resolvedData.getTextFrame(tf.previousFrameId());
                if (prevTf != null && prevTf.geometricBounds() != null && tf.geometricBounds() != null) {
                    // 다른 페이지에 있으면 독립 배치
                    boolean diffPage = prevTf.pageIndex() != tf.pageIndex();
                    double prevBottom = prevTf.geometricBounds()[2];
                    double curTop = tf.geometricBounds()[0];
                    double gap = curTop - prevBottom;
                    double lineH = tf.geometricBounds()[2] - tf.geometricBounds()[0];
                    // gap이 한 줄 높이 이상이거나 다른 페이지이면 독립 배치
                    if (diffPage || gap > lineH * 0.5) {
                        // 병합하지 않고 독립 배치 → continue하지 않음
                    } else {
                        continue; // 인접 → 병합 (첫 프레임에서 처리)
                    }
                } else {
                    continue;
                }
            }

            // 페이지 인덱스 결정 (document offset → section index 매핑)
            int pageIdx = toSectionIndex(tf.pageIndex());
            if (pageIdx < 0 || pageIdx >= sections.size()) continue;

            // 좌표 계산: geometricBounds는 spread 좌표 (applyScale 후 pt)
            // → page bounds를 빼서 page-relative로 변환
            double[] gb = tf.geometricBounds();
            if (gb == null || gb.length < 4) continue;

            ResolvedPage rPage = (pageIdx < resolvedData.pages().size())
                    ? resolvedData.pages().get(pageIdx) : null;
            double pageLeft = (rPage != null && rPage.bounds() != null) ? rPage.bounds()[1] : 0;
            double pageTop = (rPage != null && rPage.bounds() != null) ? rPage.bounds()[0] : 0;

            // 연결 글상자 체인이면 인접한 프레임만 bounds 합산 (복사본 사용)
            if (tf.nextFrameId() != null) {
                gb = new double[]{gb[0], gb[1], gb[2], gb[3]};
                String nextId = tf.nextFrameId();
                while (nextId != null) {
                    ResolvedTextFrame next = resolvedData.getTextFrame(nextId);
                    if (next == null || next.geometricBounds() == null) break;
                    double[] ngb = next.geometricBounds();
                    // 다른 페이지이거나 Y 간격이 한 줄 높이의 50% 이상이면 합산 중단
                    if (next.pageIndex() != tf.pageIndex()) break;
                    double gap = ngb[0] - gb[2];
                    double lineH = ngb[2] - ngb[0];
                    if (gap > lineH * 0.5) break;
                    if (ngb[0] < gb[0]) gb[0] = ngb[0];
                    if (ngb[1] < gb[1]) gb[1] = ngb[1];
                    if (ngb[2] > gb[2]) gb[2] = ngb[2];
                    if (ngb[3] > gb[3]) gb[3] = ngb[3];
                    nextId = next.nextFrameId();
                }
            }

            // facing pages: InDesign geometricBounds가 이미 page-relative인 경우 감지
            // gb.left < pageBounds.left이면 spread 좌표가 아닌 page-relative 좌표
            boolean gbAlreadyPageRelative = (pageLeft > 0 && gb[1] < pageLeft);
            double x = gbAlreadyPageRelative ? gb[1] : (gb[1] - pageLeft);
            double y = gb[0] - pageTop;
            double w = gb[3] - gb[1];
            double h = gb[2] - gb[0];

            ASTSection section = sections.get(pageIdx);

            // 음수 좌표 클램핑
            if (x < 0) { w += x; x = 0; }
            if (y < 0) { h += y; y = 0; }
            if (w <= 0 || h <= 0) continue;

            // composedLines 기반 글상자 분할
            if (tf.composedLines() != null && tf.composedLines().size() > 1) {
                // 1) wrap indent 기반 분할 (텍스트가 이미지를 비껴가는 경우)
                // placeByWrapIndent는 Phase 5에서 후처리 (Phase 3 변환 파이프라인 유지)
                // if (placeByWrapIndent(tf, section, pageLeft, pageTop)) continue;
                // 2) Y 점프 기반 분할 (큰 수직 갭이 있는 경우)
                if (placeByYGapSplit(tf, section, pageLeft, pageTop)) {
                    continue;
                }
            }

            ASTTextFrameBlock block = new ASTTextFrameBlock();
            block.sourceId("u" + Integer.toHexString(Integer.parseInt(tf.id())));
            block.x(CoordinateConverter.pointsToHwpunits(x));
            block.y(CoordinateConverter.pointsToHwpunits(y));
            block.width(CoordinateConverter.pointsToHwpunits(w));
            block.height(CoordinateConverter.pointsToHwpunits(h));
            block.zOrder(tf.zOrder());
            block.columnCount(tf.columnCount() > 0 ? tf.columnCount() : 1);
            block.columnGutter(CoordinateConverter.pointsToHwpunits(tf.columnGutter() * scaleFactor));

            // 내부 여백 (insetSpacing — 이미 pt로 스케일됨)
            if (tf.insetSpacing() != null) {
                double[] inset = tf.insetSpacing();
                block.insetTop(CoordinateConverter.pointsToHwpunits(inset[0]));
                block.insetLeft(CoordinateConverter.pointsToHwpunits(inset[1]));
                block.insetBottom(CoordinateConverter.pointsToHwpunits(inset[2]));
                block.insetRight(CoordinateConverter.pointsToHwpunits(inset[3]));
            }

            // 수직 정렬
            if (tf.verticalJustification() != null) {
                block.verticalJustification(tf.verticalJustification());
            }

            if (tf.rotationAngle() != 0) {
                block.rotationAngle(tf.rotationAngle());
            }

            // 시각 속성: 배경색은 배경 PNG에 포함됨 (텍스트만 비우고 프레임은 유지)
            // HWPX 글상자에는 fillColor를 적용하지 않음 (이중 표시 방지)

            // overflow 감지용 텍스트 길이 저장
            String visText = tf.frameVisibleText();
            if (visText != null) {
                block.frameVisibleTextLength(visText.replace("\uFFFC", "").replace("\n", "").replace("\r", "").length());
            }
            // storyTotalTextLength는 convertStories()에서 설정

            section.addBlock(block);
        }
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
        int splitCount = 0;
        for (ASTSection section : sections) {
            List<ASTBlock> newBlocks = new ArrayList<>();
            for (ASTBlock blk : section.blocks()) {
                if (!(blk instanceof ASTTextFrameBlock)) {
                    newBlocks.add(blk);
                    continue;
                }
                ASTTextFrameBlock tfb = (ASTTextFrameBlock) blk;
                String srcId = tfb.sourceId();
                // sourceId → resolved TextFrame → composedLines
                String hexPart = srcId != null && srcId.startsWith("u")
                        ? srcId.substring(1) : null;
                if (hexPart == null) { newBlocks.add(blk); continue; }
                if (hexPart.contains("_")) hexPart = hexPart.substring(0, hexPart.indexOf('_'));
                String domId;
                try { domId = String.valueOf(Integer.parseInt(hexPart, 16)); }
                catch (NumberFormatException e) { newBlocks.add(blk); continue; }

                ResolvedTextFrame rtf = resolvedData.getTextFrame(domId);
                if (rtf == null) { newBlocks.add(blk); continue; }

                // 연결 글상자 체인이면 모든 TF의 composedLines를 합침
                List<ResolvedTextFrame.ComposedLine> allComposedLines = new ArrayList<>();
                if (rtf.composedLines() != null) allComposedLines.addAll(rtf.composedLines());
                if (rtf.nextFrameId() != null) {
                    String nextId = rtf.nextFrameId();
                    while (nextId != null) {
                        ResolvedTextFrame nextTf = resolvedData.getTextFrame(nextId);
                        if (nextTf == null) break;
                        if (nextTf.composedLines() != null) allComposedLines.addAll(nextTf.composedLines());
                        nextId = nextTf.nextFrameId();
                    }
                }
                if (allComposedLines.size() < 2) {
                    newBlocks.add(blk);
                    continue;
                }
                boolean isLinkedChain = allComposedLines.size() > (rtf.composedLines() != null ? rtf.composedLines().size() : 0);
                // 문단이 비어있는 블록은 분할하지 않음 (다단 분할 등으로 문단 미배분)
                if (tfb.paragraphs() == null || tfb.paragraphs().isEmpty()) {
                    newBlocks.add(blk);
                    continue;
                }

                List<ResolvedTextFrame.ComposedLine> lines = allComposedLines;

                // YGapSplit 블록: 해당 paraIndex 범위의 composedLines만 사용
                if (srcId != null && srcId.contains("_g") && tfb.composedCharStart() >= 0) {
                    int tfParaStart = rtf.paragraphStart();
                    int gParaStart = tfb.composedCharStart();
                    int gParaEnd = tfb.composedCharEnd();
                    // 절대 paraIndex → TF 내부 상대 paraIndex
                    int relStart = gParaStart - tfParaStart;
                    int relEnd = gParaEnd - tfParaStart;
                    List<ResolvedTextFrame.ComposedLine> filtered = new ArrayList<>();
                    for (ResolvedTextFrame.ComposedLine cl : lines) {
                        int pi = cl.paraIndex();
                        if (pi >= relStart && pi <= relEnd) filtered.add(cl);
                    }
                    lines = filtered;
                    if (lines.size() < 2) { newBlocks.add(blk); continue; }
                }

                // wrap 감지: 좌/우 indent > threshold 가 3행 이상 연속
                // (normalizeToPoints 후 indent는 pt 단위)
                double[] gb0 = rtf.geometricBounds();
                double frameW0 = gb0[3] - gb0[1];
                // 연결 글상자 체인은 indent가 작아도 일관되면 textwrap → threshold 낮춤
                double indentThreshold = frameW0 * (isLinkedChain ? 0.12 : 0.20);
                int indentedConsecutive = 0;
                int maxConsecutive = 0;
                for (int i = 0; i < lines.size(); i++) {
                    double indR = lines.get(i).wrapIndentRight();
                    double indL = lines.get(i).wrapIndentLeft();
                    if ((indR > indentThreshold || indL > indentThreshold) && i < lines.size() - 1) {
                        indentedConsecutive++;
                        maxConsecutive = Math.max(maxConsecutive, indentedConsecutive);
                    } else {
                        indentedConsecutive = 0;
                    }
                }
                if (maxConsecutive < 3) {
                    // 대부분의 줄에 일관된 indent가 있으면 폭을 중앙값만큼 축소 (분할 대신 shrink)
                    // 안전 장치: 최소값 사용, 프레임 폭의 15% 이상 축소 안 함
                    if (lines.size() >= 3) {
                        double maxShrink = frameW0 * 0.15; // 최대 축소량
                        List<Double> indRs2 = new ArrayList<>(), indLs2 = new ArrayList<>();
                        for (ResolvedTextFrame.ComposedLine cl : lines) {
                            if (cl.wrapIndentRight() > 10) indRs2.add(cl.wrapIndentRight());
                            if (cl.wrapIndentLeft() > 10) indLs2.add(cl.wrapIndentLeft());
                        }
                        if (indRs2.size() >= lines.size() / 2) {
                            java.util.Collections.sort(indRs2);
                            // 최소값 사용 (문단 끝 줄의 큰 indR 무시)
                            double shrinkR = Math.min(indRs2.get(0), maxShrink);
                            tfb.width(tfb.width() - CoordinateConverter.pointsToHwpunits(shrinkR));
                        }
                        if (indLs2.size() >= lines.size() / 2) {
                            java.util.Collections.sort(indLs2);
                            double shrinkL = Math.min(indLs2.get(0), maxShrink);
                            tfb.x(tfb.x() + CoordinateConverter.pointsToHwpunits(shrinkL));
                            tfb.width(tfb.width() - CoordinateConverter.pointsToHwpunits(shrinkL));
                        }
                    }
                    newBlocks.add(blk);
                    continue;
                }

                // indent 패턴으로 행 그룹 분할: 넓은 vs 좁은(좌/우 indent 큰) 연속 구간
                // 1~2행만 다른 패턴이면 앞/뒤 그룹에 병합 (너무 세밀한 분할 방지)
                // Step 1: 행별 narrow 판정 (좌/우 어느 쪽이든 threshold 초과)
                // narrowDir: 0=wide, 1=narrowRight, 2=narrowLeft, 3=narrowBoth
                int[] narrowDir = new int[lines.size()];
                boolean[] narrowFlags = new boolean[lines.size()];
                for (int i = 0; i < lines.size(); i++) {
                    double indR = lines.get(i).wrapIndentRight();
                    double indL = lines.get(i).wrapIndentLeft();
                    boolean isLast = (i >= lines.size() - 1);
                    // 문단 끝 줄(\r로 끝남)의 indR은 텍스트가 짧아서 생기는 빈 공간 → narrow 제외
                    String lineText = lines.get(i).text();
                    boolean isParagraphEnd = lineText != null && lineText.endsWith("\r");
                    boolean nr = indR > indentThreshold && !isLast && !isParagraphEnd;
                    boolean nl = indL > indentThreshold && !isLast;
                    narrowFlags[i] = nr || nl;
                    if (nr && nl) narrowDir[i] = 3;
                    else if (nl) narrowDir[i] = 2;
                    else if (nr) narrowDir[i] = 1;
                    else narrowDir[i] = 0;
                }
                // Step 2: 1~2행짜리 구간은 이전 구간에 병합 (좁은→넓은 방향만, 또는 마지막 구간)
                for (int i = 1; i < lines.size(); i++) {
                    if (narrowDir[i] != narrowDir[i - 1]) {
                        int runLen = 1;
                        while (i + runLen < lines.size() && narrowDir[i + runLen] == narrowDir[i]) runLen++;
                        if (runLen <= 2) {
                            boolean mergeToWide = narrowFlags[i]; // 좁은 구간이면 넓은으로
                            boolean isTrailing = (i + runLen >= lines.size()); // 마지막 구간
                            if (mergeToWide || isTrailing) {
                                for (int j = i; j < i + runLen && j < lines.size(); j++) {
                                    narrowFlags[j] = narrowFlags[i - 1];
                                    narrowDir[j] = narrowDir[i - 1];
                                }
                            }
                        }
                    }
                }
                // Step 3: 그룹 생성 (narrowDir이 다르면 새 그룹)
                List<List<ResolvedTextFrame.ComposedLine>> groups = new ArrayList<>();
                List<ResolvedTextFrame.ComposedLine> currentGroup = new ArrayList<>();
                for (int i = 0; i < lines.size(); i++) {
                    if (i > 0 && narrowDir[i] != narrowDir[i - 1] && !currentGroup.isEmpty()) {
                        groups.add(currentGroup);
                        currentGroup = new ArrayList<>();
                    }
                    currentGroup.add(lines.get(i));
                }
                if (!currentGroup.isEmpty()) groups.add(currentGroup);
                // 마지막 그룹이 실질 텍스트가 적으면 이전 그룹에 병합
                // (빈 줄, 인라인 마커, 짧은 문장 끝부분 등)
                while (groups.size() > 1) {
                    List<ResolvedTextFrame.ComposedLine> lastGrp = groups.get(groups.size() - 1);
                    String lastText = "";
                    int substantiveLines = 0;
                    for (ResolvedTextFrame.ComposedLine cl : lastGrp) {
                        String t = cl.text();
                        if (t != null) {
                            lastText += t;
                            String stripped = t.replace("\r", "").replace("\n", "").replace("\uFFFC", "").trim();
                            if (!stripped.isEmpty()) substantiveLines++;
                        }
                    }
                    lastText = lastText.replace("\r", "").replace("\n", "").replace("\uFFFC", "").trim();
                    // 실질 텍스트 줄이 1줄 이하이면 병합
                    if (substantiveLines <= 1) {
                        groups.remove(groups.size() - 1);
                        groups.get(groups.size() - 1).addAll(lastGrp);
                    } else {
                        break;
                    }
                }
                System.err.println("[WrapPhase5] tf=" + domId + " groups=" + groups.size() + " narrowDir=" + java.util.Arrays.toString(narrowDir));
                if (groups.size() <= 1) {
                    // 전체가 narrow이면 블록 폭을 일관된 indent만큼 축소
                    if (groups.size() == 1 && narrowFlags[0]) {
                        int firstLineIdx2 = 0;
                        int groupDir2 = narrowDir[firstLineIdx2];
                        // 줄별 indent를 수집하여 하위 25% 값 사용 (outlier 제거)
                        double adjIndR = 0, adjIndL = 0;
                        if (groupDir2 == 1 || groupDir2 == 3) {
                            for (ResolvedTextFrame.ComposedLine cl : lines) {
                                if (cl.wrapIndentRight() > adjIndR) adjIndR = cl.wrapIndentRight();
                            }
                        }
                        if (groupDir2 == 2 || groupDir2 == 3) {
                            for (ResolvedTextFrame.ComposedLine cl : lines) {
                                if (cl.wrapIndentLeft() > adjIndL) adjIndL = cl.wrapIndentLeft();
                            }
                        }
                        if (adjIndR > 0) {
                            tfb.width(tfb.width() - CoordinateConverter.pointsToHwpunits(adjIndR));
                        }
                        if (adjIndL > 0) {
                            tfb.x(tfb.x() + CoordinateConverter.pointsToHwpunits(adjIndL));
                            tfb.width(tfb.width() - CoordinateConverter.pointsToHwpunits(adjIndL));
                        }
                        System.err.println("[WrapNarrow]   newW=" + tfb.width());
                    }
                    newBlocks.add(blk);
                    continue;
                }

                // 블록의 단락 텍스트를 연결하여 문자 범위 매핑
                StringBuilder storyText = new StringBuilder();
                List<int[]> paraRanges = new ArrayList<>();
                if (tfb.paragraphs() != null) {
                    for (ASTParagraph p : tfb.paragraphs()) {
                        int s = storyText.length();
                        String pt = getParaPlainText(p);
                        storyText.append(pt != null ? pt : "");
                        paraRanges.add(new int[]{s, storyText.length()});
                    }
                }

                // 각 그룹의 문자 범위 계산 (composedLine 텍스트 누적)
                double[] gb = rtf.geometricBounds();
                double frameW = gb[3] - gb[1];
                System.err.println("[WrapPhase5] tf=" + domId + " sourceId=" + tfb.sourceId() + " frameW=" + String.format("%.1f", frameW) + " lines=" + lines.size() + " maxConsR=" + maxConsecutive);

                int charOffset = 0;
                for (int gi = 0; gi < groups.size(); gi++) {
                    List<ResolvedTextFrame.ComposedLine> group = groups.get(gi);
                    ResolvedTextFrame.ComposedLine first = group.get(0);
                    ResolvedTextFrame.ComposedLine last = group.get(group.size() - 1);

                    // 그룹의 indent 계산: narrow 그룹은 해당 방향의 최소 indent
                    // wide 그룹도 일관된 indent가 있으면 반영 (사이드바 등)
                    int firstLineIdx = lines.indexOf(group.get(0));
                    int groupDir = firstLineIdx >= 0 ? narrowDir[firstLineIdx] : 0;
                    double indR = 0;
                    double indL = 0;
                    if (groupDir != 0) {
                        // narrowRight(1) 또는 both(3) → indR 사용
                        if (groupDir == 1 || groupDir == 3) {
                            indR = Double.MAX_VALUE;
                            for (ResolvedTextFrame.ComposedLine cl : group) {
                                if (cl.wrapIndentRight() > 0) indR = Math.min(indR, cl.wrapIndentRight());
                            }
                            if (indR == Double.MAX_VALUE) indR = 0;
                        }
                        // narrowLeft(2) 또는 both(3) → indL 사용
                        if (groupDir == 2 || groupDir == 3) {
                            indL = Double.MAX_VALUE;
                            for (ResolvedTextFrame.ComposedLine cl : group) {
                                if (cl.wrapIndentLeft() > 0) indL = Math.min(indL, cl.wrapIndentLeft());
                            }
                            if (indL == Double.MAX_VALUE) indL = 0;
                        }
                    } else {
                        // wide 그룹이라도 일관된 indent가 있으면 폭에 반영
                        double minIndR = Double.MAX_VALUE;
                        double minIndL = Double.MAX_VALUE;
                        boolean allHaveR = true, allHaveL = true;
                        for (ResolvedTextFrame.ComposedLine cl : group) {
                            if (cl.wrapIndentRight() > 0) minIndR = Math.min(minIndR, cl.wrapIndentRight());
                            else allHaveR = false;
                            if (cl.wrapIndentLeft() > 0) minIndL = Math.min(minIndL, cl.wrapIndentLeft());
                            else allHaveL = false;
                        }
                        if (allHaveR && minIndR != Double.MAX_VALUE && minIndR > 10) indR = minIndR;
                        if (allHaveL && minIndL != Double.MAX_VALUE && minIndL > 10) indL = minIndL;
                    }

                    // frameW, gb, indent, bounds 모두 pt (normalizeToPoints 적용)
                    double groupW = frameW - indL - indR;
                    double groupH = last.bounds()[2] - first.bounds()[0];
                    // Y 위치: _g 블록은 tfb.y()가 이미 절대 좌표 → 그룹 내 상대 오프셋 사용
                    boolean isYGapBlock = srcId != null && srcId.contains("_g");
                    double groupY;
                    if (isYGapBlock) {
                        // _g 블록의 첫 composedLine과 필터링된 lines의 첫 줄 사이 오프셋
                        double blockFirstLineTop = lines.get(0).bounds()[0];
                        groupY = tfb.y() + CoordinateConverter.pointsToHwpunits(
                                first.bounds()[0] - blockFirstLineTop);
                    } else {
                        groupY = tfb.y() + CoordinateConverter.pointsToHwpunits(
                                first.bounds()[0] - gb[0]);
                    }

                    if (groupW <= 0 || groupH <= 0) {
                        charOffset += groupTextLen(group);
                        continue;
                    }

                    // 그룹의 문자 범위
                    int groupCharStart = charOffset;
                    int groupCharEnd = charOffset + groupTextLen(group);
                    charOffset = groupCharEnd;

                    // 새 블록 생성
                    ASTTextFrameBlock splitBlock = new ASTTextFrameBlock();
                    splitBlock.sourceId(tfb.sourceId());
                    splitBlock.x(tfb.x() + CoordinateConverter.pointsToHwpunits(indL));
                    splitBlock.y((long) groupY);
                    splitBlock.width(CoordinateConverter.pointsToHwpunits(groupW));
                    splitBlock.height(CoordinateConverter.pointsToHwpunits(groupH));
                    splitBlock.zOrder(tfb.zOrder());
                    splitBlock.columnCount(1);
                    splitBlock.insetTop(tfb.insetTop());
                    splitBlock.insetLeft(tfb.insetLeft());
                    splitBlock.insetBottom(tfb.insetBottom());
                    splitBlock.insetRight(tfb.insetRight());

                    // 문자 범위에 해당하는 단락 분배
                    if (tfb.paragraphs() != null) {
                        for (int pi = 0; pi < tfb.paragraphs().size(); pi++) {
                            int paraStart = paraRanges.get(pi)[0];
                            int paraEnd = paraRanges.get(pi)[1];
                            if (paraEnd <= groupCharStart) continue;
                            if (paraStart >= groupCharEnd) break;
                            if (paraStart >= groupCharStart && paraEnd <= groupCharEnd) {
                                splitBlock.addParagraph(tfb.paragraphs().get(pi));
                            } else if (paraStart < groupCharEnd && paraEnd > groupCharEnd) {
                                int cutLen = groupCharEnd - paraStart;
                                ASTParagraph trimmed = createSplitParagraph(tfb.paragraphs().get(pi),
                                        getParaPlainText(tfb.paragraphs().get(pi)) != null
                                                ? getParaPlainText(tfb.paragraphs().get(pi)).substring(0, Math.min(cutLen, getParaPlainText(tfb.paragraphs().get(pi)).length()))
                                                : "");
                                if (trimmed != null) splitBlock.addParagraph(trimmed);
                            } else if (paraStart < groupCharStart && paraEnd > groupCharStart) {
                                int skipLen = groupCharStart - paraStart;
                                String fullText = getParaPlainText(tfb.paragraphs().get(pi));
                                String contText = (fullText != null && skipLen < fullText.length())
                                        ? fullText.substring(skipLen) : "";
                                ASTParagraph cont = createContinuationParagraph(tfb.paragraphs().get(pi), skipLen, contText);
                                if (cont != null) splitBlock.addParagraph(cont);
                            }
                        }
                    }

                    System.err.println("[WrapSplit] group " + gi + ": w=" + splitBlock.width() + " h=" + splitBlock.height() + " y=" + splitBlock.y() + " paras=" + (splitBlock.paragraphs() != null ? splitBlock.paragraphs().size() : 0));
                    newBlocks.add(splitBlock);
                }
                splitCount++;
            }
            // 블록 리스트 교체
            section.blocks().clear();
            for (ASTBlock b : newBlocks) section.addBlock(b);
        }
        if (splitCount > 0) {
            System.err.println("[SplitByWrapIndent] " + splitCount + " blocks split");
        }
    }

    private int groupTextLen(List<ResolvedTextFrame.ComposedLine> group) {
        int len = 0;
        for (ResolvedTextFrame.ComposedLine cl : group) {
            String t = cl.text();
            if (t != null) len += t.replace("\r", "").length();
        }
        return len;
    }

    /**
     * (레거시) composedLines의 wrapIndent(X축 밀림)를 감지하여 글상자를 분할한다.
     * Phase 5 splitByWrapIndent로 대체됨.
     */
    private boolean placeByWrapIndent(ResolvedTextFrame tf, ASTSection section,
                                       double pageLeft, double pageTop) {
        List<ResolvedTextFrame.ComposedLine> lines = tf.composedLines();
        if (lines == null || lines.size() < 2) return false;

        // 실제 wrap 감지: 오른쪽 indent > 30pt가 3행 이상 연속 (이미지 비껴가기)
        int rightIndentedLines = 0;
        int maxConsecutiveRight = 0;
        for (int i = 0; i < lines.size(); i++) {
            double indR = lines.get(i).wrapIndentRight();
            if (indR > 30.0 && i < lines.size() - 1) {
                rightIndentedLines++;
                maxConsecutiveRight = Math.max(maxConsecutiveRight, rightIndentedLines);
            } else {
                rightIndentedLines = 0;
            }
        }
        // 작은 indent(<10pt)는 프레임 모양/인셋 차이일 뿐
        boolean hasRightWrap = maxConsecutiveRight >= 3;
        if (!hasRightWrap) return false; // 현재는 오른쪽 wrap만 지원

        // 행을 indent 패턴 그룹으로 분할
        // 같은 indent 패턴(좌/우 밀림 유사)인 연속 행을 하나의 그룹으로
        List<List<ResolvedTextFrame.ComposedLine>> groups = new ArrayList<>();
        List<ResolvedTextFrame.ComposedLine> currentGroup = new ArrayList<>();
        double curIndentR = 0;

        for (int i = 0; i < lines.size(); i++) {
            ResolvedTextFrame.ComposedLine cl = lines.get(i);
            double indR = cl.wrapIndentRight();

            // indent 변화 분할 기준: 오른쪽의 큰 변화 (30pt 이상), 마지막 행은 분할 제외
            boolean isLastLine = (i == lines.size() - 1);
            boolean indentChanged = !isLastLine
                    && (Math.abs(indR - curIndentR) > 30.0 && (indR > 30.0 || curIndentR > 30.0));

            if (indentChanged && !currentGroup.isEmpty()) {
                groups.add(currentGroup);
                currentGroup = new ArrayList<>();
            }
            currentGroup.add(cl);
            curIndentR = indR;
        }
        if (!currentGroup.isEmpty()) groups.add(currentGroup);

        // 그룹이 1개면 분할 불필요
        if (groups.size() <= 1) return false;

        System.err.println("[WrapIndent] TF " + tf.id() + " → " + groups.size() + " groups");

        // 각 그룹을 별도 글상자로 배치
        double[] gb = tf.geometricBounds();
        double frameW = gb[3] - gb[1];

        for (List<ResolvedTextFrame.ComposedLine> group : groups) {
            ResolvedTextFrame.ComposedLine first = group.get(0);
            ResolvedTextFrame.ComposedLine last = group.get(group.size() - 1);

            // 그룹 내 대표 indent (마지막 행 제외한 최대값)
            double indL = 0, indR = 0;
            for (int gi = 0; gi < group.size() - 1; gi++) {
                indL = Math.max(indL, group.get(gi).wrapIndentLeft());
                indR = Math.max(indR, group.get(gi).wrapIndentRight());
            }
            if (group.size() == 1) {
                indL = first.wrapIndentLeft();
                indR = first.wrapIndentRight();
            }

            // 글상자 좌표: 프레임 bounds 기준 (spread 좌표 → page-relative)
            // facing pages 보정: pageRelativeBounds가 있으면 사용
            double frameX = gb[1] - pageLeft;
            double frameY = gb[0] - pageTop;
            boolean gbPageRelative = (pageLeft > 0 && gb[1] < pageLeft);
            if (gbPageRelative) { frameX = gb[1]; frameY = gb[0] - pageTop; }

            // 글상자 X 위치: 프레임 원본 X 사용 (indL은 첫 줄 들여쓰기일 수 있으므로 위치에 반영 안 함)
            double groupX = frameX;
            double groupY = frameY + (first.bounds()[0] - gb[0]);
            double groupW = frameW - indL - indR;
            double groupH = last.bounds()[2] - first.bounds()[0];

            // 줄바꿈 방지: groupW가 너무 좁으면 프레임 전체 폭으로 확장
            if (groupW < frameW * 0.15) groupW = frameW;

            if (groupX < 0) groupX = 0;
            if (groupW <= 0 || groupH <= 0) continue;

            ASTTextFrameBlock block = new ASTTextFrameBlock();
            block.sourceId("u" + Integer.toHexString(Integer.parseInt(tf.id())));
            block.x(CoordinateConverter.pointsToHwpunits(groupX));
            block.y(CoordinateConverter.pointsToHwpunits(groupY));
            block.width(CoordinateConverter.pointsToHwpunits(groupW));
            block.height(CoordinateConverter.pointsToHwpunits(groupH));
            block.zOrder(tf.zOrder());
            block.columnCount(1);
            // composedLines 텍스트로 직접 단락 생성 + 인라인 객체 로드
            // resolved Story에서 anchoredObjectId 목록 수집 (인라인 객체 로드용)
            List<Integer> storyAnchorIds = new ArrayList<>();
            ResolvedStory rs = resolvedData.getStory(tf.storyId());
            if (rs != null) {
                for (ResolvedParagraph rp : rs.paragraphs()) {
                    if (rp.runs() != null) {
                        for (ResolvedRun rr : rp.runs()) {
                            if (rr.anchoredObjectId() != null) {
                                storyAnchorIds.add(rr.anchoredObjectId());
                            }
                        }
                    }
                }
            }
            int anchorIdx = 0;

            int prevParaIdx = -1;
            ASTParagraph curPara = null;
            for (ResolvedTextFrame.ComposedLine cl : group) {
                if (cl.paraIndex() != prevParaIdx || curPara == null) {
                    curPara = new ASTParagraph();
                    block.addParagraph(curPara);
                    prevParaIdx = cl.paraIndex();
                }
                String lineText = cl.text();
                if (lineText != null && !lineText.isEmpty()) {
                    if (lineText.endsWith("\r")) lineText = lineText.substring(0, lineText.length() - 1);
                    if (cl.runs() != null && !cl.runs().isEmpty()) {
                        for (ResolvedTextFrame.ComposedRun cr : cl.runs()) {
                            String runText = cr.text();
                            if (runText == null || runText.isEmpty()) continue;
                            if (runText.endsWith("\r")) runText = runText.substring(0, runText.length() - 1);
                            // \uFFFC → 인라인 객체 로드
                            if (runText.contains("\uFFFC")) {
                                String[] parts = runText.split("\uFFFC", -1);
                                for (int ffi = 0; ffi < parts.length; ffi++) {
                                    if (!parts[ffi].isEmpty()) {
                                        ASTTextRun tr = new ASTTextRun();
                                        tr.text(parts[ffi]);
                                        if (cr.fillColor() != null) tr.textColor(resolveColorToHex(cr.fillColor()));
                                        if (cr.fontFamily() != null) tr.fontFamily(cr.fontFamily());
                                        if (cr.fontStyle() != null) tr.fontStyle(cr.fontStyle());
                                        if (cr.fontSize() != null && cr.fontSize() > 0)
                                            tr.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(cr.fontSize()));
                                        curPara.addItem(tr);
                                    }
                                    if (ffi < parts.length - 1 && anchorIdx < storyAnchorIds.size()) {
                                        int aid = storyAnchorIds.get(anchorIdx++);
                                        ASTInlineObject inlineObj = loadInlineObject(aid);
                                        if (inlineObj != null) {
                                            curPara.addItem(inlineObj);
                                        }
                                    }
                                }
                            } else {
                                ASTTextRun run = new ASTTextRun();
                                run.text(runText);
                                if (cr.fillColor() != null) run.textColor(resolveColorToHex(cr.fillColor()));
                                if (cr.fontFamily() != null) run.fontFamily(cr.fontFamily());
                                if (cr.fontStyle() != null) run.fontStyle(cr.fontStyle());
                                if (cr.fontSize() != null && cr.fontSize() > 0)
                                    run.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(cr.fontSize()));
                                curPara.addItem(run);
                            }
                        }
                    } else {
                        ASTTextRun run = new ASTTextRun();
                        run.text(lineText.replace('\uFFFC', ' '));
                        curPara.addItem(run);
                    }
                }
            }

            section.addBlock(block);
        }

        return true;
    }

    /**
     * composedLines에서 Y 간격이 비정상적으로 큰 지점을 감지하여 글상자를 분할한다.
     * 정상 행간의 3배 이상 Y 점프가 있으면 분할 지점으로 판단.
     * 분할이 없으면 false 반환 → 기존 경로 사용.
     */
    private boolean placeByYGapSplit(ResolvedTextFrame tf, ASTSection section,
                                      double pageLeft, double pageTop) {
        List<ResolvedTextFrame.ComposedLine> lines = tf.composedLines();

        // 정상 행간 계산: 처음 몇 줄의 Y 간격 중앙값
        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            double[] prev = lines.get(i - 1).bounds();
            double[] curr = lines.get(i).bounds();
            if (prev == null || curr == null) continue;
            double gap = curr[0] - prev[0]; // top 차이
            if (gap > 0) gaps.add(gap);
        }
        if (gaps.isEmpty()) return false;

        java.util.Collections.sort(gaps);
        double medianGap = gaps.get(gaps.size() / 2);

        // Y 점프 분할 지점 감지 (중앙값의 3배 이상)
        List<Integer> splitPoints = new ArrayList<>(); // 분할 후 새 그룹 시작 라인 인덱스
        for (int i = 1; i < lines.size(); i++) {
            double[] prev = lines.get(i - 1).bounds();
            double[] curr = lines.get(i).bounds();
            if (prev == null || curr == null) continue;
            double gap = curr[0] - prev[0];
            if (gap > medianGap * 3) {
                splitPoints.add(i);
            }
        }

        if (splitPoints.isEmpty()) return false; // 분할 불필요

        // 분할 지점으로 라인 그룹 생성
        List<List<ResolvedTextFrame.ComposedLine>> groups = new ArrayList<>();
        int from = 0;
        for (int sp : splitPoints) {
            groups.add(lines.subList(from, sp));
            from = sp;
        }
        groups.add(lines.subList(from, lines.size()));

        String sourceIdBase = "u" + Integer.toHexString(Integer.parseInt(tf.id()));
        int charOffset = 0;

        for (int gi = 0; gi < groups.size(); gi++) {
            List<ResolvedTextFrame.ComposedLine> group = groups.get(gi);

            // 그룹 bounds (앞뒤 빈 줄은 높이 계산에서 제외)
            int firstSubstantive = 0;
            while (firstSubstantive < group.size()) {
                String lt = group.get(firstSubstantive).text();
                if (lt != null && !lt.replace("\r", "").replace("\n", "").replace("\uFFFC", "").trim().isEmpty()) break;
                firstSubstantive++;
            }
            int lastSubstantive = group.size() - 1;
            while (lastSubstantive > firstSubstantive) {
                String lt = group.get(lastSubstantive).text();
                if (lt != null && !lt.replace("\r", "").replace("\n", "").replace("\uFFFC", "").trim().isEmpty()) break;
                lastSubstantive--;
            }
            double minLeft = Double.MAX_VALUE, minTop = Double.MAX_VALUE;
            double maxRight = -Double.MAX_VALUE, maxBottom = -Double.MAX_VALUE;
            int groupCharCount = 0;
            for (int li = 0; li < group.size(); li++) {
                ResolvedTextFrame.ComposedLine line = group.get(li);
                double[] b = line.bounds();
                // 앞뒤 빈 줄은 top/bottom 계산에서 제외
                if (li >= firstSubstantive && b[0] < minTop) minTop = b[0];
                if (b[1] < minLeft) minLeft = b[1];
                if (li <= lastSubstantive && b[2] > maxBottom) maxBottom = b[2];
                if (b[3] > maxRight) maxRight = b[3];
                if (line.text() != null) groupCharCount += line.text().length();
            }

            // normalizeToPoints() 후 bounds는 이미 pt 단위
            // 폭은 TF의 geometricBounds를 사용 (composedLine bounds는 텍스트 영역만 반영하여 좁음)
            double[] tfGb = tf.geometricBounds();
            double gx = tfGb[1] - pageLeft;
            double gy = minTop - pageTop;
            double gw = tfGb[3] - tfGb[1];
            double gh = maxBottom - minTop;

            if (gx < 0) { gw += gx; gx = 0; }
            if (gy < 0) { gh += gy; gy = 0; }
            if (gw <= 0 || gh <= 0) continue;

            ASTTextFrameBlock block = new ASTTextFrameBlock();
            block.sourceId(sourceIdBase + (groups.size() > 1 ? "_g" + gi : ""));
            block.x(CoordinateConverter.pointsToHwpunits(gx));
            block.y(CoordinateConverter.pointsToHwpunits(gy));
            block.width(CoordinateConverter.pointsToHwpunits(gw));
            block.height(CoordinateConverter.pointsToHwpunits(gh));
            block.zOrder(tf.zOrder());
            block.storyId(tf.storyId());
            block.distributed(true); // 분할 블록: 연결 글상자 링크 해제
            block.frameVisibleTextLength(groupCharCount);
            // frameVisibleText 설정 (distributeParagraphs에서 텍스트 기반 분배 사용)
            StringBuilder groupText = new StringBuilder();
            for (int li = firstSubstantive; li <= lastSubstantive && li < group.size(); li++) {
                ResolvedTextFrame.ComposedLine cl = group.get(li);
                if (cl.text() != null) groupText.append(cl.text());
            }
            block.frameVisibleText(groupText.toString());
            // paraIndex를 resolved TF의 paragraphStart 기준 절대 인덱스로 변환
            int tfParaStart = tf.paragraphStart();
            int absParaStart = Integer.MAX_VALUE, absParaEnd = -1;
            for (int li = firstSubstantive; li <= lastSubstantive && li < group.size(); li++) {
                int pi = group.get(li).paraIndex();
                if (pi >= 0) {
                    int abs = tfParaStart + pi;
                    if (abs < absParaStart) absParaStart = abs;
                    if (abs > absParaEnd) absParaEnd = abs;
                }
            }
            if (absParaStart != Integer.MAX_VALUE) {
                block.composedCharStart(absParaStart);
                block.composedCharEnd(absParaEnd);
            }

            // 프레임 속성 복사 (insetSpacing은 이미 pt)
            if (tf.insetSpacing() != null) {
                double[] inset = tf.insetSpacing();
                block.insetTop(CoordinateConverter.pointsToHwpunits(inset[0]));
                block.insetLeft(CoordinateConverter.pointsToHwpunits(inset[1]));
                block.insetBottom(CoordinateConverter.pointsToHwpunits(inset[2]));
                block.insetRight(CoordinateConverter.pointsToHwpunits(inset[3]));
            }

            charOffset += groupCharCount;
            section.addBlock(block);
        }

        return true;
    }

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
    private void placeTablesFromIDML(List<ASTSection> sections) {
        if (idmlDir == null) return;
        int tableCount = 0;

        for (ResolvedTextFrame tf : resolvedData.textFrames()) {
            // Story에 테이블이 있는지 먼저 확인
            String storyId = tf.storyId();
            if (storyId == null) continue;
            IDMLStory idmlStory = loadIDMLStory(storyId);
            if (idmlStory == null || !idmlStory.hasTables()) continue;

            // inline + non-editable이면 테이블이 있어도 건너뜀 (단, 테이블 포함 TF는 예외)
            if (tf.isInline() && resolvedData.isEditableTextFrame(tf.id())) continue;
            if (!tf.isInline() && !resolvedData.isEditableTextFrame(tf.id())) continue;

            // 페이지 결정 (document offset → section index 매핑)
            int pageIdx = toSectionIndex(tf.pageIndex());
            if (pageIdx < 0 || pageIdx >= sections.size()) continue;

            // 좌표 계산
            double[] gb = tf.geometricBounds();
            if (gb == null || gb.length < 4) continue;
            ResolvedPage rPage = (pageIdx < resolvedData.pages().size())
                    ? resolvedData.pages().get(pageIdx) : null;
            double pageLeft = (rPage != null && rPage.bounds() != null) ? rPage.bounds()[1] : 0;
            double pageTop = (rPage != null && rPage.bounds() != null) ? rPage.bounds()[0] : 0;
            boolean gbAlreadyPageRelative = (pageLeft > 0 && gb[1] < pageLeft);
            double x = gbAlreadyPageRelative ? gb[1] : (gb[1] - pageLeft);
            double y = gb[0] - pageTop;

            long hx = CoordinateConverter.pointsToHwpunits(x);
            long hy = CoordinateConverter.pointsToHwpunits(y);

            // 프레임 insetSpacing 반영 (테이블 위치에 인셋 추가)
            if (tf.insetSpacing() != null) {
                double[] inset = tf.insetSpacing();
                hy += CoordinateConverter.pointsToHwpunits(inset[0]); // top
                hx += CoordinateConverter.pointsToHwpunits(inset[1]); // left
            }

            // 테이블 앞 텍스트 높이 계산 (테이블 Y 오프셋)
            // IDML paragraphIndexBefore로 테이블 앞 단락 수 파악
            // 중첩 테이블 감지: selfId가 다른 테이블의 selfId를 접두사로 포함하면 중첩
            List<kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable> allTables = idmlStory.tables();
            // 중첩 테이블 부모 탐색: O(n) — selfId를 HashMap에 등록 후 접두사로 부모 lookup
            Map<String, kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable> tableById = new HashMap<>();
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable t : allTables) {
                tableById.put(t.selfId(), t);
            }
            Map<String, kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable> parentTableMap = new HashMap<>();
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable t : allTables) {
                // selfId 형식: "부모ID/Cell:N/Table" — 접두사에서 부모 테이블 ID 추출
                String sid = t.selfId();
                int lastSlash = sid.lastIndexOf('/');
                if (lastSlash > 0) {
                    // 부모 후보: "부모ID/Cell:N" → 그 앞의 테이블 ID
                    String parentPart = sid.substring(0, lastSlash);
                    int prevSlash = parentPart.lastIndexOf('/');
                    if (prevSlash > 0) {
                        String candidateId = parentPart.substring(0, prevSlash);
                        kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable parent = tableById.get(candidateId);
                        if (parent != null) {
                            parentTableMap.put(sid, parent);
                        }
                    }
                }
            }

            long tableYOffset = 0;
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable idmlTable : allTables) {
                long thisX = hx;
                long thisY = hy;

                kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable parentTable = parentTableMap.get(idmlTable.selfId());
                if (parentTable != null) {
                    // 중첩 테이블: 부모 테이블의 행 높이를 합산하여 y 오프셋 계산
                    // selfId에서 셀 인덱스 추출: "u1cf74i1cf91i6i1cf9b" → "i6" → 셀 row=6
                    String parentId = parentTable.selfId();
                    String remainder = idmlTable.selfId().substring(parentId.length()); // "i6i1cf9b"
                    int cellRowIdx = -1;
                    if (remainder.startsWith("i")) {
                        // "i6i..." → 6
                        String cellPart = remainder.substring(1);
                        int nextI = cellPart.indexOf('i');
                        String rowStr = (nextI > 0) ? cellPart.substring(0, nextI) : cellPart;
                        try { cellRowIdx = Integer.parseInt(rowStr); } catch (NumberFormatException e) { /* ignore */ }
                    }
                    if (cellRowIdx >= 0) {
                        // 부모 테이블의 row 0 ~ cellRowIdx-1 높이 합산
                        long rowHeightSum = 0;
                        int ri = 0;
                        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow pr : parentTable.rows()) {
                            if (ri >= cellRowIdx) break;
                            rowHeightSum += CoordinateConverter.pointsToHwpunits(pr.rowHeight());
                            ri++;
                        }
                        thisY = hy + rowHeightSum;
                    }
                } else {
                    // 최상위 테이블
                    int parasBefore = idmlTable.paragraphIndexBefore();
                    if (parasBefore > 0) {
                        double estLineHeight = 8.0 * scaleFactor;
                        tableYOffset = CoordinateConverter.pointsToHwpunits(parasBefore * estLineHeight);
                    }
                    thisY = hy + tableYOffset;
                }

                // 테이블 셀 복잡도 체크: 인라인 객체가 포함된 테이블은 플로팅 이미지로 변환 시도
                if (hasInlineObjectsInTable(idmlTable)) {
                    ASTFigure fig = renderTableAsImage(idmlTable, tf, thisX, thisY, pageIdx);
                    if (fig != null) {
                        sections.get(pageIdx).addBlock(fig);
                        tableCount++;
                        continue;
                    }
                    // PNG 없으면 일반 테이블로 폴백
                }

                ensureIdmlInfra();
                ASTTable astTable = ASTTableConverter.convertTableSimple(
                        idmlTable, thisX, thisY, tf.zOrder(),
                        idmlDocument, colorResolver, imageLoader, resolvedData);
                sections.get(pageIdx).addBlock(astTable);
                tableCount++;
            }
        }

        if (tableCount > 0) {
            System.err.println("[ResolvedToASTBuilder] Phase 4: " + tableCount + " tables from IDML");
        }
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
    // Phase 3: Story→단락→런 변환
    // ═══════════════════════════════════════════════════

    private void convertStories(List<ASTSection> sections) {
        // TextFrameBlock에 Story 텍스트 연결
        // storyId → TextFrameBlock 매핑
        Map<String, List<ASTTextFrameBlock>> storyToBlocks = new HashMap<>();
        for (ASTSection sec : sections) {
            for (ASTBlock blk : sec.blocks()) {
                if (blk instanceof ASTTextFrameBlock) {
                    ASTTextFrameBlock tfb = (ASTTextFrameBlock) blk;
                    // composedLines 경로에서 이미 단락이 생성된 블록은 건너뜀
                    if (tfb.paragraphs() != null && !tfb.paragraphs().isEmpty()) continue;
                    String sourceId = tfb.sourceId();
                    if (sourceId == null) continue;
                    // sourceId → DOM decimal → textFrame → storyId
                    String hexPart = sourceId.startsWith("u") ? sourceId.substring(1) : sourceId;
                    if (hexPart.contains("_")) hexPart = hexPart.substring(0, hexPart.indexOf('_'));
                    String domId;
                    try {
                        domId = String.valueOf(Integer.parseInt(hexPart, 16));
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    ResolvedTextFrame rtf = resolvedData.getTextFrame(domId);
                    if (rtf != null && rtf.storyId() != null) {
                        storyToBlocks.computeIfAbsent(rtf.storyId(), k -> new ArrayList<>()).add(tfb);
                    }
                }
            }
        }

        System.err.println("[ResolvedToASTBuilder] Phase 3: " + storyToBlocks.size() + " stories matched to TextFrameBlocks");

        // 각 Story → 단락 변환 후 TextFrameBlock에 분배
        // IDML Story XML 우선, 없으면 resolved fallback
        int totalParas = 0;
        int idmlCount = 0;
        int resolvedCount = 0;
        for (Map.Entry<String, List<ASTTextFrameBlock>> entry : storyToBlocks.entrySet()) {
            String storyId = entry.getKey();
            List<ASTTextFrameBlock> blocks = entry.getValue();

            // 1차: IDML Story XML에서 단락 파싱 (정확한 단락 구조)
            List<ASTParagraph> paragraphs = convertStoryFromIDML(storyId);
            boolean useIdml = paragraphs != null && !paragraphs.isEmpty();

            // IDML-SHORT/PARA-MISMATCH 감지: resolved fallback 전환 조건
            // 1) IDML 텍스트가 resolved의 30% 미만 (불릿 전용 Story 등)
            // 2) IDML 단락 수가 resolved의 50% 미만 (강제 줄바꿈이 단락으로 처리되지 않는 경우)
            if (useIdml) {
                ResolvedStory rs = resolvedData.getStory(storyId);
                if (rs != null) {
                    int idmlLen = 0;
                    for (ASTParagraph p : paragraphs)
                        for (Object item : p.items())
                            if (item instanceof ASTTextRun) idmlLen += ((ASTTextRun) item).text() != null ? ((ASTTextRun) item).text().length() : 0;
                    int resolvedLen = 0;
                    for (ResolvedParagraph rp : rs.paragraphs())
                        if (rp.runs() != null)
                            for (ResolvedRun r : rp.runs())
                                resolvedLen += r.text() != null ? r.text().length() : 0;
                    if (resolvedLen > 10 && idmlLen < resolvedLen * 0.3) {
                        useIdml = false; // 텍스트 길이 부족 → resolved fallback
                    }
                    // 단락 수 불일치: IDML 1~2개 단락인데 resolved 5개 이상이면 강제 줄바꿈 누락
                    int resolvedParaCount = rs.paragraphs().size();
                    if (paragraphs.size() <= 2 && resolvedParaCount >= 5) {
                        useIdml = false; // 단락 구조 불일치 → resolved fallback
                    }
                }
            }

            if (useIdml) {
                idmlCount++;
            } else {
                // 2차: resolved.json fallback
                ResolvedStory story = resolvedData.getStory(storyId);
                if (story == null) continue;
                paragraphs = convertStoryParagraphs(story);
                resolvedCount++;
            }
            totalParas += paragraphs.size();

            // Story 전체 텍스트 길이 저장 (overflow 감지용)
            int storyTextLen = 0;
            for (ASTParagraph p : paragraphs) {
                String pt = getParaPlainText(p);
                if (pt != null) storyTextLen += pt.length();
            }
            for (ASTTextFrameBlock b : blocks) {
                b.storyTotalTextLength(storyTextLen);
            }

            // 오버플로우 감지: 모든 블록의 frameVisibleText가 거의 비어있고 Story가 길면 할당 안 함
            if (storyTextLen > 20) {
                boolean allBlocksEmpty = true;
                for (ASTTextFrameBlock b : blocks) {
                    if (b.frameVisibleTextLength() > 1) { allBlocksEmpty = false; break; }
                }
                if (allBlocksEmpty) continue;
            }

            // 단락 분배: paragraphStart/End에 따라 각 TextFrameBlock에 할당
            distributeParagraphs(paragraphs, blocks, storyId);
        }
        System.err.println("[ResolvedToASTBuilder] Phase 3: " + totalParas + " paragraphs converted (IDML=" + idmlCount + " resolved=" + resolvedCount + ")");
    }

    /**
     * IDML Story XML에서 단락을 파싱하여 ASTParagraph 리스트로 변환.
     * IDML의 단락 구조는 정확 (중복 없음, <Br/> 기반 분리).
     * 단락 속성(leading, indent)은 resolved에서 보강.
     */
    private List<ASTParagraph> convertStoryFromIDML(String storyId) {
        if (idmlDir == null) return null;

        // storyId(DOM decimal) → IDML hex → Story_u{hex}.xml
        String hexId;
        try {
            hexId = "u" + Integer.toHexString(Integer.parseInt(storyId));
        } catch (NumberFormatException e) {
            return null;
        }

        // 캐시 확인
        IDMLStory idmlStory = idmlStoryCache.get(hexId);
        if (idmlStory == null) {
            File storyFile = new File(new File(idmlDir, "Stories"), "Story_" + hexId + ".xml");
            if (!storyFile.exists()) return null;
            try {
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                DocumentBuilder db = dbf.newDocumentBuilder();
                org.w3c.dom.Document xmlDoc = db.parse(storyFile);
                idmlStory = IDMLStoryParser.parseStory(xmlDoc, hexId);
                idmlStoryCache.put(hexId, idmlStory);
            } catch (Exception e) {
                return null;
            }
        }

        if (idmlStory == null || idmlStory.paragraphs() == null) return null;

        // resolved에서 단락 속성 보강용
        ResolvedStory resolvedStory = resolvedData.getStory(storyId);

        List<ASTParagraph> paragraphs = new ArrayList<>();
        List<IDMLParagraph> idmlParas = idmlStory.paragraphs();
        for (int i = 0; i < idmlParas.size(); i++) {
            IDMLParagraph ip = idmlParas.get(i);
            ASTParagraph para = new ASTParagraph();
            boolean hasIdmlInlineAnchors = false; // FFFC 앵커 순서로 인라인 삽입된 경우 true

            // 칼럼 브레이크 (ACE 8)
            if (ip.columnBreakAfter()) {
                para.columnBreakAfter(true);
            }

            // 단락 스타일 (IDML)
            if (ip.appliedParagraphStyle() != null) {
                para.paragraphStyleRef(ip.appliedParagraphStyle());
            }

            // 단락 속성: resolved에서 가져옴 (정확한 pt 값)
            // 정렬: IDML 스타일 → resolved 단락 → resolved top-level paragraphStyles → JUSTIFY
            {
                String idmlStyleName = ip.appliedParagraphStyle();
                String cleanStyleName = (idmlStyleName != null && idmlStyleName.contains("/"))
                        ? idmlStyleName.substring(idmlStyleName.lastIndexOf('/') + 1) : idmlStyleName;
                String idmlStyleJust = resolveStyleAlignment(cleanStyleName, astDoc);
                if (idmlStyleJust != null) {
                    para.alignment(idmlStyleJust);
                } else if (resolvedStory != null && i < resolvedStory.paragraphs().size()
                        && resolvedStory.paragraphs().get(i).justification() != null) {
                    para.alignment(resolvedStory.paragraphs().get(i).justification());
                } else if (cleanStyleName != null && resolvedData != null
                        && resolvedData.getParagraphStyleJustification(cleanStyleName) != null) {
                    // resolved.json top-level paragraphStyles fallback
                    para.alignment(resolvedData.getParagraphStyleJustification(cleanStyleName));
                }
                // alignment가 null이면 HwpxParagraphBuilder에서 baseStyle 또는 기본 JUSTIFY 적용
            }
            if (resolvedStory != null && i < resolvedStory.paragraphs().size()) {
                ResolvedParagraph rp = resolvedStory.paragraphs().get(i);
                // leading: resolved 우선 (실제 렌더링 값), IDML 스타일 fallback
                // 단, auto leading(>50pt = percentage 값)은 무시
                Double fixedLeading = rp.fixedLeading(); // resolved (실제 렌더링 값)
                if (fixedLeading == null || fixedLeading <= 0) {
                    fixedLeading = getStyleLeading(ip.appliedParagraphStyle()); // IDML 스타일
                    if (fixedLeading != null && fixedLeading > 50) fixedLeading = null;
                }
                if (fixedLeading == null || fixedLeading <= 0) {
                    fixedLeading = ip.leading(); // IDML CharacterRun leading
                    if (fixedLeading != null && fixedLeading > 50) fixedLeading = null;
                }
                if (fixedLeading != null && fixedLeading > 0) {
                    // InDesign Leading(pt) → HWPX 고정 줄간격(HWPUNIT)
                    para.lineSpacing((int) CoordinateConverter.pointsToHwpunits(fixedLeading));
                    para.lineSpacingType("fixed");
                }
                if (rp.spaceBefore() != null && rp.spaceBefore() > 0) {
                    para.spaceBefore(CoordinateConverter.pointsToHwpunits(rp.spaceBefore()));
                }
                if (rp.spaceAfter() != null && rp.spaceAfter() > 0) {
                    para.spaceAfter(CoordinateConverter.pointsToHwpunits(rp.spaceAfter()));
                }
                if (rp.leftIndent() != null && rp.leftIndent() != 0) {
                    para.leftMargin(CoordinateConverter.pointsToHwpunits(rp.leftIndent()));
                }
                if (rp.firstLineIndent() != null && rp.firstLineIndent() != 0) {
                    para.firstLineIndent(CoordinateConverter.pointsToHwpunits(rp.firstLineIndent()));
                }
                // 탭 스톱
                if (rp.hasTabStops()) {
                    // HWPX 탭은 leftMargin 기준 상대 위치
                    double leftPt = (rp.leftIndent() != null ? rp.leftIndent() : 0) * scaleFactor;
                    for (ResolvedTabStop rts : rp.tabStops()) {
                        if (rts.position() != null && rts.position() > 0) {
                            // resolved tabStop position은 mm(절대) → leftMargin 빼서 상대 → pt → hwpunit
                            double posPt = rts.position() * scaleFactor - leftPt;
                            if (posPt < 0) posPt = 0; // 음수는 0으로
                            String align = "left";
                            if (rts.alignment() != null) {
                                String a = rts.alignment().toLowerCase();
                                if (a.contains("center")) align = "center";
                                else if (a.contains("right")) align = "right";
                                else if (a.contains("decimal")) align = "decimal";
                            }
                            para.addTabStop(new ASTTabStop(
                                    CoordinateConverter.pointsToHwpunits(posPt), align, null));
                        }
                    }
                }
            } else {
                // resolvedStory 매칭 실패 → IDML 단락 스타일에서 정렬 상속
                String idmlStyle = ip.appliedParagraphStyle();
                if (idmlStyle != null) {
                    // "ParagraphStyle/스타일명" → "스타일명"
                    String styleName = idmlStyle.contains("/")
                            ? idmlStyle.substring(idmlStyle.lastIndexOf('/') + 1) : idmlStyle;
                    String styleJust = resolveStyleAlignment(styleName, astDoc);
                    if (styleJust != null) para.alignment(styleJust);
                }
            }

            // resolved 런 (스타일 상속 보강용)
            List<ResolvedRun> resolvedRuns = null;
            if (resolvedStory != null && i < resolvedStory.paragraphs().size()) {
                resolvedRuns = resolvedStory.paragraphs().get(i).runs();
            }

            // ParagraphStyle에서 FillColor/Tracking/FontFamily 미리 구해둠 (런에서 없을 때 사용)
            StyleContext sc = new StyleContext(
                    getStyleFillColor(ip.appliedParagraphStyle()),
                    getStyleTracking(ip.appliedParagraphStyle()),
                    getStyleFontFamily(ip.appliedParagraphStyle()),
                    getStyleFontSize(ip.appliedParagraphStyle()));

            // 런 변환: IDML CharacterRun → ASTTextRun + 수식 그룹화
            // resolved 런 중 가장 긴 텍스트를 가진 런을 기본값으로 (불릿/특수문자 런 회피)
            ResolvedRun defaultRR = findDefaultResolvedRun(resolvedRuns);
            int resolvedRunIdx = 0;

            // 전처리: 한국어+수식마커 혼합 런 분리 + 원문자 변환
            List<IDMLCharacterRun> runs = ASTMathGrouper.splitMathKoreanMixedRuns(ip.characterRuns());
            ASTRunConverter.convertCircledNumberRuns(runs);

            // 수식 그룹화 상태
            List<IDMLCharacterRun> mathGroup = new ArrayList<>();
            List<IDMLCharacterRun> npMathGroup = new ArrayList<>();
            List<IDMLCharacterRun> ehMathGroup = new ArrayList<>();

            boolean paraHasBTRuns = false;
            boolean paraHasNPStructuralRuns = false;
            boolean paraHasMathSymbols = false;
            // IDML 원본 CharacterRun에서 수식 기호 유무 확인 (GREP 분리 전 기준)
            for (IDMLCharacterRun r : ip.characterRuns()) {
                if (r.isBTFont() || r.isNPFont() || r.isEHFont()) { paraHasMathSymbols = true; break; }
                String rt = r.content();
                if (rt != null) {
                    for (int ci = 0; ci < rt.length(); ci++) {
                        char cc = rt.charAt(ci);
                        if ("+=<>≤≥±×÷√²³^_π∑∫∞".indexOf(cc) >= 0
                                || (cc >= 0xC0 && cc <= 0xFF)) { // EH encoded chars (확장 라틴 전체)
                            paraHasMathSymbols = true;
                            break;
                        }
                    }
                }
                if (paraHasMathSymbols) break;
            }
            for (IDMLCharacterRun r : runs) {
                if (r.isBTFont()) paraHasBTRuns = true;
                if (r.isNPFont()) {
                    NPFontGlyphMap.FontCategory cat = NPFontGlyphMap.getCategory(r.npFontName());
                    if (cat == NPFontGlyphMap.FontCategory.SUBSCRIPT_INDEX
                            || cat == NPFontGlyphMap.FontCategory.SUPERSCRIPT_INDEX
                            || cat == NPFontGlyphMap.FontCategory.ROOT
                            || cat == NPFontGlyphMap.FontCategory.FRACTION_BAR
                            || cat == NPFontGlyphMap.FontCategory.INTEGRAL
                            || cat == NPFontGlyphMap.FontCategory.SUMMATION
                            || cat == NPFontGlyphMap.FontCategory.LIMIT
                            || cat == NPFontGlyphMap.FontCategory.SPECIAL_SYMBOL) {
                        paraHasNPStructuralRuns = true;
                    }
                }
            }

            for (int idx = 0; idx < runs.size(); idx++) {
                IDMLCharacterRun run = runs.get(idx);

                // GREP 수식 리셋: 단일 라틴 문자(수식 변수 x, a, n)는 유지, 나머지 제거
                if (run.grepMathFont()) {
                    String ct = run.content();
                    boolean isSingleLatinVar = ct != null && ct.trim().length() == 1
                            && Character.isLetter(ct.trim().charAt(0));
                    if (!isSingleLatinVar) {
                        run.grepMathFont(false);
                        String ff = run.fontFamily();
                        if (ff != null && ff.contains("BT수식")) {
                            run.fontFamily(null);
                        }
                        run.fontStyle(null);
                    }
                }
                // EH 수식 폰트 리셋
                if (run.isEHFont()) {
                    String ct = run.content();
                    boolean isSingleLatinVar = ct != null && ct.trim().length() == 1
                            && Character.isLetter(ct.trim().charAt(0));
                    // 이 런 또는 근처 런(±5)에 3자+ 영단어가 있으면 이름/약어 그룹 → 리셋
                    boolean nearLongWord = containsLongLatinWord(ct, 3);
                    if (!nearLongWord) {
                        for (int d = 1; d <= 5 && !nearLongWord; d++) {
                            if (idx - d >= 0) nearLongWord = containsLongLatinWord(runs.get(idx - d).content(), 3);
                            if (idx + d < runs.size()) nearLongWord = nearLongWord || containsLongLatinWord(runs.get(idx + d).content(), 3);
                        }
                    }
                    if ((!paraHasMathSymbols && !isSingleLatinVar) || nearLongWord) {
                        run.fontFamily(null);
                        run.fontStyle(null);
                        run.appliedCharacterStyle(null);
                    }
                }

                // EH 수식: fontFamily가 null이면 CharacterStyle 이름에서 추출
                if (run.isEHFont() && run.fontFamily() == null) {
                    String ehFont = EHFontGlyphMap.extractFontFromStyle(run.appliedCharacterStyle());
                    if (ehFont != null) run.fontFamily(ehFont);
                }

                // EH 수식 그룹 진입
                boolean enterEH = run.isEHFont()
                        || EHFontGlyphMap.containsEHEncodedChars(run.content())
                        || EHFontGlyphMap.containsEHFractionPattern(run.content())
                        || (!ehMathGroup.isEmpty() && ASTMathGrouper.isEHMathBridgeRun(run, runs, idx))
                        || (!ehMathGroup.isEmpty() && isEHSqrtContent(run, ehMathGroup));

                // NP 수식 그룹 진입
                boolean enterNP = false;
                if (!enterEH) {
                    enterNP = run.isNPFont()
                            || (!npMathGroup.isEmpty() && ASTMathGrouper.isNPMathBridgeRun(run, runs, idx))
                            || (npMathGroup.isEmpty() && ASTMathGrouper.isPreNPMathRun(run, runs, idx))
                            || (paraHasNPStructuralRuns && !run.isNPFont() && !run.isBTFont()
                                && !run.isEHFont()
                                && ASTMathGrouper.isStandaloneMathRun(run));
                }

                // BT 수식 그룹 진입
                boolean enterBT = false;
                if (!enterEH && !enterNP) {
                    enterBT = (run.isBTFont()
                                && !ASTMathGrouper.isBTRunWithOnlyKorean(run.content()))
                            || (!mathGroup.isEmpty() && ASTMathGrouper.isMathBridgeRun(run, runs, idx))
                            || (paraHasBTRuns && ASTMathGrouper.looksLikeMathRun(run.content()));
                }

                if (enterEH) {
                    flushMathGroups(mathGroup, npMathGroup, null, para);
                    ehMathGroup.add(run);
                } else if (enterNP) {
                    flushMathGroups(mathGroup, null, ehMathGroup, para);
                    npMathGroup.add(run);
                } else if (enterBT) {
                    flushMathGroups(null, npMathGroup, ehMathGroup, para);
                    mathGroup.add(run);
                } else {
                    // 비수식 런: 열린 그룹 모두 flush
                    flushMathGroups(mathGroup, npMathGroup, ehMathGroup, para);

                    // 일반 런 변환 (U+FFFC 인라인 객체 포함)
                    String text = run.content();
                    if (text == null || text.isEmpty()) continue;

                    if (text.contains("\uFFFC")) {
                        hasIdmlInlineAnchors = true;
                        String[] parts = text.split("\uFFFC", -1);
                        // inlineAnchors 순서로 인라인 ID 목록 생성 (FFFC 출현 순서 보장)
                        List<String> inlineIds = new ArrayList<>();
                        if (run.inlineAnchors() != null && !run.inlineAnchors().isEmpty()) {
                            for (IDMLCharacterRun.InlineAnchor anchor : run.inlineAnchors()) {
                                if (anchor.type() == IDMLCharacterRun.InlineAnchorType.FRAME
                                        && run.inlineFrames() != null && anchor.index() < run.inlineFrames().size()) {
                                    inlineIds.add(run.inlineFrames().get(anchor.index()).selfId());
                                } else if (anchor.type() == IDMLCharacterRun.InlineAnchorType.GRAPHIC
                                        && run.inlineGraphics() != null && anchor.index() < run.inlineGraphics().size()) {
                                    inlineIds.add(run.inlineGraphics().get(anchor.index()).selfId());
                                }
                            }
                        } else {
                            // fallback: inlineFrames + inlineGraphics 순서
                            if (run.inlineFrames() != null) {
                                for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTextFrame itf : run.inlineFrames()) {
                                    inlineIds.add(itf.selfId());
                                }
                            }
                            if (run.inlineGraphics() != null) {
                                for (IDMLCharacterRun.InlineGraphic ig : run.inlineGraphics()) {
                                    inlineIds.add(ig.selfId());
                                }
                            }
                        }
                        int anchorIdx = 0;
                        for (int pi = 0; pi < parts.length; pi++) {
                            // 인라인 앵커 직전 텍스트의 후행 공백 제거 (위치 조정용 공백)
                            String partText = parts[pi];
                            if (pi < parts.length - 1 && partText.endsWith("  ")) {
                                partText = partText.replaceAll("\\s+$", " "); // 후행 다중 공백 → 단일 공백
                            }
                            if (!partText.isEmpty()) {
                                // resolved 런 스타일 차이가 있으면 분할 시도
                                boolean partSplit = false;
                                if (resolvedRuns != null && resolvedRuns.size() > 1 && hasStyleVariation(resolvedRuns)) {
                                    partSplit = splitIdmlRunByResolvedRuns(run, partText, resolvedRuns, resolvedRunIdx,
                                            para, sc);
                                }
                                if (!partSplit) {
                                    ResolvedRun matchedRR = findResolvedRun(resolvedRuns, resolvedRunIdx, partText);
                                    if (matchedRR != null) resolvedRunIdx = lastMatchResult[0] + 1;
                                    ASTTextRun tr = createRunFromIDML(run, partText, matchedRR != null ? matchedRR : defaultRR, sc);
                                    if (!splitBulletRun(tr, para)) {
                                        splitLatinVarsInMixedText(tr, para);
                                    }
                                }
                            }
                            if (pi < parts.length - 1 && anchorIdx < inlineIds.size()) {
                                String inlineHexId = inlineIds.get(anchorIdx);
                                try {
                                    int domId = Integer.parseInt(inlineHexId.substring(1), 16);
                                    // 커스텀 위치 앵커 객체 건너뛰기: resolved TextFrame의 중심X가 부모 범위 밖이면 인라인 삽입 안 함
                                    if (isAnchoredOutsideParentByTextFrame(domId, storyId)) {
                                        anchorIdx++;
                                        continue;
                                    }
                                    // 짧은 텍스트 인라인 TextFrame → 텍스트 런으로 변환 우선
                                    // (rendered badge는 tryInlineTextFrameAsRun에서 건너뛰고 loadInlineObject로 PNG 로드)
                                    ASTTextRun textRun = tryInlineTextFrameAsRun(domId);
                                    if (textRun != null) {
                                        para.addItem(textRun);
                                    } else {
                                        ASTInlineObject inlineObj = loadInlineObject(domId);
                                        if (inlineObj != null) {
                                            para.addItem(inlineObj);
                                        }
                                    }
                                } catch (Exception e) { /* skip */ }
                                anchorIdx++;
                            }
                        }
                    } else {
                        // GREP 스타일 분할: IDML 단일 런이 resolved에서 여러 런(다른 색상/폰트)으로 분할된 경우
                        // resolved 런 경계에서 IDML 런을 분할하여 각각의 색상을 적용
                        boolean splitByResolved = false;
                        if (resolvedRuns != null && resolvedRuns.size() > 1 && hasStyleVariation(resolvedRuns)) {
                            splitByResolved = splitIdmlRunByResolvedRuns(run, text, resolvedRuns, resolvedRunIdx,
                                    para, sc);
                        }
                        if (!splitByResolved) {
                            ResolvedRun matchedRR2 = findResolvedRun(resolvedRuns, resolvedRunIdx, text);
                            if (matchedRR2 != null) resolvedRunIdx = lastMatchResult[0] + 1;
                            ASTTextRun tr = createRunFromIDML(run, text, matchedRR2 != null ? matchedRR2 : defaultRR, sc);
                            // ;...; 분수 GREP 패턴이 포함된 텍스트 → 분수 수식으로 분리
                            if (!splitBulletRun(tr, para)) {
                                if (EHFontGlyphMap.containsEHFractionPattern(text)) {
                                    splitFractionPatternInText(text, tr, para);
                                } else {
                                    splitLatinVarsInMixedText(tr, para);
                                }
                            }
                        }
                    }
                }
            }
            // 단락 끝 잔여 수식 그룹 flush
            flushMathGroups(mathGroup, npMathGroup, ehMathGroup, para);

            // 패턴 감지: 행잉 인덴트 + 인라인 아이콘 + 탭
            // InDesign에서 인라인 아이콘이 마진 밖(-indent 영역)에 배치되지만
            // HWPX에서는 마진 안에 배치되므로, 행잉 인덴트를 리셋하여 자연 들여쓰기 사용
            if (para.firstLineIndent() != null && para.firstLineIndent() < 0
                    && !para.items().isEmpty()
                    && para.items().get(0) instanceof ASTInlineObject) {
                para.leftMargin(null);
                para.firstLineIndent(null);
            }

            // 불릿 단락이면 불릿 이후 런 색상을 검정으로 리셋
            resetBulletParagraphColors(para);

            // 인라인 객체 boundsX 기반 재정렬: FFFC 앵커 순서가 없는 경우에만
            // (IDML 경로에서 FFFC 분할 후 앵커 순서로 삽입된 경우 재정렬하면 순서 깨짐)
            if (!hasIdmlInlineAnchors) {
                ASTTableConverter.reorderInlineObjectsByBoundsX(para);
            }

            paragraphs.add(para);
        }

        return paragraphs;
    }

    private ASTTextRun createRunFromIDML(IDMLCharacterRun cr, String text, ResolvedRun rr, StyleContext sc) {
        ASTTextRun tr = new ASTTextRun();
        // 특수 제어 문자 제거
        // \u0008 = Indent to Here (ACE 7) — HWPX에 대응 없음
        // \n = Frame Break (ACE 3) — 같은 글상자 안에서 의미 없음
        // \t + \u0008 패턴: 인라인 아이콘 앞 탭+IndentToHere → 둘 다 제거
        // 단독 \t: resolved에서 온 탭이지만 탭스톱 없으면 불필요한 간격 생성 → 공백으로 치환
        if (text != null) {
            text = text.replace("\t\u0008", ""); // \t + IndentToHere 조합 제거
            text = text.replace("\u0008", "");   // 단독 IndentToHere 제거
            text = text.replace("\n", "");       // Frame Break 제거
            text = text.replace("\t", " ");      // 탭 → 공백 (탭스톱 없는 경우 간격 방지)
            text = text.replace("\u2009", " ");   // Thin Space → 공백
            text = text.replace("\u2002", "");   // En Space 제거
            text = text.replace("\u2003", "");   // Em Space 제거
            text = text.replace("\u200A", "");   // Hair Space 제거
            text = text.replace("\uFFE3", "~");  // Fullwidth Macron → 물결 (한글 호환)
        }
        tr.text(text);
        // IDML CharacterRun 속성 우선
        // EH 수식 폰트가 한국어 텍스트에 잘못 적용되면 제거
        String fontFamily = cr.fontFamily();
        if (fontFamily != null && EHFontGlyphMap.isEHFontFamily(fontFamily)
                && text != null && EHTextClassifier.isKoreanOnly(text)) {
            fontFamily = null; // 한국어 텍스트에 EH 폰트 적용 방지
            tr.fontStyle(null); // EH 폰트의 Italic fontStyle 제거
        }
        if (fontFamily != null) tr.fontFamily(fontFamily);
        if (cr.fontStyle() != null) tr.fontStyle(cr.fontStyle());
        if (cr.fontSize() != null && cr.fontSize() > 0) {
            tr.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(cr.fontSize()));
        }
        if (cr.fillColor() != null) tr.textColor(resolveColorToHex(cr.fillColor()));
        // GREP 스타일 색상 적용: grepAppliedCharStyle의 FillColor가 있으면 우선
        if (cr.grepAppliedCharStyle() != null && idmlDocument != null) {
            ensureIdmlInfra();
            if (idmlDocument != null) {
                kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStyleDef grepCharStyle =
                        idmlDocument.charStyles().get(cr.grepAppliedCharStyle());
                if (grepCharStyle == null) {
                    // "CharacterStyle/" 접두사 제거 시도
                    String shortRef = cr.grepAppliedCharStyle();
                    if (shortRef.startsWith("CharacterStyle/")) shortRef = shortRef.substring("CharacterStyle/".length());
                    grepCharStyle = idmlDocument.charStyles().get(shortRef);
                }
                if (grepCharStyle != null && grepCharStyle.fillColor() != null) {
                    String grepColor = resolveColorToHex(grepCharStyle.fillColor());
                    if (grepColor != null) tr.textColor(grepColor);
                }
            }
        }
        // InDesign Tracking → HWPX 자간
        // 한컴돋움/한컴바탕 fallback 폰트 매핑 시: tracking 값 그대로 (e.g., -15 → -15%)
        // 명시적 매핑 폰트: tracking / 10 (e.g., -30 → -3%)
        // 한국어 폰트(한글 포함 이름)는 대부분 한컴돋움 fallback → 그대로 사용
        {
            Double trackingVal = (cr.tracking() != null && cr.tracking() != 0)
                    ? cr.tracking() : sc.tracking;
            if (trackingVal != null && trackingVal != 0) {
                String fn = (fontFamily != null) ? fontFamily
                        : (tr.fontFamily() != null ? tr.fontFamily()
                        : (sc.fontFamily != null ? sc.fontFamily
                        : (rr != null ? rr.fontFamily() : null)));
                boolean isDefaultFallback = isKoreanFontName(fn);
                if (isDefaultFallback) {
                    // 한컴돋움 fallback: tracking 값의 50%
                    tr.letterSpacing((short) Math.round(trackingVal * 0.5));
                } else {
                    // 명시적 매핑: tracking / 10
                    tr.letterSpacing((short) Math.round(trackingVal / 10.0));
                }
            }
        }
        // baselineShift: InDesign pt → HWPX % (fontSize 기준)
        if (rr != null && rr.baselineShift() != null && rr.baselineShift() != 0) {
            double bsPt = rr.baselineShift();
            double fs = (rr.fontSize() != null && rr.fontSize() > 0) ? rr.fontSize() : 10.0;
            short bsPct = (short) Math.round((bsPt / fs) * 100);
            tr.baselineShift(bsPct);
        }
        // IDML에 없는 속성은 ParagraphStyle → resolved 런 순으로 보강
        if (rr != null) {
            if (tr.fontFamily() == null) {
                // ParagraphStyle 기본 폰트 우선 (IDML CSR에 명시 없을 때)
                if (sc.fontFamily != null) {
                    tr.fontFamily(sc.fontFamily);
                } else if (rr.fontFamily() != null) {
                    // EH/BT 수식 폰트는 수식 기호가 있는 텍스트에만 적용
                    boolean isEHorBT = EHFontGlyphMap.isEHFontFamily(rr.fontFamily())
                            || (rr.fontFamily() != null && rr.fontFamily().contains("BT수식"));
                    boolean isSingleLatin = text != null && text.trim().length() == 1
                            && Character.isLetter(text.trim().charAt(0));
                    if (!isEHorBT || isSingleLatin) {
                        tr.fontFamily(rr.fontFamily());
                    }
                }
            }
            if (rr.fontStyle() != null) {
                boolean isEHorBT = rr.fontFamily() != null
                        && (EHFontGlyphMap.isEHFontFamily(rr.fontFamily()) || rr.fontFamily().contains("BT수식"));
                boolean isSingleLatin = text != null && text.trim().length() == 1
                        && Character.isLetter(text.trim().charAt(0));
                if (!isEHorBT || isSingleLatin) {
                    // resolved fontStyle 우선 (GREP/중첩 스타일 반영 — IDML은 미반영)
                    tr.fontStyle(rr.fontStyle());
                }
            }
            if (tr.fontSizeHwpunits() == null) {
                // ParagraphStyle 기본 fontSize 우선
                if (sc.fontSize != null && sc.fontSize > 0) {
                    tr.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(sc.fontSize));
                } else if (rr.fontSize() != null && rr.fontSize() > 0) {
                    tr.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(rr.fontSize()));
                }
            }
            // horizontalScale: IDML에 없으면 resolved에서 보강
            if (tr.horizontalScale() == null && rr.horizontalScale() != null
                    && rr.horizontalScale() != 0 && rr.horizontalScale() != 100) {
                // horizontalScale == verticalScale인 경우: 비례 확대 → fontSize에 반영, ratio는 100 유지
                Double vs = rr.verticalScale();
                if (vs != null && Math.abs(rr.horizontalScale() - vs) < 1.0) {
                    // 비례 확대: fontSize를 스케일 비율만큼 키움
                    if (tr.fontSizeHwpunits() != null) {
                        tr.fontSizeHwpunits((int) Math.round(tr.fontSizeHwpunits() * rr.horizontalScale() / 100.0));
                    }
                    // horizontalScale, verticalScale 모두 적용하지 않음
                } else {
                    tr.horizontalScale((short) rr.horizontalScale().doubleValue());
                }
            }
            // FillColor: IDML CSR 없으면 → ParagraphStyle → Black 기본값
            // (resolved 색상은 중첩 스타일에 의해 잘못될 수 있으므로 우선하지 않음)
            if (tr.textColor() == null) {
                if (sc.fillColor != null) {
                    tr.textColor(sc.fillColor);
                } else {
                    tr.textColor("#000000"); // 기본 Black
                }
            }
            // underline / strikeThrough
            if (rr.underline() != null && rr.underline()) {
                tr.underline(true);
            }
            if (rr.strikeThru() != null && rr.strikeThru()) {
                tr.strikeThrough(true);
            }
        }
        // ParagraphStyle 폴백 (IDML 런과 resolved 런 모두 속성이 없을 때)
        if (tr.fontFamily() == null && sc.fontFamily != null) {
            tr.fontFamily(sc.fontFamily);
        }
        if (tr.fontSizeHwpunits() == null && sc.fontSize != null && sc.fontSize > 0) {
            tr.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(sc.fontSize));
        }
        // IDML CharacterRun의 underline/strikeThrough (resolved보다 우선)
        if (cr.underline() != null && cr.underline()) {
            tr.underline(true);
        }
        if (cr.strikeThrough() != null && cr.strikeThrough()) {
            tr.strikeThrough(true);
        }
        // IDML UnderlineType → underlineShape 매핑 (Wavy → WAVE 등)
        if (cr.underlineType() != null) {
            String ulType = cr.underlineType().toLowerCase();
            if (ulType.contains("wavy") || ulType.contains("wave")) {
                tr.underline(true);
                tr.underlineShape("WAVE");
            } else if (ulType.contains("dashed") || ulType.contains("dash")) {
                tr.underline(true);
                tr.underlineShape("DASH");
            } else if (ulType.contains("dotted") || ulType.contains("dot")) {
                tr.underline(true);
                tr.underlineShape("DOT");
            }
        }
        // CharacterStyle 이름에서 밑줄/취소선 추론
        String charStyle = cr.appliedCharacterStyle();
        if (charStyle != null) {
            if (charStyle.contains("밑줄") || charStyle.toLowerCase().contains("underline")) {
                tr.underline(true);
            }
            if (charStyle.contains("취소선") || charStyle.toLowerCase().contains("strikethrough")) {
                tr.strikeThrough(true);
            }
            // CharacterStyle에서 물결 밑줄 추론
            if (charStyle.contains("물결") || charStyle.toLowerCase().contains("wavy")) {
                tr.underlineShape("WAVE");
            }
        }
        // 수식 폰트 감지는 convertMathRunsInParagraph에서 후처리
        return tr;
    }

    private static final String BULLET_CHARS = "●•◆◇▶▷■□";

    /** 한국어 폰트 이름 판별: 한글 문자가 포함되어 있으면 한국어 폰트 */
    private static boolean isKoreanFontName(String fontName) {
        if (fontName == null) return false;
        for (int i = 0; i < fontName.length(); i++) {
            char c = fontName.charAt(i);
            if ((c >= 0xAC00 && c <= 0xD7AF) || (c >= 0x3131 && c <= 0x318E)) return true;
        }
        return false;
    }

    /**
     * 불릿 문자(●, •)로 시작하는 런을 불릿 런 + 본문 런으로 분리하여 단락에 추가.
     * InDesign에서 불릿과 본문이 같은 런에 포함되면 불릿 색상이 본문에도 적용되는 문제 해결.
     * 분리 시 불릿 런은 원래 색상 유지, 본문 런은 검정(#000000)으로 리셋.
     * @return true: 분리되어 단락에 추가됨, false: 분리 불필요 (호출자가 직접 추가)
     */
    private boolean splitBulletRun(ASTTextRun tr, ASTParagraph para) {
        String text = tr.text();
        if (text == null || text.length() < 2) return false;

        char first = text.charAt(0);
        if (BULLET_CHARS.indexOf(first) < 0) return false;

        // 불릿 뒤에 공백/탭이 있어야 분리 (단독 불릿 문자는 무시)
        int splitIdx = 1;
        if (splitIdx < text.length() && (text.charAt(splitIdx) == ' ' || text.charAt(splitIdx) == '\t')) {
            splitIdx++; // 공백/탭 포함
        }
        if (splitIdx >= text.length()) return false; // 불릿만 있으면 분리 불필요

        // 단락에 불릿 플래그 설정 (이후 런의 색상 리셋용)
        para.bulletParagraph(true);

        // 불릿 런: 원래 색상, 약간 작은 크기
        ASTTextRun bulletRun = new ASTTextRun();
        bulletRun.text(String.valueOf(first));
        bulletRun.textColor(tr.textColor());
        bulletRun.fontFamily(tr.fontFamily());
        bulletRun.fontStyle(tr.fontStyle());
        if (tr.fontSizeHwpunits() != null) {
            // 불릿 크기: 본문의 50% (함초롬돋움의 ● 글리프가 크므로)
            bulletRun.fontSizeHwpunits((int) (tr.fontSizeHwpunits() * 0.5));
        }
        bulletRun.letterSpacing(tr.letterSpacing());
        para.addItem(bulletRun);

        // 구분자(탭/공백) 런
        if (splitIdx > 1) {
            ASTTextRun sepRun = new ASTTextRun();
            sepRun.text(text.substring(1, splitIdx));
            sepRun.fontFamily(tr.fontFamily());
            sepRun.fontStyle(tr.fontStyle());
            sepRun.fontSizeHwpunits(tr.fontSizeHwpunits());
            sepRun.textColor("#000000");
            para.addItem(sepRun);
        }

        // 본문 런: 검정색
        ASTTextRun bodyRun = new ASTTextRun();
        bodyRun.text(text.substring(splitIdx));
        bodyRun.fontFamily(tr.fontFamily());
        bodyRun.fontStyle(tr.fontStyle());
        bodyRun.fontSizeHwpunits(tr.fontSizeHwpunits());
        bodyRun.letterSpacing(tr.letterSpacing());
        bodyRun.textColor("#000000");
        bodyRun.baselineShift(tr.baselineShift());
        para.addItem(bodyRun);

        return true;
    }

    /**
     * 불릿 단락의 런 색상을 검정으로 리셋 (불릿 런 자체는 제외).
     * splitBulletRun이 단락 첫 런만 처리하므로, 이후 런의 색상도 리셋 필요.
     */
    private void resetBulletParagraphColors(ASTParagraph para) {
        if (!para.bulletParagraph()) return;
        boolean firstItem = true;
        for (Object item : para.items()) {
            if (item instanceof ASTTextRun) {
                if (firstItem) {
                    firstItem = false;
                    continue; // 불릿 런 자체는 건너뜀
                }
                ASTTextRun run = (ASTTextRun) item;
                // 불릿 색상(비검정)이면 검정으로 리셋
                if (run.textColor() != null && !run.textColor().equals("#000000")) {
                    run.textColor("#000000");
                }
            } else {
                firstItem = false;
            }
        }
    }

    /**
     * ParagraphStyle의 Leading 값을 가져옴 (pt). StylePropertyResolver에 위임.
     */
    private Double getStyleLeading(String styleRef) {
        if (styleResolver == null) return null;
        IDMLStyleDef resolved = styleResolver.getResolvedParagraphStyle(styleRef);
        return resolved != null ? resolved.leading() : null;
    }

    /**
     * ParagraphStyle의 Tracking(자간) 값을 가져옴. StylePropertyResolver에 위임.
     */
    private Double getStyleTracking(String styleRef) {
        if (styleResolver == null) return null;
        IDMLStyleDef resolved = styleResolver.getResolvedParagraphStyle(styleRef);
        return resolved != null ? resolved.tracking() : null;
    }

    /**
     * ParagraphStyle의 FillColor를 hex로 변환하여 반환. StylePropertyResolver에 위임.
     */
    private String getStyleFillColor(String styleRef) {
        if (styleResolver == null) return null;
        IDMLStyleDef resolved = styleResolver.getResolvedParagraphStyle(styleRef);
        if (resolved != null && resolved.fillColor() != null) {
            return resolveColorToHex(resolved.fillColor());
        }
        return null;
    }

    private String getStyleFontFamily(String styleRef) {
        if (styleResolver == null) return null;
        IDMLStyleDef resolved = styleResolver.getResolvedParagraphStyle(styleRef);
        return resolved != null ? resolved.fontFamily() : null;
    }

    private Double getStyleFontSize(String styleRef) {
        if (styleResolver == null) return null;
        IDMLStyleDef resolved = styleResolver.getResolvedParagraphStyle(styleRef);
        return resolved != null ? resolved.fontSize() : null;
    }

    /**
     * 한국어 사이 단일 라틴 문자를 수식 변수(이탤릭)로 분리.
     * "길이를 x라고 할 때" → "길이를 " + [수식 x] + "라고 할 때"
     */
    private void splitLatinVarsInMixedText(ASTTextRun originalRun, ASTParagraph para) {
        String text = originalRun.text();
        if (text == null || text.isEmpty()) { para.addItem(originalRun); return; }

        // 한국어가 포함된 텍스트에서만 분리 (순수 라틴 텍스트는 그대로)
        boolean hasKorean = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0xAC00 && c <= 0xD7AF) || (c >= 0x3131 && c <= 0x318E)) { hasKorean = true; break; }
        }
        if (!hasKorean) { para.addItem(originalRun); return; }

        // 단일 라틴 문자(공백으로 둘러싸인)를 찾아 분리
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean isLatinLetter = Character.isLetter(c) && c < 0x100 && !Character.isDigit(c);
            // 단일 라틴 문자: 앞뒤가 공백/한국어/숫자이고, 다음 문자가 라틴이 아님
            boolean isSingleVar = isLatinLetter
                    && (i == 0 || !Character.isLetter(text.charAt(i - 1)) || text.charAt(i - 1) >= 0x100)
                    && (i == text.length() - 1 || !Character.isLetter(text.charAt(i + 1)) || text.charAt(i + 1) >= 0x100);
            if (isSingleVar) {
                // 앞 텍스트 flush
                if (buf.length() > 0) {
                    ASTTextRun before = cloneRunWithText(originalRun, buf.toString());
                    para.addItem(before);
                    buf.setLength(0);
                }
                // 수식 변수
                ASTEquation eq = new ASTEquation();
                eq.hwpScript(String.valueOf(c));
                eq.sourceType("LATIN_VAR");
                if (originalRun.textColor() != null) eq.textColor(originalRun.textColor());
                para.addItem(eq);
            } else {
                buf.append(c);
            }
        }
        if (buf.length() > 0) {
            ASTTextRun after = cloneRunWithText(originalRun, buf.toString());
            para.addItem(after);
        }
    }

    private ASTTextRun cloneRunWithText(ASTTextRun src, String text) {
        ASTTextRun tr = new ASTTextRun();
        tr.text(text);
        tr.fontFamily(src.fontFamily());
        tr.fontStyle(src.fontStyle());
        tr.fontSizeHwpunits(src.fontSizeHwpunits());
        tr.textColor(src.textColor());
        tr.letterSpacing(src.letterSpacing());
        tr.grepMathFont(src.grepMathFont());
        tr.underline(src.underline());
        tr.underlineShape(src.underlineShape());
        tr.underlineColor(src.underlineColor());
        tr.strikeThrough(src.strikeThrough());
        tr.characterStyleRef(src.characterStyleRef());
        tr.horizontalScale(src.horizontalScale());
        tr.verticalScale(src.verticalScale());
        tr.baselineShift(src.baselineShift());
        return tr;
    }

    /**
     * 텍스트에 수식 기호 또는 EH 인코딩 문자가 포함되어 있는지 확인.
     */
    /** 텍스트에 minLen자 이상 연속 라틴 문자(영단어)가 포함되어 있는지 확인. */
    private static boolean containsLongLatinWord(String text, int minLen) {
        if (text == null) return false;
        int streak = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c) && c < 0x100) {
                streak++;
                if (streak >= minLen) return true;
            } else {
                streak = 0;
            }
        }
        return false;
    }

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
     * 색상 이름/CMYK 문자열을 hex RGB로 변환.
     * IDML 스와치 이름("Color/홀수_1단원_MD") 또는 CMYK 문자열("C=0 M=0 Y=0 K=70") 지원.
     */
    private String resolveColorToHex(String color) {
        if (color == null) return null;
        // 이미 hex (#RRGGBB 또는 #RGB 형식: # 뒤가 모두 hex 문자)
        if (color.startsWith("#") && color.length() >= 4 && color.substring(1).matches("[0-9a-fA-F]+")) {
            return color;
        }
        // # 접두사가 있지만 hex가 아닌 스와치 이름 (예: "#활동 번호 색") → 이름으로 조회
        if (color.startsWith("#")) {
            String hex = resolvedData.resolveColorHex(color);
            if (hex != null) return hex;
            // # 제거 후 재조회
            hex = resolvedData.resolveColorHex(color.substring(1));
            if (hex != null) return hex;
        }
        // IDML 스와치: "Color/Paper" → "Paper"
        String name = color.startsWith("Color/") ? color.substring(6) : color;
        // resolvedData에서 조회
        String hex = resolvedData.resolveColorHex(name);
        if (hex != null) return hex;
        // CMYK 문자열 파싱: "C=0 M=15 Y=80 K=0"
        if (name.contains("C=") && name.contains("M=")) {
            try {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("C=(\\d+\\.?\\d*)\\s+M=(\\d+\\.?\\d*)\\s+Y=(\\d+\\.?\\d*)\\s+K=(\\d+\\.?\\d*)")
                        .matcher(name);
                if (m.find()) {
                    double c = Double.parseDouble(m.group(1)) / 100.0;
                    double mm = Double.parseDouble(m.group(2)) / 100.0;
                    double y = Double.parseDouble(m.group(3)) / 100.0;
                    double k = Double.parseDouble(m.group(4)) / 100.0;
                    int r = (int) (255 * (1 - c) * (1 - k));
                    int g = (int) (255 * (1 - mm) * (1 - k));
                    int b = (int) (255 * (1 - y) * (1 - k));
                    return String.format("#%02X%02X%02X", r, g, b);
                }
            } catch (Exception e) {}
        }
        return null;
    }

    /**
     * resolved 런 중 가장 긴 텍스트를 가진 런을 기본값으로 선택.
     * 불릿(●, ▪ 등 1~2자)이나 특수문자 런이 아닌 본문 런을 우선 선택.
     */
    private ResolvedRun findDefaultResolvedRun(List<ResolvedRun> runs) {
        if (runs == null || runs.isEmpty()) return null;
        ResolvedRun longest = null;
        int maxLen = 0;
        for (ResolvedRun r : runs) {
            String text = r.text();
            if (text == null) continue;
            String trimmed = text.replace("\uFFFC", "").trim();
            if (trimmed.length() > maxLen) {
                maxLen = trimmed.length();
                longest = r;
            }
        }
        return longest != null ? longest : runs.get(0);
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
    private boolean splitIdmlRunByResolvedRuns(IDMLCharacterRun cr, String text,
            List<ResolvedRun> resolvedRuns, int startIdx,
            ASTParagraph para, StyleContext sc) {
        if (text == null || text.isEmpty() || resolvedRuns == null) return false;

        // resolved 런에서 이 텍스트와 겹치는 연속 런들을 찾기
        // 텍스트 시작부터 순차적으로 resolved 런 텍스트를 매칭
        String remaining = text;
        List<String[]> segments = new ArrayList<>(); // [segText, resolvedRunIndex]
        int rIdx = startIdx;
        boolean foundSplit = false;

        while (!remaining.isEmpty() && rIdx < resolvedRuns.size()) {
            ResolvedRun rr = resolvedRuns.get(rIdx);
            String rrText = rr.text();
            if (rrText == null || rrText.isEmpty()) { rIdx++; continue; }

            // resolved 런 텍스트가 remaining의 접두사인지 확인
            // 특수 공백(Figure Space \u2007 등)을 일반 공백으로 정규화하여 비교
            String normRemaining = normalizeSpaces(remaining);
            String normRRText = normalizeSpaces(rrText);
            if (normRemaining.startsWith(normRRText)) {
                // 원본 텍스트에서 정규화된 길이만큼 잘라냄
                int cutLen = findOriginalLength(remaining, normRRText.length());
                segments.add(new String[]{remaining.substring(0, cutLen), String.valueOf(rIdx)});
                remaining = remaining.substring(cutLen);
                rIdx++;
            } else if (remaining.startsWith(rrText)) {
                segments.add(new String[]{rrText, String.valueOf(rIdx)});
                remaining = remaining.substring(rrText.length());
                rIdx++;
            } else if (rrText.length() > 0 && remaining.startsWith(rrText.substring(0, Math.min(3, rrText.length())))) {
                // 부분 매칭: resolved 런 텍스트의 앞 3자가 remaining에 포함
                // remaining에서 다음 resolved 런의 시작 위치를 찾아 분할
                if (rIdx + 1 < resolvedRuns.size()) {
                    ResolvedRun nextRR = resolvedRuns.get(rIdx + 1);
                    String nextText = nextRR.text();
                    if (nextText != null && nextText.length() >= 3) {
                        String nextKey = nextText.substring(0, Math.min(5, nextText.length()));
                        int splitPos = remaining.indexOf(nextKey);
                        if (splitPos > 0) {
                            segments.add(new String[]{remaining.substring(0, splitPos), String.valueOf(rIdx)});
                            remaining = remaining.substring(splitPos);
                            rIdx++;
                            foundSplit = true;
                            continue;
                        }
                    }
                }
                // 분할 실패 시 나머지를 현재 런으로
                segments.add(new String[]{remaining, String.valueOf(rIdx)});
                remaining = "";
                rIdx++;
            } else {
                rIdx++; // 매칭 안 되면 다음 resolved 런 시도
            }
        }
        if (!remaining.isEmpty()) {
            segments.add(new String[]{remaining, String.valueOf(Math.max(0, rIdx - 1))});
        }

        // 분할이 없으면(세그먼트 1개) 기존 로직 사용
        if (segments.size() <= 1 && !foundSplit) return false;

        // 각 세그먼트별로 ASTTextRun 생성
        for (String[] seg : segments) {
            String segText = seg[0];
            int rrIdx = Integer.parseInt(seg[1]);
            ResolvedRun rr = (rrIdx >= 0 && rrIdx < resolvedRuns.size()) ? resolvedRuns.get(rrIdx) : null;
            ASTTextRun tr = createRunFromIDML(cr, segText, rr != null ? rr : findDefaultResolvedRun(resolvedRuns), sc);
            if (!splitBulletRun(tr, para)) {
                splitLatinVarsInMixedText(tr, para);
            }
        }
        return true;
    }

    /** resolved 런 간 스타일(색상, 폰트, fontStyle) 차이가 있는지 확인 */
    private boolean hasStyleVariation(List<ResolvedRun> runs) {
        if (runs == null || runs.size() <= 1) return false;
        String firstColor = null, firstFont = null, firstStyle = null;
        boolean initialized = false;
        for (ResolvedRun rr : runs) {
            if (rr.text() == null || rr.text().isEmpty()) continue;
            String color = rr.fillColor();
            String font = rr.fontFamily();
            String style = rr.fontStyle();
            if (!initialized) {
                firstColor = color; firstFont = font; firstStyle = style;
                initialized = true;
            } else {
                if (!eq(color, firstColor) || !eq(font, firstFont) || !eq(style, firstStyle)) return true;
            }
        }
        return false;
    }

    private static boolean eq(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    /** 특수 공백(Figure Space, En/Em Space 등)을 일반 공백으로 정규화 */
    private static String normalizeSpaces(String s) {
        if (s == null) return "";
        return s.replace('\u2007', ' ').replace('\u2002', ' ').replace('\u2003', ' ')
                .replace('\u2009', ' ').replace('\u200A', ' ').replace('\u00A0', ' ');
    }

    /** 정규화된 길이에 대응하는 원본 문자열의 실제 길이 (1:1 매핑이므로 동일) */
    private static int findOriginalLength(String original, int normalizedLen) {
        return Math.min(normalizedLen, original.length());
    }

    /**
     * 텍스트 기반 resolved 런 매칭.
     * 매칭 성공 시 resolvedRunIdx를 매칭된 인덱스+1로 갱신 (순차 추적).
     * 매칭 실패 시 null 반환 (호출측에서 defaultRR 사용).
     */
    private int[] lastMatchResult = new int[]{0}; // [0]=matched index

    private ResolvedRun findResolvedRun(List<ResolvedRun> runs, int startIdx, String text) {
        if (runs == null || runs.isEmpty() || text == null || text.isEmpty()) return null;
        String key = text.length() > 5 ? text.substring(0, 5) : text;
        // startIdx부터 순차 검색
        for (int i = startIdx; i < runs.size(); i++) {
            String rt = runs.get(i).text();
            if (rt != null && rt.contains(key)) {
                lastMatchResult[0] = i;
                return runs.get(i);
            }
        }
        // 못 찾으면 처음부터
        for (int i = 0; i < Math.min(startIdx, runs.size()); i++) {
            String rt = runs.get(i).text();
            if (rt != null && rt.contains(key)) {
                lastMatchResult[0] = i;
                return runs.get(i);
            }
        }
        // 매칭 실패 시 null 반환 — 호출측에서 defaultRR 사용
        return null;
    }

    private List<ASTParagraph> convertStoryParagraphs(ResolvedStory story) {
        List<ASTParagraph> paragraphs = new ArrayList<>();

        if (story.paragraphs() == null) return paragraphs;

        // 중복 단락 제거: InDesign의 overset/threaded frame에서
        // story.paragraphs가 중복 텍스트를 가진 단락을 반환하는 경우 감지
        String prevParaText = "";
        for (ResolvedParagraph rp : story.paragraphs()) {
            // 현재 단락 텍스트 추출
            StringBuilder sb = new StringBuilder();
            if (rp.runs() != null) {
                for (ResolvedRun r : rp.runs()) {
                    if (r.text() != null) sb.append(r.text());
                }
            }
            String curText = sb.toString().trim();

            // 이전 단락 끝부분과 현재 단락이 겹치면 건너뜀
            if (prevParaText.length() > 10 && curText.length() > 10) {
                String prevTail = prevParaText.substring(Math.max(0, prevParaText.length() - 20));
                if (curText.startsWith(prevTail.substring(Math.min(prevTail.length(), 5)))) {
                    continue; // 중복 단락 건너뜀
                }
            }
            prevParaText = curText;
            ASTParagraph para = new ASTParagraph();

            // 단락 스타일
            if (rp.styleName() != null) {
                para.paragraphStyleRef(rp.styleName());
            }

            // 단락 속성 (leading, spacing, indent는 InDesign에서 항상 pt 단위)
            if (rp.justification() != null) {
                para.alignment(rp.justification());
            } else if (rp.styleName() != null) {
                // 개별 단락에 justification 없으면 스타일에서 상속
                String styleJust = resolveStyleAlignment(rp.styleName(), astDoc);
                if (styleJust != null) para.alignment(styleJust);
            }
            Double fixedLeading = rp.fixedLeading();
            if (fixedLeading != null && fixedLeading > 0) {
                // InDesign Leading(pt) → HWPX 고정 줄간격(HWPUNIT)
                para.lineSpacing((int) CoordinateConverter.pointsToHwpunits(fixedLeading));
                para.lineSpacingType("fixed");
            }
            if (rp.spaceBefore() != null && rp.spaceBefore() > 0) {
                para.spaceBefore(CoordinateConverter.pointsToHwpunits(rp.spaceBefore()));
            }
            if (rp.spaceAfter() != null && rp.spaceAfter() > 0) {
                para.spaceAfter(CoordinateConverter.pointsToHwpunits(rp.spaceAfter()));
            }
            if (rp.leftIndent() != null && rp.leftIndent() != 0) {
                para.leftMargin(CoordinateConverter.pointsToHwpunits(rp.leftIndent()));
            }
            if (rp.firstLineIndent() != null && rp.firstLineIndent() != 0) {
                para.firstLineIndent(CoordinateConverter.pointsToHwpunits(rp.firstLineIndent()));
            }

            // 런 변환 (ResolvedParagraph → runs 직접)
            if (rp.runs() != null) {
                for (ResolvedRun run : rp.runs()) {
                    // inline_anchor: 인라인 그래픽 → ASTInlineObject로 변환
                    if (run.isInlineAnchor()) {
                        Integer anchoredId = run.anchoredObjectId();
                        if (anchoredId != null) {
                            // 커스텀 위치 앵커 객체 건너뛰기
                            if (isAnchoredOutsideParent(anchoredId, story.id())) {
                                continue;
                            }
                            // 짧은 텍스트 인라인 TextFrame → 텍스트 런으로 변환 우선
                            ASTTextRun textRun = tryInlineTextFrameAsRun(anchoredId);
                            if (textRun != null) {
                                para.addItem(textRun);
                                continue;
                            }
                            ASTInlineObject inlineObj = loadInlineObject(anchoredId);
                            if (inlineObj != null) {
                                para.addItem(inlineObj);
                                continue;
                            }
                            // PNG도 텍스트도 없는 인라인 앵커 → 빈칸 공백으로 대체
                            ASTTextRun spaceRun = createSpaceRunForEmptyAnchor(anchoredId);
                            if (spaceRun != null) {
                                para.addItem(spaceRun);
                                continue;
                            }
                        }
                        continue; // 로드 실패 시 건너뜀
                    }

                    ASTTextRun textRun = new ASTTextRun();
                    String runText = run.text();
                    // 특수 제어 문자 제거 (IDML 경로와 동일)
                    if (runText != null) {
                        runText = runText.replace("\t\u0008", "");
                        runText = runText.replace("\u0008", "");
                        runText = runText.replace("\n", "");
                        runText = runText.replace("\t", " ");
                    }
                    textRun.text(runText);
                    textRun.fontFamily(run.fontFamily());
                    if (run.fontSize() != null && run.fontSize() > 0) {
                        textRun.fontSizeHwpunits((int) Math.round(run.fontSize() * 100));
                    }
                    textRun.fontStyle(run.fontStyle());
                    textRun.textColor(resolveColorToHex(run.fillColor()));
                    if (run.tracking() != null && run.tracking() != 0) {
                        textRun.letterSpacing((short) Math.round(run.tracking() / 10.0));
                    }
                    if (run.horizontalScale() != null && run.horizontalScale() != 0 && run.horizontalScale() != 100) {
                        Double vs = run.verticalScale();
                        if (vs != null && Math.abs(run.horizontalScale() - vs) < 1.0) {
                            // 비례 확대: fontSize에 반영
                            if (textRun.fontSizeHwpunits() != null) {
                                textRun.fontSizeHwpunits((int) Math.round(textRun.fontSizeHwpunits() * run.horizontalScale() / 100.0));
                            }
                        } else {
                            textRun.horizontalScale((short) run.horizontalScale().doubleValue());
                        }
                    }
                    if (run.baselineShift() != null && run.baselineShift() != 0) {
                        textRun.baselineShift((short) run.baselineShift().doubleValue());
                    }
                    para.addItem(textRun);
                }
            }

            // 인라인 객체 boundsX 기반 재정렬
            ASTTableConverter.reorderInlineObjectsByBoundsX(para);

            paragraphs.add(para);
        }

        // 수식 폰트 런 → ASTEquation 변환 (단락별 후처리)
        for (ASTParagraph para : paragraphs) {
            convertMathRunsInParagraph(para);
        }

        return paragraphs;
    }

    /**
     * resolved-only 단락 내 수식 폰트 런(EH/BT/NP)을 ASTEquation으로 변환.
     * ASTTextRun의 fontFamily를 기반으로 IDMLCharacterRun 어댑터를 생성하여
     * ASTMathGrouper.flush* 메서드로 위임.
     */
    private void convertMathRunsInParagraph(ASTParagraph para) {
        List<ASTInlineItem> items = para.items();
        if (items == null || items.isEmpty()) return;

        List<ASTInlineItem> newItems = new ArrayList<>();
        List<IDMLCharacterRun> mathGroup = new ArrayList<>();
        String mathType = null; // "EH", "BT", "NP"

        for (int i = 0; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof ASTTextRun)) {
                flushResolvedMathGroup(mathGroup, mathType, newItems, para);
                mathGroup.clear();
                mathType = null;
                newItems.add(item);
                continue;
            }

            ASTTextRun tr = (ASTTextRun) item;
            String ff = tr.fontFamily();
            String currentType = null;
            if (ff != null) {
                if (EHFontGlyphMap.isEHFontFamily(ff)) currentType = "EH";
                else if (BTFontGlyphMap.isBTFontFamily(ff)) currentType = "BT";
                else if (NPFontGlyphMap.isNPFont(ff)) currentType = "NP";
            }

            if (currentType != null) {
                if (mathType == null || mathType.equals(currentType)) {
                    mathType = currentType;
                    IDMLCharacterRun cr = new IDMLCharacterRun();
                    cr.content(tr.text());
                    cr.fontFamily(ff);
                    mathGroup.add(cr);
                } else {
                    flushResolvedMathGroup(mathGroup, mathType, newItems, para);
                    mathGroup.clear();
                    mathType = currentType;
                    IDMLCharacterRun cr = new IDMLCharacterRun();
                    cr.content(tr.text());
                    cr.fontFamily(ff);
                    mathGroup.add(cr);
                }
            } else {
                // EH 그룹이 열려있고 마지막이 EH분수대문자(√)이면,
                // 비EH 런이라도 라틴/숫자로 시작하면 루트 내용으로 포함
                if ("EH".equals(mathType) && !mathGroup.isEmpty()) {
                    IDMLCharacterRun lastEH = mathGroup.get(mathGroup.size() - 1);
                    String text = tr.text();
                    if (EHFontGlyphMap.isFractionNumeratorFont(lastEH.fontFamily())
                            && text != null && !text.isEmpty()
                            && Character.isLetterOrDigit(text.charAt(0))
                            && !(text.charAt(0) >= 0xAC00 && text.charAt(0) <= 0xD7AF)) {
                        // 루트 내용으로 EH상부자처럼 포함
                        IDMLCharacterRun cr = new IDMLCharacterRun();
                        cr.content(text);
                        cr.fontFamily("EH상부자"); // 상부자로 간주
                        mathGroup.add(cr);
                        continue;
                    }
                }
                flushResolvedMathGroup(mathGroup, mathType, newItems, para);
                mathGroup.clear();
                mathType = null;
                newItems.add(item);
            }
        }
        flushResolvedMathGroup(mathGroup, mathType, newItems, para);

        if (newItems.size() != items.size() || !newItems.equals(items)) {
            items.clear();
            items.addAll(newItems);
        }
    }

    private void flushResolvedMathGroup(List<IDMLCharacterRun> group, String type,
                                         List<ASTInlineItem> out, ASTParagraph ignoredPara) {
        if (group == null || group.isEmpty()) return;
        // flush 메서드는 para에 직접 추가하므로, 임시 para를 사용하여 결과를 꺼냄
        ASTParagraph tempPara = new ASTParagraph();
        if ("EH".equals(type)) {
            ASTMathGrouper.flushEHMathGroup(group, tempPara);
        } else if ("BT".equals(type)) {
            ASTMathGrouper.flushMathGroup(group, tempPara);
        } else if ("NP".equals(type)) {
            ASTMathGrouper.flushNPMathGroup(group, tempPara);
        }
        out.addAll(tempPara.items());
    }

    /**
     * 수식 그룹 flush 헬퍼: null이 아닌 그룹만 flush하고 clear.
     */
    private void flushMathGroups(List<IDMLCharacterRun> btGroup,
                                  List<IDMLCharacterRun> npGroup,
                                  List<IDMLCharacterRun> ehGroup,
                                  ASTParagraph para) {
        if (btGroup != null && !btGroup.isEmpty()) {
            ASTMathGrouper.flushMathGroup(btGroup, para);
            btGroup.clear();
        }
        if (npGroup != null && !npGroup.isEmpty()) {
            ASTMathGrouper.flushNPMathGroup(npGroup, para);
            npGroup.clear();
        }
        if (ehGroup != null && !ehGroup.isEmpty()) {
            ASTMathGrouper.flushEHMathGroup(ehGroup, para);
            ehGroup.clear();
        }
    }


    /**
     * 텍스트 내 ;...; 분수 GREP 패턴을 인라인 수식(ASTEquation)으로 분리.
     * 예: "이므로 ;4!;의 제곱근은" → "이므로 " + ASTEquation({1} over {4}) + "의 제곱근은"
     */
    private void splitFractionPatternInText(String text, ASTTextRun templateRun, ASTParagraph para) {
        for (EHGrepFractionConverter.Segment seg : EHGrepFractionConverter.splitAndConvert(text)) {
            if (seg.type() == EHGrepFractionConverter.Segment.Type.EQUATION) {
                para.addItem(new ASTEquation(seg.content(), "EH_FONT"));
            } else {
                ASTTextRun tr = new ASTTextRun();
                tr.text(seg.content());
                tr.fontFamily(templateRun.fontFamily());
                tr.fontStyle(templateRun.fontStyle());
                tr.fontSizeHwpunits(templateRun.fontSizeHwpunits());
                tr.textColor(templateRun.textColor());
                para.addItem(tr);
            }
        }
    }

    /**
     * EH 그룹이 열려있고 마지막이 EH분수대문자(√)일 때,
     * 바로 뒤의 짧은 비EH 런이 루트 내용(radicand)인지 판단.
     * GREP 스타일이 IDML에 반영되지 않아 fontFamily=null인 런도 포함.
     */
    private static boolean isEHSqrtContent(IDMLCharacterRun run,
                                            List<IDMLCharacterRun> ehGroup) {
        if (ehGroup.isEmpty()) return false;
        // 마지막 EH 런이 분수대문자(√)인지
        IDMLCharacterRun last = ehGroup.get(ehGroup.size() - 1);
        if (!EHFontGlyphMap.isFractionNumeratorFont(last.fontFamily())) return false;
        // 현재 런이 짧은 라틴/수학 텍스트인지 (한국어만으로 시작하면 제외)
        String text = run.content();
        if (text == null || text.isEmpty()) return false;
        char first = text.charAt(0);
        // 첫 문자가 라틴 알파벳, 숫자, 수학 기호이면 루트 내용
        return Character.isLetterOrDigit(first)
                && !(first >= 0xAC00 && first <= 0xD7AF)
                && !(first >= 0x3131 && first <= 0x318E);
    }

    /**
     * 텍스트 기반 단락 분배: frameParaTexts로 IDML 단락을 각 프레임에 할당.
     * paragraphStart/End 인덱스 대신, 텍스트 내용을 순차 매칭하여 프레임 간 단락 분할을 정확히 처리.
     */
    /**
     * wrap 분할 블록에 대한 텍스트 분배.
     * 각 블록의 frameVisibleText를 기반으로 전체 스토리 텍스트에서 해당 범위를 찾아 단락을 할당.
     */
    private void distributeByWrapVisibleText(List<ASTParagraph> paragraphs,
                                              List<ASTTextFrameBlock> blocks) {
        // 전체 단락 텍스트 합침
        StringBuilder sb = new StringBuilder();
        List<int[]> paraRanges = new ArrayList<>();
        for (ASTParagraph p : paragraphs) {
            int s = sb.length();
            String pt = getParaPlainText(p);
            sb.append(pt != null ? pt : "");
            paraRanges.add(new int[]{s, sb.length()});
        }
        String storyText = sb.toString();

        // 각 wrap 블록의 frameVisibleText로 storyText 내 범위 결정
        int searchFrom = 0;
        for (ASTTextFrameBlock block : blocks) {
            String visText = block.frameVisibleText();
            if (visText == null || visText.isEmpty()) continue;

            // 특수 문자 정규화하여 검색
            String cleanVis = normalizeSpaces(visText.replace("\uFFFC", "").replace("\r", "").replace("\n", ""));
            String cleanStory = normalizeSpaces(storyText);

            // visText의 앞부분(20자)으로 시작 위치 찾기
            String startKey = cleanVis.length() > 15 ? cleanVis.substring(0, 15) : cleanVis;
            int foundStart = cleanStory.indexOf(startKey, searchFrom);
            if (foundStart < 0) foundStart = searchFrom;

            int foundEnd = foundStart + cleanVis.length();
            if (foundEnd > storyText.length()) foundEnd = storyText.length();

            // 범위에 겹치는 단락 할당
            for (int i = 0; i < paragraphs.size(); i++) {
                int pStart = paraRanges.get(i)[0];
                int pEnd = paraRanges.get(i)[1];
                if (pEnd <= foundStart) continue;
                if (pStart >= foundEnd) break;

                if (pStart >= foundStart && pEnd <= foundEnd) {
                    block.addParagraph(paragraphs.get(i));
                } else if (pStart < foundEnd && pEnd > foundEnd) {
                    int cutLen = foundEnd - pStart;
                    String fullText = getParaPlainText(paragraphs.get(i));
                    ASTParagraph trimmed = createSplitParagraph(paragraphs.get(i),
                            fullText != null ? fullText.substring(0, Math.min(cutLen, fullText.length())) : "");
                    if (trimmed != null) block.addParagraph(trimmed);
                } else if (pStart < foundStart && pEnd > foundStart) {
                    int skipLen = foundStart - pStart;
                    String fullText = getParaPlainText(paragraphs.get(i));
                    String contText = (fullText != null && skipLen < fullText.length()) ? fullText.substring(skipLen) : "";
                    ASTParagraph cont = createContinuationParagraph(paragraphs.get(i), skipLen, contText);
                    if (cont != null) block.addParagraph(cont);
                }
            }
            searchFrom = foundEnd;
        }
    }

    /**
     * composedLines 문자 범위 기반 단락 분배.
     * 각 블록의 composedCharStart~composedCharEnd 범위에 해당하는 단락을 할당.
     */
    private void distributeByComposedCharRange(List<ASTParagraph> paragraphs,
                                                List<ASTTextFrameBlock> blocks) {
        // 전체 단락 텍스트를 연속 문자열로 합침
        StringBuilder sb = new StringBuilder();
        List<int[]> paraRanges = new ArrayList<>();
        for (ASTParagraph p : paragraphs) {
            int s = sb.length();
            String pt = getParaPlainText(p);
            sb.append(pt != null ? pt : "");
            paraRanges.add(new int[]{s, sb.length()});
        }

        // 범위 겹침 기반 분배 (단락이 블록 경계를 걸치면 분할)
        for (ASTTextFrameBlock block : blocks) {
            int blockStart = block.composedCharStart();
            int blockEnd = block.composedCharEnd();
            if (blockStart < 0) continue;

            // YGapSplit 블록: composedCharStart/End가 paraIndex 범위를 의미
            boolean isYGapBlock = block.sourceId() != null && block.sourceId().contains("_g");
            if (isYGapBlock) {
                for (int i = 0; i < paragraphs.size(); i++) {
                    if (i >= blockStart && i <= blockEnd) {
                        block.addParagraph(paragraphs.get(i));
                    }
                }
                continue;
            }

            for (int i = 0; i < paragraphs.size(); i++) {
                int paraStart = paraRanges.get(i)[0];
                int paraEnd = paraRanges.get(i)[1];

                if (paraEnd <= blockStart) continue; // 단락이 블록 이전
                if (paraStart >= blockEnd) break;    // 단락이 블록 이후

                if (paraStart >= blockStart && paraEnd <= blockEnd) {
                    // 단락이 블록 안에 완전히 포함
                    block.addParagraph(paragraphs.get(i));
                } else if (paraStart < blockEnd && paraEnd > blockEnd) {
                    // 단락이 블록 끝을 넘김 → 앞부분만
                    int cutLen = blockEnd - paraStart;
                    ASTParagraph trimmed = createSplitParagraph(paragraphs.get(i),
                            getParaPlainText(paragraphs.get(i)) != null
                                    ? getParaPlainText(paragraphs.get(i)).substring(0, Math.min(cutLen, getParaPlainText(paragraphs.get(i)).length()))
                                    : "");
                    if (trimmed != null) block.addParagraph(trimmed);
                } else if (paraStart < blockStart && paraEnd > blockStart) {
                    // 이전 블록에서 시작된 단락의 나머지
                    int skipLen = blockStart - paraStart;
                    String fullText = getParaPlainText(paragraphs.get(i));
                    String contText = (fullText != null && skipLen < fullText.length())
                            ? fullText.substring(skipLen) : "";
                    ASTParagraph cont = createContinuationParagraph(paragraphs.get(i), skipLen, contText);
                    if (cont != null) block.addParagraph(cont);
                }
            }
        }
    }

    private void distributeParagraphs(List<ASTParagraph> paragraphs,
                                       List<ASTTextFrameBlock> blocks, String storyId) {
        // composedLines 분할 블록 감지: composedCharStart >= 0인 블록이 있으면
        boolean hasComposedBlocks = false;
        for (ASTTextFrameBlock b : blocks) {
            if (b.composedCharStart() >= 0) { hasComposedBlocks = true; break; }
        }
        if (hasComposedBlocks) {
            distributeByComposedCharRange(paragraphs, blocks);
            return;
        }

        // 단일 프레임: frameVisibleText와 Story 텍스트 길이 비교
        if (blocks.size() == 1) {
            ASTTextFrameBlock block = blocks.get(0);
            // Story 텍스트가 길고 frameVisibleText가 거의 비어있으면 할당하지 않음
            // (다른 페이지의 프레임에서 실제로 표시되는 텍스트가 이 프레임에 잘못 할당되는 것 방지)
            int storyLen = 0;
            for (ASTParagraph p : paragraphs) {
                String pt = getParaPlainText(p);
                if (pt != null) storyLen += pt.length();
            }
            int visLen = block.frameVisibleTextLength();
            if (storyLen > 20 && visLen <= 1) {
                // Story가 20자 이상인데 프레임에 보이는 텍스트가 0~1자 → 오버플로우/미표시 프레임
                return;
            }
            for (ASTParagraph p : paragraphs) {
                block.addParagraph(p);
            }
            return;
        }

        // 다중 프레임: frameVisibleText 기반 분배
        List<ASTTextFrameBlock> ordered = orderByThreadChain(blocks);

        // 전체 IDML 단락 텍스트를 하나의 연속 문자열로 합침
        StringBuilder storyTextBuilder = new StringBuilder();
        List<int[]> paraRanges = new ArrayList<>(); // [startCharIdx, endCharIdx]
        for (ASTParagraph p : paragraphs) {
            int s = storyTextBuilder.length();
            String pt = getParaPlainText(p);
            storyTextBuilder.append(pt != null ? pt : "");
            paraRanges.add(new int[]{s, storyTextBuilder.length()});
        }
        String storyText = storyTextBuilder.toString();

        // 각 프레임의 첫 frameParaText를 storyText에서 검색하여 정확한 범위 결정
        // 프레임별 (startOffset, endOffset) 계산
        int[][] frameRanges = new int[ordered.size()][2];
        int searchFrom = 0;
        for (int fi = 0; fi < ordered.size(); fi++) {
            ASTTextFrameBlock block = ordered.get(fi);
            String sid2 = block.sourceId();
            String hex2 = sid2 != null && sid2.startsWith("u") ? sid2.substring(1) : sid2;
            if (hex2 != null && hex2.contains("_")) hex2 = hex2.substring(0, hex2.indexOf('_'));
            String domId;
            try { domId = String.valueOf(Integer.parseInt(hex2, 16)); }
            catch (NumberFormatException e) { domId = sid2; }
            ResolvedTextFrame rtf = resolvedData.getTextFrame(domId);
            // wrap 분할 블록은 블록 자체의 frameVisibleText 우선
            String visibleText = block.frameVisibleText();
            if (visibleText == null) {
                visibleText = (rtf != null) ? rtf.frameVisibleText() : null;
            }
            if (visibleText != null) {
                visibleText = visibleText.replace("\uFFFC", "").replace("\n", "");
            }

            if (visibleText == null || visibleText.isEmpty()) {
                // frameVisibleText가 없으면 frameParaTexts 폴백
                java.util.List<String> frameTexts = (rtf != null) ? rtf.frameParaTexts() : null;
                if (frameTexts != null && !frameTexts.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (String ft : frameTexts) {
                        if (ft != null) sb.append(ft.replace("\uFFFC", ""));
                    }
                    visibleText = sb.toString();
                }
            }

            if (visibleText == null || visibleText.isEmpty()) {
                frameRanges[fi][0] = searchFrom;
                frameRanges[fi][1] = (fi == ordered.size() - 1) ? storyText.length() : searchFrom;
                continue;
            }

            // visibleText의 앞부분을 storyText에서 검색하여 시작 위치 결정
            String startKey = visibleText.length() > 20 ? visibleText.substring(0, 20) : visibleText;
            int foundStart = storyText.indexOf(startKey, searchFrom);
            if (foundStart < 0) foundStart = searchFrom;

            // visibleText의 끝부분을 storyText에서 검색하여 종료 위치 결정
            String endKey = visibleText.length() > 20 ? visibleText.substring(visibleText.length() - 20) : visibleText;
            int foundEnd = storyText.indexOf(endKey, foundStart);
            if (foundEnd >= 0) {
                foundEnd += endKey.length();
            } else {
                foundEnd = foundStart + visibleText.length();
            }

            frameRanges[fi][0] = foundStart;
            frameRanges[fi][1] = Math.min(foundEnd, storyText.length());
            searchFrom = frameRanges[fi][1];
        }

        // 프레임별 단락 할당
        for (int fi = 0; fi < ordered.size(); fi++) {
            ASTTextFrameBlock block = ordered.get(fi);
            int frameStart = frameRanges[fi][0];
            int frameEnd = frameRanges[fi][1];
            String sid3 = block.sourceId();
            String hex3 = sid3 != null && sid3.startsWith("u") ? sid3.substring(1) : sid3;
            if (hex3 != null && hex3.contains("_")) hex3 = hex3.substring(0, hex3.indexOf('_'));
            String domId3;
            try { domId3 = String.valueOf(Integer.parseInt(hex3, 16)); }
            catch (NumberFormatException e) { domId3 = sid3; }
            ResolvedTextFrame rtf = resolvedData.getTextFrame(domId3);
            java.util.List<String> frameTexts = (rtf != null) ? rtf.frameParaTexts() : null;

            if (frameTexts == null || frameTexts.isEmpty()) {
                int start = (rtf != null) ? Math.max(0, rtf.paragraphStart()) : 0;
                int end = (rtf != null && rtf.paragraphEnd() >= 0) ? rtf.paragraphEnd() : paragraphs.size() - 1;
                for (int i = start; i <= end && i < paragraphs.size(); i++) {
                    block.addParagraph(paragraphs.get(i));
                }
                continue;
            }

            for (int i = 0; i < paragraphs.size(); i++) {
                int paraStart = paraRanges.get(i)[0];
                int paraEnd = paraRanges.get(i)[1];

                if (paraEnd <= frameStart) continue;
                if (paraStart >= frameEnd) break;

                if (paraStart >= frameStart && paraEnd <= frameEnd) {
                    // 단락이 프레임 안에 완전히 포함
                    block.addParagraph(paragraphs.get(i));
                } else if (paraStart < frameEnd && paraEnd > frameEnd) {
                    // 단락이 프레임 경계에 걸침 → 앞부분만 (cutLen 글자)
                    int cutLen = frameEnd - paraStart;
                    String fullText = getParaPlainText(paragraphs.get(i));
                    String cutText = (fullText != null && cutLen < fullText.length()) ? fullText.substring(0, cutLen) : fullText;
                    ASTParagraph trimmed = createSplitParagraph(paragraphs.get(i), cutText);
                    if (trimmed != null) {
                        block.addParagraph(trimmed);
                    }
                } else if (paraStart < frameStart && paraEnd > frameStart) {
                    // 이전 프레임에서 시작된 단락의 나머지
                    int skipLen = frameStart - paraStart;
                    String fullText = getParaPlainText(paragraphs.get(i));
                    String contText = (fullText != null && skipLen < fullText.length()) ? fullText.substring(skipLen) : "";
                    ASTParagraph continuation = createContinuationParagraph(paragraphs.get(i), skipLen, contText);
                    if (continuation != null) {
                        block.addParagraph(continuation);
                    }
                }
            }
        }
    }

    /**
     * 원본 단락에서 skipLen 이후의 텍스트만 포함하는 이어지기 단락 생성.
     */
    private ASTParagraph createContinuationParagraph(ASTParagraph original, int skipLen, String expectedText) {
        ASTParagraph cont = new ASTParagraph();
        cont.paragraphStyleRef(original.paragraphStyleRef());
        cont.alignment(original.alignment());
        if (original.lineSpacing() != null) {
            cont.lineSpacing(original.lineSpacing());
            cont.lineSpacingType(original.lineSpacingType());
        }
        if (original.spaceBefore() != null) cont.spaceBefore(original.spaceBefore());
        if (original.spaceAfter() != null) cont.spaceAfter(original.spaceAfter());
        if (original.leftMargin() != null) cont.leftMargin(original.leftMargin());

        int skipped = 0;
        for (ASTInlineItem item : original.items()) {
            if (item instanceof ASTTextRun) {
                ASTTextRun origRun = (ASTTextRun) item;
                String text = origRun.text();
                if (text == null) continue;
                if (skipped + text.length() <= skipLen) {
                    skipped += text.length();
                    continue;
                }
                if (skipped < skipLen) {
                    // 런 중간에서 시작
                    int offset = skipLen - skipped;
                    ASTTextRun partialRun = new ASTTextRun();
                    partialRun.text(text.substring(offset));
                    partialRun.fontFamily(origRun.fontFamily());
                    partialRun.fontStyle(origRun.fontStyle());
                    partialRun.fontSizeHwpunits(origRun.fontSizeHwpunits());
                    partialRun.textColor(origRun.textColor());
                    cont.addItem(partialRun);
                    skipped = skipLen;
                } else {
                    cont.addItem(origRun);
                }
            } else {
                if (skipped >= skipLen) {
                    cont.addItem(item);
                } else {
                    skipped++;
                }
            }
        }

        return cont.items().isEmpty() ? null : cont;
    }

    /** ASTParagraph의 전체 plain text를 반환 */
    private String getParaPlainText(ASTParagraph para) {
        StringBuilder sb = new StringBuilder();
        for (ASTInlineItem item : para.items()) {
            if (item instanceof ASTTextRun) {
                String t = ((ASTTextRun) item).text();
                if (t != null) sb.append(t);
            }
        }
        return sb.toString();
    }

    /**
     * 원본 단락에서 frameText에 해당하는 부분만 포함하는 분할 단락 생성.
     * 원본 단락의 스타일을 복제하고, 텍스트 런을 frameText 길이에 맞게 잘라냄.
     */
    private ASTParagraph createSplitParagraph(ASTParagraph original, String frameText) {
        if (frameText == null || frameText.trim().isEmpty()) return null;

        ASTParagraph split = new ASTParagraph();
        // 단락 스타일 복제
        split.paragraphStyleRef(original.paragraphStyleRef());
        split.alignment(original.alignment());
        if (original.lineSpacing() != null) {
            split.lineSpacing(original.lineSpacing());
            split.lineSpacingType(original.lineSpacingType());
        }
        if (original.spaceBefore() != null) split.spaceBefore(original.spaceBefore());
        if (original.spaceAfter() != null) split.spaceAfter(original.spaceAfter());
        if (original.leftMargin() != null) split.leftMargin(original.leftMargin());
        if (original.firstLineIndent() != null) split.firstLineIndent(original.firstLineIndent());

        // 텍스트 런을 frameText 길이에 맞게 복제
        int remaining = frameText.length();
        for (ASTInlineItem item : original.items()) {
            if (remaining <= 0) break;
            if (item instanceof ASTTextRun) {
                ASTTextRun origRun = (ASTTextRun) item;
                String text = origRun.text();
                if (text == null) continue;
                if (text.length() <= remaining) {
                    split.addItem(origRun); // 전체 런 복제
                    remaining -= text.length();
                } else {
                    // 런 잘라내기
                    ASTTextRun trimmedRun = new ASTTextRun();
                    trimmedRun.text(text.substring(0, remaining));
                    trimmedRun.fontFamily(origRun.fontFamily());
                    trimmedRun.fontStyle(origRun.fontStyle());
                    trimmedRun.fontSizeHwpunits(origRun.fontSizeHwpunits());
                    trimmedRun.textColor(origRun.textColor());
                    split.addItem(trimmedRun);
                    remaining = 0;
                }
            } else {
                split.addItem(item); // 인라인 객체는 그대로
                remaining--; // U+FFFC 자리
            }
        }

        return split.items().isEmpty() ? null : split;
    }

    /**
     * 스레드 체인 순서로 블록 정렬: previousFrameId=null인 첫 번째 프레임부터 순서대로.
     */
    private List<ASTTextFrameBlock> orderByThreadChain(List<ASTTextFrameBlock> blocks) {
        if (blocks.size() <= 1) return blocks;

        // domId → block 매핑
        Map<String, ASTTextFrameBlock> byDomId = new java.util.LinkedHashMap<String, ASTTextFrameBlock>();
        for (ASTTextFrameBlock b : blocks) {
            String sid = b.sourceId();
            if (sid == null) continue;
            String hexPart = sid.startsWith("u") ? sid.substring(1) : sid;
            if (hexPart.contains("_")) hexPart = hexPart.substring(0, hexPart.indexOf('_'));
            String domId;
            try { domId = String.valueOf(Integer.parseInt(hexPart, 16)); }
            catch (NumberFormatException e) { domId = sid; }
            byDomId.put(domId, b);
        }

        // 첫 번째 프레임 찾기 (previousFrameId=null)
        String firstId = null;
        for (String domId : byDomId.keySet()) {
            ResolvedTextFrame rtf = resolvedData.getTextFrame(domId);
            if (rtf != null && rtf.previousFrameId() == null) {
                firstId = domId;
                break;
            }
        }

        if (firstId == null) return blocks; // 체인 시작을 못 찾으면 원래 순서

        // 체인 순서로 정렬
        List<ASTTextFrameBlock> ordered = new ArrayList<ASTTextFrameBlock>();
        String currentId = firstId;
        java.util.Set<String> visited = new java.util.HashSet<String>();
        while (currentId != null && !visited.contains(currentId)) {
            visited.add(currentId);
            ASTTextFrameBlock b = byDomId.get(currentId);
            if (b != null) ordered.add(b);
            ResolvedTextFrame rtf = resolvedData.getTextFrame(currentId);
            currentId = (rtf != null) ? rtf.nextFrameId() : null;
        }

        // 체인에 포함되지 않은 블록 추가
        for (ASTTextFrameBlock b : blocks) {
            if (!ordered.contains(b)) ordered.add(b);
        }

        return ordered;
    }

    // ═══════════════════════════════════════════════════
    // 인라인 객체 로드
    // ═══════════════════════════════════════════════════

    /**
     * 인라인 앵커 객체가 짧은 텍스트(1~5자)를 가진 TextFrame이면
     * PNG 이미지 대신 ASTTextRun으로 변환 (줄간격 영향 없음, 폰트 매핑 가능).
     * @return ASTTextRun (텍스트로 변환됨) 또는 null (PNG 변환 필요)
     */
    private ASTTextRun tryInlineTextFrameAsRun(int anchoredObjectId) {
        String domId = String.valueOf(anchoredObjectId);
        ResolvedTextFrame tf = resolvedData.getTextFrame(domId);
        if (tf == null || !tf.isInline()) return null;

        // rendered된 TF(badge_group 등)는 PNG로 이미 배치됨 → 텍스트 런 변환 안 함
        if (resolvedData.isRenderedByOtherChannel(anchoredObjectId)) return null;

        // frameVisibleText 또는 IDML Story에서 텍스트 가져오기
        String visText = tf.frameVisibleText();
        if (visText != null) {
            visText = visText.replace("\uFFFC", "").replace("\n", "").replace("\r", "").trim();
        }
        if (visText == null || visText.isEmpty()) {
            // IDML Story에서 폴백
            if (tf.storyId() != null) {
                IDMLStory idmlStory = loadIDMLStory(tf.storyId());
                if (idmlStory != null) {
                    StringBuilder sb = new StringBuilder();
                    for (IDMLParagraph p : idmlStory.paragraphs()) {
                        for (IDMLCharacterRun r : p.characterRuns()) {
                            if (r.content() != null) sb.append(r.content());
                        }
                    }
                    visText = sb.toString().replace("\uFFFC", "").trim();
                }
            }
        }
        if (visText == null || visText.isEmpty()) return null;

        // 장식 번호 인라인(≤3자 + 큰 폰트 또는 정사각형 프레임) → 텍스트 런 변환하지 않음 (PNG 유지)
        if (visText.length() <= 3) {
            ResolvedStory rs = (tf.storyId() != null) ? resolvedData.getStory(tf.storyId()) : null;
            boolean isDecorativeNumber = false;
            // 큰 폰트(≥16pt) 체크
            if (rs != null && !rs.paragraphs().isEmpty()) {
                ResolvedParagraph rp0 = rs.paragraphs().get(0);
                if (rp0.runs() != null && !rp0.runs().isEmpty()) {
                    Double fs = rp0.runs().get(0).fontSize();
                    if (fs != null && fs >= 16) isDecorativeNumber = true;
                }
            }
            // 정사각형에 가까운 프레임 (가로/세로 비율 0.7~1.4)
            double[] gb = tf.geometricBounds();
            if (gb != null && gb.length >= 4) {
                double fw = gb[3] - gb[1];
                double fh = gb[2] - gb[0];
                if (fw > 0 && fh > 0) {
                    double ratio = fw / fh;
                    if (ratio >= 0.7 && ratio <= 1.4) isDecorativeNumber = true;
                }
            }
            if (isDecorativeNumber) return null; // PNG 폴백 (loadInlineObject로 처리)
        }

        // resolved story에서 런 스타일 가져오기
        ResolvedStory story = (tf.storyId() != null) ? resolvedData.getStory(tf.storyId()) : null;
        ASTTextRun run = new ASTTextRun();
        run.text(visText + " "); // 뒤에 공백 추가 (텍스트와의 간격)

        if (story != null && !story.paragraphs().isEmpty()) {
            ResolvedParagraph rp = story.paragraphs().get(0);
            if (rp.runs() != null && !rp.runs().isEmpty()) {
                ResolvedRun rr = rp.runs().get(0);
                if (rr.fontFamily() != null) run.fontFamily(rr.fontFamily());
                if (rr.fontStyle() != null) run.fontStyle(rr.fontStyle());
                if (rr.fontSize() != null && rr.fontSize() > 0) {
                    run.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(rr.fontSize()));
                }
                if (rr.fillColor() != null) run.textColor(resolveColorToHex(rr.fillColor()));
                if (rr.underline() != null && rr.underline()) run.underline(true);
                if (rr.strikeThru() != null && rr.strikeThru()) run.strikeThrough(true);
            }
        }
        // IDML CharacterStyle에서 밑줄 추론
        if (tf.storyId() != null) {
            IDMLStory idmlStory = loadIDMLStory(tf.storyId());
            if (idmlStory != null && !idmlStory.paragraphs().isEmpty()) {
                IDMLParagraph ip = idmlStory.paragraphs().get(0);
                if (!ip.characterRuns().isEmpty()) {
                    IDMLCharacterRun cr = ip.characterRuns().get(0);
                    if (cr.underline() != null && cr.underline()) run.underline(true);
                    String cs = cr.appliedCharacterStyle();
                    if (cs != null && (cs.contains("밑줄") || cs.toLowerCase().contains("underline"))) {
                        run.underline(true);
                    }
                }
            }
        }

        // 인라인 TextFrame의 ParagraphStyle에서 밑줄 추론
        // resolved story의 styleName에 "선", "답+선", "underline" 등이 포함되면 밑줄
        if (story != null && !story.paragraphs().isEmpty()) {
            String styleName = story.paragraphs().get(0).styleName();
            if (styleName != null && (styleName.contains("선") || styleName.toLowerCase().contains("underline"))) {
                run.underline(true);
            }
        }

        return run;
    }

    /**
     * 인라인 앵커 객체가 부모 TextFrame의 X 범위 밖에 있는지 판별.
     * 커스텀 위치 앵커(AnchoredPosition=Anchored + 큰 오프셋)는 텍스트 흐름과 무관하게 배치되므로
     * 인라인 삽입하면 안 된다.
     */
    /** IDML 경로용: resolved TextFrame bounds만으로 판별 (renderedFloatingItems 사용 안 함) */
    private boolean isAnchoredOutsideParentByTextFrame(int anchoredId, String parentStoryId) {
        ResolvedTextFrame anchoredTf = resolvedData.getTextFrame(String.valueOf(anchoredId));
        if (anchoredTf == null) {
            // TextFrame이 아닌 인라인(Polygon/Rectangle 등): renderedFloatingItems에서 bounds 확인
            return isAnchoredOutsideParent(anchoredId, parentStoryId);
        }
        double[] aGb = anchoredTf.geometricBounds();
        if (aGb == null || aGb.length < 4) return false;
        for (ResolvedTextFrame tf : resolvedData.textFrames()) {
            if (parentStoryId.equals(tf.storyId()) && !tf.isInline()) {
                double[] pGb = tf.geometricBounds();
                if (pGb == null || pGb.length < 4) continue;
                return isOutsideParentBounds(aGb, pGb);
            }
        }
        return false;
    }

    /** Resolved 경로용: resolved TextFrame + renderedFloatingItems bounds로 판별 */
    private boolean isAnchoredOutsideParent(int anchoredId, String parentStoryId) {
        double[] aGb = null;
        // 1) resolved TextFrame에서 bounds
        ResolvedTextFrame anchoredTf = resolvedData.getTextFrame(String.valueOf(anchoredId));
        if (anchoredTf != null) {
            aGb = anchoredTf.geometricBounds();
        }
        // 2) resolved에 없으면 renderedFloatingItems의 inline_object bounds
        //    renderedFloatingItems bounds は mm 単位 → pt に変換が必要
        if (aGb == null || aGb.length < 4) {
            for (RenderedGroup rg : resolvedData.allRenderedFloatingItems()) {
                if (rg.id() == anchoredId && "inline_object".equals(rg.itemType())) {
                    double[] raw = rg.bounds();
                    if (raw != null && raw.length >= 4) {
                        aGb = new double[]{raw[0] * scaleFactor, raw[1] * scaleFactor,
                                raw[2] * scaleFactor, raw[3] * scaleFactor};
                    }
                    break;
                }
            }
        }
        if (aGb == null || aGb.length < 4) return false;

        // 부모 Story의 첫 번째 (주) TextFrame 찾기
        for (ResolvedTextFrame tf : resolvedData.textFrames()) {
            if (parentStoryId.equals(tf.storyId()) && !tf.isInline()) {
                double[] pGb = tf.geometricBounds();
                if (pGb == null || pGb.length < 4) continue;
                return isOutsideParentBounds(aGb, pGb);
            }
        }
        return false;
    }

    /**
     * 인라인 객체 bounds가 부모 프레임 밖에 위치하는지 판단.
     * - 중심 X가 부모 밖 3pt 이상 → outside
     * - 오른쪽 끝이 부모 밖으로 돌출하고 폭의 50% 이상 밖 → outside (장식 그래픽)
     */
    private boolean isOutsideParentBounds(double[] aGb, double[] pGb) {
        double aCenterX = (aGb[1] + aGb[3]) / 2.0;
        // 중심 X 기준 (기존 로직, 허용 오차 3pt)
        if (aCenterX > pGb[3] + 3.0 || aCenterX < pGb[1] - 3.0) return true;
        // 오른쪽 돌출 체크: 인라인 객체의 절반 이상이 부모 밖
        double aWidth = aGb[3] - aGb[1];
        if (aWidth > 0 && aGb[3] > pGb[3]) {
            double overshoot = aGb[3] - pGb[3];
            if (overshoot > aWidth * 0.5) return true;
        }
        // 왼쪽 돌출 체크
        if (aWidth > 0 && aGb[1] < pGb[1]) {
            double overshoot = pGb[1] - aGb[1];
            if (overshoot > aWidth * 0.5) return true;
        }
        return false;
    }

    /**
     * PNG/텍스트 없는 인라인 빈칸 앵커를 공백 텍스트 런으로 대체.
     * 교과서 빈칸 채우기 문제의 ( ) 안 공백 등.
     */
    private ASTTextRun createSpaceRunForEmptyAnchor(int anchoredObjectId) {
        // 빈칸 그래픽의 기본 공백: 6칸 (약 20pt 폭)
        ASTTextRun run = new ASTTextRun();
        run.text("      "); // 6 spaces
        return run;
    }

    /**
     * renderedFloatingItems에서 인라인 객체 PNG를 로드하여 ASTInlineObject로 변환.
     */
    private ASTInlineObject loadInlineObject(int anchoredObjectId) {
        if (basePath == null) return null;

        // renderedFloatingItems에서 해당 ID의 inline_object 찾기
        for (RenderedGroup rg : resolvedData.allRenderedFloatingItems()) {
            if (rg.id() == anchoredObjectId && "inline_object".equals(rg.itemType())) {
                if (rg.file() == null) return null;
                File pngFile = new File(basePath, rg.file());
                if (!pngFile.exists()) return null;

                try {
                    byte[] imageData = java.nio.file.Files.readAllBytes(pngFile.toPath());
                    BufferedImage img = ImageIO.read(pngFile);
                    if (img == null) return null;
                    // 2x2 이하 빈 이미지 무시
                    if (img.getWidth() <= 2 && img.getHeight() <= 2) return null;

                    ASTInlineObject obj = new ASTInlineObject();
                    obj.kind(ASTInlineObject.ObjectKind.IMAGE);
                    obj.imageData(imageData);
                    obj.imageFormat("png");
                    obj.pixelWidth(img.getWidth());
                    obj.pixelHeight(img.getHeight());

                    // 크기: bounds [top, left, bottom, right]
                    double[] bounds = rg.bounds();
                    if (bounds != null && bounds.length >= 4) {
                        obj.boundsX(bounds[1]); // rendered X 좌표 (인라인 정렬용)
                        double bw = Math.abs(bounds[3] - bounds[1]) * scaleFactor; // right - left
                        double bh = Math.abs(bounds[2] - bounds[0]) * scaleFactor; // bottom - top
                        // PNG 비율로 보정 (bounds가 부정확한 경우)
                        double pngRatio = (double) img.getWidth() / img.getHeight();
                        double boundsRatio = bw / bh;
                        // bounds 비율과 PNG 비율이 다르면 PNG 비율 기준으로 보정
                        // bounds의 작은 쪽을 기준으로 맞춤 (원본 크기 초과 방지)
                        if (Math.abs(pngRatio - boundsRatio) / Math.max(pngRatio, boundsRatio) > 0.1) {
                            if (pngRatio < 1.0) {
                                // 세로가 더 긴 PNG → 높이 유지, 폭 축소
                                bw = bh * pngRatio;
                            } else {
                                // 가로가 더 긴 PNG → 폭 유지, 높이 축소
                                bh = bw / pngRatio;
                            }
                        }
                        obj.width(CoordinateConverter.pointsToHwpunits(bw));
                        obj.height(CoordinateConverter.pointsToHwpunits(bh));
                    } else {
                        double pw = img.getWidth(), ph = img.getHeight();
                        obj.width(CoordinateConverter.pointsToHwpunits(Math.max(pw, ph) * 72.0 / pngExportDpi));
                        obj.height(CoordinateConverter.pointsToHwpunits(Math.min(pw, ph) * 72.0 / pngExportDpi));
                    }

                    obj.sourceId("u" + Integer.toHexString(anchoredObjectId));

                    // 장식 번호 인라인(≤3자 + 큰 폰트/정사각형): 높이를 본문 줄 높이로 제한
                    // 인라인 이미지 높이가 줄간격을 벌리는 것 방지
                    ResolvedTextFrame rtf = resolvedData.getTextFrame(String.valueOf(anchoredObjectId));
                    if (rtf != null && obj.height() > 1500) { // 15pt 초과
                        // 프레임 가로/세로 비율이 정사각형에 가까우면 높이 제한
                        double[] rtfGb = rtf.geometricBounds();
                        if (rtfGb != null && rtfGb.length >= 4) {
                            double fw = rtfGb[3] - rtfGb[1];
                            double fh = rtfGb[2] - rtfGb[0];
                            if (fw > 0 && fh > 0 && fw / fh >= 0.7 && fw / fh <= 1.4) {
                                long maxH = 1200; // 12pt — 본문 줄 높이 이하
                                long scaledW = obj.width() * maxH / obj.height();
                                obj.height(maxH);
                                obj.width(scaledW);
                            }
                        }
                    }

                    return obj;
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * TextFrame이 다른 TextFrame 안에 중첩되어 있는지 확인.
     * resolved.json의 pageItems에서 parentId 체인을 추적하여 부모 중 TextFrame이 있으면 true.
     */
    private boolean isNestedInTextFrame(ResolvedTextFrame tf) {
        List<ResolvedPageItem> pageItems = resolvedData.pageItems();
        if (pageItems == null) return false;

        // 이 TextFrame의 parentId 찾기
        String parentId = null;
        for (ResolvedPageItem pi : pageItems) {
            if (tf.id().equals(String.valueOf(pi.id()))) {
                parentId = pi.parentId() != null ? String.valueOf(pi.parentId()) : null;
                break;
            }
        }

        // parentId 체인을 추적하여 TextFrame 부모 확인
        for (int depth = 0; depth < 5 && parentId != null; depth++) {
            for (ResolvedPageItem pi : pageItems) {
                if (parentId.equals(String.valueOf(pi.id()))) {
                    if ("TextFrame".equals(pi.type())) return true;
                    parentId = pi.parentId() != null ? String.valueOf(pi.parentId()) : null;
                    break;
                }
            }
        }
        return false;
    }

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
     * BulletList 스타일(스타일 이름에 • 포함)의 단락에 불릿 문자를 자동 삽입.
     * InDesign의 자동 불릿은 텍스트에 포함되지 않으므로 변환 시 명시적으로 추가.
     */
    private void insertBulletsForBulletStyles(List<ASTSection> sections) {
        int count = 0;
        for (ASTSection section : sections) {
            for (ASTBlock blk : section.blocks()) {
                if (!(blk instanceof ASTTextFrameBlock)) continue;
                ASTTextFrameBlock tfb = (ASTTextFrameBlock) blk;
                if (tfb.paragraphs() == null) continue;
                for (ASTParagraph para : tfb.paragraphs()) {
                    String styleRef = para.paragraphStyleRef();
                    if (styleRef == null || !styleRef.contains("\u2022")) continue; // • = \u2022
                    // 이미 불릿으로 시작하면 건너뜀
                    List<ASTInlineItem> items = para.items();
                    if (items != null && !items.isEmpty()) {
                        ASTInlineItem first = items.get(0);
                        if (first.itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                            String firstText = ((ASTTextRun) first).text();
                            if (firstText != null && (firstText.startsWith("\u2022") || firstText.startsWith("\u00B7")
                                    || firstText.startsWith("•") || firstText.startsWith("·"))) {
                                continue; // 이미 불릿 있음
                            }
                        }
                    }
                    // 불릿 런 삽입 (가장 긴 텍스트 런의 폰트/크기 상속)
                    ASTTextRun bulletRun = new ASTTextRun();
                    bulletRun.text("\u00B7 "); // middle dot + space
                    // 대표 런 결정: 가장 긴 텍스트 런
                    ASTTextRun bodyRun = null;
                    int bodyMaxLen = 0;
                    if (items != null) {
                        for (ASTInlineItem it : items) {
                            if (it.itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                                ASTTextRun tr = (ASTTextRun) it;
                                int len = (tr.text() != null) ? tr.text().trim().length() : 0;
                                if (len > bodyMaxLen) { bodyMaxLen = len; bodyRun = tr; }
                            }
                        }
                    }
                    if (bodyRun != null) {
                        bulletRun.fontFamily(bodyRun.fontFamily());
                        // fontSizeHwpunits가 null이면 스타일에서 가져옴
                        Integer bodyFs = bodyRun.fontSizeHwpunits();
                        if (bodyFs == null || bodyFs <= 0) {
                            // resolved에서 fontSize 확인
                            bodyFs = 1100; // 기본 11pt
                        }
                        bulletRun.fontSizeHwpunits(bodyFs);
                        bulletRun.fontStyle(bodyRun.fontStyle());
                    }
                    para.items().add(0, bulletRun);
                    count++;
                }
            }
        }
        if (count > 0) {
            System.err.println("[ResolvedToASTBuilder] Phase 4.5: " + count + " bullets inserted");
        }
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
     * renderable TF(배지: 부모에 배경색이 있는 짧은 텍스트)를 플로팅 이미지로 배치.
     * renderedTextFrames에서 type이 없는(일반 renderable) 항목의 PNG를 ASTFigure로 변환.
     */
    private void placeRenderableFrames(List<ASTSection> sections) {
        if (basePath == null) return;
        int count = 0;
        for (RenderedGroup rt : resolvedData.allRenderedTextFrames()) {
            if (rt.file() == null) continue;
            // badge_group은 이미 인라인으로 처리됨 → 건너뜀
            if (rt.isBadgeGroup()) continue;

            File pngFile = new File(basePath, rt.file());
            if (!pngFile.exists()) continue;

            int pageIdx = toSectionIndex(rt.pageIndex());
            if (pageIdx < 0 || pageIdx >= sections.size()) continue;

            try {
                byte[] imageData = java.nio.file.Files.readAllBytes(pngFile.toPath());
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(pngFile);
                if (img == null || img.getWidth() <= 2) continue;

                double[] bounds = rt.bounds();
                if (bounds == null || bounds.length < 4) continue;

                // bounds는 normalizeToPoints()에서 이미 pt 단위로 변환됨
                double bw = Math.abs(bounds[3] - bounds[1]);
                double bh = Math.abs(bounds[2] - bounds[0]);
                if (bw <= 0 || bh <= 0) continue;

                // PNG 비율 보정
                double pngRatio = (double) img.getWidth() / img.getHeight();
                double boundsRatio = bw / bh;
                if (Math.abs(pngRatio - boundsRatio) / Math.max(pngRatio, boundsRatio) > 0.1) {
                    if (pngRatio < 1.0) { bw = bh * pngRatio; } else { bh = bw / pngRatio; }
                }

                double x = bounds[1];
                double y = bounds[0];

                ASTFigure fig = new ASTFigure();
                fig.sourceId("renderable_" + rt.id());
                fig.x(CoordinateConverter.pointsToHwpunits(x));
                fig.y(CoordinateConverter.pointsToHwpunits(y));
                fig.width(CoordinateConverter.pointsToHwpunits(bw));
                fig.height(CoordinateConverter.pointsToHwpunits(bh));
                fig.imageData(imageData);
                fig.imageFormat("png");
                fig.pixelWidth(img.getWidth());
                fig.pixelHeight(img.getHeight());
                fig.zOrder(Math.max(rt.zOrder(), 10)); // 배경(0) 위에 배치
                fig.fromGroup(true); // IN_FRONT_OF_TEXT
                sections.get(pageIdx).addBlock(fig);
                count++;
            } catch (Exception e) { /* skip */ }
        }
        if (count > 0) {
            System.err.println("[ResolvedToASTBuilder] Phase 7: " + count + " renderable frames placed");
        }
    }

    private void injectPageBackgrounds(List<ASTSection> sections) {
        List<RenderedGroup> floatingItems = resolvedData.allRenderedFloatingItems();
        if (floatingItems == null || floatingItems.isEmpty()) return;

        // PDF 래스터화 캐시 (한 번만 로드)
        java.util.List<byte[]> pdfPages = null;
        String loadedPdfPath = null;

        for (RenderedGroup rg : floatingItems) {
            if (!"page_background".equals(rg.itemType())) continue;

            int pageIdx = toSectionIndex(rg.pageIndex());
            if (pageIdx < 0 || pageIdx >= sections.size()) continue;

            double[] bounds = rg.bounds();
            if (bounds == null || bounds.length < 4) continue;

            byte[] imageData = null;
            int pixelW = 0, pixelH = 0;

            // PDF 배경 래스터화 비활성화 — PNG 600dpi가 더 고품질
            if (false && rg.pdfFile() != null && rg.pdfPageIndex() >= 0) {
                try {
                    File pdfFile = new File(basePath, rg.pdfFile());
                    if (pdfFile.exists()) {
                        String pdfPath = pdfFile.getAbsolutePath();
                        if (pdfPages == null || !pdfPath.equals(loadedPdfPath)) {
                            pdfPages = kr.dogfoot.hwpxlib.tool.idmlconverter.converter
                                    .PdfPageRenderer.renderAllPages(pdfFile, 600);
                            loadedPdfPath = pdfPath;
                        }
                        int pdfIdx = rg.pdfPageIndex();
                        if (pdfIdx >= 0 && pdfIdx < pdfPages.size()) {
                            imageData = pdfPages.get(pdfIdx);
                            BufferedImage pdfImg = ImageIO.read(
                                    new java.io.ByteArrayInputStream(imageData));
                            if (pdfImg != null) {
                                pixelW = pdfImg.getWidth();
                                pixelH = pdfImg.getHeight();
                                pdfImg.flush();
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[ResolvedToASTBuilder] PDF 배경 래스터화 실패: " + e.getMessage());
                    imageData = null;
                }
            }

            // PNG 폴백
            if (imageData == null && rg.file() != null) {
                try {
                    File pngFile = new File(basePath, rg.file());
                    if (pngFile.exists()) {
                        imageData = java.nio.file.Files.readAllBytes(pngFile.toPath());
                        BufferedImage img = ImageIO.read(pngFile);
                        if (img != null) {
                            pixelW = img.getWidth();
                            pixelH = img.getHeight();
                            img.flush();
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[ResolvedToASTBuilder] PNG 배경 로드 실패: " + e.getMessage());
                    continue;
                }
            }

            if (imageData == null) continue;

            long figW = CoordinateConverter.pointsToHwpunits((bounds[3] - bounds[1]) * scaleFactor);
            long figH = CoordinateConverter.pointsToHwpunits((bounds[2] - bounds[0]) * scaleFactor);

            ASTFigure fig = new ASTFigure();
            fig.x(0);
            fig.y(0);
            fig.width(figW);
            fig.height(figH);
            fig.imageData(imageData);
            fig.imageFormat("png");
            fig.pixelWidth(pixelW);
            fig.pixelHeight(pixelH);
            fig.zOrder(0);
            fig.fromGroup(false);  // BEHIND_TEXT
            fig.sourceId("page_bg_" + pageIdx);

            sections.get(pageIdx).addBlock(fig);
        }
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
     * paragraphStyles에서 스타일 이름으로 alignment를 조회한다.
     */
    private static String resolveStyleAlignment(String styleName, ASTDocument doc) {
        if (styleName == null || doc == null) return null;
        for (ASTStyleDef sd : doc.paragraphStyles()) {
            if (styleName.equals(sd.styleName())) {
                return sd.alignment();
            }
        }
        return null;
    }

    /**
     * resolved paragraphStyles의 justification으로 ASTDocument 스타일 보강.
     * 1차: resolved.json top-level paragraphStyles (가장 정확한 소스)
     * 2차: resolved Story 단락의 개별 justification (fallback)
     * IDML Styles.xml에 이미 Justification이 설정된 스타일은 건너뜀.
     */
    private void enrichStyleAlignmentFromResolved(ASTDocument doc) {
        if (resolvedData == null) return;

        // 1차 소스: resolved.json의 top-level paragraphStyles (ResolvedDataReader에서 파싱)
        Map<String, String> topLevelJustMap = resolvedData.paragraphStyleJustMap();

        // 2차 소스: 각 Story 단락의 개별 justification (fallback)
        Map<String, String> storyParaJustMap = new HashMap<>();
        try {
            for (String sid : resolvedData.allStoryIds()) {
                ResolvedStory rs = resolvedData.getStory(sid);
                if (rs == null || rs.paragraphs() == null) continue;
                for (ResolvedParagraph rp : rs.paragraphs()) {
                    if (rp.styleName() != null && rp.justification() != null) {
                        storyParaJustMap.putIfAbsent(rp.styleName(), rp.justification());
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }

        // ASTDocument 스타일에 alignment 보강
        int enriched = 0;
        for (ASTStyleDef sd : doc.paragraphStyles()) {
            if (sd.alignment() == null && sd.styleName() != null) {
                // 1차: top-level paragraphStyles
                String just = topLevelJustMap != null ? topLevelJustMap.get(sd.styleName()) : null;
                // 2차: Story 단락 fallback
                if (just == null) {
                    just = storyParaJustMap.get(sd.styleName());
                }
                if (just != null) {
                    sd.alignment(just);
                    enriched++;
                }
            }
        }
        if (enriched > 0) {
            System.err.println("[ResolvedToASTBuilder] enrichStyleAlignment: " + enriched
                    + " styles enriched from resolved (topLevel=" + (topLevelJustMap != null ? topLevelJustMap.size() : 0) + ")");
        }
    }

    /**
     * 테이블 셀에 인라인 객체(Group, Rectangle 등)가 포함되어 있는지 확인.
     */
    /**
     * 테이블이 배경 PNG fallback 대상인지 판정.
     * 셀 텍스트가 30자 미만이면서 인라인 객체를 포함하면 → 배경 PNG로 처리.
     * (짧은 텍스트 + 인라인 배지/아이콘 = 글상자 변환 시 레이아웃 깨짐)
     */
    private static boolean hasInlineObjectsInTable(kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable table) {
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow row : table.rows()) {
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell cell : row.cells()) {
                boolean hasInline = false;
                int textLen = 0;
                for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph para : cell.paragraphs()) {
                    for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun run : para.characterRuns()) {
                        if (run.inlineGraphics() != null && !run.inlineGraphics().isEmpty()) hasInline = true;
                        if (run.inlineFrames() != null && !run.inlineFrames().isEmpty()) hasInline = true;
                        if (run.content() != null) textLen += run.content().replace("\uFFFC", "").trim().length();
                    }
                }
                if (hasInline && textLen < 30) return true;
            }
        }
        return false;
    }

    /**
     * 인라인 객체가 포함된 테이블을 rendered PNG 이미지(ASTFigure)로 변환.
     * renderedFloatingItems에서 type="table_inline"인 항목을 찾아 사용.
     */
    private ASTFigure renderTableAsImage(kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable table,
                                          ResolvedTextFrame tf, long x, long y, int pageIdx) {
        if (basePath == null || resolvedData == null) return null;

        // TextFrame의 DOM ID로 rendered PNG 찾기
        String tfDomId = tf.id();
        int domId = -1;
        try { domId = Integer.parseInt(tfDomId); } catch (NumberFormatException e) { return null; }

        // 1. 직접 파일 (table_XXXXX.png) 또는 renderedFloatingItems에서 검색
        File directFile = new File(basePath, "rendered_frames/table_" + domId + ".png");
        File pngFile = null;
        double[] rgBounds = null;

        if (directFile.exists()) {
            pngFile = directFile;
        }
        // renderedFloatingItems에서도 검색
        if (pngFile == null) {
            for (RenderedGroup rg : resolvedData.allRenderedFloatingItems()) {
                if (rg.id() == domId && rg.file() != null) {
                    File f = new File(basePath, rg.file());
                    if (f.exists()) {
                        pngFile = f;
                        rgBounds = rg.bounds();
                        break;
                    }
                }
            }
        }
        if (pngFile == null) return null;

        try {
            byte[] imageData = java.nio.file.Files.readAllBytes(pngFile.toPath());
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(pngFile);
            if (img == null) return null;

            ASTFigure fig = new ASTFigure();
            fig.sourceId("tbl_" + table.selfId());
            fig.x(x);
            fig.y(y);
            fig.zOrder(tf.zOrder());
            fig.imageData(imageData);
            fig.imageFormat("png");
            fig.pixelWidth(img.getWidth());
            fig.pixelHeight(img.getHeight());

            // 크기: resolved bounds 또는 테이블 크기
            if (rgBounds != null && rgBounds.length >= 4) {
                double bw = Math.abs(rgBounds[3] - rgBounds[1]) * scaleFactor;
                double bh = Math.abs(rgBounds[2] - rgBounds[0]) * scaleFactor;
                fig.width(CoordinateConverter.pointsToHwpunits(bw));
                fig.height(CoordinateConverter.pointsToHwpunits(bh));
            } else {
                // 테이블 행 높이 + 컬럼 너비 합산
                long tw = 0, th = 0;
                for (double cw : table.columnWidths()) tw += CoordinateConverter.pointsToHwpunits(cw);
                for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow r : table.rows())
                    th += CoordinateConverter.pointsToHwpunits(r.rowHeight());
                fig.width(tw);
                fig.height(th);
            }
            return fig;
        } catch (Exception e) {
            return null;
        }
    }
}
