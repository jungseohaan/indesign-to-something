package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

import java.util.ArrayList;
import java.util.List;

/**
 * IDML 벡터 도형 (Rectangle, Polygon, Oval, GraphicLine 등).
 * 이미지를 포함하지 않는 순수 벡터 그래픽.
 */
public class IDMLVectorShape {

    public enum ShapeType {
        RECTANGLE,
        POLYGON,
        OVAL,
        GRAPHIC_LINE
    }

    /**
     * 선 끝 모양 (Line Cap).
     */
    public enum LineCap {
        BUTT,       // 끝이 잘린 형태
        ROUND,      // 둥근 끝
        PROJECTING  // 돌출 (Square)
    }

    /**
     * 선 연결 모양 (Line Join).
     */
    public enum LineJoin {
        MITER,      // 뾰족한 연결
        ROUND,      // 둥근 연결
        BEVEL       // 각진 연결
    }

    /**
     * PathPoint: 베지어 곡선 정의.
     * anchor = 실제 점
     * leftDirection = 앵커로 들어오는 방향 핸들
     * rightDirection = 앵커에서 나가는 방향 핸들
     */
    public static class PathPoint {
        private double anchorX;
        private double anchorY;
        private double leftX;
        private double leftY;
        private double rightX;
        private double rightY;

        public PathPoint(double anchorX, double anchorY,
                         double leftX, double leftY,
                         double rightX, double rightY) {
            this.anchorX = anchorX;
            this.anchorY = anchorY;
            this.leftX = leftX;
            this.leftY = leftY;
            this.rightX = rightX;
            this.rightY = rightY;
        }

        public double anchorX() { return anchorX; }
        public double anchorY() { return anchorY; }
        public double leftX() { return leftX; }
        public double leftY() { return leftY; }
        public double rightX() { return rightX; }
        public double rightY() { return rightY; }

        /**
         * 직선인지 확인 (핸들이 앵커와 같은 위치).
         */
        public boolean isStraight() {
            return Math.abs(anchorX - leftX) < 0.001 && Math.abs(anchorY - leftY) < 0.001
                    && Math.abs(anchorX - rightX) < 0.001 && Math.abs(anchorY - rightY) < 0.001;
        }
    }

    /**
     * SubPath: 하나의 연속 경로 (GeometryPathType 하나에 대응).
     * 복합 글리프는 여러 SubPath로 구성됨.
     */
    public static class SubPath {
        private List<PathPoint> points;
        private boolean open;  // true = 열린 경로 (선), false = 닫힌 경로 (면)

        public SubPath(boolean open) {
            this.points = new ArrayList<>();
            this.open = open;
        }

        public List<PathPoint> points() { return points; }
        public void addPoint(PathPoint p) { points.add(p); }
        public boolean isOpen() { return open; }
        public int size() { return points.size(); }
    }

    private String selfId;
    private ShapeType shapeType;
    private double[] geometricBounds;  // [top, left, bottom, right]
    private double[] itemTransform;    // [a, b, c, d, tx, ty]
    private List<PathPoint> pathPoints;
    private boolean pathOpen;          // true = 열린 경로 (선), false = 닫힌 경로 (면)
    private List<SubPath> subPaths;    // 복합 경로 (여러 GeometryPathType)

    // 스타일
    private String fillColor;          // "Color/xxx" 참조 또는 "None"
    private String strokeColor;
    private double strokeWeight;
    private double cornerRadius;
    private double[] cornerRadii;      // [topLeft, topRight, bottomLeft, bottomRight]

    // 선 스타일
    private LineCap startCap;          // 시작점 끝 모양
    private LineCap endCap;            // 끝점 끝 모양
    private LineJoin lineJoin;         // 선 연결 모양
    private double miterLimit;         // Miter 한계값
    private double[] dashPattern;      // 점선 패턴 (예: [4, 2] = 4pt 선, 2pt 간격)

    public IDMLVectorShape() {
        this.pathPoints = new ArrayList<>();
        this.subPaths = new ArrayList<>();
        this.itemTransform = new double[]{1, 0, 0, 1, 0, 0};
        this.strokeWeight = 1.0;
        this.cornerRadius = 0;
        this.startCap = LineCap.BUTT;
        this.endCap = LineCap.BUTT;
        this.lineJoin = LineJoin.MITER;
        this.miterLimit = 4.0;
    }

