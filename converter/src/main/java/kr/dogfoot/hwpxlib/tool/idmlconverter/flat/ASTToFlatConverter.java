package kr.dogfoot.hwpxlib.tool.idmlconverter.flat;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;

import java.util.*;

/**
 * AST → Flat 변환기.
 * 재귀적 ASTDocument 트리를 3개 레이어의 평탄한 FlatDocument로 변환한다.
 * <ul>
 *   <li>Layer 0: FlatPage (ASTSection에서)</li>
 *   <li>Layer 1: FlatLayoutNode (ASTTextFrameBlock, ASTTable, ASTFigure, ASTInlineObject에서)</li>
 *   <li>Layer 2: FlatComponent (ASTParagraph에서)</li>
 * </ul>
 */
public class ASTToFlatConverter {

    /**
     * ASTDocument를 FlatDocument로 변환한다.
     *
     * @param astDoc 변환할 AST 문서
     * @return 평탄화된 문서
     */
    public static FlatDocument convert(ASTDocument astDoc) {
        FlatDocument flat = new FlatDocument();
        FlatIdGenerator idGen = new FlatIdGenerator();

        // Copy metadata (fonts, styles, colors, stories, backgrounds)
        copyMetadata(astDoc, flat);

        // Convert each section (page)
        for (ASTSection section : astDoc.sections()) {
            FlatPage page = convertPage(section, idGen);
            flat.pages().add(page);

            // Convert blocks on this page
            for (ASTBlock block : section.blocks()) {
                if (block instanceof ASTTextFrameBlock) {
                    convertTextFrameBlock((ASTTextFrameBlock) block, page, flat, idGen);
                } else if (block instanceof ASTTable) {
                    convertTable((ASTTable) block, page, flat, idGen,
                            FlatLayoutNode.PositioningMode.ABSOLUTE);
                } else if (block instanceof ASTFigure) {
                    convertFigure((ASTFigure) block, page, flat, idGen);
                }
            }

            // Sort zOrderedNodeIds
            sortByZOrder(page, flat);

            // Classify semantic layers
            classifySemanticLayers(page, flat);
        }
        return flat;
    }

    // =========================================================================
    // Metadata copy
    // =========================================================================

    private static void copyMetadata(ASTDocument astDoc, FlatDocument flat) {
        flat.sourceFile(astDoc.sourceFile());
        flat.sourceFormat(astDoc.sourceFormat());

        // Fonts — share references
        flat.fonts(new ArrayList<ASTFontDef>(astDoc.fonts()));

        // Paragraph styles
        flat.paragraphStyles(new ArrayList<ASTStyleDef>(astDoc.paragraphStyles()));

        // Character styles
        flat.characterStyles(new ArrayList<ASTStyleDef>(astDoc.characterStyles()));

        // Colors
        flat.colors(new LinkedHashMap<String, String>(astDoc.colors()));

        // Stories
        flat.stories(new ArrayList<ASTStory>(astDoc.stories()));

        // Backgrounds
        flat.backgrounds(new ArrayList<ASTPageBackground>(astDoc.backgrounds()));
    }

    // =========================================================================
    // Page conversion
    // =========================================================================

    private static FlatPage convertPage(ASTSection section, FlatIdGenerator idGen) {
        FlatPage page = new FlatPage();
        page.pageId(idGen.nextPageId());
        page.pageNumber(section.pageNumber());

        ASTPageLayout layout = section.layout();
        if (layout != null) {
            page.pageWidth(layout.pageWidth());
            page.pageHeight(layout.pageHeight());
            page.marginTop(layout.marginTop());
            page.marginBottom(layout.marginBottom());
            page.marginLeft(layout.marginLeft());
            page.marginRight(layout.marginRight());
            page.columnCount(layout.columnCount());
            page.columnGutter(layout.columnGutter());
        }
        return page;
    }

    // =========================================================================
    // TextFrameBlock conversion
    // =========================================================================

    private static void convertTextFrameBlock(ASTTextFrameBlock tfb, FlatPage page,
                                              FlatDocument flat, FlatIdGenerator idGen) {
        FlatLayoutNode node = new FlatLayoutNode();
        node.nodeId(idGen.nextNodeId());
        node.nodeType(FlatLayoutNode.NodeType.TEXT_FRAME);
        node.positioning(FlatLayoutNode.PositioningMode.ABSOLUTE);
        node.pageId(page.pageId());
        node.sourceId(tfb.sourceId());

        // Geometry
        node.x(tfb.x());
        node.y(tfb.y());
        node.width(tfb.width());
        node.height(tfb.height());
        node.zOrder(tfb.zOrder());

        // Column layout
        node.columnCount(tfb.columnCount());
        node.columnGutter(tfb.columnGutter());
        node.columnWidths(tfb.columnWidths());

        // Text direction
        node.verticalText(tfb.verticalText());
        node.verticalJustification(tfb.verticalJustification());

        // Insets
        node.insetTop(tfb.insetTop());
        node.insetLeft(tfb.insetLeft());
        node.insetBottom(tfb.insetBottom());
        node.insetRight(tfb.insetRight());

        // Frame style
        node.fillColor(tfb.fillColor());
        node.strokeColor(tfb.strokeColor());
        node.strokeWeight(tfb.strokeWeight());
        node.strokeType(tfb.strokeType());
        node.fillTint(tfb.fillTint());
        node.strokeTint(tfb.strokeTint());
        node.cornerRadius(tfb.cornerRadius());
        node.fromGroup(tfb.fromGroup());
        node.storyId(tfb.storyId());
        node.distributed(tfb.distributed());
        node.rotationAngle(tfb.rotationAngle());
        node.narrowedWidth(tfb.narrowedWidth());
        node.narrowedXOffset(tfb.narrowedXOffset());

        // Wrapper fill
        node.wrapperFillColor(tfb.wrapperFillColor());
        node.wrapperFillTint(tfb.wrapperFillTint());

        // Drop shadow
        node.dropShadow(tfb.dropShadow());

        // Polygon path
        node.pathPoints(tfb.pathPointsX(), tfb.pathPointsY());

        // Convert paragraphs
        convertParagraphs(tfb.paragraphs(), node, page, flat, idGen);

        flat.layoutNodes().add(node);
        page.layoutNodeIds().add(node.nodeId());
    }

