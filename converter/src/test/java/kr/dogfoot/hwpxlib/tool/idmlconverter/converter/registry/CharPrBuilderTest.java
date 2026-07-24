package kr.dogfoot.hwpxlib.tool.idmlconverter.converter.registry;

import org.junit.Assert;
import org.junit.Test;

public class CharPrBuilderTest {
    @Test
    public void fontMappingRatioIsTheSingleFinalWidthScale() {
        Assert.assertEquals(86, CharPrBuilder.resolveEffectiveRatio((short) 97, 0.86));
        Assert.assertEquals(86, CharPrBuilder.resolveEffectiveRatio((short) 100, 0.86));
        Assert.assertEquals(90, CharPrBuilder.resolveEffectiveRatio(null, 0.90));
    }

    @Test
    public void sourceHorizontalScaleAppliesWhenFontMappingHasNoRatio() {
        Assert.assertEquals(97, CharPrBuilder.resolveEffectiveRatio((short) 97, 1.0));
        Assert.assertEquals(100, CharPrBuilder.resolveEffectiveRatio(null, 1.0));
    }
}
