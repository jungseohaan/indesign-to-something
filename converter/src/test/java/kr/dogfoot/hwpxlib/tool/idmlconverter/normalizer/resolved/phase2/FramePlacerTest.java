package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase2;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.AnchoredTablePlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.CoordinateSpace;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Materialization;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Placement;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.TextAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualLayer;
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

    @Test
    public void anchoredTableOwnerWithTableStylePlanKeepsCarrierBlock() {
        ResolvedData data = new ResolvedData();
        data.addPage(page(0, new double[] { 0, 0, 280, 220 }));
        ResolvedTextFrame tf = tableMarkerFrame();
        data.addTextFrame(tf);
        data.editableTextFrameIds(Collections.singleton("14162"));

        ResolvedBuildContext ctx = new ResolvedBuildContext();
        ctx.resolvedData = data;
        ctx.scaleFactor = 1.0;
        ctx.addOwnershipPlan(tableStylePlan(14162));
        ctx.addAnchoredTablePlan(new AnchoredTablePlan(
                14162,
                "13994",
                3,
                "u36aai4b7d",
                27753,
                "27756",
                "u6c6ci6c82",
                9,
                "test_anchored_table_owner"));
        List<ASTSection> sections = PageLayoutBuilder.build(ctx);
        ctx.toSectionIndex = docPageIndex ->
                ctx.pageDocOffsetToSection.getOrDefault(docPageIndex, -1);

        FramePlacer.placeTextFrames(ctx, sections);

        Assert.assertEquals(1, sections.get(0).blocks().size());
        ASTBlock block = sections.get(0).blocks().get(0);
        Assert.assertTrue(block instanceof ASTTextFrameBlock);
        ASTTextFrameBlock carrier = (ASTTextFrameBlock) block;
        Assert.assertEquals("u3752", carrier.sourceId());
        Assert.assertEquals("13994", carrier.storyId());
        Assert.assertEquals(CoordinateConverter.pointsToHwpunits(57), carrier.x());
        Assert.assertEquals(CoordinateConverter.pointsToHwpunits(40), carrier.y());
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

    private static ResolvedTextFrame tableMarkerFrame() {
        ResolvedTextFrame tf = new ResolvedTextFrame();
        tf.id("14162");
        tf.pageIndex(0);
        tf.storyId("13994");
        tf.paragraphStart(0);
        tf.paragraphEnd(4);
        tf.lineCount(1);
        tf.frameVisibleText("\u0016");
        tf.geometricBounds(new double[] { 40, 57, 128, 197 });
        tf.pageRelativeBounds(new double[] { 40, 57, 128, 197 });
        return tf;
    }

    private static ObjectPlan tableStylePlan(int domId) {
        return new ObjectPlan(
                domId,
                "planner_declared_style_only:pass.table_only_text_frames:TextFrame",
                0,
                TextAction.OWNED_BY_HWPX_TEXT,
                VisualAction.PLACE_TABLE_STYLE,
                VisualLayer.CONTENT_VISUAL,
                Placement.FLOATING,
                null,
                new int[] { domId },
                new int[0],
                new int[] { 19325, 19360 },
                new int[] { domId },
                new int[0],
                "textFrame.tableOnly." + domId,
                Materialization.HWPX_TABLE_STYLE,
                CoordinateSpace.PAGE,
                null,
                0,
                "table_only_text_frame",
                "",
                null,
                null,
                null,
                -1);
    }
}
