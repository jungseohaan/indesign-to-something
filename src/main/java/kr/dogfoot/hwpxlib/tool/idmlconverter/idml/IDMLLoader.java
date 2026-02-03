package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertException;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;

import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * IDML(InDesign Markup Language) 파일을 로드하여 IDMLDocument 메모리 모델로 변환한다.
 *
 * 처리 순서:
 * 1. IDML ZIP 해제 (또는 이미 해제된 디렉토리 사용)
 * 2. designmap.xml → 스프레드/Story 목록, Section(페이지 번호) 정보
 * 3. Resources/Fonts.xml → 폰트 정의
 * 4. Resources/Styles.xml → 단락/문자 스타일
 * 5. Resources/Graphic.xml → 색상 정의
 * 6. Spreads/*.xml → 페이지, TextFrame, ImageFrame
 * 7. Stories/*.xml → 텍스트 내용 (ParagraphStyleRange → CharacterStyleRange → Content)
 */
public class IDMLLoader {

    /**
     * IDML ZIP 파일을 로드하여 IDMLDocument로 반환한다.
     */
    public static IDMLDocument load(String idmlPath) throws ConvertException {
        return load(new File(idmlPath));
    }

    /**
     * IDML ZIP 파일을 로드하여 IDMLDocument로 반환한다.
     */
    public static IDMLDocument load(File idmlFile) throws ConvertException {
        if (!idmlFile.exists()) {
            throw new ConvertException(ConvertException.Phase.LOADING,
                    "IDML file not found: " + idmlFile.getAbsolutePath());
        }

        File tempDir = extractZip(idmlFile);
        try {
            IDMLDocument doc = loadFromDirectory(tempDir);
            doc.tempDir(tempDir);  // 변환 완료 후 cleanup()에서 삭제
            return doc;
        } catch (Exception e) {
            // 로드 실패 시 즉시 정리
            deleteDirectory(tempDir);
            if (e instanceof ConvertException) {
                throw (ConvertException) e;
            }
            throw new ConvertException(ConvertException.Phase.LOADING,
                    "Failed to load IDML: " + e.getMessage(), e);
        }
    }

    /**
     * 이미 해제된 IDML 디렉토리에서 로드한다.
     */
    public static IDMLDocument loadFromDirectory(String dirPath) throws ConvertException {
        return loadFromDirectory(new File(dirPath));
    }

