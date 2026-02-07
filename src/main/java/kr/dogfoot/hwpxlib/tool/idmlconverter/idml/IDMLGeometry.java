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
     * GeometricBounds에서 너비 (points) - 변환 미적용.
     * bounds: [top, left, bottom, right]
     */
    public static double width(double[] bounds) {
        return bounds[3] - bounds[1];
    }

    /**
     * GeometricBounds에서 높이 (points) - 변환 미적용.
     */
    public static double height(double[] bounds) {
        return bounds[2] - bounds[0];
    }

    /**
     * 변환이 적용된 실제 너비 계산 (points).
     * 4개 코너를 변환한 후 bounding box의 너비를 반환.
     */
    public static double transformedWidth(double[] bounds, double[] transform) {
        if (transform == null) return width(bounds);
        double[] box = getTransformedBoundingBox(bounds, transform);
        return box[2] - box[0];  // maxX - minX
    }

    /**
     * 변환이 적용된 실제 높이 계산 (points).
     * 4개 코너를 변환한 후 bounding box의 높이를 반환.
     */
    public static double transformedHeight(double[] bounds, double[] transform) {
        if (transform == null) return height(bounds);
        double[] box = getTransformedBoundingBox(bounds, transform);
        return box[3] - box[1];  // maxY - minY
    }

    /**
     * bounds의 4개 코너에 transform을 적용한 후의 bounding box 계산.
     * @return [minX, minY, maxX, maxY]
     */
    public static double[] getTransformedBoundingBox(double[] bounds, double[] transform) {
        // bounds: [top, left, bottom, right]
        double top = bounds[0], left = bounds[1], bottom = bounds[2], right = bounds[3];

        // 4개 코너 변환
        double[] tl = CoordinateConverter.applyTransform(transform, left, top);
        double[] tr = CoordinateConverter.applyTransform(transform, right, top);
        double[] bl = CoordinateConverter.applyTransform(transform, left, bottom);
        double[] br = CoordinateConverter.applyTransform(transform, right, bottom);

        // bounding box
        double minX = Math.min(Math.min(tl[0], tr[0]), Math.min(bl[0], br[0]));
        double maxX = Math.max(Math.max(tl[0], tr[0]), Math.max(bl[0], br[0]));
        double minY = Math.min(Math.min(tl[1], tr[1]), Math.min(bl[1], br[1]));
        double maxY = Math.max(Math.max(tl[1], tr[1]), Math.max(bl[1], br[1]));

        return new double[]{minX, minY, maxX, maxY};
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
     * ItemTransform에서 회전 각도를 추출한다.
     * transform = [a, b, c, d, tx, ty] 에서:
     *   a = scaleX * cos(θ)
     *   b = scaleX * sin(θ)
     *   c = -scaleY * sin(θ)
     *   d = scaleY * cos(θ)
     *
     * 회전 각도 θ = atan2(b, a) (시계 방향 양수, 도 단위로 반환)
     *
     * @param transform ItemTransform 배열 [a, b, c, d, tx, ty]
     * @return 회전 각도 (도, degree) - 시계 방향 양수
     */
    public static double extractRotation(double[] transform) {
        if (transform == null || transform.length < 4) {
            return 0.0;
        }
        double a = transform[0];
        double b = transform[1];

        // atan2(b, a)로 라디안 → 도 변환
        double radians = Math.atan2(b, a);
        double degrees = Math.toDegrees(radians);

        return degrees;
    }

    /**
     * 프레임이 특정 페이지에 속하는지 판별.
     * 프레임의 중심점이 페이지 영역 안에 있는지 확인.
     *
     * 경계 처리: 약간의 허용 오차를 두어 경계 근처의 객체도 포함
     * → 경계에 있는 객체가 누락되지 않도록 함
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

        // 페이지 영역 내부인지 확인 (약간의 여유를 주어 경계 문제 방지)
        double tolerance = 0.1;  // 0.1pt 허용 오차
        return frameCenter[0] >= pageMinX - tolerance && frameCenter[0] <= pageMaxX + tolerance
                && frameCenter[1] >= pageMinY - tolerance && frameCenter[1] <= pageMaxY + tolerance;
    }
}
