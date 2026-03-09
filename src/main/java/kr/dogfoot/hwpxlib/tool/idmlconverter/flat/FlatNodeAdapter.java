package kr.dogfoot.hwpxlib.tool.idmlconverter.flat;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;

import java.util.*;

/**
 * FlatLayoutNode → AST 타입 per-node 변환 어댑터.
 * FlatDocumentGateway를 통해 참조를 해석하며,
 * FlatToHwpxConverter에서 기존 빌더에 AST 타입을 전달하는 데 사용.
 */
public class FlatNodeAdapter {

    private final FlatDocumentGateway gateway;

    public FlatNodeAdapter(FlatDocumentGateway gateway) {
        this.gateway = gateway;
    }

    // =========================================================================
    // TEXT_FRAME -> ASTTextFrameBlock
    // =========================================================================

    public ASTTextFrameBlock toTextFrameBlock(FlatLayoutNode node) {
        ASTTextFrameBlock tfb = new ASTTextFrameBlock();
        tfb.sourceId(node.sourceId());

        // Geometry
        tfb.x(node.x());
        tfb.y(node.y());
        tfb.width(node.width());
        tfb.height(node.height());
        tfb.zOrder(node.zOrder());

        // Column layout
        tfb.columnCount(node.columnCount());
        tfb.columnGutter(node.columnGutter());
        tfb.columnWidths(node.columnWidths());

        // Text direction
        tfb.verticalText(node.verticalText());
        tfb.verticalJustification(node.verticalJustification());

        // Insets
        tfb.insetTop(node.insetTop());
        tfb.insetLeft(node.insetLeft());
        tfb.insetBottom(node.insetBottom());
        tfb.insetRight(node.insetRight());

        // Frame style
        tfb.fillColor(node.fillColor());
        tfb.strokeColor(node.strokeColor());
        tfb.strokeWeight(node.strokeWeight());
        tfb.strokeType(node.strokeType());
        tfb.fillTint(node.fillTint());
        tfb.strokeTint(node.strokeTint());
        tfb.cornerRadius(node.cornerRadius());
        tfb.fromGroup(node.fromGroup());
        tfb.storyId(node.storyId());
        tfb.distributed(node.distributed());
        tfb.rotationAngle(node.rotationAngle());
        tfb.narrowedWidth(node.narrowedWidth());

        // Wrapper fill
        tfb.wrapperFillColor(node.wrapperFillColor());
        tfb.wrapperFillTint(node.wrapperFillTint());

        // Drop shadow
        tfb.dropShadow(node.dropShadow());

        // Polygon path
        tfb.pathPoints(node.pathPointsX(), node.pathPointsY());

        // Convert components -> paragraphs
        List<ASTParagraph> paragraphs = toParagraphs(node.componentIds());
        for (ASTParagraph p : paragraphs) {
            tfb.addParagraph(p);
        }

        return tfb;
    }

    // =========================================================================
    // FIGURE -> ASTFigure
    // =========================================================================

    public ASTFigure toFigure(FlatLayoutNode node) {
        ASTFigure fig = new ASTFigure();
        fig.sourceId(node.sourceId());

        // Figure kind
        if (node.figureKind() != null) {
            try {
                fig.kind(ASTFigure.FigureKind.valueOf(node.figureKind()));
            } catch (IllegalArgumentException e) {
                // Unknown kind, leave null
            }
        }

        // Geometry
        fig.x(node.x());
        fig.y(node.y());
        fig.width(node.width());
        fig.height(node.height());
        fig.zOrder(node.zOrder());
        fig.rotationAngle(node.rotationAngle());
        fig.flipHorizontal(node.flipHorizontal());
        fig.flipVertical(node.flipVertical());

        // Image data
        fig.imageFormat(node.imageFormat());
        fig.imageData(node.imageData());
        fig.imagePath(node.imagePath());
        fig.pixelWidth(node.pixelWidth());
        fig.pixelHeight(node.pixelHeight());

        // Crop
        fig.cropLeftFraction(node.cropLeftFraction());
        fig.cropTopFraction(node.cropTopFraction());
        fig.cropRightFraction(node.cropRightFraction());
        fig.cropBottomFraction(node.cropBottomFraction());

        // Text wrap
        fig.textWrapMode(node.textWrapMode());
        fig.textWrapSide(node.textWrapSide());
        fig.textWrapTop(node.textWrapTop());
        fig.textWrapLeft(node.textWrapLeft());
        fig.textWrapBottom(node.textWrapBottom());
        fig.textWrapRight(node.textWrapRight());

        // Bundle path
        fig.bundlePath(node.bundlePath());

        // From group
        fig.fromGroup(node.fromGroup());

        return fig;
    }