    /**
     * 이미 해제된 IDML 디렉토리에서 로드한다.
     */
    public static IDMLDocument loadFromDirectory(File dir) throws ConvertException {
        if (!dir.exists() || !dir.isDirectory()) {
            throw new ConvertException(ConvertException.Phase.LOADING,
                    "IDML directory not found: " + dir.getAbsolutePath());
        }

        File designmapFile = new File(dir, "designmap.xml");
        if (!designmapFile.exists()) {
            throw new ConvertException(ConvertException.Phase.LOADING,
                    "designmap.xml not found in: " + dir.getAbsolutePath());
        }

        IDMLDocument doc = new IDMLDocument();
        doc.basePath(dir.getAbsolutePath());

        try {
            // 1. designmap.xml에서 기본 정보 추출
            Document designmap = parseXML(designmapFile);
            List<String> spreadSources = new ArrayList<String>();
            List<SectionInfo> sections = new ArrayList<SectionInfo>();
            parseDesignmap(designmap, spreadSources, sections, doc);

            // 2. 폰트 로드
            File fontsFile = new File(dir, "Resources/Fonts.xml");
            if (fontsFile.exists()) {
                parseFonts(parseXML(fontsFile), doc);
            }

            // 3. 스타일 로드
            File stylesFile = new File(dir, "Resources/Styles.xml");
            if (stylesFile.exists()) {
                parseStyles(parseXML(stylesFile), doc);
            }

            // 4. 색상 로드
            File graphicFile = new File(dir, "Resources/Graphic.xml");
            if (graphicFile.exists()) {
                parseGraphic(parseXML(graphicFile), doc);
            }

            // 5. 스프레드 로드 (페이지 + 프레임)
            int pageIndex = 0;
            for (String spreadSrc : spreadSources) {
                File spreadFile = new File(dir, spreadSrc);
                if (spreadFile.exists()) {
                    IDMLSpread spread = parseSpread(parseXML(spreadFile), doc.hiddenLayerIds());
                    // 페이지 번호 할당
                    for (IDMLPage page : spread.pages()) {
                        pageIndex++;
                        int pageNum = resolvePageNumber(pageIndex, page.selfId(), sections);
                        page.pageNumber(pageNum);
                    }
                    doc.addSpread(spread);
                }
            }

            // 6. 스프레드에서 참조하는 Story들 수집
            Set<String> neededStoryIds = collectNeededStoryIds(doc);

            // 7. Story 로드
            File storiesDir = new File(dir, "Stories");
            if (storiesDir.exists() && storiesDir.isDirectory()) {
                for (String storyId : neededStoryIds) {
                    File storyFile = new File(storiesDir, "Story_" + storyId + ".xml");
                    if (storyFile.exists()) {
                        IDMLStory story = parseStory(parseXML(storyFile), storyId);
                        doc.putStory(storyId, story);
                    }
                }
            }

        } catch (ConvertException ce) {
            throw ce;
        } catch (Exception e) {
            throw new ConvertException(ConvertException.Phase.PARSING,
                    "Failed to parse IDML: " + e.getMessage(), e);
        }

        return doc;
    }

    // ===== designmap.xml 파싱 =====