    // === Getters/Setters ===

    public String selfId() { return selfId; }
    public void selfId(String v) { this.selfId = v; }

    public ShapeType shapeType() { return shapeType; }
    public void shapeType(ShapeType v) { this.shapeType = v; }

    public double[] geometricBounds() { return geometricBounds; }
    public void geometricBounds(double[] v) { this.geometricBounds = v; }

    public double[] itemTransform() { return itemTransform; }
    public void itemTransform(double[] v) { this.itemTransform = v; }

    public List<PathPoint> pathPoints() { return pathPoints; }
    public void addPathPoint(PathPoint p) { this.pathPoints.add(p); }

    public boolean pathOpen() { return pathOpen; }
    public void pathOpen(boolean v) { this.pathOpen = v; }

    public List<SubPath> subPaths() { return subPaths; }
    public void addSubPath(SubPath sp) { this.subPaths.add(sp); }
    public boolean hasSubPaths() { return subPaths != null && !subPaths.isEmpty(); }

    /**
     * 새 SubPath를 시작하고 반환.
     */
    public SubPath startNewSubPath(boolean open) {
        SubPath sp = new SubPath(open);
        subPaths.add(sp);
        return sp;
    }

    public String fillColor() { return fillColor; }
    public void fillColor(String v) { this.fillColor = v; }

    public String strokeColor() { return strokeColor; }
    public void strokeColor(String v) { this.strokeColor = v; }

    public double strokeWeight() { return strokeWeight; }
    public void strokeWeight(double v) { this.strokeWeight = v; }

    public double cornerRadius() { return cornerRadius; }
    public void cornerRadius(double v) { this.cornerRadius = v; }

    public double[] cornerRadii() { return cornerRadii; }
    public void cornerRadii(double[] v) { this.cornerRadii = v; }

    public LineCap startCap() { return startCap; }
    public void startCap(LineCap v) { this.startCap = v; }

    public LineCap endCap() { return endCap; }
    public void endCap(LineCap v) { this.endCap = v; }

    public LineJoin lineJoin() { return lineJoin; }
    public void lineJoin(LineJoin v) { this.lineJoin = v; }

    public double miterLimit() { return miterLimit; }
    public void miterLimit(double v) { this.miterLimit = v; }

    public double[] dashPattern() { return dashPattern; }
    public void dashPattern(double[] v) { this.dashPattern = v; }

    /**
     * 점선 패턴이 있는지 확인.
     */
    public boolean hasDashPattern() {
        return dashPattern != null && dashPattern.length > 0;
    }

    // === Computed properties ===

    public double widthPoints() {
        if (geometricBounds != null && geometricBounds.length >= 4) {
            return geometricBounds[3] - geometricBounds[1];
        }
        return 0;
    }

    public double heightPoints() {
        if (geometricBounds != null && geometricBounds.length >= 4) {
            return geometricBounds[2] - geometricBounds[0];
        }
        return 0;
    }

    /**
     * 채우기가 있는지 확인.
     */
    public boolean hasFill() {
        return fillColor != null && !fillColor.isEmpty()
                && !"None".equals(fillColor) && !fillColor.contains("[None]");
    }

    /**
     * 선이 있는지 확인.
     */
    public boolean hasStroke() {
        return strokeColor != null && !strokeColor.isEmpty()
                && !"None".equals(strokeColor) && !strokeColor.contains("[None]")
                && strokeWeight > 0;
    }

    /**
     * 모서리가 둥근지 확인.
     */
    public boolean hasRoundedCorners() {
        if (cornerRadius > 0) return true;
        if (cornerRadii != null) {
            for (double r : cornerRadii) {
                if (r > 0) return true;
            }
        }
        return false;
    }

    /**
     * 베지어 곡선이 포함되어 있는지 확인.
     */
    public boolean hasCurves() {
        for (PathPoint p : pathPoints) {
            if (!p.isStraight()) return true;
        }
        return false;
    }
}