    // =========================================================================
    // Table conversion
    // =========================================================================

    private static void convertTable(ASTTable table, FlatPage page, FlatDocument flat,
                                     FlatIdGenerator idGen,
                                     FlatLayoutNode.PositioningMode positioning) {
        FlatLayoutNode node = new FlatLayoutNode();
        node.nodeId(idGen.nextNodeId());
        node.nodeType(FlatLayoutNode.NodeType.TABLE);
        node.positioning(positioning);
        node.pageId(page.pageId());
        node.sourceId(table.sourceId());

        // Geometry
        node.x(table.x());
        node.y(table.y());
        node.width(table.width());
        node.height(table.height());
        node.zOrder(table.zOrder());

        // Table structure
        node.rowCount(table.rowCount());
        node.colCount(table.colCount());

        // Column widths
        if (table.columnWidths() != null) {
            node.tableColumnWidths(new ArrayList<Long>(table.columnWidths()));
        }

        // Table style
        node.appliedTableStyle(table.appliedTableStyle());
        node.borderColor(table.borderColor());
        node.borderWidth(table.borderWidth());

        // Convert rows
        for (ASTTableRow astRow : table.rows()) {
            FlatTableRow flatRow = new FlatTableRow();
            flatRow.rowIndex(astRow.rowIndex());
            flatRow.rowHeight(astRow.rowHeight());
            flatRow.autoGrow(astRow.autoGrow());

            for (ASTTableCell astCell : astRow.cells()) {
                FlatTableCell flatCell = convertTableCell(astCell, node, page, flat, idGen);
                flatRow.addCell(flatCell);
            }
            node.addTableRow(flatRow);
        }

        flat.layoutNodes().add(node);
        page.layoutNodeIds().add(node.nodeId());
    }

    private static FlatTableCell convertTableCell(ASTTableCell astCell, FlatLayoutNode tableNode,
                                                  FlatPage page, FlatDocument flat,
                                                  FlatIdGenerator idGen) {
        FlatTableCell flatCell = new FlatTableCell();
        flatCell.rowIndex(astCell.rowIndex());
        flatCell.columnIndex(astCell.columnIndex());
        flatCell.rowSpan(astCell.rowSpan());
        flatCell.columnSpan(astCell.columnSpan());
        flatCell.width(astCell.width());
        flatCell.height(astCell.height());

        // Cell style
        flatCell.fillColor(astCell.fillColor());

        // Cell borders
        flatCell.topBorder(convertCellBorder(astCell.topBorder()));
        flatCell.bottomBorder(convertCellBorder(astCell.bottomBorder()));
        flatCell.leftBorder(convertCellBorder(astCell.leftBorder()));
        flatCell.rightBorder(convertCellBorder(astCell.rightBorder()));
        flatCell.topLeftDiagonalLine(astCell.topLeftDiagonalLine());
        flatCell.topRightDiagonalLine(astCell.topRightDiagonalLine());
        flatCell.diagonalBorder(convertCellBorder(astCell.diagonalBorder()));

        // Cell margins
        flatCell.marginTop(astCell.marginTop());
        flatCell.marginBottom(astCell.marginBottom());
        flatCell.marginLeft(astCell.marginLeft());
        flatCell.marginRight(astCell.marginRight());
        flatCell.verticalAlign(astCell.verticalAlign());

        // Convert cell paragraphs → FlatComponent
        for (ASTParagraph astPara : astCell.paragraphs()) {
            FlatComponent comp = convertParagraph(astPara, tableNode, page, flat, idGen);
            flatCell.addComponentId(comp.componentId());
            tableNode.addComponentId(comp.componentId());
        }

        return flatCell;
    }

    private static FlatTableCell.CellBorder convertCellBorder(ASTTableCell.CellBorder astBorder) {
        if (astBorder == null) {
            return null;
        }
        FlatTableCell.CellBorder flatBorder = new FlatTableCell.CellBorder();
        flatBorder.color(astBorder.color());
        flatBorder.weight(astBorder.weight());
        flatBorder.strokeType(astBorder.strokeType());
        flatBorder.tint(astBorder.tint());
        return flatBorder;
    }

    // =========================================================================
    // Figure conversion
    // =========================================================================

    private static void convertFigure(ASTFigure fig, FlatPage page,
                                      FlatDocument flat, FlatIdGenerator idGen) {
        FlatLayoutNode node = new FlatLayoutNode();
        node.nodeId(idGen.nextNodeId());
        node.nodeType(FlatLayoutNode.NodeType.FIGURE);
        node.positioning(FlatLayoutNode.PositioningMode.ABSOLUTE);
        node.pageId(page.pageId());
        node.sourceId(fig.sourceId());

        // Figure kind
        node.figureKind(fig.kind() != null ? fig.kind().name() : null);

        // Geometry
        node.x(fig.x());
        node.y(fig.y());
        node.width(fig.width());
        node.height(fig.height());
        node.zOrder(fig.zOrder());
        node.rotationAngle(fig.rotationAngle());
        node.flipHorizontal(fig.flipHorizontal());
        node.flipVertical(fig.flipVertical());

        // Image data
        node.imageFormat(fig.imageFormat());
        node.imageData(fig.imageData());
        node.imagePath(fig.imagePath());
        node.pixelWidth(fig.pixelWidth());
        node.pixelHeight(fig.pixelHeight());

        // Crop
        node.cropLeftFraction(fig.cropLeftFraction());
        node.cropTopFraction(fig.cropTopFraction());
        node.cropRightFraction(fig.cropRightFraction());
        node.cropBottomFraction(fig.cropBottomFraction());

        // Text wrap
        node.textWrapMode(fig.textWrapMode());
        node.textWrapSide(fig.textWrapSide());
        node.textWrapTop(fig.textWrapTop());
        node.textWrapLeft(fig.textWrapLeft());
        node.textWrapBottom(fig.textWrapBottom());
        node.textWrapRight(fig.textWrapRight());

        // Bundle path
        node.bundlePath(fig.bundlePath());

        // From group
        node.fromGroup(fig.fromGroup());

        flat.layoutNodes().add(node);
        page.layoutNodeIds().add(node.nodeId());
    }

