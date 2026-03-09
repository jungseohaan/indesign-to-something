package kr.dogfoot.hwpxlib.tool.idmlconverter.flat;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFontDef;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTPageBackground;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTStyleDef;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTabStop;

import java.util.List;
import java.util.Map;

/**
 * FlatDocument를 JSON 문자열로 직렬화.
 * 외부 라이브러리 없이 StringBuilder 기반 수동 JSON 생성.
 *
 * 규칙:
 * - byte[] 필드 (imageData, pngData) → 제외
 * - null 필드는 출력 생략
 * - 기본값(boolean false, int 0, long 0, double 0.0)은 생략
 *   단, 의미 있는 기본값(fillTint=100, strokeTint=100 등)은 해당 노드 타입에서만 조건부 출력
 * - JSON 키는 camelCase (Java 필드명 그대로)
 */
public class FlatSerializer {

    public static String toJson(FlatDocument doc) {
        StringBuilder sb = new StringBuilder();
        writeDocument(sb, doc);
        return sb.toString();
    }

    // ─── Document ────────────────────────────────────────────────

    private static void writeDocument(StringBuilder sb, FlatDocument doc) {
        sb.append('{');
        boolean first = true;

        first = writeStringField(sb, "sourceFile", doc.sourceFile(), first);
        first = writeStringField(sb, "sourceFormat", doc.sourceFormat(), first);

        // fonts
        if (doc.fonts() != null && !doc.fonts().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"fonts\":[");
            for (int i = 0; i < doc.fonts().size(); i++) {
                if (i > 0) sb.append(',');
                writeFontDef(sb, doc.fonts().get(i));
            }
            sb.append(']');
        }

