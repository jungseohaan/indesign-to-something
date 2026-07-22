package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;
import org.junit.Assert;
import org.junit.Test;

public class AnchoredTablePlannerTest {
    @Test
    public void storyAnchoredNestedTableWithoutResolvedOwnerIsNotPlanned() {
        ResolvedBuildContext ctx = contextWithOwnerFrame();
        IDMLStory ownerStory = storyWithText();
        ownerStory.addTable(wrapperTableWithNestedStoryRef("u6ad0"));
        IDMLStory nestedStory = storyWithTable("u6ad0", "u6ad0i6ae6");

        ctx.loadIDMLStory = storyId -> {
            if ("13994".equals(storyId)) return ownerStory;
            if ("u6ad0".equals(storyId)) return nestedStory;
            return null;
        };

        AnchoredTablePlanner.plan(ctx);

        Assert.assertTrue(ctx.anchoredTablePlans().isEmpty());
        Assert.assertTrue(ctx.isAnchoredTableSource("wrapper"));
        Assert.assertTrue(ctx.isAnchoredWrapperTableSource("wrapper"));
        Assert.assertTrue(ctx.isAnchoredNestedTableSource("u6ad0i6ae6"));
    }

    @Test
    public void storyAnchoredNestedTableWithResolvedOwnerIsPlanned() {
        ResolvedBuildContext ctx = contextWithOwnerFrame();
        ResolvedTextFrame nestedFrame = new ResolvedTextFrame();
        nestedFrame.id("27753");
        nestedFrame.storyId("27344");
        nestedFrame.isInline(true);
        nestedFrame.pageIndex(9);
        ctx.resolvedData.addTextFrame(nestedFrame);

        IDMLStory ownerStory = storyWithText();
        ownerStory.addTable(wrapperTableWithNestedStoryRef("u6ad0"));
        IDMLStory nestedStory = storyWithTable("u6ad0", "u6ad0i6ae6");

        ctx.loadIDMLStory = storyId -> {
            if ("13994".equals(storyId)) return ownerStory;
            if ("u6ad0".equals(storyId)) return nestedStory;
            return null;
        };

        AnchoredTablePlanner.plan(ctx);

        Assert.assertEquals(1, ctx.anchoredTablePlans().size());
        AnchoredTablePlan plan = ctx.anchoredTablePlans().get(0);
        Assert.assertEquals(27753, plan.anchoredTextFrameDomId);
        Assert.assertEquals("27344", plan.nestedStoryId);
        Assert.assertEquals("u6ad0i6ae6", plan.nestedTableId);
        Assert.assertTrue(ctx.isAnchoredTableSource("wrapper"));
        Assert.assertTrue(ctx.isAnchoredNestedTableSource("u6ad0i6ae6"));
    }

    private static ResolvedBuildContext contextWithOwnerFrame() {
        ResolvedBuildContext ctx = new ResolvedBuildContext();
        ctx.resolvedData = new ResolvedData();
        ResolvedTextFrame owner = new ResolvedTextFrame();
        owner.id("14162");
        owner.storyId("13994");
        owner.pageIndex(9);
        owner.frameVisibleText("\u0016");
        ctx.resolvedData.addTextFrame(owner);
        return ctx;
    }

    private static IDMLStory storyWithText() {
        IDMLStory story = new IDMLStory();
        story.selfId("13994");
        IDMLParagraph paragraph = new IDMLParagraph();
        IDMLCharacterRun run = new IDMLCharacterRun();
        run.content("본문");
        paragraph.addCharacterRun(run);
        story.addParagraph(paragraph);
        return story;
    }

    private static IDMLStory storyWithTable(String storyId, String tableId) {
        IDMLStory story = new IDMLStory();
        story.selfId(storyId);
        IDMLTable table = new IDMLTable();
        table.selfId(tableId);
        IDMLTableRow row = new IDMLTableRow();
        row.addCell(new IDMLTableCell());
        table.addRow(row);
        story.addTable(table);
        return story;
    }

    private static IDMLTable wrapperTableWithNestedStoryRef(String storyRef) {
        IDMLTable table = new IDMLTable();
        table.selfId("wrapper");
        table.paragraphIndexBefore(2);
        IDMLTableRow row = new IDMLTableRow();
        IDMLTableCell cell = new IDMLTableCell();
        cell.addTextFrameStoryRef(storyRef);
        row.addCell(cell);
        table.addRow(row);
        return table;
    }
}
