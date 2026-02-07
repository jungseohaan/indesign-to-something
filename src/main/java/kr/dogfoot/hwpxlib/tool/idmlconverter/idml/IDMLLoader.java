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

            // 4.5. 마스터 스프레드 로드 (마진 정보 수집)
            Map<String, MasterPageMargins> masterMargins = new HashMap<String, MasterPageMargins>();
            for (String masterSrc : masterSpreadSources) {
                File masterFile = new File(dir, masterSrc);
                if (masterFile.exists()) {
                    parseMasterSpreadForMargins(parseXML(masterFile), masterMargins);
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
            }

            // 8. Story에서 인라인 그래픽(앵커 오브젝트) 추출 및 스프레드에 추가
            extractInlineGraphicsFromStories(doc, storiesDir, neededStoryIds);

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
            Element tabList = getFirstChildElement(props2, "TabList");
            if (tabList != null) {
                List<Element> listItems = getChildElements(tabList, "ListItem");
                for (Element item : listItems) {
                    Double position = parseDoubleAttr(item, "Position");
                    String alignment = getAttrOrNull(item, "Alignment");
                    String leader = getAttrOrNull(item, "Leader");
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
                    // 디버그: 스프레드 직접 자식 텍스트의 ty 확인
                    double ty = frame.itemTransform()[5];
                    if (ty > 1000 || ty < -1000) {
                        System.err.println("[DEBUG] 스프레드 직접 텍스트: " + frame.selfId()
                            + " ty=" + CoordinateConverter.fmt(ty)
                            + " storyId=" + frame.parentStoryId());
                    }
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
                        // 디버그: 스프레드 직접 자식 벡터의 ty 확인
                        double ty = vectorShape.itemTransform()[5];
                        if (ty > 1000 || ty < -1000) {
                            System.err.println("[DEBUG] 스프레드 직접 벡터: " + vectorShape.selfId()
                                + " ty=" + CoordinateConverter.fmt(ty));
                        }
                        vectorShape.zOrder(zOrderCounter[0]++);
                        spread.addVectorShape(vectorShape);
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
                parseGroupForFrames(elem, spread, groupTransform, hiddenLayerIds, zOrderCounter);
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

        // Stroke/Outline properties
        frame.strokeColor(getAttrOrNull(frameElem, "StrokeColor"));
        frame.strokeWeight(parseDoubleAttrDef(frameElem, "StrokeWeight", 0));
        frame.cornerRadius(parseDoubleAttrDef(frameElem, "CornerRadius", 0));
        frame.fillTint(parseDoubleAttrDef(frameElem, "FillTint", 100));
        frame.strokeTint(parseDoubleAttrDef(frameElem, "StrokeTint", 100));

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

            // InsetSpacing (단일 값 또는 4면 개별 값)
            String insetStr = tfPref.getAttribute("InsetSpacing");
            if (insetStr != null && !insetStr.isEmpty()) {
                double inset = Double.parseDouble(insetStr);
                frame.insetSpacing(new double[]{inset, inset, inset, inset});
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
        shape.fillColor(getAttrOrNull(shapeElem, "FillColor"));
        shape.strokeColor(getAttrOrNull(shapeElem, "StrokeColor"));
        shape.strokeWeight(parseDoubleAttrDef(shapeElem, "StrokeWeight", 1.0));
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
        shape.fillTint(parseDoubleAttrDef(shapeElem, "FillTint", 100));
        shape.strokeTint(parseDoubleAttrDef(shapeElem, "StrokeTint", 100));

        // 라인 스타일 속성
        String startCap = getAttrOrNull(shapeElem, "LeftLineEnd");
        String endCap = getAttrOrNull(shapeElem, "RightLineEnd");
        String lineJoin = getAttrOrNull(shapeElem, "StrokeCornerAdjustment");

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

        return shape;
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
                                             int[] zOrderCounter) {
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
                    imageFrame.zOrder(zOrderCounter[0]++);
                    spread.addImageFrame(imageFrame);
                } else {
                    // 순수 벡터 도형으로 파싱
                    IDMLVectorShape vectorShape = tryParseVectorShape(elem);
                    if (vectorShape != null) {
                        double[] combinedTransform = CoordinateConverter.combineTransforms(
                                accumulatedTransform, vectorShape.itemTransform());

                        // 디버그: 누적 변환의 ty가 1000pt 이상인 경우 로그 출력
                        if (combinedTransform[5] > 1000 || combinedTransform[5] < -1000) {
                            System.err.println("[DEBUG] 큰 Y오프셋 벡터: " + vectorShape.selfId()
                                + " ty=" + CoordinateConverter.fmt(combinedTransform[5])
                                + " (원본ty=" + CoordinateConverter.fmt(vectorShape.itemTransform()[5])
                                + ", 누적ty=" + CoordinateConverter.fmt(accumulatedTransform[5]) + ")");
                        }

                        vectorShape.itemTransform(combinedTransform);
                        vectorShape.zOrder(zOrderCounter[0]++);
                        spread.addVectorShape(vectorShape);
                    }
                }
            } else if ("GraphicLine".equals(elem.getTagName())) {
                // 그래픽 라인도 벡터 도형으로 처리
                IDMLVectorShape vectorShape = tryParseVectorShape(elem);
                if (vectorShape != null) {
                    vectorShape.shapeType(IDMLVectorShape.ShapeType.GRAPHIC_LINE);
                    vectorShape.itemTransform(CoordinateConverter.combineTransforms(
                            accumulatedTransform, vectorShape.itemTransform()));
                    vectorShape.zOrder(zOrderCounter[0]++);
                    spread.addVectorShape(vectorShape);
                }
            } else if ("Group".equals(elem.getTagName())) {
                // 중첩 Group: 누적 변환에 현재 Group 변환을 결합
                double[] childGroupTransform = IDMLGeometry.parseTransform(
                        elem.getAttribute("ItemTransform"));
                double[] combined = CoordinateConverter.combineTransforms(
                        accumulatedTransform, childGroupTransform);
                parseGroupForFrames(elem, spread, combined, hiddenLayerIds, zOrderCounter);
            }
        }
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

            IDMLParagraph para = parseParagraph(paraRange);
            story.addParagraph(para);
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
                IDMLParagraph para = parseParagraph(paraRange);
                cell.addParagraph(para);
            }
        }

        // Also check for direct ParagraphStyleRange children (alternative structure)
        List<Element> directParas = getChildElements(cellElem, "ParagraphStyleRange");
        for (Element paraRange : directParas) {
            IDMLParagraph para = parseParagraph(paraRange);
            cell.addParagraph(para);
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

    private static IDMLParagraph parseParagraph(Element paraRange) {
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

        // Leading은 Properties 안에 있을 수 있음
        Element paraProps = getFirstChildElement(paraRange, "Properties");
        if (paraProps != null) {
            String leadingText = getPropertyText(paraProps, "Leading");
            if (leadingText != null && !"Auto".equalsIgnoreCase(leadingText)) {
                try {
                    para.leading(Double.parseDouble(leadingText));
                } catch (NumberFormatException ignored) {}
            }
        }

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

        System.err.println("[DEBUG] 인라인 그래픽 추출 시작. 대상 Story 수: " + neededStoryIds.size());

        for (String storyId : neededStoryIds) {
            File storyFile = new File(storiesDir, "Story_" + storyId + ".xml");
            if (!storyFile.exists()) {
                System.err.println("[DEBUG] Story 파일 없음: " + storyId);
                continue;
            }

            Document storyDoc = parseXML(storyFile);

            // Story에서 인라인 그래픽 찾기 (Group 변환 누적 포함)
            List<InlineGraphicInfo> inlineGraphics = new ArrayList<>();
            collectInlineGraphics(storyDoc.getDocumentElement(), inlineGraphics);

            // 디버그: 인라인 그래픽 수집 결과 (u65d 포함 모든 스토리)
            System.err.println("[DEBUG] Story " + storyId + ": 인라인 그래픽 " + inlineGraphics.size() + "개");
            if (!inlineGraphics.isEmpty()) {
                System.err.println("[DEBUG] Story " + storyId + ": 인라인 그래픽 " + inlineGraphics.size() + "개 발견");
            }

            if (inlineGraphics.isEmpty()) continue;

            // 이 Story를 참조하는 모든 TextFrame 찾기
            List<IDMLTextFrame> textFrames = storyToTextFrames.get(storyId);
            if (textFrames == null || textFrames.isEmpty()) {
                System.err.println("[DEBUG] Story " + storyId + ": TextFrame 없음!");
                continue;
            }

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

                    // 디버그 로그
                    System.err.println("[DEBUG] 인라인 그래픽: " + vectorShape.selfId()
                        + " | groupTy=" + CoordinateConverter.fmt(groupCombinedTransform[5])
                        + " | tfTy=" + CoordinateConverter.fmt(textFrameTransform[5])
                        + " | deltaY=" + CoordinateConverter.fmtInt(bestDeltaY)
                        + " | needsOffset=" + needsOffset
                        + " | finalTy=" + CoordinateConverter.fmt(finalTransform[5])
                        + " | Story=" + storyId
                        + " | TextFrames=" + textFrames.size());

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
            } else if ("Table".equals(tagName) || "Cell".equals(tagName) ||
                       "ParagraphStyleRange".equals(tagName) || "CharacterStyleRange".equals(tagName)) {
                // Table, Cell, ParagraphStyleRange, CharacterStyleRange 내부도 탐색
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
