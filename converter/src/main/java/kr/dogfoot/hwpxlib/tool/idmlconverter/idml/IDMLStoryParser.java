package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

import kr.dogfoot.hwpxlib.tool.equationconverter.idml.BTFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;

import org.w3c.dom.*;
import java.io.*;
import java.util.*;
import static kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLXmlUtils.*;

/**
 * IDML Story 파싱: 텍스트, 테이블, 단락, 문자 런, 인라인 그래픽, GREP 스타일 해석.
 */
public class IDMLStoryParser {

    // ===== Story XML 파싱 =====

    public static IDMLStory parseStory(Document storyDoc, String storyId) {
        return parseStory(storyDoc, storyId, null);
    }

    public static IDMLStory parseStory(Document storyDoc, String storyId, IDMLDocument doc) {
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
            IDMLTable table = parseTable(tableElem, doc);
            if (table != null) {
                story.addTable(table);
            }
        }

        // 테이블 selfId → IDMLTable 매핑 (위치 추적용)
        Map<String, IDMLTable> tableById = new HashMap<String, IDMLTable>();
        for (IDMLTable t : story.tables()) {
            tableById.put(t.selfId(), t);
        }

        // Story 루트에서 직접 하위의 ParagraphStyleRange만 파싱 (Table 내부 제외)
        int storyParaCount = 0;
        NodeList paraRanges = storyDoc.getElementsByTagName("ParagraphStyleRange");
        for (int i = 0; i < paraRanges.getLength(); i++) {
            Element paraRange = (Element) paraRanges.item(i);

            // Table 내부의 단락은 제외 (테이블 셀에서 별도로 파싱됨)
            if (isInsideTable(paraRange)) {
                continue;
            }

            // 이 ParagraphStyleRange에 포함된 Table의 스토리 내 위치 기록
            // PSR 내부의 <Br/> 개수를 세어 정확한 문단 인덱스 계산
            List<Element> inlineTables = getDescendantElements(paraRange, "Table");
            if (!inlineTables.isEmpty()) {
                Map<String, Integer> brCounts = countBrsBeforeTables(paraRange);
                for (Element tableElem : inlineTables) {
                    String tableSelfId = tableElem.getAttribute("Self");
                    IDMLTable matchedTable = tableById.get(tableSelfId);
                    if (matchedTable != null) {
                        Integer localBrs = brCounts.get(tableSelfId);
                        int idx = storyParaCount + (localBrs != null ? localBrs : 0);
                        matchedTable.paragraphIndexBefore(idx);
                    }
                }
            }

            List<IDMLParagraph> parsedParas = parseParagraphs(paraRange, doc);
            for (IDMLParagraph para : parsedParas) {
                story.addParagraph(para);
            }
            storyParaCount += parsedParas.size();
        }

