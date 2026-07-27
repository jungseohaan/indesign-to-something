package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

import org.junit.Assert;
import org.junit.Test;

public class ShellRoleTest {
    @Test
    public void demotedShellDoesNotReclaimEditableTextFromOwnedTextFrameMetadata() {
        ObjectPlan plan = shellPlan(TextAction.DROP_TEXT);

        Assert.assertFalse(ShellRole.ownsEditableText(plan));
        Assert.assertEquals(ShellRole.CONTENT_SHELL, ShellRole.from(plan));
    }

    @Test
    public void canonicalHwpxTextShellOwnsEditableText() {
        ObjectPlan plan = shellPlan(TextAction.OWNED_BY_HWPX_TEXT);

        Assert.assertTrue(ShellRole.ownsEditableText(plan));
        Assert.assertEquals(ShellRole.TEXT_OWNING_SHELL, ShellRole.from(plan));
    }

    private static ObjectPlan shellPlan(TextAction textAction) {
        return new ObjectPlan(
                100,
                "page_object",
                0,
                textAction,
                VisualAction.PLACE_TEXT_SHELL,
                VisualLayer.CONTAINER_BACKDROP,
                Placement.FLOATING,
                100,
                new int[] { 100, 101 },
                new int[] { 100 },
                new int[0],
                new int[] { 101 },
                new int[0],
                "bundle.test.100",
                Materialization.EXTRACTED_PNG_VECTOR,
                CoordinateSpace.PAGE,
                null,
                10,
                "test",
                "shell.png",
                new double[] { 0, 0, 10, 10 },
                null,
                null,
                -1);
    }
}
