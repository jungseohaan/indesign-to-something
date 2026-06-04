package kr.dogfoot.hwpxlib.tool.idmlconverter.devtool;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.*;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * IDML 좌표와 resolved.json 좌표를 비교하는 진단 도구.
 *
 * 모든 페이지 아이템(텍스트프레임, 이미지프레임, 벡터셰이프)에 대해
 * IDML 변환행렬 기반 좌표와 resolved pageItem 좌표를 나란히 출력한다.
 *
 * 사용: java -jar converter.jar --diagnose input.idml --resolved resolved.json
 */
public class CoordinateDiagnoser {

    private static final double DELTA_THRESHOLD = 5.0;   // 5pt 이상이면 LARGE_DELTA
    private static final double SIZE_THRESHOLD = 0.05;    // 5% 이상이면 LARGE_SIZE_DELTA

    /**
     * 진단을 실행하고 JSON 결과를 출력한다. (전체 페이지)
     */
    public static void diagnose(IDMLDocument idmlDoc, ResolvedData resolved, PrintStream out) {
        diagnose(idmlDoc, resolved, 0, 0, out);
    }

    /**
     * 진단을 실행하고 JSON 결과를 출력한다.
     * @param startPage 시작 페이지 (1-based, 0이면 전체)
     * @param endPage 끝 페이지 (1-based, 0이면 전체)
     */
    public static void diagnose(IDMLDocument idmlDoc, ResolvedData resolved, int startPage, int endPage, PrintStream out) {
        // 단위 정규화 (resolved가 mm 등일 수 있음)
        if (resolved != null) {
            List<IDMLPage> allPages = idmlDoc.getAllPages();
            if (!allPages.isEmpty()) {
                resolved.normalizeToPoints(allPages.get(0).widthPoints());
            }
        }

        List<IDMLPage> allPages = idmlDoc.getAllPages();
        boolean facingPages = allPages.size() > 1 && idmlDoc.spreads().size() > 0
                && idmlDoc.spreads().get(0).pages().size() > 1;

        double pageWidth = allPages.isEmpty() ? 0 : allPages.get(0).widthPoints();
        double pageHeight = allPages.isEmpty() ? 0 : allPages.get(0).heightPoints();

        // 페이지 범위 계산 (1-based → 0-based)
        int piStart = 0;
        int piEnd = allPages.size() - 1;
        if (startPage > 0) {
            piStart = startPage - 1;
        }
        if (endPage > 0) {
            piEnd = Math.min(endPage - 1, allPages.size() - 1);
        }

        // summary counters
        int totalObjects = 0;
        int withResolved = 0;
        int mismatches = 0;
        int largeDeltas = 0;
        int noResolved = 0;

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");

        // document info
        sb.append("  \"document\": {\n");
        sb.append("    \"pages\": ").append(allPages.size()).append(",\n");
        sb.append("    \"facingPages\": ").append(facingPages).append(",\n");
        sb.append("    \"pageWidth\": ").append(fmt(pageWidth)).append(",\n");
        sb.append("    \"pageHeight\": ").append(fmt(pageHeight)).append(",\n");
        if (startPage > 0) {
            sb.append("    \"filteredPages\": \"").append(startPage).append("-").append(endPage > 0 ? endPage : allPages.size()).append("\",\n");
        }
        sb.append("    \"resolvedPages\": ").append(resolved != null ? resolved.pageCount() : 0).append(",\n");
        sb.append("    \"resolvedPageItems\": ").append(resolved != null ? resolved.pageItemCount() : 0).append("\n");
        sb.append("  },\n");

        // pages array
        sb.append("  \"pages\": [\n");

        for (int pi = piStart; pi <= piEnd && pi < allPages.size(); pi++) {
            IDMLPage page = allPages.get(pi);

            // 이 페이지가 속한 스프레드 찾기
            IDMLSpread spread = findSpreadForPage(idmlDoc, page);
            if (spread == null) continue;

            // resolved 페이지 매칭
            ResolvedPage resolvedPage = null;
            if (resolved != null) {
                resolvedPage = resolved.getPage(pi);
            }

            sb.append("    {\n");
            sb.append("      \"pageIndex\": ").append(pi).append(",\n");
            sb.append("      \"pageName\": \"").append(page.pageNumber()).append("\",\n");

            double pw = page.widthPoints();
            double ph = page.heightPoints();
            sb.append("      \"pageWidth\": ").append(fmt(pw)).append(",\n");
            sb.append("      \"pageHeight\": ").append(fmt(ph)).append(",\n");

            // resolved page info
            if (resolvedPage != null && resolvedPage.bounds() != null) {
                double rpw = resolvedPage.width();
                double rph = resolvedPage.height();
                sb.append("      \"resolvedPageWidth\": ").append(fmt(rpw)).append(",\n");
                sb.append("      \"resolvedPageHeight\": ").append(fmt(rph)).append(",\n");
            }

            // objects on this page
            sb.append("      \"objects\": [\n");
            List<String> objectJsons = new ArrayList<String>();

            // Text Frames
            for (IDMLTextFrame tf : spread.getTextFramesOnPage(page)) {
                totalObjects++;
                String json = diagnoseObject(
                        tf.selfId(), "TextFrame",
                        tf.geometricBounds(), tf.itemTransform(),
                        page, resolved, resolvedPage);
                objectJsons.add(json);

                if (json.contains("\"NO_RESOLVED\"")) noResolved++;
                else withResolved++;
                if (json.contains("LARGE_")) { mismatches++; largeDeltas++; }
            }

            // Image Frames
            for (IDMLImageFrame img : spread.getImageFramesOnPage(page)) {
                totalObjects++;
                String json = diagnoseObject(
                        img.selfId(), "ImageFrame",
                        img.geometricBounds(), img.itemTransform(),
                        page, resolved, resolvedPage);
                objectJsons.add(json);

                if (json.contains("\"NO_RESOLVED\"")) noResolved++;
                else withResolved++;
                if (json.contains("LARGE_")) { mismatches++; largeDeltas++; }
            }

            // Vector Shapes
            for (IDMLVectorShape vs : spread.getVectorShapesOnPage(page)) {
                totalObjects++;
                String json = diagnoseObject(
                        vs.selfId(), vs.shapeType() != null ? vs.shapeType().name() : "VectorShape",
                        vs.geometricBounds(), vs.itemTransform(),
                        page, resolved, resolvedPage);
                objectJsons.add(json);

                if (json.contains("\"NO_RESOLVED\"")) noResolved++;
                else withResolved++;
                if (json.contains("LARGE_")) { mismatches++; largeDeltas++; }
            }

            for (int oi = 0; oi < objectJsons.size(); oi++) {
                sb.append(objectJsons.get(oi));
                if (oi < objectJsons.size() - 1) sb.append(",");
                sb.append("\n");
            }

            sb.append("      ]\n");
            sb.append("    }");
            if (pi < piEnd && pi < allPages.size() - 1) sb.append(",");
            sb.append("\n");
        }

        sb.append("  ],\n");

        // summary
        sb.append("  \"summary\": {\n");
        sb.append("    \"totalObjects\": ").append(totalObjects).append(",\n");
        sb.append("    \"withResolved\": ").append(withResolved).append(",\n");
        sb.append("    \"noResolved\": ").append(noResolved).append(",\n");
        sb.append("    \"mismatches\": ").append(mismatches).append(",\n");
        sb.append("    \"largeDeltas\": ").append(largeDeltas).append("\n");
        sb.append("  }\n");
        sb.append("}\n");

        out.print(sb.toString());
    }

