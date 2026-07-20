package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;

/**
 * JSON 문자열 → ASTDocument 역변환.
 * Gson의 JsonParser를 사용한 수동 필드 매핑.
 *
 * bundlePath 필드는 파싱되지만 imageData/pngData는 null로 남음.
 * (ASTBundleReader에서 이미지 로딩 담당)
 */
public class ASTDeserializer {

    public static ASTDocument fromJson(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        ASTDocument doc = new ASTDocument();

        doc.sourceFile(getString(root, "sourceFile"));
        if (root.has("sourceFormat")) {
            doc.sourceFormat(getString(root, "sourceFormat"));
        }

        // stories
        if (root.has("stories")) {
            for (JsonElement e : root.getAsJsonArray("stories")) {
                doc.addStory(parseStory(e.getAsJsonObject()));
            }
        }

        // sections
        if (root.has("sections")) {
            for (JsonElement e : root.getAsJsonArray("sections")) {
                doc.addSection(parseSection(e.getAsJsonObject()));
            }
        }

        // backgrounds
        if (root.has("backgrounds")) {
            for (JsonElement e : root.getAsJsonArray("backgrounds")) {
                doc.addBackground(parseBackground(e.getAsJsonObject()));
            }
        }

        // fonts
        if (root.has("fonts")) {
            for (JsonElement e : root.getAsJsonArray("fonts")) {
                doc.addFont(parseFontDef(e.getAsJsonObject()));
            }
        }

        // paragraphStyles
        if (root.has("paragraphStyles")) {
            for (JsonElement e : root.getAsJsonArray("paragraphStyles")) {
                doc.addParagraphStyle(parseStyleDef(e.getAsJsonObject()));
            }
        }

        // characterStyles
        if (root.has("characterStyles")) {
            for (JsonElement e : root.getAsJsonArray("characterStyles")) {
                doc.addCharacterStyle(parseStyleDef(e.getAsJsonObject()));
            }
        }

        // colors
        if (root.has("colors")) {
            JsonObject colors = root.getAsJsonObject("colors");
            for (Map.Entry<String, JsonElement> entry : colors.entrySet()) {
                doc.putColor(entry.getKey(), entry.getValue().getAsString());
            }
        }

        return doc;
    }

    // ─── Story ───────────────────────────────────────────────────

    private static ASTStory parseStory(JsonObject o) {
        ASTStory s = new ASTStory();
        s.storyId(getString(o, "storyId"));
        s.orientation(getString(o, "orientation"));
        s.paragraphCount(getInt(o, "paragraphCount"));
        s.tableCount(getInt(o, "tableCount"));

        if (o.has("linkedFrameIds")) {
            java.util.List<String> ids = new java.util.ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("linkedFrameIds")) {
                ids.add(e.getAsString());
            }
            s.linkedFrameIds(ids);
        }

