package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;

/**
 * IDML 좌표계 유틸리티.
 * GeometricBounds: [top, left, bottom, right] (points)
 * ItemTransform: [a, b, c, d, tx, ty] (2D affine)
 */
public class IDMLGeometry {

    /**
     * GeometricBounds 문자열 "top left bottom right"을 double 배열로 파싱.
     */
    public static double[] parseBounds(String boundsStr) {
        if (boundsStr == null || boundsStr.isEmpty()) {
            return new double[]{0, 0, 0, 0};
        }
        String[] parts = boundsStr.trim().split("\\s+");
        double[] bounds = new double[4];
        for (int i = 0; i < Math.min(parts.length, 4); i++) {
            bounds[i] = Double.parseDouble(parts[i]);
        }
        return bounds;
    }

    /**
     * ItemTransform 문자열 "a b c d tx ty"를 double 배열로 파싱.
     */
    public static double[] parseTransform(String transformStr) {
        if (transformStr == null || transformStr.isEmpty()) {
            return new double[]{1, 0, 0, 1, 0, 0};  // identity
        }
        String[] parts = transformStr.trim().split("\\s+");
        double[] transform = new double[6];
        for (int i = 0; i < Math.min(parts.length, 6); i++) {
            transform[i] = Double.parseDouble(parts[i]);
        }
        return transform;
    }

    /**
     * GeometricBounds에서 너비 (points).
     * bounds: [top, left, bottom, right]
     */
    public static double width(double[] bounds) {
        return bounds[3] - bounds[1];
    }

    /**
     * GeometricBounds에서 높이 (points).
     */
    public static double height(double[] bounds) {
        return bounds[2] - bounds[0];
    }

    /**
     * GeometricBounds의 top-left를 ItemTransform으로 변환한 절대 위치.
     * @return [absoluteX, absoluteY]
     */
    public static double[] absoluteTopLeft(double[] bounds, double[] transform) {
        return CoordinateConverter.applyTransform(transform, bounds[1], bounds[0]);
    }

    /**
     * 프레임의 페이지 상대 위치 계산 (points).
     * @param frameBounds 프레임 GeometricBounds
     * @param frameTransform 프레임 ItemTransform
     * @param pageBounds 페이지 GeometricBounds
     * @param pageTransform 페이지 ItemTransform
     * @return [relativeX, relativeY] (points)
     */
    public static double[] pageRelativePosition(
            double[] frameBounds, double[] frameTransform,
            double[] pageBounds, double[] pageTransform) {
        double[] frameAbs = absoluteTopLeft(frameBounds, frameTransform);
        double[] pageAbs = absoluteTopLeft(pageBounds, pageTransform);
        return new double[]{
                frameAbs[0] - pageAbs[0],
                frameAbs[1] - pageAbs[1]
        };
    }

    /**
     * 프레임이 특정 페이지에 속하는지 판별.
     * 프레임의 중심점이 페이지 영역 안에 있는지 확인.
     */
    public static boolean isFrameOnPage(
            double[] frameBounds, double[] frameTransform,
            double[] pageBounds, double[] pageTransform) {
        // 프레임 중심점
        double frameCenterX = (frameBounds[1] + frameBounds[3]) / 2.0;
        double frameCenterY = (frameBounds[0] + frameBounds[2]) / 2.0;
        double[] frameCenter = CoordinateConverter.applyTransform(
                frameTransform, frameCenterX, frameCenterY);

        // 페이지 영역
        double[] pageTopLeft = CoordinateConverter.applyTransform(
                pageTransform, pageBounds[1], pageBounds[0]);
        double[] pageBottomRight = CoordinateConverter.applyTransform(
                pageTransform, pageBounds[3], pageBounds[2]);

        double pageMinX = Math.min(pageTopLeft[0], pageBottomRight[0]);
        double pageMaxX = Math.max(pageTopLeft[0], pageBottomRight[0]);
        double pageMinY = Math.min(pageTopLeft[1], pageBottomRight[1]);
        double pageMaxY = Math.max(pageTopLeft[1], pageBottomRight[1]);

        return frameCenter[0] >= pageMinX && frameCenter[0] <= pageMaxX
                && frameCenter[1] >= pageMinY && frameCenter[1] <= pageMaxY;
    }
}
