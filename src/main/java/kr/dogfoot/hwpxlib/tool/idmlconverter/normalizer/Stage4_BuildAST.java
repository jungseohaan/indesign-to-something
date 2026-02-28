package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertOptions;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Stage 4: 스토리 우선 AST 구축.
 * IDMLDocument + FlattenedObjectPool → ASTDocument.
 *
 * 오케스트레이터: 실제 변환 로직은 아래 클래스에 위임한다.
 * - {@link ASTStoryConverter}: 스토리/단락/런 변환
 * - {@link ASTMathGrouper}: BT 수식 폰트 런 그룹핑
 * - {@link ASTInlineObjectBuilder}: 인라인 그래픽, 이미지, 벡터, 테이블
 */
public class Stage4_BuildAST {

    public static ASTDocument build(FlattenedObjectPool pool, IDMLDocument idmlDoc,
                                     ConvertOptions options, String sourceFileName) {
        System.err.println("[Stage4_BuildAST] Building AST from stories...");

        ASTDocument doc = new ASTDocument();
        doc.sourceFile(sourceFileName);
        doc.sourceFormat("IDML");

        ColorResolver colorResolver = new ColorResolver(idmlDoc);
        Set<String> processedStories = new HashSet<>();

        // 이미지 로더 초기화
        ASTImageLoader imageLoader = options.includeImages()
                ? new ASTImageLoader(idmlDoc, options) : null;

        // 페이지별 섹션 구축
        for (IDMLSpread spread : idmlDoc.spreads()) {
            for (IDMLPage page : spread.pages()) {
                ASTSection section = new ASTSection();
                section.pageNumber(page.pageNumber());

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

                // 페이지의 텍스트 프레임 수집 (위→아래, 왼→오른 정렬)
                List<FlatObject> textFrames = pool.getTextFramesOnPage(page.pageNumber());
                sortByPosition(textFrames, page);

                // 각 텍스트 프레임의 스토리 → AST 블록 변환
                for (FlatObject fo : textFrames) {
                    IDMLTextFrame tf = (IDMLTextFrame) fo.sourceObject();
                    if (tf.isEditorialNote()) continue;

                    String storyId = tf.parentStoryId();
                    if (storyId == null) continue;

                    // 연결 프레임 체인에서 첫 프레임이 아닌 경우 → 빈 블록만 생성 (연결 글상자용)
                    String prevFrame = tf.previousTextFrame();
                    boolean isLinkedContinuation = prevFrame != null && !prevFrame.isEmpty()
                            && !"n".equals(prevFrame) && !"null".equalsIgnoreCase(prevFrame);
                    if (isLinkedContinuation) {
                        // 빈 블록(geometry only) 생성 — 텍스트는 첫 프레임에만
                        ASTTextFrameBlock emptyBlock = createTextFrameBlock(tf, page, fo.zOrder(), colorResolver);
                        if (emptyBlock != null) {
                            emptyBlock.storyId(storyId);
                            section.addBlock(emptyBlock);
                        }
                        continue;
                    }

                    if (processedStories.contains(storyId)) continue;
                    processedStories.add(storyId);

                    IDMLStory story = idmlDoc.getStory(storyId);
                    boolean hasFill = tf.fillColor() != null
                            && !tf.fillColor().contains("None")
                            && !tf.fillColor().contains("Paper");
                    if (story == null || (story.isEmpty() && !hasFill)) continue;

                    // Story 메타데이터 수집
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
                    ASTTextFrameBlock block = createTextFrameBlock(tf, page, fo.zOrder(), colorResolver);
                    if (block == null) continue; // 페이지 밖 객체 건너뜀
                    block.storyId(storyId);

                    // 스토리 → 단락 변환
                    convertStoryToParagraphs(story, block, pool, idmlDoc, colorResolver, imageLoader);

                    // 테이블 처리
                    convertStoryTables(story, section, tf, page, fo.zOrder(), idmlDoc, colorResolver, imageLoader);

                    if (hasContent(block)) {
                        section.addBlock(block);
                    }
                }

                // 페이지의 이미지 프레임 → ASTFigure 블록 변환 (병렬)
                // 같은 이미지가 같은 위치/크기로 중복 배치된 경우 하나만 출력 (PSD 레이어 가시성 미지원 대응)
                if (imageLoader != null) {
                    List<IDMLImageFrame> imageFrames = spread.getImageFramesOnPage(page);
                    Set<String> processedFrameKeys = new HashSet<>();
                    List<IDMLImageFrame> uniqueFrames = new ArrayList<>();
                    for (IDMLImageFrame imgFrame : imageFrames) {
                        String dedupKey = ASTInlineObjectBuilder.buildImageFrameDedupKey(imgFrame);
                        if (dedupKey != null && !processedFrameKeys.add(dedupKey)) {
                            continue;  // 중복 프레임 스킵
                        }
                        uniqueFrames.add(imgFrame);
                    }
                    ASTImageLoader finalImageLoader = imageLoader;
                    IDMLPage finalPage = page;
                    int totalImgFrames = uniqueFrames.size();
                    List<ASTFigure> imageFigures = uniqueFrames.parallelStream()
                            .map(imgFrame -> {
                                ASTFigure fig = ASTInlineObjectBuilder.createFigureFromImageFrame(imgFrame, finalPage, finalImageLoader);
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

                // 페이지의 벡터 도형 → ASTFigure (PNG 래스터화) 블록 변환 (병렬)
                if (imageLoader != null) {
                    List<IDMLVectorShape> vectorShapes = spread.getVectorShapesOnPage(page);
                    // 양면 스프레드에서 페이지 경계 근처 도형 중복 방지: 중심점 기준 필터
                    vectorShapes.removeIf(s -> !isShapeCenterOnPage(s, page));

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

                    ASTImageLoader finalImageLoader2 = imageLoader;
                    IDMLPage finalPage2 = page;

                    // 개별 도형 래스터화 (기존 로직)
                    List<ASTFigure> vectorFigures = ungrouped.parallelStream()
                            .map(shape -> ASTInlineObjectBuilder.createFigureFromVectorShape(shape, finalPage2, finalImageLoader2, colorResolver))
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());

                    // 그룹별 합성 래스터화
                    for (List<IDMLVectorShape> group : groupedShapes.values()) {
                        ASTFigure fig = ASTInlineObjectBuilder.createFigureFromVectorGroup(
                                group, finalPage2, finalImageLoader2, colorResolver);
                        if (fig != null) {
                            vectorFigures.add(fig);
                        }
                    }

                    // 페이지 경계로 클리핑 (bleed/pasteboard 밖 도형 제거)
                    long fullPageW = CoordinateConverter.pointsToHwpunits(
                            IDMLGeometry.width(page.geometricBounds()));
                    long fullPageH = CoordinateConverter.pointsToHwpunits(
                            IDMLGeometry.height(page.geometricBounds()));
                    vectorFigures.removeIf(fig -> !clipFigureToPage(fig, fullPageW, fullPageH));
                    vectorFigures.forEach(section::addBlock);
                }

                // === 마스터 페이지의 이미지/벡터 → ASTFigure (배경 레이어) ===
                if (imageLoader != null && page.appliedMasterSpread() != null) {
                    IDMLSpread masterSpread = idmlDoc.getMasterSpread(page.appliedMasterSpread());
                    if (masterSpread != null) {
                        IDMLPage masterPage = findMatchingMasterPage(masterSpread, page);
                        if (masterPage != null) {
                            // 마스터 이미지 프레임 (중복 제거 포함)
                            List<IDMLImageFrame> masterImages = masterSpread.getImageFramesOnPage(masterPage);
                            Set<String> masterImgKeys = new HashSet<>();
                            List<IDMLImageFrame> uniqueMasterImgs = new ArrayList<>();
                            for (IDMLImageFrame mif : masterImages) {
                                String key = ASTInlineObjectBuilder.buildImageFrameDedupKey(mif);
                                if (key == null || masterImgKeys.add(key)) {
                                    uniqueMasterImgs.add(mif);
                                }
                            }
                            ASTImageLoader masterImgLoader = imageLoader;
                            IDMLPage mp = masterPage;
                            List<ASTFigure> masterImgFigs = uniqueMasterImgs.parallelStream()
                                    .map(f -> ASTInlineObjectBuilder.createFigureFromImageFrame(f, mp, masterImgLoader))
                                    .filter(Objects::nonNull)
                                    .collect(Collectors.toList());
                            // 마스터 도형을 페이지 경계로 클리핑 (양면 스프레드 대응)
                            long clipPageW = CoordinateConverter.pointsToHwpunits(
                                    IDMLGeometry.width(page.geometricBounds()));
                            long clipPageH = CoordinateConverter.pointsToHwpunits(
                                    IDMLGeometry.height(page.geometricBounds()));

                            for (ASTFigure fig : masterImgFigs) {
                                fig.zOrder(fig.zOrder() - 10000);
                            }
                            masterImgFigs.removeIf(fig -> !clipFigureToPage(fig, clipPageW, clipPageH));
                            masterImgFigs.forEach(section::addBlock);

                            // 마스터 벡터 도형
                            List<IDMLVectorShape> masterVectors = masterSpread.getVectorShapesOnPage(masterPage);
                            masterVectors.removeIf(s -> !isShapeCenterOnPage(s, masterPage));
                            ASTImageLoader masterVecLoader = imageLoader;
                            IDMLPage mp2 = masterPage;
                            List<ASTFigure> masterVecFigs = masterVectors.parallelStream()
                                    .map(s -> ASTInlineObjectBuilder.createFigureFromVectorShape(s, mp2, masterVecLoader, colorResolver))
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
                    }
                }

                // === 마스터 페이지 푸터 텍스트 추출 ===
                if (page.appliedMasterSpread() != null) {
                    IDMLSpread masterSpreadForFooter = idmlDoc.getMasterSpread(page.appliedMasterSpread());
                    if (masterSpreadForFooter != null) {
                        IDMLPage masterPageForFooter = findMatchingMasterPage(masterSpreadForFooter, page);
                        if (masterPageForFooter != null) {
                            List<IDMLTextFrame> masterTFs = masterSpreadForFooter.getTextFramesOnPage(masterPageForFooter);
                            for (IDMLTextFrame mtf : masterTFs) {
                                if (isFooterTextFrame(mtf, masterPageForFooter)) {
                                    String storyId = mtf.parentStoryId();
                                    IDMLStory story = storyId != null ? idmlDoc.getStory(storyId) : null;
                                    if (story != null) {
                                        String footerText = resolveFooterText(story, page);
                                        if (footerText != null && !footerText.trim().isEmpty()) {
                                            ASTTextFrameBlock footerBlock = createFooterBlock(
                                                    mtf, masterPageForFooter, page, footerText, colorResolver);
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
                    }
                }

                // 디버그: 각 페이지별 블록 수
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

                doc.addSection(section);
            }
        }

        // 메타데이터: 폰트, 스타일, 색상
        populateMetadata(doc, idmlDoc, colorResolver);

        System.err.println("[Stage4_BuildAST] Built " + doc.sections().size() + " sections.");
        return doc;
    }

    /**
     * 연결 프레임 체인 수집: 시작 프레임의 nextTextFrame을 따라가며 체인 구축.
     */
    private static void collectLinkedFrames(IDMLTextFrame startFrame,
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
     * 프레임 체인의 페이지 번호 수집 (중복 제거, 순서 유지).
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
     * 마스터 스프레드가 2페이지(좌/우)인 경우, 일반 페이지의 좌/우 위치에 맞는 페이지를 선택.
     */
    private static IDMLPage findMatchingMasterPage(IDMLSpread masterSpread, IDMLPage targetPage) {
        List<IDMLPage> masterPages = masterSpread.pages();
        if (masterPages.isEmpty()) return null;
        if (masterPages.size() == 1) return masterPages.get(0);

        // 좌/우 페이지 구분: itemTransform의 tx(4번째) 부호로 판별
        //   tx < 0 → 왼쪽 페이지 → 마스터 첫 번째
        //   tx >= 0 → 오른쪽 페이지 → 마스터 마지막
        double targetTx = targetPage.itemTransform() != null ? targetPage.itemTransform()[4] : 0;
        if (targetTx < 0) {
            return masterPages.get(0);
        } else {
            return masterPages.get(masterPages.size() - 1);
        }
    }

    /**
     * 도형의 중심점이 페이지 영역 안에 있는지 확인.
     * 양면 스프레드에서 페이지 경계 근처 도형이 양쪽 페이지에 중복 포함되는 것을 방지한다.
     * 스프레드 전체를 덮는 배경 도형은 양쪽 페이지에 모두 포함시킨다.
     */
    private static boolean isShapeCenterOnPage(IDMLVectorShape shape, IDMLPage page) {
        double[] bounds = shape.geometricBounds();
        double[] transform = shape.itemTransform();
        if (bounds == null || transform == null) return true;

        // 도형의 변환 후 바운딩 박스
        double[] shapeBox = IDMLGeometry.getTransformedBoundingBox(bounds, transform);
        // shapeBox: [minX, minY, maxX, maxY]

        // 페이지 영역 (스프레드 좌표)
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

        // 스프레드 전체를 덮는 도형(배경 등)만 양쪽 페이지에 모두 포함
        // 2x 이상이면 전체 스프레드 배경으로 간주 (예: u1517 = 2.03x)
        double shapeW = shapeBox[2] - shapeBox[0];
        double shapeH = shapeBox[3] - shapeBox[1];
        if (shapeW > pageW * 2.0 || shapeH > pageH * 2.0) {
            return true;
        }

        // 도형 중심점 (스프레드 좌표)
        double cx = (bounds[1] + bounds[3]) / 2.0;
        double cy = (bounds[0] + bounds[2]) / 2.0;
        double[] absCenter = CoordinateConverter.applyTransform(transform, cx, cy);

        // 1pt 여유 (부동소수점 오차 보정)
        double tolerance = 1.0;
        return absCenter[0] >= pageMinX - tolerance && absCenter[0] <= pageMaxX + tolerance
                && absCenter[1] >= pageMinY - tolerance && absCenter[1] <= pageMaxY + tolerance;
    }

    /**
     * 마스터 페이지 figure를 페이지 경계로 클리핑.
     * 양면 마스터 스프레드의 도형이 양쪽 페이지에 걸쳐 있을 때,
     * 해당 페이지 영역만 보이도록 이미지를 잘라낸다.
     *
     * @return false if figure is completely outside page bounds (should be removed)
     */
    private static boolean clipFigureToPage(ASTFigure fig, long pageW, long pageH) {
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

        // 이미지 크롭 비율 계산
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
     * 메타데이터 (폰트, 스타일, 색상) 채우기.
     */
    private static void populateMetadata(ASTDocument doc, IDMLDocument idmlDoc, ColorResolver colorResolver) {
        // 폰트
        int fontIdx = 0;
        for (Map.Entry<String, IDMLFontDef> entry : idmlDoc.fonts().entrySet()) {
            ASTFontDef fd = new ASTFontDef();
            fd.fontId(String.valueOf(fontIdx++));
            fd.fontFamily(entry.getValue().fontFamily());
            fd.fontType(entry.getValue().fontType());
            doc.addFont(fd);
        }

        // 단락 스타일
        for (Map.Entry<String, IDMLStyleDef> entry : idmlDoc.paraStyles().entrySet()) {
            IDMLStyleDef s = entry.getValue();
            ASTStyleDef sd = new ASTStyleDef();
            sd.styleId(entry.getKey());
            sd.styleName(s.simpleName());
            sd.basedOnStyleRef(s.basedOn());
            sd.alignment(s.textAlignment());
            sd.fontFamily(s.fontFamily());
            sd.fontStyle(s.fontStyle());
            if (s.fontSize() != null) sd.fontSizeHwpunits((int)(s.fontSize() * 100));
            if (s.fillColor() != null) sd.textColor(colorResolver.resolve(s.fillColor()));
            if (s.firstLineIndent() != null) sd.firstLineIndent((long)(s.firstLineIndent() * 100));
            if (s.leftIndent() != null) sd.leftMargin((long)(s.leftIndent() * 100));
            if (s.rightIndent() != null) sd.rightMargin((long)(s.rightIndent() * 100));
            if (s.spaceBefore() != null) sd.spaceBefore((long)(s.spaceBefore() * 100));
            if (s.spaceAfter() != null) sd.spaceAfter((long)(s.spaceAfter() * 100));
            if (s.tracking() != null) sd.letterSpacing((short) Math.round(s.tracking() / 10.0));
            // bold / italic
            sd.bold(s.bold());
            sd.italic(s.italic());
            // 장평
            if (s.horizontalScale() != null) sd.horizontalScale((short) Math.round(s.horizontalScale()));
            // 어간
            if (s.desiredWordSpacing() != null) sd.wordSpacing(s.desiredWordSpacing());
            // leading → lineSpacing
            if (s.leading() != null) {
                sd.lineSpacingType("fixed");
                sd.lineSpacing((int) CoordinateConverter.pointsToHwpunits(s.leading()));
            } else if ("Auto".equals(s.leadingType())) {
                sd.lineSpacingType("percent");
                double autoLd = s.autoLeading() != null ? s.autoLeading() : 120;
                sd.lineSpacing((int) Math.round(autoLd));
            }
            // autoLeading 비율 보존
            if (s.autoLeading() != null) sd.autoLeading(s.autoLeading());
            // 밑줄 / 취소선
            sd.underline(s.underline());
            sd.strikeThrough(s.strikeThrough());
            // 두문자 (DropCap)
            if (s.dropCapLines() != null && s.dropCapLines() > 0) {
                sd.dropCapLines(s.dropCapLines());
                sd.dropCapCharacters(s.dropCapCharacters());
            }
            // 스타일 내 탭 정지점
            if (s.tabStops() != null && !s.tabStops().isEmpty()) {
                java.util.List<ASTTabStop> astTabs = new java.util.ArrayList<>();
                for (IDMLStyleDef.TabStop ts : s.tabStops()) {
                    ASTTabStop at = new ASTTabStop();
                    at.position((long)(ts.position() * 100));
                    at.alignment(ASTStoryConverter.mapTabAlignment(ts.alignment()));
                    at.leader(ts.leader());
                    astTabs.add(at);
                }
                sd.tabStops(astTabs);
            }
            doc.addParagraphStyle(sd);
        }

        // 문자 스타일
        for (Map.Entry<String, IDMLStyleDef> entry : idmlDoc.charStyles().entrySet()) {
            IDMLStyleDef s = entry.getValue();
            ASTStyleDef sd = new ASTStyleDef();
            sd.styleId(entry.getKey());
            sd.styleName(s.simpleName());
            sd.basedOnStyleRef(s.basedOn());
            sd.fontFamily(s.fontFamily());
            sd.fontStyle(s.fontStyle());
            if (s.fontSize() != null) sd.fontSizeHwpunits((int)(s.fontSize() * 100));
            if (s.fillColor() != null) sd.textColor(colorResolver.resolve(s.fillColor()));
            if (s.tracking() != null) sd.letterSpacing((short) Math.round(s.tracking() / 10.0));
            sd.bold(s.bold());
            sd.italic(s.italic());
            if (s.horizontalScale() != null) sd.horizontalScale((short) Math.round(s.horizontalScale()));
            sd.underline(s.underline());
            sd.strikeThrough(s.strikeThrough());
            doc.addCharacterStyle(sd);
        }

        // 색상
        for (Map.Entry<String, String> entry : idmlDoc.colors().entrySet()) {
            doc.putColor(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 텍스트 프레임 블록 생성.
     */
    private static ASTTextFrameBlock createTextFrameBlock(IDMLTextFrame tf, IDMLPage page, int zOrder, ColorResolver colorResolver) {
        ASTTextFrameBlock block = new ASTTextFrameBlock();
        block.sourceId(tf.selfId());

        // 페이지 상대 좌표 계산
        double rotation = IDMLGeometry.extractRotation(tf.itemTransform());
        boolean hasRotation = Math.abs(rotation) > 0.5;

        double w, h;
        long xHwp, yHwp, wHwp, hHwp;

        if (hasRotation) {
            // 회전 있는 프레임: 실제 크기(scaledWidth/Height) 사용 + 중심 기준 위치
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
            // 비회전: 기존 방식 (바운딩 박스)
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

        // 페이지 경계 클리핑: 양쪽 페이지에 걸친 배경 사각형 등을 페이지 내로 제한
        // 회전된 프레임은 AABB 클리핑이 의미 없으므로 건너뜀
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
        block.zOrder(zOrder);
        block.columnCount(tf.columnCount());
        block.columnGutter(CoordinateConverter.pointsToHwpunits(tf.columnGutter()));

        // 컬럼 폭 정보 (FixedWidth / FlexibleWidth인 경우)
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
        block.fillColor(tf.fillColor() != null ? colorResolver.resolve(tf.fillColor()) : null);
        block.strokeColor(tf.strokeColor() != null ? colorResolver.resolve(tf.strokeColor()) : null);
        block.strokeWeight(tf.strokeWeight());
        block.strokeType(tf.strokeType());
        block.fillTint(tf.fillTint());
        block.strokeTint(tf.strokeTint());
        block.cornerRadius(tf.cornerRadius());
        block.rotationAngle(rotation);

        return block;
    }

    /**
     * 마스터 페이지 텍스트프레임이 푸터(페이지 하단)에 위치하는지 판별.
     * 페이지 높이 대비 Y 위치가 85% 이상이면 푸터로 간주한다.
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
     * 마스터 페이지 스토리의 텍스트를 해석하여 푸터 텍스트를 생성한다.
     * \uFFFE → 페이지 번호, \uFFFF → 섹션 마커로 치환.
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
     * \uFFFC = Object Replacement Character (인라인 오브젝트 앵커, ACE 8)
     * \uFFFE = Auto Page Number (ACE 18)
     * \uFFFF = Section Marker (ACE 19)
     * 이 문자들은 XML에서 허용되지 않으므로 일반 텍스트 런에서 반드시 제거해야 한다.
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
     * 마스터 페이지 푸터 텍스트프레임을 문서 페이지 좌표계의 ASTTextFrameBlock으로 생성한다.
     */
    private static ASTTextFrameBlock createFooterBlock(IDMLTextFrame tf, IDMLPage masterPage,
                                                        IDMLPage docPage, String footerText,
                                                        ColorResolver colorResolver) {
        // 마스터 페이지 기준 상대 좌표 계산
        double[] relPos = IDMLGeometry.pageRelativePosition(
                tf.geometricBounds(), tf.itemTransform(),
                masterPage.geometricBounds(), masterPage.itemTransform());
        double w = IDMLGeometry.transformedWidth(tf.geometricBounds(), tf.itemTransform());
        double h = IDMLGeometry.transformedHeight(tf.geometricBounds(), tf.itemTransform());

        long xHwp = CoordinateConverter.pointsToHwpunits(relPos[0]);
        long yHwp = CoordinateConverter.pointsToHwpunits(relPos[1]);
        long wHwp = CoordinateConverter.pointsToHwpunits(w);
        long hHwp = CoordinateConverter.pointsToHwpunits(h);

        // 페이지 경계 클리핑
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

        // 푸터 텍스트를 단락으로 추가
        ASTParagraph para = new ASTParagraph();
        // 홀수 페이지(우측) → 우측 정렬, 짝수 페이지(좌측) → 좌측 정렬
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
     * IDMLStory → ASTParagraph 리스트 변환.
     * 교사용프레임(해설) 인라인 텍스트 프레임은 본문 뒤에 배치.
     */
    private static void convertStoryToParagraphs(IDMLStory story, ASTTextFrameBlock block,
                                                   FlattenedObjectPool pool,
                                                   IDMLDocument idmlDoc,
                                                   ColorResolver colorResolver,
                                                   ASTImageLoader imageLoader) {
        // 스토리 전체에 BT 수식 폰트 런이 있는지 미리 확인
        // (하나의 스토리 안에서 한 단락만 BT 런이 있어도 다른 단락에도 수식이 있을 수 있음)
        boolean storyHasBTRuns = false;
        for (IDMLParagraph p : story.paragraphs()) {
            for (IDMLCharacterRun r : p.characterRuns()) {
                if (r.isBTFont() || r.grepMathFont()) { storyHasBTRuns = true; break; }
            }
            if (storyHasBTRuns) break;
        }

        // 본문 뒤로 이동할 인라인 프레임 수집
        List<IDMLTextFrame> deferredFrames = new ArrayList<>();

        for (IDMLParagraph idmlPara : story.paragraphs()) {
            // 교사용프레임 등 뒤로 이동할 인라인 프레임 수집
            for (IDMLCharacterRun run : idmlPara.characterRuns()) {
                for (IDMLTextFrame inlineTf : run.inlineFrames()) {
                    if (shouldDeferInlineFrame(inlineTf)) {
                        deferredFrames.add(inlineTf);
                    }
                }
            }
            ASTParagraph astPara = ASTStoryConverter.convertParagraph(idmlPara, pool, idmlDoc, colorResolver, imageLoader, storyHasBTRuns);
            if (astPara != null) {
                block.addParagraph(astPara);
            }
        }

        // 지연된 프레임의 스토리 내용을 본문 뒤에 추가
        for (IDMLTextFrame deferredTf : deferredFrames) {
            String deferredStoryId = deferredTf.parentStoryId();
            if (deferredStoryId == null) continue;
            IDMLStory deferredStory = idmlDoc.getStory(deferredStoryId);
            if (deferredStory == null) continue;

            for (IDMLParagraph deferredPara : deferredStory.paragraphs()) {
                ASTParagraph astPara = ASTStoryConverter.convertParagraph(deferredPara, pool, idmlDoc, colorResolver, imageLoader, false);
                if (astPara != null) {
                    block.addParagraph(astPara);
                }
            }
        }

        // 마지막 빈 단락 제거 (IDML <Br/> 이후 빈 단락 방지)
        removeTrailingEmptyParagraphs(block.paragraphs());
    }

    /**
     * 인라인 텍스트 프레임을 본문 뒤로 이동해야 하는지 판별.
     * - 교사용프레임: 해설 내용이 문제 앞에 인라인으로 삽입되어 있으므로 뒤로 이동
     */
    static boolean shouldDeferInlineFrame(IDMLTextFrame inlineTf) {
        // 교사용프레임 오브젝트 스타일 체크
        String style = inlineTf.appliedObjectStyle();
        if (style != null && style.contains("교사용프레임")) {
            return true;
        }
        return false;
    }

    /**
     * 스토리의 테이블 → ASTTable 변환.
     */
    private static void convertStoryTables(IDMLStory story, ASTSection section,
                                             IDMLTextFrame tf, IDMLPage page,
                                             int zOrder, IDMLDocument idmlDoc,
                                             ColorResolver colorResolver,
                                             ASTImageLoader imageLoader) {
        for (IDMLTable idmlTable : story.tables()) {
            ASTTable table = ASTInlineObjectBuilder.convertTable(idmlTable, tf, page, zOrder, idmlDoc, colorResolver, imageLoader);
            if (table != null) {
                section.addBlock(table);
            }
        }
    }

    /**
     * FlatObject 리스트를 페이지 내 위치 순서로 정렬 (위→아래, 왼→오른).
     */
    private static void sortByPosition(List<FlatObject> objects, IDMLPage page) {
        objects.sort((a, b) -> {
            double[] aPos = IDMLGeometry.pageRelativePosition(
                    a.geometricBounds(), a.itemTransform(),
                    page.geometricBounds(), page.itemTransform());
            double[] bPos = IDMLGeometry.pageRelativePosition(
                    b.geometricBounds(), b.itemTransform(),
                    page.geometricBounds(), page.itemTransform());
            // Y 먼저 (위→아래), 같으면 X (왼→오른)
            int cmp = Double.compare(aPos[1], bPos[1]);
            if (cmp != 0) return cmp;
            return Double.compare(aPos[0], bPos[0]);
        });
    }

    /**
     * 텍스트 프레임 블록에 실제 콘텐츠(텍스트, 인라인 객체, 또는 배경색)가 있는지 확인.
     * 스타일만 있고 텍스트가 없는 빈 단락만 포함된 블록은 제거 대상.
     * 단, fillColor가 있으면 장식용 배경 사각형이므로 유지한다.
     */
    private static boolean hasContent(ASTTextFrameBlock block) {
        // 배경색이 있으면 텍스트가 없어도 유지 (장식용 배경 사각형)
        if (block.fillColor() != null) return true;
        if (block.paragraphs().isEmpty()) return false;
        for (ASTParagraph para : block.paragraphs()) {
            if (!para.items().isEmpty()) return true;
        }
        return false;
    }

    /**
     * 마지막 빈 단락 제거.
     * IDML의 마지막 &lt;Br/&gt; 이후에 생기는 빈 단락을 제거하여
     * HWPX 글상자에 불필요한 줄바꿈이 추가되지 않도록 한다.
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
            // BREAK만 있는 단락도 빈 단락으로 취급
        }
        return true;
    }
}