    /**
     * 개별 객체의 IDML vs resolved 좌표 비교 JSON 생성.
     */
    private static String diagnoseObject(
            String selfId, String type,
            double[] bounds, double[] transform,
            IDMLPage page, ResolvedData resolved, ResolvedPage resolvedPage) {

        StringBuilder sb = new StringBuilder();
        sb.append("        {\n");
        sb.append("          \"idmlId\": \"").append(esc(selfId)).append("\",\n");
        sb.append("          \"type\": \"").append(esc(type)).append("\",\n");

        // IDML 좌표 계산
        double idmlX = 0, idmlY = 0, idmlW = 0, idmlH = 0;
        double rotation = 0;
        boolean hasRotation = false;

        if (bounds != null && transform != null && page.geometricBounds() != null && page.itemTransform() != null) {
            rotation = IDMLGeometry.extractRotation(transform);
            hasRotation = Math.abs(rotation) > 0.1;

            if (hasRotation) {
                idmlW = IDMLGeometry.transformedWidth(bounds, transform);
                idmlH = IDMLGeometry.transformedHeight(bounds, transform);
                double[] bbox = IDMLGeometry.getTransformedBoundingBox(bounds, transform);
                double[] pageAbs = IDMLGeometry.absoluteTopLeft(page.geometricBounds(), page.itemTransform());
                idmlX = bbox[0] - pageAbs[0];
                idmlY = bbox[1] - pageAbs[1];
            } else {
                idmlW = IDMLGeometry.scaledWidth(bounds, transform);
                idmlH = IDMLGeometry.scaledHeight(bounds, transform);
                double[] relCenter = IDMLGeometry.pageRelativeCenter(
                        bounds, transform, page.geometricBounds(), page.itemTransform());
                idmlX = relCenter[0] - idmlW / 2;
                idmlY = relCenter[1] - idmlH / 2;
            }
        }

        sb.append("          \"idml\": { ");
        sb.append("\"x\": ").append(fmt(idmlX)).append(", ");
        sb.append("\"y\": ").append(fmt(idmlY)).append(", ");
        sb.append("\"w\": ").append(fmt(idmlW)).append(", ");
        sb.append("\"h\": ").append(fmt(idmlH));
        if (hasRotation) sb.append(", \"rotation\": ").append(fmt(rotation));
        sb.append(" },\n");

        // Resolved 좌표 조회
        ResolvedPageItem ri = null;
        if (resolved != null && selfId != null) {
            ri = resolved.getPageItemByIdmlId(selfId);
        }

        List<String> flags = new ArrayList<String>();

        if (ri != null && ri.geometricBounds() != null) {
            double[] gb = ri.geometricBounds();
            double rW = gb[3] - gb[1];
            double rH = gb[2] - gb[0];

            double rX = 0, rY = 0;
            if (resolvedPage != null && resolvedPage.bounds() != null) {
                double[] rel = resolvedPage.toPageRelative(gb);
                rX = rel[0];
                rY = rel[1];
            }

            // resolved DOM ID
            String resolvedId = ri.id();

            sb.append("          \"resolved\": { ");
            sb.append("\"id\": \"").append(esc(resolvedId)).append("\", ");
            sb.append("\"x\": ").append(fmt(rX)).append(", ");
            sb.append("\"y\": ").append(fmt(rY)).append(", ");
            sb.append("\"w\": ").append(fmt(rW)).append(", ");
            sb.append("\"h\": ").append(fmt(rH));
            sb.append(", \"type\": \"").append(esc(ri.type())).append("\"");
            sb.append(", \"pageIndex\": ").append(ri.pageIndex());
            if (Math.abs(ri.absoluteRotationAngle()) > 0.1) {
                sb.append(", \"rotation\": ").append(fmt(ri.absoluteRotationAngle()));
            }
            sb.append(" },\n");

            // 델타 계산
            double dx = rX - idmlX;
            double dy = rY - idmlY;
            double dw = rW - idmlW;
            double dh = rH - idmlH;

            sb.append("          \"delta\": { ");
            sb.append("\"dx\": ").append(fmt(dx)).append(", ");
            sb.append("\"dy\": ").append(fmt(dy)).append(", ");
            sb.append("\"dw\": ").append(fmt(dw)).append(", ");
            sb.append("\"dh\": ").append(fmt(dh));
            sb.append(" },\n");

            // 플래그 판정
            if (Math.abs(dx) > DELTA_THRESHOLD) flags.add("LARGE_X_DELTA");
            if (Math.abs(dy) > DELTA_THRESHOLD) flags.add("LARGE_Y_DELTA");
            if (idmlW > 0 && Math.abs(dw / idmlW) > SIZE_THRESHOLD) flags.add("LARGE_W_DELTA");
            if (idmlH > 0 && Math.abs(dh / idmlH) > SIZE_THRESHOLD) flags.add("LARGE_H_DELTA");
        } else {
            sb.append("          \"resolved\": null,\n");
            sb.append("          \"delta\": null,\n");
            flags.add("NO_RESOLVED");
        }

        // 공통 플래그
        if (hasRotation) flags.add("ROTATED");
        double pgW = page.widthPoints();
        double pgH = page.heightPoints();
        if (idmlX + idmlW < 0 || idmlY + idmlH < 0 || idmlX > pgW || idmlY > pgH) {
            flags.add("OFF_PAGE");
        }

        sb.append("          \"flags\": [");
        for (int fi = 0; fi < flags.size(); fi++) {
            sb.append("\"").append(flags.get(fi)).append("\"");
            if (fi < flags.size() - 1) sb.append(", ");
        }
        sb.append("]\n");

        sb.append("        }");
        return sb.toString();
    }

    private static IDMLSpread findSpreadForPage(IDMLDocument doc, IDMLPage page) {
        for (IDMLSpread spread : doc.spreads()) {
            for (IDMLPage p : spread.pages()) {
                if (p == page) return spread;
            }
        }
        return null;
    }

    private static String fmt(double v) {
        if (v == (long) v) return String.valueOf((long) v);
        return String.format("%.2f", v);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
