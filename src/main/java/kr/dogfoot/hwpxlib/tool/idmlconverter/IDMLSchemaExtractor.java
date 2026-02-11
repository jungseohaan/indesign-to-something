package kr.dogfoot.hwpxlib.tool.idmlconverter;

import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * IDML 파일에서 인라인 Group 패턴을 분석하여 템플릿 스키마(JSON)를 추출한다.
 *
 * 스키마는 다음을 포함:
 * - 레이아웃 정보 (페이지 크기, 마진, 컬럼)
 * - 마스터 스프레드 목록
 * - Group 템플릿 구조 (자식 요소 타입, 바운드, TextFrame→Story→ParagraphStyle 매핑)
 * - 데이터 필드 목록 (각 TextFrame의 스타일명 → 필드명)
 */
public class IDMLSchemaExtractor {

    /**
     * IDML 파일을 분석하여 템플릿 스키마 JSON 문자열을 반환한다.
     */
    public static String extractSchema(String idmlPath) throws Exception {
        File file = new File(idmlPath);
        if (!file.exists()) {
            throw new Exception("File not found: " + idmlPath);
        }

        try (ZipFile zip = new ZipFile(file)) {
            // 1. designmap.xml 파싱
            DesignmapInfo dmInfo = parseDesignmap(zip);

            // 2. 마스터 스프레드 레이아웃 추출
            LayoutInfo layout = null;
            List<MasterInfo> masters = new ArrayList<>();
            for (MasterRef mRef : dmInfo.masterRefs) {
                MasterInfo mi = extractMasterInfo(zip, mRef.src);
                if (mi != null) {
                    mi.id = mRef.masterId;
                    masters.add(mi);
                    if (layout == null) {
                        layout = mi.layout;
                    }
                }
            }
            if (layout == null) {
                layout = new LayoutInfo(); // defaults
            }

            // 3. 모든 Story에서 인라인 Group 탐색
            GroupTemplate groupTemplate = findGroupTemplate(zip, dmInfo, layout);

            // 4. JSON 출력
            return buildSchemaJson(idmlPath, layout, masters, groupTemplate);
        }
    }

    // ─────────────────────────────────────────────────────────
    // designmap.xml 파싱
    // ─────────────────────────────────────────────────────────

