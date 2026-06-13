package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class TextRunSegmenterTest {
    @Test
    public void resolvedRunKeepsStyleBoundaryAndStopsAtParagraphBreak() {
        ResolvedRun code = new ResolvedRun();
        code.text("[12문학01-01]");
        code.charStyle("색자");
        code.fontSize(12.0);
        code.fillColor("#8B3EBB");

        TextRunSegmenter.Result codeResult =
                TextRunSegmenter.fromResolvedRun(code, null, false, false, true);

        Assert.assertEquals(1, codeResult.runs().size());
        Assert.assertFalse(codeResult.stopAfterRun());
        Assert.assertEquals("[12문학01-01]", codeResult.runs().get(0).text());
        Assert.assertEquals("색자", codeResult.runs().get(0).characterStyleRef());
        Assert.assertEquals("#8B3EBB", codeResult.runs().get(0).textColor());

        ResolvedRun body = new ResolvedRun();
        body.text("\t문학이 인간과 세계를 이해한다.\r[12문학01-02]");
        body.fontSize(10.0);
        body.fillColor("#000000");

        TextRunSegmenter.Result bodyResult =
                TextRunSegmenter.fromResolvedRun(body, null, false, false, true);

        Assert.assertEquals(1, bodyResult.runs().size());
        Assert.assertTrue(bodyResult.stopAfterRun());
        Assert.assertEquals(" 문학이 인간과 세계를 이해한다.", bodyResult.runs().get(0).text());
        Assert.assertFalse(bodyResult.runs().get(0).text().contains("[12문학01-02]"));
    }

    @Test
    public void idmlRunDropsObjectReplacementBeforeCreatingVisibleRuns() {
        IDMLCharacterRun run = new IDMLCharacterRun();
        run.content("가\uFFFC나");

        List<ASTTextRun> runs =
                TextRunSegmenter.fromIdmlRun(run, null, null, null, false, false);

        Assert.assertEquals(2, runs.size());
        Assert.assertEquals("가", runs.get(0).text());
        Assert.assertEquals("나", runs.get(1).text());
    }

    @Test
    public void syntheticTextUsesSharedSegmentationAndExplicitStyle() {
        TextStyleApplicator.ExplicitStyle style = new TextStyleApplicator.ExplicitStyle();
        style.fontFamily = "TestFont";
        style.fontSizePt = 9.0;
        style.textColorHex = "#FFFFFF";

        List<ASTTextRun> runs =
                TextRunSegmenter.fromSyntheticText("가\uFFFC나", style, false);

        Assert.assertEquals(2, runs.size());
        Assert.assertEquals("가", runs.get(0).text());
        Assert.assertEquals("나", runs.get(1).text());
        Assert.assertEquals("TestFont", runs.get(0).fontFamily());
        Assert.assertEquals("#FFFFFF", runs.get(0).textColor());
    }
}