    // =========================================================================
    // TABLE -> ASTTable
    // =========================================================================

    public ASTTable toTable(FlatLayoutNode node) {
        ASTTable table = new ASTTable();
        table.sourceId(node.sourceId());

        // Geometry
        table.x(node.x());
        table.y(node.y());
        table.width(node.width());
        table.height(node.height());
        table.zOrder(node.zOrder());

        // Table structure
        table.rowCount(node.rowCount());
        table.colCount(node.colCount());

        // Column widths
        if (node.tableColumnWidths() != null) {
            for (Long w : node.tableColumnWidths()) {
                table.addColumnWidth(w);
            }
        }

        // Table style
        table.appliedTableStyle(node.appliedTableStyle());
        table.borderColor(node.borderColor());
        table.borderWidth(node.borderWidth());

        // Convert rows
        if (node.tableRows() != null) {
            for (FlatTableRow flatRow : node.tableRows()) {
                ASTTableRow astRow = new ASTTableRow();
                astRow.rowIndex(flatRow.rowIndex());
                astRow.rowHeight(flatRow.rowHeight());
                astRow.autoGrow(flatRow.autoGrow());

                for (FlatTableCell flatCell : flatRow.cells()) {
                    ASTTableCell astCell = toTableCell(flatCell);
                    astRow.addCell(astCell);
                }

                table.addRow(astRow);
            }
        }

        return table;
    }

    private ASTTableCell toTableCell(FlatTableCell flatCell) {
        ASTTableCell astCell = new ASTTableCell();
        astCell.rowIndex(flatCell.rowIndex());
        astCell.columnIndex(flatCell.columnIndex());
        astCell.rowSpan(flatCell.rowSpan());
        astCell.columnSpan(flatCell.columnSpan());
        astCell.width(flatCell.width());
        astCell.height(flatCell.height());

        // Cell style
        astCell.fillColor(flatCell.fillColor());

        // Cell borders
        astCell.topBorder(convertCellBorder(flatCell.topBorder()));
        astCell.bottomBorder(convertCellBorder(flatCell.bottomBorder()));
        astCell.leftBorder(convertCellBorder(flatCell.leftBorder()));
        astCell.rightBorder(convertCellBorder(flatCell.rightBorder()));
        astCell.topLeftDiagonalLine(flatCell.topLeftDiagonalLine());
        astCell.topRightDiagonalLine(flatCell.topRightDiagonalLine());
        astCell.diagonalBorder(convertCellBorder(flatCell.diagonalBorder()));

        // Cell margins
        astCell.marginTop(flatCell.marginTop());
        astCell.marginBottom(flatCell.marginBottom());
        astCell.marginLeft(flatCell.marginLeft());
        astCell.marginRight(flatCell.marginRight());
        astCell.verticalAlign(flatCell.verticalAlign());

        // Convert cell paragraphs from componentIds
        List<ASTParagraph> paragraphs = toParagraphs(flatCell.componentIds());
        for (ASTParagraph p : paragraphs) {
            astCell.addParagraph(p);
        }

        return astCell;
    }

    static ASTTableCell.CellBorder convertCellBorder(FlatTableCell.CellBorder flatBorder) {
        if (flatBorder == null) {
            return null;
        }
        ASTTableCell.CellBorder astBorder = new ASTTableCell.CellBorder();
        astBorder.color(flatBorder.color());
        astBorder.weight(flatBorder.weight());
        astBorder.strokeType(flatBorder.strokeType());
        astBorder.tint(flatBorder.tint());
        return astBorder;
    }

    // =========================================================================
    // Component -> ASTParagraph conversion
    // =========================================================================

    public List<ASTParagraph> toParagraphs(List<String> componentIds) {
        List<ASTParagraph> result = new ArrayList<ASTParagraph>();
        if (componentIds == null) {
            return result;
        }
        for (String cid : componentIds) {
            FlatComponent comp = gateway.component(cid);
            if (comp != null) {
                result.add(toParagraph(comp));
            }
        }
        return result;
    }

