package kr.dogfoot.hwpxlib.tool.idmlconverter.resolved;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import java.io.*;
import java.util.Set;

/**
 * resolved.json 파서.
 * Gson JsonParser 기반 수동 필드 매핑 (ASTDeserializer 패턴).
 */
public class ResolvedDataReader {

    /**
     * resolved.json 파일을 읽어 ResolvedData로 변환한다.
     */
    public static ResolvedData read(String filePath) throws IOException {
        // 파일을 직접 스트리밍 파싱 (String 중간 변환 없이 메모리 절감)
        JsonReader reader = new JsonReader(new BufferedReader(new InputStreamReader(
                new FileInputStream(filePath), "UTF-8"), 65536));
        try {
            reader.setLenient(true);
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            return fromJsonObject(root);
        } finally {
            reader.close();
        }
    }

    public static ResolvedData fromJson(String json) {
        // ExtendScript JSON 폴리필이 제어 문자를 이스케이프하지 못할 수 있으므로 lenient 모드 사용
        JsonReader reader = new JsonReader(new StringReader(json));
        try {
        reader.setLenient(true);
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        return fromJsonObject(root);
        } finally {
            try { reader.close(); } catch (IOException e) {
                System.err.println("[ResolvedDataReader] JSON reader close 실패: " + e.getMessage());
            }
        }
    }

    private static ResolvedData fromJsonObject(JsonObject root) {
        ResolvedData data = new ResolvedData();

        // colors → colorHexMap
        if (root.has("colors")) {
            for (JsonElement e : root.getAsJsonArray("colors")) {
                JsonObject c = e.getAsJsonObject();
                String name = getString(c, "name");
                String hex = getString(c, "hex");
                if (name != null && hex != null) {
                    data.addColor(name, hex);
                }
            }
        }

        // stories (+ tables within stories)
        if (root.has("stories")) {
            for (JsonElement e : root.getAsJsonArray("stories")) {
                JsonObject storyObj = e.getAsJsonObject();
                data.addStory(parseStory(storyObj));
                // tables within story
                if (storyObj.has("tables")) {
                    for (JsonElement te : storyObj.getAsJsonArray("tables")) {
                        data.addTable(parseTable(te.getAsJsonObject()));
                    }
                }
            }
        }

        // textFrames
        if (root.has("textFrames")) {
            for (JsonElement e : root.getAsJsonArray("textFrames")) {
                data.addTextFrame(parseTextFrame(e.getAsJsonObject()));
            }
        }

        // pages
        if (root.has("pages")) {
            for (JsonElement e : root.getAsJsonArray("pages")) {
                data.addPage(parsePage(e.getAsJsonObject()));
            }
        }

        // pageItems
        if (root.has("pageItems")) {
            for (JsonElement e : root.getAsJsonArray("pageItems")) {
                data.addPageItem(parsePageItem(e.getAsJsonObject()));
            }
        }

        // renderedTextFrames
        if (root.has("renderedTextFrames")) {
            for (JsonElement e : root.getAsJsonArray("renderedTextFrames")) {
                RenderedGroup rg = parseRenderedGroup(e.getAsJsonObject());
                // badge_group_child는 부모 badge_group에서 처리하므로 개별 등록 불필요
                if (rg.isBadgeGroupChild()) continue;
                data.addRenderedTextFrame(rg);
            }
        }

        // renderedPdfFrames (PDF 배치 프레임을 InDesign에서 직접 래스터화한 PNG)
        if (root.has("renderedPdfFrames")) {
            for (JsonElement e : root.getAsJsonArray("renderedPdfFrames")) {
                RenderedGroup rg = parseRenderedGroup(e.getAsJsonObject());
                data.addRenderedPdfFrame(rg);
            }
        }

        // renderedGraphicFrames (복합 장식 그래픽을 InDesign에서 직접 래스터화한 PNG)
        if (root.has("renderedGraphicFrames")) {
            for (JsonElement e : root.getAsJsonArray("renderedGraphicFrames")) {
                RenderedGroup rg = parseRenderedGroup(e.getAsJsonObject());
                data.addRenderedGraphicFrame(rg);
            }
        }

        // renderedImageFrames (이미지 배치 프레임을 InDesign에서 직접 래스터화한 PNG)
        if (root.has("renderedImageFrames")) {
            for (JsonElement e : root.getAsJsonArray("renderedImageFrames")) {
                RenderedGroup rg = parseRenderedGroup(e.getAsJsonObject());
                data.addRenderedImageFrame(rg);
            }
        }

        // renderedFloatingItems (통합 플로팅 그래픽 렌더링)
        if (root.has("renderedFloatingItems")) {
            for (JsonElement e : root.getAsJsonArray("renderedFloatingItems")) {
                RenderedGroup rg = parseRenderedGroup(e.getAsJsonObject());
                JsonObject obj = e.getAsJsonObject();
                if (obj.has("zOrder")) rg.zOrder(obj.get("zOrder").getAsInt());
                if (obj.has("type")) rg.itemType(obj.get("type").getAsString());
                data.addRenderedFloatingItem(rg);
            }
        }

        // editableTextFrameIds (배경에서 숨겨진 = 글상자 배치 대상)
        if (root.has("editableTextFrameIds") && !root.get("editableTextFrameIds").isJsonNull()) {
            JsonArray arr = root.getAsJsonArray("editableTextFrameIds");
            Set<String> ids = new java.util.HashSet<>();
            for (int i = 0; i < arr.size(); i++) {
                ids.add(String.valueOf(arr.get(i).getAsInt()));
            }
            data.editableTextFrameIds(ids);
        }

        // paragraphStyles (top-level 단락 스타일 정의 — justification 등)
        if (root.has("paragraphStyles")) {
            for (JsonElement e : root.getAsJsonArray("paragraphStyles")) {
                JsonObject ps = e.getAsJsonObject();
                String name = getString(ps, "name");
                String just = getString(ps, "justification");
                if (name != null && just != null) {
                    data.addParagraphStyleJustification(name, just);
                }
            }
        }

        // fontMetrics (InDesign에서 측정한 폰트 메트릭)
        if (root.has("fontMetrics")) {
            for (JsonElement e : root.getAsJsonArray("fontMetrics")) {
                FontMetricEntry fm = parseFontMetric(e.getAsJsonObject());
                if (fm != null) data.addFontMetric(fm);
            }
        }

        return data;
    }