    // =========================================================================
    // Paragraph conversion (shared by TextFrame, Table cells, InlineTextFrame)
    // =========================================================================

    /**
     * 다수의 ASTParagraph를 FlatComponent로 변환하고 부모 노드에 등록한다.
     */
    private static void convertParagraphs(List<ASTParagraph> paragraphs,
                                          FlatLayoutNode parentNode,
                                          FlatPage page, FlatDocument flat,
                                          FlatIdGenerator idGen) {
        if (paragraphs == null) {
            return;
        }
        for (ASTParagraph astPara : paragraphs) {
            FlatComponent comp = convertParagraph(astPara, parentNode, page, flat, idGen);
            parentNode.addComponentId(comp.componentId());
        }
    }

    /**
     * 단일 ASTParagraph → FlatComponent 변환.
     * 인라인 아이템을 순회하며, 인라인 객체는 FlatLayoutNode + LAYOUT_REF로 변환한다.
     */
    private static FlatComponent convertParagraph(ASTParagraph astPara,
                                                  FlatLayoutNode parentNode,
                                                  FlatPage page, FlatDocument flat,
                                                  FlatIdGenerator idGen) {
        FlatComponent comp = new FlatComponent();
        comp.componentId(idGen.nextComponentId());
        comp.type(FlatComponent.ComponentType.PARAGRAPH);
        comp.parentNodeId(parentNode.nodeId());

        // Paragraph style reference
        comp.paragraphStyleRef(astPara.paragraphStyleRef());

        // Paragraph attributes (local overrides)
        comp.alignment(astPara.alignment());
        comp.firstLineIndent(astPara.firstLineIndent());
        comp.leftMargin(astPara.leftMargin());
        comp.rightMargin(astPara.rightMargin());
        comp.spaceBefore(astPara.spaceBefore());
        comp.spaceAfter(astPara.spaceAfter());
        comp.lineSpacing(astPara.lineSpacing());
        comp.lineSpacingType(astPara.lineSpacingType());
        comp.letterSpacing(astPara.letterSpacing());

        // Paragraph shading
        comp.shadingOn(astPara.shadingOn());
        comp.shadingColor(astPara.shadingColor());
        comp.shadingTint(astPara.shadingTint());
        comp.shadingLeftOffset(astPara.shadingLeftOffset());
        comp.shadingRightOffset(astPara.shadingRightOffset());
        comp.shadingTopOffset(astPara.shadingTopOffset());
        comp.shadingBottomOffset(astPara.shadingBottomOffset());

        // Tab stops
        if (astPara.hasTabStops()) {
            for (ASTTabStop ts : astPara.tabStops()) {
                comp.addTabStop(ts);
            }
        }

        // Frame Y offset
        comp.yOffsetInFrame(astPara.yOffsetInFrame());

        // Column break
        comp.columnBreakAfter(astPara.columnBreakAfter());

        // Indent to Here
        comp.indentToHerePosition(astPara.indentToHerePosition());

        // Pending underline color
        comp.pendingUnderlineColor(astPara.pendingUnderlineColor());

        // Handle inline table (prepend LAYOUT_REF before inline items)
        if (astPara.inlineTable() != null) {
            FlatLayoutNode tableNode = convertInlineTable(astPara.inlineTable(), comp, page, flat, idGen);
            FlatInlineItem tableRef = FlatInlineItem.layoutRef(tableNode.nodeId());
            comp.addItem(tableRef);
        }

        // Convert inline items
        List<ASTInlineItem> items = astPara.items();
        for (int i = 0; i < items.size(); i++) {
            ASTInlineItem astItem = items.get(i);
            switch (astItem.itemType()) {
                case TEXT_RUN:
                    comp.addItem(convertTextRun((ASTTextRun) astItem));
                    break;
                case BREAK:
                    comp.addItem(convertBreak((ASTBreak) astItem));
                    break;
                case EQUATION:
                    comp.addItem(convertEquation((ASTEquation) astItem));
                    break;
                case INLINE_OBJECT:
                    convertInlineObject((ASTInlineObject) astItem, comp, i, page, flat, idGen);
                    break;
            }
        }

        flat.components().add(comp);
        return comp;
    }

    // =========================================================================
    // Inline item conversion
    // =========================================================================

    private static FlatInlineItem convertTextRun(ASTTextRun run) {
        FlatInlineItem item = FlatInlineItem.textRun();
        item.characterStyleRef(run.characterStyleRef());
        item.text(run.text());
        item.fontFamily(run.fontFamily());
        item.fontStyle(run.fontStyle());
        item.fontSizeHwpunits(run.fontSizeHwpunits());
        item.textColor(run.textColor());
        item.letterSpacing(run.letterSpacing());
        item.subscript(run.subscript());
        item.superscript(run.superscript());
        item.grepMathFont(run.grepMathFont());
        item.underline(run.underline());
        item.underlineColor(run.underlineColor());
        item.underlineShape(run.underlineShape());
        item.strikeThrough(run.strikeThrough());
        item.horizontalScale(run.horizontalScale());
        item.verticalScale(run.verticalScale());
        item.baselineShift(run.baselineShift());
        return item;
    }

