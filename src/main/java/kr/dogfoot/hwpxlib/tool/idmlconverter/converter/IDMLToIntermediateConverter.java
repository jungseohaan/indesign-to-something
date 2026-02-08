package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.tool.equationconverter.idml.IDMLEquationExtractor;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertException;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertOptions;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.idml.ImageFrameConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.idml.TextFrameConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.idml.VectorShapeConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;
import kr.dogfoot.hwpxlib.tool.imageinserter.DesignFileConverter;
import kr.dogfoot.hwpxlib.tool.imageinserter.ImageInserter;
import kr.dogfoot.hwpxlib.tool.textfit.TextFitter;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.util.*;
import java.util.Base64;
import javax.imageio.ImageIO;

/**
 * IDML 문서(IDMLDocument)를 중간 포맷(IntermediateDocument)으로 변환한다.
 *
 * 변환 순서:
 * 1. 페이지 필터링 (ConvertOptions의 startPage/endPage)
 * 2. 폰트 매핑 (FontMapper)
 * 3. 스타일 매핑 (StyleMapper)
 * 4. 페이지별 프레임 → IntermediatePage/IntermediateFrame 변환
 * 5. Story 내용 → IntermediateParagraph/IntermediateTextRun 변환
 * 6. 수식 추출 (IDMLEquationExtractor)
 * 7. 이미지 데이터 읽기
 */
public class IDMLToIntermediateConverter {

    /**
     * 변환 결과 (IntermediateDocument + 경고 목록).
     */
    public static class Result {
        private final IntermediateDocument document;
        private final List<String> warnings;

        Result(IntermediateDocument document, List<String> warnings) {
            this.document = document;
            this.warnings = warnings;
        }

        public IntermediateDocument document() { return document; }
        public List<String> warnings() { return warnings; }
    }

    /**
     * IDMLDocument를 IntermediateDocument로 변환한다.
     */
    public static Result convert(IDMLDocument idmlDoc,
                                  ConvertOptions options,
                                  String sourceFileName) throws ConvertException {
        try {
            IDMLToIntermediateConverter converter =
                    new IDMLToIntermediateConverter(idmlDoc, options, sourceFileName);
            IntermediateDocument doc = converter.doConvert();
            return new Result(doc, converter.warnings);
        } catch (ConvertException ce) {
            throw ce;
        } catch (Exception e) {
            throw new ConvertException(ConvertException.Phase.PARSING,
                    "Failed to convert IDML to intermediate format: " + e.getMessage(), e);
        }
    }

    private final IDMLDocument idmlDoc;
    private final ConvertOptions options;
    private final String sourceFileName;
    private final PageFilter pageFilter;
    private final Map<String, String> paraStyleRefToId;
    private final Map<String, String> charStyleRefToId;
    private final List<String> warnings;
    // 색상 참조 해석기
    private final ColorResolver colorResolver;
    // 벡터 도형 변환기
    private final VectorShapeConverter vectorShapeConverter;
    // 이미지 프레임 변환기
    private final ImageFrameConverter imageFrameConverter;
    // 텍스트 프레임 변환기
    private final TextFrameConverter textFrameConverter;

    private IDMLToIntermediateConverter(IDMLDocument idmlDoc, ConvertOptions options,
                                        String sourceFileName) {
        this.idmlDoc = idmlDoc;
        this.options = options;
        this.sourceFileName = sourceFileName;
        this.pageFilter = new PageFilter(options);
        this.paraStyleRefToId = new LinkedHashMap<String, String>();
        this.charStyleRefToId = new LinkedHashMap<String, String>();
        this.warnings = new ArrayList<String>();
        this.colorResolver = new ColorResolver(idmlDoc);
        this.vectorShapeConverter = new VectorShapeConverter(colorResolver);
        this.imageFrameConverter = new ImageFrameConverter(idmlDoc, options, warnings);
        this.textFrameConverter = new TextFrameConverter(idmlDoc, paraStyleRefToId,
                charStyleRefToId, colorResolver, warnings);
    }

