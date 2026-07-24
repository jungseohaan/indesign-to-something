package kr.dogfoot.hwpxlib.tool.idmlconverter.font;

import org.junit.Assert;
import org.junit.Test;

public class FontStyleClassifierTest {
    @Test
    public void recognizesInDesignWeightAbbreviations() {
        Assert.assertTrue(FontStyleClassifier.isBoldStyle("07 Bd"));
        Assert.assertTrue(FontStyleClassifier.isBoldStyle("06 Hv"));
        Assert.assertTrue(FontStyleClassifier.isBoldStyle("Blk"));
        Assert.assertTrue(FontStyleClassifier.isBoldStyle("Sb"));

        Assert.assertFalse(FontStyleClassifier.isBoldStyle("05 Md"));
        Assert.assertFalse(FontStyleClassifier.isBoldStyle("03 Basic Rg"));
    }

    @Test
    public void preservesNumberedWeightRules() {
        Assert.assertTrue(FontStyleClassifier.isBoldStyle("65 Medium"));
        Assert.assertTrue(FontStyleClassifier.isBoldStyle("70"));

        Assert.assertFalse(FontStyleClassifier.isBoldStyle("50 Medium"));
        Assert.assertFalse(FontStyleClassifier.isBoldStyle("40"));
    }

    @Test
    public void recognizesItalicAbbreviations() {
        Assert.assertTrue(FontStyleClassifier.isItalicStyle("Italic"));
        Assert.assertTrue(FontStyleClassifier.isItalicStyle("07 Bd It"));
        Assert.assertTrue(FontStyleClassifier.isItalicStyle("Oblique"));

        Assert.assertFalse(FontStyleClassifier.isItalicStyle("03 Basic Rg"));
    }
}