    private static FlatInlineItem convertBreak(ASTBreak brk) {
        FlatInlineItem item = new FlatInlineItem();
        item.itemType(FlatInlineItem.ItemType.BREAK);
        item.breakType(brk.breakType() != null ? brk.breakType().name() : null);
        return item;
    }

    private static FlatInlineItem convertEquation(ASTEquation eq) {
        FlatInlineItem item = FlatInlineItem.equation(eq.hwpScript(), eq.sourceType());
        item.equationTextColor(eq.textColor());
        return item;
    }

    // =========================================================================
    // Inline object conversion — the recursion breaker
    // =========================================================================

    /**
     * ASTInlineObject를 FlatLayoutNode(INLINE) + FlatInlineItem(LAYOUT_REF)로 변환.
     * 인라인 객체의 종류(IMAGE, INLINE_TEXT_FRAME, RENDERED_GROUP, SPACER_RECT)에 따라
     * 다른 FlatLayoutNode 타입을 생성한다.
     */
    private static void convertInlineObject(ASTInlineObject inObj, FlatComponent parentComp,
                                            int insertionIdx, FlatPage page,
                                            FlatDocument flat, FlatIdGenerator idGen) {
        ASTInlineObject.ObjectKind kind = inObj.kind();
        if (kind == null) {
            return;
        }

        switch (kind) {
            case IMAGE:
                convertInlineImage(inObj, parentComp, insertionIdx, page, flat, idGen);
                break;
            case RENDERED_GROUP:
                convertInlineRenderedGroup(inObj, parentComp, insertionIdx, page, flat, idGen);
                break;
            case INLINE_TEXT_FRAME:
                convertInlineTextFrame(inObj, parentComp, insertionIdx, page, flat, idGen);
                break;
            case SPACER_RECT:
                convertInlineSpacer(inObj, parentComp, insertionIdx, page, flat, idGen);
                break;
        }
    }

    // ---- IMAGE ----

    private static void convertInlineImage(ASTInlineObject inObj, FlatComponent parentComp,
                                           int insertionIdx, FlatPage page,
                                           FlatDocument flat, FlatIdGenerator idGen) {
        FlatLayoutNode node = new FlatLayoutNode();
        node.nodeId(idGen.nextNodeId());
        node.nodeType(FlatLayoutNode.NodeType.FIGURE);
        node.positioning(FlatLayoutNode.PositioningMode.INLINE);
        node.pageId(page.pageId());
        node.sourceId(inObj.sourceId());
        node.parentComponentId(parentComp.componentId());
        node.insertionIndex(insertionIdx);

        // Figure kind
        node.figureKind("IMAGE");

        // Size
        node.width(inObj.width());
        node.height(inObj.height());

        // Image data
        node.imageFormat(inObj.imageFormat());
        node.imageData(inObj.imageData());
        node.imagePath(inObj.imagePath());
        node.pixelWidth(inObj.pixelWidth());
        node.pixelHeight(inObj.pixelHeight());
        node.bundlePath(inObj.bundlePath());

        // Container size
        node.containerWidth(inObj.containerWidth());
        node.containerHeight(inObj.containerHeight());
        node.imageOffsetX(inObj.imageOffsetX());
        node.imageOffsetY(inObj.imageOffsetY());

        // Frame style (rendered text frame replacement can set these)
        node.fillColor(inObj.fillColor());
        node.fillTint(inObj.fillTint());
        node.strokeColor(inObj.strokeColor());
        node.strokeWeight(inObj.strokeWeight());
        node.strokeTint(inObj.strokeTint());
        node.cornerRadius(inObj.cornerRadius());

        // Anchoring / text wrap
        node.anchoredPosition(inObj.anchoredPosition());
        node.textWrapMode(inObj.textWrapMode());
        node.textWrapSide(inObj.textWrapSide());
        node.textWrapTop(inObj.textWrapTop());
        node.textWrapLeft(inObj.textWrapLeft());
        node.textWrapBottom(inObj.textWrapBottom());
        node.textWrapRight(inObj.textWrapRight());

        // Resolved coordinates
        node.resolvedPageX(inObj.resolvedPageX());
        node.resolvedPageY(inObj.resolvedPageY());
        node.resolvedWidth(inObj.resolvedWidth());
        node.resolvedHeight(inObj.resolvedHeight());

        flat.layoutNodes().add(node);
        page.layoutNodeIds().add(node.nodeId());

        // Add LAYOUT_REF to parent component
        FlatInlineItem ref = FlatInlineItem.layoutRef(node.nodeId());
        parentComp.addItem(ref);

        // Convert overlay frames (if any)
        if (inObj.overlayFrames() != null) {
            for (ASTInlineObject overlayObj : inObj.overlayFrames()) {
                convertOverlayFrame(overlayObj, node, page, flat, idGen);
            }
        }
    }

    // ---- RENDERED_GROUP ----