    private ASTParagraph toParagraph(FlatComponent comp) {
        ASTParagraph para = new ASTParagraph();

        // Paragraph style reference
        para.paragraphStyleRef(comp.paragraphStyleRef());

        // Paragraph attributes (local overrides)
        para.alignment(comp.alignment());
        para.firstLineIndent(comp.firstLineIndent());
        para.leftMargin(comp.leftMargin());
        para.rightMargin(comp.rightMargin());
        para.spaceBefore(comp.spaceBefore());
        para.spaceAfter(comp.spaceAfter());
        para.lineSpacing(comp.lineSpacing());
        para.lineSpacingType(comp.lineSpacingType());
        para.letterSpacing(comp.letterSpacing());

        // Paragraph shading
        para.shadingOn(comp.shadingOn());
        para.shadingColor(comp.shadingColor());
        para.shadingTint(comp.shadingTint());
        para.shadingLeftOffset(comp.shadingLeftOffset());
        para.shadingRightOffset(comp.shadingRightOffset());
        para.shadingTopOffset(comp.shadingTopOffset());
        para.shadingBottomOffset(comp.shadingBottomOffset());

        // Tab stops (share references)
        if (comp.hasTabStops()) {
            for (ASTTabStop ts : comp.tabStops()) {
                para.addTabStop(ts);
            }
        }

        // Frame Y offset
        para.yOffsetInFrame(comp.yOffsetInFrame());

        // Column break
        para.columnBreakAfter(comp.columnBreakAfter());

        // Pending underline color
        para.pendingUnderlineColor(comp.pendingUnderlineColor());

        // Convert inline items
        // In ASTToFlatConverter, inlineTable was prepended as the first LAYOUT_REF.
        // So check if the first item is a LAYOUT_REF to TABLE(INLINE) -> set as inlineTable.
        List<FlatInlineItem> items = comp.items();
        int startIdx = 0;

        if (!items.isEmpty()) {
            FlatInlineItem firstItem = items.get(0);
            if (firstItem.itemType() == FlatInlineItem.ItemType.LAYOUT_REF) {
                FlatLayoutNode refNode = gateway.layoutNode(firstItem.layoutNodeId());
                if (refNode != null
                        && refNode.nodeType() == FlatLayoutNode.NodeType.TABLE
                        && refNode.positioning() == FlatLayoutNode.PositioningMode.INLINE) {
                    para.inlineTable(toTable(refNode));
                    startIdx = 1;
                }
            }
        }

        for (int i = startIdx; i < items.size(); i++) {
            FlatInlineItem flatItem = items.get(i);
            ASTInlineItem astItem = convertInlineItem(flatItem);
            if (astItem != null) {
                para.addItem(astItem);
            }
        }

        return para;
    }

    // =========================================================================
    // Inline item conversion
    // =========================================================================

    private ASTInlineItem convertInlineItem(FlatInlineItem flatItem) {
        switch (flatItem.itemType()) {
            case TEXT_RUN:
                return convertTextRun(flatItem);
            case BREAK:
                return convertBreak(flatItem);
            case EQUATION:
                return convertEquation(flatItem);
            case LAYOUT_REF:
                return convertLayoutRef(flatItem);
            default:
                return null;
        }
    }

    private ASTTextRun convertTextRun(FlatInlineItem item) {
        ASTTextRun run = new ASTTextRun();
        run.characterStyleRef(item.characterStyleRef());
        run.text(item.text());
        run.fontFamily(item.fontFamily());
        run.fontStyle(item.fontStyle());
        run.fontSizeHwpunits(item.fontSizeHwpunits());
        run.textColor(item.textColor());
        run.letterSpacing(item.letterSpacing());
        run.subscript(item.subscript());
        run.superscript(item.superscript());
        run.grepMathFont(item.grepMathFont());
        run.underline(item.underline());
        run.underlineColor(item.underlineColor());
        run.underlineShape(item.underlineShape());
        run.strikeThrough(item.strikeThrough());
        run.horizontalScale(item.horizontalScale());
        run.verticalScale(item.verticalScale());
        run.baselineShift(item.baselineShift());
        return run;
    }

    private ASTBreak convertBreak(FlatInlineItem item) {
        ASTBreak brk = new ASTBreak();
        if (item.breakType() != null) {
            try {
                brk.breakType(ASTBreak.BreakType.valueOf(item.breakType()));
            } catch (IllegalArgumentException e) {
                // Unknown break type, leave null
            }
        }
        return brk;
    }

    private ASTEquation convertEquation(FlatInlineItem item) {
        ASTEquation eq = new ASTEquation(item.hwpScript(), item.equationSourceType());
        eq.textColor(item.equationTextColor());
        return eq;
    }

    // =========================================================================
    // LAYOUT_REF -> ASTInlineObject
    // =========================================================================

    private ASTInlineItem convertLayoutRef(FlatInlineItem item) {
        FlatLayoutNode node = gateway.layoutNode(item.layoutNodeId());
        if (node == null) {
            return null;
        }

        switch (node.nodeType()) {
            case FIGURE:
                return toInlineFigure(node);
            case TEXT_FRAME:
                return toInlineTextFrame(node);
            case TABLE:
                return null;
            case SPACER:
                return toInlineSpacer(node);
            default:
                return null;
        }
    }

