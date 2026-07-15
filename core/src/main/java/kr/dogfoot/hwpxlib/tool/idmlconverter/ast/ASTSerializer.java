package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

import java.util.Map;

/**
 * ASTDocument를 JSON 문자열로 직렬화.
 * 외부 라이브러리 없이 StringBuilder 기반 수동 JSON 생성.
 *
 * 규칙:
 * - byte[] 필드 (imageData, pngData) → 제외, 메타데이터만 출력
 * - null 필드는 출력 생략
 * - JSON 키는 camelCase (Java 필드명 그대로)
 */
public class ASTSerializer {

    public static String toJson(ASTDocument doc) {
        StringBuilder sb = new StringBuilder();
        writeDocument(sb, doc);
        return sb.toString();
    }

    private static void writeDocument(StringBuilder sb, ASTDocument doc) {
        sb.append('{');
        boolean first = true;

        first = writeStringField(sb, "sourceFile", doc.sourceFile(), first);
        first = writeStringField(sb, "sourceFormat", doc.sourceFormat(), first);

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

        // sections
        if (doc.sections() != null && !doc.sections().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"sections\":[");
            for (int i = 0; i < doc.sections().size(); i++) {
                if (i > 0) sb.append(',');
                writeSection(sb, doc.sections().get(i));
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

        sb.append('}');
    }

    private static void writeSection(StringBuilder sb, ASTSection sec) {
        sb.append('{');
        boolean first = true;

        first = writeIntField(sb, "pageNumber", sec.pageNumber(), first);

        // layout
        if (sec.layout() != null) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"layout\":");
            writePageLayout(sb, sec.layout());
        }