        if (o.has("pages")) {
            java.util.List<Integer> pages = new java.util.ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("pages")) {
                pages.add(e.getAsInt());
            }
            s.pages(pages);
        }

        return s;
    }

    // ─── Section / Layout ────────────────────────────────────────

    private static ASTSection parseSection(JsonObject o) {
        ASTSection sec = new ASTSection();
        sec.pageNumber(getInt(o, "pageNumber"));

        if (o.has("layout")) {
            sec.layout(parsePageLayout(o.getAsJsonObject("layout")));
        }

        if (o.has("blocks")) {
            for (JsonElement e : o.getAsJsonArray("blocks")) {
                ASTBlock block = parseBlock(e.getAsJsonObject());
                if (block != null) sec.addBlock(block);
            }
        }

        return sec;
    }

    private static ASTPageLayout parsePageLayout(JsonObject o) {
        ASTPageLayout layout = new ASTPageLayout();
        layout.pageWidth(getLong(o, "pageWidth"));
        layout.pageHeight(getLong(o, "pageHeight"));
        layout.marginTop(getLong(o, "marginTop"));
        layout.marginBottom(getLong(o, "marginBottom"));
        layout.marginLeft(getLong(o, "marginLeft"));
        layout.marginRight(getLong(o, "marginRight"));
        layout.columnCount(getInt(o, "columnCount"));
        layout.columnGutter(getLong(o, "columnGutter"));
        return layout;
    }

    // ─── Block dispatch ──────────────────────────────────────────

    private static ASTBlock parseBlock(JsonObject o) {
        String type = getString(o, "blockType");
        if (type == null) return null;

        switch (type) {
            case "TEXT_FRAME_BLOCK":
                return parseTextFrameBlock(o);
            case "TABLE":
                return parseTable(o);
            case "FIGURE":
                return parseFigure(o);
            default:
                return null;
        }
    }

    // ─── TextFrameBlock ──────────────────────────────────────────

    private static ASTTextFrameBlock parseTextFrameBlock(JsonObject o) {
        ASTTextFrameBlock tf = new ASTTextFrameBlock();
        tf.sourceId(getString(o, "sourceId"));
        tf.storyId(getString(o, "storyId"));
        tf.x(getLong(o, "x"));
        tf.y(getLong(o, "y"));
        tf.width(getLong(o, "width"));
        tf.height(getLong(o, "height"));
        tf.zOrder(getInt(o, "zOrder"));
        tf.columnCount(getInt(o, "columnCount"));
        tf.columnGutter(getLong(o, "columnGutter"));
        tf.columnWidths(getLongArray(o, "columnWidths"));
        tf.verticalText(getBool(o, "verticalText"));
        tf.verticalJustification(getString(o, "verticalJustification"));
        tf.insetTop(getLong(o, "insetTop"));
        tf.insetLeft(getLong(o, "insetLeft"));
        tf.insetBottom(getLong(o, "insetBottom"));
        tf.insetRight(getLong(o, "insetRight"));
        tf.fillColor(getString(o, "fillColor"));
        tf.strokeColor(getString(o, "strokeColor"));
        tf.strokeWeight(getDouble(o, "strokeWeight"));
        tf.cornerRadius(getDouble(o, "cornerRadius"));
        if (o.has("strokeType")) tf.strokeType(getString(o, "strokeType"));
        if (o.has("fillTint")) tf.fillTint(getDouble(o, "fillTint"));
        if (o.has("strokeTint")) tf.strokeTint(getDouble(o, "strokeTint"));
        tf.fromGroup(getBool(o, "fromGroup"));
        tf.noAutoLineWrap(getBool(o, "noAutoLineWrap"));
        tf.plannedVisualTextOverlay(getBool(o, "plannedVisualTextOverlay"));
        tf.anchoredFlowWithText(getBool(o, "anchoredFlowWithText"));

        if (o.has("paragraphs")) {
            for (JsonElement e : o.getAsJsonArray("paragraphs")) {
                tf.addParagraph(parseParagraph(e.getAsJsonObject()));
            }
        }

        return tf;
    }

    // ─── Table ───────────────────────────────────────────────────

    private static ASTTable parseTable(JsonObject o) {
        ASTTable table = new ASTTable();
        table.sourceId(getString(o, "sourceId"));
        table.x(getLong(o, "x"));
        table.y(getLong(o, "y"));
        table.width(getLong(o, "width"));
        table.height(getLong(o, "height"));
        table.zOrder(getInt(o, "zOrder"));
        table.flowWithText(getBool(o, "flowWithText"));
        table.anchoredFlowWithText(getBool(o, "anchoredFlowWithText"));
        table.fixedOuterBounds(getBool(o, "fixedOuterBounds"));
        table.rowCount(getInt(o, "rowCount"));
        table.colCount(getInt(o, "colCount"));
        table.appliedTableStyle(getString(o, "appliedTableStyle"));
        table.borderColor(getString(o, "borderColor"));
        table.borderWidth(getLong(o, "borderWidth"));

        if (o.has("columnWidths")) {
            for (JsonElement e : o.getAsJsonArray("columnWidths")) {
                table.addColumnWidth(e.getAsLong());
            }
        }

        if (o.has("rows")) {
            for (JsonElement e : o.getAsJsonArray("rows")) {
                table.addRow(parseTableRow(e.getAsJsonObject()));
            }
        }

        return table;
    }

    private static ASTTableRow parseTableRow(JsonObject o) {
        ASTTableRow row = new ASTTableRow();
        row.rowIndex(getInt(o, "rowIndex"));
        row.rowHeight(getLong(o, "rowHeight"));
        row.autoGrow(getBool(o, "autoGrow"));

        if (o.has("cells")) {
            for (JsonElement e : o.getAsJsonArray("cells")) {
                row.addCell(parseTableCell(e.getAsJsonObject()));
            }
        }

        return row;
    }

    private static ASTTableCell parseTableCell(JsonObject o) {
        ASTTableCell cell = new ASTTableCell();
        cell.rowIndex(getInt(o, "rowIndex"));
        cell.columnIndex(getInt(o, "columnIndex"));
        if (o.has("rowSpan")) cell.rowSpan(getInt(o, "rowSpan"));
        if (o.has("columnSpan")) cell.columnSpan(getInt(o, "columnSpan"));
        cell.width(getLong(o, "width"));
        cell.height(getLong(o, "height"));
        cell.fillColor(getString(o, "fillColor"));
        cell.verticalAlign(getString(o, "verticalAlign"));
        cell.firstBaselineOffset(getString(o, "firstBaselineOffset"));
        cell.minimumFirstBaselineOffset(getLong(o, "minimumFirstBaselineOffset"));
        cell.squeezeLineWrap(getBool(o, "squeezeLineWrap"));
        cell.marginTop(getLong(o, "marginTop"));
        cell.marginBottom(getLong(o, "marginBottom"));
        cell.marginLeft(getLong(o, "marginLeft"));
        cell.marginRight(getLong(o, "marginRight"));

        if (o.has("topBorder")) cell.topBorder(parseCellBorder(o.getAsJsonObject("topBorder")));
        if (o.has("bottomBorder")) cell.bottomBorder(parseCellBorder(o.getAsJsonObject("bottomBorder")));
        if (o.has("leftBorder")) cell.leftBorder(parseCellBorder(o.getAsJsonObject("leftBorder")));
        if (o.has("rightBorder")) cell.rightBorder(parseCellBorder(o.getAsJsonObject("rightBorder")));

        cell.topLeftDiagonalLine(getBool(o, "topLeftDiagonalLine"));
        cell.topRightDiagonalLine(getBool(o, "topRightDiagonalLine"));
        if (o.has("diagonalBorder")) cell.diagonalBorder(parseCellBorder(o.getAsJsonObject("diagonalBorder")));

        if (o.has("paragraphs")) {
            for (JsonElement e : o.getAsJsonArray("paragraphs")) {
                cell.addParagraph(parseParagraph(e.getAsJsonObject()));
            }
        }

        return cell;
    }

    private static ASTTableCell.CellBorder parseCellBorder(JsonObject o) {
        ASTTableCell.CellBorder border = new ASTTableCell.CellBorder();
        border.color(getString(o, "color"));
        border.weight(getDouble(o, "weight"));
        border.strokeType(getString(o, "strokeType"));
        if (o.has("tint")) border.tint(getDouble(o, "tint"));
        return border;
    }

    // ─── Figure ──────────────────────────────────────────────────

    private static ASTFigure parseFigure(JsonObject o) {
        ASTFigure fig = new ASTFigure();
        fig.sourceId(getString(o, "sourceId"));
        if (o.has("kind")) {
            fig.kind(ASTFigure.FigureKind.valueOf(getString(o, "kind")));
        }
        fig.x(getLong(o, "x"));
        fig.y(getLong(o, "y"));
        fig.width(getLong(o, "width"));
        fig.height(getLong(o, "height"));
        fig.zOrder(getInt(o, "zOrder"));
        fig.rotationAngle(getDouble(o, "rotationAngle"));
        fig.imageFormat(getString(o, "imageFormat"));
        fig.imagePath(getString(o, "imagePath"));
        fig.pixelWidth(getInt(o, "pixelWidth"));
        fig.pixelHeight(getInt(o, "pixelHeight"));
        fig.cropLeftFraction(getDouble(o, "cropLeftFraction"));
        fig.cropTopFraction(getDouble(o, "cropTopFraction"));
        fig.cropRightFraction(getDouble(o, "cropRightFraction"));
        fig.cropBottomFraction(getDouble(o, "cropBottomFraction"));
        fig.flipHorizontal(getBool(o, "flipHorizontal"));
        fig.flipVertical(getBool(o, "flipVertical"));
        fig.bundlePath(getString(o, "bundlePath"));
        fig.visualLayer(getString(o, "visualLayer"));
        if (o.has("sourceLayerIndex") && !o.get("sourceLayerIndex").isJsonNull()) {
            fig.sourceLayerIndex(getInt(o, "sourceLayerIndex"));
        }
        return fig;
    }

    // ─── Paragraph ───────────────────────────────────────────────

    private static ASTParagraph parseParagraph(JsonObject o) {
        ASTParagraph para = new ASTParagraph();
        para.paragraphStyleRef(getString(o, "paragraphStyleRef"));
        para.alignment(getString(o, "alignment"));
        para.firstLineIndent(getBoxedLong(o, "firstLineIndent"));
        para.leftMargin(getBoxedLong(o, "leftMargin"));
        para.rightMargin(getBoxedLong(o, "rightMargin"));
        para.spaceBefore(getBoxedLong(o, "spaceBefore"));
        para.spaceAfter(getBoxedLong(o, "spaceAfter"));
        para.lineSpacing(getBoxedInt(o, "lineSpacing"));
        para.lineSpacingType(getString(o, "lineSpacingType"));
        para.autoLeadingPercent(getBoxedInt(o, "autoLeadingPercent"));
        para.letterSpacing(getBoxedShort(o, "letterSpacing"));
        para.squeezeLineWrap(getBool(o, "squeezeLineWrap"));
        para.keepLineWrap(getBool(o, "keepLineWrap"));
        para.sourceTextWrapSpacing(getBool(o, "sourceTextWrapSpacing"));

        // shading
        para.shadingOn(getBool(o, "shadingOn"));
        para.shadingColor(getString(o, "shadingColor"));
        para.shadingTint(getBoxedDouble(o, "shadingTint"));
        para.shadingLeftOffset(getBoxedLong(o, "shadingLeftOffset"));
        para.shadingRightOffset(getBoxedLong(o, "shadingRightOffset"));
        para.shadingTopOffset(getBoxedLong(o, "shadingTopOffset"));
        para.shadingBottomOffset(getBoxedLong(o, "shadingBottomOffset"));

        // tabStops
        if (o.has("tabStops")) {
            for (JsonElement e : o.getAsJsonArray("tabStops")) {
                JsonObject tso = e.getAsJsonObject();
                ASTTabStop ts = new ASTTabStop();
                ts.position(getLong(tso, "position"));
                ts.alignment(getString(tso, "alignment"));
                ts.leader(getString(tso, "leader"));
                para.addTabStop(ts);
            }
        }

        // items
        if (o.has("items")) {
            for (JsonElement e : o.getAsJsonArray("items")) {
                ASTInlineItem item = parseInlineItem(e.getAsJsonObject());
                if (item != null) para.addItem(item);
            }
        }

        return para;
    }

    // ─── Inline item dispatch ────────────────────────────────────

    private static ASTInlineItem parseInlineItem(JsonObject o) {
        String type = getString(o, "itemType");
        if (type == null) return null;

        switch (type) {
            case "TEXT_RUN":
                return parseTextRun(o);
            case "INLINE_OBJECT":
                return parseInlineObject(o);
            case "EQUATION":
                return parseEquation(o);
            case "BREAK":
                return parseBreak(o);
            default:
                return null;
        }
    }

    private static ASTTextRun parseTextRun(JsonObject o) {
        ASTTextRun run = new ASTTextRun();
        run.text(getString(o, "text"));
        run.characterStyleRef(getString(o, "characterStyleRef"));
        run.fontFamily(getString(o, "fontFamily"));
        run.fontStyle(getString(o, "fontStyle"));
        run.fontSizeHwpunits(getBoxedInt(o, "fontSizeHwpunits"));
        run.textColor(getString(o, "textColor"));
        run.shadeColor(getString(o, "shadeColor"));
        run.letterSpacing(getBoxedShort(o, "letterSpacing"));
        run.subscript(getBool(o, "subscript"));
        run.superscript(getBool(o, "superscript"));
        run.underline(getBool(o, "underline"));
        run.strikeThrough(getBool(o, "strikeThrough"));
        run.horizontalScale(getBoxedShort(o, "horizontalScale"));
        run.grepMathFont(getBool(o, "grepMathFont"));
        return run;
    }

    private static ASTInlineObject parseInlineObject(JsonObject o) {
        ASTInlineObject obj = new ASTInlineObject();
        if (o.has("kind")) {
            obj.kind(ASTInlineObject.ObjectKind.valueOf(getString(o, "kind")));
        }
        obj.sourceId(getString(o, "sourceId"));
        obj.width(getLong(o, "width"));
        obj.height(getLong(o, "height"));
        obj.imageFormat(getString(o, "imageFormat"));
        obj.imagePath(getString(o, "imagePath"));
        obj.pixelWidth(getInt(o, "pixelWidth"));
        obj.pixelHeight(getInt(o, "pixelHeight"));
        obj.anchoredPosition(getString(o, "anchoredPosition"));
        obj.textWrapMode(getString(o, "textWrapMode"));
        obj.textWrapSide(getString(o, "textWrapSide"));
        if (o.has("affectsLineSpacing")) {
            obj.affectsLineSpacing(getBool(o, "affectsLineSpacing"));
        }
        obj.textWrapTop(getLong(o, "textWrapTop"));
        obj.textWrapLeft(getLong(o, "textWrapLeft"));
        obj.textWrapBottom(getLong(o, "textWrapBottom"));
        obj.textWrapRight(getLong(o, "textWrapRight"));

        obj.fillColor(getString(o, "fillColor"));
        if (o.has("fillTint")) obj.fillTint(getDouble(o, "fillTint"));
        obj.strokeColor(getString(o, "strokeColor"));
        obj.strokeWeight(getDouble(o, "strokeWeight"));
        if (o.has("strokeTint")) obj.strokeTint(getDouble(o, "strokeTint"));
        obj.cornerRadius(getDouble(o, "cornerRadius"));
        if (o.has("shellShapeType")) obj.shellShapeType(getString(o, "shellShapeType"));
        obj.noAutoLineWrap(getBool(o, "noAutoLineWrap"));

        obj.textMarginTop(getLong(o, "textMarginTop"));
        obj.textMarginLeft(getLong(o, "textMarginLeft"));
        obj.textMarginBottom(getLong(o, "textMarginBottom"));
        obj.textMarginRight(getLong(o, "textMarginRight"));

        obj.isOverlay(getBool(o, "isOverlay"));
        obj.overlayX(getLong(o, "overlayX"));
        obj.overlayY(getLong(o, "overlayY"));
        obj.overlayParentWidth(getLong(o, "overlayParentWidth"));
        obj.overlayParentHeight(getLong(o, "overlayParentHeight"));

        obj.containerWidth(getLong(o, "containerWidth"));
        obj.containerHeight(getLong(o, "containerHeight"));
        obj.imageOffsetX(getLong(o, "imageOffsetX"));
        obj.imageOffsetY(getLong(o, "imageOffsetY"));

        // overlayFrames (재귀)
        if (o.has("overlayFrames")) {
            for (JsonElement e : o.getAsJsonArray("overlayFrames")) {
                obj.addOverlayFrame(parseInlineObject(e.getAsJsonObject()));
            }
        }

        // paragraphs
        if (o.has("paragraphs")) {
            for (JsonElement e : o.getAsJsonArray("paragraphs")) {
                obj.addParagraph(parseParagraph(e.getAsJsonObject()));
            }
        }

        // inlineTables
        if (o.has("inlineTables")) {
            for (JsonElement e : o.getAsJsonArray("inlineTables")) {
                obj.addInlineTable(parseTable(e.getAsJsonObject()));
            }
        }

        obj.bundlePath(getString(o, "bundlePath"));
        return obj;
    }

    private static ASTEquation parseEquation(JsonObject o) {
        ASTEquation eq = new ASTEquation();
        eq.hwpScript(getString(o, "hwpScript"));
        eq.sourceType(getString(o, "sourceType"));
        eq.textColor(getString(o, "textColor"));
        eq.preferredBaseUnit(getBoxedInt(o, "preferredBaseUnit"));
        eq.preferredFontFamily(getString(o, "preferredFontFamily"));
        return eq;
    }

    private static ASTBreak parseBreak(JsonObject o) {
        ASTBreak brk = new ASTBreak();
        if (o.has("breakType")) {
            brk.breakType(ASTBreak.BreakType.valueOf(getString(o, "breakType")));
        }
        return brk;
    }

    // ─── Background ──────────────────────────────────────────────

    private static ASTPageBackground parseBackground(JsonObject o) {
        ASTPageBackground bg = new ASTPageBackground();
        bg.pageNumber(getInt(o, "pageNumber"));
        bg.pageWidth(getLong(o, "pageWidth"));
        bg.pageHeight(getLong(o, "pageHeight"));
        bg.pixelWidth(getInt(o, "pixelWidth"));
        bg.pixelHeight(getInt(o, "pixelHeight"));
        bg.bundlePath(getString(o, "bundlePath"));
        return bg;
    }

    // ─── FontDef ─────────────────────────────────────────────────

    private static ASTFontDef parseFontDef(JsonObject o) {
        ASTFontDef font = new ASTFontDef();
        font.fontId(getString(o, "fontId"));
        font.fontFamily(getString(o, "fontFamily"));
        font.fontType(getString(o, "fontType"));
        return font;
    }

    // ─── StyleDef ────────────────────────────────────────────────

    private static ASTStyleDef parseStyleDef(JsonObject o) {
        ASTStyleDef style = new ASTStyleDef();
        style.styleId(getString(o, "styleId"));
        style.styleName(getString(o, "styleName"));
        style.basedOnStyleRef(getString(o, "basedOnStyleRef"));
        style.alignment(getString(o, "alignment"));
        style.firstLineIndent(getBoxedLong(o, "firstLineIndent"));
        style.leftMargin(getBoxedLong(o, "leftMargin"));
        style.rightMargin(getBoxedLong(o, "rightMargin"));
        style.spaceBefore(getBoxedLong(o, "spaceBefore"));
        style.spaceAfter(getBoxedLong(o, "spaceAfter"));
        style.lineSpacing(getBoxedInt(o, "lineSpacing"));
        style.lineSpacingType(getString(o, "lineSpacingType"));
        style.fontFamily(getString(o, "fontFamily"));
        style.fontStyle(getString(o, "fontStyle"));
        style.fontSizeHwpunits(getBoxedInt(o, "fontSizeHwpunits"));
        style.textColor(getString(o, "textColor"));
        style.letterSpacing(getBoxedShort(o, "letterSpacing"));
        if (o.has("bold")) style.bold(getBool(o, "bold"));
        if (o.has("italic")) style.italic(getBool(o, "italic"));
        style.horizontalScale(getBoxedShort(o, "horizontalScale"));
        style.wordSpacing(getBoxedDouble(o, "wordSpacing"));
        style.autoLeading(getBoxedDouble(o, "autoLeading"));
        if (o.has("underline")) style.underline(getBool(o, "underline"));
        if (o.has("strikeThrough")) style.strikeThrough(getBool(o, "strikeThrough"));
        style.dropCapLines(getBoxedInt(o, "dropCapLines"));
        style.dropCapCharacters(getBoxedInt(o, "dropCapCharacters"));

        if (o.has("tabStops")) {
            java.util.List<ASTTabStop> tabs = new java.util.ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("tabStops")) {
                JsonObject tso = e.getAsJsonObject();
                ASTTabStop ts = new ASTTabStop();
                ts.position(getLong(tso, "position"));
                ts.alignment(getString(tso, "alignment"));
                ts.leader(getString(tso, "leader"));
                tabs.add(ts);
            }
            style.tabStops(tabs);
        }

        return style;
    }

    // ─── JSON field helpers ──────────────────────────────────────

    private static String getString(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return null;
        return o.get(key).getAsString();
    }

    private static int getInt(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return 0;
        return o.get(key).getAsInt();
    }

    private static long getLong(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return 0;
        return o.get(key).getAsLong();
    }

    private static double getDouble(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return 0.0;
        return o.get(key).getAsDouble();
    }

    private static boolean getBool(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return false;
        return o.get(key).getAsBoolean();
    }

    private static long[] getLongArray(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull() || !o.get(key).isJsonArray()) return null;
        com.google.gson.JsonArray arr = o.getAsJsonArray(key);
        long[] result = new long[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            result[i] = arr.get(i).getAsLong();
        }
        return result;
    }

    private static Integer getBoxedInt(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return null;
        return o.get(key).getAsInt();
    }

    private static Long getBoxedLong(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return null;
        return o.get(key).getAsLong();
    }

    private static Short getBoxedShort(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return null;
        return o.get(key).getAsShort();
    }

    private static Double getBoxedDouble(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return null;
        return o.get(key).getAsDouble();
    }
}
