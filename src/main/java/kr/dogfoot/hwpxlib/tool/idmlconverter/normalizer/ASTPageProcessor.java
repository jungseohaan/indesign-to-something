package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 페이지별 AST 섹션 빌드 로직.
 * Stage4_BuildAST에서 분리됨 — 텍스트 프레임, 이미지, 벡터, 마스터 콘텐츠, 푸터 처리.
 */
class ASTPageProcessor {

    /**
     * 단일 페이지를 처리하여 ASTSection을 생성.
     *
     * @param processedStories 이미 처리된 스토리 ID 집합 (페이지 간 공유, 변경됨)
     * @param doc              ASTDocument (스토리 메타데이터 추가용)
     */
    static ASTSection processPage(IDMLSpread spread, IDMLPage page,
                                   FlattenedObjectPool pool, IDMLDocument idmlDoc,
                                   ColorResolver colorResolver, ASTImageLoader imageLoader,
                                   ResolvedData resolvedData,
                                   Set<String> processedStories, ASTDocument doc) {
        ASTSection section = new ASTSection();
        section.pageNumber(page.pageNumber());

        // resolved page 조회 (name 기반)
        ResolvedPage resolvedPage = (resolvedData != null)
                ? resolvedData.getPageByName(String.valueOf(page.pageNumber())) : null;

        // 페이지 레이아웃
        ASTPageLayout layout = new ASTPageLayout();
        layout.pageWidth(page.widthHwpunits());
        layout.pageHeight(page.heightHwpunits());
        layout.marginTop(CoordinateConverter.pointsToHwpunits(page.marginTop()));
        layout.marginBottom(CoordinateConverter.pointsToHwpunits(page.marginBottom()));
        layout.marginLeft(CoordinateConverter.pointsToHwpunits(page.marginLeft()));
        layout.marginRight(CoordinateConverter.pointsToHwpunits(page.marginRight()));
        layout.columnCount(page.columnCount());
        layout.columnGutter(CoordinateConverter.pointsToHwpunits(page.columnGutter()));
        section.layout(layout);

        // 텍스트 프레임 처리
        processTextFrames(spread, page, pool, idmlDoc, colorResolver, imageLoader,
                resolvedData, resolvedPage, processedStories, doc, section);

        // 이미지 프레임 처리
        processImageFrames(spread, page, imageLoader, colorResolver,
                resolvedData, resolvedPage, section);

        // 벡터 도형 처리
        processVectorShapes(spread, page, imageLoader,
                colorResolver, resolvedData, resolvedPage, section);

        // 여백 가이드라인
        if (imageLoader != null) {
            ASTFigure marginGuide = ASTTextWrapSimulator.createMarginGuideFigure(page);
            if (marginGuide != null) {
                section.addBlock(marginGuide);
            }
        }

        // 마스터 페이지 콘텐츠
        processMasterContent(page, idmlDoc, imageLoader, colorResolver, section);

        // 마스터 페이지 푸터
        processMasterFooter(page, idmlDoc, colorResolver, section);

        // 디버그 출력
        int textBlocks = 0, figureBlocks = 0, tableBlocks = 0;
        for (ASTBlock b : section.blocks()) {
            if (b instanceof ASTTextFrameBlock) textBlocks++;
            else if (b instanceof ASTFigure) figureBlocks++;
            else if (b instanceof ASTTable) tableBlocks++;
        }
        System.err.println("[Stage4] Page " + page.pageNumber()
                + ": textFrames=" + textBlocks + " figures=" + figureBlocks
                + " tables=" + tableBlocks
                + " imgFrames=" + (imageLoader != null ? spread.getImageFramesOnPage(page).size() : 0)
                + " vectors=" + (imageLoader != null ? spread.getVectorShapesOnPage(page).size() : 0));

        // Cross-frame textwrap 시뮬레이션
        ASTTextWrapSimulator.simulateTextWrap(section);

        return section;
    }

    // ── 스프레드 단위 처리 ──────────────────────────────────────

    /**
     * 스프레드 전체를 하나의 ASTSection으로 처리.
     * 각 페이지를 독립 처리한 뒤, 두 번째 페이지부터 X 오프셋을 적용하여 병합한다.
     */
    static ASTSection processSpread(IDMLSpread spread,
                                     FlattenedObjectPool pool, IDMLDocument idmlDoc,
                                     ColorResolver colorResolver, ASTImageLoader imageLoader,
                                     ResolvedData resolvedData,
                                     Set<String> processedStories, ASTDocument doc) {
        List<IDMLPage> pages = spread.pages();
        if (pages.isEmpty()) {
            return new ASTSection();
        }

        // 스프레드 전체 크기 계산 (모든 페이지의 geometricBounds union)
        double minLeft = Double.MAX_VALUE, minTop = Double.MAX_VALUE;
        double maxRight = -Double.MAX_VALUE, maxBottom = -Double.MAX_VALUE;
        for (IDMLPage p : pages) {
            double[] gb = p.geometricBounds();
            if (gb == null || gb.length < 4) continue;
            double[] t = p.itemTransform();
            // 페이지 좌상단/우하단 (스프레드 좌표)
            double left = (t != null && t.length >= 6) ? t[4] + gb[1] : gb[1];
            double top = (t != null && t.length >= 6) ? t[5] + gb[0] : gb[0];
            double right = left + (gb[3] - gb[1]);
            double bottom = top + (gb[2] - gb[0]);
            if (left < minLeft) minLeft = left;
            if (top < minTop) minTop = top;
            if (right > maxRight) maxRight = right;
            if (bottom > maxBottom) maxBottom = bottom;
        }

        double spreadWidth = maxRight - minLeft;
        double spreadHeight = maxBottom - minTop;

        // 첫 페이지 기준으로 섹션 처리 후 레이아웃 덮어쓰기
        // 각 페이지를 processPage로 처리하고 블록들의 X좌표에 오프셋 적용
        ASTSection mergedSection = new ASTSection();
        mergedSection.pageNumber(pages.get(0).pageNumber());

        // 스프레드 전체 레이아웃 (여백 없음)
        ASTPageLayout layout = new ASTPageLayout();
        layout.pageWidth(CoordinateConverter.pointsToHwpunits(spreadWidth));
        layout.pageHeight(CoordinateConverter.pointsToHwpunits(spreadHeight));
        layout.marginTop(0);
        layout.marginBottom(0);
        layout.marginLeft(0);
        layout.marginRight(0);
        layout.columnCount(1);
        layout.columnGutter(0);
        mergedSection.layout(layout);

        for (IDMLPage page : pages) {
            // 페이지의 스프레드 내 X 오프셋 계산
            double[] gb = page.geometricBounds();
            double[] t = page.itemTransform();
            double pageLeft = (t != null && t.length >= 6) ? t[4] + gb[1] : gb[1];
            long xOffset = CoordinateConverter.pointsToHwpunits(pageLeft - minLeft);

            // 페이지 단위로 처리
            ASTSection pageSection = processPage(
                    spread, page, pool, idmlDoc, colorResolver, imageLoader,
                    resolvedData, processedStories, doc);

            // 블록들의 X 좌표에 오프셋 적용하여 병합
            for (ASTBlock block : pageSection.blocks()) {
                if (block instanceof ASTTextFrameBlock) {
                    ASTTextFrameBlock tfb = (ASTTextFrameBlock) block;
                    tfb.x(tfb.x() + xOffset);
                } else if (block instanceof ASTFigure) {
                    ASTFigure fig = (ASTFigure) block;
                    fig.x(fig.x() + xOffset);
                } else if (block instanceof ASTTable) {
                    ASTTable tbl = (ASTTable) block;
                    tbl.x(tbl.x() + xOffset);
                }
                mergedSection.addBlock(block);
            }
        }

        System.err.println("[Stage4] Spread pages=" + pages.size()
                + " width=" + Math.round(spreadWidth) + "pt"
                + " height=" + Math.round(spreadHeight) + "pt"
                + " blocks=" + mergedSection.blocks().size());

        return mergedSection;
    }

