package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLVectorShape;

import java.util.List;
import java.util.Locale;

/**
 * IDMLVectorShape를 SVG XML 문자열로 변환한다.
 * ASTImageLoader의 AWT 래스터화 로직을 SVG path 생성으로 치환.
 */
public class SvgGenerator {

    public static class SvgResult {
        public String svgXml;
        public double widthPts;
        public double heightPts;
    }

    /**
     * 단일 벡터 도형 → SVG.
     */
    public static SvgResult generate(IDMLVectorShape shape, String fillHex, String strokeHex) {
        double[] bounds = shape.geometricBounds();
        if (bounds == null || bounds.length < 4) return null;

        double wPts = bounds[3] - bounds[1];
        double hPts = bounds[2] - bounds[0];
        if ((wPts <= 0 || hPts <= 0) && shape.hasStroke() && shape.strokeWeight() > 0) {
            double minDim = shape.strokeWeight();
            if (wPts <= 0) wPts = minDim;
            if (hPts <= 0) hPts = minDim;
        }
        if (wPts <= 0 || hPts <= 0) return null;

        // stroke padding
        double strokePad = shape.hasStroke() ? shape.strokeWeight() / 2.0 + 0.5 : 0;
        double svgW = wPts + strokePad * 2;
        double svgH = hPts + strokePad * 2;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US,
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %.2f %.2f\" width=\"%.2fpt\" height=\"%.2fpt\">\n",
                svgW, svgH, svgW, svgH));

        // 둥근 사각형 최적화 (Rectangle 또는 직선 4점 Polygon)
        if (isRoundableRect(shape)) {
            appendRoundedRect(sb, shape, strokePad, strokePad, wPts, hPts, fillHex, strokeHex);
        } else {
            // 일반 path
            String pathD = buildSvgPathD(shape, strokePad, strokePad);
            if (pathD == null || pathD.isEmpty()) return null;

            String fillRule = shape.hasSubPaths() ? " fill-rule=\"evenodd\"" : "";
            sb.append(String.format("  <path d=\"%s\"%s", pathD, fillRule));
            appendStyleAttrs(sb, fillHex, strokeHex, shape);
            sb.append("/>\n");
        }

        sb.append("</svg>");

        SvgResult result = new SvgResult();
        result.svgXml = sb.toString();
        result.widthPts = svgW;
        result.heightPts = svgH;
        return result;
    }

    /**
     * 여러 벡터 도형 합성 → SVG.
     */
    public static SvgResult generate(List<ASTImageLoader.ShapeWithColor> shapes,
                                      double[] groupTransform) {
        if (shapes == null || shapes.isEmpty()) return null;

        // 합산 bounding box
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (ASTImageLoader.ShapeWithColor sc : shapes) {
            double[] b = sc.transformedBounds();
            if (b == null || b.length < 4) continue;
            if (b[1] < minX) minX = b[1];
            if (b[0] < minY) minY = b[0];
            if (b[3] > maxX) maxX = b[3];
            if (b[2] > maxY) maxY = b[2];
        }
        // 수평/수직 GraphicLine 등 한 축이 0인 경우 stroke width만큼 확장
        if (minY >= maxY || minX >= maxX) {
            double maxStroke = 0;
            for (ASTImageLoader.ShapeWithColor sc : shapes) {
                if (sc.shape.strokeWeight() > 0 && sc.strokeHex != null) {
                    maxStroke = Math.max(maxStroke, sc.shape.strokeWeight());
                }
            }
            if (maxStroke > 0) {
                // 점선 패턴이 화면에서 보이려면 최소 3pt 높이 필요
                boolean hasDash = false;
                for (ASTImageLoader.ShapeWithColor sc : shapes) {
                    if (sc.shape.hasDashPattern()) { hasDash = true; break; }
                }
                double minPad = hasDash ? 1.5 : (maxStroke / 2.0 + 0.5);
                double pad = Math.max(maxStroke / 2.0 + 0.5, minPad);
                if (minY >= maxY) { minY -= pad; maxY += pad; }
                if (minX >= maxX) { minX -= pad; maxX += pad; }
            }
        }
        if (minX >= maxX || minY >= maxY) return null;

        double wPts = maxX - minX;
        double hPts = maxY - minY;
        double strokePad = 1;
        double svgW = wPts + strokePad * 2;
        double svgH = hPts + strokePad * 2;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US,
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %.2f %.2f\" width=\"%.2fpt\" height=\"%.2fpt\">\n",
                svgW, svgH, svgW, svgH));

        for (ASTImageLoader.ShapeWithColor sc : shapes) {
            double[] tb = sc.transformedBounds();
            if (tb == null || tb.length < 4) continue;

            double offX = tb[1] - minX + strokePad;
            double offY = tb[0] - minY + strokePad;
            double sw = tb[3] - tb[1];
            double sh = tb[2] - tb[0];
            if ((sw <= 0 || sh <= 0) && sc.accTransform == null) continue;

            // 둥근 사각형 최적화 (Rectangle 또는 직선 4점 Polygon)
            if (isRoundableRect(sc.shape)) {
                appendRoundedRect(sb, sc.shape, offX, offY, sw, sh, sc.fillHex, sc.strokeHex);
            } else {
                String pathD;
                if (sc.accTransform != null) {
                    pathD = buildTransformedSvgPathD(sc.shape, sc.accTransform,
                            minX - strokePad, minY - strokePad);
                } else {
                    pathD = buildSvgPathD(sc.shape, offX, offY);
                }
                if (pathD == null || pathD.isEmpty()) continue;

                String fillRule = sc.shape.hasSubPaths() ? " fill-rule=\"evenodd\"" : "";
                sb.append(String.format("  <path d=\"%s\"%s", pathD, fillRule));
                appendStyleAttrs(sb, sc.fillHex, sc.strokeHex, sc.shape);
                sb.append("/>\n");
            }
        }

        sb.append("</svg>");

        SvgResult result = new SvgResult();
        result.svgXml = sb.toString();
        result.widthPts = svgW;
        result.heightPts = svgH;
        return result;
    }

    // =========================================================================
    // SVG path d 생성
    // =========================================================================

    /**
     * PathPoints → SVG path d 문자열.
     * ASTImageLoader.buildPathFromPoints() 대응.
     */
    private static String buildSvgPathD(IDMLVectorShape shape, double offX, double offY) {
        double[] bounds = shape.geometricBounds();
        double baseOffX = (bounds != null) ? -bounds[1] + offX : offX;
        double baseOffY = (bounds != null) ? -bounds[0] + offY : offY;

        StringBuilder d = new StringBuilder();

        if (shape.hasSubPaths()) {
            for (IDMLVectorShape.SubPath sub : shape.subPaths()) {
                appendSubPathD(d, sub.points(), sub.isOpen(), baseOffX, baseOffY);
            }
        } else if (shape.pathPoints() != null && !shape.pathPoints().isEmpty()) {
            appendSubPathD(d, shape.pathPoints(), shape.pathOpen(), baseOffX, baseOffY);
        }

        return d.toString().trim();
    }

    /**
     * 변환 적용된 PathPoints → SVG path d.
     * ASTImageLoader.buildTransformedPath() 대응.
     */
    private static String buildTransformedSvgPathD(IDMLVectorShape shape,
                                                     double[] accTransform,
                                                     double canvasMinX, double canvasMinY) {
        double ta = accTransform[0], tb = accTransform[1];
        double tc = accTransform[2], td = accTransform[3];
        double ttx = accTransform[4], tty = accTransform[5];

        StringBuilder d = new StringBuilder();

        java.util.List<java.util.List<IDMLVectorShape.PathPoint>> allPointSets =
                new java.util.ArrayList<java.util.List<IDMLVectorShape.PathPoint>>();
        java.util.List<Boolean> allOpenFlags = new java.util.ArrayList<Boolean>();

        if (shape.hasSubPaths()) {
            for (IDMLVectorShape.SubPath sub : shape.subPaths()) {
                allPointSets.add(sub.points());
                allOpenFlags.add(sub.isOpen());
            }
        } else if (shape.pathPoints() != null && !shape.pathPoints().isEmpty()) {
            allPointSets.add(shape.pathPoints());
            allOpenFlags.add(shape.pathOpen());
        }

        for (int s = 0; s < allPointSets.size(); s++) {
            java.util.List<IDMLVectorShape.PathPoint> points = allPointSets.get(s);
            boolean isOpen = allOpenFlags.get(s);
            if (points.isEmpty()) continue;

            for (int i = 0; i < points.size(); i++) {
                IDMLVectorShape.PathPoint pt = points.get(i);
                double ax = ta * pt.anchorX() + tc * pt.anchorY() + ttx - canvasMinX;
                double ay = tb * pt.anchorX() + td * pt.anchorY() + tty - canvasMinY;

                if (i == 0) {
                    d.append(String.format(Locale.US, "M%.2f %.2f ", ax, ay));
                } else {
                    IDMLVectorShape.PathPoint prev = points.get(i - 1);
                    double prx = ta * prev.rightX() + tc * prev.rightY() + ttx - canvasMinX;
                    double pry = tb * prev.rightX() + td * prev.rightY() + tty - canvasMinY;
                    double lx = ta * pt.leftX() + tc * pt.leftY() + ttx - canvasMinX;
                    double ly = tb * pt.leftX() + td * pt.leftY() + tty - canvasMinY;

                    double prevAx = ta * prev.anchorX() + tc * prev.anchorY() + ttx - canvasMinX;
                    double prevAy = tb * prev.anchorX() + td * prev.anchorY() + tty - canvasMinY;

                    boolean isBezier = (Math.abs(prx - prevAx) > 0.01)
                            || (Math.abs(lx - ax) > 0.01)
                            || (Math.abs(pry - prevAy) > 0.01)
                            || (Math.abs(ly - ay) > 0.01);

                    if (isBezier) {
                        d.append(String.format(Locale.US, "C%.2f %.2f %.2f %.2f %.2f %.2f ",
                                prx, pry, lx, ly, ax, ay));
                    } else {
                        d.append(String.format(Locale.US, "L%.2f %.2f ", ax, ay));
                    }
                }
            }

            // 닫힌 경로
            if (!isOpen && points.size() > 1) {
                IDMLVectorShape.PathPoint last = points.get(points.size() - 1);
                IDMLVectorShape.PathPoint first = points.get(0);
                double lrx = ta * last.rightX() + tc * last.rightY() + ttx - canvasMinX;
                double lry = tb * last.rightX() + td * last.rightY() + tty - canvasMinY;
                double flx = ta * first.leftX() + tc * first.leftY() + ttx - canvasMinX;
                double fly = tb * first.leftX() + td * first.leftY() + tty - canvasMinY;
                double fax = ta * first.anchorX() + tc * first.anchorY() + ttx - canvasMinX;
                double fay = tb * first.anchorX() + td * first.anchorY() + tty - canvasMinY;

                boolean isBezier = true; // closing segment - use curve for fidelity
                double lastAx = ta * last.anchorX() + tc * last.anchorY() + ttx - canvasMinX;
                double lastAy = tb * last.anchorX() + td * last.anchorY() + tty - canvasMinY;
                if (Math.abs(lrx - lastAx) < 0.01 && Math.abs(flx - fax) < 0.01
                        && Math.abs(lry - lastAy) < 0.01 && Math.abs(fly - fay) < 0.01) {
                    isBezier = false;
                }

                if (isBezier) {
                    d.append(String.format(Locale.US, "C%.2f %.2f %.2f %.2f %.2f %.2f ",
                            lrx, lry, flx, fly, fax, fay));
                }
                d.append("Z ");
            }
        }

        return d.toString().trim();
    }

    /**
     * 단일 sub-path의 points를 SVG path d에 추가.
     * ASTImageLoader.appendSubPath() 대응.
     */
    private static void appendSubPathD(StringBuilder d,
                                         java.util.List<IDMLVectorShape.PathPoint> points,
                                         boolean isOpen,
                                         double baseOffX, double baseOffY) {
        if (points == null || points.isEmpty()) return;

        for (int i = 0; i < points.size(); i++) {
            IDMLVectorShape.PathPoint pt = points.get(i);
            double ax = pt.anchorX() + baseOffX;
            double ay = pt.anchorY() + baseOffY;

            if (i == 0) {
                d.append(String.format(Locale.US, "M%.2f %.2f ", ax, ay));
            } else {
                IDMLVectorShape.PathPoint prev = points.get(i - 1);
                double prx = prev.rightX() + baseOffX;
                double pry = prev.rightY() + baseOffY;
                double lx = pt.leftX() + baseOffX;
                double ly = pt.leftY() + baseOffY;

                double prevAx = prev.anchorX() + baseOffX;
                double prevAy = prev.anchorY() + baseOffY;

                boolean isBezier = (Math.abs(prx - prevAx) > 0.01)
                        || (Math.abs(lx - ax) > 0.01)
                        || (Math.abs(pry - prevAy) > 0.01)
                        || (Math.abs(ly - ay) > 0.01);

                if (isBezier) {
                    d.append(String.format(Locale.US, "C%.2f %.2f %.2f %.2f %.2f %.2f ",
                            prx, pry, lx, ly, ax, ay));
                } else {
                    d.append(String.format(Locale.US, "L%.2f %.2f ", ax, ay));
                }
            }
        }

        // 닫힌 경로
        if (!isOpen && points.size() > 1) {
            IDMLVectorShape.PathPoint last = points.get(points.size() - 1);
            IDMLVectorShape.PathPoint first = points.get(0);
            double lrx = last.rightX() + baseOffX;
            double lry = last.rightY() + baseOffY;
            double flx = first.leftX() + baseOffX;
            double fly = first.leftY() + baseOffY;
            double fax = first.anchorX() + baseOffX;
            double fay = first.anchorY() + baseOffY;

            double lastAx = last.anchorX() + baseOffX;
            double lastAy = last.anchorY() + baseOffY;
            boolean isBezier = (Math.abs(lrx - lastAx) > 0.01)
                    || (Math.abs(flx - fax) > 0.01)
                    || (Math.abs(lry - lastAy) > 0.01)
                    || (Math.abs(fly - fay) > 0.01);

            if (isBezier) {
                d.append(String.format(Locale.US, "C%.2f %.2f %.2f %.2f %.2f %.2f ",
                        lrx, lry, flx, fly, fax, fay));
            }
            d.append("Z ");
        }
    }

    // =========================================================================
    // SVG 스타일 속성
    // =========================================================================

    private static void appendStyleAttrs(StringBuilder sb, String fillHex, String strokeHex,
                                          IDMLVectorShape shape) {
        // Fill
        if (fillHex != null) {
            sb.append(String.format(" fill=\"%s\"", fillHex));
            if (shape.fillTint() < 100) {
                sb.append(String.format(Locale.US, " fill-opacity=\"%.2f\"",
                        shape.fillTint() / 100.0));
            }
        } else {
            sb.append(" fill=\"none\"");
        }

        // Stroke
        if (strokeHex != null && shape.strokeWeight() > 0) {
            sb.append(String.format(" stroke=\"%s\"", strokeHex));
            sb.append(String.format(Locale.US, " stroke-width=\"%.2f\"", shape.strokeWeight()));
            if (shape.strokeTint() < 100) {
                sb.append(String.format(Locale.US, " stroke-opacity=\"%.2f\"",
                        shape.strokeTint() / 100.0));
            }

            // Line cap
            if (shape.endCap() != null) {
                switch (shape.endCap()) {
                    case ROUND: sb.append(" stroke-linecap=\"round\""); break;
                    case PROJECTING: sb.append(" stroke-linecap=\"square\""); break;
                    default: break; // BUTT is SVG default
                }
            }

            // Line join
            if (shape.lineJoin() != null) {
                switch (shape.lineJoin()) {
                    case ROUND: sb.append(" stroke-linejoin=\"round\""); break;
                    case BEVEL: sb.append(" stroke-linejoin=\"bevel\""); break;
                    default: break; // MITER is SVG default
                }
            }

            // Miter limit
            if (shape.miterLimit() != 4.0) {
                sb.append(String.format(Locale.US, " stroke-miterlimit=\"%.1f\"",
                        shape.miterLimit()));
            }

            // Dash pattern
            if (shape.hasDashPattern()) {
                sb.append(" stroke-dasharray=\"");
                double[] dash = shape.dashPattern();
                for (int i = 0; i < dash.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append(String.format(Locale.US, "%.1f", dash[i]));
                }
                sb.append("\"");
            }
        } else if (strokeHex == null) {
            sb.append(" stroke=\"none\"");
        }
    }

    /**
     * 둥근 사각형을 SVG에 추가한다.
     * per-corner 반경이 있으면 &lt;path&gt;, 균일하면 &lt;rect rx ry&gt; 사용.
     */
    private static void appendRoundedRect(StringBuilder sb, IDMLVectorShape shape,
                                           double x, double y, double w, double h,
                                           String fillHex, String strokeHex) {
        double maxR = Math.min(w, h) / 2.0;
        double[] radii = shape.cornerRadii();

        if (radii != null && hasVariedRadii(radii)) {
            // per-corner: [topLeft, topRight, bottomLeft, bottomRight]
            double rTL = Math.min(radii[0] >= 0 ? radii[0] : shape.cornerRadius(), maxR);
            double rTR = Math.min(radii[1] >= 0 ? radii[1] : shape.cornerRadius(), maxR);
            double rBL = Math.min(radii[2] >= 0 ? radii[2] : shape.cornerRadius(), maxR);
            double rBR = Math.min(radii[3] >= 0 ? radii[3] : shape.cornerRadius(), maxR);

            String d = String.format(Locale.US,
                    "M %.2f %.2f L %.2f %.2f A %.2f %.2f 0 0 1 %.2f %.2f " +
                    "L %.2f %.2f A %.2f %.2f 0 0 1 %.2f %.2f " +
                    "L %.2f %.2f A %.2f %.2f 0 0 1 %.2f %.2f " +
                    "L %.2f %.2f A %.2f %.2f 0 0 1 %.2f %.2f Z",
                    // 상단변 (TL → TR)
                    x + rTL, y,
                    x + w - rTR, y,
                    rTR, rTR, x + w, y + rTR,
                    // 우측변 (TR → BR)
                    x + w, y + h - rBR,
                    rBR, rBR, x + w - rBR, y + h,
                    // 하단변 (BR → BL)
                    x + rBL, y + h,
                    rBL, rBL, x, y + h - rBL,
                    // 좌측변 (BL → TL)
                    x, y + rTL,
                    rTL, rTL, x + rTL, y);
            sb.append(String.format("  <path d=\"%s\"", d));
        } else {
            double r = Math.min(shape.cornerRadius(), maxR);
            sb.append(String.format(Locale.US,
                    "  <rect x=\"%.2f\" y=\"%.2f\" width=\"%.2f\" height=\"%.2f\" rx=\"%.2f\" ry=\"%.2f\"",
                    x, y, w, h, r, r));
        }
        appendStyleAttrs(sb, fillHex, strokeHex, shape);
        sb.append("/>\n");
    }

    /**
     * 둥근 사각형으로 처리할 수 있는 도형인지 판별.
     * Rectangle 또는 직선 4점 Polygon이면서 CornerRadius가 있는 경우.
     */
    private static boolean isRoundableRect(IDMLVectorShape shape) {
        if (!shape.hasRoundedCorners()) return false;
        if (hasNonRectangularPath(shape)) return false;
        IDMLVectorShape.ShapeType st = shape.shapeType();
        return st == IDMLVectorShape.ShapeType.RECTANGLE
                || st == IDMLVectorShape.ShapeType.POLYGON;
    }

    private static boolean hasVariedRadii(double[] radii) {
        if (radii == null || radii.length < 4) return false;
        double first = radii[0];
        for (int i = 1; i < 4; i++) {
            if (Math.abs(radii[i] - first) > 0.01) return true;
        }
        return false;
    }

    // =========================================================================
    // Utility
    // =========================================================================

    /**
     * 비사각형 패스 검출 (ASTImageLoader.hasNonRectangularPath와 동일).
     */
    private static boolean hasNonRectangularPath(IDMLVectorShape shape) {
        if (shape.hasSubPaths()) {
            for (IDMLVectorShape.SubPath sub : shape.subPaths()) {
                if (sub.points().size() != 4) return true;
                for (IDMLVectorShape.PathPoint pt : sub.points()) {
                    if (!pt.isStraight()) return true;
                }
            }
            return false;
        }
        List<IDMLVectorShape.PathPoint> pts = shape.pathPoints();
        if (pts == null || pts.isEmpty()) return false;
        if (pts.size() != 4) return true;
        for (IDMLVectorShape.PathPoint pt : pts) {
            if (!pt.isStraight()) return true;
        }
        return false;
    }
}