    private static void convertInlineRenderedGroup(ASTInlineObject inObj, FlatComponent parentComp,
                                                   int insertionIdx, FlatPage page,
                                                   FlatDocument flat, FlatIdGenerator idGen) {
        // RENDERED_GROUP with paragraphs/tables → treat as TEXT_FRAME
        // (same behavior as HwpxParagraphBuilder which routes these to textBoxBuilder)
        boolean hasContent = (inObj.paragraphs() != null && !inObj.paragraphs().isEmpty())
                || (inObj.inlineTables() != null && !inObj.inlineTables().isEmpty());

        if (hasContent) {
            int sizeBefore = flat.layoutNodes().size();
            convertInlineTextFrame(inObj, parentComp, insertionIdx, page, flat, idGen);
            // Tag the first created node (the TEXT_FRAME itself) with figureKind
            // to preserve original RENDERED_GROUP kind for round-trip
            if (flat.layoutNodes().size() > sizeBefore) {
                FlatLayoutNode createdNode = flat.layoutNodes().get(sizeBefore);
                createdNode.figureKind("RENDERED_GROUP");
            }
            return;
        }

        FlatLayoutNode node = new FlatLayoutNode();
        node.nodeId(idGen.nextNodeId());
        node.nodeType(FlatLayoutNode.NodeType.FIGURE);
        node.positioning(FlatLayoutNode.PositioningMode.INLINE);
        node.pageId(page.pageId());
        node.sourceId(inObj.sourceId());
        node.parentComponentId(parentComp.componentId());
        node.insertionIndex(insertionIdx);

        // Figure kind
        node.figureKind("RENDERED_GROUP");

        // Size
        node.width(inObj.width());
        node.height(inObj.height());

        // Image data
        node.imageFormat(inObj.imageFormat());
        node.imageData(inObj.imageData());
        node.imagePath(inObj.imagePath());
        node.pixelWidth(inObj.pixelWidth());
        node.pixelHeight(inObj.pixelHeight());
        node.bundlePath(inObj.bundlePath());

        // Container size
        node.containerWidth(inObj.containerWidth());
        node.containerHeight(inObj.containerHeight());
        node.imageOffsetX(inObj.imageOffsetX());
        node.imageOffsetY(inObj.imageOffsetY());

        // Frame style
        node.fillColor(inObj.fillColor());
        node.fillTint(inObj.fillTint());
        node.strokeColor(inObj.strokeColor());
        node.strokeWeight(inObj.strokeWeight());
        node.strokeTint(inObj.strokeTint());
        node.cornerRadius(inObj.cornerRadius());

        // Anchoring / text wrap
        node.anchoredPosition(inObj.anchoredPosition());
        node.textWrapMode(inObj.textWrapMode());
        node.textWrapSide(inObj.textWrapSide());
        node.textWrapTop(inObj.textWrapTop());
        node.textWrapLeft(inObj.textWrapLeft());
        node.textWrapBottom(inObj.textWrapBottom());
        node.textWrapRight(inObj.textWrapRight());

        // Resolved coordinates
        node.resolvedPageX(inObj.resolvedPageX());
        node.resolvedPageY(inObj.resolvedPageY());
        node.resolvedWidth(inObj.resolvedWidth());
        node.resolvedHeight(inObj.resolvedHeight());

        flat.layoutNodes().add(node);
        page.layoutNodeIds().add(node.nodeId());

        // Add LAYOUT_REF to parent component
        FlatInlineItem ref = FlatInlineItem.layoutRef(node.nodeId());
        parentComp.addItem(ref);
    }

    // ---- INLINE_TEXT_FRAME ----

    private static void convertInlineTextFrame(ASTInlineObject inObj, FlatComponent parentComp,
                                               int insertionIdx, FlatPage page,
                                               FlatDocument flat, FlatIdGenerator idGen) {
        FlatLayoutNode node = new FlatLayoutNode();
        node.nodeId(idGen.nextNodeId());
        node.nodeType(FlatLayoutNode.NodeType.TEXT_FRAME);
        node.positioning(FlatLayoutNode.PositioningMode.INLINE);
        node.pageId(page.pageId());
        node.sourceId(inObj.sourceId());
        node.parentComponentId(parentComp.componentId());
        node.insertionIndex(insertionIdx);

        // Size
        node.width(inObj.width());
        node.height(inObj.height());

        // Frame style
        node.fillColor(inObj.fillColor());
        node.fillTint(inObj.fillTint());
        node.strokeColor(inObj.strokeColor());
        node.strokeWeight(inObj.strokeWeight());
        node.strokeTint(inObj.strokeTint());
        node.cornerRadius(inObj.cornerRadius());

        // Text margins → insets
        node.insetTop(inObj.textMarginTop());
        node.insetLeft(inObj.textMarginLeft());
        node.insetBottom(inObj.textMarginBottom());
        node.insetRight(inObj.textMarginRight());

        // Also keep textMargin fields for backward compat
        node.textMarginTop(inObj.textMarginTop());
        node.textMarginLeft(inObj.textMarginLeft());
        node.textMarginBottom(inObj.textMarginBottom());
        node.textMarginRight(inObj.textMarginRight());

        // Vertical justification
        node.verticalJustification(inObj.verticalJustification());

        // Overlay properties (standalone INLINE text frames can have isOverlay=true)
        node.isOverlay(inObj.isOverlay());
        node.overlayOffsetX(inObj.overlayX());
        node.overlayOffsetY(inObj.overlayY());
        node.overlayParentWidth(inObj.overlayParentWidth());
        node.overlayParentHeight(inObj.overlayParentHeight());
        node.overlayCenterDeltaX(inObj.overlayCenterDeltaX());
        node.overlayCenterDeltaY(inObj.overlayCenterDeltaY());

        // Anchoring / text wrap
        node.anchoredPosition(inObj.anchoredPosition());
        node.textWrapMode(inObj.textWrapMode());
        node.textWrapSide(inObj.textWrapSide());
        node.textWrapTop(inObj.textWrapTop());
        node.textWrapLeft(inObj.textWrapLeft());
        node.textWrapBottom(inObj.textWrapBottom());
        node.textWrapRight(inObj.textWrapRight());

        // Resolved coordinates
        node.resolvedPageX(inObj.resolvedPageX());
        node.resolvedPageY(inObj.resolvedPageY());
        node.resolvedWidth(inObj.resolvedWidth());
        node.resolvedHeight(inObj.resolvedHeight());

        flat.layoutNodes().add(node);
        page.layoutNodeIds().add(node.nodeId());

        // Add LAYOUT_REF to parent component
        FlatInlineItem ref = FlatInlineItem.layoutRef(node.nodeId());
        parentComp.addItem(ref);

        // Recursively convert paragraphs inside the inline text frame
        convertParagraphs(inObj.paragraphs(), node, page, flat, idGen);

        // Convert inline tables (if any) — set parentComponentId to first component
        // so FlatToASTConverter can find them and restore inlineTables
        if (inObj.inlineTables() != null) {
            // Use first component ID if available, otherwise use node ID as fallback
            // (RENDERED_GROUP may have inlineTables but no paragraphs → no components)
            String parentCompId = node.componentIds().isEmpty() ? node.nodeId() : node.componentIds().get(0);
            for (ASTTable inlineTable : inObj.inlineTables()) {
                FlatLayoutNode tableNode = new FlatLayoutNode();
                tableNode.nodeId(idGen.nextNodeId());
                tableNode.nodeType(FlatLayoutNode.NodeType.TABLE);
                tableNode.positioning(FlatLayoutNode.PositioningMode.INLINE);
                tableNode.pageId(page.pageId());
                tableNode.sourceId(inlineTable.sourceId());
                tableNode.parentComponentId(parentCompId);

                // Geometry
                tableNode.x(inlineTable.x());
                tableNode.y(inlineTable.y());
                tableNode.width(inlineTable.width());
                tableNode.height(inlineTable.height());
                tableNode.zOrder(inlineTable.zOrder());

                // Table structure
                tableNode.rowCount(inlineTable.rowCount());
                tableNode.colCount(inlineTable.colCount());
                if (inlineTable.columnWidths() != null) {
                    tableNode.tableColumnWidths(new ArrayList<Long>(inlineTable.columnWidths()));
                }
                tableNode.appliedTableStyle(inlineTable.appliedTableStyle());
                tableNode.borderColor(inlineTable.borderColor());
                tableNode.borderWidth(inlineTable.borderWidth());

                // Convert rows
                for (ASTTableRow astRow : inlineTable.rows()) {
                    FlatTableRow flatRow = new FlatTableRow();
                    flatRow.rowIndex(astRow.rowIndex());
                    flatRow.rowHeight(astRow.rowHeight());
                    flatRow.autoGrow(astRow.autoGrow());

                    for (ASTTableCell astCell : astRow.cells()) {
                        FlatTableCell flatCell = convertTableCell(astCell, tableNode, page, flat, idGen);
                        flatRow.addCell(flatCell);
                    }
                    tableNode.addTableRow(flatRow);
                }

                flat.layoutNodes().add(tableNode);
                page.layoutNodeIds().add(tableNode.nodeId());
            }
        }
    }