    // ── 텍스트 프레임 ──────────────────────────────────────

    private static void processTextFrames(IDMLSpread spread, IDMLPage page,
                                           FlattenedObjectPool pool, IDMLDocument idmlDoc,
                                           ColorResolver colorResolver, ASTImageLoader imageLoader,
                                           ResolvedData resolvedData, ResolvedPage resolvedPage,
                                           Set<String> processedStories, ASTDocument doc,
                                           ASTSection section) {
        List<FlatObject> textFrames = pool.getTextFramesOnPage(page.pageNumber());
        sortByPosition(textFrames, page);

        for (FlatObject fo : textFrames) {
            IDMLTextFrame tf = (IDMLTextFrame) fo.sourceObject();
            if (tf.isEditorialNote()) continue;

            String storyId = tf.parentStoryId();
            if (storyId == null) continue;

            // 연결 프레임 체인에서 첫 프레임이 아닌 경우 → 빈 블록만 생성
            String prevFrame = tf.previousTextFrame();
            boolean isLinkedContinuation = prevFrame != null && !prevFrame.isEmpty()
                    && !"n".equals(prevFrame) && !"null".equalsIgnoreCase(prevFrame);
            if (isLinkedContinuation) {
                ASTTextFrameBlock emptyBlock = createTextFrameBlock(tf, page, fo.zOrder(), colorResolver,
                        resolvedData, resolvedPage);
                if (emptyBlock != null) {
                    emptyBlock.storyId(storyId);
                    section.addBlock(emptyBlock);
                }
                continue;
            }

            IDMLStory story = idmlDoc.getStory(storyId);
            boolean hasFill = tf.fillColor() != null
                    && !tf.fillColor().contains("None")
                    && !tf.fillColor().contains("Paper");

            // 이미 처리된 스토리
            if (processedStories.contains(storyId)) {
                if (hasFill && (story == null || story.isEmpty())) {
                    ASTTextFrameBlock bgBlock = createTextFrameBlock(tf, page, fo.zOrder(), colorResolver,
                            resolvedData, resolvedPage);
                    if (bgBlock != null) {
                        bgBlock.storyId(storyId);
                        section.addBlock(bgBlock);
                    }
                }
                continue;
            }
            processedStories.add(storyId);

            if (story == null || (story.isEmpty() && !hasFill)) continue;

            // Story 메타데이터
            ASTStory astStory = new ASTStory();
            astStory.storyId(storyId);
            astStory.orientation(story.storyOrientation());
            astStory.paragraphCount(story.paragraphs().size());
            astStory.tableCount(story.tables().size());

            List<String> frameChain = new ArrayList<>();
            frameChain.add(tf.selfId());
            collectLinkedFrames(tf, pool, frameChain);
            astStory.linkedFrameIds(frameChain);

            List<Integer> storyPages = new ArrayList<>();
            storyPages.add(page.pageNumber());
            collectLinkedFramePages(frameChain, pool, storyPages);
            astStory.pages(storyPages);

            doc.addStory(astStory);

            // 텍스트 프레임 블록 생성
            ASTTextFrameBlock block = createTextFrameBlock(tf, page, fo.zOrder(), colorResolver,
                    resolvedData, resolvedPage);
            if (block == null) continue;
            block.storyId(storyId);

            // 같은 그룹의 형제 도형과 겹침 조정
            if (fo.fromGroup() && fo.parentGroupId() != null) {
                adjustGroupSiblingOverlap(block, fo, pool);
            }

            // 스토리 → 단락 변환
            convertStoryToParagraphs(story, block, pool, tf, page, fo.zOrder(),
                    idmlDoc, colorResolver, imageLoader, resolvedData);

            // 인라인 처리되지 않은 테이블
            convertStoryTables(story, section, tf, page, fo.zOrder(), idmlDoc, colorResolver, imageLoader);

            if (hasContent(block)) {
                section.addBlock(block);
            }
        }
    }

    // ── 그룹 형제 겹침 조정 ─────────────────────────────────

    /**
     * 같은 그룹 내 형제 벡터 도형과 겹치는 텍스트 프레임의 폭/위치를 조정한다.
     * InDesign에서는 투명 텍스트 프레임이 다른 요소와 겹쳐도 시각적 문제가 없지만,
     * HWPX에서는 글상자 경계가 보이므로 겹침을 해소해야 한다.
     */
    private static void adjustGroupSiblingOverlap(ASTTextFrameBlock block, FlatObject fo,
                                                   FlattenedObjectPool pool) {
        double[] tfBbox = fo.absoluteBbox();
        if (tfBbox == null) return;

        double tfW = tfBbox[2] - tfBbox[0];
        if (tfW <= 0) return;

        String groupId = fo.parentGroupId();

        for (FlatObject sibling : pool.all()) {
            if (sibling == fo) continue;
            if (sibling.pageNumber() != fo.pageNumber()) continue;
            if (!groupId.equals(sibling.parentGroupId())) continue;
            if (sibling.contentType() != FlatObject.ContentType.VECTOR_SHAPE) continue;

            // 채우기가 있는 가시적 도형만
            Object srcObj = sibling.sourceObject();
            if (!(srcObj instanceof IDMLVectorShape)) continue;
            IDMLVectorShape vs = (IDMLVectorShape) srcObj;
            String fill = vs.fillColor();
            if (fill == null || fill.contains("None") || fill.contains("Paper")) continue;

            double[] sBbox = sibling.absoluteBbox();
            if (sBbox == null) continue;

            double sW = sBbox[2] - sBbox[0];
            // 형제 도형이 텍스트 프레임보다 크면 배경 → 건너뛰기
            if (sW >= tfW * 0.7) continue;

            // X, Y 겹침 확인
            double xOverlap = Math.min(tfBbox[2], sBbox[2]) - Math.max(tfBbox[0], sBbox[0]);
            if (xOverlap <= 0) continue;
            double yOverlap = Math.min(tfBbox[3], sBbox[3]) - Math.max(tfBbox[1], sBbox[1]);
            if (yOverlap <= 0) continue;

            // 겹침이 텍스트 프레임 폭의 30% 이하일 때만 조정
            if (xOverlap / tfW > 0.3) continue;

            long trimHwp = CoordinateConverter.pointsToHwpunits(xOverlap);

            double tfCenterX = (tfBbox[0] + tfBbox[2]) / 2;
            double sCenterX = (sBbox[0] + sBbox[2]) / 2;

            if (sCenterX < tfCenterX) {
                // 도형이 왼쪽 → 텍스트 프레임 왼쪽 트리밍
                block.x(block.x() + trimHwp);
                block.width(block.width() - trimHwp);
            } else {
                // 도형이 오른쪽 → 텍스트 프레임 오른쪽 트리밍
                block.width(block.width() - trimHwp);
            }
        }
    }