    private static DesignmapInfo parseDesignmap(ZipFile zip) throws Exception {
        ZipEntry entry = zip.getEntry("designmap.xml");
        if (entry == null) throw new Exception("designmap.xml not found");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(zip.getInputStream(entry));
        Element root = doc.getDocumentElement();

        DesignmapInfo info = new DesignmapInfo();
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element elem = (Element) node;
            String tag = elem.getTagName();

            if ("idPkg:MasterSpread".equals(tag)) {
                String src = elem.getAttribute("src");
                if (src != null && !src.isEmpty()) {
                    MasterRef ref = new MasterRef();
                    ref.src = src;
                    ref.masterId = extractIdFromSpreadXml(zip, src);
                    info.masterRefs.add(ref);
                }
            } else if ("idPkg:Story".equals(tag)) {
                String src = elem.getAttribute("src");
                if (src != null && !src.isEmpty()) {
                    info.storySrcs.add(src);
                }
            }
        }
        return info;
    }

    private static String extractIdFromSpreadXml(ZipFile zip, String src) {
        try {
            ZipEntry entry = zip.getEntry(src);
            if (entry == null) return "unknown";
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(zip.getInputStream(entry));
            NodeList nodes = doc.getElementsByTagName("MasterSpread");
            if (nodes.getLength() == 0) nodes = doc.getElementsByTagName("Spread");
            if (nodes.getLength() > 0) {
                return ((Element) nodes.item(0)).getAttribute("Self");
            }
        } catch (Exception e) { /* ignore */ }
        return "unknown";
    }

    // ─────────────────────────────────────────────────────────
    // 마스터 스프레드 분석
    // ─────────────────────────────────────────────────────────

    private static MasterInfo extractMasterInfo(ZipFile zip, String src) {
        try {
            ZipEntry entry = zip.getEntry(src);
            if (entry == null) return null;

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(zip.getInputStream(entry));

            NodeList pages = doc.getElementsByTagName("Page");
            if (pages.getLength() == 0) return null;

            MasterInfo mi = new MasterInfo();
            mi.pageCount = pages.getLength();

            Element pageElem = (Element) pages.item(0);

            // 마스터 이름: Name 속성에서 추출
            String name = pageElem.getAttribute("Name");
            if (name != null && !name.isEmpty()) {
                mi.name = name;
            }

            // 레이아웃 정보
            mi.layout = new LayoutInfo();
            String boundsStr = pageElem.getAttribute("GeometricBounds");
            if (boundsStr != null && !boundsStr.isEmpty()) {
                String[] parts = boundsStr.trim().split("\\s+");
                if (parts.length >= 4) {
                    double top = Double.parseDouble(parts[0]);
                    double left = Double.parseDouble(parts[1]);
                    double bottom = Double.parseDouble(parts[2]);
                    double right = Double.parseDouble(parts[3]);
                    mi.layout.pageWidth = right - left;
                    mi.layout.pageHeight = bottom - top;
                }
            }

            NodeList marginPrefs = pageElem.getElementsByTagName("MarginPreference");
            if (marginPrefs.getLength() > 0) {
                Element mp = (Element) marginPrefs.item(0);
                mi.layout.marginTop = parseDouble(mp, "Top", mi.layout.marginTop);
                mi.layout.marginBottom = parseDouble(mp, "Bottom", mi.layout.marginBottom);
                mi.layout.marginLeft = parseDouble(mp, "Left", mi.layout.marginLeft);
                mi.layout.marginRight = parseDouble(mp, "Right", mi.layout.marginRight);
                mi.layout.columnCount = parseInt(mp, "ColumnCount", mi.layout.columnCount);
                mi.layout.columnGutter = parseDouble(mp, "ColumnGutter", mi.layout.columnGutter);
            }

            return mi;
        } catch (Exception e) {
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────
    // 인라인 Group 탐색
    // ─────────────────────────────────────────────────────────

    private static GroupTemplate findGroupTemplate(ZipFile zip, DesignmapInfo dmInfo, LayoutInfo layout) throws Exception {
        for (String storySrc : dmInfo.storySrcs) {
            ZipEntry entry = zip.getEntry(storySrc);
            if (entry == null) continue;

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(zip.getInputStream(entry));

            // CharacterStyleRange 내 인라인 Group 탐색
            NodeList csRanges = doc.getElementsByTagName("CharacterStyleRange");
            for (int r = 0; r < csRanges.getLength(); r++) {
                Element csRange = (Element) csRanges.item(r);
                NodeList children = csRange.getChildNodes();
                for (int c = 0; c < children.getLength(); c++) {
                    Node node = children.item(c);
                    if (node.getNodeType() != Node.ELEMENT_NODE) continue;
                    if (!"Group".equals(((Element) node).getTagName())) continue;
                    Element groupElem = (Element) node;

                    // 의미있는 자식 요소 수 확인 (Rectangle, TextFrame, GraphicLine 등)
                    int meaningfulChildren = countMeaningfulChildren(groupElem);
                    if (meaningfulChildren < 3) continue;

                    // TextFrame이 2개 이상이어야 문항 구조
                    int tfCount = countChildrenByTag(groupElem, "TextFrame");
                    if (tfCount < 2) continue;

                    // 이 Group의 자식 요소 분석
                    GroupTemplate template = analyzeGroupChildren(zip, groupElem);
                    if (template != null && !template.children.isEmpty()) {
                        return template;
                    }
                }
            }
        }

        return null; // Group 패턴 없음
    }

    private static int countMeaningfulChildren(Element elem) {
        int count = 0;
        NodeList children = elem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() != Node.ELEMENT_NODE) continue;
            String tag = ((Element) children.item(i)).getTagName();
            if ("Rectangle".equals(tag) || "TextFrame".equals(tag) ||
                "GraphicLine".equals(tag) || "Oval".equals(tag) || "Polygon".equals(tag)) {
                count++;
            }
        }
        return count;
    }

    private static int countChildrenByTag(Element elem, String tagName) {
        int count = 0;
        NodeList children = elem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() != Node.ELEMENT_NODE) continue;
            if (tagName.equals(((Element) children.item(i)).getTagName())) count++;
        }
        return count;
    }

    private static GroupTemplate analyzeGroupChildren(ZipFile zip, Element groupElem) throws Exception {
        GroupTemplate template = new GroupTemplate();

        NodeList children = groupElem.getChildNodes();
        List<String> fieldNames = new ArrayList<>();

        // 최대 너비 추적 (Group 크기 추정용)
        double maxChildWidth = 0;

        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element child = (Element) node;
            String tag = child.getTagName();

            if ("Rectangle".equals(tag)) {
                GroupChild gc = new GroupChild();
                gc.type = "rectangle";
                gc.role = "background";
                gc.bounds = extractBoundsFromPath(child);
                gc.fillColor = child.getAttribute("FillColor");
                gc.strokeColor = child.getAttribute("StrokeColor");
                gc.strokeWeight = parseDouble(child, "StrokeWeight", 0);
                gc.contentType = child.getAttribute("ContentType");
                if (gc.bounds != null) {
                    double w = gc.bounds[3] - gc.bounds[1];
                    if (w > maxChildWidth) maxChildWidth = w;
                }
                template.children.add(gc);

            } else if ("TextFrame".equals(tag)) {
                GroupChild gc = new GroupChild();
                gc.type = "textFrame";
                gc.bounds = extractBoundsFromPath(child);
                if (gc.bounds != null) {
                    double w = gc.bounds[3] - gc.bounds[1];
                    if (w > maxChildWidth) maxChildWidth = w;
                }

                // AutoSizing 확인
                NodeList tfPrefs = child.getElementsByTagName("TextFramePreference");
                if (tfPrefs.getLength() > 0) {
                    Element pref = (Element) tfPrefs.item(0);
                    String autoSizing = pref.getAttribute("AutoSizingType");
                    if (autoSizing != null && !autoSizing.isEmpty()) {
                        gc.autoSize = autoSizing;
                    }
                }

                // ParentStory → Story 파일에서 ParagraphStyle 추출
                String parentStoryId = child.getAttribute("ParentStory");
                if (parentStoryId != null && !parentStoryId.isEmpty()) {
                    List<FieldDef> fields = extractFieldsFromStory(zip, parentStoryId);
                    gc.fields = fields;

                    // role 결정: 필드 수와 위치 기반
                    if (fields.size() == 1) {
                        gc.role = fields.get(0).name;
                    } else if (fields.size() > 1) {
                        gc.role = "content";
                    }

                    for (FieldDef f : fields) {
                        if (!fieldNames.contains(f.name)) {
                            fieldNames.add(f.name);
                        }
                    }
                }
                template.children.add(gc);

            } else if ("GraphicLine".equals(tag)) {
                GroupChild gc = new GroupChild();
                gc.type = "graphicLine";
                gc.role = "divider";
                gc.strokeWeight = parseDouble(child, "StrokeWeight", 0);
                gc.strokeColor = child.getAttribute("StrokeColor");
                gc.bounds = extractBoundsFromPath(child);
                template.children.add(gc);

            } else if ("Oval".equals(tag) || "Polygon".equals(tag)) {
                GroupChild gc = new GroupChild();
                gc.type = tag.toLowerCase();
                gc.role = tag.toLowerCase();
                gc.bounds = extractBoundsFromPath(child);
                gc.fillColor = child.getAttribute("FillColor");
                gc.strokeColor = child.getAttribute("StrokeColor");
                gc.strokeWeight = parseDouble(child, "StrokeWeight", 0);
                template.children.add(gc);
            }
        }

        // Group 크기: 가장 넓은 자식의 너비 사용 (PathGeometry는 각 자식의 로컬 좌표)
        template.width = maxChildWidth;
        // 각 자식 bounds를 [0,0] 기준 width×height로 정규화
        for (GroupChild gc : template.children) {
            if (gc.bounds != null) {
                double w = gc.bounds[3] - gc.bounds[1];
                double h = gc.bounds[2] - gc.bounds[0];
                gc.bounds = new double[] { 0, 0, h, w };
            }
        }

        template.fieldNames = fieldNames;
        return template;
    }

    /**
     * Story ID로 해당 Story 파일을 찾아 ParagraphStyleRange의 스타일명을 추출한다.
     */
    private static List<FieldDef> extractFieldsFromStory(ZipFile zip, String storyId) throws Exception {
        List<FieldDef> fields = new ArrayList<>();

        // Stories/Story_{storyId}.xml 패턴으로 찾기
        String storyPath = "Stories/Story_" + storyId + ".xml";
        ZipEntry entry = zip.getEntry(storyPath);

        // 정확한 경로가 없으면 모든 Story 파일 순회
        if (entry == null) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry ze = entries.nextElement();
                String name = ze.getName();
                if (name.startsWith("Stories/") && name.endsWith(".xml")) {
                    // Story 파일을 읽어서 Self 속성 확인
                    Document doc = parseXml(zip, ze);
                    NodeList stories = doc.getElementsByTagName("Story");
                    for (int s = 0; s < stories.getLength(); s++) {
                        Element storyElem = (Element) stories.item(s);
                        if (storyId.equals(storyElem.getAttribute("Self"))) {
                            return extractFieldsFromStoryElement(storyElem);
                        }
                    }
                }
            }
            return fields;
        }

        Document doc = parseXml(zip, entry);
        NodeList stories = doc.getElementsByTagName("Story");
        if (stories.getLength() > 0) {
            return extractFieldsFromStoryElement((Element) stories.item(0));
        }
        return fields;
    }

    private static List<FieldDef> extractFieldsFromStoryElement(Element storyElem) {
        List<FieldDef> fields = new ArrayList<>();
        NodeList paraRanges = storyElem.getElementsByTagName("ParagraphStyleRange");

        for (int p = 0; p < paraRanges.getLength(); p++) {
            Element paraRange = (Element) paraRanges.item(p);
            String styleName = paraRange.getAttribute("AppliedParagraphStyle");

            // "ParagraphStyle/스타일명" 형식에서 스타일명 추출
            if (styleName != null && styleName.startsWith("ParagraphStyle/")) {
                String name = styleName.substring("ParagraphStyle/".length());
                // $ID/[Basic Paragraph] 같은 기본 스타일은 "default"로
                if (name.startsWith("$ID/")) {
                    name = "default";
                }

                FieldDef fd = new FieldDef();
                fd.style = name;
                fd.name = styleToFieldName(name);
                fields.add(fd);
            }
        }
        return fields;
    }

    /**
     * 단락 스타일명을 필드명으로 변환한다.
     * 예: "!년도" → "year", "20풀_문제" → "question", "!문제번호" → "number"
     */
    private static String styleToFieldName(String styleName) {
        // 잘 알려진 스타일 매핑
        if (styleName.contains("년도") || styleName.contains("출처")) return "year";
        if (styleName.contains("문제번호") || styleName.contains("번호")) return "number";
        if (styleName.contains("문제")) return "question";
        if (styleName.contains("문항") || styleName.contains("보기")) return "choices";
        if (styleName.contains("해설")) return "explanation";
        if (styleName.contains("풀이")) return "solution";
        if ("default".equals(styleName)) return "text";

        // 특수문자 제거 후 소문자 변환
        String cleaned = styleName.replaceAll("[^a-zA-Z0-9가-힣_]", "");
        if (cleaned.isEmpty()) return "field_" + Math.abs(styleName.hashCode() % 1000);
        return cleaned;
    }

    // ─────────────────────────────────────────────────────────
    // JSON 빌더
    // ─────────────────────────────────────────────────────────

    private static String buildSchemaJson(String sourcePath, LayoutInfo layout,
                                           List<MasterInfo> masters, GroupTemplate groupTemplate) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": \"1.0\",\n");

        // source 파일명만
        String filename = sourcePath;
        int lastSlash = sourcePath.lastIndexOf('/');
        if (lastSlash >= 0) filename = sourcePath.substring(lastSlash + 1);
        int lastBackslash = filename.lastIndexOf('\\');
        if (lastBackslash >= 0) filename = filename.substring(lastBackslash + 1);
        sb.append("  \"source\": ").append(jsonString(filename)).append(",\n");

        // layout
        sb.append("  \"layout\": {\n");
        sb.append("    \"pageWidth\": ").append(layout.pageWidth).append(",\n");
        sb.append("    \"pageHeight\": ").append(layout.pageHeight).append(",\n");
        sb.append("    \"margins\": {\n");
        sb.append("      \"top\": ").append(round2(layout.marginTop)).append(",\n");
        sb.append("      \"bottom\": ").append(round2(layout.marginBottom)).append(",\n");
        sb.append("      \"left\": ").append(round2(layout.marginLeft)).append(",\n");
        sb.append("      \"right\": ").append(round2(layout.marginRight)).append("\n");
        sb.append("    },\n");
        sb.append("    \"columns\": {\n");
        sb.append("      \"count\": ").append(layout.columnCount).append(",\n");
        sb.append("      \"gutter\": ").append(round2(layout.columnGutter)).append("\n");
        sb.append("    }\n");
        sb.append("  },\n");

        // masterSpreads
        sb.append("  \"masterSpreads\": [\n");
        for (int i = 0; i < masters.size(); i++) {
            MasterInfo mi = masters.get(i);
            sb.append("    { \"id\": ").append(jsonString(mi.id));
            sb.append(", \"name\": ").append(jsonString(mi.name));
            sb.append(", \"pageCount\": ").append(mi.pageCount).append(" }");
            if (i < masters.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n");

        // groupTemplate
        if (groupTemplate != null && !groupTemplate.children.isEmpty()) {
            sb.append("  \"groupTemplate\": {\n");
            sb.append("    \"width\": ").append(round2(groupTemplate.width)).append(",\n");
            sb.append("    \"totalHeight\": ").append(round2(groupTemplate.totalHeight)).append(",\n");
            sb.append("    \"children\": [\n");

            for (int c = 0; c < groupTemplate.children.size(); c++) {
                GroupChild gc = groupTemplate.children.get(c);
                sb.append("      {\n");
                sb.append("        \"type\": ").append(jsonString(gc.type)).append(",\n");
                sb.append("        \"role\": ").append(jsonString(gc.role)).append(",\n");

                // bounds
                if (gc.bounds != null) {
                    double bTop = gc.bounds[0];
                    double bLeft = gc.bounds[1];
                    double bWidth = gc.bounds[3] - gc.bounds[1];
                    double bHeight = gc.bounds[2] - gc.bounds[0];
                    sb.append("        \"bounds\": { \"top\": ").append(round2(bTop));
                    sb.append(", \"left\": ").append(round2(bLeft));
                    sb.append(", \"width\": ").append(round2(bWidth));
                    sb.append(", \"height\": ").append(round2(bHeight)).append(" },\n");
                }

                // type-specific properties
                if ("rectangle".equals(gc.type)) {
                    if (gc.contentType != null && !gc.contentType.isEmpty()) {
                        sb.append("        \"contentType\": ").append(jsonString(gc.contentType)).append(",\n");
                    }
                    sb.append("        \"fillColor\": ").append(jsonString(gc.fillColor != null ? gc.fillColor : "none")).append(",\n");
                    sb.append("        \"strokeWeight\": ").append(gc.strokeWeight).append("\n");
                } else if ("textFrame".equals(gc.type)) {
                    if (gc.autoSize != null) {
                        sb.append("        \"autoSize\": ").append(jsonString(gc.autoSize)).append(",\n");
                    }
                    if (gc.fields != null && !gc.fields.isEmpty()) {
                        sb.append("        \"fields\": [\n");
                        for (int f = 0; f < gc.fields.size(); f++) {
                            FieldDef fd = gc.fields.get(f);
                            sb.append("          { \"style\": ").append(jsonString(fd.style));
                            sb.append(", \"name\": ").append(jsonString(fd.name)).append(" }");
                            if (f < gc.fields.size() - 1) sb.append(",");
                            sb.append("\n");
                        }
                        sb.append("        ]\n");
                    } else {
                        sb.append("        \"fields\": []\n");
                    }
                } else if ("graphicLine".equals(gc.type)) {
                    sb.append("        \"strokeWeight\": ").append(gc.strokeWeight).append(",\n");
                    sb.append("        \"strokeColor\": ").append(jsonString(gc.strokeColor != null ? gc.strokeColor : "")).append("\n");
                } else {
                    // oval, polygon 등
                    sb.append("        \"fillColor\": ").append(jsonString(gc.fillColor != null ? gc.fillColor : "none")).append(",\n");
                    sb.append("        \"strokeWeight\": ").append(gc.strokeWeight).append("\n");
                }

                sb.append("      }");
                if (c < groupTemplate.children.size() - 1) sb.append(",");
                sb.append("\n");
            }

            sb.append("    ]\n");
            sb.append("  },\n");

            // itemFields
            sb.append("  \"itemFields\": [");
            for (int f = 0; f < groupTemplate.fieldNames.size(); f++) {
                if (f > 0) sb.append(", ");
                sb.append(jsonString(groupTemplate.fieldNames.get(f)));
            }
            sb.append("]\n");
        } else {
            sb.append("  \"groupTemplate\": null,\n");
            sb.append("  \"itemFields\": []\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────────────────

    private static Document parseXml(ZipFile zip, ZipEntry entry) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(zip.getInputStream(entry));
    }

    /**
     * PathGeometry의 PathPointArray에서 바운드를 계산한다.
     * GeometricBounds가 없는 실제 IDML에서 사용.
     */
    private static double[] extractBoundsFromPath(Element elem) {
        // 먼저 GeometricBounds 시도
        String gb = elem.getAttribute("GeometricBounds");
        if (gb != null && !gb.isEmpty()) {
            String[] parts = gb.trim().split("\\s+");
            if (parts.length >= 4) {
                try {
                    return new double[] {
                        Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                        Double.parseDouble(parts[2]), Double.parseDouble(parts[3])
                    };
                } catch (NumberFormatException e) { /* fall through */ }
            }
        }

        // PathPointArray의 Anchor 포인트에서 바운드 계산
        NodeList pathPoints = elem.getElementsByTagName("PathPointType");
        if (pathPoints.getLength() == 0) return null;

        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        for (int i = 0; i < pathPoints.getLength(); i++) {
            Element pp = (Element) pathPoints.item(i);
            String anchor = pp.getAttribute("Anchor");
            if (anchor == null || anchor.isEmpty()) continue;
            String[] xy = anchor.trim().split("\\s+");
            if (xy.length < 2) continue;
            try {
                double x = Double.parseDouble(xy[0]);
                double y = Double.parseDouble(xy[1]);
                minX = Math.min(minX, x); minY = Math.min(minY, y);
                maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
            } catch (NumberFormatException e) { /* skip */ }
        }

        if (minX == Double.MAX_VALUE) return null;
        // IDML PathPoint: Anchor="x y" → bounds [top, left, bottom, right] = [minY, minX, maxY, maxX]
        return new double[] { minY, minX, maxY, maxX };
    }

    private static double parseDouble(Element elem, String attr, double defaultVal) {
        String val = elem.getAttribute(attr);
        if (val == null || val.isEmpty()) return defaultVal;
        try { return Double.parseDouble(val); } catch (NumberFormatException e) { return defaultVal; }
    }

    private static int parseInt(Element elem, String attr, int defaultVal) {
        String val = elem.getAttribute(attr);
        if (val == null || val.isEmpty()) return defaultVal;
        try { return Integer.parseInt(val); } catch (NumberFormatException e) { return defaultVal; }
    }

    private static double round2(double val) {
        return Math.round(val * 100.0) / 100.0;
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                        .replace("\n", "\\n").replace("\r", "\\r")
                        .replace("\t", "\\t") + "\"";
    }

    // ─────────────────────────────────────────────────────────
    // Data classes
    // ─────────────────────────────────────────────────────────

    private static class DesignmapInfo {
        List<MasterRef> masterRefs = new ArrayList<>();
        List<String> storySrcs = new ArrayList<>();
    }

    private static class MasterRef {
        String src;
        String masterId;
    }

    static class LayoutInfo {
        double pageWidth = 595.28;
        double pageHeight = 841.88;
        double marginTop = 56.69;
        double marginBottom = 56.69;
        double marginLeft = 56.69;
        double marginRight = 56.69;
        int columnCount = 1;
        double columnGutter = 14.17;
    }

    private static class MasterInfo {
        String id;
        String name = "Unknown";
        int pageCount = 1;
        LayoutInfo layout;
    }

    static class GroupTemplate {
        double width;
        double totalHeight;
        List<GroupChild> children = new ArrayList<>();
        List<String> fieldNames = new ArrayList<>();
    }

    static class GroupChild {
        String type;       // "rectangle", "textFrame", "graphicLine", "oval", "polygon"
        String role;       // "background", "year", "number", "content", "divider"
        double[] bounds;   // [top, left, bottom, right]
        String fillColor;
        String strokeColor;
        double strokeWeight;
        String contentType; // Rectangle의 ContentType (GraphicType 등)
        String autoSize;   // TextFrame의 AutoSizingType
        List<FieldDef> fields; // TextFrame의 필드 정의
    }

    static class FieldDef {
        String style;  // 단락 스타일명 (예: "!년도", "20풀_문제")
        String name;   // 필드명 (예: "year", "question")
    }
}
