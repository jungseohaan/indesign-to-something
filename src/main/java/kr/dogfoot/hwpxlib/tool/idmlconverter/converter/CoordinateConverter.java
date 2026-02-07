package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

/**
 * IDML (Adobe Points) <-> HWPX (HWPUNIT) 좌표 변환.
 *
 * <ul>
 *   <li>1 Adobe Point = 1/72 inch</li>
 *   <li>1 HWPUNIT = 1/7200 inch</li>
 *   <li>1 Point = 100 HWPUNIT</li>
 * </ul>
 */
public class CoordinateConverter {

    public static final double POINTS_TO_HWPUNITS = 100.0;

    /**
     * Adobe Points -> HWPUNIT.
     */
    public static long pointsToHwpunits(double points) {
        return Math.round(points * POINTS_TO_HWPUNITS);
    }

    /**
     * HWPUNIT -> Adobe Points.
     */
    public static double hwpunitsToPoints(long hwpunits) {
        return hwpunits / POINTS_TO_HWPUNITS;
    }

    /**
     * IDML 폰트 크기(pt) -> HWPX CharPr height.
     * HWPX에서 1pt = 100 height units.
     */
    public static int fontSizeToHeight(double pointSize) {
        return (int) Math.round(pointSize * 100);
    }

    /**
     * HWPX CharPr height -> 폰트 크기(pt).
     */
    public static double heightToFontSize(int height) {
        return height / 100.0;
    }

    /**
     * IDML 아핀 변환 행렬 [a, b, c, d, tx, ty]을 점 (x, y)에 적용.
     *
     * 행렬:
     * | a  c  tx |   | x |   | a*x + c*y + tx |
     * | b  d  ty | * | y | = | b*x + d*y + ty |
     * | 0  0   1 |   | 1 |   |       1        |
     *
     * @return [transformedX, transformedY]
     */
    public static double[] applyTransform(double[] transform, double x, double y) {
        double a = transform[0], b = transform[1];
        double c = transform[2], d = transform[3];
        double tx = transform[4], ty = transform[5];
        return new double[]{
                a * x + c * y + tx,
                b * x + d * y + ty
        };
    }

    /**
     * 두 아핀 변환 행렬을 결합한다 (parent * child).
     * 결과: child 변환을 먼저 적용한 뒤 parent 변환을 적용하는 행렬.
     *
     * @param parent 부모 변환 [a1, b1, c1, d1, tx1, ty1]
     * @param child  자식 변환 [a2, b2, c2, d2, tx2, ty2]
     * @return 결합된 변환 [a, b, c, d, tx, ty]
     */
    public static double[] combineTransforms(double[] parent, double[] child) {
        double a1 = parent[0], b1 = parent[1], c1 = parent[2], d1 = parent[3];
        double tx1 = parent[4], ty1 = parent[5];
        double a2 = child[0], b2 = child[1], c2 = child[2], d2 = child[3];
        double tx2 = child[4], ty2 = child[5];
        return new double[]{
                a1 * a2 + c1 * b2,
                b1 * a2 + d1 * b2,
                a1 * c2 + c1 * d2,
                b1 * c2 + d1 * d2,
                a1 * tx2 + c1 * ty2 + tx1,
                b1 * tx2 + d1 * ty2 + ty1
        };
    }

    /**
     * mm -> HWPUNIT 변환. 1mm = 283.4645... hwpunits.
     */
    public static long mmToHwpunits(double mm) {
        return Math.round(mm * 7200.0 / 25.4);
    }

    /**
     * HWPUNIT -> mm 변환.
     */
    public static double hwpunitsToMm(long hwpunits) {
        return hwpunits * 25.4 / 7200.0;
    }

    /**
     * 좌표 값을 로그용 문자열로 변환 (소수점 5자리).
     */
    public static String fmt(double value) {
        return String.format("%.5f", value);
    }

    /**
     * 좌표 값을 로그용 문자열로 변환 (정수).
     */
    public static String fmtInt(double value) {
        return String.format("%.0f", value);
    }
}