    // ── 이미지 프레임 ──────────────────────────────────────

    private static void processImageFrames(IDMLSpread spread, IDMLPage page,
                                            ASTImageLoader imageLoader,
                                            ColorResolver colorResolver,
                                            ResolvedData resolvedData,
                                            ResolvedPage resolvedPage,
                                            ASTSection section) {
        if (imageLoader == null) return;

        List<IDMLImageFrame> imageFrames = spread.getImageFramesOnPage(page);
        Set<String> processedFrameKeys = new HashSet<>();
        List<IDMLImageFrame> uniqueFrames = new ArrayList<>();
        for (IDMLImageFrame imgFrame : imageFrames) {
            String dedupKey = ASTFigureBuilder.buildImageFrameDedupKey(imgFrame);
            if (dedupKey != null && !processedFrameKeys.add(dedupKey)) {
                continue;
            }
            uniqueFrames.add(imgFrame);
        }

        IDMLPage finalPage = page;
        int totalImgFrames = uniqueFrames.size();
        List<ASTFigure> imageFigures = uniqueFrames.parallelStream()
                .map(imgFrame -> {
                    ASTFigure fig = ASTFigureBuilder.createFigureFromImageFrame(
                            imgFrame, finalPage, imageLoader, colorResolver,
                            resolvedData, resolvedPage);
                    if (fig == null) {
                        System.err.println("[IMG-FAIL] Page " + finalPage.pageNumber()
                                + " imageFrame URI=" + imgFrame.linkResourceURI()
                                + " bounds=" + java.util.Arrays.toString(imgFrame.geometricBounds())
                                + " transform=" + java.util.Arrays.toString(imgFrame.itemTransform()));
                    }
                    return fig;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (imageFigures.size() < totalImgFrames) {
            System.err.println("[IMG-WARN] Page " + page.pageNumber()
                    + ": " + (totalImgFrames - imageFigures.size()) + "/" + totalImgFrames + " image frames failed");
        }
        imageFigures.forEach(section::addBlock);
    }

    // ── 복잡 벡터 그룹 사전 스캔 ──────────────────────────────────────

    /**
     * 벡터 도형 그룹 중 복잡한 것(벡터 ≥3 + 같은 그룹 텍스트 프레임 존재)을 찾아
     * 해당 그룹 ID를 반환. 텍스트 프레임 처리에서 이 그룹의 TF를 건너뛰기 위함.
     */
    // ── 벡터 도형 ──────────────────────────────────────

    private static void processVectorShapes(IDMLSpread spread, IDMLPage page,
                                             ASTImageLoader imageLoader,
                                             ColorResolver colorResolver,
                                             ResolvedData resolvedData,
                                             ResolvedPage resolvedPage,
                                             ASTSection section) {
        if (imageLoader == null) return;

        List<IDMLVectorShape> vectorShapes = spread.getVectorShapesOnPage(page);
        vectorShapes.removeIf(s -> !isShapeCenterOnPage(s, page));

        // 퇴화된 도형 제거 (열린 경로에 점이 1개뿐인 도형 — 렌더링 불가)
        vectorShapes.removeIf(s -> isDegenerateShape(s));

        // 그룹 소속 도형과 개별 도형 분리
        Map<String, List<IDMLVectorShape>> groupedShapes = new LinkedHashMap<>();
        List<IDMLVectorShape> ungrouped = new ArrayList<>();
        for (IDMLVectorShape s : vectorShapes) {
            if (s.fromGroup() && s.parentGroupId() != null) {
                groupedShapes.computeIfAbsent(s.parentGroupId(), k -> new ArrayList<>()).add(s);
            } else {
                ungrouped.add(s);
            }
        }

        IDMLPage finalPage = page;

        // 개별 도형 래스터화
        List<ASTFigure> vectorFigures = ungrouped.parallelStream()
                .map(shape -> ASTFigureBuilder.createFigureFromVectorShape(
                        shape, finalPage, imageLoader, colorResolver,
                        resolvedData, resolvedPage))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());


        // 그룹별 합성 래스터화 (넓게 분산된 도형은 클러스터별 분할)
        for (List<IDMLVectorShape> group : groupedShapes.values()) {
            List<List<IDMLVectorShape>> clusters = clusterGroupShapes(group);
            for (List<IDMLVectorShape> cluster : clusters) {
                ASTFigure fig = ASTFigureBuilder.createFigureFromVectorGroup(
                        cluster, finalPage, imageLoader, colorResolver,
                        resolvedData, resolvedPage);
                if (fig != null) {
                    vectorFigures.add(fig);
                }
            }
        }

        // 페이지 경계 클리핑
        long fullPageW = CoordinateConverter.pointsToHwpunits(
                IDMLGeometry.width(page.geometricBounds()));
        long fullPageH = CoordinateConverter.pointsToHwpunits(
                IDMLGeometry.height(page.geometricBounds()));
        vectorFigures.removeIf(fig -> !clipFigureToPage(fig, fullPageW, fullPageH));
        vectorFigures.forEach(section::addBlock);
    }

    // ── 마스터 페이지 콘텐츠 ──────────────────────────────────────

    private static void processMasterContent(IDMLPage page, IDMLDocument idmlDoc,
                                              ASTImageLoader imageLoader,
                                              ColorResolver colorResolver,
                                              ASTSection section) {
        if (imageLoader == null || page.appliedMasterSpread() == null) return;

        IDMLSpread masterSpread = idmlDoc.getMasterSpread(page.appliedMasterSpread());
        if (masterSpread == null) return;

        IDMLPage masterPage = findMatchingMasterPage(masterSpread, page);
        if (masterPage == null) return;

        long clipPageW = CoordinateConverter.pointsToHwpunits(
                IDMLGeometry.width(page.geometricBounds()));
        long clipPageH = CoordinateConverter.pointsToHwpunits(
                IDMLGeometry.height(page.geometricBounds()));

        // 마스터 이미지 프레임 (중복 제거)
        List<IDMLImageFrame> masterImages = masterSpread.getImageFramesOnPage(masterPage);
        Set<String> masterImgKeys = new HashSet<>();
        List<IDMLImageFrame> uniqueMasterImgs = new ArrayList<>();
        for (IDMLImageFrame mif : masterImages) {
            String key = ASTFigureBuilder.buildImageFrameDedupKey(mif);
            if (key == null || masterImgKeys.add(key)) {
                uniqueMasterImgs.add(mif);
            }
        }
        IDMLPage mp = masterPage;
        List<ASTFigure> masterImgFigs = uniqueMasterImgs.parallelStream()
                .map(f -> ASTFigureBuilder.createFigureFromImageFrame(f, mp, imageLoader, colorResolver))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        for (ASTFigure fig : masterImgFigs) {
            fig.zOrder(fig.zOrder() - 10000);
        }
        masterImgFigs.removeIf(fig -> !clipFigureToPage(fig, clipPageW, clipPageH));
        masterImgFigs.forEach(section::addBlock);

        // 마스터 벡터 도형
        List<IDMLVectorShape> masterVectors = masterSpread.getVectorShapesOnPage(masterPage);
        masterVectors.removeIf(s -> !isShapeCenterOnPage(s, masterPage));
        IDMLPage mp2 = masterPage;
        List<ASTFigure> masterVecFigs = masterVectors.parallelStream()
                .map(s -> ASTFigureBuilder.createFigureFromVectorShape(
                        s, mp2, imageLoader, colorResolver))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        for (ASTFigure fig : masterVecFigs) {
            fig.zOrder(fig.zOrder() - 10000);
        }
        masterVecFigs.removeIf(fig -> !clipFigureToPage(fig, clipPageW, clipPageH));
        masterVecFigs.forEach(section::addBlock);

        System.err.println("[Stage4] Page " + page.pageNumber()
                + " master: images=" + masterImgFigs.size()
                + " vectors=" + masterVecFigs.size());
    }

    // ── 마스터 페이지 푸터 ──────────────────────────────────────

    private static void processMasterFooter(IDMLPage page, IDMLDocument idmlDoc,
                                             ColorResolver colorResolver, ASTSection section) {
        if (page.appliedMasterSpread() == null) return;

        IDMLSpread masterSpread = idmlDoc.getMasterSpread(page.appliedMasterSpread());
        if (masterSpread == null) return;

        IDMLPage masterPage = findMatchingMasterPage(masterSpread, page);
        if (masterPage == null) return;

        List<IDMLTextFrame> masterTFs = masterSpread.getTextFramesOnPage(masterPage);
        for (IDMLTextFrame mtf : masterTFs) {
            if (isFooterTextFrame(mtf, masterPage)) {
                String storyId = mtf.parentStoryId();
                IDMLStory story = storyId != null ? idmlDoc.getStory(storyId) : null;
                if (story != null) {
                    String footerText = resolveFooterText(story, page);
                    if (footerText != null && !footerText.trim().isEmpty()) {
                        ASTTextFrameBlock footerBlock = createFooterBlock(
                                mtf, masterPage, page, footerText, colorResolver);
                        if (footerBlock != null) {
                            section.addBlock(footerBlock);
                            System.err.println("[Stage4] Page " + page.pageNumber()
                                    + " footer: \"" + footerText.trim() + "\"");
                        }
                    }
                }
            }
        }
    }

    // ── 스토리 → 단락 변환 ──────────────────────────────────────

    /**
     * IDMLStory → ASTParagraph 리스트 변환.
     * 스토리 내 테이블은 문단 흐름의 올바른 위치에 인라인으로 삽입.
     */
    static void convertStoryToParagraphs(IDMLStory story, ASTTextFrameBlock block,
                                            FlattenedObjectPool pool,
                                            IDMLTextFrame tf, IDMLPage page,
                                            int zOrder,
                                            IDMLDocument idmlDoc,
                                            ColorResolver colorResolver,
                                            ASTImageLoader imageLoader,
                                            ResolvedData resolvedData) {
        // 스토리 전체에 BT 수식 폰트 런이 있는지 미리 확인
        boolean storyHasBTRuns = false;
        for (IDMLParagraph p : story.paragraphs()) {
            for (IDMLCharacterRun r : p.characterRuns()) {
                if (r.isBTFont() || r.grepMathFont()) { storyHasBTRuns = true; break; }
            }
            if (storyHasBTRuns) break;
        }

        // 테이블을 paragraphIndexBefore 기준으로 매핑
        // 문단이 없는 스토리(table-only)에서는 인라인 삽입 불가 → convertStoryTables에서 독립 처리
        Map<Integer, List<IDMLTable>> tablesByParaIdx = new HashMap<>();
        if (!story.paragraphs().isEmpty()) {
            for (IDMLTable t : story.tables()) {
                if (t.paragraphIndexBefore() >= 0) {
                    List<IDMLTable> list = tablesByParaIdx.get(t.paragraphIndexBefore());
                    if (list == null) {
                        list = new ArrayList<>();
                        tablesByParaIdx.put(t.paragraphIndexBefore(), list);
                    }
                    list.add(t);
                }
            }
        }

        // 본문 뒤로 이동할 인라인 프레임 수집
        List<IDMLTextFrame> deferredFrames = new ArrayList<>();
        int paraIdx = 0;

        for (IDMLParagraph idmlPara : story.paragraphs()) {
            // 이 문단 앞에 삽입할 테이블
            List<IDMLTable> tablesHere = tablesByParaIdx.get(paraIdx);
            if (tablesHere != null) {
                for (IDMLTable idmlTable : tablesHere) {
                    ASTTable astTable = ASTTableConverter.convertTable(
                            idmlTable, tf, page, zOrder, idmlDoc, colorResolver, imageLoader);
                    if (astTable != null) {
                        addInlineTableOrFlatten(astTable, block);
                    }
                }
            }

            // 교사용프레임 등 뒤로 이동할 인라인 프레임 수집
            for (IDMLCharacterRun run : idmlPara.characterRuns()) {
                for (IDMLTextFrame inlineTf : run.inlineFrames()) {
                    if (shouldDeferInlineFrame(inlineTf)) {
                        deferredFrames.add(inlineTf);
                    }
                }
            }
            ASTParagraph astPara = ASTStoryConverter.convertParagraph(
                    idmlPara, pool, idmlDoc, colorResolver, imageLoader, storyHasBTRuns, resolvedData);
            if (astPara != null) {
                block.addParagraph(astPara);
            }
            paraIdx++;
        }

        // 마지막 문단 뒤 테이블
        List<IDMLTable> tablesAfterLast = tablesByParaIdx.get(paraIdx);
        if (tablesAfterLast != null) {
            for (IDMLTable idmlTable : tablesAfterLast) {
                ASTTable astTable = ASTTableConverter.convertTable(
                        idmlTable, tf, page, zOrder, idmlDoc, colorResolver, imageLoader);
                if (astTable != null) {
                    addInlineTableOrFlatten(astTable, block);
                }
            }
        }

        // 지연된 프레임의 스토리 내용을 본문 뒤에 추가
        for (IDMLTextFrame deferredTf : deferredFrames) {
            String deferredStoryId = deferredTf.parentStoryId();
            if (deferredStoryId == null) continue;
            IDMLStory deferredStory = idmlDoc.getStory(deferredStoryId);
            if (deferredStory == null) continue;

            for (IDMLParagraph deferredPara : deferredStory.paragraphs()) {
                ASTParagraph astPara = ASTStoryConverter.convertParagraph(
                        deferredPara, pool, idmlDoc, colorResolver, imageLoader, false, resolvedData);
                if (astPara != null) {
                    block.addParagraph(astPara);
                }
            }
        }

        // 마지막 빈 단락 제거
        removeTrailingEmptyParagraphs(block.paragraphs());
    }

    /**
     * 스토리의 테이블 → ASTTable 변환.
     * paragraphIndexBefore가 설정된 테이블은 이미 인라인 처리되었으므로 스킵.
     * 단, 문단이 없는 table-only 스토리는 인라인 삽입이 불가하므로 독립 블록으로 처리.
     */
    private static void convertStoryTables(IDMLStory story, ASTSection section,
                                             IDMLTextFrame tf, IDMLPage page,
                                             int zOrder, IDMLDocument idmlDoc,
                                             ColorResolver colorResolver,
                                             ASTImageLoader imageLoader) {
        boolean tableOnlyStory = story.paragraphs().isEmpty();
        for (IDMLTable idmlTable : story.tables()) {
            // 인라인 처리된 테이블은 스킵 — 단, table-only 스토리는 인라인 처리 안 됨
            if (idmlTable.paragraphIndexBefore() >= 0 && !tableOnlyStory) {
                continue;
            }
            ASTTable table = ASTTableConverter.convertTable(
                    idmlTable, tf, page, zOrder, idmlDoc, colorResolver, imageLoader);
            if (table != null) {
                section.addBlock(table);
            }
        }
    }

    // ── 헬퍼 메서드 ──────────────────────────────────────

    /**
     * 연결 프레임 체인 수집.
     */
    static void collectLinkedFrames(IDMLTextFrame startFrame,
                                      FlattenedObjectPool pool,
                                      List<String> frameChain) {
        String nextId = startFrame.nextTextFrame();
        Set<String> visited = new HashSet<>(frameChain);
        while (nextId != null && !nextId.isEmpty()
                && !"n".equals(nextId) && !"null".equalsIgnoreCase(nextId)
                && !visited.contains(nextId)) {
            visited.add(nextId);
            frameChain.add(nextId);
            FlatObject nextFo = pool.get(nextId);
            if (nextFo == null || !(nextFo.sourceObject() instanceof IDMLTextFrame)) break;
            IDMLTextFrame nextTf = (IDMLTextFrame) nextFo.sourceObject();
            nextId = nextTf.nextTextFrame();
        }
    }

    /**
     * 프레임 체인의 페이지 번호 수집.
     */
    private static void collectLinkedFramePages(List<String> frameIds,
                                                 FlattenedObjectPool pool,
                                                 List<Integer> pages) {
        Set<Integer> seen = new HashSet<>(pages);
        for (int i = 1; i < frameIds.size(); i++) {
            FlatObject fo = pool.get(frameIds.get(i));
            if (fo != null && !seen.contains(fo.pageNumber())) {
                seen.add(fo.pageNumber());
                pages.add(fo.pageNumber());
            }
        }
    }

    /**
     * 마스터 스프레드에서 현재 페이지에 매칭되는 마스터 페이지를 찾는다.
     */
    static IDMLPage findMatchingMasterPage(IDMLSpread masterSpread, IDMLPage targetPage) {
        List<IDMLPage> masterPages = masterSpread.pages();
        if (masterPages.isEmpty()) return null;
        if (masterPages.size() == 1) return masterPages.get(0);

        double targetTx = targetPage.itemTransform() != null ? targetPage.itemTransform()[4] : 0;
        if (targetTx < 0) {
            return masterPages.get(0);
        } else {
            return masterPages.get(masterPages.size() - 1);
        }
    }

    /**
     * 도형의 중심점이 페이지 영역 안에 있는지 확인.
     */
    /**
     * 그룹 내 도형을 공간적 클러스터로 분할.
     * 도형 간 간격이 GAP_THRESHOLD(50pt) 이상이면 별도 클러스터로 분리하여
     * 불필요한 투명 영역이 큰 합성 PNG를 방지한다.
     */
    private static List<List<IDMLVectorShape>> clusterGroupShapes(List<IDMLVectorShape> shapes) {
        if (shapes.size() <= 1) {
            return Collections.singletonList(shapes);
        }

        final double GAP_THRESHOLD = 50.0; // points

        // 각 도형의 변환된 bounding box 계산
        double[][] bounds = new double[shapes.size()][];
        for (int i = 0; i < shapes.size(); i++) {
            IDMLVectorShape s = shapes.get(i);
            bounds[i] = IDMLGeometry.getTransformedBoundingBox(
                    s.geometricBounds(), s.itemTransform());
        }

        // Union-Find 기반 클러스터링: 두 도형의 bbox가 GAP_THRESHOLD 이내이면 같은 클러스터
        int[] parent = new int[shapes.size()];
        for (int i = 0; i < parent.length; i++) parent[i] = i;

        for (int i = 0; i < shapes.size(); i++) {
            if (bounds[i] == null) continue;
            for (int j = i + 1; j < shapes.size(); j++) {
                if (bounds[j] == null) continue;
                if (bboxGap(bounds[i], bounds[j]) <= GAP_THRESHOLD) {
                    union(parent, i, j);
                }
            }
        }

        // 클러스터별 그룹화
        Map<Integer, List<IDMLVectorShape>> clusterMap = new LinkedHashMap<>();
        for (int i = 0; i < shapes.size(); i++) {
            int root = find(parent, i);
            clusterMap.computeIfAbsent(root, k -> new ArrayList<>()).add(shapes.get(i));
        }

        return new ArrayList<>(clusterMap.values());
    }

    /** 두 bounding box 사이의 최소 간격 (겹치면 0). */
    private static double bboxGap(double[] a, double[] b) {
        // a = [top, left, bottom, right], b = [top, left, bottom, right]
        double gapX = Math.max(0, Math.max(a[1] - b[3], b[1] - a[3]));
        double gapY = Math.max(0, Math.max(a[0] - b[2], b[0] - a[2]));
        return Math.max(gapX, gapY);
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; }
        return i;
    }

