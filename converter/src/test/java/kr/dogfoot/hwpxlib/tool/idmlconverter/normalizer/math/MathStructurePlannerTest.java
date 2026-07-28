package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.math;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import org.junit.Assert;
import org.junit.Test;

public class MathStructurePlannerTest {

    @Test
    public void unitAndBareExponentArePlannedWithoutMutatingSource() {
        ASTParagraph paragraph = new ASTParagraph();
        ASTTextRun text = text("길이는 3cm");
        ASTEquation exponent = new ASTEquation("^{2},", "EH_FONT");
        exponent.textColor("#123456");
        paragraph.addItem(text);
        paragraph.addItem(exponent);

        MathStructurePlan plan = MathStructurePlanner.plan(paragraph);

        Assert.assertEquals(1, plan.replacements().size());
        Assert.assertEquals(MathStructurePlanner.REASON_UNIT_WITH_BARE_EXPONENT,
                plan.replacements().get(0).reason());
        Assert.assertEquals("길이는 3cm", text.text());
        Assert.assertEquals("^{2},", exponent.hwpScript());

        MathPlanConverter.materialize(paragraph, plan);

        Assert.assertEquals(2, paragraph.items().size());
        Assert.assertEquals("길이는 3",
                ((ASTTextRun) paragraph.items().get(0)).text());
        ASTEquation merged = (ASTEquation) paragraph.items().get(1);
        Assert.assertEquals("rm cm^{2},", merged.hwpScript());
        Assert.assertEquals("#123456", merged.textColor());
    }

    @Test
    public void unitWordSuffixIsNotTreatedAsMeasurementUnit() {
        ASTParagraph paragraph = new ASTParagraph();
        paragraph.addItem(text("program"));
        paragraph.addItem(new ASTEquation("^{2}", "EH_FONT"));

        Assert.assertTrue(MathStructurePlanner.plan(paragraph).isEmpty());
    }

    @Test
    public void triangleMarkerJoinsUppercaseLabelFragments() {
        ASTParagraph paragraph = new ASTParagraph();
        paragraph.addItem(text("△"));
        ASTTextRun head = text("A");
        paragraph.addItem(head);
        ASTEquation tail = new ASTEquation("BC", "EH_FONT");
        tail.preferredBaseUnit(900);
        tail.preferredFontFamily("본문");
        tail.textColor("#ABCDEF");
        paragraph.addItem(tail);

        MathStructurePlan plan = MathStructurePlanner.plan(paragraph);

        Assert.assertEquals(1, plan.replacements().size());
        Assert.assertEquals(MathStructurePlanner.REASON_TRIANGLE_VERTEX_LABEL,
                plan.replacements().get(0).reason());
        Assert.assertSame(head, paragraph.items().get(1));
        Assert.assertSame(tail, paragraph.items().get(2));

        MathPlanConverter.materialize(paragraph, plan);

        Assert.assertEquals(2, paragraph.items().size());
        Assert.assertEquals("△", ((ASTTextRun) paragraph.items().get(0)).text());
        ASTEquation merged = (ASTEquation) paragraph.items().get(1);
        Assert.assertEquals("ABC", merged.hwpScript());
        Assert.assertEquals(Integer.valueOf(900), merged.preferredBaseUnit());
        Assert.assertEquals("본문", merged.preferredFontFamily());
        Assert.assertEquals("#ABCDEF", merged.textColor());
    }

    @Test
    public void uppercaseFragmentsWithoutTriangleStructureRemainSeparate() {
        ASTParagraph paragraph = new ASTParagraph();
        paragraph.addItem(text("각 "));
        paragraph.addItem(text("A"));
        paragraph.addItem(new ASTEquation("BC", "EH_FONT"));

        Assert.assertTrue(MathStructurePlanner.plan(paragraph).isEmpty());
    }

    private static ASTTextRun text(String value) {
        ASTTextRun run = new ASTTextRun();
        run.text(value);
        return run;
    }
}
