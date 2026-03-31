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

        // Phase 6: 페이지 배경 PNG 주입
        injectPageBackgrounds(sections);

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

        return sections;
    }

    // ═══════════════════════════════════════════════════
    // Phase 2: TextFrame 분류 및 배치
    // ═══════════════════════════════════════════════════

    private void placeTextFrames(List<ASTSection> sections) {
        List<ResolvedTextFrame> frames = resolvedData.textFrames();

        for (ResolvedTextFrame tf : frames) {
            // 인라인 프레임은 Phase 3에서 처리
            if (tf.isInline()) continue;

            // 다른 TextFrame 안에 중첩된 프레임은 건너뜀 (부모가 배경에 포함)
            if (isNestedInTextFrame(tf)) continue;

            // 배경에 포함된 프레임은 건너뜀 (editable 프레임만 글상자로 배치)
            // 단, 스레드 체인(연결 텍스트 프레임)은 무조건 배치 — 본문 텍스트일 가능성 높음
            // 배경에 포함된 프레임은 건너뜀 (editable 프레임만 글상자로 배치)
            if (!resolvedData.isEditableTextFrame(tf.id())) continue;

            // 페이지 인덱스 결정
            int pageIdx = tf.pageIndex();
            if (pageIdx < 0 || pageIdx >= sections.size()) continue;

            // 좌표 계산: geometricBounds는 spread 좌표 (applyScale 후 pt)
            // → page bounds를 빼서 page-relative로 변환
            double[] gb = tf.geometricBounds();
            if (gb == null || gb.length < 4) continue;

            ResolvedPage rPage = (pageIdx < resolvedData.pages().size())
                    ? resolvedData.pages().get(pageIdx) : null;
            double pageLeft = (rPage != null && rPage.bounds() != null) ? rPage.bounds()[1] : 0;
            double pageTop = (rPage != null && rPage.bounds() != null) ? rPage.bounds()[0] : 0;

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

            // composedLines: Y 점프 기반 글상자 분할
            if (tf.composedLines() != null && tf.composedLines().size() > 1) {
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

            // 그룹 bounds
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
            block.sourceId(sourceIdBase + (groups.size() > 1 ? "_g" + gi : ""));
            block.x(CoordinateConverter.pointsToHwpunits(gx));
            block.y(CoordinateConverter.pointsToHwpunits(gy));
            block.width(CoordinateConverter.pointsToHwpunits(gw));
            block.height(CoordinateConverter.pointsToHwpunits(gh));
            block.zOrder(tf.zOrder());
            block.storyId(tf.storyId());
            block.distributed(true); // 분할 블록: 연결 글상자 링크 해제
            block.composedCharStart(charOffset);
            block.composedCharEnd(charOffset + groupCharCount);

            // 프레임 속성 복사
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
            if (tf.isInline()) continue;
            // editable 프레임만 처리 (non-editable은 배경 PNG에 이미 포함)
            if (!resolvedData.isEditableTextFrame(tf.id())) continue;

            // Story에 테이블이 있는지 IDML에서 확인
            String storyId = tf.storyId();
            if (storyId == null) continue;

            IDMLStory idmlStory = loadIDMLStory(storyId);
            if (idmlStory == null || !idmlStory.hasTables()) continue;

            // 페이지 결정
            int pageIdx = tf.pageIndex();
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
            Map<String, kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable> parentTableMap = new HashMap<>();
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable t1 : allTables) {
                for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable t2 : allTables) {
                    if (!t1.selfId().equals(t2.selfId()) && t1.selfId().startsWith(t2.selfId())) {
                        parentTableMap.put(t1.selfId(), t2); // t1은 t2의 중첩 테이블
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
                // leading: IDML ParagraphStyle 우선, CharacterRun, resolved 순
                // 단, auto leading(>50pt = percentage 값)은 무시
                Double fixedLeading = getStyleLeading(ip.appliedParagraphStyle());
                if (fixedLeading != null && fixedLeading > 50) fixedLeading = null; // auto leading percentage 무시
                if (fixedLeading == null || fixedLeading <= 0) {
                    fixedLeading = ip.leading(); // IDML CharacterRun leading
                    if (fixedLeading != null && fixedLeading > 50) fixedLeading = null;
                }
                if (fixedLeading == null || fixedLeading <= 0) {
                    fixedLeading = rp.fixedLeading(); // resolved fallback
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
            String styleFillColor = getStyleFillColor(ip.appliedParagraphStyle());
            Double styleTracking = getStyleTracking(ip.appliedParagraphStyle());
            String styleFontFamily = getStyleFontFamily(ip.appliedParagraphStyle());
            Double styleFontSize = getStyleFontSize(ip.appliedParagraphStyle());

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
                        String[] parts = text.split("\uFFFC", -1);
                        List<String> inlineIds = new ArrayList<>();
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
                        int anchorIdx = 0;
                        for (int pi = 0; pi < parts.length; pi++) {
                            // 인라인 앵커 직전 텍스트의 후행 공백 제거 (위치 조정용 공백)
                            String partText = parts[pi];
                            if (pi < parts.length - 1 && partText.endsWith("  ")) {
                                partText = partText.replaceAll("\\s+$", " "); // 후행 다중 공백 → 단일 공백
                            }
                            if (!partText.isEmpty()) {
                                ResolvedRun matchedRR = findResolvedRun(resolvedRuns, resolvedRunIdx, partText);
                                ASTTextRun tr = createRunFromIDML(run, partText, matchedRR != null ? matchedRR : defaultRR, styleFillColor, styleTracking, styleFontFamily, styleFontSize);
                                if (!splitBulletRun(tr, para)) {
                                    splitLatinVarsInMixedText(tr, para);
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
                        ResolvedRun matchedRR2 = findResolvedRun(resolvedRuns, resolvedRunIdx, text);
                        ASTTextRun tr = createRunFromIDML(run, text, matchedRR2 != null ? matchedRR2 : defaultRR, styleFillColor, styleTracking, styleFontFamily, styleFontSize);
                        // ;...; 분수 GREP 패턴이 포함된 텍스트 → 분수 수식으로 분리
                        if (!splitBulletRun(tr, para)) {
                            if (EHFontGlyphMap.containsEHFractionPattern(text)) {
                                splitFractionPatternInText(text, tr, para);
                            } else {
                                // 한국어 사이 단일 라틴 문자를 수식 변수로 분리
                                splitLatinVarsInMixedText(tr, para);
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

            // 인라인 객체 boundsX 기반 재정렬 (테이블 셀뿐 아니라 일반 TextFrame에서도)
            ASTTableConverter.reorderInlineObjectsByBoundsX(para);

            paragraphs.add(para);
        }

        return paragraphs;
    }

    private ASTTextRun createRunFromIDML(IDMLCharacterRun cr, String text, ResolvedRun rr, String styleFillColor, Double styleTracking, String styleFontFamily, Double styleFontSize) {
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
            text = text.replace("\u2009", "");   // Thin Space 제거
            text = text.replace("\u2002", "");   // En Space 제거
            text = text.replace("\u2003", "");   // Em Space 제거
            text = text.replace("\u200A", "");   // Hair Space 제거
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
        // InDesign Tracking → HWPX 자간
        // 한컴돋움/한컴바탕 fallback 폰트 매핑 시: tracking 값 그대로 (e.g., -15 → -15%)
        // 명시적 매핑 폰트: tracking / 10 (e.g., -30 → -3%)
        // 한국어 폰트(한글 포함 이름)는 대부분 한컴돋움 fallback → 그대로 사용
        {
            Double trackingVal = (cr.tracking() != null && cr.tracking() != 0)
                    ? cr.tracking() : styleTracking;
            if (trackingVal != null && trackingVal != 0) {
                String fn = (fontFamily != null) ? fontFamily : (rr != null ? rr.fontFamily() : null);
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
            if (tr.fontFamily() == null && rr.fontFamily() != null) {
                // EH/BT 수식 폰트는 수식 기호가 있는 텍스트에만 적용
                boolean isEHorBT = EHFontGlyphMap.isEHFontFamily(rr.fontFamily())
                        || (rr.fontFamily() != null && rr.fontFamily().contains("BT수식"));
                boolean isSingleLatin = text != null && text.trim().length() == 1
                        && Character.isLetter(text.trim().charAt(0));
                if (!isEHorBT || isSingleLatin) {
                    tr.fontFamily(rr.fontFamily());
                }
            }
            if (tr.fontStyle() == null && rr.fontStyle() != null) {
                boolean isEHorBT = rr.fontFamily() != null
                        && (EHFontGlyphMap.isEHFontFamily(rr.fontFamily()) || rr.fontFamily().contains("BT수식"));
                boolean isSingleLatin = text != null && text.trim().length() == 1
                        && Character.isLetter(text.trim().charAt(0));
                if (!isEHorBT || isSingleLatin) {
                    tr.fontStyle(rr.fontStyle());
                }
            }
            if (tr.fontSizeHwpunits() == null && rr.fontSize() != null && rr.fontSize() > 0) {
                tr.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(rr.fontSize()));
            }
            // FillColor: ParagraphStyle 우선, resolved fallback
            if (tr.textColor() == null && styleFillColor != null) {
                tr.textColor(styleFillColor);
            }
            if (tr.textColor() == null && rr.fillColor() != null) tr.textColor(resolveColorToHex(rr.fillColor()));
            // underline / strikeThrough
            if (rr.underline() != null && rr.underline()) {
                tr.underline(true);
            }
            if (rr.strikeThru() != null && rr.strikeThru()) {
                tr.strikeThrough(true);
            }
        }
        // ParagraphStyle 폴백 (IDML 런과 resolved 런 모두 속성이 없을 때)
        if (tr.fontFamily() == null && styleFontFamily != null) {
            tr.fontFamily(styleFontFamily);
        }
        if (tr.fontSizeHwpunits() == null && styleFontSize != null && styleFontSize > 0) {
            tr.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(styleFontSize));
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
    private ResolvedRun findResolvedRun(List<ResolvedRun> runs, int startIdx, String text) {
        if (runs == null || runs.isEmpty() || text == null || text.isEmpty()) return null;
        String key = text.length() > 5 ? text.substring(0, 5) : text;
        // startIdx부터 순차 검색
        for (int i = startIdx; i < runs.size(); i++) {
            String rt = runs.get(i).text();
            if (rt != null && rt.contains(key)) return runs.get(i);
        }
        // 못 찾으면 처음부터
        for (int i = 0; i < Math.min(startIdx, runs.size()); i++) {
            String rt = runs.get(i).text();
            if (rt != null && rt.contains(key)) return runs.get(i);
        }
        // 그래도 못 찾으면 첫 번째 런 반환 (스타일 상속용)
        return runs.isEmpty() ? null : runs.get(0);
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
                        textRun.horizontalScale((short) run.horizontalScale().doubleValue());
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

        // 단락의 중심 위치가 블록 범위 안에 있으면 할당 (중복 방지)
        Set<Integer> assigned = new HashSet<>();
        for (ASTTextFrameBlock block : blocks) {
            int blockStart = block.composedCharStart();
            int blockEnd = block.composedCharEnd();
            if (blockStart < 0) continue;

            for (int i = 0; i < paragraphs.size(); i++) {
                if (assigned.contains(i)) continue;
                int paraStart = paraRanges.get(i)[0];
                int paraEnd = paraRanges.get(i)[1];
                int paraMid = (paraStart + paraEnd) / 2;
                // 단락 중심이 블록 범위 안이면 할당
                if (paraMid >= blockStart && paraMid < blockEnd) {
                    block.addParagraph(paragraphs.get(i));
                    assigned.add(i);
                }
            }
        }

        // 미할당 단락은 마지막 블록에 추가
        ASTTextFrameBlock lastBlock = blocks.get(blocks.size() - 1);
        for (int i = 0; i < paragraphs.size(); i++) {
            if (!assigned.contains(i)) {
                lastBlock.addParagraph(paragraphs.get(i));
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

        // 단일 프레임: 모든 단락을 그대로 할당 (분배/트리밍 불필요)
        if (blocks.size() == 1) {
            ASTTextFrameBlock block = blocks.get(0);
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
            String domId = block.sourceId().startsWith("u")
                    ? String.valueOf(Integer.parseInt(block.sourceId().substring(1), 16))
                    : block.sourceId();
            ResolvedTextFrame rtf = resolvedData.getTextFrame(domId);
            // frameVisibleText 사용 (정확한 프레임 보이는 텍스트)
            String visibleText = (rtf != null) ? rtf.frameVisibleText() : null;
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
            String domId = block.sourceId().startsWith("u")
                    ? String.valueOf(Integer.parseInt(block.sourceId().substring(1), 16))
                    : block.sourceId();
            ResolvedTextFrame rtf = resolvedData.getTextFrame(domId);
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
            String domId = b.sourceId().startsWith("u")
                    ? String.valueOf(Integer.parseInt(b.sourceId().substring(1), 16))
                    : b.sourceId();
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
        if (anchoredTf == null) return false;
        double[] aGb = anchoredTf.geometricBounds();
        if (aGb == null || aGb.length < 4) return false;
        for (ResolvedTextFrame tf : resolvedData.textFrames()) {
            if (parentStoryId.equals(tf.storyId()) && !tf.isInline()) {
                double[] pGb = tf.geometricBounds();
                if (pGb == null || pGb.length < 4) continue;
                double aCenterX = (aGb[1] + aGb[3]) / 2.0;
                return aCenterX > pGb[3] + 5.0 || aCenterX < pGb[1] - 5.0;
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
        if (aGb == null || aGb.length < 4) {
            for (RenderedGroup rg : resolvedData.allRenderedFloatingItems()) {
                if (rg.id() == anchoredId && "inline_object".equals(rg.itemType())) {
                    aGb = rg.bounds();
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
                // 인라인 객체의 중심 X가 부모 프레임 X범위에서 5mm 이상 벗어나면 커스텀 위치 앵커
                double aCenterX = (aGb[1] + aGb[3]) / 2.0;
                boolean outside = aCenterX > pGb[3] + 5.0 || aCenterX < pGb[1] - 5.0;
                return outside;
            }
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
                        // bounds 비율과 PNG 비율이 크게 다르면 PNG 비율 기준으로 보정
                        if (Math.abs(pngRatio - boundsRatio) / Math.max(pngRatio, boundsRatio) > 0.3) {
                            bh = bw / pngRatio;
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

    private void placeTables(List<ASTSection> sections) {
        // TODO: ResolvedStory에 tables 지원이 추가되면 구현
        // 현재 ResolvedStory는 paragraphs만 보유하고 tables 접근자가 없음
    }

    private int findPageForStory(String storyId) {
        // storyId → textFrame → pageIndex
        for (ResolvedTextFrame tf : resolvedData.textFrames()) {
            if (storyId.equals(tf.storyId())) {
                return tf.pageIndex();
            }
        }
        return -1;
    }

    // ═══════════════════════════════════════════════════
    // Phase 5: Figure/Image 배치 (향후 구현)
    // ═══════════════════════════════════════════════════

    // ExtendScript에서 렌더한 개별 이미지를 ASTFigure로 배치
    // 현재는 Phase 6 (페이지 배경)으로 대체

    // ═══════════════════════════════════════════════════
    // Phase 6: 페이지 배경 PNG 주입
    // ═══════════════════════════════════════════════════

    private void injectPageBackgrounds(List<ASTSection> sections) {
        List<RenderedGroup> floatingItems = resolvedData.allRenderedFloatingItems();
        if (floatingItems == null || floatingItems.isEmpty()) return;

        // PDF 래스터화 캐시 (한 번만 로드)
        java.util.List<byte[]> pdfPages = null;
        String loadedPdfPath = null;

        for (RenderedGroup rg : floatingItems) {
            if (!"page_background".equals(rg.itemType())) continue;

            int pageIdx = rg.pageIndex();
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
}