    private static void union(int[] parent, int i, int j) {
        int ri = find(parent, i), rj = find(parent, j);
        if (ri != rj) parent[ri] = rj;
    }

    /**
     * 퇴화된(degenerate) 도형 판별.
     * 열린 경로에 점이 1개뿐이면 선도 면도 그릴 수 없으므로 렌더링 불가.
     */
    private static boolean isDegenerateShape(IDMLVectorShape shape) {
        if (!shape.pathOpen()) return false;
        if (shape.hasSubPaths()) return false;
        List<IDMLVectorShape.PathPoint> pts = shape.pathPoints();
        return pts == null || pts.size() < 2;
    }

    static boolean isShapeCenterOnPage(IDMLVectorShape shape, IDMLPage page) {
        double[] bounds = shape.geometricBounds();
        double[] transform = shape.itemTransform();
        if (bounds == null || transform == null) return true;

        double[] shapeBox = IDMLGeometry.getTransformedBoundingBox(bounds, transform);

        double[] pageBounds = page.geometricBounds();
        double[] pageTransform = page.itemTransform();
        if (pageBounds == null || pageTransform == null) return true;

        double[] pageTL = CoordinateConverter.applyTransform(pageTransform, pageBounds[1], pageBounds[0]);
        double[] pageBR = CoordinateConverter.applyTransform(pageTransform, pageBounds[3], pageBounds[2]);
        double pageMinX = Math.min(pageTL[0], pageBR[0]);
        double pageMaxX = Math.max(pageTL[0], pageBR[0]);
        double pageMinY = Math.min(pageTL[1], pageBR[1]);
        double pageMaxY = Math.max(pageTL[1], pageBR[1]);

        double pageW = pageMaxX - pageMinX;
        double pageH = pageMaxY - pageMinY;

        double shapeW = shapeBox[2] - shapeBox[0];
        double shapeH = shapeBox[3] - shapeBox[1];
        if (shapeW > pageW * 2.0 || shapeH > pageH * 2.0) {
            return true;
        }

        double cx = (bounds[1] + bounds[3]) / 2.0;
        double cy = (bounds[0] + bounds[2]) / 2.0;
        double[] absCenter = CoordinateConverter.applyTransform(transform, cx, cy);

        double tolerance = 1.0;
        return absCenter[0] >= pageMinX - tolerance && absCenter[0] <= pageMaxX + tolerance
                && absCenter[1] >= pageMinY - tolerance && absCenter[1] <= pageMaxY + tolerance;
    }