    // ---- FIGURE (INLINE) -> ASTInlineObject (IMAGE or RENDERED_GROUP) ----

    private ASTInlineObject toInlineFigure(FlatLayoutNode node) {
        ASTInlineObject obj = new ASTInlineObject();

        if ("IMAGE".equals(node.figureKind())) {
            obj.kind(ASTInlineObject.ObjectKind.IMAGE);
        } else if ("RENDERED_GROUP".equals(node.figureKind())) {
            obj.kind(ASTInlineObject.ObjectKind.RENDERED_GROUP);
        }

        obj.sourceId(node.sourceId());

        // Size
        obj.width(node.width());
        obj.height(node.height());

        // Image data
        obj.imageFormat(node.imageFormat());
        obj.imageData(node.imageData());
        obj.imagePath(node.imagePath());
        obj.pixelWidth(node.pixelWidth());
        obj.pixelHeight(node.pixelHeight());
        obj.bundlePath(node.bundlePath());

        // Container size
        obj.containerWidth(node.containerWidth());
        obj.containerHeight(node.containerHeight());
        obj.imageOffsetX(node.imageOffsetX());
        obj.imageOffsetY(node.imageOffsetY());

        // Frame style
        obj.fillColor(node.fillColor());
        obj.fillTint(node.fillTint());
        obj.strokeColor(node.strokeColor());
        obj.strokeWeight(node.strokeWeight());
        obj.strokeTint(node.strokeTint());
        obj.cornerRadius(node.cornerRadius());

        // Anchoring / text wrap
        obj.anchoredPosition(node.anchoredPosition());
        obj.textWrapMode(node.textWrapMode());
        obj.textWrapSide(node.textWrapSide());
        obj.textWrapTop(node.textWrapTop());
        obj.textWrapLeft(node.textWrapLeft());
        obj.textWrapBottom(node.textWrapBottom());
        obj.textWrapRight(node.textWrapRight());

        // Resolved coordinates
        obj.resolvedPageX(node.resolvedPageX());
        obj.resolvedPageY(node.resolvedPageY());
        obj.resolvedWidth(node.resolvedWidth());
        obj.resolvedHeight(node.resolvedHeight());

        // Overlay frames (IMAGE kind only)
        if (obj.kind() == ASTInlineObject.ObjectKind.IMAGE) {
            List<FlatLayoutNode> overlays = gateway.overlayChildren(node.nodeId());
            for (FlatLayoutNode overlayNode : overlays) {
                ASTInlineObject overlayObj = toOverlayFrame(overlayNode);
                obj.addOverlayFrame(overlayObj);
            }
        }

        return obj;
    }

    // ---- TEXT_FRAME (INLINE) -> ASTInlineObject (INLINE_TEXT_FRAME or RENDERED_GROUP) ----

    private ASTInlineObject toInlineTextFrame(FlatLayoutNode node) {
        ASTInlineObject obj = new ASTInlineObject();
        if ("RENDERED_GROUP".equals(node.figureKind())) {
            obj.kind(ASTInlineObject.ObjectKind.RENDERED_GROUP);
        } else {
            obj.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
        }
        obj.sourceId(node.sourceId());

        // Size
        obj.width(node.width());
        obj.height(node.height());

        // Frame style
        obj.fillColor(node.fillColor());
        obj.fillTint(node.fillTint());
        obj.strokeColor(node.strokeColor());
        obj.strokeWeight(node.strokeWeight());
        obj.strokeTint(node.strokeTint());
        obj.cornerRadius(node.cornerRadius());

        // Text margins
        obj.textMarginTop(node.textMarginTop());
        obj.textMarginLeft(node.textMarginLeft());
        obj.textMarginBottom(node.textMarginBottom());
        obj.textMarginRight(node.textMarginRight());

        // Vertical justification
        obj.verticalJustification(node.verticalJustification());

        // Overlay properties
        obj.isOverlay(node.isOverlay());
        obj.overlayX(node.overlayOffsetX());
        obj.overlayY(node.overlayOffsetY());
        obj.overlayParentWidth(node.overlayParentWidth());
        obj.overlayParentHeight(node.overlayParentHeight());
        obj.overlayCenterDeltaX(node.overlayCenterDeltaX());
        obj.overlayCenterDeltaY(node.overlayCenterDeltaY());

        // Anchoring / text wrap
        obj.anchoredPosition(node.anchoredPosition());
        obj.textWrapMode(node.textWrapMode());
        obj.textWrapSide(node.textWrapSide());
        obj.textWrapTop(node.textWrapTop());
        obj.textWrapLeft(node.textWrapLeft());
        obj.textWrapBottom(node.textWrapBottom());
        obj.textWrapRight(node.textWrapRight());

        // Resolved coordinates
        obj.resolvedPageX(node.resolvedPageX());
        obj.resolvedPageY(node.resolvedPageY());
        obj.resolvedWidth(node.resolvedWidth());
        obj.resolvedHeight(node.resolvedHeight());

        // Convert child components -> paragraphs
        List<ASTParagraph> paragraphs = toParagraphs(node.componentIds());
        obj.paragraphs(paragraphs);

        // Find inline TABLE children
        Set<String> myComponentIds = new HashSet<String>(node.componentIds());
        myComponentIds.add(node.nodeId());
        for (FlatLayoutNode candidate : gateway.allNodesOnPage(node.pageId())) {
            if (candidate.nodeType() == FlatLayoutNode.NodeType.TABLE
                    && candidate.positioning() == FlatLayoutNode.PositioningMode.INLINE
                    && candidate.parentComponentId() != null
                    && myComponentIds.contains(candidate.parentComponentId())) {
                obj.addInlineTable(toTable(candidate));
            }
        }

        return obj;
    }

