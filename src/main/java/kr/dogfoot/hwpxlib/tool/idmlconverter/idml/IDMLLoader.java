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
            List<String> masterSpreadSources = new ArrayList<String>();
            List<SectionInfo> sections = new ArrayList<SectionInfo>();
            parseDesignmap(designmap, spreadSources, masterSpreadSources, sections, doc);

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

            // 4.5. 마스터 스프레드 로드 (마진 정보 수집 + IDMLSpread 객체 생성)
            Map<String, MasterPageMargins> masterMargins = new HashMap<String, MasterPageMargins>();
            for (String masterSrc : masterSpreadSources) {
                File masterFile = new File(dir, masterSrc);
                if (masterFile.exists()) {
                    Document masterDoc = parseXML(masterFile);
                    parseMasterSpreadForMargins(masterDoc, masterMargins);

                    // 마스터 스프레드를 IDMLSpread 객체로도 로드
                    IDMLSpread masterSpread = parseSpread(masterDoc, doc.hiddenLayerIds());
                    if (masterSpread.selfId() != null) {
                        doc.addMasterSpread(masterSpread.selfId(), masterSpread);
                    }
                }
            }

            // 5. 스프레드 로드 (페이지 + 프레임)
            int pageIndex = 0;
            for (String spreadSrc : spreadSources) {
                File spreadFile = new File(dir, spreadSrc);
                if (spreadFile.exists()) {
                    IDMLSpread spread = parseSpread(parseXML(spreadFile), doc.hiddenLayerIds());
                    // 페이지 번호 할당 및 마스터 마진 상속
                    for (IDMLPage page : spread.pages()) {
                        pageIndex++;
                        int pageNum = resolvePageNumber(pageIndex, page.selfId(), sections);
                        page.pageNumber(pageNum);

                        // 마스터 마진 상속 (로컬 마진이 모두 0인 경우)
                        if (page.appliedMasterSpread() != null && isAllMarginsZero(page)) {
                            MasterPageMargins master = masterMargins.get(page.appliedMasterSpread());
                            if (master != null) {
                                page.marginTop(master.marginTop);
                                page.marginBottom(master.marginBottom);
                                page.marginLeft(master.marginLeft);
                                page.marginRight(master.marginRight);
                                if (page.columnCount() <= 1 && master.columnCount > 1) {
                                    page.columnCount(master.columnCount);
                                    page.columnGutter(master.columnGutter);
                                }
                            }
                        }
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

                // 7-1. 로드된 Story 내 인라인 TextFrame이 참조하는 추가 Story를 재귀 로드
                loadReferencedInlineStories(doc, storiesDir);
            }

            // 8. Story에서 인라인 그래픽(앵커 오브젝트) 추출 및 스프레드에 추가
            extractInlineGraphicsFromStories(doc, storiesDir, neededStoryIds);

            // 9. GREP 스타일에서 BT수식M 폰트가 동적 적용되는 런 해석
            resolveGrepMathStyles(doc);

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
                                       List<String> masterSpreadSources,
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
            } else if ("idPkg:MasterSpread".equals(tagName)) {
                String src = elem.getAttribute("src");
                if (src != null && !src.isEmpty()) {
                    masterSpreadSources.add(src);
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

        // Word Spacing (DesiredWordSpacing, MinimumWordSpacing, MaximumWordSpacing)
        def.desiredWordSpacing(parseDoubleAttr(styleElem, "DesiredWordSpacing"));
        def.minimumWordSpacing(parseDoubleAttr(styleElem, "MinimumWordSpacing"));
        def.maximumWordSpacing(parseDoubleAttr(styleElem, "MaximumWordSpacing"));

        // AutoLeading (퍼센트 값)
        def.autoLeading(parseDoubleAttr(styleElem, "AutoLeading"));

        // TabList (탭 정지점 목록)
        Element props2 = getFirstChildElement(styleElem, "Properties");
        if (props2 != null) {
            // AllGREPStyles (GREP 스타일 규칙 — 단락 스타일에서만 존재)
            Element allGrepStyles = getFirstChildElement(props2, "AllGREPStyles");
            if (allGrepStyles != null) {
                List<Element> grepItems = getChildElements(allGrepStyles, "ListItem");
                for (Element item : grepItems) {
                    String charStyle = getChildElementText(item, "AppliedCharacterStyle");
                    String grepExpr = getChildElementText(item, "GrepExpression");
                    if (charStyle != null && grepExpr != null) {
                        def.addGrepStyle(new IDMLStyleDef.GrepStyleRule(grepExpr, charStyle));
                    }
                }
            }

            Element tabList = getFirstChildElement(props2, "TabList");
            if (tabList != null) {
                List<Element> listItems = getChildElements(tabList, "ListItem");
                for (Element item : listItems) {
                    Double position = parseChildElementDouble(item, "Position");
                    String alignment = getChildElementText(item, "Alignment");
                    String leader = getChildElementText(item, "Leader");
                    if (position != null) {
                        def.addTabStop(new IDMLStyleDef.TabStop(position, alignment, leader));
                    }
                }
            }
        }

        return def;
    }

    // ===== Resources/Graphic.xml 파싱 =====

    private static void parseGraphic(Document graphicDoc, IDMLDocument doc) {
        // 색상 파싱
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

        // 그레이디언트 파싱: 첫 번째 GradientStop의 색상을 대표 색상으로 등록
        NodeList gradients = graphicDoc.getElementsByTagName("Gradient");
        for (int i = 0; i < gradients.getLength(); i++) {
            Element gradientElem = (Element) gradients.item(i);
            String selfRef = gradientElem.getAttribute("Self");

            // 첫 번째 GradientStop의 StopColor를 찾아 대표 색상으로 사용
            NodeList stops = gradientElem.getElementsByTagName("GradientStop");
            if (stops.getLength() > 0) {
                Element firstStop = (Element) stops.item(0);
                String stopColorRef = firstStop.getAttribute("StopColor");
                if (stopColorRef != null && !stopColorRef.isEmpty()) {
                    String hexColor = doc.getColor(stopColorRef);
                    if (hexColor != null) {
                        doc.putColor(selfRef, hexColor);
                    }
                }
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
            return kr.dogfoot.hwpxlib.tool.idmlconverter.util.CMYKColorConverter.cmykToHex(c, m, y, k);
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

        // Spread 또는 MasterSpread 루트 요소 찾기
        NodeList spreadNodes = spreadDoc.getElementsByTagName("Spread");
        if (spreadNodes.getLength() == 0) {
            spreadNodes = spreadDoc.getElementsByTagName("MasterSpread");
        }
        if (spreadNodes.getLength() == 0) return spread;

        Element spreadElem = (Element) spreadNodes.item(0);
        spread.selfId(spreadElem.getAttribute("Self"));

        // Page, TextFrame, Group 처리 (z-order 순서 추적)
        int[] zOrderCounter = {0};  // 배열로 래핑하여 람다/내부 메서드에서 수정 가능
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
                    imageFrame.zOrder(zOrderCounter[0]++);
                    spread.addImageFrame(imageFrame);
                } else {
                    // 이미지가 없으면 순수 벡터 도형으로 파싱
                    IDMLVectorShape vectorShape = tryParseVectorShape(elem);
                    if (vectorShape != null) {
                        vectorShape.zOrder(zOrderCounter[0]++);
                        spread.addVectorShape(vectorShape);
                    }
                    // 클리핑 자식이 있으면 Group 자식이 이미 수집됨 → extractGroups 건너뛰기
                    if (vectorShape == null || !vectorShape.hasClippedChildren()) {
                        double[] frameTransform = IDMLGeometry.parseTransform(
                                elem.getAttribute("ItemTransform"));
                        extractGroupsFromFrame(elem, spread, frameTransform,
                                hiddenLayerIds, zOrderCounter);
                    }
                }
            } else if ("GraphicLine".equals(elem.getTagName())) {
                // 그래픽 라인도 벡터 도형으로 처리
                IDMLVectorShape vectorShape = tryParseVectorShape(elem);
                if (vectorShape != null) {
                    vectorShape.shapeType(IDMLVectorShape.ShapeType.GRAPHIC_LINE);
                    vectorShape.zOrder(zOrderCounter[0]++);
                    spread.addVectorShape(vectorShape);
                }
            } else if ("Group".equals(elem.getTagName())) {
                double[] groupTransform = IDMLGeometry.parseTransform(
                        elem.getAttribute("ItemTransform"));
                String groupSelfId = elem.getAttribute("Self");
                parseGroupForFrames(elem, spread, groupTransform, hiddenLayerIds, zOrderCounter, groupSelfId);

                // 그룹 구조도 보존 (글상자 변환용)
                IDMLGroup group = parseGroupAsObject(elem, groupTransform, hiddenLayerIds);
                if (group != null) {
                    spread.addGroup(group);
                }
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
            page.columnGutter(parseDoubleAttrDef(marginPref, "ColumnGutter", 12.0));
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

        // Stroke/Outline properties
        frame.strokeColor(getAttrOrNull(frameElem, "StrokeColor"));
        frame.strokeWeight(parseDoubleAttrDef(frameElem, "StrokeWeight", 0));
        frame.cornerRadius(parseDoubleAttrDef(frameElem, "CornerRadius", 0));
        frame.fillTint(parseDoubleAttrDef(frameElem, "FillTint", 100));
        frame.strokeTint(parseDoubleAttrDef(frameElem, "StrokeTint", 100));

        // StrokeType (Solid, Dashed, Dotted, etc.)
        String strokeType = getAttrOrNull(frameElem, "StrokeType");
        if (strokeType != null) {
            if (strokeType.contains("Dashed")) {
                frame.strokeType("Dashed");
            } else if (strokeType.contains("Dotted")) {
                frame.strokeType("Dotted");
            } else if (strokeType.contains("Solid")) {
                frame.strokeType("Solid");
            } else {
                frame.strokeType(strokeType);
            }
        }

        // Per-corner radius
        double tlRadius = parseDoubleAttrDef(frameElem, "TopLeftCornerRadius", -1);
        double trRadius = parseDoubleAttrDef(frameElem, "TopRightCornerRadius", -1);
        double blRadius = parseDoubleAttrDef(frameElem, "BottomLeftCornerRadius", -1);
        double brRadius = parseDoubleAttrDef(frameElem, "BottomRightCornerRadius", -1);
        if (tlRadius >= 0 || trRadius >= 0 || blRadius >= 0 || brRadius >= 0) {
            double defaultRadius = frame.cornerRadius();
            frame.cornerRadii(new double[]{
                    tlRadius >= 0 ? tlRadius : defaultRadius,
                    trRadius >= 0 ? trRadius : defaultRadius,
                    blRadius >= 0 ? blRadius : defaultRadius,
                    brRadius >= 0 ? brRadius : defaultRadius
            });
        }

        // TextFramePreference에서 컬럼 정보 파싱
        Element tfPref = getFirstChildElement(frameElem, "TextFramePreference");
        if (tfPref != null) {
            frame.columnCount(parseIntAttr(tfPref, "TextColumnCount", 1));
            frame.columnGutter(parseDoubleAttrDef(tfPref, "TextColumnGutter", 12.0));

            // InsetSpacing (속성 단일 값 또는 Properties 리스트)
            String insetStr = tfPref.getAttribute("InsetSpacing");
            if (insetStr != null && !insetStr.isEmpty()) {
                try {
                    double inset = Double.parseDouble(insetStr);
                    frame.insetSpacing(new double[]{inset, inset, inset, inset});
                } catch (NumberFormatException ignored) {}
            }
            if (frame.insetSpacing() == null) {
                Element tfProps = getFirstChildElement(tfPref, "Properties");
                if (tfProps != null) {
                    Element insetElem = getFirstChildElement(tfProps, "InsetSpacing");
                    if (insetElem != null) {
                        List<Element> items = getChildElements(insetElem, "ListItem");
                        if (items.size() >= 4) {
                            try {
                                double[] insets = new double[4];
                                for (int si = 0; si < 4; si++) {
                                    insets[si] = Double.parseDouble(items.get(si).getTextContent().trim());
                                }
                                frame.insetSpacing(insets);
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }

            // 컬럼 유형 (고정 수, 고정 너비, 가변 너비)
            boolean useFixedWidth = "true".equalsIgnoreCase(tfPref.getAttribute("UseFixedColumnWidth"));
            if (useFixedWidth) {
                frame.columnType("FixedWidth");
                frame.columnFixedWidth(parseDoubleAttrDef(tfPref, "TextColumnFixedWidth", 0));
            } else {
                // ColumnWidths가 있으면 FlexibleWidth, 없으면 FixedNumber
                Element props = getFirstChildElement(tfPref, "Properties");
                if (props != null) {
                    Element textColumnWidths = getFirstChildElement(props, "TextColumnWidths");
                    if (textColumnWidths != null) {
                        List<Element> listItems = getChildElements(textColumnWidths, "ListItem");
                        if (!listItems.isEmpty()) {
                            frame.columnType("FlexibleWidth");
                            double[] widths = new double[listItems.size()];
                            for (int w = 0; w < listItems.size(); w++) {
                                String widthText = listItems.get(w).getTextContent();
                                if (widthText != null && !widthText.isEmpty()) {
                                    try {
                                        widths[w] = Double.parseDouble(widthText.trim());
                                    } catch (NumberFormatException ignored) {}
                                }
                            }
                            frame.columnWidths(widths);
                        }
                    }
                }
            }

            // 수직 정렬
            String vJust = getAttrOrNull(tfPref, "VerticalJustification");
            if (vJust != null) {
                frame.verticalJustification(vJust);
            }

            // 텍스트 감싸기 무시
            frame.ignoreWrap("true".equalsIgnoreCase(tfPref.getAttribute("IgnoreWrap")));

            // 단 경계선 (Column Rule)
            frame.useColumnRule("true".equalsIgnoreCase(tfPref.getAttribute("UseColumnRulePlacement")));
            frame.columnRuleWidth(parseDoubleAttrDef(tfPref, "ColumnRuleStrokeWidth", 1.0));
            String ruleType = getAttrOrNull(tfPref, "ColumnRuleStrokeType");
            if (ruleType != null) {
                frame.columnRuleType(ruleType);
            }
            String ruleColor = getAttrOrNull(tfPref, "ColumnRuleStrokeColor");
            if (ruleColor != null) {
                frame.columnRuleColor(ruleColor);
            }
            frame.columnRuleTint(parseDoubleAttrDef(tfPref, "ColumnRuleStrokeTint", 100));
            frame.columnRuleOffset(parseDoubleAttrDef(tfPref, "ColumnRuleOffset", 0));
            frame.columnRuleInsetWidth(parseDoubleAttrDef(tfPref, "ColumnRuleInsetWidth", 0));
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

        // 이미지의 ItemTransform (클리핑을 위한 이미지 위치/스케일)
        String imgTransformStr = imageElem.getAttribute("ItemTransform");
        if (imgTransformStr != null && !imgTransformStr.isEmpty()) {
            frame.imageTransform(IDMLGeometry.parseTransform(imgTransformStr));
        }

        // GraphicBounds (원본 이미지 크기)
        Element imgProps = getFirstChildElement(imageElem, "Properties");
        if (imgProps != null) {
            Element graphicBoundsElem = getFirstChildElement(imgProps, "GraphicBounds");
            if (graphicBoundsElem != null) {
                double left = parseDoubleAttrDef(graphicBoundsElem, "Left", 0);
                double top = parseDoubleAttrDef(graphicBoundsElem, "Top", 0);
                double right = parseDoubleAttrDef(graphicBoundsElem, "Right", 0);
                double bottom = parseDoubleAttrDef(graphicBoundsElem, "Bottom", 0);
                frame.graphicBounds(new double[]{left, top, right, bottom});
            }
        }

        if (!links.isEmpty()) {
            Element link = links.get(0);
            frame.linkResourceURI(getAttrOrNull(link, "LinkResourceURI"));
            frame.linkStoredState(getAttrOrNull(link, "StoredState"));
            frame.linkResourceFormat(getAttrOrNull(link, "LinkResourceFormat"));
        }

        return frame;
    }

    /**
     * Rectangle/Polygon/Oval/GraphicLine을 벡터 도형으로 파싱한다.
     */
    private static IDMLVectorShape tryParseVectorShape(Element shapeElem) {
        IDMLVectorShape shape = new IDMLVectorShape();
        shape.selfId(shapeElem.getAttribute("Self"));
        shape.geometricBounds(resolveGeometricBounds(shapeElem));
        shape.itemTransform(IDMLGeometry.parseTransform(
                shapeElem.getAttribute("ItemTransform")));

        // 도형 타입 설정
        String tagName = shapeElem.getTagName();
        if ("Rectangle".equals(tagName)) {
            shape.shapeType(IDMLVectorShape.ShapeType.RECTANGLE);
        } else if ("Polygon".equals(tagName)) {
            shape.shapeType(IDMLVectorShape.ShapeType.POLYGON);
        } else if ("Oval".equals(tagName)) {
            shape.shapeType(IDMLVectorShape.ShapeType.OVAL);
        } else if ("GraphicLine".equals(tagName)) {
            shape.shapeType(IDMLVectorShape.ShapeType.GRAPHIC_LINE);
        }

        // 스타일 속성
        String fillColor = getAttrOrNull(shapeElem, "FillColor");
        String strokeColor = getAttrOrNull(shapeElem, "StrokeColor");
        double strokeWeight = parseDoubleAttrDef(shapeElem, "StrokeWeight", 1.0);

        shape.fillColor(fillColor);
        shape.strokeColor(strokeColor);
        shape.strokeWeight(strokeWeight);

        // 클리핑 프레임 패턴 감지:
        // 1) 외부 Rectangle(채우기 없음)이 내부 자식 도형을 클리핑
        // 2) ContentType=GraphicType인 프레임이 내부 자식 도형을 클리핑 (채우기 유무 무관)
        boolean isGraphicFrame = "GraphicType".equals(shapeElem.getAttribute("ContentType"));
        if (!shape.hasFill() || isGraphicFrame) {
            NodeList childNodes = shapeElem.getChildNodes();
            for (int i = 0; i < childNodes.getLength(); i++) {
                Node child = childNodes.item(i);
                if (child.getNodeType() != Node.ELEMENT_NODE) continue;
                Element childElem = (Element) child;
                String childTag = childElem.getTagName();
                if ("Rectangle".equals(childTag) || "Polygon".equals(childTag)
                        || "Oval".equals(childTag)) {
                    IDMLVectorShape childShape = tryParseVectorShape(childElem);
                    if (childShape != null && childShape.hasFill()) {
                        shape.clippedChild(childShape);
                        break;
                    }
                } else if ("Group".equals(childTag)) {
                    // Group 자식 클리핑: 외부 프레임이 클리핑 마스크, 내부 Group의 도형이 피클리핑 대상
                    double[] groupTransform = IDMLGeometry.parseTransform(
                            childElem.getAttribute("ItemTransform"));
                    collectClippedChildrenFromGroup(childElem, shape, groupTransform);
                }
            }
        }
        shape.cornerRadius(parseDoubleAttrDef(shapeElem, "CornerRadius", 0));

        // 개별 모서리 둥글기 (TopLeftCornerRadius, TopRightCornerRadius, BottomLeftCornerRadius, BottomRightCornerRadius)
        double tlRadius = parseDoubleAttrDef(shapeElem, "TopLeftCornerRadius", -1);
        double trRadius = parseDoubleAttrDef(shapeElem, "TopRightCornerRadius", -1);
        double blRadius = parseDoubleAttrDef(shapeElem, "BottomLeftCornerRadius", -1);
        double brRadius = parseDoubleAttrDef(shapeElem, "BottomRightCornerRadius", -1);
        if (tlRadius >= 0 || trRadius >= 0 || blRadius >= 0 || brRadius >= 0) {
            double defaultRadius = shape.cornerRadius();
            shape.cornerRadii(new double[]{
                    tlRadius >= 0 ? tlRadius : defaultRadius,
                    trRadius >= 0 ? trRadius : defaultRadius,
                    blRadius >= 0 ? blRadius : defaultRadius,
                    brRadius >= 0 ? brRadius : defaultRadius
            });
        }

        // 투명도 (FillTint, StrokeTint: 0~100, 100=불투명)
        // FillTint=0 + 유효한 FillColor → 100%로 보정 (아웃라인된 글리프 등 IDML 내보내기 아티팩트)
        double fillTint = parseDoubleAttrDef(shapeElem, "FillTint", 100);
        if (fillTint == 0 && shape.hasFill()) {
            fillTint = 100;
        }
        shape.fillTint(fillTint);
        shape.strokeTint(parseDoubleAttrDef(shapeElem, "StrokeTint", 100));

        // 라인 끝 모양 (EndCap: ButtEndCap, RoundEndCap, ProjectingEndCap)
        String endCapStr = getAttrOrNull(shapeElem, "EndCap");
        if (endCapStr != null) {
            if (endCapStr.contains("Round")) {
                shape.startCap(IDMLVectorShape.LineCap.ROUND);
                shape.endCap(IDMLVectorShape.LineCap.ROUND);
            } else if (endCapStr.contains("Projecting")) {
                shape.startCap(IDMLVectorShape.LineCap.PROJECTING);
                shape.endCap(IDMLVectorShape.LineCap.PROJECTING);
            }
        }

        // 라인 연결 모양 (EndJoin: MiterEndJoin, RoundEndJoin, BevelEndJoin)
        String endJoinStr = getAttrOrNull(shapeElem, "EndJoin");
        if (endJoinStr != null) {
            if (endJoinStr.contains("Round")) {
                shape.lineJoin(IDMLVectorShape.LineJoin.ROUND);
            } else if (endJoinStr.contains("Bevel")) {
                shape.lineJoin(IDMLVectorShape.LineJoin.BEVEL);
            }
        }
        shape.miterLimit(parseDoubleAttrDef(shapeElem, "MiterLimit", 4.0));

        // StrokeType → 점선 패턴 (Solid, Canned Dashed, Canned Dotted, Japanese Dots 등)
        String strokeType = getAttrOrNull(shapeElem, "StrokeType");
        if (strokeType != null) {
            double[] dash = resolveStrokeDashPattern(strokeType, shape.strokeWeight());
            if (dash != null) {
                shape.dashPattern(dash);
            }
            // Japanese Dots는 CAP_ROUND 필수 (0-길이 대시 → 둥근 점)
            if (strokeType.contains("Japanese Dots")) {
                shape.startCap(IDMLVectorShape.LineCap.ROUND);
                shape.endCap(IDMLVectorShape.LineCap.ROUND);
            }
        }

        // PathPoint 파싱 (Properties/PathGeometry/GeometryPathType/PathPointArray)
        Element props = getFirstChildElement(shapeElem, "Properties");
        if (props != null) {
            Element pathGeom = getFirstChildElement(props, "PathGeometry");
            if (pathGeom != null) {
                List<Element> pathTypes = getChildElements(pathGeom, "GeometryPathType");
                for (Element pathType : pathTypes) {
                    boolean isOpen = "true".equalsIgnoreCase(pathType.getAttribute("PathOpen"));

                    if (pathTypes.size() > 1) {
                        // 복합 경로 (여러 SubPath)
                        IDMLVectorShape.SubPath subPath = shape.startNewSubPath(isOpen);
                        parsePathPoints(pathType, subPath);
                    } else {
                        // 단일 경로
                        shape.pathOpen(isOpen);
                        parsePathPointsToShape(pathType, shape);
                    }
                }
            }
        }

        // GradientFeatherSetting 파싱 (TransparencySetting 하위)
        parseGradientFeather(shapeElem, shape);

        return shape;
    }

    /**
     * GradientFeatherSetting 파싱.
     * TransparencySetting > GradientFeatherSetting에서 각도, 길이, 시작점을 추출.
     */
    private static void parseGradientFeather(Element shapeElem, IDMLVectorShape shape) {
        NodeList children = shapeElem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element elem = (Element) node;
            if (!"TransparencySetting".equals(elem.getTagName())) continue;

            Element gfs = getFirstChildElement(elem, "GradientFeatherSetting");
            if (gfs == null) continue;

            double angle = parseDoubleAttrDef(gfs, "Angle", Double.NaN);
            double length = parseDoubleAttrDef(gfs, "Length", 0);
            if (Double.isNaN(angle) || length <= 0) continue;

            shape.gradientFeatherAngle(angle);
            shape.gradientFeatherLength(length);

            String startStr = getAttrOrNull(gfs, "GradientStart");
            if (startStr != null) {
                String[] parts = startStr.trim().split("\\s+");
                if (parts.length >= 2) {
                    try {
                        shape.gradientFeatherStart(new double[]{
                                Double.parseDouble(parts[0]),
                                Double.parseDouble(parts[1])
                        });
                    } catch (NumberFormatException ignored) {}
                }
            }
            break;
        }
    }

    /**
     * IDML StrokeType → 점선 dash 패턴 (포인트 단위).
     * Solid이면 null 반환 (점선 없음).
     */
    private static double[] resolveStrokeDashPattern(String strokeType, double strokeWeight) {
        if (strokeType == null || strokeType.contains("Solid")) return null;
        if (strokeWeight <= 0) strokeWeight = 1.0;

        if (strokeType.contains("Canned Dashed 4x4")) {
            return new double[]{4, 4};
        } else if (strokeType.contains("Canned Dashed 3x2")) {
            return new double[]{3, 2};
        } else if (strokeType.contains("Canned Dotted")) {
            return new double[]{strokeWeight, strokeWeight * 2};
        } else if (strokeType.contains("Japanese Dots")) {
            // CAP_ROUND + 0-길이 대시 → 둥근 점. BasicStroke는 양수만 허용하므로 0.01 사용.
            return new double[]{0.01, strokeWeight * 3};
        } else if (strokeType.contains("Dashed")) {
            return new double[]{6, 4};
        }
        // Wavy, Hash, Diamond 등 복잡한 패턴은 Solid 처리
        return null;
    }

    /**
     * PathPointArray를 SubPath에 파싱한다.
     */
    private static void parsePathPoints(Element pathType, IDMLVectorShape.SubPath subPath) {
        Element pointArray = getFirstChildElement(pathType, "PathPointArray");
        if (pointArray == null) return;

        List<Element> points = getChildElements(pointArray, "PathPointType");
        for (Element pt : points) {
            double[] anchor = parsePointAttr(pt, "Anchor");
            double[] left = parsePointAttr(pt, "LeftDirection");
            double[] right = parsePointAttr(pt, "RightDirection");

            if (anchor != null) {
                double lx = (left != null) ? left[0] : anchor[0];
                double ly = (left != null) ? left[1] : anchor[1];
                double rx = (right != null) ? right[0] : anchor[0];
                double ry = (right != null) ? right[1] : anchor[1];

                subPath.addPoint(new IDMLVectorShape.PathPoint(
                        anchor[0], anchor[1], lx, ly, rx, ry));
            }
        }
    }

    /**
     * PathPointArray를 shape에 직접 파싱한다 (단일 경로).
     */
    private static void parsePathPointsToShape(Element pathType, IDMLVectorShape shape) {
        Element pointArray = getFirstChildElement(pathType, "PathPointArray");
        if (pointArray == null) return;

        List<Element> points = getChildElements(pointArray, "PathPointType");
        for (Element pt : points) {
            double[] anchor = parsePointAttr(pt, "Anchor");
            double[] left = parsePointAttr(pt, "LeftDirection");
            double[] right = parsePointAttr(pt, "RightDirection");

            if (anchor != null) {
                double lx = (left != null) ? left[0] : anchor[0];
                double ly = (left != null) ? left[1] : anchor[1];
                double rx = (right != null) ? right[0] : anchor[0];
                double ry = (right != null) ? right[1] : anchor[1];

                shape.addPathPoint(new IDMLVectorShape.PathPoint(
                        anchor[0], anchor[1], lx, ly, rx, ry));
            }
        }
    }

    /**
     * "x y" 형식의 포인트 속성을 파싱한다.
     */
    private static double[] parsePointAttr(Element elem, String attrName) {
        String val = elem.getAttribute(attrName);
        if (val == null || val.isEmpty()) return null;
        String[] parts = val.trim().split("\\s+");
        if (parts.length < 2) return null;
        try {
            return new double[]{ Double.parseDouble(parts[0]), Double.parseDouble(parts[1]) };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 프레임(Rectangle/Polygon/Oval) 내부의 Group 자식을 탐색하여
     * 벡터 도형/이미지/텍스트 프레임을 추출한다.
     * InDesign에서 프레임 안에 Group이 배치될 수 있으며,
     * 이 경우 프레임의 ItemTransform을 누적하여 Group 내 요소를 처리한다.
     */
    private static void extractGroupsFromFrame(Element frameElem, IDMLSpread spread,
                                                double[] frameTransform,
                                                Set<String> hiddenLayerIds,
                                                int[] zOrderCounter) {
        NodeList children = frameElem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element child = (Element) node;
            if ("Group".equals(child.getTagName())) {
                double[] groupTransform = IDMLGeometry.parseTransform(
                        child.getAttribute("ItemTransform"));
                double[] combined = CoordinateConverter.combineTransforms(
                        frameTransform, groupTransform);
                String groupSelfId = child.getAttribute("Self");
                parseGroupForFrames(child, spread, combined, hiddenLayerIds,
                        zOrderCounter, groupSelfId);
            }
        }
    }

    /**
     * 클리핑 프레임 내부의 Group에서 벡터 도형 자식을 재귀적으로 수집한다.
     * 수집된 도형은 clipFrame의 clippedChildren에 추가되며,
     * itemTransform은 클리핑 프레임 로컬 좌표계 기준으로 설정된다.
     *
     * @param groupElem          Group 요소
     * @param clipFrame          클리핑 프레임 (외부 도형)
     * @param accumulatedTransform 이 Group까지의 누적 변환 (클리핑 프레임 기준)
     */
    private static void collectClippedChildrenFromGroup(Element groupElem,
                                                         IDMLVectorShape clipFrame,
                                                         double[] accumulatedTransform) {
        NodeList children = groupElem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element elem = (Element) node;
            String tag = elem.getTagName();

            if ("Rectangle".equals(tag) || "Polygon".equals(tag)
                    || "Oval".equals(tag) || "GraphicLine".equals(tag)) {
                IDMLVectorShape childShape = tryParseVectorShape(elem);
                if (childShape != null) {
                    double[] combinedTransform = CoordinateConverter.combineTransforms(
                            accumulatedTransform, childShape.itemTransform());
                    childShape.itemTransform(combinedTransform);
                    clipFrame.addClippedChild(childShape);
                }
            } else if ("Group".equals(tag)) {
                // 중첩 Group: 누적 변환에 현재 Group 변환을 결합
                double[] childGroupTransform = IDMLGeometry.parseTransform(
                        elem.getAttribute("ItemTransform"));
                double[] combined = CoordinateConverter.combineTransforms(
                        accumulatedTransform, childGroupTransform);
                collectClippedChildrenFromGroup(elem, clipFrame, combined);
            }
        }
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
     * @param zOrderCounter    z-order 카운터 배열 (공유됨)
     */
    private static void parseGroupForFrames(Element groupElem, IDMLSpread spread,
                                             double[] accumulatedTransform,
                                             Set<String> hiddenLayerIds,
                                             int[] zOrderCounter,
                                             String groupSelfId) {
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
                    frame.parentGroupId(groupSelfId);
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
                    imageFrame.fromGroup(true);
                    imageFrame.zOrder(zOrderCounter[0]++);
                    spread.addImageFrame(imageFrame);
                } else {
                    // 순수 벡터 도형으로 파싱
                    IDMLVectorShape vectorShape = tryParseVectorShape(elem);
                    if (vectorShape != null) {
                        double[] combinedTransform = CoordinateConverter.combineTransforms(
                                accumulatedTransform, vectorShape.itemTransform());

                        vectorShape.itemTransform(combinedTransform);
                        vectorShape.fromGroup(true);
                        vectorShape.zOrder(zOrderCounter[0]++);
                        spread.addVectorShape(vectorShape);
                    }
                    // 프레임 내부의 Group 자식도 탐색
                    extractGroupsFromFrame(elem, spread, accumulatedTransform,
                            hiddenLayerIds, zOrderCounter);
                }
            } else if ("GraphicLine".equals(elem.getTagName())) {
                // 그래픽 라인도 벡터 도형으로 처리
                IDMLVectorShape vectorShape = tryParseVectorShape(elem);
                if (vectorShape != null) {
                    vectorShape.shapeType(IDMLVectorShape.ShapeType.GRAPHIC_LINE);
                    vectorShape.itemTransform(CoordinateConverter.combineTransforms(
                            accumulatedTransform, vectorShape.itemTransform()));
                    vectorShape.fromGroup(true);
                    vectorShape.zOrder(zOrderCounter[0]++);
                    spread.addVectorShape(vectorShape);
                }
            } else if ("Group".equals(elem.getTagName())) {
                // 중첩 Group: 누적 변환에 현재 Group 변환을 결합
                double[] childGroupTransform = IDMLGeometry.parseTransform(
                        elem.getAttribute("ItemTransform"));
                double[] combined = CoordinateConverter.combineTransforms(
                        accumulatedTransform, childGroupTransform);
                parseGroupForFrames(elem, spread, combined, hiddenLayerIds, zOrderCounter, groupSelfId);
            }
        }
    }

    /**
     * Group 요소를 IDMLGroup 객체로 파싱한다 (구조 보존).
     * 텍스트 프레임은 제외하고, 이미지/벡터/중첩 그룹만 수집한다.
     */
    private static IDMLGroup parseGroupAsObject(Element groupElem,
                                                  double[] accumulatedTransform,
                                                  Set<String> hiddenLayerIds) {
        String groupLayer = getAttrOrNull(groupElem, "ItemLayer");
        if (groupLayer != null && hiddenLayerIds.contains(groupLayer)) return null;

        IDMLGroup group = new IDMLGroup();
        group.selfId(groupElem.getAttribute("Self"));
        group.geometricBounds(IDMLGeometry.parseBounds(
                groupElem.getAttribute("GeometricBounds")));
        group.itemTransform(accumulatedTransform);

        NodeList children = groupElem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element elem = (Element) node;

            String itemLayer = getAttrOrNull(elem, "ItemLayer");
            if (itemLayer != null && hiddenLayerIds.contains(itemLayer)) continue;

            if ("TextFrame".equals(elem.getTagName())) {
                IDMLTextFrame frame = parseTextFrame(elem);
                if (frame != null) {
                    frame.itemTransform(CoordinateConverter.combineTransforms(
                            accumulatedTransform, frame.itemTransform()));
                    group.addTextFrame(frame);
                }
            } else if ("Rectangle".equals(elem.getTagName())
                    || "Polygon".equals(elem.getTagName())
                    || "Oval".equals(elem.getTagName())) {
                IDMLImageFrame imageFrame = tryParseImageFrame(elem);
                if (imageFrame != null) {
                    imageFrame.itemTransform(CoordinateConverter.combineTransforms(
                            accumulatedTransform, imageFrame.itemTransform()));
                    group.addImageFrame(imageFrame);
                } else {
                    IDMLVectorShape vectorShape = tryParseVectorShape(elem);
                    if (vectorShape != null) {
                        vectorShape.itemTransform(CoordinateConverter.combineTransforms(
                                accumulatedTransform, vectorShape.itemTransform()));
                        group.addVectorShape(vectorShape);
                    }
                }
            } else if ("GraphicLine".equals(elem.getTagName())) {
                IDMLVectorShape vectorShape = tryParseVectorShape(elem);
                if (vectorShape != null) {
                    vectorShape.shapeType(IDMLVectorShape.ShapeType.GRAPHIC_LINE);
                    vectorShape.itemTransform(CoordinateConverter.combineTransforms(
                            accumulatedTransform, vectorShape.itemTransform()));
                    group.addVectorShape(vectorShape);
                }
            } else if ("Group".equals(elem.getTagName())) {
                double[] childGroupTransform = IDMLGeometry.parseTransform(
                        elem.getAttribute("ItemTransform"));
                double[] combined = CoordinateConverter.combineTransforms(
                        accumulatedTransform, childGroupTransform);
                IDMLGroup childGroup = parseGroupAsObject(elem, combined, hiddenLayerIds);
                if (childGroup != null) {
                    group.addChildGroup(childGroup);
                }
            }
        }

        // 자식 요소가 전혀 없으면 null 반환
        if (group.textFrames().isEmpty() && group.imageFrames().isEmpty()
                && group.vectorShapes().isEmpty() && group.childGroups().isEmpty()) {
            return null;
        }

        // geometricBounds가 없거나 [0,0,0,0]이면 자식들의 bounds로부터 계산
        double[] gb = group.geometricBounds();
        if (gb == null || (gb[0] == 0 && gb[1] == 0 && gb[2] == 0 && gb[3] == 0)) {
            group.geometricBounds(computeGroupBounds(group));
        }

        return group;
    }

    /**
     * 그룹의 자식 요소들로부터 geometricBounds를 계산한다.
     * 자식들의 bounds+transform으로 스프레드 좌표계 기준 영역을 구한 뒤,
     * 그룹의 itemTransform 역변환을 적용하여 그룹 로컬 좌표계로 변환한다.
     * 반환: [top, left, bottom, right] (그룹 로컬 좌표계)
     */
    private static double[] computeGroupBounds(IDMLGroup group) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        // 자식의 bounds 중심을 자식의 transform으로 스프레드 좌표계로 변환
        for (IDMLTextFrame tf : group.textFrames()) {
            double[] b = tf.geometricBounds();
            double[] t = tf.itemTransform();
            if (b == null || t == null) continue;
            double[] tl = CoordinateConverter.applyTransform(t, b[1], b[0]);
            double[] br = CoordinateConverter.applyTransform(t, b[3], b[2]);
            if (tl[0] < minX) minX = tl[0]; if (tl[1] < minY) minY = tl[1];
            if (br[0] > maxX) maxX = br[0]; if (br[1] > maxY) maxY = br[1];
        }
        for (IDMLImageFrame img : group.imageFrames()) {
            double[] b = img.geometricBounds();
            double[] t = img.itemTransform();
            if (b == null || t == null) continue;
            double[] tl = CoordinateConverter.applyTransform(t, b[1], b[0]);
            double[] br = CoordinateConverter.applyTransform(t, b[3], b[2]);
            if (tl[0] < minX) minX = tl[0]; if (tl[1] < minY) minY = tl[1];
            if (br[0] > maxX) maxX = br[0]; if (br[1] > maxY) maxY = br[1];
        }
        for (IDMLVectorShape vs : group.vectorShapes()) {
            double[] b = vs.geometricBounds();
            double[] t = vs.itemTransform();
            if (b == null || t == null) continue;
            double[] tl = CoordinateConverter.applyTransform(t, b[1], b[0]);
            double[] br = CoordinateConverter.applyTransform(t, b[3], b[2]);
            if (tl[0] < minX) minX = tl[0]; if (tl[1] < minY) minY = tl[1];
            if (br[0] > maxX) maxX = br[0]; if (br[1] > maxY) maxY = br[1];
        }
        for (IDMLGroup child : group.childGroups()) {
            double[] b = child.geometricBounds();
            double[] t = child.itemTransform();
            if (b == null || t == null) continue;
            double[] tl = CoordinateConverter.applyTransform(t, b[1], b[0]);
            double[] br = CoordinateConverter.applyTransform(t, b[3], b[2]);
            if (tl[0] < minX) minX = tl[0]; if (tl[1] < minY) minY = tl[1];
            if (br[0] > maxX) maxX = br[0]; if (br[1] > maxY) maxY = br[1];
        }

        if (minX == Double.MAX_VALUE) return null;

        // 스프레드 좌표계 기준 영역을 그룹의 transform 역변환으로 로컬 좌표계로 변환
        // 그룹 transform이 단순 이동(identity + translation)인 경우만 역변환 적용
        double[] gt = group.itemTransform();
        if (gt != null) {
            // 단순 이동: a=1,b=0,c=0,d=1 → 역변환은 tx,ty 빼기
            minX -= gt[4]; minY -= gt[5];
            maxX -= gt[4]; maxY -= gt[5];
        }

        return new double[]{minY, minX, maxY, maxX};  // [top, left, bottom, right]
    }

    // ===== Story XML 파싱 =====

    private static IDMLStory parseStory(Document storyDoc, String storyId) {
        IDMLStory story = new IDMLStory();
        story.selfId(storyId);

        // Parse StoryPreference for text direction
        NodeList storyPrefs = storyDoc.getElementsByTagName("StoryPreference");
        if (storyPrefs.getLength() > 0) {
            Element prefElem = (Element) storyPrefs.item(0);
            String orientation = getAttrOrNull(prefElem, "StoryOrientation");
            if (orientation != null) {
                story.storyOrientation(orientation);
            }
        }

        // Parse tables from the story
        NodeList tables = storyDoc.getElementsByTagName("Table");
        for (int i = 0; i < tables.getLength(); i++) {
            Element tableElem = (Element) tables.item(i);
            IDMLTable table = parseTable(tableElem);
            if (table != null) {
                story.addTable(table);
            }
        }

        // Story 루트에서 직접 하위의 ParagraphStyleRange만 파싱 (Table 내부 제외)
        NodeList paraRanges = storyDoc.getElementsByTagName("ParagraphStyleRange");
        for (int i = 0; i < paraRanges.getLength(); i++) {
            Element paraRange = (Element) paraRanges.item(i);

            // Table 내부의 단락은 제외 (테이블 셀에서 별도로 파싱됨)
            if (isInsideTable(paraRange)) {
                continue;
            }

            for (IDMLParagraph para : parseParagraphs(paraRange)) {
                story.addParagraph(para);
            }
        }

        return story;
    }

    // ===== Table XML 파싱 =====

    /**
     * Parse IDML Table element.
     */
    private static IDMLTable parseTable(Element tableElem) {
        IDMLTable table = new IDMLTable();
        table.selfId(tableElem.getAttribute("Self"));

        // Table spacing
        table.spaceBefore(parseDoubleAttrDef(tableElem, "SpaceBefore", 0));
        table.spaceAfter(parseDoubleAttrDef(tableElem, "SpaceAfter", 0));
        table.appliedTableStyle(getAttrOrNull(tableElem, "AppliedTableStyle"));

        // Parse column widths from Column elements - use actual count, not ColumnCount attribute
        List<Element> columns = getChildElements(tableElem, "Column");
        for (Element col : columns) {
            double width = parseDoubleAttrDef(col, "SingleColumnWidth", 72);  // default 1 inch
            table.addColumnWidth(width);
        }
        // Column count = actual number of Column elements
        table.columnCount(columns.size());

        // Parse rows - first collect all Row elements
        List<Element> rowElements = new ArrayList<>();

        // Rows can be inside TableStyleRange or direct children
        List<Element> styleRanges = getChildElements(tableElem, "TableStyleRange");
        for (Element range : styleRanges) {
            rowElements.addAll(getChildElements(range, "Row"));
        }
        // Also check for direct Row children
        rowElements.addAll(getChildElements(tableElem, "Row"));

        // Create row objects from Row elements
        int rowIndex = 0;
        Map<Integer, IDMLTableRow> rowMap = new HashMap<>();
        for (Element rowElem : rowElements) {
            IDMLTableRow row = parseTableRow(rowElem, rowIndex);
            rowMap.put(rowIndex, row);
            rowIndex++;
        }

        // IDML quirk: Cell elements are direct children of Table, not Row
        // Parse cells and assign to correct rows based on Name attribute (e.g., "1:0" = col:row)
        int columnCount = table.columnCount();
        List<Element> cellElements = getChildElements(tableElem, "Cell");
        for (Element cellElem : cellElements) {
            String name = cellElem.getAttribute("Name");  // Format: "col:row" (IDML 표준)
            int[] pos = parseCellPosition(name);
            int cellCol = pos[0];  // 첫 번째 값이 컬럼
            int cellRow = pos[1];  // 두 번째 값이 행

            // Skip cells outside column range
            if (cellCol >= columnCount) {
                continue;
            }

            IDMLTableCell cell = parseTableCell(cellElem, cellRow, cellCol);

            // Add cell to the correct row
            IDMLTableRow targetRow = rowMap.get(cellRow);
            if (targetRow != null) {
                targetRow.addCell(cell);
            }
        }

        // Only add rows that have cells (skip empty rows) and reindex
        int actualRowCount = 0;
        for (int i = 0; i < rowIndex; i++) {
            IDMLTableRow row = rowMap.get(i);
            if (row != null && !row.cells().isEmpty()) {
                // Reindex row and its cells
                row.rowIndex(actualRowCount);
                for (IDMLTableCell cell : row.cells()) {
                    cell.rowIndex(actualRowCount);
                }
                table.addRow(row);
                actualRowCount++;
            }
        }
        table.rowCount(actualRowCount);

        return table;
    }

    /**
     * Parse cell position from Name attribute (e.g., "1:0" -> [col=1, row=0]).
     * IDML Cell Name format: "col:row"
     */
    private static int[] parseCellPosition(String name) {
        if (name == null || !name.contains(":")) {
            return new int[]{0, 0};
        }
        String[] parts = name.split(":");
        try {
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (NumberFormatException e) {
            return new int[]{0, 0};
        }
    }

    /**
     * Parse IDML Table Row (Row element) - row info only, no cells.
     */
    private static IDMLTableRow parseTableRow(Element rowElem, int rowIndex) {
        IDMLTableRow row = new IDMLTableRow();
        row.selfId(rowElem.getAttribute("Self"));
        row.rowIndex(rowIndex);

        // Row height
        double singleRowHeight = parseDoubleAttrDef(rowElem, "SingleRowHeight", 24);  // default ~8.5mm
        row.rowHeight(singleRowHeight);
        row.minRowHeight(parseDoubleAttrDef(rowElem, "MinimumHeight", singleRowHeight));
        row.autoGrow(!"false".equalsIgnoreCase(rowElem.getAttribute("AutoGrow")));

        // Cells are parsed separately from Table level
        return row;
    }

    /**
     * Parse IDML Table Cell (Cell element).
     */
    private static IDMLTableCell parseTableCell(Element cellElem, int rowIndex, int colIndex) {
        IDMLTableCell cell = new IDMLTableCell();
        cell.selfId(cellElem.getAttribute("Self"));
        cell.rowIndex(rowIndex);
        cell.columnIndex(colIndex);

        // Cell spanning
        cell.rowSpan(parseIntAttr(cellElem, "RowSpan", 1));
        cell.columnSpan(parseIntAttr(cellElem, "ColumnSpan", 1));

        // Cell style
        cell.appliedCellStyle(getAttrOrNull(cellElem, "AppliedCellStyle"));
        cell.fillColor(getAttrOrNull(cellElem, "FillColor"));
        cell.fillTint(parseDoubleAttrDef(cellElem, "FillTint", 100));

        // Cell insets/padding
        cell.topInset(parseDoubleAttrDef(cellElem, "TopInset", 4));
        cell.bottomInset(parseDoubleAttrDef(cellElem, "BottomInset", 4));
        cell.leftInset(parseDoubleAttrDef(cellElem, "LeftInset", 4));
        cell.rightInset(parseDoubleAttrDef(cellElem, "RightInset", 4));

        // Vertical justification
        cell.verticalJustification(getAttrOrNull(cellElem, "VerticalJustification"));

        // Cell borders (각 변의 테두리 속성)
        cell.topBorder(parseCellBorder(cellElem, "TopEdge"));
        cell.bottomBorder(parseCellBorder(cellElem, "BottomEdge"));
        cell.leftBorder(parseCellBorder(cellElem, "LeftEdge"));
        cell.rightBorder(parseCellBorder(cellElem, "RightEdge"));

        // Diagonal lines (대각선)
        cell.topLeftDiagonalLine("true".equalsIgnoreCase(cellElem.getAttribute("TopLeftDiagonalLine")));
        cell.topRightDiagonalLine("true".equalsIgnoreCase(cellElem.getAttribute("TopRightDiagonalLine")));
        if (cell.topLeftDiagonalLine() || cell.topRightDiagonalLine()) {
            cell.diagonalBorder(parseCellBorder(cellElem, "DiagonalLine"));
        }

        // Parse cell content (paragraphs)
        // Cell content is inside CellStyleRange > ParagraphStyleRange
        List<Element> cellRanges = getChildElements(cellElem, "CellStyleRange");
        for (Element range : cellRanges) {
            List<Element> paraRanges = getChildElements(range, "ParagraphStyleRange");
            for (Element paraRange : paraRanges) {
                for (IDMLParagraph para : parseParagraphs(paraRange)) {
                    cell.addParagraph(para);
                }
            }
        }

        // Also check for direct ParagraphStyleRange children (alternative structure)
        List<Element> directParas = getChildElements(cellElem, "ParagraphStyleRange");
        for (Element paraRange : directParas) {
            for (IDMLParagraph para : parseParagraphs(paraRange)) {
                cell.addParagraph(para);
            }
        }

        return cell;
    }

    /**
     * Parse cell border from IDML Cell element.
     * @param cellElem Cell element
     * @param prefix Border prefix (TopEdge, BottomEdge, LeftEdge, RightEdge)
     */
    private static IDMLTableCell.CellBorder parseCellBorder(Element cellElem, String prefix) {
        IDMLTableCell.CellBorder border = new IDMLTableCell.CellBorder();

        // StrokeWeight (선 두께, 포인트)
        border.strokeWeight = parseDoubleAttrDef(cellElem, prefix + "StrokeWeight", 1.0);

        // StrokeColor (색상 참조 ID)
        String colorRef = getAttrOrNull(cellElem, prefix + "StrokeColor");
        border.strokeColor = colorRef;

        // StrokeType (Solid, Dashed, etc.)
        String strokeType = getAttrOrNull(cellElem, prefix + "StrokeType");
        if (strokeType != null) {
            // StrokeType can be "$ID/Solid" or similar
            if (strokeType.contains("Solid")) {
                border.strokeType = "Solid";
            } else if (strokeType.contains("Dashed")) {
                border.strokeType = "Dashed";
            } else if (strokeType.contains("Dotted")) {
                border.strokeType = "Dotted";
            } else {
                border.strokeType = strokeType;
            }
        }

        // StrokeTint (투명도, 0-100)
        border.strokeTint = parseDoubleAttrDef(cellElem, prefix + "StrokeTint", 100.0);

        return border;
    }

    /**
     * ParagraphStyleRange → IDMLParagraph 리스트.
     * IDML에서 &lt;Br/&gt;는 단락 구분자이므로, 하나의 ParagraphStyleRange 안에
     * 여러 &lt;Br/&gt;가 있으면 같은 스타일의 복수 단락으로 분리한다.
     */
    private static List<IDMLParagraph> parseParagraphs(Element paraRange) {
        List<IDMLParagraph> result = new ArrayList<>();
        IDMLParagraph currentPara = createParagraphFromRange(paraRange);

        List<Element> charRanges = getChildElements(paraRange, "CharacterStyleRange");
        for (Element charRange : charRanges) {
            // CharacterStyleRange 내부를 직접 순회하여 <Br/> 단위로 분리
            IDMLCharacterRun currentRun = createRunBase(charRange);
            StringBuilder contentBuilder = new StringBuilder();

            NodeList children = charRange.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node.getNodeType() != Node.ELEMENT_NODE) continue;
                Element elem = (Element) node;
                String tag = elem.getTagName();

                if ("Br".equals(tag)) {
                    // 현재 런을 마무리하고 단락을 분리
                    String text = contentBuilder.toString();
                    if (!text.isEmpty()) {
                        currentRun.content(text);
                    }
                    if (currentRun.content() != null || !currentRun.inlineFrames().isEmpty()
                            || !currentRun.inlineGraphics().isEmpty()) {
                        currentPara.addCharacterRun(currentRun);
                    }
                    result.add(currentPara);

                    // 새 단락 + 새 런 시작
                    currentPara = createParagraphFromRange(paraRange);
                    currentRun = createRunBase(charRange);
                    contentBuilder = new StringBuilder();
                } else if ("Content".equals(tag)) {
                    contentBuilder.append(elem.getTextContent());
                } else if ("TextFrame".equals(tag)) {
                    parseInlineTextFrame(elem, currentRun);
                } else if ("Group".equals(tag)) {
                    IDMLCharacterRun.InlineGraphic inlineGroup = parseInlineGroup(elem);
                    currentRun.addInlineGraphic(inlineGroup);
                } else if ("Rectangle".equals(tag) || "Polygon".equals(tag)
                        || "Oval".equals(tag)) {
                    IDMLCharacterRun.InlineGraphic graphic = parseInlineGraphicElement(elem);
                    currentRun.addInlineGraphic(graphic);
                }
            }

            // CharacterStyleRange 끝: 남은 내용을 현재 단락에 추가
            String text = contentBuilder.toString();
            if (!text.isEmpty()) {
                currentRun.content(text);
            }
            if (currentRun.content() != null || !currentRun.inlineFrames().isEmpty()
                    || !currentRun.inlineGraphics().isEmpty()) {
                currentPara.addCharacterRun(currentRun);
            }
        }

        result.add(currentPara);
        return result;
    }

    /**
     * ParagraphStyleRange에서 단락 속성만 파싱하여 IDMLParagraph 생성.
     */
    private static IDMLParagraph createParagraphFromRange(Element paraRange) {
        IDMLParagraph para = new IDMLParagraph();
        para.appliedParagraphStyle(getAttrOrNull(paraRange, "AppliedParagraphStyle"));

        // 인라인 단락 속성 (로컬 오버라이드)
        para.justification(getAttrOrNull(paraRange, "Justification"));
        para.firstLineIndent(parseDoubleAttr(paraRange, "FirstLineIndent"));
        para.leftIndent(parseDoubleAttr(paraRange, "LeftIndent"));
        para.rightIndent(parseDoubleAttr(paraRange, "RightIndent"));
        para.spaceBefore(parseDoubleAttr(paraRange, "SpaceBefore"));
        para.spaceAfter(parseDoubleAttr(paraRange, "SpaceAfter"));
        para.tracking(parseDoubleAttr(paraRange, "Tracking"));

        // 단락 음영 (Paragraph Shading)
        para.shadingOn("true".equalsIgnoreCase(paraRange.getAttribute("ParagraphShadingOn")));
        para.shadingColor(getAttrOrNull(paraRange, "ParagraphShadingColor"));
        para.shadingTint(parseDoubleAttr(paraRange, "ParagraphShadingTint"));
        para.shadingWidth(getAttrOrNull(paraRange, "ParagraphShadingWidth"));
        para.shadingOffsetLeft(parseDoubleAttr(paraRange, "ParagraphShadingLeftOffset"));
        para.shadingOffsetRight(parseDoubleAttr(paraRange, "ParagraphShadingRightOffset"));
        para.shadingOffsetTop(parseDoubleAttr(paraRange, "ParagraphShadingTopOffset"));
        para.shadingOffsetBottom(parseDoubleAttr(paraRange, "ParagraphShadingBottomOffset"));

        // Leading과 TabList는 Properties 안에 있을 수 있음
        Element paraProps = getFirstChildElement(paraRange, "Properties");
        if (paraProps != null) {
            String leadingText = getPropertyText(paraProps, "Leading");
            if (leadingText != null && !"Auto".equalsIgnoreCase(leadingText)) {
                try {
                    para.leading(Double.parseDouble(leadingText));
                } catch (NumberFormatException ignored) {}
            }

            // 인라인 탭 정지점 오버라이드
            Element tabList = getFirstChildElement(paraProps, "TabList");
            if (tabList != null) {
                List<Element> listItems = getChildElements(tabList, "ListItem");
                for (Element item : listItems) {
                    Double position = parseChildElementDouble(item, "Position");
                    String alignment = getChildElementText(item, "Alignment");
                    String leader = getChildElementText(item, "Leader");
                    if (position != null) {
                        para.addTabStop(new IDMLStyleDef.TabStop(position, alignment, leader));
                    }
                }
            }
        }

        return para;
    }

    /**
     * CharacterStyleRange의 스타일 속성만으로 IDMLCharacterRun 생성 (Content 없이).
     */
    private static IDMLCharacterRun createRunBase(Element charRange) {
        IDMLCharacterRun run = new IDMLCharacterRun();
        run.appliedCharacterStyle(getAttrOrNull(charRange, "AppliedCharacterStyle"));
        run.fontStyle(getAttrOrNull(charRange, "FontStyle"));
        run.fillColor(getAttrOrNull(charRange, "FillColor"));
        run.position(getAttrOrNull(charRange, "Position"));
        run.fontSize(parseDoubleAttr(charRange, "PointSize"));

        Element props = getFirstChildElement(charRange, "Properties");
        if (props != null) {
            String fontFamily = getPropertyText(props, "AppliedFont");
            if (fontFamily != null) {
                run.fontFamily(fontFamily);
            }
        }
        return run;
    }

    /**
     * 인라인 TextFrame 파싱 → IDMLCharacterRun에 추가.
     */
    private static void parseInlineTextFrame(Element elem, IDMLCharacterRun run) {
        IDMLTextFrame inlineFrame = new IDMLTextFrame();
        inlineFrame.selfId(elem.getAttribute("Self"));
        inlineFrame.parentStoryId(getAttrOrNull(elem, "ParentStory"));
        inlineFrame.appliedObjectStyle(getAttrOrNull(elem, "AppliedObjectStyle"));
        inlineFrame.geometricBounds(resolveGeometricBounds(elem));
        inlineFrame.itemTransform(IDMLGeometry.parseTransform(elem.getAttribute("ItemTransform")));
        List<Element> aosList = getDescendantElements(elem, "AnchoredObjectSetting");
        if (!aosList.isEmpty()) {
            String anchoredPos = aosList.get(0).getAttribute("AnchoredPosition");
            if (anchoredPos != null && !anchoredPos.isEmpty()) {
                inlineFrame.anchoredPosition(anchoredPos);
            }
        }
        run.addInlineFrame(inlineFrame);
    }

    /**
     * 인라인 그래픽 요소(Rectangle, Polygon, Oval)를 InlineGraphic으로 파싱.
     * 내부에 TextFrame이 있으면 childTextFrames로 수집.
     */
    private static IDMLCharacterRun.InlineGraphic parseInlineGraphicElement(Element elem) {
        IDMLCharacterRun.InlineGraphic graphic = new IDMLCharacterRun.InlineGraphic();
        graphic.selfId(elem.getAttribute("Self"));
        String tag = elem.getTagName();
        if ("Rectangle".equals(tag)) graphic.type("rectangle");
        else if ("Polygon".equals(tag)) graphic.type("polygon");
        else if ("Oval".equals(tag)) graphic.type("ellipse");
        else graphic.type(tag.toLowerCase());

        double[] bounds = resolveGeometricBounds(elem);
        if (bounds != null && bounds.length >= 4) {
            graphic.widthPoints(bounds[3] - bounds[1]);
            graphic.heightPoints(bounds[2] - bounds[0]);
            graphic.geometricBounds(bounds);
        }
        graphic.itemTransform(IDMLGeometry.parseTransform(elem.getAttribute("ItemTransform")));

        // 앵커/래핑 속성 파싱
        parseAnchorAndWrapSettings(elem, graphic);

        // Rectangle/Polygon/Oval 내부에 Image, TextFrame 등이 있을 수 있음
        collectInlineChildren(elem, graphic);
        collectInlineImageLink(elem, graphic);

        // 이미지가 없는 경우 벡터 도형으로 파싱 (글리프 아웃라인 래스터화용)
        if (!graphic.hasImage()) {
            IDMLVectorShape vectorShape = tryParseVectorShape(elem);
            if (vectorShape != null) {
                graphic.vectorShape(vectorShape);
            }
        }

        return graphic;
    }

    /**
     * 인라인 Group을 InlineGraphic(type="group")으로 파싱.
     * Group 내부의 TextFrame, 그래픽, 중첩 Group을 재귀적으로 수집.
     */
    private static IDMLCharacterRun.InlineGraphic parseInlineGroup(Element groupElem) {
        IDMLCharacterRun.InlineGraphic group = new IDMLCharacterRun.InlineGraphic();
        group.selfId(groupElem.getAttribute("Self"));
        group.type("group");

        double[] bounds = resolveGeometricBounds(groupElem);
        if (bounds != null && bounds.length >= 4) {
            group.widthPoints(bounds[3] - bounds[1]);
            group.heightPoints(bounds[2] - bounds[0]);
            group.geometricBounds(bounds);
        }
        group.itemTransform(IDMLGeometry.parseTransform(groupElem.getAttribute("ItemTransform")));

        // 앵커/래핑 속성 파싱
        parseAnchorAndWrapSettings(groupElem, group);

        collectInlineChildren(groupElem, group);
        collectInlineImageLink(groupElem, group);

        return group;
    }

    /**
     * 인라인 그래픽/그룹에서 AnchoredObjectSetting, TextWrapPreference 파싱.
     */
    private static void parseAnchorAndWrapSettings(Element elem, IDMLCharacterRun.InlineGraphic graphic) {
        List<Element> aosList = getDescendantElements(elem, "AnchoredObjectSetting");
        if (!aosList.isEmpty()) {
            String pos = aosList.get(0).getAttribute("AnchoredPosition");
            if (pos != null && !pos.isEmpty()) {
                graphic.anchoredPosition(pos);
            }
        }
        List<Element> twpList = getDescendantElements(elem, "TextWrapPreference");
        if (!twpList.isEmpty()) {
            Element twp = twpList.get(0);
            String mode = twp.getAttribute("TextWrapMode");
            if (mode != null && !mode.isEmpty()) {
                graphic.textWrapMode(mode);
            }
            String side = twp.getAttribute("TextWrapSide");
            if (side != null && !side.isEmpty()) {
                graphic.textWrapSide(side);
            }
            Element props = getFirstChildElement(twp, "Properties");
            if (props != null) {
                Element offset = getFirstChildElement(props, "TextWrapOffset");
                if (offset != null) {
                    graphic.textWrapTop(parseDoubleAttrDef(offset, "Top", 0));
                    graphic.textWrapLeft(parseDoubleAttrDef(offset, "Left", 0));
                    graphic.textWrapBottom(parseDoubleAttrDef(offset, "Bottom", 0));
                    graphic.textWrapRight(parseDoubleAttrDef(offset, "Right", 0));
                }
            }
        }
    }

    /**
     * 요소의 자식에서 TextFrame, 그래픽, Group을 재귀적으로 수집.
     */
    private static void collectInlineChildren(Element parent, IDMLCharacterRun.InlineGraphic target) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element child = (Element) node;
            String tag = child.getTagName();

            if ("TextFrame".equals(tag)) {
                IDMLTextFrame tf = new IDMLTextFrame();
                tf.selfId(child.getAttribute("Self"));
                tf.parentStoryId(getAttrOrNull(child, "ParentStory"));
                tf.appliedObjectStyle(getAttrOrNull(child, "AppliedObjectStyle"));
                tf.geometricBounds(resolveGeometricBounds(child));
                tf.itemTransform(IDMLGeometry.parseTransform(child.getAttribute("ItemTransform")));
                target.addChildTextFrame(tf);
            } else if ("Rectangle".equals(tag) || "Polygon".equals(tag) || "Oval".equals(tag)) {
                target.addChildGraphic(parseInlineGraphicElement(child));
            } else if ("Group".equals(tag)) {
                target.addChildGraphic(parseInlineGroup(child));
            }
        }
    }

    /**
     * 인라인 그래픽 요소 내부에 Image/PDF/EPS + Link가 있으면 링크 정보를 추출한다.
     */
    private static void collectInlineImageLink(Element shapeElem, IDMLCharacterRun.InlineGraphic graphic) {
        List<Element> images = getDescendantElements(shapeElem, "Image");
        if (images.isEmpty()) images = getDescendantElements(shapeElem, "PDF");
        if (images.isEmpty()) images = getDescendantElements(shapeElem, "EPS");
        if (images.isEmpty()) return;

        Element imageElem = images.get(0);

        // 이미지의 ItemTransform (클리핑용)
        String imgTransformStr = imageElem.getAttribute("ItemTransform");
        if (imgTransformStr != null && !imgTransformStr.isEmpty()) {
            graphic.imageTransform(IDMLGeometry.parseTransform(imgTransformStr));
        }

        // GraphicBounds (원본 이미지 크기)
        Element imgProps = getFirstChildElement(imageElem, "Properties");
        if (imgProps != null) {
            Element graphicBoundsElem = getFirstChildElement(imgProps, "GraphicBounds");
            if (graphicBoundsElem != null) {
                double left = parseDoubleAttrDef(graphicBoundsElem, "Left", 0);
                double top = parseDoubleAttrDef(graphicBoundsElem, "Top", 0);
                double right = parseDoubleAttrDef(graphicBoundsElem, "Right", 0);
                double bottom = parseDoubleAttrDef(graphicBoundsElem, "Bottom", 0);
                graphic.graphicBounds(new double[]{left, top, right, bottom});
            }
        }

        // Link 정보
        List<Element> links = getChildElements(imageElem, "Link");
        if (!links.isEmpty()) {
            Element link = links.get(0);
            graphic.linkResourceURI(getAttrOrNull(link, "LinkResourceURI"));
            graphic.linkStoredState(getAttrOrNull(link, "StoredState"));
            graphic.linkResourceFormat(getAttrOrNull(link, "LinkResourceFormat"));
        }
    }

    // ===== 인라인 그래픽(앵커 오브젝트) 추출 =====

    /**
     * Story 파일에서 인라인 그래픽(Rectangle, Polygon, Oval)을 추출하여
     * 해당 Story를 참조하는 TextFrame이 있는 스프레드에 추가한다.
     */
    private static void extractInlineGraphicsFromStories(
            IDMLDocument doc, File storiesDir, Set<String> neededStoryIds) throws Exception {

        if (storiesDir == null || !storiesDir.exists()) return;

        // Story -> TextFrame 목록 매핑 (하나의 Story가 여러 TextFrame에 걸칠 수 있음)
        Map<String, List<IDMLTextFrame>> storyToTextFrames = new HashMap<>();
        Map<String, IDMLSpread> textFrameToSpread = new HashMap<>();

        for (IDMLSpread spread : doc.spreads()) {
            for (IDMLTextFrame tf : spread.textFrames()) {
                String storyId = tf.parentStoryId();
                if (storyId != null) {
                    if (!storyToTextFrames.containsKey(storyId)) {
                        storyToTextFrames.put(storyId, new ArrayList<IDMLTextFrame>());
                    }
                    storyToTextFrames.get(storyId).add(tf);
                    textFrameToSpread.put(tf.selfId(), spread);
                }
            }
        }

        int[] inlineZOrder = {10000};  // 인라인 그래픽은 높은 z-order 시작

        for (String storyId : neededStoryIds) {
            File storyFile = new File(storiesDir, "Story_" + storyId + ".xml");
            if (!storyFile.exists()) continue;

            Document storyDoc = parseXML(storyFile);

            // Story에서 인라인 그래픽 찾기 (Group 변환 누적 포함)
            List<InlineGraphicInfo> inlineGraphics = new ArrayList<>();
            collectInlineGraphics(storyDoc.getDocumentElement(), inlineGraphics);

            if (inlineGraphics.isEmpty()) continue;

            // 이 Story를 참조하는 모든 TextFrame 찾기
            List<IDMLTextFrame> textFrames = storyToTextFrames.get(storyId);
            if (textFrames == null || textFrames.isEmpty()) continue;

            // 각 인라인 그래픽을 모든 관련 스프레드에 추가
            // (나중에 isFrameOnPage로 올바른 페이지에만 렌더링)
            for (InlineGraphicInfo info : inlineGraphics) {
                IDMLVectorShape vectorShape = tryParseVectorShape(info.element);
                if (vectorShape != null) {
                    // 그래픽 자체의 변환과 누적된 Group 변환을 결합
                    double[] graphicTransform = vectorShape.itemTransform();
                    double[] groupCombinedTransform = CoordinateConverter.combineTransforms(
                            info.accumulatedTransform, graphicTransform);

                    // 각 TextFrame에 대해 인라인 그래픽 배치 시도
                    // Y 좌표가 가장 가까운 TextFrame을 선택
                    IDMLTextFrame bestTextFrame = null;
                    double bestDeltaY = Double.MAX_VALUE;

                    for (IDMLTextFrame tf : textFrames) {
                        double[] tfTransform = tf.itemTransform();
                        if (tfTransform != null) {
                            double deltaY = Math.abs(groupCombinedTransform[5] - tfTransform[5]);
                            if (deltaY < bestDeltaY) {
                                bestDeltaY = deltaY;
                                bestTextFrame = tf;
                            }
                        }
                    }

                    if (bestTextFrame == null) continue;

                    double[] textFrameTransform = bestTextFrame.itemTransform();
                    IDMLSpread spread = textFrameToSpread.get(bestTextFrame.selfId());
                    if (spread == null) continue;

                    // 인라인 그래픽 위치 조정 로직:
                    // Y 값이 TextFrame Y와 크게 다르면(1000 이상 차이) 상대 좌표계로 판단
                    double[] finalTransform = groupCombinedTransform.clone();
                    boolean needsOffset = bestDeltaY > 1000;

                    if (needsOffset) {
                        finalTransform[4] = groupCombinedTransform[4] + textFrameTransform[4];
                        finalTransform[5] = groupCombinedTransform[5] + textFrameTransform[5];
                    }

                    // 결합된 변환 적용
                    vectorShape.itemTransform(finalTransform);
                    vectorShape.zOrder(inlineZOrder[0]++);
                    // 인라인 그래픽 표시
                    vectorShape.isInline(true);
                    vectorShape.parentStoryId(storyId);
                    spread.addVectorShape(vectorShape);
                }
            }
        }
    }

    /**
     * 인라인 그래픽 정보 (요소 + 누적 변환).
     */
    private static class InlineGraphicInfo {
        Element element;
        double[] accumulatedTransform;

        InlineGraphicInfo(Element element, double[] accumulatedTransform) {
            this.element = element;
            this.accumulatedTransform = accumulatedTransform;
        }
    }

    /**
     * 요소와 자식들에서 인라인 그래픽(Rectangle, Polygon, Oval)을 재귀적으로 수집한다.
     * CharacterStyleRange 내부의 그래픽만 수집 (진짜 인라인 그래픽).
     */
    private static void collectInlineGraphics(Element parent, List<InlineGraphicInfo> result) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element elem = (Element) node;

            String tagName = elem.getTagName();

            // CharacterStyleRange 내부의 그래픽 요소 수집
            if ("CharacterStyleRange".equals(tagName)) {
                double[] identity = {1, 0, 0, 1, 0, 0};
                collectGraphicsFromCharacterRange(elem, result, identity);
            } else {
                // 재귀 탐색 (ParagraphStyleRange 등)
                collectInlineGraphics(elem, result);
            }
        }
    }

    /**
     * CharacterStyleRange에서 그래픽 요소를 수집한다 (Table 내부 포함).
     */
    private static void collectGraphicsFromCharacterRange(Element charRange,
            List<InlineGraphicInfo> result, double[] parentTransform) {
        NodeList children = charRange.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element elem = (Element) node;

            String tagName = elem.getTagName();
            if ("Rectangle".equals(tagName) || "Polygon".equals(tagName) || "Oval".equals(tagName)) {
                // 인라인 그래픽 발견 - 부모 변환 저장
                result.add(new InlineGraphicInfo(elem, parentTransform));
            } else if ("Group".equals(tagName)) {
                // Group의 transform을 누적
                double[] groupTransform = IDMLGeometry.parseTransform(elem.getAttribute("ItemTransform"));
                double[] combinedTransform = CoordinateConverter.combineTransforms(parentTransform, groupTransform);
                collectGraphicsFromGroup(elem, result, combinedTransform);
            } else if ("Table".equals(tagName)) {
                // Table 셀 내부의 인라인 그래픽은 테이블 변환 파이프라인에서 처리 → 스킵
            } else if ("Cell".equals(tagName) ||
                       "ParagraphStyleRange".equals(tagName) || "CharacterStyleRange".equals(tagName)) {
                collectGraphicsFromCharacterRange(elem, result, parentTransform);
            }
        }
    }

    /**
     * Group에서 그래픽 요소를 재귀적으로 수집한다 (변환 누적).
     */
    private static void collectGraphicsFromGroup(Element group,
            List<InlineGraphicInfo> result, double[] parentTransform) {
        NodeList children = group.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element elem = (Element) node;

            String tagName = elem.getTagName();
            if ("Rectangle".equals(tagName) || "Polygon".equals(tagName) || "Oval".equals(tagName)) {
                // 그래픽 요소 - 누적된 부모 변환 저장
                result.add(new InlineGraphicInfo(elem, parentTransform));
            } else if ("Group".equals(tagName)) {
                // 중첩 Group의 transform도 누적
                double[] groupTransform = IDMLGeometry.parseTransform(elem.getAttribute("ItemTransform"));
                double[] combinedTransform = CoordinateConverter.combineTransforms(parentTransform, groupTransform);
                collectGraphicsFromGroup(elem, result, combinedTransform);
            }
        }
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

    /**
     * 로드된 Story 내부의 인라인 TextFrame(및 InlineGraphic 내 childTextFrame)이
     * 참조하는 추가 Story를 재귀적으로 로드한다.
     */
    private static void loadReferencedInlineStories(IDMLDocument doc, File storiesDir) throws Exception {
        Set<String> loaded = new LinkedHashSet<String>(doc.stories().keySet());
        Queue<String> queue = new LinkedList<String>();

        // 이미 로드된 스토리에서 참조하는 인라인 스토리 ID 수집
        for (IDMLStory story : doc.stories().values()) {
            collectInlineStoryIds(story, loaded, queue);
        }

        // BFS로 추가 스토리 로드
        while (!queue.isEmpty()) {
            String storyId = queue.poll();
            if (loaded.contains(storyId)) continue;
            loaded.add(storyId);

            File storyFile = new File(storiesDir, "Story_" + storyId + ".xml");
            if (!storyFile.exists()) continue;

            IDMLStory story = parseStory(parseXML(storyFile), storyId);
            doc.putStory(storyId, story);

            // 새로 로드된 스토리에서 또 다른 인라인 참조 수집
            collectInlineStoryIds(story, loaded, queue);
        }
    }

    /**
     * Story의 인라인 TextFrame 및 InlineGraphic.childTextFrames에서
     * 참조하는 Story ID를 수집하여 큐에 추가한다.
     */
    private static void collectInlineStoryIds(IDMLStory story, Set<String> loaded, Queue<String> queue) {
        // Story 직속 paragraphs
        collectInlineStoryIdsFromParagraphs(story.paragraphs(), loaded, queue);

        // Story 내 테이블 셀의 paragraphs
        for (IDMLTable table : story.tables()) {
            for (IDMLTableRow row : table.rows()) {
                for (IDMLTableCell cell : row.cells()) {
                    collectInlineStoryIdsFromParagraphs(cell.paragraphs(), loaded, queue);
                }
            }
        }
    }

    private static void collectInlineStoryIdsFromParagraphs(List<IDMLParagraph> paragraphs,
                                                              Set<String> loaded, Queue<String> queue) {
        for (IDMLParagraph para : paragraphs) {
            for (IDMLCharacterRun run : para.characterRuns()) {
                // 직접 인라인 TextFrame
                for (IDMLTextFrame inlineTf : run.inlineFrames()) {
                    String sid = inlineTf.parentStoryId();
                    if (sid != null && !loaded.contains(sid)) {
                        queue.add(sid);
                    }
                }
                // InlineGraphic 내 childTextFrames (Rectangle > TextFrame 등)
                for (IDMLCharacterRun.InlineGraphic ig : run.inlineGraphics()) {
                    collectInlineStoryIdsFromGraphic(ig, loaded, queue);
                }
            }
        }
    }

    private static void collectInlineStoryIdsFromGraphic(IDMLCharacterRun.InlineGraphic ig,
                                                           Set<String> loaded, Queue<String> queue) {
        for (IDMLTextFrame childTf : ig.childTextFrames()) {
            String sid = childTf.parentStoryId();
            if (sid != null && !loaded.contains(sid)) {
                queue.add(sid);
            }
        }
        // 재귀: childGraphics 내부도 탐색
        for (IDMLCharacterRun.InlineGraphic childIg : ig.childGraphics()) {
            collectInlineStoryIdsFromGraphic(childIg, loaded, queue);
        }
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
     * 요소 자신의 PathGeometry에서 bounding box를 계산한다.
     * Properties/PathGeometry/GeometryPathType/PathPointArray/PathPointType 만 사용.
     * (getDescendantElements를 사용하면 자식 도형의 PathPointType까지 포함되어
     * 클리핑 프레임 등의 bounds가 비정상적으로 커지는 문제가 발생함)
     *
     * Anchor, LeftDirection, RightDirection 모든 좌표를 포함하여
     * 베지에 곡선이 앵커 밖으로 확장되는 경우를 처리한다.
     */
    private static double[] computeBoundsFromPathGeometry(Element elem) {
        // 요소 자신의 Properties/PathGeometry 만 탐색 (자식 도형 제외)
        Element props = getFirstChildElement(elem, "Properties");
        if (props == null) return new double[]{0, 0, 0, 0};

        Element pathGeom = getFirstChildElement(props, "PathGeometry");
        if (pathGeom == null) return new double[]{0, 0, 0, 0};

        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        for (Element pathType : getChildElements(pathGeom, "GeometryPathType")) {
            Element ppa = getFirstChildElement(pathType, "PathPointArray");
            if (ppa == null) continue;
            for (Element pp : getChildElements(ppa, "PathPointType")) {
                // Anchor, LeftDirection, RightDirection 모두 포함
                String[] attrs = {"Anchor", "LeftDirection", "RightDirection"};
                for (String attr : attrs) {
                    String val = pp.getAttribute(attr);
                    if (val == null || val.isEmpty()) continue;
                    String[] parts = val.trim().split("\\s+");
                    if (parts.length >= 2) {
                        double x = Double.parseDouble(parts[0]);
                        double y = Double.parseDouble(parts[1]);
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
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

        // JDK XML 파서 속성 제한 해제 (IDML ParagraphStyle에 200개 이상 속성이 있을 수 있음)
        try {
            factory.setAttribute("jdk.xml.elementAttributeLimit", "0");
        } catch (IllegalArgumentException e) {
            // JDK 버전에 따라 지원되지 않을 수 있음 - 무시
        }

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
     * 요소가 Table 내부에 있는지 확인한다.
     */
    private static boolean isInsideTable(Element elem) {
        Node parent = elem.getParentNode();
        while (parent != null) {
            if (parent.getNodeType() == Node.ELEMENT_NODE) {
                String nodeName = parent.getNodeName();
                if ("Table".equals(nodeName) || "Cell".equals(nodeName)) {
                    return true;
                }
            }
            parent = parent.getParentNode();
        }
        return false;
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

    /**
     * 자식 엘리먼트의 텍스트 내용을 반환한다.
     * IDML TabList 등에서 &lt;Position type="unit"&gt;215.4&lt;/Position&gt; 형태를 파싱할 때 사용.
     */
    private static String getChildElementText(Element parent, String childName) {
        Element child = getFirstChildElement(parent, childName);
        if (child == null) return null;
        String text = child.getTextContent();
        return (text != null && !text.isEmpty()) ? text.trim() : null;
    }

    private static Double parseChildElementDouble(Element parent, String childName) {
        String text = getChildElementText(parent, childName);
        if (text == null) return null;
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ===== GREP 스타일 해석 =====

    /**
     * GREP 스타일에서 BT수식M 폰트가 동적 적용되는 CharacterRun을 해석한다.
     *
     * InDesign 단락 스타일의 AllGREPStyles 규칙은 렌더링 시점에 문자 단위로
     * 문자 스타일을 동적 적용하지만, Story XML에는 기록되지 않는다.
     * 이 메서드는 BT수식M 폰트를 적용하는 GREP 규칙을 찾아
     * 해당 텍스트 런에 fontFamily를 설정한다.
     */
    private static void resolveGrepMathStyles(IDMLDocument doc) {
        // 1. BT수식M AppliedFont를 가진 문자 스타일 ID 셋 구축
        Set<String> btMathCharStyleRefs = new HashSet<>();
        for (Map.Entry<String, IDMLStyleDef> entry : doc.charStyles().entrySet()) {
            IDMLStyleDef charStyle = entry.getValue();
            String font = charStyle.fontFamily();
            if (font != null && (font.contains("BT수식") || font.contains("BTM"))) {
                btMathCharStyleRefs.add(entry.getKey());
            }
        }
        if (btMathCharStyleRefs.isEmpty()) return;

        // 2. 단락 스타일별 BT수식M GREP 규칙의 Java Pattern 캐시 구축
        //    key: 단락 스타일 selfRef, value: 컴파일된 Pattern 목록
        Map<String, List<java.util.regex.Pattern>> paraStyleGrepPatterns = new HashMap<>();

        for (Map.Entry<String, IDMLStyleDef> entry : doc.paraStyles().entrySet()) {
            IDMLStyleDef paraStyle = entry.getValue();
            if (paraStyle.grepStyles() == null) continue;

            List<java.util.regex.Pattern> patterns = new ArrayList<>();
            for (IDMLStyleDef.GrepStyleRule rule : paraStyle.grepStyles()) {
                // GREP 규칙이 BT수식M 문자 스타일을 적용하는지 확인
                if (!btMathCharStyleRefs.contains(rule.appliedCharacterStyle())) continue;

                java.util.regex.Pattern pat = convertIdGrepToJavaPattern(rule.grepExpression());
                if (pat != null) {
                    patterns.add(pat);
                }
            }
            if (!patterns.isEmpty()) {
                paraStyleGrepPatterns.put(entry.getKey(), patterns);
            }
        }
        if (paraStyleGrepPatterns.isEmpty()) return;

        // 3. 모든 Story의 CharacterRun을 순회하여 GREP 매칭 수행
        //    한국어 혼합 런은 한국어/비한국어 구간으로 분리하여 처리
        int[] counts = {0, 0}; // [resolvedCount, splitCount]
        for (IDMLStory story : doc.stories().values()) {
            // 스토리 단락
            for (IDMLParagraph para : story.paragraphs()) {
                resolveGrepForParagraph(para, paraStyleGrepPatterns, counts);
            }
            // 테이블 셀 단락
            for (IDMLTable table : story.tables()) {
                for (IDMLTableRow row : table.rows()) {
                    for (IDMLTableCell cell : row.cells()) {
                        for (IDMLParagraph para : cell.paragraphs()) {
                            resolveGrepForParagraph(para, paraStyleGrepPatterns, counts);
                        }
                    }
                }
            }
        }

        if (counts[0] > 0 || counts[1] > 0) {
            System.err.println("[IDMLLoader] GREP->BT math resolved: " + counts[0] + " runs"
                    + ", split: " + counts[1] + " mixed runs"
                    + " (BT charStyles: " + btMathCharStyleRefs.size()
                    + ", paraStyles with GREP: " + paraStyleGrepPatterns.size() + ")");
        }
    }

    /**
     * 단락 내 CharacterRun에 GREP 수식 스타일 매칭을 수행한다.
     */
    private static void resolveGrepForParagraph(IDMLParagraph para,
                                                  Map<String, List<java.util.regex.Pattern>> paraStyleGrepPatterns,
                                                  int[] counts) {
        String paraStyleRef = para.appliedParagraphStyle();
        List<java.util.regex.Pattern> patterns = paraStyleGrepPatterns.get(paraStyleRef);
        if (patterns == null) return;

        List<IDMLCharacterRun> originalRuns = new ArrayList<>(para.characterRuns());
        List<IDMLCharacterRun> newRuns = new ArrayList<>();
        boolean modified = false;

        for (IDMLCharacterRun run : originalRuns) {
            if (run.isBTFont()) {
                newRuns.add(run);
                continue;
            }

            String text = run.content();
            if (text == null || text.isEmpty()) {
                newRuns.add(run);
                continue;
            }

            // 한국어가 없으면 직접 GREP 매칭
            if (!containsKorean(text)) {
                if (matchesGrepPattern(text, patterns)) {
                    run.grepMathFont(true);
                    counts[0]++;
                }
                newRuns.add(run);
                continue;
            }

            // 한국어 혼합 런 → 한국어/비한국어 구간으로 분리
            List<String[]> segments = splitKoreanSegments(text);
            if (segments.size() <= 1) {
                newRuns.add(run);
                continue;
            }

            // 분리된 세그먼트를 각각 별도 런으로 생성
            modified = true;
            counts[1]++;
            for (String[] seg : segments) {
                IDMLCharacterRun subRun = cloneRunWithText(run, seg[0]);
                if ("non-korean".equals(seg[1]) && matchesGrepPattern(seg[0], patterns)) {
                    subRun.grepMathFont(true);
                    counts[0]++;
                }
                newRuns.add(subRun);
            }
        }

        if (modified) {
            para.characterRuns().clear();
            para.characterRuns().addAll(newRuns);
        }
    }

    /**
     * 텍스트에 한국어 문자(가-힣)가 포함되어 있는지 확인.
     */
    private static boolean containsKorean(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7AF) return true;
        }
        return false;
    }

    /**
     * GREP 패턴 목록 중 하나라도 텍스트에 매칭되는지 확인.
     */
    private static boolean matchesGrepPattern(String text, List<java.util.regex.Pattern> patterns) {
        for (java.util.regex.Pattern pat : patterns) {
            try {
                if (pat.matcher(text).find()) return true;
            } catch (Exception e) {
                // 런타임 매칭 에러 무시
            }
        }
        return false;
    }

    /**
     * 텍스트를 한국어/비한국어 구간으로 분리한다.
     * 중립 문자(공백, 일반 구두점)는 주변 문맥의 타입을 상속받는다.
     * 예: "의 원소의 개수는 n(A)=n(B)이다." → ["의 원소의 개수는 ", "n(A)=n(B)", "이다."]
     * @return [text, type] 배열 목록. type은 "korean" 또는 "non-korean"
     */
    private static List<String[]> splitKoreanSegments(String text) {
        int len = text.length();
        // 1단계: 각 문자를 KOREAN(1), LATIN_MATH(2), NEUTRAL(0)로 분류
        int[] types = new int[len];
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (isKoreanChar(c)) {
                types[i] = 1; // KOREAN
            } else if (isLatinOrMathChar(c)) {
                types[i] = 2; // LATIN_MATH
            } else {
                types[i] = 0; // NEUTRAL (공백, 구두점 등)
            }
        }

        // 2단계: 중립 문자에 주변 문맥 타입 할당 (이전 비중립 타입 → 다음 비중립 타입 순)
        int lastNonNeutral = 0;
        for (int i = 0; i < len; i++) {
            if (types[i] != 0) {
                lastNonNeutral = types[i];
            } else {
                // 이전 비중립 타입이 있으면 상속
                if (lastNonNeutral != 0) {
                    types[i] = lastNonNeutral;
                } else {
                    // 이전이 없으면 다음 비중립 타입을 탐색
                    for (int j = i + 1; j < len; j++) {
                        if (types[j] != 0) {
                            types[i] = types[j];
                            break;
                        }
                    }
                    if (types[i] == 0) types[i] = 1; // 전부 중립이면 한국어로 기본 처리
                }
            }
        }

        // 3단계: 연속 동일 타입 구간을 세그먼트로 묶기
        List<String[]> segments = new ArrayList<>();
        int segStart = 0;
        for (int i = 1; i <= len; i++) {
            if (i == len || types[i] != types[segStart]) {
                String segText = text.substring(segStart, i);
                String segType = (types[segStart] == 1) ? "korean" : "non-korean";
                segments.add(new String[]{segText, segType});
                segStart = i;
            }
        }
        return segments;
    }

    /**
     * 한국어 음절 또는 한글 자모인지 확인.
     */
    private static boolean isKoreanChar(char c) {
        return (c >= 0xAC00 && c <= 0xD7AF)   // 한글 음절
                || (c >= 0x3130 && c <= 0x318F); // 한글 호환 자모
    }

    /**
     * 라틴 문자, 숫자 또는 수학 기호인지 확인.
     */
    private static boolean isLatinOrMathChar(char c) {
        if (Character.isLetter(c) && !isKoreanChar(c)) return true; // 라틴/그리스 등 비한국어 문자
        if (Character.isDigit(c)) return true;
        // 수학 기호 및 연산자
        if ("+-*/=<>()[]{}|^~.".indexOf(c) >= 0) return true;
        // 유니코드 수학 기호 범위
        if (c >= 0x2200 && c <= 0x22FF) return true; // Mathematical Operators
        if (c >= 0x2100 && c <= 0x214F) return true; // Letterlike Symbols
        return false;
    }

    /**
     * 런의 스타일 속성을 복사하고 텍스트만 변경한 새 런을 생성한다.
     */
    private static IDMLCharacterRun cloneRunWithText(IDMLCharacterRun source, String newText) {
        IDMLCharacterRun clone = new IDMLCharacterRun();
        clone.appliedCharacterStyle(source.appliedCharacterStyle());
        clone.fontFamily(source.fontFamily());
        clone.fontSize(source.fontSize());
        clone.fillColor(source.fillColor());
        clone.fontStyle(source.fontStyle());
        clone.position(source.position());
        clone.tracking(source.tracking());
        clone.content(newText);
        return clone;
    }

    /**
     * InDesign GREP 정규식을 Java Pattern으로 변환.
     * InDesign GREP 전용 문자 클래스를 Java 유니코드 프로퍼티로 치환한다.
     */
    private static java.util.regex.Pattern convertIdGrepToJavaPattern(String idGrep) {
        if (idGrep == null || idGrep.isEmpty()) return null;
        try {
            String javaRegex = idGrep;
            // InDesign GREP uppercase class -> Java Unicode property
            javaRegex = javaRegex.replace("\\u", "\\p{Lu}");
            // InDesign GREP lowercase class -> Java Unicode property
            javaRegex = javaRegex.replace("\\l", "\\p{Ll}");
            java.util.regex.Pattern pat = java.util.regex.Pattern.compile(javaRegex);
            // 런타임 매칭 에러 사전 검증 (일부 패턴은 compile은 되나 match에서 NPE 발생)
            pat.matcher("test").find();
            return pat;
        } catch (Exception e) {
            // 변환 불가한 InDesign 전용 문법은 무시
            return null;
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

    /**
     * 마스터 페이지의 마진 정보.
     */
    static class MasterPageMargins {
        double marginTop;
        double marginBottom;
        double marginLeft;
        double marginRight;
        int columnCount = 1;
        double columnGutter = 12.0;
    }

    /**
     * 마스터 스프레드에서 마진 정보를 파싱한다.
     */
    private static void parseMasterSpreadForMargins(Document masterSpreadDoc,
                                                     Map<String, MasterPageMargins> masterMargins) {
        Element root = masterSpreadDoc.getDocumentElement();
        String masterSpreadId = root.getAttribute("Self");

        // 마스터 스프레드의 첫 번째 페이지에서 마진 정보를 가져온다
        NodeList pages = root.getElementsByTagName("Page");
        if (pages.getLength() > 0) {
            Element pageElem = (Element) pages.item(0);
            Element marginPref = getFirstChildElement(pageElem, "MarginPreference");
            if (marginPref != null) {
                MasterPageMargins margins = new MasterPageMargins();
                margins.marginTop = parseDoubleAttrDef(marginPref, "Top", 0);
                margins.marginBottom = parseDoubleAttrDef(marginPref, "Bottom", 0);
                margins.marginLeft = parseDoubleAttrDef(marginPref, "Left", 0);
                margins.marginRight = parseDoubleAttrDef(marginPref, "Right", 0);
                margins.columnCount = parseIntAttr(marginPref, "ColumnCount", 1);
                margins.columnGutter = parseDoubleAttrDef(marginPref, "ColumnGutter", 12.0);
                masterMargins.put(masterSpreadId, margins);
            }
        }
    }

    /**
     * 페이지의 모든 마진이 0인지 확인한다.
     */
    private static boolean isAllMarginsZero(IDMLPage page) {
        return page.marginTop() == 0 &&
               page.marginBottom() == 0 &&
               page.marginLeft() == 0 &&
               page.marginRight() == 0;
    }
}