    private static ResolvedTextFrame parseTextFrame(JsonObject o) {
        ResolvedTextFrame tf = new ResolvedTextFrame();
        tf.id(getString(o, "id"));
        tf.storyId(getString(o, "storyId"));
        tf.paragraphStart(getInt(o, "paragraphStart", -1));
        tf.paragraphEnd(getInt(o, "paragraphEnd", -1));
        tf.lineCount(getInt(o, "lineCount", 0));
        tf.overflows(getBool(o, "overflows", false));

        if (o.has("paragraphYOffsets")) {
            JsonArray arr = o.getAsJsonArray("paragraphYOffsets");
            double[] offsets = new double[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                offsets[i] = arr.get(i).getAsDouble();
            }
            tf.paragraphYOffsets(offsets);
        }

        // Phase 3: 프레임 메타데이터
        if (o.has("geometricBounds")) {
            tf.geometricBounds(parseDoubleArray(o.getAsJsonArray("geometricBounds")));
        }
        tf.columnCount(getInt(o, "columnCount", 1));
        tf.columnGutter(getDouble(o, "columnGutter", 0));
        if (o.has("insetSpacing")) {
            tf.insetSpacing(parseDoubleArray(o.getAsJsonArray("insetSpacing")));
        }
        tf.verticalJustification(getString(o, "verticalJustification"));
        tf.rotationAngle(getDouble(o, "rotationAngle", 0));

        // IDML-Free 파이프라인 보강 필드
        tf.previousFrameId(getString(o, "previousFrameId"));
        tf.nextFrameId(getString(o, "nextFrameId"));
        tf.isInline(getBool(o, "isInline", false));
        tf.pageIndex(getInt(o, "pageIndex", -1));
        tf.zOrder(getInt(o, "zOrder", 0));
        if (o.has("pageRelativeBounds") && !o.get("pageRelativeBounds").isJsonNull()) {
            tf.pageRelativeBounds(parseDoubleArray(o.getAsJsonArray("pageRelativeBounds")));
        }
        tf.fillColor(getString(o, "fillColor"));
        tf.fillTint(getDouble(o, "fillTint", 100));
        tf.strokeColor(getString(o, "strokeColor"));
        tf.strokeWeight(getDouble(o, "strokeWeight", 0));
        tf.opacity(getDouble(o, "opacity", 100));
        tf.cornerRadius(getDouble(o, "cornerRadius", 0));

        // frameParaTexts: 프레임에 보이는 각 단락의 실제 텍스트
        if (o.has("frameParaTexts") && !o.get("frameParaTexts").isJsonNull()) {
            JsonArray fptArr = o.getAsJsonArray("frameParaTexts");
            java.util.List<String> texts = new java.util.ArrayList<>();
            for (int i = 0; i < fptArr.size(); i++) {
                texts.add(fptArr.get(i).isJsonNull() ? "" : fptArr.get(i).getAsString());
            }
            tf.frameParaTexts(texts);
        }

        // frameVisibleText: 프레임에 실제 보이는 전체 텍스트 (오버플로우 제외)
        if (o.has("frameVisibleText") && !o.get("frameVisibleText").isJsonNull()) {
            tf.frameVisibleText(o.get("frameVisibleText").getAsString());
        }

        // composedLines: 조판 결과 (Phase 4)
        if (o.has("composedLines") && !o.get("composedLines").isJsonNull()) {
            JsonArray clArr = o.getAsJsonArray("composedLines");
            java.util.List<ResolvedTextFrame.ComposedLine> lines = new java.util.ArrayList<>();
            for (int i = 0; i < clArr.size(); i++) {
                JsonObject clObj = clArr.get(i).getAsJsonObject();
                ResolvedTextFrame.ComposedLine cl = new ResolvedTextFrame.ComposedLine();
                if (clObj.has("bounds") && !clObj.get("bounds").isJsonNull()) {
                    JsonArray ba = clObj.getAsJsonArray("bounds");
                    cl.bounds(new double[]{ba.get(0).getAsDouble(), ba.get(1).getAsDouble(),
                            ba.get(2).getAsDouble(), ba.get(3).getAsDouble()});
                }
                cl.text(getString(clObj, "text"));
                cl.paraIndex(getInt(clObj, "paraIndex", 0));
                if (clObj.has("runs") && !clObj.get("runs").isJsonNull()) {
                    JsonArray runsArr = clObj.getAsJsonArray("runs");
                    java.util.List<ResolvedTextFrame.ComposedRun> runs = new java.util.ArrayList<>();
                    for (int j = 0; j < runsArr.size(); j++) {
                        JsonObject rObj = runsArr.get(j).getAsJsonObject();
                        ResolvedTextFrame.ComposedRun cr = new ResolvedTextFrame.ComposedRun();
                        cr.text(getString(rObj, "text"));
                        cr.fillColor(getString(rObj, "fillColor"));
                        cr.fontSize(rObj.has("fontSize") && !rObj.get("fontSize").isJsonNull()
                                ? rObj.get("fontSize").getAsDouble() : null);
                        cr.fontFamily(getString(rObj, "fontFamily"));
                        cr.fontStyle(getString(rObj, "fontStyle"));
                        runs.add(cr);
                    }
                    cl.runs(runs);
                }
                lines.add(cl);
            }
            tf.composedLines(lines);
        }

        return tf;
    }