    // ---- SPACER (INLINE) -> ASTInlineObject (SPACER_RECT) ----

    private ASTInlineObject toInlineSpacer(FlatLayoutNode node) {
        ASTInlineObject obj = new ASTInlineObject();
        obj.kind(ASTInlineObject.ObjectKind.SPACER_RECT);
        obj.sourceId(node.sourceId());

        // Size
        obj.width(node.width());
        obj.height(node.height());

        // Style
        obj.fillColor(node.fillColor());
        obj.fillTint(node.fillTint());
        obj.strokeColor(node.strokeColor());
        obj.strokeWeight(node.strokeWeight());
        obj.strokeTint(node.strokeTint());
        obj.cornerRadius(node.cornerRadius());

        // Anchoring / text wrap
        obj.anchoredPosition(node.anchoredPosition());
        obj.textWrapMode(node.textWrapMode());
        obj.textWrapSide(node.textWrapSide());
        obj.textWrapTop(node.textWrapTop());
        obj.textWrapLeft(node.textWrapLeft());
        obj.textWrapBottom(node.textWrapBottom());
        obj.textWrapRight(node.textWrapRight());

        // Resolved coordinates
        obj.resolvedPageX(node.resolvedPageX());
        obj.resolvedPageY(node.resolvedPageY());
        obj.resolvedWidth(node.resolvedWidth());
        obj.resolvedHeight(node.resolvedHeight());

        return obj;
    }

    // =========================================================================
    // Overlay frame conversion
    // =========================================================================

    private ASTInlineObject toOverlayFrame(FlatLayoutNode node) {
        ASTInlineObject obj = new ASTInlineObject();
        obj.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
        obj.sourceId(node.sourceId());
        obj.isOverlay(true);

        // Size
        obj.width(node.width());
        obj.height(node.height());

        // Frame style
        obj.fillColor(node.fillColor());
        obj.fillTint(node.fillTint());
        obj.strokeColor(node.strokeColor());
        obj.strokeWeight(node.strokeWeight());
        obj.strokeTint(node.strokeTint());
        obj.cornerRadius(node.cornerRadius());

        // Overlay position
        obj.overlayX(node.overlayOffsetX());
        obj.overlayY(node.overlayOffsetY());

        // Overlay centering
        obj.overlayCenterDeltaX(node.overlayCenterDeltaX());
        obj.overlayCenterDeltaY(node.overlayCenterDeltaY());
        obj.overlayParentWidth(node.overlayParentWidth());
        obj.overlayParentHeight(node.overlayParentHeight());

        // Text margins
        obj.textMarginTop(node.textMarginTop());
        obj.textMarginLeft(node.textMarginLeft());
        obj.textMarginBottom(node.textMarginBottom());
        obj.textMarginRight(node.textMarginRight());

        // Vertical justification
        obj.verticalJustification(node.verticalJustification());

        // Resolved coordinates
        obj.resolvedPageX(node.resolvedPageX());
        obj.resolvedPageY(node.resolvedPageY());
        obj.resolvedWidth(node.resolvedWidth());
        obj.resolvedHeight(node.resolvedHeight());

        // Convert child components -> paragraphs
        List<ASTParagraph> paragraphs = toParagraphs(node.componentIds());
        obj.paragraphs(paragraphs);

        return obj;
    }
}