        // blocks
        if (sec.blocks() != null && !sec.blocks().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"blocks\":[");
            for (int i = 0; i < sec.blocks().size(); i++) {
                if (i > 0) sb.append(',');
                writeBlock(sb, sec.blocks().get(i));
            }
            sb.append(']');
        }

        sb.append('}');
    }

    private static void writePageLayout(StringBuilder sb, ASTPageLayout layout) {
        sb.append('{');
        boolean first = true;
        first = writeLongField(sb, "pageWidth", layout.pageWidth(), first);
        first = writeLongField(sb, "pageHeight", layout.pageHeight(), first);
        first = writeLongField(sb, "marginTop", layout.marginTop(), first);
        first = writeLongField(sb, "marginBottom", layout.marginBottom(), first);
        first = writeLongField(sb, "marginLeft", layout.marginLeft(), first);
        first = writeLongField(sb, "marginRight", layout.marginRight(), first);
        first = writeIntField(sb, "columnCount", layout.columnCount(), first);
        first = writeLongField(sb, "columnGutter", layout.columnGutter(), first);
        sb.append('}');
    }

    private static void writeBackground(StringBuilder sb, ASTPageBackground bg) {
        sb.append('{');
        boolean first = true;
        first = writeIntField(sb, "pageNumber", bg.pageNumber(), first);
        first = writeLongField(sb, "pageWidth", bg.pageWidth(), first);
        first = writeLongField(sb, "pageHeight", bg.pageHeight(), first);
        // pngData → skip byte[], only output pixel dimensions
        first = writeIntField(sb, "pixelWidth", bg.pixelWidth(), first);
        first = writeIntField(sb, "pixelHeight", bg.pixelHeight(), first);
        first = writeBooleanField(sb, "hasPngData", bg.pngData() != null, first);
        first = writeStringField(sb, "bundlePath", bg.bundlePath(), first);
        sb.append('}');
    }

    private static void writeBlock(StringBuilder sb, ASTBlock block) {
        if (block instanceof ASTTextFrameBlock) {
            writeTextFrameBlock(sb, (ASTTextFrameBlock) block);
        } else if (block instanceof ASTTable) {
            writeTable(sb, (ASTTable) block);
        } else if (block instanceof ASTFigure) {
            writeFigure(sb, (ASTFigure) block);
        }
    }

    private static void writeStory(StringBuilder sb, ASTStory story) {
        sb.append('{');
        boolean first = true;
        first = writeStringField(sb, "storyId", story.storyId(), first);
        first = writeStringField(sb, "orientation", story.orientation(), first);
        first = writeIntField(sb, "paragraphCount", story.paragraphCount(), first);
        first = writeIntField(sb, "tableCount", story.tableCount(), first);

        if (story.linkedFrameIds() != null && !story.linkedFrameIds().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"linkedFrameIds\":[");
            for (int i = 0; i < story.linkedFrameIds().size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(escapeJson(story.linkedFrameIds().get(i))).append('"');
            }
            sb.append(']');
        }

        if (story.pages() != null && !story.pages().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"pages\":[");
            for (int i = 0; i < story.pages().size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(story.pages().get(i));
            }
            sb.append(']');
        }

        sb.append('}');
    }

    private static void writeTextFrameBlock(StringBuilder sb, ASTTextFrameBlock tf) {
        sb.append('{');
        boolean first = true;
        first = writeStringField(sb, "blockType", "TEXT_FRAME_BLOCK", first);
        first = writeStringField(sb, "sourceId", tf.sourceId(), first);
        first = writeDebugMeta(sb, tf.debug(), first);
        first = writeStringField(sb, "storyId", tf.storyId(), first);
        first = writeLongField(sb, "x", tf.x(), first);
        first = writeLongField(sb, "y", tf.y(), first);
        first = writeLongField(sb, "width", tf.width(), first);
        first = writeLongField(sb, "height", tf.height(), first);
        first = writeIntField(sb, "zOrder", tf.zOrder(), first);
        first = writeIntField(sb, "columnCount", tf.columnCount(), first);
        first = writeLongField(sb, "columnGutter", tf.columnGutter(), first);
        if (tf.columnWidths() != null) {
            first = writeLongArrayField(sb, "columnWidths", tf.columnWidths(), first);
        }
        if (tf.verticalText()) {
            first = writeBooleanField(sb, "verticalText", true, first);
        }
        first = writeStringField(sb, "verticalJustification", tf.verticalJustification(), first);
        first = writeLongField(sb, "insetTop", tf.insetTop(), first);
        first = writeLongField(sb, "insetLeft", tf.insetLeft(), first);
        first = writeLongField(sb, "insetBottom", tf.insetBottom(), first);
        first = writeLongField(sb, "insetRight", tf.insetRight(), first);
        first = writeStringField(sb, "fillColor", tf.fillColor(), first);
        first = writeStringField(sb, "strokeColor", tf.strokeColor(), first);
        if (tf.strokeWeight() != 0.0) {
            first = writeDoubleField(sb, "strokeWeight", tf.strokeWeight(), first);
        }
        if (tf.cornerRadius() != 0.0) {
            first = writeDoubleField(sb, "cornerRadius", tf.cornerRadius(), first);
        }
        if (!"Solid".equals(tf.strokeType())) {
            first = writeStringField(sb, "strokeType", tf.strokeType(), first);
        }
        if (tf.fillTint() != 100.0) {
            first = writeDoubleField(sb, "fillTint", tf.fillTint(), first);
        }
        if (tf.strokeTint() != 100.0) {
            first = writeDoubleField(sb, "strokeTint", tf.strokeTint(), first);
        }
        if (tf.fromGroup()) {
            first = writeBooleanField(sb, "fromGroup", true, first);
        }
        if (tf.noAutoLineWrap()) {
            first = writeBooleanField(sb, "noAutoLineWrap", true, first);
        }
        if (tf.plannedVisualTextOverlay()) {
            first = writeBooleanField(sb, "plannedVisualTextOverlay", true, first);
        }
        if (tf.anchoredFlowWithText()) {
            first = writeBooleanField(sb, "anchoredFlowWithText", true, first);
        }

        // paragraphs
        if (tf.paragraphs() != null && !tf.paragraphs().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"paragraphs\":[");
            for (int i = 0; i < tf.paragraphs().size(); i++) {
                if (i > 0) sb.append(',');
                writeParagraph(sb, tf.paragraphs().get(i));
            }
            sb.append(']');
        }

        sb.append('}');
    }

    private static void writeTable(StringBuilder sb, ASTTable table) {
        sb.append('{');
        boolean first = true;
        first = writeStringField(sb, "blockType", "TABLE", first);
        first = writeStringField(sb, "sourceId", table.sourceId(), first);
        first = writeDebugMeta(sb, table.debug(), first);
        first = writeLongField(sb, "x", table.x(), first);
        first = writeLongField(sb, "y", table.y(), first);
        first = writeLongField(sb, "width", table.width(), first);
        first = writeLongField(sb, "height", table.height(), first);
        first = writeIntField(sb, "zOrder", table.zOrder(), first);
        if (table.flowWithText()) {
            first = writeBooleanField(sb, "flowWithText", true, first);
        }
        if (table.anchoredFlowWithText()) {
            first = writeBooleanField(sb, "anchoredFlowWithText", true, first);
        }
        if (table.fixedOuterBounds()) {
            first = writeBooleanField(sb, "fixedOuterBounds", true, first);
        }
        first = writeIntField(sb, "rowCount", table.rowCount(), first);
        first = writeIntField(sb, "colCount", table.colCount(), first);
        first = writeStringField(sb, "appliedTableStyle", table.appliedTableStyle(), first);
        first = writeStringField(sb, "borderColor", table.borderColor(), first);
        if (table.borderWidth() != 0) {
            first = writeLongField(sb, "borderWidth", table.borderWidth(), first);
        }

        // columnWidths
        if (table.columnWidths() != null && !table.columnWidths().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"columnWidths\":[");
            for (int i = 0; i < table.columnWidths().size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(table.columnWidths().get(i));
            }
            sb.append(']');
        }

        // rows
        if (table.rows() != null && !table.rows().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"rows\":[");
            for (int i = 0; i < table.rows().size(); i++) {
                if (i > 0) sb.append(',');
                writeTableRow(sb, table.rows().get(i));
            }
            sb.append(']');
        }

        sb.append('}');
    }

    private static void writeTableRow(StringBuilder sb, ASTTableRow row) {
        sb.append('{');
        boolean first = true;
        first = writeIntField(sb, "rowIndex", row.rowIndex(), first);
        first = writeLongField(sb, "rowHeight", row.rowHeight(), first);
        if (row.autoGrow()) {
            first = writeBooleanField(sb, "autoGrow", true, first);
        }

        // cells
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

    private static void writeTableCell(StringBuilder sb, ASTTableCell cell) {
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
        first = writeStringField(sb, "fillColor", cell.fillColor(), first);
        first = writeStringField(sb, "verticalAlign", cell.verticalAlign(), first);
        first = writeStringField(sb, "firstBaselineOffset", cell.firstBaselineOffset(), first);
        if (cell.minimumFirstBaselineOffset() != 0) {
            first = writeLongField(sb, "minimumFirstBaselineOffset", cell.minimumFirstBaselineOffset(), first);
        }

        // margins
        if (cell.marginTop() != 0) first = writeLongField(sb, "marginTop", cell.marginTop(), first);
        if (cell.marginBottom() != 0) first = writeLongField(sb, "marginBottom", cell.marginBottom(), first);
        if (cell.marginLeft() != 0) first = writeLongField(sb, "marginLeft", cell.marginLeft(), first);
        if (cell.marginRight() != 0) first = writeLongField(sb, "marginRight", cell.marginRight(), first);

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

        // paragraphs
        if (cell.paragraphs() != null && !cell.paragraphs().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"paragraphs\":[");
            for (int i = 0; i < cell.paragraphs().size(); i++) {
                if (i > 0) sb.append(',');
                writeParagraph(sb, cell.paragraphs().get(i));
            }
            sb.append(']');
        }

        sb.append('}');
    }

    private static void writeCellBorder(StringBuilder sb, ASTTableCell.CellBorder border) {
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

    private static void writeFigure(StringBuilder sb, ASTFigure fig) {
        sb.append('{');
        boolean first = true;
        first = writeStringField(sb, "blockType", "FIGURE", first);
        first = writeStringField(sb, "sourceId", fig.sourceId(), first);
        first = writeDebugMeta(sb, fig.debug(), first);
        if (fig.kind() != null) {
            first = writeStringField(sb, "kind", fig.kind().name(), first);
        }
        first = writeLongField(sb, "x", fig.x(), first);
        first = writeLongField(sb, "y", fig.y(), first);
        first = writeLongField(sb, "width", fig.width(), first);
        first = writeLongField(sb, "height", fig.height(), first);
        first = writeIntField(sb, "zOrder", fig.zOrder(), first);
        if (fig.rotationAngle() != 0.0) {
            first = writeDoubleField(sb, "rotationAngle", fig.rotationAngle(), first);
        }
        first = writeStringField(sb, "imageFormat", fig.imageFormat(), first);
        // imageData → skip byte[], only output metadata
        first = writeStringField(sb, "imagePath", fig.imagePath(), first);
        if (fig.pixelWidth() != 0) {
            first = writeIntField(sb, "pixelWidth", fig.pixelWidth(), first);
        }
        if (fig.pixelHeight() != 0) {
            first = writeIntField(sb, "pixelHeight", fig.pixelHeight(), first);
        }
        first = writeBooleanField(sb, "hasImageData", fig.imageData() != null, first);
        if (fig.hasCrop()) {
            first = writeDoubleField(sb, "cropLeftFraction", fig.cropLeftFraction(), first);
            first = writeDoubleField(sb, "cropTopFraction", fig.cropTopFraction(), first);
            first = writeDoubleField(sb, "cropRightFraction", fig.cropRightFraction(), first);
            first = writeDoubleField(sb, "cropBottomFraction", fig.cropBottomFraction(), first);
        }
        if (fig.flipHorizontal()) first = writeBooleanField(sb, "flipHorizontal", true, first);
        if (fig.flipVertical()) first = writeBooleanField(sb, "flipVertical", true, first);
        first = writeStringField(sb, "bundlePath", fig.bundlePath(), first);
        first = writeStringField(sb, "visualLayer", fig.visualLayer(), first);
        if (fig.sourceLayerIndex() >= 0) {
            first = writeIntField(sb, "sourceLayerIndex", fig.sourceLayerIndex(), first);
        }

        sb.append('}');
    }

    private static void writeParagraph(StringBuilder sb, ASTParagraph para) {
        sb.append('{');
        boolean first = true;
        first = writeStringField(sb, "paragraphStyleRef", para.paragraphStyleRef(), first);
        first = writeStringField(sb, "alignment", para.alignment(), first);
        if (para.firstLineIndent() != null) {
            first = writeBoxedLongField(sb, "firstLineIndent", para.firstLineIndent(), first);
        }
        if (para.leftMargin() != null) {
            first = writeBoxedLongField(sb, "leftMargin", para.leftMargin(), first);
        }
        if (para.rightMargin() != null) {
            first = writeBoxedLongField(sb, "rightMargin", para.rightMargin(), first);
        }
        if (para.spaceBefore() != null) {
            first = writeBoxedLongField(sb, "spaceBefore", para.spaceBefore(), first);
        }
        if (para.spaceAfter() != null) {
            first = writeBoxedLongField(sb, "spaceAfter", para.spaceAfter(), first);
        }
        if (para.lineSpacing() != null) {
            first = writeBoxedIntField(sb, "lineSpacing", para.lineSpacing(), first);
        }
        first = writeStringField(sb, "lineSpacingType", para.lineSpacingType(), first);
        if (para.autoLeadingPercent() != null) {
            first = writeBoxedIntField(sb, "autoLeadingPercent", para.autoLeadingPercent(), first);
        }
        if (para.letterSpacing() != null) {
            first = writeBoxedShortField(sb, "letterSpacing", para.letterSpacing(), first);
        }
        if (para.squeezeLineWrap()) {
            first = writeBooleanField(sb, "squeezeLineWrap", true, first);
        }
        // shading
        if (para.shadingOn()) {
            first = writeBooleanField(sb, "shadingOn", true, first);
            first = writeStringField(sb, "shadingColor", para.shadingColor(), first);
            if (para.shadingTint() != null) {
                first = writeBoxedDoubleField(sb, "shadingTint", para.shadingTint(), first);
            }
            if (para.shadingLeftOffset() != null) {
                first = writeBoxedLongField(sb, "shadingLeftOffset", para.shadingLeftOffset(), first);
            }
            if (para.shadingRightOffset() != null) {
                first = writeBoxedLongField(sb, "shadingRightOffset", para.shadingRightOffset(), first);
            }
            if (para.shadingTopOffset() != null) {
                first = writeBoxedLongField(sb, "shadingTopOffset", para.shadingTopOffset(), first);
            }
            if (para.shadingBottomOffset() != null) {
                first = writeBoxedLongField(sb, "shadingBottomOffset", para.shadingBottomOffset(), first);
            }
        }

        // tabStops
        if (para.hasTabStops()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"tabStops\":[");
            for (int i = 0; i < para.tabStops().size(); i++) {
                if (i > 0) sb.append(',');
                ASTTabStop ts = para.tabStops().get(i);
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
            sb.append(']');
        }

        // items
        if (para.items() != null && !para.items().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"items\":[");
            for (int i = 0; i < para.items().size(); i++) {
                if (i > 0) sb.append(',');
                writeInlineItem(sb, para.items().get(i));
            }
            sb.append(']');
        }

        sb.append('}');
    }

    private static void writeInlineItem(StringBuilder sb, ASTInlineItem item) {
        if (item instanceof ASTTextRun) {
            writeTextRun(sb, (ASTTextRun) item);
        } else if (item instanceof ASTInlineObject) {
            writeInlineObject(sb, (ASTInlineObject) item);
        } else if (item instanceof ASTEquation) {
            writeEquation(sb, (ASTEquation) item);
        } else if (item instanceof ASTBreak) {
            writeBreak(sb, (ASTBreak) item);
        }
    }

    private static void writeTextRun(StringBuilder sb, ASTTextRun run) {
        sb.append('{');
        boolean first = true;
        first = writeStringField(sb, "itemType", "TEXT_RUN", first);
        first = writeStringField(sb, "text", run.text(), first);
        first = writeStringField(sb, "characterStyleRef", run.characterStyleRef(), first);
        first = writeStringField(sb, "fontFamily", run.fontFamily(), first);
        first = writeStringField(sb, "fontStyle", run.fontStyle(), first);
        if (run.fontSizeHwpunits() != null) {
            first = writeBoxedIntField(sb, "fontSizeHwpunits", run.fontSizeHwpunits(), first);
        }
        first = writeStringField(sb, "textColor", run.textColor(), first);
        first = writeStringField(sb, "shadeColor", run.shadeColor(), first);
        if (run.letterSpacing() != null) {
            first = writeBoxedShortField(sb, "letterSpacing", run.letterSpacing(), first);
        }
        if (run.subscript()) {
            first = writeBooleanField(sb, "subscript", true, first);
        }
        if (run.superscript()) {
            first = writeBooleanField(sb, "superscript", true, first);
        }
        if (run.underline()) {
            first = writeBooleanField(sb, "underline", true, first);
        }
        if (run.strikeThrough()) {
            first = writeBooleanField(sb, "strikeThrough", true, first);
        }
        if (run.horizontalScale() != null) {
            first = writeBoxedShortField(sb, "horizontalScale", run.horizontalScale(), first);
        }
        if (run.grepMathFont()) {
            first = writeBooleanField(sb, "grepMathFont", true, first);
        }
        sb.append('}');
    }

    private static void writeInlineObject(StringBuilder sb, ASTInlineObject obj) {
        sb.append('{');
        boolean first = true;
        first = writeStringField(sb, "itemType", "INLINE_OBJECT", first);
        if (obj.kind() != null) {
            first = writeStringField(sb, "kind", obj.kind().name(), first);
        }
        first = writeStringField(sb, "sourceId", obj.sourceId(), first);
        first = writeLongField(sb, "width", obj.width(), first);
        first = writeLongField(sb, "height", obj.height(), first);
        first = writeStringField(sb, "imageFormat", obj.imageFormat(), first);
        // imageData → skip byte[], only output metadata
        first = writeStringField(sb, "imagePath", obj.imagePath(), first);
        if (obj.pixelWidth() != 0) {
            first = writeIntField(sb, "pixelWidth", obj.pixelWidth(), first);
        }
        if (obj.pixelHeight() != 0) {
            first = writeIntField(sb, "pixelHeight", obj.pixelHeight(), first);
        }
        first = writeBooleanField(sb, "hasImageData", obj.imageData() != null, first);
        first = writeStringField(sb, "anchoredPosition", obj.anchoredPosition(), first);
        first = writeStringField(sb, "textWrapMode", obj.textWrapMode(), first);
        first = writeStringField(sb, "textWrapSide", obj.textWrapSide(), first);
        if (!obj.affectsLineSpacing()) {
            first = writeBooleanField(sb, "affectsLineSpacing", false, first);
        }
        first = writeStringField(sb, "fillColor", obj.fillColor(), first);
        if (obj.fillTint() != 100.0) {
            first = writeDoubleField(sb, "fillTint", obj.fillTint(), first);
        }
        first = writeStringField(sb, "strokeColor", obj.strokeColor(), first);
        if (obj.strokeWeight() != 0.0) first = writeDoubleField(sb, "strokeWeight", obj.strokeWeight(), first);
        if (obj.strokeTint() != 100.0) first = writeDoubleField(sb, "strokeTint", obj.strokeTint(), first);
        if (obj.cornerRadius() != 0.0) first = writeDoubleField(sb, "cornerRadius", obj.cornerRadius(), first);
        if (obj.shellShapeType() != null) first = writeStringField(sb, "shellShapeType", obj.shellShapeType(), first);
        if (obj.noAutoLineWrap()) first = writeBooleanField(sb, "noAutoLineWrap", true, first);

        if (obj.textWrapTop() != 0) first = writeLongField(sb, "textWrapTop", obj.textWrapTop(), first);
        if (obj.textWrapLeft() != 0) first = writeLongField(sb, "textWrapLeft", obj.textWrapLeft(), first);
        if (obj.textWrapBottom() != 0) first = writeLongField(sb, "textWrapBottom", obj.textWrapBottom(), first);
        if (obj.textWrapRight() != 0) first = writeLongField(sb, "textWrapRight", obj.textWrapRight(), first);

        if (obj.textMarginTop() != 0) first = writeLongField(sb, "textMarginTop", obj.textMarginTop(), first);
        if (obj.textMarginLeft() != 0) first = writeLongField(sb, "textMarginLeft", obj.textMarginLeft(), first);
        if (obj.textMarginBottom() != 0) first = writeLongField(sb, "textMarginBottom", obj.textMarginBottom(), first);
        if (obj.textMarginRight() != 0) first = writeLongField(sb, "textMarginRight", obj.textMarginRight(), first);

        if (obj.isOverlay()) {
            first = writeBooleanField(sb, "isOverlay", true, first);
            first = writeLongField(sb, "overlayX", obj.overlayX(), first);
            first = writeLongField(sb, "overlayY", obj.overlayY(), first);
            first = writeLongField(sb, "overlayParentWidth", obj.overlayParentWidth(), first);
            first = writeLongField(sb, "overlayParentHeight", obj.overlayParentHeight(), first);
        }

        if (obj.containerWidth() != 0) first = writeLongField(sb, "containerWidth", obj.containerWidth(), first);
        if (obj.containerHeight() != 0) first = writeLongField(sb, "containerHeight", obj.containerHeight(), first);
        if (obj.imageOffsetX() != 0) first = writeLongField(sb, "imageOffsetX", obj.imageOffsetX(), first);
        if (obj.imageOffsetY() != 0) first = writeLongField(sb, "imageOffsetY", obj.imageOffsetY(), first);

        // overlayFrames (재귀)
        if (obj.overlayFrames() != null && !obj.overlayFrames().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"overlayFrames\":[");
            for (int i = 0; i < obj.overlayFrames().size(); i++) {
                if (i > 0) sb.append(',');
                writeInlineObject(sb, obj.overlayFrames().get(i));
            }
            sb.append(']');
        }

        // 인라인 텍스트 프레임 단락
        if (obj.paragraphs() != null && !obj.paragraphs().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"paragraphs\":[");
            for (int i = 0; i < obj.paragraphs().size(); i++) {
                if (i > 0) sb.append(',');
                writeParagraph(sb, obj.paragraphs().get(i));
            }
            sb.append(']');
        }

        // inlineTables
        if (obj.inlineTables() != null && !obj.inlineTables().isEmpty()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"inlineTables\":[");
            for (int i = 0; i < obj.inlineTables().size(); i++) {
                if (i > 0) sb.append(',');
                writeTable(sb, obj.inlineTables().get(i));
            }
            sb.append(']');
        }

        first = writeStringField(sb, "bundlePath", obj.bundlePath(), first);
        sb.append('}');
    }

    private static void writeEquation(StringBuilder sb, ASTEquation eq) {
        sb.append('{');
        boolean first = true;
        first = writeStringField(sb, "itemType", "EQUATION", first);
        first = writeStringField(sb, "hwpScript", eq.hwpScript(), first);
        first = writeStringField(sb, "sourceType", eq.sourceType(), first);
        first = writeStringField(sb, "textColor", eq.textColor(), first);
        first = writeBoxedIntField(sb, "preferredBaseUnit", eq.preferredBaseUnit(), first);
        first = writeStringField(sb, "preferredFontFamily", eq.preferredFontFamily(), first);
        sb.append('}');
    }

    private static void writeBreak(StringBuilder sb, ASTBreak brk) {
        sb.append('{');
        boolean first = true;
        first = writeStringField(sb, "itemType", "BREAK", first);
        if (brk.breakType() != null) {
            first = writeStringField(sb, "breakType", brk.breakType().name(), first);
        }
        sb.append('}');
    }

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
        first = writeStringField(sb, "basedOnStyleRef", style.basedOnStyleRef(), first);
        first = writeStringField(sb, "alignment", style.alignment(), first);
        if (style.firstLineIndent() != null) {
            first = writeBoxedLongField(sb, "firstLineIndent", style.firstLineIndent(), first);
        }
        if (style.leftMargin() != null) {
            first = writeBoxedLongField(sb, "leftMargin", style.leftMargin(), first);
        }
        if (style.rightMargin() != null) {
            first = writeBoxedLongField(sb, "rightMargin", style.rightMargin(), first);
        }
        if (style.spaceBefore() != null) {
            first = writeBoxedLongField(sb, "spaceBefore", style.spaceBefore(), first);
        }
        if (style.spaceAfter() != null) {
            first = writeBoxedLongField(sb, "spaceAfter", style.spaceAfter(), first);
        }
        if (style.lineSpacing() != null) {
            first = writeBoxedIntField(sb, "lineSpacing", style.lineSpacing(), first);
        }
        first = writeStringField(sb, "fontFamily", style.fontFamily(), first);
        first = writeStringField(sb, "fontStyle", style.fontStyle(), first);
        if (style.fontSizeHwpunits() != null) {
            first = writeBoxedIntField(sb, "fontSizeHwpunits", style.fontSizeHwpunits(), first);
        }
        first = writeStringField(sb, "textColor", style.textColor(), first);
        if (style.letterSpacing() != null) {
            first = writeBoxedShortField(sb, "letterSpacing", style.letterSpacing(), first);
        }
        if (style.bold() != null && style.bold()) {
            first = writeBooleanField(sb, "bold", true, first);
        }
        if (style.italic() != null && style.italic()) {
            first = writeBooleanField(sb, "italic", true, first);
        }
        if (style.underline() != null && style.underline()) {
            first = writeBooleanField(sb, "underline", true, first);
        }
        if (style.strikeThrough() != null && style.strikeThrough()) {
            first = writeBooleanField(sb, "strikeThrough", true, first);
        }
        if (style.dropCapLines() != null && style.dropCapLines() > 0) {
            first = writeBoxedIntField(sb, "dropCapLines", style.dropCapLines(), first);
            if (style.dropCapCharacters() != null) {
                first = writeBoxedIntField(sb, "dropCapCharacters", style.dropCapCharacters(), first);
            }
        }
        if (style.horizontalScale() != null) {
            first = writeBoxedShortField(sb, "horizontalScale", style.horizontalScale(), first);
        }
        if (style.wordSpacing() != null) {
            first = writeBoxedDoubleField(sb, "wordSpacing", style.wordSpacing(), first);
        }
        if (style.autoLeading() != null) {
            first = writeBoxedDoubleField(sb, "autoLeading", style.autoLeading(), first);
        }
        first = writeStringField(sb, "lineSpacingType", style.lineSpacingType(), first);
        if (style.hasTabStops()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"tabStops\":[");
            for (int i = 0; i < style.tabStops().size(); i++) {
                if (i > 0) sb.append(',');
                ASTTabStop ts = style.tabStops().get(i);
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
            sb.append(']');
        }
        sb.append('}');
    }

    // ─── Field writers ────────────────────────────────────────────

    private static boolean writeStringField(StringBuilder sb, String key, String value, boolean first) {
        if (value == null) return first;
        if (!first) sb.append(',');
        sb.append('"').append(key).append("\":\"").append(escapeJson(value)).append('"');
        return false;
    }

    /**
     * SPEC-015: DebugMeta를 {@code "debug": { ... }} 객체로 직렬화. 비어있으면 생략.
     */
    private static boolean writeDebugMeta(StringBuilder sb, DebugMeta dm, boolean first) {
        if (dm == null || dm.isEmpty()) return first;
        if (!first) sb.append(',');
        sb.append("\"debug\":{");
        boolean inner = true;
        inner = writeStringField(sb, "createdAt", dm.createdAt, inner);
        inner = writeStringField(sb, "sourceId", dm.sourceId, inner);
        if (dm.appliedFrom != null && !dm.appliedFrom.isEmpty()) {
            if (!inner) sb.append(',');
            sb.append("\"appliedFrom\":{");
            boolean afFirst = true;
            for (java.util.Map.Entry<String, String> e : dm.appliedFrom.entrySet()) {
                if (!afFirst) sb.append(',');
                sb.append('"').append(escapeJson(e.getKey())).append("\":\"")
                        .append(escapeJson(String.valueOf(e.getValue()))).append('"');
                afFirst = false;
            }
            sb.append('}');
            inner = false;
        }
        if (dm.notes != null && !dm.notes.isEmpty()) {
            if (!inner) sb.append(',');
            sb.append("\"notes\":[");
            for (int i = 0; i < dm.notes.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(escapeJson(dm.notes.get(i))).append('"');
            }
            sb.append(']');
            inner = false;
        }
        sb.append('}');
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

    // ─── JSON string escaping ─────────────────────────────────────

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
