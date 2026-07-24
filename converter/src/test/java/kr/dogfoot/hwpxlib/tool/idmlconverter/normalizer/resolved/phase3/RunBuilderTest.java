package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class RunBuilderTest {
    @Test
    public void splitResolvedRunsIgnoresLayoutTabRemovedFromIdmlText() {
        ResolvedBuildContext ctx = new ResolvedBuildContext();
        ctx.resolvedData = new ResolvedData();
        ctx.spec016Counts = new int[3];
        ctx.lastMatchResult = new int[] { -1 };

        IDMLCharacterRun raw = new IDMLCharacterRun();
        raw.fontSize(8.7);
        raw.fontFamily("나눔고딕OTF");
        raw.fontStyle("Bold");
        raw.fillColor("C=79 M=59 Y=0 K=38");

        ResolvedRun label = new ResolvedRun();
        label.text("\t협력적 소통\u2003");
        label.fontFamily("나눔고딕OTF");
        label.fontSize(8.7);
        label.fontStyle("Bold");
        label.fillColor("C=79 M=59 Y=0 K=38");
        label.tracking(0.0);
        label.horizontalScale(110.0);

        ResolvedRun body = new ResolvedRun();
        body.text("모둠 구성원과 함께 사용 설명서를 공유하고 개선점을 이야기해 보자.");
        body.fontFamily("ViMaru OTF");
        body.fontSize(10.0);
        body.fontStyle("Regular");
        body.fillColor("Black");
        body.tracking(0.0);
        body.horizontalScale(110.0);

        ASTParagraph paragraph = new ASTParagraph();
        StoryConverter.StyleContext styleContext =
                new StoryConverter.StyleContext("#000000", null, "ViMaru OTF", 10.0, null, null);

        boolean split = RunBuilder.splitIdmlRunByResolvedRuns(
                ctx,
                raw,
                "협력적 소통\u2003모둠 구성원과 함께 사용 설명서를 공유하고 개선점을 이야기해 보자.",
                Arrays.asList(label, body),
                0,
                paragraph,
                styleContext);

        Assert.assertTrue(split);
        ASTTextRun labelRun = firstTextRunContaining(paragraph.items(), "협력적");
        ASTTextRun bodyRun = firstTextRunContaining(paragraph.items(), "모둠");
        Assert.assertNotNull(labelRun);
        Assert.assertNotNull(bodyRun);
        Assert.assertEquals(Integer.valueOf(870), labelRun.fontSizeHwpunits());
        Assert.assertEquals(Integer.valueOf(1000), bodyRun.fontSizeHwpunits());
        Assert.assertEquals("나눔고딕OTF", labelRun.fontFamily());
        Assert.assertEquals("ViMaru OTF", bodyRun.fontFamily());
        Assert.assertEquals("Bold", labelRun.fontStyle());
        Assert.assertEquals("Regular", bodyRun.fontStyle());
        Assert.assertEquals("#000000", bodyRun.textColor());
    }

    @Test
    public void splitResolvedRunsConvertsTrackingToHwpxLetterSpacing() {
        ResolvedBuildContext ctx = new ResolvedBuildContext();
        ctx.resolvedData = new ResolvedData();
        ctx.spec016Counts = new int[3];
        ctx.lastMatchResult = new int[] { -1 };

        IDMLCharacterRun raw = new IDMLCharacterRun();
        raw.fontSize(10.0);
        raw.fontFamily("Adobe Myungjo Std");
        raw.fontStyle("Regular");
        raw.tracking(-60.0);

        ResolvedRun head = new ResolvedRun();
        head.text("화학 반응이");
        head.fontFamily("Adobe Myungjo Std");
        head.fontSize(10.0);
        head.fontStyle("Regular");
        head.tracking(-60.0);
        head.horizontalScale(100.0);

        ResolvedRun tail = new ResolvedRun();
        tail.text(" 일어날 때");
        tail.fontFamily("Adobe Myungjo Std");
        tail.fontSize(10.0);
        tail.fontStyle("Regular");
        tail.tracking(-60.0);
        tail.horizontalScale(100.0);

        ASTParagraph paragraph = new ASTParagraph();
        StoryConverter.StyleContext styleContext =
                new StoryConverter.StyleContext("#000000", -60.0, "Adobe Myungjo Std", 10.0, null, null);

        boolean split = RunBuilder.splitIdmlRunByResolvedRuns(
                ctx,
                raw,
                "화학 반응이 일어날 때",
                Arrays.asList(head, tail),
                0,
                paragraph,
                styleContext);

        Assert.assertTrue(split);
        ASTTextRun headRun = firstTextRunContaining(paragraph.items(), "화학");
        ASTTextRun tailRun = firstTextRunContaining(paragraph.items(), "일어날");
        Assert.assertNotNull(headRun);
        Assert.assertNotNull(tailRun);
        Assert.assertEquals(Short.valueOf((short) -6), headRun.letterSpacing());
        Assert.assertEquals(Short.valueOf((short) -6), tailRun.letterSpacing());
    }

    private static ASTTextRun firstTextRunContaining(List<ASTInlineItem> items, String text) {
        for (ASTInlineItem item : items) {
            if (!(item instanceof ASTTextRun)) continue;
            ASTTextRun run = (ASTTextRun) item;
            if (run.text() != null && run.text().contains(text)) {
                return run;
            }
        }
        return null;
    }
}
