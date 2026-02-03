package kr.dogfoot.hwpxlib.tool.idmlconverter;

import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import org.junit.Assert;
import org.junit.Test;

public class TestCoordinateConverter {

    @Test
    public void testPointsToHwpunits() {
        // 1pt = 100 hwpunits
        Assert.assertEquals(100, CoordinateConverter.pointsToHwpunits(1.0));
        Assert.assertEquals(0, CoordinateConverter.pointsToHwpunits(0.0));
        Assert.assertEquals(500, CoordinateConverter.pointsToHwpunits(5.0));
        Assert.assertEquals(1000, CoordinateConverter.pointsToHwpunits(10.0));
    }

    @Test
    public void testPointsToHwpunitsRounding() {
        // 소수점 반올림
        Assert.assertEquals(105, CoordinateConverter.pointsToHwpunits(1.05));
        Assert.assertEquals(95, CoordinateConverter.pointsToHwpunits(0.95));
        Assert.assertEquals(75, CoordinateConverter.pointsToHwpunits(0.75));
    }

    @Test
    public void testHwpunitsToPoints() {
        Assert.assertEquals(1.0, CoordinateConverter.hwpunitsToPoints(100), 0.001);
        Assert.assertEquals(0.0, CoordinateConverter.hwpunitsToPoints(0), 0.001);
        Assert.assertEquals(5.0, CoordinateConverter.hwpunitsToPoints(500), 0.001);
    }

    @Test
    public void testA4PageSize() {
        // A4: 595.28pt x 841.89pt → HWPUNIT: 59528 x 84189
        long widthHu = CoordinateConverter.pointsToHwpunits(595.28);
        long heightHu = CoordinateConverter.pointsToHwpunits(841.89);
        Assert.assertEquals(59528, widthHu);
        Assert.assertEquals(84189, heightHu);
    }

    @Test
    public void testFontSizeToHeight() {
        // 1pt = 100 height units
        Assert.assertEquals(1000, CoordinateConverter.fontSizeToHeight(10.0));
        Assert.assertEquals(1200, CoordinateConverter.fontSizeToHeight(12.0));
        Assert.assertEquals(700, CoordinateConverter.fontSizeToHeight(7.0));
        Assert.assertEquals(1150, CoordinateConverter.fontSizeToHeight(11.5));
    }

    @Test
    public void testHeightToFontSize() {
        Assert.assertEquals(10.0, CoordinateConverter.heightToFontSize(1000), 0.001);
        Assert.assertEquals(12.0, CoordinateConverter.heightToFontSize(1200), 0.001);
        Assert.assertEquals(11.5, CoordinateConverter.heightToFontSize(1150), 0.001);
    }

    @Test
    public void testApplyTransformIdentity() {
        // 단위 행렬: [1, 0, 0, 1, 0, 0]
        double[] identity = {1, 0, 0, 1, 0, 0};
        double[] result = CoordinateConverter.applyTransform(identity, 10, 20);
        Assert.assertEquals(10.0, result[0], 0.001);
        Assert.assertEquals(20.0, result[1], 0.001);
    }

    @Test
    public void testApplyTransformTranslation() {
        // 이동 행렬: [1, 0, 0, 1, 100, 200]
        double[] translation = {1, 0, 0, 1, 100, 200};
        double[] result = CoordinateConverter.applyTransform(translation, 10, 20);
        Assert.assertEquals(110.0, result[0], 0.001);
        Assert.assertEquals(220.0, result[1], 0.001);
    }

    @Test
    public void testApplyTransformScale() {
        // 2배 스케일: [2, 0, 0, 2, 0, 0]
        double[] scale = {2, 0, 0, 2, 0, 0};
        double[] result = CoordinateConverter.applyTransform(scale, 10, 20);
        Assert.assertEquals(20.0, result[0], 0.001);
        Assert.assertEquals(40.0, result[1], 0.001);
    }

    @Test
    public void testApplyTransformCombined() {
        // 스케일 + 이동: [2, 0, 0, 2, 50, 100]
        double[] combined = {2, 0, 0, 2, 50, 100};
        double[] result = CoordinateConverter.applyTransform(combined, 10, 20);
        Assert.assertEquals(70.0, result[0], 0.001);  // 2*10 + 0*20 + 50
        Assert.assertEquals(140.0, result[1], 0.001); // 0*10 + 2*20 + 100
    }

    @Test
    public void testMmToHwpunits() {
        // A4: 210mm x 297mm
        long width = CoordinateConverter.mmToHwpunits(210.0);
        long height = CoordinateConverter.mmToHwpunits(297.0);
        // 210mm = 210 * 7200 / 25.4 = 59528
        Assert.assertEquals(59528, width);
        // 297mm = 297 * 7200 / 25.4 = 84189
        Assert.assertEquals(84189, height);
    }

    @Test
    public void testHwpunitsToMm() {
        Assert.assertEquals(210.0, CoordinateConverter.hwpunitsToMm(59528), 0.1);
        Assert.assertEquals(297.0, CoordinateConverter.hwpunitsToMm(84189), 0.1);
    }

    @Test
    public void testRoundtripPointsHwpunits() {
        // 정수 points는 왕복 변환 후 동일해야 함
        for (int pt = 0; pt <= 1000; pt++) {
            long hu = CoordinateConverter.pointsToHwpunits(pt);
            double back = CoordinateConverter.hwpunitsToPoints(hu);
            Assert.assertEquals((double) pt, back, 0.001);
        }
    }
}
