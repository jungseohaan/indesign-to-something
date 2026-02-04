package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.tool.equationconverter.idml.IDMLEquationExtractor;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertException;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertOptions;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.*;
import kr.dogfoot.hwpxlib.tool.imageinserter.DesignFileConverter;
import kr.dogfoot.hwpxlib.tool.imageinserter.ImageInserter;

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

    private IDMLToIntermediateConverter(IDMLDocument idmlDoc, ConvertOptions options,
                                        String sourceFileName) {
        this.idmlDoc = idmlDoc;
        this.options = options;
        this.sourceFileName = sourceFileName;
        this.pageFilter = new PageFilter(options);
        this.paraStyleRefToId = new LinkedHashMap<String, String>();
        this.charStyleRefToId = new LinkedHashMap<String, String>();
        this.warnings = new ArrayList<String>();
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
            doc.useSpreadMode(true);
            convertSpreads(doc, processedStories);
            return doc;
        }

        // === 페이지 모드: 각 페이지를 개별적으로 변환 ===
        for (IDMLSpread spread : idmlDoc.spreads()) {
            for (IDMLPage page : spread.pages()) {
                if (!pageFilter.shouldInclude(page.pageNumber())) {
                    continue;
                }

                IntermediatePage iPage = new IntermediatePage();
                iPage.pageNumber(page.pageNumber());
                iPage.pageWidth(page.widthHwpunits());
                iPage.pageHeight(page.heightHwpunits());

                // 텍스트 프레임 수집 및 정렬 (위→아래, 왼→오른)
                List<IDMLTextFrame> textFrames = spread.getTextFramesOnPage(page);
                List<IDMLImageFrame> imageFrames = spread.getImageFramesOnPage(page);

                // 벡터 도형을 PNG로 렌더링하여 이미지 프레임으로 변환 (가장 낮은 zOrder)
                if (options.includeImages()) {
                    List<IDMLVectorShape> vectorShapes = spread.getVectorShapesOnPage(page);
                    IDMLPageRenderer renderer = new IDMLPageRenderer(idmlDoc, 300);  // 300 DPI

                    for (IDMLVectorShape shape : vectorShapes) {
                        try {
                            IDMLPageRenderer.RenderResult result = renderer.renderVectorToPng(shape, page);
                            if (result != null && result.pngData() != null && result.pngData().length > 0) {
                                IntermediateFrame iFrame = new IntermediateFrame();
                                iFrame.frameId("vector_" + shape.selfId());
                                iFrame.frameType("image");
                                iFrame.x(Math.round(result.x() * 100));  // points to HWPUNIT
                                iFrame.y(Math.round(result.y() * 100));
                                iFrame.width(Math.round(result.width() * 100));
                                iFrame.height(Math.round(result.height() * 100));
                                iFrame.zOrder(zOrderCounter++);

                                IntermediateImage iImage = new IntermediateImage();
                                iImage.imageId("vec_" + shape.selfId());
                                iImage.format("png");
                                iImage.base64Data(Base64.getEncoder().encodeToString(result.pngData()));
                                iImage.pixelWidth(result.pixelWidth());
                                iImage.pixelHeight(result.pixelHeight());
                                iImage.displayWidth(Math.round(result.width() * 100));
                                iImage.displayHeight(Math.round(result.height() * 100));
                                iFrame.image(iImage);

                                iPage.addFrame(iFrame);
                            }
                        } catch (IOException e) {
                            warnings.add("Vector shape render failed: " + shape.selfId() + " - " + e.getMessage());
                        }
                    }
                }

                // 이미지 프레임을 변환 (벡터 뒤, 텍스트 앞)
                if (options.includeImages()) {
                    Collections.sort(imageFrames, new ImageFramePositionComparator(page));

                    for (IDMLImageFrame imgFrame : imageFrames) {
                        IntermediateFrame iFrame = convertImageFrame(imgFrame, page, zOrderCounter++);
                        if (iFrame != null) {
                            iPage.addFrame(iFrame);
                        }
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

                    // 연결된 TextFrame 체인의 첫 번째만 처리
                    // IDML에서 PreviousTextFrame="n"은 "없음"을 의미
                    if (tf.previousTextFrame() != null
                            && !tf.previousTextFrame().isEmpty()
                            && !"n".equals(tf.previousTextFrame())) {
                        continue;
                    }

                    String storyId = tf.parentStoryId();
                    if (storyId == null || processedStories.contains(storyId)) {
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

                    IntermediateFrame iFrame = new IntermediateFrame();
                    iFrame.frameId("frame_" + tf.selfId());
                    iFrame.frameType("text");

                    // 프레임 좌표 계산 (페이지 상대)
                    setFramePosition(iFrame, tf.geometricBounds(), tf.itemTransform(),
                            page.geometricBounds(), page.itemTransform());
                    iFrame.zOrder(zOrderCounter++);

                    // Story 내용 변환
                    Map<String, List<IDMLEquationExtractor.ExtractedEquation>> storyEquations =
                            extractStoryEquations(equationExtractor, storyId);

                    convertStory(story, iFrame, storyEquations);
                    iPage.addFrame(iFrame);
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
        IDMLPageRenderer renderer = new IDMLPageRenderer(idmlDoc, 300);

        for (IDMLSpread spread : idmlDoc.spreads()) {
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

            // 벡터 도형을 PNG로 렌더링
            if (options.includeImages()) {
                for (IDMLVectorShape shape : spread.vectorShapes()) {
                    // 어느 페이지에 속하는지 확인
                    IDMLPage targetPage = findPageForShape(shape, pages);
                    if (targetPage == null) continue;

                    try {
                        IDMLPageRenderer.RenderResult result = renderer.renderVectorToPng(shape, targetPage);
                        if (result != null && result.pngData() != null && result.pngData().length > 0) {
                            // 페이지 상대 좌표 → 스프레드 상대 좌표
                            double[] pageTopLeft = getPageTopLeft(targetPage, minX, minY);

                            IntermediateFrame iFrame = new IntermediateFrame();
                            iFrame.frameId("vector_" + shape.selfId());
                            iFrame.frameType("image");
                            iFrame.x(CoordinateConverter.pointsToHwpunits(result.x() + pageTopLeft[0]));
                            iFrame.y(CoordinateConverter.pointsToHwpunits(result.y() + pageTopLeft[1]));
                            iFrame.width(CoordinateConverter.pointsToHwpunits(result.width()));
                            iFrame.height(CoordinateConverter.pointsToHwpunits(result.height()));
                            iFrame.zOrder(zOrderCounter++);

                            IntermediateImage iImage = new IntermediateImage();
                            iImage.imageId("vec_" + shape.selfId());
                            iImage.format("png");
                            iImage.base64Data(Base64.getEncoder().encodeToString(result.pngData()));
                            iImage.pixelWidth(result.pixelWidth());
                            iImage.pixelHeight(result.pixelHeight());
                            iImage.displayWidth(CoordinateConverter.pointsToHwpunits(result.width()));
                            iImage.displayHeight(CoordinateConverter.pointsToHwpunits(result.height()));
                            iFrame.image(iImage);

                            iSpread.addFrame(iFrame);
                        }
                    } catch (IOException e) {
                        warnings.add("Vector shape render failed: " + shape.selfId() + " - " + e.getMessage());
                    }
                }

                // 이미지 프레임 변환
                for (IDMLImageFrame imgFrame : spread.imageFrames()) {
                    IDMLPage targetPage = findPageForImageFrame(imgFrame, pages);
                    if (targetPage == null) continue;

                    double[] pageTopLeft = getPageTopLeft(targetPage, minX, minY);
                    IntermediateFrame iFrame = convertImageFrameForSpread(imgFrame, targetPage, pageTopLeft, zOrderCounter++);
                    if (iFrame != null) {
                        iSpread.addFrame(iFrame);
                    }
                }
            }

            // 텍스트 프레임 변환
            for (IDMLTextFrame tf : spread.textFrames()) {
                if (tf.isEditorialNote()) continue;
                if (tf.previousTextFrame() != null && !tf.previousTextFrame().isEmpty()
                        && !"n".equals(tf.previousTextFrame())) continue;

                String storyId = tf.parentStoryId();
                if (storyId == null || processedStories.contains(storyId)) continue;
                processedStories.add(storyId);

                IDMLStory story = idmlDoc.getStory(storyId);
                if (story == null || story.isEmpty()) continue;

                IDMLPage targetPage = findPageForTextFrame(tf, pages);
                if (targetPage == null) continue;

                double[] pageTopLeft = getPageTopLeft(targetPage, minX, minY);
                IntermediateFrame iFrame = convertTextFrameForSpread(tf, story, targetPage, pageTopLeft, zOrderCounter++);
                if (iFrame != null) {
                    iSpread.addFrame(iFrame);
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
        iFrame.x(iFrame.x() + CoordinateConverter.pointsToHwpunits(pageTopLeft[0]));
        iFrame.y(iFrame.y() + CoordinateConverter.pointsToHwpunits(pageTopLeft[1]));
        iFrame.zOrder(zOrder);

        // Story 내용 변환 (수식 없이 텍스트만)
        for (IDMLParagraph para : story.paragraphs()) {
            IntermediateParagraph iPara = new IntermediateParagraph();

            String paraStyleRef = para.appliedParagraphStyle();
            if (paraStyleRef != null && paraStyleRefToId.containsKey(paraStyleRef)) {
                iPara.paragraphStyleRef(paraStyleRefToId.get(paraStyleRef));
            }

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
     * 프레임의 페이지 상대 좌표를 설정한다.
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

        double[] relPos = IDMLGeometry.pageRelativePosition(
                frameBounds, frameTransform, pageBounds, pageTransform);

        iFrame.x(CoordinateConverter.pointsToHwpunits(relPos[0]));
        iFrame.y(CoordinateConverter.pointsToHwpunits(relPos[1]));
        iFrame.width(CoordinateConverter.pointsToHwpunits(IDMLGeometry.width(frameBounds)));
        iFrame.height(CoordinateConverter.pointsToHwpunits(IDMLGeometry.height(frameBounds)));
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
            String color = StyleMapper.resolveColor(run.fillColor(), idmlDoc.colors());
            iRun.textColor(color);
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
     * 일반 텍스트 런만 있는 단락을 변환한다.
     */
    private void convertTextRuns(IDMLParagraph para, IntermediateParagraph iPara) {
        for (IDMLCharacterRun run : para.characterRuns()) {
            if (run.isNPFont()) continue;
            IntermediateTextRun iRun = createTextRun(run);
            if (iRun != null) iPara.addRun(iRun);
        }
    }

    /**
     * 이미지 프레임을 변환한다.
     */
    private IntermediateFrame convertImageFrame(IDMLImageFrame imgFrame, IDMLPage page,
                                                 int zOrder) {
        IntermediateFrame iFrame = new IntermediateFrame();
        iFrame.frameId("frame_" + imgFrame.selfId());
        iFrame.frameType("image");
        iFrame.zOrder(zOrder);

        setFramePosition(iFrame, imgFrame.geometricBounds(), imgFrame.itemTransform(),
                page.geometricBounds(), page.itemTransform());

        // 배경 이미지 판별: 페이지 면적의 80% 이상을 차지하면 배경으로 간주
        long pageWidth = page.widthHwpunits();
        long pageHeight = page.heightHwpunits();
        long pageArea = pageWidth * pageHeight;
        long frameArea = iFrame.width() * iFrame.height();
        if (pageArea > 0 && frameArea >= pageArea * 0.8) {
            iFrame.isBackgroundImage(true);
        }

        IntermediateImage iImage = new IntermediateImage();
        iImage.imageId("img_" + imgFrame.selfId());
        iImage.originalPath(imgFrame.linkResourceURI());
        iImage.displayWidth(iFrame.width());
        iImage.displayHeight(iFrame.height());

        String uri = imgFrame.linkResourceURI();
        if (uri != null) {
            iImage.format(detectFormat(stripFileUri(uri)));
        }

        // 이미지 파일 로드 및 필요 시 PNG로 변환
        if (options.includeImages()) {
            loadAndConvertImage(imgFrame, iImage);
        }

        iFrame.image(iImage);
        return iFrame;
    }

    /**
     * 이미지 파일 경로를 해석한다.
     * IDML의 LinkResourceURI는 "file:/path/to/file" 형태의 URI이거나
     * 퍼센트 인코딩된 경로일 수 있다.
     *
     * 검색 순서:
     * 1. 절대 경로 시도 (URI 그대로)
     * 2. options.linksDirectory()에서 파일명으로 검색 (지정된 경우)
     * 3. basePath + 상대 경로
     * 4. basePath/Links/ 폴더에서 파일명으로 검색
     * 5. 대소문자 무시 검색
     */
    private String resolveImagePath(IDMLImageFrame imgFrame) {
        String uri = imgFrame.linkResourceURI();
        if (uri == null || uri.isEmpty()) return null;

        // file: URI 스킴 처리
        String path = stripFileUri(uri);
        String filename = extractFilename(path);

        // 1. 절대 경로 시도
        File absolute = new File(path);
        if (absolute.exists()) return absolute.getAbsolutePath();

        // 2. options.linksDirectory()에서 검색 (지정된 경우)
        if (options.linksDirectory() != null && filename != null) {
            File linksDir = new File(options.linksDirectory());
            if (linksDir.isDirectory()) {
                File inLinks = new File(linksDir, filename);
                if (inLinks.exists()) return inLinks.getAbsolutePath();

                // 대소문자 무시 검색
                String found = findFileIgnoreCase(linksDir, filename);
                if (found != null) return found;
            }
        }

        // 3. 상대 경로 (basePath 기준)
        if (idmlDoc.basePath() != null) {
            File relative = new File(idmlDoc.basePath(), path);
            if (relative.exists()) return relative.getAbsolutePath();

            // 4. Links/ 접두어 제거 시도
            if (path.startsWith("Links/")) {
                File linksRelative = new File(idmlDoc.basePath(), path.substring("Links/".length()));
                if (linksRelative.exists()) return linksRelative.getAbsolutePath();
            }

            // 5. 파일명만 추출하여 basePath/Links/ 폴더에서 검색
            if (filename != null) {
                File inLinks = new File(new File(idmlDoc.basePath(), "Links"), filename);
                if (inLinks.exists()) return inLinks.getAbsolutePath();

                // 6. basePath 직접 검색
                File inBase = new File(idmlDoc.basePath(), filename);
                if (inBase.exists()) return inBase.getAbsolutePath();

                // 7. 대소문자 무시 검색 (Links/ 폴더)
                File linksDir = new File(idmlDoc.basePath(), "Links");
                if (linksDir.isDirectory()) {
                    String found = findFileIgnoreCase(linksDir, filename);
                    if (found != null) return found;
                }
            }
        }

        return null;
    }

    /**
     * 경로에서 파일명만 추출한다.
     * 예: "/Users/foo/Links/image.psd" → "image.psd"
     */
    private static String extractFilename(String path) {
        if (path == null || path.isEmpty()) return null;
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (lastSlash >= 0 && lastSlash < path.length() - 1) {
            return path.substring(lastSlash + 1);
        }
        return path;
    }

    /**
     * 디렉토리에서 파일명을 대소문자 무시하여 검색한다.
     */
    private static String findFileIgnoreCase(File directory, String filename) {
        File[] files = directory.listFiles();
        if (files == null) return null;
        String lowerTarget = filename.toLowerCase();
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase().equals(lowerTarget)) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    /**
     * file: URI 스킴을 제거하고 퍼센트 인코딩을 디코딩한다.
     * 예: "file:/Users/foo/bar%20baz.psd" → "/Users/foo/bar baz.psd"
     *     "file:///Users/foo/bar.psd" → "/Users/foo/bar.psd"
     */
    private static String stripFileUri(String uri) {
        String path = uri;

        // file: 스킴 제거
        if (path.startsWith("file:///")) {
            path = path.substring("file://".length());  // keep leading /
        } else if (path.startsWith("file:/")) {
            path = path.substring("file:".length());
        }

        // 퍼센트 인코딩 디코딩
        try {
            path = URLDecoder.decode(path, "UTF-8");
        } catch (Exception e) {
            // 디코딩 실패 시 원본 사용
        }

        return path;
    }

    /**
     * 파일 이름에서 이미지 포맷을 추출한다.
     */
    private static String detectFormat(String filename) {
        String lower = filename.toLowerCase();
        int dot = lower.lastIndexOf('.');
        if (dot >= 0 && dot < lower.length() - 1) {
            String ext = lower.substring(dot + 1);
            switch (ext) {
                case "jpg":
                case "jpeg":
                    return "jpeg";
                case "png":
                    return "png";
                case "gif":
                    return "gif";
                case "bmp":
                    return "bmp";
                case "tiff":
                case "tif":
                    return "tiff";
                case "psd":
                    return "psd";
                case "ai":
                    return "ai";
                default:
                    return ext;
            }
        }
        return "png";
    }

    /**
     * 이미지 파일을 로드하고, 필요 시 PNG로 변환하여 base64Data를 설정한다.
     * 이미지는 options.imageDpi()에 맞게 리사이즈된다 (기본 72 DPI).
     */
    private void loadAndConvertImage(IDMLImageFrame imgFrame, IntermediateImage iImage) {
        String resolvedPath = resolveImagePath(imgFrame);
        if (resolvedPath == null) {
            // 상세 경고 메시지
            String uri = imgFrame.linkResourceURI();
            String filename = extractFilename(stripFileUri(uri != null ? uri : ""));
            StringBuilder msg = new StringBuilder("Image not found: ");
            msg.append(filename != null ? filename : uri);
            if (idmlDoc.basePath() != null) {
                File linksDir = new File(idmlDoc.basePath(), "Links");
                if (!linksDir.isDirectory()) {
                    msg.append(" (IDML package has no Links/ folder - images not embedded)");
                } else {
                    msg.append(" (not found in IDML Links/ folder)");
                }
            }
            warnings.add(msg.toString());

            // placeholder 이미지 생성 (액박)
            createPlaceholderImage(iImage, filename);
            return;
        }

        File imageFile = new File(resolvedPath);
        String format = iImage.format();

        try {
            byte[] imageData;
            String outputFormat = format;

            if (isDesignFormat(format)) {
                // PSD, AI, PDF, EPS, TIFF → PNG 변환
                if ("ai".equals(format) || "pdf".equals(format) || "eps".equals(format)) {
                    // 벡터 포맷은 DPI 지정 필요
                    imageData = DesignFileConverter.convertToPng(imageFile, options.imageDpi());
                } else {
                    // 래스터 포맷 (PSD, TIFF)
                    imageData = DesignFileConverter.convertToPng(imageFile);
                }
                outputFormat = "png";
            } else {
                // PNG, JPEG, GIF, BMP 등 → 그대로 읽기
                imageData = Files.readAllBytes(imageFile.toPath());
            }

            // 클리핑이 필요한 경우 (imageTransform이 있으면 이미지가 프레임 내에서 변환됨)
            double[] imageTransform = imgFrame.imageTransform();
            double[] frameBounds = imgFrame.geometricBounds();
            if (imageTransform != null && frameBounds != null) {
                imageData = applyImageClipping(imageData, imgFrame, iImage);
                outputFormat = "png";
            } else {
                // 클리핑 없음 - 기존 로직 (리사이즈만)
                byte[] originalData = imageData;
                imageData = processImageWithoutClipping(imageData, iImage);
                if (imageData != originalData) {
                    outputFormat = "png";  // 리사이즈된 경우 PNG로 변환됨
                }
            }

            // base64 인코딩
            iImage.base64Data(Base64.getEncoder().encodeToString(imageData));
            iImage.format(outputFormat);

        } catch (IOException e) {
            warnings.add("Image conversion failed: " + imageFile.getName()
                    + " (" + format + ") - " + e.getMessage());

            // 변환 실패 시에도 placeholder 생성
            createPlaceholderImage(iImage, imageFile.getName());
        }
    }

    /**
     * 클리핑 없이 이미지를 처리한다 (리사이즈만).
     */
    private byte[] processImageWithoutClipping(byte[] imageData, IntermediateImage iImage) throws IOException {
        // 픽셀 크기 감지
        int originalWidth, originalHeight;
        try {
            int[] pixelSize = ImageInserter.detectPixelSize(imageData);
            originalWidth = pixelSize[0];
            originalHeight = pixelSize[1];
        } catch (IOException e) {
            // displayDimension 기반 폴백 (300 DPI 가정)
            originalWidth = (int)(iImage.displayWidth() / 24);  // 7200/300 = 24
            originalHeight = (int)(iImage.displayHeight() / 24);
        }

        // 목표 DPI에 맞게 리사이즈 (displayWidth/Height는 HWPUNIT = 1/7200 inch)
        int targetDpi = options.imageDpi();
        int targetWidth = (int) Math.round(iImage.displayWidth() * targetDpi / 7200.0);
        int targetHeight = (int) Math.round(iImage.displayHeight() * targetDpi / 7200.0);

        // 최소 크기 보장
        targetWidth = Math.max(10, targetWidth);
        targetHeight = Math.max(10, targetHeight);

        // 원본보다 크게 확대하지 않음 (품질 저하 방지)
        if (targetWidth > originalWidth || targetHeight > originalHeight) {
            targetWidth = originalWidth;
            targetHeight = originalHeight;
        }

        // 리사이즈 필요 여부 확인 (20% 이상 차이가 나면 리사이즈)
        boolean needsResize = originalWidth > targetWidth * 1.2 || originalHeight > targetHeight * 1.2;

        if (needsResize) {
            byte[] resizedData = resizeImage(imageData, targetWidth, targetHeight);
            iImage.pixelWidth(targetWidth);
            iImage.pixelHeight(targetHeight);
            return resizedData;
        } else {
            iImage.pixelWidth(originalWidth);
            iImage.pixelHeight(originalHeight);
            return imageData;
        }
    }

    /**
     * 이미지 클리핑을 적용한다.
     * IDML의 imageTransform과 graphicBounds를 사용하여 프레임에 맞게 이미지를 자른다.
     */
    private byte[] applyImageClipping(byte[] imageData, IDMLImageFrame imgFrame,
                                       IntermediateImage iImage) throws IOException {
        BufferedImage srcImage = ImageIO.read(new ByteArrayInputStream(imageData));
        if (srcImage == null) {
            warnings.add("Failed to decode image for clipping");
            return imageData;
        }

        double[] frameBounds = imgFrame.geometricBounds();  // [top, left, bottom, right]
        double[] imageTransform = imgFrame.imageTransform(); // [a, b, c, d, tx, ty]
        double[] graphicBounds = imgFrame.graphicBounds();   // [left, top, right, bottom]

        // 이미지 변환 정보
        double imgScaleX = imageTransform[0];
        double imgScaleY = imageTransform[3];
        double imgTx = imageTransform[4];
        double imgTy = imageTransform[5];

        // graphicBounds = 원본 이미지의 좌표계 범위 (points 단위)
        double gLeft = 0, gTop = 0, gRight = srcImage.getWidth(), gBottom = srcImage.getHeight();
        if (graphicBounds != null && graphicBounds.length >= 4) {
            gLeft = graphicBounds[0];
            gTop = graphicBounds[1];
            gRight = graphicBounds[2];
            gBottom = graphicBounds[3];
        }

        // 스케일 절대값 (음수 = 뒤집기)
        double absScaleX = Math.abs(imgScaleX);
        double absScaleY = Math.abs(imgScaleY);
        if (absScaleX < 0.001) absScaleX = 1.0;
        if (absScaleY < 0.001) absScaleY = 1.0;

        // 그래픽 좌표 (points) → 소스 픽셀 변환 스케일
        double graphicW = gRight - gLeft;
        double graphicH = gBottom - gTop;
        double pointsToPixelX = (graphicW > 0) ? srcImage.getWidth() / graphicW : 1.0;
        double pointsToPixelY = (graphicH > 0) ? srcImage.getHeight() / graphicH : 1.0;

        // 프레임 로컬 좌표
        double frameLeft = frameBounds[1];
        double frameTop = frameBounds[0];
        double frameRight = frameBounds[3];
        double frameBottom = frameBounds[2];
        double frameW = frameRight - frameLeft;
        double frameH = frameBottom - frameTop;

        // 프레임 좌표 → 그래픽 좌표 변환
        // graphic_pos = (frame_local_pos - imgTx) / imgScale + gLeft
        double graphicLeft = (frameLeft - imgTx) / imgScaleX + gLeft;
        double graphicTop = (frameTop - imgTy) / imgScaleY + gTop;
        double graphicRight = (frameRight - imgTx) / imgScaleX + gLeft;
        double graphicBottom = (frameBottom - imgTy) / imgScaleY + gTop;

        // 그래픽 좌표를 소스 픽셀 좌표로 변환
        double srcLeft = (graphicLeft - gLeft) * pointsToPixelX;
        double srcTop = (graphicTop - gTop) * pointsToPixelY;
        double srcRight = (graphicRight - gLeft) * pointsToPixelX;
        double srcBottom = (graphicBottom - gTop) * pointsToPixelY;

        // 소스 이미지 범위로 클리핑
        int srcX1 = Math.max(0, (int) Math.floor(Math.min(srcLeft, srcRight)));
        int srcY1 = Math.max(0, (int) Math.floor(Math.min(srcTop, srcBottom)));
        int srcX2 = Math.min(srcImage.getWidth(), (int) Math.ceil(Math.max(srcLeft, srcRight)));
        int srcY2 = Math.min(srcImage.getHeight(), (int) Math.ceil(Math.max(srcTop, srcBottom)));

        // 유효한 영역이 있는지 확인
        if (srcX1 >= srcX2 || srcY1 >= srcY2) {
            warnings.add("Image clipping resulted in empty area");
            return imageData;
        }

        // 출력 이미지 크기 계산
        int targetDpi = options.imageDpi();
        int pixelWidth = (int) Math.ceil(frameW * targetDpi / 72.0);
        int pixelHeight = (int) Math.ceil(frameH * targetDpi / 72.0);
        pixelWidth = Math.max(10, pixelWidth);
        pixelHeight = Math.max(10, pixelHeight);

        // 투명 PNG 생성
        BufferedImage clippedImage = new BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = clippedImage.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // 이미지 반전 처리
        int actualSrcX1 = srcX1, actualSrcX2 = srcX2;
        int actualSrcY1 = srcY1, actualSrcY2 = srcY2;
        if (imgScaleX < 0) {
            actualSrcX1 = srcImage.getWidth() - srcX2;
            actualSrcX2 = srcImage.getWidth() - srcX1;
        }
        if (imgScaleY < 0) {
            actualSrcY1 = srcImage.getHeight() - srcY2;
            actualSrcY2 = srcImage.getHeight() - srcY1;
        }

        // 이미지 그리기 (클리핑된 영역을 출력 이미지 전체에 맞춤)
        g.drawImage(srcImage,
                0, 0, pixelWidth, pixelHeight,
                actualSrcX1, actualSrcY1, actualSrcX2, actualSrcY2, null);

        g.dispose();

        // PNG 인코딩
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(clippedImage, "png", baos);

        iImage.pixelWidth(pixelWidth);
        iImage.pixelHeight(pixelHeight);

        return baos.toByteArray();
    }

    /**
     * 이미지를 지정 크기로 리사이즈한다.
     */
    private byte[] resizeImage(byte[] imageData, int targetWidth, int targetHeight) throws IOException {
        java.awt.image.BufferedImage original = javax.imageio.ImageIO.read(
                new java.io.ByteArrayInputStream(imageData));
        if (original == null) {
            return imageData;  // 리사이즈 실패 시 원본 반환
        }

        java.awt.image.BufferedImage resized = new java.awt.image.BufferedImage(
                targetWidth, targetHeight, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = resized.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(original, 0, 0, targetWidth, targetHeight, null);
        g.dispose();

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(resized, "png", baos);
        return baos.toByteArray();
    }

    /**
     * 이미지를 찾을 수 없거나 변환 실패 시 표시할 placeholder 이미지를 생성한다.
     * 회색 배경에 X 표시와 파일명을 보여주는 PNG 이미지.
     */
    private void createPlaceholderImage(IntermediateImage iImage, String filename) {
        // displayDimension 기반 픽셀 크기 계산 (1 HWPUNIT ≈ 1/75 pixel)
        int width = Math.max(100, (int)(iImage.displayWidth() / 75));
        int height = Math.max(100, (int)(iImage.displayHeight() / 75));

        // 최대 크기 제한 (메모리 절약)
        if (width > 800) {
            height = height * 800 / width;
            width = 800;
        }
        if (height > 800) {
            width = width * 800 / height;
            height = 800;
        }

        try {
            byte[] pngData = createPlaceholderPng(width, height, filename);
            iImage.base64Data(Base64.getEncoder().encodeToString(pngData));
            iImage.format("png");
            iImage.pixelWidth(width);
            iImage.pixelHeight(height);
        } catch (Exception e) {
            // placeholder 생성도 실패하면 무시
        }
    }

    /**
     * X 표시가 있는 placeholder PNG 이미지를 생성한다.
     */
    private static byte[] createPlaceholderPng(int width, int height, String filename)
            throws IOException {
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(width, height,
                        java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();

        // 배경: 연한 회색
        g.setColor(new java.awt.Color(220, 220, 220));
        g.fillRect(0, 0, width, height);

        // 테두리: 진한 회색
        g.setColor(new java.awt.Color(128, 128, 128));
        g.drawRect(0, 0, width - 1, height - 1);

        // X 표시: 빨간색
        g.setColor(new java.awt.Color(200, 50, 50));
        g.setStroke(new java.awt.BasicStroke(2));
        int margin = Math.min(width, height) / 6;
        g.drawLine(margin, margin, width - margin, height - margin);
        g.drawLine(width - margin, margin, margin, height - margin);

        // 파일명 표시 (하단)
        if (filename != null && !filename.isEmpty()) {
            g.setColor(new java.awt.Color(80, 80, 80));
            java.awt.Font font = new java.awt.Font("SansSerif", java.awt.Font.PLAIN,
                    Math.max(10, Math.min(14, height / 10)));
            g.setFont(font);
            java.awt.FontMetrics fm = g.getFontMetrics();

            // 파일명이 너무 길면 자르기
            String displayName = filename;
            if (fm.stringWidth(displayName) > width - 10) {
                while (displayName.length() > 3 && fm.stringWidth(displayName + "...") > width - 10) {
                    displayName = displayName.substring(0, displayName.length() - 1);
                }
                displayName = displayName + "...";
            }

            int textX = (width - fm.stringWidth(displayName)) / 2;
            int textY = height - margin / 2;
            g.drawString(displayName, textX, textY);
        }

        g.dispose();

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private static boolean isDesignFormat(String format) {
        if (format == null) return false;
        switch (format.toLowerCase()) {
            case "psd":
            case "ai":
            case "pdf":
            case "eps":
            case "tiff":
            case "tif":
                return true;
            default:
                return false;
        }
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