    private IntermediateDocument doConvert() throws ConvertException {
        IntermediateDocument doc = new IntermediateDocument();
        doc.version("1.0");
        doc.sourceFormat("IDML");
        doc.sourceFile(sourceFileName);

        // 1. 폰트 매핑
        List<IntermediateFontDef> fonts = FontMapper.buildFontList(idmlDoc.fonts());
        for (IntermediateFontDef font : fonts) {
            doc.addFont(font);
        }

        // 2. 스타일 매핑
        if (options.includeStyles()) {
            List<IntermediateStyleDef> paraStyles = new ArrayList<IntermediateStyleDef>();
            Map<String, String> pRefMap = StyleMapper.buildParagraphStyleMap(
                    idmlDoc.paraStyles(), idmlDoc.colors(), paraStyles);
            paraStyleRefToId.putAll(pRefMap);
            for (IntermediateStyleDef style : paraStyles) {
                doc.addParagraphStyle(style);
            }

            List<IntermediateStyleDef> charStyles = new ArrayList<IntermediateStyleDef>();
            Map<String, String> cRefMap = StyleMapper.buildCharacterStyleMap(
                    idmlDoc.charStyles(), idmlDoc.colors(), charStyles);
            charStyleRefToId.putAll(cRefMap);
            for (IntermediateStyleDef style : charStyles) {
                doc.addCharacterStyle(style);
            }
        }

        // 3. 레이아웃 (첫 페이지 기준)
        DocumentLayout layout = buildLayout();
        doc.layout(layout);

        // 4. 수식 추출기 준비
        IDMLEquationExtractor equationExtractor = null;
        if (options.includeEquations() && idmlDoc.basePath() != null) {
            File storiesDir = new File(idmlDoc.basePath(), "Stories");
            if (storiesDir.isDirectory()) {
                equationExtractor = new IDMLEquationExtractor(storiesDir.getAbsolutePath());
            }
        }

        // 5. 스프레드/페이지 변환
        Set<String> processedStories = new HashSet<String>();
        int zOrderCounter = 0;

        if (options.spreadBasedConversion()) {
            // === 스프레드 모드: 각 스프레드를 하나의 큰 페이지로 변환 ===
            System.err.println("[DEBUG] Spread mode enabled, converting spreads...");
            doc.useSpreadMode(true);
            convertSpreads(doc, processedStories);
            System.err.println("[DEBUG] After convertSpreads: spreads count = " + doc.spreads().size());
            return doc;
        }

        // === 페이지 모드: 각 페이지를 개별적으로 변환 ===
        int spreadIndex = 0;
        for (IDMLSpread spread : idmlDoc.spreads()) {
            spreadIndex++;
            for (IDMLPage page : spread.pages()) {
                if (!pageFilter.shouldInclude(page.pageNumber())) {
                    continue;
                }

                System.err.println("[INFO] 스프레드 " + spreadIndex + " / 페이지 " + page.pageNumber() + " 변환 중...");

                IntermediatePage iPage = new IntermediatePage();
                iPage.pageNumber(page.pageNumber());
                iPage.pageWidth(page.widthHwpunits());
                iPage.pageHeight(page.heightHwpunits());

                // 페이지 마진 설정 (IDML points → HWPUNIT)
                iPage.marginTop(CoordinateConverter.pointsToHwpunits(page.marginTop()));
                iPage.marginBottom(CoordinateConverter.pointsToHwpunits(page.marginBottom()));
                iPage.marginLeft(CoordinateConverter.pointsToHwpunits(page.marginLeft()));
                iPage.marginRight(CoordinateConverter.pointsToHwpunits(page.marginRight()));

                // 텍스트 프레임 수집 및 정렬 (위→아래, 왼→오른)
                List<IDMLTextFrame> textFrames = spread.getTextFramesOnPage(page);

                // 모든 이미지와 벡터 그래픽을 페이지 크기의 배경 PNG로 렌더링
                if (options.includeImages()) {
                    try {
                        IDMLPageRenderer renderer = new IDMLPageRenderer(idmlDoc, options.vectorDpi());
                        byte[] pageBackground = renderer.renderPage(spread, page, options.linksDirectory(),
                                options.drawPageBoundary());

                        if (pageBackground != null && pageBackground.length > 0) {
                            // 페이지 크기 (픽셀)
                            double scale = options.vectorDpi() / 72.0;
                            int pixelWidth = (int) Math.ceil(page.widthPoints() * scale);
                            int pixelHeight = (int) Math.ceil(page.heightPoints() * scale);

                            // 배경 이미지 프레임 생성
                            IntermediateFrame bgFrame = new IntermediateFrame();
                            bgFrame.frameId("page_background_" + page.pageNumber());
                            bgFrame.frameType("image");
                            bgFrame.x(0);
                            bgFrame.y(0);
                            bgFrame.width(page.widthHwpunits());
                            bgFrame.height(page.heightHwpunits());
                            bgFrame.zOrder(0);  // 가장 낮은 z-order (배경)
                            bgFrame.isBackgroundImage(true);

                            IntermediateImage bgImage = new IntermediateImage();
                            bgImage.imageId("page_bg_" + page.pageNumber());
                            bgImage.format("png");
                            bgImage.base64Data(Base64.getEncoder().encodeToString(pageBackground));
                            bgImage.pixelWidth(pixelWidth);
                            bgImage.pixelHeight(pixelHeight);
                            bgImage.displayWidth(page.widthHwpunits());
                            bgImage.displayHeight(page.heightHwpunits());
                            bgFrame.image(bgImage);

                            iPage.addFrame(bgFrame);
                            zOrderCounter = 1;  // 텍스트는 1부터 시작
                        }
                    } catch (IOException e) {
                        warnings.add("Page background render failed: " + page.pageNumber() + " - " + e.getMessage());
                    }
                }

                // 텍스트 프레임을 위치 순서로 정렬
                Collections.sort(textFrames, new FramePositionComparator(page));

                // 동일 위치 프레임 중복 제거 (디자인 변형/대안 콘텐츠)
                textFrames = deduplicateByPosition(textFrames);

                // 텍스트 프레임 변환 (높은 zOrder → 이미지 위에 배치)
                for (IDMLTextFrame tf : textFrames) {
                    // 조판지시서 (편집 지시) 프레임은 건너뛴다
                    if (tf.isEditorialNote()) {
                        continue;
                    }

                    String storyId = tf.parentStoryId();
                    if (storyId == null) {
                        continue;
                    }

                    // 이미 캐시된 연결 프레임의 일부인 경우 (다른 페이지에서 시작된 체인)
                    if (textFrameConverter.isLinkedFrameInCache(tf.selfId())) {
                        IDMLStory cachedStory = idmlDoc.getStory(storyId);
                        IntermediateFrame iFrame = createLinkedFrameForPage(
                                tf, storyId, cachedStory, page, zOrderCounter++);
                        if (iFrame != null) {
                            iPage.addFrame(iFrame);
                        }
                        continue;
                    }

                    // 연결된 TextFrame 체인의 첫 번째가 아니면 건너뛴다
                    if (!isFirstInLinkedChain(tf)) {
                        continue;
                    }

                    if (processedStories.contains(storyId)) {
                        continue;
                    }
                    processedStories.add(storyId);

                    IDMLStory story = idmlDoc.getStory(storyId);
                    if (story == null) {
                        warnings.add("Story not found: " + storyId);
                        continue;
                    }

                    // 빈 Story (텍스트 없음)는 건너뛴다 — 장식용 배경 프레임
                    if (story.isEmpty()) {
                        continue;
                    }

                    // 연결된 프레임 체인 확인
                    if (hasLinkedFrames(tf)) {
                        // 텍스트 분할 방식: 연결된 프레임 체인 처리
                        // 먼저 체인 전체의 텍스트 분할을 캐시에 저장
                        prepareLinkedFrameChain(tf, spread, story);

                        // 현재 페이지에 속한 프레임만 처리
                        List<IDMLTextFrame> frameChain = collectLinkedFrameChain(tf, spread);
                        for (IDMLTextFrame chainFrame : frameChain) {
                            // 이 프레임이 현재 페이지에 속하는지 확인
                            if (!isFrameOnPage(chainFrame, page)) {
                                continue;
                            }

                            IntermediateFrame iFrame = createLinkedFrameForPage(
                                    chainFrame, storyId, story, page, zOrderCounter++);
                            if (iFrame != null) {
                                iPage.addFrame(iFrame);
                            }
                        }
                    } else {
                        // 단일 프레임: 기존 방식
                        IntermediateFrame iFrame = new IntermediateFrame();
                        iFrame.frameId("frame_" + tf.selfId());
                        iFrame.frameType("text");

                        // 프레임 좌표 계산 (페이지 상대)
                        setFramePosition(iFrame, tf.geometricBounds(), tf.itemTransform(),
                                page.geometricBounds(), page.itemTransform());
                        iFrame.zOrder(zOrderCounter++);

                        // 컬럼 정보 설정
                        setColumnInfo(iFrame, tf);

                        // 텍스트 방향 설정 (세로쓰기 여부)
                        iFrame.verticalText(story.isVertical());

                        // 디버그: 좌표 계산 추적 (페이지 모드)
                        String textPreview = "";
                        if (!story.paragraphs().isEmpty() && !story.paragraphs().get(0).characterRuns().isEmpty()) {
                            textPreview = story.paragraphs().get(0).characterRuns().get(0).content();
                            if (textPreview != null && textPreview.length() > 20) {
                                textPreview = textPreview.substring(0, 20);
                            }
                        }
                        double[] fb = tf.geometricBounds();
                        double[] ft = tf.itemTransform();
                        double[] pb = page.geometricBounds();
                        double[] pt = page.itemTransform();
                        System.err.printf("[DEBUG-PAGE] TextFrame %s: \"%s\"%n", tf.selfId(), textPreview);
                        System.err.printf("  IDML frameBounds: [%.2f, %.2f, %.2f, %.2f] (top,left,bottom,right)%n",
                                fb[0], fb[1], fb[2], fb[3]);
                        System.err.printf("  IDML frameTransform: [%.4f, %.4f, %.4f, %.4f, %.2f, %.2f]%n",
                                ft[0], ft[1], ft[2], ft[3], ft[4], ft[5]);
                        System.err.printf("  IDML pageBounds: [%.2f, %.2f, %.2f, %.2f]%n",
                                pb[0], pb[1], pb[2], pb[3]);
                        System.err.printf("  IDML pageTransform: [%.4f, %.4f, %.4f, %.4f, %.2f, %.2f]%n",
                                pt[0], pt[1], pt[2], pt[3], pt[4], pt[5]);
                        System.err.printf("  결과 좌표: (%d, %d) HWPUNIT = (%.2f, %.2f) mm%n",
                                iFrame.x(), iFrame.y(), iFrame.x()/283.465, iFrame.y()/283.465);
                        System.err.printf("  결과 크기: %d x %d HWPUNIT = %.2f x %.2f mm%n%n",
                                iFrame.width(), iFrame.height(), iFrame.width()/283.465, iFrame.height()/283.465);

                        // Story 내용 변환
                        Map<String, List<IDMLEquationExtractor.ExtractedEquation>> storyEquations =
                                extractStoryEquations(equationExtractor, storyId);

                        convertStory(story, iFrame, storyEquations);
                        iPage.addFrame(iFrame);

                        // Story 내 테이블 변환
                        if (story.hasTables()) {
                            List<IntermediateFrame> tableFrames = convertStoryTables(
                                    story, tf.geometricBounds(), tf.itemTransform(),
                                    page.geometricBounds(), page.itemTransform(),
                                    zOrderCounter);
                            for (IntermediateFrame tableFrame : tableFrames) {
                                iPage.addFrame(tableFrame);
                            }
                            zOrderCounter += tableFrames.size();
                        }
                    }
                }

                // 인라인 벡터 도형 변환 (페이지 모드)
                List<IDMLVectorShape> inlineShapes = spread.getInlineVectorShapesOnPage(page);
                for (IDMLVectorShape shape : inlineShapes) {
                    IntermediateFrame shapeFrame = convertInlineVectorShape(
                            shape, page.geometricBounds(), page.itemTransform(), zOrderCounter++);
                    if (shapeFrame != null) {
                        iPage.addFrame(shapeFrame);
                    }
                }

                doc.addPage(iPage);
            }
        }

        return doc;
    }