    private static void parseDesignmap(Document designmap,
                                       List<String> spreadSources,
                                       List<SectionInfo> sections,
                                       IDMLDocument doc) {
        Element root = designmap.getDocumentElement();

        // Spread 참조 수집 (순서 유지)
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element elem = (Element) node;
            String tagName = elem.getTagName();

            if ("idPkg:Spread".equals(tagName)) {
                String src = elem.getAttribute("src");
                if (src != null && !src.isEmpty()) {
                    spreadSources.add(src);
                }
            } else if ("Section".equals(tagName)) {
                SectionInfo si = new SectionInfo();
                si.selfId = elem.getAttribute("Self");
                si.pageStart = elem.getAttribute("PageStart");
                si.pageNumberStart = parseIntAttr(elem, "PageNumberStart", 1);
                si.length = parseIntAttr(elem, "Length", 0);
                si.name = elem.getAttribute("Name");
                si.marker = elem.getAttribute("Marker");
                sections.add(si);
            } else if ("Layer".equals(tagName)) {
                String layerVisible = elem.getAttribute("Visible");
                if ("false".equals(layerVisible)) {
                    String layerSelf = elem.getAttribute("Self");
                    if (layerSelf != null && !layerSelf.isEmpty()) {
                        doc.addHiddenLayerId(layerSelf);
                    }
                }
            }
        }
    }

    // ===== Resources/Fonts.xml 파싱 =====

    private static void parseFonts(Document fontsDoc, IDMLDocument doc) {
        NodeList fontFamilies = fontsDoc.getElementsByTagName("FontFamily");
        for (int i = 0; i < fontFamilies.getLength(); i++) {
            Element family = (Element) fontFamilies.item(i);
            String familyName = family.getAttribute("Name");

            List<Element> fonts = getChildElements(family, "Font");
            for (Element fontElem : fonts) {
                IDMLFontDef fontDef = new IDMLFontDef();
                fontDef.selfRef(fontElem.getAttribute("Self"));
                fontDef.fontFamily(familyName);
                fontDef.fontStyleName(fontElem.getAttribute("FontStyleName"));
                fontDef.postScriptName(fontElem.getAttribute("PostScriptName"));
                fontDef.fontType(fontElem.getAttribute("FontType"));
                doc.putFont(fontDef.selfRef(), fontDef);
            }
        }
    }

    // ===== Resources/Styles.xml 파싱 =====

    private static void parseStyles(Document stylesDoc, IDMLDocument doc) {
        // ParagraphStyle
        NodeList paraStyles = stylesDoc.getElementsByTagName("ParagraphStyle");
        for (int i = 0; i < paraStyles.getLength(); i++) {
            Element styleElem = (Element) paraStyles.item(i);
            IDMLStyleDef styleDef = parseStyleDef(styleElem);
            doc.putParagraphStyle(styleDef.selfRef(), styleDef);
        }

        // CharacterStyle
        NodeList charStyles = stylesDoc.getElementsByTagName("CharacterStyle");
        for (int i = 0; i < charStyles.getLength(); i++) {
            Element styleElem = (Element) charStyles.item(i);
            IDMLStyleDef styleDef = parseStyleDef(styleElem);
            doc.putCharacterStyle(styleDef.selfRef(), styleDef);
        }
    }

    private static IDMLStyleDef parseStyleDef(Element styleElem) {
        IDMLStyleDef def = new IDMLStyleDef();
        def.selfRef(styleElem.getAttribute("Self"));
        def.name(styleElem.getAttribute("Name"));
        def.fontStyle(getAttrOrNull(styleElem, "FontStyle"));
        def.fillColor(getAttrOrNull(styleElem, "FillColor"));
        def.textAlignment(getAttrOrNull(styleElem, "Justification"));

        // 숫자 속성
        def.fontSize(parseDoubleAttr(styleElem, "PointSize"));
        def.firstLineIndent(parseDoubleAttr(styleElem, "FirstLineIndent"));
        def.leftIndent(parseDoubleAttr(styleElem, "LeftIndent"));
        def.rightIndent(parseDoubleAttr(styleElem, "RightIndent"));
        def.spaceBefore(parseDoubleAttr(styleElem, "SpaceBefore"));
        def.spaceAfter(parseDoubleAttr(styleElem, "SpaceAfter"));
        def.horizontalScale(parseDoubleAttr(styleElem, "HorizontalScale"));
        def.tracking(parseDoubleAttr(styleElem, "Tracking"));

        // Properties 안의 값들
        Element props = getFirstChildElement(styleElem, "Properties");
        if (props != null) {
            def.basedOn(getPropertyText(props, "BasedOn"));
            def.fontFamily(getPropertyText(props, "AppliedFont"));

            // Leading (행간)
            String leadingText = getPropertyText(props, "Leading");
            if (leadingText != null) {
                if ("Auto".equalsIgnoreCase(leadingText)) {
                    def.leadingType("Auto");
                } else {
                    try {
                        def.leading(Double.parseDouble(leadingText));
                        def.leadingType("Fixed");
                    } catch (NumberFormatException e) {
                        def.leadingType(leadingText);
                    }
                }
            }
        }

        // Bold/Italic 판별 (FontStyle 속성에서)
        String fontStyle = def.fontStyle();
        if (fontStyle != null) {
            String lower = fontStyle.toLowerCase();
            def.bold(lower.contains("bold"));
            def.italic(lower.contains("italic") || lower.contains("oblique"));
        }

        return def;
    }

    // ===== Resources/Graphic.xml 파싱 =====

    private static void parseGraphic(Document graphicDoc, IDMLDocument doc) {
        NodeList colors = graphicDoc.getElementsByTagName("Color");
        for (int i = 0; i < colors.getLength(); i++) {
            Element colorElem = (Element) colors.item(i);
            String selfRef = colorElem.getAttribute("Self");
            String colorValue = colorElem.getAttribute("ColorValue");
            String model = colorElem.getAttribute("Model");
            String space = colorElem.getAttribute("Space");

            String hexColor = convertColorToHex(colorValue, model, space);
            if (hexColor != null) {
                doc.putColor(selfRef, hexColor);
            }
        }
    }

    /**
     * CMYK 또는 RGB ColorValue를 #RRGGBB로 변환한다.
     */
    public static String convertColorToHex(String colorValue, String model, String space) {
        if (colorValue == null || colorValue.isEmpty()) return null;

        String[] parts = colorValue.trim().split("\\s+");
        if ("CMYK".equals(space) && parts.length >= 4) {
            double c = Double.parseDouble(parts[0]) / 100.0;
            double m = Double.parseDouble(parts[1]) / 100.0;
            double y = Double.parseDouble(parts[2]) / 100.0;
            double k = Double.parseDouble(parts[3]) / 100.0;
            int r = (int) Math.round(255 * (1 - c) * (1 - k));
            int g = (int) Math.round(255 * (1 - m) * (1 - k));
            int b = (int) Math.round(255 * (1 - y) * (1 - k));
            r = Math.max(0, Math.min(255, r));
            g = Math.max(0, Math.min(255, g));
            b = Math.max(0, Math.min(255, b));
            return String.format("#%02X%02X%02X", r, g, b);
        } else if ("RGB".equals(space) && parts.length >= 3) {
            int r = Math.max(0, Math.min(255, (int) Math.round(Double.parseDouble(parts[0]))));
            int g = Math.max(0, Math.min(255, (int) Math.round(Double.parseDouble(parts[1]))));
            int b = Math.max(0, Math.min(255, (int) Math.round(Double.parseDouble(parts[2]))));
            return String.format("#%02X%02X%02X", r, g, b);
        }

        return null;
    }

    // ===== Spread XML 파싱 =====

    private static IDMLSpread parseSpread(Document spreadDoc, Set<String> hiddenLayerIds) {
        IDMLSpread spread = new IDMLSpread();

        // Spread 루트 요소 찾기
        NodeList spreadNodes = spreadDoc.getElementsByTagName("Spread");
        if (spreadNodes.getLength() == 0) return spread;

        Element spreadElem = (Element) spreadNodes.item(0);
        spread.selfId(spreadElem.getAttribute("Self"));

        // Page, TextFrame, Group 처리
        NodeList children = spreadElem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element elem = (Element) node;

            // 숨겨진 레이어에 속한 요소는 건너뛴다
            String itemLayer = getAttrOrNull(elem, "ItemLayer");
            if (itemLayer != null && hiddenLayerIds.contains(itemLayer)) continue;

            if ("Page".equals(elem.getTagName())) {
                spread.addPage(parsePage(elem));
            } else if ("TextFrame".equals(elem.getTagName())) {
                IDMLTextFrame frame = parseTextFrame(elem);
                if (frame != null) {
                    spread.addTextFrame(frame);
                }
            } else if ("Rectangle".equals(elem.getTagName())
                    || "Polygon".equals(elem.getTagName())
                    || "Oval".equals(elem.getTagName())) {
                IDMLImageFrame imageFrame = tryParseImageFrame(elem);
                if (imageFrame != null) {
                    spread.addImageFrame(imageFrame);
                }
            } else if ("Group".equals(elem.getTagName())) {
                double[] groupTransform = IDMLGeometry.parseTransform(
                        elem.getAttribute("ItemTransform"));
                parseGroupForFrames(elem, spread, groupTransform, hiddenLayerIds);
            }
        }

        return spread;
    }

    private static IDMLPage parsePage(Element pageElem) {
        IDMLPage page = new IDMLPage();
        page.selfId(pageElem.getAttribute("Self"));
        page.name(pageElem.getAttribute("Name"));
        page.geometricBounds(IDMLGeometry.parseBounds(
                pageElem.getAttribute("GeometricBounds")));
        page.itemTransform(IDMLGeometry.parseTransform(
                pageElem.getAttribute("ItemTransform")));
        page.appliedMasterSpread(getAttrOrNull(pageElem, "AppliedMaster"));

        // MarginPreference
        Element marginPref = getFirstChildElement(pageElem, "MarginPreference");
        if (marginPref != null) {
            page.marginTop(parseDoubleAttrDef(marginPref, "Top", 0));
            page.marginBottom(parseDoubleAttrDef(marginPref, "Bottom", 0));
            page.marginLeft(parseDoubleAttrDef(marginPref, "Left", 0));
            page.marginRight(parseDoubleAttrDef(marginPref, "Right", 0));
            page.columnCount(parseIntAttr(marginPref, "ColumnCount", 1));
        }

        return page;
    }

    private static IDMLTextFrame parseTextFrame(Element frameElem) {
        String contentType = frameElem.getAttribute("ContentType");
        // ContentType가 "GraphicType"이면 텍스트 프레임이 아님
        if ("GraphicType".equals(contentType)) return null;

        IDMLTextFrame frame = new IDMLTextFrame();
        frame.selfId(frameElem.getAttribute("Self"));
        frame.parentStoryId(getAttrOrNull(frameElem, "ParentStory"));
        frame.geometricBounds(resolveGeometricBounds(frameElem));
        frame.itemTransform(IDMLGeometry.parseTransform(
                frameElem.getAttribute("ItemTransform")));
        frame.appliedObjectStyle(getAttrOrNull(frameElem, "AppliedObjectStyle"));
        frame.fillColor(getAttrOrNull(frameElem, "FillColor"));
        frame.previousTextFrame(getAttrOrNull(frameElem, "PreviousTextFrame"));
        frame.nextTextFrame(getAttrOrNull(frameElem, "NextTextFrame"));

        // TextFramePreference에서 columnCount
        Element tfPref = getFirstChildElement(frameElem, "TextFramePreference");
        if (tfPref != null) {
            frame.columnCount(parseIntAttr(tfPref, "TextColumnCount", 1));
        }

        return frame;
    }

    /**
     * Rectangle/Polygon/Oval에서 이미지 프레임인지 확인하고 파싱한다.
     * 내부에 Image + Link가 있으면 이미지 프레임.
     */
    private static IDMLImageFrame tryParseImageFrame(Element shapeElem) {
        // 내부에 Image, PDF, EPS가 있는지 확인
        List<Element> images = getDescendantElements(shapeElem, "Image");
        if (images.isEmpty()) images = getDescendantElements(shapeElem, "PDF");
        if (images.isEmpty()) images = getDescendantElements(shapeElem, "EPS");
        if (images.isEmpty()) return null;

        Element imageElem = images.get(0);
        List<Element> links = getChildElements(imageElem, "Link");

        IDMLImageFrame frame = new IDMLImageFrame();
        frame.selfId(shapeElem.getAttribute("Self"));
        frame.geometricBounds(resolveGeometricBounds(shapeElem));
        frame.itemTransform(IDMLGeometry.parseTransform(
                shapeElem.getAttribute("ItemTransform")));
        frame.appliedObjectStyle(getAttrOrNull(shapeElem, "AppliedObjectStyle"));

        if (!links.isEmpty()) {
            Element link = links.get(0);
            frame.linkResourceURI(getAttrOrNull(link, "LinkResourceURI"));
            frame.linkStoredState(getAttrOrNull(link, "StoredState"));
            frame.linkResourceFormat(getAttrOrNull(link, "LinkResourceFormat"));
        }

        return frame;
    }

    /**
     * Group 내부의 TextFrame과 이미지 프레임을 재귀적으로 수집한다.
     * Group의 ItemTransform을 자식 프레임의 ItemTransform에 결합하여
     * 절대 좌표를 올바르게 계산한다.
     *
     * @param groupElem        Group 요소
     * @param spread           프레임을 추가할 Spread
     * @param accumulatedTransform 이 Group까지의 누적 변환 행렬
     * @param hiddenLayerIds   숨겨진 레이어 ID 집합
     */
    private static void parseGroupForFrames(Element groupElem, IDMLSpread spread,
                                             double[] accumulatedTransform,
                                             Set<String> hiddenLayerIds) {
        // Group 자체가 숨겨진 레이어에 속하면 전체 건너뛰기
        String groupLayer = getAttrOrNull(groupElem, "ItemLayer");
        if (groupLayer != null && hiddenLayerIds.contains(groupLayer)) return;

        NodeList children = groupElem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element elem = (Element) node;

            // 자식 요소 레이어 확인
            String itemLayer = getAttrOrNull(elem, "ItemLayer");
            if (itemLayer != null && hiddenLayerIds.contains(itemLayer)) continue;

            if ("TextFrame".equals(elem.getTagName())) {
                IDMLTextFrame frame = parseTextFrame(elem);
                if (frame != null) {
                    // 프레임의 ItemTransform에 Group 변환을 결합
                    frame.itemTransform(CoordinateConverter.combineTransforms(
                            accumulatedTransform, frame.itemTransform()));
                    spread.addTextFrame(frame);
                }
            } else if ("Rectangle".equals(elem.getTagName())
                    || "Polygon".equals(elem.getTagName())
                    || "Oval".equals(elem.getTagName())) {
                IDMLImageFrame imageFrame = tryParseImageFrame(elem);
                if (imageFrame != null) {
                    // 이미지 프레임의 ItemTransform에 Group 변환을 결합
                    imageFrame.itemTransform(CoordinateConverter.combineTransforms(
                            accumulatedTransform, imageFrame.itemTransform()));
                    spread.addImageFrame(imageFrame);
                }
            } else if ("Group".equals(elem.getTagName())) {
                // 중첩 Group: 누적 변환에 현재 Group 변환을 결합
                double[] childGroupTransform = IDMLGeometry.parseTransform(
                        elem.getAttribute("ItemTransform"));
                double[] combined = CoordinateConverter.combineTransforms(
                        accumulatedTransform, childGroupTransform);
                parseGroupForFrames(elem, spread, combined, hiddenLayerIds);
            }
        }
    }

    // ===== Story XML 파싱 =====

    private static IDMLStory parseStory(Document storyDoc, String storyId) {
        IDMLStory story = new IDMLStory();
        story.selfId(storyId);

        NodeList paraRanges = storyDoc.getElementsByTagName("ParagraphStyleRange");
        for (int i = 0; i < paraRanges.getLength(); i++) {
            Element paraRange = (Element) paraRanges.item(i);
            IDMLParagraph para = parseParagraph(paraRange);
            story.addParagraph(para);
        }

        return story;
    }

    private static IDMLParagraph parseParagraph(Element paraRange) {
        IDMLParagraph para = new IDMLParagraph();
        para.appliedParagraphStyle(getAttrOrNull(paraRange, "AppliedParagraphStyle"));

        List<Element> charRanges = getChildElements(paraRange, "CharacterStyleRange");
        for (Element charRange : charRanges) {
            IDMLCharacterRun run = parseCharacterRun(charRange);
            para.addCharacterRun(run);
        }

        return para;
    }

    private static IDMLCharacterRun parseCharacterRun(Element charRange) {
        IDMLCharacterRun run = new IDMLCharacterRun();
        run.appliedCharacterStyle(getAttrOrNull(charRange, "AppliedCharacterStyle"));
        run.fontStyle(getAttrOrNull(charRange, "FontStyle"));
        run.fillColor(getAttrOrNull(charRange, "FillColor"));
        run.position(getAttrOrNull(charRange, "Position"));
        run.fontSize(parseDoubleAttr(charRange, "PointSize"));

        // Properties 안의 AppliedFont
        Element props = getFirstChildElement(charRange, "Properties");
        if (props != null) {
            String fontFamily = getPropertyText(props, "AppliedFont");
            if (fontFamily != null) {
                run.fontFamily(fontFamily);
            }
        }

        // Content 텍스트 수집
        StringBuilder contentBuilder = new StringBuilder();
        NodeList children = charRange.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element elem = (Element) node;

            if ("Content".equals(elem.getTagName())) {
                contentBuilder.append(elem.getTextContent());
            } else if ("Br".equals(elem.getTagName())) {
                contentBuilder.append("\n");
            } else if ("TextFrame".equals(elem.getTagName())) {
                // 인라인 TextFrame (분수, limit 등)
                IDMLTextFrame inlineFrame = new IDMLTextFrame();
                inlineFrame.selfId(elem.getAttribute("Self"));
                inlineFrame.parentStoryId(getAttrOrNull(elem, "ParentStory"));
                inlineFrame.appliedObjectStyle(getAttrOrNull(elem, "AppliedObjectStyle"));
                run.addInlineFrame(inlineFrame);
            }
        }

        String content = contentBuilder.toString();
        if (!content.isEmpty()) {
            run.content(content);
        }

        return run;
    }

    // ===== 페이지 번호 관련 =====

    /**
     * Section 정보를 기반으로 페이지의 실제 번호를 결정한다.
     */
    private static int resolvePageNumber(int pageIndex, String pageId,
                                         List<SectionInfo> sections) {
        // Section이 없으면 순차 번호
        if (sections.isEmpty()) return pageIndex;

        // 가장 마지막으로 시작된 Section을 찾는다
        // Section.PageStart는 해당 Section이 시작하는 Page의 Self ID
        SectionInfo applicableSection = null;
        int pagesBeforeSection = 0;
        int currentPageCount = 0;

        for (SectionInfo section : sections) {
            if (section.pageStart != null && !section.pageStart.isEmpty()) {
                // 이 Section의 시작 페이지 위치를 기반으로 판단
                // pageIndex가 이 Section 범위에 속하는지 확인
                if (currentPageCount < pageIndex) {
                    applicableSection = section;
                    pagesBeforeSection = currentPageCount;
                }
                currentPageCount += section.length;
            }
        }

        if (applicableSection != null) {
            int offset = pageIndex - pagesBeforeSection - 1;
            return applicableSection.pageNumberStart + offset;
        }

        return pageIndex;
    }

    /**
     * 모든 스프레드에서 참조하는 Story ID를 수집한다.
     */
    private static Set<String> collectNeededStoryIds(IDMLDocument doc) {
        Set<String> storyIds = new LinkedHashSet<String>();
        for (IDMLSpread spread : doc.spreads()) {
            for (IDMLTextFrame frame : spread.textFrames()) {
                if (frame.parentStoryId() != null) {
                    storyIds.add(frame.parentStoryId());
                }
            }
        }
        return storyIds;
    }

    // ===== ZIP 처리 =====

    private static File extractZip(File zipFile) throws ConvertException {
        try {
            File tempDir = File.createTempFile("idml_", "_extract");
            tempDir.delete();
            tempDir.mkdirs();

            ZipFile zip = new ZipFile(zipFile);
            try {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    File outFile = new File(tempDir, entry.getName());

                    if (entry.isDirectory()) {
                        outFile.mkdirs();
                    } else {
                        outFile.getParentFile().mkdirs();
                        InputStream in = zip.getInputStream(entry);
                        try {
                            FileOutputStream out = new FileOutputStream(outFile);
                            try {
                                byte[] buf = new byte[8192];
                                int len;
                                while ((len = in.read(buf)) > 0) {
                                    out.write(buf, 0, len);
                                }
                            } finally {
                                out.close();
                            }
                        } finally {
                            in.close();
                        }
                    }
                }
            } finally {
                zip.close();
            }

            return tempDir;
        } catch (IOException e) {
            throw new ConvertException(ConvertException.Phase.LOADING,
                    "Failed to extract IDML ZIP: " + e.getMessage(), e);
        }
    }

    private static void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirectory(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }

    // ===== GeometricBounds 해석 =====

    /**
     * 요소의 GeometricBounds를 결정한다.
     * 직접 속성이 있으면 사용하고, 없으면 PathGeometry의 PathPointArray에서 계산.
     */
    private static double[] resolveGeometricBounds(Element elem) {
        String boundsAttr = elem.getAttribute("GeometricBounds");
        if (boundsAttr != null && !boundsAttr.isEmpty()) {
            return IDMLGeometry.parseBounds(boundsAttr);
        }

        // PathGeometry에서 bounds 계산
        return computeBoundsFromPathGeometry(elem);
    }

    /**
     * PathGeometry > GeometryPathType > PathPointArray > PathPointType[Anchor]
     * 에서 bounding box를 계산한다.
     */
    private static double[] computeBoundsFromPathGeometry(Element elem) {
        List<Element> pathPoints = getDescendantElements(elem, "PathPointType");
        if (pathPoints.isEmpty()) {
            return new double[]{0, 0, 0, 0};
        }

        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        for (Element pp : pathPoints) {
            String anchor = pp.getAttribute("Anchor");
            if (anchor == null || anchor.isEmpty()) continue;
            String[] parts = anchor.trim().split("\\s+");
            if (parts.length >= 2) {
                double x = Double.parseDouble(parts[0]);
                double y = Double.parseDouble(parts[1]);
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }

        if (minX == Double.MAX_VALUE) {
            return new double[]{0, 0, 0, 0};
        }

        // GeometricBounds: [top, left, bottom, right]
        return new double[]{minY, minX, maxY, maxX};
    }

    // ===== XML 유틸리티 =====

    private static Document parseXML(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(file);
    }

    private static List<Element> getChildElements(Element parent, String tagName) {
        List<Element> result = new ArrayList<Element>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && tagName.equals(node.getNodeName())) {
                result.add((Element) node);
            }
        }
        return result;
    }

    /**
     * 자손 요소 중 특정 태그명의 요소를 재귀적으로 검색한다.
     */
    private static List<Element> getDescendantElements(Element parent, String tagName) {
        List<Element> result = new ArrayList<Element>();
        NodeList descendants = parent.getElementsByTagName(tagName);
        for (int i = 0; i < descendants.getLength(); i++) {
            result.add((Element) descendants.item(i));
        }
        return result;
    }

    private static Element getFirstChildElement(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && tagName.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }

    /**
     * Properties 블록 안의 특정 요소의 텍스트 내용을 가져온다.
     * 예: <Properties><AppliedFont type="string">Myriad Pro</AppliedFont></Properties>
     */
    private static String getPropertyText(Element propsElem, String propertyName) {
        NodeList children = propsElem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && propertyName.equals(node.getNodeName())) {
                String text = node.getTextContent();
                return (text != null && !text.trim().isEmpty()) ? text.trim() : null;
            }
        }
        return null;
    }

    private static String getAttrOrNull(Element elem, String attrName) {
        String val = elem.getAttribute(attrName);
        return (val != null && !val.isEmpty()) ? val : null;
    }

    private static Double parseDoubleAttr(Element elem, String attrName) {
        String val = elem.getAttribute(attrName);
        if (val == null || val.isEmpty()) return null;
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double parseDoubleAttrDef(Element elem, String attrName, double defaultVal) {
        Double val = parseDoubleAttr(elem, attrName);
        return val != null ? val : defaultVal;
    }

    private static int parseIntAttr(Element elem, String attrName, int defaultVal) {
        String val = elem.getAttribute(attrName);
        if (val == null || val.isEmpty()) return defaultVal;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    // ===== 내부 데이터 =====

    /**
     * designmap.xml의 Section 정보.
     */
    static class SectionInfo {
        String selfId;
        String pageStart;
        int pageNumberStart;
        int length;
        String name;
        String marker;
    }
}