        return story;
    }

    // ===== Table XML 파싱 =====

    /**
     * Parse IDML Table element.
     */
    static IDMLTable parseTable(Element tableElem) {
        return parseTable(tableElem, null);
    }

    static IDMLTable parseTable(Element tableElem, IDMLDocument doc) {
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

            IDMLTableCell cell = parseTableCell(cellElem, cellRow, cellCol, doc);

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
        return parseTableCell(cellElem, rowIndex, colIndex, null);
    }

    static IDMLTableCell parseTableCell(Element cellElem, int rowIndex, int colIndex, IDMLDocument doc) {
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
        double cellFillTint = parseDoubleAttrDef(cellElem, "FillTint", 100);
        if (cellFillTint < 0) cellFillTint = 100;  // IDML FillTint=-1은 기본값(100%) 의미
        cell.fillTint(cellFillTint);

        // Cell insets/padding: direct Cell attributes override AppliedCellStyle,
        // then InDesign's default 4pt is used as the final fallback.
        IDMLDocument.CellStyleInfo styleInfo = doc != null ? doc.getCellStyle(cell.appliedCellStyle()) : null;
        double[] styleInsets = styleInfo != null ? styleInfo.insets() : null;
        cell.topInset(parseCellInset(cellElem, "TextTopInset", styleInsets, 0, 4));
        cell.bottomInset(parseCellInset(cellElem, "TextBottomInset", styleInsets, 2, 4));
        cell.leftInset(parseCellInset(cellElem, "TextLeftInset", styleInsets, 1, 4));
        cell.rightInset(parseCellInset(cellElem, "TextRightInset", styleInsets, 3, 4));

        // Vertical justification
        cell.verticalJustification(firstStringAttr(cellElem, "VerticalJustification",
                styleInfo != null ? styleInfo.verticalJustification() : null,
                "TopAlign"));
        cell.firstBaselineOffset(firstStringAttr(cellElem, "FirstBaselineOffset",
                styleInfo != null ? styleInfo.firstBaselineOffset() : null,
                null));
        cell.minimumFirstBaselineOffset(firstDoubleAttr(cellElem, "MinimumFirstBaselineOffset",
                styleInfo != null ? styleInfo.minimumFirstBaselineOffset() : null,
                0));

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
                for (IDMLParagraph para : parseParagraphs(paraRange, doc)) {
                    cell.addParagraph(para);
                }
            }
        }

        // Also check for direct ParagraphStyleRange children (alternative structure)
        List<Element> directParas = getChildElements(cellElem, "ParagraphStyleRange");
        for (Element paraRange : directParas) {
            for (IDMLParagraph para : parseParagraphs(paraRange, doc)) {
                cell.addParagraph(para);
            }
        }

        NodeList nestedTextFrames = cellElem.getElementsByTagName("TextFrame");
        for (int i = 0; i < nestedTextFrames.getLength(); i++) {
            Element textFrame = (Element) nestedTextFrames.item(i);
            String parentStory = getAttrOrNull(textFrame, "ParentStory");
            if (parentStory != null && !parentStory.isEmpty()) {
                cell.addTextFrameStoryRef(parentStory);
            }
        }

        collectDirectNestedTablesInCell(cellElem, doc, cell);

        return cell;
    }

    private static void collectDirectNestedTablesInCell(
            Element cellElem,
            IDMLDocument doc,
            IDMLTableCell cell) {
        if (cellElem == null || cell == null) return;
        Set<String> seen = new LinkedHashSet<>();
        collectDirectNestedTablesInNode(cellElem, doc, cell, seen);
    }

    private static void collectDirectNestedTablesInNode(
            Element root,
            IDMLDocument doc,
            IDMLTableCell cell,
            Set<String> seen) {
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            Element elem = (Element) child;
            String tag = elem.getTagName();
            if ("Table".equals(tag)) {
                IDMLTable nestedTable = parseTable(elem, doc);
                if (nestedTable != null
                        && nestedTable.selfId() != null
                        && seen.add(nestedTable.selfId())) {
                    cell.addDirectNestedTable(nestedTable);
                }
                continue;
            }
            collectDirectNestedTablesInNode(elem, doc, cell, seen);
        }
    }

    private static double parseCellInset(
            Element cellElem,
            String attrName,
            double[] styleInsets,
            int styleIndex,
            double fallback) {
        if (cellElem != null && cellElem.hasAttribute(attrName)) {
            return parseDoubleAttrDef(cellElem, attrName, fallback);
        }
        if (styleInsets != null && styleIndex >= 0 && styleIndex < styleInsets.length) {
            return styleInsets[styleIndex];
        }
        return fallback;
    }

    private static String firstStringAttr(Element elem, String attrName, String inherited, String fallback) {
        if (elem != null && elem.hasAttribute(attrName)) {
            String v = getAttrOrNull(elem, attrName);
            if (v != null && !v.isEmpty()) return v;
        }
        if (inherited != null && !inherited.isEmpty()) return inherited;
        return fallback;
    }

    private static double firstDoubleAttr(Element elem, String attrName, Double inherited, double fallback) {
        if (elem != null && elem.hasAttribute(attrName)) {
            return parseDoubleAttrDef(elem, attrName, fallback);
        }
        return inherited != null ? inherited : fallback;
    }

    /**
     * Parse cell border from IDML Cell element.
     * @param cellElem Cell element
     * @param prefix Border prefix (TopEdge, BottomEdge, LeftEdge, RightEdge)
     */
    static IDMLTableCell.CellBorder parseCellBorder(Element cellElem, String prefix) {
        IDMLTableCell.CellBorder border = new IDMLTableCell.CellBorder();

        // StrokeWeight (선 두께, 포인트)
        // Cell에 속성이 명시되지 않으면 CellStyle 상속 — 대부분 weight=0이므로 기본값 0
        border.strokeWeightSpecified = cellElem.hasAttribute(prefix + "StrokeWeight");
        border.strokeWeight = parseDoubleAttrDef(cellElem, prefix + "StrokeWeight", 0);

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
        return parseParagraphs(paraRange, null);
    }

    static List<IDMLParagraph> parseParagraphs(Element paraRange, IDMLDocument doc) {
        List<IDMLParagraph> result = new ArrayList<>();
        IDMLParagraph currentPara = createParagraphFromRange(paraRange);

        List<Element> charRanges = getChildElements(paraRange, "CharacterStyleRange");
        for (Element charRange : charRanges) {
            // CharacterStyleRange 내부를 직접 순회하여 <Br/> 단위로 분리
            IDMLCharacterRun currentRun = createRunBase(charRange);
            StringBuilder contentBuilder = new StringBuilder();
            int pendingAce8 = 0; // ACE 8이 삽입한 \uFFFC 중 아직 TextFrame과 매칭되지 않은 수

            NodeList children = charRange.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node.getNodeType() != Node.ELEMENT_NODE) continue;
                Element elem = (Element) node;
                String tag = elem.getTagName();

                if ("Br".equals(tag)) {
                    // 현재 런을 마무리하고 단락을 분리
                    String text = replaceUnmatchedObjectAnchors(contentBuilder.toString(), pendingAce8);
                    if (!text.isEmpty()) {
                        currentRun.content(text);
                    }
                    normalizeArrowGlyphRun(currentRun);
                    if (!isDuplicateArrowRun(currentPara, currentRun)
                            && (currentRun.content() != null || !currentRun.inlineFrames().isEmpty()
                                || !currentRun.inlineGraphics().isEmpty())) {
                        currentPara.addCharacterRun(currentRun);
                    }
                    result.add(currentPara);

                    // NextColumn → 방금 추가한 단락에 컬럼 브레이크 마킹
                    String pBreakType = getAttrOrNull(charRange, "ParagraphBreakType");
                    if ("NextColumn".equals(pBreakType)) {
                        currentPara.columnBreakAfter(true);
                    }
                    applyNestedStyles(currentPara, doc);

                    // 새 단락 + 새 런 시작
                    currentPara = createParagraphFromRange(paraRange);
                    currentRun = createRunBase(charRange);
                    contentBuilder = new StringBuilder();
                    pendingAce8 = 0;
                } else if ("Content".equals(tag)) {
                    int lenBefore = contentBuilder.length();
                    appendContentWithPIs(elem, contentBuilder, currentRun);
                    // ACE 8이 추가한 \uFFFC 개수 카운트
                    for (int ci = lenBefore; ci < contentBuilder.length(); ci++) {
                        if (contentBuilder.charAt(ci) == '\uFFFC') pendingAce8++;
                    }
                } else if ("TextFrame".equals(tag)) {
                    if (canConsumeTrailingAce8Anchor(contentBuilder, pendingAce8)) {
                        pendingAce8--; // ACE 8이 바로 앞에 있는 경우만 인라인 앵커로 소비
                    } else {
                        pendingAce8 = materializePendingAce8AsLayoutMarker(contentBuilder, pendingAce8);
                        contentBuilder.append('\uFFFC'); // ACE 8 없는 인라인 TextFrame → 앵커 삽입
                    }
                    int frameIdx = currentRun.inlineFrames().size();
                    parseInlineTextFrame(elem, currentRun);
                    currentRun.addInlineAnchor(IDMLCharacterRun.InlineAnchorType.FRAME, frameIdx);
                } else if ("Group".equals(tag)) {
                    if (canConsumeTrailingAce8Anchor(contentBuilder, pendingAce8)) {
                        pendingAce8--;
                    } else {
                        pendingAce8 = materializePendingAce8AsLayoutMarker(contentBuilder, pendingAce8);
                        contentBuilder.append('\uFFFC');
                    }
                    int graphicIdx = currentRun.inlineGraphics().size();
                    IDMLCharacterRun.InlineGraphic inlineGroup = parseInlineGroup(elem);
                    currentRun.addInlineGraphic(inlineGroup);
                    currentRun.addInlineAnchor(IDMLCharacterRun.InlineAnchorType.GRAPHIC, graphicIdx);
                } else if ("TextVariableInstance".equals(tag)) {
                    // 텍스트 변수 (페이지 번호, 단원명 등) → ResultText를 콘텐츠로 삽입
                    String resultText = getAttrOrNull(elem, "ResultText");
                    String varName = getAttrOrNull(elem, "Name");
                    if (resultText != null && !resultText.isEmpty()) {
                        // 미해결 플레이스홀더 감지: <VarName> 또는 <#VarName> 형태
                        boolean isUnresolved = resultText.startsWith("<#")
                                || (varName != null && resultText.equals("<" + varName + ">"));
                        if (isUnresolved && varName != null) {
                            // 푸터 해석기가 인식할 수 있는 <#VarName> 형태로 정규화
                            contentBuilder.append("<#").append(varName).append(">");
                        } else {
                            contentBuilder.append(resultText);
                            // 해결된 값을 문서에 저장
                            if (doc != null && varName != null) {
                                doc.putTextVariableValue(varName, resultText);
                            }
                        }
                    }
                } else if ("Rectangle".equals(tag) || "Polygon".equals(tag)
                        || "Oval".equals(tag) || "GraphicLine".equals(tag)) {
                    IDMLCharacterRun.InlineGraphic graphic = parseInlineGraphicElement(elem);

                    // 괄호 빈칸 스페이서 — 파싱 직후 한 번만 치환 (화살표 정규화와 같은 전략).
                    //
                    // "( )" 답란의 안쪽 공백은 스페이스가 아니라 fill/stroke 없는 납작한
                    // 인라인 Rectangle 로 폭을 확보한 조판이다. 이 앵커를 하류 경로별로
                    // 살리려 하면 경로마다 대응해야 하므로, 여기서 같은 폭의 NBSP 텍스트로
                    // 바꿔 모든 하류가 평범한 텍스트로 다루게 한다 (과학 u1 p46 사례).
                    String blankSpacer = kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer
                            .BlankAnchorSpacer.spacerTextForGraphic(graphic);
                    if (kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer
                            .BlankAnchorSpacer.isEquationFontRun(currentRun.fontFamily())) {
                        blankSpacer = null;
                    }
                    if (blankSpacer != null) {
                        if (canConsumeTrailingAce8Anchor(contentBuilder, pendingAce8)) {
                            pendingAce8--;
                            int anchorIdx = contentBuilder.lastIndexOf("\uFFFC");
                            if (anchorIdx >= 0) {
                                contentBuilder.replace(anchorIdx, anchorIdx + 1, blankSpacer);
                            } else {
                                contentBuilder.append(blankSpacer);
                            }
                        } else {
                            pendingAce8 = materializePendingAce8AsLayoutMarker(contentBuilder, pendingAce8);
                            contentBuilder.append(blankSpacer);
                        }
                    } else {
                        if (canConsumeTrailingAce8Anchor(contentBuilder, pendingAce8)) {
                            pendingAce8--;
                        } else {
                            pendingAce8 = materializePendingAce8AsLayoutMarker(contentBuilder, pendingAce8);
                            contentBuilder.append('\uFFFC');
                        }
                        int graphicIdx = currentRun.inlineGraphics().size();
                        currentRun.addInlineGraphic(graphic);
                        currentRun.addInlineAnchor(IDMLCharacterRun.InlineAnchorType.GRAPHIC, graphicIdx);
                    }
                }
            }

            // CharacterStyleRange 끝: 남은 내용을 현재 단락에 추가
            String text = replacePUA(replaceUnmatchedObjectAnchors(contentBuilder.toString(), pendingAce8));
            if (!text.isEmpty()) {
                currentRun.content(text);
            }
            normalizeArrowGlyphRun(currentRun);
            if (!isDuplicateArrowRun(currentPara, currentRun)
                    && (currentRun.content() != null || !currentRun.inlineFrames().isEmpty()
                        || !currentRun.inlineGraphics().isEmpty())) {
                currentPara.addCharacterRun(currentRun);
            }
        }

        // Br가 마지막이면 빈 단락이 생성됨 — 내용이 있을 때만 추가
        if (!currentPara.characterRuns().isEmpty()) {
            applyNestedStyles(currentPara, doc);
            result.add(currentPara);
        }
        return result;
    }

    private static boolean canConsumeTrailingAce8Anchor(StringBuilder contentBuilder, int pendingAce8) {
        return pendingAce8 > 0
                && contentBuilder != null
                && contentBuilder.length() > 0
                && contentBuilder.charAt(contentBuilder.length() - 1) == '\uFFFC';
    }

    private static int materializePendingAce8AsLayoutMarker(StringBuilder contentBuilder, int pendingAce8) {
        if (pendingAce8 <= 0 || contentBuilder == null || contentBuilder.length() == 0) return pendingAce8;
        int idx = contentBuilder.lastIndexOf("\uFFFC");
        if (idx < 0) return pendingAce8;
        contentBuilder.setCharAt(idx, '\u0008');
        return pendingAce8 - 1;
    }

    private static void applyNestedStyles(IDMLParagraph para, IDMLDocument doc) {
        if (para == null || doc == null || para.characterRuns() == null || para.characterRuns().isEmpty()) {
            return;
        }
        IDMLStyleDef paraStyle = findStyle(para.appliedParagraphStyle(), doc.paraStyles());
        if (paraStyle == null || paraStyle.nestedStyles() == null || paraStyle.nestedStyles().isEmpty()) {
            return;
        }
        String text = para.getPlainText();
        if (text == null || text.isEmpty()) return;

        String[] styleByChar = new String[text.length()];
        int cursor = 0;
        for (IDMLStyleDef.NestedStyleRule rule : paraStyle.nestedStyles()) {
            if (rule == null || rule.appliedCharacterStyle() == null
                    || rule.delimiter() == null || rule.delimiter().isEmpty()) {
                continue;
            }
            if (cursor >= text.length()) break;
            int delimiterStart = findRepeatedDelimiter(
                    text, rule.delimiter(), cursor, Math.max(1, rule.repetition()));
            int styledEnd;
            int nextCursor;
            if (delimiterStart >= 0) {
                styledEnd = rule.inclusive()
                        ? delimiterStart + rule.delimiter().length()
                        : delimiterStart;
                nextCursor = delimiterStart + rule.delimiter().length();
            } else {
                styledEnd = text.length();
                nextCursor = text.length();
            }
            for (int i = cursor; i < styledEnd && i < styleByChar.length; i++) {
                styleByChar[i] = rule.appliedCharacterStyle();
            }
            cursor = Math.max(nextCursor, cursor);
        }

        List<IDMLCharacterRun> originalRuns = new ArrayList<>(para.characterRuns());
        List<IDMLCharacterRun> newRuns = new ArrayList<>();
        boolean modified = false;
        int globalOffset = 0;
        for (IDMLCharacterRun run : originalRuns) {
            String runText = run.content();
            int len = runText != null ? runText.length() : 0;
            if (len == 0 || hasInlinePayload(run)) {
                newRuns.add(run);
                globalOffset += len;
                continue;
            }
            int start = 0;
            while (start < len) {
                String style = styleAt(styleByChar, globalOffset + start);
                int end = start + 1;
                while (end < len && sameStyle(style, styleAt(styleByChar, globalOffset + end))) {
                    end++;
                }
                IDMLCharacterRun subRun = cloneRunWithText(run, runText.substring(start, end));
                if (style != null) {
                    subRun.appliedCharacterStyle(style);
                    applyCharacterStyleToRun(subRun, findStyle(style, doc.charStyles()));
                    modified = true;
                }
                newRuns.add(subRun);
                start = end;
            }
            globalOffset += len;
        }
        if (modified) {
            para.characterRuns().clear();
            para.characterRuns().addAll(newRuns);
        }
    }

    /**
     * 문자 스타일 값을 런에 채운다. 여기서 채운 값은 <em>상속</em>이므로
     * {@code *Explicit} 플래그를 세우지 않는다 — 런 태그가 직접 명시한 값만 explicit 이다
     * (SPEC-072).
     */
    private static void applyCharacterStyleToRun(IDMLCharacterRun run, IDMLStyleDef style) {
        if (run == null || style == null) return;
        if (style.fontFamily() != null) run.fontFamily(style.fontFamily());
        if (style.fontSize() != null) run.fontSize(style.fontSize());
        if (style.fillColor() != null) run.fillColor(style.fillColor());
        if (style.fillTint() != null) run.fillTint(style.fillTint());
        if (style.fontStyle() != null) run.fontStyle(style.fontStyle());
        if (style.tracking() != null) run.tracking(style.tracking());
        if (style.underline() != null) run.underline(style.underline());
        if (style.underlineType() != null) run.underlineType(style.underlineType());
        if (style.shadeColor() != null) run.shadeColor(style.shadeColor());
        if (style.shadeTint() != null) run.shadeTint(style.shadeTint());
        if (style.strikeThrough() != null) run.strikeThrough(style.strikeThrough());
        if (style.baselineShift() != null) run.baselineShift(style.baselineShift());
        if (style.horizontalScale() != null) run.horizontalScale(style.horizontalScale());
        if (style.verticalScale() != null) run.verticalScale(style.verticalScale());
        if (style.capitalization() != null) run.capitalization(style.capitalization());
    }

    private static int findRepeatedDelimiter(String text, String delimiter, int start, int repetition) {
        int from = Math.max(0, start);
        int found = -1;
        for (int i = 0; i < repetition; i++) {
            found = text.indexOf(delimiter, from);
            if (found < 0) return -1;
            from = found + delimiter.length();
        }
        return found;
    }

    private static boolean hasInlinePayload(IDMLCharacterRun run) {
        return run != null
                && ((run.inlineFrames() != null && !run.inlineFrames().isEmpty())
                || (run.inlineGraphics() != null && !run.inlineGraphics().isEmpty())
                || (run.inlineAnchors() != null && !run.inlineAnchors().isEmpty()));
    }

    private static String styleAt(String[] styles, int index) {
        return styles != null && index >= 0 && index < styles.length ? styles[index] : null;
    }

    private static boolean sameStyle(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static IDMLStyleDef findStyle(String styleRef, Map<String, IDMLStyleDef> styles) {
        if (styleRef == null || styles == null || styles.isEmpty()) return null;
        IDMLStyleDef direct = styles.get(styleRef);
        if (direct != null) return direct;
        String decoded = styleRef.replace("%3a", ":").replace("%25", "%");
        direct = styles.get(decoded);
        if (direct != null) return direct;
        String shortRef = styleRef;
        if (shortRef.startsWith("ParagraphStyle/")) {
            shortRef = shortRef.substring("ParagraphStyle/".length());
        } else if (shortRef.startsWith("CharacterStyle/")) {
            shortRef = shortRef.substring("CharacterStyle/".length());
        }
        for (Map.Entry<String, IDMLStyleDef> entry : styles.entrySet()) {
            IDMLStyleDef style = entry.getValue();
            if (style == null) continue;
            if (shortRef.equals(style.name()) || shortRef.equals(style.simpleName())) return style;
            if (decoded.equals(entry.getKey())) return style;
        }
        return null;
    }

    private static String replaceUnmatchedObjectAnchors(String text, int count) {
        if (text == null || text.isEmpty() || count <= 0) return text;
        StringBuilder sb = new StringBuilder(text);
        for (int replaced = 0; replaced < count; replaced++) {
            int idx = sb.lastIndexOf("\uFFFC");
            if (idx < 0) break;
            sb.setCharAt(idx, '\u0008');
        }
        return sb.toString();
    }

    /**
     * Content 요소의 텍스트 및 처리 지시(PI)를 StringBuilder에 추가한다.
     */
    static void appendContentWithPIs(Element contentElem, StringBuilder builder) {
        appendContentWithPIs(contentElem, builder, null);
    }

    static void appendContentWithPIs(Element contentElem, StringBuilder builder, IDMLCharacterRun run) {
        NodeList children = contentElem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.TEXT_NODE) {
                builder.append(node.getNodeValue());
            } else if (node.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE) {
                String target = node.getNodeName();
                String data = node.getNodeValue() != null ? node.getNodeValue().trim() : "";
                if ("ACE".equals(target)) {
                    if ("3".equals(data)) {
                        // ACE 3 = END_NESTED_STYLE_HERE — 시각 효과 없음, 무시
                        // (bullet 단락에서 "● <?ACE 3?><?ACE 7?>text" 패턴이 줄바꿈으로 잘못 변환되던 회귀)
                    } else if ("4".equals(data)) {
                        builder.append('\n'); // Column Break → 줄바꿈
                    } else if ("7".equals(data)) {
                        builder.append('\u0008'); // Indent to Here
                    } else if ("8".equals(data)) {
                        builder.append('\uFFFC'); // Object Replacement Character (인라인 오브젝트 앵커)
                    } else if ("18".equals(data)) {
                        builder.append('\uFFFE'); // Auto Page Number
                    } else if ("19".equals(data)) {
                        builder.append('\uFFFF'); // Section Marker
                    } else if ("1a".equalsIgnoreCase(data) && isArrowGlyphRange(run)) {
                        builder.append(BTFontGlyphMap.ARROW);
                    }
                }
            }
        }
    }

    private static boolean isArrowGlyphRange(IDMLCharacterRun run) {
        if (run == null) return false;
        return BTFontGlyphMap.isBTArrowFont(run.fontFamily())
                || BTFontGlyphMap.isBTArrowFontStyle(run.appliedCharacterStyle());
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
        para.numberingExpression(getAttrOrNull(paraRange, "NumberingExpression"));
        para.bulletsTextAfter(getAttrOrNull(paraRange, "BulletsTextAfter"));

        // 단락 아래선 (RuleBelow) — 분수 TextFrame 감지용
        para.ruleBelowOn("true".equalsIgnoreCase(paraRange.getAttribute("RuleBelow")));

        // 단락 음영 (Paragraph Shading)
        para.shadingOn("true".equalsIgnoreCase(paraRange.getAttribute("ParagraphShadingOn")));
        para.shadingColor(getAttrOrNull(paraRange, "ParagraphShadingColor"));
        para.shadingTint(parseDoubleAttr(paraRange, "ParagraphShadingTint"));
        para.shadingWidth(getAttrOrNull(paraRange, "ParagraphShadingWidth"));
        para.shadingOffsetLeft(parseDoubleAttr(paraRange, "ParagraphShadingLeftOffset"));
        para.shadingOffsetRight(parseDoubleAttr(paraRange, "ParagraphShadingRightOffset"));
        para.shadingOffsetTop(parseDoubleAttr(paraRange, "ParagraphShadingTopOffset"));
        para.shadingOffsetBottom(parseDoubleAttr(paraRange, "ParagraphShadingBottomOffset"));

        // 단락 분리 제어 (인라인 오버라이드가 있으면 적용, 없으면 스타일 상속)
        String kwn = getAttrOrNull(paraRange, "KeepWithNext");
        if (kwn != null) para.keepWithNext("true".equalsIgnoreCase(kwn));
        String kalt = getAttrOrNull(paraRange, "KeepAllLinesTogether");
        if (kalt != null) para.keepLinesTogether("true".equalsIgnoreCase(kalt));
        String startPara = getAttrOrNull(paraRange, "StartParagraph");
        if (startPara != null) {
            para.pageBreakBefore("true".equalsIgnoreCase(startPara)
                    || "NextPage".equalsIgnoreCase(startPara));
        }

        // Leading과 TabList는 Properties 안에 있을 수 있음
        Element paraProps = getFirstChildElement(paraRange, "Properties");
        if (paraProps != null) {
            String leadingText = getPropertyText(paraProps, "Leading");
            if (leadingText != null) {
                if ("Auto".equalsIgnoreCase(leadingText)) {
                    para.leadingType("Auto");
                } else {
                    try {
                        para.leading(Double.parseDouble(leadingText));
                    } catch (NumberFormatException e) {
                        System.err.println("[IDMLStoryParser] Leading 파싱 실패: " + leadingText);
                    }
                }
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

        // ParagraphStyleRange에 Leading이 없으면 첫 번째 CharacterStyleRange에서 폴백.
        // InDesign에서 Leading은 character-level 속성이므로 CharacterStyleRange/Properties에
        // 지정되는 경우가 많다.
        if (para.leading() == null && para.leadingType() == null) {
            Element firstCharRange = getFirstChildElement(paraRange, "CharacterStyleRange");
            if (firstCharRange != null) {
                Element charProps = getFirstChildElement(firstCharRange, "Properties");
                if (charProps != null) {
                    String charLeading = getPropertyText(charProps, "Leading");
                    if (charLeading != null) {
                        if ("Auto".equalsIgnoreCase(charLeading)) {
                            para.leadingType("Auto");
                        } else {
                            try {
                                para.leading(Double.parseDouble(charLeading));
                            } catch (NumberFormatException e) {
                                System.err.println("[IDMLStoryParser] CharLeading 파싱 실패: " + charLeading);
                            }
                        }
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
        run.fillColorExplicit(run.fillColor() != null);
        run.fillTint(parseDoubleAttr(charRange, "FillTint"));
        run.position(getAttrOrNull(charRange, "Position"));
        run.fontSize(parseDoubleAttr(charRange, "PointSize"));
        run.fontSizeExplicit(run.fontSize() != null);
        run.tracking(parseDoubleAttr(charRange, "Tracking"));
        run.baselineShift(parseDoubleAttr(charRange, "BaselineShift"));
        run.horizontalScale(parseDoubleAttr(charRange, "HorizontalScale"));
        run.verticalScale(parseDoubleAttr(charRange, "VerticalScale"));
        run.capitalization(getAttrOrNull(charRange, "Capitalization"));
        String directShadeColor = firstAttr(charRange,
                "CharacterShadingColor", "ShadingColor", "TextShadingColor");
        if (isShadingEnabled(charRange, directShadeColor)) {
            run.shadeColor(directShadeColor);
        }
        run.shadeTint(firstDoubleAttr(charRange,
                "CharacterShadingTint", "ShadingTint", "TextShadingTint"));

        // 밑줄 / 취소선
        String underline = getAttrOrNull(charRange, "Underline");
        if ("true".equalsIgnoreCase(underline)) run.underline(true);
        String underlineTint = getAttrOrNull(charRange, "UnderlineTint");
        if (underlineTint != null) {
            try { run.underlineTint(Double.parseDouble(underlineTint)); } catch (NumberFormatException e) {
                System.err.println("[IDMLStoryParser] UnderlineTint 파싱 실패: " + underlineTint);
            }
        }
        String strikeThru = getAttrOrNull(charRange, "StrikeThru");
        if ("true".equalsIgnoreCase(strikeThru)) run.strikeThrough(true);

        Element props = getFirstChildElement(charRange, "Properties");
        if (props != null) {
            String fontFamily = getPropertyText(props, "AppliedFont");
            if (fontFamily != null) {
                run.fontFamily(fontFamily);
                run.fontFamilyExplicit(true);
            }
            // UnderlineType (Properties 안: <UnderlineType type="object">StrokeStyle/$ID/Wavy</UnderlineType>)
            String ulType = getPropertyText(props, "UnderlineType");
            if (ulType != null) {
                run.underlineType(ulType);
            }
            String propShadeColor = firstPropertyText(props,
                    "CharacterShadingColor", "ShadingColor", "TextShadingColor");
            if (run.shadeColor() == null && isShadingEnabled(props, propShadeColor)) {
                run.shadeColor(propShadeColor);
            }
            if (run.shadeTint() == null) {
                run.shadeTint(firstPropertyDouble(props,
                        "CharacterShadingTint", "ShadingTint", "TextShadingTint"));
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
        // TextWrapPreference 파싱
        List<Element> twpList = getDescendantElements(elem, "TextWrapPreference");
        if (!twpList.isEmpty()) {
            String wrapMode = twpList.get(0).getAttribute("TextWrapMode");
            if (wrapMode != null && !wrapMode.isEmpty() && !"None".equals(wrapMode)) {
                inlineFrame.textWrapMode(wrapMode);
            }
        }
        // 테두리/채우기 속성
        inlineFrame.fillColor(getAttrOrNull(elem, "FillColor"));
        inlineFrame.fillTint(parseDoubleAttrDef(elem, "FillTint", 100));
        inlineFrame.strokeColor(getAttrOrNull(elem, "StrokeColor"));
        inlineFrame.strokeWeight(parseDoubleAttrDef(elem, "StrokeWeight", 0));
        inlineFrame.cornerRadius(parseDoubleAttrDef(elem, "CornerRadius", 0));
        // TextFramePreference — VerticalJustification, InsetSpacing 파싱
        Element tfPref = getFirstChildElement(elem, "TextFramePreference");
        if (tfPref != null) {
            String vJust = getAttrOrNull(tfPref, "VerticalJustification");
            if (vJust != null) {
                inlineFrame.verticalJustification(vJust);
            }
            String insetStr = getAttrOrNull(tfPref, "InsetSpacing");
            if (insetStr != null && !insetStr.isEmpty()) {
                String[] parts = insetStr.split("\\s+");
                if (parts.length >= 4) {
                    double[] inset = new double[4];
                    for (int k = 0; k < 4; k++) {
                        try { inset[k] = Double.parseDouble(parts[k]); } catch (NumberFormatException ignored) {}
                    }
                    inlineFrame.insetSpacing(inset);
                }
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
        graphic.appliedObjectStyle(getAttrOrNull(elem, "AppliedObjectStyle"));

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

        // Group 레벨 stroke/fill 색상 (자식 도형 색상 상속용)
        group.groupStrokeColor(getAttrOrNull(groupElem, "StrokeColor"));
        group.groupFillColor(getAttrOrNull(groupElem, "FillColor"));
        String fillTintAttr = getAttrOrNull(groupElem, "FillTint");
        if (fillTintAttr != null) {
            try { group.groupFillTint(Double.parseDouble(fillTintAttr)); } catch (NumberFormatException e) {
                System.err.println("[IDMLStoryParser] Group FillTint 파싱 실패: " + fillTintAttr);
            }
        }
        String strokeTintAttr = getAttrOrNull(groupElem, "StrokeTint");
        if (strokeTintAttr != null) {
            try { group.groupStrokeTint(Double.parseDouble(strokeTintAttr)); } catch (NumberFormatException e) {
                System.err.println("[IDMLStoryParser] Group StrokeTint 파싱 실패: " + strokeTintAttr);
            }
        }
        String strokeWeightAttr = getAttrOrNull(groupElem, "StrokeWeight");
        if (strokeWeightAttr != null) {
            try { group.groupStrokeWeight(Double.parseDouble(strokeWeightAttr)); } catch (NumberFormatException e) {
                System.err.println("[IDMLStoryParser] Group StrokeWeight 파싱 실패: " + strokeWeightAttr);
            }
        }

        return group;
    }

    /**
     * 인라인 그래픽/그룹에서 AnchoredObjectSetting, TextWrapPreference 파싱.
     */
    static void parseAnchorAndWrapSettings(Element elem, IDMLCharacterRun.InlineGraphic graphic) {
        // AnchoredObjectSetting은 직접 자식만 검색 (중첩 TextFrame 등의 설정을 상속하지 않기 위해)
        Element aosElem = getFirstChildElement(elem, "AnchoredObjectSetting");
        if (aosElem != null) {
            String pos = aosElem.getAttribute("AnchoredPosition");
            if (pos != null && !pos.isEmpty()) {
                graphic.anchoredPosition(pos);
            }
        }
        // TextWrapPreference도 직접 자식만 검색
        Element twp = getFirstChildElement(elem, "TextWrapPreference");
        if (twp != null) {
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

                // TextFramePreference — VerticalJustification 파싱
                Element tfPref = getFirstChildElement(child, "TextFramePreference");
                if (tfPref != null) {
                    String vJust = getAttrOrNull(tfPref, "VerticalJustification");
                    if (vJust != null) {
                        tf.verticalJustification(vJust);
                    }
                }

                target.addChildTextFrame(tf);

                // 장식용 TextFrame(fill 있고 PathGeometry 도형) → 벡터 도형으로도 파싱 (체크마크 등)
                String tfFill = getAttrOrNull(child, "FillColor");
                if (tfFill != null && !tfFill.contains("None") && !tfFill.contains("Paper")) {
                    IDMLVectorShape vs = IDMLSpreadParser.tryParseVectorShape(child);
                    if (vs != null) {
                        IDMLCharacterRun.InlineGraphic shapeGfx = new IDMLCharacterRun.InlineGraphic();
                        shapeGfx.selfId(child.getAttribute("Self") + "_vs");
                        shapeGfx.type("textframe_shape");
                        shapeGfx.vectorShape(vs);
                        double[] bounds = IDMLSpreadParser.resolveGeometricBounds(child);
                        if (bounds != null && bounds.length >= 4) {
                            shapeGfx.widthPoints(bounds[3] - bounds[1]);
                            shapeGfx.heightPoints(bounds[2] - bounds[0]);
                            shapeGfx.geometricBounds(bounds);
                        }
                        shapeGfx.itemTransform(IDMLGeometry.parseTransform(
                                child.getAttribute("ItemTransform")));
                        target.addChildGraphic(shapeGfx);
                    }
                }
            } else if ("Rectangle".equals(tag) || "Polygon".equals(tag)
                    || "Oval".equals(tag) || "GraphicLine".equals(tag)) {
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

            // 내장 이미지 데이터: <Contents><![CDATA[base64...]]></Contents>
            Element contentsElem = getFirstChildElement(imgProps, "Contents");
            if (contentsElem != null) {
                String base64Data = contentsElem.getTextContent();
                if (base64Data != null && !base64Data.isEmpty()) {
                    graphic.embeddedContents(base64Data.trim());
                }
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

            IDMLStory story = parseStory(parseXML(storyFile), storyId, doc);
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
                    collectInlineStoryIdsFromCellStoryRefs(cell, loaded, queue);
                    collectInlineStoryIdsFromParagraphs(cell.paragraphs(), loaded, queue);
                }
            }
        }
    }

    static void collectInlineStoryIdsFromCellStoryRefs(IDMLTableCell cell,
                                                       Set<String> loaded,
                                                       Queue<String> queue) {
        if (cell == null || cell.textFrameStoryRefs() == null) return;
        for (String sid : cell.textFrameStoryRefs()) {
            if (sid != null && !sid.isEmpty() && !loaded.contains(sid)) {
                queue.add(sid);
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

    /**
     * ParagraphStyleRange 내부에서 각 Table 앞에 있는 &lt;Br/&gt; 개수를 계산한다.
     * CharacterStyleRange 자식을 문서 순서대로 순회하며 Br과 Table을 추적한다.
     * @return Table Self ID → 해당 Table 앞의 Br 개수
     */
    static Map<String, Integer> countBrsBeforeTables(Element paraRange) {
        Map<String, Integer> result = new HashMap<String, Integer>();
        int brCount = 0;

        List<Element> charRanges = getChildElements(paraRange, "CharacterStyleRange");
        for (Element charRange : charRanges) {
            NodeList children = charRange.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node.getNodeType() != Node.ELEMENT_NODE) continue;
                Element elem = (Element) node;
                String tag = elem.getTagName();

                if ("Br".equals(tag)) {
                    brCount++;
                } else if ("Table".equals(tag)) {
                    result.put(elem.getAttribute("Self"), brCount);
                }
            }
        }
        return result;
    }

    // ===== CharacterStyle 폰트 상속 해석 =====

    /**
     * CharacterStyleRange에 명시적 AppliedFont/FillColor가 없는 경우,
     * AppliedCharacterStyle 또는 ParagraphStyle 정의에서 상속한다.
     */
    static void resolveCharacterStyleFonts(IDMLDocument doc) {
        int fontCount = 0;
        int colorCount = 0;
        for (IDMLStory story : doc.stories().values()) {
            for (IDMLParagraph para : story.paragraphs()) {
                int[] counts = resolveStylePropsForParagraph(para, doc);
                fontCount += counts[0];
                colorCount += counts[1];
            }
            for (IDMLTable table : story.tables()) {
                for (IDMLTableRow row : table.rows()) {
                    for (IDMLTableCell cell : row.cells()) {
                        for (IDMLParagraph para : cell.paragraphs()) {
                            int[] counts = resolveStylePropsForParagraph(para, doc);
                            fontCount += counts[0];
                            colorCount += counts[1];
                        }
                    }
                }
            }
        }
        if (fontCount > 0 || colorCount > 0) {
            System.err.println("[IDMLLoader] CharacterStyle inheritance resolved: "
                    + fontCount + " fonts, " + colorCount + " colors");
        }
    }

    /**
     * 단락 내 런의 fontFamily/fillColor를 스타일 정의에서 상속.
     * fontFamily: CharacterStyle에서만 상속 (ParagraphStyle 폰트는 범용이므로 제외)
     * fillColor: CharacterStyle → ParagraphStyle 순서로 상속
     * @return [fontCount, colorCount]
     */
    private static int[] resolveStylePropsForParagraph(IDMLParagraph para, IDMLDocument doc) {
        int fontCount = 0;
        int colorCount = 0;
        String paraStyleRef = para.appliedParagraphStyle();

        for (IDMLCharacterRun run : para.characterRuns()) {
            String charStyleRef = run.appliedCharacterStyle();

            // fontFamily 상속 (CharacterStyle에서만)
            if (run.fontFamily() == null && charStyleRef != null) {
                String font = resolveStyleProp(charStyleRef, doc, true, 0);
                if (font != null) {
                    run.fontFamily(font);
                    fontCount++;
                }
            }

            // fillColor 상속 (CharacterStyle → ParagraphStyle)
            if (run.fillColor() == null) {
                String color = resolveStyleProp(charStyleRef, doc, false, 0);
                Double tint = color != null ? resolveStyleFillTint(charStyleRef, doc, 0) : null;
                if (color == null) {
                    color = resolveStyleProp(paraStyleRef, doc, false, 0);
                    tint = color != null ? resolveStyleFillTint(paraStyleRef, doc, 0) : null;
                }
                if (color != null) {
                    run.fillColor(color);
                    if (run.fillTint() == null && tint != null) {
                        run.fillTint(tint);
                    }
                    colorCount++;
                }
            }

            // position 상속 (CharacterStyle에서만): 화학식 H₂O 같은 단순 첨자는
            // 수식 객체가 아니라 HWPX 문자 속성으로 보존해야 한다.
            if (isNormalPosition(run.position()) && charStyleRef != null) {
                String position = resolveStylePosition(charStyleRef, doc, 0);
                if (position != null) {
                    run.position(position);
                }
            }
        }
        return new int[]{fontCount, colorCount};
    }

    private static boolean isNormalPosition(String position) {
        if (position == null || position.trim().isEmpty()) return true;
        return position.toLowerCase(java.util.Locale.ROOT).contains("normal");
    }

    /**
     * 스타일 정의에서 속성 조회 (BasedOn 체인 따라감).
     * @param isFont true면 fontFamily, false면 fillColor
     */
    private static String resolveStyleProp(String styleRef, IDMLDocument doc,
                                             boolean isFont, int depth) {
        if (styleRef == null || depth > 10) return null;
        // CharacterStyle 먼저 시도, 없으면 ParagraphStyle
        IDMLStyleDef styleDef = doc.getCharacterStyle(styleRef);
        if (styleDef == null) styleDef = doc.getParagraphStyle(styleRef);
        if (styleDef == null) return null;

        String value = isFont ? styleDef.fontFamily() : styleDef.fillColor();
        if (value != null) return value;
        return resolveStyleProp(styleDef.basedOn(), doc, isFont, depth + 1);
    }

    private static Double resolveStyleFillTint(String styleRef, IDMLDocument doc, int depth) {
        if (styleRef == null || doc == null || depth > 10) return null;
        IDMLStyleDef styleDef = doc.getCharacterStyle(styleRef);
        if (styleDef == null) styleDef = doc.getParagraphStyle(styleRef);
        if (styleDef == null) return null;
        if (styleDef.fillTint() != null) return styleDef.fillTint();
        return resolveStyleFillTint(styleDef.basedOn(), doc, depth + 1);
    }

    private static String resolveStylePosition(String styleRef, IDMLDocument doc, int depth) {
        if (styleRef == null || depth > 10) return null;
        IDMLStyleDef styleDef = doc.getCharacterStyle(styleRef);
        if (styleDef == null) return null;
        if (styleDef.position() != null) return styleDef.position();
        return resolveStylePosition(styleDef.basedOn(), doc, depth + 1);
    }

    // ===== GREP 스타일 해석 =====

    /**
     * GREP 스타일에서 수식 폰트가 동적 적용되는 CharacterRun을 해석한다.
     */
    static void resolveGrepMathStyles(IDMLDocument doc) {
        // 1. 수식 AppliedFont(BT/EH)를 가진 문자 스타일 ID 셋 구축
        Set<String> mathCharStyleRefs = new HashSet<>();
        for (Map.Entry<String, IDMLStyleDef> entry : doc.charStyles().entrySet()) {
            IDMLStyleDef charStyle = entry.getValue();
            if (isGrepMathCharacterStyle(charStyle)) {
                mathCharStyleRefs.add(entry.getKey());
            }
        }
        if (mathCharStyleRefs.isEmpty()) return;

        // 2. 단락 스타일별 수식 GREP 규칙의 Java Pattern 캐시 구축
        Map<String, List<java.util.regex.Pattern>> paraStyleGrepPatterns = new HashMap<>();

        for (Map.Entry<String, IDMLStyleDef> entry : doc.paraStyles().entrySet()) {
            IDMLStyleDef paraStyle = entry.getValue();
            // SPEC-067: 수식 GREP 도 BasedOn 부모 체인까지 상속해 수집.
            List<IDMLStyleDef.GrepStyleRule> inheritedGrep = collectInheritedGrepStyles(doc, paraStyle);
            if (inheritedGrep.isEmpty()) continue;

            List<java.util.regex.Pattern> patterns = new ArrayList<>();
            for (IDMLStyleDef.GrepStyleRule rule : inheritedGrep) {
                // GREP 규칙이 수식 문자 스타일을 적용하는지 확인
                if (!mathCharStyleRefs.contains(rule.appliedCharacterStyle())) continue;

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
            System.err.println("[IDMLLoader] GREP math resolved: " + counts[0] + " runs"
                    + ", split: " + counts[1] + " mixed runs"
                    + " (math charStyles: " + mathCharStyleRefs.size()
                    + ", paraStyles with GREP: " + paraStyleGrepPatterns.size() + ")");
        }
    }

    private static boolean isGrepMathCharacterStyle(IDMLStyleDef charStyle) {
        if (charStyle == null) return false;
        String font = charStyle.fontFamily();
        if (font == null) return false;
        return BTFontGlyphMap.isBTFontFamily(font) || EHFontGlyphMap.isEHFontFamily(font);
    }

    /**
     * 문자 스타일 참조를 해결한다 (CharacterStyle/ 접두사 제거 후 맵 조회).
     */
    private static IDMLStyleDef findCharStyle(String ref, IDMLDocument doc) {
        if (ref == null) return null;
        IDMLStyleDef style = doc.charStyles().get(ref);
        if (style != null) return style;
        // "CharacterStyle/" 접두사 제거 시도
        if (ref.startsWith("CharacterStyle/")) {
            return doc.charStyles().get(ref.substring("CharacterStyle/".length()));
        }
        return null;
    }

    /**
     * GREP 스타일에서 일반 문자 스타일 속성(FillColor 등)을 동적 적용한다.
     * BT수식M 전용인 resolveGrepMathStyles와 달리, 모든 GREP 규칙의 문자 스타일을 적용한다.
     */
    /**
     * SPEC-067: 문단스타일의 GREP 규칙을 BasedOn 부모 체인까지 상속해 수집한다.
     * 자식 스타일이 같은 GREP 표현식을 재정의하면 자식 것이 이긴다(부모는 스킵).
     * InDesign 은 파생 문단스타일이 부모의 GREP 스타일을 물려받는다.
     */
    private static List<IDMLStyleDef.GrepStyleRule> collectInheritedGrepStyles(
            IDMLDocument doc, IDMLStyleDef paraStyle) {
        List<IDMLStyleDef.GrepStyleRule> out = new ArrayList<>();
        Set<String> seenExpr = new HashSet<>();
        Set<String> visited = new HashSet<>();
        IDMLStyleDef cur = paraStyle;
        while (cur != null) {
            String selfRef = cur.selfRef();
            if (selfRef != null && !visited.add(selfRef)) break;   // 순환 방지
            if (cur.grepStyles() != null) {
                for (IDMLStyleDef.GrepStyleRule rule : cur.grepStyles()) {
                    // 자식이 먼저 추가되므로 같은 표현식의 부모 규칙은 스킵(자식 우선)
                    if (rule.grepExpression() != null && seenExpr.add(rule.grepExpression())) {
                        out.add(rule);
                    }
                }
            }
            String basedOn = cur.basedOn();
            if (basedOn == null || basedOn.isEmpty()
                    || basedOn.contains("[No Paragraph Style]")
                    || basedOn.contains("[Root Paragraph Style]")) {
                break;
            }
            IDMLStyleDef parent = doc.paraStyles().get(basedOn);
            if (parent == null && basedOn.startsWith("ParagraphStyle/")) {
                parent = doc.paraStyles().get(basedOn.substring("ParagraphStyle/".length()));
            }
            cur = parent;
        }
        return out;
    }

    static void resolveGrepGenericStyles(IDMLDocument doc) {
        // BT수식M 문자 스타일 ID (이미 resolveGrepMathStyles에서 처리됨 → 제외)
        Set<String> btMathCharStyleRefs = new HashSet<>();
        for (Map.Entry<String, IDMLStyleDef> entry : doc.charStyles().entrySet()) {
            IDMLStyleDef charStyle = entry.getValue();
            String font = charStyle.fontFamily();
            if (font != null && (font.contains("BT수식") || font.contains("BTM"))) {
                btMathCharStyleRefs.add(entry.getKey());
            }
        }

        // 단락 스타일별 비수식 GREP 규칙 수집: {charStyleRef, compiledPattern}
        // 각 규칙에 적용할 문자 스타일 참조를 함께 저장
        Map<String, List<Object[]>> paraStyleGrepRules = new HashMap<>();

        for (Map.Entry<String, IDMLStyleDef> entry : doc.paraStyles().entrySet()) {
            IDMLStyleDef paraStyle = entry.getValue();
            // SPEC-067: GREP 규칙을 BasedOn 부모 체인까지 상속해 수집한다. 파생 문단
            // 스타일(예: "02_탐구,해보기_준비물(내어쓰기)")은 자기 GREP 이 없고 부모
            // ("02_탐구,해보기_준비물")의 "^무엇을 알아볼까→2_무엇을알아볼까요(제목)" 초록
            // GREP 을 물려받아야 한다. 상속을 안 하면 초록 제목이 색을 잃고 DOM 색에
            // 의존하게 된다(과학 u1 탐구 학습목표 초록).
            List<IDMLStyleDef.GrepStyleRule> inheritedGrep = collectInheritedGrepStyles(doc, paraStyle);
            if (inheritedGrep.isEmpty()) continue;

            List<Object[]> rules = new ArrayList<>();
            for (IDMLStyleDef.GrepStyleRule rule : inheritedGrep) {
                if (btMathCharStyleRefs.contains(rule.appliedCharacterStyle())) continue;
                // 적용 대상 문자 스타일이 존재하는지 확인
                IDMLStyleDef charStyle = findCharStyle(rule.appliedCharacterStyle(), doc);
                if (charStyle == null) continue;
                // 문자 스타일에 적용할 속성이 있는지 확인 (FillColor 등)
                if (charStyle.fillColor() == null && charStyle.fontFamily() == null
                        && charStyle.fontSize() == null && charStyle.fontStyle() == null) continue;

                java.util.regex.Pattern pat = convertIdGrepToJavaPattern(rule.grepExpression());
                if (pat != null) {
                    rules.add(new Object[]{rule.appliedCharacterStyle(), pat});
                }
            }
            if (!rules.isEmpty()) {
                paraStyleGrepRules.put(entry.getKey(), rules);
            }
        }
        if (paraStyleGrepRules.isEmpty()) return;

        int[] counts = {0, 0}; // [resolvedCount, splitCount]
        for (IDMLStory story : doc.stories().values()) {
            for (IDMLParagraph para : story.paragraphs()) {
                resolveGrepGenericForParagraph(para, paraStyleGrepRules, counts);
            }
            for (IDMLTable table : story.tables()) {
                for (IDMLTableRow row : table.rows()) {
                    for (IDMLTableCell cell : row.cells()) {
                        for (IDMLParagraph para : cell.paragraphs()) {
                            resolveGrepGenericForParagraph(para, paraStyleGrepRules, counts);
                        }
                    }
                }
            }
        }

        if (counts[0] > 0 || counts[1] > 0) {
            System.err.println("[IDMLLoader] GREP generic styles resolved: " + counts[0] + " runs"
                    + ", split: " + counts[1] + " mixed runs"
                    + " (paraStyles with GREP: " + paraStyleGrepRules.size() + ")");
        }
    }

    /**
     * 단락 내 CharacterRun에 일반 GREP 스타일 매칭을 수행한다.
     */
    static void resolveGrepGenericForParagraph(IDMLParagraph para,
                                                Map<String, List<Object[]>> paraStyleGrepRules,
                                                int[] counts) {
        String paraStyleRef = para.appliedParagraphStyle();
        List<Object[]> rules = paraStyleRef != null ? paraStyleGrepRules.get(paraStyleRef) : null;
        if (rules == null || rules.isEmpty()) return;

        List<IDMLCharacterRun> originalRuns = new ArrayList<>(para.characterRuns());
        StringBuilder paragraphText = new StringBuilder();
        int[] runStarts = new int[originalRuns.size()];
        java.util.Arrays.fill(runStarts, -1);
        for (int i = 0; i < originalRuns.size(); i++) {
            IDMLCharacterRun run = originalRuns.get(i);
            String text = run != null ? run.content() : null;
            if (text == null || text.isEmpty()) continue;
            runStarts[i] = paragraphText.length();
            paragraphText.append(text);
        }
        if (paragraphText.length() == 0) return;

        // InDesign GREP styles are paragraph-scoped. Matching run-by-run misses
        // styles that cross CharacterRun or anchored-object boundaries.
        String[] charStylePerChar = new String[paragraphText.length()];
        boolean anyParagraphMatch = false;
        for (Object[] rule : rules) {
            String charStyleRef = (String) rule[0];
            java.util.regex.Pattern pat = (java.util.regex.Pattern) rule[1];
            try {
                java.util.regex.Matcher m = pat.matcher(paragraphText);
                while (m.find()) {
                    if (m.end() <= m.start()) continue;
                    for (int i = m.start(); i < m.end(); i++) {
                        charStylePerChar[i] = charStyleRef;
                        anyParagraphMatch = true;
                    }
                }
            } catch (Exception e) { /* ignore */ }
        }
        if (!anyParagraphMatch) return;

        List<IDMLCharacterRun> newRuns = new ArrayList<>();
        boolean modified = false;

        for (int runIndex = 0; runIndex < originalRuns.size(); runIndex++) {
            IDMLCharacterRun run = originalRuns.get(runIndex);
            String text = run.content();
            if (text == null || text.isEmpty()) {
                newRuns.add(run);
                continue;
            }

            int paragraphOffset = runStarts[runIndex];
            if (paragraphOffset < 0) {
                newRuns.add(run);
                continue;
            }

            // 전체 매칭 확인
            boolean allSame = true;
            String firstStyle = charStylePerChar[paragraphOffset];
            for (int i = 1; i < text.length(); i++) {
                if (!java.util.Objects.equals(charStylePerChar[paragraphOffset + i], firstStyle)) {
                    allSame = false;
                    break;
                }
            }
            if (allSame && firstStyle != null) {
                // 전체 런이 동일 GREP 스타일 → 분리 불필요
                run.grepAppliedCharStyle(firstStyle);
                counts[0]++;
                newRuns.add(run);
                continue;
            }
            if (allSame) {
                // 전체 매칭 없음 (firstStyle == null)
                newRuns.add(run);
                continue;
            }

            // 매칭/비매칭 경계에서 분리
            modified = true;
            counts[1]++;
            // 인라인 프레임/그래픽의 \uFFFC 앵커 위치별 배분 준비
            List<IDMLTextFrame> srcFrames = run.inlineFrames();
            List<IDMLCharacterRun.InlineGraphic> srcGraphics = run.inlineGraphics();
            List<IDMLCharacterRun.InlineAnchor> srcAnchors = run.inlineAnchors();
            int anchorIdx = 0;
            int frameIdx = 0;
            int segStart = 0;
            for (int i = 1; i <= text.length(); i++) {
                if (i == text.length()
                        || !java.util.Objects.equals(
                                charStylePerChar[paragraphOffset + i],
                                charStylePerChar[paragraphOffset + segStart])) {
                    String segText = text.substring(segStart, i);
                    IDMLCharacterRun subRun = cloneRunWithText(run, segText);
                    if (charStylePerChar[paragraphOffset + segStart] != null) {
                        subRun.grepAppliedCharStyle(charStylePerChar[paragraphOffset + segStart]);
                        counts[0]++;
                    }
                    // 이 세그먼트에 포함된 \uFFFC 개수만큼 인라인 항목 배분
                    for (int ci = 0; ci < segText.length(); ci++) {
                        if (segText.charAt(ci) == '\uFFFC') {
                            if (!srcAnchors.isEmpty() && anchorIdx < srcAnchors.size()) {
                                IDMLCharacterRun.InlineAnchor anchor = srcAnchors.get(anchorIdx++);
                                if (anchor.type() == IDMLCharacterRun.InlineAnchorType.FRAME
                                        && anchor.index() < srcFrames.size()) {
                                    int newIdx = subRun.inlineFrames().size();
                                    subRun.addInlineFrame(srcFrames.get(anchor.index()));
                                    subRun.addInlineAnchor(IDMLCharacterRun.InlineAnchorType.FRAME, newIdx);
                                } else if (anchor.type() == IDMLCharacterRun.InlineAnchorType.GRAPHIC
                                        && anchor.index() < srcGraphics.size()) {
                                    int newIdx = subRun.inlineGraphics().size();
                                    subRun.addInlineGraphic(srcGraphics.get(anchor.index()));
                                    subRun.addInlineAnchor(IDMLCharacterRun.InlineAnchorType.GRAPHIC, newIdx);
                                }
                            } else if (frameIdx < srcFrames.size()) {
                                subRun.addInlineFrame(srcFrames.get(frameIdx));
                                frameIdx++;
                            }
                        }
                    }
                    newRuns.add(subRun);
                    segStart = i;
                }
            }
            // 나머지 항목을 마지막 서브런에 전달
            if (!newRuns.isEmpty()) {
                IDMLCharacterRun lastSub = newRuns.get(newRuns.size() - 1);
                if (!srcAnchors.isEmpty()) {
                    for (int ai = anchorIdx; ai < srcAnchors.size(); ai++) {
                        IDMLCharacterRun.InlineAnchor anchor = srcAnchors.get(ai);
                        if (anchor.type() == IDMLCharacterRun.InlineAnchorType.FRAME
                                && anchor.index() < srcFrames.size()) {
                            lastSub.addInlineFrame(srcFrames.get(anchor.index()));
                        } else if (anchor.type() == IDMLCharacterRun.InlineAnchorType.GRAPHIC
                                && anchor.index() < srcGraphics.size()) {
                            lastSub.addInlineGraphic(srcGraphics.get(anchor.index()));
                        }
                    }
                } else {
                    for (int fi = frameIdx; fi < srcFrames.size(); fi++) {
                        lastSub.addInlineFrame(srcFrames.get(fi));
                    }
                    for (IDMLCharacterRun.InlineGraphic ig : srcGraphics) {
                        lastSub.addInlineGraphic(ig);
                    }
                }
            }
        }

        if (modified) {
            para.characterRuns().clear();
            para.characterRuns().addAll(newRuns);
        }
    }

    /**
     * 단락 내 CharacterRun에 GREP 수식 스타일 매칭을 수행한다.
     */
    static void resolveGrepForParagraph(IDMLParagraph para,
                                        Map<String, List<java.util.regex.Pattern>> paraStyleGrepPatterns,
                                        int[] counts) {
        String paraStyleRef = para.appliedParagraphStyle();
        List<java.util.regex.Pattern> patterns = paraStyleRef != null
                ? paraStyleGrepPatterns.get(paraStyleRef)
                : null;
        if (patterns == null || patterns.isEmpty()) return;

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

            // 문자 단위 GREP 매칭 (한국어 포함 여부와 무관하게 동일 처리)
            boolean[] isMatch = new boolean[text.length()];
            boolean anyMatch = false;
            boolean allMatch = true;
            for (java.util.regex.Pattern pat : patterns) {
                try {
                    java.util.regex.Matcher m = pat.matcher(text);
                    while (m.find()) {
                        for (int i = m.start(); i < m.end(); i++) {
                            if (!isMatch[i]) { isMatch[i] = true; anyMatch = true; }
                        }
                    }
                } catch (Exception e) { /* ignore */ }
            }
            if (!anyMatch) {
                newRuns.add(run);
                continue;
            }
            for (boolean b : isMatch) { if (!b) { allMatch = false; break; } }
            if (allMatch) {
                run.grepMathFont(true);
                counts[0]++;
                newRuns.add(run);
                continue;
            }
            // 매칭/비매칭 경계에서 분리
            modified = true;
            counts[1]++;
            // 인라인 프레임/그래픽의 \uFFFC 앵커 위치별 배분 준비
            List<IDMLTextFrame> srcFrames = run.inlineFrames();
            List<IDMLCharacterRun.InlineGraphic> srcGraphics = run.inlineGraphics();
            List<IDMLCharacterRun.InlineAnchor> srcAnchors = run.inlineAnchors();
            int anchorIdx = 0;  // anchor mode
            int frameIdx = 0;   // legacy mode
            int segStart = 0;
            for (int i = 1; i <= text.length(); i++) {
                if (i == text.length() || isMatch[i] != isMatch[segStart]) {
                    String segText = text.substring(segStart, i);
                    IDMLCharacterRun subRun = cloneRunWithText(run, segText);
                    if (isMatch[segStart]) {
                        subRun.grepMathFont(true);
                        counts[0]++;
                    }
                    // 이 세그먼트에 포함된 \uFFFC 개수만큼 인라인 항목 배분
                    for (int ci = 0; ci < segText.length(); ci++) {
                        if (segText.charAt(ci) == '\uFFFC') {
                            if (!srcAnchors.isEmpty() && anchorIdx < srcAnchors.size()) {
                                // 앵커 기반: 타입에 따라 프레임 또는 그래픽 배분
                                IDMLCharacterRun.InlineAnchor anchor = srcAnchors.get(anchorIdx++);
                                if (anchor.type() == IDMLCharacterRun.InlineAnchorType.FRAME
                                        && anchor.index() < srcFrames.size()) {
                                    int newIdx = subRun.inlineFrames().size();
                                    subRun.addInlineFrame(srcFrames.get(anchor.index()));
                                    subRun.addInlineAnchor(IDMLCharacterRun.InlineAnchorType.FRAME, newIdx);
                                } else if (anchor.type() == IDMLCharacterRun.InlineAnchorType.GRAPHIC
                                        && anchor.index() < srcGraphics.size()) {
                                    int newIdx = subRun.inlineGraphics().size();
                                    subRun.addInlineGraphic(srcGraphics.get(anchor.index()));
                                    subRun.addInlineAnchor(IDMLCharacterRun.InlineAnchorType.GRAPHIC, newIdx);
                                }
                            } else if (frameIdx < srcFrames.size()) {
                                // 레거시: FFFC → TextFrame 순서 매핑
                                subRun.addInlineFrame(srcFrames.get(frameIdx));
                                frameIdx++;
                            }
                        }
                    }
                    newRuns.add(subRun);
                    segStart = i;
                }
            }
            // 나머지 항목을 마지막 서브런에 전달
            if (!newRuns.isEmpty()) {
                IDMLCharacterRun lastSub = newRuns.get(newRuns.size() - 1);
                if (!srcAnchors.isEmpty()) {
                    // 앵커 기반: 미처리 앵커를 마지막 서브런에 전달
                    for (int ai = anchorIdx; ai < srcAnchors.size(); ai++) {
                        IDMLCharacterRun.InlineAnchor anchor = srcAnchors.get(ai);
                        if (anchor.type() == IDMLCharacterRun.InlineAnchorType.FRAME
                                && anchor.index() < srcFrames.size()) {
                            lastSub.addInlineFrame(srcFrames.get(anchor.index()));
                        } else if (anchor.type() == IDMLCharacterRun.InlineAnchorType.GRAPHIC
                                && anchor.index() < srcGraphics.size()) {
                            lastSub.addInlineGraphic(srcGraphics.get(anchor.index()));
                        }
                    }
                } else {
                    // 레거시: 남은 프레임 + 모든 그래픽을 마지막 서브런에 전달
                    for (int fi = frameIdx; fi < srcFrames.size(); fi++) {
                        lastSub.addInlineFrame(srcFrames.get(fi));
                    }
                    for (IDMLCharacterRun.InlineGraphic ig : srcGraphics) {
                        lastSub.addInlineGraphic(ig);
                    }
                }
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

    private static String firstAttr(Element elem, String... names) {
        if (elem == null || names == null) return null;
        for (String name : names) {
            String value = getAttrOrNull(elem, name);
            if (value != null && !value.isEmpty() && !"Nothing".equalsIgnoreCase(value)
                    && !"None".equalsIgnoreCase(value)) {
                return value;
            }
        }
        return null;
    }

    private static Double firstDoubleAttr(Element elem, String... names) {
        String value = firstAttr(elem, names);
        if (value == null) return null;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstPropertyText(Element props, String... names) {
        if (props == null || names == null) return null;
        for (String name : names) {
            String value = getPropertyText(props, name);
            if (value != null && !value.isEmpty() && !"Nothing".equalsIgnoreCase(value)
                    && !"None".equalsIgnoreCase(value)) {
                return value;
            }
        }
        return null;
    }

    private static Double firstPropertyDouble(Element props, String... names) {
        String value = firstPropertyText(props, names);
        if (value == null) return null;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isShadingEnabled(Element elem, String color) {
        if (elem == null) return false;
        String on = firstAttr(elem, "CharacterShadingOn", "ShadingOn", "TextShadingOn");
        if (on != null) return "true".equalsIgnoreCase(on);
        String propOn = getPropertyText(elem, "CharacterShadingOn");
        if (propOn == null) propOn = getPropertyText(elem, "ShadingOn");
        if (propOn == null) propOn = getPropertyText(elem, "TextShadingOn");
        if (propOn != null) return "true".equalsIgnoreCase(propOn);
        return color != null;
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
        clone.fillTint(source.fillTint());
        clone.fontStyle(source.fontStyle());
        clone.position(source.position());
        clone.tracking(source.tracking());
        clone.underline(source.underline());
        clone.underlineType(source.underlineType());
        clone.underlineTint(source.underlineTint());
        clone.shadeColor(source.shadeColor());
        clone.shadeTint(source.shadeTint());
        clone.strikeThrough(source.strikeThrough());
        clone.baselineShift(source.baselineShift());
        clone.horizontalScale(source.horizontalScale());
        clone.capitalization(source.capitalization());
        clone.grepMathFont(source.grepMathFont());
        clone.grepFillColor(source.grepFillColor());
        clone.grepAppliedCharStyle(source.grepAppliedCharStyle());
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
            // InDesign GREP anchored object marker -> Object Replacement Character.
            javaRegex = javaRegex.replace("~a", "\uFFFC");
            // InDesign GREP figure space marker. Glossary/title styles commonly
            // use this as the delimiter between the term and its explanation.
            javaRegex = javaRegex.replace("~/", "\u2007");
            // InDesign GREP em space marker. Process-label styles use this as
            // the delimiter after labels such as "관찰·예상".
            javaRegex = javaRegex.replace("~m", "\u2003");
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

    // ===== PUA → 표준 유니코드 변환 =====

    /**
     * Private Use Area(PUA) 문자를 표준 유니코드로 변환한다.
     * InDesign 전용 폰트가 PUA 코드포인트에 IPA 발음 기호 등을 매핑하는데,
     * HWPX에서는 해당 폰트가 없어 한글고어 등으로 잘못 표시된다.
     */
    private static final Map<Character, String> PUA_MAP = new HashMap<>();
    static {
        // IPA 발음 기호
        PUA_MAP.put('\uE1B5', "\u00E6");         // æ
        PUA_MAP.put('\uE194', "\u0259");         // ə
        PUA_MAP.put('\uE121', "\u0251");         // ɑ
        PUA_MAP.put('\uE13A', "\u02D0");         // ː
        // 기호: U+E285/E287/E288은 HwpxUtil.sanitizeText에서 □(U+25A1)로 통합 변환
    }

    static String replacePUA(String text) {
        if (text == null || text.isEmpty()) return text;
        boolean hasPUA = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= '\uE000' && ch <= '\uF8FF') {
                hasPUA = true;
                break;
            }
        }
        if (!hasPUA) return text;

        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            String replacement = PUA_MAP.get(ch);
            if (replacement != null) {
                sb.append(replacement);
            } else if (ch >= '\uE000' && ch <= '\uF8FF') {
                // 매핑 없는 PUA → 그대로 유지하되 경고
                System.err.println("[PUA-WARN] Unmapped PUA character U+"
                        + String.format("%04X", (int) ch));
                sb.append(ch);
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /**
     * 화살표 글리프 런의 텍스트를 실제 화살표(→)로 정규화한다 — 파싱 직후 한 번만.
     *
     * <p>InDesign 은 화학 반응식의 화살표를 "BT화살표" 전용 폰트로 조판한다. 그 폰트는
     * 특정 글자 자리에 화살표 모양을 그려둔 것이라, 파일에 저장된 실제 글자가 제각각이다
     * (실측 — 과학 1단원 한 문서 안에서: "C" 5회, "@" 4회, "@C" 3회, "?C" 2회).
     * 전부 화면에는 똑같이 화살표로 보인다.
     *
     * <p>이걸 하류에서 각자 치환하면 문제가 생긴다. 실제로 네 곳에서 제각각 치환하다가
     * 중복 삽입("→→")과 런 매칭 어긋남(Ca(OH)₂ 의 C 소실)이 발생했다.
     * → 여기서 통일해두면 하류는 평범한 텍스트로 다루면 된다.
     *
     * <p>폰트/스타일은 지우지 않는다. 하류가 그 정보를 쓸 수 있어 텍스트만 바꾼다.
     */
    private static void normalizeArrowGlyphRun(IDMLCharacterRun run) {
        if (run == null) return;
        String normalized = BTFontGlyphMap.normalizeArrowGlyphText(
                run.fontFamily(), run.appliedCharacterStyle(), run.content());
        if (normalized != null && !normalized.equals(run.content())) {
            run.content(normalized);
        }
    }

    /**
     * 이 런이 앞 런과 같은 화살표 글리프의 나머지 조각인가.
     *
     * <p>하나의 화살표 글리프가 여러 런으로 쪼개져 들어오기도 한다(실측: "@C" 가
     * 런 '@' + 런 'C' 로 분리). 각각 "→" 로 정규화하면 "→→" 가 되므로,
     * 앞 런이 이미 화살표면 이 조각은 버린다.
     */
    private static boolean isDuplicateArrowRun(IDMLParagraph para, IDMLCharacterRun run) {
        if (para == null || run == null) return false;
        if (!BTFontGlyphMap.ARROW.equals(run.content())) return false;
        List<IDMLCharacterRun> runs = para.characterRuns();
        if (runs == null || runs.isEmpty()) return false;
        IDMLCharacterRun last = runs.get(runs.size() - 1);
        return last != null && BTFontGlyphMap.ARROW.equals(last.content());
    }

}