    /**
     * 스프레드 모드 변환: 각 스프레드를 하나의 IntermediateSpread로 변환한다.
     */
    private void convertSpreads(IntermediateDocument doc, Set<String> processedStories) {
        int zOrderCounter = 0;
        IDMLPageRenderer renderer = new IDMLPageRenderer(idmlDoc, options.vectorDpi());

        int spreadIndex = 0;
        for (IDMLSpread spread : idmlDoc.spreads()) {
            spreadIndex++;
            List<IDMLPage> pages = spread.pages();
            if (pages.isEmpty()) continue;

            // 이 스프레드에 포함된 페이지 중 변환 대상이 있는지 확인
            boolean hasTargetPage = false;
            for (IDMLPage page : pages) {
                if (pageFilter.shouldInclude(page.pageNumber())) {
                    hasTargetPage = true;
                    break;
                }
            }
            if (!hasTargetPage) continue;

            System.err.println("[INFO] 스프레드 " + spreadIndex + " 변환 중 (페이지 " + pages.size() + "개)...");

            IntermediateSpread iSpread = new IntermediateSpread();
            iSpread.spreadId(spread.selfId());

            // 스프레드 크기 계산 (모든 페이지를 포함하는 영역)
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

            for (IDMLPage page : pages) {
                double[] bounds = page.geometricBounds();
                double[] transform = page.itemTransform();
                if (bounds == null || transform == null) continue;

                double[] topLeft = IDMLGeometry.absoluteTopLeft(bounds, transform);
                double pageW = IDMLGeometry.width(bounds);
                double pageH = IDMLGeometry.height(bounds);

                minX = Math.min(minX, topLeft[0]);
                minY = Math.min(minY, topLeft[1]);
                maxX = Math.max(maxX, topLeft[0] + pageW);
                maxY = Math.max(maxY, topLeft[1] + pageH);
            }

            double spreadW = maxX - minX;
            double spreadH = maxY - minY;
            iSpread.spreadWidth(CoordinateConverter.pointsToHwpunits(spreadW));
            iSpread.spreadHeight(CoordinateConverter.pointsToHwpunits(spreadH));

            // 페이지 정보 추가
            for (IDMLPage page : pages) {
                double[] bounds = page.geometricBounds();
                double[] transform = page.itemTransform();
                if (bounds == null || transform == null) continue;

                double[] topLeft = IDMLGeometry.absoluteTopLeft(bounds, transform);
                double pageW = IDMLGeometry.width(bounds);
                double pageH = IDMLGeometry.height(bounds);

                IntermediatePageInfo pageInfo = new IntermediatePageInfo();
                pageInfo.pageNumber(page.pageNumber());
                pageInfo.offsetX(CoordinateConverter.pointsToHwpunits(topLeft[0] - minX));
                pageInfo.offsetY(CoordinateConverter.pointsToHwpunits(topLeft[1] - minY));
                pageInfo.pageWidth(CoordinateConverter.pointsToHwpunits(pageW));
                pageInfo.pageHeight(CoordinateConverter.pointsToHwpunits(pageH));
                iSpread.addPageInfo(pageInfo);
            }

            // 각 페이지의 이미지와 벡터 그래픽을 배경 PNG로 렌더링
            if (options.includeImages()) {
                IDMLPageRenderer pageRenderer = new IDMLPageRenderer(idmlDoc, options.vectorDpi());

                for (IDMLPage page : pages) {
                    if (!pageFilter.shouldInclude(page.pageNumber())) continue;

                    System.err.println("[INFO]   페이지 " + page.pageNumber() + " 배경 렌더링 중...");

                    try {
                        byte[] pageBackground = pageRenderer.renderPage(spread, page, options.linksDirectory(),
                                options.drawPageBoundary());

                        if (pageBackground != null && pageBackground.length > 0) {
                            double[] pageTopLeft = getPageTopLeft(page, minX, minY);
                            double pageW = page.widthPoints();
                            double pageH = page.heightPoints();
                            double scale = options.vectorDpi() / 72.0;
                            int pixelWidth = (int) Math.ceil(pageW * scale);
                            int pixelHeight = (int) Math.ceil(pageH * scale);

                            IntermediateFrame bgFrame = new IntermediateFrame();
                            bgFrame.frameId("page_background_" + page.pageNumber());
                            bgFrame.frameType("image");
                            bgFrame.x(CoordinateConverter.pointsToHwpunits(pageTopLeft[0]));
                            bgFrame.y(CoordinateConverter.pointsToHwpunits(pageTopLeft[1]));
                            bgFrame.width(CoordinateConverter.pointsToHwpunits(pageW));
                            bgFrame.height(CoordinateConverter.pointsToHwpunits(pageH));
                            bgFrame.zOrder(zOrderCounter++);
                            bgFrame.isBackgroundImage(true);

                            IntermediateImage bgImage = new IntermediateImage();
                            bgImage.imageId("page_bg_" + page.pageNumber());
                            bgImage.format("png");
                            bgImage.base64Data(Base64.getEncoder().encodeToString(pageBackground));
                            bgImage.pixelWidth(pixelWidth);
                            bgImage.pixelHeight(pixelHeight);
                            bgImage.displayWidth(CoordinateConverter.pointsToHwpunits(pageW));
                            bgImage.displayHeight(CoordinateConverter.pointsToHwpunits(pageH));
                            bgFrame.image(bgImage);

                            iSpread.addFrame(bgFrame);
                        }
                    } catch (IOException e) {
                        warnings.add("Page background render failed: " + page.pageNumber() + " - " + e.getMessage());
                    }
                }
            }

            // 텍스트 프레임 변환
            for (IDMLTextFrame tf : spread.textFrames()) {
                if (tf.isEditorialNote()) continue;
                if (!isFirstInLinkedChain(tf)) continue;

                String storyId = tf.parentStoryId();
                if (storyId == null || processedStories.contains(storyId)) continue;
                processedStories.add(storyId);

                IDMLStory story = idmlDoc.getStory(storyId);
                if (story == null || story.isEmpty()) continue;

                // 연결된 프레임 체인 확인
                if (hasLinkedFrames(tf)) {
                    // 텍스트 분할 방식: 연결된 프레임 체인을 독립 글상자로 변환
                    List<IDMLTextFrame> frameChain = collectLinkedFrameChain(tf, spread);
                    List<IntermediateFrame> linkedFrames = convertLinkedTextFramesForSpread(
                            frameChain, story, pages, minX, minY, zOrderCounter);
                    for (IntermediateFrame linkedFrame : linkedFrames) {
                        iSpread.addFrame(linkedFrame);
                    }
                    zOrderCounter += linkedFrames.size();
                } else {
                    // 단일 프레임: 기존 방식
                    IDMLPage targetPage = findPageForTextFrame(tf, pages);
                    if (targetPage == null) continue;

                    double[] pageTopLeft = getPageTopLeft(targetPage, minX, minY);
                    IntermediateFrame iFrame = convertTextFrameForSpread(tf, story, targetPage, pageTopLeft, zOrderCounter++);
                    if (iFrame != null) {
                        iSpread.addFrame(iFrame);
                    }
                }

                // Story 내 테이블 변환 (스프레드 모드)
                if (story.hasTables()) {
                    IDMLPage targetPage = findPageForTextFrame(tf, pages);
                    if (targetPage != null) {
                        double[] tablePageTopLeft = getPageTopLeft(targetPage, minX, minY);
                        List<IntermediateFrame> tableFrames = convertStoryTables(
                                story, tf.geometricBounds(), tf.itemTransform(),
                                targetPage.geometricBounds(), targetPage.itemTransform(),
                                zOrderCounter);
                        // 페이지 오프셋 적용 (스프레드 내 페이지 위치)
                        long offsetX = CoordinateConverter.pointsToHwpunits(tablePageTopLeft[0]);
                        long offsetY = CoordinateConverter.pointsToHwpunits(tablePageTopLeft[1]);
                        for (IntermediateFrame tableFrame : tableFrames) {
                            tableFrame.x(tableFrame.x() + offsetX);
                            tableFrame.y(tableFrame.y() + offsetY);
                            if (tableFrame.table() != null) {
                                tableFrame.table().x(tableFrame.x());
                                tableFrame.table().y(tableFrame.y());
                            }
                            iSpread.addFrame(tableFrame);
                        }
                        zOrderCounter += tableFrames.size();
                    }
                }
            }

            // 인라인 벡터 도형 변환 (스프레드 모드)
            for (IDMLPage page : pages) {
                if (!pageFilter.shouldInclude(page.pageNumber())) continue;

                double[] pageTopLeft = getPageTopLeft(page, minX, minY);
                List<IDMLVectorShape> inlineShapes = spread.getInlineVectorShapesOnPage(page);
                for (IDMLVectorShape shape : inlineShapes) {
                    IntermediateFrame shapeFrame = convertInlineVectorShape(
                            shape, page.geometricBounds(), page.itemTransform(), zOrderCounter++);
                    if (shapeFrame != null) {
                        // 스프레드 내 페이지 오프셋 적용
                        long offsetX = CoordinateConverter.pointsToHwpunits(pageTopLeft[0]);
                        long offsetY = CoordinateConverter.pointsToHwpunits(pageTopLeft[1]);
                        shapeFrame.x(shapeFrame.x() + offsetX);
                        shapeFrame.y(shapeFrame.y() + offsetY);
                        iSpread.addFrame(shapeFrame);
                    }
                }
            }

            doc.addSpread(iSpread);
        }
    }