    // ---- SPACER_RECT ----

    private static void convertInlineSpacer(ASTInlineObject inObj, FlatComponent parentComp,
                                            int insertionIdx, FlatPage page,
                                            FlatDocument flat, FlatIdGenerator idGen) {
        FlatLayoutNode node = new FlatLayoutNode();
        node.nodeId(idGen.nextNodeId());
        node.nodeType(FlatLayoutNode.NodeType.SPACER);
        node.positioning(FlatLayoutNode.PositioningMode.INLINE);
        node.pageId(page.pageId());
        node.sourceId(inObj.sourceId());
        node.parentComponentId(parentComp.componentId());
        node.insertionIndex(insertionIdx);

        // Size
        node.width(inObj.width());
        node.height(inObj.height());

        // Style
        node.fillColor(inObj.fillColor());
        node.fillTint(inObj.fillTint());
        node.strokeColor(inObj.strokeColor());
        node.strokeWeight(inObj.strokeWeight());
        node.strokeTint(inObj.strokeTint());
        node.cornerRadius(inObj.cornerRadius());

        // Anchoring / text wrap
        node.anchoredPosition(inObj.anchoredPosition());
        node.textWrapMode(inObj.textWrapMode());
        node.textWrapSide(inObj.textWrapSide());
        node.textWrapTop(inObj.textWrapTop());
        node.textWrapLeft(inObj.textWrapLeft());
        node.textWrapBottom(inObj.textWrapBottom());
        node.textWrapRight(inObj.textWrapRight());

        // Resolved coordinates
        node.resolvedPageX(inObj.resolvedPageX());
        node.resolvedPageY(inObj.resolvedPageY());
        node.resolvedWidth(inObj.resolvedWidth());
        node.resolvedHeight(inObj.resolvedHeight());

        flat.layoutNodes().add(node);
        page.layoutNodeIds().add(node.nodeId());

        // Add LAYOUT_REF to parent component
        FlatInlineItem ref = FlatInlineItem.layoutRef(node.nodeId());
        parentComp.addItem(ref);
    }

    // =========================================================================
    // Overlay frame conversion
    // =========================================================================

