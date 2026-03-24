package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.FontMapper;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStoryParser;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.*;

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
    private final Map<String, IDMLStory> idmlStoryCache = new HashMap<>();

    public ResolvedToASTBuilder(ResolvedData resolvedData) {
        this(resolvedData, null);
    }

    public ResolvedToASTBuilder(ResolvedData resolvedData, File idmlDir) {
        this.resolvedData = resolvedData;
        this.basePath = resolvedData.basePath();
        this.scaleFactor = resolvedData.scaleFactor();
        this.idmlDir = idmlDir;
    }

    /**
     * resolved.json → ASTDocument 변환.
     */
    public ASTDocument build() {
        ASTDocument doc = new ASTDocument();
        doc.sourceFormat("Resolved");

        // Phase 1: 페이지/섹션 빌드
        List<ASTSection> sections = buildSections();
        for (ASTSection sec : sections) {
            doc.addSection(sec);
        }

        // Phase 2: TextFrame 분류 및 배치
        placeTextFrames(sections);

        // Phase 3: Story→단락→런 변환
        convertStories(sections);

        // Phase 4: 테이블 배치 (TODO: API 매칭 후 구현)
        // placeTables(sections);

        // Phase 6: 페이지 배경 PNG 주입
        injectPageBackgrounds(sections);

        System.out.println("[ResolvedToASTBuilder] Built " + sections.size() + " sections");
        return doc;
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

            // 배경에 포함된 프레임은 건너뜀 (editable 프레임만 글상자로 배치)
            // 단, 스레드 체인(연결 텍스트 프레임)은 무조건 배치 — 본문 텍스트일 가능성 높음
            boolean isThreaded = tf.previousFrameId() != null || tf.nextFrameId() != null;
            if (!isThreaded && !resolvedData.isEditableTextFrame(tf.id())) continue;

            // 페이지 인덱스 결정
            int pageIdx = tf.pageIndex();
            if (pageIdx < 0 || pageIdx >= sections.size()) continue;

            ASTSection section = sections.get(pageIdx);

            // 좌표 계산: geometricBounds는 spread 좌표 (applyScale 후 pt)
            // → page bounds를 빼서 page-relative로 변환
            double[] gb = tf.geometricBounds();
            if (gb == null || gb.length < 4) continue;

            ResolvedPage rPage = (pageIdx < resolvedData.pages().size())
                    ? resolvedData.pages().get(pageIdx) : null;
            double pageLeft = (rPage != null && rPage.bounds() != null) ? rPage.bounds()[1] : 0;
            double pageTop = (rPage != null && rPage.bounds() != null) ? rPage.bounds()[0] : 0;
            double x = gb[1] - pageLeft;
            double y = gb[0] - pageTop;
            double w = gb[3] - gb[1];
            double h = gb[2] - gb[0];
            // 음수 좌표 클램핑
            if (x < 0) { w += x; x = 0; }
            if (y < 0) { h += y; y = 0; }
            if (w <= 0 || h <= 0) continue;

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

            // 시각 속성
            if (tf.fillColor() != null && !"None".equals(tf.fillColor())) {
                // TODO: 색상 이름 → hex 변환 (ColorResolver 필요)
            }
            if (tf.strokeColor() != null && !"None".equals(tf.strokeColor())) {
                // TODO: 스트로크
            }

            section.addBlock(block);
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
                    String sourceId = tfb.sourceId();
                    if (sourceId == null) continue;
                    // sourceId → DOM decimal → textFrame → storyId
                    String domId = sourceId.startsWith("u")
                            ? String.valueOf(Integer.parseInt(sourceId.substring(1), 16))
                            : sourceId;
                    ResolvedTextFrame rtf = resolvedData.getTextFrame(domId);
                    if (rtf != null && rtf.storyId() != null) {
                        storyToBlocks.computeIfAbsent(rtf.storyId(), k -> new ArrayList<>()).add(tfb);
                    }
                }
            }
        }

        System.out.println("[ResolvedToASTBuilder] Phase 3: " + storyToBlocks.size() + " stories matched to TextFrameBlocks");

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
            if (paragraphs != null && !paragraphs.isEmpty()) {
                idmlCount++;
            } else {
                // 2차: resolved.json fallback
                ResolvedStory story = resolvedData.getStory(storyId);
                if (story == null) continue;
                paragraphs = convertStoryParagraphs(story);
                resolvedCount++;
            }
            totalParas += paragraphs.size();

            // 단락 분배: paragraphStart/End에 따라 각 TextFrameBlock에 할당
            distributeParagraphs(paragraphs, blocks, storyId);
        }
        System.out.println("[ResolvedToASTBuilder] Phase 3: " + totalParas + " paragraphs converted (IDML=" + idmlCount + " resolved=" + resolvedCount + ")");
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

            // 단락 스타일 (IDML)
            if (ip.appliedParagraphStyle() != null) {
                para.paragraphStyleRef(ip.appliedParagraphStyle());
            }

            // 단락 속성: resolved에서 가져옴 (정확한 pt 값)
            if (resolvedStory != null && i < resolvedStory.paragraphs().size()) {
                ResolvedParagraph rp = resolvedStory.paragraphs().get(i);
                if (rp.justification() != null) para.alignment(rp.justification());
                Double fixedLeading = rp.fixedLeading();
                if (fixedLeading != null && fixedLeading > 0) {
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
            }

            // 런 변환: IDML CharacterRun → ASTTextRun
            for (IDMLCharacterRun cr : ip.characterRuns()) {
                String text = cr.content();
                if (text == null || text.isEmpty()) continue;

                // U+FFFC (인라인 객체 마커) 처리
                if (text.contains("\uFFFC")) {
                    String[] parts = text.split("\uFFFC", -1);
                    // IDML에서 인라인 프레임/그래픽 ID 수집
                    List<String> inlineIds = new ArrayList<>();
                    if (cr.inlineFrames() != null) {
                        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTextFrame itf : cr.inlineFrames()) {
                            inlineIds.add(itf.selfId());
                        }
                    }
                    if (cr.inlineGraphics() != null) {
                        for (IDMLCharacterRun.InlineGraphic ig : cr.inlineGraphics()) {
                            inlineIds.add(ig.selfId());
                        }
                    }
                    int anchorIdx = 0;
                    for (int pi = 0; pi < parts.length; pi++) {
                        if (!parts[pi].isEmpty()) {
                            ASTTextRun tr = createRunFromIDML(cr, parts[pi]);
                            para.addItem(tr);
                        }
                        // U+FFFC 위치에 인라인 객체 삽입
                        if (pi < parts.length - 1 && anchorIdx < inlineIds.size()) {
                            String inlineHexId = inlineIds.get(anchorIdx);
                            try {
                                int domId = Integer.parseInt(inlineHexId.substring(1), 16);
                                ASTInlineObject inlineObj = loadInlineObject(domId);
                                if (inlineObj != null) {
                                    para.addItem(inlineObj);
                                }
                            } catch (Exception e) { /* skip */ }
                            anchorIdx++;
                        }
                    }
                } else {
                    ASTTextRun tr = createRunFromIDML(cr, text);
                    para.addItem(tr);
                }
            }

            paragraphs.add(para);
        }

        return paragraphs;
    }

    private ASTTextRun createRunFromIDML(IDMLCharacterRun cr, String text) {
        ASTTextRun tr = new ASTTextRun();
        tr.text(text);
        if (cr.fontFamily() != null) tr.fontFamily(cr.fontFamily());
        if (cr.fontStyle() != null) tr.fontStyle(cr.fontStyle());
        if (cr.fontSize() != null && cr.fontSize() > 0) {
            tr.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(cr.fontSize()));
        }
        if (cr.fillColor() != null) tr.textColor(cr.fillColor());
        // tracking은 ASTParagraph 수준 또는 CharPr에서 처리
        return tr;
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
            if (rp.justification() != null) para.alignment(rp.justification());
            Double fixedLeading = rp.fixedLeading();
            if (fixedLeading != null && fixedLeading > 0) {
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
                            ASTInlineObject inlineObj = loadInlineObject(anchoredId);
                            if (inlineObj != null) {
                                para.addItem(inlineObj);
                                continue;
                            }
                        }
                        continue; // 로드 실패 시 건너뜀
                    }

                    ASTTextRun textRun = new ASTTextRun();
                    textRun.text(run.text());
                    textRun.fontFamily(run.fontFamily());
                    if (run.fontSize() != null && run.fontSize() > 0) {
                        textRun.fontSizeHwpunits((int) Math.round(run.fontSize() * 100));
                    }
                    textRun.fontStyle(run.fontStyle());
                    textRun.textColor(run.fillColor());
                    if (run.tracking() != null && run.tracking() != 0) {
                        textRun.letterSpacing((short) run.tracking().doubleValue());
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

            paragraphs.add(para);
        }

        return paragraphs;
    }

    /**
     * 텍스트 기반 단락 분배: frameParaTexts로 IDML 단락을 각 프레임에 할당.
     * paragraphStart/End 인덱스 대신, 텍스트 내용을 순차 매칭하여 프레임 간 단락 분할을 정확히 처리.
     */
    private void distributeParagraphs(List<ASTParagraph> paragraphs,
                                       List<ASTTextFrameBlock> blocks, String storyId) {
        if (blocks.size() == 1) {
            for (ASTParagraph p : paragraphs) {
                blocks.get(0).addParagraph(p);
            }
            return;
        }

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
            if (ordered.size() > 1) {
                System.out.println("[FRANGE] " + storyId + " TF=" + domId + " range=[" + foundStart + "," + frameRanges[fi][1] + "] visLen=" + (visibleText != null ? visibleText.length() : 0));
            }
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

                    ASTInlineObject obj = new ASTInlineObject();
                    obj.kind(ASTInlineObject.ObjectKind.IMAGE);
                    obj.imageData(imageData);
                    obj.imageFormat("png");
                    obj.pixelWidth(img.getWidth());
                    obj.pixelHeight(img.getHeight());

                    // 크기: bounds에서 계산 (mm → pt → HWPUNIT)
                    double[] bounds = rg.bounds();
                    if (bounds != null && bounds.length >= 4) {
                        double w = (bounds[3] - bounds[1]) * scaleFactor;
                        double h = (bounds[2] - bounds[0]) * scaleFactor;
                        obj.width(CoordinateConverter.pointsToHwpunits(w));
                        obj.height(CoordinateConverter.pointsToHwpunits(h));
                    } else {
                        // 폴백: 300dpi 기준 pt 계산
                        obj.width(CoordinateConverter.pointsToHwpunits(img.getWidth() * 72.0 / 300));
                        obj.height(CoordinateConverter.pointsToHwpunits(img.getHeight() * 72.0 / 300));
                    }

                    obj.sourceId("u" + Integer.toHexString(anchoredObjectId));
                    return obj;
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
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
}