    private IDMLPage findPageForShape(IDMLVectorShape shape, List<IDMLPage> pages) {
        double[] bounds = shape.geometricBounds();
        double[] transform = shape.itemTransform();
        if (bounds == null || transform == null) return null;

        double[] shapePos = IDMLGeometry.absoluteTopLeft(bounds, transform);
        return findPageContaining(shapePos[0], shapePos[1], pages);
    }

    private IDMLPage findPageForImageFrame(IDMLImageFrame frame, List<IDMLPage> pages) {
        double[] bounds = frame.geometricBounds();
        double[] transform = frame.itemTransform();
        if (bounds == null || transform == null) return null;

        double[] pos = IDMLGeometry.absoluteTopLeft(bounds, transform);
        return findPageContaining(pos[0], pos[1], pages);
    }

    private IDMLPage findPageForTextFrame(IDMLTextFrame frame, List<IDMLPage> pages) {
        double[] bounds = frame.geometricBounds();
        double[] transform = frame.itemTransform();
        if (bounds == null || transform == null) return null;

        double[] pos = IDMLGeometry.absoluteTopLeft(bounds, transform);
        return findPageContaining(pos[0], pos[1], pages);
    }

    private IDMLPage findPageContaining(double x, double y, List<IDMLPage> pages) {
        for (IDMLPage page : pages) {
            double[] bounds = page.geometricBounds();
            double[] transform = page.itemTransform();
            if (bounds == null || transform == null) continue;

            double[] topLeft = IDMLGeometry.absoluteTopLeft(bounds, transform);
            double pageW = IDMLGeometry.width(bounds);
            double pageH = IDMLGeometry.height(bounds);

            if (x >= topLeft[0] && x <= topLeft[0] + pageW &&
                y >= topLeft[1] && y <= topLeft[1] + pageH) {
                return page;
            }
        }
        // 가장 가까운 페이지 반환
        return pages.isEmpty() ? null : pages.get(0);
    }

