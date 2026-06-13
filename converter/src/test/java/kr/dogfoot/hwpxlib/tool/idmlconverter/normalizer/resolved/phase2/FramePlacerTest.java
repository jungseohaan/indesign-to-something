package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase2;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase1.PageLayoutBuilder;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

public class FramePlacerTest {

    @Test
    public void emptySpreadCrossingTextFrameIsClippedToCurrentPage() {
        ResolvedData data = new ResolvedData();
        data.addPage(page(6, new double[] { 0, 220, 280, 440 }));
        data.addTextFrame(spreadCrossingWhitespaceFrame());
        data.editableTextFrameIds(Collections.singleton("3152"));

        ResolvedBuildContext ctx = new ResolvedBuildContext();
        ctx.resolvedData = data;
        ctx.scaleFactor = 1.0;
        List<ASTSection> sections = PageLayoutBuilder.build(ctx);
        ctx.toSectionIndex = docPageIndex ->
                ctx.pageDocOffsetToSection.getOrDefault(docPageIndex, -1);

        FramePlacer.placeTextFrames(ctx, sections);

        Assert.assertEquals(1, sections.get(0).blocks().size());
        ASTBlock block = sections.get(0).blocks().get(0);
        Assert.assertTrue(block instanceof ASTTextFrameBlock);
        ASTTextFrameBlock tf = (ASTTextFrameBlock) block;
        Assert.assertEquals(0, tf.x());
        Assert.assertEquals(CoordinateConverter.pointsToHwpunits(220), tf.width());
        Assert.assertEquals(0, tf.y());
        Assert.assertEquals(CoordinateConverter.pointsToHwpunits(280), tf.height());
    }

    private static ResolvedPage page(int index, double[] bounds) {
        ResolvedPage page = new ResolvedPage();
        page.index(index);
        page.name(String.valueOf(index + 1));
        page.bounds(bounds);
        return page;
    }

    private static ResolvedTextFrame spreadCrossingWhitespaceFrame() {
        ResolvedTextFrame tf = new ResolvedTextFrame();
        tf.id("3152");
        tf.pageIndex(6);
        tf.storyId("3134");
        tf.paragraphStart(0);
        tf.paragraphEnd(0);
        tf.lineCount(1);
        tf.frameVisibleText(" ");
        tf.geometricBounds(new double[] { -5, -5, 286, 446 });
        tf.pageRelativeBounds(new double[] { -5, -225, 286, 226 });
        tf.strokeColor("C=0 M=33 Y=100 K=0");
        tf.strokeWeight(75.0);
        tf.fillColor("None");
        return tf;
    }
}