    private static ResolvedTable parseTable(JsonObject o) {
        ResolvedTable table = new ResolvedTable();
        table.id(getString(o, "id"));
        table.rowCount(getInt(o, "rowCount", 0));
        table.columnCount(getInt(o, "columnCount", 0));
        if (o.has("columnWidths")) {
            table.columnWidths(parseDoubleArray(o.getAsJsonArray("columnWidths")));
        }
        if (o.has("rowHeights")) {
            table.rowHeights(parseDoubleArray(o.getAsJsonArray("rowHeights")));
        }
        if (o.has("bounds") && !o.get("bounds").isJsonNull()) {
            table.bounds(parseDoubleArray(o.getAsJsonArray("bounds")));
        }
        return table;
    }

    private static ResolvedStory parseStory(JsonObject o) {
        ResolvedStory story = new ResolvedStory();
        story.id(getString(o, "id"));

        if (o.has("paragraphs")) {
            for (JsonElement e : o.getAsJsonArray("paragraphs")) {
                story.addParagraph(parseParagraph(e.getAsJsonObject()));
            }
        }
        return story;
    }

    private static ResolvedParagraph parseParagraph(JsonObject o) {
        ResolvedParagraph para = new ResolvedParagraph();
        para.styleName(getString(o, "styleName"));
        para.autoLeading(getBoxedDouble(o, "autoLeading"));
        para.spaceBefore(getBoxedDouble(o, "spaceBefore"));
        para.spaceAfter(getBoxedDouble(o, "spaceAfter"));
        para.firstLineIndent(getBoxedDouble(o, "firstLineIndent"));
        para.leftIndent(getBoxedDouble(o, "leftIndent"));
        para.rightIndent(getBoxedDouble(o, "rightIndent"));
        para.shadingOn(getBoxedBool(o, "shadingOn"));
        para.shadingColor(getString(o, "shadingColor"));
        para.shadingTint(getBoxedDouble(o, "shadingTint"));
        para.justification(getString(o, "justification"));

        // leading: can be number or string "Auto"
        if (o.has("leading") && !o.get("leading").isJsonNull()) {
            JsonElement leadingEl = o.get("leading");
            if (leadingEl.isJsonPrimitive()) {
                if (leadingEl.getAsJsonPrimitive().isNumber()) {
                    para.leading(leadingEl.getAsDouble());
                } else {
                    para.leading(leadingEl.getAsString());
                }
            }
        }

        if (o.has("tabStops")) {
            for (JsonElement e : o.getAsJsonArray("tabStops")) {
                JsonObject ts = e.getAsJsonObject();
                ResolvedTabStop tab = new ResolvedTabStop();
                tab.position(getBoxedDouble(ts, "position"));
                tab.alignment(getString(ts, "alignment"));
                tab.leader(getString(ts, "leader"));
                para.addTabStop(tab);
            }
        }

        if (o.has("runs")) {
            for (JsonElement e : o.getAsJsonArray("runs")) {
                para.addRun(parseRun(e.getAsJsonObject()));
            }
        }
        return para;
    }