    private double[] getPageTopLeft(IDMLPage page, double spreadMinX, double spreadMinY) {
        double[] bounds = page.geometricBounds();
        double[] transform = page.itemTransform();
        double[] topLeft = IDMLGeometry.absoluteTopLeft(bounds, transform);
        return new double[]{ topLeft[0] - spreadMinX, topLeft[1] - spreadMinY };
    }

    /**
     * 인라인 벡터 도형을 IntermediateFrame으로 변환한다.
     * VectorShapeConverter에 위임한다.
     */
    private IntermediateFrame convertInlineVectorShape(IDMLVectorShape shape,
                                                         double[] pageBounds, double[] pageTransform,
                                                         int zOrder) {
        return vectorShapeConverter.convert(shape, pageBounds, pageTransform, zOrder);
    }

    private IntermediateFrame convertImageFrameForSpread(IDMLImageFrame imgFrame, IDMLPage page,
                                                          double[] pageTopLeft, int zOrder) {
        IntermediateFrame iFrame = convertImageFrame(imgFrame, page, zOrder);
        if (iFrame != null) {
            // 페이지 상대 좌표를 스프레드 상대 좌표로 변환
            iFrame.x(iFrame.x() + CoordinateConverter.pointsToHwpunits(pageTopLeft[0]));
            iFrame.y(iFrame.y() + CoordinateConverter.pointsToHwpunits(pageTopLeft[1]));
        }
        return iFrame;
    }

    private IntermediateFrame convertTextFrameForSpread(IDMLTextFrame tf, IDMLStory story,
                                                         IDMLPage page, double[] pageTopLeft, int zOrder) {
        return textFrameConverter.convertForSpread(tf, story, page, pageTopLeft, zOrder);
    }

    /**
     * 기본 레이아웃을 첫 페이지 기준으로 생성한다.
     */
    private DocumentLayout buildLayout() {
        DocumentLayout layout = new DocumentLayout();
        List<IDMLPage> allPages = idmlDoc.getAllPages();
        if (!allPages.isEmpty()) {
            IDMLPage firstPage = allPages.get(0);
            layout.defaultPageWidth(firstPage.widthHwpunits());
            layout.defaultPageHeight(firstPage.heightHwpunits());
            layout.marginTop(CoordinateConverter.pointsToHwpunits(firstPage.marginTop()));
            layout.marginBottom(CoordinateConverter.pointsToHwpunits(firstPage.marginBottom()));
            layout.marginLeft(CoordinateConverter.pointsToHwpunits(firstPage.marginLeft()));
            layout.marginRight(CoordinateConverter.pointsToHwpunits(firstPage.marginRight()));
        } else {
            // A4 기본값
            layout.defaultPageWidth(59528);
            layout.defaultPageHeight(84188);
            layout.marginTop(5668);
            layout.marginBottom(4252);
            layout.marginLeft(8504);
            layout.marginRight(8504);
        }
        return layout;
    }

    /**
     * 프레임의 페이지 상대 좌표를 설정한다.
     * TextFrameConverter에 위임한다.
     */
    private void setFramePosition(IntermediateFrame iFrame,
                                   double[] frameBounds, double[] frameTransform,
                                   double[] pageBounds, double[] pageTransform) {
        textFrameConverter.setFramePosition(iFrame, frameBounds, frameTransform, pageBounds, pageTransform);
    }