        // paragraphStyles
        if (doc.paragraphStyles() != null && !doc.paragraphStyles().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"paragraphStyles\":[");
            for (int i = 0; i < doc.paragraphStyles().size(); i++) {
                if (i > 0) sb.append(',');
                writeStyleDef(sb, doc.paragraphStyles().get(i));
            }
            sb.append(']');
        }

        // characterStyles
        if (doc.characterStyles() != null && !doc.characterStyles().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"characterStyles\":[");
            for (int i = 0; i < doc.characterStyles().size(); i++) {
                if (i > 0) sb.append(',');
                writeStyleDef(sb, doc.characterStyles().get(i));
            }
            sb.append(']');
        }

        // colors
        if (doc.colors() != null && !doc.colors().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"colors\":{");
            boolean cfirst = true;
            for (Map.Entry<String, String> entry : doc.colors().entrySet()) {
                if (!cfirst) sb.append(',');
                cfirst = false;
                sb.append('"').append(escapeJson(entry.getKey())).append("\":\"")
                  .append(escapeJson(entry.getValue())).append('"');
            }
            sb.append('}');
        }

        // stories
        if (doc.stories() != null && !doc.stories().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"stories\":[");
            for (int i = 0; i < doc.stories().size(); i++) {
                if (i > 0) sb.append(',');
                writeStory(sb, doc.stories().get(i));
            }
            sb.append(']');
        }

        // pages
        if (doc.pages() != null && !doc.pages().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"pages\":[");
            for (int i = 0; i < doc.pages().size(); i++) {
                if (i > 0) sb.append(',');
                writePage(sb, doc.pages().get(i));
            }
            sb.append(']');
        }

        // layoutNodes
        if (doc.layoutNodes() != null && !doc.layoutNodes().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"layoutNodes\":[");
            for (int i = 0; i < doc.layoutNodes().size(); i++) {
                if (i > 0) sb.append(',');
                writeLayoutNode(sb, doc.layoutNodes().get(i));
            }
            sb.append(']');
        }

        // components
        if (doc.components() != null && !doc.components().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"components\":[");
            for (int i = 0; i < doc.components().size(); i++) {
                if (i > 0) sb.append(',');
                writeComponent(sb, doc.components().get(i));
            }
            sb.append(']');
        }

        // backgrounds
        if (doc.backgrounds() != null && !doc.backgrounds().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"backgrounds\":[");
            for (int i = 0; i < doc.backgrounds().size(); i++) {
                if (i > 0) sb.append(',');
                writeBackground(sb, doc.backgrounds().get(i));
            }
            sb.append(']');
        }

        sb.append('}');
    }

    // ─── Shared definitions ──────────────────────────────────────

    private static void writeFontDef(StringBuilder sb, ASTFontDef font) {
        sb.append('{');
        boolean first = true;
        first = writeStringField(sb, "fontId", font.fontId(), first);
        first = writeStringField(sb, "fontFamily", font.fontFamily(), first);
        first = writeStringField(sb, "fontType", font.fontType(), first);
        sb.append('}');
    }

    private static void writeStyleDef(StringBuilder sb, ASTStyleDef style) {
        sb.append('{');
        boolean first = true;
        first = writeStringField(sb, "styleId", style.styleId(), first);
        first = writeStringField(sb, "styleName", style.styleName(), first);
        first = writeStringField(sb, "parentStyleId", style.basedOnStyleRef(), first);
        sb.append('}');
    }

    private static void writeStory(StringBuilder sb, ASTStory story) {
        sb.append('{');
        boolean first = true;
        first = writeStringField(sb, "storyId", story.storyId(), first);

        if (story.linkedFrameIds() != null && !story.linkedFrameIds().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"frameIds\":[");
            for (int i = 0; i < story.linkedFrameIds().size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(escapeJson(story.linkedFrameIds().get(i))).append('"');
            }
            sb.append(']');
        }

        sb.append('}');
    }

    private static void writeBackground(StringBuilder sb, ASTPageBackground bg) {
        sb.append('{');
        boolean first = true;
        first = writeIntField(sb, "pageNumber", bg.pageNumber(), first);
        first = writeLongField(sb, "pageWidth", bg.pageWidth(), first);
        first = writeLongField(sb, "pageHeight", bg.pageHeight(), first);
        // pngData → skip byte[]
        if (bg.pixelWidth() != 0) {
            first = writeIntField(sb, "pixelWidth", bg.pixelWidth(), first);
        }
        if (bg.pixelHeight() != 0) {
            first = writeIntField(sb, "pixelHeight", bg.pixelHeight(), first);
        }
        first = writeStringField(sb, "bundlePath", bg.bundlePath(), first);
        sb.append('}');
    }

    // ─── Layer 0: Page ───────────────────────────────────────────

    private static void writePage(StringBuilder sb, FlatPage page) {
        sb.append('{');
        boolean first = true;
        first = writeStringField(sb, "pageId", page.pageId(), first);
        first = writeIntField(sb, "pageNumber", page.pageNumber(), first);
        first = writeLongField(sb, "pageWidth", page.pageWidth(), first);
        first = writeLongField(sb, "pageHeight", page.pageHeight(), first);
        if (page.marginTop() != 0) first = writeLongField(sb, "marginTop", page.marginTop(), first);
        if (page.marginBottom() != 0) first = writeLongField(sb, "marginBottom", page.marginBottom(), first);
        if (page.marginLeft() != 0) first = writeLongField(sb, "marginLeft", page.marginLeft(), first);
        if (page.marginRight() != 0) first = writeLongField(sb, "marginRight", page.marginRight(), first);
        if (page.columnCount() != 0) first = writeIntField(sb, "columnCount", page.columnCount(), first);
        if (page.columnGutter() != 0) first = writeLongField(sb, "columnGutter", page.columnGutter(), first);

        if (page.layoutNodeIds() != null && !page.layoutNodeIds().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"layoutNodeIds\":");
            writeStringList(sb, page.layoutNodeIds());
        }

        if (page.zOrderedNodeIds() != null && !page.zOrderedNodeIds().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"zOrderedNodeIds\":");
            writeStringList(sb, page.zOrderedNodeIds());
        }

        if (page.backgroundNodeIds() != null && !page.backgroundNodeIds().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"backgroundNodeIds\":");
            writeStringList(sb, page.backgroundNodeIds());
        }

        if (page.contentNodeIds() != null && !page.contentNodeIds().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"contentNodeIds\":");
            writeStringList(sb, page.contentNodeIds());
        }

        if (page.foregroundNodeIds() != null && !page.foregroundNodeIds().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"foregroundNodeIds\":");
            writeStringList(sb, page.foregroundNodeIds());
        }

        sb.append('}');
    }

    // ─── Layer 1: LayoutNode ─────────────────────────────────────

    private static void writeLayoutNode(StringBuilder sb, FlatLayoutNode node) {
        sb.append('{');
        boolean first = true;

        // --- Common fields ---
        first = writeStringField(sb, "nodeId", node.nodeId(), first);
        if (node.nodeType() != null) {
            first = writeStringField(sb, "nodeType", node.nodeType().name(), first);
        }
        if (node.positioning() != null) {
            first = writeStringField(sb, "positioning", node.positioning().name(), first);
        }
        first = writeStringField(sb, "pageId", node.pageId(), first);
        first = writeStringField(sb, "sourceId", node.sourceId(), first);
        if (node.zOrder() != 0) first = writeIntField(sb, "zOrder", node.zOrder(), first);
        if (node.semanticLayer() != null) {
            first = writeStringField(sb, "semanticLayer", node.semanticLayer().name(), first);
        }
        if (node.layerRelativeOrder() != 0) {
            first = writeIntField(sb, "layerRelativeOrder", node.layerRelativeOrder(), first);
        }
        if (node.x() != 0) first = writeLongField(sb, "x", node.x(), first);
        if (node.y() != 0) first = writeLongField(sb, "y", node.y(), first);
        if (node.width() != 0) first = writeLongField(sb, "width", node.width(), first);
        if (node.height() != 0) first = writeLongField(sb, "height", node.height(), first);

        // --- INLINE positioning ---
        first = writeStringField(sb, "parentComponentId", node.parentComponentId(), first);
        if (node.insertionIndex() != -1) {
            first = writeIntField(sb, "insertionIndex", node.insertionIndex(), first);
        }

        // --- OVERLAY positioning ---
        first = writeStringField(sb, "overlayParentId", node.overlayParentId(), first);
        if (node.overlayOffsetX() != 0) first = writeLongField(sb, "overlayOffsetX", node.overlayOffsetX(), first);
        if (node.overlayOffsetY() != 0) first = writeLongField(sb, "overlayOffsetY", node.overlayOffsetY(), first);

        // --- Component references ---
        if (node.componentIds() != null && !node.componentIds().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"componentIds\":");
            writeStringList(sb, node.componentIds());
        }

        // --- Type-specific fields ---
        FlatLayoutNode.NodeType type = node.nodeType();

        if (type == FlatLayoutNode.NodeType.TEXT_FRAME) {
            first = writeTextFrameFields(sb, node, first);
        } else if (type == FlatLayoutNode.NodeType.FIGURE) {
            first = writeFigureFields(sb, node, first);
        } else if (type == FlatLayoutNode.NodeType.TABLE) {
            first = writeTableFields(sb, node, first);
        } else if (type == FlatLayoutNode.NodeType.SPACER) {
            first = writeStringField(sb, "fillColor", node.fillColor(), first);
            first = writeStringField(sb, "strokeColor", node.strokeColor(), first);
            if (node.strokeWeight() != 0.0) {
                first = writeDoubleField(sb, "strokeWeight", node.strokeWeight(), first);
            }
            if (node.fillTint() != 100.0) {
                first = writeDoubleField(sb, "fillTint", node.fillTint(), first);
            }
            if (node.strokeTint() != 100.0) {
                first = writeDoubleField(sb, "strokeTint", node.strokeTint(), first);
            }
            if (node.cornerRadius() != 0.0) {
                first = writeDoubleField(sb, "cornerRadius", node.cornerRadius(), first);
            }
        }

        // --- Text wrap (shared) ---
        first = writeStringField(sb, "anchoredPosition", node.anchoredPosition(), first);
        first = writeStringField(sb, "textWrapMode", node.textWrapMode(), first);
        first = writeStringField(sb, "textWrapSide", node.textWrapSide(), first);
        if (node.textWrapTop() != 0) first = writeLongField(sb, "textWrapTop", node.textWrapTop(), first);
        if (node.textWrapLeft() != 0) first = writeLongField(sb, "textWrapLeft", node.textWrapLeft(), first);
        if (node.textWrapBottom() != 0) first = writeLongField(sb, "textWrapBottom", node.textWrapBottom(), first);
        if (node.textWrapRight() != 0) first = writeLongField(sb, "textWrapRight", node.textWrapRight(), first);

        // --- Overlay centering ---
        if (node.overlayCenterDeltaX() != 0) first = writeLongField(sb, "overlayCenterDeltaX", node.overlayCenterDeltaX(), first);
        if (node.overlayCenterDeltaY() != 0) first = writeLongField(sb, "overlayCenterDeltaY", node.overlayCenterDeltaY(), first);
        if (node.overlayParentWidth() != 0) first = writeLongField(sb, "overlayParentWidth", node.overlayParentWidth(), first);
        if (node.overlayParentHeight() != 0) first = writeLongField(sb, "overlayParentHeight", node.overlayParentHeight(), first);

        // --- Resolved coordinates (default -1, only write if set) ---
        if (node.resolvedPageX() != -1) first = writeLongField(sb, "resolvedPageX", node.resolvedPageX(), first);
        if (node.resolvedPageY() != -1) first = writeLongField(sb, "resolvedPageY", node.resolvedPageY(), first);
        if (node.resolvedWidth() != -1) first = writeLongField(sb, "resolvedWidth", node.resolvedWidth(), first);
        if (node.resolvedHeight() != -1) first = writeLongField(sb, "resolvedHeight", node.resolvedHeight(), first);

        sb.append('}');
    }

    private static boolean writeTextFrameFields(StringBuilder sb, FlatLayoutNode node, boolean first) {
        // figureKind: RENDERED_GROUP with content becomes TEXT_FRAME but keeps figureKind for round-trip
        first = writeStringField(sb, "figureKind", node.figureKind(), first);
        if (node.columnCount() != 0) first = writeIntField(sb, "columnCount", node.columnCount(), first);
        if (node.columnGutter() != 0) first = writeLongField(sb, "columnGutter", node.columnGutter(), first);
        if (node.columnWidths() != null) {
            first = writeLongArrayField(sb, "columnWidths", node.columnWidths(), first);
        }
        if (node.verticalText()) {
            first = writeBooleanField(sb, "verticalText", true, first);
        }
        first = writeStringField(sb, "verticalJustification", node.verticalJustification(), first);
        if (node.insetTop() != 0) first = writeLongField(sb, "insetTop", node.insetTop(), first);
        if (node.insetLeft() != 0) first = writeLongField(sb, "insetLeft", node.insetLeft(), first);
        if (node.insetBottom() != 0) first = writeLongField(sb, "insetBottom", node.insetBottom(), first);
        if (node.insetRight() != 0) first = writeLongField(sb, "insetRight", node.insetRight(), first);
        first = writeStringField(sb, "fillColor", node.fillColor(), first);
        first = writeStringField(sb, "strokeColor", node.strokeColor(), first);
        if (node.strokeWeight() != 0.0) {
            first = writeDoubleField(sb, "strokeWeight", node.strokeWeight(), first);
        }
        if (!"Solid".equals(node.strokeType())) {
            first = writeStringField(sb, "strokeType", node.strokeType(), first);
        }
        if (node.fillTint() != 100.0) {
            first = writeDoubleField(sb, "fillTint", node.fillTint(), first);
        }
        if (node.strokeTint() != 100.0) {
            first = writeDoubleField(sb, "strokeTint", node.strokeTint(), first);
        }
        if (node.cornerRadius() != 0.0) {
            first = writeDoubleField(sb, "cornerRadius", node.cornerRadius(), first);
        }
        if (node.fromGroup()) {
            first = writeBooleanField(sb, "fromGroup", true, first);
        }
        first = writeStringField(sb, "storyId", node.storyId(), first);
        if (node.distributed()) {
            first = writeBooleanField(sb, "distributed", true, first);
        }
        if (node.rotationAngle() != 0.0) {
            first = writeDoubleField(sb, "rotationAngle", node.rotationAngle(), first);
        }
        if (node.narrowedWidth() != 0) {
            first = writeLongField(sb, "narrowedWidth", node.narrowedWidth(), first);
        }
        first = writeStringField(sb, "wrapperFillColor", node.wrapperFillColor(), first);
        if (node.wrapperFillTint() != -1.0) {
            first = writeDoubleField(sb, "wrapperFillTint", node.wrapperFillTint(), first);
        }
        if (node.dropShadow()) {
            first = writeBooleanField(sb, "dropShadow", true, first);
        }
        if (node.pathPointsX() != null) {
            first = writeLongArrayField(sb, "pathPointsX", node.pathPointsX(), first);
        }
        if (node.pathPointsY() != null) {
            first = writeLongArrayField(sb, "pathPointsY", node.pathPointsY(), first);
        }
        if (node.textMarginTop() != 0) first = writeLongField(sb, "textMarginTop", node.textMarginTop(), first);
        if (node.textMarginLeft() != 0) first = writeLongField(sb, "textMarginLeft", node.textMarginLeft(), first);
        if (node.textMarginBottom() != 0) first = writeLongField(sb, "textMarginBottom", node.textMarginBottom(), first);
        if (node.textMarginRight() != 0) first = writeLongField(sb, "textMarginRight", node.textMarginRight(), first);
        if (node.isOverlay()) first = writeBooleanField(sb, "isOverlay", true, first);
        return first;
    }

    private static boolean writeFigureFields(StringBuilder sb, FlatLayoutNode node, boolean first) {
        first = writeStringField(sb, "figureKind", node.figureKind(), first);
        first = writeStringField(sb, "imageFormat", node.imageFormat(), first);
        // imageData → skip byte[]
        first = writeStringField(sb, "imagePath", node.imagePath(), first);
        if (node.pixelWidth() != 0) {
            first = writeIntField(sb, "pixelWidth", node.pixelWidth(), first);
        }
        if (node.pixelHeight() != 0) {
            first = writeIntField(sb, "pixelHeight", node.pixelHeight(), first);
        }
        if (node.hasCrop()) {
            first = writeDoubleField(sb, "cropLeftFraction", node.cropLeftFraction(), first);
            first = writeDoubleField(sb, "cropTopFraction", node.cropTopFraction(), first);
            first = writeDoubleField(sb, "cropRightFraction", node.cropRightFraction(), first);
            first = writeDoubleField(sb, "cropBottomFraction", node.cropBottomFraction(), first);
        }
        if (node.flipHorizontal()) first = writeBooleanField(sb, "flipHorizontal", true, first);
        if (node.flipVertical()) first = writeBooleanField(sb, "flipVertical", true, first);
        first = writeStringField(sb, "bundlePath", node.bundlePath(), first);
        if (node.containerWidth() != 0) first = writeLongField(sb, "containerWidth", node.containerWidth(), first);
        if (node.containerHeight() != 0) first = writeLongField(sb, "containerHeight", node.containerHeight(), first);
        if (node.imageOffsetX() != 0) first = writeLongField(sb, "imageOffsetX", node.imageOffsetX(), first);
        if (node.imageOffsetY() != 0) first = writeLongField(sb, "imageOffsetY", node.imageOffsetY(), first);
        // FIGURE also has fill/stroke for container
        first = writeStringField(sb, "fillColor", node.fillColor(), first);
        first = writeStringField(sb, "strokeColor", node.strokeColor(), first);
        if (node.strokeWeight() != 0.0) {
            first = writeDoubleField(sb, "strokeWeight", node.strokeWeight(), first);
        }
        if (node.fillColor() != null && node.fillTint() != 100.0) {
            first = writeDoubleField(sb, "fillTint", node.fillTint(), first);
        }
        if (node.strokeColor() != null && node.strokeTint() != 100.0) {
            first = writeDoubleField(sb, "strokeTint", node.strokeTint(), first);
        }
        if (node.cornerRadius() != 0.0) {
            first = writeDoubleField(sb, "cornerRadius", node.cornerRadius(), first);
        }
        return first;
    }

    private static boolean writeTableFields(StringBuilder sb, FlatLayoutNode node, boolean first) {
        if (node.rowCount() != 0) first = writeIntField(sb, "rowCount", node.rowCount(), first);
        if (node.colCount() != 0) first = writeIntField(sb, "colCount", node.colCount(), first);

        if (node.tableColumnWidths() != null && !node.tableColumnWidths().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"tableColumnWidths\":[");
            for (int i = 0; i < node.tableColumnWidths().size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(node.tableColumnWidths().get(i));
            }
            sb.append(']');
        }

        first = writeStringField(sb, "appliedTableStyle", node.appliedTableStyle(), first);
        first = writeStringField(sb, "borderColor", node.borderColor(), first);
        if (node.borderWidth() != 0) {
            first = writeLongField(sb, "borderWidth", node.borderWidth(), first);
        }

        if (node.tableRows() != null && !node.tableRows().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"tableRows\":[");
            for (int i = 0; i < node.tableRows().size(); i++) {
                if (i > 0) sb.append(',');
                writeTableRow(sb, node.tableRows().get(i));
            }
            sb.append(']');
        }

        return first;
    }

    // ─── Table sub-structures ────────────────────────────────────

    private static void writeTableRow(StringBuilder sb, FlatTableRow row) {
        sb.append('{');
        boolean first = true;
        first = writeIntField(sb, "rowIndex", row.rowIndex(), first);
        first = writeLongField(sb, "rowHeight", row.rowHeight(), first);
        if (row.autoGrow()) {
            first = writeBooleanField(sb, "autoGrow", true, first);
        }

        if (row.cells() != null && !row.cells().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"cells\":[");
            for (int i = 0; i < row.cells().size(); i++) {
                if (i > 0) sb.append(',');
                writeTableCell(sb, row.cells().get(i));
            }
            sb.append(']');
        }

        sb.append('}');
    }

    private static void writeTableCell(StringBuilder sb, FlatTableCell cell) {
        sb.append('{');
        boolean first = true;
        first = writeIntField(sb, "rowIndex", cell.rowIndex(), first);
        first = writeIntField(sb, "columnIndex", cell.columnIndex(), first);
        if (cell.rowSpan() != 1) {
            first = writeIntField(sb, "rowSpan", cell.rowSpan(), first);
        }
        if (cell.columnSpan() != 1) {
            first = writeIntField(sb, "columnSpan", cell.columnSpan(), first);
        }
        first = writeLongField(sb, "width", cell.width(), first);
        first = writeLongField(sb, "height", cell.height(), first);

        if (cell.componentIds() != null && !cell.componentIds().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"componentIds\":");
            writeStringList(sb, cell.componentIds());
        }

        first = writeStringField(sb, "fillColor", cell.fillColor(), first);

        // borders
        if (cell.topBorder() != null) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"topBorder\":");
            writeCellBorder(sb, cell.topBorder());
        }
        if (cell.bottomBorder() != null) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"bottomBorder\":");
            writeCellBorder(sb, cell.bottomBorder());
        }
        if (cell.leftBorder() != null) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"leftBorder\":");
            writeCellBorder(sb, cell.leftBorder());
        }
        if (cell.rightBorder() != null) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"rightBorder\":");
            writeCellBorder(sb, cell.rightBorder());
        }

        // diagonal
        if (cell.topLeftDiagonalLine()) {
            first = writeBooleanField(sb, "topLeftDiagonalLine", true, first);
        }
        if (cell.topRightDiagonalLine()) {
            first = writeBooleanField(sb, "topRightDiagonalLine", true, first);
        }
        if (cell.diagonalBorder() != null) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"diagonalBorder\":");
            writeCellBorder(sb, cell.diagonalBorder());
        }

        // margins
        if (cell.marginTop() != 0) first = writeLongField(sb, "marginTop", cell.marginTop(), first);
        if (cell.marginBottom() != 0) first = writeLongField(sb, "marginBottom", cell.marginBottom(), first);
        if (cell.marginLeft() != 0) first = writeLongField(sb, "marginLeft", cell.marginLeft(), first);
        if (cell.marginRight() != 0) first = writeLongField(sb, "marginRight", cell.marginRight(), first);

        if (!"TopAlign".equals(cell.verticalAlign())) {
            first = writeStringField(sb, "verticalAlign", cell.verticalAlign(), first);
        }

        sb.append('}');
    }

    private static void writeCellBorder(StringBuilder sb, FlatTableCell.CellBorder border) {
        sb.append('{');
        boolean first = true;
        first = writeStringField(sb, "color", border.color(), first);
        if (border.weight() != 0.0) {
            first = writeDoubleField(sb, "weight", border.weight(), first);
        }
        first = writeStringField(sb, "strokeType", border.strokeType(), first);
        if (border.tint() != 100.0) {
            first = writeDoubleField(sb, "tint", border.tint(), first);
        }
        sb.append('}');
    }

    // ─── Layer 2: Component ──────────────────────────────────────

    private static void writeComponent(StringBuilder sb, FlatComponent comp) {
        sb.append('{');
        boolean first = true;

        first = writeStringField(sb, "componentId", comp.componentId(), first);
        if (comp.type() != null) {
            first = writeStringField(sb, "type", comp.type().name(), first);
        }
        first = writeStringField(sb, "parentNodeId", comp.parentNodeId(), first);

        // paragraph style
        first = writeStringField(sb, "paragraphStyleRef", comp.paragraphStyleRef(), first);
        first = writeStringField(sb, "alignment", comp.alignment(), first);
        if (comp.firstLineIndent() != null) {
            first = writeBoxedLongField(sb, "firstLineIndent", comp.firstLineIndent(), first);
        }
        if (comp.leftMargin() != null) {
            first = writeBoxedLongField(sb, "leftMargin", comp.leftMargin(), first);
        }
        if (comp.rightMargin() != null) {
            first = writeBoxedLongField(sb, "rightMargin", comp.rightMargin(), first);
        }
        if (comp.spaceBefore() != null) {
            first = writeBoxedLongField(sb, "spaceBefore", comp.spaceBefore(), first);
        }
        if (comp.spaceAfter() != null) {
            first = writeBoxedLongField(sb, "spaceAfter", comp.spaceAfter(), first);
        }
        if (comp.lineSpacing() != null) {
            first = writeBoxedIntField(sb, "lineSpacing", comp.lineSpacing(), first);
        }
        first = writeStringField(sb, "lineSpacingType", comp.lineSpacingType(), first);
        if (comp.letterSpacing() != null) {
            first = writeBoxedShortField(sb, "letterSpacing", comp.letterSpacing(), first);
        }

        // shading
        if (comp.shadingOn()) {
            first = writeBooleanField(sb, "shadingOn", true, first);
            first = writeStringField(sb, "shadingColor", comp.shadingColor(), first);
            if (comp.shadingTint() != null) {
                first = writeBoxedDoubleField(sb, "shadingTint", comp.shadingTint(), first);
            }
            if (comp.shadingLeftOffset() != null) {
                first = writeBoxedLongField(sb, "shadingLeftOffset", comp.shadingLeftOffset(), first);
            }
            if (comp.shadingRightOffset() != null) {
                first = writeBoxedLongField(sb, "shadingRightOffset", comp.shadingRightOffset(), first);
            }
            if (comp.shadingTopOffset() != null) {
                first = writeBoxedLongField(sb, "shadingTopOffset", comp.shadingTopOffset(), first);
            }
            if (comp.shadingBottomOffset() != null) {
                first = writeBoxedLongField(sb, "shadingBottomOffset", comp.shadingBottomOffset(), first);
            }
        }

        // tabStops
        if (comp.hasTabStops()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"tabStops\":[");
            for (int i = 0; i < comp.tabStops().size(); i++) {
                if (i > 0) sb.append(',');
                writeTabStop(sb, comp.tabStops().get(i));
            }
            sb.append(']');
        }

        // yOffsetInFrame (default -1)
        if (comp.yOffsetInFrame() != -1.0) {
            first = writeDoubleField(sb, "yOffsetInFrame", comp.yOffsetInFrame(), first);
        }

        if (comp.columnBreakAfter()) {
            first = writeBooleanField(sb, "columnBreakAfter", true, first);
        }

        first = writeStringField(sb, "pendingUnderlineColor", comp.pendingUnderlineColor(), first);

        // items
        if (comp.items() != null && !comp.items().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"items\":[");
            for (int i = 0; i < comp.items().size(); i++) {
                if (i > 0) sb.append(',');
                writeInlineItem(sb, comp.items().get(i));
            }
            sb.append(']');
        }

        sb.append('}');
    }

    // ─── Inline items ────────────────────────────────────────────

    private static void writeInlineItem(StringBuilder sb, FlatInlineItem item) {
        if (item.itemType() == null) return;
        switch (item.itemType()) {
            case TEXT_RUN:
                writeTextRun(sb, item);
                break;
            case BREAK:
                writeBreak(sb, item);
                break;
            case EQUATION:
                writeEquation(sb, item);
                break;
            case LAYOUT_REF:
                writeLayoutRef(sb, item);
                break;
        }
    }

    private static void writeTextRun(StringBuilder sb, FlatInlineItem item) {
        sb.append('{');
        boolean first = true;
        first = writeStringField(sb, "itemType", "TEXT_RUN", first);
        first = writeStringField(sb, "characterStyleRef", item.characterStyleRef(), first);
        first = writeStringField(sb, "text", item.text(), first);
        first = writeStringField(sb, "fontFamily", item.fontFamily(), first);
        first = writeStringField(sb, "fontStyle", item.fontStyle(), first);
        if (item.fontSizeHwpunits() != null) {
            first = writeBoxedIntField(sb, "fontSizeHwpunits", item.fontSizeHwpunits(), first);
        }
        first = writeStringField(sb, "textColor", item.textColor(), first);
        if (item.letterSpacing() != null) {
            first = writeBoxedShortField(sb, "letterSpacing", item.letterSpacing(), first);
        }
        if (item.subscript()) first = writeBooleanField(sb, "subscript", true, first);
        if (item.superscript()) first = writeBooleanField(sb, "superscript", true, first);
        if (item.grepMathFont()) first = writeBooleanField(sb, "grepMathFont", true, first);
        if (item.underline()) first = writeBooleanField(sb, "underline", true, first);
        if (item.strikeThrough()) first = writeBooleanField(sb, "strikeThrough", true, first);
        first = writeStringField(sb, "underlineColor", item.underlineColor(), first);
        first = writeStringField(sb, "underlineShape", item.underlineShape(), first);
        if (item.horizontalScale() != null) {
            first = writeBoxedShortField(sb, "horizontalScale", item.horizontalScale(), first);
        }
        if (item.verticalScale() != null) {
            first = writeBoxedShortField(sb, "verticalScale", item.verticalScale(), first);
        }
        if (item.baselineShift() != null) {
            first = writeBoxedShortField(sb, "baselineShift", item.baselineShift(), first);
        }
        sb.append('}');
    }

    private static void writeBreak(StringBuilder sb, FlatInlineItem item) {
        sb.append('{');
        boolean first = true;
        first = writeStringField(sb, "itemType", "BREAK", first);
        first = writeStringField(sb, "breakType", item.breakType(), first);
        sb.append('}');
    }

    private static void writeEquation(StringBuilder sb, FlatInlineItem item) {
        sb.append('{');
        boolean first = true;
        first = writeStringField(sb, "itemType", "EQUATION", first);
        first = writeStringField(sb, "hwpScript", item.hwpScript(), first);
        first = writeStringField(sb, "equationSourceType", item.equationSourceType(), first);
        first = writeStringField(sb, "equationTextColor", item.equationTextColor(), first);
        sb.append('}');
    }

    private static void writeLayoutRef(StringBuilder sb, FlatInlineItem item) {
        sb.append('{');
        boolean first = true;
        first = writeStringField(sb, "itemType", "LAYOUT_REF", first);
        first = writeStringField(sb, "layoutNodeId", item.layoutNodeId(), first);
        sb.append('}');
    }

    // ─── Tab stop ────────────────────────────────────────────────

    private static void writeTabStop(StringBuilder sb, ASTTabStop ts) {
        sb.append('{');
        sb.append("\"position\":").append(ts.position());
        if (ts.alignment() != null) {
            sb.append(",\"alignment\":\"").append(escapeJson(ts.alignment())).append('"');
        }
        if (ts.leader() != null) {
            sb.append(",\"leader\":\"").append(escapeJson(ts.leader())).append('"');
        }
        sb.append('}');
    }

    // ─── Helper: string list ─────────────────────────────────────

    private static void writeStringList(StringBuilder sb, List<String> list) {
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escapeJson(list.get(i))).append('"');
        }
        sb.append(']');
    }

    // ─── Field writers ───────────────────────────────────────────

    private static boolean writeStringField(StringBuilder sb, String key, String value, boolean first) {
        if (value == null) return first;
        if (!first) sb.append(',');
        sb.append('"').append(key).append("\":\"").append(escapeJson(value)).append('"');
        return false;
    }

    private static boolean writeIntField(StringBuilder sb, String key, int value, boolean first) {
        if (!first) sb.append(',');
        sb.append('"').append(key).append("\":").append(value);
        return false;
    }

    private static boolean writeLongField(StringBuilder sb, String key, long value, boolean first) {
        if (!first) sb.append(',');
        sb.append('"').append(key).append("\":").append(value);
        return false;
    }

    private static boolean writeDoubleField(StringBuilder sb, String key, double value, boolean first) {
        if (!first) sb.append(',');
        sb.append('"').append(key).append("\":").append(value);
        return false;
    }

    private static boolean writeBooleanField(StringBuilder sb, String key, boolean value, boolean first) {
        if (!first) sb.append(',');
        sb.append('"').append(key).append("\":").append(value);
        return false;
    }

    private static boolean writeLongArrayField(StringBuilder sb, String key, long[] value, boolean first) {
        if (value == null) return first;
        if (!first) sb.append(',');
        sb.append('"').append(key).append("\":[");
        for (int i = 0; i < value.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(value[i]);
        }
        sb.append(']');
        return false;
    }

    private static boolean writeBoxedIntField(StringBuilder sb, String key, Integer value, boolean first) {
        if (value == null) return first;
        if (!first) sb.append(',');
        sb.append('"').append(key).append("\":").append(value.intValue());
        return false;
    }

    private static boolean writeBoxedLongField(StringBuilder sb, String key, Long value, boolean first) {
        if (value == null) return first;
        if (!first) sb.append(',');
        sb.append('"').append(key).append("\":").append(value.longValue());
        return false;
    }

    private static boolean writeBoxedShortField(StringBuilder sb, String key, Short value, boolean first) {
        if (value == null) return first;
        if (!first) sb.append(',');
        sb.append('"').append(key).append("\":").append(value.shortValue());
        return false;
    }

    private static boolean writeBoxedDoubleField(StringBuilder sb, String key, Double value, boolean first) {
        if (value == null) return first;
        if (!first) sb.append(',');
        sb.append('"').append(key).append("\":").append(value.doubleValue());
        return false;
    }

    // ─── JSON string escaping ────────────────────────────────────

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}