    private static ResolvedRun parseRun(JsonObject o) {
        ResolvedRun run = new ResolvedRun();
        run.text(getString(o, "text"));
        run.fontFamily(getString(o, "fontFamily"));
        run.fontSize(getBoxedDouble(o, "fontSize"));
        run.fontStyle(getString(o, "fontStyle"));
        run.fillColor(getString(o, "fillColor"));
        run.charStyle(getString(o, "charStyle"));
        run.tracking(getBoxedDouble(o, "tracking"));
        run.horizontalScale(getBoxedDouble(o, "horizontalScale"));
        run.verticalScale(getBoxedDouble(o, "verticalScale"));
        run.baselineShift(getBoxedDouble(o, "baselineShift"));
        run.position(getString(o, "position"));
        run.underline(getBoxedBool(o, "underline"));
        run.strikeThru(getBoxedBool(o, "strikeThru"));
        // IDML-Free: inline_anchor
        run.type(getString(o, "type"));
        if (o.has("anchoredObjectId") && !o.get("anchoredObjectId").isJsonNull()) {
            run.anchoredObjectId(o.get("anchoredObjectId").getAsInt());
        }
        return run;
    }

    private static ResolvedPage parsePage(JsonObject o) {
        ResolvedPage page = new ResolvedPage();
        page.index(getInt(o, "index", -1));
        page.name(getString(o, "name"));
        if (o.has("bounds")) {
            page.bounds(parseDoubleArray(o.getAsJsonArray("bounds")));
        }
        if (o.has("marginPreferences")) {
            JsonObject mp = o.getAsJsonObject("marginPreferences");
            page.marginTop(getDouble(mp, "top", 0));
            page.marginBottom(getDouble(mp, "bottom", 0));
            page.marginLeft(getDouble(mp, "left", 0));
            page.marginRight(getDouble(mp, "right", 0));
        }
        return page;
    }