    /**
     * 텍스트 프레임의 컬럼 정보를 설정한다.
     * TextFrameConverter에 위임한다.
     */
    private void setColumnInfo(IntermediateFrame iFrame, IDMLTextFrame tf) {
        textFrameConverter.setColumnInfo(iFrame, tf);
    }

    /**
     * Story의 수식을 추출한다.
     */
    private Map<String, List<IDMLEquationExtractor.ExtractedEquation>> extractStoryEquations(
            IDMLEquationExtractor extractor, String storyId) {
        Map<String, List<IDMLEquationExtractor.ExtractedEquation>> result =
                new HashMap<String, List<IDMLEquationExtractor.ExtractedEquation>>();
        if (extractor == null) return result;

        try {
            List<IDMLEquationExtractor.ExtractedEquation> equations =
                    extractor.extractFromStory("Story_" + storyId + ".xml");
            if (!equations.isEmpty()) {
                result.put(storyId, equations);
            }
        } catch (Exception e) {
            warnings.add("Equation extraction failed for story " + storyId + ": " + e.getMessage());
        }
        return result;
    }

    /**
     * Story 내용을 IntermediateFrame에 변환한다.
     * TextFrameConverter에 위임한다.
     */
    private void convertStory(IDMLStory story, IntermediateFrame iFrame,
                               Map<String, List<IDMLEquationExtractor.ExtractedEquation>> storyEquations) {
        textFrameConverter.convertStory(story, iFrame, storyEquations);
    }

    /**
     * 프레임이 특정 페이지에 속하는지 확인한다.
     */
    private boolean isFrameOnPage(IDMLTextFrame frame, IDMLPage page) {
        if (frame.geometricBounds() == null || frame.itemTransform() == null
                || page.geometricBounds() == null || page.itemTransform() == null) {
            return false;
        }
        return IDMLGeometry.isFrameOnPage(
                frame.geometricBounds(), frame.itemTransform(),
                page.geometricBounds(), page.itemTransform());
    }

    /**
     * 연결된 프레임 체인의 텍스트 분할을 준비하고 캐시에 저장한다.
     * TextFrameConverter에 위임한다.
     */
    private void prepareLinkedFrameChain(IDMLTextFrame firstFrame, IDMLSpread spread, IDMLStory story) {
        textFrameConverter.prepareLinkedFrameChain(firstFrame, spread, story);
    }

    /**
     * 캐시된 텍스트를 사용하여 연결된 프레임의 IntermediateFrame을 생성한다.
     * TextFrameConverter에 위임한다.
     */
    private IntermediateFrame createLinkedFrameForPage(IDMLTextFrame tf, String storyId,
                                                        IDMLStory story, IDMLPage page, int zOrder) {
        return textFrameConverter.createLinkedFrameForPage(tf, storyId, story, page, zOrder);
    }

    /**
     * 연결된 텍스트 프레임 체인을 수집한다.
     * TextFrameConverter에 위임한다.
     */
    private List<IDMLTextFrame> collectLinkedFrameChain(IDMLTextFrame firstFrame, IDMLSpread spread) {
        return textFrameConverter.collectLinkedFrameChain(firstFrame, spread);
    }

    /**
     * 연결된 텍스트 프레임 체인의 텍스트를 분할하여 여러 IntermediateFrame을 생성한다.
     * TextFrameConverter에 위임한다.
     */
    private List<IntermediateFrame> convertLinkedTextFrames(List<IDMLTextFrame> frameChain,
                                                             IDMLStory story,
                                                             IDMLPage page,
                                                             int startZOrder,
                                                             Map<String, List<IDMLEquationExtractor.ExtractedEquation>> storyEquations) {
        return textFrameConverter.convertLinkedTextFrames(frameChain, story, page, startZOrder, storyEquations);
    }

    /**
     * 연결된 텍스트 프레임 체인을 스프레드 좌표계로 변환한다.
     * TextFrameConverter에 위임한다.
     */
    private List<IntermediateFrame> convertLinkedTextFramesForSpread(List<IDMLTextFrame> frameChain,
                                                                       IDMLStory story,
                                                                       List<IDMLPage> pages,
                                                                       double spreadMinX,
                                                                       double spreadMinY,
                                                                       int startZOrder) {
        // PageLocator 구현
        TextFrameConverter.PageLocator pageLocator = new TextFrameConverter.PageLocator() {
            @Override
            public IDMLPage findPageForTextFrame(IDMLTextFrame tf, List<IDMLPage> pageList) {
                return IDMLToIntermediateConverter.this.findPageForTextFrame(tf, pageList);
            }
            @Override
            public double[] getPageTopLeft(IDMLPage page, double minX, double minY) {
                return IDMLToIntermediateConverter.this.getPageTopLeft(page, minX, minY);
            }
        };
        return textFrameConverter.convertLinkedTextFramesForSpread(
                frameChain, story, pages, spreadMinX, spreadMinY, startZOrder, pageLocator);
    }

    /**
     * 텍스트 프레임이 연결된 체인의 첫 번째 프레임인지 확인한다.
     */
    private boolean isFirstInLinkedChain(IDMLTextFrame tf) {
        return textFrameConverter.isFirstInLinkedChain(tf);
    }

    /**
     * 텍스트 프레임이 연결된 체인의 일부인지 확인한다.
     */
    private boolean hasLinkedFrames(IDMLTextFrame tf) {
        return textFrameConverter.hasLinkedFrames(tf);
    }

    /**
     * 이미지 프레임을 변환한다.
     * ImageFrameConverter에 위임한다.
     */
    private IntermediateFrame convertImageFrame(IDMLImageFrame imgFrame, IDMLPage page,
                                                 int zOrder) {
        return imageFrameConverter.convert(imgFrame, page, zOrder);
    }

