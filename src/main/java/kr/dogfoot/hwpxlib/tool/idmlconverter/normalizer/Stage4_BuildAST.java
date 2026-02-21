package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertOptions;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.BTFontEquationConverter;
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
 */
public class Stage4_BuildAST {

    /** 이 높이(HWPUNIT)를 넘는 인라인 이미지는 별도 단락으로 분리 (~30pt ≈ 1cm) */
    private static final long IMAGE_SPLIT_THRESHOLD = 3000;

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

                    // 연결 프레임 체인에서 첫 프레임이 아닌 경우
                    // IDML에서 "n"은 "없음"을 의미 (no previous frame)
                    String prevFrame = tf.previousTextFrame();
                    if (prevFrame != null && !prevFrame.isEmpty()
                            && !"n".equals(prevFrame) && !"null".equalsIgnoreCase(prevFrame)) {
                        continue;
                    }

                    if (processedStories.contains(storyId)) continue;
                    processedStories.add(storyId);

                    IDMLStory story = idmlDoc.getStory(storyId);
                    boolean hasFill = tf.fillColor() != null
                            && !tf.fillColor().contains("None")
                            && !tf.fillColor().contains("Paper");
                    if (story == null || (story.isEmpty() && !hasFill)) continue;

                    // 텍스트 프레임 블록 생성
                    ASTTextFrameBlock block = createTextFrameBlock(tf, page, fo.zOrder(), colorResolver);
                    if (block == null) continue; // 페이지 밖 객체 건너뜀

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
                        String dedupKey = buildImageFrameDedupKey(imgFrame);
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
                                ASTFigure fig = createFigureFromImageFrame(imgFrame, finalPage, finalImageLoader);
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
                    ASTImageLoader finalImageLoader2 = imageLoader;
                    IDMLPage finalPage2 = page;
                    List<ASTFigure> vectorFigures = vectorShapes.parallelStream()
                            .map(shape -> createFigureFromVectorShape(shape, finalPage2, finalImageLoader2, colorResolver))
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
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
                                String key = buildImageFrameDedupKey(mif);
                                if (key == null || masterImgKeys.add(key)) {
                                    uniqueMasterImgs.add(mif);
                                }
                            }
                            ASTImageLoader masterImgLoader = imageLoader;
                            IDMLPage mp = masterPage;
                            List<ASTFigure> masterImgFigs = uniqueMasterImgs.parallelStream()
                                    .map(f -> createFigureFromImageFrame(f, mp, masterImgLoader))
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
                                    .map(s -> createFigureFromVectorShape(s, mp2, masterVecLoader, colorResolver))
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
     */
    private static boolean isShapeCenterOnPage(IDMLVectorShape shape, IDMLPage page) {
        double[] bounds = shape.geometricBounds();
        double[] transform = shape.itemTransform();
        if (bounds == null || transform == null) return true;

        // 도형 중심점 (스프레드 좌표)
        double cx = (bounds[1] + bounds[3]) / 2.0;
        double cy = (bounds[0] + bounds[2]) / 2.0;
        double[] absCenter = CoordinateConverter.applyTransform(transform, cx, cy);

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

        return absCenter[0] >= pageMinX && absCenter[0] <= pageMaxX
                && absCenter[1] >= pageMinY && absCenter[1] <= pageMaxY;
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
            // leading → lineSpacing
            if (s.leading() != null) {
                sd.lineSpacingType("fixed");
                sd.lineSpacing((int) CoordinateConverter.pointsToHwpunits(s.leading()));
            } else if ("Auto".equals(s.leadingType())) {
                sd.lineSpacingType("percent");
                double autoLeading = s.autoLeading() != null ? s.autoLeading() : 120;
                sd.lineSpacing((int) Math.round(autoLeading));
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
        double[] relPos = IDMLGeometry.pageRelativePosition(
                tf.geometricBounds(), tf.itemTransform(),
                page.geometricBounds(), page.itemTransform());
        double w = IDMLGeometry.transformedWidth(tf.geometricBounds(), tf.itemTransform());
        double h = IDMLGeometry.transformedHeight(tf.geometricBounds(), tf.itemTransform());

        long xHwp = CoordinateConverter.pointsToHwpunits(relPos[0]);
        long yHwp = CoordinateConverter.pointsToHwpunits(relPos[1]);
        long wHwp = CoordinateConverter.pointsToHwpunits(w);
        long hHwp = CoordinateConverter.pointsToHwpunits(h);

        // 페이지 경계 클리핑: 양쪽 페이지에 걸친 배경 사각형 등을 페이지 내로 제한
        long pageW = CoordinateConverter.pointsToHwpunits(IDMLGeometry.width(page.geometricBounds()));
        long pageH = CoordinateConverter.pointsToHwpunits(IDMLGeometry.height(page.geometricBounds()));
        if (xHwp < 0) { wHwp += xHwp; xHwp = 0; }
        if (yHwp < 0) { hHwp += yHwp; yHwp = 0; }
        if (xHwp + wHwp > pageW) { wHwp = pageW - xHwp; }
        if (yHwp + hHwp > pageH) { hHwp = pageH - yHwp; }
        // 클리핑 결과 크기가 0 이하 → 페이지 밖 객체이므로 건너뜀
        if (wHwp <= 0 || hHwp <= 0) {
            System.err.println("[CLIP-SKIP] TextFrame id=" + tf.selfId()
                    + " clipped to w=" + wHwp + " h=" + hHwp
                    + " (orig x=" + CoordinateConverter.pointsToHwpunits(relPos[0])
                    + " y=" + CoordinateConverter.pointsToHwpunits(relPos[1])
                    + " w=" + CoordinateConverter.pointsToHwpunits(w)
                    + " h=" + CoordinateConverter.pointsToHwpunits(h)
                    + " pageW=" + pageW + " pageH=" + pageH + ")");
            return null;
        }

        block.x(xHwp);
        block.y(yHwp);
        block.width(wHwp);
        block.height(hHwp);
        block.zOrder(zOrder);
        block.columnCount(tf.columnCount());
        block.columnGutter(CoordinateConverter.pointsToHwpunits(tf.columnGutter()));

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
            ASTParagraph astPara = convertParagraph(idmlPara, pool, idmlDoc, colorResolver, imageLoader, storyHasBTRuns);
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
                ASTParagraph astPara = convertParagraph(deferredPara, pool, idmlDoc, colorResolver, imageLoader, false);
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
     * - AnchoredPosition="Anchored": 커스텀 위치 지정된 프레임
     */
    private static boolean shouldDeferInlineFrame(IDMLTextFrame inlineTf) {
        // 교사용프레임 오브젝트 스타일 체크
        String style = inlineTf.appliedObjectStyle();
        if (style != null && style.contains("교사용프레임")) {
            return true;
        }
        // AnchoredPosition="Anchored" 체크
        if ("Anchored".equals(inlineTf.anchoredPosition())) {
            return true;
        }
        return false;
    }

    /**
     * IDMLParagraph → ASTParagraph 변환.
     */
    private static ASTParagraph convertParagraph(IDMLParagraph idmlPara,
                                                   FlattenedObjectPool pool,
                                                   IDMLDocument idmlDoc,
                                                   ColorResolver colorResolver,
                                                   ASTImageLoader imageLoader,
                                                   boolean storyHasBTRuns) {
        ASTParagraph para = new ASTParagraph();

        // 단락 스타일
        String paraStyleRef = idmlPara.appliedParagraphStyle();
        if (paraStyleRef != null) {
            para.paragraphStyleRef(cleanStyleRef(paraStyleRef));
        }

        // 단락 속성
        if (idmlPara.justification() != null) {
            para.alignment(idmlPara.justification());
        }
        if (idmlPara.firstLineIndent() != null && idmlPara.firstLineIndent() != 0) {
            para.firstLineIndent(CoordinateConverter.pointsToHwpunits(idmlPara.firstLineIndent()));
        }
        if (idmlPara.leftIndent() != null && idmlPara.leftIndent() != 0) {
            para.leftMargin(CoordinateConverter.pointsToHwpunits(idmlPara.leftIndent()));
        }
        if (idmlPara.rightIndent() != null && idmlPara.rightIndent() != 0) {
            para.rightMargin(CoordinateConverter.pointsToHwpunits(idmlPara.rightIndent()));
        }
        if (idmlPara.spaceBefore() != null && idmlPara.spaceBefore() != 0) {
            para.spaceBefore(CoordinateConverter.pointsToHwpunits(idmlPara.spaceBefore()));
        }
        if (idmlPara.spaceAfter() != null && idmlPara.spaceAfter() != 0) {
            para.spaceAfter(CoordinateConverter.pointsToHwpunits(idmlPara.spaceAfter()));
        }
        // 줄간격 (leading)
        if (idmlPara.leading() != null) {
            para.lineSpacingType("fixed");
            para.lineSpacing((int) CoordinateConverter.pointsToHwpunits(idmlPara.leading()));
        }

        // 단락 배경
        if (idmlPara.shadingOn()) {
            para.shadingOn(true);
            String shadingColor = idmlPara.shadingColor();
            if (shadingColor != null) {
                para.shadingColor(colorResolver.resolve(shadingColor));
            }
            para.shadingTint(idmlPara.shadingTint());
        }

        // 탭 정지점 (인라인 오버라이드)
        if (idmlPara.tabStops() != null) {
            for (IDMLStyleDef.TabStop ts : idmlPara.tabStops()) {
                long posHwpunits = CoordinateConverter.pointsToHwpunits(ts.position());
                String alignment = mapTabAlignment(ts.alignment());
                para.addTabStop(new ASTTabStop(posHwpunits, alignment, ts.leader()));
            }
        }

        // Character Runs → 인라인 항목 (BT수식M 폰트 런은 그룹핑하여 ASTEquation으로 변환)
        // NP 폰트는 인라인 주석(아래첨자, 근호 등)이므로 그룹핑하지 않고 텍스트 런으로 처리
        // BT 런 사이에 끼인 짧은 일반 텍스트(변수명 등)는 "브릿지"로 수식 그룹에 포함

        // 전처리: 한국어+수식마커 혼합 런을 분리 (예: "_r를 구해" → "_r" + "를 구해")
        List<IDMLCharacterRun> runs = splitMathKoreanMixedRuns(idmlPara.characterRuns());
        List<IDMLCharacterRun> mathGroup = new ArrayList<>();

        // 단락 또는 스토리에 BT 수식 폰트 런이 하나라도 있는지 확인
        boolean paraHasBTRuns = storyHasBTRuns;
        if (!paraHasBTRuns) {
            for (IDMLCharacterRun r : runs) {
                if (r.isBTFont() || r.grepMathFont()) { paraHasBTRuns = true; break; }
            }
        }

        for (int idx = 0; idx < runs.size(); idx++) {
            IDMLCharacterRun run = runs.get(idx);
            if ((run.isBTFont() || run.grepMathFont()) && !isBTRunWithOnlyKorean(run.content())) {
                mathGroup.add(run);
            } else if (!mathGroup.isEmpty() && isMathBridgeRun(run, runs, idx)) {
                // BT 런 사이 또는 뒤의 비한국어 텍스트 → 수식 그룹에 포함
                mathGroup.add(run);
            } else if (paraHasBTRuns && looksLikeMathRun(run.content())) {
                // 단락에 BT 런이 있고 이 런에 BT 마커(_^&\) 포함 → 수식 그룹 시작/계속
                mathGroup.add(run);
            } else {
                // 수식 그룹 종료 → 변환
                if (!mathGroup.isEmpty()) {
                    flushMathGroup(mathGroup, para);
                    mathGroup.clear();
                }
                convertCharacterRun(run, idmlPara, para, pool, idmlDoc, colorResolver, imageLoader);
            }
        }
        // 마지막 수식 그룹 처리
        if (!mathGroup.isEmpty()) {
            flushMathGroup(mathGroup, para);
        }

        // 단락 끝의 trailing lineBreak 제거
        // 인라인 객체(InlineObject)가 뒤에 있어도, 마지막 텍스트런 이후의 break를 제거
        List<ASTInlineItem> items = para.items();
        int lastTextIdx = -1;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                lastTextIdx = i;
                break;
            }
        }
        for (int i = items.size() - 1; i > lastTextIdx; i--) {
            if (items.get(i).itemType() == ASTInlineItem.ItemType.BREAK) {
                items.remove(i);
            }
        }

        return para;
    }

    /**
     * IDML 탭 정렬 문자열을 HWPX 탭 타입으로 매핑.
     */
    private static String mapTabAlignment(String idmlAlignment) {
        if (idmlAlignment == null) return "left";
        switch (idmlAlignment) {
            case "CenterAlign": return "center";
            case "RightAlign": return "right";
            case "DecimalAlign": // IDML uses "Character" for decimal
            case "Character": return "decimal";
            default: return "left"; // LeftAlign 또는 기타
        }
    }

    /**
     * IDMLCharacterRun → ASTTextRun + ASTInlineObject + ASTBreak 변환.
     */
    private static void convertCharacterRun(IDMLCharacterRun run, IDMLParagraph parentPara,
                                              ASTParagraph para,
                                              FlattenedObjectPool pool,
                                              IDMLDocument idmlDoc,
                                              ColorResolver colorResolver,
                                              ASTImageLoader imageLoader) {
        String text = run.content();
        if (text != null && !text.isEmpty()) {
            // NP 폰트 글리프 → 유니코드 변환
            if (run.isNPFont()) {
                text = kr.dogfoot.hwpxlib.tool.equationconverter.idml.NPFontGlyphMap
                        .convertRunToUnicode(run.npFontName(), text);
                if (text.isEmpty()) return; // 분수 괄호 등 변환 후 빈 텍스트
            }
            // 연속 줄바꿈(\n\n+)을 하나로 머지
            text = text.replaceAll("\n{2,}", "\n");
            // 줄바꿈 분리
            String[] segments = text.split("\n", -1);
            for (int i = 0; i < segments.length; i++) {
                if (i > 0) {
                    para.addItem(new ASTBreak(ASTBreak.BreakType.LINE));
                }
                String seg = segments[i];
                if (!seg.isEmpty()) {
                    ASTTextRun textRun = createTextRun(run, seg, parentPara, idmlDoc, colorResolver);
                    para.addItem(textRun);
                }
            }
        }

        // 인라인 텍스트 프레임 (Anchored 위치의 프레임은 본문 뒤로 이동하므로 건너뜀)
        for (IDMLTextFrame inlineTf : run.inlineFrames()) {
            if (shouldDeferInlineFrame(inlineTf)) {
                continue;
            }
            ASTInlineObject inlineObj = createInlineObjectFromTextFrame(inlineTf, idmlDoc, colorResolver, imageLoader);
            if (inlineObj != null) {
                para.addItem(inlineObj);
            }
        }

        // 인라인 그래픽
        for (IDMLCharacterRun.InlineGraphic ig : run.inlineGraphics()) {
            ASTInlineObject inlineObj = createInlineObjectFromGraphic(ig, imageLoader, colorResolver);
            if (inlineObj != null) {
                // 크기 0인 RENDERED_GROUP 래퍼는 추가하지 않음 (배경 사각형+텍스트프레임 구조의 Group)
                boolean isEmptyWrapper = inlineObj.kind() == ASTInlineObject.ObjectKind.RENDERED_GROUP
                        && inlineObj.width() <= 0 && inlineObj.height() <= 0
                        && (inlineObj.imageData() == null || inlineObj.imageData().length == 0);
                if (!isEmptyWrapper) {
                    para.addItem(inlineObj);
                }
                // IMAGE로 처리된 Group은 자식 텍스트프레임을 별도 추출하지 않음
                // (이미지와 텍스트 오버레이가 하나의 시각 단위이므로 분리하면 겹침)
                if (inlineObj.kind() == ASTInlineObject.ObjectKind.IMAGE) {
                    continue;
                }
            }
            // 부모 Group의 배경 사각형에서 전체 스타일 추출 (fill, stroke, cornerRadius)
            GroupBackground bg = extractGroupBackground(ig, colorResolver);
            // 인라인 그래픽 내부의 자식 텍스트프레임 처리 (중첩 Group 포함, 재귀)
            collectChildTextFrames(ig, para, idmlDoc, colorResolver, imageLoader, bg);
        }
    }

    /**
     * 한국어+수식마커 혼합 런을 한국어/비한국어 경계에서 분리한다.
     * 예: "&P_r를 구해 보자" → "&P_r" + "를 구해 보자"
     * BT 폰트/grepMath 런이나 한국어/수식마커가 혼합되지 않은 런은 그대로 통과.
     */
    private static List<IDMLCharacterRun> splitMathKoreanMixedRuns(List<IDMLCharacterRun> runs) {
        List<IDMLCharacterRun> result = new ArrayList<>();
        for (IDMLCharacterRun run : runs) {
            // BT/grepMath 런도 한국어+수식 혼합이면 분리 대상 (한국어 부분을 수식에서 제외하기 위해)
            String text = run.content();
            if (text == null || text.isEmpty()) {
                result.add(run);
                continue;
            }
            // 한국어와 라틴/수학 문자가 모두 포함된 경우 분리 대상
            boolean hasKorean = false;
            boolean hasLatinMath = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c >= 0xAC00 && c <= 0xD7AF || c >= 0x3131 && c <= 0x318E) hasKorean = true;
                if (Character.isLetterOrDigit(c) && !(c >= 0xAC00 && c <= 0xD7AF) && !(c >= 0x3131 && c <= 0x318E))
                    hasLatinMath = true;
                if (c == '_' || c == '^' || c == '&' || c == '\\' || c == '`'
                        || "+-*/=<>()[]{}|!.".indexOf(c) >= 0) hasLatinMath = true;
            }
            if (!hasKorean || !hasLatinMath) {
                result.add(run);
                continue;
            }
            // 한국어/비한국어 경계에서 분리
            // 각 문자를 KOREAN(1) / OTHER(2) / NEUTRAL(0)로 분류
            int len = text.length();
            int[] types = new int[len];
            for (int i = 0; i < len; i++) {
                char c = text.charAt(i);
                if (c >= 0xAC00 && c <= 0xD7AF || c >= 0x3131 && c <= 0x318E) {
                    types[i] = 1; // KOREAN
                } else if (Character.isLetterOrDigit(c) || c == '_' || c == '^' || c == '&'
                        || c == '\\' || c == '`' || "+-*/=<>()[]{}|!.".indexOf(c) >= 0) {
                    types[i] = 2; // LATIN/MATH
                } else {
                    types[i] = 0; // NEUTRAL (공백, 기타)
                }
            }
            // 중립 문자 → 이전 타입 상속
            int lastType = 0;
            for (int i = 0; i < len; i++) {
                if (types[i] != 0) {
                    lastType = types[i];
                } else {
                    types[i] = (lastType != 0) ? lastType : 1;
                }
            }
            // 연속 동일 타입 구간을 분리
            int segStart = 0;
            for (int i = 1; i <= len; i++) {
                if (i == len || types[i] != types[segStart]) {
                    String segText = text.substring(segStart, i);
                    IDMLCharacterRun subRun = cloneRunForSplit(run, segText);
                    result.add(subRun);
                    segStart = i;
                }
            }
        }
        return result;
    }

    /**
     * 런의 스타일을 복사하고 텍스트만 변경한 새 런을 생성한다 (수식/한국어 분리용).
     */
    private static IDMLCharacterRun cloneRunForSplit(IDMLCharacterRun source, String newText) {
        IDMLCharacterRun clone = new IDMLCharacterRun();
        clone.appliedCharacterStyle(source.appliedCharacterStyle());
        clone.fontFamily(source.fontFamily());
        clone.fontSize(source.fontSize());
        clone.fillColor(source.fillColor());
        clone.fontStyle(source.fontStyle());
        clone.position(source.position());
        clone.tracking(source.tracking());
        clone.grepMathFont(source.grepMathFont());
        clone.content(newText);
        return clone;
    }

    /**
     * BT 수식 런 사이 또는 뒤의 비수식 런이 수식 그룹에 포함될 수 있는지 확인.
     * 비한국어 텍스트이고, (1) 뒤에 BT 수식 런이 이어지거나 (2) BT 마커를 포함하면 수식 그룹에 포함.
     */
    /**
     * 텍스트가 수식처럼 보이는지 확인.
     * BT 마커(_^&\), BT 키워드(.c3), 또는 연산자+변수 조합을 감지한다.
     */
    private static boolean looksLikeMathRun(String text) {
        if (text == null || text.isEmpty()) return false;
        // BT 마커
        boolean hasOperator = false;
        boolean hasLetterOrDigit = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '_' || c == '^' || c == '&' || c == '\\') return true;
            if ("+-*/=<>()[]{}|".indexOf(c) >= 0) hasOperator = true;
            if (Character.isLetterOrDigit(c)) hasLetterOrDigit = true;
        }
        // BT 키워드
        if (text.contains(".c3")) return true;
        // 연산자 + 문자/숫자 조합 → 수식 (예: (n-1), {n-(r-1)})
        return hasOperator && hasLetterOrDigit;
    }

    /**
     * BT 폰트 런의 내용이 한국어만 포함하는지 확인.
     * 한국어 + 공백/구두점만 있고 라틴 문자, 숫자, 수식 마커/연산자가 없으면 true.
     * BT 폰트 런이라도 한국어만 있으면 수식이 아닌 일반 텍스트로 처리하기 위해 사용.
     */
    private static boolean isBTRunWithOnlyKorean(String text) {
        if (text == null || text.isEmpty()) return false;
        boolean hasKorean = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7AF || c >= 0x3131 && c <= 0x318E) {
                hasKorean = true;
            } else if (Character.isLetterOrDigit(c)) {
                return false; // 라틴 문자 또는 숫자
            } else if (c == '_' || c == '^' || c == '&' || c == '\\' || c == '`') {
                return false; // BT 마커
            } else if ("+-*/=<>()[]{}|".indexOf(c) >= 0) {
                return false; // 수학 연산자
            }
        }
        return hasKorean;
    }

    private static boolean isMathBridgeRun(IDMLCharacterRun run, List<IDMLCharacterRun> runs, int idx) {
        String text = run.content();
        if (text == null || text.isEmpty()) return false;
        // 한국어 포함 → 브릿지 아님
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7AF) return false; // 한글 음절
            if (c >= 0x3131 && c <= 0x318E) return false; // 한글 자모
        }
        // BT수식M 마커 포함 → 수식 그룹의 연속으로 직접 포함 (look-ahead 불필요)
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '_' || c == '^' || c == '&' || c == '\\') return true;
        }
        // 뒤에 BT 수식 런이 있는지 확인 (비한국어 런은 건너뜀)
        for (int j = idx + 1; j < runs.size(); j++) {
            IDMLCharacterRun next = runs.get(j);
            if (next.isBTFont() || next.grepMathFont()) return true;
            String nextText = next.content();
            if (nextText == null || nextText.isEmpty()) continue;
            boolean hasKorean = false;
            for (int i = 0; i < nextText.length(); i++) {
                char c = nextText.charAt(i);
                if ((c >= 0xAC00 && c <= 0xD7AF) || (c >= 0x3131 && c <= 0x318E)) {
                    hasKorean = true; break;
                }
            }
            if (hasKorean) return false;
        }
        return false;
    }

    /**
     * 연속된 수식 폰트 런 그룹을 ASTEquation으로 변환하여 단락에 추가.
     * 수식으로 변환할 수 없는 경우 (순수 텍스트 등) 일반 텍스트 런으로 폴백.
     */
    private static void flushMathGroup(List<IDMLCharacterRun> mathRuns, ASTParagraph para) {
        String hwpScript = BTFontEquationConverter.convert(mathRuns);
        if (hwpScript != null) {
            String sourceType = mathRuns.get(0).isBTFont() ? "BT_FONT" : "GREP_FONT";
            para.addItem(new ASTEquation(hwpScript, sourceType));
        } else {
            // 수식이 아닌 BT 폰트 텍스트 → 일반 텍스트 런으로 폴백
            for (IDMLCharacterRun run : mathRuns) {
                String text = run.content();
                if (text != null && !text.isEmpty()) {
                    ASTTextRun textRun = new ASTTextRun();
                    textRun.text(text);
                    String ff = run.fontFamily();
                    if (run.isBTFont() || run.grepMathFont()) {
                        if (ff == null || !ff.contains("BT수식")) ff = "BT수식M";
                    }
                    textRun.fontFamily(ff);
                    textRun.grepMathFont(run.grepMathFont());
                    if (run.fontStyle() != null) textRun.fontStyle(run.fontStyle());
                    if (run.fontSize() != null) textRun.fontSizeHwpunits((int)(run.fontSize() * 100));
                    para.addItem(textRun);
                }
            }
        }
    }

    /**
     * InlineGraphic 내부의 TextFrame을 재귀적으로 수집하여 ASTParagraph에 추가.
     * 중첩 Group 구조에서도 모든 TextFrame을 찾아낸다.
     */
    private static void collectChildTextFrames(IDMLCharacterRun.InlineGraphic ig,
                                                 ASTParagraph para,
                                                 IDMLDocument idmlDoc,
                                                 ColorResolver colorResolver,
                                                 ASTImageLoader imageLoader,
                                                 GroupBackground bg) {
        for (IDMLTextFrame childTf : ig.childTextFrames()) {
            ASTInlineObject childObj = createInlineObjectFromTextFrame(childTf, idmlDoc, colorResolver, imageLoader);
            if (childObj != null) {
                if (bg != null && childObj.fillColor() == null) {
                    childObj.fillColor(bg.fillHex);
                    childObj.fillTint(bg.fillTint);
                    if (bg.strokeHex != null) {
                        childObj.strokeColor(bg.strokeHex);
                        childObj.strokeWeight(bg.strokeWeight);
                        childObj.strokeTint(bg.strokeTint);
                    }
                    if (bg.cornerRadius > 0) {
                        childObj.cornerRadius(bg.cornerRadius);
                    }
                }
                para.addItem(childObj);
            }
        }
        // 중첩 그래픽(Group 등) 내부의 TextFrame도 재귀적으로 처리
        for (IDMLCharacterRun.InlineGraphic childIg : ig.childGraphics()) {
            collectChildTextFrames(childIg, para, idmlDoc, colorResolver, imageLoader, bg);
        }
    }

    /**
     * 인라인 그래픽 계층에 자식 텍스트프레임이 있는지 재귀적으로 확인.
     */
    private static boolean hasChildTextFramesRecursive(IDMLCharacterRun.InlineGraphic ig) {
        if (!ig.childTextFrames().isEmpty()) return true;
        for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
            if (hasChildTextFramesRecursive(child)) return true;
        }
        return false;
    }

    /**
     * 인라인 Group의 자식 그래픽에서 배경 사각형의 채우기 색상을 추출.
     * Group이 배경 사각형 + 텍스트 프레임으로 구성된 경우,
     * 배경 사각형의 fillColor를 텍스트 프레임에 전달하기 위해 사용.
     */
    /**
     * 배경 사각형 스타일 정보.
     */
    private static class GroupBackground {
        String fillHex;
        double fillTint = 100;
        String strokeHex;
        double strokeWeight;
        double strokeTint = 100;
        double cornerRadius;
    }

    /**
     * 인라인 Group의 자식 그래픽에서 배경 사각형의 전체 스타일을 추출.
     * fill, stroke, cornerRadius 등을 포함.
     */
    private static GroupBackground extractGroupBackground(IDMLCharacterRun.InlineGraphic ig,
                                                            ColorResolver colorResolver) {
        if (!"group".equals(ig.type())) return null;
        for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
            if (child.hasVectorShape()) {
                IDMLVectorShape shape = child.vectorShape();
                if (shape.fillColor() != null) {
                    String hex = resolveColorHex(shape.fillColor(), colorResolver);
                    if (hex != null) {
                        GroupBackground bg = new GroupBackground();
                        // 틴트를 색상에 사전 블렌딩 (HWPX alpha 비호환 방지)
                        bg.fillHex = blendColorWithWhite(hex, shape.fillTint() / 100.0);
                        bg.fillTint = 100; // 이미 블렌딩됨
                        String sHex = resolveColorHex(shape.strokeColor(), colorResolver);
                        bg.strokeHex = sHex != null
                                ? blendColorWithWhite(sHex, shape.strokeTint() / 100.0) : null;
                        bg.strokeWeight = shape.strokeWeight();
                        bg.strokeTint = 100;
                        bg.cornerRadius = shape.cornerRadius();
                        return bg;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 색상 hex를 fraction 비율로 흰색과 블렌딩.
     * fraction=1.0 → 원래 색상, fraction=0.0 → 흰색.
     */
    private static String blendColorWithWhite(String hex, double fraction) {
        if (hex == null || !hex.startsWith("#") || hex.length() < 7) return hex;
        try {
            int rgb = Integer.parseInt(hex.substring(1, 7), 16);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            r = (int) Math.round(255 + (r - 255) * fraction);
            g = (int) Math.round(255 + (g - 255) * fraction);
            b = (int) Math.round(255 + (b - 255) * fraction);
            return String.format("#%02X%02X%02X",
                    Math.max(0, Math.min(255, r)),
                    Math.max(0, Math.min(255, g)),
                    Math.max(0, Math.min(255, b)));
        } catch (Exception e) {
            return hex;
        }
    }

    /**
     * ASTTextRun 생성.
     * IDML 스타일 상속을 해결하여 fontFamily/fontSize/fillColor 등을 설정.
     *
     * 해결 순서: 런 직접 속성 → 적용된 CharacterStyle → 적용된 ParagraphStyle (basedOn 체인 포함)
     */
    private static ASTTextRun createTextRun(IDMLCharacterRun run, String text,
                                              IDMLParagraph parentPara,
                                              IDMLDocument idmlDoc,
                                              ColorResolver colorResolver) {
        ASTTextRun textRun = new ASTTextRun();
        textRun.text(text);

        String charStyleRef = run.appliedCharacterStyle();
        if (charStyleRef != null) {
            textRun.characterStyleRef(cleanStyleRef(charStyleRef));
        }

        // 스타일 상속 해결: 런 → CharacterStyle → ParagraphStyle
        String fontFamily = run.fontFamily();
        Double fontSize = run.fontSize();
        String fillColor = run.fillColor();
        String fontStyle = run.fontStyle();
        Double tracking = run.tracking();

        // CharacterStyle에서 빈 속성 채우기
        if (charStyleRef != null) {
            IDMLStyleDef charStyle = resolveStyle(charStyleRef, idmlDoc.charStyles());
            if (charStyle != null) {
                if (fontFamily == null) fontFamily = charStyle.fontFamily();
                if (fontSize == null) fontSize = charStyle.fontSize();
                if (fillColor == null) fillColor = charStyle.fillColor();
                if (fontStyle == null) fontStyle = charStyle.fontStyle();
                if (tracking == null) tracking = charStyle.tracking();
            }
        }

        // ParagraphStyle에서 빈 속성 채우기
        String paraStyleRef = parentPara != null ? parentPara.appliedParagraphStyle() : null;
        if (paraStyleRef != null) {
            IDMLStyleDef paraStyle = resolveStyle(paraStyleRef, idmlDoc.paraStyles());
            if (paraStyle != null) {
                if (fontFamily == null) fontFamily = paraStyle.fontFamily();
                if (fontSize == null) fontSize = paraStyle.fontSize();
                if (fillColor == null) fillColor = paraStyle.fillColor();
                if (fontStyle == null) fontStyle = paraStyle.fontStyle();
                if (tracking == null) tracking = paraStyle.tracking();
            }
        }

        textRun.fontFamily(fontFamily);
        textRun.fontStyle(fontStyle);

        if (fontSize != null) {
            textRun.fontSizeHwpunits((int) (fontSize * 100));
        }

        if (fillColor != null) {
            textRun.textColor(colorResolver.resolve(fillColor));
        }

        if (tracking != null) {
            // IDML tracking: 1/1000 em → HWPX spacing: %
            textRun.letterSpacing((short) Math.round(tracking / 10.0));
        }

        textRun.subscript(run.isSubscript());
        textRun.superscript(run.isSuperscript());
        textRun.grepMathFont(run.grepMathFont());

        return textRun;
    }

    /**
     * 스타일 상속 체인(basedOn)을 따라 속성을 해결한다.
     */
    private static IDMLStyleDef resolveStyle(String styleRef, Map<String, IDMLStyleDef> allStyles) {
        IDMLStyleDef style = findStyle(styleRef, allStyles);
        if (style == null) return null;
        if (style.basedOn() == null || style.basedOn().isEmpty()) return style;

        // 재귀적으로 부모 해결
        IDMLStyleDef parent = resolveStyle(style.basedOn(), allStyles);
        if (parent == null) return style;

        // 병합: 자식 우선, 빈 속성은 부모에서
        IDMLStyleDef merged = new IDMLStyleDef();
        merged.selfRef(style.selfRef());
        merged.name(style.name());
        merged.fontFamily(style.fontFamily() != null ? style.fontFamily() : parent.fontFamily());
        merged.fontSize(style.fontSize() != null ? style.fontSize() : parent.fontSize());
        merged.fillColor(style.fillColor() != null ? style.fillColor() : parent.fillColor());
        merged.fontStyle(style.fontStyle() != null ? style.fontStyle() : parent.fontStyle());
        merged.bold(style.bold() != null ? style.bold() : parent.bold());
        merged.italic(style.italic() != null ? style.italic() : parent.italic());
        merged.tracking(style.tracking() != null ? style.tracking() : parent.tracking());
        merged.leading(style.leading() != null ? style.leading() : parent.leading());
        merged.leadingType(style.leadingType() != null ? style.leadingType() : parent.leadingType());
        merged.autoLeading(style.autoLeading() != null ? style.autoLeading() : parent.autoLeading());
        return merged;
    }

    /**
     * 스타일 맵에서 스타일을 찾는다.
     * IDML의 basedOn 값은 접두사가 없을 수 있으므로 (예: "$ID/[No paragraph style]"),
     * 직접 조회 실패 시 "ParagraphStyle/" 또는 "CharacterStyle/" 접두사를 붙여 재시도.
     */
    private static IDMLStyleDef findStyle(String styleRef, Map<String, IDMLStyleDef> allStyles) {
        if (styleRef == null) return null;
        IDMLStyleDef style = allStyles.get(styleRef);
        if (style != null) return style;

        // 접두사 붙여서 재시도
        for (String prefix : new String[]{"ParagraphStyle/", "CharacterStyle/"}) {
            style = allStyles.get(prefix + styleRef);
            if (style != null) {
                return style;
            }
        }
        return null;
    }

    /**
     * 인라인 텍스트 프레임 → ASTInlineObject(INLINE_TEXT_FRAME) 변환.
     * 인라인 스토리의 단락을 ASTParagraph로 재귀 변환하여 보존.
     */
    private static ASTInlineObject createInlineObjectFromTextFrame(IDMLTextFrame tf,
                                                                     IDMLDocument idmlDoc,
                                                                     ColorResolver colorResolver,
                                                                     ASTImageLoader imageLoader) {
        if (tf.parentStoryId() == null) return null;

        IDMLStory inlineStory = idmlDoc.getStory(tf.parentStoryId());
        if (inlineStory == null) return null;

        // 텍스트 내용이 있는지 확인
        boolean hasContent = false;
        for (IDMLParagraph para : inlineStory.paragraphs()) {
            for (IDMLCharacterRun run : para.characterRuns()) {
                if (run.content() != null && !run.content().trim().isEmpty()) {
                    hasContent = true;
                    break;
                }
            }
            if (hasContent) break;
        }
        if (!hasContent && inlineStory.tables().isEmpty()) return null;

        ASTInlineObject obj = new ASTInlineObject();
        obj.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
        obj.sourceId(tf.selfId());

        double w = IDMLGeometry.transformedWidth(tf.geometricBounds(), tf.itemTransform());
        double h = IDMLGeometry.transformedHeight(tf.geometricBounds(), tf.itemTransform());
        obj.width(CoordinateConverter.pointsToHwpunits(w));
        obj.height(CoordinateConverter.pointsToHwpunits(h));

        // 인라인 스토리의 단락을 ASTParagraph로 변환 (큰 이미지는 별도 단락으로 분리)
        FlattenedObjectPool emptyPool = new FlattenedObjectPool();
        for (IDMLParagraph idmlPara : inlineStory.paragraphs()) {
            ASTParagraph astPara = convertParagraph(idmlPara, emptyPool, idmlDoc, colorResolver, imageLoader, false);
            if (astPara != null && !astPara.items().isEmpty()) {
                for (ASTParagraph split : splitParagraphAtLargeImages(astPara)) {
                    obj.addParagraph(split);
                }
            }
        }

        // 인라인 스토리의 테이블을 ASTTable로 변환
        for (IDMLTable idmlTable : inlineStory.tables()) {
            ASTTable table = convertInlineTable(idmlTable, idmlDoc, colorResolver, imageLoader);
            if (table != null) {
                obj.addInlineTable(table);
            }
        }

        boolean hasParagraphs = obj.paragraphs() != null && !obj.paragraphs().isEmpty();
        boolean hasTables = obj.inlineTables() != null && !obj.inlineTables().isEmpty();
        return (hasParagraphs || hasTables) ? obj : null;
    }

    /**
     * 단락 내 큰 인라인 이미지를 별도 단락으로 분리.
     * 텍스트와 큰 이미지가 같은 단락에 있으면 고정 줄간격으로 인해 겹침이 발생하므로,
     * [Text, LargeImage, Text] → [TextPara, ImagePara, TextPara] 로 분리.
     */
    static List<ASTParagraph> splitParagraphAtLargeImages(ASTParagraph para) {
        List<ASTInlineItem> items = para.items();

        // 큰 이미지가 있는지 + 텍스트가 있는지 확인
        boolean hasLargeImage = false;
        boolean hasText = false;
        for (ASTInlineItem item : items) {
            if (item.itemType() == ASTInlineItem.ItemType.INLINE_OBJECT) {
                ASTInlineObject obj = (ASTInlineObject) item;
                if (isLargeImage(obj)) hasLargeImage = true;
            } else if (item.itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                hasText = true;
            }
        }
        if (!hasLargeImage || !hasText) {
            return Collections.singletonList(para);
        }

        // 큰 이미지를 경계로 분할
        List<ASTParagraph> result = new ArrayList<>();
        List<ASTInlineItem> currentItems = new ArrayList<>();

        for (ASTInlineItem item : items) {
            if (item.itemType() == ASTInlineItem.ItemType.INLINE_OBJECT
                    && isLargeImage((ASTInlineObject) item)) {
                // 축적된 아이템을 단락으로
                if (!currentItems.isEmpty()) {
                    result.add(createSplitParagraph(para, currentItems, result.isEmpty()));
                    currentItems = new ArrayList<>();
                }
                // 큰 이미지를 독립 단락으로
                List<ASTInlineItem> imgItems = new ArrayList<>();
                imgItems.add(item);
                result.add(createSplitParagraph(para, imgItems, result.isEmpty()));
            } else {
                currentItems.add(item);
            }
        }
        // 나머지 아이템
        if (!currentItems.isEmpty()) {
            result.add(createSplitParagraph(para, currentItems, result.isEmpty()));
        }

        // 단락 간격 보존: spaceBefore → 첫 단락만, spaceAfter → 마지막 단락만
        if (result.size() > 1) {
            for (int i = 1; i < result.size(); i++) {
                result.get(i).spaceBefore(0L);
            }
            for (int i = 0; i < result.size() - 1; i++) {
                result.get(i).spaceAfter(0L);
            }
        }

        return result;
    }

    private static boolean isLargeImage(ASTInlineObject obj) {
        if (obj.kind() == ASTInlineObject.ObjectKind.IMAGE)
            return obj.height() > IMAGE_SPLIT_THRESHOLD;
        if (obj.kind() == ASTInlineObject.ObjectKind.RENDERED_GROUP
                && (obj.paragraphs() == null || obj.paragraphs().isEmpty()))
            return obj.height() > IMAGE_SPLIT_THRESHOLD;
        return false;
    }

    /**
     * 원본 단락의 스타일 속성을 복제하여 새 단락 생성.
     * isFirst=true일 때만 firstLineIndent 보존.
     * 이미지 단독 단락은 lineSpacing을 설정하지 않아 자동 확장.
     */
    private static ASTParagraph createSplitParagraph(ASTParagraph source,
                                                      List<ASTInlineItem> items,
                                                      boolean isFirst) {
        ASTParagraph p = new ASTParagraph();
        p.paragraphStyleRef(source.paragraphStyleRef());
        p.alignment(source.alignment());
        p.leftMargin(source.leftMargin());
        p.rightMargin(source.rightMargin());
        p.spaceBefore(source.spaceBefore());
        p.spaceAfter(source.spaceAfter());
        if (isFirst) {
            p.firstLineIndent(source.firstLineIndent());
        }
        // 이미지 단독 단락에는 lineSpacing 미설정 (자동 확장)
        boolean isImageOnly = items.size() == 1
                && items.get(0).itemType() == ASTInlineItem.ItemType.INLINE_OBJECT;
        if (!isImageOnly) {
            p.lineSpacingType(source.lineSpacingType());
            p.lineSpacing(source.lineSpacing());
        }
        p.letterSpacing(source.letterSpacing());
        if (source.tabStops() != null) {
            for (ASTTabStop ts : source.tabStops()) {
                p.addTabStop(ts);
            }
        }
        p.shadingOn(source.shadingOn());
        p.shadingColor(source.shadingColor());
        p.shadingTint(source.shadingTint());

        for (ASTInlineItem item : items) {
            p.addItem(item);
        }
        return p;
    }

    /**
     * 인라인 스토리 내 테이블 → ASTTable 변환 (위치 정보 없이).
     */
    private static ASTTable convertInlineTable(IDMLTable idmlTable,
                                                 IDMLDocument idmlDoc,
                                                 ColorResolver colorResolver,
                                                 ASTImageLoader imageLoader) {
        ASTTable table = new ASTTable();
        table.sourceId(idmlTable.selfId());

        // 컬럼 너비
        for (double cw : idmlTable.columnWidths()) {
            table.addColumnWidth(CoordinateConverter.pointsToHwpunits(cw));
        }
        table.colCount(idmlTable.columnWidths().size());

        // 행 변환
        long totalHeight = 0;
        int rowIdx = 0;
        for (IDMLTableRow idmlRow : idmlTable.rows()) {
            ASTTableRow row = new ASTTableRow();
            row.rowIndex(rowIdx);
            row.rowHeight(CoordinateConverter.pointsToHwpunits(idmlRow.rowHeight()));
            row.autoGrow(idmlRow.autoGrow());
            totalHeight += row.rowHeight();

            for (IDMLTableCell idmlCell : idmlRow.cells()) {
                int colIdx = idmlCell.columnIndex();
                ASTTableCell cell = convertTableCell(idmlCell, rowIdx, colIdx,
                        idmlDoc, colorResolver, imageLoader);
                row.addCell(cell);
            }

            table.addRow(row);
            rowIdx++;
        }
        table.rowCount(rowIdx);

        // 테이블 크기
        long totalWidth = 0;
        for (long cw : table.columnWidths()) {
            totalWidth += cw;
        }
        table.width(totalWidth);
        table.height(totalHeight);

        // 셀 크기 계산
        List<Long> colWidths = table.columnWidths();
        for (ASTTableRow row : table.rows()) {
            for (ASTTableCell cell : row.cells()) {
                long cellWidth = 0;
                int startCol = cell.columnIndex();
                int endCol = Math.min(startCol + cell.columnSpan(), colWidths.size());
                for (int c = startCol; c < endCol; c++) {
                    cellWidth += colWidths.get(c);
                }
                cell.width(cellWidth);

                long cellHeight = 0;
                int startRow = cell.rowIndex();
                int endRow = Math.min(startRow + cell.rowSpan(), table.rows().size());
                for (int r = startRow; r < endRow; r++) {
                    cellHeight += table.rows().get(r).rowHeight();
                }
                cell.height(cellHeight);
            }
        }

        ASTTableSpacerMerger.merge(table);
        return table;
    }

    /**
     * 인라인 그래픽 → ASTInlineObject 변환.
     * 이미지 링크가 있으면 이미지 데이터를 로드한다.
     */
    private static ASTInlineObject createInlineObjectFromGraphic(IDMLCharacterRun.InlineGraphic ig,
                                                                    ASTImageLoader imageLoader,
                                                                    ColorResolver colorResolver) {
        ASTInlineObject obj = new ASTInlineObject();
        obj.sourceId(ig.selfId());

        long w = CoordinateConverter.pointsToHwpunits(ig.widthPoints());
        long h = CoordinateConverter.pointsToHwpunits(ig.heightPoints());
        obj.width(w);
        obj.height(h);

        // 이미지 링크가 있거나, 자식에 이미지가 있으면 IMAGE로 처리
        IDMLCharacterRun.InlineGraphic imageFrame = findImageFrame(ig);
        boolean hasDirectImage = ig.hasImage();
        boolean hasChildImage = imageFrame != null && imageFrame.hasImage();

        if ((hasDirectImage || hasChildImage) && imageLoader != null) {
            obj.kind(ASTInlineObject.ObjectKind.IMAGE);

            // 이미지 표시 크기 계산: 계층의 스케일 팩터를 누적 적용
            IDMLCharacterRun.InlineGraphic sizeFrame = hasChildImage ? imageFrame : ig;
            double imgW = sizeFrame.widthPoints();
            double imgH = sizeFrame.heightPoints();
            double cumulativeScale = computeCumulativeScale(ig, hasChildImage ? imageFrame : null);
            imgW *= cumulativeScale;
            imgH *= cumulativeScale;

            long displayW = CoordinateConverter.pointsToHwpunits(imgW);
            long displayH = CoordinateConverter.pointsToHwpunits(imgH);
            if (displayW > 0 && displayH > 0) {
                obj.width(displayW);
                obj.height(displayH);
            }

            // 프레임 bounds (points) — 클리핑용, 실제 로컬 좌표 사용
            IDMLCharacterRun.InlineGraphic frameForBounds = hasChildImage ? imageFrame : ig;
            double[] frameBounds = frameForBounds.geometricBounds();
            if (frameBounds == null) {
                double frameW = frameForBounds.widthPoints();
                double frameH = frameForBounds.heightPoints();
                if (frameW > 0 && frameH > 0) {
                    frameBounds = new double[]{0, 0, frameH, frameW};
                }
            }

            // 이미지 transform/graphicBounds는 이미지 프레임의 것을 우선 사용
            IDMLCharacterRun.InlineGraphic imgSrc = hasChildImage ? imageFrame : ig;
            double[] imgTransform = imgSrc.imageTransform() != null
                    ? imgSrc.imageTransform() : ig.imageTransform();
            double[] graphicBounds = imgSrc.graphicBounds() != null
                    ? imgSrc.graphicBounds() : ig.graphicBounds();
            String linkURI = imgSrc.linkResourceURI() != null
                    ? imgSrc.linkResourceURI() : ig.linkResourceURI();

            ASTImageLoader.ImageResult result = imageLoader.loadImage(
                    linkURI, displayW, displayH,
                    imgTransform, frameBounds, graphicBounds);

            if (result != null) {
                obj.imageData(result.imageData);
                obj.imageFormat(result.format);
                obj.pixelWidth(result.pixelWidth);
                obj.pixelHeight(result.pixelHeight);
            }
        } else if (ig.hasVectorShape() && imageLoader != null) {
            // 벡터 도형 (글리프 아웃라인 등) → PNG 래스터화
            IDMLVectorShape shape = ig.vectorShape();
            String fillHex = resolveColorHex(shape.fillColor(), colorResolver);
            String strokeHex = resolveColorHex(shape.strokeColor(), colorResolver);

            if (fillHex != null || strokeHex != null) {
                obj.kind(ASTInlineObject.ObjectKind.IMAGE);
                ASTImageLoader.ImageResult result = imageLoader.rasterizeShape(shape, fillHex, strokeHex);
                if (result != null && result.imageData != null) {
                    obj.imageData(result.imageData);
                    obj.imageFormat(result.format);
                    obj.pixelWidth(result.pixelWidth);
                    obj.pixelHeight(result.pixelHeight);
                } else {
                    obj.kind(ASTInlineObject.ObjectKind.RENDERED_GROUP);
                }
            } else {
                obj.kind(ASTInlineObject.ObjectKind.RENDERED_GROUP);
            }
        } else if (imageLoader != null) {
            // 그룹 내 자식 텍스트프레임이 있으면 래스터화하지 않고 텍스트 우선 처리
            // (배경 사각형 + 텍스트프레임 구조는 스타일 적용된 글상자로 변환)
            boolean hasChildTextFrames = hasChildTextFramesRecursive(ig);
            List<ASTImageLoader.ShapeWithColor> childShapes = collectChildVectorShapes(ig, colorResolver);
            if (!childShapes.isEmpty() && !hasChildTextFrames) {
                obj.kind(ASTInlineObject.ObjectKind.IMAGE);
                ASTImageLoader.ImageResult result = imageLoader.rasterizeShapes(childShapes, ig.itemTransform());
                if (result != null && result.imageData != null) {
                    obj.imageData(result.imageData);
                    obj.imageFormat(result.format);
                    obj.pixelWidth(result.pixelWidth);
                    obj.pixelHeight(result.pixelHeight);
                    if (result.pixelWidth > 0 && result.pixelHeight > 0) {
                        obj.width(CoordinateConverter.pointsToHwpunits(result.widthPts));
                        obj.height(CoordinateConverter.pointsToHwpunits(result.heightPts));
                    }
                } else {
                    obj.kind(ASTInlineObject.ObjectKind.RENDERED_GROUP);
                }
            } else {
                obj.kind(ASTInlineObject.ObjectKind.RENDERED_GROUP);
            }
        } else {
            obj.kind(ASTInlineObject.ObjectKind.RENDERED_GROUP);
        }

        // 앵커/래핑 속성 복사 (IDML → AST)
        obj.anchoredPosition(ig.anchoredPosition());
        obj.textWrapMode(ig.textWrapMode());
        obj.textWrapSide(ig.textWrapSide());
        obj.textWrapTop(CoordinateConverter.pointsToHwpunits(ig.textWrapTop()));
        obj.textWrapLeft(CoordinateConverter.pointsToHwpunits(ig.textWrapLeft()));
        obj.textWrapBottom(CoordinateConverter.pointsToHwpunits(ig.textWrapBottom()));
        obj.textWrapRight(CoordinateConverter.pointsToHwpunits(ig.textWrapRight()));

        return obj;
    }

    /**
     * 인라인 그룹의 자식 그래픽에서 벡터 도형(VectorShape)을 재귀적으로 수집한다.
     * 각 도형에 누적 ItemTransform을 부여하여 그룹 루트 좌표계 기준으로 정확한
     * 위치/크기를 계산할 수 있게 한다.
     */
    private static List<ASTImageLoader.ShapeWithColor> collectChildVectorShapes(
            IDMLCharacterRun.InlineGraphic ig, ColorResolver colorResolver) {
        List<ASTImageLoader.ShapeWithColor> result = new ArrayList<>();
        collectChildVectorShapesRecursive(ig, colorResolver, result, null);
        return result;
    }

    private static void collectChildVectorShapesRecursive(
            IDMLCharacterRun.InlineGraphic ig, ColorResolver colorResolver,
            List<ASTImageLoader.ShapeWithColor> result, double[] parentTransform) {
        for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
            // 자식의 ItemTransform을 부모 누적 변환과 결합
            double[] childTransform = child.itemTransform();
            double[] accTransform = combineInlineTransforms(parentTransform, childTransform);

            if (child.hasVectorShape()) {
                IDMLVectorShape shape = child.vectorShape();
                String fillHex = resolveColorHex(shape.fillColor(), colorResolver);
                String strokeHex = resolveColorHex(shape.strokeColor(), colorResolver);
                if (fillHex != null || strokeHex != null) {
                    result.add(new ASTImageLoader.ShapeWithColor(shape, fillHex, strokeHex, accTransform));
                }
            }
            // 재귀적으로 하위 그룹도 수집 (누적 변환 전달)
            collectChildVectorShapesRecursive(child, colorResolver, result, accTransform);
        }
    }

    /**
     * 인라인 그래픽 계층의 누적 변환을 결합한다.
     * parent가 null이면 child만 반환. child가 null이면 parent만 반환.
     * 둘 다 null이면 null 반환 (변환 없음 = 항등 변환).
     */
    private static double[] combineInlineTransforms(double[] parent, double[] child) {
        if (child == null && parent == null) return null;
        if (child == null) return parent;
        if (parent == null) {
            // 항등 변환 여부 확인 — 항등이면 null 반환하여 불필요한 변환 비용 회피
            if (child[0] == 1 && child[1] == 0 && child[2] == 0 && child[3] == 1
                    && child[4] == 0 && child[5] == 0) {
                return null;
            }
            return child;
        }
        return CoordinateConverter.combineTransforms(parent, child);
    }

    /**
     * 인라인 그래픽 계층에서 이미지를 직접 포함하는 자식 프레임을 찾는다.
     * 부모 Group의 linkResourceURI와 일치하는 자식을 우선 선택한다.
     * (collectInlineImageLink가 Image→PDF→EPS 순서로 검색하므로,
     * Group의 URI가 주요 콘텐츠 이미지를 가리킨다.)
     * 일치하는 자식이 없으면 가장 큰 이미지 자식을 선택한다.
     */
    private static IDMLCharacterRun.InlineGraphic findImageFrame(IDMLCharacterRun.InlineGraphic ig) {
        // 1. 부모의 linkResourceURI와 일치하는 자식을 우선 검색
        String parentURI = ig.linkResourceURI();
        if (parentURI != null && !parentURI.isEmpty()) {
            IDMLCharacterRun.InlineGraphic match = findImageFrameByURI(ig, parentURI);
            if (match != null) return match;
        }
        // 2. 일치하는 자식이 없으면 가장 큰 이미지 자식 선택
        return findLargestImageFrame(ig);
    }

    private static IDMLCharacterRun.InlineGraphic findImageFrameByURI(
            IDMLCharacterRun.InlineGraphic ig, String targetURI) {
        for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
            if (child.hasImage() && child.childGraphics().isEmpty()
                    && targetURI.equals(child.linkResourceURI())) {
                return child;
            }
            IDMLCharacterRun.InlineGraphic found = findImageFrameByURI(child, targetURI);
            if (found != null) return found;
        }
        return null;
    }

    private static IDMLCharacterRun.InlineGraphic findLargestImageFrame(IDMLCharacterRun.InlineGraphic ig) {
        IDMLCharacterRun.InlineGraphic best = null;
        double bestArea = 0;
        for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
            if (child.hasImage() && child.childGraphics().isEmpty()) {
                double area = child.widthPoints() * child.heightPoints();
                if (area > bestArea) {
                    bestArea = area;
                    best = child;
                }
            }
            IDMLCharacterRun.InlineGraphic found = findLargestImageFrame(child);
            if (found != null) {
                double area = found.widthPoints() * found.heightPoints();
                if (area > bestArea) {
                    bestArea = area;
                    best = found;
                }
            }
        }
        return best;
    }

    /**
     * 인라인 그래픽 계층에서 루트부터 이미지 프레임까지의 누적 스케일 팩터를 계산한다.
     * 각 계층의 itemTransform[0] (scaleX)을 곱해서 반환한다 (scaleX ≈ scaleY 가정).
     */
    private static double computeCumulativeScale(IDMLCharacterRun.InlineGraphic root,
                                                   IDMLCharacterRun.InlineGraphic target) {
        if (target == null) {
            // target이 없으면 루트 자체의 스케일만
            return extractScale(root);
        }
        // 루트 → target 경로의 스케일을 누적
        double scale = extractScale(root);
        scale *= computeScaleToTarget(root, target);
        return scale;
    }

    private static double computeScaleToTarget(IDMLCharacterRun.InlineGraphic current,
                                                 IDMLCharacterRun.InlineGraphic target) {
        for (IDMLCharacterRun.InlineGraphic child : current.childGraphics()) {
            if (child == target) {
                return extractScale(child);
            }
            if (containsTarget(child, target)) {
                return extractScale(child) * computeScaleToTarget(child, target);
            }
        }
        return 1.0;
    }

    private static boolean containsTarget(IDMLCharacterRun.InlineGraphic node,
                                            IDMLCharacterRun.InlineGraphic target) {
        if (node == target) return true;
        for (IDMLCharacterRun.InlineGraphic child : node.childGraphics()) {
            if (containsTarget(child, target)) return true;
        }
        return false;
    }

    private static double extractScale(IDMLCharacterRun.InlineGraphic ig) {
        if (ig.itemTransform() == null) return 1.0;
        double scaleX = Math.abs(ig.itemTransform()[0]);
        return (scaleX > 0.01 && scaleX != 1.0) ? scaleX : 1.0;
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
            ASTTable table = convertTable(idmlTable, tf, page, zOrder, idmlDoc, colorResolver, imageLoader);
            if (table != null) {
                section.addBlock(table);
            }
        }
    }

    /**
     * IDMLTable → ASTTable 변환.
     */
    private static ASTTable convertTable(IDMLTable idmlTable, IDMLTextFrame tf,
                                           IDMLPage page, int zOrder,
                                           IDMLDocument idmlDoc, ColorResolver colorResolver,
                                           ASTImageLoader imageLoader) {
        ASTTable table = new ASTTable();
        table.sourceId(idmlTable.selfId());
        table.zOrder(zOrder);

        // 테이블 위치 (텍스트 프레임 기준)
        double[] relPos = IDMLGeometry.pageRelativePosition(
                tf.geometricBounds(), tf.itemTransform(),
                page.geometricBounds(), page.itemTransform());
        table.x(CoordinateConverter.pointsToHwpunits(relPos[0]));
        table.y(CoordinateConverter.pointsToHwpunits(relPos[1]));

        // 컬럼 너비
        for (double cw : idmlTable.columnWidths()) {
            table.addColumnWidth(CoordinateConverter.pointsToHwpunits(cw));
        }
        table.colCount(idmlTable.columnWidths().size());

        // 행 변환
        long totalHeight = 0;
        int rowIdx = 0;
        for (IDMLTableRow idmlRow : idmlTable.rows()) {
            ASTTableRow row = new ASTTableRow();
            row.rowIndex(rowIdx);
            row.rowHeight(CoordinateConverter.pointsToHwpunits(idmlRow.rowHeight()));
            row.autoGrow(idmlRow.autoGrow());
            totalHeight += row.rowHeight();

            // 셀 변환
            for (IDMLTableCell idmlCell : idmlRow.cells()) {
                int colIdx = idmlCell.columnIndex();
                ASTTableCell cell = convertTableCell(idmlCell, rowIdx, colIdx,
                        idmlDoc, colorResolver, imageLoader);
                row.addCell(cell);
            }

            table.addRow(row);
            rowIdx++;
        }
        table.rowCount(rowIdx);

        // 테이블 크기
        long totalWidth = 0;
        for (long cw : table.columnWidths()) {
            totalWidth += cw;
        }
        table.width(totalWidth);
        table.height(totalHeight);

        // 셀 크기 계산 (columnWidths + rowHeights 기반)
        List<Long> colWidths = table.columnWidths();
        for (ASTTableRow row : table.rows()) {
            for (ASTTableCell cell : row.cells()) {
                // 셀 너비 = 시작 컬럼부터 colSpan만큼 합산
                long cellWidth = 0;
                int startCol = cell.columnIndex();
                int endCol = Math.min(startCol + cell.columnSpan(), colWidths.size());
                for (int c = startCol; c < endCol; c++) {
                    cellWidth += colWidths.get(c);
                }
                cell.width(cellWidth);

                // 셀 높이 = 시작 행부터 rowSpan만큼 합산
                long cellHeight = 0;
                int startRow = cell.rowIndex();
                int endRow = Math.min(startRow + cell.rowSpan(), table.rows().size());
                for (int r = startRow; r < endRow; r++) {
                    cellHeight += table.rows().get(r).rowHeight();
                }
                cell.height(cellHeight);
            }
        }

        ASTTableSpacerMerger.merge(table);
        return table;
    }

    /**
     * IDMLTableCell → ASTTableCell 변환 (미니 문서).
     */
    private static ASTTableCell convertTableCell(IDMLTableCell idmlCell,
                                                   int rowIdx, int colIdx,
                                                   IDMLDocument idmlDoc,
                                                   ColorResolver colorResolver,
                                                   ASTImageLoader imageLoader) {
        ASTTableCell cell = new ASTTableCell();
        cell.rowIndex(rowIdx);
        cell.columnIndex(colIdx);
        cell.rowSpan(idmlCell.rowSpan());
        cell.columnSpan(idmlCell.columnSpan());

        // 셀 스타일
        if (idmlCell.fillColor() != null) {
            cell.fillColor(colorResolver.resolve(idmlCell.fillColor()));
        }
        cell.verticalAlign(idmlCell.verticalJustification());

        // 셀 여백
        cell.marginTop(CoordinateConverter.pointsToHwpunits(idmlCell.topInset()));
        cell.marginBottom(CoordinateConverter.pointsToHwpunits(idmlCell.bottomInset()));
        cell.marginLeft(CoordinateConverter.pointsToHwpunits(idmlCell.leftInset()));
        cell.marginRight(CoordinateConverter.pointsToHwpunits(idmlCell.rightInset()));

        // 셀 테두리 (IDMLTableCell.CellBorder → ASTTableCell.CellBorder)
        cell.topBorder(convertCellBorder(idmlCell.topBorder(), colorResolver));
        cell.bottomBorder(convertCellBorder(idmlCell.bottomBorder(), colorResolver));
        cell.leftBorder(convertCellBorder(idmlCell.leftBorder(), colorResolver));
        cell.rightBorder(convertCellBorder(idmlCell.rightBorder(), colorResolver));

        // 대각선
        cell.topLeftDiagonalLine(idmlCell.topLeftDiagonalLine());
        cell.topRightDiagonalLine(idmlCell.topRightDiagonalLine());

        // 셀 내용 → 미니 문서 (재귀)
        FlattenedObjectPool emptyPool = new FlattenedObjectPool(); // 셀 내 인라인은 별도 처리
        for (IDMLParagraph cellPara : idmlCell.paragraphs()) {
            ASTParagraph astPara = convertParagraph(cellPara, emptyPool, idmlDoc, colorResolver, imageLoader, false);
            if (astPara != null) {
                cell.addParagraph(astPara);
            }
        }

        // 마지막 빈 단락 제거
        removeTrailingEmptyParagraphs(cell.paragraphs());

        return cell;
    }

    /**
     * IDMLTableCell.CellBorder → ASTTableCell.CellBorder 변환.
     */
    private static ASTTableCell.CellBorder convertCellBorder(IDMLTableCell.CellBorder src,
                                                               ColorResolver colorResolver) {
        if (src == null || src.strokeWeight <= 0) return null;
        ASTTableCell.CellBorder border = new ASTTableCell.CellBorder();
        border.weight(src.strokeWeight);
        border.strokeType(src.strokeType);
        border.tint(src.strokeTint);
        if (src.strokeColor != null) {
            border.color(colorResolver.resolve(src.strokeColor));
        }
        return border;
    }

    /**
     * 스타일 참조에서 "ParagraphStyle/" 또는 "CharacterStyle/" 접두사 제거.
     */
    private static String cleanStyleRef(String ref) {
        if (ref == null) return null;
        if (ref.startsWith("ParagraphStyle/")) {
            return ref.substring("ParagraphStyle/".length());
        }
        if (ref.startsWith("CharacterStyle/")) {
            return ref.substring("CharacterStyle/".length());
        }
        return ref;
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
     * IDMLImageFrame → ASTFigure 변환 (플로팅 이미지).
     */
    private static ASTFigure createFigureFromImageFrame(IDMLImageFrame imgFrame,
                                                          IDMLPage page,
                                                          ASTImageLoader imageLoader) {
        double[] t = imgFrame.itemTransform();
        boolean hasRotOrFlip = t != null && (Math.abs(t[1]) > 0.001 || Math.abs(t[2]) > 0.001);

        long wHwp, hHwp, xHwp, yHwp;

        if (hasRotOrFlip) {
            // === 회전/반전 사전 렌더링 경로 (벡터 도형과 동일) ===
            double w = IDMLGeometry.transformedWidth(imgFrame.geometricBounds(), imgFrame.itemTransform());
            double h = IDMLGeometry.transformedHeight(imgFrame.geometricBounds(), imgFrame.itemTransform());
            wHwp = CoordinateConverter.pointsToHwpunits(w);
            hHwp = CoordinateConverter.pointsToHwpunits(h);

            double[] bbox = IDMLGeometry.getTransformedBoundingBox(
                    imgFrame.geometricBounds(), imgFrame.itemTransform());
            double[] pageAbs = IDMLGeometry.absoluteTopLeft(
                    page.geometricBounds(), page.itemTransform());
            xHwp = CoordinateConverter.pointsToHwpunits(bbox[0] - pageAbs[0]);
            yHwp = CoordinateConverter.pointsToHwpunits(bbox[1] - pageAbs[1]);
        } else {
            // === 기존 비회전 경로 ===
            double w = IDMLGeometry.scaledWidth(imgFrame.geometricBounds(), imgFrame.itemTransform());
            double h = IDMLGeometry.scaledHeight(imgFrame.geometricBounds(), imgFrame.itemTransform());

            double[] relCenter = IDMLGeometry.pageRelativeCenter(
                    imgFrame.geometricBounds(), imgFrame.itemTransform(),
                    page.geometricBounds(), page.itemTransform());

            wHwp = CoordinateConverter.pointsToHwpunits(w);
            hHwp = CoordinateConverter.pointsToHwpunits(h);
            xHwp = CoordinateConverter.pointsToHwpunits(relCenter[0]) - wHwp / 2;
            yHwp = CoordinateConverter.pointsToHwpunits(relCenter[1]) - hHwp / 2;
        }

        if (wHwp <= 0 || hHwp <= 0) return null;

        // 프레임 bounds (points) — 클리핑용
        double[] frameBounds = imgFrame.geometricBounds();

        ASTImageLoader.ImageResult result = imageLoader.loadImage(
                imgFrame.linkResourceURI(), wHwp, hHwp,
                imgFrame.imageTransform(), frameBounds, imgFrame.graphicBounds());

        if (result == null || result.imageData == null) return null;

        // 회전/반전이 있으면 이미지를 픽셀 레벨에서 회전
        if (hasRotOrFlip) {
            ASTImageLoader.ImageResult rotated =
                    ASTImageLoader.preRenderRotation(result.imageData, imgFrame.itemTransform());
            if (rotated != null) {
                result = rotated;
            }
        }

        ASTFigure figure = new ASTFigure();
        figure.kind(ASTFigure.FigureKind.IMAGE);
        figure.x(xHwp);
        figure.y(yHwp);
        figure.width(wHwp);
        figure.height(hHwp);
        figure.zOrder(imgFrame.zOrder());
        figure.imageData(result.imageData);
        figure.imageFormat(result.format);
        figure.pixelWidth(result.pixelWidth);
        figure.pixelHeight(result.pixelHeight);
        figure.imagePath(imgFrame.linkResourceURI());

        if (!hasRotOrFlip) {
            // 비회전 경로에서만 기존 flip 처리
            // 프레임과 이미지 양쪽 flip을 XOR: 둘 다 flip이면 상쇄
            boolean frameFlip = IDMLGeometry.hasFlip(imgFrame.itemTransform());
            boolean imageFlip = IDMLGeometry.hasFlip(imgFrame.imageTransform());
            boolean netFlip = frameFlip ^ imageFlip;
            if (netFlip) {
                double rotation = IDMLGeometry.extractRotation(imgFrame.itemTransform());
                if (Math.abs(Math.abs(rotation) - 180) < 0.5) {
                    result.imageData = ASTImageLoader.flipVertically(result.imageData);
                    figure.imageData(result.imageData);
                } else if (Math.abs(rotation) < 0.5) {
                    result.imageData = ASTImageLoader.flipHorizontally(result.imageData);
                    figure.imageData(result.imageData);
                }
            }
        }
        // hasRotOrFlip: 회전/반전이 preRenderRotation에서 이미 처리됨

        // 페이지 경계를 벗어나는 이미지의 크롭 비율 계산 (스프레드 걸침 이미지 처리)
        // bleed(인쇄 여백 넘침, ~1%)는 무시하고, 실제 페이지 걸침(>5%)만 크롭
        long pageW = CoordinateConverter.pointsToHwpunits(
                IDMLGeometry.width(page.geometricBounds()));
        long pageH = CoordinateConverter.pointsToHwpunits(
                IDMLGeometry.height(page.geometricBounds()));
        double minCropThreshold = 0.05;  // 5% 미만은 bleed로 간주, 크롭하지 않음

        if (xHwp < 0) {
            double frac = (double) -xHwp / wHwp;
            if (frac > minCropThreshold) figure.cropLeftFraction(frac);
        }
        if (yHwp < 0) {
            double frac = (double) -yHwp / hHwp;
            if (frac > minCropThreshold) figure.cropTopFraction(frac);
        }
        if (xHwp + wHwp > pageW) {
            double frac = (double) (xHwp + wHwp - pageW) / wHwp;
            if (frac > minCropThreshold) figure.cropRightFraction(frac);
        }
        if (yHwp + hHwp > pageH) {
            double frac = (double) (yHwp + hHwp - pageH) / hHwp;
            if (frac > minCropThreshold) figure.cropBottomFraction(frac);
        }

        return figure;
    }

    /**
     * IDMLVectorShape → ASTFigure 변환 (플로팅 벡터 도형 → PNG 래스터화).
     */
    private static ASTFigure createFigureFromVectorShape(IDMLVectorShape shape,
                                                          IDMLPage page,
                                                          ASTImageLoader imageLoader,
                                                          ColorResolver colorResolver) {
        // === 복수 클리핑 자식 (clippedChildren) 처리 ===
        if (shape.hasClippedChildren()) {
            return createFigureFromClippedGroup(shape, page, imageLoader, colorResolver);
        }

        // 채우기/선 색상 해석
        IDMLVectorShape renderTarget = shape.hasClippedChild() ? shape.clippedChild() : shape;
        String fillHex = resolveColorHex(renderTarget.fillColor(), colorResolver);
        String strokeHex = resolveColorHex(renderTarget.strokeColor(), colorResolver);

        // 보이지 않는 도형 스킵
        if (fillHex == null && strokeHex == null) return null;

        double[] effectiveBounds = shape.geometricBounds();
        double[] t = shape.itemTransform();
        boolean hasRotOrFlip = t != null && (Math.abs(t[1]) > 0.001 || Math.abs(t[2]) > 0.001);

        long wHwp, hHwp, xHwp, yHwp;
        ASTImageLoader.ImageResult result;

        if (hasRotOrFlip) {
            // === 회전/반전 사전 렌더링 경로 ===
            double w = IDMLGeometry.transformedWidth(effectiveBounds, shape.itemTransform());
            double h = IDMLGeometry.transformedHeight(effectiveBounds, shape.itemTransform());
            wHwp = CoordinateConverter.pointsToHwpunits(w);
            hHwp = CoordinateConverter.pointsToHwpunits(h);

            double[] bbox = IDMLGeometry.getTransformedBoundingBox(
                    effectiveBounds, shape.itemTransform());
            double[] pageAbs = IDMLGeometry.absoluteTopLeft(
                    page.geometricBounds(), page.itemTransform());
            xHwp = CoordinateConverter.pointsToHwpunits(bbox[0] - pageAbs[0]);
            yHwp = CoordinateConverter.pointsToHwpunits(bbox[1] - pageAbs[1]);

            // 선 도형(GraphicLine 등)은 한 축이 0일 수 있음: stroke weight를 최소 크기로 사용
            if (wHwp <= 0 || hHwp <= 0) {
                if (shape.hasStroke() && shape.strokeWeight() > 0) {
                    long minDim = CoordinateConverter.pointsToHwpunits(shape.strokeWeight());
                    if (minDim < 100) minDim = 100;
                    if (wHwp <= 0) wHwp = minDim;
                    if (hHwp <= 0) hHwp = minDim;
                } else {
                    return null;
                }
            }

            // 페이지보다 훨씬 큰 도형은 스프레드 배경이므로 스킵
            long pageW = CoordinateConverter.pointsToHwpunits(IDMLGeometry.width(page.geometricBounds()));
            long pageH = CoordinateConverter.pointsToHwpunits(IDMLGeometry.height(page.geometricBounds()));
            if (wHwp > pageW * 2 || hHwp > pageH * 2) return null;

            // 회전 사전 렌더링된 래스터
            result = imageLoader.rasterizeShape(shape, fillHex, strokeHex, shape.itemTransform());
        } else {
            // === 기존 비회전 경로 ===
            double w = IDMLGeometry.scaledWidth(effectiveBounds, shape.itemTransform());
            double h = IDMLGeometry.scaledHeight(effectiveBounds, shape.itemTransform());

            double[] relCenter = IDMLGeometry.pageRelativeCenter(
                    effectiveBounds, shape.itemTransform(),
                    page.geometricBounds(), page.itemTransform());

            wHwp = CoordinateConverter.pointsToHwpunits(w);
            hHwp = CoordinateConverter.pointsToHwpunits(h);
            xHwp = CoordinateConverter.pointsToHwpunits(relCenter[0]) - wHwp / 2;
            yHwp = CoordinateConverter.pointsToHwpunits(relCenter[1]) - hHwp / 2;

            if (wHwp <= 0 || hHwp <= 0) {
                if (shape.hasStroke() && shape.strokeWeight() > 0) {
                    long minDim = CoordinateConverter.pointsToHwpunits(shape.strokeWeight());
                    if (minDim < 100) minDim = 100;
                    if (wHwp <= 0) wHwp = minDim;
                    if (hHwp <= 0) hHwp = minDim;
                    xHwp = CoordinateConverter.pointsToHwpunits(relCenter[0]) - wHwp / 2;
                    yHwp = CoordinateConverter.pointsToHwpunits(relCenter[1]) - hHwp / 2;
                } else {
                    return null;
                }
            }

            // 페이지보다 훨씬 큰 도형은 스프레드 배경이므로 스킵
            long pageW = CoordinateConverter.pointsToHwpunits(IDMLGeometry.width(page.geometricBounds()));
            long pageH = CoordinateConverter.pointsToHwpunits(IDMLGeometry.height(page.geometricBounds()));
            if (wHwp > pageW * 2 || hHwp > pageH * 2) return null;

            result = imageLoader.rasterizeShape(shape, fillHex, strokeHex);
        }

        if (result == null || result.imageData == null) return null;

        ASTFigure figure = new ASTFigure();
        figure.kind(ASTFigure.FigureKind.RENDERED_SHAPE);
        figure.x(xHwp);
        figure.y(yHwp);
        figure.width(wHwp);
        figure.height(hHwp);
        figure.zOrder(shape.zOrder());
        figure.imageData(result.imageData);
        figure.imageFormat(result.format);
        figure.pixelWidth(result.pixelWidth);
        figure.pixelHeight(result.pixelHeight);

        if (!hasRotOrFlip) {
            // 비회전 경로에서만 HWPX rotation/flip 처리
            if (IDMLGeometry.hasFlip(shape.itemTransform())) {
                double rotation = IDMLGeometry.extractRotation(shape.itemTransform());
                if (Math.abs(Math.abs(rotation) - 180) < 0.5) {
                    result.imageData = ASTImageLoader.flipVertically(result.imageData);
                    figure.imageData(result.imageData);
                } else if (Math.abs(rotation) < 0.5) {
                    result.imageData = ASTImageLoader.flipHorizontally(result.imageData);
                    figure.imageData(result.imageData);
                } else {
                    result.imageData = ASTImageLoader.flipHorizontally(result.imageData);
                    figure.imageData(result.imageData);
                    figure.rotationAngle(rotation);
                }
            } else {
                double rotation = IDMLGeometry.extractRotation(shape.itemTransform());
                if (Math.abs(rotation) > 0.1) {
                    figure.rotationAngle(rotation);
                }
            }
        }
        // hasRotOrFlip인 경우: 회전/반전이 이미 래스터에 포함됨 → rotationAngle 기본값 0

        return figure;
    }

    /**
     * 클리핑 프레임 + 복수 자식 도형을 합성 래스터화하여 ASTFigure로 변환.
     * 유효 영역(자식 바운딩 박스 ∩ 클리핑 프레임)만 렌더링하고 배치한다.
     */
    private static ASTFigure createFigureFromClippedGroup(IDMLVectorShape clipFrame,
                                                           IDMLPage page,
                                                           ASTImageLoader imageLoader,
                                                           ColorResolver colorResolver) {
        // 자식 중 하나라도 보이는 색상이 있는지 확인
        boolean anyVisible = false;
        for (IDMLVectorShape child : clipFrame.clippedChildren()) {
            String fh = resolveColorHex(child.fillColor(), colorResolver);
            String sh = resolveColorHex(child.strokeColor(), colorResolver);
            if (fh != null || sh != null) { anyVisible = true; break; }
        }
        if (!anyVisible) return null;

        double[] clipBounds = clipFrame.geometricBounds(); // [top, left, bottom, right]
        double clipTop = clipBounds[0], clipLeft = clipBounds[1];
        double clipBottom = clipBounds[2], clipRight = clipBounds[3];

        // 자식들의 바운딩 박스를 클리핑 프레임 로컬 좌표계에서 계산
        double uLeft = Double.MAX_VALUE, uTop = Double.MAX_VALUE;
        double uRight = -Double.MAX_VALUE, uBottom = -Double.MAX_VALUE;
        for (IDMLVectorShape child : clipFrame.clippedChildren()) {
            double[] ct = child.itemTransform();
            double[] cb = child.geometricBounds();
            // 자식 bounds의 4개 꼭짓점을 클리핑 프레임 공간으로 변환
            double[][] corners = {{cb[1], cb[0]}, {cb[3], cb[0]}, {cb[1], cb[2]}, {cb[3], cb[2]}};
            for (double[] c : corners) {
                double tx = ct[0] * c[0] + ct[2] * c[1] + ct[4];
                double ty = ct[1] * c[0] + ct[3] * c[1] + ct[5];
                uLeft = Math.min(uLeft, tx);
                uTop = Math.min(uTop, ty);
                uRight = Math.max(uRight, tx);
                uBottom = Math.max(uBottom, ty);
            }
        }

        // 클리핑 프레임과 교차 → 유효 영역
        double eLeft = Math.max(uLeft, clipLeft);
        double eTop = Math.max(uTop, clipTop);
        double eRight = Math.min(uRight, clipRight);
        double eBottom = Math.min(uBottom, clipBottom);

        if (eRight <= eLeft || eBottom <= eTop) return null;

        double effectiveW = eRight - eLeft;
        double effectiveH = eBottom - eTop;
        double effectiveCX = (eLeft + eRight) / 2.0;
        double effectiveCY = (eTop + eBottom) / 2.0;

        // 유효 영역 중심을 절대 좌표로 변환
        double[] t = clipFrame.itemTransform();
        double[] absCenter = CoordinateConverter.applyTransform(t, effectiveCX, effectiveCY);
        double[] pageAbs = IDMLGeometry.absoluteTopLeft(
                page.geometricBounds(), page.itemTransform());

        long wHwp = CoordinateConverter.pointsToHwpunits(effectiveW);
        long hHwp = CoordinateConverter.pointsToHwpunits(effectiveH);
        long xHwp = CoordinateConverter.pointsToHwpunits(absCenter[0] - pageAbs[0]) - wHwp / 2;
        long yHwp = CoordinateConverter.pointsToHwpunits(absCenter[1] - pageAbs[1]) - hHwp / 2;

        if (wHwp <= 0 || hHwp <= 0) return null;

        // 페이지보다 훨씬 큰 도형은 스프레드 배경이므로 스킵
        long pageW = CoordinateConverter.pointsToHwpunits(IDMLGeometry.width(page.geometricBounds()));
        long pageH = CoordinateConverter.pointsToHwpunits(IDMLGeometry.height(page.geometricBounds()));
        if (wHwp > pageW * 2 || hHwp > pageH * 2) return null;

        // 유효 영역만 합성 래스터화
        ASTImageLoader.ImageResult result = imageLoader.rasterizeClippedGroup(
                clipFrame, colorResolver, eLeft, eTop, effectiveW, effectiveH);
        if (result == null || result.imageData == null) return null;

        ASTFigure figure = new ASTFigure();
        figure.kind(ASTFigure.FigureKind.RENDERED_SHAPE);
        figure.x(xHwp);
        figure.y(yHwp);
        figure.width(wHwp);
        figure.height(hHwp);
        figure.zOrder(clipFrame.zOrder());
        figure.imageData(result.imageData);
        figure.imageFormat(result.format);
        figure.pixelWidth(result.pixelWidth);
        figure.pixelHeight(result.pixelHeight);

        return figure;
    }

    private static String resolveColorHex(String colorRef, ColorResolver colorResolver) {
        if (colorRef == null || "None".equals(colorRef) || colorRef.contains("[None]")) return null;
        String hex = colorResolver.resolve(colorRef);
        return (hex != null && !hex.isEmpty()) ? hex : null;
    }

    /**
     * 이미지 프레임 중복 제거 키 생성.
     * 같은 이미지 URI + 같은 위치(tx, ty) + 같은 프레임 크기인 경우 같은 키를 반환.
     * PSD 레이어 가시성이 다른 중복 배치를 하나로 합치기 위함.
     */
    private static String buildImageFrameDedupKey(IDMLImageFrame frame) {
        String uri = frame.linkResourceURI();
        if (uri == null || uri.isEmpty()) return null;

        double[] t = frame.itemTransform();
        double[] b = frame.geometricBounds();
        if (t == null || b == null) return null;

        // 위치(tx, ty)와 프레임 크기를 0.1pt 정밀도로 반올림하여 키 생성
        long tx = Math.round(t[4] * 10);
        long ty = Math.round(t[5] * 10);
        long w = Math.round((b[3] - b[1]) * 10);
        long h = Math.round((b[2] - b[0]) * 10);

        return uri + "|" + tx + "," + ty + "|" + w + "x" + h;
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
    private static void removeTrailingEmptyParagraphs(List<ASTParagraph> paragraphs) {
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
            } else if (item.itemType() == ASTInlineItem.ItemType.INLINE_OBJECT) {
                return false;
            }
            // BREAK만 있는 단락도 빈 단락으로 취급
        }
        return true;
    }
}