    private static FontMetricEntry parseFontMetric(JsonObject o) {
        FontMetricEntry fm = new FontMetricEntry();
        fm.family(getString(o, "family"));
        fm.style(getString(o, "style"));
        fm.korWidth(getDouble(o, "korWidth", 0));
        fm.latWidth(getDouble(o, "latWidth", 0));
        fm.weight((int) getDouble(o, "weight", 400));
        fm.xHeight(getDouble(o, "xHeight", 0));
        fm.ascent(getDouble(o, "ascent", 0));
        fm.descent(getDouble(o, "descent", 0));
        return fm;
    }

    private static ResolvedPageItem parsePageItem(JsonObject o) {
        ResolvedPageItem item = new ResolvedPageItem();
        item.id(getString(o, "id"));
        item.type(getString(o, "type"));
        item.name(getString(o, "name"));
        item.parentId(getString(o, "parentId"));
        item.pageIndex(getInt(o, "pageIndex", -1));

        // 기하
        if (o.has("geometricBounds")) {
            item.geometricBounds(parseDoubleArray(o.getAsJsonArray("geometricBounds")));
        }
        if (o.has("visibleBounds")) {
            item.visibleBounds(parseDoubleArray(o.getAsJsonArray("visibleBounds")));
        }

        // 변환
        item.absoluteRotationAngle(getDouble(o, "absoluteRotationAngle", 0));
        item.absoluteShearAngle(getDouble(o, "absoluteShearAngle", 0));
        item.absoluteHorizontalScale(getDouble(o, "absoluteHorizontalScale", 100));
        item.absoluteVerticalScale(getDouble(o, "absoluteVerticalScale", 100));

        // 채우기
        item.fillColorName(getString(o, "fillColorName"));
        item.fillTint(getDouble(o, "fillTint", 100));

        // 스트로크
        item.strokeColorName(getString(o, "strokeColorName"));
        item.strokeTint(getDouble(o, "strokeTint", 100));
        item.strokeWeight(getDouble(o, "strokeWeight", 0));
        item.strokeAlignment(getString(o, "strokeAlignment"));

        // 효과
        item.opacity(getDouble(o, "opacity", 100));

        // 그라디언트 페더
        if (o.has("gradientFeather")) {
            JsonObject gf = o.getAsJsonObject("gradientFeather");
            item.gradientFeatherApplied(getBool(gf, "applied", false));
            item.gradientFeatherAngle(getDouble(gf, "angle", 0));
            item.gradientFeatherLength(getDouble(gf, "length", 0));
            item.gradientFeatherType(getString(gf, "type"));
        }

        // 드롭 섀도우
        if (o.has("dropShadow")) {
            JsonObject ds = o.getAsJsonObject("dropShadow");
            item.dropShadowAngle(getDouble(ds, "angle", 0));
            item.dropShadowDistance(getDouble(ds, "distance", 0));
            item.dropShadowSize(getDouble(ds, "size", 0));
            item.dropShadowOpacity(getDouble(ds, "opacity", 0));
            item.dropShadowColorName(getString(ds, "colorName"));
        }

        // 코너
        item.cornerRadius(getDouble(o, "cornerRadius", 0));

        // 플립
        item.absoluteFlip(getString(o, "absoluteFlip"));

        // IDML-Free 파이프라인 보강 필드
        item.zOrder(getInt(o, "zOrder", 0));
        item.isInline(getBool(o, "isInline", false));
        item.clipContent(getBool(o, "clipContent", false));
        if (o.has("childIds") && !o.get("childIds").isJsonNull()) {
            item.childIds(parseIntArray(o.getAsJsonArray("childIds")));
        }
        if (o.has("pageRelativeBounds") && !o.get("pageRelativeBounds").isJsonNull()) {
            item.pageRelativeBounds(parseDoubleArray(o.getAsJsonArray("pageRelativeBounds")));
        }

        return item;
    }