    /**
     * Figure를 페이지 경계로 클리핑.
     *
     * @return false if figure is completely outside page bounds
     */
    static boolean clipFigureToPage(ASTFigure fig, long pageW, long pageH) {
        long x = fig.x();
        long y = fig.y();
        long w = fig.width();
        long h = fig.height();

        long clipLeft = Math.max(0, x);
        long clipTop = Math.max(0, y);
        long clipRight = Math.min(pageW, x + w);
        long clipBottom = Math.min(pageH, y + h);

        long clipW = clipRight - clipLeft;
        long clipH = clipBottom - clipTop;

        if (clipW <= 0 || clipH <= 0) return false;
        if (clipLeft == x && clipTop == y && clipW == w && clipH == h) return true;

        double leftRatio = (double) (clipLeft - x) / w;
        double topRatio = (double) (clipTop - y) / h;
        double rightRatio = (double) (clipRight - x) / w;
        double bottomRatio = (double) (clipBottom - y) / h;

        byte[] imageData = fig.imageData();
        if (imageData != null && imageData.length > 0) {
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageData));
                if (img != null) {
                    int imgW = img.getWidth();
                    int imgH = img.getHeight();
                    int cropX = Math.max(0, Math.min((int) Math.round(imgW * leftRatio), imgW - 1));
                    int cropY = Math.max(0, Math.min((int) Math.round(imgH * topRatio), imgH - 1));
                    int cropW = Math.max(1, Math.min((int) Math.round(imgW * (rightRatio - leftRatio)), imgW - cropX));
                    int cropH = Math.max(1, Math.min((int) Math.round(imgH * (bottomRatio - topRatio)), imgH - cropY));

                    BufferedImage cropped = img.getSubimage(cropX, cropY, cropW, cropH);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(cropped, "png", baos);
                    fig.imageData(baos.toByteArray());
                    fig.pixelWidth(cropW);
                    fig.pixelHeight(cropH);
                }
            } catch (Exception e) {
                // 이미지 크롭 실패 시 위치/크기만 클리핑
            }
        }

        fig.x(clipLeft);
        fig.y(clipTop);
        fig.width(clipW);
        fig.height(clipH);
        return true;
    }

    /**
     * 텍스트 프레임 블록 생성.
     */
    static ASTTextFrameBlock createTextFrameBlock(IDMLTextFrame tf, IDMLPage page,
                                                    int zOrder, ColorResolver colorResolver,
                                                    ResolvedData resolvedData, ResolvedPage resolvedPage) {
        ASTTextFrameBlock block = new ASTTextFrameBlock();
        block.sourceId(tf.selfId());

        double rotation = IDMLGeometry.extractRotation(tf.itemTransform());
        boolean hasRotation = Math.abs(rotation) > 0.5;

        long xHwp, yHwp, wHwp, hHwp;

        // --- resolved.json 좌표 우선, IDML 폴백 ---
        boolean resolvedApplied = false;
        if (!hasRotation && resolvedData != null && resolvedPage != null
                && resolvedPage.bounds() != null && tf.selfId() != null) {
            ResolvedPageItem ri = resolvedData.getPageItemByIdmlId(tf.selfId());
            if (ri != null && ri.geometricBounds() != null) {
                double[] gb = ri.geometricBounds();
                double rW = gb[3] - gb[1];
                double rH = gb[2] - gb[0];
                if (rW > 0 && rH > 0) {
                    double[] rel = resolvedPage.toPageRelative(gb);
                    xHwp = CoordinateConverter.pointsToHwpunits(rel[0]);
                    yHwp = CoordinateConverter.pointsToHwpunits(rel[1]);
                    wHwp = CoordinateConverter.pointsToHwpunits(rW);
                    hHwp = CoordinateConverter.pointsToHwpunits(rH);

                    // 페이지 경계 클리핑
                    long pageW = CoordinateConverter.pointsToHwpunits(resolvedPage.width());
                    long pageH = CoordinateConverter.pointsToHwpunits(resolvedPage.height());
                    if (xHwp < 0) { wHwp += xHwp; xHwp = 0; }
                    if (yHwp < 0) { hHwp += yHwp; yHwp = 0; }
                    if (xHwp + wHwp > pageW) { wHwp = pageW - xHwp; }
                    if (yHwp + hHwp > pageH) { hHwp = pageH - yHwp; }
                    if (wHwp > 0 && hHwp > 0) {
                        block.x(xHwp);
                        block.y(yHwp);
                        block.width(wHwp);
                        block.height(hHwp);
                        resolvedApplied = true;
                    }
                }
            }
        }

        if (!resolvedApplied) {
            double w, h;
            if (hasRotation) {
                w = IDMLGeometry.scaledWidth(tf.geometricBounds(), tf.itemTransform());
                h = IDMLGeometry.scaledHeight(tf.geometricBounds(), tf.itemTransform());
                wHwp = CoordinateConverter.pointsToHwpunits(w);
                hHwp = CoordinateConverter.pointsToHwpunits(h);
                double[] relCenter = IDMLGeometry.pageRelativeCenter(
                        tf.geometricBounds(), tf.itemTransform(),
                        page.geometricBounds(), page.itemTransform());
                xHwp = CoordinateConverter.pointsToHwpunits(relCenter[0]) - wHwp / 2;
                yHwp = CoordinateConverter.pointsToHwpunits(relCenter[1]) - hHwp / 2;
            } else {
                double[] relPos = IDMLGeometry.pageRelativePosition(
                        tf.geometricBounds(), tf.itemTransform(),
                        page.geometricBounds(), page.itemTransform());
                w = IDMLGeometry.transformedWidth(tf.geometricBounds(), tf.itemTransform());
                h = IDMLGeometry.transformedHeight(tf.geometricBounds(), tf.itemTransform());
                xHwp = CoordinateConverter.pointsToHwpunits(relPos[0]);
                yHwp = CoordinateConverter.pointsToHwpunits(relPos[1]);
                wHwp = CoordinateConverter.pointsToHwpunits(w);
                hHwp = CoordinateConverter.pointsToHwpunits(h);
            }

            // 페이지 경계 클리핑
            if (!hasRotation) {
                long pageW = CoordinateConverter.pointsToHwpunits(IDMLGeometry.width(page.geometricBounds()));
                long pageH = CoordinateConverter.pointsToHwpunits(IDMLGeometry.height(page.geometricBounds()));
                if (xHwp < 0) { wHwp += xHwp; xHwp = 0; }
                if (yHwp < 0) { hHwp += yHwp; yHwp = 0; }
                if (xHwp + wHwp > pageW) { wHwp = pageW - xHwp; }
                if (yHwp + hHwp > pageH) { hHwp = pageH - yHwp; }
                if (wHwp <= 0 || hHwp <= 0) {
                    System.err.println("[CLIP-SKIP] TextFrame id=" + tf.selfId()
                            + " clipped to w=" + wHwp + " h=" + hHwp
                            + " w_orig=" + CoordinateConverter.pointsToHwpunits(w)
                            + " h_orig=" + CoordinateConverter.pointsToHwpunits(h)
                            + " pageW=" + pageW + " pageH=" + pageH);
                    return null;
                }
            }

            block.x(xHwp);
            block.y(yHwp);
            block.width(wHwp);
            block.height(hHwp);
        }

        block.zOrder(zOrder);
        block.columnCount(tf.columnCount());
        block.columnGutter(CoordinateConverter.pointsToHwpunits(tf.columnGutter()));

        // 컬럼 폭 정보
        if ("FixedWidth".equals(tf.columnType())) {
            long fw = CoordinateConverter.pointsToHwpunits(tf.columnFixedWidth());
            long[] widths = new long[tf.columnCount()];
            java.util.Arrays.fill(widths, fw);
            block.columnWidths(widths);
        } else if ("FlexibleWidth".equals(tf.columnType()) && tf.columnWidths() != null) {
            double[] srcWidths = tf.columnWidths();
            long[] widths = new long[srcWidths.length];
            for (int cw = 0; cw < srcWidths.length; cw++) {
                widths[cw] = CoordinateConverter.pointsToHwpunits(srcWidths[cw]);
            }
            block.columnWidths(widths);
        }

        if (tf.insetSpacing() != null) {
            double[] inset = tf.insetSpacing();
            block.insetTop(CoordinateConverter.pointsToHwpunits(inset[0]));
            block.insetLeft(CoordinateConverter.pointsToHwpunits(inset[1]));
            block.insetBottom(CoordinateConverter.pointsToHwpunits(inset[2]));
            block.insetRight(CoordinateConverter.pointsToHwpunits(inset[3]));
        }

        block.verticalJustification(tf.verticalJustification());

        // 프레임 자체의 fill/stroke 해석
        String resolvedFill = tf.fillColor() != null ? colorResolver.resolve(tf.fillColor()) : null;
        String resolvedStroke = tf.strokeColor() != null ? colorResolver.resolve(tf.strokeColor()) : null;
        double strokeWeight = tf.strokeWeight();
        String strokeType = tf.strokeType();
        double fillTint = tf.fillTint();
        double strokeTint = tf.strokeTint();
        double cornerRadius = tf.cornerRadius();
        // per-corner radii fallback (TopLeftCornerRadius 등 개별 속성만 있는 경우)
        if (cornerRadius <= 0 && tf.cornerRadii() != null) {
            for (double r : tf.cornerRadii()) {
                cornerRadius = Math.max(cornerRadius, r);
            }
        }

        // 래퍼 사각형 속성 병합: 프레임 자체에 유효한 stroke가 없고 래퍼에 fill이 있으면
        // 래퍼 fill을 셀 배경으로 사용하고, 프레임 fill은 유지
        if (tf.hasWrapper()) {
            String wFill = colorResolver.resolve(tf.wrapperFillColor());
            String wStroke = colorResolver.resolve(tf.wrapperStrokeColor());

            // 래퍼 fill → 블록 배경 (프레임 자체 fill이 Paper/None인 경우)
            boolean frameHasVisibleFill = resolvedFill != null
                    && !resolvedFill.equals("#FFFFFF")
                    && tf.fillColor() != null
                    && !tf.fillColor().contains("Paper");
            if (!frameHasVisibleFill && wFill != null) {
                block.wrapperFillColor(wFill);
                block.wrapperFillTint(tf.wrapperFillTint() >= 0 ? tf.wrapperFillTint() : 100);
            }

            // 래퍼 stroke → 블록 stroke (프레임 자체 stroke가 없는 경우)
            if ((resolvedStroke == null || strokeWeight <= 0) && wStroke != null) {
                resolvedStroke = wStroke;
                strokeWeight = tf.wrapperStrokeWeight();
                strokeTint = 100;
                if (tf.wrapperStrokeType() != null) {
                    strokeType = tf.wrapperStrokeType();
                }
            }

            // 래퍼 corner radius → 블록 corner radius (프레임 자체가 0인 경우)
            if (cornerRadius <= 0 && tf.wrapperCornerRadius() > 0) {
                cornerRadius = tf.wrapperCornerRadius();
            }
        }

        block.fillColor(resolvedFill);
        block.strokeColor(resolvedStroke);
        block.strokeWeight(strokeWeight);
        block.strokeType(strokeType);
        block.fillTint(fillTint);
        block.strokeTint(strokeTint);
        block.cornerRadius(cornerRadius);
        block.rotationAngle(rotation);

        // 비사각형 폴리곤 경로
        if (tf.localPathX() != null && tf.localPathX().length > 4) {
            double[] lpx = tf.localPathX();
            double[] lpy = tf.localPathY();
            double[] pageAbs = IDMLGeometry.absoluteTopLeft(
                    page.geometricBounds(), page.itemTransform());
            long[] pxHwp = new long[lpx.length];
            long[] pyHwp = new long[lpy.length];
            for (int i = 0; i < lpx.length; i++) {
                double[] abs = CoordinateConverter.applyTransform(
                        tf.itemTransform(), lpx[i], lpy[i]);
                pxHwp[i] = CoordinateConverter.pointsToHwpunits(abs[0] - pageAbs[0]);
                pyHwp[i] = CoordinateConverter.pointsToHwpunits(abs[1] - pageAbs[1]);
            }
            block.pathPoints(pxHwp, pyHwp);
        }

        return block;
    }

    /**
     * 마스터 페이지 텍스트프레임이 푸터에 위치하는지 판별.
     */
    private static boolean isFooterTextFrame(IDMLTextFrame tf, IDMLPage page) {
        if (tf.geometricBounds() == null || tf.itemTransform() == null
                || page.geometricBounds() == null || page.itemTransform() == null) {
            return false;
        }
        double[] relPos = IDMLGeometry.pageRelativePosition(
                tf.geometricBounds(), tf.itemTransform(),
                page.geometricBounds(), page.itemTransform());
        double pageHeight = IDMLGeometry.height(page.geometricBounds());
        return pageHeight > 0 && relPos[1] > pageHeight * 0.85;
    }

    /**
     * 마스터 페이지 스토리의 텍스트를 해석하여 푸터 텍스트를 생성.
     */
    private static String resolveFooterText(IDMLStory story, IDMLPage docPage) {
        StringBuilder sb = new StringBuilder();
        for (IDMLParagraph para : story.paragraphs()) {
            for (IDMLCharacterRun run : para.characterRuns()) {
                String content = run.content();
                if (content == null) continue;
                for (int i = 0; i < content.length(); i++) {
                    char c = content.charAt(i);
                    if (c == '\uFFFE') {
                        sb.append(docPage.pageNumber());
                    } else if (c == '\uFFFF') {
                        String marker = docPage.sectionMarker();
                        if (marker != null) {
                            sb.append(marker);
                        }
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * ACE 플레이스홀더 문자(\uFFFC, \uFFFE, \uFFFF)를 제거한다.
     */
    static String stripACEPlaceholders(String text) {
        if (text == null) return null;
        if (text.indexOf('\uFFFC') < 0 && text.indexOf('\uFFFE') < 0 && text.indexOf('\uFFFF') < 0) return text;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\uFFFC' && c != '\uFFFE' && c != '\uFFFF') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 마스터 페이지 푸터 블록 생성.
     */
    private static ASTTextFrameBlock createFooterBlock(IDMLTextFrame tf, IDMLPage masterPage,
                                                        IDMLPage docPage, String footerText,
                                                        ColorResolver colorResolver) {
        double[] relPos = IDMLGeometry.pageRelativePosition(
                tf.geometricBounds(), tf.itemTransform(),
                masterPage.geometricBounds(), masterPage.itemTransform());
        double w = IDMLGeometry.transformedWidth(tf.geometricBounds(), tf.itemTransform());
        double h = IDMLGeometry.transformedHeight(tf.geometricBounds(), tf.itemTransform());

        long xHwp = CoordinateConverter.pointsToHwpunits(relPos[0]);
        long yHwp = CoordinateConverter.pointsToHwpunits(relPos[1]);
        long wHwp = CoordinateConverter.pointsToHwpunits(w);
        long hHwp = CoordinateConverter.pointsToHwpunits(h);

        long pageW = CoordinateConverter.pointsToHwpunits(IDMLGeometry.width(docPage.geometricBounds()));
        long pageH = CoordinateConverter.pointsToHwpunits(IDMLGeometry.height(docPage.geometricBounds()));
        if (xHwp < 0) { wHwp += xHwp; xHwp = 0; }
        if (yHwp < 0) { hHwp += yHwp; yHwp = 0; }
        if (xHwp + wHwp > pageW) { wHwp = pageW - xHwp; }
        if (yHwp + hHwp > pageH) { hHwp = pageH - yHwp; }
        if (wHwp <= 0 || hHwp <= 0) return null;

        ASTTextFrameBlock block = new ASTTextFrameBlock();
        block.sourceId("footer_" + docPage.pageNumber());
        block.x(xHwp);
        block.y(yHwp);
        block.width(wHwp);
        block.height(hHwp);
        block.zOrder(9999);
        block.columnCount(1);

        ASTParagraph para = new ASTParagraph();
        if (docPage.pageNumber() % 2 == 1) {
            para.alignment("right");
        } else {
            para.alignment("left");
        }
        ASTTextRun textRun = new ASTTextRun();
        textRun.text(footerText.trim());
        para.addItem(textRun);
        block.addParagraph(para);

        return block;
    }

    /**
     * 인라인 텍스트 프레임을 본문 뒤로 이동해야 하는지 판별.
     */
    static boolean shouldDeferInlineFrame(IDMLTextFrame inlineTf) {
        String style = inlineTf.appliedObjectStyle();
        if (style != null && style.contains("교사용프레임")) {
            return true;
        }
        return false;
    }

    /**
     * FlatObject 리스트를 페이지 내 위치 순서로 정렬.
     */
    static void sortByPosition(List<FlatObject> objects, IDMLPage page) {
        objects.sort((a, b) -> {
            double[] aPos = IDMLGeometry.pageRelativePosition(
                    a.geometricBounds(), a.itemTransform(),
                    page.geometricBounds(), page.itemTransform());
            double[] bPos = IDMLGeometry.pageRelativePosition(
                    b.geometricBounds(), b.itemTransform(),
                    page.geometricBounds(), page.itemTransform());
            int cmp = Double.compare(aPos[1], bPos[1]);
            if (cmp != 0) return cmp;
            return Double.compare(aPos[0], bPos[0]);
        });
    }

    /**
     * 1x1 인라인 테이블은 셀 내용을 직접 블록에 추가 (이중 테이블 방지),
     * 그 외에는 인라인 테이블로 추가.
     */
    private static void addInlineTableOrFlatten(ASTTable astTable, ASTTextFrameBlock block) {
        if (astTable.rowCount() == 1 && astTable.colCount() == 1) {
            ASTTableCell cell = astTable.rows().get(0).cells().get(0);
            if (!hasSolidBorder(cell)) {
                // 테두리 없는 1x1 인라인 테이블 → 셀 내용을 직접 추가
                for (ASTParagraph p : cell.paragraphs()) {
                    block.addParagraph(p);
                }
                return;
            }
        }
        ASTParagraph tablePara = new ASTParagraph();
        tablePara.inlineTable(astTable);
        block.addParagraph(tablePara);
    }

    private static boolean hasSolidBorder(ASTTableCell cell) {
        return isSolidBorder(cell.topBorder())
                || isSolidBorder(cell.bottomBorder())
                || isSolidBorder(cell.leftBorder())
                || isSolidBorder(cell.rightBorder());
    }

    private static boolean isSolidBorder(ASTTableCell.CellBorder border) {
        if (border == null) return false;
        String type = border.strokeType();
        return type == null || "solid".equalsIgnoreCase(type);
    }

    /**
     * 텍스트 프레임 블록에 실제 콘텐츠가 있는지 확인.
     */
    static boolean hasContent(ASTTextFrameBlock block) {
        if (block.fillColor() != null) return true;
        // visible stroke가 있으면 테두리 사각형으로 출력 (table-only 래퍼 등)
        if (block.strokeColor() != null && block.strokeWeight() > 0) return true;
        if (block.paragraphs().isEmpty()) return false;
        for (ASTParagraph para : block.paragraphs()) {
            if (!para.items().isEmpty()) return true;
            if (para.inlineTable() != null) return true;
        }
        return false;
    }

    /**
     * 마지막 빈 단락 제거.
     */
    static void removeTrailingEmptyParagraphs(List<ASTParagraph> paragraphs) {
        while (paragraphs.size() > 1) {
            ASTParagraph last = paragraphs.get(paragraphs.size() - 1);
            if (isEffectivelyEmpty(last)) {
                paragraphs.remove(paragraphs.size() - 1);
            } else {
                break;
            }
        }
    }

    private static boolean isEffectivelyEmpty(ASTParagraph para) {
        if (para.items().isEmpty()) return true;
        for (ASTInlineItem item : para.items()) {
            if (item.itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                String text = ((ASTTextRun) item).text();
                if (text != null && !text.trim().isEmpty()) return false;
            } else if (item.itemType() == ASTInlineItem.ItemType.INLINE_OBJECT
                    || item.itemType() == ASTInlineItem.ItemType.EQUATION) {
                return false;
            }
        }
        return true;
    }
}
