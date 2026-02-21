package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;

import org.w3c.dom.*;
import java.io.*;
import java.util.*;
import static kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLXmlUtils.*;

/**
 * IDML Story 파싱: 텍스트, 테이블, 단락, 문자 런, 인라인 그래픽, GREP 스타일 해석.
 */
class IDMLStoryParser {

    // ===== Story XML 파싱 =====

    static IDMLStory parseStory(Document storyDoc, String storyId) {
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
    static IDMLTable parseTable(Element tableElem) {
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
    static int[] parseCellPosition(String name) {
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
    static IDMLTableRow parseTableRow(Element rowElem, int rowIndex) {
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
    static IDMLTableCell parseTableCell(Element cellElem, int rowIndex, int colIndex) {
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
    static IDMLTableCell.CellBorder parseCellBorder(Element cellElem, String prefix) {
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
     */
    static List<IDMLParagraph> parseParagraphs(Element paraRange) {
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
                    appendContentWithPIs(elem, contentBuilder);
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
     * Content 요소의 텍스트 및 처리 지시(PI)를 StringBuilder에 추가한다.
     */
    static void appendContentWithPIs(Element contentElem, StringBuilder builder) {
        NodeList children = contentElem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.TEXT_NODE) {
                builder.append(node.getNodeValue());
            } else if (node.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE) {
                String target = node.getNodeName();
                String data = node.getNodeValue() != null ? node.getNodeValue().trim() : "";
                if ("ACE".equals(target)) {
                    if ("18".equals(data)) {
                        builder.append('\uFFFE'); // Auto Page Number
                    } else if ("19".equals(data)) {
                        builder.append('\uFFFF'); // Section Marker
                    }
                }
            }
        }
    }

    /**
     * ParagraphStyleRange에서 단락 속성만 파싱하여 IDMLParagraph 생성.
     */
    static IDMLParagraph createParagraphFromRange(Element paraRange) {
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
    static IDMLCharacterRun createRunBase(Element charRange) {
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
    static void parseInlineTextFrame(Element elem, IDMLCharacterRun run) {
        IDMLTextFrame inlineFrame = new IDMLTextFrame();
        inlineFrame.selfId(elem.getAttribute("Self"));
        inlineFrame.parentStoryId(getAttrOrNull(elem, "ParentStory"));
        inlineFrame.appliedObjectStyle(getAttrOrNull(elem, "AppliedObjectStyle"));
        inlineFrame.geometricBounds(IDMLSpreadParser.resolveGeometricBounds(elem));
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
     */
    static IDMLCharacterRun.InlineGraphic parseInlineGraphicElement(Element elem) {
        IDMLCharacterRun.InlineGraphic graphic = new IDMLCharacterRun.InlineGraphic();
        graphic.selfId(elem.getAttribute("Self"));
        String tag = elem.getTagName();
        if ("Rectangle".equals(tag)) graphic.type("rectangle");
        else if ("Polygon".equals(tag)) graphic.type("polygon");
        else if ("Oval".equals(tag)) graphic.type("ellipse");
        else graphic.type(tag.toLowerCase());

        double[] bounds = IDMLSpreadParser.resolveGeometricBounds(elem);
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
            IDMLVectorShape vectorShape = IDMLSpreadParser.tryParseVectorShape(elem);
            if (vectorShape != null) {
                graphic.vectorShape(vectorShape);
            }
        }

        return graphic;
    }

    /**
     * 인라인 Group을 InlineGraphic(type="group")으로 파싱.
     */
    static IDMLCharacterRun.InlineGraphic parseInlineGroup(Element groupElem) {
        IDMLCharacterRun.InlineGraphic group = new IDMLCharacterRun.InlineGraphic();
        group.selfId(groupElem.getAttribute("Self"));
        group.type("group");

        double[] bounds = IDMLSpreadParser.resolveGeometricBounds(groupElem);
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
    static void parseAnchorAndWrapSettings(Element elem, IDMLCharacterRun.InlineGraphic graphic) {
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
    static void collectInlineChildren(Element parent, IDMLCharacterRun.InlineGraphic target) {
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
                tf.geometricBounds(IDMLSpreadParser.resolveGeometricBounds(child));
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
    static void collectInlineImageLink(Element shapeElem, IDMLCharacterRun.InlineGraphic graphic) {
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
    static void extractInlineGraphicsFromStories(
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
            for (InlineGraphicInfo info : inlineGraphics) {
                IDMLVectorShape vectorShape = IDMLSpreadParser.tryParseVectorShape(info.element);
                if (vectorShape != null) {
                    // 그래픽 자체의 변환과 누적된 Group 변환을 결합
                    double[] graphicTransform = vectorShape.itemTransform();
                    double[] groupCombinedTransform = CoordinateConverter.combineTransforms(
                            info.accumulatedTransform, graphicTransform);

                    // 각 TextFrame에 대해 인라인 그래픽 배치 시도
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

                    // 인라인 그래픽 위치 조정 로직
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
    static class InlineGraphicInfo {
        Element element;
        double[] accumulatedTransform;

        InlineGraphicInfo(Element element, double[] accumulatedTransform) {
            this.element = element;
            this.accumulatedTransform = accumulatedTransform;
        }
    }

    /**
     * 요소와 자식들에서 인라인 그래픽(Rectangle, Polygon, Oval)을 재귀적으로 수집한다.
     */
    static void collectInlineGraphics(Element parent, List<InlineGraphicInfo> result) {
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
    static void collectGraphicsFromCharacterRange(Element charRange,
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
    static void collectGraphicsFromGroup(Element group,
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

    // ===== Story ID 수집 =====

    /**
     * 모든 스프레드에서 참조하는 Story ID를 수집한다.
     */
    static Set<String> collectNeededStoryIds(IDMLDocument doc) {
        Set<String> storyIds = new LinkedHashSet<String>();
        for (IDMLSpread spread : doc.spreads()) {
            for (IDMLTextFrame frame : spread.textFrames()) {
                if (frame.parentStoryId() != null) {
                    storyIds.add(frame.parentStoryId());
                }
            }
        }
        // 마스터 스프레드 텍스트프레임의 스토리도 포함 (푸터 텍스트 등)
        for (IDMLSpread masterSpread : doc.masterSpreads().values()) {
            for (IDMLTextFrame frame : masterSpread.textFrames()) {
                if (frame.parentStoryId() != null) {
                    storyIds.add(frame.parentStoryId());
                }
            }
        }
        return storyIds;
    }

    /**
     * 로드된 Story 내부의 인라인 TextFrame이 참조하는 추가 Story를 재귀적으로 로드한다.
     */
    static void loadReferencedInlineStories(IDMLDocument doc, File storiesDir) throws Exception {
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
    static void collectInlineStoryIds(IDMLStory story, Set<String> loaded, Queue<String> queue) {
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

    static void collectInlineStoryIdsFromParagraphs(List<IDMLParagraph> paragraphs,
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

    static void collectInlineStoryIdsFromGraphic(IDMLCharacterRun.InlineGraphic ig,
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

    // ===== Table 내부 확인 =====

    /**
     * 요소가 Table 내부에 있는지 확인한다.
     */
    static boolean isInsideTable(Element elem) {
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

    // ===== GREP 스타일 해석 =====

    /**
     * GREP 스타일에서 BT수식M 폰트가 동적 적용되는 CharacterRun을 해석한다.
     */
    static void resolveGrepMathStyles(IDMLDocument doc) {
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
    static void resolveGrepForParagraph(IDMLParagraph para,
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
    static boolean containsKorean(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7AF) return true;
        }
        return false;
    }

    /**
     * GREP 패턴 목록 중 하나라도 텍스트에 매칭되는지 확인.
     */
    static boolean matchesGrepPattern(String text, List<java.util.regex.Pattern> patterns) {
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
     */
    static List<String[]> splitKoreanSegments(String text) {
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

        // 2단계: 중립 문자에 주변 문맥 타입 할당
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
    static boolean isKoreanChar(char c) {
        return (c >= 0xAC00 && c <= 0xD7AF)   // 한글 음절
                || (c >= 0x3130 && c <= 0x318F); // 한글 호환 자모
    }

    /**
     * 라틴 문자, 숫자 또는 수학 기호인지 확인.
     */
    static boolean isLatinOrMathChar(char c) {
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
    static IDMLCharacterRun cloneRunWithText(IDMLCharacterRun source, String newText) {
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
     */
    static java.util.regex.Pattern convertIdGrepToJavaPattern(String idGrep) {
        if (idGrep == null || idGrep.isEmpty()) return null;
        try {
            String javaRegex = idGrep;
            // InDesign GREP uppercase class -> Java Unicode property
            javaRegex = javaRegex.replace("\\u", "\\p{Lu}");
            // InDesign GREP lowercase class -> Java Unicode property
            javaRegex = javaRegex.replace("\\l", "\\p{Ll}");
            java.util.regex.Pattern pat = java.util.regex.Pattern.compile(javaRegex);
            // 런타임 매칭 에러 사전 검증
            pat.matcher("test").find();
            return pat;
        } catch (Exception e) {
            // 변환 불가한 InDesign 전용 문법은 무시
            return null;
        }
    }
}
