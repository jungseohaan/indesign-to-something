package kr.dogfoot.hwpxlib.tool.idmlconverter.resolved;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;
import org.junit.Assert;
import org.junit.Test;

public class ResolvedDataColorTest {

    @Test
    public void resolvesTintedNamedColorWithHashPrefix() {
        ResolvedData data = new ResolvedData();
        data.addColor("#표색_인디핑크미색", "#ffdbf7");

        Assert.assertEquals("#FFE6F9", data.resolveColorHex("Tint/#표색_인디핑크미색 70%25"));
        Assert.assertEquals("#FFE6F9", data.resolveColorHex("#표색_인디핑크미색 70%"));
    }

    @Test
    public void colorResolverDoesNotTreatNamedHashColorAsHex() {
        IDMLDocument doc = new IDMLDocument();
        doc.putColor("Color/#표색_인디핑크미색", "#ffdbf7");
        ColorResolver resolver = new ColorResolver(doc);

        Assert.assertEquals("#ffdbf7", resolver.resolve("#표색_인디핑크미색"));
        Assert.assertEquals("#FFE6F9", resolver.resolve("Tint/#표색_인디핑크미색 70%25"));
    }
}