    /**
     * IMAGE 그룹 내 오버레이 텍스트프레임을 FlatLayoutNode(TEXT_FRAME, OVERLAY)로 변환.
     */
    private static void convertOverlayFrame(ASTInlineObject overlayObj,
                                            FlatLayoutNode imageNode,
                                            FlatPage page, FlatDocument flat,
                                            FlatIdGenerator idGen) {
        FlatLayoutNode node = new FlatLayoutNode();
        node.nodeId(idGen.nextNodeId());
        node.nodeType(FlatLayoutNode.NodeType.TEXT_FRAME);
        node.positioning(FlatLayoutNode.PositioningMode.OVERLAY);
        node.pageId(page.pageId());
        node.sourceId(overlayObj.sourceId());

        // Overlay parent reference
        node.overlayParentId(imageNode.nodeId());

        // Overlay position
        node.overlayOffsetX(overlayObj.overlayX());
        node.overlayOffsetY(overlayObj.overlayY());

        // Overlay centering
        node.overlayCenterDeltaX(overlayObj.overlayCenterDeltaX());
        node.overlayCenterDeltaY(overlayObj.overlayCenterDeltaY());
        node.overlayParentWidth(overlayObj.overlayParentWidth());
        node.overlayParentHeight(overlayObj.overlayParentHeight());

        // Size
        node.width(overlayObj.width());
        node.height(overlayObj.height());

        // Frame style
        node.fillColor(overlayObj.fillColor());
        node.fillTint(overlayObj.fillTint());
        node.strokeColor(overlayObj.strokeColor());
        node.strokeWeight(overlayObj.strokeWeight());
        node.strokeTint(overlayObj.strokeTint());
        node.cornerRadius(overlayObj.cornerRadius());

        // Text margins → insets
        node.insetTop(overlayObj.textMarginTop());
        node.insetLeft(overlayObj.textMarginLeft());
        node.insetBottom(overlayObj.textMarginBottom());
        node.insetRight(overlayObj.textMarginRight());

        // Also keep textMargin fields
        node.textMarginTop(overlayObj.textMarginTop());
        node.textMarginLeft(overlayObj.textMarginLeft());
        node.textMarginBottom(overlayObj.textMarginBottom());
        node.textMarginRight(overlayObj.textMarginRight());

        // Vertical justification
        node.verticalJustification(overlayObj.verticalJustification());

        // Resolved coordinates
        node.resolvedPageX(overlayObj.resolvedPageX());
        node.resolvedPageY(overlayObj.resolvedPageY());
        node.resolvedWidth(overlayObj.resolvedWidth());
        node.resolvedHeight(overlayObj.resolvedHeight());

        flat.layoutNodes().add(node);
        page.layoutNodeIds().add(node.nodeId());

        // Convert paragraphs inside the overlay frame
        convertParagraphs(overlayObj.paragraphs(), node, page, flat, idGen);
    }

    // =========================================================================
    // Inline table (within paragraph)
    // =========================================================================

    /**
     * ASTParagraph.inlineTable → FlatLayoutNode(TABLE, INLINE).
     * parentComponentId를 설정하여 어떤 컴포넌트에 속하는지 추적한다.
     */
    private static FlatLayoutNode convertInlineTable(ASTTable table, FlatComponent parentComp,
                                                     FlatPage page, FlatDocument flat,
                                                     FlatIdGenerator idGen) {
        FlatLayoutNode node = new FlatLayoutNode();
        node.nodeId(idGen.nextNodeId());
        node.nodeType(FlatLayoutNode.NodeType.TABLE);
        node.positioning(FlatLayoutNode.PositioningMode.INLINE);
        node.pageId(page.pageId());
        node.sourceId(table.sourceId());
        node.parentComponentId(parentComp.componentId());

        // Geometry
        node.x(table.x());
        node.y(table.y());
        node.width(table.width());
        node.height(table.height());
        node.zOrder(table.zOrder());

        // Table structure
        node.rowCount(table.rowCount());
        node.colCount(table.colCount());

        // Column widths
        if (table.columnWidths() != null) {
            node.tableColumnWidths(new ArrayList<Long>(table.columnWidths()));
        }

        // Table style
        node.appliedTableStyle(table.appliedTableStyle());
        node.borderColor(table.borderColor());
        node.borderWidth(table.borderWidth());

        // Convert rows
        for (ASTTableRow astRow : table.rows()) {
            FlatTableRow flatRow = new FlatTableRow();
            flatRow.rowIndex(astRow.rowIndex());
            flatRow.rowHeight(astRow.rowHeight());
            flatRow.autoGrow(astRow.autoGrow());

            for (ASTTableCell astCell : astRow.cells()) {
                FlatTableCell flatCell = convertTableCell(astCell, node, page, flat, idGen);
                flatRow.addCell(flatCell);
            }
            node.addTableRow(flatRow);
        }

        flat.layoutNodes().add(node);
        page.layoutNodeIds().add(node.nodeId());
        return node;
    }

    // =========================================================================
    // Z-order sorting
    // =========================================================================

    /**
     * 페이지의 layoutNodeIds를 zOrder 기준으로 정렬하여 zOrderedNodeIds에 저장한다.
     * ABSOLUTE 배치 노드만 정렬 대상.
     */
    private static void sortByZOrder(FlatPage page, FlatDocument flat) {
        // Build a map of nodeId → FlatLayoutNode for quick lookup
        Map<String, FlatLayoutNode> nodeMap = new HashMap<String, FlatLayoutNode>();
        for (FlatLayoutNode node : flat.layoutNodes()) {
            if (page.pageId().equals(node.pageId())) {
                nodeMap.put(node.nodeId(), node);
            }
        }

        // Collect ABSOLUTE nodes and sort by zOrder
        List<String> absoluteNodeIds = new ArrayList<String>();
        for (String nodeId : page.layoutNodeIds()) {
            FlatLayoutNode node = nodeMap.get(nodeId);
            if (node != null && node.positioning() == FlatLayoutNode.PositioningMode.ABSOLUTE) {
                absoluteNodeIds.add(nodeId);
            }
        }

        Collections.sort(absoluteNodeIds, new Comparator<String>() {
            @Override
            public int compare(String id1, String id2) {
                FlatLayoutNode n1 = nodeMap.get(id1);
                FlatLayoutNode n2 = nodeMap.get(id2);
                int z1 = n1 != null ? n1.zOrder() : 0;
                int z2 = n2 != null ? n2.zOrder() : 0;
                return Integer.compare(z1, z2);
            }
        });

        page.zOrderedNodeIds(absoluteNodeIds);
    }

    // =========================================================================
    // Semantic layer classification
    // =========================================================================

