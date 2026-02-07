package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.tool.equationconverter.idml.IDMLEquationExtractor;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertException;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertOptions;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.idml.ImageFrameConverter;
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
    // 연결된 프레임 체인의 텍스트 분할 결과 캐시 (storyId -> 분할된 텍스트 목록)
    private final Map<String, List<String>> linkedFrameTextCache;
    // 연결된 프레임 체인의 프레임 순서 (frameId -> 체인 내 인덱스)
    private final Map<String, Integer> linkedFrameIndexCache;
    // 색상 참조 해석기
    private final ColorResolver colorResolver;
    // 벡터 도형 변환기
    private final VectorShapeConverter vectorShapeConverter;
    // 이미지 프레임 변환기
    private final ImageFrameConverter imageFrameConverter;

    private IDMLToIntermediateConverter(IDMLDocument idmlDoc, ConvertOptions options,
                                        String sourceFileName) {
        this.idmlDoc = idmlDoc;
        this.options = options;
        this.sourceFileName = sourceFileName;
        this.pageFilter = new PageFilter(options);
        this.paraStyleRefToId = new LinkedHashMap<String, String>();
        this.charStyleRefToId = new LinkedHashMap<String, String>();
        this.warnings = new ArrayList<String>();
        this.linkedFrameTextCache = new HashMap<String, List<String>>();
        this.linkedFrameIndexCache = new HashMap<String, Integer>();
        this.colorResolver = new ColorResolver(idmlDoc);
        this.vectorShapeConverter = new VectorShapeConverter(colorResolver);
        this.imageFrameConverter = new ImageFrameConverter(idmlDoc, options, warnings);
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
                    if (linkedFrameIndexCache.containsKey(tf.selfId())) {
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
        IntermediateFrame iFrame = new IntermediateFrame();
        iFrame.frameId("frame_" + tf.selfId());
        iFrame.frameType("text");

        // 프레임 좌표 계산 (페이지 상대 → 스프레드 상대)
        setFramePosition(iFrame, tf.geometricBounds(), tf.itemTransform(),
                page.geometricBounds(), page.itemTransform());

        // 디버그: 좌표 계산 추적
        long pageRelX = iFrame.x();
        long pageRelY = iFrame.y();

        iFrame.x(iFrame.x() + CoordinateConverter.pointsToHwpunits(pageTopLeft[0]));
        iFrame.y(iFrame.y() + CoordinateConverter.pointsToHwpunits(pageTopLeft[1]));
        iFrame.zOrder(zOrder);

        // 텍스트 방향 설정 (세로쓰기 여부)
        iFrame.verticalText(story.isVertical());

        // 첫 번째 단락의 텍스트 미리보기 (디버그용)
        String textPreview = "";
        if (!story.paragraphs().isEmpty() && !story.paragraphs().get(0).characterRuns().isEmpty()) {
            textPreview = story.paragraphs().get(0).characterRuns().get(0).content();
            if (textPreview != null && textPreview.length() > 20) {
                textPreview = textPreview.substring(0, 20);
            }
        }

        // 디버그 출력
        double[] fb = tf.geometricBounds();
        System.err.printf("[DEBUG] TextFrame %s: \"%s\"%n", tf.selfId(), textPreview);
        System.err.printf("  IDML bounds: [%.2f, %.2f, %.2f, %.2f] (top,left,bottom,right)%n",
                fb[0], fb[1], fb[2], fb[3]);
        System.err.printf("  IDML size: %.2f x %.2f pts (%.2f x %.2f mm)%n",
                fb[3]-fb[1], fb[2]-fb[0], (fb[3]-fb[1])*0.3528, (fb[2]-fb[0])*0.3528);
        System.err.printf("  pageTopLeft offset: [%.2f, %.2f] pts%n", pageTopLeft[0], pageTopLeft[1]);
        System.err.printf("  페이지 상대 좌표: (%d, %d) HWPUNIT = (%.2f, %.2f) mm%n",
                pageRelX, pageRelY, pageRelX/283.465, pageRelY/283.465);
        System.err.printf("  최종 스프레드 좌표: (%d, %d) HWPUNIT = (%.2f, %.2f) mm%n",
                iFrame.x(), iFrame.y(), iFrame.x()/283.465, iFrame.y()/283.465);
        System.err.printf("  최종 크기: %d x %d HWPUNIT = %.2f x %.2f mm%n%n",
                iFrame.width(), iFrame.height(), iFrame.width()/283.465, iFrame.height()/283.465);

        // 컬럼 정보 설정
        setColumnInfo(iFrame, tf);

        // Story 내용 변환 (수식 없이 텍스트만)
        for (IDMLParagraph para : story.paragraphs()) {
            IntermediateParagraph iPara = new IntermediateParagraph();

            String paraStyleRef = para.appliedParagraphStyle();
            if (paraStyleRef != null && paraStyleRefToId.containsKey(paraStyleRef)) {
                iPara.paragraphStyleRef(paraStyleRefToId.get(paraStyleRef));
            }

            // 인라인 단락 속성 (로컬 오버라이드)
            setInlineParagraphProperties(para, iPara);

            convertTextRuns(para, iPara);
            iFrame.addParagraph(iPara);
        }

        return iFrame;
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
     * 프레임의 페이지 상대 좌표를 설정한다 (스케일, 회전 변환 적용).
     */
    private void setFramePosition(IntermediateFrame iFrame,
                                   double[] frameBounds, double[] frameTransform,
                                   double[] pageBounds, double[] pageTransform) {
        if (frameBounds == null) {
            iFrame.x(0);
            iFrame.y(0);
            iFrame.width(0);
            iFrame.height(0);
            return;
        }

        // 프레임의 변환된 bounding box 계산
        double[] transformedBox = IDMLGeometry.getTransformedBoundingBox(frameBounds, frameTransform);
        // transformedBox: [minX, minY, maxX, maxY] 절대 좌표

        // 페이지 절대 좌표
        double[] pageAbs = IDMLGeometry.absoluteTopLeft(pageBounds, pageTransform);

        // 페이지 상대 좌표
        double relX = transformedBox[0] - pageAbs[0];
        double relY = transformedBox[1] - pageAbs[1];
        double width = transformedBox[2] - transformedBox[0];
        double height = transformedBox[3] - transformedBox[1];

        iFrame.x(CoordinateConverter.pointsToHwpunits(relX));
        iFrame.y(CoordinateConverter.pointsToHwpunits(relY));
        iFrame.width(CoordinateConverter.pointsToHwpunits(width));
        iFrame.height(CoordinateConverter.pointsToHwpunits(height));

        // 회전 각도 추출 및 설정
        double rotation = IDMLGeometry.extractRotation(frameTransform);
        iFrame.rotationAngle(rotation);

        // 회전이 있는 경우 디버그 출력
        if (Math.abs(rotation) > 0.1) {
            System.err.println("[DEBUG] Frame rotation: " + iFrame.frameId() + " = " + CoordinateConverter.fmt(rotation) + "°");
        }
    }

    /**
     * 텍스트 프레임의 컬럼 정보를 설정한다.
     */
    private void setColumnInfo(IntermediateFrame iFrame, IDMLTextFrame tf) {
        iFrame.columnCount(tf.columnCount() > 0 ? tf.columnCount() : 1);

        if (tf.columnCount() > 1) {
            iFrame.columnGutter(CoordinateConverter.pointsToHwpunits(tf.columnGutter()));
        }

        if (tf.insetSpacing() != null) {
            double[] inset = tf.insetSpacing();
            iFrame.insetTop(CoordinateConverter.pointsToHwpunits(inset[0]));
            iFrame.insetLeft(CoordinateConverter.pointsToHwpunits(inset[1]));
            iFrame.insetBottom(CoordinateConverter.pointsToHwpunits(inset[2]));
            iFrame.insetRight(CoordinateConverter.pointsToHwpunits(inset[3]));
        }

        // 컬럼 상세 속성
        iFrame.columnType(tf.columnType());
        if (tf.columnFixedWidth() > 0) {
            iFrame.columnFixedWidth(CoordinateConverter.pointsToHwpunits(tf.columnFixedWidth()));
        }
        if (tf.columnWidths() != null) {
            double[] srcWidths = tf.columnWidths();
            long[] dstWidths = new long[srcWidths.length];
            for (int i = 0; i < srcWidths.length; i++) {
                dstWidths[i] = CoordinateConverter.pointsToHwpunits(srcWidths[i]);
            }
            iFrame.columnWidths(dstWidths);
        }

        // 수직 정렬 (IDML: TopAlign, CenterAlign, BottomAlign, JustifyAlign → Intermediate: top, center, bottom, justify)
        String vJust = tf.verticalJustification();
        if (vJust != null) {
            if (vJust.contains("Top")) iFrame.verticalJustification("top");
            else if (vJust.contains("Center")) iFrame.verticalJustification("center");
            else if (vJust.contains("Bottom")) iFrame.verticalJustification("bottom");
            else if (vJust.contains("Justify")) iFrame.verticalJustification("justify");
        }

        // 텍스트 감싸기 무시
        iFrame.ignoreWrap(tf.ignoreWrap());

        // 단 경계선 (Column Rule)
        iFrame.useColumnRule(tf.useColumnRule());
        if (tf.useColumnRule()) {
            iFrame.columnRuleWidth(CoordinateConverter.pointsToHwpunits(tf.columnRuleWidth()));
            iFrame.columnRuleType(tf.columnRuleType());
            // 색상 변환 (Color 참조 → HEX)
            String ruleColor = tf.columnRuleColor();
            if (ruleColor != null && !ruleColor.contains("None")) {
                String hexColor = idmlDoc.getColor(ruleColor);
                iFrame.columnRuleColor(hexColor != null ? hexColor : ruleColor);
            }
            iFrame.columnRuleTint(tf.columnRuleTint());
            iFrame.columnRuleOffset(CoordinateConverter.pointsToHwpunits(tf.columnRuleOffset()));
            iFrame.columnRuleInsetWidth(CoordinateConverter.pointsToHwpunits(tf.columnRuleInsetWidth()));
        }

        // 스트로크/아웃라인 속성 설정
        setTextFrameStrokeInfo(iFrame, tf);
    }

    /**
     * 텍스트 프레임의 스트로크/아웃라인 속성을 설정한다.
     */
    private void setTextFrameStrokeInfo(IntermediateFrame iFrame, IDMLTextFrame tf) {
        // 획 색상 (Color 참조를 HEX로 변환)
        String strokeColor = tf.strokeColor();
        if (strokeColor != null && !strokeColor.contains("None")) {
            String hexColor = idmlDoc.getColor(strokeColor);
            iFrame.strokeColor(hexColor != null ? hexColor : strokeColor);
        }

        // 획 두께
        iFrame.strokeWeight(tf.strokeWeight());

        // 모서리 둥글기
        iFrame.cornerRadius(tf.cornerRadius());
        if (tf.cornerRadii() != null) {
            iFrame.cornerRadii(tf.cornerRadii());
        }

        // 불투명도
        iFrame.fillTint(tf.fillTint());
        iFrame.strokeTint(tf.strokeTint());

        // 채우기 색상 (Color 참조를 HEX로 변환)
        String fillColor = tf.fillColor();
        if (fillColor != null && !fillColor.contains("None")) {
            String hexColor = idmlDoc.getColor(fillColor);
            iFrame.textFrameFillColor(hexColor != null ? hexColor : fillColor);
        }
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
     */
    private void convertStory(IDMLStory story, IntermediateFrame iFrame,
                               Map<String, List<IDMLEquationExtractor.ExtractedEquation>> storyEquations) {
        List<IDMLEquationExtractor.ExtractedEquation> equations =
                storyEquations.get(story.selfId());

        // 단락 인덱스별 수식 매핑 생성
        Map<Integer, List<IDMLEquationExtractor.ExtractedEquation>> equationsByPara =
                new HashMap<Integer, List<IDMLEquationExtractor.ExtractedEquation>>();
        if (equations != null) {
            for (IDMLEquationExtractor.ExtractedEquation eq : equations) {
                List<IDMLEquationExtractor.ExtractedEquation> list = equationsByPara.get(eq.paragraphIndex);
                if (list == null) {
                    list = new ArrayList<IDMLEquationExtractor.ExtractedEquation>();
                    equationsByPara.put(eq.paragraphIndex, list);
                }
                list.add(eq);
            }
        }

        for (int paraIdx = 0; paraIdx < story.paragraphs().size(); paraIdx++) {
            IDMLParagraph para = story.paragraphs().get(paraIdx);
            IntermediateParagraph iPara = new IntermediateParagraph();

            // 단락 스타일 참조
            String paraStyleRef = para.appliedParagraphStyle();
            if (paraStyleRef != null && paraStyleRefToId.containsKey(paraStyleRef)) {
                iPara.paragraphStyleRef(paraStyleRefToId.get(paraStyleRef));
            }

            // 인라인 단락 속성 (로컬 오버라이드)
            setInlineParagraphProperties(para, iPara);

            List<IDMLEquationExtractor.ExtractedEquation> paraEquations =
                    equationsByPara.get(paraIdx);

            if (para.hasEquationContent() && paraEquations != null && !paraEquations.isEmpty()) {
                // 혼합 단락: NP 런 경계를 기준으로 텍스트/수식 교차 배치
                convertMixedParagraph(para, iPara, paraEquations);
            } else {
                // 일반 텍스트 단락
                convertTextRuns(para, iPara);
            }

            // 빈 단락도 추가 (줄바꿈 역할)
            iFrame.addParagraph(iPara);
        }
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
     */
    private void prepareLinkedFrameChain(IDMLTextFrame firstFrame, IDMLSpread spread, IDMLStory story) {
        String storyId = story.selfId();
        if (linkedFrameTextCache.containsKey(storyId)) {
            return;  // 이미 처리됨
        }

        // 1. 체인 수집
        List<IDMLTextFrame> frameChain = collectLinkedFrameChain(firstFrame, spread);

        // 2. 전체 Story 텍스트 추출
        StringBuilder fullText = new StringBuilder();
        for (IDMLParagraph para : story.paragraphs()) {
            for (IDMLCharacterRun run : para.characterRuns()) {
                if (!run.isNPFont() && run.content() != null) {
                    fullText.append(run.content());
                }
            }
            fullText.append("\n");
        }
        String storyText = fullText.toString().trim();

        // 3. 프레임 크기 정보 수집
        List<TextFitter.FrameInfo> frameInfos = new ArrayList<TextFitter.FrameInfo>();
        for (IDMLTextFrame tf : frameChain) {
            frameInfos.add(TextFitter.FrameInfo.fromPoints(tf.widthPoints(), tf.heightPoints()));
        }

        // 4. 텍스트 분할
        double defaultFontSize = 10.0;
        double lineSpacingRatio = 1.6;
        List<String> splitTexts = TextFitter.fitText(storyText, frameInfos, defaultFontSize, lineSpacingRatio);

        System.err.println("[INFO] 연결된 프레임 " + frameChain.size() + "개의 텍스트 분할 준비 (Story: " + storyId + ")");
        for (int i = 0; i < splitTexts.size(); i++) {
            String text = splitTexts.get(i);
            System.err.println("[INFO]   프레임 " + (i + 1) + " (" + frameChain.get(i).selfId() + "): " + text.length() + "자");
        }

        // 5. 캐시에 저장
        linkedFrameTextCache.put(storyId, splitTexts);
        for (int i = 0; i < frameChain.size(); i++) {
            linkedFrameIndexCache.put(frameChain.get(i).selfId(), i);
        }
    }

    /**
     * 캐시된 텍스트를 사용하여 연결된 프레임의 IntermediateFrame을 생성한다.
     */
    private IntermediateFrame createLinkedFrameForPage(IDMLTextFrame tf, String storyId,
                                                        IDMLStory story, IDMLPage page, int zOrder) {
        Integer frameIndex = linkedFrameIndexCache.get(tf.selfId());
        List<String> splitTexts = linkedFrameTextCache.get(storyId);

        if (frameIndex == null || splitTexts == null) {
            return null;
        }

        String frameText = (frameIndex < splitTexts.size()) ? splitTexts.get(frameIndex) : "";

        IntermediateFrame iFrame = new IntermediateFrame();
        iFrame.frameId("frame_" + tf.selfId());
        iFrame.frameType("text");

        // 프레임 좌표 계산 (해당 페이지 기준)
        setFramePosition(iFrame, tf.geometricBounds(), tf.itemTransform(),
                page.geometricBounds(), page.itemTransform());
        iFrame.zOrder(zOrder);

        // 컬럼 정보 설정
        setColumnInfo(iFrame, tf);

        // 텍스트 방향 설정 (세로쓰기 여부)
        if (story != null) {
            iFrame.verticalText(story.isVertical());
        }

        // 단락 생성 (줄바꿈으로 분리하여 각 단락에 스타일 적용)
        if (!frameText.isEmpty()) {
            // 기본 단락 스타일 ID 결정 (Story의 첫 번째 단락 스타일 사용)
            String defaultParaStyleId = null;
            if (story != null && !story.paragraphs().isEmpty()) {
                String paraStyleRef = story.paragraphs().get(0).appliedParagraphStyle();
                if (paraStyleRef != null && paraStyleRefToId.containsKey(paraStyleRef)) {
                    defaultParaStyleId = paraStyleRefToId.get(paraStyleRef);
                }
            }

            // 줄바꿈으로 분리하여 각각 단락으로 생성
            String[] lines = frameText.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String lineText = lines[i];
                // 마지막 빈 줄은 건너뛰기
                if (i == lines.length - 1 && lineText.isEmpty()) {
                    continue;
                }

                IntermediateParagraph iPara = new IntermediateParagraph();

                // 단락 스타일 적용
                if (defaultParaStyleId != null) {
                    iPara.paragraphStyleRef(defaultParaStyleId);
                }

                if (!lineText.isEmpty()) {
                    IntermediateTextRun iRun = new IntermediateTextRun();
                    iRun.text(lineText);
                    iPara.addRun(iRun);
                }
                iFrame.addParagraph(iPara);
            }
        }

        return iFrame;
    }

    /**
     * 연결된 텍스트 프레임 체인을 수집한다.
     * 첫 번째 프레임부터 시작하여 nextTextFrame을 따라가며 모든 프레임을 수집한다.
     * 여러 스프레드에 걸친 체인도 처리한다.
     */
    private List<IDMLTextFrame> collectLinkedFrameChain(IDMLTextFrame firstFrame, IDMLSpread spread) {
        List<IDMLTextFrame> chain = new ArrayList<IDMLTextFrame>();
        chain.add(firstFrame);

        IDMLTextFrame current = firstFrame;
        while (current.nextTextFrame() != null
                && !current.nextTextFrame().isEmpty()
                && !"n".equals(current.nextTextFrame())) {
            // 먼저 현재 스프레드에서 검색
            IDMLTextFrame next = spread.findTextFrameById(current.nextTextFrame());
            // 없으면 전체 문서에서 검색
            if (next == null) {
                next = findTextFrameInDocument(current.nextTextFrame());
            }
            if (next == null) break;
            chain.add(next);
            current = next;
        }

        return chain;
    }

    /**
     * 전체 문서에서 TextFrame을 ID로 찾는다.
     */
    private IDMLTextFrame findTextFrameInDocument(String frameId) {
        if (frameId == null || "n".equals(frameId)) return null;
        for (IDMLSpread s : idmlDoc.spreads()) {
            IDMLTextFrame tf = s.findTextFrameById(frameId);
            if (tf != null) return tf;
        }
        return null;
    }

    /**
     * 연결된 텍스트 프레임 체인의 텍스트를 분할하여 여러 IntermediateFrame을 생성한다.
     * "텍스트 분할" 방식: IDML의 연결된 프레임을 독립적인 HWPX 글상자로 변환.
     */
    private List<IntermediateFrame> convertLinkedTextFrames(List<IDMLTextFrame> frameChain,
                                                             IDMLStory story,
                                                             IDMLPage page,
                                                             int startZOrder,
                                                             Map<String, List<IDMLEquationExtractor.ExtractedEquation>> storyEquations) {
        List<IntermediateFrame> result = new ArrayList<IntermediateFrame>();

        // 1. 전체 Story 텍스트 추출
        StringBuilder fullText = new StringBuilder();
        for (IDMLParagraph para : story.paragraphs()) {
            for (IDMLCharacterRun run : para.characterRuns()) {
                if (!run.isNPFont() && run.content() != null) {
                    fullText.append(run.content());
                }
            }
            fullText.append("\n");  // 단락 구분
        }
        String storyText = fullText.toString().trim();

        // 2. 프레임 크기 정보 수집
        List<TextFitter.FrameInfo> frameInfos = new ArrayList<TextFitter.FrameInfo>();
        for (IDMLTextFrame tf : frameChain) {
            frameInfos.add(TextFitter.FrameInfo.fromPoints(tf.widthPoints(), tf.heightPoints()));
        }

        // 3. 텍스트 분할 (기본 폰트 10pt, 줄간격 1.6 가정)
        double defaultFontSize = 10.0;
        double lineSpacingRatio = 1.6;
        List<String> splitTexts = TextFitter.fitText(storyText, frameInfos, defaultFontSize, lineSpacingRatio);

        System.err.println("[INFO] 연결된 프레임 " + frameChain.size() + "개를 텍스트 분할 방식으로 변환");
        for (int i = 0; i < splitTexts.size(); i++) {
            String text = splitTexts.get(i);
            System.err.println("[INFO]   프레임 " + (i + 1) + ": " + text.length() + "자"
                    + (text.length() > 30 ? " - \"" + text.substring(0, 30) + "...\"" : ""));
        }

        // 4. 각 프레임별로 IntermediateFrame 생성
        for (int i = 0; i < frameChain.size(); i++) {
            IDMLTextFrame tf = frameChain.get(i);
            String frameText = (i < splitTexts.size()) ? splitTexts.get(i) : "";

            IntermediateFrame iFrame = new IntermediateFrame();
            iFrame.frameId("frame_" + tf.selfId());
            iFrame.frameType("text");

            // 프레임 좌표 계산 (페이지 상대)
            setFramePosition(iFrame, tf.geometricBounds(), tf.itemTransform(),
                    page.geometricBounds(), page.itemTransform());
            iFrame.zOrder(startZOrder + i);

            // 컬럼 정보 설정
            setColumnInfo(iFrame, tf);

            // 단락 생성 (단순 텍스트)
            if (!frameText.isEmpty()) {
                IntermediateParagraph iPara = new IntermediateParagraph();
                IntermediateTextRun iRun = new IntermediateTextRun();
                iRun.text(frameText);
                iPara.addRun(iRun);
                iFrame.addParagraph(iPara);
            }

            result.add(iFrame);
        }

        return result;
    }

    /**
     * 연결된 텍스트 프레임 체인을 스프레드 좌표계로 변환한다.
     */
    private List<IntermediateFrame> convertLinkedTextFramesForSpread(List<IDMLTextFrame> frameChain,
                                                                       IDMLStory story,
                                                                       List<IDMLPage> pages,
                                                                       double spreadMinX,
                                                                       double spreadMinY,
                                                                       int startZOrder) {
        List<IntermediateFrame> result = new ArrayList<IntermediateFrame>();

        // 1. 전체 Story 텍스트 추출
        StringBuilder fullText = new StringBuilder();
        for (IDMLParagraph para : story.paragraphs()) {
            for (IDMLCharacterRun run : para.characterRuns()) {
                if (!run.isNPFont() && run.content() != null) {
                    fullText.append(run.content());
                }
            }
            fullText.append("\n");
        }
        String storyText = fullText.toString().trim();

        // 2. 프레임 크기 정보 수집
        List<TextFitter.FrameInfo> frameInfos = new ArrayList<TextFitter.FrameInfo>();
        for (IDMLTextFrame tf : frameChain) {
            frameInfos.add(TextFitter.FrameInfo.fromPoints(tf.widthPoints(), tf.heightPoints()));
        }

        // 3. 텍스트 분할
        double defaultFontSize = 10.0;
        double lineSpacingRatio = 1.6;
        List<String> splitTexts = TextFitter.fitText(storyText, frameInfos, defaultFontSize, lineSpacingRatio);

        System.err.println("[INFO] 연결된 프레임 " + frameChain.size() + "개를 텍스트 분할 방식으로 변환 (스프레드)");

        // 4. 각 프레임별로 IntermediateFrame 생성 (스프레드 좌표계)
        for (int i = 0; i < frameChain.size(); i++) {
            IDMLTextFrame tf = frameChain.get(i);
            String frameText = (i < splitTexts.size()) ? splitTexts.get(i) : "";

            // 프레임이 속한 페이지 찾기
            IDMLPage targetPage = findPageForTextFrame(tf, pages);
            if (targetPage == null) continue;

            double[] pageTopLeft = getPageTopLeft(targetPage, spreadMinX, spreadMinY);

            IntermediateFrame iFrame = new IntermediateFrame();
            iFrame.frameId("frame_" + tf.selfId());
            iFrame.frameType("text");

            // 프레임 좌표 계산 (페이지 상대 → 스프레드 상대)
            setFramePosition(iFrame, tf.geometricBounds(), tf.itemTransform(),
                    targetPage.geometricBounds(), targetPage.itemTransform());
            iFrame.x(iFrame.x() + CoordinateConverter.pointsToHwpunits(pageTopLeft[0]));
            iFrame.y(iFrame.y() + CoordinateConverter.pointsToHwpunits(pageTopLeft[1]));
            iFrame.zOrder(startZOrder + i);

            // 컬럼 정보 설정
            setColumnInfo(iFrame, tf);

            // 기본 단락 스타일 ID 결정 (Story의 첫 번째 단락 스타일 사용)
            String defaultParaStyleId = null;
            if (!story.paragraphs().isEmpty()) {
                String paraStyleRef = story.paragraphs().get(0).appliedParagraphStyle();
                if (paraStyleRef != null && paraStyleRefToId.containsKey(paraStyleRef)) {
                    defaultParaStyleId = paraStyleRefToId.get(paraStyleRef);
                }
            }

            // 단락 생성 (줄바꿈으로 분리하여 각 단락에 스타일 적용)
            if (!frameText.isEmpty()) {
                String[] lines = frameText.split("\n", -1);
                for (int li = 0; li < lines.length; li++) {
                    String lineText = lines[li];
                    // 마지막 빈 줄은 건너뛰기
                    if (li == lines.length - 1 && lineText.isEmpty()) {
                        continue;
                    }

                    IntermediateParagraph iPara = new IntermediateParagraph();

                    // 단락 스타일 적용
                    if (defaultParaStyleId != null) {
                        iPara.paragraphStyleRef(defaultParaStyleId);
                    }

                    if (!lineText.isEmpty()) {
                        IntermediateTextRun iRun = new IntermediateTextRun();
                        iRun.text(lineText);
                        iPara.addRun(iRun);
                    }
                    iFrame.addParagraph(iPara);
                }
            }

            result.add(iFrame);
        }

        return result;
    }

    /**
     * 텍스트 프레임이 연결된 체인의 첫 번째 프레임인지 확인한다.
     */
    private boolean isFirstInLinkedChain(IDMLTextFrame tf) {
        String prev = tf.previousTextFrame();
        return prev == null || prev.isEmpty() || "n".equals(prev);
    }

    /**
     * 텍스트 프레임이 연결된 체인의 일부인지 확인한다 (nextTextFrame이 있는지).
     */
    private boolean hasLinkedFrames(IDMLTextFrame tf) {
        String next = tf.nextTextFrame();
        return next != null && !next.isEmpty() && !"n".equals(next);
    }

    /**
     * 수식과 텍스트가 혼합된 단락을 변환한다.
     * 수식 경계 = NP 폰트 런 경계.
     * NP 폰트 연속 구간이 시작되면 수식을 삽입하고, non-NP 런은 텍스트로 처리한다.
     */
    private void convertMixedParagraph(IDMLParagraph para, IntermediateParagraph iPara,
                                        List<IDMLEquationExtractor.ExtractedEquation> equations) {
        int eqIdx = 0;
        boolean inNPRun = false;

        for (IDMLCharacterRun run : para.characterRuns()) {
            if (run.isNPFont()) {
                if (!inNPRun) {
                    // NP 런 연속 구간 시작 → 수식 삽입
                    inNPRun = true;
                    if (eqIdx < equations.size()) {
                        iPara.addEquation(new IntermediateEquation(
                                equations.get(eqIdx).hwpScript, "NP_FONT"));
                        eqIdx++;
                    }
                }
                // NP 런 내용은 수식에 포함되므로 건너뜀
            } else {
                inNPRun = false;
                // non-NP 런은 무조건 텍스트
                IntermediateTextRun iRun = createTextRun(run);
                if (iRun != null) {
                    // 단락 인라인 자간을 런에 전파 (런 레벨 자간이 없을 때)
                    if (iRun.letterSpacing() == null && iPara.inlineLetterSpacing() != null) {
                        iRun.letterSpacing(iPara.inlineLetterSpacing());
                    }
                    iPara.addRun(iRun);
                }
            }
        }
    }

    /**
     * IDMLCharacterRun에서 IntermediateTextRun을 생성한다.
     */
    private IntermediateTextRun createTextRun(IDMLCharacterRun run) {
        String content = run.content();
        if (content == null || content.isEmpty()) return null;

        IntermediateTextRun iRun = new IntermediateTextRun();
        iRun.text(content);

        // 문자 스타일 참조
        String charStyleRef = run.appliedCharacterStyle();
        if (charStyleRef != null && charStyleRefToId.containsKey(charStyleRef)) {
            iRun.characterStyleRef(charStyleRefToId.get(charStyleRef));
        }

        // 런 레벨 오버라이드
        if (run.fontFamily() != null) {
            iRun.fontFamily(run.fontFamily());
        }
        if (run.fontSize() != null) {
            iRun.fontSizeHwpunits(CoordinateConverter.fontSizeToHeight(run.fontSize()));
        }
        if (run.fillColor() != null) {
            String color = resolveColorRef(run.fillColor());
            if (color != null) {
                iRun.textColor(color);
            }
        }
        if (run.fontStyle() != null) {
            String style = run.fontStyle().toLowerCase();
            if (style.contains("bold")) {
                iRun.bold(true);
            }
            if (style.contains("italic") || style.contains("oblique")) {
                iRun.italic(true);
            }
        }

        return iRun;
    }

    /**
     * IDML 단락의 인라인 속성(로컬 오버라이드)을 IntermediateParagraph에 설정한다.
     */
    private void setInlineParagraphProperties(IDMLParagraph para, IntermediateParagraph iPara) {
        // 정렬
        if (para.justification() != null) {
            iPara.inlineAlignment(StyleMapper.mapAlignment(para.justification()));
        }
        // 첫 줄 들여쓰기
        if (para.firstLineIndent() != null) {
            iPara.inlineFirstLineIndent(CoordinateConverter.pointsToHwpunits(para.firstLineIndent()));
        }
        // 왼쪽 여백
        if (para.leftIndent() != null) {
            iPara.inlineLeftMargin(CoordinateConverter.pointsToHwpunits(para.leftIndent()));
        }
        // 오른쪽 여백
        if (para.rightIndent() != null) {
            iPara.inlineRightMargin(CoordinateConverter.pointsToHwpunits(para.rightIndent()));
        }
        // 단락 앞 간격
        if (para.spaceBefore() != null) {
            iPara.inlineSpaceBefore(CoordinateConverter.pointsToHwpunits(para.spaceBefore()));
        }
        // 단락 뒤 간격
        if (para.spaceAfter() != null) {
            iPara.inlineSpaceAfter(CoordinateConverter.pointsToHwpunits(para.spaceAfter()));
        }
        // 줄간격 (leading → 백분율로 변환, 기본 폰트 크기 12pt 기준)
        if (para.leading() != null) {
            // leading(pt) / 12pt * 100 = 백분율 (예: 14.4pt / 12pt * 100 = 120%)
            int lineSpacingPercent = (int) Math.round(para.leading() / 12.0 * 100.0);
            iPara.inlineLineSpacing(lineSpacingPercent);
        }
        // 자간 (tracking → HWPX spacing 변환)
        // IDML tracking: 1/1000 em (100 = 10%, -50 = -5%)
        // HWPX spacing: -50 ~ 50 범위, tracking / 10으로 근사 변환
        if (para.tracking() != null) {
            short letterSpacing = (short) Math.round(para.tracking() / 10.0);
            // 범위 제한 (-50 ~ 50)
            letterSpacing = (short) Math.max(-50, Math.min(50, letterSpacing));
            iPara.inlineLetterSpacing(letterSpacing);
        }
        // 단락 음영 (Paragraph Shading)
        if (para.shadingOn()) {
            iPara.shadingOn(true);
            // 색상 해석
            if (para.shadingColor() != null) {
                String resolvedColor = StyleMapper.resolveColor(para.shadingColor(), idmlDoc.colors());
                // Tint 적용 (0~100, 기본값 100)
                if (para.shadingTint() != null && para.shadingTint() < 100.0) {
                    resolvedColor = applyTintToColor(resolvedColor, para.shadingTint());
                }
                iPara.shadingColor(resolvedColor);
            }
            iPara.shadingTint(para.shadingTint());
            // 오프셋 변환 (pt → HWPUNIT)
            if (para.shadingOffsetLeft() != null) {
                iPara.shadingOffsetLeft(CoordinateConverter.pointsToHwpunits(para.shadingOffsetLeft()));
            }
            if (para.shadingOffsetRight() != null) {
                iPara.shadingOffsetRight(CoordinateConverter.pointsToHwpunits(para.shadingOffsetRight()));
            }
            if (para.shadingOffsetTop() != null) {
                iPara.shadingOffsetTop(CoordinateConverter.pointsToHwpunits(para.shadingOffsetTop()));
            }
            if (para.shadingOffsetBottom() != null) {
                iPara.shadingOffsetBottom(CoordinateConverter.pointsToHwpunits(para.shadingOffsetBottom()));
            }
        }
    }

    /**
     * 색상에 Tint를 적용한다. (0~100, 100이면 원색)
     * Tint가 낮을수록 흰색에 가까워진다.
     */
    private String applyTintToColor(String hexColor, double tint) {
        if (hexColor == null || !hexColor.startsWith("#") || hexColor.length() < 7) {
            return hexColor;
        }
        try {
            int r = Integer.parseInt(hexColor.substring(1, 3), 16);
            int g = Integer.parseInt(hexColor.substring(3, 5), 16);
            int b = Integer.parseInt(hexColor.substring(5, 7), 16);
            // Tint 적용: 흰색과의 보간
            double factor = tint / 100.0;
            r = (int) Math.round(255 - (255 - r) * factor);
            g = (int) Math.round(255 - (255 - g) * factor);
            b = (int) Math.round(255 - (255 - b) * factor);
            return String.format("#%02X%02X%02X", r, g, b);
        } catch (NumberFormatException e) {
            return hexColor;
        }
    }

    /**
     * 일반 텍스트 런만 있는 단락을 변환한다.
     */
    private void convertTextRuns(IDMLParagraph para, IntermediateParagraph iPara) {
        for (IDMLCharacterRun run : para.characterRuns()) {
            if (run.isNPFont()) continue;
            IntermediateTextRun iRun = createTextRun(run);
            if (iRun != null) {
                // 단락 인라인 자간을 런에 전파 (런 레벨 자간이 없을 때)
                if (iRun.letterSpacing() == null && iPara.inlineLetterSpacing() != null) {
                    iRun.letterSpacing(iPara.inlineLetterSpacing());
                }
                iPara.addRun(iRun);
            }
        }
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
     */
    private IntermediateParagraph convertCellParagraph(IDMLParagraph para) {
        IntermediateParagraph iPara = new IntermediateParagraph();

        // 단락 스타일 참조
        String paraStyleRef = para.appliedParagraphStyle();
        if (paraStyleRef != null && paraStyleRefToId.containsKey(paraStyleRef)) {
            iPara.paragraphStyleRef(paraStyleRefToId.get(paraStyleRef));
        }

        // 인라인 단락 속성
        setInlineParagraphProperties(para, iPara);

        // 텍스트 런 변환 (기존 createTextRun 메서드 재사용)
        for (IDMLCharacterRun run : para.characterRuns()) {
            if (run.isNPFont()) continue;  // 수식 마커 건너뜀

            IntermediateTextRun iRun = createTextRun(run);
            if (iRun != null) {
                // 단락 레벨 속성 상속
                if (iRun.letterSpacing() == null && iPara.inlineLetterSpacing() != null) {
                    iRun.letterSpacing(iPara.inlineLetterSpacing());
                }
                iPara.addRun(iRun);
            }
        }

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