    private static RenderedGroup parseRenderedGroup(JsonObject o) {
        RenderedGroup group = new RenderedGroup();
        group.id(getInt(o, "id", 0));
        group.file(getString(o, "file"));
        group.pageIndex(getInt(o, "pageIndex", 0));
        if (o.has("bounds") && !o.get("bounds").isJsonNull()) {
            group.bounds(parseDoubleArray(o.getAsJsonArray("bounds")));
        }
        if (o.has("visibleExpansion") && !o.get("visibleExpansion").isJsonNull()) {
            group.visibleExpansion(parseDoubleArray(o.getAsJsonArray("visibleExpansion")));
        }
        // 배지 그룹 필드
        group.type(getString(o, "type"));
        if (o.has("childIds") && !o.get("childIds").isJsonNull()) {
            group.childIds(parseIntArray(o.getAsJsonArray("childIds")));
        }
        if (o.has("childTextFrameIds") && !o.get("childTextFrameIds").isJsonNull()) {
            group.childTextFrameIds(parseIntArray(o.getAsJsonArray("childTextFrameIds")));
        }
        group.badgeGroupId(getInt(o, "badgeGroupId", 0));
        if (o.has("childImageIds") && !o.get("childImageIds").isJsonNull()) {
            group.childImageIds(parseIntArray(o.getAsJsonArray("childImageIds")));
        }
        // PDF 배경 필드
        group.pdfFile(getString(o, "pdfFile"));
        group.pdfPageIndex(getInt(o, "pdfPageIndex", -1));
        return group;
    }

    private static int[] parseIntArray(JsonArray arr) {
        int[] result = new int[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            result[i] = (el == null || el.isJsonNull()) ? 0 : el.getAsInt();
        }
        return result;
    }

    // ─── JSON helpers ────────────────────────────────────────

    private static String getString(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return null;
        return o.get(key).getAsString();
    }

    private static Double getBoxedDouble(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return null;
        return o.get(key).getAsDouble();
    }

    private static Boolean getBoxedBool(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return null;
        return o.get(key).getAsBoolean();
    }

    private static int getInt(JsonObject o, String key, int defaultValue) {
        if (!o.has(key) || o.get(key).isJsonNull()) return defaultValue;
        return o.get(key).getAsInt();
    }

    private static boolean getBool(JsonObject o, String key, boolean defaultValue) {
        if (!o.has(key) || o.get(key).isJsonNull()) return defaultValue;
        return o.get(key).getAsBoolean();
    }

    private static double getDouble(JsonObject o, String key, double defaultValue) {
        if (!o.has(key) || o.get(key).isJsonNull()) return defaultValue;
        return o.get(key).getAsDouble();
    }

    private static double[] parseDoubleArray(JsonArray arr) {
        double[] result = new double[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            result[i] = (el == null || el.isJsonNull()) ? 0 : el.getAsDouble();
        }
        return result;
    }
}
