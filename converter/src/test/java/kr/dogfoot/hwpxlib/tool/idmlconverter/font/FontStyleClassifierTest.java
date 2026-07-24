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
        // SPEC-067: "Bk" 는 Black 약자 (예: "19 Bk" — 영어 단원명). 낮은 웨이트
        // 숫자에도 Bk 가 Black 신호. IDML 원본 FontStyle 을 쓰면 이 약자로 나온다.
        Assert.assertTrue(FontStyleClassifier.isBoldStyle("19 Bk"));
        Assert.assertTrue(FontStyleClassifier.isBoldStyle("Bk"));

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
