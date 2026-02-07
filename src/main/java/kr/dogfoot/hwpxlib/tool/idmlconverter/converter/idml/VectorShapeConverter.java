package kr.dogfoot.hwpxlib.tool.idmlconverter.converter.idml;

import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLGeometry;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLVectorShape;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.IntermediateFrame;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * IDML 벡터 도형을 IntermediateFrame으로 변환한다.
 */
public class VectorShapeConverter {

    private final ColorResolver colorResolver;

    public VectorShapeConverter(ColorResolver colorResolver) {
        this.colorResolver = colorResolver;
    }

    /**
     * 인라인 벡터 도형을 IntermediateFrame으로 변환한다.
     */
    public IntermediateFrame convert(IDMLVectorShape shape,
                                      double[] pageBounds, double[] pageTransform,
                                      int zOrder) {
        if (shape == null || shape.geometricBounds() == null || shape.itemTransform() == null) {
            return null;
        }

        IntermediateFrame iFrame = new IntermediateFrame();
        iFrame.frameId("shape_" + shape.selfId());
        iFrame.frameType("shape");
        iFrame.isInline(true);
        iFrame.zOrder(zOrder);

        // 도형 타입 설정
        setShapeType(iFrame, shape);

        // 좌표 계산 (페이지 상대)
        calculatePosition(iFrame, shape, pageBounds, pageTransform);

        // 색상 설정
        setColors(iFrame, shape);

        // 모서리 둥글기
        setCornerRadius(iFrame, shape);

        // 폴리곤 경로 점 저장
        setPathPoints(iFrame, shape);

        if (isDebugEnabled()) {
            System.err.println("[DEBUG] 인라인 도형 변환: " + shape.selfId()
                    + " | type=" + iFrame.shapeType()
                    + " | pos=(" + iFrame.x() + "," + iFrame.y() + ")"
                    + " | size=" + iFrame.width() + "x" + iFrame.height()
                    + " | fill=" + iFrame.fillColor()
                    + " | stroke=" + iFrame.strokeColor());
        }

        return iFrame;
    }

    private void setShapeType(IntermediateFrame iFrame, IDMLVectorShape shape) {
        switch (shape.shapeType()) {
            case RECTANGLE:
                iFrame.shapeType("rectangle");
                break;
            case OVAL:
                iFrame.shapeType("oval");
                break;
            case POLYGON:
                iFrame.shapeType("polygon");
                break;
            case GRAPHIC_LINE:
                iFrame.shapeType("line");
                break;
            default:
                iFrame.shapeType("rectangle");
        }
    }

    private void calculatePosition(IntermediateFrame iFrame, IDMLVectorShape shape,
                                    double[] pageBounds, double[] pageTransform) {
        double[] shapeBounds = shape.geometricBounds();
        double[] shapeTransform = shape.itemTransform();
        double[] shapeAbs = IDMLGeometry.absoluteTopLeft(shapeBounds, shapeTransform);
        double[] pageAbs = IDMLGeometry.absoluteTopLeft(pageBounds, pageTransform);

        double relX = shapeAbs[0] - pageAbs[0];
        double relY = shapeAbs[1] - pageAbs[1];
        double w = IDMLGeometry.width(shapeBounds);
        double h = IDMLGeometry.height(shapeBounds);

        iFrame.x(CoordinateConverter.pointsToHwpunits(relX));
        iFrame.y(CoordinateConverter.pointsToHwpunits(relY));
        iFrame.width(CoordinateConverter.pointsToHwpunits(w));
        iFrame.height(CoordinateConverter.pointsToHwpunits(h));
    }

    private void setColors(IntermediateFrame iFrame, IDMLVectorShape shape) {
        if (shape.hasFill()) {
            String fillHex = resolveColorRef(shape.fillColor());
            iFrame.fillColor(fillHex);
            iFrame.fillTint(shape.fillTint());
        }

        if (shape.hasStroke()) {
            String strokeHex = resolveColorRef(shape.strokeColor());
            iFrame.strokeColor(strokeHex);
            iFrame.strokeWeight(shape.strokeWeight());
            iFrame.strokeTint(shape.strokeTint());
        }
    }

    private void setCornerRadius(IntermediateFrame iFrame, IDMLVectorShape shape) {
        if (shape.hasRoundedCorners()) {
            iFrame.cornerRadius(shape.cornerRadius());
            iFrame.cornerRadii(shape.cornerRadii());

            double[] shapeBounds = shape.geometricBounds();
            double w = IDMLGeometry.width(shapeBounds);
            double h = IDMLGeometry.height(shapeBounds);
            double maxDim = Math.min(w, h);

            if (maxDim > 0) {
                short ratio = (short) Math.min(50, Math.round(shape.cornerRadius() / maxDim * 100));
                iFrame.cornerRatio(ratio);
            }
        }
    }

    private void setPathPoints(IntermediateFrame iFrame, IDMLVectorShape shape) {
        if (shape.shapeType() == IDMLVectorShape.ShapeType.POLYGON && shape.pathPoints() != null) {
            List<double[]> points = new ArrayList<>();
            for (IDMLVectorShape.PathPoint pt : shape.pathPoints()) {
                points.add(new double[]{ pt.anchorX(), pt.anchorY() });
            }
            iFrame.pathPoints(points);
        }
    }

    /**
     * 색상 참조를 HEX 색상으로 변환한다.
     */
    private String resolveColorRef(String colorRef) {
        return colorResolver.resolve(colorRef);
    }

    private boolean isDebugEnabled() {
        return true; // TODO: ConvertOptions에서 가져오기
    }
}
