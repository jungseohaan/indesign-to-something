package kr.dogfoot.hwpxlib.tool.idmlconverter.resolved;

import org.junit.Assert;
import org.junit.Test;

public class ResolvedDataBlankAnchorSpacerTest {

    @Test
    public void materializesBlankSpacerInlineAnchorAsNbspTextRun() {
        ResolvedData data = new ResolvedData();

        ResolvedPage page = new ResolvedPage();
        page.name("1");
        page.bounds(new double[]{0, 0, 100, 100});
        data.addPage(page);

        ResolvedPageItem spacerRect = new ResolvedPageItem();
        spacerRect.id("16601");
        spacerRect.type("Rectangle");
        spacerRect.geometricBounds(new double[]{10, 20, 13, 28});
        data.addPageItem(spacerRect);

        ResolvedStory story = new ResolvedStory();
        story.id("14701");
        ResolvedParagraph paragraph = new ResolvedParagraph();
        paragraph.addRun(textRun("("));
        ResolvedRun anchor = new ResolvedRun();
        anchor.type("inline_anchor");
        anchor.anchoredObjectId(16601);
        paragraph.addRun(anchor);
        paragraph.addRun(textRun(") No"));
        story.addParagraph(paragraph);
        data.addStory(story);

        Assert.assertEquals(1, data.materializeBlankSpacerAnchorRuns());
        Assert.assertFalse(anchor.isInlineAnchor());
        Assert.assertNull(anchor.anchoredObjectId());
        Assert.assertEquals("\u00A0\u00A0\u00A0", anchor.text());
    }

    @Test
    public void doesNotMaterializeBlankSpacerBetweenEquationFontRuns() {
        ResolvedData data = new ResolvedData();

        ResolvedPage page = new ResolvedPage();
        page.name("1");
        page.bounds(new double[]{0, 0, 100, 100});
        data.addPage(page);

        ResolvedPageItem spacerRect = new ResolvedPageItem();
        spacerRect.id("16601");
        spacerRect.type("Rectangle");
        spacerRect.geometricBounds(new double[]{10, 20, 13, 28});
        data.addPageItem(spacerRect);

        ResolvedStory story = new ResolvedStory();
        story.id("14701");
        ResolvedParagraph paragraph = new ResolvedParagraph();
        ResolvedRun before = textRun("sqrt");
        before.fontFamily("BT수식M");
        paragraph.addRun(before);
        ResolvedRun anchor = new ResolvedRun();
        anchor.type("inline_anchor");
        anchor.anchoredObjectId(16601);
        paragraph.addRun(anchor);
        paragraph.addRun(textRun("2"));
        story.addParagraph(paragraph);
        data.addStory(story);

        Assert.assertEquals(0, data.materializeBlankSpacerAnchorRuns());
        Assert.assertTrue(anchor.isInlineAnchor());
        Assert.assertEquals(Integer.valueOf(16601), anchor.anchoredObjectId());
    }

    private static ResolvedRun textRun(String text) {
        ResolvedRun run = new ResolvedRun();
        run.text(text);
        run.fontFamily("Arial");
        run.fontSize(10.0);
        return run;
    }
}