    /**
     * 경고 목록을 반환한다.
     */
    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }

    // ── 프레임 정렬 (위→아래, 왼→오른) ──

    /**
     * 동일한 위치와 크기에 있는 텍스트 프레임 중 첫 번째만 유지한다.
     * IDML에서 디자인 변형(대안 콘텐츠)이 동일 위치에 겹쳐진 경우 중복을 제거한다.
     * ItemTransform의 이동 값(tx, ty)과 프레임 크기가 거의 동일하면
     * 같은 위치의 중복 프레임으로 판단한다.
     */
    private static List<IDMLTextFrame> deduplicateByPosition(List<IDMLTextFrame> frames) {
        List<IDMLTextFrame> result = new ArrayList<IDMLTextFrame>();
        // 각 결과 프레임의 (tx, ty, width, height) 목록
        List<double[]> resultPositions = new ArrayList<double[]>();
        // 위치 비교 허용 오차 (포인트)
        double POS_TOLERANCE = 3.0;
        // 크기 비교 허용 오차 (포인트)
        double SIZE_TOLERANCE = 5.0;

        for (IDMLTextFrame frame : frames) {
            double[] transform = frame.itemTransform();
            if (transform == null || transform.length < 6) {
                result.add(frame);
                continue;
            }
            double tx = transform[4];
            double ty = transform[5];
            double w = frame.widthPoints();
            double h = frame.heightPoints();

            // 이미 추가된 프레임 중 동일 위치/크기가 있는지 확인
            boolean duplicate = false;
            for (double[] pos : resultPositions) {
                if (Math.abs(pos[0] - tx) < POS_TOLERANCE
                        && Math.abs(pos[1] - ty) < POS_TOLERANCE
                        && Math.abs(pos[2] - w) < SIZE_TOLERANCE
                        && Math.abs(pos[3] - h) < SIZE_TOLERANCE) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) {
                continue; // 동일 위치/크기의 이전 프레임이 이미 있으므로 건너뛴다
            }
            result.add(frame);
            resultPositions.add(new double[]{tx, ty, w, h});
        }
        return result;
    }

    // =========================================================================
    // 테이블 변환 메서드
    // =========================================================================

    /**
     * IDMLTable을 IntermediateTable로 변환한다.
     */
    private IntermediateTable convertTable(IDMLTable idmlTable) {
        IntermediateTable iTable = new IntermediateTable();
        iTable.tableId(idmlTable.selfId());
        iTable.rowCount(idmlTable.rowCount());
        iTable.columnCount(idmlTable.columnCount());

        // 컬럼 너비 변환 (points → HWPUNIT)
        for (Double colWidth : idmlTable.columnWidths()) {
            iTable.addColumnWidth(CoordinateConverter.pointsToHwpunits(colWidth));
        }

        // 테이블 전체 크기 계산
        iTable.width(CoordinateConverter.pointsToHwpunits(idmlTable.totalWidth()));
        iTable.height(CoordinateConverter.pointsToHwpunits(idmlTable.totalHeight()));

        // 테이블 스타일
        if (idmlTable.strokeColor() != null) {
            iTable.borderColor(resolveColorRef(idmlTable.strokeColor()));
        }
        iTable.borderWidth(CoordinateConverter.pointsToHwpunits(idmlTable.strokeWeight()));

        // 행 변환
        for (IDMLTableRow idmlRow : idmlTable.rows()) {
            IntermediateTableRow iRow = convertTableRow(idmlRow, iTable.columnWidths());
            iTable.addRow(iRow);
        }

        return iTable;
    }

    /**
     * IDMLTableRow를 IntermediateTableRow로 변환한다.
     */
    private IntermediateTableRow convertTableRow(IDMLTableRow idmlRow, List<Long> columnWidths) {
        IntermediateTableRow iRow = new IntermediateTableRow();
        iRow.rowIndex(idmlRow.rowIndex());
        iRow.rowHeight(CoordinateConverter.pointsToHwpunits(idmlRow.rowHeight()));

        // 셀 변환
        for (IDMLTableCell idmlCell : idmlRow.cells()) {
            IntermediateTableCell iCell = convertTableCell(idmlCell, columnWidths, iRow.rowHeight());
            iRow.addCell(iCell);
        }

        return iRow;
    }

    /**
     * IDMLTableCell을 IntermediateTableCell로 변환한다.
     */
    private IntermediateTableCell convertTableCell(IDMLTableCell idmlCell, List<Long> columnWidths, long rowHeight) {
        IntermediateTableCell iCell = new IntermediateTableCell();
        iCell.rowIndex(idmlCell.rowIndex());
        iCell.columnIndex(idmlCell.columnIndex());
        iCell.rowSpan(idmlCell.rowSpan());
        iCell.columnSpan(idmlCell.columnSpan());

        // 셀 크기 계산 (병합 셀 고려)
        long cellWidth = 0;
        int startCol = idmlCell.columnIndex();
        int endCol = startCol + idmlCell.columnSpan();
        for (int c = startCol; c < endCol && c < columnWidths.size(); c++) {
            cellWidth += columnWidths.get(c);
        }
        iCell.width(cellWidth);
        iCell.height(rowHeight * idmlCell.rowSpan());

        // 셀 스타일
        if (idmlCell.fillColor() != null) {
            iCell.fillColor(resolveColorRef(idmlCell.fillColor()));
        }

        // 셀 여백 (points → HWPUNIT)
        iCell.marginTop(CoordinateConverter.pointsToHwpunits(idmlCell.topInset()));
        iCell.marginBottom(CoordinateConverter.pointsToHwpunits(idmlCell.bottomInset()));
        iCell.marginLeft(CoordinateConverter.pointsToHwpunits(idmlCell.leftInset()));
        iCell.marginRight(CoordinateConverter.pointsToHwpunits(idmlCell.rightInset()));

        // 수직 정렬
        String vJust = idmlCell.verticalJustification();
        if ("CenterAlign".equals(vJust)) {
            iCell.verticalAlign("center");
        } else if ("BottomAlign".equals(vJust)) {
            iCell.verticalAlign("bottom");
        } else {
            iCell.verticalAlign("top");
        }

        // 셀 테두리 변환
        iCell.topBorder(convertCellBorder(idmlCell.topBorder()));
        iCell.bottomBorder(convertCellBorder(idmlCell.bottomBorder()));
        iCell.leftBorder(convertCellBorder(idmlCell.leftBorder()));
        iCell.rightBorder(convertCellBorder(idmlCell.rightBorder()));

        // 대각선 변환
        iCell.topLeftDiagonalLine(idmlCell.topLeftDiagonalLine());
        iCell.topRightDiagonalLine(idmlCell.topRightDiagonalLine());
        iCell.diagonalBorder(convertCellBorder(idmlCell.diagonalBorder()));

        // 셀 내용 (단락) 변환
        for (IDMLParagraph idmlPara : idmlCell.paragraphs()) {
            IntermediateParagraph iPara = convertCellParagraph(idmlPara);
            iCell.addParagraph(iPara);
        }

        return iCell;
    }

    /**
     * IDMLTableCell.CellBorder를 IntermediateTableCell.CellBorder로 변환한다.
     */
    private IntermediateTableCell.CellBorder convertCellBorder(IDMLTableCell.CellBorder idmlBorder) {
        if (idmlBorder == null) {
            return null;
        }

        IntermediateTableCell.CellBorder iBorder = new IntermediateTableCell.CellBorder();

        // 선 두께 (points → HWPUNIT)
        iBorder.strokeWeight = CoordinateConverter.pointsToHwpunits(idmlBorder.strokeWeight);

        // 선 색상 (색상 참조 → 실제 색상)
        if (idmlBorder.strokeColor != null) {
            iBorder.strokeColor = resolveColorRef(idmlBorder.strokeColor);
        }

        // 선 타입
        iBorder.strokeType = idmlBorder.strokeType;

        // 투명도
        iBorder.strokeTint = idmlBorder.strokeTint;

        return iBorder;
    }

    /**
     * 테이블 셀 내의 단락을 변환한다.
     * TextFrameConverter의 메서드를 활용한다.
     */
    private IntermediateParagraph convertCellParagraph(IDMLParagraph para) {
        IntermediateParagraph iPara = new IntermediateParagraph();

        // 단락 스타일 참조
        String paraStyleRef = para.appliedParagraphStyle();
        if (paraStyleRef != null && paraStyleRefToId.containsKey(paraStyleRef)) {
            iPara.paragraphStyleRef(paraStyleRefToId.get(paraStyleRef));
        }

        // 텍스트 런 변환 (TextFrameConverter의 메서드 재사용)
        textFrameConverter.convertTextRuns(para, iPara);

        return iPara;
    }

    /**
     * Story에서 테이블을 추출하여 IntermediateFrame 목록으로 변환한다.
     * 각 테이블은 별도의 프레임으로 생성된다.
     */
    private List<IntermediateFrame> convertStoryTables(IDMLStory story,
                                                        double[] frameBounds, double[] frameTransform,
                                                        double[] pageBounds, double[] pageTransform,
                                                        int startZOrder) {
        List<IntermediateFrame> tableFrames = new ArrayList<>();

        if (!story.hasTables()) {
            return tableFrames;
        }

        int zOrder = startZOrder;
        double yOffset = 0;  // 테이블 간 Y 오프셋 누적

        for (IDMLTable idmlTable : story.tables()) {
            IntermediateFrame iFrame = new IntermediateFrame();
            iFrame.frameId("table_" + idmlTable.selfId());
            iFrame.frameType("table");
            iFrame.zOrder(zOrder++);

            // 테이블 변환
            IntermediateTable iTable = convertTable(idmlTable);
            iFrame.table(iTable);

            // 프레임 위치 계산 (텍스트 프레임 기준)
            double[] pos = IDMLGeometry.pageRelativePosition(frameBounds, frameTransform, pageBounds, pageTransform);
            long x = CoordinateConverter.pointsToHwpunits(pos[0]);
            long y = CoordinateConverter.pointsToHwpunits(pos[1] + yOffset);

            iFrame.x(x);
            iFrame.y(y);
            iFrame.width(iTable.width());
            iFrame.height(iTable.height());

            // 테이블에도 위치 설정
            iTable.x(x);
            iTable.y(y);
            iTable.zOrder(iFrame.zOrder());

            tableFrames.add(iFrame);

            // 다음 테이블 Y 오프셋 (현재 테이블 높이 + 간격)
            yOffset += idmlTable.totalHeight() + idmlTable.spaceAfter();
        }

        return tableFrames;
    }

    /**
     * 색상 참조를 HEX 색상으로 변환한다.
     * ColorResolver에 위임한다.
     */
    private String resolveColorRef(String colorRef) {
        return colorResolver.resolve(colorRef);
    }

    /**
     * TextFrame을 위→아래, 왼→오른 순서로 정렬한다.
     */
    private static class FramePositionComparator implements Comparator<IDMLTextFrame> {
        private final IDMLPage page;

        FramePositionComparator(IDMLPage page) {
            this.page = page;
        }

        public int compare(IDMLTextFrame a, IDMLTextFrame b) {
            double[] posA = getPosition(a);
            double[] posB = getPosition(b);
            // Y 좌표 먼저 비교 (위→아래)
            int cmp = Double.compare(posA[1], posB[1]);
            if (cmp != 0) return cmp;
            // X 좌표 비교 (왼→오른)
            return Double.compare(posA[0], posB[0]);
        }

        private double[] getPosition(IDMLTextFrame frame) {
            if (frame.geometricBounds() != null && frame.itemTransform() != null
                    && page.geometricBounds() != null && page.itemTransform() != null) {
                return IDMLGeometry.pageRelativePosition(
                        frame.geometricBounds(), frame.itemTransform(),
                        page.geometricBounds(), page.itemTransform());
            }
            return new double[]{0, 0};
        }
    }

    /**
     * ImageFrame을 위→아래, 왼→오른 순서로 정렬한다.
     */
    private static class ImageFramePositionComparator implements Comparator<IDMLImageFrame> {
        private final IDMLPage page;

        ImageFramePositionComparator(IDMLPage page) {
            this.page = page;
        }

        public int compare(IDMLImageFrame a, IDMLImageFrame b) {
            double[] posA = getPosition(a);
            double[] posB = getPosition(b);
            int cmp = Double.compare(posA[1], posB[1]);
            if (cmp != 0) return cmp;
            return Double.compare(posA[0], posB[0]);
        }

        private double[] getPosition(IDMLImageFrame frame) {
            if (frame.geometricBounds() != null && frame.itemTransform() != null
                    && page.geometricBounds() != null && page.itemTransform() != null) {
                return IDMLGeometry.pageRelativePosition(
                        frame.geometricBounds(), frame.itemTransform(),
                        page.geometricBounds(), page.itemTransform());
            }
            return new double[]{0, 0};
        }
    }
}