    /**
     * 페이지의 ABSOLUTE 노드를 의미적 레이어(BACKGROUND/CONTENT/FOREGROUND)로 분류한다.
     * sortByZOrder() 이후 호출되어야 한다.
     */
    private static void classifySemanticLayers(FlatPage page, FlatDocument flat) {
        // 컴포넌트 조회를 위한 임시 인덱스
        Map<String, FlatLayoutNode> nodeMap = new HashMap<String, FlatLayoutNode>();
        Map<String, List<FlatComponent>> compsByNode = new HashMap<String, List<FlatComponent>>();
        for (FlatLayoutNode node : flat.layoutNodes()) {
            nodeMap.put(node.nodeId(), node);
        }
        for (FlatComponent comp : flat.components()) {
            if (comp.parentNodeId() != null) {
                List<FlatComponent> list = compsByNode.get(comp.parentNodeId());
                if (list == null) {
                    list = new ArrayList<FlatComponent>();
                    compsByNode.put(comp.parentNodeId(), list);
                }
                list.add(comp);
            }
        }

        List<String> bgIds = new ArrayList<String>();
        List<String> contentIds = new ArrayList<String>();
        List<String> fgIds = new ArrayList<String>();

        for (String nodeId : page.zOrderedNodeIds()) {
            FlatLayoutNode node = nodeMap.get(nodeId);
            if (node == null) continue;

            FlatLayoutNode.SemanticLayer layer = classifyNode(node, compsByNode);
            node.semanticLayer(layer);

            switch (layer) {
                case BACKGROUND: bgIds.add(nodeId); break;
                case CONTENT:    contentIds.add(nodeId); break;
                case FOREGROUND: fgIds.add(nodeId); break;
            }
        }

        // 레이어 내 상대 순서 부여
        assignRelativeOrder(bgIds, nodeMap);
        assignRelativeOrder(contentIds, nodeMap);
        assignRelativeOrder(fgIds, nodeMap);

        page.backgroundNodeIds(bgIds);
        page.contentNodeIds(contentIds);
        page.foregroundNodeIds(fgIds);

        // OVERLAY, INLINE 노드는 FOREGROUND로 분류
        for (FlatLayoutNode node : flat.layoutNodes()) {
            if (!page.pageId().equals(node.pageId())) continue;
            if (node.positioning() == FlatLayoutNode.PositioningMode.OVERLAY) {
                node.semanticLayer(FlatLayoutNode.SemanticLayer.FOREGROUND);
            } else if (node.positioning() == FlatLayoutNode.PositioningMode.INLINE) {
                // 인라인은 부모 컨텐츠의 일부 → CONTENT
                node.semanticLayer(FlatLayoutNode.SemanticLayer.CONTENT);
            }
        }
    }

    /**
     * 개별 노드의 의미적 레이어를 판별한다 (ABSOLUTE 노드 전용).
     *
     * Layer A (BACKGROUND):
     *   - 배경 전용 TEXT_FRAME (fillColor + 텍스트 없음)
     *   - 배경 FIGURE (비그룹 또는 대형 그룹 내부)
     *
     * Layer C (FOREGROUND):
     *   - isOverlay가 true인 노드
     *
     * Layer B (CONTENT): 나머지 전부
     */
    private static FlatLayoutNode.SemanticLayer classifyNode(
            FlatLayoutNode node,
            Map<String, List<FlatComponent>> compsByNode) {

        // FOREGROUND: 오버레이 속성
        if (node.isOverlay()) {
            return FlatLayoutNode.SemanticLayer.FOREGROUND;
        }

        FlatLayoutNode.NodeType type = node.nodeType();

        // FIGURE 분류
        if (type == FlatLayoutNode.NodeType.FIGURE) {
            // 그룹 외부 FIGURE → 배경 이미지
            if (!node.fromGroup()) {
                return FlatLayoutNode.SemanticLayer.BACKGROUND;
            }
            // 그룹 내부 FIGURE → 콘텐츠 (z-order로 스태킹 제어)
            return FlatLayoutNode.SemanticLayer.CONTENT;
        }

        // TEXT_FRAME 분류: 배경 전용 판별
        if (type == FlatLayoutNode.NodeType.TEXT_FRAME) {
            if (isBackgroundOnlyNode(node, compsByNode)) {
                return FlatLayoutNode.SemanticLayer.BACKGROUND;
            }
            return FlatLayoutNode.SemanticLayer.CONTENT;
        }

        // SPACER: 빈 공간 확보용 → 배경
        if (type == FlatLayoutNode.NodeType.SPACER) {
            return FlatLayoutNode.SemanticLayer.BACKGROUND;
        }

        // TABLE, 기타 → CONTENT
        return FlatLayoutNode.SemanticLayer.CONTENT;
    }

    /**
     * 배경 전용 TEXT_FRAME 판별 (Gateway.isBackgroundOnly와 동일 로직).
     * fillColor가 있으면서 실질 텍스트/인라인 객체가 없는 노드.
     */
    private static boolean isBackgroundOnlyNode(
            FlatLayoutNode node,
            Map<String, List<FlatComponent>> compsByNode) {
        if (node.fillColor() == null || node.fillColor().isEmpty()) return false;

        List<FlatComponent> comps = compsByNode.get(node.nodeId());
        if (comps == null) return true; // 컴포넌트 없으면 텍스트 없음

        for (FlatComponent comp : comps) {
            for (FlatInlineItem item : comp.items()) {
                if (item.itemType() == FlatInlineItem.ItemType.TEXT_RUN) {
                    String text = item.text();
                    if (text != null && !text.trim().isEmpty()) return false;
                } else if (item.itemType() == FlatInlineItem.ItemType.LAYOUT_REF) {
                    return false;
                } else if (item.itemType() == FlatInlineItem.ItemType.EQUATION) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 레이어 내 상대 순서 부여 (0부터). */
    private static void assignRelativeOrder(List<String> nodeIds,
                                            Map<String, FlatLayoutNode> nodeMap) {
        for (int i = 0; i < nodeIds.size(); i++) {
            FlatLayoutNode node = nodeMap.get(nodeIds.get(i));
            if (node != null) {
                node.layerRelativeOrder(i);
            }
        }
    }
}
